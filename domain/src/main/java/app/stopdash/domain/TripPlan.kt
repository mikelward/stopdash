package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * One leg of a planned trip (SPEC *Trips with a change*): a ride on one line from [fromId] to [toId],
 * or a walk between them ([isWalk]). The times are TfL Journey Planner's timetable view — the lines
 * and changes are its, the live times are StopDash's own ([TripTiming]).
 *
 * [path] is the stop ids the leg calls at after boarding, through [toId], and [pathNames] their names
 * (cleaned; empty when not known), as TfL can name a station by another id; [changeAfter] is the time
 * the Planner allows to change to the next leg; [headings] is the terminus the Planner's service
 * runs to ("Stanmore"), as its front and a station card show it, for when no live train does.
 *
 * A bus leg's ends are stop pairs to the Planner ("490G…", a road's two poles), [fromArea] and
 * [toArea]; [fromId] and [toId] are the poles it names within them, which can be the other side of
 * the road from the one the bus uses. Empty for any other stop.
 */
data class TripLeg(
    val mode: String,
    val lineId: String,
    val lineName: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val departure: Instant,
    val arrival: Instant,
    val path: List<String> = emptyList(),
    val pathNames: List<String> = emptyList(),
    val changeAfter: Duration = Duration.ZERO,
    val headings: List<String> = emptyList(),
    val fromArea: String = "",
    val toArea: String = "",
) {
    val isWalk: Boolean get() = mode.equals(WALKING, ignoreCase = true)

    /** The Planner's time on board (or on foot). */
    val run: Duration get() = Duration.between(departure, arrival).coerceAtLeast(Duration.ZERO)

    /** How many stops the leg rides before getting off ("6 stops to Whitechapel"). */
    val stops: Int get() = path.size

    companion object {
        const val WALKING = "walking"
    }
}

/** One route the Planner offered: its [legs] in order. */
data class TripRoute(val legs: List<TripLeg>) {
    /** The legs ridden, walks left out: the line pills a route shows. */
    val rides: List<TripLeg> get() = legs.filterNot { it.isWalk }
}

/**
 * Where a trip is planned **to** (SPEC *Trips* / D9): a known TfL [Stop] by id, or a [Place] at a
 * coordinate — a favorite, or a resolved postcode — which TfL routes to with a final walk leg. Kept a
 * type rather than a bare string so a coordinate destination can't be mistaken for a stop id (it has
 * no live arrivals to fetch; SPEC D9).
 */
sealed interface TripDestination {
    data class Stop(val id: String) : TripDestination
    data class Place(val coordinate: Coordinates, val name: String) : TripDestination
}

/**
 * Plans a trip from a stop to a [TripDestination], behind a domain interface so the trip's logic is
 * tested against recorded fixtures (SPEC *Testing*). Returns the Planner's routes in its own order;
 * throws a [TflException] on a transport or decode failure, as [TflClient] does.
 */
interface JourneyPlanner {
    suspend fun journeys(fromId: String, to: TripDestination): List<TripRoute>
}
