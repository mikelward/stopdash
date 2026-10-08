package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dialog ticking National Rail opens with no key set (SPEC *Finding stops → Hiding a mode*): it
 * says a key is needed, where to get one, and offers Settings or the rows without times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RailKeyDialogScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun railKey_saysWhereToGetOne() {
        var added = false
        var shown = false
        composeRule.setContent {
            StopDashTheme {
                RailKeyDialog(onAddKey = { added = true }, onShowAnyway = { shown = true }, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("National Rail needs a key").assertIsDisplayed()
        composeRule.onNodeWithText("Rail Data Marketplace", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Get a key").assertIsDisplayed()

        if (capturing()) {
            composeRule.onNode(isDialog())
                .captureRoboImage(filePath = "src/test/snapshots/images/rail-key-dialog.png")
        }

        composeRule.onNodeWithText("Add key").performClick()
        composeRule.onNodeWithText("Show anyway").performClick()
        assertTrue(added)
        assertTrue(shown)
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
