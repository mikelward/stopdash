package app.stopdash.domain

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStartTest {
    // A Monday morning in late September, London summer time.
    private val now: Instant = Instant.parse("2026-09-28T06:00:00Z")

    private fun start(text: String) = AlertStart.startDate(text, now)

    @Test
    fun `a start written after from reads as that day`() {
        assertEquals(LocalDate.of(2026, 10, 13), start("Road closed for works from 13 Oct 07:00 until 31 Oct 18:00."))
        assertEquals(LocalDate.of(2026, 10, 5), start("Closed from 08:00 on 5 October until 14:00 on 10 October."))
        assertEquals(LocalDate.of(2026, 9, 21), start("STREET A: From 18:30 Monday 21 September until 18:30 Wednesday 30 September, buses divert."))
        assertEquals(LocalDate.of(2026, 9, 28), start("From Monday 28 September at 07:00 until Friday 16 October at 18:00."))
        assertEquals(LocalDate.of(2026, 7, 20), start("Diverted from 08:00, Monday 20 July to 18:00, Monday 2 November 2026."))
    }

    @Test
    fun `a single day, a list and a range start on their first day`() {
        assertEquals(LocalDate.of(2026, 10, 10), start("Road will be closed on 10 October 05:30-19:00 for filming."))
        assertEquals(LocalDate.of(2026, 10, 8), start("ROUTES 1 2: Road will be closed due to resurfacing works on 8 October 08:00-16:00."))
        assertEquals(LocalDate.of(2026, 10, 10), start("Road closed to traffic on 10 October."))
        assertEquals(LocalDate.of(2026, 10, 12), start("Closed nightly 20:00-05:00 12-17 October. Buses divert."))
        assertEquals(LocalDate.of(2026, 9, 28), start("Closed 08:30-16:30 daily on 28, 29 and 30 September for resurfacing."))
        assertEquals(LocalDate.of(2026, 10, 24), start("No service to Town A on Saturday 24 October and Saturday 31 October only."))
        assertEquals(LocalDate.of(2026, 10, 3), start("No service Saturday 3 and Sunday 4 October between Station A and Station B."))
        assertEquals(LocalDate.of(2026, 10, 27), start("Closed nightly 21:00-03:00 27-29 October."))
    }

    @Test
    fun `work only bounded by an end is already under way`() {
        assertNull(start("STREET A: Until Monday 23 November at 17:00, ROUTE 1 is diverted."))
        assertNull(start("Buses diverted until 23:00 on Thursday 1 October due to works."))
        assertNull(start("Until 17:00 Tuesday 29 September, ROUTE 1 is on diversion."))
        // Its later dates don't count: the first one is an end.
        assertNull(start("Buses divert until 18:00 on Friday 29 January 2027 (except between Friday 11 December and Monday 04 January)."))
    }

    @Test
    fun `a date not plainly a start is left as under way`() {
        // End wording outside any list: read as nothing, never as a start (Codex, PR #337).
        assertNull(start("Buses are diverted and expected to finish by 13 October."))
        assertNull(start("Works ending 13 October; buses divert."))
        assertNull(start("Road closed. Reopens 13 October."))
        assertNull(start("Works ending on 13 October; buses divert."))
        assertNull(start("Buses divert. Works are expected to finish on 13 October."))
        assertNull(start("Diverted until 17:00 on Tuesday 13 October."))
        // A time's colon isn't a clause break: the "until" before it still counts.
        assertNull(start("STREET A, SE1: Until 17:00 on Wednesday 30 September, ROUTES 1 and 2 are on diversion."))
        assertNull(start("STREET A, SE1: Route 1 is on diversion until 23:00 on Thursday 1 October due to works."))
        assertNull(start("The station reopens on 13 October."))
        // A range is an end too when its clause says so.
        assertNull(start("Works are expected to finish 12-17 October."))
        assertNull(start("Buses diverted until 12-13 October."))
        // A service coming back is an end (Codex, PR #337).
        assertNull(start("Service resumes on 13 October."))
        assertNull(start("Normal service is restored on 13 October."))
        assertNull(start("Buses return to their usual route on 13 October."))
        assertNull(start("The stop is reinstated on 13 October."))
        assertNull(start("Service back to normal on 13 October."))
        assertNull(start("The station opens on 13 October."))
        assertNull(start("Normal service restarts on 13 October."))
        assertNull(start("Service recommences on 13 October."))
        // A date for the next update or review is neither end nor start: under way.
        assertNull(start("Service suspended until further notice. Next update on 13 October."))
        assertNull(start("Line closed. Situation will be reviewed on 13 October."))
        assertNull(start("Current diversion. Timetable details will be published on 13 October."))
        assertNull(start("The closure will be lifted on 13 October."))
        // A first date in figures can't be read, so a later worded one isn't trusted either.
        assertNull(start("Closed until 12/10/2026. More work starts from 13 October."))
        assertNull(start("Entrance closed; the new entrance opening on 13 October."))
        // A current disruption with a later dated change stays current (Codex, PR #337).
        assertNull(start("Service suspended until further notice. Replacement buses start from 13 October."))
        assertNull(start("Buses diverted until further notice; a new stop opens from 13 October."))
        // A leading bare date isn't affirmed as a start either.
        assertNull(start("13 October: road closed, buses divert."))
    }

    @Test
    fun `words that only sound like an end don't hide a start`() {
        // "Complete" naming the closure, and "expected" on its own, say nothing of an end (Codex, PR #337).
        assertEquals(LocalDate.of(2026, 10, 13), start("Complete closure from 13 October."))
        assertEquals(LocalDate.of(2026, 10, 13), start("Complete line closure from 13 October."))
        assertEquals(LocalDate.of(2026, 10, 13), start("Complete Victoria line closure from 13 October."))
        // The verb still ends: "works complete on" is when they finish.
        assertNull(start("Works complete on 13 October."))
        assertEquals(LocalDate.of(2026, 10, 13), start("Works expected to start on 13 October."))
        assertEquals(LocalDate.of(2026, 10, 13), start("Works begin on 13 October."))
        assertEquals(LocalDate.of(2026, 10, 13), start("Normal service will be suspended from 13 October."))
        // "End" naming a place.
        assertEquals(LocalDate.of(2026, 10, 13), start("West End Lane will be closed from 13 October."))
        // "may" the modal isn't the month.
        assertEquals(LocalDate.of(2026, 10, 13), start("Route 1 may be diverted from 13 October."))
        assertEquals(LocalDate.of(2027, 5, 3), start("Closed from 3 May 2027."))
        assertEquals(LocalDate.of(2026, 10, 13), start("Route 1 may miss stops from 13 October."))
        assertEquals(java.time.MonthDay.of(5, 3), start("Closed from 3 May until 5 May.")?.let(java.time.MonthDay::from))
        // A range written with "to" starts on its first day.
        assertEquals(LocalDate.of(2026, 10, 12), start("Closed from 12 to 17 October."))
        assertNull(start("Diverted until 12 to 17 October."))
        assertEquals(LocalDate.of(2026, 10, 13), start("Mile End Road closed on 13 October."))
        assertNull(start("Works end on 13 October."))
        assertNull(start("Diversion: Works End on 13 October."))
        assertNull(start("Diversion: Service Will End on 13 October."))
        // All caps can't tell a place from the verb: left as under way, the safe side.
        assertNull(start("WEST END LANE CLOSED ON 13 OCTOBER."))
    }

    @Test
    fun `no date is no start`() {
        assertNull(start("Buses are diverted due to a burst water main."))
        assertNull(start(""))
        assertNull(AlertStart.startDate(null, now))
    }

    @Test
    fun `a stated year is kept, and a missing one is the nearest`() {
        assertEquals(LocalDate.of(2027, 3, 1), start("The station will be closed from 1 March 2027 04:00 until 2 March 2028."))
        assertEquals(LocalDate.of(2026, 6, 12), start("From 05:00 Thursday 12 June until 23:00 Thursday 31 December."))
        val december = Instant.parse("2026-12-20T12:00:00Z")
        assertEquals(LocalDate.of(2027, 1, 4), AlertStart.startDate("Closed from 4 January.", december))
        // Six months back is work under way, not work six months off.
        assertEquals(LocalDate.of(2026, 3, 23), start("From 09:00 on Monday 23 March until 17:00 on Monday 26 October, routes divert."))
    }

    @Test
    fun `a missing year is the first on or after the day TfL posted the alert`() {
        val postedMarch = Instant.parse("2026-03-10T09:00:00Z")
        assertEquals(LocalDate.of(2026, 3, 23), AlertStart.startDate("From Monday 23 March until 26 October.", now, postedMarch))
        val postedSeptember = Instant.parse("2026-09-14T07:26:00Z")
        assertEquals(LocalDate.of(2026, 10, 13), AlertStart.startDate("Closed from 13 Oct until 31 Oct.", now, postedSeptember))
        // Posted after it started: still this year's, not next.
        assertEquals(LocalDate.of(2026, 9, 1), AlertStart.startDate("Closed from 1 September.", now, postedSeptember))
        // Posted long before: the nearer year is the past one, which reads as under way, the safe side.
        assertEquals(LocalDate.of(2025, 12, 1), AlertStart.startDate("Closed from 1 December.", now, postedMarch))
        val postedDecember = Instant.parse("2026-12-18T09:00:00Z")
        assertEquals(LocalDate.of(2027, 1, 4), AlertStart.startDate("Closed from 4 January.", Instant.parse("2026-12-20T12:00:00Z"), postedDecember))
    }

    @Test
    fun `upcoming only when it starts after today`() {
        assertTrue(AlertStart.isUpcoming("Road closed from 13 Oct 07:00 until 31 Oct 18:00.", now))
        // Starting later today already counts as under way.
        assertFalse(AlertStart.isUpcoming("Road closed from 28 September 20:00 to 29 September 08:00.", now))
        assertFalse(AlertStart.isUpcoming("Road closed from 20 July until 30 November.", now))
        assertFalse(AlertStart.isUpcoming("Buses diverted until 13 October.", now))
        assertFalse(AlertStart.isUpcoming("Minor delays due to a signal failure.", now))
        // Just after midnight in London, still the 27th in UTC: the 28th has started.
        val londonMidnight = Instant.parse("2026-09-27T23:30:00Z")
        assertFalse(AlertStart.isUpcoming("Closed on 28 September.", londonMidnight))
    }

    @Test
    fun `all-capitals text reads the same`() {
        assertEquals(LocalDate.of(2026, 10, 13), start("ROAD CLOSED FROM 13 OCTOBER UNTIL 31 OCTOBER."))
        assertNull(start("ROUTES DIVERTED UNTIL 13 OCTOBER."))
    }
}
