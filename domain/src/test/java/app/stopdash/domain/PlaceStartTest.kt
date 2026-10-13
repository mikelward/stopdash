package app.stopdash.domain

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class PlaceStartTest {
    @Test
    fun `a place's id reads back as its coordinate`() {
        val at = Coordinates(51.5, -0.12)
        assertEquals(at, PlaceStart.coordinate(PlaceStart.id(at)))
    }

    @Test
    fun `a plain place's id is the one older builds wrote`() {
        assertEquals("place:51.5,-0.12", PlaceStart.id(Coordinates(51.5, -0.12)))
        assertEquals(PlaceArt.PLAIN, PlaceStart.art("place:51.5,-0.12"))
    }

    @Test
    fun `a searched place's id carries what it is`() {
        val at = Coordinates(51.5, -0.12)
        for (art in listOf(PlaceArt(type = PlaceType.PUB), PlaceArt(PlaceKind.STREET), PlaceArt(PlaceKind.POSTCODE))) {
            val id = PlaceStart.id(at, art)
            assertEquals(at, PlaceStart.coordinate(id))
            assertEquals(art, PlaceStart.art(id))
        }
    }

    @Test
    fun `an art this build doesn't know is a plain place, still at its coordinate`() {
        assertEquals(Coordinates(51.5, -0.12), PlaceStart.coordinate("place:51.5,-0.12,aquarium"))
        assertEquals(PlaceArt.PLAIN, PlaceStart.art("place:51.5,-0.12,aquarium"))
    }

    @Test
    fun `a station's id is no place`() {
        assertNull(PlaceStart.coordinate("940GZZLUKSX"))
        assertNull(PlaceStart.coordinate("HUBKGX"))
        assertEquals(PlaceArt.PLAIN, PlaceStart.art("940GZZLUKSX"))
    }

    @Test
    fun `a malformed place id is no place`() {
        assertNull(PlaceStart.coordinate("place:"))
        assertNull(PlaceStart.coordinate("place:51.5"))
        assertNull(PlaceStart.coordinate("place:x,y"))
        assertNull(PlaceStart.coordinate("place:51.5,-0.12,pub,extra"))
    }
}
