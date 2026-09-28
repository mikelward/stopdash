package app.stopdash.ui

import app.stopdash.domain.LineSequence
import app.stopdash.domain.RideLines
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute

/** [RideLines.of] from a trip's [state]: its boarding stops' arrivals and its stop pairs' poles. */
internal fun rideLines(
    routes: List<TripRoute>,
    state: TripViewModel.State,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
): Map<TripLeg, RideLines> = RideLines.of(routes, state.live.mapValues { it.value.departures }, state.areaPoles, sequences, hidden)

/** [RideLines.lineIds] from a trip's [state]: the lines whose route data and status [rideLines] needs. */
internal fun rideLineIds(routes: List<TripRoute>, state: TripViewModel.State, hidden: Set<String>): List<String> =
    RideLines.lineIds(routes, state.live.mapValues { it.value.departures }, state.areaPoles, hidden)

/**
 * [state] with the routes its timed routes offer with a change left out ([RideLines.through]): a
 * train that runs on through the change is a route of its own, with one ride fewer. One already
 * planned (the same lines and stops, [routeKey]) is left to the Planner's copy.
 */
internal fun withThroughRoutes(
    state: TripViewModel.State,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
): TripViewModel.State {
    val routes = state.routes ?: return state
    val timed = TripViewModel.bestOf(state.shownRoutes(hidden).orEmpty())
    val keys = routes.mapTo(HashSet(), ::routeKey)
    val through = RideLines.through(timed, state.live.mapValues { it.value.departures }, state.areaPoles, sequences, hidden)
        .filter { routeKey(it) !in keys }
    return if (through.isEmpty()) state else state.copy(routes = routes + through)
}
