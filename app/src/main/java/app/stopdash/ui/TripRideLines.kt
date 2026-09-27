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
