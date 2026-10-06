package app.stopdash.wear

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.stopdash.data.WatchTrip
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tap on the trip's ongoing activity brings the open app back to the trip. Stock stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w227dp-h227dp-round-watch-xhdpi")
class WatchHomeOpenTripTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private val trip = WatchTrip(
        title = "Walk to King's Cross St. Pancras",
        steps = listOf(WatchTrip.Step("Walk to King's Cross St. Pancras", walk = true, mode = "walking")),
        current = 0,
        sentAt = now.toEpochMilli(),
    )
    private val lines = (1..20).map { TileLine.Departure(TileRow("Victoria", "victoria", "tube", "VIC", "Brixton $it", "$it min", starred = false, stale = false)) }

    @Test
    fun `opening the trip scrolls back to it`() {
        val opened = mutableIntStateOf(0)
        compose.setContent {
            WatchHomeScreen(TileFrame.Rows(lines, ageMinutes = 0, stale = false, partial = false), trip = ShownTrip(trip, stale = false), now = now, openTrip = opened.intValue)
        }
        repeat(4) { compose.onRoot().performTouchInput { swipeUp() } }
        compose.onNodeWithText("Walk to King's Cross St. Pancras").assertDoesNotExist()
        opened.intValue++
        compose.onNodeWithText("Walk to King's Cross St. Pancras").assertIsDisplayed()
    }
}
