package app.stopdash.domain

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
) {
    /** True when TfL reports anything other than a good service on this line. */
    val disrupted: Boolean get() = severity != GOOD_SERVICE

    /**
     * The status a row travelling in [direction] should show: that direction's own when TfL scoped
     * the line's alerts by direction ([byDirection]), else the line-wide one. A row with no TfL
     * direction (most rail predictions) keeps the line-wide status, so nothing is hidden from it.
     */
    fun forDirection(direction: String): LineStatus = byDirection[direction] ?: this

    /** True when there is anything to show for the line: a disruption now, or work to come. */
    val hasAlerts: Boolean get() = disrupted || planned.isNotEmpty()

    /** This status and each per-direction one: every alert a row could show for the line. */
    val allStatuses: List<LineStatus> get() = listOf(this) + byDirection.values

    /**
     * This status as of [today] in London: planned work whose day has come counts as under way,
     * the worst of it and any disruption already shown making the chip, and leaves [planned]. A
     * status is classified when it is fetched, but a kept one (a later check failed, or is reused)
     * outlives that day, and work already started must not stay muted as to come (Codex, PR #337).
     */
    fun asOf(today: LocalDate): LineStatus {
        val due = planned.filter { !it.startsOn.isAfter(today) }
        val split = byDirection.mapValues { (_, status) -> status.asOf(today) }
        if (due.isEmpty()) return if (split == byDirection) this else copy(byDirection = split)
        val now = due.map { ResolvedDisruption(it.label, it.severity, isFallback = it.isFallback, fullText = it.fullText) } +
            listOfNotNull(if (disrupted) ResolvedDisruption(description, severity, isFallback = isFallback, fullText = fullText.orEmpty()) else null)
        val worst = mostSevereDisruption(now) ?: return this
        return copy(
            severity = worst.severity,
            description = worst.label,
            isFallback = worst.isFallback,
            fullText = worst.fullText.ifBlank { null },
            byDirection = split,
            planned = planned - due.toSet(),
        )
    }

    companion object {
        /** TfL's `statusSeverity` for a normal, undisrupted line. */
        const val GOOD_SERVICE = 10

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
 * A line alert for work that hasn't started ([AlertStart]): its chip [label] ("Diversion"), TfL's
 * [fullText], the day it [startsOn] in London, and its [severity] on TfL's scale and [isFallback], for
 * ranking it when that day comes ([LineStatus.asOf]).
 */
data class PlannedAlert(
    val label: String,
    val fullText: String,
    val startsOn: LocalDate,
    val severity: Int = PART_CLOSURE,
    // Resolved only to the generic fallback label ([ResolvedDisruption.isFallback]): ranks last
    // when it starts, as it would in a fresh parse (Codex, PR #337).
    val isFallback: Boolean = false,
) {
    companion object {
        /** TfL's severity for a part closure: planned work's usual grade. */
        const val PART_CLOSURE = 5
    }
}
