package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration

/**
 * The longest walk a trip may ask of the rider, for the walks a trip plans (SPEC *Trips with a
 * change*, maintainer 2026-09-30): the Journey Planner's `maxWalkingMinutes`, which no walk in a
 * route it offers exceeds — to the first stop, between stations, or on to the end. Timed at the
 * rider's [WalkingSpeed], as the Planner times every walk. [TWENTY] is the stored default
 * (maintainer, 2026-10-03; it was [THIRTY]): long enough for the walk to a station about a mile off
 * at an average pace, which the old fixed 15 dropped even when it was the fastest way there.
 */
enum class MaxWalk(
    /** The Journey Planner's value for `maxWalkingMinutes`. */
    val minutes: Int,
) {
    TEN(10),
    FIFTEEN(15),
    TWENTY(20),
    THIRTY(30),
    FORTY_FIVE(45),
    SIXTY(60),
    ;

    /** Whether no walk in [route] takes longer than this limit. */
    @WorkerThread
    fun allows(route: TripRoute): Boolean {
        val most = Duration.ofMinutes(minutes.toLong())
        return route.legs.none { it.isWalk && it.run > most }
    }

    /**
     * [routes] less those with a walk longer than this limit. The Planner is sent the limit, but holds to it only
     * loosely: the walk from the rider's position and the walk on to a place can come back longer (maintainer's
     * report, 2026-11-01: 16 and 19 min walks under a 15 min limit). None is kept even where none is within it: the
     * rider chose the limit, and the trip's chip beside its routes raises it.
     */
    @WorkerThread
    fun within(routes: List<TripRoute>): List<TripRoute> = routes.filter(::allows)

    companion object {
        /** The stored default. */
        val DEFAULT = TWENTY

        /** A stored name read back; anything unrecognized (a newer build's value) is [DEFAULT]. */
        fun fromStored(name: String?): MaxWalk = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
