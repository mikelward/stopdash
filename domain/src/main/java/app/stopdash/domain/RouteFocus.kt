package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The route a user tapped on a card — its [destination] and the TfL [branch] of the row's soonest
 * train — so the detail follows that route rather than whichever of the row's trains is soonest (a
 * card shows one route row per destination and branch of a line and direction).
 *
 * The raw branch, not the row's topology merge key, is what's kept: the topology can change while
 * the page is open (it loads asynchronously on a cold start), and a stored key would stop matching.
 * Which trains count as this route is worked out when it's used ([followedDeparture]), under the
 * topology in force then.
 *
 * [way] names the train's own way ([Departure.wayKey]) where its row groups it with a train to the same
 * place by the same via that runs another (a bus route's variants, told apart only by their stops; a
 * loop's trains on platforms of opposite bounds): the page then follows a train run that way, from a route
 * chip handing over to it (Codex on #1038, #1045). Null follows the place and via alone, as a tap on a row
 * does.
 */
data class RouteFocus(val destination: String, val branch: String?, val way: String? = null) {
    companion object {
        /** The focus for a card's route row: its soonest timed train's, else (every train has no time) its soonest's. */
        fun of(group: DestinationGroup) =
            RouteFocus(group.destination, (group.times.firstOrNull() ?: group.untimed.firstOrNull()?.train)?.branch)
    }
}

/**
 * The departure the detail follows: the soonest on the [focus] route — the trains the card groups
 * into that row under [topology] (a merged row can mix branches that share the path from here) — at
 * any of the stop's platforms among [siblings] ([otherPlatforms]) as on the row's own: the page is the
 * route's, not the platform's, so a train moved to another platform is still followed (maintainer,
 * 2026-10-15). Only once the route has no train left at the stop, the row's soonest. The page's title
 * follows the same train, so the title and the stop list always agree. It never slips to another route
 * row's trains (the same destination on a branch the card shows as its own row).
 *
 * A route whose every train has no time ([DepartureRow.untimed]) follows its soonest by schedule,
 * which places the route as a timed train would (destination, branch, platform), never another
 * route's train. Nothing reads a time off the train it follows.
 */
fun followedDeparture(
    row: DepartureRow,
    focus: RouteFocus?,
    topology: RouteTopology = RouteTopology.EMPTY,
    siblings: List<DepartureRow> = emptyList(),
): Departure? {
    if (focus != null) {
        val rows = if (siblings.isEmpty()) listOf(row) else listOf(row) + otherPlatforms(row, siblings)
        soonestOnRoute(rows, focus, topology)?.let { return it.first }
    }
    return row.upcoming.firstOrNull() ?: row.untimed.firstOrNull()?.train
}

/**
 * The soonest of [row]'s own trains on the [focus] route ([followedDeparture]), timed else by schedule;
 * null once it has none left there.
 */
fun focusedDeparture(row: DepartureRow, focus: RouteFocus, topology: RouteTopology = RouteTopology.EMPTY): Departure? =
    soonestOnRoute(listOf(row), focus, topology)?.first

/**
 * Where the [focus] route of [lineId] at [stopId], the way [direction] runs, still leaves from once the row
 * its page was opened on is gone (its platform's last train left, or the train moved platform): the row among
 * [rows] holding the route's soonest train, timed else by schedule, on any platform the same way
 * ([otherPlatforms]'s rule). Null once no platform at the stop has one, so the page closes then (maintainer,
 * 2026-10-15).
 */
@WorkerThread
fun routeAnchor(
    rows: List<DepartureRow>,
    stopId: String,
    lineId: String,
    direction: String,
    focus: RouteFocus,
    topology: RouteTopology = RouteTopology.EMPTY,
): DepartureRow? {
    val way = rows.filter {
        it.stopId == stopId && it.lineId == lineId && it.direction == direction && (direction.isNotBlank() || nationalRail(it))
    }
    return soonestOnRoute(way, focus, topology)?.second
}

/**
 * The soonest train on the [focus] route among [rows], timed else by schedule, with the row it's in: run
 * [RouteFocus.way] where the focus names one, else (none of those left) any on the route, so the page
 * stays on the place its way's train left from.
 */
private fun soonestOnRoute(rows: List<DepartureRow>, focus: RouteFocus, topology: RouteTopology): Pair<Departure, DepartureRow>? {
    if (rows.isEmpty()) return null
    val first = rows.first()
    val route = topology.grouping(first.lineId, first.stopId, focus.destination, focus.branch).mergeKey
    fun onRoute(it: Departure) = it.destination == focus.destination &&
        topology.grouping(first.lineId, first.stopId, it.destination, it.branch).mergeKey == route
    val way = focus.way
    if (way != null) soonestOf(rows) { onRoute(it) && it.wayKey == way }?.let { return it }
    return soonestOf(rows, ::onRoute)
}

/** The soonest train among [rows] that [counts], timed else by schedule, with the row it's in. */
private inline fun soonestOf(rows: List<DepartureRow>, counts: (Departure) -> Boolean): Pair<Departure, DepartureRow>? {
    var timed: Pair<Departure, DepartureRow>? = null
    for (row in rows) {
        val train = row.upcoming.firstOrNull(counts) ?: continue
        if (timed == null || train.expectedArrival < timed.first.expectedArrival) timed = train to row
    }
    if (timed != null) return timed
    var untimed: Pair<Departure, DepartureRow>? = null
    for (row in rows) {
        val train = row.untimed.firstOrNull { counts(it.train) }?.train ?: continue
        if (untimed == null || train.expectedArrival < untimed.first.expectedArrival) untimed = train to row
    }
    return untimed
}


/**
 * Every departure on the route the detail follows — the [followedDeparture]'s destination and
 * topology route, as the card groups its trains ([DepartureRows.destinationLines]) — soonest-first and
 * uncapped, so the page can list more than the card's few. It keys on the followed train, not [focus]
 * directly, so the list always matches the title, fallback included. Every train the card shows on
 * that route is listed, whichever way round a loop it runs ([Departure.via]): the stop list follows
 * the soonest, but a train to the same terminus another way is still one the rider can take there
 * (maintainer, 2026-10-14). With [siblings], the route's trains on the stop's other platforms join it
 * ([otherPlatforms]). Empty for a status row.
 */
@WorkerThread
fun routeDepartures(
    row: DepartureRow,
    focus: RouteFocus?,
    topology: RouteTopology = RouteTopology.EMPTY,
    siblings: List<DepartureRow> = emptyList(),
): List<Departure> {
    val followed = followedDeparture(row, focus, topology, siblings) ?: return emptyList()
    val route = topology.grouping(row.lineId, row.stopId, followed.destination, followed.branch).mergeKey
    val others = otherPlatforms(row, siblings)
    val trains = if (others.isEmpty()) row.upcoming else (row.upcoming + others.flatMap { it.upcoming }).sortedBy { it.expectedArrival }
    return trains.filter {
        it.destination == followed.destination &&
            topology.grouping(row.lineId, row.stopId, it.destination, it.branch).mergeKey == route
    }
}

/**
 * The trains with no time ([DepartureRow.untimed]) on the route [routeDepartures] lists: the followed
 * train's destination and topology route, as the card groups them ([DepartureRows.destinationLines]),
 * on any of the stop's platforms [siblings] hold. Empty for a status row.
 */
@WorkerThread
fun routeUntimed(
    row: DepartureRow,
    focus: RouteFocus?,
    topology: RouteTopology = RouteTopology.EMPTY,
    siblings: List<DepartureRow> = emptyList(),
): List<UntimedTrain> {
    val followed = followedDeparture(row, focus, topology, siblings) ?: return emptyList()
    val route = topology.grouping(row.lineId, row.stopId, followed.destination, followed.branch).mergeKey
    val others = otherPlatforms(row, siblings)
    val trains = if (others.isEmpty()) row.untimed else (row.untimed + others.flatMap { it.untimed }).sortedBy { it.train.expectedArrival }
    return trains.filter {
        it.train.destination == followed.destination &&
            topology.grouping(row.lineId, row.stopId, it.train.destination, it.train.branch).mergeKey == route
    }
}

/**
 * The rows among [siblings] of [row]'s line at its stop, the way it runs, on another platform — or none
 * named — than [row]'s (a row is a line, way and platform: [DepartureRow.directionKey]): where its route's
 * trains also leave from, so its page lists them all, whichever platform each is at (maintainer,
 * 2026-10-14). The same stop's rows come from one fetch, so they're as current as [row]. A way TfL names
 * ("outbound") must match, so a loop's trains the other way round to the same terminus stay off. A
 * National Rail board names none, and its destinations tell its ways apart. Any other line whose way TfL
 * left off is keyed by its platform ([DepartureRows.directionKeyOf]), which is then the only way it's told
 * apart (a loop's two platforms to one terminus), so it takes no other platform's.
 */
@WorkerThread
fun otherPlatforms(row: DepartureRow, siblings: List<DepartureRow>): List<DepartureRow> {
    if (row.direction.isBlank() && !nationalRail(row)) return emptyList()
    return siblings.filter {
        it.stopId == row.stopId && it.lineId == row.lineId && it.direction == row.direction &&
            (it.directionKey != row.directionKey || it.platform != row.platform)
    }
}

private fun nationalRail(row: DepartureRow): Boolean =
    row.mode.ifBlank { Connections.knownMode(row.lineId).orEmpty() }.equals("national-rail", ignoreCase = true)
