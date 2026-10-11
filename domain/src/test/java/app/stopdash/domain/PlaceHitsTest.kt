package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceHitsTest {
    // Synthetic place names and coordinates only — no real landmark or postcode (AGENTS *Privacy*).
    private fun candidate(name: String, lat: Double = 51.5, lon: Double = -0.1) =
        PlaceCandidate(name, Coordinates(lat, lon))

    @Test
    fun `a place named after its area ranks as a prefix match on its own part`() {
        // "Area, Place": typing the place's own name is a prefix match, so it outranks a word-initials match ("Zoo Entrance")
        // rather than sinking to the fuzzy tier (maintainer, 2026-09-28).
        val candidates = listOf(candidate("Zoo Entrance"), candidate("Alpha District, Zeta Gallery"))
        val hits = PlaceHits.rank("ze", candidates, PlaceKind.PLACE)
        assertEquals(listOf("Alpha District, Zeta Gallery", "Zoo Entrance"), hits.map { it.name })
    }

    @Test
    fun `re-ranks a place-name query by our matcher, floating the prefix match above a weak partial`() {
        // TfL's geocoder returns these in a noisy order (a weak partial can lead); our matcher puts the
        // Prefix match ("Zeta …") ahead of the Anchored one ("Alpha Zeta").
        val candidates = listOf(candidate("Alpha Zeta"), candidate("Zeta Gardens"), candidate("Zeta Hall"))
        val hits = PlaceHits.rank("zeta", candidates, PlaceKind.PLACE)
        assertEquals(listOf("Zeta Hall", "Zeta Gardens", "Alpha Zeta"), hits.map { it.name })
        hits.forEach { assertEquals(PlaceKind.PLACE, it.kind) }
    }

    @Test
    fun `a place with an ampersand isn't split, so the longer prefix match still leads it`() {
        // Only an interchange's name parts at an "&"; a place's would beat the prefix match on length.
        val candidates = listOf(candidate("Alpha & Zeta"), candidate("Zeta Gardens Hall"))
        assertEquals(listOf("Zeta Gardens Hall", "Alpha & Zeta"), PlaceHits.rank("zeta", candidates, PlaceKind.PLACE).map { it.name })
    }

    @Test
    fun `drops a place-name candidate whose name doesn't match the query at all`() {
        val candidates = listOf(candidate("Zeta Hall"), candidate("Gamma House"))
        assertEquals(listOf("Zeta Hall"), PlaceHits.rank("zeta", candidates, PlaceKind.PLACE).map { it.name })
    }

    @Test
    fun `keeps a postcode query's candidates in TfL's order, since their names needn't match the digits`() {
        // A postcode resolves to a location whose name doesn't contain the postcode, so name-matching
        // would wrongly drop it: keep TfL's own order and tag them Postcode.
        val candidates = listOf(candidate("First Result"), candidate("Second Result"))
        val hits = PlaceHits.rank("X1 9XX", candidates, PlaceKind.POSTCODE)
        assertEquals(listOf("First Result", "Second Result"), hits.map { it.name })
        hits.forEach { assertEquals(PlaceKind.POSTCODE, it.kind) }
    }

    @Test
    fun `caps the number of hits so places don't crowd out the stops`() {
        val many = (1..20).map { candidate("Zeta $it", lat = 51.5 + it / 1000.0) }
        assertEquals(PlaceHits.DEFAULT_LIMIT, PlaceHits.rank("zeta", many, PlaceKind.PLACE).size)
    }

    @Test
    fun `de-duplicates candidates with the same name and coordinate`() {
        val candidates = listOf(candidate("Zeta Hall"), candidate("Zeta Hall"))
        assertEquals(1, PlaceHits.rank("zeta", candidates, PlaceKind.PLACE).size)
    }

    @Test
    fun `the bundle answers a query only with a prefix or substring match`() {
        val at = Coordinates(51.5, -0.12)
        fun hit(name: String) = PlaceHit(name, at, PlaceKind.PLACE)
        assertTrue(PlaceHits.answers("zeta gal", listOf(hit("Zeta Gallery"))))
        assertTrue(PlaceHits.answers("gallery", listOf(hit("Zeta Gallery"))))
        assertFalse(PlaceHits.answers("zgly", listOf(hit("Zeta Gallery"))))
        assertFalse(PlaceHits.answers("50 zeta road", listOf(hit("350 Zeta Road"))))
        assertFalse(PlaceHits.answers("zeta", emptyList()))
    }

    @Test
    fun `a house number then a word reads as an address`() {
        assertTrue(PlaceHits.looksLikeAddress("50 Euston Road"))
        assertTrue(PlaceHits.looksLikeAddress("12a Zeta Street"))
        assertTrue(PlaceHits.looksLikeAddress("10-12 Zeta Street"))
        assertFalse(PlaceHits.looksLikeAddress("Euston Road"))
        assertFalse(PlaceHits.looksLikeAddress("SW1A 1AA"))
        assertFalse(PlaceHits.looksLikeAddress("50"))
    }
}
