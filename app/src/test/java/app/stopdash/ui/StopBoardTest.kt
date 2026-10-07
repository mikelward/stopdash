package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.StopArrivals
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stop opened from a line's map (SPEC *Finding a line*) leads its board with that line, by platform,
 * then every other service it has; the split is worked out on the page's worker, never the main thread
 * (AGENTS.md *Main thread*). Oxford Circus, a public interchange, with made-up times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class StopBoardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now: Instant = Instant.parse("2026-10-07T09:00:00Z")

    private fun train(line: String, destination: String, platform: String, minutes: Long, direction: String = "outbound") =
        Departure(line, line.replaceFirstChar { it.uppercase() }, direction, destination, platform, now.plusSeconds(minutes * 60), "tube")

    private fun loaded(departures: List<Departure>) = DeparturesUiState.Loaded(
        stops = listOf(StopArrivals("940GZZLUOXC", "Oxford Circus", departures, fetchedAt = now)),
        fetchedAt = now,
    )

    private val board = listOf(
        train("victoria", "Walthamstow Central", "Northbound - Platform 5", 2, "inbound"),
        train("victoria", "Brixton", "Southbound - Platform 6", 3),
        train("central", "Epping", "Eastbound - Platform 1", 1),
        train("bakerloo", "Elephant & Castle", "Southbound - Platform 4", 4),
    )

    @Test
    fun the_line_it_was_opened_from_leads_by_platform_and_the_rest_follow() {
        val view = stopBoardView(loaded(board), "victoria", now)
        // Victoria's two platforms, each a card, and nothing else in them.
        assertEquals(2, view.line.size)
        assertTrue(view.line.all { card -> card.group.rows.all { it.lineId == "victoria" } })
        // The Central and Bakerloo lines after them, Victoria in none.
        val others = view.others.flatMap { card -> card.group.rows.map { it.lineId } }.toSet()
        assertEquals(setOf("central", "bakerloo"), others)
    }

    @Test
    fun a_line_with_nothing_due_leaves_its_part_empty_and_the_rest_still_show() {
        val view = stopBoardView(loaded(board), "jubilee", now)
        assertTrue(view.line.isEmpty())
        assertEquals(setOf("victoria", "central", "bakerloo"), view.others.flatMap { card -> card.group.rows.map { it.lineId } }.toSet())
    }

    @Test
    fun with_no_line_leading_every_service_is_on_the_board() {
        // A station opened from another's details, off the line the page was opened from.
        val view = stopBoardView(loaded(board), null, now)
        assertTrue(view.line.isEmpty())
        assertEquals(setOf("victoria", "central", "bakerloo"), view.others.flatMap { card -> card.group.rows.map { it.lineId } }.toSet())
    }

    @Test
    fun a_station_under_two_ids_is_one_place_on_the_board() {
        // St Pancras's two records, both serving one platform's trains: one card, not two of one name (Codex on #664).
        val refs = lineStopRefs("910GSTPX", "St Pancras International", emptyList(), listOf("910GSTPADOM"))
        assertEquals(setOf("910GSTPX"), refs.map { it.clusterId }.toSet())
        assertEquals("", lineStopRefs("940GZZLUOXC", "Oxford Circus", emptyList(), emptyList()).single().clusterId)
        fun rail(destination: String, minutes: Long) =
            Departure("thameslink", "Thameslink", "outbound", destination, "Platform A", now.plusSeconds(minutes * 60), "national-rail")
        fun board(cluster: String) = DeparturesUiState.Loaded(
            stops = refs.mapIndexed { i, ref ->
                StopArrivals(ref.id, ref.name, listOf(rail(if (i == 0) "Brighton" else "Bedford", i + 2L)), fetchedAt = now, clusterId = cluster)
            },
            fetchedAt = now,
        )
        assertEquals(1, stopBoardView(board(refs.first().clusterId), null, now).others.size)
        // Each id its own cluster, as before: two places of one name.
        assertEquals(2, stopBoardView(board(""), null, now).others.size)
    }

    @Test
    fun a_closure_notice_comes_out_ahead_of_the_cards_never_dropped() {
        // Grouping leaves a stop's own notice to its caller: the board keeps it (Codex on #661).
        val closed = DeparturesUiState.Loaded(
            stops = listOf(
                StopArrivals(
                    "940GZZLUOXC", "Oxford Circus", board, fetchedAt = now,
                    disruptions = listOf(app.stopdash.domain.StopDisruption("Station closed: example notice")),
                ),
            ),
            fetchedAt = now,
        )
        val view = stopBoardView(closed, "victoria", now)
        assertEquals(1, view.closures.size)
        assertTrue(view.closures.single().stopDisruption!!.contains("example notice"))
        assertTrue((view.line + view.others).all { card -> card.group.rows.none { it.stopDisruption != null } })
    }

    @Test
    fun the_stamp_says_a_disruption_check_still_out_or_failed_rather_than_its_age() {
        // Arrivals in, their disruption checks not: the times never read as checked clean (Codex on #661).
        var state by androidx.compose.runtime.mutableStateOf(loaded(board).copy(statusPending = true, disruptionUnknown = true))
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides kotlinx.coroutines.Dispatchers.Unconfined) {
                val departures = StopDepartures(state, now, onRefresh = {})
                val view = rememberStopBoard(departures, "victoria")
                androidx.compose.foundation.lazy.LazyColumn {
                    stopBoard(departures, view, "Victoria")
                }
            }
        }
        composeRule.onNodeWithTag("stopBoardStamp").assertTextEquals("Checking…")
        state = loaded(board).copy(disruptionUnknown = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stopBoardStamp").assertTextEquals("Couldn't check for disruptions")
        state = loaded(board)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stopBoardStamp").assertTextEquals("Just now")
    }

    @Test
    fun a_suspended_line_with_nothing_due_shows_its_status_where_its_trains_would_be() {
        // The line it was opened from is declared served there: suspended with nothing due, its status takes
        // its place on the board, never a bare "No departures" (Codex on #661).
        val victoria = app.stopdash.domain.LineRef("victoria", "Victoria", "tube")
        val suspended = app.stopdash.domain.LineStatus("victoria", 6, "Suspended")
        val state = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("940GZZLUOXC", "Oxford Circus", board.filter { it.lineId != "victoria" }, fetchedAt = now, lines = listOf(victoria))),
            fetchedAt = now,
            lineStatuses = mapOf("victoria" to suspended),
            determinedLineIds = setOf("victoria"),
        )
        val view = stopBoardView(state, "victoria", now)
        assertTrue(view.line.isNotEmpty())
        assertEquals(suspended, view.line.flatMap { it.group.rows }.single().status)
    }

    @Test
    fun a_closure_dismissed_elsewhere_stays_dismissed_on_the_board() {
        // Dismissals are place-wide and persisted: the board applies them as the near-me list does (Codex on #661).
        val closed = DeparturesUiState.Loaded(
            stops = listOf(
                StopArrivals(
                    "940GZZLUOXC", "Oxford Circus", board, fetchedAt = now,
                    disruptions = listOf(app.stopdash.domain.StopDisruption("Station closed: example notice")),
                ),
            ),
            fetchedAt = now,
        )
        val notice = stopBoardView(closed, "victoria", now).closures.single()
        val dismissed = setOf(app.stopdash.domain.DismissedAlert.ofStopClosure(notice))
        assertTrue(stopBoardView(closed, "victoria", now, dismissed = dismissed).closures.isEmpty())
    }

    @Test
    fun an_empty_line_after_a_failed_refresh_or_gone_stale_is_not_claimed_as_none_due() {
        // "No departures" only where the board can stand behind it (Codex on #661).
        val others = board.filter { it.lineId != "victoria" }
        assertFalse(stopBoardView(loaded(others), "victoria", now).emptyUncertain)
        val failed = loaded(others).copy(refreshFailure = DeparturesUiState.Error.Kind.NETWORK)
        assertTrue(stopBoardView(failed, "victoria", now).emptyUncertain)
        assertTrue(stopBoardView(loaded(others), "victoria", now.plus(java.time.Duration.ofMinutes(10))).emptyUncertain)
    }

    @Test
    fun a_partial_refresh_is_said_over_the_cards_it_kept() {
        // Arrivals that couldn't be refreshed while another check could are kept, and said so (Codex on #661).
        // Made once, as the model's flow hands out one state: the board's work is keyed by its identity.
        val partial = loaded(board).copy(partialRefresh = true)
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides kotlinx.coroutines.Dispatchers.Unconfined) {
                val departures = StopDepartures(partial, now, onRefresh = {})
                val view = rememberStopBoard(departures, "victoria")
                androidx.compose.foundation.lazy.LazyColumn {
                    stopBoard(departures, view, "Victoria")
                }
            }
        }
        composeRule.onNodeWithText("Some stops couldn't be refreshed").assertExists()
    }

    @Test
    fun the_split_runs_on_the_worker_thread_never_the_caller_s() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "board-worker") }
        val worker = executor.asCoroutineDispatcher()
        try {
            val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
            val departures = StopDepartures(loaded(NotingList(board, reads)), now, onRefresh = {})
            var view: StopBoardView? = null
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    view = rememberStopBoard(departures, "victoria")
                }
            }
            composeRule.waitUntilWorked(executor) { view != null }
            assertEquals(2, view!!.line.size)
            assertTrue(reads.isNotEmpty())
            assertEquals(setOf("board-worker"), reads.toSet())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun the_board_keeps_its_services_by_route_so_a_route_page_finds_its_row_again() {
        val view = stopBoardView(loaded(board), "victoria", now)
        assertEquals(4, view.rowsByKey.size)
        view.rowsByKey.forEach { (key, row) -> assertEquals(key, row.detailKey()) }
        assertEquals(setOf("victoria", "central", "bakerloo"), view.rowsByKey.values.map { it.lineId }.toSet())
    }

    @Test
    fun a_bus_alert_wholly_behind_the_stop_is_muted_on_its_board_and_route_page_as_on_the_near_me_list() {
        // A made-up bus route north from Bank, and a diversion in TfL's words on a stretch at its south end.
        val route = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("Bank - North End", listOf("b1", "b2", "b3", "b4", "b5"), "inbound")),
            mapOf("b1" to "Bank / King William Street", "b2" to "Example Street", "b3" to "Moorgate", "b4" to "Alpha Road", "b5" to "North End"),
        )
        val diversion = app.stopdash.domain.LineStatus(
            "99", 5, "Diversion",
            "Buses are not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'.",
            soleAlert = true,
        )
        val state = DeparturesUiState.Loaded(
            stops = listOf(
                StopArrivals("b4", "Alpha Road", listOf(Departure("99", "99", "inbound", "North End", "", now.plusSeconds(120), "bus")), fetchedAt = now),
            ),
            fetchedAt = now,
            lineStatuses = mapOf("99" to diversion),
        )
        // Past the stretch: the row the route page opens on keeps the alert to show muted, unflagged.
        val placed = stopBoardView(state, "99", now, alertSequences = mapOf("99" to route)).rowsByKey.values.single()
        assertEquals(null, placed.status)
        assertEquals(diversion, placed.statusBehind)
        // With no route in, the alert stays on.
        assertEquals(diversion, stopBoardView(state, "99", now).rowsByKey.values.single().status)
        // The board asks for the line's route itself, through the route pages' cache, and mutes the alert once it's in.
        val repository = app.stopdash.domain.RouteStopsRepository(
            object : app.stopdash.domain.RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = route
            },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            compute = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        var view: StopBoardView? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalWorker provides kotlinx.coroutines.Dispatchers.Unconfined,
                LocalRouteStops provides repository,
            ) {
                view = rememberStopBoard(StopDepartures(state, now, onRefresh = {}), "99")
            }
        }
        composeRule.waitUntil(5_000) { view?.rowsByKey?.values?.singleOrNull()?.statusBehind != null }
        assertEquals(null, view!!.rowsByKey.values.single().status)
    }

    @Test
    fun a_route_held_past_the_cache_s_day_is_asked_for_again() {
        val route = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("Bank - North End", listOf("b1", "b2", "b3", "b4", "b5"), "inbound")),
            mapOf("b1" to "Bank / King William Street", "b2" to "Example Street", "b3" to "Moorgate", "b4" to "Alpha Road", "b5" to "North End"),
        )
        val diversion = app.stopdash.domain.LineStatus(
            "99", 5, "Diversion",
            "Buses are not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'.",
            soleAlert = true,
        )
        fun board(at: Instant) = DeparturesUiState.Loaded(
            stops = listOf(
                StopArrivals("b4", "Alpha Road", listOf(Departure("99", "99", "inbound", "North End", "", at.plusSeconds(120), "bus")), fetchedAt = at),
            ),
            fetchedAt = at,
            lineStatuses = mapOf("99" to diversion),
        )
        var clock = now
        var asked = 0
        val repository = app.stopdash.domain.RouteStopsRepository(
            object : app.stopdash.domain.RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): app.stopdash.domain.LineSequence {
                    asked++
                    return route
                }
            },
            clock = { clock },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            compute = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        var departures by androidx.compose.runtime.mutableStateOf(StopDepartures(board(now), now, onRefresh = {}))
        var view: StopBoardView? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalWorker provides kotlinx.coroutines.Dispatchers.Unconfined,
                LocalRouteStops provides repository,
            ) {
                view = rememberStopBoard(departures, "99")
            }
        }
        composeRule.waitUntil(5_000) { view?.rowsByKey?.values?.singleOrNull()?.statusBehind != null }
        val first = asked
        assertTrue(first > 0)
        // A refresh within the day asks the cache, not TfL.
        departures = StopDepartures(board(now.plusSeconds(60)), now.plusSeconds(60), onRefresh = {})
        composeRule.waitForIdle()
        assertEquals(first, asked)
        // The details still up a day and more later: the next refresh asks for the route again.
        clock = now.plus(java.time.Duration.ofHours(25))
        departures = StopDepartures(board(clock), clock, onRefresh = {})
        composeRule.waitForIdle()
        assertTrue(asked > first)
    }

    @Test
    fun a_dismissed_bus_alert_asks_for_no_route() {
        val diversion = app.stopdash.domain.LineStatus(
            "99", 5, "Diversion",
            "Buses are not serving stops between 'Bank Station/King William Street' and 'Moorgate Station'.",
            soleAlert = true,
        )
        val state = DeparturesUiState.Loaded(
            stops = listOf(
                StopArrivals("b4", "Alpha Road", listOf(Departure("99", "99", "inbound", "North End", "", now.plusSeconds(120), "bus")), fetchedAt = now),
            ),
            fetchedAt = now,
            lineStatuses = mapOf("99" to diversion),
        )
        // The rider dismissed it already: its route couldn't change the board, so none is asked for.
        val dismissed = setOfNotNull(app.stopdash.domain.DismissedAlert.of(DepartureRows.across(state.stops, now, state.lineStatuses).single()))
        assertEquals(1, dismissed.size)
        var asked = 0
        val repository = app.stopdash.domain.RouteStopsRepository(
            object : app.stopdash.domain.RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): app.stopdash.domain.LineSequence {
                    asked++
                    error("not asked for")
                }
            },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            compute = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        var view: StopBoardView? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalWorker provides kotlinx.coroutines.Dispatchers.Unconfined,
                LocalRouteStops provides repository,
            ) {
                view = rememberStopBoard(StopDepartures(state, now, onRefresh = {}, dismissed = dismissed), "99")
            }
        }
        composeRule.waitUntil(5_000) { view != null }
        composeRule.waitForIdle()
        assertEquals(0, asked)
    }

    @Test
    fun a_row_tapped_on_the_board_opens_its_route_page_and_back_returns_to_the_stop() {
        val state = loaded(board)
        var opened: Pair<String, String?>? = null
        var routeKey by androidx.compose.runtime.mutableStateOf<String?>(null)
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides kotlinx.coroutines.Dispatchers.Unconfined) {
                val departures = StopDepartures(state, now, onRefresh = {})
                val view = rememberStopBoard(departures, "victoria")
                androidx.compose.foundation.lazy.LazyColumn {
                    stopBoard(departures, view, "Victoria") { row, focus ->
                        opened = row.lineId to focus?.destination
                        routeKey = row.detailKey()
                    }
                }
                routeKey?.let { key -> StopRoutePage(view, key, null, androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, onBack = { routeKey = null }) }
            }
        }
        composeRule.onNodeWithText("Brixton").performClick()
        composeRule.waitForIdle()
        assertEquals("victoria" to "Brixton", opened)
        // The route page is up over the board; its Back closes it, back to the stop.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        assertEquals(null, routeKey)
    }

    /** [items], noting the thread of each pass over it in [reads]. */
    private class NotingList<T>(private val items: List<T>, private val reads: MutableList<String>) : AbstractList<T>() {
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
        override fun iterator(): Iterator<T> {
            reads += Thread.currentThread().name.substringBefore(" @")
            return items.iterator()
        }
    }
}
