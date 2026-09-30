package app.stopdash.domain

/**
 * A trip to a place asked about once more (SPEC *Trips with a change*, *One ride to the fastest
 * route's last stop*): to a coordinate, the Planner's fewest changes can trade the one bus the whole
 * way for a train and a long walk from the station, as a walk counts as no change, so neither of the
 * plan's two requests offers the bus (maintainer's report, 2026-09-30). Asked for the fewest changes
 * to the place **via** the stop the fastest route gets off its last ride at ([stopId]), it finds that
 * bus, with the Planner's own walk on to the place ([fewerRides] keeps what it adds).
 *
 * The Planner builds the whole route, so nothing is joined here: an earlier design ended the plan at
 * the stop and added the fastest route's walk on, and every place the two met (which pole, what
 * change time, a bus later moved to its line's pole) needed its own care (maintainer, 2026-09-30).
 */
class FinalStop private constructor(
    /** Where the route should pass: the fastest route's last ride's stop pair ("490G…"), or its stop. */
    val stopId: String,
    // How many times the fastest route rides: what a route asked for here has to beat.
    private val rides: Int,
) {
    /**
     * The routes of [answer] that ride fewer times than the fastest route, and at least once. One
     * riding as often adds nothing the plan doesn't have, and a walk the whole way is no ride to it.
     */
    fun fewerRides(answer: List<TripRoute>): List<TripRoute> = answer.filter { it.rides.size in 1 until rides }

    companion object {
        /**
         * For [routes] planned to a place: the one arriving soonest by the Planner's times, the first
         * of a tie. Null when it rides once, as nothing rides fewer times and still rides; when it
         * ends on a ride, with no walk on to a place; or when its last ride gets off at no named stop.
         */
        fun of(routes: List<TripRoute>): FinalStop? {
            val fastest = routes.filter { it.legs.isNotEmpty() }.minByOrNull { it.legs.last().arrival } ?: return null
            val rides = fastest.rides.size
            if (rides < 2) return null
            val last = fastest.legs.indexOfLast { !it.isWalk }
            if (last == fastest.legs.lastIndex) return null
            val ride = fastest.legs[last]
            val stop = ride.toArea.ifEmpty { ride.toId }
            return if (stop.isBlank()) null else FinalStop(stop, rides)
        }
    }
}
