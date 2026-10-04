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
    fun `a station's id is no place`() {
        assertNull(PlaceStart.coordinate("940GZZLUKSX"))
        assertNull(PlaceStart.coordinate("HUBKGX"))
    }

    @Test
    fun `a malformed place id is no place`() {
        assertNull(PlaceStart.coordinate("place:"))
        assertNull(PlaceStart.coordinate("place:51.5"))
        assertNull(PlaceStart.coordinate("place:x,y"))
    }
}
