package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.RideEnds
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripRoute

/**
 * A trip page's routes as it draws them ([state]), worked out on a worker from the routes as planned and
 * placed ([of], by identity): each last ride arriving by hub cut and named at its first call in the destination's
 * hubs ([RideEnds.cut]), and where every ride of those gets off ([ends]). Only for drawing: the trip's own routes
 * are never changed, and a copy drawn cut is its route as planned ([TripRoute.drawnFrom]) by key ([routeKey]), so
 * it's opened, saved, fetched for and ranked as that route, and a Start hands on the route as the Planner gave it
 * ([plannedOf]). A train through a change is made from the routes as planned and drawn cut the same way
 * ([withThroughRoutes]), so it too is drawn cut and started whole.
 *
 * [pending] while the routes in hand haven't been worked out yet: the page holds its last frame, or none,
 * rather than draw a ride's end that moves a moment later.
 */
internal class ShownRoutes private constructor(
    /** The state as planned and placed these were worked out for, by identity. */
    val of: TripViewModel.State,
    /** The step-free level they were worked out at. */
    val stepFree: StepFree,
    /**
     * The lifts out of service (TfL's lift ids) a step-free trip's ends were checked against, the very set the screen
     * passed, so it tells a new answer from this one by identity; empty at no level, where lifts change nothing.
     */
    val liftsOut: Set<String>,
    /** [of] with each route as shown. */
    val state: TripViewModel.State,
    /**
     * Where each ride of [state]'s routes gets off, and of [of]'s as planned: a train through a change is made
     * from those ([withThroughRoutes]).
     */
    val ends: RideEnds,
    /** Not worked out for [of] yet: [state] is [of] itself, standing in. */
    val pending: Boolean = false,
) {
    /**
     * The route a Start of [route], as shown, starts: the route as the Planner gave it ([TripRoute.drawnFrom]), never
     * the copy cut to draw ([RideEnds.cut]). A train through a change is made from the routes as planned and only
     * drawn cut ([withThroughRoutes]), so it's handed on whole too: the train to the Planner's end and the walk on from there.
     * Any other route is itself.
     */
    fun plannedOf(route: TripRoute): TripRoute = route.drawnFrom ?: route

    companion object {
        /** [state]'s routes as planned, each ride getting off where the Planner gets it off ([RideEnds.planned]). */
        fun planned(state: TripViewModel.State, stepFree: StepFree, liftsOut: Set<String> = emptySet()): ShownRoutes =
            ShownRoutes(state, stepFree, liftsOut, state, RideEnds.planned(stepFree))

        /**
         * [state]'s routes standing in while they're worked out ([of]): as planned, with the [held] answer's ends
         * (looked up by each ride's raw end, so a ride worked out before reads the same), those for the lifts it was
         * checked against while the new ones are.
         */
        fun pending(state: TripViewModel.State, stepFree: StepFree, held: ShownRoutes?, liftsOut: Set<String> = emptySet()): ShownRoutes {
            val ends = held?.ends?.takeIf { it.stepFree == stepFree } ?: RideEnds.planned(stepFree)
            return ShownRoutes(state, stepFree, liftsOut, state, ends, pending = !state.routes.isNullOrEmpty())
        }

        /**
         * [state]'s routes as shown at [stepFree] through [destination] ([RideEnds.cut], [RideEnds.of]), one for each
         * in the Planner's order: two the cut draws alike stay two, each its own plan ([TripRoute.drawnFrom]). A Direct
         * row's route ([TripViewModel.State.directKeys]) is drawn as the plan's are, its copy that route by key
         * ([routeKey]), so it's still the row's. A step-free trip's ends are checked against [destination]'s step-free
         * table ([RideEnds.Destination.access]), with the lifts in [liftsOut] out. Work over the routes: off the main thread.
         */
        @WorkerThread
        fun of(state: TripViewModel.State, stepFree: StepFree, destination: RideEnds.Destination?, liftsOut: Set<String> = emptySet()): ShownRoutes {
            val routes = state.routes ?: return planned(state, stepFree, liftsOut)
            val shown = RideEnds.cut(routes, stepFree, destination)
            if (shown.indices.all { shown[it] === routes[it] }) {
                return ShownRoutes(state, stepFree, liftsOut, state, RideEnds.of(routes, stepFree, destination))
            }
            // The cut copies' ends, and the routes' as planned, which the trains through a change are made from.
            val drawnCut = shown.filterIndexed { index, route -> route !== routes[index] }
            val ends = RideEnds.of(routes + drawnCut, stepFree, destination)
            return ShownRoutes(state, stepFree, liftsOut, state.copy(routes = shown), ends)
        }
    }
}
