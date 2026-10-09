package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The journey alert check's debug-log lines (SPEC *Journeys → Alerts*), on synthetic stops only. */
class JourneyAlertLogTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-10-09T06:16:00Z")
    private val journey = FavoriteJourney(JourneyEnd("A", "Alpha"), JourneyEnd("B", "Beta"), "northern", "Northern", "tube")

    @Test
    fun `a scheduled check says when, in local time, and whether it asks TfL`() {
        assertEquals(
            "next check in 15 min (07:31), window open, waits for a network",
            JourneyAlertLog.scheduled(now, now.plus(Duration.ofMinutes(15)), asks = true, zone),
        )
        assertEquals(
            "next check in 0 min (07:16), no window open then",
            JourneyAlertLog.scheduled(now, now.minusSeconds(30), asks = false, zone),
        )
    }

    @Test
    fun `a check names each line it asked about with what TfL said, and what it did`() {
        val statuses = mapOf(
            "northern" to LineStatus("northern", 6, "Severe Delays"),
            "134" to LineStatus("134", LineStatus.GOOD_SERVICE, "Good Service"),
        )
        val result = JourneyAlertResult(journey.key, listOf(JourneyLineAlert("northern", "Northern", "Severe Delays", null)))
        val line = JourneyAlertLog.check(
            late = Duration.ofSeconds(806), closeOnly = false, watched = 1, skipped = null,
            asked = setOf("northern", "134", "43"), statuses = statuses,
            done = listOf(JourneyAlertAction.Post(result)), notDone = 0,
        )
        assertEquals(
            "check ran 806 s after due: 1 journeys watched; TfL: 134 Good Service, 43 not answered, northern Severe Delays; posted 1, renewed 0, cleared 0",
            line,
        )
        // Never a journey's ends: those say where the rider lives and works.
        assertFalse(line.contains("Alpha") || line.contains("Beta") || line.contains(journey.key))
    }

    @Test
    fun `a check TfL didn't answer says so rather than reading as all clear`() {
        assertEquals(
            "check ran: 1 journeys watched; TfL: northern no answer; posted 0, renewed 0, cleared 0",
            JourneyAlertLog.check(null, false, 1, null, setOf("northern"), null, emptyList(), 0),
        )
    }

    @Test
    fun `a check that asked nothing says why, and decisions left for later are counted`() {
        assertEquals(
            "close check ran 3 s after due: 0 journeys watched; no window open; posted 0, renewed 0, cleared 1, left 2 for later",
            JourneyAlertLog.check(Duration.ofSeconds(3), true, 0, "no window open", emptySet(), null, listOf(JourneyAlertAction.Clear("k")), 2),
        )
    }

    @Test
    fun `a close check says when it runs`() {
        assertEquals("close check in 45 min (08:01)", JourneyAlertLog.closeScheduled(now, now.plus(Duration.ofMinutes(45)), zone))
        assertEquals("close check in 0 min (07:16)", JourneyAlertLog.closeScheduled(now, now.minusSeconds(5), zone))
    }

    @Test
    fun `nothing armed says why`() {
        assertEquals("not scheduled: notifications off", JourneyAlertLog.notScheduled(watched = true))
        assertEquals("not scheduled: no direction watched", JourneyAlertLog.notScheduled(watched = false))
    }
}
