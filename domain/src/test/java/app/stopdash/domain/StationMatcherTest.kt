package app.stopdash.domain

import app.stopdash.domain.StationMatchTier.Anchored
import app.stopdash.domain.StationMatchTier.Fuzzy
import app.stopdash.domain.StationMatchTier.Prefix
import app.stopdash.domain.StationMatchTier.Substring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The type-to-find tiers, on public station names and ids (no user data). */
class StationMatcherTest {
    private fun tier(query: String, name: String, id: String = "", hub: String = "") =
        StationMatcher.tier(query, name, id, hub)

    @Test
    fun `a name prefix matches, ignoring case, apostrophes and spaces`() {
        assertEquals(Prefix, tier("king", "King's Cross St. Pancras"))
        assertEquals(Prefix, tier("kings", "King's Cross St. Pancras"))
        assertEquals(Prefix, tier("kings cross", "King's Cross St. Pancras"))
    }

    @Test
    fun `each part of a name matches as a prefix`() {
        // A stop named with its cross street, a place named after its area (maintainer, 2026-09-28).
        assertEquals(Prefix, tier("ba", "Foo Street / Bar Road"))
        assertEquals(Prefix, tier("bar road", "Foo Street / Bar Road"))
        assertEquals(Prefix, tier("zeta", "Alpha District, Zeta Gallery"))
        assertEquals(Prefix, tier("ze", "Alpha District, Zeta Gallery"))
        // The whole name still matches from its start, across the separator.
        assertEquals(Prefix, tier("foo street bar", "Foo Street / Bar Road"))
    }

    @Test
    fun `each station of an interchange named for two matches as a prefix`() {
        assertEquals(Prefix, tier("st pa", "King's Cross & St Pancras International", "HUBKGX"))
        assertEquals(Prefix, tier("kings", "King's Cross & St Pancras International", "HUBKGX"))
        assertEquals(Prefix, tier("castle", "Elephant & Castle", "HUBEPH"))
    }

    @Test
    fun `a place with an ampersand is one name, so it ranks below one starting with the query`() {
        // A geocoded place or a line isn't an interchange: "Alpha & Zeta" only contains "zeta".
        assertEquals(Anchored, tier("zeta", "Alpha & Zeta"))
        assertEquals(Prefix, tier("zeta", "Zeta Gardens Hall"))
    }

    @Test
    fun `word initials anchor`() {
        assertEquals(Anchored, tier("kc", "King's Cross St. Pancras"))
        assertEquals(Anchored, tier("ksp", "King's Cross St. Pancras"))
        assertEquals(Anchored, tier("tcr", "Tottenham Court Road"))
    }

    @Test
    fun `Cross also reads X, so KX and CX find their stations`() {
        assertEquals(Anchored, tier("kx", "King's Cross St. Pancras"))
        assertEquals(Anchored, tier("cx", "Charing Cross"))
        assertEquals(Prefix, tier("charing x", "Charing Cross"))
    }

    @Test
    fun `a station or hub code matches`() {
        assertEquals(Prefix, tier("kgx", "King's Cross St. Pancras", id = "HUBKGX"))
        assertEquals(Prefix, tier("ksx", "King's Cross St. Pancras", id = "940GZZLUKSX"))
        assertEquals(Prefix, tier("kgx", "King's Cross St. Pancras", id = "940GZZLUKSX", hub = "HUBKGX"))
        assertEquals(Substring, tier("kg", "King's Cross St. Pancras", id = "HUBKGX"))
        assertEquals(Prefix, tier("hubkgx", "King's Cross St. Pancras", id = "HUBKGX"))
        assertEquals(Prefix, tier("bfr", "Blackfriars Pier", id = "930GBFR"))
        assertEquals(Prefix, tier("gwp", "Greenwich Peninsula", id = "940GZZALGWP"))
    }

    @Test
    fun `a later word's start anchors, a mid-word run is a substring, and letters in order are fuzzy`() {
        // "cross" starts a word, so it anchors there — a better tier than a bare substring.
        assertEquals(Anchored, tier("cross", "Charing Cross"))
        assertEquals(Substring, tier("aring", "Charing Cross"))
        assertEquals(Fuzzy, tier("vctra", "Victoria"))
    }

    @Test
    fun `a lower-case interior letter doesn't start a match`() {
        // "o" is inside "Victoria" and starts no word: no anchored or fuzzy match from it.
        assertNull(tier("oa", "Victoria"))
    }

    @Test
    fun `accents fold and a blank query matches nothing`() {
        assertEquals(Prefix, tier("cafe", "Café Street"))
        assertNull(tier("  ", "Victoria"))
        assertNull(tier("zzz", "Victoria"))
    }

    @Test
    fun `codes drop their fixed prefixes`() {
        assertEquals("KGX", StationMatcher.codeOf("HUBKGX"))
        assertEquals("KSX", StationMatcher.codeOf("940GZZLUKSX"))
        assertEquals("CAW", StationMatcher.codeOf("940GZZDLCAW"))
        assertEquals("BFR", StationMatcher.codeOf("930GBFR"))
        assertEquals("GWP", StationMatcher.codeOf("940GZZALGWP"))
        assertEquals("KGX", StationMatcher.codeOf("910GKGX"))
        assertEquals("490000000001A", StationMatcher.codeOf("490000000001A"))
    }
}
