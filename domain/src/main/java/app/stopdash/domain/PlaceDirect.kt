package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant

/**
 * The trains and buses from the rider's stops that go straight to a place (SPEC *Direct to a place*):
 * a departure whose line's route calls at one of the stops a short walk from it ([PlaceStops]), judged
 * as a trip's leg is ([DirectTrips.filter]), so a departure whose route isn't known yet is left out and
 * flagged rather than guessed (SPEC principle 1). One row per line, from its nearest stop that meets
 * the rider's step-free level.
 */
object PlaceDirect {
    /** How many trains a row counts down to. */
    const val TRAINS = 3

    /** A line from [fromName] that reaches the place, its next [departures] soonest first. */
    data class Row(
        val lineId: String,
        val lineName: String,
        val mode: String,
        val fromId: String,
        val fromName: String,
        val departures: List<Departure>,
        // The stops near the place each of [departures] gets the rider to, in the same order, nearest
        // the place first: where a closure would leave that train's rider with nowhere to get off.
        val trainReaches: List<List<DirectTrips.End>> = emptyList(),
        // Per train, whether its [trainReaches] are alternatives (a fork it may take either way, so it
        // reaches only one of them, which can't be told) rather than stops on its one known way.
        val trainForks: List<Boolean> = emptyList(),
    ) {
        /** Every stop near the place any of these trains reaches. */
        val reaches: List<DirectTrips.End> get() = trainReaches.flatten().distinct()
    }

    /**
     * The [rows], nearest stop first, and why fewer may show than reach the place: a line's route
     * still loading ([pending]), or a departure or a station's step-free access that couldn't be
     * checked ([unresolved]), each departure whose route couldn't be told named in [misses] for the debug log.
     */
    data class Result(val rows: List<Row>, val pending: Boolean, val unresolved: Boolean, val misses: Set<RouteMiss> = emptySet())

    /**
     * The level [stepFree] needs at a platform (SPEC *Step-free*): Station to the platform; Fully onto
     * the train as well, a staff ramp included. Null for Any, which needs none.
     */
    private fun needed(stepFree: StepFree): StepFreeLevel? = when (stepFree) {
        StepFree.ANY -> null
        StepFree.STATION -> StepFreeLevel.PLATFORM
        StepFree.FULLY -> StepFreeLevel.RAMP
    }

    // Modes TfL describes no step-free platforms for: their stops are at the street.
    private val NO_PLATFORMS = setOf("bus", "replacement-bus", "coach", "river-bus", "river-tour")

    private fun atStreet(mode: String) = mode.lowercase() in NO_PLATFORMS

    /**
     * The lines in [stops] (their fresh arrivals) whose route reaches one of [ends] after boarding, each
     * once from its nearest stop by [distanceMeters], with its next [TRAINS] trains not yet gone at
     * [now]; rows nearest first, then soonest. [hidden] modes are left out unchecked.
     */
    @WorkerThread
    fun rows(
        stops: List<StopArrivals>,
        ends: List<DirectTrips.End>,
        sequences: Map<String, LineSequence?>,
        distanceMeters: Map<String, Double>,
        now: Instant,
        hidden: Set<String> = emptySet(),
        // The rider's step-free level for a trip's routes, and the stations' access to judge it by.
        stepFree: StepFree = StepFree.ANY,
        access: StepFreeAccess? = null,
    ): Result {
        if (ends.isEmpty()) return Result(emptyList(), pending = false, unresolved = false)
        val misses = LinkedHashSet<RouteMiss>()
        val hubs = stops.associate { it.stopId to it.hubId }
        val level = needed(stepFree)
        var unknownAccess = false
        val kept: List<StopArrivals>
        var pending: Boolean
        var unresolved: Boolean
        if (level == null) {
            val result = DirectTrips.filter(stops, ends, sequences, hubs, hidden)
            kept = result.stops
            pending = result.pending
            unresolved = result.unresolved
            misses += result.misses
        } else {
            // Each line judged only against the stops near the place it can get off at step-free, so a
            // train is kept only where the stop it reaches meets the level, not some other branch's.
            val parts = ArrayList<StopArrivals>()
            pending = false
            unresolved = false
            val modes = LinkedHashMap<String, String>()
            stops.forEach { stop -> stop.departures.forEach { modes.putIfAbsent(it.lineId, it.mode) } }
            for ((lineId, mode) in modes) {
                if (HiddenModes.isHidden(mode, lineId, hidden)) continue
                val lineStops = stops.map { stop -> stop.copy(departures = stop.departures.filter { it.lineId == lineId }, lines = emptyList()) }
                val lineEnds = if (atStreet(mode)) {
                    ends
                } else {
                    val levels = ends.associateWith { end -> access?.levelFor(end.id, lineId, mode) }
                    // A stop the table doesn't describe for this line: if a train gets there, that train
                    // went unchecked, never a definite miss.
                    val unknown = levels.filterValues { it == null }.keys.toList()
                    if (unknown.isNotEmpty()) {
                        val probe = DirectTrips.filter(lineStops, unknown, sequences, hubs, hidden)
                        if (probe.stops.any { it.departures.isNotEmpty() }) unknownAccess = true
                        // A route still loading, or one that failed, leaves those trains unchecked too.
                        pending = pending || probe.pending
                        unresolved = unresolved || probe.unresolved
                        misses += probe.misses
                    }
                    levels.filter { (_, at) -> at != null && at >= level }.keys.toList()
                }
                if (lineEnds.isEmpty()) continue
                val result = DirectTrips.filter(lineStops, lineEnds, sequences, hubs, hidden)
                parts += result.stops
                pending = pending || result.pending
                unresolved = unresolved || result.unresolved
                misses += result.misses
            }
            kept = parts
        }
        val distance = { stopId: String -> distanceMeters[stopId] ?: Double.MAX_VALUE }
        val byLine = LinkedHashMap<String, Row>()
        for (stop in kept.sortedBy { distance(it.stopId) }) {
            stop.departures
                .filter { !it.expectedArrival.isBefore(now) }
                .sortedBy { it.expectedArrival }
                .groupBy { it.lineId }
                .forEach { (lineId, trains) ->
                    if (lineId in byLine) return@forEach
                    val first = trains.first()
                    // The stop it leaves from must meet the level too; a farther stop on the line may.
                    if (level != null && !atStreet(first.mode)) {
                        val boarding = access?.levelFor(stop.stopId, lineId, first.mode)
                        if (boarding == null) unknownAccess = true
                        if (boarding == null || boarding < level) return@forEach
                    }
                    // Which of the stops near the place each train reaches, resolved before any step-free
                    // judgment: each train and stop checked alone ([DirectTrips.filter]) over the line's own
                    // route, so a branch's train is never credited with another branch's stop, and a stop the
                    // route lists is where it calls, a sibling id of it ([LineSequence.callingAt]) standing in
                    // only where the route lists none.
                    val onRoute = sequences[lineId]?.routes?.flatMapTo(HashSet()) { it.stopIds }.orEmpty()
                    fun meets(end: DirectTrips.End) =
                        level == null || atStreet(first.mode) || access?.levelFor(end.id, lineId, first.mode)?.let { it >= level } == true
                    // The stops near the place [train] reaches, and whether they're alternatives (a fork).
                    fun reachesOf(train: Departure): Pair<List<DirectTrips.End>, Boolean> {
                        val one = listOf(stop.copy(departures = listOf(train), lines = emptyList()))
                        val reached = ends.filter { end -> DirectTrips.filter(one, listOf(end), sequences, hubs, hidden).stops.any { it.departures.isNotEmpty() } }
                        if (reached.isEmpty()) {
                            // Kept against the stops together but none alone: its route can't be told (a fork it
                            // may take either way), so it may reach any of them on a way it may take, never a
                            // stop around the place it can't. Stood behind only where every one meets the
                            // step-free level; otherwise, or with none, said as unchecked.
                            // The ways this train may take from here ([RouteStops.candidatePaths]): only the
                            // stops after boarding on one of them, never another branch's. Over the route as
                            // [DirectTrips.filter] sees it ([DirectTrips.routeAt]), so a stop near the place the
                            // route lists under a sibling id still counts.
                            val ways = sequences[lineId]?.let { sequence ->
                                RouteStops.candidatePaths(
                                    DirectTrips.routeAt(sequence, stop.stopId, stop.hubId, stop.stopName, ends), stop.stopId, train.destination, train.branch,
                                    train.mode.equals("bus", ignoreCase = true), RouteStops.boundOf(train.platform),
                                    train.direction, train.destinationId, train.via,
                                )
                            }.orEmpty().flatMapTo(HashSet()) { it.drop(1) }
                            val possible = ends.filter { it.id in ways }
                            if (possible.isNotEmpty() && possible.all(::meets)) return possible to (possible.size > 1)
                            unknownAccess = true
                            return emptyList<DirectTrips.End>() to false
                        }
                        val called = reached.filter { it.id in onRoute }.ifEmpty { reached }
                        // Then, with a step-free level, only those that meet it: the rider can't get off at the rest.
                        return called.filter(::meets) to false
                    }
                    // With a step-free level, a train whose stops near the place all fall short isn't one the
                    // rider can take there: left out, as a train that doesn't go there is.
                    val candidates = trains.map { it to reachesOf(it) }
                    val usable = if (level == null || atStreet(first.mode)) candidates else candidates.filter { (_, reaches) -> reaches.first.isNotEmpty() }
                    if (usable.isEmpty()) return@forEach
                    val picked = usable.take(TRAINS)
                    val shown = picked.map { it.first }
                    val trainReaches = picked.map { it.second.first }
                    val trainForks = picked.map { it.second.second }
                    byLine[lineId] = Row(lineId, first.lineName, first.mode, stop.stopId, stop.stopName, shown, trainReaches, trainForks)
                }
        }
        val rows = byLine.values.sortedWith(compareBy({ distance(it.fromId) }, { it.departures.first().expectedArrival }))
        return Result(rows, pending, unresolved || unknownAccess, misses)
    }
}
