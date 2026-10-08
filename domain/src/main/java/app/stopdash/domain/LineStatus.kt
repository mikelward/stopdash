package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.LocalDate

/**
 * A line's current TfL status, reduced to what the surfaces need: whether the line is
 * disrupted and a short description to show (SPEC *Disruptions* / D3). TfL reports one or
 * more statuses per line; this carries the worst of them. A line is disrupted whenever any
 * status is not a good service ([GOOD_SERVICE]), so a delayed or suspended line is marked
 * rather than have its countdowns shown as if they could be trusted (SPEC principle 1 —
 * never show a departure stopdash doesn't stand behind).
 *
 * [description] is the short chip label: TfL's own wording for the shown status ("Severe
 * Delays", "Suspended", "Good Service") where that already names the disruption, else a
 * concise label recovered from TfL's free-text reason when the wording is only a vague
 * "Special Service" ([resolveDisruption]).
 *
 * [fullText] is TfL's free-text reason for the shown disruption — the prose behind the chip
 * ("Victoria line: Severe delays while we fix a signal failure…"), for the tap-to-open route
 * detail (SPEC *Disruptions*): the compact card shows only [description], the detail shows
 * this. Null when there is no prose to show — a good service, or a disruption TfL worded but
 * gave no reason for — so the detail then has nothing to expand beyond the label.
 *
 * [byDirection] is the same reduction made once per direction of travel (TfL's `inbound` /
 * `outbound`), for a line whose alerts TfL scopes to one direction: a diversion on the way into
 * town says nothing about the buses heading out, so a row going the other way shouldn't carry it
 * ([forDirection]). An alert whose direction isn't known counts for both. Empty when the split
 * changes nothing — no directional alerts, or none looked up yet — so the line-wide status holds.
 *
 * [awaitingDirections] is true while a lookup of some alert's direction is under way: the status
 * is complete for now but will split once it lands, so a caller that reuses a recent status
 * asks again rather than keeping this one for its whole reuse window.
 *
 * [planned] is the line's work that hasn't started yet ([AlertStart]): kept apart from the
 * disruption, so a closure next month doesn't flag today's buses, but still there for a row to note
 * and its page to spell out. A line with only planned work is a good service with [planned] set.
 *
 * [closures] are **every** part closure or part suspension under way ([PART_SEVERITIES]) that TfL
 * words itself ([PartClosure]), not only the one shown: two can be under way at once on different
 * stretches, and the one shown is whichever TfL ranks worse, which can be a milder status TfL numbers
 * lower (Codex, PR #446). Each is kept whole, its wording with the stretches TfL places it on, so one
 * placed on a ride can be named for what it is ([closureOn]), and one behind a milder alert is still
 * a closure under way. A label read from a catch-all's reason (a diversion,
 * [ResolvedDisruption.inferred]) is none.
 */
data class LineStatus(
    val lineId: String,
    val severity: Int,
    val description: String,
    val fullText: String? = null,
    val byDirection: Map<String, LineStatus> = emptyMap(),
    val awaitingDirections: Boolean = false,
    val planned: List<PlannedAlert> = emptyList(),
    // The shown disruption resolved only to the generic fallback label ([ResolvedDisruption.isFallback]):
    // kept so a status re-ranked later ([asOf]) orders it as a fresh parse would (Codex, PR #337).
    val isFallback: Boolean = false,
    val closures: List<PartClosure> = emptyList(),
    // Whether [description] and [fullText] are the line's only alert under way, as TfL answered: when
    // several are, only the worst's words are kept, so its words don't speak for the rest
    // ([RouteDisruption.offRide]; Codex, PR #455). False where not known.
    val soleAlert: Boolean = false,
    // Where this alert lies wholly behind the stop for a row going that way, as the app found on the
    // line's routes ([AlertBehind]): the widget and the watch, which have no routes, move it off such
    // a row as the app does ([DepartureRows.withAlertsBehind]). Empty in the app, which places alerts
    // itself, and wherever nothing was placed.
    val behindAt: Set<StopWay> = emptySet(),
    // Every alert under way on the line, each in its own words, as TfL answered (the one shown among
    // them): what lets a trip leave out a bus line's alerts that each name only stops off the ride
    // though several are under way ([RouteDisruption.offRide], maintainer 2026-10-03). Empty where not
    // known, or nothing is under way.
    val underWay: List<LineAlert> = emptyList(),
) {

    /** True when TfL reports anything other than a good service on this line. */
    val disrupted: Boolean get() = severity != GOOD_SERVICE

    /**
     * The status a row travelling in [direction] should show: that direction's own when TfL scoped
     * the line's alerts by direction ([byDirection]), else the line-wide one. A row with no TfL
     * direction (most rail predictions) keeps the line-wide status, so nothing is hidden from it.
     */
    fun forDirection(direction: String): LineStatus = byDirection[direction] ?: this

    /**
     * This status with [ahead] (TfL's work for the coming days, [planned]'s kind) added to its work to come,
     * soonest first: an alert under way in the same words isn't added, nor one already listed, as TfL gives
     * the same alert in both answers once it's posted. A listed alert is matched by its words, as the two
     * answers can date one alert differently (its posting here, its period there), and accounts for one of
     * [ahead]'s alerts in those words, the soonest, so a later one in the same words is still added. Among
     * [ahead], alerts are told apart by their whole identity ([plannedAlertFingerprint]): two closures in the
     * same words on different days stay two, and one TfL gave no words for stays (Codex, #697). A listed alert
     * with no stretch placed (no closure, or one with no sections) takes the stretch its match in [ahead] carries ([PlannedAlert.closure]), keeping
     * its own identity, so the line's map can mark it (Codex, #707). Walks both: on a worker only.
     */
    @WorkerThread
    fun withWorkAhead(ahead: List<PlannedAlert>): LineStatus {
        if (ahead.isEmpty()) return this
        val underWayWords = HashSet<String>()
        fullText?.let(underWayWords::add)
        underWay.mapNotNullTo(underWayWords) { it.fullText }
        underWayWords.remove("")
        // How many of [planned] are still to be matched, by their words; and each one's whole identity.
        val unmatched = HashMap<String, Int>()
        planned.forEach { if (it.fullText.isNotEmpty()) unmatched.merge(it.fullText, 1, Int::plus) }
        val listed = planned.mapTo(HashSet()) { plannedAlertFingerprint(it) }
        val seen = HashSet<String>()
        val added = ArrayList<PlannedAlert>()
        // [planned] with the stretches matched alerts in [ahead] place, where it placed none.
        val placed = planned.toMutableList()
        fun place(alert: PlannedAlert, identity: String) {
            val stretch = alert.closure ?: return
            val at = placed.indices.firstOrNull { i ->
                placed[i].closure?.sections.isNullOrEmpty() && (plannedAlertFingerprint(placed[i]) == identity || placed[i].fullText == alert.fullText)
            } ?: return
            placed[at] = placed[at].copy(closure = stretch)
        }
        for (alert in ahead.sortedBy { it.startsOn }) {
            val identity = plannedAlertFingerprint(alert)
            if (!seen.add(identity) || alert.fullText in underWayWords) continue
            val left = unmatched[alert.fullText] ?: 0
            if (identity in listed || left > 0) {
                if (left > 0) unmatched[alert.fullText] = left - 1
                place(alert, identity)
                continue
            }
            added += alert
        }
        val enriched = placed.indices.any { placed[it] !== planned[it] }
        if (added.isEmpty() && !enriched) return this
        return copy(planned = (placed + added).sortedBy { it.startsOn })
    }

    /** True when there is anything to show for the line: a disruption now, or work to come. */
    val hasAlerts: Boolean get() = disrupted || planned.isNotEmpty()

    /** This status and each per-direction one: every alert a row could show for the line. */
    val allStatuses: List<LineStatus> get() = listOf(this) + byDirection.values

    /**
     * The worst of [closures] TfL places on a ride calling at [calls] ([PartClosure.coversRide]), or
     * null when none is, or none is known.
     */
    fun closureOn(calls: List<String>): PartClosure? =
        closures.filter { it.coversRide(calls) }.minByOrNull { it.severity }

    /** Whether any of [closures] is placed on a ride calling at [calls] ([closureOn]). */
    fun coversRide(calls: List<String>): Boolean = closureOn(calls) != null

    /**
     * This status naming [closure] instead of the alert shown: a closure placed on a ride is what the
     * rider is told of there, whatever TfL ranks above it.
     */
    fun naming(closure: PartClosure): LineStatus =
        copy(severity = closure.severity, description = closure.description, fullText = closure.fullText, isFallback = false, byDirection = emptyMap(), behindAt = emptySet())

    /**
     * This status as of [today] in London: planned work whose day has come counts as under way,
     * the worst of it and any disruption already shown making the chip, and leaves [planned]. A
     * status is classified when it is fetched, but a kept one (a later check failed, or is reused)
     * outlives that day, and work already started must not stay muted as to come (Codex, PR #337).
     */
    @WorkerThread
    fun asOf(today: LocalDate): LineStatus {
        val due = planned.filter { !it.startsOn.isAfter(today) }
        val split = byDirection.mapValues { (_, status) -> status.asOf(today) }
        if (due.isEmpty()) return if (split == byDirection) this else copy(byDirection = split)
        val now = due.map { ResolvedDisruption(it.label, it.severity, isFallback = it.isFallback, fullText = it.fullText) } +
            listOfNotNull(if (disrupted) ResolvedDisruption(description, severity, isFallback = isFallback, fullText = fullText.orEmpty()) else null)
        val worst = mostSevereDisruption(now) ?: return this
        val sole = (soleAlert || !disrupted) && now.map { Triple(it.severity, it.label, it.fullText) }.distinct().size == 1
        // Work that has started joins what's under way, only where all of that is known: added to a list
        // that was never kept, it would pass for the whole of it.
        val started = due.map { LineAlert(it.severity, it.label, it.fullText.ifBlank { null }) }
        val all = if (!disrupted || underWay.isNotEmpty()) (underWay + started).distinct() else emptyList()
        return copy(
            severity = worst.severity,
            description = worst.label,
            isFallback = worst.isFallback,
            fullText = worst.fullText.ifBlank { null },
            // [closures] kept: they are still under way, whichever alert now shows. A started part
            // closure joins them, placed by the sections it was planned with.
            closures = (closures + due.mapNotNull { it.closure }).distinct(),
            byDirection = split,
            planned = planned - due.toSet(),
            // The one alert under way only while nothing else is: work that has started is another
            // (Codex, PR #455).
            soleAlert = sole,
            underWay = all,
            // Work that has started is an alert the app placed on its own words, not the one shown
            // before: placed as it was ([PlannedAlert.behindAt]) where it's all that's under way and
            // nothing was before it, else nowhere.
            behindAt = if (sole && !disrupted && due.map(::plannedShownFingerprint).distinct().size == 1) {
                due.map { it.behindAt }.reduce { a, b -> a intersect b }
            } else {
                emptySet()
            },
        )
    }

    companion object {
        /** TfL's `statusSeverity` for a normal, undisrupted line. */
        const val GOOD_SERVICE = 10

        /**
         * TfL `statusSeverity` values for a line shut over part of its length (part suspended, part
         * closure, part closed): the ones whose stops TfL names place them ([closures]).
         */
        val PART_SEVERITIES: Set<Int> = setOf(3, 5, 11)

        /** [statuses] each as of [now]'s day in London ([asOf]). */
        fun asOf(statuses: Map<String, LineStatus>, now: java.time.Instant): Map<String, LineStatus> {
            val today = now.atZone(AlertStart.ZONE).toLocalDate()
            if (statuses.values.none { it.hasDue(today) }) return statuses
            return statuses.mapValues { (_, status) -> status.asOf(today) }
        }

        /**
         * [statuses] as a trip's rides travel them (Codex, PR #337): [rides] gives, for each line, the
         * trains seen along each of its rides, and a line takes its status for the one direction
         * they all go ([forDirection]), as a list row of those trains shows it, so a card riding the
         * unaffected way doesn't warn of the other way's alert. A line with a ride whose trains
         * aren't seen yet, or are seen going both ways, keeps its line-wide status, which hides
         * nothing.
         */
        fun alongRides(statuses: Map<String, LineStatus>, rides: Map<String, List<List<Departure>>>): Map<String, LineStatus> {
            if (statuses.values.none { it.byDirection.isNotEmpty() }) return statuses
            return statuses.mapValues { (line, status) ->
                // Each ride's one direction, or null where it can't be told; then the line's, if one.
                val direction = rides[line].orEmpty()
                    .map { trains -> trains.map { it.direction }.filter { it.isNotBlank() }.toSet().singleOrNull() }
                    .distinct().singleOrNull()
                direction?.let(status::forDirection) ?: status
            }
        }
    }

    private fun hasDue(today: LocalDate): Boolean =
        planned.any { !it.startsOn.isAfter(today) } || byDirection.values.any { it.hasDue(today) }
}

/**
 * A part closure or part suspension under way ([LineStatus.PART_SEVERITIES]): its [severity],
 * [description] and [fullText] as an alert shows them, and the [sections] TfL names it as shutting.
 * They come from the same detail lookup as an alert's direction: each section's stops in the order its
 * route runs them, its ends included, a section apart from another kept apart. Empty while not looked
 * up, or where TfL gave nothing to order the stops by, so they say only where a closure is, never where
 * one isn't.
 */
data class PartClosure(
    val severity: Int,
    val description: String,
    val fullText: String?,
    val sections: List<List<String>>,
) {
    /**
     * The stops strictly inside one of its [sections] (not at either edge, where trains still run up
     * to it), by where each first appears. Worked out once, as the closure is built off the main
     * thread, so asking whether it shuts a stop ([EmptyTimes.notRunningAt]) is a lookup, not a scan.
     */
    val interior: Set<String> = buildSet {
        for (section in sections) {
            val seen = HashSet<String>()
            section.forEachIndexed { i, stop -> if (seen.add(stop) && i > 0 && i < section.lastIndex) add(stop) }
        }
    }

    /**
     * Whether it's placed on a ride calling at [calls] in order (where it boards, then each stop
     * through where it gets off): two calls in a row both in one of its [sections], the same way
     * round, so the ride runs through a section it names in the direction it's shut. A ride that only
     * starts or ends at a section's edge isn't: trains still run up to it. Nor is one running between
     * two sections, or through one the other way. A section on a loop can name a stop twice: the
     * stretch is there if any of its calls at the second stop follows any at the first (Codex, PR #446).
     */
    @WorkerThread
    fun coversRide(calls: List<String>): Boolean =
        calls.zipWithNext().any { (from, to) ->
            sections.any { section -> section.indexOf(from).let { at -> at >= 0 && section.lastIndexOf(to) > at } }
        }
}

/**
 * A line alert for work that hasn't started ([AlertStart]): its chip [label] ("Diversion"), TfL's
 * [fullText], the day it [startsOn] in London, and its [severity] on TfL's scale and [isFallback], for
 * ranking it when that day comes ([LineStatus.asOf]).
 *
 * [fingerprint] is its full dismissal identity ([plannedAlertFingerprint]) where [fullText] isn't
 * carried: a check the widget stores drops TfL's prose, as it does a status's, so the identity a
 * dismissal is matched against is kept instead. Null for one read from TfL, whose identity is
 * worked out from the alert itself.
 *
 * [closure] is the part closure or suspension it becomes when it starts ([LineStatus.closures]),
 * with the sections TfL places it on, so a status kept across that day places it at once rather than
 * after its next check (Codex, PR #446). Null for any other alert, and where the widget stores it.
 */
data class PlannedAlert(
    val label: String,
    val fullText: String,
    val startsOn: LocalDate,
    val severity: Int = PART_CLOSURE,
    // Resolved only to the generic fallback label ([ResolvedDisruption.isFallback]): ranks last
    // when it starts, as it would in a fresh parse (Codex, PR #337).
    val isFallback: Boolean = false,
    val fingerprint: String? = null,
    val closure: PartClosure? = null,
    // The line's status's full identity once this is the alert it shows ([plannedShownFingerprint]),
    // kept, like [fingerprint], where the prose is left out.
    val shownFingerprint: String? = null,
    // Where this alert, once its day comes, lies wholly behind the stop for a row going that way, as
    // the app found on its words ([LineStatus.behindAt]); [LineStatus.asOf] takes it as the status's
    // where nothing else is under way. Set only where a stored snapshot is read.
    val behindAt: Set<StopWay> = emptySet(),
) {
    companion object {
        /** TfL's severity for a part closure: planned work's usual grade. */
        const val PART_CLOSURE = 5
    }
}

/**
 * One alert under way on a line, in its own words ([LineStatus.underWay]), as [LineStatus] words the one
 * shown, with the [directions] (TfL's `inbound`/`outbound`) it was found to affect; null where not known.
 */
data class LineAlert(val severity: Int, val description: String, val fullText: String?, val directions: Set<String>? = null)
