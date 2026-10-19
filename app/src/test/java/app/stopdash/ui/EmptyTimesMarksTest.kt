package app.stopdash.ui

import app.stopdash.domain.DepartureRow
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.STATUS_DIRECTION_KEY
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyTimesMarksTest {
    @Test
    fun `the clock ticks on whole minutes, whatever second it started`() {
        assertEquals(1_000L, untilNextMinute(Instant.parse("2026-10-06T11:00:59Z")))
        assertEquals(59_500L, untilNextMinute(Instant.parse("2026-10-06T11:00:00.500Z")))
        // On a minute, the next one.
        assertEquals(60_000L, untilNextMinute(Instant.parse("2026-10-06T11:01:00Z")))
    }

    @Test
    fun `a mark counts from this tick or the one before, and only since the row came on screen`() {
        val tick = Instant.parse("2026-10-06T11:01:00Z")
        fun at(time: String) = EmptyTimes.Marked(EmptyTimes.Mark.NONE, Instant.parse("2026-10-06T$time"))
        val shown = Instant.parse("2026-10-06T10:00:00Z")
        // Last tick's mark holds until this tick's lands, so the dash doesn't blink to "?".
        assertEquals(EmptyTimes.Mark.NONE, freshMark(at("11:00:00.010Z"), shown, tick))
        assertEquals(EmptyTimes.Mark.NONE, freshMark(at("11:01:00.010Z"), shown, tick))
        // One from before a pause, though the row stayed in place: loading until it's worked out again.
        assertEquals(EmptyTimes.Mark.LOADING, freshMark(at("10:30:00Z"), shown, tick))
        // One from before the row came back on screen.
        assertEquals(EmptyTimes.Mark.LOADING, freshMark(at("11:00:30Z"), tick, tick))
        assertEquals(EmptyTimes.Mark.LOADING, freshMark(null, shown, tick))
    }

    @Test
    fun `a mark stamped after the tick, from before the clock was set back, isn't fresh`() {
        val tick = Instant.parse("2026-10-06T11:01:00Z")
        val shown = Instant.parse("2026-10-06T10:00:00Z")
        fun at(time: String) = EmptyTimes.Marked(EmptyTimes.Mark.NONE, Instant.parse("2026-10-06T$time"))
        // Worked out during this tick: fresh.
        assertEquals(EmptyTimes.Mark.NONE, freshMark(at("11:01:30Z"), shown, tick))
        // Worked out an hour "later": the clock went back an hour since.
        assertEquals(EmptyTimes.Mark.LOADING, freshMark(at("12:01:00Z"), shown, tick))
        // A row shown "since" a time the clock has gone back past still takes marks worked out now.
        assertEquals(EmptyTimes.Mark.NONE, freshMark(at("11:01:10Z"), Instant.parse("2026-10-06T12:00:00Z"), tick))
    }

    @Test
    fun `a list keeps only the quiet rows freshly marked unknown`() {
        val now = Instant.parse("2026-10-06T11:01:00Z")
        fun row(lineId: String, quiet: Boolean) = DepartureRow(
            stopId = "940GZZLUKSX", stopName = "King's Cross St. Pancras", lineId = lineId, lineName = lineId,
            direction = "", directionKey = STATUS_DIRECTION_KEY, destination = "", mode = "tube",
            upcoming = emptyList(), fetchedAt = now, quiet = quiet,
        )
        val shown = row("victoria", true)
        val ruledOut = row("northern", true)
        val stale = row("piccadilly", true)
        val pending = row("circle", true)
        val suspended = row("hammersmith-city", false)
        val marks = mapOf(
            quietId(shown) to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, now),
            quietId(ruledOut) to EmptyTimes.Marked(EmptyTimes.Mark.NONE, now),
            quietId(stale) to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, now.minusSeconds(3_600)),
            // From before the clock was set back an hour.
            quietId(pending) to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, now.plusSeconds(3_600)),
        )
        val timed = row("jubilee", false)
        val distances = mapOf("940GZZLUKSX" to 100.0)
        val rows = resolveQuietRows(listOf(timed, suspended), listOf(shown, ruledOut, stale, pending), marks, now, distances)
        // Only the freshly confirmed "?" is added, after its stop's other rows.
        assertEquals(listOf(timed, suspended, shown).toSet(), rows.toSet())
        assertEquals(3, rows.size)
        assertEquals(shown, rows.last())
    }

    @Test
    fun `a line's "?" comes from the nearest stop whose timetable has a train due`() {
        val now = Instant.parse("2026-10-06T11:01:00Z")
        fun row(stopId: String) = DepartureRow(
            stopId = stopId, stopName = stopId, lineId = "northern", lineName = "Northern",
            direction = "", directionKey = STATUS_DIRECTION_KEY, destination = "", mode = "tube",
            upcoming = emptyList(), fetchedAt = now, quiet = true,
        )
        val nearest = row("940GZZLUKSX")
        val middle = row("940GZZLUEUS")
        val farthest = row("940GZZLUWRR")
        val distances = mapOf("940GZZLUKSX" to 100.0, "940GZZLUEUS" to 600.0, "940GZZLUWRR" to 900.0)
        // Nothing due from the nearest by its timetable; due from both others.
        val marks = mapOf(
            quietId(nearest) to EmptyTimes.Marked(EmptyTimes.Mark.NONE, now),
            quietId(middle) to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, now),
            quietId(farthest) to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, now),
        )
        assertEquals(listOf(middle), resolveQuietRows(emptyList(), listOf(nearest, middle, farthest), marks, now, distances))
    }

    @Test
    fun `a quiet row goes as soon as its stop's arrivals go stale, between minute ticks`() {
        val tick = Instant.parse("2026-10-06T11:01:00Z")
        val row = DepartureRow(
            stopId = "940GZZLUKSX", stopName = "King's Cross St. Pancras", lineId = "victoria", lineName = "Victoria",
            direction = "", directionKey = STATUS_DIRECTION_KEY, destination = "", mode = "tube",
            upcoming = emptyList(), fetchedAt = tick.minusSeconds(4 * 60 + 30), quiet = true,
        )
        val marks = mapOf(quietId(row) to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, tick))
        val distances = mapOf("940GZZLUKSX" to 100.0)
        // At the tick, the arrivals are four and a half minutes old: shown.
        assertEquals(listOf(row), resolveQuietRows(emptyList(), listOf(row), marks, tick, distances, tick))
        // Forty seconds on, still within the minute, they're past five: gone.
        assertEquals(emptyList<DepartureRow>(), resolveQuietRows(emptyList(), listOf(row), marks, tick, distances, tick.plusSeconds(40)))
    }

}
