package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * The ways a trip on the way's ride can be left (maintainer, 2026-10-05): at a ride's boarding stop, a
 * branch of the ride's line that runs the ride's way for a stop or more and then turns off it, as the
 * Bank branch does at Camden Town for a rider going down the Charing Cross branch. Read from the line's
 * route, not from TfL's live labels, which can name a Bank train Charing Cross. The trip's board lists
 * them grayed, and **Take this one** reroutes the trip ([take]): a ride to where the branch turns off,
 * then a change there onto the rest of the ride, followed as any route is.
 */
object OffPlan {
    /**
     * A branch named [label] ("Bank", or where it ends) that leaves the ride at its path's stop
     * [forkIndex] ([TripLeg.path]), named [forkName]: where the rider changes back. [trains] are the
     * board's trains whose way TfL's labels put on it, soonest first: none where TfL lists none, or
     * sends them another way. [ways] are the stop ids the branch runs on past the fork, one per route
     * pattern merged into it: patterns that run on alike are one branch, whatever their names.
     */
    data class Branch(
        val label: String,
        val forkIndex: Int,
        val forkName: String,
        val trains: List<Departure> = emptyList(),
        val ways: List<List<String>> = emptyList(),
    )

    /**
     * The branches that leave [ride] ([Branch]), by its line's route ([sequences], by line), nearest
     * fork first, each with the trains of [departures] still to come at [now] whose way runs on it.
     * Route patterns that run on alike past the same fork are one branch (Codex, #583), and so are two
     * that would read the same. None for a bus, whose path is too loose to send a rider to change on
     * (as a journey card's change at a fork, SPEC *Journeys*), or with the line's route not loaded.
     */
    @WorkerThread
    fun branches(ride: TripLeg, departures: List<Departure>, sequences: Map<String, LineSequence?>, now: Instant): List<Branch> {
        if (ride.isWalk || ride.isBus || !OnTheWay.checkable(ride)) return emptyList()
        val sequence = sequences[ride.lineId]?.callingAt(ride.fromId) ?: return emptyList()
        val found = LinkedHashMap<Pair<Int, String>, Branch>()
        for (route in sequence.routes) {
            for (at in route.stopIds.indices.filter { route.stopIds[it] == ride.fromId }) {
                val after = route.stopIds.drop(at + 1).map { RouteStop(it, sequence.stopNames[it].orEmpty()) }
                val fork = forkAlong(ride, after, sequence) ?: continue
                val on = after.drop(fork + 1)
                val way = on.map { it.id }
                // Already one branch's way, under whatever name it was found by first.
                if (found.values.any { it.forkIndex == fork && way in it.ways }) continue
                val label = labelOf(route, on) ?: continue
                val branch = found[fork to label]
                found[fork to label] = branch?.copy(ways = branch.ways + listOf(way)) ?: Branch(label, fork, forkName(ride, fork, sequence), ways = listOf(way))
            }
        }
        val listed = Countdown.upcoming(departures.filter { it.lineId == ride.lineId }, now).sortedBy { it.expectedArrival }
            .mapNotNull { train -> wayOf(ride, train, sequence)?.let { train to it } }
        return found.values.sortedWith(compareBy({ it.forkIndex }, { it.label })).map { branch ->
            branch.copy(trains = listed.filter { (_, way) -> onBranch(branch, way) }.map { it.first })
        }
    }

    // What a branch run by [route] is called past its fork, along [on], its stops from there: the route's
    // "via" where it names one of those stops (the Bank branch), else where it ends. A "via" the train
    // passed before the fork names no choice made there (Codex, #583).
    private fun labelOf(route: LineRoute, on: List<RouteStop>): String? {
        val via = route.name.substringAfter(" via ", "").trim()
        if (via.isNotBlank() && on.any { it.name.isNotBlank() && sameStation(via, it.name) }) return branchOf(route.name)
        return DepartureLabels.destinationLabel(on.lastOrNull()?.name.orEmpty(), "")
    }

    // Whether a train whose way runs past the fork as [way] is on [branch]: at its fork, on one of its
    // ways, or a short working of one (its way the start of one).
    private fun onBranch(branch: Branch, way: Pair<Int, List<String>>): Boolean =
        way.first == branch.forkIndex && way.second.isNotEmpty() &&
            branch.ways.any { it.size >= way.second.size && it.subList(0, way.second.size) == way.second }

    // Where a way from the boarding stop ([after], its stops past it) leaves [ride], as an index into the
    // ride's path, or null: one running the whole ride is the plan's own, one sharing no stop past the
    // boarding one goes another way altogether, and one that comes back to where the rider gets off
    // still takes them there, a change on it for nothing (Codex, #583).
    private fun forkAlong(ride: TripLeg, after: List<RouteStop>, sequence: LineSequence): Int? {
        var shared = 0
        while (shared < after.size && shared < ride.path.size && sameStop(ride, shared, after[shared], sequence)) shared++
        if (shared >= ride.path.size) return null
        if (after.drop(shared).any { reachesEnd(ride, it) }) return null
        return (shared - 1).takeIf { it >= 0 }
    }

    // Whether [stop] is where [ride] gets off: its id, else its name.
    private fun reachesEnd(ride: TripLeg, stop: RouteStop): Boolean =
        stop.id == ride.toId || (ride.toName.isNotBlank() && stop.name.isNotBlank() && sameStation(ride.toName, stop.name))

    /**
     * Where [departure] leaves [ride], as an index into the ride's path, or null when it doesn't: its
     * way from the boarding stop, by its line's [sequence], runs the ride's next stop and on, then turns
     * off before where the rider gets off. A train running the whole ride is the plan's own; one
     * sharing no stop past the boarding one goes another way altogether.
     */
    @WorkerThread
    fun forkOf(ride: TripLeg, departure: Departure, sequence: LineSequence): Int? = wayOf(ride, departure, sequence)?.first

    // Where [departure] leaves [ride] ([forkOf]) and the stop ids it runs on past there, or null.
    private fun wayOf(ride: TripLeg, departure: Departure, sequence: LineSequence): Pair<Int, List<String>>? {
        val resolution = RouteStops.resolve(
            sequence, ride.fromId, departure.destination, departure.branch, departure.lineId, bound = RouteStops.boundOf(departure.platform),
            direction = departure.direction, destinationId = departure.destinationId, via = departure.via,
        )
        // The way from the boarding stop: its first stop is the boarding stop itself.
        val after = (resolution as? RouteStops.Resolution.Found)?.stops?.drop(1) ?: return null
        val fork = forkAlong(ride, after, sequence) ?: return null
        return fork to after.drop(fork + 1).map { it.id }
    }

    // Whether the ride's path stop [index] is [stop]: the same id, else the same name (the Planner can
    // name a station by another of its ids).
    private fun sameStop(ride: TripLeg, index: Int, stop: RouteStop, sequence: LineSequence): Boolean {
        val id = ride.path[index]
        if (id == stop.id) return true
        val name = ride.pathNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: sequence.stopNames[id].orEmpty()
        return name.isNotBlank() && sameStation(name, stop.name)
    }

    private fun forkName(ride: TripLeg, index: Int, sequence: LineSequence): String =
        ride.pathNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: sequence.stopNames[ride.path[index]].orEmpty()

    /**
     * [trip] rerouted onto [branch] for the ride at leg [rideIndex] (maintainer, 2026-10-05: like a
     * reroute, nothing followed specially): the ride split where the branch turns off, into a ride to
     * there and the rest of the ride from there, a change between. The trip then goes on as on any route:
     * the ride to the fork picks the next train that reaches it, whatever TfL calls its way, and the
     * change picks the next on from there. Leg indices past the ride move up one, and what was heard or
     * let go of on the ride holds for both its parts. On the ride, its train is looked for afresh; on the
     * walk to it, the walk goes on. Null when [rideIndex] isn't the ride the rider is on, or on their way
     * to, or once they're seen on board it.
     */
    @WorkerThread
    fun take(trip: ActiveTrip, rideIndex: Int, branch: Branch, now: Instant): ActiveTrip? {
        val ride = trip.route.legs.getOrNull(rideIndex) ?: return null
        if (ride.isWalk || branch.forkIndex !in 0 until ride.path.size - 1) return null
        val onIt = rideIndex == trip.legIndex && !trip.onBoardSeen
        val walkingTo = rideIndex == trip.legIndex + 1 && trip.leg?.isWalk == true
        if (!onIt && !walkingTo) return null
        val (first, rest) = split(ride, branch)
        val legs = trip.route.legs.take(rideIndex) + first + rest + trip.route.legs.drop(rideIndex + 1)
        fun shift(index: Int) = if (index > rideIndex) index + 1 else index
        val split = trip.copy(
            route = TripRoute(legs),
            warnedLeg = if (trip.warnedLeg < 0) trip.warnedLeg else shift(trip.warnedLeg),
            onFootChanges = trip.onFootChanges?.mapTo(HashSet(), ::shift),
            disruptionsHeard = shiftKeys(trip.disruptionsHeard, rideIndex),
            disruptionsDismissed = shiftKeys(trip.disruptionsDismissed, rideIndex),
            leftRide = null,
        )
        return if (onIt) OnTheWay.atLeg(split, rideIndex, now) else split
    }

    // [ride] as two: to where [branch] leaves it, and on from there. The Planner's times are shared out
    // by stops, as no time is known for the stop between.
    private fun split(ride: TripLeg, branch: Branch): Pair<TripLeg, TripLeg> {
        val k = branch.forkIndex
        val forkId = ride.path[k]
        val stops = ride.path.size
        val at = ride.departure.plus(Duration.ofMillis(ride.run.toMillis() * (k + 1) / stops))
        val first = ride.copy(
            toId = forkId, toName = branch.forkName, arrival = at, path = ride.path.take(k + 1), pathNames = ride.pathNames.take(k + 1),
            changeAfter = Duration.ZERO, toArea = "", toAt = null, plannedToId = "",
        )
        val rest = ride.copy(
            fromId = forkId, fromName = branch.forkName, departure = at, path = ride.path.drop(k + 1), pathNames = ride.pathNames.drop(k + 1),
            fromArea = "", fromAt = null, plannedFromId = "",
        )
        return first to rest
    }

    // The trip's disruption keys ("kind/leg/…", [RouteDisruption.Signal.key]) for the route split at
    // [rideIndex]: a later leg's move up one, and the ride's hold for both its parts.
    private fun shiftKeys(keys: Set<String>, rideIndex: Int): Set<String> = keys.flatMapTo(LinkedHashSet()) { key ->
        val parts = key.split('/', limit = 3)
        val leg = parts.getOrNull(1)?.toIntOrNull()
        when {
            leg == null || parts.size < 3 || leg < rideIndex -> listOf(key)
            leg == rideIndex -> listOf(key, "${parts[0]}/${leg + 1}/${parts[2]}")
            else -> listOf("${parts[0]}/${leg + 1}/${parts[2]}")
        }
    }
}
