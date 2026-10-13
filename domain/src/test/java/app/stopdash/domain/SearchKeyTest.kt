package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchKeyTest {
    // Public TfL stations, and a synthetic bus stop and place, no one's own.
    private val waterloo = SearchEntry.Stop(StationMatch("940GZZLUWLO", "Waterloo", listOf("tube")))
    private val waterlooEast = SearchEntry.Stop(StationMatch("910GWLOE", "Waterloo East", listOf("national-rail")))
    private val waterlooBus = SearchEntry.Stop(StationMatch("490000000Z", "Waterloo", listOf("bus")))
    private val gallery = SearchEntry.Place(PlaceHit("Sample Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE, type = PlaceType.GALLERY))
    private fun postcode(name: String) = SearchEntry.Place(PlaceHit(name, Coordinates(51.5, -0.12), PlaceKind.POSTCODE))

    @Test
    fun `the top row named exactly what was typed is opened`() {
        assertEquals(waterloo, SearchKey.pick("waterloo", listOf(waterloo, waterlooEast)))
        // Case, spaces and punctuation as the matcher reads them.
        assertEquals(waterloo, SearchKey.pick("  WATERLOO ", listOf(waterloo, waterlooEast)))
        assertEquals(gallery, SearchKey.pick("sample gallery", listOf(gallery, waterlooEast)))
    }

    @Test
    fun `two rows named exactly the same are a choice, not a pick`() {
        assertNull(SearchKey.pick("waterloo", listOf(waterloo, waterlooBus, waterlooEast)))
    }

    @Test
    fun `an exact name below the top row is a choice, not a pick`() {
        assertNull(SearchKey.pick("waterloo", listOf(waterlooEast, waterloo)))
    }

    @Test
    fun `the only row is opened, however loosely it matched`() {
        assertEquals(waterlooEast, SearchKey.pick("waterloo e", listOf(waterlooEast)))
    }

    @Test
    fun `several loose rows are a choice, not a pick`() {
        assertNull(SearchKey.pick("waterl", listOf(waterloo, waterlooEast)))
    }

    @Test
    fun `no rows open nothing`() {
        assertNull(SearchKey.pick("waterloo", emptyList()))
    }

    @Test
    fun `a complete postcode opens only its own row`() {
        // Synthetic postcodes in the X area, none real.
        assertEquals(postcode("XX1 1BA"), SearchKey.pick("xx11ba", listOf(postcode("XX1 1BA"))))
        // The bundle's lone near postcode is a guess at one it doesn't hold: TfL is asked next.
        assertNull(SearchKey.pick("XX1 1BA", listOf(postcode("XX1 1BB"))))
        // A partial one is typed toward a list, and its only row is opened like any other.
        assertEquals(postcode("XX1 1BA"), SearchKey.pick("XX1 1", listOf(postcode("XX1 1BA"))))
    }
}
