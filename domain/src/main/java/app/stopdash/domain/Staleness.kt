package app.stopdash.domain

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration

/**
 * The single, shared "too old to trust" policy (SPEC D4). One threshold, applied
 * identically by every surface, past which countdowns are withheld and the surface
 * shows "tap to refresh" instead of numbers that are probably wrong. Exactly one
 * constant, pinned by test, so no two surfaces can disagree about when data has
 * gone stale.
 *
 * Clock-free like [RelativeTime]: the caller passes the age of the fetch, or the fetch's stamp and
 * the wall time to judge it at ([age]), which tells the age by the steady clock ([SteadyClock]) so
 * setting the device's clock doesn't change it. JVM-testable without Android.
 */
object Staleness {
    /**
     * Live predictions drift within about a minute, but withholding that aggressively
     * would leave every surface showing "tap to refresh" between routine refreshes,
     * with the client-side recompute (SPEC D4) already keeping each countdown honest in
     * between. This is the longer bound past which the underlying prediction *set* is
     * itself likely wrong — services since added or dropped, not merely each number a
     * little off — so the safe answer becomes "refresh" rather than a stale list.
     *
     * A tuned constant, not a spec value (SPEC *Freshness*); tunable on device, tracked
     * under TODO "Decisions needing review".
     */
    val THRESHOLD: Duration = 5.minutes

    /**
     * How far a stamp may be ahead of the clock and still count as just now: a screen's own clock
     * ticks every few seconds behind the fetch it shows, and a watch's clock isn't the phone's.
     * A stamp further ahead than this was made before the clock was set back, so its real age
     * can't be told.
     */
    val CLOCK_SKEW: Duration = 1.minutes

    /**
     * True once a fetch this old should no longer have its countdowns shown, and for a fetch
     * stamped more than [CLOCK_SKEW] in the future ([isFromFuture]): it would otherwise read as
     * fresh until the clock caught up with it.
     */
    @MainSafe
    fun isStale(age: Duration, threshold: Duration = THRESHOLD): Boolean = isFromFuture(age) || age >= threshold

    /** True for a stamp more than [CLOCK_SKEW] ahead of the clock: made before the clock was set back. */
    @MainSafe
    fun isFromFuture(age: Duration): Boolean = age < -CLOCK_SKEW

    /**
     * How old the fetch stamped [fetchedAt] is at the wall time [now], by the steady clock
     * ([SteadyClock.age]), so setting the device's clock doesn't make old data read as new.
     */
    @MainSafe
    fun age(fetchedAt: Instant, now: Instant): Duration = SteadyClock.age(fetchedAt, now).toKotlinDuration()

    /** Whether the fetch stamped [fetchedAt] is stale at the wall time [now] ([age], [isStale]). */
    @MainSafe
    fun isStale(fetchedAt: Instant, now: Instant, threshold: Duration = THRESHOLD): Boolean = isStale(age(fetchedAt, now), threshold)

    /**
     * Time left before a fetch this old crosses the staleness boundary — for scheduling a
     * one-shot render-only redraw that flips a *static* surface (the widget, whose host never
     * re-renders it on its own) to the stale treatment at the boundary. [Duration.ZERO] once
     * already at or past it: there is nothing left to flip, so the caller schedules nothing.
     * Clock-free like [isStale]; the caller passes the age.
     */
    fun remainingUntilStale(age: Duration): Duration = (THRESHOLD - age).coerceAtLeast(Duration.ZERO)
}
