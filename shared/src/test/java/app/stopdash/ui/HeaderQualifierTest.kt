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

    @Test
    fun `a closure is headed by the most specific name covering its poles`() {
        val kx = "King's Cross & St Pancras International"
        fun pole(name: String, letter: String, towards: String = "") = ClosurePole(name, BusPoleCues(letter, towards, ""))
        // One pole: its own stop name, then that pole; the place where it has no name.
        assertEquals("Pentonville Road – Stop K", closureTitle(kx, listOf(pole("Pentonville Road", "k"))))
        assertEquals("Pentonville Road ➔ Euston", closureTitle(kx, listOf(pole("Pentonville Road", "", "Euston"))))
        assertEquals("$kx – Stop K", closureTitle(kx, listOf(pole(" ", "K"))))
        // Several poles under one stop name: that name, not a list of poles.
        assertEquals("Pentonville Road", closureTitle(kx, listOf(pole("Pentonville Road", "K"), pole("Pentonville Road", "L"))))
        // Both directions: poles under different names, no pole (a station), or a pole that can't be named
        // keep the place.
        assertEquals(kx, closureTitle(kx, listOf(pole("Pentonville Road", "K"), pole("York Way", "S"))))
        assertEquals(kx, closureTitle(kx, emptyList()))
        assertEquals(kx, closureTitle(kx, listOf(pole("Pentonville Road", ""))))
    }

    @Test
    fun `a boarding stop is named by the platform its train leaves from`() {
        assertEquals(
            "King's Cross St. Pancras – Platform 7",
            groupHeaderTitle("King's Cross St. Pancras", boardingQualifier("Northbound - Platform 7", "", "", bus = false)),
        )
        // The number its row is split on, where the train itself names none ("Platform Unknown").
        assertEquals(StopQualifier.Platform("7", null), boardingQualifier("Platform Unknown", "7", "", bus = false))
        assertEquals(StopQualifier.Platform("7", null), boardingQualifier(null, "7", "", bus = false))
        // Never a direction word: the stop list is headed by that.
        assertNull(boardingQualifier("Northbound", "", "", bus = false))
        assertNull(boardingQualifier("Platform Unknown", "Unknown", "", bus = false))
    }

    @Test
    fun `a boarding bus stop is named by its pole's letter alone`() {
        assertEquals("Park – Stop K", groupHeaderTitle("Park", boardingQualifier(null, "", "k", bus = true)))
        assertEquals("Stop K", groupHeaderSpoken(boardingQualifier(null, "", "K", bus = true)))
        // TfL's stop-local platform on a bus prediction is never read as one (Codex, #850).
        assertEquals(StopQualifier.BusStop("K", null), boardingQualifier("Platform 1", "", "K", bus = true))
        // A letterless pole: the bare place.
        assertNull(boardingQualifier("Platform 1", "", " ", bus = true))
    }

    @Test
    fun aRoutePagesFromLineSaysTheWayBesideThePlatform() {
        assertEquals("Highgate – Southbound – Platform 2", routeFromTitle("Highgate", "Southbound", StopQualifier.Platform("2", null)))
        assertEquals("Highgate, Southbound, Platform 2", routeFromSpoken("Highgate", "Southbound", StopQualifier.Platform("2", null)))
        assertEquals("Park – Southbound – Stop K", routeFromTitle("Park", "Southbound", StopQualifier.BusStop("k", null)))
        // Either one alone, or neither: the bare place, heard as written.
        assertEquals("Highgate – Southbound", routeFromTitle("Highgate", "Southbound", null))
        assertEquals("Highgate, Southbound", routeFromSpoken("Highgate", "Southbound", null))
        assertEquals("Highgate – Platform 2", routeFromTitle("Highgate", null, StopQualifier.Platform("2", null)))
        assertEquals("Highgate", routeFromTitle("Highgate", null, null))
        assertNull(routeFromSpoken("Highgate", null, null))
    }
}
