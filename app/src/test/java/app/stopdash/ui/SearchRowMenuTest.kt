package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationMatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A search row's long-press menu ([SearchRowMenu]). Public station names and synthetic positions only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class SearchRowMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bank = SearchEntry.Stop(StationMatch("940GZZLUBNK", "Bank", listOf("tube"), latitude = 51.5, longitude = -0.12))
    private val gallery = SearchEntry.Place(PlaceHit("Example Gallery", Coordinates(51.51, -0.13), PlaceKind.PLACE))
    private val saved = FavoritePlace("p1", FavoriteKind.CUSTOM, "Gallery", Coordinates(51.51, -0.13))

    @Test
    fun whether_the_row_is_recent_or_saved_is_worked_out_on_the_worker_and_the_items_come_together() {
        // The worker's tasks queue here and run on their own thread only when the test says so, so the
        // first frame can be looked at before they run (AGENTS.md *Main thread: read and dispatch only*).
        val queued = LinkedBlockingQueue<Runnable>()
        val worker = Executor { queued.add(it) }.asCoroutineDispatcher()
        val ranOn = mutableSetOf<Thread>()
        fun runQueued() {
            val thread = Thread {
                while (true) {
                    val task = queued.poll() ?: break
                    synchronized(ranOn) { ranOn += Thread.currentThread() }
                    task.run()
                }
            }
            thread.start()
            thread.join()
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides worker) {
                SearchRowMenu(
                    entry = bank,
                    recent = listOf(bank),
                    savedPlaces = emptyList(),
                    actions = SearchRowActions(onShowOnMap = { _, _ -> }, onAskRemoveRecent = {}, onAddPlace = {}),
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        // Nothing is shown until the worker has run: the menu comes up whole.
        composeRule.onNodeWithTag("rowMenuMap").assertDoesNotExist()
        composeRule.onNodeWithTag("rowMenuRemoveRecent").assertDoesNotExist()
        runQueued()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rowMenuMap").assertExists()
        composeRule.onNodeWithTag("rowMenuRemoveRecent").assertExists()
        composeRule.onNodeWithTag("rowMenuAddPlace").assertExists()
        val main = Thread.currentThread()
        assertTrue("the work ran", ranOn.isNotEmpty())
        assertFalse("never on the main thread", main in ranOn)
    }

    @Test
    fun a_row_at_a_saved_place_offers_its_removal_and_shows_on_the_map_where_it_is() {
        var mapped: Pair<String, Coordinates>? = null
        var removed: FavoritePlace? = null
        var closed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                SearchRowMenu(
                    entry = gallery,
                    recent = emptyList(),
                    savedPlaces = listOf(saved),
                    actions = SearchRowActions(
                        onShowOnMap = { name, at -> mapped = name to at },
                        onAskRemoveRecent = {},
                        onAddPlace = {},
                        onRemovePlace = { removed = it },
                    ),
                    onDismiss = { closed = true },
                )
            }
        }
        composeRule.waitForIdle()
        // Not a recent pick, and saved: no removal from Recent, and no adding it again.
        composeRule.onNodeWithTag("rowMenuRemoveRecent").assertDoesNotExist()
        composeRule.onNodeWithTag("rowMenuAddPlace").assertDoesNotExist()
        composeRule.onNodeWithTag("rowMenuRemovePlace").performClick()
        assertEquals(saved, removed)
        assertTrue(closed)
        composeRule.onNodeWithTag("rowMenuMap").performClick()
        assertEquals("Example Gallery" to Coordinates(51.51, -0.13), mapped)
    }

    @Test
    fun adding_a_place_hands_the_editor_the_row_at_its_position() {
        var added: StationMatch? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                SearchRowMenu(
                    entry = gallery,
                    recent = emptyList(),
                    savedPlaces = emptyList(),
                    actions = SearchRowActions(onAddPlace = { added = it }, onRemovePlace = {}),
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        // Nothing to show it on the map with: that item is left out.
        composeRule.onNodeWithTag("rowMenuMap").assertDoesNotExist()
        composeRule.onNodeWithTag("rowMenuAddPlace").performClick()
        assertEquals("Example Gallery", added?.name)
        assertEquals(51.51, added?.latitude)
        assertEquals(-0.13, added?.longitude)
    }

    @Test
    fun a_stop_with_no_position_offers_no_map() {
        val unplaced = SearchEntry.Stop(StationMatch("490000000001A", "Example Road", listOf("bus")))
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                SearchRowMenu(
                    entry = unplaced,
                    recent = emptyList(),
                    savedPlaces = listOf(saved),
                    actions = SearchRowActions(onShowOnMap = { _, _ -> }, onAddPlace = {}, onRemovePlace = {}),
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rowMenuMap").assertDoesNotExist()
        composeRule.onNodeWithTag("rowMenuAddPlace").assertExists()
    }

    @Test
    fun saved_places_that_could_not_be_read_offer_neither_favorite_item() {
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                SearchRowMenu(
                    entry = gallery,
                    recent = emptyList(),
                    savedPlaces = emptyList(),
                    actions = SearchRowActions(onShowOnMap = { _, _ -> }, onAddPlace = {}, onRemovePlace = {}),
                    onDismiss = {},
                    placesKnown = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rowMenuMap").assertExists()
        composeRule.onNodeWithTag("rowMenuAddPlace").assertDoesNotExist()
        composeRule.onNodeWithTag("rowMenuRemovePlace").assertDoesNotExist()
    }

    @Test
    fun a_place_found_outside_london_opens_the_menu_too() {
        // A place outside London that TfL's search found: a synthetic name and position.
        val faraway = PlaceHit("Example Town", Coordinates(52.0, -1.0), PlaceKind.PLACE)
        var opened = false
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                StationSearchScreen(
                    state = StationSearchViewModel.State(
                        query = "Example",
                        result = StationSearchViewModel.Result.NoMatches,
                        outside = OutsideLondon.Found(listOf(faraway)),
                    ),
                    onQueryChange = {},
                    onOpenStation = {},
                    onBack = {},
                    autoFocus = false,
                    onOpenPlace = { opened = true },
                    onShowOnMap = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Example Town").performTouchInput { longClick() }
        composeRule.onNodeWithTag("rowMenuMap").assertExists()
        assertFalse("a long press never opens the row", opened)
    }

    @Test
    fun a_station_with_no_position_of_its_own_is_placed_by_lookup_and_found_saved() {
        // Bundled rail and Tube stations carry no position: the menu looks it up (the view model's
        // positionOf), so it can show the map and see that a favorite is saved there.
        val station = SearchEntry.Stop(StationMatch("940GZZLUBNK", "Bank", listOf("tube")))
        val at = Coordinates(51.513, -0.089)
        val savedHere = FavoritePlace("p2", FavoriteKind.CUSTOM, "Bank", at)
        var mapped: Coordinates? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                SearchRowMenu(
                    entry = station,
                    recent = emptyList(),
                    savedPlaces = listOf(savedHere),
                    actions = SearchRowActions(onShowOnMap = { _, c -> mapped = c }, onAddPlace = {}, onRemovePlace = {}),
                    onDismiss = {},
                    positionOf = { at },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rowMenuAddPlace").assertDoesNotExist()
        composeRule.onNodeWithTag("rowMenuRemovePlace").assertExists()
        composeRule.onNodeWithTag("rowMenuMap").performClick()
        assertEquals(at, mapped)
    }
}
