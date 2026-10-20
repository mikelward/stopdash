package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.LegEnd
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RideLines
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute

/**
 * [RideLines.of] from a trip's [state]: its boarding stops' arrivals and its stop pairs' poles, each ride
 * getting off where the Planner does at the trip's [stepFree] level ([plannedEnds]). By the ride alone:
 * every ride has the one end ([LegEnd.planned] reads only the ride), so no two pairs share a ride, until
 * arriving by hub reads the end and the page's lookups take the pair (TODO.md, *Arriving by hub*).
 */
internal fun rideLines(
    routes: List<TripRoute>,
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
): Map<TripLeg, RideLines> =
    RideLines.of(routes, plannedEnds(stepFree), state.live.mapValues { it.value.departures }, state.areaPoles, sequences, hidden)
        .mapKeys { it.key.first }

/** Where each ride of a route gets off at [stepFree]: the Planner's call ([LegEnd.planned]), until arriving by hub reads the end. */
internal fun plannedEnds(stepFree: StepFree): (TripRoute) -> List<Pair<TripLeg, LegEnd>> = { route -> LegEnd.planned(route, stepFree) }

/** [RideLines.lineIds] from a trip's [state]: the lines whose route data and status [rideLines] needs. */
internal fun rideLineIds(routes: List<TripRoute>, state: TripViewModel.State, hidden: Set<String>): List<String> =
    RideLines.lineIds(routes, state.live.mapValues { it.value.departures }, state.areaPoles, hidden)

/**
 * [state] with the routes its timed routes offer with a change left out ([RideLines.through]): a
 * train that runs on through the change is a route of its own, with one ride fewer, getting off where
 * the second ride does at [stepFree] ([plannedEnds]). One already
 * planned (the same lines and stops, [routeKey]) is left to the Planner's copy. The [open] route is
 * among them too, kept whole ([OpenRoute]), whether or not these routes make it now: its train may
 * not be predicted, or its change be past the cap ([TripViewModel.bestOf]).
 */
internal fun withThroughRoutes(
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    open: TripRoute? = null,
): TripViewModel.State {
    val routes = state.routes ?: return state
    val timed = TripViewModel.bestOf(state.shownRoutes(hidden).orEmpty(), direct = state.directKeys, delayed = state.delayedLines)
    val keys = routes.mapTo(HashSet(), ::routeKey)
    val through = RideLines.through(timed, plannedEnds(stepFree), state.live.mapValues { it.value.departures }, state.areaPoles, sequences, hidden)
        .filter { routeKey(it) !in keys }
    // The open route, unless it's planned or made now (the same route, freshly timed).
    val kept = open?.takeIf { route -> routeKey(route).let { key -> key !in keys && through.none { routeKey(it) == key } } }
    return if (through.isEmpty() && kept == null) state else state.copy(routes = routes + through + listOfNotNull(kept))
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
): OpenRoute = openRoutesOf(listOf(route), state, stepFree, sequences, hidden).getValue(routeKey(route))

/** [openRouteOf] for each of [routes], by its key, going over the board for trains through a change once. */
@WorkerThread
internal fun openRoutesOf(
    routes: List<TripRoute>,
    state: TripViewModel.State,
    stepFree: StepFree,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
): Map<String, OpenRoute> {
    val shown = state.shownRoutes(hidden).orEmpty()
    val planned = shown.mapTo(HashSet(), ::routeKey)
    val keys = routes.map(::routeKey).distinct()
    val ways by lazy {
        RideLines.throughWays(TripViewModel.bestOf(shown, direct = state.directKeys, delayed = state.delayedLines), plannedEnds(stepFree), state.live.mapValues { it.value.departures }, state.areaPoles, sequences, hidden)
            .associateBy { routeKey(it.route) }
    }
    return keys.associateWith { key ->
        if (key in planned) return@associateWith OpenRoute(key)
        // None: a route no plan offers, so the open route closes as soon as it's settled ([openRouteGone]).
        ways[key]?.let { OpenRoute(routeKey(it.from), it.at, it.route.legs[it.at], key) } ?: OpenRoute(key)
    }
}
