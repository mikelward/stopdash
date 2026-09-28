package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultsTest {
    // Synthetic names and coordinates only (AGENTS *Privacy*).
    private fun stop(name: String) = StationMatch("id-$name", name, emptyList())
    private fun place(name: String, kind: PlaceKind = PlaceKind.PLACE) = PlaceHit(name, Coordinates(51.5, -0.1), kind)

    private fun names(entries: List<SearchEntry>) = entries.map {
        when (it) {
            is SearchEntry.Stop -> it.match.name
            is SearchEntry.Place -> it.hit.name
        }
    }

    @Test
    fun `a place the query starts ranks above stops that only contain it`() {
        val stops = listOf(stop("Zeta Lane"), stop("Farm Bezeta"), stop("Upper Bezeta"))
        val places = listOf(place("Alpha District, Zeta Gallery"))
        assertEquals(
            listOf("Zeta Lane", "Alpha District, Zeta Gallery", "Farm Bezeta", "Upper Bezeta"),
            names(SearchResults.merge("zeta", stops, places)),
        )
    }

    @Test
    fun `each list keeps its own order, and a stop wins a tie`() {
        val stops = listOf(stop("Zeta One"), stop("Zeta Two"))
        val places = listOf(place("Zeta Hall"), place("Zeta Gardens"))
        assertEquals(
            listOf("Zeta One", "Zeta Two", "Zeta Hall", "Zeta Gardens"),
            names(SearchResults.merge("zeta", stops, places)),
        )
    }

    @Test
    fun `a postcode's places follow the stops`() {
        val stops = listOf(stop("Zeta Lane"))
        val places = listOf(place("Somewhere", PlaceKind.POSTCODE))
        assertEquals(listOf("Zeta Lane", "Somewhere"), names(SearchResults.merge("zeta", stops, places)))
    }

    private fun keys(entries: List<SearchEntry>) = entries.map { it.key }

    @Test
    fun `appending keeps every shown row in place and adds the rest below`() {
        val shown = SearchResults.merge("zeta", listOf(stop("Upper Bezeta")), emptyList())
        val next = SearchResults.appended(shown, "zeta", listOf(stop("Zeta Lane"), stop("Upper Bezeta")), listOf(place("Zeta Hall")))
        assertEquals(listOf("Upper Bezeta", "Zeta Lane", "Zeta Hall"), names(next))
    }

    @Test
    fun `a shown row takes the newer copy of itself in place`() {
        val shown = SearchResults.merge("zeta", listOf(stop("Zeta Lane"), stop("Zeta Road")), emptyList())
        val fuller = StationMatch("id-Zeta Lane", "Zeta Lane", listOf("bus"), latitude = 51.5, longitude = -0.1)
        val next = SearchResults.appended(shown, "zeta", listOf(fuller), emptyList())
        assertEquals(listOf("id-Zeta Lane", "id-Zeta Road"), keys(next))
        assertEquals(51.5, (next.first() as SearchEntry.Stop).match.latitude)
    }

    @Test
    fun `a shown stop folded out of the full ranking leaves, one only past the cap stays`() {
        val shown = SearchResults.merge("zeta", listOf(stop("Zeta One"), stop("Zeta Two"), stop("Zeta Three")), emptyList())
        // The full ranking folded "Zeta Two" into another; "Zeta Three" is only past the cap.
        val every = listOf(stop("Zeta One"), stop("Zeta Three"))
        val next = SearchResults.appended(shown, "zeta", every.take(1), emptyList(), everyStop = every)
        assertEquals(listOf("id-Zeta One", "id-Zeta Three"), keys(next))
    }

    @Test
    fun `without the full ranking, no shown row leaves`() {
        val shown = SearchResults.merge("zeta", listOf(stop("Zeta One"), stop("Zeta Two")), emptyList())
        val next = SearchResults.appended(shown, "zeta", emptyList(), listOf(place("Zeta Hall")))
        assertEquals(listOf("Zeta One", "Zeta Two", "Zeta Hall"), names(next))
    }
}
