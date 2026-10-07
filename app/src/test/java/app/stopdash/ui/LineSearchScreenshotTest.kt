package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.performScrollTo
import app.stopdash.domain.LineRef
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
 * *Lines…* (SPEC *Finding a line*): the search before a query (the recent lines, and none yet), with
 * matches, with none, and when TfL's lines couldn't be loaded. UI only, so it renders with no network.
 * Public TfL lines only, no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineSearchScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val victoria = LineRef("victoria", "Victoria", "tube")
    private val elizabeth = LineRef("elizabeth", "Elizabeth line", "elizabeth-line")
    private fun bus(n: String) = LineRef(n.lowercase(), n, "bus")
    private val ready = LinesViewModel.Catalog.Ready(listOf(victoria, elizabeth, bus("29"), bus("299")))

    private fun show(state: LinesViewModel.State, onOpen: (LineRef) -> Unit = {}, onRetry: () -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme {
                LineSearchScreen(state = state, onQueryChange = {}, onOpenLine = onOpen, onRetry = onRetry, onBack = {}, autoFocus = false)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun line_search_recent() {
        var opened: LineRef? = null
        show(LinesViewModel.State(recent = listOf(bus("299"), victoria), catalog = ready), onOpen = { opened = it })
        composeRule.onNodeWithText("Recent").assertIsDisplayed()
        composeRule.onNodeWithText("Tube").assertIsDisplayed()
        captureSnapshot("line-search-recent.png")
        composeRule.onNodeWithText("Victoria").performClick()
        assertEquals(victoria, opened)
    }

    @Test
    fun line_search_no_recent_lines_yet() {
        show(LinesViewModel.State(recent = emptyList(), catalog = LinesViewModel.Catalog.Loading))
        composeRule.onNodeWithText("Type a line name or route number").assertIsDisplayed()
        captureSnapshot("line-search-empty.png")
    }

    @Test
    fun line_search_matches() {
        show(LinesViewModel.State(query = "29", recent = emptyList(), catalog = ready, matches = listOf(bus("29"), bus("299")), matchesFor = "29"))
        composeRule.onNodeWithText("299").assertIsDisplayed()
        captureSnapshot("line-search-matches.png")
    }

    @Test
    fun line_search_no_matches() {
        show(LinesViewModel.State(query = "zz", recent = emptyList(), catalog = ready, matches = emptyList(), matchesFor = "zz"))
        composeRule.onNodeWithText("No lines match").assertIsDisplayed()
    }

    @Test
    fun line_search_failed() {
        var retried = false
        show(LinesViewModel.State(query = "29", recent = emptyList(), catalog = LinesViewModel.Catalog.Failed), onRetry = { retried = true })
        composeRule.onNodeWithText("Couldn't load the lines").assertIsDisplayed()
        captureSnapshot("line-search-failed.png")
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun a_failed_list_says_so_before_anything_is_typed_with_the_recent_lines_below() {
        var retried = false
        show(LinesViewModel.State(query = "", recent = listOf(victoria), catalog = LinesViewModel.Catalog.Failed), onRetry = { retried = true })
        composeRule.onNodeWithText("Couldn't load the lines").assertIsDisplayed()
        composeRule.onNodeWithText("Victoria").assertIsDisplayed()
        composeRule.onNodeWithText("Type a line name or route number").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun line_stop_details() {
        var from = 0
        var to = 0
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Oxford Circus", distance = "350 m", onFrom = { from++ }, onTo = { to++ }, onBack = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Oxford Circus (350 m)").assertIsDisplayed()
        captureSnapshot("line-stop-details.png")
        composeRule.onNodeWithText("From").performClick()
        composeRule.onNodeWithText("To").performClick()
        assertEquals(1, from)
        assertEquals(1, to)
    }

    @Test
    fun from_and_to_stay_reachable_under_a_long_name_on_a_short_screen() {
        // A short window and a long name: the page scrolls to its buttons rather than clip them (Codex on #659).
        var from = 0
        composeRule.setContent {
            StopDashTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(180.dp)) {
                    LineStopPage(
                        name = List(12) { "King's Cross St. Pancras" }.joinToString(" "),
                        distance = "350 m",
                        onFrom = { from++ },
                        onTo = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("From").performScrollTo().performClick()
        assertEquals(1, from)
    }

    @Test
    fun a_stop_with_no_distance_and_no_to_shows_its_name_and_from_alone() {
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Oxford Circus", distance = null, onFrom = {}, onTo = null, onBack = {})
            }
        }
        composeRule.onNodeWithText("Oxford Circus").assertIsDisplayed()
        composeRule.onNodeWithText("From").assertIsDisplayed()
        composeRule.onNodeWithText("To").assertDoesNotExist()
    }

    @Test
    fun the_overflow_item_opens_lines_and_closes_the_menu() {
        var opened = false
        var closed = false
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalOpenLines provides { opened = true }) {
                    Column { LinesMenuItem(close = { closed = true }) }
                }
            }
        }
        composeRule.onNodeWithText("Lines…").performClick()
        assertTrue(opened)
        assertTrue(closed)
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

    // Recorded only when CI asks: a plain `./gradlew test` renders and asserts without rewriting PNGs.
    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
