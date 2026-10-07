package app.stopdash

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NearbyArea] keeps what its body saved (an open route page) under a stop opened from that page, so
 * Back returns there, and drops it under any other overlay, which closes onto the list afresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class NearbyAreaTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var generation by mutableStateOf(0)

    private fun showWith(keepBody: Boolean): (Boolean) -> Unit {
        var overlayOpen by mutableStateOf(false)
        composeRule.setContent {
            NearbyArea(
                overlayOpen = overlayOpen,
                aboveOverlay = {},
                overlayContent = { Text("Stop page") },
                body = {
                    // Stands in for the main screen's saved open route page.
                    var routeOpen by rememberSaveable { mutableStateOf(false) }
                    Text(if (routeOpen) "Route page" else "List", Modifier.clickable { routeOpen = true })
                },
                keepBody = keepBody,
                bodyGeneration = generation,
            )
        }
        composeRule.onNodeWithText("List").performClick()
        composeRule.onNodeWithText("Route page").assertIsDisplayed()
        return { open -> overlayOpen = open }
    }

    @Test
    fun `back from a stop opened from a route page returns to that page`() {
        val setOverlay = showWith(keepBody = true)
        setOverlay(true)
        composeRule.onNodeWithText("Stop page").assertIsDisplayed()
        setOverlay(false)
        composeRule.onNodeWithText("Route page").assertIsDisplayed()
    }

    @Test
    fun `any other overlay closes onto the list afresh`() {
        val setOverlay = showWith(keepBody = false)
        setOverlay(true)
        composeRule.waitForIdle()
        setOverlay(false)
        composeRule.onNodeWithText("List").assertIsDisplayed()
    }

    @Test
    fun `an arrived trip's Done lands on the list, even over a kept route page`() {
        val setOverlay = showWith(keepBody = true)
        setOverlay(true)
        composeRule.waitForIdle()
        // Done: the station and the trip close together, the main view's state dropped with them.
        generation++
        setOverlay(false)
        composeRule.onNodeWithText("List").assertIsDisplayed()
    }
}
