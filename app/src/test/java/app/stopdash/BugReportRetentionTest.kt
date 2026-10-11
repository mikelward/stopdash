package app.stopdash

import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.ShareOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Sending a bug report keeps the earlier runs it carried: whether a report was really sent is
 * unknowable (the share sheet reports nothing back, and a clipboard copy is only a copy), so the
 * runs stay on the phone under the log's own retention and a later report can carry them again.
 *
 * Runs StopDash's real sink installation ([installDiagnosticSinks]) and the same androidlog
 * collect/deliver calls the report path makes, so a library release that went back to deleting
 * on share fails here rather than being taken in a version bump unnoticed (Codex, PR #780).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugReportRetentionTest {
    @Test
    fun `sending a report keeps the earlier run for the next one`() {
        val context = RuntimeEnvironment.getApplication()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // The previous process's log, left where the sink finds it at start.
        File(context.cacheDir, "androidlog.log").writeText("the earlier run's last line\n")

        val log = DebugLog()
        val sink = installDiagnosticSinks(log, context, installCrashHandler = false)
        sink.awaitIdle()

        fun share(): Pair<String, ShareOutcome> {
            val report = DebugReport.collect(log, sink) { "this run" }
            val outcome = DebugReport.deliver(
                context = context,
                log = log,
                report = report,
                subject = "subject",
                chooserTitle = "chooser",
                clipboardLabel = "label",
            )
            sink.awaitIdle()
            return report.text to outcome
        }

        val (first, firstOutcome) = share()
        assertEquals(ShareOutcome.SHARED, firstOutcome)
        assertTrue("precondition: the report carried the earlier run", "the earlier run's last line" in first)

        val (second, _) = share()
        assertTrue("the next report still has it", "the earlier run's last line" in second)
    }
}
