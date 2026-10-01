package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvoidedLinesTest {
    private val northern = AvoidedLines.key("Northern", "Northern line")

    @Test
    fun `a line is avoided by its id, whatever its case`() {
        assertEquals("line:northern=Northern line", northern)
        assertTrue(AvoidedLines.avoids("northern", setOf(northern)))
        assertTrue(AvoidedLines.avoids("NORTHERN", setOf(northern)))
        assertFalse(AvoidedLines.avoids("central", setOf(northern)))
        assertFalse(AvoidedLines.avoids("", setOf(northern)))
    }

    @Test
    fun `each avoided line is named by its label, in the order avoided`() {
        val central = AvoidedLines.key("central", "Central line")
        assertEquals(listOf(central to "Central line", northern to "Northern line"), AvoidedLines.labeled(linkedSetOf(central, northern, "bus")))
        assertEquals(emptyList<Pair<String, String>>(), AvoidedLines.labeled(emptySet()))
    }

    @Test
    fun `a stored entry that isn't a line's is dropped`() {
        assertEquals(setOf(northern), AvoidedLines.fromStored(setOf(northern, "bus")))
    }

    @Test
    fun `a trip leaves out what's hidden and what's avoided`() {
        val hidden = setOf("bus")
        assertSame(hidden, AvoidedLines.excluded(hidden, emptySet()))
        val excluded = AvoidedLines.excluded(hidden, setOf(northern))
        assertEquals(setOf("bus", northern), excluded)
        // A route on the avoided line is left out as a hidden line's is.
        assertTrue(HiddenModes.isHidden("tube", "northern", excluded))
        assertFalse(HiddenModes.isHidden("tube", "central", excluded))
    }
}
