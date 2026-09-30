package app.stopdash

import com.mikelward.androidlog.DebugLog

/**
 * StopDash's process-wide diagnostic log — the shared `mikelward/androidlog` buffer that
 * `docs/PRIVACY.md` describes. A named subclass rather than the base [DebugLog] directly, so
 * it reads as one log at every call site while a test can still build a fresh instance.
 *
 * Sinks are registered once in [StopdashApp]: a Logcat sink (the developer-facing log) and the
 * on-device file sink that persists the buffer to `cacheDir`, so a crash or a silent kill still
 * leaves a diagnosable record. The one **off-device** sink is Crashlytics, registered only in a
 * build with a Firebase config and fed only while the user has opted in
 * (`app.stopdash.telemetry.CrashlyticsLogSink`). It receives the library's `Destination.OFF_DEVICE`
 * rendering, where a `String` argument is withheld and shown as a placeholder unless wrapped in
 * `safe(...)` — so no call site's stop id, line id or coordinate reaches it.
 *
 * The *Privacy* rule governs every call regardless of sink: log a stop id, a line id, or an
 * HTTP status — never a coordinate or the TfL `app_key`. Pass a format **literal** with one
 * `%s` per argument (never interpolate a value into the format string), so an argument's type,
 * not its text, decides whether it could ever leave the device.
 */
internal object StopdashDebugLog : DebugLog()

/**
 * This run's log for the consent-gated bug report: the whole ring, preceded by any pinned lines
 * the ring has since evicted, such as why the previous processes ended ([logRecentProcessExits]).
 * [DebugLog.snapshot] reads the ring only, so a busy run would push those out of the report.
 *
 * Neither section is trimmed, because the report carries the whole ring and has no size cap of
 * its own. A cap would reserve at least `ProcessExits.maxBatchChars()` for the pinned section.
 */
internal fun DebugLog.reportLines(): List<String> =
    boundedSnapshot(pinnedBudgetChars = Int.MAX_VALUE, recentBudgetChars = Int.MAX_VALUE)
