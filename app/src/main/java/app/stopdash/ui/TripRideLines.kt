package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.LegEnd
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RideLines
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute

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

    companion object {
        /** None worked out: every ride has just its Planner's line, getting off where the Planner gets it off. */
        val NONE = TripRideLines(emptyMap(), plannedEnds(StepFree.DEFAULT))
    }
}

/**
 * [RideLines.of] from a trip's [state]: its boarding stops' arrivals and its stop pairs' poles, each ride
 * getting off where [ends] says, by default where the Planner does at the trip's [stepFree] level
 * ([plannedEnds]). Kept by the pair ([TripRideLines]), so each lookup names its ride's end.
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
