package app.stopdash

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NearbyArea] keeps what its body saved (an open route page) under any overlay, and what each overlay
 * saved under another opened over it, so Back returns to the page it was opened from (SPEC *Back*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class NearbyAreaTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var generation by mutableStateOf(0)

    // The overlays open, the last of them showing: none shows the body.
    private var open by mutableStateOf(listOf<String>())

    /** A page with a page of its own, opened by a tap on it, standing in for a saved route page. */
    @Composable
    private fun Page(name: String) {
        var inner by rememberSaveable { mutableStateOf(false) }
        Text(if (inner) "$name's route page" else name, Modifier.clickable { inner = true })
    }

    private fun show() {
        composeRule.setContent {
            NearbyArea(
                overlayOpen = open.isNotEmpty(),
                aboveOverlay = {},
                overlayContent = { Page(open.last()) },
                body = { Page("List") },
                bodyGeneration = generation,
                overlayPage = open.lastOrNull().orEmpty(),
                openOverlayPages = open.toSet(),
            )
        }
    }

    @Test
    fun `back from any overlay returns to the page it was opened over`() {
        show()
        composeRule.onNodeWithText("List").performClick()
        // Settings from a route page's menu: it used to close onto the list afresh.
        open = listOf("Settings")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        open = emptyList()
        composeRule.onNodeWithText("List's route page").assertIsDisplayed()
    }

    @Test
    fun `an overlay covered by another keeps its pages for Back`() {
        show()
        open = listOf("Station")
        composeRule.onNodeWithText("Station").performClick()
        composeRule.onNodeWithText("Station's route page").assertIsDisplayed()
        // About → Licenses over the station's route page, and Back.
        open = listOf("Station", "Licenses")
        composeRule.onNodeWithText("Licenses").assertIsDisplayed()
        open = listOf("Station")
        composeRule.onNodeWithText("Station's route page").assertIsDisplayed()
    }

    @Test
    fun `an overlay closed opens afresh the next time`() {
        show()
        open = listOf("Station")
        composeRule.onNodeWithText("Station").performClick()
        open = listOf("Station", "Licenses")
        composeRule.waitForIdle()
        // The station closed while covered: its pages go with it.
        open = listOf("Licenses")
        composeRule.waitForIdle()
        open = listOf("Licenses", "Station")
        composeRule.onNodeWithText("Station").assertIsDisplayed()
    }

    @Test
    fun `an arrived trip's Done lands on the list, even over a kept route page`() {
        show()
        composeRule.onNodeWithText("List").performClick()
        open = listOf("On the way")
        composeRule.waitForIdle()
        // Done: the station and the trip close together, the main view's state dropped with them.
        generation++
        open = emptyList()
        composeRule.onNodeWithText("List").assertIsDisplayed()
    }

    @Test
    fun `each station flow's page is kept by its station, under its search`() {
        assertEquals("STATIONS:940GZZLUKSX", overlayPage(TopOverlay.STATIONS, "940GZZLUKSX"))
        assertEquals("STATIONS", overlayPage(TopOverlay.STATIONS, null))
        assertEquals("SETTINGS", overlayPage(TopOverlay.SETTINGS, "940GZZLUKSX"))
        assertEquals(
            setOf("SETTINGS", "LINES", "STATIONS", "STATIONS:940GZZLUKSX"),
            openOverlayPages(
                licenses = false, onTheWay = false, favoritePlaces = false, favoriteJourneys = false,
                settings = true, lines = true, stationSearch = true, stationId = "940GZZLUKSX",
            ),
        )
        assertEquals(
            setOf("LICENSES", "ON_THE_WAY", "FAVORITE_PLACES", "FAVORITE_JOURNEYS"),
            openOverlayPages(
                licenses = true, onTheWay = true, favoritePlaces = true, favoriteJourneys = true,
                settings = false, lines = false, stationSearch = false, stationId = null,
            ),
        )
    }
}
