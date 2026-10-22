package app.stopdash

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * page under the one being swiped, starting nothing it would start on top (`dev-docs/navigation3.md`).
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

    /**
     * A screen with a page of its own, opened by a tap on it, standing in for a saved route page: that page's
     * Back closes it, and the screen's Back closes the screen ([page], or none for the near-me view).
     */
    @Composable
    private fun Screen(name: String, page: Page?) {
        var inner by rememberSaveable { mutableStateOf(false) }
        val follow = rememberBackFollow()
        if (page != null) {
            RootBack(follow) {
                closed += name
                pages.close(page)
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
                body = { Screen(LIST, null) },
            )
        }
        composeRule.waitForIdle()
    }

    private fun open(page: Page) {
        composeRule.runOnIdle { pages.show(page) }
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

    private companion object {
        const val LIST = "List"
    }
}
