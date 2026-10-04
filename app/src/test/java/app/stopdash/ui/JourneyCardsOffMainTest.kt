package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
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
import app.stopdash.domain.StopAreaSource
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a journey boards and alights on its route ([app.stopdash.domain.Journeys.segment]) is worked
 * out on the list's worker, never in composition (AGENTS.md *Main thread: read and dispatch only*).
 * While it is, the card checks: a route in but not yet placed on never reads "Couldn't check".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
class JourneyCardsOffMainTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val victoriaLine = LineSequence(
        routes = listOf(LineRoute("Brixton ↔ Walthamstow Central", listOf("940GZZLUVIC", "940GZZLUWRR", "940GZZLUWWL"))),
        stopNames = mapOf("940GZZLUVIC" to "Victoria", "940GZZLUWRR" to "Warren Street", "940GZZLUWWL" to "Walthamstow Central"),
    )
    private val victoriaToWarrenStreet = StarredJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria",
    )
    private val victoria = StopArrivals(
        stopId = "940GZZLUVIC",
        stopName = "Victoria",
        departures = listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(240), "tube")),
        fetchedAt = now,
    )

    // Runs the held worker until nothing more is queued on it: each stage starts once the one before is in.
    private fun settle(scheduler: TestCoroutineScheduler) {
        repeat(5) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun a_route_is_placed_on_the_worker_and_the_card_checks_meanwhile() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        // The route comes in only once released, after the list is drawn.
        val route = CompletableDeferred<LineSequence>()
        val listWork = ListWork()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(
                    LocalWorker provides held,
                    LocalRouteStops provides RouteStopsRepository(
                        object : RouteSequenceSource {
                            override suspend fun routeSequence(lineId: String, direction: String) = route.await()
                        },
                        io = Dispatchers.Unconfined,
                    ),
                ) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = listOf(victoriaToWarrenStreet),
                        listKey = "victoria",
                        listWork = listWork,
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onAllNodesWithText("Checking trains…").assertCountEquals(1)

        // The route is in: nothing places the journey on it until the worker runs.
        route.complete(victoriaLine)
        composeRule.waitForIdle()
        assertNull(listWork.segments.value?.value?.segments?.get(victoriaToWarrenStreet.key))

        // One pass of the worker at a time: the card checks until the journey is placed, never reading
        // the unplaced route as "Couldn't check".
        repeat(6) {
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("Couldn't check trains").assertCountEquals(0)
        }
        settle(scheduler)
        assertNotNull(listWork.segments.value?.value?.segments?.get(victoriaToWarrenStreet.key))
        composeRule.onAllNodesWithText("Checking trains…").assertCountEquals(0)
        composeRule.onAllNodesWithText("Couldn't check trains").assertCountEquals(0)
    }
    @Test
    fun a_flipped_journey_is_never_fetched_from_the_other_way_round() {
        // A flip keeps the journey's key and route: the placing for the old way round mustn't stand in
        // for the new one, or the card would fetch (and judge) from the old origin (Codex, #564).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var journey by mutableStateOf(victoriaToWarrenStreet)
        var origins = emptyList<String>()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(
                    LocalWorker provides held,
                    LocalRouteStops provides RouteStopsRepository(
                        object : RouteSequenceSource {
                            override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                        },
                        io = Dispatchers.Unconfined,
                    ),
                ) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(victoria), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = listOf(journey),
                        listKey = "victoria",
                        onJourneyOrigins = { refs -> origins = refs.map { it.id } },
                    )
                }
            }
        }
        settle(scheduler)
        assertEquals(listOf("940GZZLUVIC"), origins)

        // Flipped: until it's placed the other way round, it's fetched from its own new origin.
        journey = victoriaToWarrenStreet.reversed()
        composeRule.waitForIdle()
        assertEquals(listOf("940GZZLUWRR"), origins)
        settle(scheduler)
        assertEquals(listOf("940GZZLUWRR"), origins)
    }
    // A fictional bus journey (no real place): b1 from Park to Hill, and b3 boarding beside it at pole K.
    private val b1 = LineSequence(
        routes = listOf(
            LineRoute("Park ↔ Hill", listOf("PARKN", "LANEN", "HILLN")),
            LineRoute("Hill ↔ Park", listOf("HILLS", "LOOPS", "PARKS")),
        ),
        stopNames = mapOf("PARKN" to "Park", "PARKS" to "Park", "LANEN" to "Lane", "HILLN" to "Hill", "HILLS" to "Hill", "LOOPS" to "Loop"),
        stopAreas = mapOf("PARKN" to "G-PARK", "PARKS" to "G-PARK", "HILLN" to "G-HILL", "HILLS" to "G-HILL"),
    )
    private val b3 = LineSequence(listOf(LineRoute("Park ↔ Hill", listOf("PARKK", "HILLN"))), mapOf("PARKK" to "Park", "HILLN" to "Hill"))
    private val parkToHill = StarredJourney(JourneyEnd("PARKN", "Park"), JourneyEnd("HILLN", "Hill"), "b1", "B1", "bus")

    private fun pole(id: String, line: String) =
        StopLocation(id, "Park", 51.5, -0.12, listOf(LineRef(line, line, "bus")), clusterId = "G-PARK", stopLetter = id.takeLast(1))

    private fun busStop(id: String, line: String) = StopArrivals(
        stopId = id,
        stopName = "Park",
        departures = listOf(Departure(line, line, "outbound", "Hill", null, now.plusSeconds(300), "bus")),
        fetchedAt = now,
        clusterId = "G-PARK",
        stopLetter = id.takeLast(1),
    )

    @Test
    fun the_poles_beside_a_bus_origin_are_worked_out_on_the_worker() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val listWork = ListWork()
        val source = object : RouteSequenceSource, StopAreaSource {
            override suspend fun routeSequence(lineId: String, direction: String) = if (lineId == "b3") b3 else b1
            override suspend fun stopAreaPoles(areaId: String) = listOf(pole("PARKN", "b1"), pole("PARKK", "b3"))
        }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(
                    LocalWorker provides held,
                    LocalRouteStops provides RouteStopsRepository(source, io = Dispatchers.Unconfined),
                ) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(busStop("PARKN", "b1"), busStop("PARKK", "b3")), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        journeys = listOf(parkToHill),
                        listKey = "park",
                        listWork = listWork,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Nothing works the poles out until the worker runs.
        assertNull(listWork.siblings.value)

        composeRule.onAllNodesWithText("Stop K", substring = true, useUnmergedTree = true).assertCountEquals(0)

        // One pass of the worker at a time: the card checks until its neighboring poles are worked out,
        // never reading them as missed.
        repeat(8) {
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("Couldn't check buses").assertCountEquals(0)
        }
        settle(scheduler)
        assertEquals(listOf("PARKK"), listWork.siblings.value?.value?.get(parkToHill.key)?.poles?.map { it.id })
        // The card heads pole K's bus under its own letter.
        composeRule.onAllNodesWithText("Stop K", substring = true, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Checking buses…").assertCountEquals(0)
        composeRule.onAllNodesWithText("Couldn't check buses").assertCountEquals(0)
    }
}
