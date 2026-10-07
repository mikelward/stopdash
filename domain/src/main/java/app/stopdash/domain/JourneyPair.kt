package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A favorite journey from two stations the rider picked, From then To (SPEC *Journeys*): each by its
 * cleaned name and the stops under it that carry departures ([StationFinder.stationStops]). Direct
 * only, as every favorite journey is: it rides a line both stations serve, rail before bus, the
 * card then showing every line from one end that calls at the other.
 */
object JourneyPair {
    sealed interface Result {
        data class Found(val journey: FavoriteJourney) : Result

        /** The same station picked for both ends. */
        data object SameStation : Result

        /** No line serves both: a journey with a change waits on *Trips with a change*. */
        data object NoDirectLine : Result
    }

    @WorkerThread
    fun resolve(fromName: String, fromStops: List<StopLocation>, toName: String, toStops: List<StopLocation>): Result {
        val toIds = toStops.mapTo(HashSet()) { it.id }
        if (fromStops.any { it.id in toIds }) return Result.SameStation
        // Each line at the far end, and the first of its stops there.
        val atTo = LinkedHashMap<String, StopLocation>()
        toStops.forEach { stop -> stop.lines.forEach { if (it.id.isNotBlank()) atTo.putIfAbsent(it.id, stop) } }
        val shared = fromStops.flatMap { stop -> stop.lines.filter { it.id in atTo }.map { stop to it } }
        // Rail first: a bus between two stations is rarely the journey meant.
        val (from, line) = shared.minByOrNull { (_, line) -> if (line.mode.equals("bus", ignoreCase = true)) 1 else 0 }
            ?: return Result.NoDirectLine
        val to = atTo.getValue(line.id)
        return Result.Found(FavoriteJourney(end(from, fromName), end(to, toName), line.id, line.name, line.mode))
    }

    private fun end(stop: StopLocation, name: String): JourneyEnd {
        // TfL's stop data gives 0.0 for a coordinate it left out: no position, never a real one, so
        // the journey is shown in full rather than measured from the far side of the world.
        val placed = stop.latitude != 0.0 && stop.longitude != 0.0
        return JourneyEnd(
            stopId = stop.id,
            name = name,
            latitude = stop.latitude.takeIf { placed },
            longitude = stop.longitude.takeIf { placed },
            // The stop area, where TfL gave one: [StopLocation.clusterId] falls back to the stop's name.
            areaId = stop.clusterId.takeIf { it.isNotBlank() && it != stop.name && ' ' !in it }.orEmpty(),
        )
    }
}
