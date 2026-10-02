package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bug-report consent screen (SPEC *Privacy*): it must state plainly that the report shares the
 * **exact location** before anything leaves the device, and it must carry the "don't ask again"
 * choice back to the caller so the opt-out can be persisted. The composable is pure (no I/O, no
 * user data), so it renders under Robolectric with nothing wired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BugReportConsentDialogScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun consent_spellsOutWhatIsShared() {
        composeRule.setContent {
            StopDashTheme {
                BugReportConsentDialog(onConfirm = {}, onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        // The honesty point: the location and the screenshot are named, not hidden.
        composeRule.onNodeWithText("your exact location", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("a screenshot of this screen", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()

        if (capturing()) {
            composeRule.onNode(isDialog())
                .captureRoboImage(filePath = "src/test/snapshots/images/bug-report-consent.png")
        }
    }

    @Test
    fun confirming_withoutTicking_reportsNoOptOut() {
        var latest: Boolean? = null
        composeRule.setContent {
            StopDashTheme {
                BugReportConsentDialog(onConfirm = { latest = it }, onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.runOnIdle { assertEquals(false, latest) }
    }

    @Test
    fun tickingDontAskAgain_thenConfirming_reportsTheOptOut() {
        var latest: Boolean? = null
        composeRule.setContent {
            StopDashTheme {
                BugReportConsentDialog(onConfirm = { latest = it }, onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_DONT_ASK_TAG).performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.runOnIdle { assertEquals(true, latest) }
    }

    @Test
    fun cancel_dismissesWithoutSharing() {
        var dismissed = false
        var confirmed = false
        composeRule.setContent {
            StopDashTheme {
                BugReportConsentDialog(onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertTrue(dismissed)
            assertTrue(!confirmed)
        }
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
