package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.LegTimetables
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.Staleness
import app.stopdash.domain.StepFree
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.onPoles
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Rides' timetables ([LegTimetables]), for a favorite ride's next trains while nothing live says ([rememberTimetabledTrains]); null gives none. */
val LocalLegTimetables = staticCompositionLocalOf<LegTimetables?> { null }

/**
 * [journey] as a trip's ride from [origin], where its card boards, to its far end: its page's stop list and
 * timetable are then the ride's own, the direction and branch it takes, not a guess at the line's.
 */
internal fun journeyRide(journey: FavoriteJourney, origin: String): TripLeg = TripLeg(
    mode = journey.mode,
    lineId = journey.lineId,
    lineName = journey.lineName.ifBlank { journey.lineId },
    fromId = origin,
    fromName = journey.from.name,
    toId = journey.to.stopId,
    toName = journey.to.name,
    // Not a planned ride: no times of its own.
    departure = Instant.EPOCH,
    arrival = Instant.EPOCH,
    fromArea = journey.from.areaId,
    toArea = journey.to.areaId,
)

/**
 * [ride] placed on the poles its line's route uses ([placedRide], from [LocalRouteStops]), as its stop list's
 * ends are: a saved bus ride's far end is the pole of the way it was saved, across the road from the one it
 * gets off at the other way (Codex on #1036). Null until placed, on the worker ([LocalWorker]); only ever [ride]'s own
 * ([Worked]), never another ride's placed for the frame the page moves on to this one.
 */
@Composable
internal fun rememberPlacedRide(ride: TripLeg): TripLeg? {
    val worker = LocalWorker.current
    val routes = LocalRouteStops.current
    val placed by produceState<Worked<TripLeg, TripLeg>?>(null, worker, routes, ride) {
        value = Worked(ride, withContext(worker) { placedRide(ride, routes) })
    }
    return placed?.takeIf { it.key == ride }?.value
}

/**
 * [ride]'s next [count] trains by its timetable as of [now] ([app.stopdash.domain.StopTimetable.nextDepartures]),
 * worked out again each minute; none while it's fetched, where the timetable can't say, while the ride isn't placed
 * yet ([rememberPlacedRide]), and none on or after a day in [plannedFrom] ([departureBefore]). Fetched and read on
 * the worker ([LocalWorker]): one request per ride a day, shared with trips' ([LegTimetables]).
 *
 * Each answer is paired with the ride, planned work and count it was worked out for ([Worked]), so none outlives
 * them: a ride no longer eligible, another one, or other planned work shows none from that very frame, not for the
 * frame or worker's turn until the next is in (Codex on #1036 and #1044), nor comes back when it's eligible again
 * (Codex on #1048). A minute's tick keeps the last one up.
 */
@Composable
internal fun rememberTimetabledTrains(ride: TripLeg?, now: Instant, plannedFrom: List<LocalDate> = emptyList(), count: Int = TIMETABLED_TRAINS): TimetabledTimes {
    val timetables = LocalLegTimetables.current ?: return TimetabledTimes.NONE
    val worker = LocalWorker.current
    val current by rememberUpdatedState(now)
    // What the trains are worked out for, compared in constant time: the planned-work days by identity, kept the same
    // list while unchanged ([rideViews]), never walked here (AGENTS.md *Main thread*, Codex on #1048).
    val asked = ride?.let { Inputs(it.lineId, it.fromId, it.toId, plannedFrom, count) }
    val next by produceState<Worked<Inputs, TimetabledTimes>?>(null, timetables, worker, asked) {
        // No longer eligible: its answer goes, so one from minutes ago isn't back up when it is again (Codex on #1048).
        val ask = asked
        if (ask == null || ride == null) {
            value = null
            return@produceState
        }
        val key = LegTimetables.Key(ride.lineId, ride.fromId, ride.toId)
        snapshotFlow { current.epochSecond / 60 }.collect {
            val at = current
            value = Worked(
                ask,
                withContext(worker) {
                    timetables.fetch(listOf(key))
                    TimetabledTimes.of(timetables.known().timetables[key]?.nextDepartures(at, count).orEmpty().mapNotNull { departureBefore(it, plannedFrom) })
                },
            )
        }
    }
    return next?.takeIf { asked != null && it.key == asked }?.value ?: TimetabledTimes.NONE
}

/**
 * Trains by a timetable ([times]), with [text], them as a page shows them ("05:41 · 05:49 · 05:57"): written on the
 * worker with them, so composition only reads it (AGENTS.md *Main thread*, Codex on #1042).
 */
internal class TimetabledTimes(val times: List<Instant>, val text: String) {
    companion object {
        val NONE = TimetabledTimes(emptyList(), "")

        @WorkerThread
        fun of(times: List<Instant>): TimetabledTimes =
            if (times.isEmpty()) NONE else TimetabledTimes(times, times.joinToString(" · ") { CLOCK.format(it.atZone(LONDON)) })
    }
}

/** How many of a ride's next trains its page gives by the timetable (maintainer, 2026-10-21: "the next few"). */
internal const val TIMETABLED_TRAINS = 3

/**
 * A favorite ride's page, from its line's row with no train ([JourneyCard.lineCard]): the route page for the
 * ride ([RouteDetailScreen]), with its stops from where its card boards to its far end, the direction and
 * branch it takes ([rememberLegRouteStops]), its line's alert if it has one, and, while nothing live says,
 * its timetable's next few trains ([rememberTimetabledTrains]). Drawn against [loaded], the list's fetch, at [now].
 */
@Composable
internal fun RidePage(view: RideView, loaded: DeparturesUiState.Loaded, now: Instant, onBack: () -> Unit, lineOpen: MutableState<Boolean>) {
    val row = view.row
    val disruptionUnknown = loaded.lineUncheckedFor(row) || row.stopId in loaded.stopsDisruptionUnknown
    val disruptionChecking = loaded.checkingDisruptionsFor(row)
    val lineUnknown = loaded.lineDoubtedFor(row, now)
    val lineChecking = loaded.checkingLineFor(row)
    // A timetable knows nothing of a disruption, so its time only once the line's good service is checked,
    // current, and its stop's closure check is back clean (Codex on #1036).
    // Its ends where its bus really stops, for its timetable and its line's page alike (Codex on #1036).
    val placed = rememberPlacedRide(view.ride)
    val stale = Staleness.isStale(row.fetchedAt, now)
    // Only while nothing live says (once the card has trains, the timetable speaks for none) and every check is
    // in clean and current, a dismissed alert still counting as one; not even fetched till then (Codex on #1036).
    val eligible = !view.live && !stale && !disruptionUnknown && !disruptionChecking && !lineUnknown && !lineChecking &&
        row.stopDisruption == null && row.status == null && !row.statusDismissed &&
        // Nor for a ride whose card holds a closure notice, whose far end has one (dismissed or not), or whose far end
        // couldn't be checked.
        view.card.closures.isEmpty() && !view.card.destinationUnchecked && !view.destinationDisrupted && view.lineListed
    // Nor a train on or after a day its line's planned work starts, which the timetable knows nothing of (Codex on #1036).
    val nextTrains = rememberTimetabledTrains(placed?.takeIf { eligible }, now, view.plannedFrom)
    RouteDetailScreen(
        lineOpen = lineOpen,
        row = row,
        isStarred = false,
        starrable = false,
        disruptionUnknown = disruptionUnknown,
        disruptionChecking = disruptionChecking,
        lineUnknown = lineUnknown,
        lineChecking = lineChecking,
        stale = stale,
        now = now,
        onToggleStar = {},
        onBack = onBack,
        loadRouteStops = { retry: Int -> rememberLegRouteStops(view.ride, StepFree.ANY, retry) },
        ride = placed ?: view.ride,
        nextTrains = nextTrains,
        // Where more than one way joins its ends, the rider chooses the one listed.
        rideChoices = true,
        // Its card's closure notices and an unchecked far end, as the card says them: why its timetable is withheld.
        closures = view.closures,
        uncheckedEnd = view.card.journey.to.name.takeIf { view.card.destinationUnchecked },
    )
}

/**
 * [ride] on the poles its line's route uses ([onPoles]), the route from [routes]; as it was where the route
 * can't be had (none in the composition, or its load failed, said by the repository), so the timetable is
 * asked for the saved stops rather than not at all.
 */
internal suspend fun placedRide(ride: TripLeg, routes: RouteStopsRepository?): TripLeg {
    routes ?: return ride
    val sequence = try {
        routes.load(ride.lineId, "")
    } catch (e: CancellationException) {
        throw e
    } catch (e: TflException) {
        // Already logged (sanitized) by the repository: the saved stops are asked for instead.
        return ride
    }
    return onPoles(ride, mapOf(ride.lineId to sequence))
}

/**
 * [train] if it leaves before every day in [plannedFrom], the days its line's planned work starts, in London; else
 * null: the timetable knows nothing of that work, so its time then isn't one to stand behind (Codex on #1036).
 */
internal fun departureBefore(train: Instant, plannedFrom: List<LocalDate>): Instant? {
    val day = train.atZone(LONDON).toLocalDate()
    return train.takeIf { plannedFrom.none { !it.isAfter(day) } }
}
