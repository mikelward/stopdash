package app.stopdash.domain

/**
 * Where a trip on the way is planned again from: the station still ahead on its route that's nearest
 * the rider (maintainer, 2026-10-02). A station, not the rider's own position: the Planner then picks up
 * the trip from somewhere it already goes, rather than from a pavement beside a moving train or a
 * platform it can't tell from the street above.
 *
 * "Ahead" is every stop of a ride the rider can still board or get off at: a ride not yet boarded from
 * its boarding stop on; the ride they're on from the first stop the train hasn't passed ([rideAhead]);
 * every later ride whole. Walks add none of their own, since each starts and ends at a ride's stop. A
 * stop already passed never counts: planning from one would offer a train the rider has left behind.
 */
object ReplanOrigin {
    /**
     * The stop [trip] is planned again from, by id, or null when no ride is left (a final walk, or
     * arrived): nothing to plan.
     *
     * [rider] is where the rider is, a fix fresh enough to plan from, or null. With one, the stop ahead
     * nearest it among those [positions] places is chosen, the earlier on the route on a tie. Without
     * one, or with none placed, the first stop ahead: where the trip has the rider next. A fix that
     * doesn't [placesRider] counts as none.
     *
     * [rideAhead] is, while the rider is on the current leg's train, the index into its path (the
     * ride as that train takes it, [OnTheWay.ridden]) of the
     * first stop the train hasn't passed (where they get off is its last); null when
     * they aren't on it yet, when the leg counts from its boarding stop, or when how far it's gone isn't
     * known, when only where they get off counts: any stop before it may be behind them.
     */
    fun of(trip: ActiveTrip, rider: LocationFix?, positions: Map<String, Coordinates>, rideAhead: Int?): String? {
        val ahead = stopsAhead(trip, rideAhead)
        if (ahead.isEmpty()) return null
        val at = rider?.takeIf(::placesRider)?.coordinates ?: return ahead.first()
        // minBy keeps the first of equals: the earlier stop on the route.
        return ahead.filter { it in positions }
            .minByOrNull { NearestStops.distanceMeters(at.latitude, at.longitude, positions.getValue(it).latitude, positions.getValue(it).longitude) }
            ?: ahead.first()
    }

    /**
     * Whether [fix] says where the rider is well enough to choose a station by: precise, sure to within
     * [OnTheWay.AT_STOP_WITHIN_METERS] (a stop's own reach, well inside the gap between stations), and
     * taken within [OnTheWay.FIX_FRESH_WITHIN_MILLIS], since a rider on a train is soon elsewhere
     * (Codex on #477). One that doesn't say its accuracy or age can't be trusted that far.
     */
    fun placesRider(fix: LocationFix): Boolean {
        if (fix.isFallback || fix.isCoarse) return false
        val accuracy = fix.accuracyMeters ?: return false
        val age = fix.ageMillis ?: return false
        return accuracy <= OnTheWay.AT_STOP_WITHIN_METERS && age <= OnTheWay.FIX_FRESH_WITHIN_MILLIS
    }

    /** The stops ahead on [trip]'s route, in route order, each once ([of]). */
    fun stopsAhead(trip: ActiveTrip, rideAhead: Int?): List<String> {
        val stops = LinkedHashSet<String>()
        trip.route.legs.withIndex().drop(trip.legIndex).forEach { (index, planned) ->
            // The ride as the rider takes it: another of its lines' train goes by its own stops
            // between, and [rideAhead] counts along those ([OnTheWay.ridden], Codex on #477).
            val leg = if (index == trip.legIndex) OnTheWay.ridden(trip) ?: planned else planned
            if (leg.isWalk) return@forEach
            // Its path runs from the stop after boarding through where it gets off ([TripLeg.path]).
            if (index == trip.legIndex && rideAhead != null) {
                stops += leg.path.drop(rideAhead.coerceAtLeast(0))
            } else if (index == trip.legIndex && (trip.boarded || trip.onBoardSeen)) {
                // On board, how far along unknown: any stop before where they get off may be behind
                // them, so only that one counts (Codex on #477).
            } else {
                stops += leg.fromId
                stops += leg.path
            }
            stops += leg.toId
        }
        return stops.filter { it.isNotBlank() }
    }
}
