package app.stopdash

import app.stopdash.domain.BugReport
import app.stopdash.domain.Coordinates
import app.stopdash.domain.RecentPositions
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.ProcessExits
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bug report's own section has to fit its share of a report androidlog holds to a size the
 * share sheet and the clipboard can carry, and small enough to survive being pasted on whole, so
 * the log is given only the room the rest of the section leaves. All inputs are synthetic — the
 * stand-in `(51.5, -0.12)` fix and made-up stop names and ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugReportSizeTest {
    private val header = BugReport.Header(
        versionName = "1.0.120+abc1234",
        versionCode = 120,
        device = "Google Pixel 8",
        androidRelease = "15",
        sdkInt = 35,
        capturedAt = Instant.parse("2026-09-21T14:30:00Z"),
    )

    private val positions = List(RecentPositions.CAPACITY) {
        "2026-09-21T14:29:${it.toString().padStart(2, '0')}Z nearby lookup 51.500000, -0.120000 ±15 m"
    }

    private fun stops(count: Int) =
        (1..count).map { BugReport.StopLine("Example Stop Number $it ${"x".repeat(40)}", "EXAMPLE$it", 1_234.5) }

    private fun busyLog() = DebugLog().apply {
        pinnedEvent("processExit reason=anr")
        repeat(DebugLog.DEFAULT_MAX_ENTRIES) { event("line-%s %s", it, "x".repeat(1_500)) }
        event("the newest event")
    }

    private fun section(stopCount: Int, log: DebugLog = busyLog()) =
        bugReportSection(header, Coordinates(51.5, -0.12), stops(stopCount), positions, log)

    private fun logLinesIn(section: String) = section.lines().count { "line-" in it }

    @Test
    fun `a busy run's log keeps its newest lines within its budget`() {
        val lines = busyLog().reportLines(30_000)

        assertTrue(lines.last().endsWith("the newest event"))
        assertTrue(lines.none { "line-0 " in it })
        assertTrue("${lines.sumOf { it.length + 1 }}", lines.sumOf { it.length + 1 } <= 30_000)
    }

    @Test
    fun `a run with nothing pinned gives the whole budget to its newest lines`() {
        val log = DebugLog().apply {
            repeat(DebugLog.DEFAULT_MAX_ENTRIES) { event("line-%s %s", it, "x".repeat(1_500)) }
        }

        val used = log.reportLines(30_000).sumOf { it.length + 1 }

        assertTrue("$used", used in (30_000 - 2_000)..30_000)
    }

    @Test
    fun `a quiet run's log is reported whole`() {
        val log = DebugLog()
        repeat(40) { log.event("line %s", it) }

        assertEquals(40, log.reportLines().count { " line " in it })
    }

    @Test
    fun `a busy run's section fills its share and keeps the newest lines`() {
        val section = section(stopCount = 10)

        assertTrue("${section.length}", section.length <= REPORT_SECTION_CHARS)
        // Short by less than a log line, so the log was given the room the rest left rather than a
        // fixed slice of it, and the pinned reserve kept none of it back.
        val shortfall = REPORT_SECTION_CHARS - section.length
        assertTrue("$shortfall", shortfall < 2_000)
        assertTrue(section.endsWith("the newest event"))
        assertTrue(section.contains("processExit reason=anr"))
    }

    @Test
    fun `a long stop list lists the nearest and leaves the log its room`() {
        val few = section(stopCount = BugReport.MAX_STOPS)
        val many = section(stopCount = 1_000)

        // The premise: the stops alone would take far more than the whole share.
        assertTrue(stops(1_000).sumOf { it.name.length } > REPORT_SECTION_CHARS)
        assertTrue("${many.length}", many.length <= REPORT_SECTION_CHARS)
        assertTrue(many.contains("(+${1_000 - BugReport.MAX_STOPS} more)"))
        assertEquals(logLinesIn(few), logLinesIn(many))
        assertTrue(many.endsWith("the newest event"))
        assertTrue(many.contains("processExit reason=anr"))
    }

    @Test
    fun `dense recent lookups don't push the section past its share`() {
        // Each lookup lists every stop it found: twenty of them would fill the share by themselves.
        val dense = List(RecentPositions.CAPACITY) { "2026-09-21T14:29:00Z nearby lookup: " + "EXAMPLE 123 m, ".repeat(100) }
        val section = bugReportSection(header, Coordinates(51.5, -0.12), stops(1_000), dense, busyLog())

        assertTrue("${section.length}", section.length <= REPORT_SECTION_CHARS)
        assertTrue(section.endsWith("the newest event"))
        assertTrue(section.contains("processExit reason=anr"))
    }

    @Test
    fun `the section is shared whole, ahead of the earlier runs`() {
        val section = section(stopCount = 1_000)

        val report = DebugReport.collect(DebugLog(), null) { section }.text

        // Under the ~20,000 characters a pasted report was kept to, with its newest line in it.
        assertTrue("${report.length}", report.length <= 20_000)
        assertTrue(report.startsWith("StopDash bug report"))
        assertTrue(report.contains("the newest event"))
    }

    @Test
    fun `at the floor a run with nothing pinned still carries its newest line`() {
        val log = DebugLog().apply {
            repeat(DebugLog.DEFAULT_MAX_ENTRIES) { event("line-%s %s", it, "x".repeat(100)) }
            event("the newest event")
        }

        val section = section(stopCount = 1_000, log = log)

        assertTrue(section.endsWith("the newest event"))
        assertTrue(logLinesIn(section) > 0)
    }
}
