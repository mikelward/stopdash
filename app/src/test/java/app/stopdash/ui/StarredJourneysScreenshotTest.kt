package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.StarredJourney
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The starred journeys in Settings (SPEC *Journeys*): the list with Remove, none, and a file this
 * build can't read. Public TfL interchanges only, never anyone's stops (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StarredJourneysScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val victoriaLine = StarredJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"),
        JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"),
        "victoria",
        lineName = "Victoria",
        mode = "tube",
    )
    private val northern = StarredJourney(
        JourneyEnd("940GZZLUEUS", "Euston"),
        JourneyEnd("940GZZLUWLO", "Waterloo"),
        "northern",
        lineName = "Northern",
        mode = "tube",
    )

    private fun show(state: StarredJourneysUi, onRemove: (StarredJourney) -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StarredJourneysScreen(state = state, onBack = {}, onRemove = onRemove)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_list_names_each_journey_and_removes_the_one_tapped() {
        val removed = mutableListOf<StarredJourney>()
        show(StarredJourneysUi(listOf(victoriaLine, northern)), onRemove = { removed += it })
        composeRule.onNodeWithText("Victoria ➔ King's Cross St. Pancras").assertIsDisplayed()
        composeRule.onNodeWithText("Northern").assertIsDisplayed()
        captureSnapshot("starred-journeys-list.png")
        composeRule.onNodeWithContentDescription("Remove Euston to Waterloo").performClick()
        assertEquals(listOf(northern), removed)
    }

    @Test
    fun none_says_how_to_star_one() {
        show(StarredJourneysUi(emptyList()))
        composeRule.onNodeWithText("Long-press a stop", substring = true).assertIsDisplayed()
        captureSnapshot("starred-journeys-empty.png")
    }

    @Test
    fun an_unreadable_file_is_said_not_shown_as_none_with_retry() {
        var retries = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StarredJourneysScreen(state = StarredJourneysUi(journeys = null), onBack = {}, onRemove = {}, onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithText("Can't read your starred journeys.").assertIsDisplayed()
        composeRule.onNodeWithTag("retryJourneys").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun a_failed_removal_says_so_until_dismissed() {
        var dismissed = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StarredJourneysScreen(
                    state = StarredJourneysUi(listOf(victoriaLine), writeFailed = true),
                    onBack = {},
                    onRemove = {},
                    onDismissWriteError = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText("Couldn't remove that journey").assertIsDisplayed()
        composeRule.onNodeWithTag("dismissJourneyWriteError").performClick()
        assertEquals(1, dismissed)
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        if (!capturing()) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
