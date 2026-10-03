package app.stopdash.domain

/**
 * The departures from a stop that call at another (SPEC *Finding stops → From… To…*): a trip's leg
 * boarding at Highgate and getting off at Euston keeps the Northern line trains whose path reaches
 * Euston and drops the rest. Like a starred journey's card ([Journeys.trains]), a departure is judged
 * by its own line's route ([RouteStops]); one whose route is still loading or failed, or whose path
 * the route can't resolve, is left out and flagged rather than guessed either way (SPEC principle 1).
 */
object DirectTrips {
    /** One of the destination station's stops: its TfL id, name and interchange (blank if none). */
    data class End(val id: String, val name: String, val hubId: String = "")

    /**
     * What a trip filter kept: the [stops] with only the departures that reach the destination (a
     * stop keeps its closure notice with none), and why an empty or short list may not be the whole
     * answer — a line's route still loading ([pending]), or a departure that couldn't be checked
     * ([unresolved]), each such departure named in [misses] for the debug log.
     */
    data class Result(
        val stops: List<StopArrivals>,
        val pending: Boolean,
        val unresolved: Boolean,
        val misses: Set<RouteMiss> = emptySet(),
    )

    /**
     * Keeps the departures in [stops] that call at one of [destination]'s stops after boarding,
     * each judged on its line's route in [sequences] (absent while loading, null when it failed).
     * [hubs] gives each origin stop's interchange, and [destination]'s stops carry theirs, so a
     * station listed under one id and routed through a sibling is matched ([LineSequence.callingAt]).
     * A stop's lines are kept only where their route reaches the destination, so a status row
     * (a suspension) shows for the lines that matter and not for the rest.
     */
    fun filter(
        stops: List<StopArrivals>,
        destination: List<End>,
        sequences: Map<String, LineSequence?>,
        hubs: Map<String, String> = emptyMap(),
        // Modes the rider has hidden: their services are left out without being checked, so a
        // hidden bus's route loading or failing never holds up or caveats a shown tube trip.
        hidden: Set<String> = emptySet(),
    ): Result {
        val destinationIds = destination.mapTo(HashSet()) { it.id }
        var pending = false
        var unresolved = false
        val misses = LinkedHashSet<RouteMiss>()
        // A line's route as seen from [stop] and the destination's stops, once per stop and line.
        fun sequenceAt(stop: StopArrivals, lineId: String): LineSequence? {
            var sequence = sequences[lineId] ?: return null
            sequence = sequence.knowing(stop.stopId, hubs[stop.stopId] ?: stop.hubId, stop.stopName).callingAt(stop.stopId)
            for (end in destination) sequence = sequence.knowing(end.id, end.hubId, end.name).callingAt(end.id)
            return sequence
        }
        val kept = stops.map { stop ->
            // The origin itself is no destination: From and To the same station is no trip.
            if (stop.stopId in destinationIds) {
                return@map stop.copy(departures = emptyList(), lines = emptyList(), disruptions = emptyList())
            }
            val routes = HashMap<String, LineSequence?>()
            fun routeOf(lineId: String): LineSequence? = routes.getOrPut(lineId) { sequenceAt(stop, lineId) }
            val departures = stop.departures.filter { departure ->
                val lineId = departure.lineId
                when {
                    HiddenModes.isHidden(departure.mode, lineId, hidden) -> false
                    // No line to follow: it may well call there, so never a silent "no".
                    lineId.isBlank() -> {
                        unresolved = true
                        misses += RouteMiss(lineId, stop.stopId, RouteStops.Resolution.NoLine)
                        false
                    }
                    lineId !in sequences -> {
                        pending = true
                        false
                    }
                    else -> {
                        val sequence = routeOf(lineId)
                        val bus = departure.mode.equals("bus", ignoreCase = true)
                        val bound = RouteStops.boundOf(departure.platform)
                        val resolution = sequence?.let {
                            RouteStops.resolve(
                                it, stop.stopId, departure.destination, departure.branch, lineId, bus, bound, departure.direction,
                                departure.destinationId,
                            )
                        }
                        // One path can't be told (no destination yet, or two ways that match it):
                        // still an answer when every way it may take agrees.
                        val agreed = if (resolution == null || resolution is RouteStops.Resolution.Found) {
                            null
                        } else {
                            RouteStops.reaches(
                                sequence!!, stop.stopId, departure.destination, departure.branch, destinationIds, bus, bound,
                                departure.direction, departure.destinationId,
                            )
                        }
                        when {
                            resolution is RouteStops.Resolution.Found -> resolution.stops.drop(1).any { it.id in destinationIds }
                            // Ending here, it goes nowhere: a sure "no", not a gap in the check.
                            resolution == RouteStops.Resolution.EndsHere -> false
                            agreed != null -> agreed
                            else -> {
                                unresolved = true
                                // A failed route (null) is logged by its fetch; a path that won't
                                // resolve is logged nowhere else, so it's named here.
                                if (resolution != null) misses += RouteMiss(lineId, stop.stopId, resolution)
                                false
                            }
                        }
                    }
                }
            }
            val lines = stop.lines.filter { line ->
                if (HiddenModes.isHidden(line, hidden)) return@filter false
                if (line.id !in sequences) {
                    pending = true
                    return@filter false
                }
                // A failed route can't say whether this line's status (a suspension) matters here.
                val sequence = routeOf(line.id) ?: run {
                    unresolved = true
                    return@filter false
                }
                reaches(sequence, stop.stopId, destinationIds)
            }
            stop.copy(departures = departures, lines = lines)
        }.filter { it.departures.isNotEmpty() || it.lines.isNotEmpty() || it.disruptions.isNotEmpty() }
        return Result(kept, pending, unresolved, misses)
    }

    /** How far from the rider a stop still counts as "here" for To… from the near-me list: 0.2 mi. */
    const val ORIGIN_RADIUS_METERS = 320.0

    /**
     * The stops a To… from the near-me list starts from (SPEC *Finding stops → From… To…*): every
     * stop the list is showing ([shown], a "More" reveal included), plus any stop the nearby lookup
     * found within [ORIGIN_RADIUS_METERS] of the rider ([distances], by stop id) — a pole across the
     * road the list folded away still boards the rider's trip. With neither, the nearest stop, so
     * the page never starts from nothing while something was found. In [distances]' nearest-first
     * order, then any shown stop without a known distance.
     */
    fun originIds(shown: Collection<String>, distances: Map<String, Double>): List<String> {
        val byDistance = distances.entries.sortedBy { it.value }
        val near = byDistance.filter { it.key in shown || it.value <= ORIGIN_RADIUS_METERS }.map { it.key }
        val rest = shown.filter { it !in distances }
        val ids = (near + rest).distinct()
        return ids.ifEmpty { listOfNotNull(byDistance.firstOrNull()?.key) }
    }

    /**
     * [this] also knowing [stopId]'s interchange and name, where the station index didn't already
     * supply them ([LineSequence.withStations]) — a searched station TfL returned outside the index —
     * so [LineSequence.callingAt] can place it through a sibling id its routes call at.
     */
    private fun LineSequence.knowing(stopId: String, hubId: String, name: String): LineSequence =
        if (hubId.isBlank() || stopHubs.containsKey(stopId)) {
            this
        } else {
            copy(
                stopHubs = stopHubs + (stopId to hubId),
                stopNames = if (stopNames.containsKey(stopId)) stopNames else stopNames + (stopId to cleanStopName(name)),
            )
        }

    /** Whether some route of [sequence] calls at [originId] and later at one of [destinationIds]. */
    private fun reaches(sequence: LineSequence, originId: String, destinationIds: Set<String>): Boolean =
        sequence.routes.any { route ->
            val i = route.stopIds.indexOf(originId)
            i >= 0 && route.stopIds.drop(i + 1).any { it in destinationIds }
        }
}
