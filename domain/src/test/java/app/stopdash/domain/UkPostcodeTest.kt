package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Postcodes here are **synthetic**: every one starts with `X`, which is never a real UK postcode first
 * letter, so none names a real place (SPEC *Privacy* — the repo pre-approves only hub/interchange
 * data). The recognizer is purely structural, so these exercise the same shapes a real code would.
 */
class UkPostcodeTest {

    @Test
    fun `complete postcodes are recognized across every outward shape`() {
        assertTrue(UkPostcode.isComplete("X1 9XX")) // A9 9AA
        assertTrue(UkPostcode.isComplete("X12 9XX")) // A99 9AA
        assertTrue(UkPostcode.isComplete("X1X 9XX")) // A9A 9AA
        assertTrue(UkPostcode.isComplete("XX1 9XX")) // AA9 9AA
        assertTrue(UkPostcode.isComplete("XX12 9XX")) // AA99 9AA
        assertTrue(UkPostcode.isComplete("XX1X 9XX")) // AA9A 9AA
    }

    @Test
    fun `any area letters are accepted — no London allow-list`() {
        // The check is by shape, not by a set of known outcodes, so codes well outside the London
        // postal areas (which TfL still plans to) must pass. Different first/second letters, same shape.
        assertTrue(UkPostcode.isComplete("XA1 9XX"))
        assertTrue(UkPostcode.isComplete("XB22 9XX"))
        assertTrue(UkPostcode.isComplete("X9 9XX"))
    }

    @Test
    fun `case and whitespace are ignored`() {
        assertTrue(UkPostcode.isComplete("x1 9xx"))
        assertTrue(UkPostcode.isComplete("x19xx")) // no space: X1 + 9XX
        assertTrue(UkPostcode.isComplete("  XX1X9XX  "))
    }

    @Test
    fun `partial matches accept every leading fragment while typing`() {
        // Typing toward "X1 9XX" — each keystroke still looks like a postcode, including the no-space
        // outcode/incode boundary ("X19" could be heading to "X19 …" or "X1 9…").
        for (fragment in listOf("X", "X1", "X19", "X19X", "X19XX")) {
            assertTrue(fragment, UkPostcode.looksLikePartial(fragment))
        }
        // And toward "XX1X 9XX", including the alphanumeric district.
        for (fragment in listOf("X", "XX", "XX1", "XX1X", "XX1X9", "XX1X9X", "XX1X9XX")) {
            assertTrue(fragment, UkPostcode.looksLikePartial(fragment))
        }
    }

    @Test
    fun `partial matches reject text that can't become a postcode`() {
        assertFalse(UkPostcode.looksLikePartial("")) // blank
        assertFalse(UkPostcode.looksLikePartial("1")) // must start with a letter
        assertFalse(UkPostcode.looksLikePartial("1X")) // ditto
        assertFalse(UkPostcode.looksLikePartial("XXX")) // no three-letter area
        assertFalse(UkPostcode.looksLikePartial("Victoria")) // a station name, not a code
        assertFalse(UkPostcode.looksLikePartial("X1 9XXYZ")) // a run of text well past a full code
    }

    @Test
    fun `an incomplete but well-shaped code is partial, not complete`() {
        assertTrue(UkPostcode.looksLikePartial("XX1X"))
        assertFalse(UkPostcode.isComplete("XX1X"))
        assertTrue(UkPostcode.looksLikePartial("XX1X 9X"))
        assertFalse(UkPostcode.isComplete("XX1X 9X"))
    }

    @Test
    fun `format canonicalizes a complete code and rejects an incomplete one`() {
        assertEquals("X1 9XX", UkPostcode.format("x19xx"))
        assertEquals("XX1X 9XX", UkPostcode.format("  xx1x 9xx "))
        assertEquals("X12 9XX", UkPostcode.format("X129XX"))
        assertNull(UkPostcode.format("XX1X")) // incomplete
        assertNull(UkPostcode.format("Victoria")) // not a postcode
    }

    @Test
    fun `non-ASCII input can't be folded into a valid postcode`() {
        // "ß".uppercase() expands to "SS" — a full-Unicode fold would forge a real-shaped code from junk.
        assertFalse(UkPostcode.isComplete("ßX 9XX"))
        assertFalse(UkPostcode.looksLikePartial("ß1"))
        assertNull(UkPostcode.format("ßX 9XX"))
        // Single-character non-ASCII letters that Unicode maps to ASCII (ſ → S, KELVIN SIGN → K) too.
        assertFalse(UkPostcode.isComplete("ſX 9XX")) // ſ (long s)
        assertFalse(UkPostcode.looksLikePartial("K1")) // U+212A KELVIN SIGN
    }

    @Test
    fun `the defunct GIR 0AA is intentionally not recognized`() {
        // Excluded on purpose: it never helps a London rider (see the class doc).
        assertFalse(UkPostcode.isComplete("GIR 0AA"))
        assertFalse(UkPostcode.looksLikePartial("GIR"))
    }
}
