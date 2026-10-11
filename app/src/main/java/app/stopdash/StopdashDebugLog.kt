package app.stopdash

import androidx.annotation.WorkerThread
import app.stopdash.domain.BugReport
import app.stopdash.domain.Coordinates
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.ProcessExits

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
 * The app's section of a consent-gated bug report. Everything but this run's log is composed
 * first, and the log ([DebugReport.logLines]) gets whatever room that leaves of the section's share,
 * [DebugReport.APP_SECTION_CHARS], so a long list of nearby stops shortens the log rather than
 * pushing the section past it. androidlog sets that share so the whole report survives being
 * pasted; past it, the library cuts a section from the middle, which here would be the start of
 * this log.
 *
 * The log keeps at least a whole process-exit batch ([ProcessExits.maxBatchChars]) whatever the
 * rest takes. Only a stop list far longer than any lookup returns needs it, and then the library's
 * cut takes the middle of that list rather than why the previous processes ended.
 */
@WorkerThread
internal fun bugReportSection(
    header: BugReport.Header,
    location: Coordinates?,
    stops: List<BugReport.StopLine>,
    recentPositions: List<String>,
    log: DebugLog = StopdashDebugLog,
): String {
    val rest = BugReport.compose(header, location, stops, logLines = emptyList(), recentPositions)
    // The log's lines replace the empty log's placeholder, so charging it to the rest is margin.
    val logBudget = maxOf(DebugReport.APP_SECTION_CHARS - rest.length, ProcessExits.maxBatchChars())
    return BugReport.compose(header, location, stops, DebugReport.logLines(log, logBudget), recentPositions)
}
