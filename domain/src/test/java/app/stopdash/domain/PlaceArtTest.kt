package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceArtTest {
    @Test
    fun `every art round-trips through its code`() {
        val arts = PlaceType.entries.map { PlaceArt(type = it) } + PlaceArt(PlaceKind.STREET) + PlaceArt(PlaceKind.POSTCODE)
        for (art in arts) assertEquals(art, PlaceArt.ofCode(art.code))
    }

    @Test
    fun `no code, or one this build doesn't know, is a plain place`() {
        assertEquals(PlaceArt.PLAIN, PlaceArt.ofCode(null))
        assertEquals(PlaceArt.PLAIN, PlaceArt.ofCode("aquarium"))
    }

    @Test
    fun `a street or postcode code is never a place type's`() {
        val codes = PlaceType.entries.map { it.code }
        assertEquals(false, PlaceArt(PlaceKind.STREET).code in codes)
        assertEquals(false, PlaceArt(PlaceKind.POSTCODE).code in codes)
    }

    @Test
    fun `a search hit's art is its kind and type`() {
        val pub = PlaceHit("A pub", Coordinates(51.5, -0.12), PlaceKind.PLACE, type = PlaceType.PUB)
        assertEquals(PlaceArt(type = PlaceType.PUB), pub.art)
        assertEquals(PlaceArt(PlaceKind.POSTCODE), PlaceHit("XX1 1BA", Coordinates(51.5, -0.12), PlaceKind.POSTCODE).art)
    }
}
