package app.stopdash.domain

/**
 * A ride's lines (SPEC *Trips with a change*): the Planner's own first, then every other line of the
 * same mode whose route runs from the ride's boarding stop to its getting-off stop. "Stop" is strict
 * (maintainer, 2026-09-27): the same stop, a road's two poles counting as one, never an interchange's
 * other stops, so walks and changes stay exactly as the Planner gave them. Each is shown beside the
 * Planner's line as an equal ([legs]). Only those also calling at the same stops in between ride the
 * same stretch, so only they share the ride's time on board and may time the route ([timed]) — and
 * then only while [checked].
 */
data class RideLines(val legs: List<TripLeg>, val timed: List<TripLeg>) {
    /** [legs] whose trains may be offered as catchable ([checked]): every use of a ride's trains goes through this. */
    fun running(statuses: Map<String, LineStatus>): List<TripLeg> = legs.filter { checked(it, legs.first(), statuses) }

    /** [timed] lines that may time the route ([checked]). */
    fun timedRunning(statuses: Map<String, LineStatus>): List<TripLeg> = timed.filter { checked(it, legs.first(), statuses) }

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
         * line ([planned]) always, as its status is weighed where the route is ranked; another line
         * only once its status is known ([statuses]) and it's running. One rule for every place that
         * uses another line's trains, so a line never checked, or suspended, is never passed off as a
         * way to go.
         */
        fun checked(line: TripLeg, planned: TripLeg, statuses: Map<String, LineStatus>): Boolean =
            line.lineId == planned.lineId ||
                (line.lineId in statuses && line.lineId !in TripTiming.notRunning(statuses.values))

        /**
         * Each ride of [routes] to its [RideLines]. A line is considered when it rides first somewhere
         * in the plan or when its trains are among the ride's boarding stop's [arrivals]; it counts once
         * its route ([sequences]) is loaded and gives exactly one run between the two stops. It boards
         * only at a stop whose arrivals the trip fetches (the ride's own, or its pair's [areaPoles]), so
         * no request is added for it. A [hidden] line is left out, as its routes are.
         */
        fun of(
            routes: List<TripRoute>,
            arrivals: Map<String, List<Departure>>,
            areaPoles: Map<String, List<String>>,
            sequences: Map<String, LineSequence?>,
            hidden: Set<String> = emptySet(),
        ): Map<TripLeg, RideLines> {
            val rides = routes.flatMap { it.rides }.distinct()
            // A stop pair ("490G…") the Planner's path names, or a pole: either is the stop, by its pair.
            val areas = HashMap<String, String>().apply { sequences.values.filterNotNull().forEach { putAll(it.stopAreas) } }
            fun stopOf(id: String) = areas[id] ?: id
            return rides.associateWith { ride ->
                val boarding = boardingStops(ride, areaPoles)
                val found = candidates(ride, rides, arrivals, boarding, hidden).mapNotNull { (lineId, lineName) ->
                    val sequence = sequences[lineId] ?: return@mapNotNull null
                    sameStops(ride, lineId, lineName, sequence, boarding)
                }
                if (found.isEmpty()) return@associateWith only(ride)
                // The stops between, by their pairs, less where the ride gets off.
                fun between(stops: List<String>) = stops.map(::stopOf).dropLastWhile { it == stopOf(ride.toId) || it == ride.toArea }
                val ridden = between(ride.path)
                RideLines(listOf(ride) + found, listOf(ride) + found.filter { between(it.path) == ridden })
            }
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
        // boarding stop to the ride's getting-off stop (or a pole of its pair); null unless the route
        // gives exactly one such run.
        private fun sameStops(
            ride: TripLeg,
            lineId: String,
            lineName: String,
            sequence: LineSequence,
            boarding: Set<String>,
        ): TripLeg? {
            // Each boarding stop as the route sees it, and the getting-off stop too: TfL can list a
            // station's departures under one id and route the line through a sibling
            // ([LineSequence.callingAt]), which is still the same stop.
            val found = boarding.flatMap { stop ->
                val seen = sequence.callingAt(stop).callingAt(ride.toId)
                fun alights(id: String) = id == ride.toId || (ride.toArea.isNotEmpty() && seen.stopAreas[id] == ride.toArea)
                seen.routes.flatMap { route ->
                    val ids = route.stopIds
                    ids.indices.filter { ids[it] == stop }.mapNotNull { i ->
                        val j = (i + 1 until ids.size).firstOrNull { alights(ids[it]) } ?: return@mapNotNull null
                        ids[i] to ids.subList(i + 1, j + 1)
                    }
                }
            }.distinct()
            val (board, on) = found.singleOrNull() ?: return null
            val off = on.last()
            return ride.copy(
                lineId = lineId,
                lineName = lineName.ifBlank { lineId },
                fromId = board,
                fromName = sequence.stopNames[board] ?: ride.fromName,
                toId = off,
                toName = sequence.stopNames[off] ?: ride.toName,
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
