package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.LegEnd
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RideEnds
import app.stopdash.domain.RideLines
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

/**
 * A trip page's ride lines ([RideLines.of]), each ride's by the ride paired with where it gets off
 * ([ends], what they were worked out with), so a ride two routes share is looked up by its own route's
 * end: a ride last on one route and in the middle of another may get off at different places once
 * arriving by hub reads the end (TODO.md, *Arriving by hub*). Every lookup names the ride's end, by
 * its route ([endsOf]); a ride with none of its own found has just its Planner's line ([RideLines.only]).
 */
internal class TripRideLines(
    private val byEnd: Map<Pair<TripLeg, LegEnd>, RideLines>,
    private val ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>>,
    // [ends] of the routes [byEnd] was worked out from, so a lookup by a route doesn't work them out again.
    private val ended: Map<TripRoute, List<Pair<TripLeg, LegEnd>>> = emptyMap(),
) {
    /** [route]'s rides, each with where it gets off, in order, as these lines were worked out with. */
    fun endsOf(route: TripRoute): List<Pair<TripLeg, LegEnd>> = ended[route] ?: ends(route)

    /** The lines of [ride], by where it gets off; null for a ride these weren't worked out for. */
    operator fun get(ride: Pair<TripLeg, LegEnd>): RideLines? = byEnd[ride]

    /** The lines of [ride], by where it gets off: just its Planner's line where none were worked out. */
    fun of(ride: Pair<TripLeg, LegEnd>): RideLines = byEnd[ride] ?: RideLines.only(ride.first)

    /** The lines of [route]'s leg [leg], by where [route] gets it off; null where none were worked out (a walk's). */
    fun found(route: TripRoute, leg: TripLeg): RideLines? = endsOf(route).firstOrNull { it.first == leg }?.let(byEnd::get)

    /** The lines of [route]'s leg [leg] ([found]): just its Planner's line where none were worked out. */
    fun of(route: TripRoute, leg: TripLeg): RideLines = found(route, leg) ?: RideLines.only(leg)

    /** The lines of each of [route]'s legs ([of]), in order: one per leg, walks included. */
    fun legsOf(route: TripRoute): List<RideLines> {
        val ends = endsOf(route)
        return route.legs.map { leg -> ends.firstOrNull { it.first == leg }?.let(::of) ?: RideLines.only(leg) }
    }

    /** Where each of [route]'s legs gets off ([endsOf]), in order: null for a walk, which gets off nowhere. */
    fun legEndsOf(route: TripRoute): List<LegEnd?> {
        val ends = endsOf(route)
        return route.legs.map { leg -> ends.firstOrNull { it.first == leg }?.second }
    }

    /** Each line of [ride] ([of]) with where the ride gets off: what its trains are judged by ([TripVerdicts]). */
    fun linesOf(ride: Pair<TripLeg, LegEnd>): List<Pair<TripLeg, LegEnd>> = of(ride).legs.map { it to ride.second }

    /** The lines of each of [route]'s rides ([of]), in order. */
    fun ridesOf(route: TripRoute): List<RideLines> = endsOf(route).map(::of)

    /** The lines [route]'s rides show besides the Planner's: each ride's other lines' legs, in order. */
    fun othersOf(route: TripRoute): List<TripLeg> = endsOf(route).flatMap { ride -> byEnd[ride]?.legs.orEmpty().filter { it != ride.first } }

    /** Every line of every ride, whatever its route, with where the ride gets off ([linesOf]): for [warmVerdicts]. */
    val allLines: List<Pair<TripLeg, LegEnd>> get() = byEnd.flatMap { (ride, lines) -> lines.legs.map { it to ride.second } }

    /** The first ride whose lines hold [leg], by where it gets off, with its lines; null where none does. */
    fun holding(leg: TripLeg): Pair<Pair<TripLeg, LegEnd>, RideLines>? =
        byEnd.entries.firstOrNull { leg in it.value.legs }?.toPair()

    /**
     * The ride whose lines hold [leg] and that gets off at [end], else the first that holds it ([holding]):
     * a leg two routes share with different ends keeps the end of the route it was found under.
     */
    fun holding(leg: TripLeg, end: LegEnd): Pair<Pair<TripLeg, LegEnd>, RideLines>? =
        byEnd.entries.firstOrNull { it.key.second == end && leg in it.value.legs }?.toPair() ?: holding(leg)

    companion object {
        /** None worked out: every ride has just its Planner's line, getting off where the Planner gets it off. */
        val NONE = TripRideLines(emptyMap(), plannedEnds(StepFree.DEFAULT))
    }
}

/**
 * [RideLines.of] from a trip's [state]: its boarding stops' arrivals and its stop pairs' poles, each ride
 * getting off where [ends] says: on the trip page [RideEnds.ends] (a last ride arriving by hub), by default
 * where the Planner does at the trip's [stepFree] level ([plannedEnds]). Kept by the pair ([TripRideLines]),
 * so each lookup names its ride's end.
 */
internal fun rideLines(
    routes: List<TripRoute>,
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>> = plannedEnds(stepFree),
): TripRideLines {
    val ended = routes.associateWith(ends)
    val lines = RideLines.of(routes, { ended[it] ?: ends(it) }, state.live.mapValues { it.value.departures }, state.areaPoles, sequences, hidden)
    return TripRideLines(lines, ends, ended)
}

/** Where each ride of a route gets off at [stepFree]: the Planner's call ([LegEnd.planned]), where no [RideEnds] were worked out. */
internal fun plannedEnds(stepFree: StepFree): (TripRoute) -> List<Pair<TripLeg, LegEnd>> = { route -> LegEnd.planned(route, stepFree) }

/**
 * The station model and the destination's hubs a trip's [RideEnds] are worked out through, once read (on io,
 * by the model holding it): the trip page's ([TripViewModel.shownRoutes]) and a later trip's list's
 * ([LaterTripViewModel]) alike. Null where there's none (a place, no model), and where it couldn't be read,
 * said to [warn] under [label], so every ride gets off where the Planner does, as with no model.
 */
internal suspend fun Deferred<RideEnds.Destination?>.awaitDestination(warn: (String) -> Unit, label: String): RideEnds.Destination? =
    try {
        await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The rides get off where the Planner does, as with no model: said, not dropped.
        warn("$label: station model unreadable (${e.javaClass.simpleName})")
        null
    }

/** [RideLines.lineIds] from a trip's [state]: the lines whose route data and status [rideLines] needs. */
internal fun rideLineIds(routes: List<TripRoute>, state: TripViewModel.State, hidden: Set<String>): List<String> =
    RideLines.lineIds(routes, state.live.mapValues { it.value.departures }, state.areaPoles, hidden)

/**
 * [state] with the routes its timed routes offer with a change left out ([RideLines.through]): a
 * train that runs on through the change is a route of its own, with one ride fewer. Each is made from the routes
 * as planned ([planned], [plannedThroughWays]), getting off where the second ride's Planner's call is with its
 * walk on, and drawn as a plan is: where its last ride arrives by hub ([ends]), cut at its first call there, the
 * copy naming the train as planned it's drawn from ([TripRoute.drawnFrom]) and being that route by key
 * ([routeKey]), so what's opened, saved and started is the train to the Planner's end with its walk on. One
 * already planned or drawn (the same lines and stops, [routeKey], [legsKey]) is left to the Planner's copy. The
 * [open] route (as planned, [OpenRoute.routeIn], [openRef] saying where it's made) is among them too, kept whole
 * and drawn the same way, whether or not these routes make it now: its train may not be predicted, or its change
 * be past the cap ([TripViewModel.bestOf]).
 */
internal fun withThroughRoutes(
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    open: TripRoute? = null,
    ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>> = plannedEnds(stepFree),
    // The routes as planned the trains through are made from: [state]'s own where they're drawn as planned.
    planned: TripViewModel.State = state,
    // What the open route is ([OpenRoute]): for a train through, the plan and leg it's made from.
    openRef: OpenRoute? = null,
): TripViewModel.State {
    val routes = state.routes ?: return state
    val keys = routes.mapTo(HashSet(), ::routeKey)
    // A route drawn cut is its plan by key ([routeKey]), so one drawn the same as another is told by its legs.
    val drawn = routes.mapTo(HashSet(), ::legsKey)
    val through = plannedThroughWays(planned, stepFree, sequences, hidden, ends).map { it.route }
        .filter { routeKey(it) !in keys && legsKey(it) !in drawn }
    // The open route, unless it's planned or made now (the same route, freshly timed), or it reaches only another
    // station of the hubs at a level that doesn't arrive by hub ([hubOnlyUnchecked]): gone, as it isn't made now.
    val kept = open?.takeIf { route -> routeKey(route).let { key -> key !in keys && through.none { routeKey(it) == key } } }
        ?.takeUnless { openRef != null && hubOnlyUnchecked(openRef, planned.routes.orEmpty(), ends) }
        ?.let { route -> openRef?.let { drawnAtHub(route, it, planned.routes.orEmpty(), ends) } ?: route }
    return if (through.isEmpty() && kept == null) state else state.copy(routes = routes + through + listOfNotNull(kept))
}

/**
 * The trains through a change [state]'s timed routes offer ([RideLines.throughWays]), each [RideLines.Through.route]
 * as drawn: made from the route as planned, getting off where the second ride's Planner's call is with the walk
 * on from there kept, so the train as planned runs on end to end. Where its last ride arrives by hub ([ends]), it's
 * drawn ending at its first call in the destination's hubs, its legs after that gone, as a plan's is
 * ([RideEnds.cut]): the copy names the train as planned it's drawn from ([TripRoute.drawnFrom]). A line that reaches
 * only another station of the hubs is a route arriving there, as planned and drawn, the walk on from the old end
 * gone, since the rider has arrived (dev-docs/station-resolution.md, phase 2).
 */
internal fun plannedThroughWays(
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>> = plannedEnds(stepFree),
): List<RideLines.Through> {
    val timed = TripViewModel.bestOf(state.shownRoutes(hidden).orEmpty(), direct = state.directKeys, delayed = state.delayedLines)
    val arrivals = state.live.mapValues { it.value.departures }
    val asPlanned = RideLines.throughWays(timed, plannedEnds(stepFree), arrivals, state.areaPoles, sequences, hidden)
    // Each by the route it's made from, where, and its line.
    fun wayOf(way: RideLines.Through) = Triple(way.from, way.at, way.route.legs[way.at].lineId)
    val byWay = asPlanned.associateBy(::wayOf)
    val byHub = RideLines.throughWays(timed, ends, arrivals, state.areaPoles, sequences, hidden)
    val drawn = byHub.mapTo(HashSet(), ::wayOf)
    val shown = byHub.map { way ->
        val legs = way.route.legs
        val asPlannedWay = byWay[wayOf(way)]
        when {
            asPlannedWay != null && asPlannedWay.route == way.route -> asPlannedWay
            // A ride before the last, or one getting off at another record of its station rather than by hub: only
            // arriving by hub moves the end on, so the train as planned, where there is one, is the train shown.
            legs.drop(way.at + 1).any { !it.isWalk } || way.end.level != LegEnd.Level.HUB -> asPlannedWay ?: way
            // Arriving by hub: nothing after it is ridden from where it's drawn getting off.
            else -> way.copy(route = TripRoute(legs.take(way.at + 1), drawnFrom = asPlannedWay?.route))
        }
    }
    return (shown + asPlanned.filter { wayOf(it) !in drawn }).distinctBy { routeKey(it.route) }
}

/**
 * Whether [open], a train through a change opened reaching only another station of the destination's hubs (its ride
 * found again only short of the plan's legs after its second ride, [OpenRoute.endsShort]), isn't offered where
 * [ends] gets that second ride off: anywhere but by hub, as at a step-free level, the Planner's call is the one
 * checked, so a step-free trip ends where the Planner's does (SPEC *Step-free*). Any other open route: false.
 * One look up of the plan, as [drawnAtHub]'s: [withThroughRoutes] calls it where it's still worked out in composition
 * (TODO.md, the trains through a change off the main thread); the screen's close calls it on its worker.
 */
internal fun hubOnlyUnchecked(open: OpenRoute, planned: List<TripRoute>, ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>>): Boolean {
    if (!open.endsShort(planned)) return false
    val plan = planned.firstOrNull { routeKey(it) == open.plan } ?: return false
    val second = plan.legs.getOrNull(open.at + 1) ?: return false
    return ends(plan).firstOrNull { it.first == second }?.second?.level != LegEnd.Level.HUB
}

// [route], a train through a change as planned that the open route [open] names, drawn as one made now would be
// ([plannedThroughWays]): where the second ride of the plan it's made from arrives by hub ([ends]), its last ride
// cut at its first call in the hubs, the walk on gone, the copy naming [route] ([TripRoute.drawnFrom]).
private fun drawnAtHub(route: TripRoute, open: OpenRoute, planned: List<TripRoute>, ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>>): TripRoute {
    val plan = planned.firstOrNull { routeKey(it) == open.plan } ?: return route
    val second = plan.legs.getOrNull(open.at + 1) ?: return route
    val end = ends(plan).firstOrNull { it.first == second }?.second ?: return route
    if (end.level != LegEnd.Level.HUB) return route
    val ride = route.legs.getOrNull(open.at)?.takeIf { !it.isWalk } ?: return route
    if (route.legs.drop(open.at + 1).any { !it.isWalk }) return route
    val ids = end.offIds(second)
    val first = ride.path.indexOfFirst { it in ids }
    if (first < 0 || first == ride.path.lastIndex) return route
    val cut = ride.copy(
        toId = ride.path[first],
        toName = ride.pathNames.getOrNull(first)?.takeIf { it.isNotBlank() } ?: ride.toName,
        path = ride.path.take(first + 1),
        pathNames = ride.pathNames.take(first + 1),
        changeAfter = java.time.Duration.ZERO,
    )
    return TripRoute(route.legs.take(open.at) + cut, drawnFrom = route)
}

/**
 * [route], tapped among [state]'s routes ([withThroughRoutes]), as the route it opens ([OpenRoute]): a
 * planned route by its key; a train through a change with the planned route it's made from, as
 * [RideLines.through] made it, rather than one guessed by its shape.
 */
internal fun openRouteOf(
    route: TripRoute,
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>> = plannedEnds(stepFree),
): OpenRoute = openRoutesOf(listOf(route), state, stepFree, sequences, hidden, ends).getValue(routeKey(route))

/**
 * [openRouteOf] for each of [routes], by its key, going over the board for trains through a change once, each
 * ride getting off where [ends] says, as [withThroughRoutes] made them.
 */
@WorkerThread
internal fun openRoutesOf(
    routes: List<TripRoute>,
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>> = plannedEnds(stepFree),
): Map<String, OpenRoute> {
    val planned = state.shownRoutes(hidden).orEmpty().mapTo(HashSet(), ::routeKey)
    val keys = routes.map(::routeKey).distinct()
    // Made as [withThroughRoutes] made them, from the routes as planned: a copy drawn cut is its train as planned
    // by key ([routeKey]), so it opens the train to the Planner's end.
    val ways by lazy { plannedThroughWays(state, stepFree, sequences, hidden, ends).associateBy { routeKey(it.route) } }
    return keys.associateWith { key ->
        if (key in planned) return@associateWith OpenRoute(key)
        // None: a route no plan offers, so the open route closes as soon as it's settled ([openRouteGone]).
        ways[key]?.let { OpenRoute(routeKey(it.from), it.at, (it.route.drawnFrom ?: it.route).legs[it.at], key) } ?: OpenRoute(key)
    }
}
