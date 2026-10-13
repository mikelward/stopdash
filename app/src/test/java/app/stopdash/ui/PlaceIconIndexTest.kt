package app.stopdash.ui

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaceIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The From/To bar finds a saved place's own icon in [PlaceIconIndex]: by the place's id, or by a trip
 * end's coordinate and name, which is what the place's chip hands a trip. Synthetic places only.
 */
class PlaceIconIndexTest {
    private val home = FavoritePlace("p-home", FavoriteKind.HOME, "Home", Coordinates(0.0, 0.0), icon = FavoritePlaceIcon.HOME)
    private val gym = FavoritePlace("p-gym", FavoriteKind.CUSTOM, "", Coordinates(0.5, 0.5), placeName = "Gym A", icon = FavoritePlaceIcon.GYM)

    @Test
    fun a_saved_place_is_found_by_its_id_and_by_where_and_what_its_chip_routes_to() {
        val index = PlaceIconIndex.of(listOf(home, gym))
        assertEquals(FavoritePlaceIcon.HOME, index.forPlace("p-home"))
        assertEquals(FavoritePlaceIcon.HOME, index.at(Coordinates(0.0, 0.0), "Home"))
        // A place with no label routes by its place name ([favoriteRouteName]), and is found by it.
        assertEquals(FavoritePlaceIcon.GYM, index.at(Coordinates(0.5, 0.5), "Gym A"))
    }

    @Test
    fun an_end_no_saved_place_matches_has_no_icon() {
        val index = PlaceIconIndex.of(listOf(home))
        // A searched place at the same spot under another name, a place renamed since, somewhere else.
        assertNull(index.at(Coordinates(0.0, 0.0), "Somewhere"))
        assertNull(index.at(Coordinates(1.0, 1.0), "Home"))
        assertNull(index.forPlace("p-other"))
    }

    @Test
    fun a_place_without_an_icon_this_build_draws_has_none() {
        val plain = home.copy(id = "p-plain", icon = null)
        val newer = home.copy(id = "p-newer", coordinate = Coordinates(0.25, 0.25), icon = "a-newer-builds-icon")
        val index = PlaceIconIndex.of(listOf(plain, newer))
        assertNull(index.forPlace("p-plain"))
        assertNull(index.forPlace("p-newer"))
        assertNull(index.at(Coordinates(0.25, 0.25), "Home"))
    }

    @Test
    fun places_that_cant_be_read_or_have_no_icons_index_nothing() {
        assertSame(PlaceIconIndex.NONE, PlaceIconIndex.of(null))
        assertSame(PlaceIconIndex.NONE, PlaceIconIndex.of(emptyList()))
        assertSame(PlaceIconIndex.NONE, PlaceIconIndex.of(listOf(home.copy(icon = null))))
        // Read, though: an end that matches nothing is known not to be a saved place.
        assertTrue(PlaceIconIndex.of(null).ready)
    }

    @Test
    fun before_the_first_read_nothing_is_known_either_way() {
        assertFalse(PlaceIconIndex.UNREAD.ready)
        assertNull(PlaceIconIndex.UNREAD.at(Coordinates(0.0, 0.0), "Home"))
        assertTrue(PlaceIconIndex.of(listOf(home)).ready)
    }
}
