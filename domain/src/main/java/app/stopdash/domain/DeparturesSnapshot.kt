package app.stopdash.domain

import java.time.Instant
import java.time.LocalDate
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The last-good departures snapshot, persisted between sessions and read back both by the
 * app (as the stamped placeholder shown before the first refresh completes) and by the
 * lock-screen widget (which can't run the fetch itself). It is the honest last-good only:
 * the [stops], each carrying its own fetch age, and the freshest [fetchedAt] for the
 * whole-screen stamp. Fetch and line check stamps are the steady clock's ([SteadyClock]), so
 * setting the device's clock doesn't change how old either reads.
 *
 * [lineStatuses] is each shown line's last status check, **stamped with when it was checked**,
 * so the widget (and the watch, which renders from the same inputs) can mark a delayed or
 * suspended service rather than show it as a normal countdown (SPEC D3). This reverses the
 * earlier rule that no line status was persisted: an unstamped status would assert a
 * disruption we can no longer stand behind, but a stamped one is withheld at the same shared
 * staleness threshold as a countdown ([liveLineStatuses], SPEC D4), so it is never shown past
 * the age the rest of the snapshot is trusted to. Good-service verdicts are kept too, so a
 * newer "good service" can replace an older disruption when two writers' snapshots merge
 * ([LineStatusCheck.newest]).
 *
 * The other transient refresh-cycle flags — partial refresh, refresh failure, "status
 * unknown" — are **not** part of the persisted snapshot, and nor are stop closures: a
 * point-in-time closure has no age-stamped rendering, and aging one would assert a
 * disruption state we can no longer stand behind (the same reason [Snapshot.mergeStop] never
 * ages a disruption). On restore the stops are shown at their real age — a stale stop's
 * countdowns are withheld — and the immediate refresh re-derives everything else. The
 * restored snapshot is, in effect, the `prior` the first refresh merges into, so a stop that
 * then fails to refresh keeps its aged rows.
 */
data class DeparturesSnapshot(
    val stops: List<StopArrivals>,
    val fetchedAt: Instant,
    // The starred journeys the widget pins to its top (SPEC *Journeys*): each origin's departures
    // that call at the far end, as the app last worked them out from the route data it has.
    val journeys: List<WidgetJourney> = emptyList(),
    // Stops kept only as a journey's origin (not nearby): the widget shows just their journey
    // departures, never their other rows.
    val journeyOnlyStopIds: Set<String> = emptySet(),
    // Stops the widget should show that the last refresh asked for but couldn't get, with no
    // earlier arrivals to fall back on, so they're absent from [stops]. Without this an initial
    // refresh where one stop failed would look complete: every stop present is fresh.
    val missingStopIds: Set<String> = emptySet(),
    // Each line's last status check (good or disrupted), by line id, for the lines the stops show.
    // Empty when none was checked, which renders as before: no line marked.
    val lineStatuses: Map<String, LineStatusCheck> = emptyMap(),
    // The nearby stops, nearest the rider first, as the app last measured them, so the widget can
    // show a line once, from its nearest stop, as the in-app list does ([DepartureRows.glanceFolded]).
    // An order, never the distances: several stops' distances would pin down where the rider was
    // (SPEC *Privacy*). Only the app knows where the rider is, so only its saves set this; a
    // journey-only stop isn't in it. Empty when unknown, which shows every stop's rows.
    val nearestFirst: List<String> = emptyList(),
) {
    /**
     * The alerts to mark at [now]: the lines disrupted, or with work still to come
     * ([LineStatus.planned]), whose check is still within the shared staleness threshold
     * ([Staleness]), less the ones the user dismissed ([LineStatusCheck.shown]). An older one is
     * withheld, as an old countdown is (D4), rather than claim a line is still disrupted (or, by its
     * absence, clear) on a check that old.
     */
    fun liveLineStatuses(now: Instant): Map<String, LineStatus> {
        val today = now.atZone(AlertStart.ZONE).toLocalDate()
        return lineStatuses.values
            .filter { it.isLive(now) }
            .mapNotNull { it.shown(today) }
            .associateBy { it.lineId }
    }

    /**
     * Whether [lineId]'s status is known at [now]: it has a live check, good or disrupted. A line
     * with none (never checked, the last lookup failed, or the check aged out) is unknown, and a
     * surface says so rather than let its countdowns read as verified-clean, as the app does.
     */
    fun statusKnown(lineId: String, now: Instant): Boolean =
        lineId.isNotBlank() && lineStatuses[lineId]?.let { it.known && it.isLive(now) } == true

    /**
     * This snapshot as a glance surface shows it, given the user's [dismissals] in the app: each
     * check they hide ([Dismissals.hide]) is marked [LineStatusCheck.dismissed], and every other
     * check unmarked. Applied where the snapshot is read (the widget's draw, the watch's publish),
     * never stored, so the dismissed set is the one place a dismissal lives and nothing can keep a
     * stale copy of it.
     */
    fun withDismissals(dismissals: Dismissals): DeparturesSnapshot {
        val marked = lineStatuses.mapValues { (_, check) ->
            val isDismissed = dismissals.hide(check)
            val directions = dismissals.hiddenDirections(check)
            val planned = dismissals.hiddenPlanned(check)
            if (isDismissed == check.dismissed && directions == check.dismissedDirections && planned == check.dismissedPlanned) check
            else check.copy(dismissed = isDismissed, dismissedDirections = directions, dismissedPlanned = planned)
        }
        return if (marked == lineStatuses) this else copy(lineStatuses = marked)
    }

    /**
     * This snapshot as it stands for the nearby stops the app last found around the rider, [nearby]:
     * a stop it holds that isn't among them is from somewhere the rider has left, so it goes (or
     * keeps only its journey's departures, if a pinned journey starts there), and one of them it
     * doesn't hold yet is missing, so the rest isn't taken for the whole picture. Without this, a
     * move whose first fetch failed left the last place's trains on the widget, read as live until
     * they aged (Codex P1 on #44). Decided where the snapshot is read rather than by clearing the
     * stored file, which raced the app's own writes (PR #53).
     */
    fun scopedTo(nearby: Set<String>): DeparturesSnapshot {
        val origins = journeys.mapTo(HashSet()) { it.originId }
        val kept = stops.filter { it.stopId in nearby || it.stopId in journeyOnlyStopIds || it.stopId in origins }
        val keptIds = kept.mapTo(HashSet()) { it.stopId }
        // Kept but not near is journey-only; a journey-only origin the rider is now near is nearby again.
        val journeyOnly = keptIds - nearby
        val missing = nearby - (keptIds - journeyOnly)
        if (kept.size == stops.size && journeyOnly == journeyOnlyStopIds && missing == missingStopIds) return this
        return copy(
            stops = kept,
            fetchedAt = kept.maxOfOrNull { it.fetchedAt } ?: fetchedAt,
            journeyOnlyStopIds = journeyOnly,
            missingStopIds = missing,
        )
    }

    /** [withDismissals] for [dismissed] active dismissals, none ended. */
    fun withDismissals(dismissed: Set<DismissedAlert>): DeparturesSnapshot = withDismissals(Dismissals(dismissed))

    /**
     * This snapshot as a glance surface shows it, given the app's [verdicts] that a bus alert lies
     * wholly behind a stop ([LineStatusCheck.withAlertsBehind]): applied where the snapshot is read,
     * as [withDismissals] is, never stored with it, so a check the widget's own refresh makes takes
     * the verdicts too. Only those at the stops it carries: a verdict says something about the stop it
     * names and nothing else, and the watch is sent no stop beyond the ones it shows (Codex, PR #471).
     */
    fun withAlertsBehind(verdicts: Set<AlertBehind>): DeparturesSnapshot {
        val carried = stops.mapTo(HashSet()) { it.stopId }
        val byLine = verdicts.filter { it.stopId in carried }.groupBy { it.lineId }
        val placed = lineStatuses.mapValues { (line, check) -> check.withAlertsBehind(byLine[line].orEmpty()) }
        return if (placed == lineStatuses) this else copy(lineStatuses = placed)
    }

    /**
     * The next instant after [now] at which what this snapshot shows changes on its own: its
     * staleness boundary, or a line check's expiry (a disruption's mark goes, or a line becomes
     * unchecked). Null when none is left. A static surface schedules its redraw here, so a mark
     * is withheld when its check expires, not only when the arrivals do.
     */
    fun nextBoundary(now: Instant): Instant? {
        val threshold = Staleness.THRESHOLD.toJavaDuration()
        // Fetch and check stamps are the steady clock's ([SteadyClock]); the boundary is scheduled by
        // the wall clock.
        val arrivalsExpire = SteadyClock.toWall(fetchedAt).plus(threshold)
        // Only an expiry that can change what's drawn: a check from the future (the clock moved
        // back) is never live, and a no-verdict one reads as unchecked from the start. A good
        // service has no mark, so its expiry matters only while a countdown on its line is still
        // fresh (the line turns unchecked under it): before the boundary of the freshest stop with
        // a departure on it. A line a stop only declares has no countdown to turn unchecked, so
        // its good check's expiry changes nothing; past the boundary, neither does any.
        fun lineFreshUntil(lineId: String): Instant? = stops
            .filter { stop -> stop.departures.any { it.lineId == lineId } }
            .maxOfOrNull { it.fetchedAt }
            ?.let(SteadyClock::toWall)
            ?.plus(threshold)
        val checkExpiries = lineStatuses.entries
            .filter { (_, check) -> check.known && !check.fromFuture(now) }
            .mapNotNull { (lineId, check) ->
                val expiry = SteadyClock.toWall(check.checkedAt).plus(threshold)
                val matters = check.shown(now.atZone(AlertStart.ZONE).toLocalDate()) != null ||
                    lineFreshUntil(lineId)?.let { expiry.isBefore(it) } == true
                expiry.takeIf { matters }
            }
        // Work starting while its check is still live turns its calendar into the ⚠ at that
        // midnight, dismissed or not ([LineStatusCheck.shown]).
        val plannedStarts = lineStatuses.values
            .filter { check -> check.known && !check.fromFuture(now) }
            .flatMap { check ->
                val expiry = SteadyClock.toWall(check.checkedAt).plus(threshold)
                check.plannedStarts.filter { it.isBefore(expiry) }
            }
        return (listOf(arrivalsExpire) + checkExpiries + plannedStarts)
            .filter { it.isAfter(now) }
            .minOrNull()
    }
}

/**
 * A line's [status] as TfL gave it at [checkedAt]: the age stamp that lets a persisted status be
 * withheld once it's as old as a stale countdown (SPEC D4). Stamped and aged by the steady clock, as
 * a fetch is ([SteadyClock]), so setting the device's clock doesn't make an old check read as new;
 * the watch, which keeps no steady frame, gets it as the wall clock read it when it was sent.
 */
data class LineStatusCheck(
    val status: LineStatus,
    val checkedAt: Instant,
    // False when the line was asked about at [checkedAt] and TfL gave no status for it: a check
    // with no verdict. It stands in the merges like any other check, so a newer "no verdict"
    // replaces an older disruption (an absent entry couldn't: it reads as "nothing new"), and the
    // line reads as unchecked. Its [status] is a placeholder that is never disrupted.
    val known: Boolean = true,
    // True when the user dismissed this status in the app (SPEC *Disruptions*): the line still
    // counts as checked, so its countdowns read as vouched for, but its mark isn't shown. Only
    // ever set by [DeparturesSnapshot.withDismissals] where the snapshot is read, never stored on
    // the phone; the watch receives it already applied. A changed status (a new severity or
    // wording) is a new alert and isn't marked.
    val dismissed: Boolean = false,
    // [status]'s full dismissal identity ([lineAlertFingerprint]), kept apart from [status] because
    // a stored check drops TfL's full reason: a reworded alert differs here even when its severity
    // and label don't, so a dismissal never hides it.
    val fingerprint: String = lineAlertFingerprint(status),
    // The directions ([LineStatus.byDirection]) whose own status the user dismissed, set where
    // [dismissed] is and for the same reason: a row going that way shows no mark, while a row
    // going the other way, or with no direction, still shows its own status.
    val dismissedDirections: Set<String> = emptySet(),
    // Each direction's status's full dismissal identity, as [fingerprint] is the line-wide one's.
    val directionFingerprints: Map<String, String> = status.byDirection.mapValues { (_, it) -> lineAlertFingerprint(it) },
    // The planned alerts ([LineStatus.planned], the line's or a direction's) the user dismissed, by
    // [plannedAlertFingerprint], set where [dismissed] is and for the same reason: each is its own
    // alert, put away on its own, and only while it's still to come ([shown]).
    val dismissedPlanned: Set<String> = emptySet(),
) {
    /**
     * This check with [verdicts] placing its alerts ([LineStatus.behindAt]): each status, the line's
     * and each direction's, takes the stops and ways its own alert, by its full words ([fingerprint],
     * [directionFingerprints]), was found behind. Only while it's the line's sole alert, as when the
     * verdict was reached ([RouteDisruption.scopable]): with another under way since, the one shown
     * no longer speaks for the rest.
     */
    fun withAlertsBehind(verdicts: Collection<AlertBehind>): LineStatusCheck {
        fun matching(lineId: String, fingerprint: String): Set<StopWay> =
            verdicts.filter { it.lineId == lineId && it.fingerprint == fingerprint }.mapTo(HashSet()) { it.way }
        fun ways(status: LineStatus, fingerprint: String): Set<StopWay> =
            if (!known || !status.soleAlert) emptySet() else matching(status.lineId, fingerprint)
        // Its work to come, each on the words it shows once its day comes, for [LineStatus.asOf] to take
        // where it's then the only alert.
        fun planned(status: LineStatus): List<PlannedAlert> =
            if (!known) status.planned
            else status.planned.map { it.copy(behindAt = matching(status.lineId, plannedShownFingerprint(it))) }
        val placed = status.copy(
            behindAt = ways(status, fingerprint),
            planned = planned(status),
            byDirection = status.byDirection.mapValues { (direction, it) ->
                it.copy(behindAt = ways(it, directionFingerprints[direction] ?: lineAlertFingerprint(it)), planned = planned(it))
            },
        )
        return if (placed == status) this else copy(status = placed)
    }

    /** Whether [alerts] holds a dismissal of exactly this alert, full reason included. */
    fun dismissedBy(alerts: Set<DismissedAlert>): Boolean =
        known && dismissedLine(alerts, status.lineId, fingerprint)

    /** The directions whose own status [alerts] holds a dismissal of, as [dismissedBy] judges the line-wide one. */
    fun directionsDismissedBy(alerts: Set<DismissedAlert>): Set<String> =
        if (!known) emptySet()
        else directionFingerprints.filterTo(mutableMapOf()) { (_, it) -> dismissedLine(alerts, status.lineId, it) }.keys

    /** The fingerprints of this check's planned alerts that [alerts] holds a dismissal of. */
    fun plannedDismissedBy(alerts: Set<DismissedAlert>): Set<String> =
        if (!known) emptySet()
        else status.allStatuses.flatMap { it.planned }.map(::plannedAlertFingerprint)
            .filterTo(mutableSetOf()) { dismissedLine(alerts, status.lineId, it) }

    /**
     * [status] as a surface marks it, with each part the user dismissed ([dismissed],
     * [dismissedDirections]) read as a good service, so a row going that way shows no mark while
     * the rest still do; null when no verdict was given or nothing is left to mark, disrupted or to
     * come. Given [today] (in London), planned work whose day has come counts as under way
     * ([LineStatus.asOf]) whether or not its calendar was dismissed: that put away the notice, not
     * the disruption (SPEC *Disruptions*). The planned work still to come keeps the ones not
     * [dismissedPlanned].
     */
    fun shown(today: LocalDate? = null): LineStatus? {
        if (!known) return null
        fun good(s: LineStatus) = s.copy(severity = LineStatus.GOOD_SERVICE)
        val directions = status.byDirection.mapValues { (direction, it) -> if (direction in dismissedDirections) good(it) else it }
        val remaining = directions.values.filter { it.disrupted }
        val whole = when {
            !dismissed -> status
            // The line-wide status is the line's worst alert, so dismissing that alert for the way it
            // applies dismisses it here too. A row with no direction still shows what's left: the
            // other way's alert. In practice one is left; of several, the most severe by TfL's scale.
            // The line's own planned work stays, as a row with no direction carries it in the app.
            remaining.isNotEmpty() -> remaining.minBy { it.severity }.copy(planned = status.planned)
            else -> good(status)
        }
        val dated = whole.copy(byDirection = directions).let { s -> today?.let(s::asOf) ?: s }
        fun kept(s: LineStatus) = s.planned.filterNot { plannedAlertFingerprint(it) in dismissedPlanned }
        val shown = if (dismissedPlanned.isEmpty()) dated
        else dated.copy(planned = kept(dated), byDirection = dated.byDirection.mapValues { (_, it) -> it.copy(planned = kept(it)) })
        return shown.takeIf { it.allStatuses.any(LineStatus::hasAlerts) }
    }

    /**
     * When each piece of planned work this check carries starts, London midnight of its day (the
     * line's or a direction's): where a surface marking it changes, its calendar becoming the ⚠
     * ([shown]). None for a check with no verdict.
     */
    val plannedStarts: List<Instant>
        get() = if (!known) emptyList()
        else status.allStatuses.flatMap { it.planned }.map { it.startsOn.atStartOfDay(AlertStart.ZONE).toInstant() }.distinct()

    /** True while the check is younger than the shared staleness threshold, and not from the future. */
    fun isLive(now: Instant): Boolean {
        val age = SteadyClock.age(checkedAt, now)
        return !age.isNegative && !Staleness.isStale(age.toKotlinDuration())
    }

    /** Whether this was stamped after the wall time [now], by the steady clock: before the clock was set back, across a reboot. */
    fun fromFuture(now: Instant): Boolean = SteadyClock.age(checkedAt, now).isNegative

    companion object {
        /** A check of [lineId] stamped [at] that TfL gave no status for ([known] false). */
        fun noVerdict(lineId: String, at: Instant): LineStatusCheck =
            LineStatusCheck(LineStatus(lineId, LineStatus.GOOD_SERVICE, ""), at, known = false)

        /**
         * [a] and [b] merged line by line, the later check winning, so neither writer's older
         * verdict can overwrite the other's newer one; kept only for [lineIds] when given (the lines
         * the snapshot's stops still show), so a departed stop's lines don't linger. Given [now], a
         * check dated in the future (the clock moved back since) loses to one that isn't.
         */
        fun newest(
            a: Map<String, LineStatusCheck>,
            b: Map<String, LineStatusCheck>,
            lineIds: Set<String>? = null,
            now: Instant? = null,
        ): Map<String, LineStatusCheck> =
            (a.keys + b.keys)
                .filter { lineIds == null || it in lineIds }
                .associateWith { id ->
                    val x = a[id]
                    val y = b[id]
                    // A check dated after [now] came from before the clock moved back: it can't be
                    // trusted as the newer one, so a real check made since replaces it.
                    fun future(c: LineStatusCheck) = now != null && c.fromFuture(now)
                    when {
                        x == null -> y!!
                        y == null -> x
                        future(x) != future(y) -> if (future(x)) y else x
                        y.checkedAt.isAfter(x.checkedAt) -> y
                        else -> x
                    }
                }

        /** Every line [stops] could show a row or a status for: predicted and declared. */
        fun linesOf(stops: List<StopArrivals>): Set<String> =
            stops.flatMapTo(HashSet()) { stop -> stop.departures.map { it.lineId } + stop.lines.map { it.id } }
                .filterTo(HashSet()) { it.isNotBlank() }
    }
}

/**
 * A starred journey as the widget shows it, which can't load route data itself: the departures at
 * [originId] that the app found to call at the far end, identified by their [calls]. [key] is the
 * journey's ([StarredJourney.key]), so the app can pick up what it last saved after a restart.
 */
data class WidgetJourney(
    val originId: String,
    val calls: Set<JourneyCall>,
    val key: String = "",
    // The stop the journey was shown from (its direction) when this was worked out, so a later
    // session showing it the other way round can tell the pin is for the old direction.
    val shownFrom: String = "",
)

/**
 * One journey card's latest check, as the app reports it for the widget: at [originId], the
 * departures [confirmed] to call at the far end, and — only when the check was complete — every
 * departure it [checked], so one the route data now rejects is dropped (SPEC principle 1).
 */
data class WidgetJourneyCheck(
    val key: String,
    val originId: String,
    val confirmed: Set<JourneyCall>,
    val checked: Set<JourneyCall> = emptySet(),
    val shownFrom: String = "",
)

/**
 * What the app's screen reports for the widget's journey pins: the starred journeys' [keys] (all of
 * them — a key missing is an unstar), each placed card's latest [checks], and the stop each journey
 * is shown [from] (its direction), so a flip drops the old direction's pin even before the new
 * direction's route can be checked.
 */
data class WidgetJourneysReport(
    val keys: Set<String>,
    val checks: List<WidgetJourneyCheck>,
    val from: Map<String, String> = emptyMap(),
    // For a journey whose neighboring poles are known: every boarding key it now has (its own and
    // one [WidgetJourneys.poleKey] per pole), so a pole's pin that no longer qualifies goes. A
    // journey absent here keeps its pole pins as they are (its poles are still being looked up).
    val boarding: Map<String, Set<String>> = emptyMap(),
)

object WidgetJourneys {
    /**
     * [stored] with its journey pins updated by [report] — the one place the pins change, applied
     * atomically to the stored snapshot, so every report builds on what is stored rather than on a
     * copy held elsewhere. A journey whose origin [stored] doesn't hold joins with the copy in
     * [origins] (as a journey-only stop); one with neither can't be shown, so isn't kept. A stored
     * origin takes the [origins] copy when that is newer; a journey-only stop no pin starts from
     * goes. Null when nothing is stored and there is nothing to pin.
     */
    /** The widget pin key for [journeyKey]'s departures from the neighboring pole [poleId]. */
    fun poleKey(journeyKey: String, poleId: String): String = "$journeyKey@$poleId"

    /** The journey a pin key belongs to: itself, or the journey of a [poleKey]. */
    fun baseKey(key: String): String = key.substringBefore('@')

    fun apply(
        stored: DeparturesSnapshot?,
        report: WidgetJourneysReport,
        origins: List<StopArrivals>,
    ): DeparturesSnapshot? {
        val prior = stored?.journeys.orEmpty()
            .filter { it.key.isNotEmpty() }
            // Pinned for the other direction than the one now shown: not this journey's pin now.
            .filterNot { j -> report.from[baseKey(j.key)]?.let { j.shownFrom.isNotEmpty() && it != j.shownFrom } == true }
            // A neighboring pole's pin its journey no longer boards from.
            .filterNot { j -> report.boarding[baseKey(j.key)]?.let { j.key !in it } == true }
            .associateBy { it.key }
        // A journey's pole pins are starred while the journey is.
        val keys = (prior.keys + report.checks.map { it.key }).filterTo(HashSet()) { baseKey(it) in report.keys }
        val stops = stored?.stops.orEmpty()
        val storedIds = stops.mapTo(HashSet()) { it.stopId }
        val supplied = origins.associateBy { it.stopId }
        val journeys = merge(prior, keys, report.checks).values
            .filter { it.calls.isNotEmpty() && (it.originId in storedIds || it.originId in supplied) }
            .sortedBy { it.key }
        val pinnedOrigins = journeys.mapTo(HashSet()) { it.originId }
        val journeyOnly = stored?.journeyOnlyStopIds.orEmpty()
        val added = pinnedOrigins.filter { it !in storedIds }.sorted()
        val next = stops
            .filter { it.stopId !in journeyOnly || it.stopId in pinnedOrigins }
            .map { stop ->
                supplied[stop.stopId]?.takeIf { stop.stopId in pinnedOrigins && it.fetchedAt > stop.fetchedAt } ?: stop
            } + added.map { supplied.getValue(it) }
        if (stored == null && next.isEmpty()) return null
        val nextIds = next.mapTo(HashSet()) { it.stopId }
        return DeparturesSnapshot(
            stops = next,
            fetchedAt = next.maxOfOrNull { it.fetchedAt } ?: stored!!.fetchedAt,
            journeys = journeys,
            // An origin that was a missing nearby stop is nearby, recovered: not journey-only.
            journeyOnlyStopIds = journeyOnly.filterTo(HashSet()) { it in nextIds } +
                added.filterNot { it in stored?.missingStopIds.orEmpty() },
            missingStopIds = stored?.missingStopIds.orEmpty() - nextIds,
            // The line checks ride along, less any only a dropped stop showed.
            lineStatuses = stored?.lineStatuses.orEmpty().filterKeys { it in LineStatusCheck.linesOf(next) },
            // The app's nearest-first order rides along too, for the stops still held.
            nearestFirst = stored?.nearestFirst.orEmpty().filter { it in nextIds },
        )
    }

    /**
     * [memory] after the latest [checks], keeping only the starred [keys]. A journey keeps what it
     * last knew through a check that couldn't finish (a route loading or failed), so a reload
     * doesn't unpin it; a complete check replaces what it judged; a new origin (the journey
     * flipped) starts afresh.
     */
    fun merge(
        memory: Map<String, WidgetJourney>,
        keys: Set<String>,
        checks: List<WidgetJourneyCheck>,
    ): Map<String, WidgetJourney> {
        val next = memory.filterKeys { it in keys }.toMutableMap()
        for (check in checks) {
            if (check.key !in keys) continue
            val prior = next[check.key]?.takeIf { it.originId == check.originId }?.calls.orEmpty()
            next[check.key] = WidgetJourney(
                check.originId, (prior - check.checked) + check.confirmed, check.key, check.shownFrom,
            )
        }
        return next
    }
}

/** A departure's route identity at a journey origin: its line, destination and branch. */
data class JourneyCall(val lineId: String, val destination: String, val branch: String?) {
    companion object {
        fun of(departure: Departure) = JourneyCall(departure.lineId, departure.destination, departure.branch)
    }
}
