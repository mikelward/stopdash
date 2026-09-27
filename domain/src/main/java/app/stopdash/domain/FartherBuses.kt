package app.stopdash.domain

/**
 * The near-me list's farther bus cards (SPEC *Finding stops → Farther stations*): the bus-stop
 * counterpart of [FartherStations], replacing the "More bus stops" button (maintainer,
 * 2026-09-25). Each card is a farther bus place — a junction's poles, or an interchange's —
 * from the nearby lookup's *more* tier that adds a route the list doesn't already show, so
 * the rider sees which routes a tap would bring in. Offering the cards costs no request: the
 * lookup already listed each stop's routes. A tap fetches the place's poles.
 */
object FartherBuses {
    /** At most this many bus places, so a busy corner isn't a wall of cards. */
    const val MAX_CARDS = 4

    const val MODE = "bus"

    /**
     * How near a station on the list a bus place's pole must be to count as at that station
     * (maintainer, 2026-09-26): the rider is walking to the station anyway, so its bus stops beat a
     * place that's a little nearer as the crow flies but a longer walk. Wide enough for a station's
     * own stops (Highgate's are 60–90 m from its position), narrow enough to leave the next road's.
     */
    const val AT_STATION_METERS = 150.0

    /**
     * A farther bus place: its [key] (the interchange and name its poles share, else the junction),
     * its poles nearest first, how far its nearest pole is, its bus routes, in the order its poles
     * list them, and the ids of the station stops a pole is within [AT_STATION_METERS] of
     * ([stationIds]). Whether one of those stations is on the list is decided against the rows the
     * screen draws ([CollapsedPlaces.withBusesPicked]).
     */
    data class Farther(
        val key: String,
        val stops: List<StopLocation>,
        val meters: Double,
        val lines: List<LineRef>,
        val stationIds: Set<String> = emptySet(),
    )

    /**
     * The stops of the stations the list may show: the [eager] clusters' stops serving a mode other
     * than bus that isn't [hidden]. What [candidates] measures a bus place's nearness to a station
     * against; whether the station is actually shown — fetched, and not folded away by the nearest-
     * stop dedupe — is left to the rows drawn (Codex).
     */
    fun stationStops(
        eager: List<NearbySelection.NearbyCluster>,
        hidden: Set<String> = emptySet(),
    ): List<StopLocation> =
        eager.flatMap { it.stops }.filter { stop ->
            stop.lines.any { line ->
                line.mode.isNotBlank() && !line.mode.equals(MODE, ignoreCase = true) && !HiddenModes.isHidden(line, hidden)
            }
        }

    /**
     * The candidate bus places in [more] (the *more* tier, nearest first), nearest first, each with
     * every bus route its poles serve. A route-less stop is no candidate, it has no departures;
     * hidden buses ([hidden]) give none. Clusters sharing an interchange and a name are one place. A
     * place records which of [stations] ([stationStops]) a pole is within [AT_STATION_METERS] of
     * ([Farther.stationIds]). Which of them become cards, and which routes each names, is decided
     * against the rows the screen actually shows ([CollapsedPlaces.withBusesPicked]), so this never
     * has to guess what the list shows.
     */
    fun candidates(
        more: List<NearbySelection.NearbyCluster>,
        hidden: Set<String> = emptySet(),
        stations: List<StopLocation> = emptyList(),
    ): List<Farther> {
        if (HiddenModes.isHidden(MODE, hidden)) return emptyList()
        // Places in first-seen order, which is nearest first: [more] is distance-ordered.
        val places = LinkedHashMap<String, MutableList<NearbySelection.NearbyCluster>>()
        for (cluster in more) {
            // TfL's mode ids aren't guaranteed lowercase ([HiddenModes]).
            if (cluster.modes.none { it.equals(MODE, ignoreCase = true) }) continue
            // An interchange's clusters are one place only where they share a name: a card is named
            // after its nearest pole, so a differently named constituent would show its routes under
            // a stop that doesn't serve them.
            val hub = cluster.stops.firstNotNullOfOrNull { it.hubId.takeIf(String::isNotBlank) }
            val key = hub?.let { "hub:$it|${cluster.stops.first().name}" } ?: cluster.key
            places.getOrPut(key) { mutableListOf() } += cluster
        }
        return places.mapNotNull { (key, clusters) ->
            val stops = clusters.flatMap { it.stops }
            val lines = LinkedHashMap<String, LineRef>()
            for (stop in stops) {
                for (line in stop.lines) {
                    if (line.mode.equals(MODE, ignoreCase = true) && line.id.isNotBlank() && !HiddenModes.isLineHidden(line.id, hidden)) {
                        lines.putIfAbsent(line.id, line)
                    }
                }
            }
            if (lines.isEmpty()) return@mapNotNull null
            val stationIds = stations.filterTo(mutableListOf()) { station ->
                stops.any { pole ->
                    NearestStops.distanceMeters(pole.latitude, pole.longitude, station.latitude, station.longitude) <=
                        AT_STATION_METERS
                }
            }.mapTo(HashSet()) { it.id }
            Farther(key, stops, clusters.minOf { it.distanceMeters }, lines.values.toList(), stationIds)
        }
    }
}
