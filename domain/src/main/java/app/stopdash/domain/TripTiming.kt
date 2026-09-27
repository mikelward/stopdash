package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * When a planned route gets the rider there, and how far StopDash stands behind that (SPEC *Trips
 * with a change*). TfL's Journey Planner chose the lines and changes; the times come from live
 * trains, worked out leg by leg: the first train the rider can reach on a leg, plus the Planner's
 * run time, plus its change time, gives when the rider can board the next leg.
 *
 * A leg with no live train in reach falls back to the Planner's own time for it while the rider can
 * still make the Planner's departure ([Basis.ESTIMATED]). Past that, a line running every few minutes
 * ([FREQUENT_MODES], its predictions showing it: [runsFrequently]) whose live trains all leave before
 * the rider reaches it — its predictions end short of them — is boarded as they arrive
 * ([Basis.ESTIMATED]); anything else, nothing says when the
 * next train leaves, so the arrival is withheld ([Basis.UNKNOWN]) rather than guessed.
 */
object TripTiming {
    /** How far StopDash stands behind a route's arrival, best first. */
    enum class Basis { LIVE, ESTIMATED, UNKNOWN }

    /**
     * One leg's timing: the live [train] the rider would catch (null for a walk or a fallback leg),
     * when they [board] and [arrive] (null once the route's timing is withheld), and whether that
     * came from a live train ([live]).
     */
    data class LegTiming(val board: Instant?, val arrive: Instant?, val train: Departure?, val live: Boolean)

    /**
     * A route's timing: its [arrival] (null when withheld), the [basis] behind it, each leg's
     * [legs] timing, whether a leg can't be ridden ([blocked]: a line not running), and whether a
     * leg's line couldn't be checked at all ([unchecked]: its status failed with none known).
     */
    data class Estimate(
        val route: TripRoute,
        val basis: Basis,
        val arrival: Instant?,
        val legs: List<LegTiming>,
        val blocked: Boolean,
        val start: Instant,
        val unchecked: Boolean = false,
    ) {
        /** Door-to-door time from now, or null when the arrival is withheld. */
        val duration: Duration? get() = arrival?.let { Duration.between(start, it) }
    }

    /**
     * Times [route] from [now], the rider [access] away from its first stop. [live] gives, for each
     * leg by index, the upcoming trains at its boarding stop that call at its alighting stop, or null
     * when there are none StopDash can vouch for (the arrivals failed, went stale, or the route
     * couldn't be checked). [notRunning] is the lines not running now; [unknown] the lines whose
     * status couldn't be checked, with none known.
     */
    fun estimate(
        route: TripRoute,
        now: Instant,
        access: Duration,
        live: (Int) -> List<Departure>?,
        notRunning: Set<String> = emptySet(),
        unknown: Set<String> = emptySet(),
        // Whether leg [index]'s arrivals came from a fetch that succeeded: after a failed refresh the
        // last ones stand, aged, but don't vouch that the line is still running.
        current: (Int) -> Boolean = { true },
    ): Estimate {
        val blocked = route.rides.any { it.lineId in notRunning }
        val unchecked = !blocked && route.rides.any { it.lineId in unknown }
        var at: Instant? = now.plus(access)
        var basis = Basis.LIVE
        val legs = route.legs.mapIndexed { index, leg ->
            val ready = at ?: return@mapIndexed LegTiming(null, null, null, false)
            val timing = if (leg.isWalk) {
                LegTiming(ready, ready.plus(leg.run), null, false)
            } else {
                val trains = live(index)
                val train = trains?.filter { !it.expectedArrival.isBefore(ready) }?.minByOrNull { it.expectedArrival }
                when {
                    train != null -> LegTiming(train.expectedArrival, train.expectedArrival.plus(leg.run), train, true)
                    !leg.departure.isBefore(ready) -> {
                        if (basis == Basis.LIVE) basis = Basis.ESTIMATED
                        LegTiming(leg.departure, leg.arrival, null, false)
                    }
                    // Its live trains vouched for and running, just not predicted as far ahead as the
                    // rider gets there: on a line every few minutes, the next one is about then. Not on a
                    // line with no trains (done for the night), one not running (its last predictions
                    // may outlive it), nor one whose arrivals failed, even with its last ones held.
                    // And only where its predictions show it running every few minutes now: how far
                    // ahead they reach says nothing, since TfL predicts only trains already running,
                    // so near a line's start they end within 15 minutes all day.
                    leg.mode.lowercase() in FREQUENT_MODES && leg.lineId !in notRunning && current(index) &&
                        runsFrequently(trains.orEmpty()) -> {
                        if (basis == Basis.LIVE) basis = Basis.ESTIMATED
                        LegTiming(ready, ready.plus(leg.run), null, false)
                    }
                    else -> {
                        basis = Basis.UNKNOWN
                        LegTiming(null, null, null, false)
                    }
                }
            }
            at = timing.arrive?.plus(leg.changeAfter)
            timing
        }
        val arrival = if (basis == Basis.UNKNOWN) null else legs.lastOrNull()?.arrive
        return Estimate(route, basis, arrival, legs, blocked, now, unchecked)
    }

    /**
     * [estimates] best first: routes checked and open, then those that couldn't be checked, then
     * those that can't be ridden; within each, fully live before estimated before withheld, then the
     * earliest arrival.
     */
    fun rank(estimates: List<Estimate>): List<Estimate> =
        estimates.sortedWith(
            compareBy<Estimate>({ it.blocked }, { it.unchecked }, { it.basis }, { it.arrival ?: Instant.MAX }),
        )

    /**
     * The walk to a trip's first stop [meters] away as the crow flies, estimated conservatively: the
     * straight line stretched for detours, at an unhurried pace, rounded up to a whole minute. It
     * errs toward graying a train that could be caught rather than offering one that can't.
     */
    fun accessWalk(meters: Double): Duration {
        if (meters <= 0.0) return Duration.ZERO
        val seconds = meters * DETOUR / WALK_METERS_PER_SECOND
        return Duration.ofMinutes(ceil(seconds / 60.0).toLong())
    }

    /**
     * Modes whose trains run every few minutes all day, so a rider reaching one past its live
     * predictions boards about as they arrive. Not National Rail, trams or buses: a wait there can
     * be long enough to matter.
     */
    val FREQUENT_MODES = setOf("tube", "dlr", "overground", "elizabeth-line")

    /**
     * Whether [trains] (a leg's live trains, in any order) show its line running every few minutes
     * now: at least [FREQUENT_MIN_TRAINS] of them, none following the one before by more than
     * [FREQUENT_MAX_GAP]. Around the night's last trains they thin out and fail this.
     */
    fun runsFrequently(trains: List<Departure>): Boolean {
        if (trains.size < FREQUENT_MIN_TRAINS) return false
        val times = trains.map { it.expectedArrival }.sorted()
        return times.zipWithNext().all { (a, b) -> Duration.between(a, b) <= FREQUENT_MAX_GAP }
    }

    /** How many predicted trains show a line running frequently ([runsFrequently]). */
    const val FREQUENT_MIN_TRAINS = 3

    /** The longest wait between predicted trains on a line running every few minutes. */
    val FREQUENT_MAX_GAP: Duration = Duration.ofMinutes(10)

    /** TfL `statusSeverity` values for a line not running: closed, suspended, planned closure, not running, service closed. */
    val NOT_RUNNING_SEVERITIES = setOf(1, 2, 4, 16, 20)

    /** The lines in [statuses] that aren't running now. */
    fun notRunning(statuses: Collection<LineStatus>): Set<String> =
        statuses.filter { it.severity in NOT_RUNNING_SEVERITIES }.mapTo(HashSet()) { it.lineId }

    // A straight line understates a street walk; 1.4 is a common urban detour factor.
    private const val DETOUR = 1.4

    // About 4 km/h: an unhurried pace, so a rider isn't sent running for a train.
    private const val WALK_METERS_PER_SECOND = 1.1
}
