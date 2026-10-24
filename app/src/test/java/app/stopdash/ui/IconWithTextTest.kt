package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * A mode icon or star beside a name grows and shrinks with the chosen text size ([withText], SPEC *Display size*),
 * and inside a card keeps the size it has at the system's own text size, as the card's text does ([CardTextSize]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class IconWithTextTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `an inline icon scales with the chosen text size, and a card's stays at the system size`() {
        var system = 0f
        var around = Dp.Unspecified
        var inCard = Dp.Unspecified
        composeRule.setContent {
            system = LocalDensity.current.fontScale
            StopDashTheme(fontSize = FontSizeState(FontSizeSettings(scale = 1.4f), onScaleSettled = {}, onPinchEnabledChange = {})) {
                around = 20.dp.withText()
                CardTextSize { inCard = 20.dp.withText() }
            }
        }
        composeRule.waitForIdle()
        // Android scales large sp sizes less than linearly, so the icon grows as 20sp text would, not by 1.4 exactly.
        assertTrue(around.value > 20f * system)
        assertTrue(inCard.value > 20f * system && inCard.value < around.value)
    }

    @Test
    fun `at the system's own size inside a card an icon is its plain size`() {
        var system = 0f
        var inCard = Dp.Unspecified
        composeRule.setContent {
            system = LocalDensity.current.fontScale
            StopDashTheme(fontSize = FontSizeState(FontSizeSettings(), onScaleSettled = {}, onPinchEnabledChange = {})) {
                CardTextSize { inCard = 20.dp.withText() }
            }
        }
        composeRule.waitForIdle()
        assertEquals(20f * system, inCard.value, 1e-3f)
    }
}
