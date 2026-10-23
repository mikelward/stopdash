package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModeIconTest {
    @Test
    fun `a stops modes are an icon each, trains first, rail lines as one`() {
        // An interchange's modes in TfL's order (a big east London hub, say).
        val match = StationMatch("940GZZZETA", "Zeta", listOf("bus", "elizabeth-line", "dlr", "overground", "tube", "national-rail"))
        assertEquals(listOf(ModeIcon.TUBE, ModeIcon.RAIL, ModeIcon.DLR, ModeIcon.BUS), match.modeIcons)
        assertNull(match.otherModesLabel)
        assertEquals("Bus · Elizabeth · DLR · Overground · Tube · National Rail", modesLabel(match.modes))
    }

    @Test
    fun `a mode with no icon is named, and a blank one is dropped`() {
        val match = StationMatch("940GZZZETA", "Zeta", listOf("tube", "some-mode", ""))
        assertEquals(listOf(ModeIcon.TUBE), match.modeIcons)
        assertEquals("Some mode", match.otherModesLabel)
        assertEquals("Tube · Some mode", modesLabel(match.modes))
    }

    @Test
    fun `a copy carries the work done when the match was made, and new modes redo it`() {
        val match = StationMatch("490000001A", "Zeta Church", listOf("bus", "some-mode"))
        // A copy for a position (a click handler's "Add favorite") reuses the very lists: no list work.
        val placed = match.copy(latitude = 51.5, longitude = -0.12)
        assertSame(match.modeIcons, placed.modeIcons)
        assertSame(match.otherModesLabel, placed.otherModesLabel)
        // New modes are worked out afresh.
        val tram = match.withModes(listOf("tram"))
        assertEquals(listOf(ModeIcon.TRAM), tram.modeIcons)
        assertNull(tram.otherModesLabel)
        assertEquals(StationMatch("490000001A", "Zeta Church", listOf("tram")), tram)
    }

    @Test
    fun `one mode's icon, any case, and none for a mode no icon stands for`() {
        assertEquals(ModeIcon.RAIL, ModeIcon.of("Elizabeth-Line"))
        assertEquals(ModeIcon.BUS, ModeIcon.of("bus"))
        assertNull(ModeIcon.of("some-mode"))
    }
}
