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
    fun `equal place-name matches go to the one nearer where the trip starts`() {
        val farther = PlaceCandidate("Zeta Town", Coordinates(52.5, -1.0))
        val nearer = PlaceCandidate("Zeta Town", Coordinates(52.0, -0.5))
        val start = Coordinates(51.9, -0.4)
        assertEquals(listOf(nearer, farther).map { it.coordinate }, PlaceHits.rank("zeta", listOf(farther, nearer), PlaceKind.PLACE, near = start).map { it.coordinate })
        assertEquals(listOf(farther, nearer).map { it.coordinate }, PlaceHits.rank("zeta", listOf(farther, nearer), PlaceKind.PLACE).map { it.coordinate })
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
    fun `a house number then a word reads as an address`() {
        assertTrue(PlaceHits.looksLikeAddress("50 Euston Road"))
        assertTrue(PlaceHits.looksLikeAddress("12a Zeta Street"))
        assertTrue(PlaceHits.looksLikeAddress("10-12 Zeta Street"))
        assertTrue(PlaceHits.looksLikeAddress("10–12 Zeta Street"))
        assertTrue(PlaceHits.looksLikeAddress("10 - 12 Zeta Street"))
        assertFalse(PlaceHits.looksLikeAddress("Euston Road"))
        assertFalse(PlaceHits.looksLikeAddress("SW1A 1AA"))
        assertFalse(PlaceHits.looksLikeAddress("50"))
    }

    @Test
    fun `an address's street is what's left without its house number`() {
        assertEquals("Euston Road", PlaceHits.withoutHouseNumber("50 Euston Road"))
        assertEquals("Zeta Street", PlaceHits.withoutHouseNumber(" 10-12, Zeta Street "))
        assertEquals("Zeta Street", PlaceHits.withoutHouseNumber("10 – 12 Zeta Street"))
        assertEquals("Euston Road", PlaceHits.withoutHouseNumber("Euston Road"))
        assertEquals("SW1A 1AA", PlaceHits.withoutHouseNumber("SW1A 1AA"))
    }

    @Test
    fun `places outside London leave out what lies in the bundled index's box`() {
        // A hub inside London (King's Cross) and a synthetic place well outside it.
        val inside = PlaceCandidate("King's Cross", Coordinates(51.5308, -0.1238))
        val outside = PlaceCandidate("Zeta Town", Coordinates(52.0, -0.5))
        assertEquals(listOf(outside), PlaceHits.outsideBundle(listOf(inside, outside)))
    }

    @Test
    fun `the bundled box matches the place index builder's`() {
        val script = listOf(java.io.File("../scripts/build_place_index.py"), java.io.File("scripts/build_place_index.py")).first { it.exists() }
        val line = script.readLines().single { it.startsWith("WEST, EAST, SOUTH, NORTH = ") }
        val bounds = line.substringAfter("= ").split(",").map { it.trim().toDouble() }
        assertEquals(
            listOf(PlaceHits.BUNDLED_WEST, PlaceHits.BUNDLED_EAST, PlaceHits.BUNDLED_SOUTH, PlaceHits.BUNDLED_NORTH),
            bounds,
        )
    }
}
