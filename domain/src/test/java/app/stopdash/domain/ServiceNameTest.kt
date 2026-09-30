package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [serviceName] names what a pill code stands for, and stays out of the way when it adds nothing. */
class ServiceNameTest {
    @Test
    fun `a code that abbreviates something is spelled out`() {
        assertEquals("London Northwestern Railway", serviceName("London Northwestern Railway", "national-rail"))
        assertEquals("Avanti West Coast", serviceName("Avanti West Coast", "national-rail"))
        assertEquals("Hammersmith & City", serviceName("Hammersmith & City", "tube"))
        assertEquals("Elizabeth line", serviceName("Elizabeth line", "elizabeth-line"))
    }

    @Test
    fun `a trailing bracketed alias is dropped, so it can't contradict the pill`() {
        assertEquals(
            "London Northwestern Railway",
            serviceName("London Northwestern Railway (LNR)", "national-rail"),
        )
    }

    @Test
    fun `West Midlands Trains' line spells out both brands above its LNR pill`() {
        assertEquals("LNR & WMR", serviceName("LNR & WMR", "national-rail", "west-midlands-trains"))
    }

    @Test
    fun `a code that already is the name is not repeated`() {
        assertNull(serviceName("91", "bus"))
        assertNull(serviceName("N73", "bus"))
        assertNull(serviceName("DLR", "dlr"))
        assertNull(serviceName("RB1", "river-bus"))
        assertNull(serviceName("c2c", "national-rail"))
        // The rail feed names LNER by its brand alone, which is also its pill code.
        assertNull(serviceName("LNER", "national-rail", "london-north-eastern-railway"))
        assertNull(serviceName("  ", "tube"))
    }

    @Test
    fun `tube and named Overground lines read as a line`() {
        assertTrue(takesLineSuffix("Victoria", "tube"))
        assertTrue(takesLineSuffix("Mildmay", "overground"))
        assertFalse(takesLineSuffix("Elizabeth line", "elizabeth-line"))
        assertFalse(takesLineSuffix("London Overground", "overground"))
        assertFalse(takesLineSuffix("Avanti West Coast", "national-rail"))
        assertFalse(takesLineSuffix("Tram", "tram"))
    }

    @Test
    fun `a line is named alone as its riders say it`() {
        assertEquals("Northern line", lineLabel("Northern", "tube"))
        assertEquals("Mildmay line", lineLabel("Mildmay", "overground"))
        assertEquals("Elizabeth line", lineLabel("Elizabeth line", "elizabeth-line"))
        assertEquals("134", lineLabel("134", "bus"))
    }
}
