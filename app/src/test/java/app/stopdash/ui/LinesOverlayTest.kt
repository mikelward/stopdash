package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.compose.ui.test.onAllNodesWithTag
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

    @Test
    fun show_on_map_opens_the_stop_where_it_is_and_waits_for_a_position() {
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
        // A synthetic central London point, as the map placed the stop.
        val placed = Coordinates(51.5, -0.12)
        var stop by mutableStateOf(LineStopRef("490X", "Somewhere Road", position = placed))
        var shown: Pair<String, Coordinates>? = null
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                    LinesOverlay(
                        model, open = line, onOpen = {}, onBack = {},
                        stop = stop,
                        onShowOnMap = { at, position -> shown = at.name to position },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineStopMap").performClick()
        assertEquals("Somewhere Road" to placed, shown)
        // A stop placed by neither the map nor the (empty) index can't be shown: the pin stays, greyed,
        // so the star beside it doesn't move.
        stop = LineStopRef("490Y", "Nowhere Lane")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineStopMap").assertIsNotEnabled()
    }

    @Test
    fun a_row_on_a_stop_s_board_opens_its_route_page_counted_as_a_route_and_back_returns_to_the_stop() {
        val sent = mutableListOf<app.stopdash.domain.UsageEvent>()
        app.stopdash.telemetry.UsageEvents.consent = { true }
        app.stopdash.telemetry.UsageEvents.install { sent += it }
        try {
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
            // Oxford Circus, a public interchange, with a made-up Victoria line train.
            val now = java.time.Instant.parse("2026-10-07T09:00:00Z")
            val board = DeparturesUiState.Loaded(
                stops = listOf(
                    app.stopdash.domain.StopArrivals(
                        "940GZZLUOXC", "Oxford Circus",
                        listOf(
                            app.stopdash.domain.Departure(
                                "victoria", "Victoria", "outbound", "Brixton", "Southbound - Platform 6", now.plusSeconds(180), "tube",
                            ),
                        ),
                        fetchedAt = now,
                    ),
                ),
                fetchedAt = now,
            )
            composeRule.setContent {
                StopDashTheme {
                    CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                        LinesOverlay(
                            model, open = line, onOpen = {}, onBack = {},
                            stop = LineStopRef("940GZZLUOXC", "Oxford Circus"),
                            departures = { _, _, _ -> StopDepartures(board, now, onRefresh = {}) },
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            // Events compare by what they send, as they reach the sink.
            val names = { sent.map { it.toString() } }
            val stopPage = app.stopdash.domain.UsageEvent.ScreenView(app.stopdash.domain.UsageEvent.Screen.LINE_STOP).toString()
            assertEquals(stopPage, names().last())
            composeRule.onNodeWithText("Brixton").performClick()
            composeRule.waitForIdle()
            // The tap counted as a stop row's, as on the near-me list, and the page as a route.
            assertTrue(app.stopdash.domain.UsageEvent.Tapped(app.stopdash.domain.UsageEvent.Tap.STOP_ROW).toString() in names())
            assertEquals(app.stopdash.domain.UsageEvent.ScreenView(app.stopdash.domain.UsageEvent.Screen.ROUTE).toString(), names().last())
            // Back from the route page: the stop's details, counted again.
            Espresso.pressBack()
            composeRule.waitForIdle()
            assertEquals(stopPage, names().last())
            composeRule.onNodeWithText("Brixton").assertExists()
        } finally {
            app.stopdash.telemetry.UsageEvents.resetForTest()
        }
    }

    @Test
    fun a_route_left_open_closes_when_the_stop_s_board_fails_and_stays_closed_after_try_again() {
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
        // Oxford Circus, a public interchange, with a made-up Victoria line train.
        val now = java.time.Instant.parse("2026-10-07T09:00:00Z")
        val loaded = DeparturesUiState.Loaded(
            stops = listOf(
                app.stopdash.domain.StopArrivals(
                    "940GZZLUOXC", "Oxford Circus",
                    listOf(
                        app.stopdash.domain.Departure(
                            "victoria", "Victoria", "outbound", "Brixton", "Southbound - Platform 6", now.plusSeconds(180), "tube",
                        ),
                    ),
                    fetchedAt = now,
                ),
            ),
            fetchedAt = now,
        )
        var state by mutableStateOf<DeparturesUiState>(loaded)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides Dispatchers.Unconfined) {
                    LinesOverlay(
                        model, open = line, onOpen = {}, onBack = {},
                        stop = LineStopRef("940GZZLUOXC", "Oxford Circus"),
                        departures = { _, _, _ -> StopDepartures(state, now, onRefresh = {}) },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Brixton").performClick()
        composeRule.waitForIdle()
        // The line's Back, the stop's over it, and the route page's over that.
        composeRule.onAllNodesWithContentDescription("Back").assertCountEquals(3)
        // The board's load fails, as a restore's reload can: the route page closes, the stop's error up.
        state = DeparturesUiState.Error(DeparturesUiState.Error.Kind.NETWORK)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stopBoardError").assertExists()
        // Try again brings the board back, not the route page the rider can no longer have been looking at.
        state = loaded
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Brixton").assertExists()
        composeRule.onAllNodesWithContentDescription("Back").assertCountEquals(2)
    }

    @Test
    fun a_bus_stop_s_details_show_its_letter_and_the_way_its_buses_go() {
        val line = LineRef("299", "299", "bus")
        // A made-up bus line: its route places the stop in a stop area, whose poles carry its letter.
        val route = LineSequence(
            listOf(app.stopdash.domain.LineRoute("North End - South End", listOf("490X", "490Y"), "inbound")),
            mapOf("490X" to "Somewhere Road", "490Y" to "Nowhere Lane"),
            stopAreas = mapOf("490X" to "490G0"),
        )
        val repository = RouteStopsRepository(
            object : RouteSequenceSource, app.stopdash.domain.StopAreaSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = route
                override suspend fun stopAreaPoles(areaId: String) = listOf(
                    app.stopdash.domain.StopLocation("490X", "Somewhere Road", 51.5, -0.12, stopLetter = "H", towards = "North End Or Elsewhere"),
                    app.stopdash.domain.StopLocation("490Z", "Somewhere Road", 51.5, -0.12, stopLetter = "J", towards = "South End"),
                )
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        // The line's map was drawn from its route, so the route is in the cache when a stop is tapped.
        kotlinx.coroutines.runBlocking { repository.load("299", "") }
        val model = LinesViewModel(
            loadLines = { listOf(line) },
            loadRecent = { emptyList() },
            recordOpen = { listOf(it) },
            lineStatus = { LineStatus(lineId = "299", severity = LineStatus.GOOD_SERVICE, description = "Good Service") },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            saved = SavedStateHandle(),
        )
        var stop by mutableStateOf(LineStopRef("490X", "Somewhere Road"))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(
                    LocalWorker provides Dispatchers.Unconfined,
                    LocalRouteStops provides repository,
                ) {
                    LinesOverlay(model, open = line, onOpen = {}, onBack = {}, stop = stop)
                }
            }
        }
        composeRule.waitForIdle()
        // Its own pole's letter, and the first place its buses go towards.
        composeRule.onNodeWithTag("lineStopPole").assertTextContains("Stop H, towards North End")
        // A stop no route places in an area (a station, or one off the line) shows none.
        stop = LineStopRef("490Y", "Nowhere Lane")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineStopPole").assertDoesNotExist()
    }

    /** A made-up bus line whose stop 490X sits in area 490G0, with [poles] there. */
    private fun poleRepository(
        poles: List<app.stopdash.domain.StopLocation>,
        asked: MutableList<String> = mutableListOf(),
    ) = RouteStopsRepository(
        object : RouteSequenceSource, app.stopdash.domain.StopAreaSource {
            override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                listOf(app.stopdash.domain.LineRoute("North End - South End", listOf("490X"), "inbound")),
                mapOf("490X" to "Somewhere Road"),
                stopAreas = mapOf("490X" to "490G0"),
            )
            override suspend fun stopAreaPoles(areaId: String): List<app.stopdash.domain.StopLocation> {
                synchronized(asked) { asked += Thread.currentThread().name }
                return poles
            }
        },
        io = Dispatchers.Unconfined,
        compute = Dispatchers.Unconfined,
    )

    private fun showStop(
        repository: RouteStopsRepository,
        line: LineRef = LineRef("299", "299", "bus"),
        worker: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        val model = LinesViewModel(
            loadLines = { listOf(line) },
            loadRecent = { emptyList() },
            recordOpen = { listOf(it) },
            lineStatus = { LineStatus(lineId = line.id, severity = LineStatus.GOOD_SERVICE, description = "Good Service") },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
            saved = SavedStateHandle(),
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(
                    LocalWorker provides worker,
                    LocalRouteStops provides repository,
                ) {
                    LinesOverlay(model, open = line, onOpen = {}, onBack = {}, stop = LineStopRef("490X", "Somewhere Road"))
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun a_restored_stop_page_loads_the_route_to_find_its_letter() {
        // No map has drawn the route yet (a page restored after process death): the lookup loads it.
        showStop(
            poleRepository(
                listOf(app.stopdash.domain.StopLocation("490X", "Somewhere Road", 51.5, -0.12, stopLetter = "H", towards = "North End")),
            ),
        )
        composeRule.onNodeWithTag("lineStopPole").assertTextContains("Stop H, towards North End")
    }

    @Test
    fun a_pole_with_only_a_bearing_shows_the_way_it_faces() {
        showStop(
            poleRepository(listOf(app.stopdash.domain.StopLocation("490X", "Somewhere Road", 51.5, -0.12, bearing = "SW"))),
        )
        composeRule.onNodeWithTag("lineStopPole").assertTextContains("Southwest-bound")
    }

    @Test
    fun a_bus_stop_s_pole_is_looked_up_on_the_worker() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
            .asCoroutineDispatcher()
        try {
            val asked = mutableListOf<String>()
            showStop(
                poleRepository(
                    listOf(app.stopdash.domain.StopLocation("490X", "Somewhere Road", 51.5, -0.12, stopLetter = "H")),
                    asked,
                ),
                worker = worker,
            )
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("lineStopPole").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("asked on $asked", asked.isNotEmpty() && asked.all { it.startsWith("test-worker") })
        } finally {
            worker.close()
        }
    }

    @Test
    fun a_station_s_details_ask_for_no_poles() {
        val asked = mutableListOf<String>()
        // A tube line's route places its stations in areas too; a station shows no letter, so none is asked.
        showStop(
            poleRepository(
                listOf(app.stopdash.domain.StopLocation("490X", "Somewhere Road", 51.5, -0.12, stopLetter = "H")),
                asked,
            ),
            line = LineRef("victoria", "Victoria", "tube"),
        )
        composeRule.onNodeWithTag("lineStopPole").assertDoesNotExist()
        // No line held for a letter a station never shows.
        composeRule.onNodeWithTag("lineStopPoleSlot").assertDoesNotExist()
        assertTrue("asked on $asked", asked.isEmpty())
    }

    @Test
    fun a_bus_stop_s_line_for_its_letter_is_held_while_it_s_looked_up() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            object : RouteSequenceSource, app.stopdash.domain.StopAreaSource {
                override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                    listOf(app.stopdash.domain.LineRoute("North End - South End", listOf("490X"), "inbound")),
                    mapOf("490X" to "Somewhere Road"),
                    stopAreas = mapOf("490X" to "490G0"),
                )
                override suspend fun stopAreaPoles(areaId: String): List<app.stopdash.domain.StopLocation> {
                    release.await()
                    return listOf(app.stopdash.domain.StopLocation("490X", "Somewhere Road", 51.5, -0.12, stopLetter = "H"))
                }
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        showStop(repository)
        // Still being looked up: no letter yet, but its line is already there.
        composeRule.onNodeWithTag("lineStopPole").assertDoesNotExist()
        composeRule.onNodeWithTag("lineStopPoleSlot").assertExists()
        release.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineStopPole").assertTextContains("Stop H", substring = true)
    }

    // Real text measurement, so a long line wraps as it would on a phone.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test
    fun what_is_under_a_bus_stop_s_letter_does_not_move_when_it_comes_in() {
        var pole by mutableStateOf<app.stopdash.domain.StopQualifier?>(null)
        composeRule.setContent {
            StopDashTheme {
                LineStopPage(
                    name = "Somewhere Road", distance = null, onFrom = {}, onTo = {}, onBack = {},
                    boardPending = true, pole = pole, poleSlot = true,
                )
            }
        }
        composeRule.waitForIdle()
        val before = composeRule.onNodeWithText("Loading departures…").getUnclippedBoundsInRoot()
        // A long "towards", well past one line on a phone: still held to the one line it was given.
        pole = app.stopdash.domain.StopQualifier.BusStop("H", "North End Interchange Bus Station And The Long Road Beyond It")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lineStopPole").assertTextContains("Stop H", substring = true)
        assertEquals(before, composeRule.onNodeWithText("Loading departures…").getUnclippedBoundsInRoot())
    }
}
