package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.stopdash.domain.Departure
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The near-me list's rows are built on the list's worker ([LocalWorker]), never in composition
 * (AGENTS.md *Main thread: read and dispatch only*; maintainer, 2026-10-03). Until a list's first
 * rows are in, it shows a spinner rather than an empty list; after that, its last rows stand in while
 * new ones are built, but never another list's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class ListRowsOffMainTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun stop(id: String, name: String, destination: String) = StopArrivals(
        stopId = id,
        stopName = name,
        departures = listOf(Departure("victoria", "Victoria", "southbound", destination, null, now.plusSeconds(240), "tube")),
        fetchedAt = now,
    )

    private val kingsCross = stop("940GZZLUKSX", "King's Cross St. Pancras", "Brixton")
    private val euston = stop("940GZZLUEUS", "Euston", "Walthamstow Central")

    // Runs the held worker until nothing more is queued on it: each stage starts once the one before is in.
    private fun settle(scheduler: TestCoroutineScheduler) {
        repeat(5) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    private fun spinners() = composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))

    @Test
    fun rows_are_built_on_the_worker_and_never_stand_in_for_another_list() {
        // Held until released by hand: built on the main thread, the rows would be there at once.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(kingsCross), fetchedAt = now))
        var shownAt by mutableStateOf(now)
        var listKey by mutableStateOf("kings-cross")
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held) {
                    MainScreen(state = state, now = shownAt, onRefresh = {}, listKey = listKey)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(0)
        spinners().assertCountEquals(1)

        settle(scheduler)
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(1)
        spinners().assertCountEquals(0)

        // The clock moves on: the same list's last rows stand in while the new ones are built.
        shownAt = now.plusSeconds(10)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(1)
        spinners().assertCountEquals(0)
        settle(scheduler)
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(1)

        // Another list: its rows are built afresh, and the last list's don't stand in meanwhile.
        state = DeparturesUiState.Loaded(stops = listOf(euston), fetchedAt = now)
        listKey = "euston"
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(0)
        composeRule.onAllNodesWithText("Walthamstow Central").assertCountEquals(0)
        spinners().assertCountEquals(1)
        settle(scheduler)
        composeRule.onAllNodesWithText("Walthamstow Central").assertCountEquals(1)
        spinners().assertCountEquals(0)
    }

    @Test
    fun a_cold_load_shows_no_rows_from_before_its_first_snapshot() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var state by mutableStateOf<DeparturesUiState>(DeparturesUiState.Loading)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held) {
                    MainScreen(state = state, now = now, onRefresh = {}, listKey = "kings-cross")
                }
            }
        }
        // The worker runs out while still loading: nothing is built that could stand in later.
        settle(scheduler)
        state = DeparturesUiState.Loaded(stops = listOf(kingsCross), fetchedAt = now)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("No upcoming departures", substring = true).assertCountEquals(0)
        spinners().assertCountEquals(1)
        settle(scheduler)
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(1)
    }

    @Test
    fun held_rows_are_drawn_against_the_time_they_were_built_for() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val soon = kingsCross.copy(
            departures = listOf(
                Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(60), "tube"),
                Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(300), "tube"),
            ),
        )
        var shownAt by mutableStateOf(now)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(soon), fetchedAt = now),
                        now = shownAt,
                        onRefresh = {},
                        listKey = "kings-cross",
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onAllNodesWithText("1 · 5 min").assertCountEquals(1)

        // The clock reaches the first train while the new rows are built: the held rows are drawn
        // against the time they were built for, not the new one, which would show the train just gone
        // as "0 min".
        shownAt = now.plusSeconds(61)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("0 ·", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("1 · 5 min").assertCountEquals(1)
        settle(scheduler)
        composeRule.onAllNodesWithText("0 ·", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("1 · 5 min").assertCountEquals(0)
    }

    @Test
    fun the_stamp_ages_the_rows_on_screen_not_a_fetch_still_being_built() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val earlier = now.minusSeconds(120)
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(kingsCross.copy(fetchedAt = earlier)), fetchedAt = earlier))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held) {
                    MainScreen(state = state, now = now, onRefresh = {}, listKey = "kings-cross")
                }
            }
        }
        settle(scheduler)
        composeRule.onAllNodesWithText("2 min ago").assertCountEquals(1)

        // A refresh lands while its rows are built: the stamp still ages the rows on screen.
        state = DeparturesUiState.Loaded(stops = listOf(kingsCross), fetchedAt = now)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("2 min ago").assertCountEquals(1)
        composeRule.onAllNodesWithText("Just now").assertCountEquals(0)
        settle(scheduler)
        composeRule.onAllNodesWithText("Just now").assertCountEquals(1)
    }

    @Test
    fun a_change_to_the_snapshot_alone_is_drawn() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val stops = listOf(kingsCross)
        val loading = DeparturesUiState.Loaded(
            stops = stops,
            fetchedAt = now,
            pendingStops = listOf(StopRef("940GZZLUEUS", "Euston", listOf(LineRef("victoria", "Victoria", "tube")))),
        )
        var state by mutableStateOf(loading)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held) {
                    MainScreen(state = state, now = now, onRefresh = {}, listKey = "kings-cross")
                }
            }
        }
        settle(scheduler)
        composeRule.onAllNodesWithText("Loading").assertCountEquals(1)

        // The fetch is canceled: the same stops, nothing loading any more. That alone is drawn.
        state = loading.copy(pendingStops = emptyList())
        settle(scheduler)
        composeRule.onAllNodesWithText("Loading").assertCountEquals(0)
    }

    private val victoriaLine = LineSequence(
        routes = listOf(LineRoute("Brixton ↔ Walthamstow Central", listOf("940GZZLUVIC", "940GZZLUWRR", "940GZZLUWWL"))),
        stopNames = mapOf("940GZZLUVIC" to "Victoria", "940GZZLUWRR" to "Warren Street", "940GZZLUWWL" to "Walthamstow Central"),
    )
    private val victoriaToWarrenStreet = StarredJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria",
    )

    private fun victoria(fetchedAt: Instant, vararg minutes: Long) = StopArrivals(
        stopId = "940GZZLUVIC",
        stopName = "Victoria",
        departures = minutes.map { Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(it * 60), "tube") },
        fetchedAt = fetchedAt,
    )

    @Test
    fun a_journey_card_is_judged_against_the_snapshot_its_rows_were_built_from() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        // An hour-old, empty origin: the card can't say no train is coming.
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(victoria(now.minusSeconds(3600))), fetchedAt = now))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(
                    LocalWorker provides held,
                    LocalRouteStops provides RouteStopsRepository(
                        object : RouteSequenceSource {
                            override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                        },
                    ),
                ) {
                    MainScreen(state = state, now = now, onRefresh = {}, journeys = listOf(victoriaToWarrenStreet), listKey = "victoria")
                }
            }
        }
        settle(scheduler)
        composeRule.onAllNodesWithText("Couldn't check trains").assertCountEquals(1)

        // A fresh fetch with a train. While its rows are built, the card keeps judging the last rows
        // against the snapshot they came from: the empty rows aren't read as a fresh "no trains".
        state = DeparturesUiState.Loaded(stops = listOf(victoria(now, 4)), fetchedAt = now)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("No trains to Warren Street soon").assertCountEquals(0)
        composeRule.onAllNodesWithText("Couldn't check trains").assertCountEquals(1)

        settle(scheduler)
        composeRule.onAllNodesWithText("Couldn't check trains").assertCountEquals(0)
        composeRule.onAllNodesWithText("No trains to Warren Street soon").assertCountEquals(0)
    }

    @Test
    fun the_list_waits_for_its_first_chips_as_for_its_rows() {
        // Readable places whose chips aren't worked out yet hold the list on its placeholder, so the
        // chip row is there when the list is (Codex on #539).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var chipsPending by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides held) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(kingsCross), fetchedAt = now),
                        now = now, onRefresh = {}, listKey = "kings-cross", favoritePlacesPending = chipsPending,
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(0)
        spinners().assertCountEquals(1)
        chipsPending = false
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Brixton").assertCountEquals(1)
        spinners().assertCountEquals(0)
    }
}

