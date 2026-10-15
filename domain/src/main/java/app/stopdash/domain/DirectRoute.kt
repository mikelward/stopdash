package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration

/**
 * A Direct row's ride as a route of its trip (SPEC *Direct to a place*): the Planner's ride between the row's
 * two stops, with the walk to its stop and the walk on to the place put on either side by the phone.
 *
 * The Planner is asked for the ride from stop to stop because a trip *via* the row's stop only has to pass it,
 * on foot as well: from the rider standing beside the stop it answered with a train from the station across
 * the road and a bus from the next stand, and never the line tapped (maintainer's report, 2026-10-15). From
 * the stop itself it offers the lines leaving there. The walks are timed as the trip's own access walk is
 * ([TripTiming.accessWalk]), at the Planner's pace for the rider's speed, so they read as its would.
 */
object DirectRoute {
    /**
     * [between]'s ride, the Planner's route riding only the row's line between its two stops, walked to from
     * [from] (none from a trip that starts at the stop itself) and on to [place]. Null where it rides other
     * than once, or the Planner didn't place a stop a walk is timed from: a walk it can't time would offer a
     * train the rider can't make.
     */
    @WorkerThread
    fun around(between: TripRoute, from: Coordinates?, place: TripDestination.Place, speed: WalkingSpeed): TripRoute? {
        val ride = between.rides.singleOrNull() ?: return null
        val off = ride.toAt ?: return null
        val to = from?.let { here ->
            val board = ride.fromAt ?: return null
            val walk = TripTiming.accessWalk(meters(here, board), speed)
            // Named only by where it goes, as the Planner's walk from the rider's own position is.
            TripLeg(
                mode = TripLeg.WALKING,
                lineId = "",
                lineName = "",
                fromId = "",
                fromName = "",
                toId = ride.fromId,
                toName = ride.fromName,
                departure = ride.departure.minus(walk),
                arrival = ride.departure,
                toArea = ride.fromArea,
            )
        }
        val walkOn = TripTiming.accessWalk(meters(off, place.coordinate), speed)
        // Ending at the place itself, named as the rider knows it, as the Planner's last walk to a place is.
        val on = TripLeg(
            mode = TripLeg.WALKING,
            lineId = "",
            lineName = "",
            fromId = ride.toId,
            fromName = ride.toName,
            toId = "",
            toName = place.name,
            departure = ride.arrival,
            arrival = ride.arrival.plus(walkOn),
            fromArea = ride.toArea,
            fromAt = off,
        )
        return TripRoute(listOfNotNull(to, ride, on), between.fare)
    }

    /**
     * Whether no walk in [route] is longer than [maxWalk], as the Planner holds every walk it offers to the rider's
     * limit: a Direct row can leave from a stop a mile off, further than the rider chose to walk.
     */
    @WorkerThread
    fun walksWithin(route: TripRoute, maxWalk: MaxWalk): Boolean {
        val most = Duration.ofMinutes(maxWalk.minutes.toLong())
        return route.legs.none { it.isWalk && it.run > most }
    }

    private fun meters(a: Coordinates, b: Coordinates): Double =
        NearestStops.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}
