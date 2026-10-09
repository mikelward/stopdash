package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * A ride put together from a route page's stop list, for a tapped stop's **Go** (SPEC *Route detail*):
 * from the boarding stop to a stop the train calls at further on, on the route's line. No Planner is
 * asked; the trip on the way follows the soonest train of the line along it, as it does a planned ride.
 */
object RouteRide {
    /** A guess at the time between calls, for the ride's arrival: it feeds only the trip's estimates. */
    val PER_STOP: Duration = Duration.ofMinutes(2)

    /**
     * The ride from [stops]' first (the boarding stop) to [toId], on [lineId]: its path every stop after
     * boarding through [toId], named as the list names them, placed where [positions] has them, and its end's
     * stop area ([areas]) kept for a bus, so a bus reaching the other stop of the pair counts. [departs] is
     * when the departure tapped leaves; it arrives [PER_STOP] a call later each. A stop the list calls at more than
     * once (a loop) is ridden to the call at [toIndex] where that's given, else the first after boarding. Null where [toId] isn't on the list after the
     * boarding stop, or the line isn't known.
     */
    @WorkerThread
    fun to(
        stops: List<RouteStop>,
        toId: String,
        mode: String,
        lineId: String,
        lineName: String,
        departs: Instant,
        towards: String,
        positions: Map<String, Pair<Double, Double>> = emptyMap(),
        areas: Map<String, String> = emptyMap(),
        // Its place in [stops] where the tap knew it: the call there it rides to, where it is [toId].
        toIndex: Int? = null,
    ): TripRoute? {
        if (lineId.isBlank() || stops.size < 2) return null
        val board = stops.first()
        // The first call there after boarding: on a loop that comes back to a stop (or to the boarding stop itself), the
        // soonest it gets there, never the boarding stop's own place at the head of the list.
        val at = toIndex?.takeIf { it in 1 until stops.size && stops[it].id == toId }
            ?: (1 until stops.size).firstOrNull { stops[it].id == toId }
            ?: return null
        val on = stops.subList(1, at + 1)
        val off = on.last()
        val bus = mode.equals("bus", ignoreCase = true)
        val leg = TripLeg(
            mode = mode,
            lineId = lineId,
            lineName = lineName.ifBlank { lineId },
            fromId = board.id,
            fromName = board.name,
            toId = off.id,
            toName = off.name,
            departure = departs,
            arrival = departs.plus(PER_STOP.multipliedBy(on.size.toLong())),
            path = on.map { it.id },
            pathNames = on.map { it.name },
            headings = listOfNotNull(towards.takeIf { it.isNotBlank() }),
            toArea = if (bus) areas[off.id].orEmpty() else "",
            fromAt = positions[board.id]?.let { Coordinates(it.first, it.second) },
            toAt = positions[off.id]?.let { Coordinates(it.first, it.second) },
        )
        return TripRoute(listOf(leg))
    }
}
