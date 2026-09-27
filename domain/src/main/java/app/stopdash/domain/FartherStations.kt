package app.stopdash.domain

/**
 * The "From …" buttons at the foot of the near-me list (SPEC *Finding stops → Farther
 * stations*): when the list reaches no station of a rail line — a tube line, a National Rail
 * service, an Overground line, the Elizabeth line, the DLR, a tram — the nearest bundled station
 * that does, so a rider far from the rest of the network can look at it in one tap. A National
 * Rail service counts by the **ends** its trains run to from a station, where the index knows its
 * routes: Thameslink to Bedford from one station and to Cambridge from another are two reasons for
 * a button, not one. Worked out
 * on the device from the bundled [StationIndex] (its stations' public positions and lines): no
 * request, and nothing fetched until a button is tapped. Buses are left out: every stop has them.
 */
object FartherStations {
    /** Beyond this a station isn't the rider's in any useful sense: 3 mi. */
    const val MAX_METERS = 4_828.0

    /** At most this many buttons in all, so a far corner of London isn't a wall of them. */
    const val MAX_BUTTONS = 8

    /** Of which at most this many tube lines, each with up to one station in either direction. */
    const val MAX_TUBE_LINES = 2

    /**
     * How far round from a line's nearest station another of its stations must lie, seen from the
     * rider, to count as the line's other direction: more than a right angle, so on the far side.
     */
    const val OTHER_DIRECTION_DEGREES = 90.0

    /** The rail modes whose lines earn a button (buses and boats never do). */
    val MODES: Set<String> = setOf("tube", "overground", "elizabeth-line", "national-rail", "dlr", "tram")

    /**
     * A farther place: the station to open, how far it is from the rider, and the lines (tube lines,
     * National Rail services, …) it adds that the list doesn't reach, nearest-first by the station
     * that stands for each.
     */
    data class Farther(val station: StationMatch, val meters: Double, val lines: List<Line> = emptyList())

    /** A line of a mode (TfL line ids are unique across modes, but a mode is kept to cap the tube). */
    data class Line(val mode: String, val id: String)

    /**
     * One stop the near-me lookup reached: its ids (stop, cluster, interchange — any may be an index
     * station) and the lines it serves as the lookup saw them.
     */
    data class ReachedStop(val ids: Set<String>, val lines: Set<Line>)

    /** What earns a button: a line, or for a National Rail service with route data, one of its ends. */
    private data class Reason(val line: Line, val end: String?)

    /** The reasons [station] stands for: its lines, a National Rail service split by route end. */
    private fun reasonsOf(station: IndexedStation): List<Reason> =
        station.lines.flatMap { (mode, ids) ->
            ids.flatMap { id ->
                val line = Line(mode, id)
                val ends = if (mode == "national-rail") station.routeEnds[id].orEmpty() else emptyList()
                if (ends.isEmpty()) listOf(Reason(line, null)) else ends.map { Reason(line, it) }
            }
        }

    /**
     * The buttons for a rider at [from], nearest first. [reached] are the stops the near-me lookup
     * already reaches (every tier, "More" included): each one's lines, and where its index record
     * knows a National Rail service's routes, just the ends it reaches — no button for any of that;
     * a [hidden] mode's lines get none either. A service at any reached stop whose routes the index
     * doesn't know counts as reached whole, so a gap in the data costs a button, never adds a
     * wrong one. For each unreached line (or National Rail route end)
     * of [MODES], the nearest station serving it, within [MAX_METERS], and for a line (not a route
     * end) its nearest station the other way from the rider; then, nearest first, a button for each
     * such station unless a nearer button's place already stands for it — one per place, from at
     * most [MAX_TUBE_LINES] tube lines and [MAX_BUTTONS] in all. A station in an interchange stands for the interchange: its
     * button opens the hub (whose page holds all its stations), so Stratford's tube, DLR and rail
     * stations are one "From Stratford…". Unplaced stations are skipped.
     */
    fun pick(
        stations: List<IndexedStation>,
        from: Coordinates,
        reached: List<ReachedStop>,
        hidden: Set<String> = emptySet(),
    ): List<Farther> {
        val byId = stations.associateBy { it.id }
        // Route ends reached from a stop whose record knows them; lines reached whole otherwise.
        val reachedEnds = HashSet<Reason>()
        val reachedWhole = HashSet<Line>()
        for (stop in reached) {
            // The live lookup's lines decide what the stop serves; the index (a weekly snapshot)
            // only adds route detail to them, or stands in when the lookup listed no lines at all.
            val reasons = stop.ids.mapNotNull(byId::get).flatMap(::reasonsOf)
                .filter { stop.lines.isEmpty() || it.line in stop.lines }
            val detailed = reasons.filter { it.end != null }
            reachedEnds += detailed
            // The stop itself is an end its services reach: a terminus nearby makes a farther station
            // whose route ends there add nothing (the index leaves a station out of its own ends).
            for (line in stop.lines) {
                if (line.mode == "national-rail") for (id in stop.ids) reachedEnds += Reason(line, id)
            }
            val known = detailed.mapTo(HashSet()) { it.line }
            reachedWhole += (stop.lines + reasons.map { it.line }).filter { it !in known }
        }
        // A service reached by route somewhere: a farther record of it with no route data can't say
        // it adds an end, so it counts as reached (TfL can list one station twice, one record bare).
        val reachedByRoute = reachedEnds.mapTo(HashSet()) { it.line }
        fun isReached(reason: Reason): Boolean =
            reason.line in reachedWhole || reason in reachedEnds || (reason.end == null && reason.line in reachedByRoute)
        // The place a station stands for: its interchange when it has one, else itself.
        fun placeOf(station: IndexedStation): IndexedStation =
            station.hubId.takeIf { it.isNotBlank() }?.let(byId::get) ?: station
        // A place the list already shows is never a button, whatever its index record claims.
        val shownIds = reached.flatMapTo(HashSet()) { it.ids }
        val nearestByReason = LinkedHashMap<Reason, Pair<IndexedStation, Double>>()
        val candidates = stations.mapNotNull { station ->
            val lat = station.latitude
            val lon = station.longitude
            if (lat == null || lon == null || station.id.startsWith("HUB", ignoreCase = true)) return@mapNotNull null
            if (station.id in shownIds || placeOf(station).id in shownIds) return@mapNotNull null
            val meters = NearestStops.distanceMeters(from.latitude, from.longitude, lat, lon)
            if (meters > MAX_METERS) null else station to meters
        }.sortedBy { it.second }
        for ((station, meters) in candidates) {
            for (reason in reasonsOf(station)) {
                val mode = reason.line.mode
                if (mode !in MODES || HiddenModes.isHidden(mode, reason.line.id, hidden) || isReached(reason)) continue
                nearestByReason.putIfAbsent(reason, station to meters)
            }
        }
        // A line's nearest station can lie the wrong way for the rider's trip, so each line also gets
        // its nearest station on the far side of the rider (maintainer, 2026-09-25): more than
        // [OTHER_DIRECTION_DEGREES] round from the nearest one, seen from where the rider stands. A
        // National Rail route end already names its direction, so it keeps just its nearest station.
        val otherByReason = LinkedHashMap<Reason, Pair<IndexedStation, Double>>()
        for ((station, meters) in candidates) {
            for (reason in reasonsOf(station)) {
                if (reason.end != null || reason in otherByReason) continue
                val (nearest, _) = nearestByReason[reason] ?: continue
                if (placeOf(nearest).id == placeOf(station).id) continue
                if (angleBetween(bearing(from, nearest), bearing(from, station)) > OTHER_DIRECTION_DEGREES) {
                    otherByReason[reason] = station to meters
                }
            }
        }
        val offered = (nearestByReason.entries + otherByReason.entries)
            .map { it.key to it.value }
            .sortedBy { it.second.second }
        val picked = LinkedHashMap<String, Farther>()
        // The tube lines the cards show, and each place's lines, counted together: a place standing
        // for two tube lines shows both, and both count toward the cap.
        val tubeLines = HashSet<Line>()
        val linesByPlace = LinkedHashMap<String, LinkedHashSet<Line>>()
        for ((reason, nearest) in offered) {
            val (station, meters) = nearest
            val place = placeOf(station)
            val tube = reason.line.mode == "tube"
            if (tube && reason.line !in tubeLines && tubeLines.size >= MAX_TUBE_LINES) continue
            if (place.id !in picked) {
                if (picked.size >= MAX_BUTTONS) continue
                picked[place.id] = Farther(StationMatch(place.id, place.name, place.modes), meters)
            }
            // Every unreached line one of whose offered stations stands for the place, whether or
            // not that line was the one that earned it (an interchange adds several).
            if (tube) tubeLines += reason.line
            linesByPlace.getOrPut(place.id) { LinkedHashSet() } += reason.line
        }
        return picked.values.map { it.copy(lines = linesByPlace[it.station.id].orEmpty().toList()) }
    }

    // The compass bearing from [from] to [station], in degrees (a flat approximation, plenty at 3 mi).
    private fun bearing(from: Coordinates, station: IndexedStation): Double {
        val north = station.latitude!! - from.latitude
        val east = (station.longitude!! - from.longitude) * Math.cos(Math.toRadians(from.latitude))
        return Math.toDegrees(Math.atan2(east, north))
    }

    // The smaller angle between two bearings, 0 to 180 degrees.
    private fun angleBetween(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
