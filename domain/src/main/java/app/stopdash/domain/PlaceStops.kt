package app.stopdash.domain

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The stops a favorite place (SPEC D9) is a walk from, as the destination a direct train or bus must
 * reach: every station, platform and bus stop within the rider's walk ([walkMeters]) of the place's
 * coordinate, nearest first. A departure from the rider's stops whose route calls at one of them gets
 * the rider there with only that walk at the end ([DirectTrips]).
 */
object PlaceStops {
    /** About ten minutes' walk at an average pace: the walk on from the stop to the place, by default. */
    const val WALK_METERS = 800

    /**
     * How far from the place a stop may be: as far as the rider's [maxWalk] reaches at their [speed]
     * ([TripTiming.walkReach]), the limit the Planner holds the trip's own walks to, so a stop the routes
     * walk on from is one this section counts too (maintainer, 2026-10-07).
     */
    fun walkMeters(maxWalk: MaxWalk, speed: WalkingSpeed): Int = TripTiming.walkReach(maxWalk.minutes, speed).toInt()

    /**
     * [stops] within [walkMeters] of [place], nearest first and each once, as [DirectTrips.End]s
     * carrying their interchange, so a station listed under one id and routed through a sibling is
     * still matched.
     */
    @WorkerThread
    fun ends(place: Coordinates, stops: List<StopLocation>, walkMeters: Int = WALK_METERS): List<DirectTrips.End> =
        stops.asSequence()
            .map { it to NearestStops.distanceMeters(place.latitude, place.longitude, it.latitude, it.longitude) }
            .filter { (_, meters) -> meters <= walkMeters }
            .sortedBy { (_, meters) -> meters }
            .distinctBy { (stop, _) -> stop.id }
            .map { (stop, _) -> DirectTrips.End(stop.id, stop.name, stop.hubId) }
            .toList()
}

/**
 * Looks up [PlaceStops] for a place through [finder]: one TfL `/StopPoint` request by coordinate, on
 * [io] (a [CachingStopFinder] reads and writes its file there), then works the answer out on
 * [compute], never the caller's thread (AGENTS.md *Main thread*). It asks for the walk plus a
 * cached lookup's reuse distance, so an answer reused from a lookup centered up to that far away still
 * covers the whole walk around this place; [PlaceStops.ends] then keeps only the walk. Give it a cache
 * of its own, so places never push the rider's own areas out of theirs. The place's coordinate goes only
 * to TfL, as a trip planned to the place already sends it (SPEC *Privacy*). Throws as [finder] does, so
 * the caller can say it couldn't check rather than show no trains (SPEC principle 2).
 */
class PlaceStopsFinder(
    private val finder: StopFinder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Workers.compute,
) {
    suspend fun ends(place: Coordinates, walkMeters: Int = PlaceStops.WALK_METERS): List<DirectTrips.End> {
        val stops = withContext(io) { finder.nearbyStops(place.latitude, place.longitude, lookupMeters(walkMeters)) }
        return withContext(compute) { PlaceStops.ends(place, stops, walkMeters) }
    }

    companion object {
        /** The walk, plus how far off a cached lookup may be centered and still be reused. */
        fun lookupMeters(walkMeters: Int): Int = walkMeters + NearbyStopsCache.REUSE_WITHIN_METERS.toInt()

        /** [lookupMeters] for the default walk. */
        val LOOKUP_METERS = lookupMeters(PlaceStops.WALK_METERS)
    }
}
