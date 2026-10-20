package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Where each ride of a trip's routes gets off ([LegEnd]), as the trip page reads it (TODO.md, *Arriving by
 * hub at the first call*): a last ride whose Planner's call is in the destination's hubs gets off at its
 * first call anywhere in them ([hub], with every TfL id of their stations and stops), and every other ride
 * where the Planner gets it off ([LegEnd.planned]).
 *
 * Which rides arrive by hub is worked out through the station model on a worker ([of]: [LegEnd.of], then
 * [Hubs.idsOf]) for the routes a plan brings, each by its last ride's raw end ([TripLeg.toId],
 * [TripLeg.toArea], [TripLeg.plannedToId], [TripLeg.toAt]); reading it ([ends]) only looks that end up, so it can be read
 * as a page is built. A route made since (a train through a change, its ride already cut at its first
 * call in the hub by [RideLines.through]) gets off where it's named to, as does every ride of a trip with
 * no model or no hub to arrive at.
 *
 * Not on a step-free trip: none of the hub's other calls is checked against today's lifts yet, so a
 * step-free ride still gets off where the Planner, who checked, does (TODO.md, *Decisions needing review*).
 */
class RideEnds private constructor(
    /** The trip's step-free level every end is for. */
    val stepFree: StepFree,
    // The destination's hub end, with its ids; null where no ride arrives by hub.
    private val hub: LegEnd?,
    // The last rides' raw ends ([keyOf]) the model placed in the destination's hubs.
    private val arriving: Set<List<Any?>>,
) {
    /** [route]'s rides, each with where it gets off, in order; walks are left out, as they get off nowhere. */
    fun ends(route: TripRoute): List<Pair<TripLeg, LegEnd>> {
        val rides = route.rides
        return rides.mapIndexed { index, ride ->
            val end = hub?.takeIf { index == rides.lastIndex && keyOf(ride) in arriving } ?: LegEnd.planned(ride, stepFree)
            ride to end
        }
    }

    /** Whether any ride arrives by hub: none, and every ride gets off where the Planner gets it off. */
    val arrivesByHub: Boolean get() = hub != null && arriving.isNotEmpty()

    // Equal by what they decide, so a plan worked out again to the same ends changes nothing that reads them.
    override fun equals(other: Any?): Boolean =
        other is RideEnds && other.stepFree == stepFree && other.hub == hub && other.arriving == arriving

    override fun hashCode(): Int = (stepFree.hashCode() * 31 + hub.hashCode()) * 31 + arriving.hashCode()

    /** The station model and the destination's matchable hubs ([Hubs.destinationHubs]) a trip's ends are resolved through. */
    class Destination(val model: Hubs, val hubs: Set<String>)

    companion object {
        /** Every ride getting off where the Planner gets it off at [stepFree] ([LegEnd.planned]). */
        fun planned(stepFree: StepFree): RideEnds = RideEnds(stepFree, null, emptySet())

        /**
         * Where [routes]' rides get off at [stepFree], arriving at [destination]'s hubs where the model
         * places a route's last ride's Planner's call in one of them ([LegEnd.of]); [planned] with no
         * destination, no hub to arrive at, or a step-free trip. Work over the routes and the hubs' ids:
         * off the main thread.
         */
        @WorkerThread
        fun of(routes: Collection<TripRoute>, stepFree: StepFree, destination: Destination?): RideEnds {
            if (destination == null || destination.hubs.isEmpty() || stepFree != StepFree.ANY) return planned(stepFree)
            val arriving = HashSet<List<Any?>>()
            var hub: LegEnd? = null
            for (route in routes) {
                val (ride, end) = LegEnd.of(route, stepFree, destination.hubs, destination.model).lastOrNull() ?: continue
                if (end.level != LegEnd.Level.HUB) continue
                arriving += keyOf(ride)
                if (hub == null) hub = end.copy(ids = destination.model.idsOf(end).keys)
            }
            return if (hub == null) planned(stepFree) else RideEnds(stepFree, hub, arriving)
        }

        // A ride's raw end, as [LegEnd.of] resolves it: the same in every copy of the ride a refresh times anew.
        // Its position too, which picks the stop of an area the model split ([Hubs.resolve]'s `near`).
        private fun keyOf(ride: TripLeg): List<Any?> = listOf(ride.toId, ride.toArea, ride.plannedToId, ride.toAt)
    }
}
