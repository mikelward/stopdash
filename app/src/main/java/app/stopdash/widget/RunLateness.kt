package app.stopdash.widget

import android.os.SystemClock
import androidx.work.Data
import kotlin.math.abs
import kotlin.math.max

/**
 * How late Android ran a background job, for the debug log: a widget holds its last render
 * meanwhile, so a long delay is a widget showing times that have passed (bug report, 2026-10-16).
 *
 * Measured on both clocks. Uptime ([SystemClock.elapsedRealtime], deep sleep included) isn't moved
 * when the wall clock is set, so it's the measure while the two agree; a wall clock corrected by
 * hours while a job waited would otherwise read as hours of lateness, or hide them (Codex). Uptime
 * restarts with the device, though, and the wall clock doesn't, so when the two disagree the log
 * gives both and says the clock was set or the device restarted: neither alone can be trusted then,
 * and no cheaper read tells which.
 */
internal object RunLateness {
    private const val DUE_UPTIME_KEY = "dueUptime"
    private const val DUE_WALL_KEY = "dueWall"

    /** Within this the two clocks agree: their own drift, and the gap between two reads. */
    private const val CLOCKS_AGREE_MILLIS = 60_000L

    /** Input entries for a job falling due [delayMillis] from now. */
    fun dueIn(
        delayMillis: Long,
        uptimeMillis: Long = SystemClock.elapsedRealtime(),
        wallMillis: Long = System.currentTimeMillis(),
    ): Array<Pair<String, Any?>> =
        arrayOf(DUE_UPTIME_KEY to uptimeMillis + delayMillis, DUE_WALL_KEY to wallMillis + delayMillis)

    /** How late a job scheduled with [input] runs now, or null when it carries no due time (an older build's). */
    fun of(
        input: Data,
        uptimeMillis: Long = SystemClock.elapsedRealtime(),
        wallMillis: Long = System.currentTimeMillis(),
    ): Late? {
        val dueUptime = input.keyValueMap[DUE_UPTIME_KEY] as? Long ?: return null
        val dueWall = input.keyValueMap[DUE_WALL_KEY] as? Long ?: return null
        return Late(byUptimeMillis = uptimeMillis - dueUptime, byWallMillis = wallMillis - dueWall)
    }

    data class Late(val byUptimeMillis: Long, val byWallMillis: Long) {
        /** False when the wall clock was set, or the device restarted, since the job was scheduled. */
        val clocksAgree: Boolean get() = abs(byUptimeMillis - byWallMillis) < CLOCKS_AGREE_MILLIS

        /** Whether it ran at least [millis] late by either clock: with the two apart, either may be the true one. */
        fun atLeast(millis: Long): Boolean =
            if (clocksAgree) byUptimeMillis >= millis else max(byUptimeMillis, byWallMillis) >= millis

        /** "600 s", or both figures when the clocks disagree. */
        fun describe(): String =
            if (clocksAgree) {
                "${byUptimeMillis / 1000} s"
            } else {
                "${byUptimeMillis / 1000} s by uptime, ${byWallMillis / 1000} s by the wall clock " +
                    "(the clock was set or the device restarted)"
            }
    }
}
