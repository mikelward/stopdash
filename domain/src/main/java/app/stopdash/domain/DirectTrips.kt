package app.stopdash.domain

import androidx.annotation.WorkerThread
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
    @WorkerThread
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
        fun sequenceAt(stop: StopArrivals, lineId: String): LineSequence? =
            sequences[lineId]?.let { routeAt(it, stop.stopId, hubs[stop.stopId] ?: stop.hubId, stop.stopName, destination) }
        val kept = stops.map { stop ->
            // The origin itself is no destination: From and To the same station is no trip.
            if (stop.stopId in destinationIds) {
                return@map stop.copy(departures = emptyList(), lines = emptyList(), disruptions = emptyList())
            }
            val routes = HashMap<String, LineSequence?>()
            fun routeOf(lineId: String): LineSequence? = routes.getOrPut(lineId) { sequenceAt(stop, lineId) }
            val departures = stop.departures.filter { departure ->
                val judged = judgeDeparture(departure, stop.stopId, destinationIds, sequences, hidden, ::routeOf)
                if (judged.pending) pending = true
                if (judged.unresolved) unresolved = true
                judged.miss?.let { misses += it }
                judged.kept
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

    /**
     * What [filter] makes of one departure: whether it's [kept], or left out because its line's route
     * is still loading ([pending]) or it couldn't be checked ([unresolved], named in [miss] when there's
     * a path to name). A departure left out for neither is a sure "no", or of a hidden mode.
     */
    data class Judged(val kept: Boolean, val pending: Boolean = false, val unresolved: Boolean = false, val miss: RouteMiss? = null)

    /**
     * [filter]'s verdict on each of [stop]'s departures, in their order, each judged on its own as
     * [filter] judges it, with [destination] and each line's route at [stop] worked out once for them
     * all: for a caller that needs to know which departure was kept, loading or unchecked.
     */
    @WorkerThread
    fun judgeEach(
        stop: StopArrivals,
        destination: List<End>,
        sequences: Map<String, LineSequence?>,
        hubs: Map<String, String> = emptyMap(),
        hidden: Set<String> = emptySet(),
    ): List<Judged> {
        val destinationIds = destination.mapTo(HashSet()) { it.id }
        // The origin itself is no destination: From and To the same station is no trip.
        if (stop.stopId in destinationIds) return stop.departures.map { Judged(kept = false) }
        val routes = HashMap<String, LineSequence?>()
        fun routeOf(lineId: String): LineSequence? = routes.getOrPut(lineId) {
            sequences[lineId]?.let { routeAt(it, stop.stopId, hubs[stop.stopId] ?: stop.hubId, stop.stopName, destination) }
        }
        return stop.departures.map { judgeDeparture(it, stop.stopId, destinationIds, sequences, hidden, ::routeOf) }
    }

    /** [departure] from [stopId] judged as [filter] judges it, its line's route from [routeOf]. */
    private fun judgeDeparture(
        departure: Departure,
        stopId: String,
        destinationIds: Set<String>,
        sequences: Map<String, LineSequence?>,
        hidden: Set<String>,
        routeOf: (String) -> LineSequence?,
    ): Judged {
        val lineId = departure.lineId
        return when {
            HiddenModes.isHidden(departure.mode, lineId, hidden) -> Judged(kept = false)
            // No line to follow: it may well call there, so never a silent "no".
            lineId.isBlank() -> Judged(kept = false, unresolved = true, miss = RouteMiss(lineId, stopId, RouteStops.Resolution.NoLine, departure.destination))
            lineId !in sequences -> Judged(kept = false, pending = true)
            else -> when (val verdict = judge(departure, stopId, routeOf(lineId), destinationIds)) {
                Verdict.Reaches -> Judged(kept = true)
                Verdict.Misses -> Judged(kept = false)
                is Verdict.Unknown -> Judged(kept = false, unresolved = true, miss = verdict.miss)
            }
        }
    }

    /** A departure judged on its line's route ([judge]). */
    sealed interface Verdict {
        /** Its path calls at the destination after boarding. */
        data object Reaches : Verdict

        /** Its path doesn't: a sure "no" (one ending at the boarding stop included). */
        data object Misses : Verdict

        /**
         * Its path can't be told: the route failed, or doesn't resolve to one path, and the ways it may
         * take disagree. A path that won't resolve is named in [miss] for the debug log; a failed route
         * (no [miss]) is logged by its fetch.
         */
        data class Unknown(val miss: RouteMiss?) : Verdict
    }

    /**
     * [sequence] as seen from [stopId] and [destination]'s stops, each matched through its interchange
     * ([LineSequence.callingAt]): what [judge] judges a departure from that stop on. Worked out once per
     * stop and line, then shared by its departures.
     */
    @WorkerThread
    fun routeAt(sequence: LineSequence, stopId: String, hubId: String, stopName: String, destination: List<End>): LineSequence {
        var route = sequence.knowing(stopId, hubId, stopName).callingAt(stopId)
        for (end in destination) route = route.knowing(end.id, end.hubId, end.name).callingAt(end.id)
        return route
    }

    /**
     * Whether [departure], boarding at [stopId], calls at one of [destinationIds] on its line's [route]
     * ([routeAt]; null when the route failed). Depends on where the departure is going, not when, so a
     * verdict holds for every later prediction of the same service.
     */
    @WorkerThread
    fun judge(departure: Departure, stopId: String, route: LineSequence?, destinationIds: Set<String>): Verdict {
        val lineId = departure.lineId
        val bus = departure.mode.equals("bus", ignoreCase = true)
        val bound = RouteStops.boundOf(departure.platform)
        val resolution = route?.let {
            RouteStops.resolve(
                it, stopId, departure.destination, departure.branch, lineId, bus, bound, departure.direction,
                departure.destinationId, departure.via,
            )
        }
        // One path can't be told (no destination yet, or two ways that match it): still an answer when
        // every way it may take agrees.
        val agreed = if (resolution == null || resolution is RouteStops.Resolution.Found) {
            null
        } else {
            RouteStops.reaches(
                route!!, stopId, departure.destination, departure.branch, destinationIds, bus, bound,
                departure.direction, departure.destinationId, departure.via,
            )
        }
        return when {
            resolution is RouteStops.Resolution.Found ->
                if (resolution.stops.drop(1).any { it.id in destinationIds }) Verdict.Reaches else Verdict.Misses
            // Ending here, it goes nowhere: a sure "no", not a gap in the check.
            resolution == RouteStops.Resolution.EndsHere -> Verdict.Misses
            agreed != null -> if (agreed) Verdict.Reaches else Verdict.Misses
            else -> Verdict.Unknown(resolution?.let { RouteMiss(lineId, stopId, it, departure.destination) })
        }
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
