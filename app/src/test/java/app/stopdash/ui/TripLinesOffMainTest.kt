package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.LineStatus
import app.stopdash.domain.TripLeg
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The trip's lines page keeps the order it opened with ([rememberOpenedOrder]), re-ordered on the
 * worker ([LocalWorker]), never in composition (AGENTS.md *Main thread: read and dispatch only*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class TripLinesOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val at = Instant.parse("2026-09-26T07:00:00Z")

    private fun line(id: String, severity: Int) = TripLine(
        TripLeg(mode = "tube", lineId = id, lineName = id, fromId = "A", fromName = "A", toId = "B", toName = "B", departure = at, arrival = at),
        LineStatus(id, severity, if (severity == LineStatus.GOOD_SERVICE) "Good Service" else "Severe Delays"),
    )

    @Test
    fun a_line_holds_its_place_while_the_page_is_open() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var row by mutableStateOf(TripRow(checking = false, every = listOf(line("red", 6), line("blue", LineStatus.GOOD_SERVICE))))
        var shown: List<TripLine>? = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { shown = rememberOpenedOrder(row).lines }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("red", "blue"), shown.orEmpty().map { it.leg.lineId })

        // The blue's check lands disrupted and the red's clears: the worker sorts it first, the page
        // keeps each where it was, and a new line goes after them.
        row = TripRow(checking = false, every = listOf(line("green", 6), line("blue", 6), line("red", LineStatus.GOOD_SERVICE)))
        composeRule.waitForIdle()
        // Nothing worked out on the main thread: the last order stands until the worker's is in.
        assertEquals(listOf(6, LineStatus.GOOD_SERVICE), shown.orEmpty().map { it.status?.severity })
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf("red", "blue", "green"), shown.orEmpty().map { it.leg.lineId })
        assertEquals(listOf(LineStatus.GOOD_SERVICE, 6, 6), shown.orEmpty().map { it.status?.severity })
    }

    @Test
    fun a_rotation_with_the_page_open_keeps_its_order() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var row by mutableStateOf(TripRow(checking = false, every = listOf(line("red", 6), line("blue", LineStatus.GOOD_SERVICE))))
        var shown: List<TripLine>? = emptyList()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CompositionLocalProvider(LocalWorker provides held) { shown = rememberOpenedOrder(row).lines }
        }
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        // The checks land re-sorted, then the screen rotates with the page open (Codex, #559).
        row = TripRow(checking = false, every = listOf(line("blue", 6), line("red", LineStatus.GOOD_SERVICE)))
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        // Before the worker applies the saved order, no lines: never a frame in the re-sorted order.
        assertEquals(null, shown)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf("red", "blue"), shown.orEmpty().map { it.leg.lineId })
    }

    @Test
    fun a_line_first_seen_after_the_page_opened_keeps_its_place_too() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        // Opened while the row was still checking, with no lines yet.
        var row by mutableStateOf(TripRow.CHECKING)
        var shown: List<TripLine>? = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { shown = rememberOpenedOrder(row).lines }
        }
        fun settle() {
            composeRule.waitForIdle()
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        settle()
        row = TripRow(checking = false, every = listOf(line("red", 6), line("blue", LineStatus.GOOD_SERVICE)))
        settle()
        assertEquals(listOf("red", "blue"), shown.orEmpty().map { it.leg.lineId })
        // The blue turns disrupted and sorts first: each keeps the place it first came in at (Codex, #559).
        row = TripRow(checking = false, every = listOf(line("blue", 6), line("red", LineStatus.GOOD_SERVICE)))
        settle()
        assertEquals(listOf("red", "blue"), shown.orEmpty().map { it.leg.lineId })
    }

    @Test
    fun a_line_gone_from_the_trip_keeps_its_place_while_the_page_is_open() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var row by mutableStateOf(TripRow(checking = false, every = listOf(line("red", 6), line("blue", LineStatus.GOOD_SERVICE), line("green", LineStatus.GOOD_SERVICE))))
        var shown: List<TripLine>? = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { shown = rememberOpenedOrder(row).lines }
        }
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        // The blue's trains leave the stop and it drops off the trip (Codex, #559): its row stays, saying
        // so, and the green under it never moves up.
        row = TripRow(checking = false, every = listOf(line("red", 6), line("green", LineStatus.GOOD_SERVICE)))
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf("red", "blue", "green"), shown.orEmpty().map { it.leg.lineId })
        assertEquals(listOf(false, true, false), shown.orEmpty().map { it.gone })
        assertEquals(null, shown.orEmpty()[1].status)
        // A line first seen after the page opened, then gone again, keeps its place too.
        row = TripRow(checking = false, every = listOf(line("red", 6), line("green", LineStatus.GOOD_SERVICE), line("pink", LineStatus.GOOD_SERVICE), line("cyan", LineStatus.GOOD_SERVICE)))
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        row = TripRow(checking = false, every = listOf(line("red", 6), line("green", LineStatus.GOOD_SERVICE), line("cyan", LineStatus.GOOD_SERVICE)))
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf("red", "blue", "green", "pink", "cyan"), shown.orEmpty().map { it.leg.lineId })
        assertEquals(listOf(false, true, false, true, false), shown.orEmpty().map { it.gone })
    }

    @Test
    fun a_gone_line_keeps_its_place_through_a_rotation() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var row by mutableStateOf(TripRow(checking = false, every = listOf(line("red", 6), line("blue", LineStatus.GOOD_SERVICE), line("green", LineStatus.GOOD_SERVICE))))
        var shown: List<TripLine>? = emptyList()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CompositionLocalProvider(LocalWorker provides held) { shown = rememberOpenedOrder(row).lines }
        }
        fun settle() {
            composeRule.waitForIdle()
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        settle()
        row = TripRow(checking = false, every = listOf(line("red", 6), line("green", LineStatus.GOOD_SERVICE)))
        settle()
        // Rotated after the blue went (Codex, #559): its placeholder is still there, named.
        restoration.emulateSavedInstanceStateRestore()
        settle()
        assertEquals(listOf("red", "blue", "green"), shown.orEmpty().map { it.leg.lineId })
        assertEquals(listOf(false, true, false), shown.orEmpty().map { it.gone })
        assertEquals("blue", shown.orEmpty()[1].leg.lineName)
    }

    @Test
    fun a_saved_line_missing_while_the_trip_still_checks_is_pending_not_gone() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var row by mutableStateOf(TripRow(checking = false, every = listOf(line("red", 6), line("blue", LineStatus.GOOD_SERVICE))))
        var shown: List<TripLine>? = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { shown = rememberOpenedOrder(row).lines }
        }
        fun settle() {
            composeRule.waitForIdle()
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
        settle()
        // The trip checks again with nothing in yet (rebuilt, a refresh): the lines aren't gone (Codex, #559).
        row = TripRow.CHECKING
        settle()
        assertEquals(listOf(false, false), shown.orEmpty().map { it.gone })
        assertEquals(listOf(true, true), shown.orEmpty().map { it.checking })
        // Only a settled check leaving one out makes it gone.
        row = TripRow(checking = false, every = listOf(line("red", 6)))
        settle()
        assertEquals(listOf(false, true), shown.orEmpty().map { it.gone })
    }

    @Test
    fun a_rotation_before_the_worker_answers_keeps_the_order_the_page_opened_with() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var row by mutableStateOf(TripRow(checking = false, every = listOf(line("red", 6), line("blue", LineStatus.GOOD_SERVICE))))
        var shown: List<TripLine>? = emptyList()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CompositionLocalProvider(LocalWorker provides held) { shown = rememberOpenedOrder(row).lines }
        }
        composeRule.waitForIdle()
        // The checks land re-sorted and the screen rotates, all before the worker has run once (Codex, #559).
        row = TripRow(checking = false, every = listOf(line("blue", 6), line("red", LineStatus.GOOD_SERVICE)))
        composeRule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf("red", "blue"), shown.orEmpty().map { it.leg.lineId })
    }

    @Test
    fun only_a_line_that_couldnt_be_checked_says_its_times_may_be_wrong() {
        // A stop's closure check failed, every line is in: the stop is named, no line is doubted (Codex, #559).
        val stopOnly = TripRow(checking = false, unknown = true, unknownStops = "Euston", every = listOf(line("red", LineStatus.GOOD_SERVICE)))
        assertEquals(false, stopOnly.linesUnknown)
        val lineToo = TripRow(checking = false, unknown = true, every = listOf(line("red", LineStatus.GOOD_SERVICE).copy(unknown = true)))
        assertEquals(true, lineToo.linesUnknown)
    }
}
