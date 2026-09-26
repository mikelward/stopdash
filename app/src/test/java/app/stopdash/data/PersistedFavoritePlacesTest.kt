package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The on-disk favorites round-trip, on synthetic coordinates only (no real place — SPEC *Privacy*). */
class PersistedFavoritePlacesTest {

    @Test
    fun `toPersisted then toDomain preserves every field`() {
        val places = listOf(
            FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12), placeName = "Example Rd"),
            FavoritePlace("c1", FavoriteKind.CUSTOM, "Gym", Coordinates(51.6, -0.10)),
        )
        assertEquals(places, places.toPersisted().toDomain())
    }

    @Test
    fun `a newer version reads as unavailable rather than being mis-read`() {
        val stored = PersistedFavoritePlaces(
            version = PersistedFavoritePlaces.CURRENT_VERSION + 1,
            places = listOf(PersistedFavoritePlace("h", "HOME", "Home", 51.5, -0.12)),
        )
        assertNull(stored.toDomain())
    }

    @Test
    fun `an unknown kind within a known version falls back to custom, keeping the place`() {
        val stored = PersistedFavoritePlaces(
            places = listOf(PersistedFavoritePlace("x", "GARAGE", "Garage", 51.5, -0.12)),
        )
        val domain = stored.toDomain()
        assertEquals(1, domain?.size)
        assertEquals(FavoriteKind.CUSTOM, domain?.first()?.kind)
        assertEquals("Garage", domain?.first()?.label)
    }
}
