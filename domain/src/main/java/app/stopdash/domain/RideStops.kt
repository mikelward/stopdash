package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A ride's stops as the On the way screen lists them under **Show stops**: where it boards, then each stop
 * it calls at through where it gets off, by name, and [here], the index of the one the rider is at or last
 * passed — where it boards until they're on board, then the stop before the next one the trip counts to.
 * Null [here] where on board but the trip can't place them, so no stop is marked as theirs. [missing] when a stop
 * had no name to show, so the line's route is worth reading for it.
 */
class RideStopList(
    val names: List<String>,
    val here: Int?,
    val missing: Boolean = false,
    // [here] comes from TfL's calls for the train (not where the rider was seen, nor the boarding stop): it goes as
    // soon as the trip's answer is too old to stand behind, before the list is worked out again ([shownStops]).
    val fromCalls: Boolean = false,
) {
    /**
     * This list as shown now, before it's worked out again for the trip's latest answer: unmarked where that answer
     * places no one ([marksNoStop]), or is [stale] and the stop came from TfL's calls (Codex, #968).
     */
    fun shownStops(stale: Boolean, progress: TripProgress?, boarded: Boolean): RideStopList = when {
        // Where it boards while the rider is still to board, as [rideStopList] marks it (Codex, #968).
        marksBoarding(progress, boarded) -> if (here == 0 || names.isEmpty()) this else RideStopList(names, 0, missing, fromCalls = false)
        here != null && ((stale && fromCalls) || marksNoStop(progress, boarded, stale)) -> RideStopList(names, null, missing, fromCalls)
        else -> this
    }
}

/** Whether [progress] leaves the rider where the ride boards ([rideStopList]'s marker at its first stop). */
fun marksBoarding(progress: TripProgress?, boarded: Boolean): Boolean = when (progress) {
    is TripProgress.Riding -> !progress.seen
    else -> !boarded
}

/**
 * Whether [progress] certainly places the rider at no stop of the ride ([rideStopList]'s marker), told without walking
 * its stops, so a list held from an earlier answer drops its marker at once rather than once it's worked out again
 * (Codex, #968). Only the cases that need no path; the rest wait for the list.
 */
fun marksNoStop(progress: TripProgress?, boarded: Boolean, stale: Boolean): Boolean = when {
    progress is TripProgress.Riding && !progress.seen -> false
    progress is TripProgress.Riding && stale && !progress.byPosition -> true
    progress is TripProgress.Riding -> progress.stopsLeft == null && progress.nextStop == null
    !boarded -> false
    progress is TripProgress.Lost -> progress.ahead == null || !progress.placed || stale
    else -> true
}

/**
 * [on]'s stops ([RideStopList]) as [progress] places the rider on it. [on] is the ride as it's taken
 * ([OnTheWay.ridingOn]), which may be another of its lines with its own stops (a Circle train along the
 * Hammersmith & City), the line the trip counts the stops left on. [progress] is the trip's for this ride, or
 * null for a ride still to come. A stop the Planner left unnamed (a bus's, often) takes its name on [lineNames],
 * the line's route's; one named by neither is left out rather than shown by its code (Codex, #968). Null when no
 * stops are named for it. Walks the path: off the main thread.
 */
@WorkerThread
fun rideStopList(
    on: TripLeg,
    progress: TripProgress?,
    boarded: Boolean,
    lineNames: Map<String, String> = emptyMap(),
    // The trip's last answer is too old to stand behind (SPEC D4): a stop its calls placed the rider at isn't marked.
    stale: Boolean = false,
): RideStopList? {
    if (on.path.isEmpty()) return null
    val all: List<String?> = listOf(on.fromName) + on.path.indices.map { i ->
        val id = on.path[i]
        on.pathNames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: lineNames[id]?.takeIf { it.isNotBlank() }
            // Where it gets off: the path's last stop, whatever id names it (a bus path's stop area, with or without the
            // leg's own), or its pole (Codex, #968).
            ?: on.toName.takeIf { i == on.path.lastIndex || id == on.toId || (on.toArea.isNotEmpty() && id == on.toArea) }
    }
    // all[i + 1] is path[i], so the stop before the path's next one is all[next].
    // Only as far as the rider is placed: a train not yet seen to have them on board leaves them where it boards,
    // as the step still says to take it, and a lost train's last stop ahead counts only once its calls place it
    // again. The next stop by name only where the ride calls there once: a loop's second visit can't be told from
    // its first (Codex, #968).
    val here = when {
        marksBoarding(progress, boarded) -> 0
        // Placed by calls too old to trust, as the card withholds them; counted from where the rider was seen stands.
        progress is TripProgress.Riding && stale && !progress.byPosition -> null
        progress is TripProgress.Riding -> progress.stopsLeft?.let { on.path.size - it }
            ?: progress.nextStop?.let { next ->
                all.indices.filter { it > 0 && all[it] == next }.singleOrNull()?.minus(1)
            }
        // Its stop ahead is counted along the planned leg's stops as well as the ridden one's, so it marks only a ride
        // whose stops are the planned leg's: on another branch it could land stops off (Codex, #968).
        progress is TripProgress.Lost -> progress.ahead?.takeIf { progress.placed && !stale && progress.leg.path == on.path }
        else -> null
    }?.coerceIn(0, on.path.size)
    val kept = all.indices.filter { all[it] != null }
    if (kept.size < 2) return if (lineNames.isEmpty()) RideStopList(emptyList(), null, missing = true) else null
    // The rider's stop among those named: theirs, else the last named before it.
    val shownHere = here?.let { h -> kept.indexOfLast { it <= h }.takeIf { it >= 0 } }
    val fromCalls = (progress is TripProgress.Riding && progress.seen && !progress.byPosition) || (progress is TripProgress.Lost && boarded)
    return RideStopList(kept.map { all[it]!! }, shownHere, missing = kept.size < all.size, fromCalls = fromCalls)
}

/**
 * [ride] with the stops it calls at from [sequence], where the Planner listed none: the line's way from where it
 * boards through where it gets off, by its station, its stop area or, failing both, its name. Unchanged where
 * it has stops already, or the route can't place it. Walks the route: off the main thread.
 */
@WorkerThread
fun withRouteStops(ride: TripLeg, sequence: LineSequence): TripLeg {
    if (ride.path.isNotEmpty()) return ride
    // The line's way from where it boards, as a trip's leg places it ([onPoles], [RouteStops.forLeg]).
    val leg = onPoles(ride, mapOf(ride.lineId to sequence))
    val route = sequence.callingAt(leg.fromId)
    val found = RouteStops.forLeg(route, leg) as? RouteStops.Resolution.Found ?: return ride
    // By id, by another id of its station (St Pancras's low level for its main id; Codex, #968), then stop area.
    // Where it gets off as placed on the route's poles ([onPoles] may pick another stand of a bus station), or as planned.
    val offIds = listOf(leg.toId, ride.toId).filter { it.isNotEmpty() }.distinct()
    fun offHere(stop: RouteStop) = offIds.any { route.sameStationId(stop.id, it) } ||
        route.stopAreas[stop.id]?.let { it in offIds || (ride.toArea.isNotEmpty() && it == ride.toArea) } == true
    val off = found.stops.indexOfFirst(::offHere).takeIf { it > 0 }
        ?: found.stops.indexOfFirst { it.name.isNotBlank() && it.name == ride.toName }.takeIf { it > 0 }
        ?: return ride
    val after = found.stops.subList(1, off + 1)
    return ride.copy(path = after.map { it.id }, pathNames = after.map { it.name })
}

/**
 * Each stop's name on [sequence], by its own id and by its stop area's: a bus's Planner path names a stop by its
 * area (`490G…`) where the route names its poles, so a pole's name stands for its area too (Codex, #968). Walks the
 * route: off the main thread.
 */
@WorkerThread
fun lineStopNames(sequence: LineSequence): Map<String, String> = buildMap {
    for ((pole, area) in sequence.stopAreas) {
        val name = sequence.stopNames[pole]?.takeIf { it.isNotBlank() } ?: continue
        if (area.isNotBlank()) putIfAbsent(area, name)
    }
    putAll(sequence.stopNames)
}
