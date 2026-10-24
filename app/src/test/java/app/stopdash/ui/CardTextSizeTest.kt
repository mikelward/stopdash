package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.stopdash.domain.DEFAULT_FONT_SCALE
import app.stopdash.domain.FontSizeSettings
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The default text size is 105% (maintainer, 2026-10-24), but the list's group headings and departure cards
 * keep the size they had at 100% ([CardTextSize]): Settings, notes and a route page grow, the board doesn't.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class CardTextSizeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `at the default size the app's text is 105 percent and a card's is the system's own`() {
        var around = 0f
        var inCard = 0f
        var system = 0f
        composeRule.setContent {
            system = LocalDensity.current.fontScale
            StopDashTheme(fontSize = FontSizeState(FontSizeSettings(), onScaleSettled = {}, onPinchEnabledChange = {})) {
                around = LocalDensity.current.fontScale
                CardTextSize { inCard = LocalDensity.current.fontScale }
            }
        }
        composeRule.waitForIdle()
        assertEquals(system * DEFAULT_FONT_SCALE, around, 1e-6f)
        assertEquals(1.05f * system, around, 1e-6f)
        assertEquals(system, inCard, 1e-6f)
    }

    @Test
    fun `a chosen size moves a card's text in step with the rest`() {
        var around = 0f
        var inCard = 0f
        composeRule.setContent {
            StopDashTheme(fontSize = FontSizeState(FontSizeSettings(scale = 1.4f), onScaleSettled = {}, onPinchEnabledChange = {})) {
                around = LocalDensity.current.fontScale
                CardTextSize { inCard = LocalDensity.current.fontScale }
            }
        }
        composeRule.waitForIdle()
        assertEquals(around / DEFAULT_FONT_SCALE, inCard, 1e-6f)
    }

    @Test
    fun `a heading inside a card is reduced once, not twice`() {
        // A faraway place's card draws its stop's heading inside it (Codex, #1191).
        var system = 0f
        var nested = 0f
        composeRule.setContent {
            system = LocalDensity.current.fontScale
            StopDashTheme(fontSize = FontSizeState(FontSizeSettings(), onScaleSettled = {}, onPinchEnabledChange = {})) {
                CardTextSize { CardTextSize { nested = LocalDensity.current.fontScale } }
            }
        }
        composeRule.waitForIdle()
        assertEquals(system, nested, 1e-6f)
    }
}
