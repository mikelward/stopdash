package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration

/**
 * Where each ride of a trip's routes gets off ([LegEnd]), as the trip page and a later trip's list read it
 * (TODO.md, *Arriving by hub at the first call*): a last ride whose Planner's call is in the destination's
 * hubs gets off at its first call anywhere in them ([hub], with every TfL id of their stations and stops),
 * and every other ride at the station or bus stop of the Planner's call ([stops], with every TfL id of it:
 * a station's records, a bus stop's poles), so a line calling at another record of a change's station gets
 * the rider there too, never at the hub's other stations. A ride ending at a stop area the model split, with
 * no pole or point to place it by, gets off where the Planner gets it off ([LegEnd.planned]).
 *
 * Which rides arrive by hub, and each other ride's station or stop, is worked out through the station model
 * on a worker ([of]: [LegEnd.of], then [Hubs.idsOf]) for the routes a plan brings, each by its ride's raw end
 * ([TripLeg.toId], [TripLeg.toArea], [TripLeg.plannedToId], [TripLeg.toAt]); reading it ([ends]) only looks
 * that end up, so it can be read as a page is built. A route made since (a train through a change, its ride
 * already cut at its first call in the hub by [RideLines.through]) gets off where it's named to, as does every
 * ride of a trip with no model.
 *
 * The Planner's own last ride arriving by hub is shown cut at its first call in the hubs ([cut]): a copy for the
 * page to draw, worked out from the route as planned, which is kept, started and asked for as the Planner gave it.
 *
 * Not on a step-free trip: none of a station's or hub's other calls is checked against today's lifts yet, so a
 * step-free ride still gets off where the Planner, who checked, does (TODO.md, *Decisions needing review*).
 */
class RideEnds private constructor(
    /** The trip's step-free level every end is for. */
    val stepFree: StepFree,
    // The destination's hub end, with its ids; null where no ride arrives by hub.
    private val hub: LegEnd?,
    // The last rides' raw ends ([keyOf]) the model placed in the destination's hubs.
    private val arriving: Set<List<Any?>>,
    // Every other ride's station or bus stop end, with its ids, by its raw end ([keyOf]).
    private val stops: Map<List<Any?>, LegEnd>,
) {
    /** [route]'s rides, each with where it gets off, in order; walks are left out, as they get off nowhere. */
    fun ends(route: TripRoute): List<Pair<TripLeg, LegEnd>> {
        val rides = route.rides
        return rides.mapIndexed { index, ride ->
            val key = keyOf(ride)
            val end = hub?.takeIf { index == rides.lastIndex && key in arriving } ?: stops[key] ?: LegEnd.planned(ride, stepFree)
            ride to end
        }
    }

    /** Whether any ride arrives by hub: none, and every ride gets off where the Planner gets it off. */
    val arrivesByHub: Boolean get() = hub != null && arriving.isNotEmpty()

    // Equal by what they decide, so a plan worked out again to the same ends changes nothing that reads them.
    override fun equals(other: Any?): Boolean =
        other is RideEnds && other.stepFree == stepFree && other.hub == hub && other.arriving == arriving && other.stops == stops

    override fun hashCode(): Int = ((stepFree.hashCode() * 31 + hub.hashCode()) * 31 + arriving.hashCode()) * 31 + stops.hashCode()

    /**
     * The station model and the destination's matchable hubs ([Hubs.destinationHubs]) a trip's ends are resolved
     * through: none for a trip to a place, which has no hub, whose rides still get off at their stations ([of]).
     */
    class Destination(val model: Hubs, val hubs: Set<String>)

    companion object {
        /** Every ride getting off where the Planner gets it off at [stepFree] ([LegEnd.planned]). */
        fun planned(stepFree: StepFree): RideEnds = RideEnds(stepFree, null, emptySet(), emptyMap())

        /**
         * Where [routes]' rides get off at [stepFree], arriving at [destination]'s hubs where the model
         * places a route's last ride's Planner's call in one of them ([LegEnd.of]), and every other ride at
         * the station or bus stop the model places its Planner's call at; [planned] with no destination or on
         * a step-free trip. Work over the routes and their ends' ids: off the main thread.
         */
        @WorkerThread
        fun of(routes: Collection<TripRoute>, stepFree: StepFree, destination: Destination?): RideEnds {
            if (destination == null || stepFree != StepFree.ANY) return planned(stepFree)
            val model = destination.model
            val arriving = HashSet<List<Any?>>()
            val stops = HashMap<List<Any?>, LegEnd>()
            // Each end's ids once: many routes change at one station.
            val withIds = HashMap<LegEnd, LegEnd>()
            var hub: LegEnd? = null
            for (route in routes) {
                val ends = LegEnd.of(route, stepFree, destination.hubs, model)
                for ((index, pair) in ends.withIndex()) {
                    val (ride, end) = pair
                    when {
                        end.level == LegEnd.Level.HUB && index == ends.lastIndex -> {
                            arriving += keyOf(ride)
                            if (hub == null) hub = end.copy(ids = model.idsOf(end).keys)
                        }
                        end.level == LegEnd.Level.STOP ->
                            stops[keyOf(ride)] = withIds.getOrPut(end) { end.copy(ids = model.idsOf(end).keys) }
                    }
                }
            }
            return if (hub == null && stops.isEmpty()) planned(stepFree) else RideEnds(stepFree, hub, arriving, stops)
        }

        /**
         * [routes] as a trip page and a later trip's list show them, in order, one for each: each whose last ride
         * arrives by hub ([of]) a copy cut at that ride's first call in the destination's hubs where the Planner gets
         * it off later, at another station or bus stop of them: the ride gets off and is named there, its stop list
         * ends there, and the legs after it (a walk on within the hub) go, since the rider has arrived
         * (dev-docs/station-resolution.md, phase 2). Only for drawing: the route itself is never changed, so a trip
         * started from it, the plan kept for reuse and On the way (which moves the end itself,
         * [OnTheWay.arrivingIds]) all read the Planner's; the copy names it ([TripRoute.drawnFrom]), so it is that
         * route wherever routes are told apart, and two routes cut alike stay two, each its own. Every other route is
         * the same object: no destination, no hub to arrive at, a step-free trip (as [of]), a first call in the hubs
         * at the Planner's own station or stop, or one naming no one TfL id to get off at (a bus stop area, its two
         * poles) or no name, where the ride doesn't move.
         *
         * The cut ride keeps the Planner's arrival at the call it no longer reaches, the latest it gets there,
         * since the Planner timed only the whole ride (TODO.md, *Decisions needing review*); a fare is kept only
         * for a bus, every London bus costing the same ([BusFare]), as the Planner priced the ride to its old end.
         * Work over the routes and their calls: off the main thread.
         */
        @WorkerThread
        fun cut(routes: List<TripRoute>, stepFree: StepFree, destination: Destination?): List<TripRoute> {
            if (destination == null || destination.hubs.isEmpty() || stepFree != StepFree.ANY) return routes
            val model = destination.model
            return routes.map { route -> cutAt(route, stepFree, destination.hubs, model) ?: route }
        }

        // [route] cut at its last ride's first call in [destinationHubs] ([cut]), or null where it isn't.
        @WorkerThread
        private fun cutAt(route: TripRoute, stepFree: StepFree, destinationHubs: Set<String>, model: Hubs): TripRoute? {
            val (ride, end) = LegEnd.of(route, stepFree, destinationHubs, model).lastOrNull() ?: return null
            if (end.level != LegEnd.Level.HUB) return null
            // Where the Planner gets it off, as [LegEnd.of] resolved it: by its pole and point, never its raw area.
            val own = LegEnd.callOf(ride, model).station ?: return null
            val first = ride.path.indexOfFirst { end.endsAt(model.resolve(it)) }
            if (first < 0) return null
            val call = model.resolve(ride.path[first])
            // Its own station or stop (another of its records or platforms, the other side of its road): already there.
            if (call.station == own) return null
            // One TfL id to get off at, or the ride doesn't move: a stop area names its poles (*The first call must be one TfL id*).
            val id = call.tflId?.takeIf { call.poles == setOf(it) } ?: return null
            val name = ride.pathNames.getOrNull(first)?.takeIf { it.isNotBlank() } ?: return null
            val cut = ride.copy(
                toId = id,
                toName = name,
                path = ride.path.take(first + 1),
                pathNames = ride.pathNames.take(first + 1),
                changeAfter = Duration.ZERO,
                // Its stop pair and position were the Planner's end's.
                toArea = "",
                toAt = null,
                plannedToId = "",
            )
            val last = route.legs.indexOfLast { !it.isWalk }
            return route.copy(legs = route.legs.take(last) + cut, fare = route.fare?.takeIf { ride.isBus }, drawnFrom = route)
        }

        // A ride's raw end, as [LegEnd.of] resolves it: the same in every copy of the ride a refresh times anew.
        // Its position too, which picks the stop of an area the model split ([Hubs.resolve]'s `near`).
        private fun keyOf(ride: TripLeg): List<Any?> = listOf(ride.toId, ride.toArea, ride.plannedToId, ride.toAt)
    }
}
