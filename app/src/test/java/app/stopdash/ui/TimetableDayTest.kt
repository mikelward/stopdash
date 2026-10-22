package app.stopdash.ui

import app.stopdash.domain.RailTimetable
import app.stopdash.domain.StopTimetable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a National Rail day lists each train by its schedule, an hour to a line, with what's said of it`() {
        // Friday morning in London (BST): a frequent line, never summed up, as each train says how it's running.
        fun at(clock: String) = Instant.parse("2026-10-23T${clock}:00+01:00")
        val trains = listOf("09:00", "09:04", "09:08", "09:12", "09:16", "09:20", "09:24").map { RailTimetable.Train(at(it), "thameslink", setOf("BDM")) } +
            listOf(
                RailTimetable.Train(at("10:15"), "thameslink", setOf("BDM"), expected = at("10:22")),
                RailTimetable.Train(at("10:30"), "thameslink", setOf("BDM"), canceled = true),
                RailTimetable.Train(at("10:45"), "thameslink", setOf("BDM"), delayed = true),
                // Past midnight, on the same service day.
                RailTimetable.Train(Instant.parse("2026-10-24T00:05:00+01:00"), "thameslink", setOf("BDM")),
            )
        val day = TimetableDay.ofRail(RailTimetable(trains.shuffled(java.util.Random(1)), complete = false), friday)
        assertEquals(3, day.rows.size)
        val nine = day.rows[0] as TimetableRow.Hour
        assertEquals(listOf("09:00", "09:04", "09:08", "09:12", "09:16", "09:20", "09:24"), nine.times.map { it.text })
        val ten = (day.rows[1] as TimetableRow.Hour).times
        assertEquals(listOf("10:15", "10:30", "10:45"), ten.map { it.text })
        // A late train is placed by its estimate, so it isn't grayed while it's still to come.
        assertEquals("10:22", ten[0].expected)
        assertEquals(10 * 60 + 22, ten[0].minute)
        assertTrue(ten[1].canceled && ten[1].expected == null)
        assertTrue(ten[2].delayed)
        assertEquals(listOf("00:05"), (day.rows[2] as TimetableRow.Hour).times.map { it.text })
        assertEquals(24 * 60 + 5, day.rows[2].last)
        // Cut short where National Rail listed all it could.
        assertTrue(day.partial)
        assertFalse(day.holiday || day.unscheduled)
    }

    @Test
    fun `a National Rail train still on the board from the day before reads its own clock time, first`() {
        // Thursday's 23:55, held so late it's still on Friday's board after 04:00: before the service day, never "00:-5"
        // (Codex on #1115).
        val held = RailTimetable.Train(Instant.parse("2026-10-22T23:55:00+01:00"), "thameslink", setOf("BDM"), delayed = true)
        val next = RailTimetable.Train(Instant.parse("2026-10-23T05:10:00+01:00"), "thameslink", setOf("BDM"))
        val day = TimetableDay.ofRail(RailTimetable(listOf(next, held)), friday)
        assertEquals(listOf(listOf("23:55"), listOf("05:10")), day.rows.map { row -> (row as TimetableRow.Hour).times.map { it.text } })
        assertEquals(-5, (day.rows[0] as TimetableRow.Hour).times.single().scheduled)
    }

    @Test
    fun `a National Rail train is due by its estimate only while that's live`() {
        // Due 10:15, estimated 10:45: by 10:45 while the estimate stands, by 10:15 once it's too old to show (Codex on #1115).
        val late = RailTimetable.Train(Instant.parse("2026-10-23T10:15:00+01:00"), "thameslink", setOf("BDM"), expected = Instant.parse("2026-10-23T10:45:00+01:00"))
        val time = (TimetableDay.ofRail(RailTimetable(listOf(late)), friday).rows.single() as TimetableRow.Hour).times.single()
        assertEquals(10 * 60 + 45, time.due(live = true))
        assertEquals(10 * 60 + 15, time.due(live = false))
        // A TfL timetable's times have only the schedule.
        val tfl = TimetableRow.Time("10:15", 10 * 60 + 15)
        assertEquals(tfl.due(live = true), tfl.due(live = false))
    }
}
