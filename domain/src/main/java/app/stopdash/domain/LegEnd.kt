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
 * A hub end is read where the last ride gets off by arriving at the destination's hub: by On the way
 * ([OnTheWay.arrivingIds]), and on the trip page by its verdicts and ride lines through [ids] ([RideEnds],
 * [offIds]), the Planner's own ride drawn cut and named at its first call there ([RideEnds.cut], the route
 * itself unchanged), so the stop list and alerts it shows end there too. Every other end still gets off where the
 * Planner's call is until its paths read it (TODO.md, *Arriving by hub at the first call*).
 *
 * [ids] are the TfL ids a hub end gets off at, every one of its hubs' stations and stops ([Hubs.idsOf]),
 * worked out through the model where the end is read ([RideEnds.of]); empty where they weren't, when the
 * ride gets off at its own call.
 */
data class LegEnd(val stepFree: StepFree, val level: Level, val places: Set<String>, val ids: Set<String> = emptySet()) {
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

    /**
     * The TfL ids [ride] gets off at, as a departure's calls and a line's route are matched against: a hub
     * end's [ids], every one of the destination's hubs' stations and stops, the ride's own call among them;
     * otherwise, and for a hub end whose ids weren't worked out, the ride's own call ([TripLeg.toId]) alone.
     */
    fun offIds(ride: TripLeg): Set<String> =
        if (level == Level.HUB && ids.isNotEmpty()) ids + ride.toId else setOf(ride.toId)

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
         * Getting [ride] off where the Planner does ([at] its own call for it, [callOf]), by the call's raw
         * TfL id: the end every ride had before arriving by hub. For a path holding one ride and not its
         * route, with no model at hand (several work it out in composition), so the call isn't resolved
         * through one: until arriving by hub reads the end, it only keys a ride, the same for the same leg
         * wherever it's worked out. [of], on a worker, resolves the call through the model.
         */
        fun planned(ride: TripLeg, stepFree: StepFree): LegEnd = at(unresolvedCallOf(ride), stepFree)

        /**
         * Each ride of [route] with where the Planner gets it off ([planned]), in order; walks are left
         * out, as [of] leaves them. What a ride's lines ([RideLines]) pair a route with until arriving
         * by hub reads the end. Not marked for a worker: the trip page still works out its ride lines in
         * composition (TODO.md, *The rest of the trip page off the main thread*), and this rides with them.
         */
        fun planned(route: TripRoute, stepFree: StepFree): List<Pair<TripLeg, LegEnd>> =
            route.rides.map { it to planned(it, stepFree) }

        /**
         * Each ride of [route] with where it gets off, in order; walks are left out, as they get off
         * nowhere. A middle ride ends at the Planner's call ([at]). The last ride ends at the
         * destination's [destinationHubs] where the Planner's own call for it is in one of them, and
         * otherwise like a middle ride: a trip to a place, which has no hub, or to a bus stop reached
         * by riding to a neighbor and walking on. Each call is resolved through [hubs] now, from the
         * leg's raw ids ([callOf]); with no model, each id is only itself. Work over the route's legs:
         * off the main thread.
         */
        @WorkerThread
        fun of(route: TripRoute, stepFree: StepFree, destinationHubs: Set<String>, hubs: Hubs?): List<Pair<TripLeg, LegEnd>> {
            val rides = route.rides
            return rides.mapIndexed { index, ride ->
                val call = callOf(ride, hubs)
                val end = if (index == rides.lastIndex && call.hub != null && call.hub in destinationHubs) {
                    LegEnd(stepFree, Level.HUB, destinationHubs)
                } else {
                    at(call, stepFree)
                }
                ride to end
            }
        }

        /**
         * The Planner's call for where [ride] gets off, resolved through [hubs] from the leg's raw ids
         * (dev-docs/station-resolution.md, *Where it's resolved*): its end by its pole and point. A bus
         * [onPoles] moved to its route's pole gets off at the Planner's stop pair ([TripLeg.toArea],
         * resolved through the Planner's pole, [TripLeg.plannedToId]) where the model holds the pair as one
         * stop with that pole in it: both directions of one road, the stop the Planner's pole is at.
         * Otherwise (a pair the model split, whose poles can be in different stops, a bus station's stand,
         * which no pair vouches for, or a pair the model doesn't hold) at that pole alone, never at the
         * Planner's pole's stop. With no model, as [unresolvedCallOf].
         */
        @WorkerThread
        internal fun callOf(ride: TripLeg, hubs: Hubs?): Resolved {
            if (hubs == null) return unresolvedCallOf(ride)
            if (ride.plannedToId.isEmpty()) return hubs.resolve(ride.toId, near = ride.toAt)
            val pair = ride.toArea.takeIf { it.isNotBlank() }?.let { hubs.resolve(it, pole = ride.plannedToId, near = ride.toAt) }
            if (pair?.station != null && pair.stops == setOf(pair.station) && ride.toId in pair.poles) return pair
            return Resolved(tflId = ride.toId, station = null, hub = null)
        }

        // The Planner's call for where [ride] gets off with no model: an end not moved is its own id
        // ([Resolved.exact]), and one moved to its route's pole that pole alone, as [callOf] has it.
        private fun unresolvedCallOf(ride: TripLeg): Resolved =
            if (ride.plannedToId.isEmpty()) Resolved.exact(ride.toId) else Resolved(tflId = ride.toId, station = null, hub = null)
    }
}
