package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
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
    fun line_stop_access() {
        // Oxford Circus, a public interchange, with its zone and its step-free line: a lift it needs is out,
        // the longest of that line's labels.
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Oxford Circus",
                    distance = "350 m",
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    zone = "1",
                    cueSlot = true,
                    access = app.stopdash.domain.StopAccess(app.stopdash.domain.StepFreeLevel.NONE, liftOut = true, byLift = true),
                    accessSlot = true,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Not step-free: a lift is out").assertIsDisplayed()
        captureSnapshot("line-stop-access.png")
    }

    @Test
    fun line_stop_departures() {
        // Oxford Circus, a public interchange, opened from the Victoria line, with made-up times: the
        // Victoria line's platforms first, then the Central and Bakerloo lines under "Also here".
        val now = java.time.Instant.parse("2026-10-07T09:00:00Z")
        fun train(line: String, name: String, destination: String, platform: String, minutes: Long) =
            app.stopdash.domain.Departure(line, name, "outbound", destination, platform, now.plusSeconds(minutes * 60), "tube")
        val state = DeparturesUiState.Loaded(
            stops = listOf(
                app.stopdash.domain.StopArrivals(
                    "940GZZLUOXC",
                    "Oxford Circus",
                    listOf(
                        train("victoria", "Victoria", "Walthamstow Central", "Northbound - Platform 5", 2),
                        train("victoria", "Victoria", "Walthamstow Central", "Northbound - Platform 5", 5),
                        train("victoria", "Victoria", "Brixton", "Southbound - Platform 6", 1),
                        train("victoria", "Victoria", "Brixton", "Southbound - Platform 6", 4),
                        train("central", "Central", "Epping", "Eastbound - Platform 1", 3),
                        train("central", "Central", "Ealing Broadway", "Westbound - Platform 2", 2),
                        train("bakerloo", "Bakerloo", "Elephant & Castle", "Southbound - Platform 4", 6),
                    ),
                    fetchedAt = now,
                ),
            ),
            fetchedAt = now,
        )
        val departures = StopDepartures(state, now, onRefresh = {})
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides kotlinx.coroutines.Dispatchers.Unconfined) {
                    LineStopPage(
                        name = "Oxford Circus",
                        distance = "350 m",
                        onFrom = {},
                        onTo = {},
                        onBack = {},
                        lineName = "Victoria",
                        departures = departures,
                        view = rememberStopBoard(departures, "victoria"),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Brixton").assertIsDisplayed()
        composeRule.onNodeWithText("Also here").assertExists()
        captureSnapshot("line-stop-departures.png")
    }

    @Test
    fun line_stop_links() {
        // King's Cross St. Pancras tube station, a public interchange: its lines as pills, the interchange's
        // rail stations, and Euston a short walk away, each opening what it names.
        val links = app.stopdash.domain.StopLinks(
            lines = listOf(
                app.stopdash.domain.LineRef("circle", "Circle", "tube"),
                app.stopdash.domain.LineRef("hammersmith-city", "Hammersmith & City", "tube"),
                app.stopdash.domain.LineRef("metropolitan", "Metropolitan", "tube"),
                app.stopdash.domain.LineRef("northern", "Northern", "tube"),
                app.stopdash.domain.LineRef("piccadilly", "Piccadilly", "tube"),
                app.stopdash.domain.LineRef("victoria", "Victoria", "tube"),
            ),
            sameHub = listOf(
                app.stopdash.domain.NearStation("910GKNGX", "London King's Cross", 20.0),
                app.stopdash.domain.NearStation("910GSTPX", "London St Pancras International", 300.0),
            ),
            nearby = listOf(app.stopdash.domain.NearStation("940GZZLUEUS", "Euston", 720.0, setOf("northern", "victoria"))),
        )
        val lines = mutableListOf<String>()
        val stations = mutableListOf<String>()
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "King's Cross St. Pancras",
                    distance = "350 m",
                    onFrom = {},
                    onTo = {},
                    onBack = {},
                    lineName = "Victoria",
                    links = links,
                    onOpenLine = { lines += it.id },
                    onOpenStation = { stations += it.id },
                )
            }
        }
        composeRule.waitForIdle()
        captureSnapshot("line-stop-links.png")
        composeRule.onNodeWithText("Same interchange").assertIsDisplayed()
        composeRule.onNodeWithTag("lineStopLine:northern").performClick()
        composeRule.onNodeWithTag("lineStopPage").performScrollToNode(hasText("Euston (0.7 km)"))
        composeRule.onNodeWithText("Euston (0.7 km)").performClick()
        composeRule.onNodeWithText("London King's Cross").performClick()
        assertEquals(listOf("northern"), lines)
        assertEquals(listOf("940GZZLUEUS", "910GKNGX"), stations)
    }

    @Test
    fun a_board_still_to_start_says_it_is_loading() {
        // A station opened off the line waits on its own lines before its board starts (Codex on #664).
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Euston", distance = null, onFrom = {}, onTo = null, onBack = {}, lineName = null, boardPending = true)
            }
        }
        composeRule.onNodeWithText("Loading departures…").assertIsDisplayed()
    }

    @Test
    fun the_star_adds_the_stop_as_a_favorite_place_once_it_is_placed() {
        var ready by mutableStateOf(false)
        var favorited = 0
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "Euston", distance = null, onFrom = {}, onTo = null, onBack = {}, actionsReady = ready, onFavorite = { favorited++ })
            }
        }
        // Waits on the stop's links, which carry where it is.
        composeRule.onNodeWithTag("lineStopFavorite").assertIsNotEnabled()
        ready = true
        composeRule.onNodeWithContentDescription("Add to favourite places").performClick()
        assertEquals(1, favorited)
    }

    @Test
    fun from_and_to_wait_for_what_they_open() {
        // Until the links are in, From and To can't open one of a station's ids alone (Codex on #664).
        var ready by androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(name = "St Pancras International", distance = null, onFrom = {}, onTo = {}, onBack = {}, actionsReady = ready)
            }
        }
        composeRule.onNodeWithText("From").assertIsNotEnabled()
        composeRule.onNodeWithText("To").assertIsNotEnabled()
        ready = true
        composeRule.onNodeWithText("From").assertIsEnabled()
        composeRule.onNodeWithText("To").assertIsEnabled()
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
        composeRule.onNodeWithTag("lineStopPage").performScrollToNode(hasText("From"))
        composeRule.onNodeWithText("From").performClick()
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
