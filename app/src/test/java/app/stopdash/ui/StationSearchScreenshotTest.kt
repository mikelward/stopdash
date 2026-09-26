package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.StationMatch
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Find a station" (SPEC *Finding stops*): the search with matches, before a query (with and
 * without the user's own stops, and as the To… destination search), and after a failure, plus a station page still loading its stops. UI-only, so it renders with no network.
 * Public station names only, no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StationSearchScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val matches = listOf(
        StationMatch("HUBKGX", "King's Cross St. Pancras", listOf("tube", "national-rail", "bus")),
        StationMatch("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube")),
        StationMatch("490G00000001", "King's Cross Station", listOf("bus")),
    )

    private fun show(state: StationSearchViewModel.State, onOpen: (StationMatch) -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = state,
                    onQueryChange = {},
                    onOpenStation = onOpen,
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun station_search_matches() {
        var opened: StationMatch? = null
        show(
            StationSearchViewModel.State(query = "kings", result = StationSearchViewModel.Result.Matches(matches)),
            onOpen = { opened = it },
        )
        composeRule.onNodeWithText("Tube · National Rail · Bus").assertIsDisplayed()
        captureSnapshot("station-search-matches.png")
        composeRule.onNodeWithText("King's Cross Station").performClick()
        assertEquals("490G00000001", opened?.id)
    }

    @Test
    fun station_search_prompt() {
        show(StationSearchViewModel.State(yoursRead = true))
        composeRule.onNodeWithText("Type a name to search").assertIsDisplayed()
        captureSnapshot("station-search-prompt.png")
    }

    @Test
    fun station_search_to() {
        composeRule.setContent {
            StopDashTheme {
                StationSearchScreen(
                    state = StationSearchViewModel.State(yoursRead = true),
                    onQueryChange = {},
                    onOpenStation = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                    hint = "To station or stop",
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("To station or stop").assertIsDisplayed()
        captureSnapshot("station-search-to.png")
    }

    @Test
    fun station_search_yours() {
        var opened: StationMatch? = null
        show(
            StationSearchViewModel.State(
                favorites = listOf(StationMatch("490G00000001", "King's Cross Station", listOf("bus"))),
                recent = listOf(StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))),
                yoursRead = true,
            ),
            onOpen = { opened = it },
        )
        composeRule.onNodeWithText("Starred").assertIsDisplayed()
        composeRule.onNodeWithText("Recent").assertIsDisplayed()
        // By last use: the recent picks head the list, the starred stops not picked lately after.
        val recentTop = composeRule.onNodeWithText("Recent").fetchSemanticsNode().boundsInRoot.top
        assertTrue(recentTop < composeRule.onNodeWithText("Starred").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(recentTop < composeRule.onNodeWithText("King's Cross Station").fetchSemanticsNode().boundsInRoot.top)
        captureSnapshot("station-search-yours.png")
        composeRule.onNodeWithText("Oxford Circus").performClick()
        assertEquals("940GZZLUOXC", opened?.id)
    }

    @Test
    fun station_search_failed() {
        show(
            StationSearchViewModel.State(
                query = "kings",
                result = StationSearchViewModel.Result.Failed(DeparturesUiState.Error.Kind.OFFLINE),
            ),
        )
        composeRule.onNodeWithText("You're offline").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        captureSnapshot("station-search-failed.png")
    }

    @Test
    fun station_loading() {
        composeRule.setContent {
            StopDashTheme {
                StationPlaceholderScreen(
                    title = "King's Cross St. Pancras",
                    state = StationStopsViewModel.State.Loading,
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("King's Cross St. Pancras").assertIsDisplayed()
        captureSnapshot("station-loading.png")
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
