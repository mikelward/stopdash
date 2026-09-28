package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import java.time.DayOfWeek
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
            FavoritePlace(
                "w", FavoriteKind.WORK, "Work", Coordinates(51.51, -0.1),
                showOnDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            ),
            FavoritePlace("s", FavoriteKind.SCHOOL, "School", Coordinates(51.52, -0.11), showOnDays = emptySet()),
        )
        assertEquals(places, places.toPersisted().toDomain())
    }

    @Test
    fun `every day is stored as absent, some days as ISO numbers, and none as an empty list`() {
        val stored = listOf(
            FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12)),
            FavoritePlace(
                "w", FavoriteKind.WORK, "Work", Coordinates(51.51, -0.1),
                showOnDays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY),
            ),
            FavoritePlace("s", FavoriteKind.SCHOOL, "School", Coordinates(51.52, -0.11), showOnDays = emptySet()),
        ).toPersisted().places
        assertEquals(listOf(null, listOf(1, 5), emptyList<Int>()), stored.map { it.showOnDays })
    }

    @Test
    fun `a write is the current version, so an older build preserves it rather than dropping the days`() {
        val written = listOf(FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))).toPersisted()
        assertEquals(2, written.version)
        assertEquals(PersistedFavoritePlaces.CURRENT_VERSION, written.version)
    }

    @Test
    fun `a place stored before days existed shows every day`() {
        // A v1 file: no days field, and the version before it was added.
        val stored = PersistedFavoritePlaces(
            version = 1,
            places = listOf(PersistedFavoritePlace("h", "HOME", "Home", 51.5, -0.12)),
        )
        assertEquals(FavoritePlace.EVERY_DAY, stored.toDomain()?.single()?.showOnDays)
    }

    @Test
    fun `an out-of-range day number is dropped, keeping the rest`() {
        val stored = PersistedFavoritePlaces(
            places = listOf(PersistedFavoritePlace("w", "WORK", "Work", 51.5, -0.12, showOnDays = listOf(0, 3, 8))),
        )
        assertEquals(setOf(DayOfWeek.WEDNESDAY), stored.toDomain()?.single()?.showOnDays)
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
