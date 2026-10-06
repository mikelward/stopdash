package app.stopdash.domain

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The stops a favorite place (SPEC D9) is a short walk from, as the destination a direct train or bus
 * must reach: every station, platform and bus stop within [WALK_METERS] of the place's coordinate,
 * nearest first. A departure from the rider's stops whose
 * route calls at one of them gets the rider there with only that walk at the end ([DirectTrips]).
 */
object PlaceStops {
    /** About ten minutes' walk at an average pace: the walk on from the stop to the place. */
    const val WALK_METERS = 800

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
 * [compute], never the caller's thread (AGENTS.md *Main thread*). It asks for [PlaceStops.WALK_METERS] plus a
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
    suspend fun ends(place: Coordinates): List<DirectTrips.End> {
        val stops = withContext(io) { finder.nearbyStops(place.latitude, place.longitude, LOOKUP_METERS) }
        return withContext(compute) { PlaceStops.ends(place, stops) }
    }

    companion object {
        /** The walk, plus how far off a cached lookup may be centered and still be reused. */
        const val LOOKUP_METERS = PlaceStops.WALK_METERS + NearbyStopsCache.REUSE_WITHIN_METERS.toInt()
    }
}
