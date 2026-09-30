package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A pill's spoken label names the service its code stands for, so the two never disagree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinePillLabelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tflsWestMidlandsTrainsLine_readsAndIsSaidAsLnr() {
        // TfL names the line after the parent company; the pill and TalkBack both say its brand.
        composeRule.setContent {
            StopDashTheme {
                LinePill(lineName = "West Midlands Trains", lineId = "west-midlands-trains", mode = "national-rail")
            }
        }
        composeRule.onNodeWithText("LNR").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("London Northwestern Railway").assertIsDisplayed()
    }
}
