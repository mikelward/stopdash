package app.stopdash.wear

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.stopdash.domain.NoTimes
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a screen reader hears for a disrupted line on the watch's list. Stock lines only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w227dp-h227dp-round-watch-xhdpi")
class WatchHomeDisruptionTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-03T08:00:00Z")

    @Test
    fun `a disrupted line is heard as disrupted, with why it has no times`() {
        compose.setContent {
            WatchHomeScreen(
                TileFrame.Rows(
                    listOf(
                        TileLine.Disruption(TileRow("Victoria", "victoria", "tube", "VIC", "", "", false, false), "Severe Delays", alone = true),
                        TileLine.Disruption(
                            TileRow("Southern", "southern", "national-rail", "SOU", "", "", false, false),
                            "Part Suspended",
                            alone = true,
                            noTimes = NoTimes.NO_KEY,
                        ),
                    ),
                    ageMinutes = 0,
                    stale = false,
                    partial = false,
                ),
                now = now,
            )
        }
        compose.onNodeWithContentDescription("Disrupted: Severe Delays", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Disrupted: Part Suspended. No key", useUnmergedTree = true).assertExists()
    }
}
