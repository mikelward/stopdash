package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * The clock a fetch's age is judged by (SPEC *Freshness*): the device's monotonic one, which setting
 * the time doesn't move, so what was fetched before the clock was set back is still as old as it is,
 * rather than reading as new until the clock catches up with its stamp (maintainer, 2026-09-29).
 * Countdowns stay on the wall clock: a departure's time is TfL's, and it's shown against the time
 * the rider sees.
 *
 * A fetch is stamped in this process's steady frame ([stamp]): the wall time it would be had the
 * clock not been set since the process started. Its age is judged in the same frame ([age]), so a
 * later change to the clock, either way, doesn't change it. What a process stores carries its
 * [Frame], so another process of the same boot moves the stamps into its own ([shiftFrom]). Across
 * a reboot the monotonic clock starts again, and the wall clock is all there is to go on:
 * [Staleness.isFromFuture] then catches a stamp from before the clock was set back.
 *
 * Pure: each app installs its [Source] at start. With none (the JVM tests), the steady frame is the
 * wall clock, so every age is as the wall clock gives it.
 */
object SteadyClock {
    /**
     * The steady frame a process stamps in: the boot it's in, and the wall time the device booted at
     * as the process first read it (steady time is this plus the time since boot). [boot] names this
     * install as well as the boot count, so a snapshot restored onto another device, whose count
     * may match, is never taken for one of this boot's (Codex, PR #371).
     */
    data class Frame(val boot: String, val originMillis: Long)

    /** Where the device's monotonic clock stands against its wall clock. */
    interface Source {
        /** This process's frame, or null where the boot can't be told (so nothing stored is moved). */
        val frame: Frame?

        /** Steady time minus wall time, now: how far the wall clock has been set back since the process started. */
        fun offset(): Duration
    }

    @Volatile
    var source: Source? = null

    /** Steady time minus wall time, now; zero with no [source]. */
    fun offset(): Duration = source?.offset() ?: Duration.ZERO

    /** A fetch made at the wall time [wall], stamped in this process's steady frame. */
    @MainSafe
    fun stamp(wall: Instant): Instant = wall.plus(offset())

    /** How old a fetch [stamp]ed in this process's frame is at the wall time [now] (a render's, or a frame to come). */
    @MainSafe
    fun age(stamp: Instant, now: Instant): Duration = Duration.between(stamp, now.plus(offset()))

    /** A [stamp]ed instant as the wall clock reads it now, for anything shown or scheduled by the wall clock. */
    @MainSafe
    fun toWall(stamp: Instant): Instant = stamp.minus(offset())

    /**
     * How far to move a stamp stored by a process of frame [from] into this one's ([shiftBetween]).
     */
    fun shiftFrom(from: Frame?): Duration = shiftBetween(from, source?.frame)

    /**
     * How far to move a stamp made in frame [from] into frame [to]: by the difference of their
     * origins within one boot, which is how far the wall clock was set between the two readings, and
     * not at all across a reboot or where either can't be told, leaving it as the wall clock read it.
     * Under [Staleness.CLOCK_SKEW] it's the clocks' own drift, not a setting, and moves nothing.
     */
    fun shiftBetween(from: Frame?, to: Frame?): Duration {
        if (from == null || to == null || from.boot != to.boot) return Duration.ZERO
        val shift = to.originMillis - from.originMillis
        return if (kotlin.math.abs(shift) < Staleness.CLOCK_SKEW.inWholeMilliseconds) Duration.ZERO else Duration.ofMillis(shift)
    }
}
