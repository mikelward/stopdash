package app.stopdash

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowShortcutManager

/** The launcher's shortcuts to the saved places ([PlaceShortcuts], SPEC *Launcher shortcuts*). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class PlaceShortcutsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Obviously synthetic positions, never a real place (SPEC *Privacy*).
    private val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(0.0, 0.0), icon = "home")
    private val gym = FavoritePlace("g", FavoriteKind.CUSTOM, "Gym", Coordinates(0.01, 0.0))

    @Before
    fun forgetLastPublished() = PlaceShortcuts.forgetPublished()

    // Robolectric's shortcut shadow keeps a disabled pin in a map of its own without clearing the
    // pin's isEnabled, so a disable is only visible there.
    @Suppress("UNCHECKED_CAST")
    private fun disabledPins(): List<String> =
        (ShadowShortcutManager::class.java.getDeclaredField("disabledPinnedShortcuts").apply { isAccessible = true }
            .get(null) as Map<String, *>).keys.sorted()

    private fun dynamic() = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }

    @Test
    fun the_shortcuts_are_published_on_the_worker_one_per_place_in_saved_order() {
        val warnings = mutableListOf<String>()
        // Held until released by hand: published on the caller's thread, they'd be there at once.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        // The caller runs in place on this one thread, up to the hop.
        var done = false
        CoroutineScope(Dispatchers.Unconfined).launch {
            PlaceShortcuts.publish(context, listOf(home, gym), "gone", worker = held, warn = { warnings += it })
            done = true
        }
        assertTrue(dynamic().isEmpty())
        assertEquals(false, done)
        scheduler.advanceUntilIdle()
        assertTrue(done)
        assertEquals(listOf("place:h", "place:g"), dynamic().map { it.id })
        assertEquals(listOf("Home", "Gym"), dynamic().map { it.shortLabel.toString() })
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun use_is_reported_on_the_worker() {
        runBlocking { PlaceShortcuts.publish(context, listOf(home), "gone", warn = {}) }
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var done = false
        CoroutineScope(Dispatchers.Unconfined).launch {
            PlaceShortcuts.reportUsed(context, "h", worker = held, warn = {})
            done = true
        }
        // Reported on the caller's thread, it would be done already.
        assertEquals(false, done)
        scheduler.advanceUntilIdle()
        assertTrue(done)
    }

    @Test
    fun a_shortcut_carries_the_place_id_and_no_coordinate() {
        runBlocking { PlaceShortcuts.publish(context, listOf(home), "gone", warn = {}) }
        val intent = dynamic().single().intent
        assertEquals(PlaceShortcuts.ACTION_ROUTE_TO_PLACE, intent.action)
        assertEquals(setOf(PlaceShortcuts.EXTRA_PLACE_ID), intent.extras?.keySet())
        assertEquals("h", intent.getStringExtra(PlaceShortcuts.EXTRA_PLACE_ID))
        // Starts a fresh screen, so nothing left open sits over the trip.
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun only_as_many_as_the_launcher_takes_are_published() {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val many = (1..max + 5).map { FavoritePlace("p$it", FavoriteKind.CUSTOM, "Place $it", Coordinates(0.0, 0.0)) }
        val warnings = mutableListOf<String>()
        runBlocking { PlaceShortcuts.publish(context, many, "gone", warn = { warnings += it }) }
        assertEquals(many.take(max).map { "place:${it.id}" }, dynamic().map { it.id })
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun a_removed_places_pinned_shortcut_is_disabled() {
        runBlocking { PlaceShortcuts.publish(context, listOf(home, gym), "gone", warn = {}) }
        val pinnedGym = dynamic().single { it.id == "place:g" }
        assertTrue(ShortcutManagerCompat.requestPinShortcut(context, pinnedGym, null))
        assertEquals(emptyList<String>(), disabledPins())
        runBlocking { PlaceShortcuts.publish(context, listOf(home), "gone", warn = {}) }
        // Disabled, so a tap on it says the place is gone rather than opening a trip.
        assertEquals(listOf("place:g"), disabledPins())
    }

    @Test
    fun a_removed_place_loses_its_shortcut() {
        runBlocking {
            PlaceShortcuts.publish(context, listOf(home, gym), "gone", warn = {})
            PlaceShortcuts.publish(context, listOf(gym), "gone", warn = {})
        }
        assertEquals(listOf("place:g"), dynamic().map { it.id })
    }

    @Test
    fun the_place_is_taken_off_the_intent_once() {
        val intent = Intent(PlaceShortcuts.ACTION_ROUTE_TO_PLACE).putExtra(PlaceShortcuts.EXTRA_PLACE_ID, "h")
        assertEquals("h", PlaceShortcuts.takePlaceId(intent))
        assertNull(PlaceShortcuts.takePlaceId(intent))
        // The launcher's own start of the app asks for no place.
        assertNull(PlaceShortcuts.takePlaceId(Intent(Intent.ACTION_MAIN).putExtra(PlaceShortcuts.EXTRA_PLACE_ID, "h")))
        assertNull(PlaceShortcuts.takePlaceId(null))
    }
}
