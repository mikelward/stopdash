package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A favorite journey from two stations the rider picked, From then To (SPEC *Journeys*): each by its
 * cleaned name and the stops under it that carry departures ([StationFinder.stationStops]), and where
 * none serves both, the rest of its place too ([withPlace], by [StopPlace]'s rule). Direct only, as every favorite journey
 * is: it rides a line both stations serve, rail before bus, the card then showing every line from one
 * end that calls at the other.
 */
object JourneyPair {
    sealed interface Result {
        data class Found(val journey: FavoriteJourney) : Result

        /** The same station picked for both ends, or two stops of one place ([StopPlace.samePlace]). */
        data object SameStation : Result

        /** No line serves both: a journey with a change waits on *Trips with a change*. */
        data object NoDirectLine : Result
    }

    @WorkerThread
    fun resolve(fromName: String, fromStops: List<StopLocation>, toName: String, toStops: List<StopLocation>): Result {
        val toIds = toStops.mapTo(HashSet()) { it.id }
        if (fromStops.any { it.id in toIds }) return Result.SameStation
        // Two picks of one place (a station's bus stop and the station, an interchange's two stations) share
        // no stop and often no line, so they'd be saved grayed as a ride needing a change (Codex on #867).
        val toPlaces = toStops.map(StopPlace::of)
        if (fromStops.any { from -> StopPlace.of(from).let { at -> toPlaces.any { StopPlace.samePlace(at, it) } } }) {
            return Result.SameStation
        }
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
     * The stops of [stops] to look around for the rest of a station's place: each placed one serving a
     * mode other than bus, its position once. A bus stop picked as an end has its poles already.
     */
    @WorkerThread
    fun stationPositions(stops: List<StopLocation>): List<StopLocation> =
        stops.filter { stop -> placed(stop) && StopPlace.isStation(stop) }.distinctBy { it.latitude to it.longitude }

    /**
     * The stops of [stops] to look around for the station a bus stop belongs to: each placed one only
     * buses serve, by position and name once, and none at all for an end with a station among its stops.
     * That end has its trains already, and an interchange's own bus poles would otherwise bring in another
     * station beside one of them, one the rider didn't pick (Codex on #863).
     */
    @WorkerThread
    fun busStopPositions(stops: List<StopLocation>): List<StopLocation> {
        if (stops.any(StopPlace::isStation)) return emptyList()
        return stops.filter { stop -> placed(stop) && StopPlace.isBusStop(stop) }
            .distinctBy { Triple(it.latitude, it.longitude, it.name) }
    }

    /**
     * The interchanges whose stops may be part of [stops]' place ([StopPlace.sameInterchange]): those of
     * its stations, or for a bus-stop end, of its bus stops. Each once, TfL's spelling of the first.
     */
    @WorkerThread
    fun interchanges(stops: List<StopLocation>): List<String> =
        stops.filter(StopPlace::isStation).ifEmpty { stops.filter(StopPlace::isBusStop) }
            .map { it.hubId }.filter { it.isNotBlank() }.distinctBy { it.uppercase() }

    /**
     * The stations a station end's bus stops are measured from: its own among [stops], then those among
     * [around] one place with one of them ([StopPlace.samePlace]), as an interchange's other stations are,
     * so a bus stop beside St Pancras counts for a ride picked at King's Cross (Codex on #867). None for a
     * bus-stop end, whose place is the stations it belongs to and never their bus stops: two of an
     * interchange's poles named apart stay two. Grows with [around], so off the main thread.
     */
    @WorkerThread
    fun placeStations(stops: List<StopLocation>, around: List<StopLocation>): List<StopLocation> {
        val own = stops.filter(StopPlace::isStation)
        if (own.isEmpty()) return emptyList()
        val ids = own.mapTo(HashSet()) { it.id }
        return own + around.filter { stop ->
            stop.id !in ids && StopPlace.isStation(stop) && own.any { StopPlace.samePlace(StopPlace.of(it), StopPlace.of(stop)) }
        }.distinctBy { it.id }
    }

    /**
     * [stops] and then the rest of their place among [around], nearest first, none twice: an end as
     * [resolve] should weigh it when no line serves both ends' own stops ([StopPlace]). A station end
     * takes the stations one place with one of its stations ([StopPlace.samePlace]: named alike close
     * by, or in its interchange) and the bus stops of any of those ([StopPlace.ownStop]: those too, and
     * any close by, [placeStations]), as TfL files a station's bus stops apart from it. A bus-stop end
     * takes only the stations it belongs to ([StopPlace.samePlace]), never one that merely stands near a
     * stop picked for itself (Codex on #863). Stations are measured only from [stops] as picked, and bus
     * stops never from another bus stop, so a station's bus stop never brings in the next station along.
     * Grows with [around], so off the main thread.
     */
    @WorkerThread
    fun withPlace(stops: List<StopLocation>, around: List<StopLocation>): List<StopLocation> {
        val own = stops.filter(StopPlace::isStation)
        if (own.isNotEmpty()) {
            val ownIds = own.mapTo(HashSet()) { it.id }
            return stops + beside(placeStations(stops, around), stops, around) { station, stop ->
                when {
                    StopPlace.isStation(stop) -> station.id in ownIds && StopPlace.samePlace(StopPlace.of(station), StopPlace.of(stop))
                    stop.lines.any(::isBus) -> StopPlace.ownStop(StopPlace.of(station), StopPlace.of(stop))
                    else -> false
                }
            }
        }
        return stops + beside(stops.filter(StopPlace::isBusStop), stops, around) { pole, stop ->
            StopPlace.isStation(stop) && StopPlace.samePlace(StopPlace.of(pole), StopPlace.of(stop))
        }
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

    // How far apart [a] and [b] stand; past everything for one without a position, which an interchange
    // can still join, so it sorts after the measured ones.
    private fun meters(a: StopLocation, b: StopLocation): Double =
        if (placed(a) && placed(b)) NearestStops.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude) else Double.MAX_VALUE

    private fun placed(stop: StopLocation): Boolean = StopPlace.placed(stop)

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
