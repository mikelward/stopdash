package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.FontSizeSettings
import app.stopdash.domain.MAX_FONT_SCALE
import app.stopdash.domain.MIN_FONT_SCALE
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settings text-size slider driven by touch (SPEC *Display size*): where a drag lands is what is
 * saved, once, however the drag ends; its minus and plus move the size one percent a tap and save it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class TextSizeSliderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val settled = mutableListOf<Float>()
    // At the system's own size, 100%, so a step reads as whole percentages from it.
    private val state = FontSizeState(FontSizeSettings(scale = 1.0f), onScaleSettled = { settled += it }, onPinchEnabledChange = {})

    private fun show(fontSize: FontSizeState = state) {
        composeRule.setContent {
            StopDashTheme(fontSize = fontSize) {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
    }

    /** Down on the slider and dragged right, a step at a time, the finger left down. */
    private fun dragRight() {
        val slider = composeRule.onNodeWithTag("textSizeSlider")
        slider.performTouchInput { down(center) }
        // Past the touch slop, then on: each step its own frame, as a finger's are.
        repeat(40) {
            slider.performTouchInput { moveBy(Offset(4f, 0f)) }
            composeRule.waitForIdle()
        }
    }

    @Test
    fun releasingSavesTheSizeTheSliderLastShowed() {
        composeRule.setContent {
            StopDashTheme(fontSize = state) {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        dragRight()
        val shown = state.scale
        assertTrue("the drag moved the size", shown > 1f)
        composeRule.onNodeWithTag("textSizeSlider").performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(listOf(shown), settled)
        assertEquals(shown, state.scale, 0f)
    }

    @Test
    fun leavingSettingsMidDragSavesWhereTheFingerWas() {
        var open by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme(fontSize = state) {
                if (open) SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        dragRight()
        val shown = state.scale
        assertTrue("the drag moved the size", shown > 1f)

        // Settings goes (Back, a notification opening a page) with the finger still on the slider:
        // the slider never hears a release.
        open = false
        composeRule.waitForIdle()

        assertEquals("saved once, where the drag was", listOf(shown), settled)
        // And the drag is over: a size arriving from the store now applies, not held for a release
        // that will never come.
        composeRule.runOnIdle { state.onPersisted(FontSizeSettings(scale = 0.9f)) }
        assertEquals(0.9f, state.scale, 0f)
    }

    @Test
    fun plusAndMinusMoveTheSizeOnePercentAndSaveIt() {
        show()
        composeRule.onNodeWithContentDescription("Larger text").performClick()
        composeRule.runOnIdle { assertEquals(1.01f, state.scale, 0f) }
        composeRule.onNodeWithText("101%").assertExists()
        composeRule.onNodeWithTag("textSizeLarger").performClick()
        composeRule.onNodeWithContentDescription("Smaller text").performClick()
        composeRule.onNodeWithTag("textSizeSmaller").performClick()
        composeRule.runOnIdle {
            assertEquals(1.0f, state.scale, 0f)
            // Each tap is saved, not only previewed.
            assertEquals(listOf(1.01f, 1.02f, 1.01f, 1.0f), settled)
        }
    }

    @Test
    fun aStepFromBetweenTwoPercentagesLandsOnTheNextWholeOne() {
        // A pinch left the size at 115.3%, shown as 115%.
        val between = FontSizeState(FontSizeSettings(scale = 1.153f), onScaleSettled = { settled += it }, onPinchEnabledChange = {})
        show(between)
        composeRule.onNodeWithTag("textSizeLarger").performClick()
        composeRule.runOnIdle { assertEquals(listOf(1.16f), settled) }
    }

    @Test
    fun theButtonsAreDisabledAtTheirEndsOfTheRange() {
        val largest = FontSizeState(FontSizeSettings(scale = MAX_FONT_SCALE), onScaleSettled = { settled += it }, onPinchEnabledChange = {})
        show(largest)
        composeRule.onNodeWithTag("textSizeLarger").assertIsNotEnabled()
        composeRule.onNodeWithTag("textSizeSmaller").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("textSizeLarger").assertIsEnabled()
        composeRule.runOnIdle { assertEquals(listOf(1.59f), settled) }
    }

    @Test
    fun minusIsDisabledAtTheSmallestSize() {
        val smallest = FontSizeState(FontSizeSettings(scale = MIN_FONT_SCALE), onScaleSettled = { settled += it }, onPinchEnabledChange = {})
        show(smallest)
        composeRule.onNodeWithTag("textSizeSmaller").assertIsNotEnabled()
        composeRule.onNodeWithTag("textSizeLarger").assertIsEnabled()
    }
}
