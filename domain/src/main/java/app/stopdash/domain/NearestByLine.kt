package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Each line's stop nearest the rider within walking reach, which a line's map keeps on the page (SPEC
 * *Line page → Folding*). Line id → stop id. Walks every stop: on a worker only.
 */
object NearestByLine {
    /** How near a stop counts: the near-me list's walking reach. */
    const val WITHIN_METERS: Int = NearbySelection.EAGER_RADIUS_METERS

    /**
     * From the nearby [stops]' own data (both tiers, a stop whose times aren't fetched included), by
     * their [distances]; a stop with no distance, or beyond [WITHIN_METERS], never counts.
     */
    @WorkerThread
    fun byLine(stops: List<StopLocation>, distances: Map<String, Double>): Map<String, String> {
        val nearest = HashMap<String, String>()
        stops.filter { within(it.id, distances) }
            .sortedBy { distances.getValue(it.id) }
            .forEach { stop -> stop.lines.forEach { if (it.id.isNotBlank()) nearest.putIfAbsent(it.id, stop.id) } }
        return nearest
    }

    /**
     * The nearer, line by line, of the fetched [arrivals]' stops (a line with a departure there, or among
     * its routes) and [known], [byLine]'s from the stops' own data, by their [distances]. None with no
     * [distances]: a list with no position to measure from (the watched stops).
     */
    @WorkerThread
    fun merged(arrivals: List<StopArrivals>, known: Map<String, String>, distances: Map<String, Double>): Map<String, String> {
        if (distances.isEmpty()) return emptyMap()
        val nearest = HashMap<String, String>()
        for (stop in arrivals.filter { within(it.stopId, distances) }.sortedBy { distances.getValue(it.stopId) }) {
            stop.departures.forEach { if (it.lineId.isNotBlank()) nearest.putIfAbsent(it.lineId, stop.stopId) }
            stop.lines.forEach { if (it.id.isNotBlank()) nearest.putIfAbsent(it.id, stop.stopId) }
        }
        for ((line, stopId) in known) {
            if (!within(stopId, distances)) continue
            val listed = nearest[line]?.let { distances[it] }
            if (listed == null || distances.getValue(stopId) < listed) nearest[line] = stopId
        }
        return nearest
    }

    private fun within(stopId: String, distances: Map<String, Double>) = distances[stopId]?.let { it <= WITHIN_METERS } == true
}
