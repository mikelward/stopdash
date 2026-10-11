package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun suggestion(id: String, name: String, vararg types: String) =
        PlaceSuggestion(id, name, detail = "Area $id", types = types.toList())

    @Test
    fun `keeps Google's order and doesn't drop a suggestion the matcher can't place`() {
        // A typo Google corrected ("zetta") and a weaker match first: Google's ranking stands as given.
        val hits = PlaceHits.fromSuggestions(listOf(suggestion("a", "Zeta Gallery"), suggestion("b", "Alpha Hall")))
        assertEquals(listOf("Zeta Gallery", "Alpha Hall"), hits.map { it.name })
        assertEquals(listOf("a", "b"), hits.map { it.placeId })
        assertEquals("Area a", hits[0].detail)
        hits.forEach {
            assertEquals(PlaceKind.PLACE, it.kind)
            assertNull(it.coordinate)
            assertTrue(it.needsLookup)
        }
    }

    @Test
    fun `drops stations, which the stop rows already list with their ids`() {
        val hits = PlaceHits.fromSuggestions(
            listOf(
                suggestion("a", "Zeta Station", "train_station", "transit_station"),
                suggestion("b", "Zeta Gallery", "art_gallery"),
                suggestion("c", "Zeta Stop", "bus_stop"),
            ),
        )
        assertEquals(listOf("Zeta Gallery"), hits.map { it.name })
    }

    @Test
    fun `de-duplicates suggestions by place id and caps them`() {
        val many = (1..20).map { suggestion("id$it", "Zeta $it") } + suggestion("id1", "Zeta 1 again")
        val hits = PlaceHits.fromSuggestions(many)
        assertEquals(PlaceHits.DEFAULT_LIMIT, hits.size)
        assertEquals(hits.size, hits.map { it.placeId }.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a place with neither a coordinate nor a place id is refused`() {
        PlaceHit("Nowhere", coordinate = null, kind = PlaceKind.PLACE)
    }
}
