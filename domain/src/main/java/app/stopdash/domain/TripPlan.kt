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
 *
 * [fromAt] is where the leg boards, as the Planner places the stop (a public stop position, null when
 * it gives none): a trip on the way sees from it whether the rider left on the train.
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
    val fromAt: Coordinates? = null,
    // Where the leg gets off: a public stop position, like [fromAt].
    val toAt: Coordinates? = null,
    // The stops the Planner named, once an end has been moved to the stop its bus really uses
    // ([onPoles]): empty while it hasn't. What the leg is known by, so a move keeps its identity.
    val plannedFromId: String = "",
    val plannedToId: String = "",
) {
    val isWalk: Boolean get() = mode.equals(WALKING, ignoreCase = true)

    /** A bus ride, whose stop the Planner can name on the wrong pole or stand ([onPoles]). */
    val isBus: Boolean get() = mode.equals("bus", ignoreCase = true)

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

    /**
     * Where the route reaches one of [destinations] and then rides on: the destination's ids it was
     * at (a stop, or a pole and its stop pair) when it rode through and out, or rode again after a
     * change or a walk there. Empty when it doesn't. A trip to a station complex is planned to each
     * of its stops, and the plan to one of them (a bus stop on the far side) can ride through another
     * and come back: to King's Cross, on past it and a bus back from the next station (maintainer's
     * report, 2026-09-27). Whether such a route is kept is [withoutDetours]'s question.
     *
     * A ride has gone through the destination when, after a call at one of [destinations], it calls
     * somewhere that isn't one. That asks nothing of how TfL names where the ride ends: its path may
     * list the end under another id, list a pole and then its stop pair ([TripLeg.toArea]), or leave
     * the end out, and a run of the destination's ids at the end is only arriving. A walk on from one
     * of the complex's stops to another is still the way there, so only a ride after reaching it counts.
     *
     * [stopOf] names the stop each id belongs to (a pole and its stop pair are one stop). With it, a
     * ride that calls at one of the destination's stops and then ends at another, with nothing
     * between, has passed the first: to Bank, on through Bank to Monument. An id it doesn't name is
     * taken for another name of where the ride ends.
     */
    fun passedAt(destinations: Set<String>, stopOf: Map<String, String> = emptyMap()): Set<String> {
        fun at(vararg ids: String?) = ids.filterNotNull().filterTo(LinkedHashSet()) { it.isNotBlank() && it in destinations }
        // Every stop of the destination it rode on from, not just the first: a detour through an
        // unplanned bus stop and then a planned station is beaten by a route getting off at either.
        val passed = LinkedHashSet<String>()
        var reached = emptySet<String>()
        legs.forEachIndexed { index, leg ->
            // Where the route starts is where the rider already is, not an arrival.
            if (index > 0) at(leg.fromId, leg.fromArea).takeIf { it.isNotEmpty() }?.let { reached = it }
            val ends = at(leg.toId, leg.toArea)
            if (!leg.isWalk) {
                passed += reached
                reached = emptySet()
                // Each run of the destination's ids the ride called at and then left. Only the path's
                // calls can leave one: an end named by an id the destination doesn't know may be the
                // same stop under another name, or one the ride goes on to. Which can't be told, so the
                // run stays reached rather than passed, and only riding on from there passes it: a route
                // wrongly kept is shown, one wrongly dropped is lost.
                var run = mutableListOf<String>()
                // The path runs through the end, and may name it last under the same id: that's the
                // end, not a call after the run.
                val calls = leg.path.filter { it.isNotBlank() }.dropLastWhile { it == leg.toId || it == leg.toArea }
                for (call in calls) {
                    if (call in destinations) {
                        run += call
                    } else {
                        passed += run
                        run = mutableListOf()
                    }
                }
                run += ends.filterNot { it in run }
                // The run the ride ends in: the stops before the one it ends at were passed.
                val last = run.lastOrNull { it in stopOf }?.let { stopOf.getValue(it) }
                run.filterTo(passed) { id -> stopOf[id]?.let { it != last } == true }
                if (run.isNotEmpty()) reached = run.filterTo(LinkedHashSet()) { id -> stopOf[id]?.let { it == last } != false }
            } else if (ends.isNotEmpty()) {
                reached = ends
            }
        }
        return passed
    }

    /** Whether the route reaches one of [destinations] and then rides on ([passedAt]). */
    fun passesThrough(destinations: Set<String>): Boolean = passedAt(destinations).isNotEmpty()

    /**
     * The ids of where the route gets off its last ride, and where any walk after it ends. Only the
     * ends the Planner names: a path's last entry can be a call before an end it left out.
     */
    internal fun endIds(): Set<String> = endTimes().keys

    /** [endIds], each with when the route is there: off the ride at its arrival, a walk's end at the walk's. */
    internal fun endTimes(): Map<String, Instant> {
        val last = legs.indexOfLast { !it.isWalk }
        if (last < 0) return emptyMap()
        val times = LinkedHashMap<String, Instant>()
        for (leg in legs.drop(last)) {
            listOf(leg.toId, leg.toArea).filter { it.isNotBlank() }.forEach { times.putIfAbsent(it, leg.arrival) }
        }
        return times
    }
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
 * Where a trip is planned **from** (SPEC *Trips with a change*): a known TfL [Stop] by id (a *From…*
 * station's own stop), or [Here], the rider's own position, which TfL routes from with a first walk
 * leg to whichever stop serves the trip best — a station a walk away included, not just the stop
 * nearest the rider (maintainer, 2026-09-28).
 */
sealed interface TripOrigin {
    data class Stop(val id: String) : TripOrigin
    data class Here(val coordinate: Coordinates) : TripOrigin
}

/**
 * Plans a trip from a [TripOrigin] to a [TripDestination], behind a domain interface so the trip's
 * logic is tested against recorded fixtures (SPEC *Testing*). Returns the Planner's routes in its own
 * order; throws a [TflException] on a transport or decode failure, as [TflClient] does.
 */
interface JourneyPlanner {
    /**
     * Every walk in the routes is timed at [speed] ([WalkingSpeed], the rider's setting), none is
     * longer than [maxWalk] ([MaxWalk], also theirs), every route is as step-free as [stepFree]
     * ([StepFree], theirs too), and none rides a kind of transport [modes] turns off ([TripModes]).
     */
    suspend fun journeys(
        from: TripOrigin,
        to: TripDestination,
        speed: WalkingSpeed = WalkingSpeed.AVERAGE,
        maxWalk: MaxWalk = MaxWalk.DEFAULT,
        stepFree: StepFree = StepFree.DEFAULT,
        modes: TripModes = TripModes.DEFAULT,
    ): List<TripRoute>
}

/**
 * [first]'s routes, then [second]'s that aren't among them: a route both offer (the same lines, ends
 * and times, leg by leg) is kept once, where [first] has it. The same route at another departure is
 * another route, as the Planner offers it.
 */
fun mergedRoutes(first: List<TripRoute>, second: List<TripRoute>): List<TripRoute> {
    fun key(route: TripRoute) = route.legs.map { listOf(it.mode, it.lineId, it.fromId, it.toId, it.departure, it.arrival) }
    val seen = first.mapTo(HashSet()) { key(it) }
    return first + second.filter { seen.add(key(it)) }
}

/** [JourneyPlanner.journeys] from the stop [fromId]. */
suspend fun JourneyPlanner.journeys(fromId: String, to: TripDestination): List<TripRoute> =
    journeys(TripOrigin.Stop(fromId), to)

/**
 * [routes] without those that ride through the destination and on ([TripRoute.passedAt]) where
 * another route gets off at that same stop and arrives no later by the Planner's times: the rider
 * would take that one, getting off the first time (maintainer, 2026-09-27). A detour nothing beats
 * stays, since it may be the only way the plan found there. [stopOf] names each id's stop, so a
 * call under one pole or its `490G…` stop area and an end at the sibling pole are the same stop;
 * an id it doesn't name is its own.
 */
fun withoutDetours(
    routes: List<TripRoute>,
    destinations: Set<String>,
    stopOf: Map<String, String> = emptyMap(),
): List<TripRoute> {
    fun stop(id: String) = stopOf[id] ?: id
    val passed = routes.associateWith { route -> route.passedAt(destinations, stopOf).mapTo(HashSet()) { stop(it) } }
    // Whether [other] gets there before [route], or with it: a route that is a detour itself still
    // beats one that passed where it gets off, but of two detours arriving together only the first
    // listed goes on, so neither takes the other out with it.
    // [theirs] is when [other] is at the stop [route] passed: a route that gets off there and walks
    // on to another of the destination's stops is there when it gets off, not when the walk ends.
    fun sooner(other: Int, route: Int, arrival: Instant, theirs: Instant): Boolean {
        return theirs.isBefore(arrival) ||
            (theirs == arrival && (passed.getValue(routes[other]).isEmpty() || other < route))
    }
    return routes.filterIndexed { index, route ->
        val at = passed.getValue(route)
        val arrival = route.legs.lastOrNull()?.arrival ?: return@filterIndexed true
        at.isEmpty() || routes.indices.none { other ->
            other != index && routes[other].endTimes().any { (id, time) -> stop(id) in at && sooner(other, index, arrival, time) }
        }
    }
}
