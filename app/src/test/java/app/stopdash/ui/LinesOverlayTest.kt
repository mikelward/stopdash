package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import app.stopdash.data.TflRouteSequenceDto
import app.stopdash.domain.Coordinates
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import kotlinx.serialization.json.Json
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.ui.theme.StopDashTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import androidx.test.espresso.Espresso
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A line's page opened from *Lines…* (SPEC *Finding a line*) asks for its status as it comes up, and
 * again when the app returns to it after a while away, rather than showing the answer it had as
 * current (SPEC D4). Public TfL lines only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class LinesOverlayTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val n299 = LineRef("299", "299", "bus")
    private var asked = 0
    private var clock = 0L

    @Test
    fun the_page_asks_again_on_returning_to_the_app_after_its_answer_has_aged() {
        val model = LinesViewModel(
            loadLines = { listOf(n299) },
            loadRecent = { emptyList() },
            recordOpen = { listOf(it) },
            lineStatus = { asked++; LineStatus(lineId = "299", severity = LineStatus.GOOD_SERVICE, description = "Good Service") },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            elapsedMillis = { clock },
            saved = SavedStateHandle(),
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                    LinesOverlay(model, open = n299, onOpen = {}, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, asked)
        // Away from the app past the answer's age, then back.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        clock += 100_000
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        assertEquals(2, asked)
    }

    @Test
    fun back_from_a_line_returns_to_where_the_search_was_scrolled() {
        // Recent lines enough to scroll: public bus routes 1 to 60.
        val buses = (1..60).map { LineRef("$it", "$it", "bus") }
        val model = LinesViewModel(
            loadLines = { buses },
            loadRecent = { buses },
            recordOpen = { buses },
            lineStatus = { null },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            saved = SavedStateHandle(),
        )
        var open by mutableStateOf<LineRef?>(null)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                    LinesOverlay(model, open = open, onOpen = { open = it }, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineSearchList").performScrollToIndex(40)
        composeRule.onNodeWithText("40").performClick()
        composeRule.waitForIdle()
        open = null
        composeRule.waitForIdle()
        // Back on the search where it was: the line picked still on screen, the top of the list not.
        composeRule.onNodeWithText("40").assertIsDisplayed()
        composeRule.onNodeWithText("Recent").assertIsNotDisplayed()
    }

    @Test
    fun the_search_keeps_its_scroll_under_an_overlay_that_covers_lines() {
        val buses = (1..60).map { LineRef("$it", "$it", "bus") }
        val model = LinesViewModel(
            loadLines = { buses },
            loadRecent = { buses },
            recordOpen = { buses },
            lineStatus = { null },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            saved = SavedStateHandle(),
        )
        // Licenses over Lines…: the overlay leaves composition, its holder (the activity's) stays.
        var shown by mutableStateOf(true)
        composeRule.setContent {
            val holder = rememberSaveableStateHolder()
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                    if (shown) LinesOverlay(model, open = null, onOpen = {}, onBack = {}, saveable = holder)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineSearchList").performScrollToIndex(40)
        composeRule.waitForIdle()
        shown = false
        composeRule.waitForIdle()
        shown = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("40").assertIsDisplayed()
        composeRule.onNodeWithText("Recent").assertIsNotDisplayed()
    }

    @Test
    fun a_query_past_its_length_is_not_taken() {
        var queries = 0
        composeRule.setContent {
            StopDashTheme {
                LineSearchScreen(
                    state = LinesViewModel.State(recent = emptyList()),
                    onQueryChange = { queries++ },
                    onOpenLine = {},
                    onRetry = {},
                    onBack = {},
                    autoFocus = false,
                )
            }
        }
        composeRule.onNodeWithTag("lineSearchField").performTextInput("x".repeat(LinesViewModel.MAX_QUERY + 1))
        composeRule.waitForIdle()
        assertEquals(0, queries)
        composeRule.onNodeWithTag("lineSearchField").performTextInput("299")
        composeRule.waitForIdle()
        assertEquals(1, queries)
    }

    @Test
    fun a_station_tapped_on_the_map_opens_its_details_with_its_distance() {
        val northern: LineSequence = Json { ignoreUnknownKeys = true }
            .decodeFromString<TflRouteSequenceDto>(checkNotNull(javaClass.getResource("/fixtures/route_sequence_northern_outbound.json")).readText())
            .toLineSequence()
            // The recorded sequence carries no positions: Euston's, TfL's public station coordinate.
            .let { it.copy(stopPositions = it.stopPositions + ("940GZZLUEUS" to (51.5282 to -0.1337))) }
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = northern
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        val line = LineRef("northern", "Northern", "tube")
        val model = LinesViewModel(
            loadLines = { listOf(line) },
            loadRecent = { emptyList() },
            recordOpen = { listOf(it) },
            lineStatus = { LineStatus(lineId = "northern", severity = LineStatus.GOOD_SERVICE, description = "Good Service") },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            saved = SavedStateHandle(),
        )
        var stop by mutableStateOf<LineStopRef?>(null)
        var fromStop: LineStopRef? = null
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined, LocalRouteStops provides repository) {
                    LinesOverlay(
                        model,
                        open = line,
                        onOpen = {},
                        onBack = {},
                        stop = stop,
                        onStop = { stop = it },
                        // A synthetic position in central London, nobody's.
                        here = Coordinates(51.5, -0.12),
                        onFrom = { fromStop = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Euston sits in a fold: every station shown first.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Show all stations"))
        composeRule.onNodeWithText("Show all stations").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Euston"))
        composeRule.onNodeWithText("Euston").performClick()
        composeRule.waitForIdle()
        assertEquals("Euston", stop?.name)
        // Its distance was worked out at the tap and kept with it, so the title is whole from the first
        // frame and stays so on a restore (Codex on #659).
        assertTrue(stop?.distance?.isNotEmpty() == true)
        assertEquals(stop, LineStopRefSaver.restore(with(LineStopRefSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stop) }!!))
        // Its name and how far it is, From to start there, no To where To… isn't offered.
        composeRule.onNodeWithTag("lineStopTitle").assertTextContains("Euston (", substring = true)
        composeRule.onNodeWithText("To").assertDoesNotExist()
        composeRule.onNodeWithText("From").performClick()
        assertEquals(stop, fromStop)
        // Back returns to the line, its map where it was: every station still shown, Euston in view
        // (Codex on #659).
        stop = null
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Euston").assertIsDisplayed()
    }

    @Test
    fun a_station_opened_off_the_line_survives_a_restore() {
        val stop = LineStopRef("910GSTPX", "London St Pancras International", "350 m", onLine = false)
        val saved = with(LineStopRefSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stop) }!!
        assertEquals(stop, LineStopRefSaver.restore(saved))
        // A save from before onLine still restores, on the line.
        assertEquals(LineStopRef("940GZZLUEUS", "Euston", null), LineStopRefSaver.restore(arrayListOf("940GZZLUEUS", "Euston", "")))
    }

    @Test
    fun a_station_opened_from_another_keeps_the_way_back_across_a_restore() {
        val kx = LineStopRef("940GZZLUKSX", "King's Cross St. Pancras", "350 m")
        val euston = LineStopRef("940GZZLUEUS", "Euston", "0.7 km", onLine = false).openedFrom(kx.copy(fromId = "HUBKGX"))
        // The station it came from rides along, without what From and To were last set to open.
        assertEquals(kx, euston.previous)
        val saved = with(LineStopRefSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(euston) }!!
        assertEquals(euston, LineStopRefSaver.restore(saved))
        assertEquals(listOf("lineStop:1:940GZZLUEUS", "lineStop:0:940GZZLUKSX"), euston.pageKeys())
    }

    @Test
    fun the_way_back_keeps_only_the_recent_stations() {
        var at = LineStopRef("0", "Station 0")
        for (n in 1..25) at = LineStopRef("$n", "Station $n").openedFrom(at)
        var steps = 0
        var back = at.previous
        while (back != null) {
            steps++
            back = back.previous
        }
        assertEquals(LineStopRef.MAX_TRAIL, steps)
        assertEquals("24", at.previous?.id)
        // Each page's key holds as older stations drop off, so Back still finds its scroll (Codex on #667).
        assertEquals("lineStop:24:24", at.previous?.pageKey)
        // The station the trail lets go of next is named, so its saved scroll goes with it (Codex on #667).
        assertEquals("lineStop:15:15", at.evictedByOpening())
        assertEquals(null, LineStopRef("1", "One").openedFrom(LineStopRef("0", "Zero")).evictedByOpening())
    }

    @Test
    fun back_from_a_station_opened_from_another_returns_to_that_one() {
        val line = LineRef("victoria", "Victoria", "tube")
        val model = LinesViewModel(
            loadLines = { listOf(line) },
            loadRecent = { emptyList() },
            recordOpen = { listOf(it) },
            lineStatus = { LineStatus(lineId = "victoria", severity = LineStatus.GOOD_SERVICE, description = "Good Service") },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            saved = SavedStateHandle(),
        )
        val kx = LineStopRef("940GZZLUKSX", "King's Cross St. Pancras")
        var stop by mutableStateOf<LineStopRef?>(LineStopRef("940GZZLUEUS", "Euston", onLine = false).openedFrom(kx))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                    LinesOverlay(model, open = line, onOpen = {}, onBack = {}, stop = stop, onStop = { stop = it })
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineStopTitle").assertTextContains("Euston", substring = true)
        Espresso.pressBack()
        composeRule.waitForIdle()
        assertEquals(kx, stop)
        composeRule.onNodeWithTag("lineStopTitle").assertTextContains("King's Cross", substring = true)
        // And from the first station, back to the line.
        Espresso.pressBack()
        composeRule.waitForIdle()
        assertEquals(null, stop)
    }

    @Test
    fun a_stop_s_position_survives_a_restore_and_older_saves_still_restore() {
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val kx = LineStopRef("940GZZLUKSX", "King's Cross St. Pancras", "350 m", position = Coordinates(51.5302, -0.1238))
        val euston = LineStopRef("940GZZLUEUS", "Euston", null, onLine = false).openedFrom(kx)
        assertEquals(euston, LineStopRefSaver.restore(with(LineStopRefSaver) { scope.save(euston) }!!))
        // #667's five strings a stop, without positions.
        val older = arrayListOf("940GZZLUEUS", "Euston", "", "0", "1", "940GZZLUKSX", "King's Cross St. Pancras", "350 m", "1", "0")
        val restored = LineStopRefSaver.restore(older)!!
        assertEquals("940GZZLUEUS", restored.id)
        assertEquals("940GZZLUKSX", restored.previous?.id)
        assertEquals(null, restored.position)
    }

    @Test
    fun a_stop_the_index_does_not_hold_favorites_where_the_map_placed_it() {
        val line = LineRef("victoria", "Victoria", "tube")
        val model = LinesViewModel(
            loadLines = { listOf(line) },
            loadRecent = { emptyList() },
            recordOpen = { listOf(it) },
            lineStatus = { LineStatus(lineId = "victoria", severity = LineStatus.GOOD_SERVICE, description = "Good Service") },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            saved = SavedStateHandle(),
        )
        // A stop the (empty) index doesn't hold, placed by the map: a synthetic central London point.
        val placed = Coordinates(51.5, -0.12)
        var favorited: Coordinates? = null
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                    LinesOverlay(
                        model, open = line, onOpen = {}, onBack = {},
                        stop = LineStopRef("490X", "Somewhere Road", position = placed),
                        onFavorite = { _, position -> favorited = position },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineStopFavorite").performClick()
        // The map's position, so no lookup is needed (Codex on #670).
        assertEquals(placed, favorited)
    }

    @Test
    fun a_stop_to_add_as_a_place_survives_a_restore() {
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val placed = app.stopdash.domain.StationMatch("940GZZLUEUS", "Euston", latitude = 51.5282, longitude = -0.1337)
        assertEquals(placed, StationMatchSaver.restore(with(StationMatchSaver) { scope.save(placed) }!!))
        val unplaced = app.stopdash.domain.StationMatch("490X", "Somewhere Road")
        assertEquals(unplaced, StationMatchSaver.restore(with(StationMatchSaver) { scope.save(unplaced) }!!))
    }
}
