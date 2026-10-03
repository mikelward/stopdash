package app.stopdash.ui

import app.stopdash.domain.StopQualifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [groupHeaderLabel]/[groupHeaderSpoken]'s mapping from a group's [StopQualifier] to the one line
 * header's title-case qualifier segment and its spoken form, tested apart from the composable. The
 * visible segment is title case with no small-caps and drops the direction/towards parenthetical; the
 * spoken form keeps the direction/towards a screen reader needs.
 */
class HeaderQualifierTest {

    @Test
    fun `a null qualifier has no label and no spoken form`() {
        assertNull(groupHeaderLabel(null))
        assertNull(groupHeaderSpoken(null))
    }

    @Test
    fun `a rail platform is title case and speaks its compass`() {
        assertEquals("Platform 2", groupHeaderLabel(StopQualifier.Platform("2", "Eastbound")))
        assertEquals("Platform 2, Eastbound", groupHeaderSpoken(StopQualifier.Platform("2", "Eastbound")))
    }

    @Test
    fun `a rail platform with no direction has no spoken compass`() {
        assertEquals("Platform 4", groupHeaderLabel(StopQualifier.Platform("4", null)))
        assertEquals("Platform 4", groupHeaderSpoken(StopQualifier.Platform("4", null)))
    }

    @Test
    fun `a bare rail compass is its label both seen and spoken`() {
        assertEquals("Eastbound", groupHeaderLabel(StopQualifier.Compass("Eastbound")))
        assertEquals("Eastbound", groupHeaderSpoken(StopQualifier.Compass("Eastbound")))
    }

    @Test
    fun `a bus stop is title case and speaks its towards`() {
        assertEquals("Stop D", groupHeaderLabel(StopQualifier.BusStop("d", "Archway")))
        assertEquals("Stop D, towards Archway", groupHeaderSpoken(StopQualifier.BusStop("d", "Archway")))
    }

    @Test
    fun `a bus stop with a two-way towards trims at Or to the first destination`() {
        // TfL's Towards is often "Farringdon Or Holborn Circus"; the spoken cue shows just the first
        // so it stays short, not a paragraph.
        assertEquals("Stop G", groupHeaderLabel(StopQualifier.BusStop("g", "Farringdon Or Holborn Circus")))
        assertEquals(
            "Stop G, towards Farringdon",
            groupHeaderSpoken(StopQualifier.BusStop("g", "Farringdon Or Holborn Circus")),
        )
    }

    @Test
    fun `a bus stop with no towards has no spoken direction`() {
        assertEquals("Stop A", groupHeaderLabel(StopQualifier.BusStop("a", null)))
        assertEquals("Stop A", groupHeaderSpoken(StopQualifier.BusStop("a", null)))
    }

    @Test
    fun `a bus bearing reads as a bare direction word, no Stop and no arrow`() {
        // "Stop" is reserved for a literal pole letter; a compass bearing is just the direction word,
        // like the rail compass ("Eastbound") — and the visible form matches the spoken.
        assertEquals("Southwest-bound", groupHeaderLabel(StopQualifier.BusBearing("sw")))
        assertEquals("Southwest-bound", groupHeaderSpoken(StopQualifier.BusBearing("sw")))
        assertEquals("Southbound", groupHeaderLabel(StopQualifier.BusBearing("s")))
    }

    @Test
    fun `a pole's towards renders an arrow and the first place its sign names, no Stop`() {
        assertEquals("➔ Archway", groupHeaderLabel(StopQualifier.Towards("Archway")))
        assertEquals("➔ King's Cross", groupHeaderLabel(StopQualifier.Towards("King's Cross Or Euston")))
        // A screen reader hears the whole of it.
        assertEquals("towards King's Cross or Euston", groupHeaderSpoken(StopQualifier.Towards("King's Cross Or Euston")))
    }

    @Test
    fun `a bus bearing names its direction, and anything else names none`() {
        assertEquals("Southbound", bearingDirection("s"))
        assertEquals("Northeast-bound", bearingDirection("NE"))
        assertNull(bearingDirection(""))
        assertNull(bearingDirection("X"))
    }

    @Test
    fun `a title joins a towards by its arrow alone, anything else by a dash`() {
        assertEquals("Turnpike Lane ➔ King's Cross", groupHeaderTitle("Turnpike Lane", StopQualifier.Towards("King's Cross")))
        assertEquals("Oxford Circus – Platform 2", groupHeaderTitle("Oxford Circus", StopQualifier.Platform("2", null)))
        assertEquals("Oxford Circus", groupHeaderTitle("Oxford Circus", null))
    }

    @Test
    fun `a lone bus pole is labeled by its letter, else its towards, else its bearing`() {
        // The watch's trip board labels each pole of a boarding pair on its own, in the grouping's
        // order (Codex P2, #492): a letterless pair still reads apart by towards or bearing.
        assertEquals("Stop D", busPoleLabel("d", "Farringdon", "W"))
        assertEquals("➔ Farringdon", busPoleLabel("", "Farringdon Or Holborn Circus", "W"))
        assertEquals("Westbound", busPoleLabel(" ", "", "W"))
        assertEquals(null, busPoleLabel("", "", ""))
    }

    @Test
    fun `poles boarding together whose labels clash fall back to their bearings`() {
        // Two letterless poles whose signs read the same, facing apart: each by its bearing (Codex P2, #492).
        assertEquals(
            listOf("Westbound", "Eastbound"),
            busPoleLabels(listOf(BusPoleCues("", "Euston", "W"), BusPoleCues("", "Euston", "E"))),
        )
        // Both directions: labels that already differ, or a lettered pole, stay as they are.
        assertEquals(
            listOf("➔ Euston", "Stop D"),
            busPoleLabels(listOf(BusPoleCues("", "Euston", "W"), BusPoleCues("D", "Euston", "W"))),
        )
        // A clash the bearings can't settle (the same, or one missing) keeps the shared label.
        assertEquals(
            listOf("➔ Euston", "➔ Euston"),
            busPoleLabels(listOf(BusPoleCues("", "Euston", "W"), BusPoleCues("", "Euston", ""))),
        )
    }
}
