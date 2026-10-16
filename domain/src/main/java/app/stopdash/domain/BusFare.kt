package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration

/**
 * The fare for a London bus route the Planner left unpriced, where StopDash can be sure of it (SPEC
 * *Trips with a change → Fare*; report, 2026-10-15: a one-bus route came back unpriced beside priced
 * routes on the same bus). Every TfL bus costs the same, and the Hopper makes every bus boarded within
 * the hour of the first part of one fare, so a route riding only buses, on lines the Planner priced
 * in this same plan, between London stops and boarded within the hour, costs what the plan's priced
 * routes of that kind cost, as long as they all agree. Anything else stays unpriced: a tube or rail
 * part turns on zones and the time of day, and a bus the Planner never priced may not take pay as
 * you go at all.
 */
object BusFare {
    /** How long after the first bus another is boarded on the same fare: TfL's Hopper. */
    val HOPPER: Duration = Duration.ofHours(1)

    // A London bus stop's NaPTAN id starts with Greater London's area code; one outside London
    // may be on a bus that charges its own fare.
    private const val LONDON = "490"

    /** [routes], each unpriced one this can be sure of priced at the plan's own bus fare. */
    @WorkerThread
    fun filled(routes: List<TripRoute>): List<TripRoute> {
        if (routes.none { it.fare == null }) return routes
        val priced = routes.filter { it.fare != null && onOneBusFare(it) }
        // One price for every such route, or none to stand behind.
        val pence = priced.map { checkNotNull(it.fare).pence }.distinct().singleOrNull() ?: return routes
        val lines = priced.flatMapTo(HashSet()) { route -> route.rides.map { it.lineId } }
        return routes.map { route ->
            if (route.fare == null && onOneBusFare(route) && route.rides.all { it.lineId in lines }) route.copy(fare = TripFare(pence)) else route
        }
    }

    // Rides charged a fare of their own, more than a bus fare: a train within the pay as you go area
    // costs more than a bus however short, and one leaving it more still; the cable car more again. Not
    // a tram, which costs a bus fare and shares the Hopper, nor a replacement bus or a coach, which may
    // cost less or nothing, nor a river boat, since the Woolwich Ferry is free.
    private val DEARER = setOf("tube", "dlr", "overground", "elizabeth-line", "national-rail", "cable-car")

    // Heathrow's free travel zone, between whose stations the trains are free (Codex, #906): the
    // terminals' rail and tube stations, by their ids' stem so every terminal and platform is one, and
    // Hatton Cross on the Piccadilly line. TfL's Planner prices none of these rides; one leaving the
    // zone it prices as usual.
    private val HEATHROW = listOf("910GHTRW", "940GZZLUHR", "940GZZLUHNX")

    /**
     * Whether [route], priced or not, costs more than a bus fare for sure: it rides a train or the cable
     * car ([DEARER]), and a bus fare is the least any ride is charged, except a train between two of
     * Heathrow's terminals or Hatton Cross, which is free. So it can't undercut a route on one bus fare
     * ([onOneBusFare]), which the *Cheapest* header leans on where the Planner left it unpriced
     * ([routeLabels]; maintainer, 2026-10-16).
     */
    @WorkerThread
    fun costsMoreThanABus(route: TripRoute): Boolean = route.rides.any { ride ->
        DEARER.any { it.equals(ride.mode, ignoreCase = true) } && !(atHeathrow(ride.fromId) && atHeathrow(ride.toId))
    }

    private fun atHeathrow(id: String): Boolean = HEATHROW.any { id.startsWith(it) }

    /** Whether [route] rides only London buses, every one boarded within the hour of the first: one bus fare. */
    @WorkerThread
    fun onOneBusFare(route: TripRoute): Boolean {
        val rides = route.rides
        val first = rides.firstOrNull()?.departure ?: return false
        return rides.all { ride ->
            ride.isBus && ride.lineId.isNotBlank() && ride.fromId.startsWith(LONDON) && ride.toId.startsWith(LONDON) &&
                Duration.between(first, ride.departure) < HOPPER
        }
    }
}
