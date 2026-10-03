package app.stopdash.ui

import app.stopdash.domain.EmptyTimes
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
        // One from before a pause, though the row stayed in place: "?" until it's worked out again.
        assertEquals(EmptyTimes.Mark.UNKNOWN, freshMark(at("10:30:00Z"), shown, tick))
        // One from before the row came back on screen.
        assertEquals(EmptyTimes.Mark.UNKNOWN, freshMark(at("11:00:30Z"), tick, tick))
        assertEquals(EmptyTimes.Mark.UNKNOWN, freshMark(null, shown, tick))
    }

    @Test
    fun `a mark stamped after the tick, from before the clock was set back, isn't fresh`() {
        val tick = Instant.parse("2026-10-06T11:01:00Z")
        val shown = Instant.parse("2026-10-06T10:00:00Z")
        fun at(time: String) = EmptyTimes.Marked(EmptyTimes.Mark.NONE, Instant.parse("2026-10-06T$time"))
        // Worked out during this tick: fresh.
        assertEquals(EmptyTimes.Mark.NONE, freshMark(at("11:01:30Z"), shown, tick))
        // Worked out an hour "later": the clock went back an hour since.
        assertEquals(EmptyTimes.Mark.UNKNOWN, freshMark(at("12:01:00Z"), shown, tick))
        // A row shown "since" a time the clock has gone back past still takes marks worked out now.
        assertEquals(EmptyTimes.Mark.NONE, freshMark(at("11:01:10Z"), Instant.parse("2026-10-06T12:00:00Z"), tick))
    }
}
