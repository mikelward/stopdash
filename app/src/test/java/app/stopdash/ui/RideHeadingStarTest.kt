package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * A favorite ride's stop heading at a large text size: its names shorten to leave the star its grown width, so
 * the star is drawn whole inside the heading (Codex, #1197).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class RideHeadingStarTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a long heading at a large text size leaves the star its whole width`() {
        var starSize = Dp.Unspecified
        composeRule.setContent {
            StopDashTheme(fontSize = FontSizeState(FontSizeSettings(scale = 1.6f), onScaleSettled = {}, onPinchEnabledChange = {})) {
                starSize = 16.dp.withText()
                Box(Modifier.width(240.dp).testTag("heading")) {
                    RideStopTitle(
                        stop = listOf("King's Cross St. Pancras Underground Station", "King's Cross St. Pancras"),
                        qualifier = listOf(" – Platform 7", " – Plat 7"),
                        destination = listOf("Heathrow Terminals 2 & 3 Underground Station", "Heathrow T2 & 3"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black,
                        marker = { RideHeadingStar(Color.Black) },
                        markerWidth = rideHeadingStarWidth(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val heading = composeRule.onNodeWithTag("heading").getBoundsInRoot()
        val star = composeRule.onNodeWithTag("rideHeadingStar", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(starSize > 16.dp)
        assertEquals(starSize.value, (star.right - star.left).value, 0.5f)
        assertTrue(star.right <= heading.right)
    }
}
