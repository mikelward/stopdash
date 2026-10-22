package app.stopdash.ui

import app.stopdash.domain.StopTimetable
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableDayTest {
    // 2026-10-23 is a Friday.
    private val friday = LocalDate.of(2026, 10, 23)
    private val north = listOf("B", "C")
    private val south = listOf("Y", "Z")

    @Test
    fun `a way's day is its own trains, a frequency summed up and the rest by the clock`() {
        val peak = (6 * 60..7 * 60 step 4).toList()
        val late = listOf(23 * 60 + 50, 24 * 60 + 5)
        val northbound = peak + late
        val southbound = listOf(6 * 60 + 2, 6 * 60 + 33)
        val departures = (northbound.map { it to north } + southbound.map { it to south }).sortedBy { it.first }
        val timetable = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(DayOfWeek.FRIDAY), departures.map { it.first }, calls = departures.map { it.second })))

        val day = TimetableDay.of(timetable, listOf("A") + north, friday)

        val every = day.rows[0] as TimetableRow.Every
        assertEquals("06:00", every.from)
        assertEquals("07:00", every.to)
        assertEquals(4, every.shortest)
        assertEquals(4, every.longest)
        assertEquals(7 * 60, every.last)
        // Past midnight the clock starts again, in an hour of its own.
        assertEquals(listOf("23:50"), (day.rows[1] as TimetableRow.Hour).times.map { it.text })
        assertEquals(listOf("00:05"), (day.rows[2] as TimetableRow.Hour).times.map { it.text })
        assertEquals(24 * 60 + 5, day.rows[2].last)
        assertEquals(3, day.rows.size)
    }

    @Test
    fun `with no way to pick out, every route's trains are its day`() {
        val departures = listOf(6 * 60 + 2 to south, 6 * 60 + 9 to north)
        val timetable = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(DayOfWeek.FRIDAY), departures.map { it.first }, calls = departures.map { it.second })))
        val day = TimetableDay.of(timetable, null, friday)
        assertEquals(listOf("06:02", "06:09"), (day.rows.single() as TimetableRow.Hour).times.map { it.text })
    }

    @Test
    fun `a way the timetable can't tell its trains apart for may be missing some`() {
        // TfL gave no calling patterns: none of its trains can be placed on a way.
        val timetable = StopTimetable(listOf(StopTimetable.DaySchedule(setOf(DayOfWeek.FRIDAY), listOf(6 * 60))))
        val day = TimetableDay.of(timetable, listOf("A") + north, friday)
        assertTrue(day.rows.isEmpty())
        assertTrue(day.partial)
    }
}
