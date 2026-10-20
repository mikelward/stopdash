package app.stopdash.domain

/**
 * A ride's lines (SPEC *Trips with a change*): the Planner's own first, then every other line of the
 * same mode whose route runs from the ride's boarding stop to its getting-off stop. "Stop" is strict
 * (maintainer, 2026-09-27): the same stop, a road's two poles counting as one, never an interchange's
 * other stops, so walks and changes stay exactly as the Planner gave them. The one exception is a trip's
 * last ride arriving by hub ([LegEnd.offIds]): there the destination is its hubs, so a line reaching
 * another of their stations gets the rider there too (dev-docs/station-resolution.md, phase 2). Each is shown beside the
 * Planner's line as an equal ([legs]). Only those also calling at the same stops in between ride the
 * same stretch, so only they share the ride's time on board and may time the route ([timed]) — and
 * then only while [checked].
 */
data class RideLines(val legs: List<TripLeg>, val timed: List<TripLeg>) {
    /** [legs] whose trains may be offered as catchable ([vouched]): every use of a ride's trains goes through this. */
    fun running(statuses: Map<String, LineStatus>, stopsOpen: (TripLeg) -> Boolean): List<TripLeg> =
        legs.filter { vouched(it, statuses, stopsOpen) }

    /** [timed] lines that may time the route ([vouched]). */
    fun timedRunning(statuses: Map<String, LineStatus>, stopsOpen: (TripLeg) -> Boolean): List<TripLeg> =
        timed.filter { vouched(it, statuses, stopsOpen) }

    /**
     * Whether a line other than the Planner's may take and time the ride: one of [timed], [checked]
     * as running. Then the route is ranked by that line, not by the Planner line's status (Codex on
     * #309).
     */
    fun othersTime(statuses: Map<String, LineStatus>, stopsOpen: (TripLeg) -> Boolean): Boolean =
        timed.any { it.lineId != legs.first().lineId && checked(it, legs.first(), statuses, stopsOpen) }

    /**
     * Whether [line], one of [legs], may be offered as catchable and time the ride: [checked], with
     * one exception. The Planner's own line is exempt from its status only while that status ranks
     * the route. Once another line keeps the ride usable ([othersTime]), the route no longer answers
     * to it, so the line does here: a suspended or unchecked line's leftover predictions mustn't
     * time a route another line keeps usable (Codex on #382).
     */
    fun vouched(line: TripLeg, statuses: Map<String, LineStatus>, stopsOpen: (TripLeg) -> Boolean): Boolean {
        val planned = legs.first()
        if (!checked(line, planned, statuses, stopsOpen)) return false
        return line.lineId != planned.lineId || runs(planned.lineId, statuses) || !othersTime(statuses, stopsOpen)
    }

    /**
     * A route [through] makes: [route], one ride fewer than [from], whose two rides from leg [at] it
     * rides as one (its leg [at]).
     */
    data class Through(val route: TripRoute, val from: TripRoute, val at: Int)

    companion object {
        fun only(leg: TripLeg) = RideLines(listOf(leg), listOf(leg))

        /**
         * The other lines of [lines] with no status known ([statuses]): shown, but neither catchable nor
         * timing a route ([checked]) — once a check has finished, the trip says it couldn't check them.
         */
        fun unchecked(lines: Collection<RideLines>, statuses: Map<String, LineStatus>): Set<String> =
            lines.flatMapTo(HashSet()) { ride -> ride.legs.drop(1).map { it.lineId }.filterNot { it in statuses } }

        /**
         * Whether [line]'s trains may be offered as catchable and time a route: the Planner's own
         * line ([planned]) always, as its status and stops are weighed where the route is ranked;
         * another line only once its status is known ([statuses]) and it's running, and the stops it
         * boards and gets off at, its own poles, are checked open ([stopsOpen], [TripClosures.opens]).
         * One rule for every place that uses another line's trains, so a line never checked, suspended,
         * or from a closed stop is never passed off as a way to go. A ride's own [vouched] adds the
         * case where the route stops weighing the Planner's status.
         */
        fun checked(line: TripLeg, planned: TripLeg, statuses: Map<String, LineStatus>, stopsOpen: (TripLeg) -> Boolean): Boolean =
            line.lineId == planned.lineId || (runs(line.lineId, statuses) && stopsOpen(line))

        // Whether [lineId]'s status is known ([statuses]) and says it's running.
        private fun runs(lineId: String, statuses: Map<String, LineStatus>): Boolean =
            lineId in statuses && lineId !in TripTiming.notRunning(statuses.values)

        /**
         * Each ride of [routes], paired with where it gets off ([ends]: [RideEnds.ends], or [LegEnd.planned]),
         * to its [RideLines]. Another line counts for a last ride arriving by hub where it reaches the
         * destination's hubs, at another station of them too, cut and named at its first call there. Keyed on the pair, so a leg last in
         * one route and in the middle of another is two keys (dev-docs/station-resolution.md, phase 2).
         * A line is considered when it rides first somewhere in the plan or when its trains are among
         * the ride's boarding stop's [arrivals]; it counts once its route ([sequences]) is loaded and
         * gives exactly one run between the two stops. It boards only at a stop whose arrivals the trip
         * fetches (the ride's own, or its pair's [areaPoles]), so no request is added for it. A [hidden]
         * line is left out, as its routes are.
         */
        fun of(
            routes: List<TripRoute>,
            ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>>,
            arrivals: Map<String, List<Departure>>,
            areaPoles: Map<String, List<String>>,
            sequences: Map<String, LineSequence?>,
            hidden: Set<String> = emptySet(),
        ): Map<Pair<TripLeg, LegEnd>, RideLines> {
            val ended = routes.flatMap(ends).distinct()
            val rides = ended.map { it.first }.distinct()
            // A stop pair ("490G…") the Planner's path names, or a pole: either is the stop, by its pair.
            val areas = HashMap<String, String>().apply { sequences.values.filterNotNull().forEach { putAll(it.stopAreas) } }
            fun stopOf(id: String) = areas[id] ?: id
            return ended.associateWith { (ride, end) ->
                val boarding = boardingStops(ride, areaPoles)
                val found = candidates(ride, rides, arrivals, boarding, hidden).mapNotNull { (lineId, lineName) ->
                    val sequence = sequences[lineId] ?: return@mapNotNull null
                    sameStops(ride, end, lineId, lineName, sequence, boarding)
                }
                if (found.isEmpty()) return@associateWith only(ride)
                // The stops between, by their pairs, less where the ride gets off: by the planned stop, or the id of
                // its station a line gets off at ([sameStops] keeps the one the train calls at; Codex on #1003).
                fun between(stops: List<String>, offId: String) =
                    stops.map(::stopOf).dropLastWhile { it == stopOf(ride.toId) || it == stopOf(offId) || it == ride.toArea }
                val ridden = between(ride.path, ride.toId)
                // A line a last ride arriving by hub takes to another of the destination's stations rides to
                // another place, so it never shares the ride's time on board: shown, never timing it.
                fun elsewhere(line: TripLeg) = end.level == LegEnd.Level.HUB && stopOf(line.toId) != stopOf(ride.toId) &&
                    (ride.toArea.isEmpty() || areas[line.toId] != ride.toArea) &&
                    sequences[line.lineId]?.sameStationId(line.toId, ride.toId) != true
                RideLines(listOf(ride) + found, listOf(ride) + found.filter { between(it.path, it.toId) == ridden && !elsewhere(it) })
            }
        }

        /**
         * The routes [routes] offer with a change left out (maintainer, 2026-09-28): where a ride is
         * followed straight away by another of the same mode, and a line boarding at the first ride's
         * stop runs on to where the second gets off, one ride on that line replaces the two. On the
         * Victoria line one stop and then the Northern line, the Northern line from the first stop is
         * that ride, a train that goes the whole way with no change. Its trains are checked on the
         * way like any ride's, so one for another branch is left out.
         *
         * The line is one [of] would consider (among the plan's rides, or at the boarding stop's
         * [arrivals]; the ride's own too), counted once its route ([sequences]) gives exactly one run
         * between the two stops. A walk between the rides is a change of station, so it stays. The
         * ride's Planner times are the two rides' time on board without the change between: an
         * estimate the live trains replace. Each route with one pair of rides joined; one already
         * among [routes] is left to the Planner's copy. The line gets off where the second ride does
         * ([ends] pairs each route's rides with where they get off, as [of]'s do).
         */
        fun through(
            routes: List<TripRoute>,
            ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>>,
            arrivals: Map<String, List<Departure>>,
            areaPoles: Map<String, List<String>>,
            sequences: Map<String, LineSequence?>,
            hidden: Set<String> = emptySet(),
        ): List<TripRoute> = throughWays(routes, ends, arrivals, areaPoles, sequences, hidden).map { it.route }

        /** [through]'s routes, each with the route of [routes] it's made from ([Through]). */
        fun throughWays(
            routes: List<TripRoute>,
            ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>>,
            arrivals: Map<String, List<Departure>>,
            areaPoles: Map<String, List<String>>,
            sequences: Map<String, LineSequence?>,
            hidden: Set<String> = emptySet(),
        ): List<Through> {
            val rides = routes.flatMap { it.rides }.distinct()
            fun key(route: TripRoute) = route.legs.map { listOf(it.mode, it.lineId, it.fromArea.ifEmpty { it.fromId }, it.toArea.ifEmpty { it.toId }) }
            val known = routes.mapTo(HashSet(), ::key)
            val found = LinkedHashMap<List<List<String>>, Through>()
            for (route in routes) {
                // Each ride's end, by its place among the route's rides: walks get none.
                val rideEnds = ends(route).map { it.second }
                var rideIndex = -1
                for (index in 0 until route.legs.size - 1) {
                    val first = route.legs[index]
                    val second = route.legs[index + 1]
                    if (!first.isWalk) rideIndex++
                    if (first.isWalk || second.isWalk || !first.mode.equals(second.mode, ignoreCase = true)) continue
                    val end = rideEnds.getOrNull(rideIndex + 1) ?: continue
                    val boarding = boardingStops(first, areaPoles)
                    val lines = (candidates(first, rides, arrivals, boarding, hidden) + (first.lineId to first.lineName))
                        .filter { it.first.isNotBlank() }
                        .distinctBy { it.first }
                    for ((lineId, lineName) in lines) {
                        if (HiddenModes.isHidden(first.mode, lineId, hidden)) continue
                        val sequence = sequences[lineId] ?: continue
                        val run = runTo(first, second, end, lineId, lineName, sequence, boarding) ?: continue
                        val joinedRide = run.copy(
                            arrival = first.departure + first.run + second.run,
                            changeAfter = second.changeAfter,
                        )
                        val joined = TripRoute(route.legs.take(index) + joinedRide + route.legs.drop(index + 2))
                        val joinedKey = key(joined)
                        if (joinedKey !in known) found.putIfAbsent(joinedKey, Through(joined, route, index))
                    }
                }
            }
            return found.values.toList()
        }

        /** The other lines [of] considers for [routes]: those among the rides' boarding stops' [arrivals]. */
        fun lineIds(
            routes: List<TripRoute>,
            arrivals: Map<String, List<Departure>>,
            areaPoles: Map<String, List<String>>,
            hidden: Set<String> = emptySet(),
        ): List<String> {
            val rides = routes.flatMap { it.rides }.distinct()
            return rides.flatMap { ride -> candidates(ride, rides, arrivals, boardingStops(ride, areaPoles), hidden).map { it.first } }.distinct()
        }

        /**
         * The other lines [of] considers for [ride] alone, of [routes]' rides ([lineIds] gives every
         * ride's): those of its mode in the plan or among its boarding stops' [arrivals]. A check of a
         * line outside these bears on another ride, never this one.
         */
        fun candidateIds(
            ride: TripLeg,
            routes: List<TripRoute>,
            arrivals: Map<String, List<Departure>>,
            areaPoles: Map<String, List<String>>,
            hidden: Set<String> = emptySet(),
        ): List<String> =
            candidates(ride, routes.flatMap { it.rides }.distinct(), arrivals, boardingStops(ride, areaPoles), hidden).map { it.first }

        /**
         * Of [poles], the poles of [ride]'s stop pair (the other side of the road, or another stand),
         * those besides its own whose board may list another line's train taking the ride ([of]), so a
         * trip that reads only their boards reads no more than it needs: a pole serving a line of the
         * ride's mode, not the ride's own nor [hidden], whose route ([sequences]) runs the ride from
         * that pole to where it gets off ([end], as [of] is given it), or whose route isn't known (none
         * in [sequences]). A pole TfL lists no lines for may serve one, so it's read too.
         */
        fun polesToRead(
            ride: TripLeg,
            end: LegEnd,
            poles: List<StopLocation>,
            sequences: Map<String, LineSequence?>,
            hidden: Set<String> = emptySet(),
        ): List<StopLocation> = poles.filter { pole ->
            pole.id != ride.fromId && (pole.lines.isEmpty() || pole.lines.any { line ->
                mayTake(ride, line, hidden) && sequences[line.id]?.let { runTo(ride, ride, end, line.id, line.name, it, setOf(pole.id)) != null } ?: true
            })
        }

        /** The lines of [poles] whose routes [polesToRead] goes by: those that may take [ride] at all. */
        fun pairLineIds(ride: TripLeg, poles: List<StopLocation>, hidden: Set<String> = emptySet()): List<String> =
            poles.filter { it.id != ride.fromId }.flatMap { it.lines }.filter { mayTake(ride, it, hidden) }.map { it.id }.distinct()

        // Whether [line] may take [ride] from another pole of its pair: of its mode (TfL may give none),
        // not its own line, and not [hidden].
        private fun mayTake(ride: TripLeg, line: LineRef, hidden: Set<String>): Boolean =
            line.id.isNotBlank() && line.id != ride.lineId && !HiddenModes.isHidden(ride.mode, line.id, hidden) &&
                (line.mode.isBlank() || line.mode.equals(ride.mode, ignoreCase = true))

        // The stops whose arrivals the trip fetches for [ride]: its own, and every pole of its stop pair.
        private fun boardingStops(ride: TripLeg, areaPoles: Map<String, List<String>>): Set<String> =
            setOf(ride.fromId) + areaPoles[ride.fromArea].orEmpty()

        // The other lines of [ride]'s mode, by id and name: in the plan's rides, or at its boarding stops.
        private fun candidates(
            ride: TripLeg,
            rides: List<TripLeg>,
            arrivals: Map<String, List<Departure>>,
            boarding: Set<String>,
            hidden: Set<String>,
        ): List<Pair<String, String>> {
            val planned = rides.map { it.mode to (it.lineId to it.lineName) }
            val live = boarding.flatMap { stop -> arrivals[stop].orEmpty().map { it.mode to (it.lineId to it.lineName) } }
            return (planned + live)
                .filter { (mode, line) -> mode.equals(ride.mode, ignoreCase = true) && line.first.isNotBlank() && line.first != ride.lineId }
                .map { it.second }
                .distinctBy { it.first }
                .filterNot { (lineId, _) -> HiddenModes.isHidden(ride.mode, lineId, hidden) }
        }

        // [ride] by line [lineId] as its route ([sequence]) runs it: from a fetched pole of the ride's
        // boarding stop to the ride's getting-off stop (or a pole of its pair), getting off at [end];
        // null unless the route gives exactly one such run.
        private fun sameStops(
            ride: TripLeg,
            end: LegEnd,
            lineId: String,
            lineName: String,
            sequence: LineSequence,
            boarding: Set<String>,
        ): TripLeg? = runTo(ride, ride, end, lineId, lineName, sequence, boarding)

        // [ride] by line [lineId] from a fetched pole of [ride]'s boarding stop to [to]'s getting-off
        // stop (or a pole of its pair), as its route ([sequence]) runs it; null unless the route gives
        // exactly one such run. [end] is where [to] gets off ([LegEnd]): every caller supplies one. A last
        // ride arriving by hub ([LegEnd.offIds]) gets off at the run's first call anywhere in the
        // destination's hubs, another station of them included, and is named there; every other at [to]'s
        // Planner stop.
        private fun runTo(
            ride: TripLeg,
            to: TripLeg,
            end: LegEnd,
            lineId: String,
            lineName: String,
            sequence: LineSequence,
            boarding: Set<String>,
        ): TripLeg? {
            // Each boarding stop as the route sees it: TfL can list a station's departures under one id and
            // route the line through a sibling ([LineSequence.callingAt]), which is still the same stop. Where
            // it gets off keeps the id the route calls at, the one its arrival is predicted at: the id the
            // ride names, failing that on every run another id of its station ([LineSequence.sameStationId]),
            // only failing it, since a station's code also covers platforms runs call at apart (Liverpool Street's).
            val offIds = end.offIds(to)
            // A hub end already holds every pole of its stops; the Planner's raw area may be one the model
            // split, its other poles at a stop outside the hub, so it isn't matched there.
            val area = to.toArea.takeIf { it.isNotEmpty() && (end.level != LegEnd.Level.HUB || end.ids.isEmpty()) }
            fun runs(station: Boolean) = boarding.flatMap { stop ->
                val seen = sequence.callingAt(stop)
                fun alights(id: String) = id in offIds || (area != null && seen.stopAreas[id] == area) ||
                    (station && seen.sameStationId(id, to.toId))
                seen.routes.flatMap { route ->
                    val ids = route.stopIds
                    ids.indices.filter { ids[it] == stop }.mapNotNull { i ->
                        val j = (i + 1 until ids.size).firstOrNull { alights(ids[it]) } ?: return@mapNotNull null
                        ids[i] to ids.subList(i + 1, j + 1)
                    }
                }
            }.distinct()
            val found = runs(station = false).ifEmpty { runs(station = true) }
            val (board, on) = found.singleOrNull() ?: return null
            val off = on.last()
            return ride.copy(
                lineId = lineId,
                lineName = lineName.ifBlank { lineId },
                fromId = board,
                fromName = sequence.stopNames[board] ?: ride.fromName,
                toId = off,
                toName = sequence.stopNames[off] ?: to.toName,
                path = on,
                pathNames = on.map { sequence.stopNames[it].orEmpty() },
                // The line's own terminus isn't the Planner's: its trains are checked on its own route.
                headings = emptyList(),
                // Already at the poles its route uses: re-placing it by the Planner's poles would undo that.
                fromArea = "",
                toArea = "",
            )
        }
    }
}
