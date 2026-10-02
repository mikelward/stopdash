package app.stopdash.domain

/**
 * The route a user tapped on a card — its [destination] and the TfL [branch] of the row's soonest
 * train — so the detail follows that route rather than whichever of the row's trains is soonest (a
 * card shows one route row per destination and branch of a line and direction).
 *
 * The raw branch, not the row's topology merge key, is what's kept: the topology can change while
 * the page is open (it loads asynchronously on a cold start), and a stored key would stop matching.
 * Which trains count as this route is worked out when it's used ([followedDeparture]), under the
 * topology in force then.
 */
data class RouteFocus(val destination: String, val branch: String?) {
    companion object {
        /** The focus for a card's route row: its soonest timed train's, else (every train has no time) its soonest's. */
        fun of(group: DestinationGroup) =
            RouteFocus(group.destination, (group.times.firstOrNull() ?: group.untimed.firstOrNull()?.train)?.branch)
    }
}

/**
 * The departure the detail follows: the soonest on the [focus] route — the trains the card groups
 * into that row under [topology] (a merged row can mix branches that share the path from here) —
 * else, once that route has no trains left, the row's soonest. The page's title follows the same
 * train, so the title and the stop list always agree. It never slips to another route row's trains
 * (the same destination on a branch the card shows as its own row).
 *
 * A route whose every train has no time ([DepartureRow.untimed]) follows its soonest by schedule,
 * which places the route as a timed train would (destination, branch, platform), never another
 * route's train. Nothing reads a time off the train it follows.
 */
fun followedDeparture(row: DepartureRow, focus: RouteFocus?, topology: RouteTopology = RouteTopology.EMPTY): Departure? {
    if (focus != null) {
        val route = topology.grouping(row.lineId, row.stopId, focus.destination, focus.branch).mergeKey
        fun onRoute(it: Departure) = it.destination == focus.destination &&
            topology.grouping(row.lineId, row.stopId, it.destination, it.branch).mergeKey == route
        (row.upcoming.firstOrNull(::onRoute) ?: row.untimed.firstOrNull { onRoute(it.train) }?.train)?.let { return it }
    }
    return row.upcoming.firstOrNull() ?: row.untimed.firstOrNull()?.train
}

/**
 * Every departure on the route the detail follows — the [followedDeparture]'s destination and
 * topology route — soonest-first and uncapped, so the page can list more than the card's few.
 * It keys on the followed train, not [focus] directly, so the list always matches the title and
 * stop list, fallback included. Empty for a status row.
 */
fun routeDepartures(row: DepartureRow, focus: RouteFocus?, topology: RouteTopology = RouteTopology.EMPTY): List<Departure> {
    val followed = followedDeparture(row, focus, topology) ?: return emptyList()
    val route = topology.grouping(row.lineId, row.stopId, followed.destination, followed.branch).mergeKey
    return row.upcoming.filter {
        it.destination == followed.destination &&
            topology.grouping(row.lineId, row.stopId, it.destination, it.branch).mergeKey == route
    }
}

/**
 * The trains with no time ([DepartureRow.untimed]) on the route [routeDepartures] lists: the followed
 * train's destination and topology route, as the card groups them ([DepartureRows.destinationLines]).
 * Empty for a status row.
 */
fun routeUntimed(row: DepartureRow, focus: RouteFocus?, topology: RouteTopology = RouteTopology.EMPTY): List<UntimedTrain> {
    val followed = followedDeparture(row, focus, topology) ?: return emptyList()
    val route = topology.grouping(row.lineId, row.stopId, followed.destination, followed.branch).mergeKey
    return row.untimed.filter {
        it.train.destination == followed.destination &&
            topology.grouping(row.lineId, row.stopId, it.train.destination, it.train.branch).mergeKey == route
    }
}
