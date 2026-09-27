package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceHitsTest {
    // Synthetic place names and coordinates only — no real landmark or postcode (AGENTS *Privacy*).
    private fun candidate(name: String, lat: Double = 51.5, lon: Double = -0.1) =
        PlaceCandidate(name, Coordinates(lat, lon))

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
}
