package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
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

    private fun settle(scheduler: TestCoroutineScheduler) {
        repeat(5) {
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
