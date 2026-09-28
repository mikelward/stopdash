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
}
