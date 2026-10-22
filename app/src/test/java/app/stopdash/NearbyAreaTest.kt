package app.stopdash

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import app.stopdash.ui.KeptPages
import app.stopdash.ui.LocalPageOnTop
import app.stopdash.ui.PredictiveBack
import app.stopdash.ui.RootBack
import app.stopdash.ui.rememberBackFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NearbyArea], the back stack's host: each page keeps what it saved (an open route page) while another covers
 * it, so Back returns to the page it was opened from as it was left (SPEC *Back*), and the back gesture draws the
 * page under the one being swiped, starting nothing it would start on top (`dev-docs/navigation3.md`): under the
 * trip from here, a placeholder for the near-me list, whose departures are built again once the trip closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class NearbyAreaTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var pages: Pages
    private lateinit var dispatcher: OnBackPressedDispatcher

    // The pages each screen's own Back closed, and whether each page drawn was on top as it last composed.
    private val closed = ArrayList<String>()
    private val onTop = HashMap<String, Boolean>()

    // The station flow's search and the station open on it, as one page of the stack ([Page.Stations]).
    private var stationSearch by mutableStateOf(true)
    private var stationId by mutableStateOf<String?>(null)

    // The near-me list's departures, as the activity holds them: each model the list builds, and when it was dropped.
    private val stores = NearbyDeparturesStores()
    private val listModels = ArrayList<ListModel>()
    private lateinit var underTrip: ListUnderTrip

    class ListModel : ViewModel() {
        var dropped = false

        override fun onCleared() {
            dropped = true
        }
    }

    /**
     * A screen with a page of its own, opened by a tap on it, standing in for a saved route page: that page's
     * Back closes it, and the screen's Back closes the screen ([page], or none for the near-me view).
     */
    @Composable
    private fun Screen(name: String, page: Page?, close: () -> Unit = { page?.let(pages::close) }) {
        var inner by rememberSaveable { mutableStateOf(false) }
        val follow = rememberBackFollow()
        if (page != null) {
            RootBack(follow) {
                closed += name
                close()
            }
        }
        PredictiveBack(follow, enabled = inner) { inner = false }
        onTop[name] = LocalPageOnTop.current
        Text(if (inner) "$name's route page" else name, Modifier.clickable { inner = true })
    }

    private fun show() {
        composeRule.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            pages = remember { Pages(NavBackStack(Page.NearMe(0))) }
            underTrip = remember { ListUnderTrip(dropped = pages.tripOpen) }
            NearbyArea(
                pages = pages,
                aboveOverlay = {},
                overlayContent = { page ->
                    if (page == Page.Stations) {
                        KeptPages(stationFlowPage(stationId), stationFlowPages(stationSearch, stationId)) {
                            Screen(stationId?.let { "Station $it" } ?: "Search", page)
                        }
                    } else {
                        Screen(page.toString(), page)
                    }
                },
                // As the activity's: the trip from here on its own page, and the list under it built only while
                // it's drawn, not for a look under a back swipe from the trip.
                body = { trip ->
                    if (trip) {
                        Screen(TRIP, pages.hereTrip) {
                            pages.tripOpen = false
                            underTrip.tripClosed()
                        }
                    } else if (pages.tripOpen && underTrip.dropped) {
                        Text(PLACEHOLDER)
                    } else {
                        DisposableEffect(underTrip) {
                            underTrip.drawing()
                            onDispose { underTrip.left(pages.tripOpen, stores) }
                        }
                        val owner = remember(stores) { stores.ownerFor(SET) }
                        viewModel(viewModelStoreOwner = owner) { ListModel().also { listModels += it } }
                        Screen(LIST, null)
                    }
                },
            )
        }
        composeRule.waitForIdle()
    }

    private fun open(page: Page) {
        composeRule.runOnIdle { pages.show(page) }
        composeRule.waitForIdle()
    }

    private fun openTrip() {
        composeRule.runOnIdle {
            underTrip.tripOpened(stores)
            pages.tripOpen = true
        }
        composeRule.waitForIdle()
    }

    private fun startSwipe() {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(90f, 400f, 0.3f, BackEventCompat.EDGE_LEFT)) }
        composeRule.waitForIdle()
    }

    private fun letGo() {
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()
    }

    /** Back: it finishes a swipe under way, or is a plain Back (a back arrow, three-button navigation). */
    private fun back() {
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `back from any page returns to the page it was opened over, as it was left`() {
        show()
        composeRule.onNodeWithText(LIST).performClick()
        // Settings from a route page's menu: it used to close onto the list afresh.
        open(Page.Settings)
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        back()
        assertEquals(listOf("Settings"), closed)
        composeRule.onNodeWithText("$LIST's route page").assertIsDisplayed()
    }

    @Test
    fun `a page covered by another keeps its pages for Back`() {
        show()
        open(Page.Stations)
        composeRule.runOnIdle { stationId = "A" }
        composeRule.onNodeWithText("Station A").performClick()
        // About → Licenses over the station's route page, and Back.
        open(Page.Licenses)
        composeRule.onNodeWithText("Licenses").assertIsDisplayed()
        back()
        composeRule.onNodeWithText("Station A's route page").assertIsDisplayed()
    }

    @Test
    fun `the station flow keeps its search as it was left under a station, and each station's pages apart`() {
        show()
        open(Page.Stations)
        composeRule.onNodeWithText("Search").performClick()
        // A station opened from the search, and Back to it: the search as it was left (Codex on #1136).
        composeRule.runOnIdle { stationId = "A" }
        composeRule.onNodeWithText("Station A").performClick()
        composeRule.runOnIdle { stationId = null }
        composeRule.onNodeWithText("Search's route page").assertIsDisplayed()
        // The station closed is forgotten: opened again, it starts afresh.
        composeRule.runOnIdle { stationId = "A" }
        composeRule.onNodeWithText("Station A").assertIsDisplayed()
        assertEquals(setOf("search", "station:A"), stationFlowPages(search = true, stationId = "A"))
        assertEquals(setOf("station:A"), stationFlowPages(search = false, stationId = "A"))
    }

    @Test
    fun `a page closed opens afresh the next time`() {
        show()
        open(Page.Settings)
        composeRule.onNodeWithText("Settings").performClick()
        open(Page.Licenses)
        // Settings closed while covered: its pages go with it.
        composeRule.runOnIdle { pages.close(Page.Settings) }
        composeRule.waitForIdle()
        open(Page.Settings)
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `a new near-me generation lands on the list afresh, the pages over it kept`() {
        show()
        composeRule.onNodeWithText(LIST).performClick()
        open(Page.Settings)
        composeRule.onNodeWithText("Settings").performClick()
        // An arrived trip's Done, from its notification over Settings: the near-me view afresh, Settings as it was.
        composeRule.runOnIdle { pages.generation++ }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings's route page").assertIsDisplayed()
        back()
        back()
        composeRule.onNodeWithText(LIST).assertIsDisplayed()
    }

    @Test
    fun `the back gesture draws the page under it, which a swipe let go of leaves as it was`() {
        show()
        open(Page.Settings)
        startSwipe()
        // Drawn under the page being swiped, but not on top: nothing it would start for the rider starts.
        composeRule.onNodeWithText(LIST).assertExists()
        assertEquals(false, onTop[LIST])
        assertEquals(true, onTop["Settings"])
        letGo()
        composeRule.onNodeWithText(LIST).assertDoesNotExist()
        assertEquals(Page.Settings, pages.top)
        assertEquals(emptyList<String>(), closed)
        // Swiped all the way: the screen's own Back closes it.
        startSwipe()
        back()
        assertEquals(listOf("Settings"), closed)
        composeRule.onNodeWithText(LIST).assertIsDisplayed()
        assertEquals(true, onTop[LIST])
    }

    @Test
    fun `a page's own inner page takes Back first, with nothing drawn under it`() {
        show()
        open(Page.Settings)
        composeRule.onNodeWithText("Settings").performClick()
        startSwipe()
        composeRule.onNodeWithText(LIST).assertDoesNotExist()
        back()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        assertEquals(emptyList<String>(), closed)
    }

    @Test
    fun `a page drawn under a swipe doesn't take Back from the page over it`() {
        show()
        // The list's own route page open, its Back up, then Settings over it.
        composeRule.onNodeWithText(LIST).performClick()
        open(Page.Settings)
        startSwipe()
        letGo()
        back()
        assertEquals(listOf("Settings"), closed)
        composeRule.onNodeWithText("$LIST's route page").assertIsDisplayed()
    }

    @Test
    fun `a window over a page leaves it drawn and on top, and a page opened over the window returns to it`() {
        show()
        composeRule.onNodeWithText(LIST).performClick()
        // A route stop's details over the list's route page: a window drawn over the pages, not one of them.
        composeRule.runOnIdle { pages.open(Page.RouteStop) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("$LIST's route page").assertIsDisplayed()
        composeRule.onNodeWithText(Page.RouteStop.toString()).assertDoesNotExist()
        assertEquals(true, onTop[LIST])
        // Settings from the details' menu covers them; its Back returns to the page under them, as it was.
        open(Page.Settings)
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        back()
        assertEquals(listOf("Settings"), closed)
        assertEquals(listOf(Page.NearMe(0), Page.RouteStop), pages.stack.toList())
        composeRule.onNodeWithText("$LIST's route page").assertIsDisplayed()
        assertEquals(true, onTop[LIST])
    }

    @Test
    fun `a page opens over the one under it in a quarter of a second`() {
        show()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            pages.show(Page.Settings)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeBy(100)
        // Both drawn as it opens: the one going and the one coming.
        composeRule.onNodeWithText(LIST).assertExists()
        composeRule.onNodeWithText("Settings").assertExists()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithText(LIST).assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        assertTrue(onTop.getValue("Settings"))
        assertFalse(onTop.getValue(LIST))
    }

    @Test
    fun `the list is drawn as the trip from here opens over it, and its departures go once it's covered`() {
        show()
        assertEquals(1, listModels.size)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            underTrip.tripOpened(stores)
            pages.tripOpen = true
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeBy(100)
        // The list as it was, sliding away under the trip: nothing dropped or built again yet.
        composeRule.onNodeWithText(LIST).assertExists()
        composeRule.onNodeWithText(TRIP).assertExists()
        assertFalse(listModels.single().dropped)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithText(LIST).assertDoesNotExist()
        assertTrue(listModels.single().dropped)
        assertEquals(1, listModels.size)
    }

    @Test
    fun `the list of a new near-me generation is drawn as the trip from here opens over it`() {
        show()
        // A shared place's search, say, over the list: the old generation's page leaves after the new one's draws.
        composeRule.runOnIdle { pages.generation++ }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            underTrip.tripOpened(stores)
            pages.tripOpen = true
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText(LIST).assertExists()
        composeRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(300)
        assertTrue(listModels.last().dropped)
    }

    @Test
    fun `a back swipe from the trip from here draws a placeholder for the list, built again on Back as it was left`() {
        show()
        // A route page open on the list, then To….
        composeRule.onNodeWithText(LIST).performClick()
        openTrip()
        assertTrue(listModels.single().dropped)
        // Under the swipe: the list's place held, nothing built for a look.
        startSwipe()
        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        assertEquals(true, onTop[TRIP])
        assertEquals(1, listModels.size)
        letGo()
        composeRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()
        assertEquals(1, listModels.size)
        // The trip's Back: the list built again, its route page as it was left.
        back()
        assertEquals(listOf(TRIP), closed)
        composeRule.onNodeWithText("$LIST's route page").assertIsDisplayed()
        assertEquals(2, listModels.size)
        assertFalse(listModels.last().dropped)
    }

    @Test
    fun `the trip from here opened over another page drops the list at once, and its Back returns to that page`() {
        show()
        // A stop's To on Lines…, the list long covered.
        open(Page.Lines)
        openTrip()
        assertTrue(listModels.single().dropped)
        assertEquals(listOf(Page.NearMe(0), Page.Lines, Page.HereTrip(0)), pages.stack.toList())
        // Back: Lines…, not the list, and nothing built for it.
        back()
        composeRule.onNodeWithText(Page.Lines.toString()).assertIsDisplayed()
        assertEquals(1, listModels.size)
        // Lines…'s Back: the list, built again.
        back()
        composeRule.onNodeWithText(LIST).assertIsDisplayed()
        assertEquals(2, listModels.size)
    }

    private companion object {
        const val LIST = "List"
        const val TRIP = "Trip"
        const val PLACEHOLDER = "Placeholder"
        const val SET = "set"
    }
}
