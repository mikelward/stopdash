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
            LDNGo bug report
            version: 1.0.120+abc1234 (120)
            device: Google Pixel 8, Android 15 (API 35)
            captured: 2026-09-21T14:30:00Z

            location (last nearby lookup): 51.500000, -0.120000
            nearby stops (2):
              • Oxford Circus (940GZZLUOXC) — 120 m (118.7 m)
              • Bond Street (940GZZLUBND) — 340 m (337.2 m)

            --- recent positions (last 15 min, at most 20) ---
            (none)

            --- log (this run) ---
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
}
