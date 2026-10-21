package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant

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
    // A train at the stop runs it that the page can't follow on its own: one grouped with a train to the same place
    // by the same via that runs another way (a bus route's variants, told apart only by their stops). It has no
    // [live] to hand over to, but it isn't a way with no train either (Codex on #1038).
    val unfollowedTrain: Boolean = false,
    // For a live train TfL names no destination for ("Check Front of Train"), the farthest stop every way it can run
    // from here reaches before they part: how far it goes at least, a guess (maintainer, 2026-10-21).
    val atLeast: String? = null,
) {
    /** Which choice this is, for the page to hold across a refresh: its way and stops, else its live route. */
    val key: String =
        if (path.isEmpty()) "$direction|live|$destination|${branch.orEmpty()}${atLeast?.let { "|$it" }.orEmpty()}" else "$direction|${path.joinToString(",")}"
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
         * another of them reorders them; ways only a live train names follow, by name. [current] is the choice
         * the page's train is on: the one [focus] follows ([followedDeparture]), else, on a page with no train,
         * the only choice where there's one.
         */
        @WorkerThread
        fun of(
            sequence: LineSequence,
            row: DepartureRow,
            rows: List<DepartureRow>,
            focus: RouteFocus?,
            topology: RouteTopology = RouteTopology.EMPTY,
            first: String = row.direction,
        ): RouteChoices {
            val lineId = row.lineId
            val stopId = row.stopId
            // A National Rail line's routes are its stopping patterns, a dozen from one platform: not chips to pick
            // a train by, so none yet (`TODO.md`).
            if (lineId.isBlank() || nationalRail(row)) return NONE
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
            // Each choice's soonest train's time, so the live route it keeps is its soonest.
            val soonestAt = HashMap<Int, Instant>()
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
                    val held = soonestAt[index]
                    if (held == null || train.expectedArrival < held) {
                        soonestAt[index] = train.expectedArrival
                        val atLeast = if (choices[index].path.isEmpty()) guess else null
                        choices[index] = choices[index].copy(live = LiveRoute(from, RouteFocus.of(group)), atLeast = atLeast)
                    }
                    if (followed != null && (group.times.any { it === followed } || group.untimed.any { it.train === followed })) current = index
                    // Its other trains, each on its own way: one running another way than its soonest has a train at
                    // the stop too, which the page, following a place and its via ([RouteFocus]), can't tell from the
                    // soonest, so that way isn't said to have none (Codex on #1038).
                    for (other in group.times.drop(1) + group.untimed.map { it.train }.filter { it !== train }) {
                        val its = RouteStops.resolve(
                            routes, stopId, other.destination, other.branch, lineId, bus, RouteStops.boundOf(other.platform),
                            other.direction, other.destinationId, other.via,
                        )
                        val otherPath = (its as? RouteStops.Resolution.Found)?.stops?.map { it.id } ?: continue
                        val otherIndex = byPath[otherPath] ?: liveOnly[otherPath.joinToString(",")] ?: continue
                        if (otherIndex != index && choices[otherIndex].live == null) {
                            choices[otherIndex] = choices[otherIndex].copy(unfollowedTrain = true)
                        }
                    }
                }
            }
            // Both ways, [first] first; within each, the line's routes in TfL's order, then the live trains' own
            // ways by name.
            val own = first
            val order = choices.indices.sortedWith(
                compareBy<Int>({ if (own.isNotBlank() && choices[it].direction == own) 0 else 1 }, { if (it < fromRoutes) 0 else 1 })
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
         * the rider to choose; none is [current], since the ride itself can't say. A National Rail ride offers none,
         * as a list row's page doesn't ([of]).
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
         * How far [train], one TfL names no destination for ([RouteStops.isUnknownDestination]), goes at least: the
         * farthest stop every way it can run from [stopId] reaches before they part (a Northern line train at Morden
         * reaching Kennington by either branch). Its ways are the ones a train is resolved onto
         * ([RouteStops.candidatePaths]), so its platform's bound wins over its TfL direction, as on a loop (Codex on
         * #1045). A guess from the route data, never TfL's destination; none where neither its bound nor its [way]
         * ([RouteChoice.direction]) is known, or its ways part at once.
         */
        @WorkerThread
        private fun atLeastOf(routes: LineSequence, stopId: String, train: Departure, way: String, bus: Boolean): String? {
            if (!RouteStops.isUnknownDestination(train.destination)) return null
            val bound = RouteStops.boundOf(train.platform)
            if (bound == null && way.isBlank()) return null
            val ways = RouteStops.candidatePaths(routes, stopId, train.destination, train.branch, bus, bound, way, train.destinationId, train.via)
            if (ways.isEmpty()) return null
            val shared = ways.reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }
            return shared.takeIf { it.size >= 2 }?.last()
        }

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
