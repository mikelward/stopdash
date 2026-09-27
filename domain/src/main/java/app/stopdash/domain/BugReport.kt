package app.stopdash.domain

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

    /** One nearby stop and, when known, the distance from the fix to it. */
    data class StopLine(val name: String, val id: String, val meters: Double?)

    /**
     * Compose the report's app section from the current context: the build [header], the exact
     * [location] the report is being filed from (null when no fix is available), the [stops] the
     * user is watching with their distances, and this run's diagnostic [logLines] (the shared
     * `DebugLog` snapshot). Deterministic and side-effect-free.
     */
    fun compose(
        header: Header,
        location: Coordinates?,
        stops: List<StopLine>,
        logLines: List<String>,
        // The last few positions the app worked from (RecentPositions): in memory only, and this
        // report is the one place they leave the device (consent-gated, SPEC *Privacy*).
        recentPositions: List<String> = emptyList(),
    ): String = buildString {
        appendLine("LDNGo bug report")
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
            for (stop in stops) {
                appendLine("  • ${stop.name} (${stop.id}) — ${distance(stop.meters)}")
            }
        }
        appendLine()

        appendLine(
            "--- recent positions (last ${RecentPositions.TTL_MILLIS / 60_000} min, " +
                "at most ${RecentPositions.CAPACITY}) ---",
        )
        appendLine(if (recentPositions.isEmpty()) "(none)" else recentPositions.joinToString("\n"))
        appendLine()

        appendLine("--- log (this run) ---")
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
