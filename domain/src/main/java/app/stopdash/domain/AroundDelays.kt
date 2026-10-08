package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Planning around severe delays (SPEC *Trips with a change*, maintainer 2026-10-08): TfL's Journey
 * Planner plans as if trains ran to the timetable, avoiding only a line that isn't running, so on a day
 * the Northern line has severe delays every route it offers can still ride it. When the route it offers
 * soonest rides a line with severe delays, the trip asks it once more with that line's mode left out
 * ([modesToLeaveOut]), so a way clear of the delays is there to choose; the routes on the delayed line
 * stay, ranked lower ([TripTiming.DELAYED_BY]).
 */
object AroundDelays {
    /** TfL `statusSeverity` for Severe Delays. */
    const val SEVERE_DELAYS = 6

    /**
     * The lines in [statuses] with severe delays: the alert shown, or any other under way ([LineStatus.underWay]),
     * as a worse one (a part closure) is the one shown while the delays go on (Codex, #703).
     */
    @WorkerThread
    fun delayed(statuses: Collection<LineStatus>): Set<String> =
        statuses.filter { status -> status.severity == SEVERE_DELAYS || status.underWay.any { it.severity == SEVERE_DELAYS } }
            .mapTo(HashSet()) { it.lineId }

    /**
     * The route of [routes] arriving soonest by the Planner's times, the first of a tie, as
     * [FinalStop.of] picks it; null when none has a leg.
     */
    @WorkerThread
    fun soonest(routes: List<TripRoute>): TripRoute? =
        routes.filter { it.legs.isNotEmpty() }.minByOrNull { it.legs.last().arrival }

    /**
     * The lines [soonest] rides, for the status check that decides whether to ask again: empty when
     * it rides none, so nothing is asked.
     */
    @WorkerThread
    fun linesOf(soonest: TripRoute): Set<String> =
        soonest.rides.mapNotNullTo(LinkedHashSet()) { it.lineId.takeIf(String::isNotBlank) }

    /**
     * The Planner modes to leave out of one more request: those of [soonest]'s rides on a [delayed]
     * line, among the modes [modes] still plans over. Empty when it rides none (nothing to plan around),
     * or when leaving them out would leave the Planner nothing to ride.
     */
    @WorkerThread
    fun modesToLeaveOut(soonest: TripRoute, delayed: Set<String>, modes: TripModes): Set<String> {
        if (delayed.isEmpty()) return emptySet()
        val planned = modes.plannerModes.split(',').toSet()
        val out = soonest.rides.filter { it.lineId in delayed }.mapTo(LinkedHashSet()) { it.mode.lowercase() }
        out.retainAll(planned)
        return if ((planned - out - WALKING).isEmpty()) emptySet() else out
    }

    private const val WALKING = "walking"
}
