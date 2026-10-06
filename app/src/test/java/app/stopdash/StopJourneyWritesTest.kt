package app.stopdash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stop page's Favorite/Remove results: only each journey's latest write is said. */
class StopJourneyWritesTest {
    @Test
    fun `an earlier write failing after a later one of the same journey landed says nothing`() {
        val first = StopJourneyWrites.begin("A|B")
        val second = StopJourneyWrites.begin("A|B")
        StopJourneyWrites.finish("A|B", second, failed = false)
        // Not the latest, so it says nothing: not on the row, nor app-wide.
        assertEquals(false, StopJourneyWrites.finish("A|B", first, failed = true))
        assertTrue("A|B" !in StopJourneyWrites.failed)
    }

    @Test
    fun `another journey's write doesn't swallow this one's failure`() {
        val a = StopJourneyWrites.begin("A|B")
        StopJourneyWrites.begin("C|D")
        assertTrue(StopJourneyWrites.finish("A|B", a, failed = true))
        assertTrue("A|B" in StopJourneyWrites.failed)
    }

    @Test
    fun `two journeys' failures are both kept`() {
        val g = StopJourneyWrites.begin("G|H")
        val i = StopJourneyWrites.begin("I|J")
        StopJourneyWrites.finish("I|J", i, failed = true)
        StopJourneyWrites.finish("G|H", g, failed = true)
        assertTrue("G|H" in StopJourneyWrites.failed && "I|J" in StopJourneyWrites.failed)
    }

    @Test
    fun `the latest write failing says so, until that journey's next begins`() {
        val only = StopJourneyWrites.begin("E|F")
        StopJourneyWrites.finish("E|F", only, failed = true)
        assertTrue("E|F" in StopJourneyWrites.failed)
        StopJourneyWrites.begin("E|F")
        assertTrue("E|F" !in StopJourneyWrites.failed)
    }
}
