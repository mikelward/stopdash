package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.Departure
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
