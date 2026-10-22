package app.stopdash.domain

import app.stopdash.domain.ScheduledDay.Stretch.Frequent
import app.stopdash.domain.ScheduledDay.Stretch.Times
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledDayTest {
    private fun at(hour: Int, minute: Int) = hour * 60 + minute

    // Every [gap] minutes from [from] through [to].
    private fun every(gap: Int, from: Int, to: Int) = (from..to step gap).toList()

    @Test
    fun `a steady run of trains sums up as one frequency`() {
        val minutes = every(4, at(6, 0), at(10, 0))
        assertEquals(listOf(Frequent(at(6, 0), at(10, 0), 4, 4)), ScheduledDay.summarize(minutes))
    }

    @Test
    fun `a run whose gap wobbles a little is one frequency, said as its range`() {
        val minutes = listOf(at(7, 0), at(7, 3), at(7, 8), at(7, 12), at(7, 15), at(7, 20), at(7, 24))
        assertEquals(listOf(Frequent(at(7, 0), at(7, 24), 3, 5)), ScheduledDay.summarize(minutes))
    }

    @Test
    fun `a change of frequency starts another stretch at the train they share`() {
        val minutes = every(4, at(6, 0), at(7, 0)) + every(8, at(7, 8), at(9, 0))
        assertEquals(
            listOf(Frequent(at(6, 0), at(7, 0), 4, 4), Frequent(at(7, 0), at(9, 0), 8, 8)),
            ScheduledDay.summarize(minutes),
        )
    }

    @Test
    fun `sparse trains are listed by time, an hour to a stretch, either side of a frequency`() {
        val early = listOf(at(5, 12), at(5, 40), at(6, 5))
        val peak = every(5, at(6, 30), at(7, 30))
        val late = listOf(at(23, 10), at(23, 45), at(24, 20))
        assertEquals(
            listOf(
                Times(listOf(at(5, 12), at(5, 40))),
                Times(listOf(at(6, 5))),
                Frequent(at(6, 30), at(7, 30), 5, 5),
                Times(listOf(at(23, 10), at(23, 45))),
                // Past midnight, the night's last train is in an hour of its own.
                Times(listOf(at(24, 20))),
            ),
            ScheduledDay.summarize(early + peak + late),
        )
    }

    @Test
    fun `a frequency's last train is never listed again after it`() {
        val minutes = every(5, at(8, 0), at(8, 30)) + listOf(at(9, 0))
        assertEquals(listOf(Frequent(at(8, 0), at(8, 30), 5, 5), Times(listOf(at(9, 0)))), ScheduledDay.summarize(minutes))
    }

    @Test
    fun `too few trains, or gaps too long or too uneven, are listed rather than summed up`() {
        // Five every 4 minutes: listed.
        val few = every(4, at(8, 0), at(8, 16))
        assertEquals(listOf(Times(few)), ScheduledDay.summarize(few))
        // Every 15 minutes: TfL's high frequency is every 12 or better.
        val sparse = every(15, at(9, 0), at(10, 45))
        assertEquals(listOf(Times(sparse.filter { it < at(10, 0) }), Times(sparse.filter { it >= at(10, 0) })), ScheduledDay.summarize(sparse))
        // Every 2 then every 9, alternately: no one frequency.
        val uneven = listOf(at(11, 0), at(11, 2), at(11, 11), at(11, 13), at(11, 22), at(11, 24), at(11, 33))
        assertEquals(listOf(Times(uneven)), ScheduledDay.summarize(uneven))
    }

    @Test
    fun `nothing scheduled is no stretches`() {
        assertEquals(emptyList<ScheduledDay.Stretch>(), ScheduledDay.summarize(emptyList()))
    }

    // 2026-10-23 is a Friday; 2026-12-25 one too, a holiday.
    private val friday = LocalDate.of(2026, 10, 23)

    @Test
    fun `a day is every schedule running then, a time two share counted once`() {
        val timetable = StopTimetable(
            listOf(
                StopTimetable.DaySchedule(setOf(MONDAY, FRIDAY), listOf(at(9, 0), at(8, 0))),
                StopTimetable.DaySchedule(setOf(FRIDAY), listOf(at(9, 0), at(10, 0))),
                StopTimetable.DaySchedule(setOf(SATURDAY), listOf(at(11, 0))),
            ),
        )
        val day = ScheduledDay.of(timetable, friday)
        assertEquals(listOf(Times(listOf(at(8, 0))), Times(listOf(at(9, 0))), Times(listOf(at(10, 0)))), day.stretches)
        assertFalse(day.holiday)
        assertFalse(day.partial)
        assertFalse(day.unscheduled)
    }

    @Test
    fun `a day that runs nothing is none scheduled, not a timetable TfL hasn't got`() {
        val day = ScheduledDay.of(StopTimetable(listOf(StopTimetable.DaySchedule(setOf(SATURDAY), listOf(at(11, 0))))), friday)
        assertTrue(day.stretches.isEmpty())
        assertFalse(day.unscheduled)
        assertTrue(ScheduledDay.of(StopTimetable(emptyList()), friday).unscheduled)
    }

    @Test
    fun `a day with a train it couldn't read, or a schedule it couldn't place, may be missing some`() {
        val unread = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(FRIDAY), listOf(at(9, 0)), complete = false)))
        assertTrue(ScheduledDay.of(unread, friday).partial)
        val unplaced = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(FRIDAY), listOf(at(9, 0)))), readable = false)
        assertTrue(ScheduledDay.of(unplaced, friday).partial)
        // Not one that's only unread for another day.
        val otherDay = StopTimetable(
            listOf(StopTimetable.DaySchedule(setOf(FRIDAY), listOf(at(9, 0))), StopTimetable.DaySchedule(setOf(SATURDAY), listOf(at(9, 0)), complete = false)),
        )
        assertFalse(ScheduledDay.of(otherDay, friday).partial)
    }

    @Test
    fun `a holiday says TfL may run another day's timetable`() {
        val timetable = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(FRIDAY), listOf(at(9, 0)))))
        assertTrue(ScheduledDay.of(timetable, LocalDate.of(2026, 12, 25)).holiday)
    }

    @Test
    fun `the time is read as minutes into its service day on London's clock`() {
        // 08:30 BST.
        val morning = Instant.parse("2026-10-23T07:30:00Z")
        assertEquals(friday, ScheduledDay.serviceDay(morning))
        assertEquals(at(8, 30).toLong(), ScheduledDay.minuteOf(morning, friday))
        // 01:15 BST on Saturday is still Friday's service day, 25:15 into it.
        val night = Instant.parse("2026-10-24T00:15:00Z")
        assertEquals(friday, ScheduledDay.serviceDay(night))
        assertEquals(at(25, 15).toLong(), ScheduledDay.minuteOf(night, friday))
    }
}
