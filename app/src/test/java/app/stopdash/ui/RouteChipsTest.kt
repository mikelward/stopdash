package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.Departure
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RideGrouping
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StopArrivals
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The route chips atop a route page (maintainer, 2026-10-21; SPEC *Route detail*): one per way the line runs on
 * from the stop, the tapped row's chosen; another with a train moves the page to that route, and one with none
 * lists its stops with no live times. The line's routes come from a fake source, worked out on a held worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteChipsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-10-21T08:00:00Z")

    private val southbound = listOf(
        LineRoute("High Barnet &harr; Morden via Bank", listOf(HBT, HGT, CTN, EUS, BNK, KNG, MDN), "outbound"),
        LineRoute("High Barnet &harr; Morden via Charing Cross", listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, MDN), "outbound"),
    )
    private val names = mapOf(
        HBT to "High Barnet", HGT to "Highgate", CTN to "Camden Town", MTC to "Mornington Crescent", EUS to "Euston",
        BNK to "Bank", CHX to "Charing Cross", KNG to "Kennington", MDN to "Morden",
    )

    private val routes = RouteStopsRepository(
        object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                if (direction == "outbound") southbound else southbound.map { it.copy(stopIds = it.stopIds.reversed(), direction = "inbound") },
                names,
            )
        },
        io = Dispatchers.Unconfined,
        compute = Dispatchers.Unconfined,
    )

    private val highgate = StopArrivals(
        stopId = HGT,
        stopName = "Highgate",
        departures = listOf(
            Departure("northern", "Northern", "outbound", "Morden", "Southbound - Platform 2", now.plusSeconds(120), "tube", "Bank"),
            Departure("northern", "Northern", "inbound", "High Barnet", "Northbound - Platform 1", now.plusSeconds(300), "tube"),
        ),
        fetchedAt = now,
    )

    private fun settle(scheduler: TestCoroutineScheduler, rounds: Int = 5) {
        repeat(rounds) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun the_tapped_route_is_chosen_and_another_moves_the_page_or_lists_its_stops() {
        val scheduler = TestCoroutineScheduler()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    MainScreen(state = DeparturesUiState.Loaded(stops = listOf(highgate), fetchedAt = now), now = now, onRefresh = {})
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        // The way south by Bank (the row tapped), by Charing Cross, and north.
        composeRule.onNodeWithTag("routeChoice-0").assertIsSelected()
        composeRule.onNodeWithText("Morden/Bank").assertIsDisplayed()
        composeRule.onNodeWithText("Morden/Charing X").assertIsDisplayed()
        composeRule.onNodeWithTag("routeChoice-2").assertIsNotSelected()

        // North, which has a train: the page is that row's, its train counted down and its star back.
        composeRule.onNodeWithTag("routeChoice-2").performClick()
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-2").assertIsSelected()
        composeRule.onNodeWithText("5 min").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()

        // By Charing Cross, which has none at the stop: its stops, with no live times and no star.
        composeRule.onNodeWithTag("routeChoice-1").performClick()
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-1").assertIsSelected()
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onAllNodesWithText("Mornington Crescent").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()

        // And back to the row's own route.
        composeRule.onNodeWithTag("routeChoice-2").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("No live times").assertDoesNotExist()
        composeRule.onNodeWithText("5 min").assertIsDisplayed()
    }

    @Test
    fun a_journey_cards_page_offers_every_way_on_its_far_ends_first_and_moves_to_another() {
        val scheduler = TestCoroutineScheduler()
        // Highgate to Charing Cross: the card counts the train by Charing Cross. With no platforms, as the routes
        // here carry no stations' positions to read a platform's bound by.
        val origin = highgate.copy(
            departures = (
                highgate.departures +
                    Departure("northern", "Northern", "outbound", "Morden", null, now.plusSeconds(420), "tube", "Charing X")
                ).map { it.copy(platform = null) },
        )
        // A stop near enough for the list, so Highgate's trains are the card's alone.
        val near = StopArrivals(
            "940GZZLUMRH", "Manor House",
            listOf(Departure("piccadilly", "Piccadilly", "westbound", "Cockfosters", "Westbound - Platform 2", now.plusSeconds(240), "tube")),
            fetchedAt = now,
        )
        // Both ways when asked for either, as TfL's route for the line places a favorite ride.
        val northbound = southbound.map { it.copy(stopIds = it.stopIds.reversed(), direction = "inbound") }
        val lineRoutes = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                    when (direction) {
                        "outbound" -> southbound
                        "inbound" -> northbound
                        else -> southbound + northbound
                    },
                    names,
                )
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides lineRoutes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(stops = listOf(near, origin), fetchedAt = now),
                        now = now,
                        onRefresh = {},
                        stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                        journeys = listOf(FavoriteJourney(JourneyEnd(HGT, "Highgate"), JourneyEnd(CHX, "Charing Cross"), "northern")),
                        rideGrouping = RideGrouping.RIDE,
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onAllNodesWithText("Morden", substring = true).onFirst().performTouchInput { click() }
        settle(scheduler)
        // Every way on from Highgate (maintainer, 2026-10-21): south by Charing Cross first, the card's way and
        // the one reaching its far end, then by Bank, then north.
        composeRule.onNodeWithTag("routeChoice-0").assert(hasText("Morden/Charing X")).assertIsSelected()
        composeRule.onNodeWithTag("routeChoice-1").assert(hasText("Morden/Bank")).assertIsNotSelected()
        composeRule.onNodeWithTag("routeChoice-2").assert(hasText("High Barnet")).assertIsNotSelected()
        composeRule.onNodeWithText("7 min").assertIsDisplayed()

        // By Bank, a train the card doesn't count on: the page moves to it.
        composeRule.onNodeWithTag("routeChoice-1").performClick()
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-1").assertIsSelected()
        composeRule.onNodeWithText("2 min").assertIsDisplayed()
        // And the chips hold their order.
        composeRule.onNodeWithTag("routeChoice-0").assert(hasText("Morden/Charing X"))
    }

    @Test
    fun a_way_chosen_with_no_train_keeps_the_page_open_once_the_rows_trains_are_gone_and_follows_its_train() {
        val scheduler = TestCoroutineScheduler()
        // Highgate declares its line, as TfL's stops do, so the list has a row for it with no times.
        val stop = highgate.copy(lines = listOf(app.stopdash.domain.LineRef("northern", "Northern", "tube")))
        var clock by mutableStateOf(now)
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(composeRule)
        restoration.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    MainScreen(state = state, now = clock, onRefresh = {})
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        // By Charing Cross, with no train at the stop.
        composeRule.onNodeWithTag("routeChoice-1").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()

        // Six minutes on, the row the page opened on is gone, its train by Bank left with no other: the page stays on
        // the way chosen rather than close with the row (Codex on #1038), as of the refresh, not the row's old fetch,
        // and never saying TfL gave the row no times, as it had some.
        val later = now.plusSeconds(360)
        val north = Departure("northern", "Northern", "inbound", "High Barnet", "Northbound - Platform 1", later.plusSeconds(300), "tube")
        composeRule.runOnIdle {
            clock = later
            state = DeparturesUiState.Loaded(stops = listOf(stop.copy(departures = listOf(north), fetchedAt = later)), fetchedAt = later)
        }
        // More rounds than a refresh alone takes: the rows, the page's row looked for on the worker, then the chips
        // worked out for the page kept on its way.
        settle(scheduler, rounds = 10)
        composeRule.onNodeWithTag("routeChoice-1").assertIsSelected()
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onAllNodesWithText("Mornington Crescent").assertCountEquals(1)
        composeRule.onNodeWithText("TfL has no live times here.").assertDoesNotExist()
        composeRule.onNodeWithText("Status may be out of date").assertDoesNotExist()
        composeRule.onNodeWithText("Stops shown once departures refresh").assertDoesNotExist()

        // Still so after a rotation, which keeps the way chosen but not the row held: the stop's row for its line
        // stands in.
        restoration.emulateSavedInstanceStateRestore()
        settle(scheduler, rounds = 10)
        composeRule.onNodeWithTag("routeChoice-1").assertIsSelected()
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onNodeWithText("TfL has no live times here.").assertDoesNotExist()

        // A train by Charing Cross comes: the page follows it, as from a tap on its row.
        val charingX = Departure("northern", "Northern", "outbound", "Morden", "Southbound - Platform 2", later.plusSeconds(240), "tube", "Charing X")
        composeRule.runOnIdle {
            state = DeparturesUiState.Loaded(stops = listOf(stop.copy(departures = listOf(north, charingX), fetchedAt = later)), fetchedAt = later)
        }
        // And again: the rows, the chips seeing the train, then the page moved to its row and its work.
        settle(scheduler, rounds = 10)
        composeRule.onNodeWithText("No live times").assertDoesNotExist()
        composeRule.onNodeWithText("4 min").assertIsDisplayed()
        composeRule.onNodeWithTag("routeChoice-1").assertIsSelected()
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()
    }

    @Test
    fun a_page_kept_on_a_way_chosen_with_no_train_closes_on_back() {
        val scheduler = TestCoroutineScheduler()
        val stop = highgate.copy(lines = listOf(app.stopdash.domain.LineRef("northern", "Northern", "tube")))
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    MainScreen(state = state, now = now, onRefresh = {})
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-1").performClick()
        settle(scheduler)
        val north = highgate.departures.filter { it.direction == "inbound" }
        composeRule.runOnIdle { state = DeparturesUiState.Loaded(stops = listOf(stop.copy(departures = north)), fetchedAt = now) }
        settle(scheduler, rounds = 10)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()

        // Back from the page kept on its way: the list, and the page stays closed when its row's trains come back
        // (Codex on #1051).
        composeRule.onNodeWithContentDescription("Back").performClick()
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-1").assertDoesNotExist()
        composeRule.onNodeWithText("High Barnet", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { state = DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now) }
        settle(scheduler, rounds = 10)
        composeRule.onNodeWithTag("routeChoice-1").assertDoesNotExist()
        composeRule.onNodeWithText("Morden", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_way_chosen_with_no_train_closes_with_its_stop() {
        val scheduler = TestCoroutineScheduler()
        val stop = highgate.copy(lines = listOf(app.stopdash.domain.LineRef("northern", "Northern", "tube")))
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now))
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    MainScreen(state = state, now = now, onRefresh = {})
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-1").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()

        // The stop itself gone from the list: nothing to stand the page on, so it closes, as a row's page does.
        composeRule.runOnIdle { state = DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now) }
        settle(scheduler)
        composeRule.onNodeWithText("No live times").assertDoesNotExist()
        composeRule.onNodeWithTag("routeChoice-1").assertDoesNotExist()
    }

    @Test
    fun a_way_chosen_with_no_train_shows_its_own_ways_alert_not_the_rows() {
        // Severe delays northbound only: the southbound row has none, the way north has.
        val delays = app.stopdash.domain.LineStatus("northern", 6, "Severe Delays")
        val status = app.stopdash.domain.LineStatus(
            "northern", 6, "Severe Delays",
            byDirection = mapOf("inbound" to delays, "outbound" to app.stopdash.domain.LineStatus("northern", 10, "Good Service")),
        )
        val southOnly = highgate.copy(departures = highgate.departures.filter { it.direction == "outbound" })
        val scheduler = TestCoroutineScheduler()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(
                            stops = listOf(southOnly), fetchedAt = now, lineStatuses = mapOf("northern" to status),
                            determinedLineIds = setOf("northern"),
                        ),
                        now = now,
                        onRefresh = {},
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        composeRule.onNodeWithText("Severe Delays").assertDoesNotExist()
        composeRule.onNodeWithText("No disruptions reported").assertIsDisplayed()

        // North, with no train at the stop: its own way's alert, with no dismiss (it's the row's to make).
        composeRule.onNodeWithTag("routeChoice-2").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onNodeWithText("Severe Delays").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Dismiss alert").assertDoesNotExist()
    }

    @Test
    fun a_way_chosen_with_no_train_counts_its_planned_work_begun_today_as_under_way() {
        // A part closure northbound, planned to start today, in a status kept from before today: still "to come" in it.
        val closure = app.stopdash.domain.PlannedAlert("Part Closure", "No service northbound.", java.time.LocalDate.of(2026, 10, 21))
        val north = app.stopdash.domain.LineStatus("northern", 10, "Good Service", planned = listOf(closure))
        val status = app.stopdash.domain.LineStatus(
            "northern", 10, "Good Service", planned = listOf(closure),
            byDirection = mapOf("inbound" to north, "outbound" to app.stopdash.domain.LineStatus("northern", 10, "Good Service")),
        )
        val southOnly = highgate.copy(departures = highgate.departures.filter { it.direction == "outbound" })
        val scheduler = TestCoroutineScheduler()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    MainScreen(
                        state = DeparturesUiState.Loaded(
                            stops = listOf(southOnly), fetchedAt = now, lineStatuses = mapOf("northern" to status),
                            determinedLineIds = setOf("northern"),
                        ),
                        now = now,
                        onRefresh = {},
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        // North, with no train: its closure is under way today, as its row would say, never "no disruptions".
        composeRule.onNodeWithTag("routeChoice-2").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("Part Closure").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
    }

    @Test
    fun routes_that_failed_to_load_are_said_with_a_retry_not_read_as_none() {
        val scheduler = TestCoroutineScheduler()
        // Offline for the chips' first ask of each way, back after.
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val flaky = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    if (asked.incrementAndGet() <= 2) throw app.stopdash.domain.TflException.Offline(null)
                    return LineSequence(
                        if (direction == "outbound") southbound else southbound.map { it.copy(stopIds = it.stopIds.reversed(), direction = "inbound") },
                        names,
                    )
                }
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides flaky) {
                    MainScreen(state = DeparturesUiState.Loaded(stops = listOf(highgate), fetchedAt = now), now = now, onRefresh = {})
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        composeRule.onNodeWithText("Couldn't load other routes").assertIsDisplayed()
        composeRule.onNodeWithTag("routeChoice-0").assertDoesNotExist()
        val failedHeight = composeRule.onNodeWithTag("routeChipsSlot").getUnclippedBoundsInRoot().height
        composeRule.onNodeWithText("Retry").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("Couldn't load other routes").assertDoesNotExist()
        composeRule.onNodeWithTag("routeChoice-0").assertIsSelected()
        // The chips came in where the failure was, at its height: nothing below moved (Codex on #1038).
        assertEquals(failedHeight, composeRule.onNodeWithTag("routeChipsSlot").getUnclippedBoundsInRoot().height)
    }

    @Test
    fun the_chips_slot_is_as_tall_while_theyre_worked_out_as_once_theyre_in() {
        val scheduler = TestCoroutineScheduler()
        val row = app.stopdash.domain.DepartureRows.across(listOf(highgate), now).first { it.direction == "outbound" }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = true,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                        onChooseRoute = { _, _ -> },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Held for them before they're worked out, at a chip's touch height, not the 32dp it's drawn at.
        composeRule.onNodeWithTag("routeChoice-0").assertDoesNotExist()
        val held = composeRule.onNodeWithTag("routeChipsSlot").getUnclippedBoundsInRoot()
        settle(scheduler)
        val slot = composeRule.onNodeWithTag("routeChipsSlot").getUnclippedBoundsInRoot()
        assertEquals(held.height, slot.height)
        assertEquals(held.top, slot.top)
        // The chips' row fills it exactly: a chip is drawn 32dp tall but takes its touch height (Codex on #1038).
        val chips = composeRule.onNodeWithTag("routeChips").getUnclippedBoundsInRoot()
        assertEquals(slot.height, chips.height)
        org.junit.Assert.assertTrue(chips.height > 32.dp)
    }

    @Test
    fun a_way_chosen_with_no_train_on_a_stale_page_claims_no_live_times() {
        val scheduler = TestCoroutineScheduler()
        val row = app.stopdash.domain.DepartureRows.across(listOf(highgate), now).first { it.direction == "outbound" }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = true,
                        disruptionUnknown = false,
                        // An old snapshot: a train may be on any way by now.
                        stale = true,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                        focus = app.stopdash.domain.RouteFocus("Morden", "Bank"),
                        onChooseRoute = { _, _ -> },
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden/Charing X").performClick()
        settle(scheduler)
        composeRule.onAllNodesWithText("Mornington Crescent").assertCountEquals(1)
        composeRule.onNodeWithText("No live times").assertDoesNotExist()
        composeRule.onNodeWithText("Status may be out of date").assertIsDisplayed()
    }

    @Test
    fun a_stop_with_one_way_on_keeps_its_chip_row_with_that_way_in_it() {
        val scheduler = TestCoroutineScheduler()
        // High Barnet, where every route starts: south is the one way on, by either branch.
        val terminus = StopArrivals(
            stopId = HBT,
            stopName = "High Barnet",
            departures = listOf(Departure("northern", "Northern", "outbound", "Morden", "Southbound - Platform 1", now.plusSeconds(120), "tube", "Bank")),
            fetchedAt = now,
        )
        val oneRoute = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                    if (direction == "outbound") southbound.take(1) else southbound.take(1).map { it.copy(stopIds = it.stopIds.reversed(), direction = "inbound") },
                    names,
                )
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides oneRoute) {
                    MainScreen(state = DeparturesUiState.Loaded(stops = listOf(terminus), fetchedAt = now), now = now, onRefresh = {})
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("Morden", substring = true).performTouchInput { click() }
        settle(scheduler)
        // The room held for the chips is filled by the one, not given up (nothing below moves).
        composeRule.onNodeWithTag("routeChoice-0").assertIsSelected()
        composeRule.onNodeWithTag("routeChoice-1").assertDoesNotExist()
    }

    @Test
    fun chips_from_rows_since_refreshed_follow_no_train_and_claim_no_live_times_until_worked_out_again() {
        val scheduler = TestCoroutineScheduler()
        val board = app.stopdash.domain.DepartureRows.across(listOf(highgate), now)
        var row by mutableStateOf(board.first { it.direction == "outbound" })
        val chosen = ArrayList<app.stopdash.domain.RouteFocus>()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = true,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                        focus = app.stopdash.domain.RouteFocus("Morden", "Bank"),
                        siblings = board,
                        onChooseRoute = { _, focus -> chosen += focus },
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-1").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()

        // The row refreshed, its chips not yet worked out again: no claim there's no train on the way chosen (one
        // may be on it now), and the way north stands but isn't followed to the train the old rows had on it.
        row = row.copy(fetchedAt = now.plusSeconds(1))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No live times").assertDoesNotExist()
        composeRule.onAllNodesWithText("Mornington Crescent").assertCountEquals(1)
        composeRule.onNodeWithTag("routeChoice-2").performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<app.stopdash.domain.RouteFocus>(), chosen)

        // Worked out again from the rows as they are: said, and followed.
        settle(scheduler)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onNodeWithTag("routeChoice-2").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(app.stopdash.domain.RouteFocus("High Barnet", null)), chosen)
    }

    @Test
    fun chips_from_rows_since_refreshed_share_their_keys_rather_than_walk_the_choices_again() {
        val board = app.stopdash.domain.DepartureRows.across(listOf(highgate), now)
        val row = board.first { it.direction == "outbound" }
        val sequence = kotlinx.coroutines.runBlocking { routes.load("northern", "") }
        val choices = RouteChoicesUi.of(
            sequence, row, board, app.stopdash.domain.RouteFocus("Morden", "Bank"), app.stopdash.domain.RouteTopology.EMPTY,
            "%1\$s via %2\$s", "outbound",
        )

        // Made on the main thread as the page recomposes, so it walks nothing: the same keys, found the same way.
        val unsettled = choices.unsettled()
        org.junit.Assert.assertFalse(unsettled.settled)
        org.junit.Assert.assertSame(choices.keys, unsettled.keys)
        assertEquals(choices.keys.indices.toList(), choices.keys.map { unsettled.indexOf(it) })
    }

    @Test
    fun a_ride_two_ways_join_the_ends_of_offers_both_and_lists_the_one_chosen() {
        // A favorite ride from Camden Town to Kennington, by either branch: its page can't say which on its own.
        val scheduler = TestCoroutineScheduler()
        val ride = app.stopdash.domain.TripLeg(
            mode = "tube", lineId = "northern", lineName = "Northern", fromId = CTN, fromName = "Camden Town", toId = KNG,
            toName = "Kennington", departure = Instant.EPOCH, arrival = Instant.EPOCH,
        )
        val row = app.stopdash.domain.DepartureRow(
            stopId = CTN, stopName = "Camden Town", lineId = "northern", lineName = "Northern", direction = "",
            directionKey = app.stopdash.domain.STATUS_DIRECTION_KEY, destination = "", mode = "tube", upcoming = emptyList(), fetchedAt = now,
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides routes) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = false,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                        loadRouteStops = { retry -> rememberLegRouteStops(ride, app.stopdash.domain.StepFree.ANY, retry) },
                        ride = ride,
                        rideChoices = true,
                    )
                }
            }
        }
        settle(scheduler)
        // Both ways, neither chosen, and no "unavailable" for want of a choice.
        composeRule.onNodeWithText("via Bank").assertIsDisplayed()
        composeRule.onNodeWithTag("routeChoice-1").assertIsNotSelected()
        composeRule.onNodeWithText("Stop list unavailable").assertDoesNotExist()
        composeRule.onNodeWithText("Mornington Crescent").assertDoesNotExist()

        composeRule.onNodeWithText("via Charing X").performClick()
        settle(scheduler)
        composeRule.onNodeWithTag("routeChoice-1").assertIsSelected()
        composeRule.onAllNodesWithText("Mornington Crescent").assertCountEquals(1)
        // A ride's page has no live times to miss.
        composeRule.onNodeWithText("No live times").assertDoesNotExist()
    }

    @Test
    fun a_ride_whose_routes_failed_to_load_offers_its_ways_once_retried() {
        val scheduler = TestCoroutineScheduler()
        // Offline for the first ask of each way, back for the rest.
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val flaky = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    if (asked.incrementAndGet() <= 2) throw app.stopdash.domain.TflException.Offline(null)
                    return LineSequence(
                        if (direction == "outbound") southbound else southbound.map { it.copy(stopIds = it.stopIds.reversed(), direction = "inbound") },
                        names,
                    )
                }
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        val ride = app.stopdash.domain.TripLeg(
            mode = "tube", lineId = "northern", lineName = "Northern", fromId = CTN, fromName = "Camden Town", toId = KNG,
            toName = "Kennington", departure = Instant.EPOCH, arrival = Instant.EPOCH,
        )
        val row = app.stopdash.domain.DepartureRow(
            stopId = CTN, stopName = "Camden Town", lineId = "northern", lineName = "Northern", direction = "",
            directionKey = app.stopdash.domain.STATUS_DIRECTION_KEY, destination = "", mode = "tube", upcoming = emptyList(), fetchedAt = now,
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides flaky) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = false,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                        loadRouteStops = { retry -> rememberLegRouteStops(ride, app.stopdash.domain.StepFree.ANY, retry) },
                        ride = ride,
                        rideChoices = true,
                    )
                }
            }
        }
        settle(scheduler)
        composeRule.onNodeWithText("via Bank").assertDoesNotExist()
        // The stop list's own load came back (as ambiguous) after the ways' failed: the failure is said, with a retry.
        composeRule.onNodeWithText("Retry").performClick()
        settle(scheduler)
        composeRule.onNodeWithText("via Bank").assertIsDisplayed()
        composeRule.onNodeWithText("via Charing X").assertIsDisplayed()
    }

    // Highgate's Northern line with no live times, its timetable due both ways: a "?" row (SPEC *Departures*).
    private val quietRow = app.stopdash.domain.DepartureRow(
        stopId = HGT, stopName = "Highgate", lineId = "northern", lineName = "Northern",
        direction = "", directionKey = app.stopdash.domain.STATUS_DIRECTION_KEY, destination = "", mode = "tube",
        upcoming = emptyList(), fetchedAt = now, quiet = true,
    )

    // 09:05 to Morden via Bank, 09:10 via Charing Cross, 09:15 to High Barnet, by each one's calling pattern.
    private val quietTimetable = app.stopdash.domain.StopTimetable(
        listOf(
            app.stopdash.domain.StopTimetable.DaySchedule(
                setOf(java.time.DayOfWeek.WEDNESDAY),
                listOf(9 * 60 + 5, 9 * 60 + 10, 9 * 60 + 15),
                calls = listOf(listOf(CTN, EUS, BNK, KNG, MDN), listOf(CTN, MTC, EUS, CHX, KNG, MDN), listOf(HBT)),
            ),
        ),
    )

    private fun showQuietPage(
        scheduler: TestCoroutineScheduler,
        disruptionUnknown: Boolean = false,
        row: app.stopdash.domain.DepartureRow = quietRow,
        timetable: app.stopdash.domain.StopTimetable = quietTimetable,
        lookup: app.stopdash.domain.EmptyTimes.Lookup = app.stopdash.domain.EmptyTimes.Lookup.Found(timetable),
        unknown: androidx.compose.runtime.State<Boolean>? = null,
        lookups: kotlinx.coroutines.flow.MutableStateFlow<Map<app.stopdash.domain.EmptyTimes.Key, app.stopdash.domain.EmptyTimes.Lookup>> =
            kotlinx.coroutines.flow.MutableStateFlow(mapOf(app.stopdash.domain.EmptyTimes.Key(HGT, "northern") to lookup)),
    ) {
        val emptyTimes = EmptyTimesState(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()), now, lookups = lookups) { _, _ -> }
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(
                    LocalWorker provides StandardTestDispatcher(scheduler),
                    LocalRouteStops provides routes,
                    LocalEmptyTimes provides emptyTimes,
                ) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = false,
                        disruptionUnknown = unknown?.value ?: disruptionUnknown,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                        siblings = listOf(row),
                        onChooseRoute = { _, _ -> },
                    )
                }
            }
        }
        settle(scheduler)
    }

    @Test
    fun a_quiet_rows_page_says_why_and_gives_each_ways_next_trains_by_the_timetable() {
        showQuietPage(TestCoroutineScheduler())
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onNodeWithText("TfL has no live times here, though its timetable has trains due.").assertIsDisplayed()
        composeRule.onNodeWithText("Timetable").assertIsDisplayed()
        // Each way its own trains, by its pattern: none of Bank's are Charing Cross's.
        composeRule.onNodeWithText("09:05").assertIsDisplayed()
        composeRule.onNodeWithText("09:10").assertIsDisplayed()
        composeRule.onNodeWithText("09:15").assertIsDisplayed()
        // The chips still choose a way's stops.
        composeRule.onNodeWithTag("routeChoice-2").assertIsDisplayed()
    }

    @Test
    fun a_quiet_rows_page_whose_stop_couldnt_be_checked_says_why_but_gives_no_timetable() {
        showQuietPage(TestCoroutineScheduler(), disruptionUnknown = true)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onNodeWithText("TfL has no live times here, though its timetable has trains due.").assertIsDisplayed()
        // A timetable knows nothing of a disruption it couldn't check for.
        composeRule.onNodeWithText("Timetable").assertDoesNotExist()
        composeRule.onNodeWithText("09:05").assertDoesNotExist()
    }

    @Test
    fun a_quiet_rows_page_drops_its_timetable_at_once_when_its_stop_can_no_longer_be_checked() {
        val scheduler = TestCoroutineScheduler()
        val unknown = androidx.compose.runtime.mutableStateOf(false)
        showQuietPage(scheduler, unknown = unknown)
        composeRule.onNodeWithText("09:05").assertIsDisplayed()
        // The stop's check fails: its trains go with the frame, not once the worker has worked the page out again
        // (Codex on #1044). The why stays, since it's the timetable's own answer.
        composeRule.runOnIdle { unknown.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Timetable").assertDoesNotExist()
        composeRule.onNodeWithText("09:05").assertDoesNotExist()
        composeRule.onNodeWithText("TfL has no live times here, though its timetable has trains due.").assertIsDisplayed()
    }

    @Test
    fun a_quiet_rows_page_shows_no_times_from_a_timetable_since_replaced_even_while_the_new_ones_are_worked_out() {
        val scheduler = TestCoroutineScheduler()
        val lookups = kotlinx.coroutines.flow.MutableStateFlow<Map<app.stopdash.domain.EmptyTimes.Key, app.stopdash.domain.EmptyTimes.Lookup>>(
            mapOf(app.stopdash.domain.EmptyTimes.Key(HGT, "northern") to app.stopdash.domain.EmptyTimes.Lookup.Found(quietTimetable)),
        )
        showQuietPage(scheduler, lookups = lookups)
        composeRule.onNodeWithText("09:05").assertIsDisplayed()
        // The stop's timetable fetched again, its trains later: the old one's go with the frame, not once the new
        // one's are worked out ([rememberWorked]).
        val later = app.stopdash.domain.StopTimetable(
            listOf(
                app.stopdash.domain.StopTimetable.DaySchedule(
                    setOf(java.time.DayOfWeek.WEDNESDAY),
                    listOf(10 * 60, 10 * 60 + 5, 10 * 60 + 10),
                    calls = listOf(listOf(CTN, EUS, BNK, KNG, MDN), listOf(CTN, MTC, EUS, CHX, KNG, MDN), listOf(HBT)),
                ),
            ),
        )
        composeRule.runOnIdle { lookups.value = mapOf(app.stopdash.domain.EmptyTimes.Key(HGT, "northern") to app.stopdash.domain.EmptyTimes.Lookup.Found(later)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("09:05").assertDoesNotExist()
        settle(scheduler)
        composeRule.onNodeWithText("10:00").assertIsDisplayed()
    }

    @Test
    fun a_quiet_rows_page_whose_timetable_failed_claims_no_trains_due() {
        // A "?" is a train due or a timetable that couldn't say (Codex, #1044): only the timetable says which.
        showQuietPage(TestCoroutineScheduler(), lookup = app.stopdash.domain.EmptyTimes.Lookup.Failed)
        assertNoTrainsClaimed()
    }

    @Test
    fun a_quiet_rows_page_whose_timetable_cant_say_for_today_claims_no_trains_due() {
        showQuietPage(TestCoroutineScheduler(), timetable = app.stopdash.domain.StopTimetable(emptyList()))
        assertNoTrainsClaimed()
    }

    private fun assertNoTrainsClaimed() {
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onNodeWithText("TfL has no live times here.").assertIsDisplayed()
        composeRule.onNodeWithText("TfL has no live times here, though its timetable has trains due.").assertDoesNotExist()
        composeRule.onNodeWithText("Timetable").assertDoesNotExist()
    }

    @Test
    fun a_dash_rows_page_says_none_is_due_and_gives_each_ways_next_trains_by_the_timetable() {
        // The line's row with no train, its timetable's first train an hour off: the list's dash.
        val dash = quietRow.copy(quiet = false)
        val later = app.stopdash.domain.StopTimetable(
            listOf(
                app.stopdash.domain.StopTimetable.DaySchedule(
                    setOf(java.time.DayOfWeek.WEDNESDAY),
                    listOf(10 * 60, 10 * 60 + 5),
                    calls = listOf(listOf(CTN, EUS, BNK, KNG, MDN), listOf(HBT)),
                ),
            ),
        )
        showQuietPage(TestCoroutineScheduler(), row = dash, timetable = later)
        composeRule.onNodeWithText("No live times").assertIsDisplayed()
        composeRule.onNodeWithText("No trains due here in the next 30 minutes, by the timetable.").assertIsDisplayed()
        composeRule.onNodeWithText("10:00").assertIsDisplayed()
        composeRule.onNodeWithText("10:05").assertIsDisplayed()
    }

    @Test
    fun a_train_with_no_destination_offers_how_far_it_goes_at_least_as_a_guess() {
        // TfL names no destination for the next southbound train: both its routes run on to Camden Town first.
        val unknown = StopArrivals(
            stopId = HGT,
            stopName = "Highgate",
            departures = listOf(Departure("northern", "Northern", "outbound", "Check Front of Train", "Southbound - Platform 2", now.plusSeconds(120), "tube")),
            fetchedAt = now,
        )
        val scheduler = TestCoroutineScheduler()
        val board = app.stopdash.domain.DepartureRows.across(listOf(unknown), now)
        val row = board.single { it.direction == "outbound" }
        // The line's routes with their stations' positions, as TfL gives them: a "Southbound" platform reads by them,
        // as any train's ways are found (Codex on #1045).
        val positions = mapOf(
            HBT to (51.6505 to -0.1943), HGT to (51.5777 to -0.1458), CTN to (51.5392 to -0.1426), MTC to (51.5343 to -0.1387),
            EUS to (51.5282 to -0.1337), BNK to (51.5133 to -0.0886), CHX to (51.5080 to -0.1247), KNG to (51.4884 to -0.1053),
            MDN to (51.4022 to -0.1948),
        )
        val placed = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                    if (direction == "outbound") southbound else southbound.map { it.copy(stopIds = it.stopIds.reversed(), direction = "inbound") },
                    names,
                    stopPositions = positions,
                )
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides StandardTestDispatcher(scheduler), LocalRouteStops provides placed) {
                    RouteDetailScreen(
                        row = row,
                        isStarred = false,
                        starrable = true,
                        disruptionUnknown = false,
                        stale = false,
                        now = now,
                        onToggleStar = {},
                        onBack = {},
                        siblings = board,
                        onChooseRoute = { _, _ -> },
                    )
                }
            }
        }
        settle(scheduler)
        // Its own chip, chosen, says the guess, marked as one; TfL's words aren't its destination.
        composeRule.onNodeWithText("At least to Camden Town?").assertIsDisplayed()
        composeRule.onNodeWithText("At least to Camden Town?").assertIsSelected()
        // The ways it could take are chips too.
        composeRule.onNodeWithText("Morden/Bank").assertIsDisplayed()
        composeRule.onNodeWithText("Morden/Charing X").assertIsDisplayed()
    }

    private companion object {
        const val HBT = "940GZZLUHBT"
        const val HGT = "940GZZLUHGT"
        const val CTN = "940GZZLUCTN"
        const val MTC = "940GZZLUMTC"
        const val EUS = "940GZZLUEUS"
        const val BNK = "940GZZLUBNK"
        const val CHX = "940GZZLUCHX"
        const val KNG = "940GZZLUKNG"
        const val MDN = "940GZZLUMDN"
    }
}
