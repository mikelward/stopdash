package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.FontSizeSettings
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
 * saved, once, however the drag ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class TextSizeSliderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val settled = mutableListOf<Float>()
    private val state = FontSizeState(FontSizeSettings(), onScaleSettled = { settled += it }, onPinchEnabledChange = {})

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
}
