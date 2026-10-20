package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Where a ride gets off (dev-docs/station-resolution.md, phase 2, *Each leg carries where it ends*):
 * at its first call whose place at [level] is in [places], with the trip's [stepFree] level for the
 * calls it boards and alights at. One shape for every caller, so no path can miss a variant:
 *
 * - a middle ride, or a last ride the Planner gets off outside the destination's hubs: the station or
 *   bus stop of the Planner's call ([Level.STOP], its [Resolved.station]);
 * - a last ride reaching the destination: the destination's matchable hubs ([Level.HUB]);
 * - Direct to a place: the stations within the rider's walk of it ([Level.STOP]);
 * - a favorite ride kept as saved: its saved end's stop ([Level.STOP]), or an area a refresh has
 *   since split: that area's own poles ([Level.POLE]).
 *
 * Not read by any feature yet: phase 2 points arriving at it.
 */
data class LegEnd(val stepFree: StepFree, val level: Level, val places: Set<String>) {
    /** Which of a call's places [places] names. */
    enum class Level {
        /** A station, or a bus stop (both directions of its road): [Resolved.station]. */
        STOP,

        /** A hub: [Resolved.hub]. */
        HUB,

        /** Exact TfL ids: [Resolved.poles]. */
        POLE,
    }

    /**
     * Whether a ride gets off at [call]: its place at [level] is in [places]. Never at a call naming
     * no one TfL id (a National Rail CRS naming several records, [Resolved.tflId] null): the ride
     * gets off at one TfL id or doesn't move (dev-docs/station-resolution.md, *The first call must be
     * one TfL id*), so it falls back to the Planner's or the route's endpoint. Nor at a split stop
     * area the model couldn't place (its [Resolved.station] and [Resolved.hub] null): its
     * [Resolved.stops] and [Resolved.hubs] list every child only for avoids, closures and dismissals,
     * so it matches nothing at those levels.
     */
    fun endsAt(call: Resolved): Boolean {
        if (call.tflId == null) return false
        return when (level) {
            Level.STOP -> call.station in places
            Level.HUB -> call.hub in places
            Level.POLE -> call.poles.any { it in places }
        }
    }

    companion object {
        /**
         * Getting off where [call] is: its station or bus stop, or its own poles where the model
         * places it at none (a split area with no pole or point), so it still ends there and never at
         * a child stop it may not be.
         */
        fun at(call: Resolved, stepFree: StepFree): LegEnd =
            call.station?.let { LegEnd(stepFree, Level.STOP, setOf(it)) }
                ?: LegEnd(stepFree, Level.POLE, call.poles)

        /**
         * Each ride of [route] with where it gets off, in order; walks are left out, as they get off
         * nowhere. A middle ride ends at the Planner's call ([at]). The last ride ends at the
         * destination's [destinationHubs] where the Planner's own call for it is in one of them, and
         * otherwise like a middle ride: a trip to a place, which has no hub, or to a bus stop reached
         * by riding to a neighbor and walking on. Work over the route's legs: off the main thread.
         */
        @WorkerThread
        fun of(route: TripRoute, stepFree: StepFree, destinationHubs: Set<String>): List<Pair<TripLeg, LegEnd>> {
            val rides = route.rides
            return rides.mapIndexed { index, ride ->
                val call = ride.placeOf(ride.toId)
                val end = if (index == rides.lastIndex && call.hub != null && call.hub in destinationHubs) {
                    LegEnd(stepFree, Level.HUB, destinationHubs)
                } else {
                    at(call, stepFree)
                }
                ride to end
            }
        }
    }
}
