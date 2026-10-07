package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherShortcutsTest {
    // Obviously synthetic positions, never a real place (SPEC *Privacy*).
    private fun place(id: String, label: String, placeName: String? = null, kind: FavoriteKind = FavoriteKind.CUSTOM) =
        FavoritePlace(id, kind, label, Coordinates(0.0, 0.0), placeName = placeName)

    private val home = place("h", "Home", kind = FavoriteKind.HOME)
    private val work = place("w", "Work", kind = FavoriteKind.WORK)
    private val gym = place("g", "Gym")

    @Test
    fun an_id_round_trips_and_other_ids_are_not_places() {
        assertEquals("h", LauncherShortcuts.placeIdOf(LauncherShortcuts.idFor(home)))
        assertNull(LauncherShortcuts.placeIdOf("h"))
        assertNull(LauncherShortcuts.placeIdOf("journey:h"))
    }

    @Test
    fun places_are_offered_in_saved_order_up_to_the_cap() {
        assertEquals(listOf(home, work, gym), LauncherShortcuts.offered(listOf(home, work, gym), 4))
        assertEquals(listOf(home, work), LauncherShortcuts.offered(listOf(home, work, gym), 2))
        assertEquals(emptyList<FavoritePlace>(), LauncherShortcuts.offered(listOf(home), 0))
    }

    @Test
    fun a_place_offered_every_day_whatever_its_chip_days() {
        val weekdays = work.copy(showOnDays = emptySet())
        assertEquals(listOf(weekdays), LauncherShortcuts.offered(listOf(weekdays), 4))
    }

    @Test
    fun a_place_with_no_name_is_left_out_but_one_named_by_its_address_is_kept() {
        val unnamed = place("u", " ")
        val byAddress = place("a", "", placeName = "1 Example Street")
        assertEquals(listOf(byAddress), LauncherShortcuts.offered(listOf(unnamed, byAddress), 4))
    }

    @Test
    fun only_pinned_shortcuts_to_removed_places_are_stale() {
        val pinned = listOf(LauncherShortcuts.idFor(home), LauncherShortcuts.idFor(gym), "something-else")
        assertEquals(listOf(LauncherShortcuts.idFor(gym)), LauncherShortcuts.stale(pinned, listOf(home, work)))
        assertEquals(emptyList<String>(), LauncherShortcuts.stale(pinned, listOf(home, gym)))
    }

    @Test
    fun a_place_is_found_by_id_or_not_at_all() {
        assertEquals(work, LauncherShortcuts.find(listOf(home, work), "w"))
        assertNull(LauncherShortcuts.find(listOf(home), "w"))
    }
}
