package app.stopdash.domain

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure favorites operations, on synthetic coordinates only (no real place — SPEC *Privacy*). */
class FavoritePlacesTest {
    private val here = Coordinates(51.5, -0.12)
    private val there = Coordinates(51.6, -0.10)

    private fun place(
        id: String,
        kind: FavoriteKind,
        label: String = kind.name,
        coordinate: Coordinates = here,
    ) = FavoritePlace(id = id, kind = kind, label = label, coordinate = coordinate)

    @Test
    fun `home and work accessors return the single slot or null`() {
        val list = listOf(place("h", FavoriteKind.HOME), place("w", FavoriteKind.WORK))
        assertEquals("h", FavoritePlaces.home(list)?.id)
        assertEquals("w", FavoritePlaces.work(list)?.id)
        assertNull(FavoritePlaces.home(emptyList()))
        assertNull(FavoritePlaces.work(emptyList()))
    }

    @Test
    fun `upsert of a singleton kind replaces in place, keeping position`() {
        val list = listOf(
            place("h1", FavoriteKind.HOME, coordinate = here),
            place("w1", FavoriteKind.WORK),
        )
        val moved = place("h2", FavoriteKind.HOME, label = "Home", coordinate = there)

        val result = FavoritePlaces.upsert(list, moved)

        assertEquals(2, result.size)
        // The HOME slot is replaced (new id, new coordinate) but stays first; WORK is untouched.
        assertEquals("h2", result[0].id)
        assertEquals(there, result[0].coordinate)
        assertEquals("w1", result[1].id)
    }

    @Test
    fun `upsert of a custom place appends when its id is new`() {
        val list = listOf(place("c1", FavoriteKind.CUSTOM, label = "Gym"))
        val result = FavoritePlaces.upsert(list, place("c2", FavoriteKind.CUSTOM, label = "Clinic"))
        assertEquals(listOf("c1", "c2"), result.map { it.id })
    }

    @Test
    fun `upsert of a custom place replaces the one with the same id`() {
        val list = listOf(
            place("c1", FavoriteKind.CUSTOM, label = "Gym", coordinate = here),
            place("c2", FavoriteKind.CUSTOM, label = "Clinic"),
        )
        val edited = place("c1", FavoriteKind.CUSTOM, label = "Gym (new)", coordinate = there)

        val result = FavoritePlaces.upsert(list, edited)

        assertEquals(listOf("c1", "c2"), result.map { it.id })
        assertEquals("Gym (new)", result[0].label)
        assertEquals(there, result[0].coordinate)
    }

    @Test
    fun `remove drops the entry with the given id and leaves the rest`() {
        val list = listOf(
            place("h", FavoriteKind.HOME),
            place("c1", FavoriteKind.CUSTOM),
            place("c2", FavoriteKind.CUSTOM),
        )
        assertEquals(listOf("h", "c2"), FavoritePlaces.remove(list, "c1").map { it.id })
        // Removing an absent id is a no-op.
        assertEquals(list, FavoritePlaces.remove(list, "missing"))
    }

    @Test
    fun `the main screen offers only the places set to show today, in list order`() {
        val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
        val work = FavoritePlace(
            "w", FavoriteKind.WORK, "Work", Coordinates(51.51, -0.1),
            showOnDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        )
        val never = FavoritePlace("n", FavoriteKind.CUSTOM, "Gym", Coordinates(51.52, -0.11), showOnDays = emptySet())
        val places = listOf(home, work, never)
        assertEquals(listOf(home, work), FavoritePlaces.onMainScreen(places, DayOfWeek.MONDAY))
        assertEquals(listOf(home), FavoritePlaces.onMainScreen(places, DayOfWeek.SATURDAY))
    }

    @Test
    fun `a place shows every day by default`() {
        val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
        DayOfWeek.values().forEach { assertEquals(true, home.showsOn(it)) }
    }
}
