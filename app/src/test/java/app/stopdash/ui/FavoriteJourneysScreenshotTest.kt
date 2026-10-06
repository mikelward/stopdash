package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.FavoriteJourney
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
 * The favorite journeys in Settings (SPEC *Journeys*): the list with Remove and Add, none, and a file
 * this build can't read; and a stop page's row offering the journey there. Public TfL interchanges only, never anyone's stops (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteJourneysScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val victoriaLine = FavoriteJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"),
        JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"),
        "victoria",
        lineName = "Victoria",
        mode = "tube",
    )
    private val northern = FavoriteJourney(
        JourneyEnd("940GZZLUEUS", "Euston"),
        JourneyEnd("940GZZLUWLO", "Waterloo"),
        "northern",
        lineName = "Northern",
        mode = "tube",
    )

    private fun show(state: FavoriteJourneysUi, onRemove: (FavoriteJourney) -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = state, onBack = {}, onRemove = onRemove)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_list_names_each_journey_and_removes_the_one_tapped() {
        val removed = mutableListOf<FavoriteJourney>()
        show(FavoriteJourneysUi(listOf(victoriaLine, northern)), onRemove = { removed += it })
        composeRule.onNodeWithText("Victoria ➔ King's Cross St. Pancras").assertIsDisplayed()
        composeRule.onNodeWithText("Northern").assertIsDisplayed()
        captureSnapshot("favorite-journeys-list.png")
        composeRule.onNodeWithContentDescription("Remove Euston to Waterloo").performClick()
        assertEquals(listOf(northern), removed)
    }

    @Test
    fun none_says_how_to_add_one() {
        show(FavoriteJourneysUi(emptyList()))
        composeRule.onNodeWithText("Tap Add", substring = true).assertIsDisplayed()
        captureSnapshot("favorite-journeys-empty.png")
    }

    @Test
    fun add_starts_picking_one() {
        var adds = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = FavoriteJourneysUi(emptyList()), onBack = {}, onRemove = {}, onAdd = { adds++ })
            }
        }
        composeRule.onNodeWithTag("addJourney").assertIsDisplayed().performClick()
        assertEquals(1, adds)
    }

    @Test
    fun a_stops_page_offers_the_journey_there_until_saved_then_its_removal() {
        var saved by mutableStateOf<Boolean?>(null)
        var toggles = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StationJourneyRow(StationJourneyState(victoriaLine, saved, failed = false) { toggles++ })
            }
        }
        composeRule.onNodeWithText("Victoria ➔ King's Cross St. Pancras").assertIsDisplayed()
        // Not read yet: the button waits rather than guess which way a tap goes.
        composeRule.onNodeWithTag("stationJourneyToggle").assertIsNotEnabled()
        saved = false
        composeRule.onNodeWithText("Favourite").assertIsDisplayed().performClick()
        assertEquals(1, toggles)
        saved = true
        composeRule.onNodeWithText("Remove favourite").assertIsDisplayed()
    }

    @Test
    fun a_stops_page_says_when_the_journey_didnt_save() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StationJourneyRow(StationJourneyState(victoriaLine, saved = false, failed = true) {})
            }
        }
        composeRule.onNodeWithText("Couldn't save that change").assertIsDisplayed()
    }

    @Test
    fun a_stops_page_still_loading_offers_the_journey_there() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalStationJourney provides StationJourneyState(victoriaLine, saved = false, failed = false) {}) {
                    StationPlaceholderScreen(
                        title = "King's Cross St. Pancras",
                        state = StationStopsViewModel.State.Loading,
                        onRetry = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Victoria ➔ King's Cross St. Pancras").assertIsDisplayed()
        composeRule.onNodeWithText("Favourite").assertIsDisplayed()
    }

    @Test
    fun a_stops_page_says_when_the_favorites_cant_be_read_with_retry() {
        var retries = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                StationJourneyRow(StationJourneyState(victoriaLine, saved = null, failed = false, unavailable = true, onRetry = { retries++ }) {})
            }
        }
        composeRule.onNodeWithText("Can't read your favourite journeys.").assertIsDisplayed()
        composeRule.onNodeWithTag("stationJourneyRetry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun a_tapped_route_stop_survives_being_saved_and_restored() {
        val scope = SaverScope { true }
        val withJourney = RouteStopOpen("940GZZLUKSX", "King's Cross St. Pancras", victoriaLine.copy(from = JourneyEnd("940GZZLUVIC", "Victoria", 51.5, -0.12, "HUBVIC")))
        val bare = RouteStopOpen("940GZZLUKSX", "King's Cross St. Pancras", null, hubId = "HUBKGX")
        for (open in listOf(withJourney, bare)) {
            val saved = with(RouteStopOpenSaver) { scope.save(open) }!!
            assertEquals(open, RouteStopOpenSaver.restore(saved))
        }
    }

    @Test
    fun a_route_stop_is_the_open_station_by_its_own_id_or_its_hubs() {
        val kingsCross = RouteStopOpen("940GZZLUKSX", "King's Cross St. Pancras", null, hubId = "HUBKGX")
        assertEquals(true, kingsCross.isOpen("940GZZLUKSX"))
        // A station page opened from search by its interchange.
        assertEquals(true, kingsCross.isOpen("HUBKGX"))
        assertEquals(false, kingsCross.isOpen("940GZZLUEUS"))
        assertEquals(false, kingsCross.isOpen(null))
        assertEquals(false, RouteStopOpen("940GZZLUEUS", "Euston", null).isOpen("HUBKGX"))
    }

    @Test
    fun an_unreadable_file_is_said_not_shown_as_none_with_retry() {
        var retries = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(state = FavoriteJourneysUi(journeys = null), onBack = {}, onRemove = {}, onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithText("Can't read your favourite journeys.").assertIsDisplayed()
        composeRule.onNodeWithTag("retryJourneys").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun a_failed_removal_says_so_until_dismissed() {
        var dismissed = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                FavoriteJourneysScreen(
                    state = FavoriteJourneysUi(listOf(victoriaLine), writeFailed = true),
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
