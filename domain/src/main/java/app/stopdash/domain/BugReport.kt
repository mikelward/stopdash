package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant
import java.util.Locale

/**
 * Builds the **text** of a user-shareable bug report — the app's own section, which the shared
 * `mikelward/androidlog` `DebugReport` then wraps (it appends any persisted earlier runs and
 * handles the clipboard + share sheet). Pure and JVM-testable: it takes plain values and returns
 * a string, so its exact wording is pinned by a unit test rather than an emulator.
 *
 * **This report deliberately crosses the on-device-log privacy line — under consent.** Unlike
 * everything the app logs (coarse stop/line/status only, never a coordinate — `docs/PRIVACY.md`),
 * a report composed here carries the user's **exact location** and **how far they are from each
 * nearby stop**, because that is exactly the context a routing/location bug is diagnosed from. It
 * is never assembled or shared except behind the explicit consent screen that names the location
 * (SPEC *Privacy*, `TODO.md`), so it is honest rather than location-safe: it says plainly what it
 * shares instead of stripping the context that makes it useful.
 */
object BugReport {
    /** The build/device facts that head the report — none of it user data. */
    data class Header(
        val versionName: String,
        val versionCode: Long,
        val device: String,
        val androidRelease: String,
        val sdkInt: Int,
        val capturedAt: Instant,
    )

    /** How many of the nearby stops a report lists, the nearest; the rest are counted. */
    const val MAX_STOPS = 20

    /** The most the recent positions may take of a report, the newest kept. */
    const val MAX_POSITIONS_CHARS = 2_000

    /** The most of one recent position a report keeps: a dense lookup lists every stop it found. */
    const val MAX_POSITION_CHARS = 300

    /**
     * [positions] (oldest first) held to [MAX_POSITIONS_CHARS], newest kept, each cut to
     * [MAX_POSITION_CHARS], with a line counting any older ones left out.
     */
    @WorkerThread
    fun boundPositions(positions: List<String>): List<String> {
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in positions.asReversed()) {
            val cut = cutPosition(line)
            if (used + cut.length + 1 > MAX_POSITIONS_CHARS) break
            kept.addFirst(cut)
            used += cut.length + 1
        }
        val dropped = positions.size - kept.size
        return if (dropped == 0) kept.toList() else listOf("($dropped older left out)") + kept
    }

    // [line] held to [MAX_POSITION_CHARS], cut from its middle: the position it was taken at ends it
    // ("… at 51.500000, -0.120000"), so a dense lookup loses some of its stops, never where it was.
    private fun cutPosition(line: String): String {
        if (line.length <= MAX_POSITION_CHARS) return line
        val at = line.lastIndexOf(" at ")
        val tail = if (at >= 0 && line.length - at < MAX_POSITION_CHARS / 2) line.substring(at) else ""
        return line.take(MAX_POSITION_CHARS - tail.length - 1) + "…" + tail
    }

    /** One nearby stop and, when known, the distance from the fix to it. */
    data class StopLine(val name: String, val id: String, val meters: Double?)

    /**
     * Compose the report's app section from the current context: the build [header], the exact
     * [location] the report is being filed from (null when no fix is available), the [stops] the
     * user is watching with their distances, and this run's diagnostic [logLines] (the shared
     * `DebugLog` snapshot). Deterministic and side-effect-free.
     */
    @WorkerThread
    fun compose(
        header: Header,
        location: Coordinates?,
        stops: List<StopLine>,
        logLines: List<String>,
        // The last few positions the app worked from (RecentPositions): in memory only, and this
        // report is the one place they leave the device (consent-gated, SPEC *Privacy*).
        recentPositions: List<String> = emptyList(),
    ): String = buildString {
        appendLine("StopDash bug report")
        appendLine("version: ${header.versionName} (${header.versionCode})")
        appendLine("device: ${header.device}, Android ${header.androidRelease} (API ${header.sdkInt})")
        appendLine("captured: ${header.capturedAt}")
        appendLine()

        // The exact coordinate — the point of this report, and why it is consent-gated. Fixed to
        // six decimals (about 0.1 m) in US format so a decimal-comma locale can't mangle it.
        // Labeled as the *last nearby lookup* fix, not the send-time position: the departures
        // screen can stay open while the user moves (auto-refresh doesn't re-locate), so the fix
        // may predate `captured` — the report says so rather than misattributing it to now.
        appendLine(
            if (location == null) {
                "location: unavailable"
            } else {
                "location (last nearby lookup): ${format(location.latitude)}, ${format(location.longitude)}"
            },
        )

        appendLine("nearby stops (${stops.size}):")
        if (stops.isEmpty()) {
            appendLine("  none")
        } else {
            // The nearest few: the rest only crowd the log out of a report that has to fit where it's
            // pasted. Sorted here, as the lookup's tiers are each nearest first but not together.
            for (stop in stops.sortedWith(compareBy(nullsLast()) { it.meters }).take(MAX_STOPS)) {
                appendLine("  • ${stop.name} (${stop.id}) — ${distance(stop.meters)}")
            }
            if (stops.size > MAX_STOPS) appendLine("  (+${stops.size - MAX_STOPS} more)")
        }
        appendLine()

        appendLine(
            "--- recent positions (last ${RecentPositions.TTL_MILLIS / 60_000} min, " +
                "at most ${RecentPositions.CAPACITY}) ---",
        )
        appendLine(if (recentPositions.isEmpty()) "(none)" else boundPositions(recentPositions).joinToString("\n"))
        appendLine()

        appendLine("--- log (this run, newest last; older lines are dropped to keep the report shareable) ---")
        if (logLines.isEmpty()) {
            append("(no diagnostics recorded this run)")
        } else {
            append(logLines.joinToString("\n"))
        }
    }

    // The human label ("120 m") plus the raw metres to one decimal, so the report keeps the
    // diagnostic precision the UI rounds away. "distance unknown" rather than a fabricated 0.
    private fun distance(meters: Double?): String =
        if (meters == null) "distance unknown" else "${StopDistance.label(meters)} (${format1(meters)} m)"

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
}
