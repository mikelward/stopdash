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
) {
    /** Which choice this is, for the page to hold across a refresh: its way and stops, else its live route. */
    val key: String = if (path.isEmpty()) "$direction|live|$destination|${branch.orEmpty()}" else "$direction|${path.joinToString(",")}"
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
            val routes = sequence.callingAt(stopId)
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
                    val index = if (path.isNotEmpty()) {
                        byPath[path] ?: liveOnly[path.joinToString(",")]
                    } else {
                        // Its way unknown: the one route choice it can only be by name, its via and its way.
                        (0 until fromRoutes).singleOrNull { i ->
                            val choice = choices[i]
                            sameStopName(choice.destination, train.destination) &&
                                (train.branch == null || choice.branch == null || choice.branch == train.branch) &&
                                (way.isBlank() || choice.direction.isBlank() || choice.direction == way)
                        } ?: liveOnly["$way|${train.destination}|${group.branch.orEmpty()}"]
                    } ?: run {
                        // A way the line's routes don't run from here (a short working): a choice of its own.
                        choices += RouteChoice(train.destination, train.branch, path, way)
                        liveOnly[if (path.isNotEmpty()) path.joinToString(",") else "$way|${train.destination}|${group.branch.orEmpty()}"] = choices.lastIndex
                        choices.lastIndex
                    }
                    val held = soonestAt[index]
                    if (held == null || train.expectedArrival < held) {
                        soonestAt[index] = train.expectedArrival
                        choices[index] = choices[index].copy(live = LiveRoute(from, RouteFocus.of(group)))
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

        private fun nationalRail(row: DepartureRow): Boolean =
            row.mode.ifBlank { Connections.knownMode(row.lineId).orEmpty() }.equals("national-rail", ignoreCase = true)

        private fun isBus(row: DepartureRow): Boolean =
            row.mode.equals("bus", ignoreCase = true) || row.upcoming.any { it.mode.equals("bus", ignoreCase = true) }
    }
}

/**
 * The stops of [path] on [sequence] as a stop list lists them, each with its connections other than [lineId], the
 * line ridden: a route choice's ([RouteChoice.path]), which no train names.
 */
@WorkerThread
fun RouteStops.stopsOf(sequence: LineSequence, path: List<String>, lineId: String): List<RouteStop> =
    path.map { id -> RouteStop(id, sequence.stopNames[id].orEmpty(), Connections.of(sequence.stopLines[id].orEmpty(), lineId)) }
