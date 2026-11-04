package app.stopdash.domain

import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A line alert's daily hours, read from TfL's words. */
class AlertHoursTest {
    private val nightly = AlertHours(LocalTime.of(22, 0), LocalTime.of(6, 0))

    // London time on 4 November 2026 (GMT, so the same in UTC).
    private fun london(time: String, day: Int = 4) = Instant.parse("2026-11-%02dT%s:00Z".format(day, time))

    @Test
    fun `daily hours are read where TfL gives them with their days or as nightly`() {
        // TfL's wording, 2026-11-04.
        assertEquals(
            nightly,
            AlertHours.of(
                "Oxford Street (East)  W1W  Routes  55  73  98  390: Due to nightly crane operations Oxford Street will be " +
                    "closed between Tottenham Court Road and Great Portland Street 22:00-06:00 3-5 November. Buses will be diverted.",
            ),
        )
        assertEquals(AlertHours(LocalTime.of(20, 0), LocalTime.of(5, 0)), AlertHours.of("Closed nightly 20:00-05:00 12-17 October."))
        assertEquals(AlertHours(LocalTime.of(9, 30), LocalTime.of(15, 0)), AlertHours.of("Diverted 09:30 - 15:00 12 October."))
    }

    @Test
    fun `anything else runs all day`() {
        // One stretch of time, from one day to the next: not daily hours.
        assertNull(AlertHours.of("Closed from 22:00 5 November until 06:00 6 November."))
        // Another time besides the pair.
        assertNull(AlertHours.of("Diverted 22:00-06:00 3-5 November, and from 10:00 on 6 November."))
        // Or another time in another form TfL writes (Codex on #1256).
        assertNull(AlertHours.of("Closed 22:00-06:00 3-5 November, and from 1000 on 6 November."))
        assertNull(AlertHours.of("Closed 22:00-06:00 3-5 November, and from 10.00 on 6 November."))
        // A pair of times with neither days nor "nightly".
        assertNull(AlertHours.of("Diverted 22:00-06:00 while works continue."))
        // Wording not seen from TfL.
        assertNull(AlertHours.of("Diverted between 22:00 and 06:00 3-5 November."))
        assertNull(AlertHours.of("Diverted."))
        assertNull(AlertHours.of(null))
    }

    @Test
    fun `hours past midnight run into the next day`() {
        assertFalse(nightly.overlaps(london("11:10"), london("12:20")))
        assertTrue(nightly.overlaps(london("21:40"), london("22:10")))
        assertTrue(nightly.overlaps(london("23:00"), london("23:30")))
        assertTrue(nightly.overlaps(london("05:30"), london("05:45")))
        assertFalse(nightly.overlaps(london("06:00"), london("21:59")))
        // A day window.
        val day = AlertHours(LocalTime.of(9, 30), LocalTime.of(15, 0))
        assertTrue(day.overlaps(london("15:00").minusSeconds(60), london("16:00")))
        assertFalse(day.overlaps(london("15:00"), london("16:00")))
    }

    @Test
    fun `hours ending in the hour the clocks repeat run to its second time`() {
        // 25 October 2026: 01:00-02:00 London comes twice, at 00:00Z (BST) and 01:00Z (GMT) (Codex on #1256).
        val late = AlertHours(LocalTime.of(22, 0), LocalTime.of(1, 30))
        assertTrue(late.overlaps(Instant.parse("2026-10-25T01:05:00Z"), Instant.parse("2026-10-25T01:25:00Z")))
        assertFalse(late.overlaps(Instant.parse("2026-10-25T01:35:00Z"), Instant.parse("2026-10-25T02:00:00Z")))
    }

    @Test
    fun `hours opening in the hour the clocks skip open when they change`() {
        // 28 March 2027: London goes from 01:00 GMT to 02:00 BST at 01:00Z (Codex on #1256).
        val early = AlertHours(LocalTime.of(1, 30), LocalTime.of(2, 15))
        // 02:05-02:10 BST.
        assertTrue(early.overlaps(Instant.parse("2027-03-28T01:05:00Z"), Instant.parse("2027-03-28T01:10:00Z")))
        // 02:20 BST on.
        assertFalse(early.overlaps(Instant.parse("2027-03-28T01:20:00Z"), Instant.parse("2027-03-28T01:40:00Z")))
    }

    @Test
    fun `hours are London's in summer time too`() {
        // 21:30 UTC is 22:30 in London in October.
        assertTrue(nightly.overlaps(Instant.parse("2026-10-12T21:30:00Z"), Instant.parse("2026-10-12T21:40:00Z")))
        assertFalse(nightly.overlaps(Instant.parse("2026-10-12T20:30:00Z"), Instant.parse("2026-10-12T20:50:00Z")))
    }
}
