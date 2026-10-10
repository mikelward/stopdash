package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Where the nearby stops sit around a position: which are picked, how far each is, their order
 * nearest first, and each one's nearer places. One implementation for the app's list and the
 * widget's own refresh (which follows the rider while the app is closed), so the two can't lay the
 * same stops out differently: each had its own copy, and they drifted (#748).
 */
object NearbyLayout {
    /**
     * The stops picked at a position ([pick]): the [eager] clusters (fetched and shown), the [more]
     * tier, and each one's [distances] in meters from the position, both tiers. Held in memory only,
     * never stored (SPEC *Privacy*).
     */
    data class Picked(
        val eager: List<NearbySelection.NearbyCluster>,
        val more: List<NearbySelection.NearbyCluster>,
        val distances: Map<String, Double>,
    )

    /**
     * The stops of [found] picked around [at] ([NearbySelection.selectClusters]), [hidden] modes left
     * out unless that leaves nothing eager (then the full set is picked, so the list says what's
     * hidden rather than claiming nothing runs nearby). [anchorStopIds], a searched station's own
     * stops, stand at [at] (0 m) for picking and distance, but keep their real positions in the set.
     */
    @WorkerThread
    fun pick(
        found: List<StopLocation>,
        at: Coordinates,
        hidden: Set<String>,
        radiusMeters: Int = NearbySelection.OUTER_RADIUS_METERS,
        anchorStopIds: Set<String> = emptySet(),
    ): Picked {
        val placed = if (anchorStopIds.isEmpty()) {
            found
        } else {
            found.map { if (it.id in anchorStopIds) it.copy(latitude = at.latitude, longitude = at.longitude) else it }
        }
        val result = NearbySelection.selectClusters(HiddenModes.stops(placed, hidden), at.latitude, at.longitude, outerRadiusMeters = radiusMeters)
            .takeIf { it.eager.isNotEmpty() }
            ?: NearbySelection.selectClusters(placed, at.latitude, at.longitude, outerRadiusMeters = radiusMeters)
        // Only the position goes back: the picked copy's lines stay (a hidden mode's are off it).
        val real = if (anchorStopIds.isEmpty()) emptyMap() else found.associateBy { it.id }
        fun restored(clusters: List<NearbySelection.NearbyCluster>) =
            if (real.isEmpty()) {
                clusters
            } else {
                clusters.map { c ->
                    c.copy(stops = c.stops.map { s -> real[s.id]?.let { s.copy(latitude = it.latitude, longitude = it.longitude) } ?: s })
                }
            }
        val eager = restored(result.eager)
        val more = restored(result.more)
        val distances = (eager + more).flatMap { it.stops }.associate {
            it.id to if (it.id in anchorStopIds) 0.0 else NearestStops.distanceMeters(at.latitude, at.longitude, it.latitude, it.longitude)
        }
        return Picked(eager, more, distances)
    }

    /** [ids] that have a distance in [distances], nearest first, a tie by id so the order is stable. */
    @WorkerThread
    fun nearestFirst(ids: Collection<String>, distances: Map<String, Double>): List<String> =
        ids.distinct().filter { it in distances }.sortedWith(compareBy<String> { distances.getValue(it) }.thenBy { it })

    /** One stop's place for [Terminating.nearer]: its [id], [clusterId] and [name], at [distances]; none without a distance. */
    fun place(id: String, clusterId: String, name: String, distances: Map<String, Double>): Terminating.Place? =
        distances[id]?.let { Terminating.Place(id, clusterId, name, it) }

    /** [stops]' places ([place]), each stop once. */
    @WorkerThread
    fun places(stops: Iterable<StopLocation>, distances: Map<String, Double>): List<Terminating.Place> =
        stops.distinctBy { it.id }.mapNotNull { place(it.id, it.clusterId, it.name, distances) }
}
