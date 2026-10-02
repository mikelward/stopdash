package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The About entry point on the location gate: reachable in every gate state (including a
 * permanently-denied one, where departures never resolve), so the app version and the
 * open-source license attribution can't be stranded behind the permission wall. Opens the same
 * About dialog as the departures top bar, whose action invokes `onOpenLicenses`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationGateAboutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun about_isReachableAndOpensLicenses_whenPermanentlyDenied() {
        var licensesOpened = false
        composeRule.setContent {
            StopDashTheme {
                LocationGate(
                    state = NearbyStopsViewModel.State.PermissionRequired,
                    onAllow = {},
                    onRetry = {},
                    onOpenSettings = {},
                    permanentlyDenied = true,
                    onOpenLicenses = { licensesOpened = true },
                )
            }
        }

        // Even with the permission permanently denied — the state that otherwise strands the
        // user — About is in the overflow and opens the dialog, whose one action opens the licenses.
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("Open source licenses").assertIsDisplayed()
        composeRule.onNodeWithText("Open source licenses").performClick()
        composeRule.runOnIdle { assertTrue(licensesOpened) }
    }
}
