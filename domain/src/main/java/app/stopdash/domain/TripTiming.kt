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
 * ([Basis.ESTIMATED]), and the wait it assumes away is kept as the arrival's [Estimate.slack]; anything else, nothing says when the
 * next train leaves, so the arrival is withheld ([Basis.UNKNOWN]) rather than guessed. Such a line is
 * boarded as they arrive, too, when the Planner's train leaves more than a gap between trains after
 * the rider gets there: the Planner planned the trip leaving later, and an earlier train is the one
 * they'd take.
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
     * [legs] timing, whether a leg can't be ridden ([blocked]: a line not running, or a stop it
     * boards or gets off at closed), and whether one couldn't be checked at all ([unchecked]: a line's
     * status or a stop's closure check failed with none known).
     */
    data class Estimate(
        val route: TripRoute,
        val basis: Basis,
        val arrival: Instant?,
        val legs: List<LegTiming>,
        val blocked: Boolean,
        val start: Instant,
        val unchecked: Boolean = false,
        // How much later than [arrival] the rider may get there: the waits [arrival] assumes away,
        // boarding a frequent line as the rider reaches it, each up to its longer typical gap
        // ([Headway]) and carried through the connections after it. Zero when every leg is timed
        // from a train or the Planner; null when a longer wait could miss a connection nothing
        // else times, so there's no latest to give.
        val slack: Duration? = Duration.ZERO,
        // Why [arrival] is withheld, for the debug log (null unless [basis] is UNKNOWN).
        val withheld: Withheld? = null,
        // The walk to the first stop the route was timed with: the rider is ready for its first leg
        // only after it ([readyAt]).
        val access: Duration = Duration.ZERO,
        // A ride is on a line with severe delays ([AroundDelays]): [rank] sets it as if it got there
        // [DELAYED_BY] later, so a route clear of the delays arriving about as soon goes first.
        val delayed: Boolean = false,
    ) {
        /** Door-to-door time from now, or null when the arrival is withheld. */
        val duration: Duration? get() = arrival?.let { Duration.between(start, it) }

        /** The latest the rider may arrive ([slack]), or null when the arrival is withheld or has no latest. */
        val latest: Instant? get() = slack?.let { arrival?.plus(it) }

        /**
         * Whether [rank] sets the route below every one checked and open: a check couldn't be made
         * ([unchecked]) and no ride is timed from a live train. The Planner offered the route, so one
         * with a live train to time it by is ranked on its arrival like a checked one, and the trip
         * names what it couldn't check (maintainer, 2026-09-30): a faster route isn't buried under a
         * slower one because a check failed.
         */
        val doubted: Boolean get() = unchecked && legs.none { it.live }
    }

    /** Why a leg withheld a route's arrival ([Withheld]). */
    enum class Reason {
        /** Its line isn't running now. */
        NOT_RUNNING,

        /** No live trains StopDash can vouch for: not fetched yet, stale (D4), or none whose route can be followed. */
        NO_LIVE,

        /** Its arrivals' last refresh failed: the held ones don't vouch the line is still running. */
        FAILED,

        /** Trains predicted for its line, but none vouched for as calling at its stop along the leg (its route loading, failed, or none does). */
        NOT_VOUCHED,

        /** No train predicted at all. */
        NO_TRAINS,

        /** Predictions end before the rider gets there, and don't show the line running every few minutes. */
        INFREQUENT,

        /** A mode never boarded on arrival (National Rail, tram): a wait there can be long. */
        MODE,
    }

    /**
     * The leg that withheld a route's arrival and why (SPEC principle 2: a degraded answer says why),
     * with what its predictions showed: how many ([predictions]), how long before the rider reaches
     * the stop the last one leaves ([lastBefore]), the longest gap between them ([gap]), and how long
     * before then the Planner's own departure left ([missedBy]). Coarse facts only: a line, never a
     * stop or a place (SPEC *Privacy*).
     */
    data class Withheld(
        val leg: Int,
        val mode: String,
        val lineId: String,
        val reason: Reason,
        val predictions: Int,
        val lastBefore: Duration?,
        val gap: Duration?,
        val missedBy: Duration,
    ) {
        /** One debug-log line: "leg 2 (bus 390): infrequent, 2 predicted, last 6 min before reach, gap 14 min; Planner's missed by 9 min". */
        fun describe(): String = buildString {
            append("leg ").append(leg + 1).append(" (").append(mode).append(' ').append(lineId).append("): ")
            append(reason.name.lowercase().replace('_', ' '))
            append(", ").append(predictions).append(" predicted")
            lastBefore?.let { append(", last ").append(it.toMinutes()).append(" min before reach") }
            gap?.let { append(", gap ").append(it.toMinutes()).append(" min") }
            append("; Planner's missed by ").append(missedBy.toMinutes()).append(" min")
        }
    }

    /**
     * Times [route] from [now], the rider [access] away from its first stop. [live] gives, for each
     * leg by index, the upcoming trains at its boarding stop that call at its alighting stop, or null
     * when there are none StopDash can vouch for (the arrivals failed, went stale, or the route
     * couldn't be checked). [notRunning] is the lines not running now; [unknown] the lines whose
     * status couldn't be checked, with none known; [stops] how the route stands by its stops' closure
     * checks ([TripClosures.standing]), which ranks it as its lines' would. A ride another line can
     * take ([otherLine]) isn't ranked by its Planner line's status.
     */
    fun estimate(
        route: TripRoute,
        now: Instant,
        access: Duration,
        live: (Int) -> List<Departure>?,
        notRunning: Set<String> = emptySet(),
        unknown: Set<String> = emptySet(),
        // Whether leg [index]'s arrivals came from a fetch that succeeded, at the boarding stop of any
        // line that times it: after a failed refresh the last ones stand, aged, but don't vouch that
        // the line is still running.
        current: (Int) -> Boolean = { true },
        // Leg [index]'s trains ([live]) from boarding stops whose last refresh succeeded: only these
        // may show its line running every few minutes ([frequentAt]), since one held from a stop that
        // failed may have stopped running since. All of [live]'s where none failed.
        refreshed: (Int) -> List<Departure> = { live(it).orEmpty() },
        // How many trains leg [index]'s lines have predicted at its boarding stop before [live]'s
        // checks, for the reason a withheld arrival gives ([Withheld]); timing never reads it.
        predicted: (Int) -> Int = { live(it)?.size ?: 0 },
        // Whether leg [index] carries the Planner's own times. One it didn't plan (a train through a
        // change, [RideLines.through]) carries the times of the rides it replaces as a placeholder,
        // which belong to other lines, so only a live train times it. So does one whose Planner line
        // another line stands in for while it isn't itself checked as running ([otherLine]): its
        // times are a service that can't be relied on.
        timetabled: (Int) -> Boolean = { true },
        stops: TripClosures.Standing = TripClosures.Standing.OPEN,
        // Whether another line, checked as running, can take leg [index]'s ride between the same
        // stops ([RideLines.timedRunning]): then the ride can be taken, or was checked, whatever its
        // Planner line's status, so a route isn't sunk below the others by a closed or unchecked
        // Planner line that another running line stands in for (Codex on #309).
        otherLine: (Int) -> Boolean = { false },
        // The lines with severe delays ([AroundDelays.delayed]): a route riding one ranks lower ([Estimate.delayed]).
        delayed: Set<String> = emptySet(),
    ): Estimate {
        // Each ride by its index among the legs, as [otherLine] takes it; a walk has no line.
        val rides = route.legs.withIndex().filterNot { it.value.isWalk }
        fun decides(index: Int, lineId: String, lines: Set<String>) = lineId in lines && !otherLine(index)
        val blocked = rides.any { (index, leg) -> decides(index, leg.lineId, notRunning) } || stops == TripClosures.Standing.CLOSED
        val unchecked = !blocked &&
            (rides.any { (index, leg) -> decides(index, leg.lineId, unknown) } || stops == TripClosures.Standing.UNCHECKED)
        var basis = Basis.LIVE
        var waits = false
        var withheld: Withheld? = null
        // The longest a frequent leg [index] may keep the rider waiting: its line's longer typical gap
        // between trains ([Headway]), or the longest a frequent line has when its trains can't say.
        fun longestGap(index: Int): Duration =
            Headway.of(refreshed(index).map { it.expectedArrival })?.let { Duration.ofMinutes(it.max.toLong()) } ?: FREQUENT_MAX_GAP
        // A leg's timing from when the rider is [ready] for it, and whether it boards a frequent line
        // as the rider arrives ([Timed.onArrival]), assuming away the wait for it ([waits]).
        fun time(index: Int, leg: TripLeg, ready: Instant): Timed {
            if (leg.isWalk) return Timed(LegTiming(ready, ready.plus(leg.run), null, false), Basis.LIVE)
            val trains = live(index)
            val train = trains?.filter { !it.expectedArrival.isBefore(ready) }?.minByOrNull { it.expectedArrival }
            fun unknown(): Timed {
                // A Planner line another line stands in for ([otherLine]) isn't why: the ride no longer
                // waits on it, so the reason is the one its other lines' trains give.
                if (withheld == null) withheld = withheldAt(index, leg, ready, trains, leg.lineId in notRunning && !otherLine(index), current(index), predicted(index))
                return Timed(LegTiming(null, null, null, false), Basis.UNKNOWN)
            }
            // Its live trains vouched for and running, just not predicted as far ahead as the rider
            // gets there: on a line every few minutes, the next one is about then. Not on a line with
            // no trains (done for the night), one not running (its last predictions may outlive it),
            // nor one whose arrivals failed, even with its last ones held. And only where its
            // predictions show it running every few minutes now: how far ahead they reach says
            // nothing, since TfL predicts only trains already running, so near a line's start they
            // end within 15 minutes all day. Judged on trains from stops that refreshed
            // ([refreshed]), not ones held from a stop that failed.
            fun frequent() = leg.lineId !in notRunning && current(index) && frequentAt(leg.mode, refreshed(index))
            return when {
                train != null -> Timed(LegTiming(train.expectedArrival, train.expectedArrival.plus(leg.run), train, true), Basis.LIVE)
                !timetabled(index) -> unknown()
                // The Planner's own train while the rider can make it, unless the line runs every few
                // minutes and that train leaves more than a gap after the rider gets there: the
                // Planner planned the trip leaving later, and an earlier train is the one they'd take
                // (maintainer's report, 2026-09-30: a bus caught sooner than planned waited nine
                // minutes at the change for the Planner's train on a line every few minutes).
                !leg.departure.isBefore(ready) && !(frequent() && leg.departure.isAfter(ready.plus(longestGap(index)))) ->
                    Timed(LegTiming(leg.departure, leg.arrival, null, false), Basis.ESTIMATED)
                frequent() -> {
                    waits = true
                    Timed(LegTiming(ready, ready.plus(leg.run), null, false), Basis.ESTIMATED, onArrival = true)
                }
                else -> unknown()
            }
        }
        var at: Instant? = now.plus(access)
        val legs = route.legs.mapIndexed { index, leg ->
            val ready = at ?: return@mapIndexed LegTiming(null, null, null, false)
            val timed = time(index, leg, ready)
            if (timed.basis > basis) basis = timed.basis
            at = timed.timing.arrive?.plus(leg.changeAfter)
            timed.timing
        }
        val arrival = if (basis == Basis.UNKNOWN) null else legs.lastOrNull()?.arrive
        // A frequent line boarded as the rider arrives may keep them up to a full gap between trains
        // ([Headway]): time the route again with each such wait at its longest, carried through every
        // later connection (a longer wait can miss the next leg's train), for the latest it may arrive.
        // Null when that later route can't be timed: a connection missed that nothing else times.
        val slack = if (arrival == null || !waits) {
            Duration.ZERO
        } else {
            var late: Instant? = now.plus(access)
            route.legs.forEachIndexed { index, leg ->
                val ready = late ?: return@forEachIndexed
                val timed = time(index, leg, ready)
                val gap = if (timed.onArrival) longestGap(index) else Duration.ZERO
                late = timed.timing.arrive?.plus(gap)?.plus(leg.changeAfter)
            }
            // The last leg's change time isn't part of the arrival.
            late?.minus(route.legs.lastOrNull()?.changeAfter ?: Duration.ZERO)?.let { Duration.between(arrival, it).coerceAtLeast(Duration.ZERO) }
        }
        val onDelayed = rides.any { (index, leg) -> decides(index, leg.lineId, delayed) }
        return Estimate(route, basis, arrival, legs, blocked, now, unchecked, slack, withheld.takeIf { basis == Basis.UNKNOWN }, access, onDelayed)
    }

    // One leg timed by [estimate]: its [timing], the [basis] behind it, and whether it boards a
    // frequent line as the rider arrives ([onArrival]), the wait assumed away.
    private data class Timed(val timing: LegTiming, val basis: Basis, val onArrival: Boolean = false)

    /**
     * When the rider is ready to board leg [index] of [estimate]'s route, as [estimate] timed it: at
     * its first stop after the walk there ([access]) for the first leg, else when the leg before gets
     * them there, plus any change time the Planner allows after it. Null when the leg before has no
     * timing (its arrival withheld), so nothing can be said to leave too soon.
     */
    fun readyAt(estimate: Estimate, access: Duration, index: Int): Instant? {
        if (index == 0) return estimate.start.plus(access)
        val before = estimate.legs.getOrNull(index - 1) ?: return null
        val leg = estimate.route.legs.getOrNull(index - 1) ?: return null
        return before.arrive?.plus(leg.changeAfter)
    }

    // Why [leg], reached at [ready] past its live [trains] and the Planner's departure, withholds the arrival.
    private fun withheldAt(
        index: Int,
        leg: TripLeg,
        ready: Instant,
        trains: List<Departure>?,
        notRunning: Boolean,
        current: Boolean,
        predicted: Int,
    ): Withheld {
        val times = trains.orEmpty().map { it.expectedArrival }.distinct().sorted()
        val reason = when {
            notRunning -> Reason.NOT_RUNNING
            // Before the empty cases: a first fetch that failed leaves nothing to vouch for either.
            !current -> Reason.FAILED
            times.isEmpty() && predicted > 0 -> Reason.NOT_VOUCHED
            trains == null -> Reason.NO_LIVE
            times.isEmpty() -> Reason.NO_TRAINS
            leg.mode.lowercase() !in FREQUENT_MODES && !leg.mode.equals(BUS, ignoreCase = true) -> Reason.MODE
            else -> Reason.INFREQUENT
        }
        return Withheld(
            leg = index,
            mode = leg.mode,
            lineId = leg.lineId,
            reason = reason,
            predictions = if (times.isEmpty()) predicted else times.size,
            lastBefore = times.lastOrNull()?.let { Duration.between(it, ready) },
            gap = times.zipWithNext { a, b -> Duration.between(a, b) }.maxOrNull(),
            missedBy = Duration.between(leg.departure, ready),
        )
    }

    /**
     * [estimates] best first: routes that can be ridden, then those that can't; among those that can,
     * any that couldn't be checked with no live train to time them by ([Estimate.doubted]) last.
     * Within each, fully live before estimated before withheld, each by the earliest arrival, then one
     * checked before one that couldn't be, then the fewest changes; except that an estimate goes ahead
     * of a live route it beats even at its latest ([beatsEvenLate]): a route faster however its waits
     * fall isn't buried under a slower one because part of it is only estimated (maintainer,
     * 2026-09-30: a live ride then a frequent bus sat below a slower live route even at its worst).
     * A route on a line with severe delays ([Estimate.delayed]) is ranked as if it got there
     * [DELAYED_BY] later: its trains' predictions don't stand for much then, so one clear of the delays
     * arriving about as soon goes first, but one much sooner still leads (maintainer, 2026-10-08).
     */
    fun rank(estimates: List<Estimate>): List<Estimate> =
        estimates.groupBy { it.blocked to it.doubted }.toSortedMap(compareBy<Pair<Boolean, Boolean>>({ it.first }, { it.second }))
            .values.flatMap { tier ->
                val live = tier.filter { it.basis == Basis.LIVE }.sortedWith(WITHIN_BASIS)
                val estimated = tier.filter { it.basis == Basis.ESTIMATED }.sortedWith(WITHIN_BASIS)
                merge(live, estimated) + tier.filter { it.basis == Basis.UNKNOWN }.sortedWith(WITHIN_BASIS)
            }

    // Within one basis: the earliest arrival (a delayed route's set later, [rankedArrival]), then checked
    // before unchecked, then the fewest changes.
    private val WITHIN_BASIS = compareBy<Estimate>({ rankedArrival(it) ?: Instant.MAX }, { it.unchecked }, { it.route.rides.size })

    /** How much later [rank] sets a route on a line with severe delays ([Estimate.delayed]). */
    val DELAYED_BY: Duration = Duration.ofMinutes(10)

    // [estimate]'s arrival as [rank] orders it: [DELAYED_BY] later on a severely delayed line.
    private fun rankedArrival(estimate: Estimate): Instant? =
        if (estimate.delayed) estimate.arrival?.plus(DELAYED_BY) else estimate.arrival


    // [live] and [estimated], each already in order, as one list: the next estimate goes ahead of the
    // next live route only when it beats that route even at its latest. Each list keeps its own order,
    // so an estimate never passes another estimate to get there.
    private fun merge(live: List<Estimate>, estimated: List<Estimate>): List<Estimate> {
        val merged = ArrayList<Estimate>(live.size + estimated.size)
        var l = 0
        var e = 0
        while (l < live.size || e < estimated.size) {
            val takeEstimate = e < estimated.size && (l >= live.size || beatsEvenLate(estimated[e], live[l]))
            merged += if (takeEstimate) estimated[e++] else live[l++]
        }
        return merged
    }

    /**
     * Whether [estimate] gets there before [live] even at its latest ([Estimate.latest]): timed from at
     * least one live train, so it isn't a timetable alone, and with a latest to give. One whose latest
     * ties or passes the live route's arrival, or that has none, stays below it. Each side's severe
     * delays count as [rank] counts them ([Estimate.delayed]); and a live route with severe delays is
     * passed by one clear of them on its timetable alone, since its own trains' predictions don't stand
     * for much then (Codex, #703).
     */
    fun beatsEvenLate(estimate: Estimate, live: Estimate): Boolean {
        val latest = estimate.latest?.let { if (estimate.delayed) it.plus(DELAYED_BY) else it } ?: return false
        val arrival = rankedArrival(live) ?: return true
        return (estimate.legs.any { it.live } || (live.delayed && !estimate.delayed)) && latest.isBefore(arrival)
    }

    /**
     * [estimates] without a route another beats on every count (maintainer, 2026-09-28): one with
     * fewer changes that gets there no later, that StopDash stands behind at least as far (usable,
     * then unchecked, then blocked; then live, estimated, withheld), and whose walk it doesn't spare
     * ([walksLess]; maintainer, 2026-10-03: a bus to the station instead of a long walk there is a
     * change worth offering a rider who'd rather not walk). Stricter than [rank], which lifts
     * an unchecked route with a live train among the checked: a route checked open is never left off
     * for one that couldn't be checked. A route with more changes is worth offering only when it's
     * faster or walks clearly less. A withheld arrival can't be compared, so it neither beats nor is
     * beaten. The route walking least is always kept when it walks clearly less than the first
     * ([walksLess]): a chain of routes each walking a little less than the last would otherwise drop
     * it, though it's the one the *Least walking* header is for. Arrivals compare as [rank] orders them,
     * a severely delayed route's [DELAYED_BY] later, so one clear of the delays isn't left off for it
     * (Codex, #703). In [estimates]' order.
     */
    fun withoutSlowerChanges(estimates: List<Estimate>): List<Estimate> {
        // Of routes walking as little, the first: the one ranked best.
        val leastWalking = estimates.minByOrNull { it.route.walking }
            ?.takeIf { walksLess(it.route, estimates.first().route) }
        return estimates.filter { route ->
            if (route === leastWalking) return@filter true
            val arrival = rankedArrival(route) ?: return@filter true
            estimates.none { other ->
                other !== route &&
                    other.route.rides.size < route.route.rides.size &&
                    rankedArrival(other)?.let { !it.isAfter(arrival) } == true &&
                    !walksLess(route.route, other.route) &&
                    STANDING.compare(other, route) <= 0
            }
        }
    }

    /**
     * Whether [route] spares the rider [than]'s walk: it walks at least [LESS_WALKING] less, by the
     * Planner's times at the rider's own pace.
     */
    fun walksLess(route: TripRoute, than: TripRoute): Boolean =
        route.walking.plus(LESS_WALKING) <= than.walking

    /**
     * The least walking a route has to save to be worth a change of its own ([withoutSlowerChanges])
     * (maintainer, 2026-10-03): a minute or two is no reason to change more and arrive later. The
     * *Least walking* header doesn't use it: every card tied for the least walking carries it
     * ([routeLabels]; maintainer, 2026-10-04).
     */
    val LESS_WALKING: Duration = Duration.ofMinutes(5)

    /**
     * [estimates] without a route that rides a leg the Planner didn't plan ([planned], every leg of
     * its routes) on anything but a live train StopDash vouches for: a train through a change
     * ([RideLines.through]) is offered only when one is predicted, never on the Planner times it
     * carries as a placeholder, which belong to other lines.
     */
    fun withoutUnvouchedLegs(estimates: List<Estimate>, planned: Set<TripLeg>): List<Estimate> =
        estimates.filter { estimate ->
            estimate.route.legs.withIndex().all { (index, leg) ->
                leg.isWalk || leg in planned || estimate.legs.getOrNull(index)?.train != null
            }
        }

    // How far StopDash stands behind a route: checked and open, then unchecked, then blocked; within
    // each, live before estimated before withheld.
    private val STANDING = compareBy<Estimate>({ it.blocked }, { it.unchecked }, { it.basis })

    /**
     * The walk to a trip's first stop [meters] away as the crow flies, at the rider's [speed]: the
     * straight line stretched for detours, at the Planner's pace for that speed ([metersPerSecond]),
     * rounded up to a whole minute, so it reads what the Planner would say for the same walk.
     */
    fun accessWalk(meters: Double, speed: WalkingSpeed = WalkingSpeed.AVERAGE): Duration {
        if (meters <= 0.0) return Duration.ZERO
        val seconds = meters * DETOUR / metersPerSecond(speed)
        return Duration.ofMinutes(ceil(seconds / 60.0).toLong())
    }

    /**
     * The pace the phone's own walk estimate ([accessWalk]) takes for [speed]: the Planner's own, so
     * the same walk reads the same either way. Measured 2026-09-28 over its walk-only routes from
     * points in central London to nearby stations: in a straight line it covered about 0.64, 0.90
     * and 1.14 m/s at Slow, Average and Fast, which is these over [DETOUR].
     */
    /**
     * How far, as the crow flies, a walk of [minutes] at [speed] reaches: [accessWalk] the other way round,
     * so a stop this far off reads as that many minutes' walk.
     */
    fun walkReach(minutes: Int, speed: WalkingSpeed): Double = minutes * 60.0 * metersPerSecond(speed) / DETOUR

    internal fun metersPerSecond(speed: WalkingSpeed): Double = when (speed) {
        WalkingSpeed.SLOW -> SLOW_METERS_PER_SECOND
        WalkingSpeed.AVERAGE -> WALK_METERS_PER_SECOND
        WalkingSpeed.FAST -> FAST_METERS_PER_SECOND
    }

    /**
     * Modes whose trains run every few minutes all day, so a rider reaching one past its live
     * predictions boards about as they arrive. Not National Rail or trams: a wait there can be long
     * enough to matter. A bus is judged on its own predictions ([frequentAt]).
     */
    val FREQUENT_MODES = setOf("tube", "dlr", "overground", "elizabeth-line")

    /**
     * Whether a [mode] leg reached past its live [trains] is boarded as the rider arrives: a
     * [FREQUENT_MODES] line running frequently now ([runsFrequently]), or a bus doing so on fewer
     * predictions, since TfL predicts a bus only about half an hour ahead, so a bus every eight
     * minutes shows two (maintainer, 2026-09-27: a bus past its two predictions withheld its route).
     */
    fun frequentAt(mode: String, trains: List<Departure>): Boolean = when (mode.lowercase()) {
        in FREQUENT_MODES -> runsFrequently(trains)
        BUS -> runsFrequently(trains, FREQUENT_MIN_BUSES)
        else -> false
    }

    /**
     * Whether [trains] (a leg's live trains, in any order) show its line running every few minutes
     * now: at least [minTrains] of them at distinct times, none following the one before by more than
     * [FREQUENT_MAX_GAP]. Around the night's last trains they thin out and fail this.
     */
    fun runsFrequently(trains: List<Departure>, minTrains: Int = FREQUENT_MIN_TRAINS): Boolean {
        // Distinct times: one train listed twice isn't two, and the gap between trains ([Headway]) the
        // wait is bounded by needs as many distinct times as this does.
        val times = trains.map { it.expectedArrival }.distinct().sorted()
        if (times.size < minTrains) return false
        return times.zipWithNext().all { (a, b) -> Duration.between(a, b) <= FREQUENT_MAX_GAP }
    }

    /** How many predicted trains show a line running frequently ([runsFrequently]). */
    // As many as a gap between trains needs ([Headway.MIN_TRAINS]), so a frequent leg always has one.
    const val FREQUENT_MIN_TRAINS = Headway.MIN_TRAINS

    /** How many predicted buses show a bus route running frequently: see [frequentAt]. */
    const val FREQUENT_MIN_BUSES = 2

    private const val BUS = "bus"

    /** The longest wait between predicted trains on a line running every few minutes. */
    val FREQUENT_MAX_GAP: Duration = Duration.ofMinutes(10)

    /** TfL `statusSeverity` values for a line not running: closed, suspended, planned closure, not running, service closed. */
    val NOT_RUNNING_SEVERITIES = setOf(1, 2, 4, 16, 20)

    /** The lines in [statuses] that aren't running now. */
    fun notRunning(statuses: Collection<LineStatus>): Set<String> =
        statuses.filter { it.severity in NOT_RUNNING_SEVERITIES }.mapTo(HashSet()) { it.lineId }

    // A straight line understates a street walk; 1.4 is a common urban detour factor.
    private const val DETOUR = 1.4

    // Along the stretched line, about 3.2, 4.5 and 5.8 km/h ([metersPerSecond]).
    private const val SLOW_METERS_PER_SECOND = 0.9
    private const val WALK_METERS_PER_SECOND = 1.25
    private const val FAST_METERS_PER_SECOND = 1.6
}
