package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wording of the shareable bug report. All inputs are synthetic — an obviously-fake
 * `(51.5, -0.12)` fix and documentation-example TfL stop ids — never a value lifted from a real
 * device (SPEC *Privacy*: the test is whether a value is somebody's, not whether it looks real).
 */
class BugReportTest {
    private val header = BugReport.Header(
        versionName = "1.0.120+abc1234",
        versionCode = 120,
        device = "Google Pixel 8",
        androidRelease = "15",
        sdkInt = 35,
        capturedAt = Instant.parse("2026-09-21T14:30:00Z"),
    )

    @Test
    fun `it renders the header, exact location, distances, and this run's log`() {
        val report = BugReport.compose(
            header = header,
            location = Coordinates(51.5, -0.12),
            stops = listOf(
                BugReport.StopLine("Oxford Circus", "940GZZLUOXC", 118.7),
                BugReport.StopLine("Bond Street", "940GZZLUBND", 337.2),
            ),
            logLines = listOf("location: fix obtained", "departures: 429 for stop 940GZZLUOXC"),
        )

        assertEquals(
            """
            StopDash bug report
            version: 1.0.120+abc1234 (120)
            device: Google Pixel 8, Android 15 (API 35)
            captured: 2026-09-21T14:30:00Z

            location (last nearby lookup): 51.500000, -0.120000
            nearby stops (2):
              • Oxford Circus (940GZZLUOXC) — 120 m (118.7 m)
              • Bond Street (940GZZLUBND) — 340 m (337.2 m)

            --- recent positions (last 15 min, at most 20) ---
            (none)

            --- log (this run, newest last; older lines are dropped to keep the report shareable) ---
            location: fix obtained
            departures: 429 for stop 940GZZLUOXC
            """.trimIndent(),
            report,
        )
    }

    @Test
    fun `an unavailable fix is stated, not faked`() {
        val report = BugReport.compose(
            header = header,
            location = null,
            stops = emptyList(),
            logLines = emptyList(),
        )

        assertTrue(report, report.contains("location: unavailable"))
        assertTrue(report, report.contains("nearby stops (0):\n  none"))
        assertTrue(report, report.contains("(no diagnostics recorded this run)"))
    }

    @Test
    fun `a stop with no known distance says so rather than showing zero`() {
        val report = BugReport.compose(
            header = header,
            location = Coordinates(51.5, -0.12),
            stops = listOf(BugReport.StopLine("Bond Street", "940GZZLUBND", null)),
            logLines = emptyList(),
        )

        assertTrue(report, report.contains("• Bond Street (940GZZLUBND) — distance unknown"))
    }

    @Test
    fun `recent positions get their own section, or say none`() {
        val none = BugReport.compose(header, location = null, stops = emptyList(), logLines = emptyList())
        assertTrue(none, "--- recent positions (last 15 min, at most 20) ---\n(none)" in none)
        val some = BugReport.compose(
            header,
            location = null,
            stops = emptyList(),
            logLines = emptyList(),
            recentPositions = listOf("10:00:00 fresh network fix at 0.00000,0.00000"),
        )
        assertTrue(some, "10:00:00 fresh network fix at 0.00000,0.00000" in some)
    }

    @Test
    fun `a long stop list keeps the nearest and counts the rest`() {
        val stops = (1..30).map { BugReport.StopLine("Example Stop $it", "EXAMPLE$it", it * 10.0) }
        val report = BugReport.compose(header, location = null, stops = stops, logLines = emptyList())

        assertTrue(report, report.contains("nearby stops (30):"))
        assertTrue(report, report.contains("(EXAMPLE20)"))
        assertTrue(report, !report.contains("(EXAMPLE21)"))
        assertTrue(report, report.contains("  (+10 more)"))
    }

    @Test
    fun `the nearest stops are listed however the lookup ordered them`() {
        // Two tiers, each nearest first: a far eager stop ahead of near ones from "more".
        val stops = listOf(BugReport.StopLine("Far", "FAR", 900.0)) +
            (1..25).map { BugReport.StopLine("Near $it", "NEAR$it", it * 10.0) } +
            listOf(BugReport.StopLine("Unknown", "UNKNOWN", null))
        val report = BugReport.compose(header, location = null, stops = stops, logLines = emptyList())

        assertTrue(report, report.contains("(NEAR20)"))
        assertTrue(report, !report.contains("(NEAR21)"))
        assertTrue(report, !report.contains("(FAR)"))
        assertTrue(report, !report.contains("(UNKNOWN)"))
    }

    @Test
    fun `recent positions keep the newest within their share, each cut short`() {
        val positions = (1..20).map { "position-$it " + "x".repeat(1_000) }
        val bound = BugReport.boundPositions(positions)

        assertTrue(bound.last(), bound.last().startsWith("position-20 "))
        assertTrue(bound.first(), bound.first().endsWith("older left out)"))
        assertTrue(bound.drop(1).all { it.length <= BugReport.MAX_POSITION_CHARS })
        assertTrue("${bound.sumOf { it.length + 1 }}", bound.sumOf { it.length + 1 } <= BugReport.MAX_POSITIONS_CHARS + 40)
        assertEquals(listOf("a", "b"), BugReport.boundPositions(listOf("a", "b")))
        // A dense lookup is cut from its middle, keeping where it was taken.
        val dense = BugReport.boundPositions(listOf("14:29:00Z nearby lookup: " + "EXAMPLE 123 m, ".repeat(50) + " at 51.500000, -0.120000"))
        assertTrue(dense.single(), dense.single().endsWith(" at 51.500000, -0.120000"))
        assertTrue(dense.single(), dense.single().startsWith("14:29:00Z nearby lookup: "))
        assertEquals(BugReport.MAX_POSITION_CHARS, dense.single().length)
    }
}
