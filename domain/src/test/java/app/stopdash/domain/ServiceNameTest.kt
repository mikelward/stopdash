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
    fun `TfL's West Midlands Trains line is named as the brand that runs it`() {
        // The parent company's name, on TfL's line lists; its line is London Northwestern's.
        assertEquals("London Northwestern Railway", riderLineName("West Midlands Trains", "national-rail"))
        assertEquals("London Northwestern Railway", serviceName("West Midlands Trains", "national-rail"))
        assertEquals("London Northwestern Railway", lineLabel("West Midlands Trains", "national-rail"))
        // Its brands, and every other name, are left as they are; and it is National Rail's alone.
        assertEquals("West Midlands Railway", riderLineName("West Midlands Railway", "national-rail"))
        assertEquals("Victoria", riderLineName("Victoria", "tube"))
        assertEquals("West Midlands Trains", riderLineName("West Midlands Trains", "bus"))
        // The rail feed's one name for both brands too: its London trains are branded LNR.
        assertEquals("London Northwestern Railway", riderLineName("LNR & WMR", "national-rail"))
        // An interchange lists its lines without a mode; the name is still the rail line's.
        assertEquals("London Northwestern Railway", riderLineName("West Midlands Trains", ""))
    }

    @Test
    fun `a trailing bracketed alias is dropped, so it can't contradict the pill`() {
        assertEquals(
            "London Northwestern Railway",
            serviceName("London Northwestern Railway (LNR)", "national-rail"),
        )
    }

    @Test
    fun `West Midlands Trains' line is titled London Northwestern Railway above its LNR pill`() {
        // The rail feed's "LNR & WMR" names both brands; its London trains are branded LNR.
        assertEquals(
            "London Northwestern Railway",
            serviceName("LNR & WMR", "national-rail", "west-midlands-trains"),
        )
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
