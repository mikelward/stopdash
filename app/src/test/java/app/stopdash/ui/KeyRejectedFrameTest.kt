package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app-wide bar for a key TfL refused (SPEC D7): shown over whatever screen is up, with Clear
 * key; nothing while the key is fine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyRejectedFrameTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rejected_showsBarOverTheScreen_andClearsTheKey() {
        var cleared = 0
        composeRule.setContent {
            StopDashTheme {
                KeyRejectedFrame(rejected = true, onClearKey = { cleared++ }) { Text("A screen") }
            }
        }

        composeRule.onNodeWithText("A screen").assertExists()
        composeRule.onNodeWithText("TfL rejected your API key").assertExists()
        composeRule.onNodeWithText("Clear key").performClick()
        composeRule.runOnIdle { assertEquals(1, cleared) }
    }

    @Test
    fun saveFailed_saysSo_andTriesAgain() {
        // A Clear that didn't save would bring the refused key back on a restart: said, not hidden.
        var retries = 0
        composeRule.setContent {
            StopDashTheme {
                KeyRejectedFrame(rejected = false, onClearKey = {}, saveFailed = true, onRetrySave = { retries++ }) { Text("A screen") }
            }
        }

        composeRule.onNodeWithText("Couldn't save your API key change; it resets when Routemo restarts").assertExists()
        composeRule.onNodeWithText("Clear key").assertDoesNotExist()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun notRejected_showsNoBar() {
        composeRule.setContent {
            StopDashTheme {
                KeyRejectedFrame(rejected = false, onClearKey = {}) { Text("A screen") }
            }
        }

        composeRule.onNodeWithText("A screen").assertExists()
        composeRule.onNodeWithText("TfL rejected your API key").assertDoesNotExist()
        composeRule.onNodeWithText("Clear key").assertDoesNotExist()
    }
}
