package app.stopdash.ui

import app.stopdash.R
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoritePlaceIcon
import app.stopdash.domain.ModeIcon
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.PlaceType
import org.junit.Assert.assertEquals
import org.junit.Test

class RowTagTest {
    @Test
    fun `every mode icon has a drawable`() {
        assertEquals(ModeIcon.entries.size, ModeIcon.entries.map { modeIcon(it) }.distinct().size)
    }

    @Test
    fun `every place type has an icon and a label, and streets and postcodes theirs`() {
        val at = Coordinates(51.5, -0.12)
        for (type in PlaceType.entries) {
            val place = PlaceHit("Zeta", at, PlaceKind.PLACE, type = type)
            // getValue throws for a type the table leaves out.
            placeIcon(place)
            placeTypeLabel(place)
        }
        assertEquals(R.drawable.ic_place_pub, placeIcon(PlaceHit("Zeta", at, PlaceKind.PLACE, type = PlaceType.PUB)))
        assertEquals(R.drawable.ic_kind_street, placeIcon(PlaceHit("Zeta Road", at, PlaceKind.STREET)))
        assertEquals(R.string.station_search_kind_postcode, placeTypeLabel(PlaceHit("XX1 1XX", at, PlaceKind.POSTCODE)))
    }

    @Test
    fun `the favorite picker can draw every choice, and offers each glyph once`() {
        val drawables = FavoritePlaceIcon.CHOICES.map { placeIconArt.getValue(it).drawable }
        assertEquals(drawables.size, drawables.distinct().size)
        // And a place saved from search starts on an icon the picker offers.
        for (type in PlaceType.entries) FavoritePlaceIcon.forType(type)?.let { assertEquals(true, it in placeIconArt) }
    }
}
