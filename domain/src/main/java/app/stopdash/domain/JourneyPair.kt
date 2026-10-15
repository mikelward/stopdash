package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A favorite journey from two stations the rider picked, From then To (SPEC *Journeys*): each by its
 * cleaned name and the stops under it that carry departures ([StationFinder.stationStops]), and where
 * none serves both, the rest of its place too: a station's own bus stops ([withStationBuses]), the
 * station a search row folded into a bus stop ([withBusStations]). Direct only, as every favorite journey
 * is: it rides a line both stations serve, rail before bus, the card then showing every line from one
 * end that calls at the other.
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

    /**
     * How near one of a station's own stops a bus stop must be to count as the station's (maintainer,
     * 2026-10-14): TfL files a Tube or rail station's bus stops apart from it, so a bus that stops
     * outside isn't listed under the station at all. The near-me list's rule for the same question.
     */
    const val STATION_BUS_METERS: Double = FartherBuses.AT_STATION_METERS

    /**
     * The stops of [stops] to look around for the station's own bus stops: each placed one serving a
     * mode other than bus, its position once. A bus stop picked as an end has its poles already.
     */
    @WorkerThread
    fun stationPositions(stops: List<StopLocation>): List<StopLocation> =
        stops.filter { stop ->
            stop.latitude != 0.0 && stop.longitude != 0.0 &&
                stop.lines.any { it.mode.isNotBlank() && !it.mode.equals(FartherBuses.MODE, ignoreCase = true) }
        }.distinctBy { it.latitude to it.longitude }

    /**
     * [stops] and then the bus stops among [around] within [STATION_BUS_METERS] of one of their
     * [stationPositions], nearest first, none twice: an end as [resolve] should weigh it when no line
     * serves both ends' own stops. Grows with [around], so off the main thread.
     */
    @WorkerThread
    fun withStationBuses(stops: List<StopLocation>, around: List<StopLocation>): List<StopLocation> =
        stops + beside(stationPositions(stops), stops, around) { station, stop ->
            stop.lines.any(::isBus) && meters(station, stop) <= STATION_BUS_METERS
        }

    /**
     * The stops of [stops] to look around for the station a search row folded into them: each placed
     * one only buses serve, by position and name once, and none at all for an end with a station among
     * its stops. That end has its trains already, and an interchange's own bus poles would otherwise
     * bring in another station beside one of them, one the rider didn't pick (Codex on #863).
     */
    @WorkerThread
    fun busStopPositions(stops: List<StopLocation>): List<StopLocation> {
        if (stops.any { stop -> stop.lines.any { it.mode.isNotBlank() && !isBus(it) } }) return emptyList()
        return stops.filter { stop ->
            stop.latitude != 0.0 && stop.longitude != 0.0 && stop.lines.isNotEmpty() && stop.lines.all(::isBus)
        }.distinctBy { Triple(it.latitude, it.longitude, it.name) }
    }

    /**
     * [stops] and then the stations among [around] (a stop serving a mode other than bus) that the
     * station search folds into one of their [busStopPositions] ([StationIndex.foldsTogether]: the same
     * name, close by), nearest first, none twice. The search shows a station and a same-named bus stop
     * among the rider's own as one row, which can carry the bus stop's id, and the station's trains would
     * never be weighed; this takes back exactly the station that row stood for, and never a station that
     * merely stands near a bus stop picked for itself (Codex on #863). Grows with [around], so off the
     * main thread.
     */
    @WorkerThread
    fun withBusStations(stops: List<StopLocation>, around: List<StopLocation>): List<StopLocation> =
        stops + beside(busStopPositions(stops), stops, around) { pole, stop ->
            stop.lines.any { it.mode.isNotBlank() && !isBus(it) } &&
                StationIndex.foldsTogether(pole.name, pole.latitude, pole.longitude, stop.name, stop.latitude, stop.longitude)
        }

    // The stops among [around] that [fit] one of [centers], nearest first, none twice and none of [stops].
    private fun beside(
        centers: List<StopLocation>,
        stops: List<StopLocation>,
        around: List<StopLocation>,
        fit: (center: StopLocation, stop: StopLocation) -> Boolean,
    ): List<StopLocation> {
        if (centers.isEmpty()) return emptyList()
        val ids = stops.mapTo(HashSet()) { it.id }
        return around.asSequence()
            .filter { stop -> stop.id !in ids }
            .mapNotNull { stop ->
                centers.filter { fit(it, stop) }.minOfOrNull { meters(it, stop) }?.let { it to stop }
            }
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }
            .distinctBy { it.id }
            .toList()
    }

    private fun meters(a: StopLocation, b: StopLocation): Double = NearestStops.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun isBus(line: LineRef): Boolean = line.mode.equals(FartherBuses.MODE, ignoreCase = true)

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
