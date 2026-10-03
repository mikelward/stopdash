package app.stopdash.domain

import java.time.DayOfWeek
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyTimesTest {
    // Tuesday 2026-10-06, 03:00 London (BST).
    private val night = Instant.parse("2026-10-06T02:00:00Z")

    // Tuesday 2026-10-06, 12:00 London.
    private val noon = Instant.parse("2026-10-06T11:00:00Z")

    private val dayOnly = StopTimetable(
        listOf(StopTimetable.DaySchedule(DayOfWeek.values().toSet(), (6 * 60..23 * 60 step 10).toList())),
    )
    private val allNight = StopTimetable(
        listOf(StopTimetable.DaySchedule(DayOfWeek.values().toSet(), (0..24 * 60 step 15).toList())),
    )

    private val day = EmptyTimes.Key("490000129E", "73")
    private val nightBus = EmptyTimes.Key("490000129E", "n73")

    @Test
    fun `a dash only when every line has nothing due`() {
        val lookups = mapOf(day to EmptyTimes.Lookup.Found(dayOnly), nightBus to EmptyTimes.Lookup.Found(allNight))
        assertEquals(EmptyTimes.Mark.NONE, EmptyTimes.mark(listOf(day), lookups, night))
        // A night bus still runs: something might be coming.
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(listOf(day, nightBus), lookups, night))
        // By day the day bus is due though TfL predicts none: times unknown.
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(listOf(day), lookups, noon))
    }

    @Test
    fun `what can't be settled is unknown, never a dash`() {
        // Not looked up yet: still loading (a spinner), not an answer either way.
        assertEquals(EmptyTimes.Mark.LOADING, EmptyTimes.mark(listOf(day), emptyMap(), night))
        // Failed, or no keys at all.
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(listOf(day), mapOf(day to EmptyTimes.Lookup.Failed), night))
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(emptyList(), emptyMap(), night))
        // A timetable with no schedule for the day.
        val none = mapOf(day to EmptyTimes.Lookup.Found(StopTimetable(emptyList())))
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(listOf(day), none, night))
    }

    @Test
    fun `a line that isn't running is a dash whatever its timetable`() {
        assertEquals(EmptyTimes.Mark.NONE, EmptyTimes.mark(listOf(day), emptyMap(), noon) { true })
    }

    @Test
    fun `a closed or suspended line isn't running, a delayed one may be`() {
        val stop = "940GZZLUKSX"
        assertTrue(EmptyTimes.notRunningAt(LineStatus("victoria", 2, "Suspended"), stop))
        assertTrue(EmptyTimes.notRunningAt(LineStatus("victoria", 20, "Service Closed"), stop))
        assertFalse(EmptyTimes.notRunningAt(LineStatus("victoria", 9, "Minor Delays"), stop))
        assertFalse(EmptyTimes.notRunningAt(null, stop))
    }

    @Test
    fun `a part closure stops a station inside it, not one at its edge`() {
        val closure = PartClosure(5, "Part Closure", null, listOf(listOf("940GZZLUEUS", "940GZZLUKSX", "940GZZLUHAI")))
        val status = LineStatus("victoria", 5, "Part Closure", closures = listOf(closure))
        assertTrue(EmptyTimes.notRunningAt(status, "940GZZLUKSX"))
        assertFalse(EmptyTimes.notRunningAt(status, "940GZZLUEUS"))
        assertFalse(EmptyTimes.notRunningAt(status, "940GZZLUHAI"))
        assertFalse(EmptyTimes.notRunningAt(status, "940GZZLUVIC"))
    }

    @Test
    fun `a line suspended one way still runs the other`() {
        val stop = "940GZZLUKSX"
        val southbound = LineStatus("victoria", 2, "Suspended")
        val northbound = LineStatus("victoria", 10, "Good Service")
        // TfL ranks the line by its worst alert; the split says the other way still runs.
        val oneWay = LineStatus("victoria", 2, "Suspended", byDirection = mapOf("outbound" to southbound, "inbound" to northbound))
        assertFalse(EmptyTimes.notRunningAt(oneWay, stop))
        // Shut both ways, it isn't running.
        val bothWays = oneWay.copy(byDirection = mapOf("outbound" to southbound, "inbound" to southbound.copy()))
        assertTrue(EmptyTimes.notRunningAt(bothWays, stop))
    }

    @Test
    fun `a suspension whose directions are still being looked up isn't taken as not running`() {
        val stop = "940GZZLUKSX"
        val suspended = LineStatus("victoria", 2, "Suspended")
        assertTrue(EmptyTimes.notRunningAt(suspended, stop))
        // It may yet prove to be one way only: until then the timetable decides.
        assertFalse(EmptyTimes.notRunningAt(suspended.copy(awaitingDirections = true), stop))
    }

    @Test
    fun `a part closure's interior is worked out once, edges and repeats as before`() {
        val closure = PartClosure(3, "Part Suspended", null, listOf(listOf("A", "B", "C", "D"), listOf("E", "F", "E")))
        assertEquals(setOf("B", "C", "F"), closure.interior)
        // A stop named again later in its section is placed by where it first appears.
        val loop = PartClosure(3, "Part Suspended", null, listOf(listOf("A", "B", "A")))
        assertEquals(setOf("B"), loop.interior)
    }

    @Test
    fun `a quiet line is shown only when its timetable has a train due, or a frequent one can't say`() {
        val lookups = mapOf(day to EmptyTimes.Lookup.Found(dayOnly))
        // A bus with a train due by day: shown as "?".
        val bus = EmptyTimes.quietBoard(day.stopId, day.lineId, "bus")
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(bus.keys, lookups, noon, unsure = bus.unsure))
        // Its timetable says nothing's due at night: hidden.
        assertEquals(EmptyTimes.Mark.NONE, EmptyTimes.mark(bus.keys, lookups, night, unsure = bus.unsure))
        // Its timetable failed: a bus is hidden, as before quiet rows; a tube line is "?".
        val failed = mapOf(day to EmptyTimes.Lookup.Failed)
        assertEquals(EmptyTimes.Mark.NONE, EmptyTimes.mark(bus.keys, failed, noon, unsure = bus.unsure, pending = bus.pending))
        val tube = EmptyTimes.quietBoard("940GZZLUKSX", "victoria", "tube")
        assertEquals(EmptyTimes.Mark.UNKNOWN, tube.unsure)
        val tubeFailed = mapOf(tube.keys.single() to EmptyTimes.Lookup.Failed)
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(tube.keys, tubeFailed, noon, unsure = tube.unsure, pending = tube.pending))
        listOf("elizabeth-line", "overground", "dlr", "tram", "Tube").forEach {
            assertEquals(it, EmptyTimes.Mark.UNKNOWN, EmptyTimes.quietBoard("s", "l", it).unsure)
        }
        listOf("bus", "river-bus", "cable-car", "national-rail").forEach {
            assertEquals(it, EmptyTimes.Mark.NONE, EmptyTimes.quietBoard("s", "l", it).unsure)
        }
    }

    @Test
    fun `a line due outweighs one that can't say, and one that can't say outweighs none`() {
        val lookups = mapOf(day to EmptyTimes.Lookup.Found(dayOnly), nightBus to EmptyTimes.Lookup.Failed)
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(listOf(day, nightBus), lookups, noon, unsure = EmptyTimes.Mark.NONE))
        assertEquals(EmptyTimes.Mark.NONE, EmptyTimes.mark(listOf(day, nightBus), lookups, night, unsure = EmptyTimes.Mark.NONE))
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(listOf(day, nightBus), lookups, night))
    }

    @Test
    fun `a quiet line waits for its timetable, but one that failed still reads unknown if frequent`() {
        val tube = EmptyTimes.quietBoard("940GZZLUKSX", "victoria", "tube")
        val key = tube.keys.single()
        // Not in yet: hidden, so a "?" never flashes up before the timetable rules it out.
        assertEquals(EmptyTimes.Mark.NONE, EmptyTimes.mark(tube.keys, emptyMap(), noon, unsure = tube.unsure, pending = tube.pending))
        // Failed: unsure, and a frequent line's unsure is "?".
        assertEquals(
            EmptyTimes.Mark.UNKNOWN,
            EmptyTimes.mark(tube.keys, mapOf(key to EmptyTimes.Lookup.Failed), noon, unsure = tube.unsure, pending = tube.pending),
        )
        // A status row's board reads as loading while its timetable is fetched, not as "?".
        assertEquals(EmptyTimes.Mark.LOADING, EmptyTimes.mark(tube.keys, emptyMap(), noon))
    }

    @Test
    fun `a failed timetable asked for again is loading, unless the board keeps its failure's mark`() {
        val status = EmptyTimes.Board(listOf(EmptyTimes.Key("940GZZLUKSX", "victoria")))
        val key = status.keys.single()
        val failed = mapOf(key to EmptyTimes.Lookup.Failed)
        assertEquals(EmptyTimes.Mark.UNKNOWN, EmptyTimes.mark(status.keys, failed, noon))
        assertEquals(EmptyTimes.Mark.LOADING, EmptyTimes.mark(status.keys, failed, noon, fetching = { true }))
        val quiet = EmptyTimes.quietBoard("940GZZLUKSX", "victoria", "tube")
        assertEquals(
            EmptyTimes.Mark.UNKNOWN,
            EmptyTimes.mark(quiet.keys, failed, noon, unsure = quiet.unsure, pending = quiet.pending, retrying = quiet.retrying, fetching = { true }),
        )
    }
}
