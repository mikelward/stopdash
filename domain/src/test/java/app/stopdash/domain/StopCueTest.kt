package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a stop's details look up (SPEC *Finding a line*), by a line's mode or a station's own modes. */
class StopCueTest {
    @Test
    fun `a stop on a line's map is classed by the line's mode`() {
        assertEquals(StopCue.POLE, lineStopCue("bus"))
        assertEquals(StopCue.ZONE, lineStopCue("tube"))
        assertEquals(StopCue.ZONE, lineStopCue("Elizabeth-Line"))
        assertEquals(StopCue.NONE, lineStopCue("river-bus"))
        assertEquals(StopCue.NONE, lineStopCue("cable-car"))
    }

    @Test
    fun `a station is classed by its own modes, a zoned one winning`() {
        assertEquals(StopCue.ZONE, stopCueOf(listOf("tube", "national-rail")))
        assertEquals(StopCue.ZONE, stopCueOf(listOf("bus", "tram")))
        assertEquals(StopCue.POLE, stopCueOf(listOf("bus")))
        assertEquals(StopCue.NONE, stopCueOf(listOf("river-bus")))
        assertEquals(StopCue.NONE, stopCueOf(emptyList()))
    }
}
