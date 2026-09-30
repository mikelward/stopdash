package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [lineCode]: a line's first three letters (uppercased) for a named line, but a
 * number/code-identified route (bus, river bus, c2c) kept verbatim so distinct routes don't
 * collapse to the same letters, and a National Rail operator shown by its initials or a
 * hand-pinned code.
 */
class LineCodeTest {
    @Test
    fun `a named line is its first three letters, uppercased`() {
        assertEquals("VIC", lineCode("Victoria", "tube"))
        assertEquals("BAK", lineCode("Bakerloo", "tube"))
        assertEquals("NOR", lineCode("Northern", "tube"))
        // First three letters, not a cleverer abbreviation ("ELZ").
        assertEquals("ELI", lineCode("Elizabeth line", "elizabeth-line"))
        // Non-letters (spaces, ampersands) are skipped before taking three.
        assertEquals("HAM", lineCode("Hammersmith & City", "tube"))
        assertEquals("WAT", lineCode("Waterloo & City", "tube"))
        assertEquals("DLR", lineCode("DLR", "dlr"))
        assertEquals("TRA", lineCode("Tram", "tram"))
        assertEquals("LIB", lineCode("Liberty", "overground"))
    }

    @Test
    fun `a numbered or coded route keeps its identifier verbatim`() {
        // A route number is already the short identity and has no letters to take.
        assertEquals("24", lineCode("24", "bus"))
        assertEquals("N73", lineCode("N73", "bus"))
        // River-bus routes carry a digit, so RB1/RB2/RB6 stay distinct rather than all
        // collapsing to "RB".
        assertEquals("RB1", lineCode("RB1", "river-bus"))
        assertEquals("RB6", lineCode("RB6", "river-bus"))
        // c2c carries a digit, so it stays verbatim as its own brand rather than an abbreviation.
        assertEquals("c2c", lineCode("c2c", "national-rail"))
    }

    @Test
    fun `a National Rail operator with a multi-word name uses its initials`() {
        // More than one capital letter ⇒ a multi-word brand ⇒ its capitals, the initialism a
        // rider sees on the train (beats the cryptic legacy TOC codes EM/GW/GR/LE/VT).
        assertEquals("EMR", lineCode("East Midlands Railway", "national-rail"))
        assertEquals("GWR", lineCode("Great Western Railway", "national-rail"))
        assertEquals("LNER", lineCode("London North Eastern Railway", "national-rail"))
        assertEquals("GA", lineCode("Greater Anglia", "national-rail"))
        assertEquals("AWC", lineCode("Avanti West Coast", "national-rail"))
        assertEquals("GN", lineCode("Great Northern", "national-rail"))
    }

    @Test
    fun `single-word rail operators that would collide are pinned to their TOC code`() {
        // First-three-letters makes Southern and Southeastern both "SOU"; the pinned TOC codes
        // keep them distinct.
        assertEquals("SN", lineCode("Southern", "national-rail"))
        assertEquals("SE", lineCode("Southeastern", "national-rail"))
        assertEquals("TL", lineCode("Thameslink", "national-rail"))
    }

    @Test
    fun `pinned rail codes override the initials where those read worse`() {
        // The Express services take the "…X" TOC code, nicer than plain initials (GE/HE).
        assertEquals("GX", lineCode("Gatwick Express", "national-rail"))
        assertEquals("HX", lineCode("Heathrow Express", "national-rail"))
        // CrossCountry takes its TOC code, not "CC", to stay clear of c2c.
        assertEquals("XC", lineCode("CrossCountry", "national-rail"))
    }

    @Test
    fun `an unpinned single-word rail operator falls back to first three letters`() {
        // One capital, not pinned ⇒ the ordinary rule, so a future operator still shows something.
        assertEquals("MER", lineCode("Merseyrail", "national-rail"))
    }

    @Test
    fun `London Northwestern is LNR however the feed spells it`() {
        assertEquals("LNR", lineCode("London Northwestern Railway", "national-rail"))
        assertEquals("LNR", lineCode("London North Western Railway", "national-rail"))
        assertEquals("LNR", lineCode("London NorthWestern Railway", "national-rail"))
        assertEquals("LNR", lineCode("London Northwestern Railway (LNR)", "national-rail"))
    }

    @Test
    fun `West Midlands Railway named on its own is WMR`() {
        assertEquals("WMR", lineCode("West Midlands Railway", "national-rail"))
        assertEquals("WMR", lineCode("West Midlands Railway", "national-rail", "west-midlands-trains"))
    }

    @Test
    fun `West Midlands Trains' line is LNR by the feed's name, TfL's name, or its line id`() {
        // The rail feed names both brands "LNR & WMR"; its capitals would overflow the pill.
        assertEquals("LNR", lineCode("LNR & WMR", "national-rail"))
        assertEquals("LNR", lineCode("LNR & WMR", "national-rail", "west-midlands-trains"))
        // TfL's line list calls it West Midlands Trains.
        assertEquals("LNR", lineCode("West Midlands Trains", "national-rail", "west-midlands-trains"))
        // A spelling nobody has listed, caught by the line id its operator code maps to.
        assertEquals("LNR", lineCode("London & Birmingham", "national-rail", "west-midlands-trains"))
        // The line id pins rail only.
        assertEquals("WES", lineCode("West Midlands Trains", "tube", "west-midlands-trains"))
    }

    @Test
    fun `LNER, named by its brand alone, is LNER`() {
        assertEquals("LNER", lineCode("LNER", "national-rail", "london-north-eastern-railway"))
    }
}
