package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeLinesTest {
    @Test
    fun `a notice led by its line is about that line`() {
        assertEquals(setOf("circle"), NoticeLines.scope("Circle Line: No service between Aldgate and Edgware Road."))
    }

    @Test
    fun `a notice led by a list of lines is about each`() {
        assertEquals(
            setOf("circle", "district", "hammersmith-city"),
            NoticeLines.scope("Circle, District and Hammersmith & City lines: trains will not stop here."),
        )
        assertEquals(setOf("hammersmith-city", "metropolitan"), NoticeLines.scope("H&C and Met lines: lift out of order."))
        assertEquals(setOf("waterloo-city"), NoticeLines.scope("WATERLOO & CITY LINE: CLOSED ON SUNDAYS"))
    }

    @Test
    fun `a list repeating line after each name is about each`() {
        // Codex on #1233: read only to its first "line", this was a Circle notice and left a District ride.
        assertEquals(setOf("circle", "district"), NoticeLines.scope("Circle line and District line: platforms closed."))
        assertFalse(NoticeLines.aboutOtherLines("Circle line and District line: platforms closed.", listOf("district")))
        assertEquals(setOf("circle", "district", "piccadilly"), NoticeLines.scope("Circle line, District line & Piccadilly lines: lift out of order."))
    }

    @Test
    fun `the DLR in a list is one of its lines`() {
        // Codex on #1233: read as the DLR alone, this left a Jubilee ride.
        assertEquals(setOf("dlr", "jubilee"), NoticeLines.scope("DLR and Jubilee line: step-free access unavailable."))
        assertFalse(NoticeLines.aboutOtherLines("DLR and Jubilee line: step-free access unavailable.", listOf("jubilee")))
        assertEquals(setOf("jubilee", "dlr"), NoticeLines.scope("Jubilee line and DLR: step-free access unavailable."))
    }

    @Test
    fun `a heading not read to its end is kept as about the whole station`() {
        assertEquals(emptySet<String>(), NoticeLines.scope("Jubilee line and Thameslink: step-free access unavailable."))
        assertFalse(NoticeLines.aboutOtherLines("Jubilee line and Thameslink: step-free access unavailable.", listOf("thameslink")))
        assertEquals(emptySet<String>(), NoticeLines.scope("DLR and Thameslink: step-free access unavailable."))
    }

    @Test
    fun `the DLR and the Overground lines lead notices too`() {
        assertEquals(setOf("dlr"), NoticeLines.scope("DLR: platform 2 closed."))
        assertEquals(setOf("weaver"), NoticeLines.scope("Weaver line: no step-free access."))
    }

    @Test
    fun `a notice led by the station's own name is read after it`() {
        assertEquals(
            setOf("circle"),
            NoticeLines.scope("King's Cross St. Pancras Underground Station: Circle line closed.", "King's Cross St. Pancras"),
        )
    }

    @Test
    fun `a line mentioned further on doesn't scope a station-wide notice`() {
        // Codex on #1233: an alternative named in passing isn't what the notice is about.
        assertEquals(emptySet<String>(), NoticeLines.scope("Station closed. Use the Circle line from another station."))
        assertEquals(emptySet<String>(), NoticeLines.scope("Station closed: Circle line customers use Euston Square."))
        assertFalse(NoticeLines.aboutOtherLines("Station closed. Use the Circle line from another station.", listOf("northern")))
    }

    @Test
    fun `a station sharing a line's name is not its line`() {
        assertEquals(emptySet<String>(), NoticeLines.scope("Victoria: the Wilton Road entrance is closed."))
        // Prose in lower case isn't TfL naming a line.
        assertEquals(emptySet<String>(), NoticeLines.scope("central line platforms are closed."))
    }

    @Test
    fun `a notice is about other lines only when it opens with lines and none of the ride's`() {
        val circle = "Circle Line: No service between Aldgate and Edgware Road."
        assertTrue(NoticeLines.aboutOtherLines(circle, listOf("northern")))
        assertFalse(NoticeLines.aboutOtherLines(circle, listOf("northern", "circle")))
        assertFalse(NoticeLines.aboutOtherLines("Northern line: lifts out of order.", listOf("northern")))
    }

    @Test
    fun `a notice naming no line, or a ride with no lines known, is kept`() {
        assertFalse(NoticeLines.aboutOtherLines("Station closed due to a fire alert.", listOf("northern")))
        assertFalse(NoticeLines.aboutOtherLines("Circle Line: No service.", emptyList()))
    }
}
