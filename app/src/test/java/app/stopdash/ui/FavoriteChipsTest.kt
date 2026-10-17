package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.TripDestination
import app.stopdash.ui.theme.StopDashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A place chip takes only its outline's height in the layout, so the row adds no blank space above and
 * below it, while its touch target stays 48dp tall: a tap in the 8dp beyond the outline still plans
 * the trip (maintainer, 2026-10-17). The coordinate is the synthetic stand-in (AGENTS.md *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteChipsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12), "Victoria")

    private val routed = mutableListOf<TripDestination.Place>()

    private fun show() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                // Room above and below, so a tap past the outline lands on nothing else.
                FavoriteChips(listOf(home), onRouteTo = { routed += it }, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
    }

    @Test
    fun `the row takes only its chips' outline height`() {
        show()
        composeRule.onNodeWithTag("favoriteChips").assertHeightIsEqualTo(32.dp)
    }

    @Test
    fun `a chip's touch target is still 48dp tall`() {
        show()
        composeRule.onNodeWithTag("favoriteChip-h").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun `a tap just past the outline still plans the trip`() {
        show()
        val chip = composeRule.onNodeWithTag("favoriteChip-h")
        chip.performTouchInput { click(Offset(center.x, -4.dp.toPx())) }
        chip.performTouchInput { click(Offset(center.x, height + 4.dp.toPx())) }
        assertEquals(listOf("Home", "Home"), routed.map { it.name })
    }

    @Test
    fun `a tap past the touch target doesn't`() {
        show()
        composeRule.onNodeWithTag("favoriteChip-h").performTouchInput { click(Offset(center.x, -12.dp.toPx())) }
        assertEquals(emptyList<TripDestination.Place>(), routed)
    }
}
