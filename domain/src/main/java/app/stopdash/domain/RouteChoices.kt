package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * One way a line runs on from a stop, as a chip atop its route page offers it (maintainer, 2026-10-21): to
 * its [destination], by the via TfL names its route by ([branch]), calling at [path] from the boarding stop
 * through its end. [direction] is TfL's way it runs, blank where not known. [live] is its soonest train at the
 * stop and the row holding it, where one is on it; a choice with none still lists its stops, from the line's
 * routes.
 *
 * [path] is empty where only a live train names the way and its stops can't be told ([RouteStops.resolve]).
 */
data class RouteChoice(
    val destination: String,
    val branch: String?,
    val path: List<String>,
    val direction: String,
    val live: LiveRoute? = null,
    // Shown with the destination, where another choice ends there too by another via.
    val showBranch: Boolean = false,
    // The stop it calls at that another choice to the same place by the same via doesn't, the first of them:
    // what tells the two apart. Null where its destination (and branch) do.
    val parting: String? = null,
    // For a live train TfL names no destination for ("Check Front of Train"), the farthest stop every way it can run
    // from here reaches before they part: how far it goes at least, a guess (maintainer, 2026-10-21).
    val atLeast: String? = null,
    // Where a National Rail choice goes, the station its stopping patterns end at ([RouteChoices.of]): what it's
    // known by, as its [path] is only one of them, and its train another's from one refresh to the next.
    val goesTo: String? = null,
) {
    /**
     * Which choice this is, for the page to hold across a refresh: where it goes, for National Rail; else its way and
     * stops, else its live route.
     */
    val key: String = when {
        goesTo != null -> "$direction|to|$goesTo"
        path.isEmpty() -> "$direction|live|$destination|${branch.orEmpty()}${atLeast?.let { "|$it" }.orEmpty()}"
        else -> "$direction|${path.joinToString(",")}"
    }
}

/** A choice's soonest train at the stop: the row it's listed in and the route a tap on that row's card follows. */
data class LiveRoute(val row: DepartureRow, val focus: RouteFocus)

/** A route page's [choices], and the one its own train runs on ([current]), null where it has none. */
data class RouteChoices(val choices: List<RouteChoice>, val current: Int?) {
    companion object {
        val NONE = RouteChoices(emptyList(), null)

        /**
         * The ways [row]'s line runs on from its stop (maintainer, 2026-10-21): each route of [sequence] calling
         * there, from the stop through its end, one choice per distinct path; and each route its trains at the
         * stop run, among [row] and [rows] (any way, any platform), on the choice whose path the train's is, or
         * one of its own where none is (a short working). Both ways the line runs are offered, [first] (the way
         * the page opened on) first, each in the order TfL lists its routes, so neither a refresh nor a move to
         * another of them reorders them; ways only a live train names follow, by name. Within each, the ways
         * calling at a stop or stop area in [reaching] after this one come first: a favorite ride's far end, on a
         * page opened from its card (maintainer, 2026-10-21). [current] is the choice the page's train is on: the
         * one [focus] follows ([followedDeparture]), else, on a page with no train, the only choice where there's
         * one.
         */
        @WorkerThread
        fun of(
            sequence: LineSequence,
            row: DepartureRow,
            rows: List<DepartureRow>,
            focus: RouteFocus?,
            topology: RouteTopology = RouteTopology.EMPTY,
            first: String = row.direction,
            reaching: Set<String> = emptySet(),
        ): RouteChoices {
            val lineId = row.lineId
            val stopId = row.stopId
            if (lineId.isBlank()) return NONE
            // A National Rail line's routes are its stopping patterns, a dozen from one platform: chips by where they go.
            if (nationalRail(row)) return ofRail(sequence, row, rows, focus, topology, first, reaching)
            val routes = sequence.eachCallingAt(stopId)
            val bus = isBus(row)
            val choices = ArrayList<RouteChoice>()
            // The line's routes from here: one choice per path ([merged]).
            val paths = ArrayList<Triple<List<String>, LineRoute, String>>()
            for (route in routes.routes) {
                val stops = route.stopIds
                for (i in stops.indices) {
                    if (stops[i] != stopId || i == stops.lastIndex) continue
                    val path = stops.subList(i, stops.size).toList()
                    paths += Triple(path, route, routes.stopNames[path.last()].orEmpty().ifBlank { RouteStops.terminusOf(route.name) })
                }
            }
            val byPath = HashMap<List<String>, Int>()
            merged(paths, byPath, choices)
            val fromRoutes = choices.size
            // Each route the stop's trains run, worked out from its soonest train, onto its choice.
            val followed = followedDeparture(row, focus, topology, otherPlatforms(row, rows))
            var current: Int? = null
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DepartureRow, Boolean>())
            val here = (listOf(row) + rows).filter { it.stopId == stopId && it.lineId == lineId && seen.add(it) }
            val liveOnly = HashMap<String, Int>()
            // Each choice's soonest train, so the live route it keeps is its soonest.
            val soonest = HashMap<Int, Departure>()
            // The choice a train runs on by its own path, where it resolves to one already offered.
            fun wayOf(train: Departure): Int? {
                val resolution = RouteStops.resolve(
                    routes, stopId, train.destination, train.branch, lineId, bus, RouteStops.boundOf(train.platform),
                    train.direction, train.destinationId, train.via,
                )
                val path = (resolution as? RouteStops.Resolution.Found)?.stops?.map { it.id } ?: return null
                return byPath[path] ?: liveOnly[path.joinToString(",")]
            }
            for (from in here) {
                for (group in DepartureRows.destinationLines(from, Int.MAX_VALUE, topology)) {
                    val train = group.times.firstOrNull() ?: group.untimed.firstOrNull()?.train ?: continue
                    val resolution = RouteStops.resolve(
                        routes, stopId, train.destination, train.branch, lineId, bus, RouteStops.boundOf(train.platform),
                        train.direction, train.destinationId, train.via,
                    )
                    // A train ending here takes no one anywhere: no way to offer.
                    if (resolution == RouteStops.Resolution.EndsHere) continue
                    val path = (resolution as? RouteStops.Resolution.Found)?.stops?.map { it.id }.orEmpty()
                    val way = train.direction.ifBlank { from.direction }
                    // A train TfL names no destination for is told apart by how far it goes at least, so a choice's
                    // guess holds for each train on it, whichever platform's the page follows (Codex on #1045).
                    val guess = if (path.isEmpty()) atLeastOf(routes, stopId, train, way, bus) else null
                    val unknown = "$way|${train.destination}|${group.branch.orEmpty()}|${guess.orEmpty()}"
                    val index = if (path.isNotEmpty()) {
                        byPath[path] ?: liveOnly[path.joinToString(",")]
                    } else {
                        // Its way unknown: the one route choice it can only be by name, its via and its way.
                        (0 until fromRoutes).singleOrNull { i ->
                            val choice = choices[i]
                            sameStopName(choice.destination, train.destination) &&
                                (train.branch == null || choice.branch == null || choice.branch == train.branch) &&
                                (way.isBlank() || choice.direction.isBlank() || choice.direction == way)
                        } ?: liveOnly[unknown]
                    } ?: run {
                        // A way the line's routes don't run from here (a short working): a choice of its own.
                        choices += RouteChoice(train.destination, train.branch, path, way, atLeast = guess)
                        liveOnly[if (path.isNotEmpty()) path.joinToString(",") else unknown] = choices.lastIndex
                        choices.lastIndex
                    }
                    // Its other trains on ways of their own: a group can hold trains to one place by one via that take
                    // different stops (a bus route's variants), which a page following the place and its via can't tell
                    // apart, so where it does, each way's chip hands the page its own train, followed by its way
                    // ([RouteFocus.way]), not the group's soonest (Codex on #1038).
                    val others = ArrayList<Pair<Int, Departure>>()
                    for (other in group.times.drop(1) + group.untimed.map { it.train }.filter { it !== train }) {
                        val otherIndex = wayOf(other) ?: continue
                        if (otherIndex != index) others += otherIndex to other
                    }
                    fun focusOn(it: Departure) = if (others.isEmpty()) RouteFocus.of(group) else RouteFocus(group.destination, it.branch, it.wayKey)
                    val held = soonest[index]
                    if (held == null || train.expectedArrival < held.expectedArrival) {
                        soonest[index] = train
                        val atLeast = if (choices[index].path.isEmpty()) guess else null
                        choices[index] = choices[index].copy(live = LiveRoute(from, focusOn(train)), atLeast = atLeast)
                    }
                    for ((otherIndex, other) in others) {
                        val heldOther = soonest[otherIndex]
                        if (heldOther == null || other.expectedArrival < heldOther.expectedArrival) {
                            soonest[otherIndex] = other
                            choices[otherIndex] = choices[otherIndex].copy(live = LiveRoute(from, focusOn(other)))
                        }
                    }
                    if (followed != null && (group.times.any { it === followed } || group.untimed.any { it.train === followed })) {
                        // The followed train's own way, not its group's soonest's: a journey card's page has the card's
                        // row, holding only the trains it counts on, beside the stop's own, whose group for a place may
                        // start with another way's train (Codex on #1049).
                        current = if (followed === train) index else wayOf(followed) ?: index
                    }
                }
            }
            // Ways whose trains hand the page the same place from rows of their own (a loop's trains TfL names no
            // destination for, on platforms of opposite bounds): the page, following the place across the stop's
            // platforms, would follow the sooner from either chip, so each hands over by its own train's way too
            // (Codex on #1045).
            val byPlace = HashMap<RouteFocus, MutableList<Int>>()
            for (i in choices.indices) {
                val focus = choices[i].live?.focus?.takeIf { it.way == null } ?: continue
                byPlace.getOrPut(focus) { ArrayList() } += i
            }
            for (same in byPlace.values) {
                if (same.size < 2) continue
                for (i in same) {
                    val live = choices[i].live ?: continue
                    val train = soonest[i] ?: continue
                    choices[i] = choices[i].copy(live = live.copy(focus = live.focus.copy(way = train.wayKey)))
                }
            }
            // Both ways, [first] first; within each, those reaching a stop of [reaching], then the line's routes in
            // TfL's order, then the live trains' own ways by name.
            val own = first
            val reaches = BooleanArray(choices.size) { i ->
                reaching.isNotEmpty() &&
                    choices[i].path.drop(1).any { it in reaching || routes.stopAreas[it]?.let { area -> area in reaching } == true }
            }
            val order = choices.indices.sortedWith(
                compareBy<Int>(
                    { if (own.isNotBlank() && choices[it].direction == own) 0 else 1 },
                    { if (reaches[it]) 0 else 1 },
                    { if (it < fromRoutes) 0 else 1 },
                )
                    .thenBy { if (it < fromRoutes) "" else choices[it].destination }
                    .thenBy { it },
            )
            val sorted = labeled(order.map { choices[it] }, routes)
            val shownCurrent = current?.let { order.indexOf(it) } ?: if (followed == null && sorted.size == 1) 0 else null
            return RouteChoices(sorted, shownCurrent)
        }

        /**
         * [of] for a National Rail line (maintainer, 2026-10-22): its routes are its stopping patterns, a dozen from one
         * platform, so a chip is where they go, not their stops. The patterns from the stop ending at one station are one
         * choice, keyed by that station ([RouteChoice.goesTo]), its [RouteChoice.path] the first of them TfL lists, which
         * the page lists while no train is on it. Each train at the stop goes on the choice for where its own stops end
         * ([RouteStops.resolve]), else, its stops unknown, the one for the station TfL's id for its end names, else the
         * one to a place of its name, else one of its own after the rest; a choice's soonest is the one the page follows,
         * listing that train's own stops. Both ways, [first] first (none for a page opened from a board, which names
         * no train's way); within each, those calling at a stop of [reaching], then in TfL's order.
         */
        @WorkerThread
        private fun ofRail(
            sequence: LineSequence,
            row: DepartureRow,
            rows: List<DepartureRow>,
            focus: RouteFocus?,
            topology: RouteTopology,
            first: String,
            reaching: Set<String>,
        ): RouteChoices {
            val lineId = row.lineId
            val stopId = row.stopId
            val routes = sequence.eachCallingAt(stopId)
            // Where a path ends, as one station ([LineSequence.stationCodes]): a pattern ending under another of its
            // ids still goes there.
            fun stationOf(id: String) = routes.stationCodeOf(id) ?: id
            val choices = ArrayList<RouteChoice>()
            val byStation = HashMap<String, Int>()
            // Every stopping pattern's stops, by choice, for whether one reaches a stop of [reaching].
            val pathsOf = ArrayList<MutableList<List<String>>>()
            val ways = ArrayList<MutableSet<String>>()
            for (route in routes.routes) {
                val stops = route.stopIds
                for (i in stops.indices) {
                    if (stops[i] != stopId || i == stops.lastIndex) continue
                    val path = stops.subList(i, stops.size).toList()
                    val station = stationOf(path.last())
                    val index = byStation.getOrPut(station) {
                        val end = routes.stopNames[path.last()].orEmpty().ifBlank { RouteStops.terminusOf(route.name) }
                        choices += RouteChoice(end, null, path, "", goesTo = station)
                        pathsOf.add(ArrayList())
                        ways.add(HashSet())
                        choices.lastIndex
                    }
                    pathsOf[index] += path
                    ways[index] += route.direction
                }
            }
            for (i in choices.indices) choices[i] = choices[i].copy(direction = ways[i].singleOrNull().orEmpty())
            val fromRoutes = choices.size
            val followed = followedDeparture(row, focus, topology, otherPlatforms(row, rows))
            var current: Int? = null
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DepartureRow, Boolean>())
            val here = (listOf(row) + rows).filter { it.stopId == stopId && it.lineId == lineId && seen.add(it) }
            val soonest = HashMap<Int, Departure>()
            for (from in here) {
                for (group in DepartureRows.destinationLines(from, Int.MAX_VALUE, topology)) {
                    val train = group.times.firstOrNull() ?: group.untimed.firstOrNull()?.train ?: continue
                    val resolution = RouteStops.resolve(
                        routes, stopId, train.destination, train.branch, lineId, false, RouteStops.boundOf(train.platform),
                        train.direction, train.destinationId, train.via,
                    )
                    // A train ending here takes no one anywhere: no way to offer.
                    if (resolution == RouteStops.Resolution.EndsHere) continue
                    val path = (resolution as? RouteStops.Resolution.Found)?.stops?.map { it.id }.orEmpty()
                    val way = train.direction.ifBlank { from.direction }
                    val index = if (path.isNotEmpty()) {
                        val station = stationOf(path.last())
                        byStation.getOrPut(station) {
                            // Where no pattern from here ends (a short working): a choice of its own, by its own stops.
                            choices += RouteChoice(train.destination, null, path, way, goesTo = station)
                            pathsOf += mutableListOf(path)
                            choices.lastIndex
                        }
                    } else {
                        // Its stops unknown (more than one pattern may run there, or none it can be told by): the
                        // choice for the station TfL's id for where it ends names, which a board may spell otherwise
                        // ("St Albans" for the routes' "St Albans City", Codex on #1095); else the one to a place of
                        // its name; else one of its own, by that station where TfL names one, else by its name.
                        val station = train.destinationId.ifBlank { null }?.let(::stationOf)
                        station?.let { byStation[it] }
                            ?: choices.indices.firstOrNull { sameStopName(choices[it].destination, train.destination) }
                            ?: run {
                                val goesTo = station ?: "name:${matchStopName(train.destination).lowercase()}"
                                choices += RouteChoice(train.destination, null, emptyList(), way, goesTo = goesTo)
                                pathsOf.add(ArrayList())
                                if (station != null) byStation[station] = choices.lastIndex
                                choices.lastIndex
                            }
                    }
                    val held = soonest[index]
                    if (held == null || train.expectedArrival < held.expectedArrival) {
                        soonest[index] = train
                        choices[index] = choices[index].copy(live = LiveRoute(from, RouteFocus.of(group)))
                    }
                    if (followed != null && (group.times.any { it === followed } || group.untimed.any { it.train === followed })) {
                        current = index
                    }
                }
            }
            // Both ways, [first] first; within each, those reaching a stop of [reaching], then TfL's order, then the
            // trains' own places by name. A board names no way for a train, so a page opened from one has none to put
            // first: TfL's order then, never the followed train's way, which choosing a chip or a refresh would move,
            // reordering the chips under the rider (Codex on #1095).
            val own = first
            val reaches = BooleanArray(choices.size) { i ->
                reaching.isNotEmpty() && pathsOf[i].any { path ->
                    path.drop(1).any { it in reaching || routes.stopAreas[it]?.let { area -> area in reaching } == true }
                }
            }
            val order = choices.indices.sortedWith(
                compareBy<Int>(
                    { if (own.isNotBlank() && choices[it].direction == own) 0 else 1 },
                    { if (reaches[it]) 0 else 1 },
                    { if (it < fromRoutes) 0 else 1 },
                )
                    .thenBy { if (it < fromRoutes) "" else choices[it].destination }
                    .thenBy { it },
            )
            val sorted = labeled(order.map { choices[it] }, routes)
            val shownCurrent = current?.let { order.indexOf(it) } ?: if (followed == null && sorted.size == 1) 0 else null
            return RouteChoices(sorted, shownCurrent)
        }

        /**
         * The ways [ride] can be ridden on its line ([sequence], both ways): each route calling where it boards and,
         * later, where it gets off, from the one to the other, one choice per distinct path (maintainer, 2026-10-21;
         * Codex on #1036). Each route is read from where the ride boards on its own ([eachCallingAt]), and gets off
         * at its stop or its stop area, else, where that route calls at neither after boarding, another id of its
         * station, so a way calling at the station under another id is offered too (Codex on #1039). More than one is
         * a ride its ends alone can't say the way of (Camden Town to Kennington by either Northern line branch), for
         * the rider to choose; none is [current], since the ride itself can't say. A National Rail ride offers none: its
         * stopping patterns all join its ends, and a list row's page offers them by where they go ([of]), which a ride's
         * end already says.
         */
        @WorkerThread
        fun forRide(sequence: LineSequence, ride: TripLeg): RouteChoices {
            // A ride saved with no mode is its line's known one, as a list row's is (Codex on #1039).
            val mode = ride.mode.ifBlank { Connections.knownMode(ride.lineId).orEmpty() }
            if (ride.lineId.isBlank() || mode.equals("national-rail", ignoreCase = true)) return NONE
            val routes = sequence.eachCallingAt(ride.fromId)
            fun exactly(id: String) = id == ride.toId || (ride.toArea.isNotEmpty() && routes.stopAreas[id] == ride.toArea)
            fun after(route: LineRoute, i: Int, alights: (String) -> Boolean) =
                (i + 1 until route.stopIds.size).firstOrNull { alights(route.stopIds[it]) }
            val starts = routes.routes.flatMap { route -> route.stopIds.indices.filter { route.stopIds[it] == ride.fromId }.map { route to it } }
            val paths = starts.mapNotNull { (route, i) ->
                // A bus's poles have no station: by its stop or stop area alone.
                val j = after(route, i, ::exactly)
                    ?: after(route, i) { !mode.equals("bus", ignoreCase = true) && routes.sameStationId(it, ride.toId) }
                    ?: return@mapNotNull null
                val path = route.stopIds.subList(i, j + 1).toList()
                Triple(path, route, routes.stopNames[path.last()].orEmpty().ifBlank { ride.toName })
            }
            val choices = ArrayList<RouteChoice>()
            merged(paths, HashMap(), choices)
            return RouteChoices(labeled(choices, routes), null)
        }

        /**
         * One choice per distinct path of [paths] (each with the route it's on and where it ends), into [choices], in
         * the order first met, its place by path in [byPath]: its via the one the routes sharing it name, where they
         * name one and agree (Battersea's unnamed route runs the Charing Cross branch's stops); its way where they
         * agree.
         */
        private fun merged(
            paths: List<Triple<List<String>, LineRoute, String>>,
            byPath: HashMap<List<String>, Int>,
            choices: ArrayList<RouteChoice>,
        ) {
            val vias = ArrayList<MutableSet<String>>()
            val ways = ArrayList<MutableSet<String>>()
            for ((path, route, end) in paths) {
                val index = byPath.getOrPut(path) {
                    choices += RouteChoice(end, null, path, "")
                    vias.add(HashSet())
                    ways.add(HashSet())
                    choices.lastIndex
                }
                branchOf(route.name)?.let { vias[index] += it }
                ways[index] += route.direction
            }
            for (i in choices.indices) {
                choices[i] = choices[i].copy(branch = vias[i].singleOrNull(), direction = ways[i].singleOrNull().orEmpty())
            }
        }

        // Where two choices end at one place, each by its via where they differ; by the first stop it calls at that
        // the others read the same way don't, where they don't.
        private fun labeled(choices: List<RouteChoice>, sequence: LineSequence): List<RouteChoice> {
            val ends = choices.map { matchStopName(it.destination).lowercase() }
            val branched = choices.indices.map { i ->
                val branch = choices[i].branch
                branch != null && choices.indices.any { j -> j != i && ends[j] == ends[i] && choices[j].branch != branch }
            }
            fun shown(i: Int) = if (branched[i]) choices[i].branch else null
            return choices.mapIndexed { i, choice ->
                val twins = choices.indices.filter { j -> j != i && ends[j] == ends[i] && shown(j) == shown(i) }.map { choices[it] }
                val parting = if (twins.isEmpty()) {
                    null
                } else {
                    choice.path.drop(1).firstOrNull { stop -> twins.none { stop in it.path } }
                        ?.let { sequence.stopNames[it]?.ifBlank { null } }
                }
                choice.copy(showBranch = branched[i], parting = parting)
            }
        }

        /**
         * How far [train], one TfL names no destination for, goes at least ([RouteStops.atLeast]): the farthest stop
         * every way it can run from [stopId] reaches before they part, its [way] ([RouteChoice.direction]) read where
         * its platform names no bound. Its route page lists the stops as far as this ([RouteStops.atLeast] too).
         */
        @WorkerThread
        private fun atLeastOf(routes: LineSequence, stopId: String, train: Departure, way: String, bus: Boolean): String? =
            RouteStops.atLeast(
                routes, stopId, train.destination, train.branch, bus, RouteStops.boundOf(train.platform), way, train.destinationId, train.via,
            )?.last()

        private fun nationalRail(row: DepartureRow): Boolean =
            row.mode.ifBlank { Connections.knownMode(row.lineId).orEmpty() }.equals("national-rail", ignoreCase = true)

        private fun isBus(row: DepartureRow): Boolean =
            row.mode.equals("bus", ignoreCase = true) || row.upcoming.any { it.mode.equals("bus", ignoreCase = true) }
    }
}

/**
 * This sequence with each of its routes seen from [stopId] on its own ([LineSequence.callingAt]): there, one route
 * calling at the stop's own id leaves another that calls at a sibling id of its station as it is, which this
 * doesn't, so every way on from the station is found (Codex on #1039; `OnTheWay`'s ride routes are read the same
 * way).
 */
@WorkerThread
fun LineSequence.eachCallingAt(stopId: String): LineSequence =
    callingAt(stopId).copy(routes = routes.map { route -> copy(routes = listOf(route)).callingAt(stopId).routes.single() })

/**
 * The stops of [path] on [sequence] as a stop list lists them, each with its connections other than [lineId], the
 * line ridden: a route choice's ([RouteChoice.path]), which no train names.
 */
@WorkerThread
fun RouteStops.stopsOf(sequence: LineSequence, path: List<String>, lineId: String): List<RouteStop> =
    path.map { id -> RouteStop(id, sequence.stopNames[id].orEmpty(), Connections.of(sequence.stopLines[id].orEmpty(), lineId)) }
