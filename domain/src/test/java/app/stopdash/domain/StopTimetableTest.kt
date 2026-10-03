package app.stopdash.domain

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopTimetableTest {
    @Test
    fun `reads TfL's day types, ranges and brackets alike`() {
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY), scheduleDays("Monday - Thursday"))
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY), scheduleDays("Monday to Thursday"))
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), scheduleDays("Mon-Fri"))
        // What's bracketed is a holiday it also runs on, not a weekday.
        assertEquals(setOf(SATURDAY), scheduleDays("Saturday (also Good Friday)"))
        assertEquals(setOf(SATURDAY, SUNDAY), scheduleDays("Saturday and Sunday"))
        // A range past Sunday wraps.
        assertEquals(setOf(FRIDAY, SATURDAY, SUNDAY, MONDAY), scheduleDays("Friday to Monday"))
        assertEquals(emptySet<Any>(), scheduleDays("School days"))
    }

    // 2026-10-06 is a Tuesday; London is on BST (UTC+1) then.
    private fun tuesday(time: String): Instant = Instant.parse("2026-10-06T${time}Z").minus(Duration.ofHours(1))

    private val weekdays = StopTimetable.DaySchedule(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY), listOf(5 * 60 + 40, 12 * 60, 24 * 60 + 30))
    private val timetable = StopTimetable(listOf(weekdays))
    private val window = Duration.ofMinutes(30)

    @Test
    fun `a departure within the window is due, one past it isn't`() {
        assertEquals(true, timetable.departsWithin(tuesday("11:45:00"), window))
        assertEquals(true, timetable.departsWithin(tuesday("12:00:00"), window))
        assertEquals(false, timetable.departsWithin(tuesday("11:20:00"), window))
        assertEquals(false, timetable.departsWithin(tuesday("12:01:00"), window))
    }

    @Test
    fun `last night's run past midnight counts, and so does the first of the morning`() {
        // Monday's 00:30 (24:30) leaves on Tuesday morning.
        assertEquals(true, timetable.departsWithin(tuesday("00:10:00"), window))
        assertEquals(false, timetable.departsWithin(tuesday("03:00:00"), window))
        assertEquals(true, timetable.departsWithin(tuesday("05:20:00"), window))
    }

    @Test
    fun `a window crossing midnight reaches tomorrow's early departures`() {
        val early = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(WEDNESDAY), listOf(10))))
        // Tuesday 23:50: Wednesday's 00:10 is 20 minutes away.
        assertEquals(true, early.departsWithin(tuesday("23:50:00"), window))
    }

    @Test
    fun `a day no schedule covers can't be told`() {
        val weekend = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(SATURDAY), listOf(12 * 60))))
        // Tuesday noon: no schedule for Monday, Tuesday or Wednesday.
        assertNull(weekend.departsWithin(tuesday("12:00:00"), window))
        assertNull(StopTimetable(emptyList()).departsWithin(tuesday("12:00:00"), window))
    }

    @Test
    fun `a day it doesn't cover can't be told, though a day beside it is`() {
        // Monday only, checked Tuesday: Monday's times are behind it, but nothing says what runs
        // on a Tuesday, so it's no answer, not "none".
        val monday = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(MONDAY), listOf(12 * 60, 24 * 60 + 30))))
        assertNull(monday.departsWithin(tuesday("12:00:00"), window))
        // Nor while Monday's night is still running past midnight into an uncovered Tuesday.
        assertNull(monday.departsWithin(tuesday("00:40:00"), window))
        // An uncovered yesterday could still be running until its service day ends at 04:00, whatever
        // the latest time another day's schedule gives (Tuesday's ends at 00:30): a Monday night
        // service could leave at 03:15.
        val tuesdayOnly = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(TUESDAY), listOf(12 * 60, 24 * 60 + 30))))
        assertNull(tuesdayOnly.departsWithin(tuesday("00:10:00"), window))
        assertNull(tuesdayOnly.departsWithin(tuesday("03:00:00"), window))
        // From 04:00, Tuesday alone answers.
        assertEquals(false, tuesdayOnly.departsWithin(tuesday("04:30:00"), window))
        // A window reaching an uncovered tomorrow can't be told either.
        assertNull(tuesdayOnly.departsWithin(tuesday("23:50:00"), window))
    }

    @Test
    fun `the window is 30 real minutes across the spring clock change`() {
        // 2027-03-28, a Sunday: at 01:00 GMT the clocks go forward to 02:00 BST. At 00:50 GMT the
        // next 30 minutes run to 02:20 BST, so a 02:10 departure is due.
        val sunday = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(SUNDAY), listOf(2 * 60 + 10))))
        assertEquals(true, sunday.departsWithin(Instant.parse("2027-03-28T00:50:00Z"), window))
        // And one at 02:30 BST (01:30Z) isn't (Saturday covered too, so its night can be ruled out).
        val later = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(SATURDAY, SUNDAY), listOf(2 * 60 + 30))))
        assertEquals(false, later.departsWithin(Instant.parse("2027-03-28T00:50:00Z"), window))
    }

    @Test
    fun `a departure in autumn's repeated hour counts at either of its times`() {
        // 2026-10-25, a Sunday: at 02:00 BST (01:00Z) the clocks go back to 01:00 GMT, so 01:30
        // happens twice. At 01:10 GMT (01:10Z) the second 01:30 is 20 minutes away.
        val sunday = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(SUNDAY), listOf(60 + 30))))
        assertEquals(true, sunday.departsWithin(Instant.parse("2026-10-25T01:10:00Z"), window))
        // And the first, before the change: at 00:10Z (01:10 BST) it's 20 minutes away too.
        assertEquals(true, sunday.departsWithin(Instant.parse("2026-10-25T00:10:00Z"), window))
    }

    @Test
    fun `a holiday gives no answer, since TfL may run another day's timetable`() {
        // Good Friday 2027 is 26 March: TfL runs its Saturday timetable ("Saturday (also Good
        // Friday)"), so the Friday one saying nothing's due at 04:00 isn't to be trusted.
        val fridays = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(THURSDAY, FRIDAY, SATURDAY), listOf(12 * 60))))
        assertNull(fridays.departsWithin(Instant.parse("2027-03-26T04:00:00Z"), window))
        // An ordinary Friday answers.
        assertEquals(false, fridays.departsWithin(Instant.parse("2027-03-19T04:00:00Z"), window))
        // A departure still found is still due.
        assertEquals(true, fridays.departsWithin(Instant.parse("2027-03-26T11:45:00Z"), window))
    }

    @Test
    fun `the holidays TfL runs differently on`() {
        // 2027: Easter Sunday 28 March.
        listOf("2027-03-26", "2027-03-29", "2027-05-03", "2027-05-31", "2027-08-30", "2027-12-24", "2027-12-25", "2027-12-31", "2028-01-01", "2028-01-03")
            .forEach { assertEquals(it, true, publicHoliday(java.time.LocalDate.parse(it))) }
        listOf("2027-03-25", "2027-03-28", "2027-05-10", "2027-08-23", "2027-12-23", "2028-01-04")
            .forEach { assertEquals(it, false, publicHoliday(java.time.LocalDate.parse(it))) }
        // Easter across a few years, the algorithm's usual traps among them.
        assertEquals(true, publicHoliday(java.time.LocalDate.parse("2026-04-03")))
        assertEquals(true, publicHoliday(java.time.LocalDate.parse("2025-04-18")))
        assertEquals(true, publicHoliday(java.time.LocalDate.parse("2038-04-23")))
    }

    @Test
    fun `a day with a departure that couldn't be read can't be said to have none`() {
        val read = StopTimetable.DaySchedule(setOf(TUESDAY), listOf(12 * 60))
        assertEquals(false, StopTimetable(listOf(read)).departsWithin(tuesday("04:30:00"), window))
        // The same day, one of its journeys unreadable: it might be the one due.
        assertNull(StopTimetable(listOf(read, read.copy(minutes = emptyList(), complete = false))).departsWithin(tuesday("04:30:00"), window))
    }

    @Test
    fun `a covered yesterday counts only until its own last departure`() {
        // Monday's schedule ends at 00:30, so by 03:00 Tuesday Monday has nothing left to run.
        val both = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(MONDAY, TUESDAY), listOf(12 * 60, 24 * 60 + 30))))
        assertEquals(false, both.departsWithin(tuesday("03:00:00"), window))
    }

    @Test
    fun `a schedule whose days couldn't be read means nothing can be said to be none`() {
        val read = StopTimetable.DaySchedule(setOf(MONDAY, TUESDAY), listOf(12 * 60))
        assertEquals(false, StopTimetable(listOf(read)).departsWithin(tuesday("04:30:00"), window))
        // A "School days" schedule left out might run today.
        assertNull(StopTimetable(listOf(read), readable = false).departsWithin(tuesday("04:30:00"), window))
        // Something found is still due.
        assertEquals(true, StopTimetable(listOf(read), readable = false).departsWithin(tuesday("11:45:00"), window))
    }

    @Test
    fun `a yesterday with a journey that couldn't be read counts until 4am`() {
        // Monday's journeys read end at noon, but one couldn't be read: it might run past midnight.
        val monday = StopTimetable.DaySchedule(setOf(MONDAY), listOf(12 * 60), complete = false)
        val tuesdayRead = StopTimetable.DaySchedule(setOf(TUESDAY), listOf(12 * 60))
        assertNull(StopTimetable(listOf(monday, tuesdayRead)).departsWithin(tuesday("03:00:00"), window))
        // From 04:00, Tuesday's whole schedule answers.
        assertEquals(false, StopTimetable(listOf(monday, tuesdayRead)).departsWithin(tuesday("04:30:00"), window))
    }

    @Test
    fun `the night after a holiday counts until 4am, whatever the weekday's timetable says`() {
        // Easter Monday 2027 is 29 March. The Monday timetable ends at 00:30, but TfL may have run
        // another day's that night, so at 01:00 Tuesday nothing can be ruled out.
        val weekdays = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(MONDAY, TUESDAY), listOf(12 * 60, 24 * 60 + 30))))
        assertNull(weekdays.departsWithin(java.time.Instant.parse("2027-03-30T00:00:00Z"), window))
        // An ordinary Monday night, a week later: Monday's last train has gone.
        assertEquals(false, weekdays.departsWithin(java.time.Instant.parse("2027-04-06T00:00:00Z"), window))
    }
}
