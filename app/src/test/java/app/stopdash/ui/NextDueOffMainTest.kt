package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.TripLeg
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trip's fallback train ([rememberNextDue]) is worked out on the page's worker ([LocalWorker]),
 * never the main thread (AGENTS.md *Main thread: read and dispatch only*; Codex, PR #520), and is
 * only ever the current board's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class NextDueOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long): Instant = now.plusSeconds(minutes * 60)

    // Synthetic stops: the ride from A to C.
    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(0), at(10))
    private val red = LineSequence(listOf(LineRoute("A-C", listOf("A", "B", "C"))), mapOf("A" to "A", "B" to "B", "C" to "C"))

    private fun train(minutes: Long) = Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = "EXAMPLE")
    private fun board(vararg minutes: Long) = ActiveTripTracker.NextBoard(ride, minutes.map(::train), fetchedAt = now)

    @Test
    fun the_next_train_is_found_on_the_worker_and_only_for_its_own_board() {
        // Held until released by hand: worked out on the main thread, it would be there at once.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var shown by mutableStateOf(board(-1, 9, 5))
        var routes by mutableStateOf(mapOf<String, LineSequence?>("red" to red))
        var version by mutableStateOf(1)
        var due: Instant? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                due = rememberNextDue(shown, routes, version, readyAt = null, now = now)
            }
        }
        composeRule.waitForIdle()
        assertNull(due)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(at(5), due)
        // A new board without that train: not the old board's answer while the new one's is worked out.
        shown = board(12)
        composeRule.waitForIdle()
        assertNull(due)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(at(12), due)
        // The route replaced in place (a retry), as many lines as before, no longer reaching C: worked out
        // again, and the board's train no longer taken (Codex, PR #520).
        routes = mapOf("red" to LineSequence(listOf(LineRoute("A-Z", listOf("A", "Z"))), mapOf("A" to "A", "Z" to "Z")))
        version = 2
        composeRule.waitForIdle()
        assertNull(due)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertNull(due)
    }
}
