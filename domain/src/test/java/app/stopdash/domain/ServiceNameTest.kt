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
        assertEquals("Elizabeth", serviceName("Elizabeth line", "elizabeth-line"))
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
    fun `a name already tidied comes back as it is`() {
        // Applied where a name comes in and again where it's shown (maintainer, 2026-10-23), so a second
        // pass must change nothing.
        for ((name, mode) in listOf(
            "West Midlands Trains" to "national-rail",
            "LNR & WMR" to "national-rail",
            "West Midlands Trains" to "",
            "Elizabeth line" to "elizabeth-line",
            "Victoria" to "tube",
            "134" to "bus",
        )) {
            val once = riderLineName(name, mode)
            assertEquals(once, riderLineName(once, mode))
        }
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
        assertEquals("Elizabeth", lineLabel("Elizabeth line", "elizabeth-line"))
        assertEquals("134", lineLabel("134", "bus"))
    }

    // A language that puts its word for "line" first, as the app's strings may.
    private val first: (String) -> String = { "ligne $it" }

    @Test
    fun `a line is named in the app's words, a name that takes none as it is`() {
        assertEquals("ligne Northern", lineLabel("Northern", "tube", first))
        assertEquals("ligne Mildmay", lineLabel("Mildmay", "overground", first))
        assertEquals("Elizabeth", lineLabel("Elizabeth line", "elizabeth-line", first))
        assertEquals("134", lineLabel("134", "bus", first))
        // Kept apart for a surface to say later: TfL's name as it is, and whether it takes the word.
        assertEquals(LineLabel("Northern", takesLine = true), LineLabel.of("Northern", "tube"))
        assertEquals(LineLabel("Elizabeth", takesLine = false), LineLabel.of("Elizabeth line", "elizabeth-line"))
        assertEquals("ligne Northern", LineLabel.of("Northern", "tube").text(first))
        assertEquals("134", LineLabel.of("134", "bus").text(first))
    }

    @Test
    fun `a saved line label reads in the app's words, and as it was saved in English`() {
        // Saved as the English label, whatever the app's language; read back in its words.
        assertEquals("ligne Northern", savedLineLabel(lineLabel("Northern", "tube"), first))
        assertEquals("134", savedLineLabel(lineLabel("134", "bus"), first))
        assertEquals("Thameslink", savedLineLabel(lineLabel("Thameslink", "national-rail"), first))
        // Every label English writes reads back the same in English, so nothing saved changes there.
        listOf("Northern" to "tube", "Mildmay" to "overground", "134" to "bus", "Elizabeth line" to "elizabeth-line", "Thameslink" to "national-rail")
            .forEach { (name, mode) -> assertEquals(lineLabel(name, mode), savedLineLabel(lineLabel(name, mode))) }
        // Renamed on the way in: the Elizabeth line saved before it was named alone, and West Midlands Trains.
        assertEquals("Elizabeth", savedLineLabel("Elizabeth line", first))
        assertEquals("London Northwestern Railway", savedLineLabel("West Midlands Trains", first))
        // Nothing to name but the word is left as saved.
        assertEquals(" line", savedLineLabel(" line", first))
    }
}
