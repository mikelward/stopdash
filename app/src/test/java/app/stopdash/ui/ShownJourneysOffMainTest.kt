package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.Coordinates
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.FavoriteJourney
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The saved journeys as the list shows them ([rememberShownJourneys]) are worked out on the list's
 * worker ([LocalWorker]), never the main thread (AGENTS.md *Main thread: read and dispatch only*).
 * Obviously synthetic positions near the origin, never a real place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ShownJourneysOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val here = Coordinates(0.0, 0.0)

    // From a stop beside the rider to one a little way off, and one well over a mile from both ends.
    private val near = FavoriteJourney(JourneyEnd("A", "Aye", 0.001, 0.0), JourneyEnd("B", "Bee", 0.01, 0.0), "example")
    private val far = FavoriteJourney(JourneyEnd("C", "Cee", 0.1, 0.0), JourneyEnd("D", "Dee", 0.11, 0.0), "example")

    @Test
    fun the_journeys_are_oriented_and_measured_on_the_worker() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val walkedOn = mutableListOf<String>()
            // Read by the work as it walks the saved journeys, so it says where that ran.
            val saved = object : List<FavoriteJourney> by listOf(near, far) {
                override fun iterator(): Iterator<FavoriteJourney> {
                    walkedOn += Thread.currentThread().name.substringBefore(" @")
                    return listOf(near, far).iterator()
                }
            }
            var shown: ShownJourneys? = null
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides pool.asCoroutineDispatcher()) {
                    val slot = remember { mutableStateOf<Worked<Inputs, ShownJourneys>?>(null) }
                    shown = rememberShownJourneys(slot, saved, here, flipped = listOf(near.key), fixConfirmed = true).shown
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { shown != null }
            assertEquals(setOf("test-worker"), walkedOn.toSet())
            // The flipped one is turned round; the far one is held back, measured to its nearer end.
            assertEquals(listOf("B", "C"), shown!!.journeys.map { it.from.stopId })
            assertEquals(setOf(far.key), shown!!.farMeters.keys)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun losing_a_confirmed_fix_holds_no_journey_back_while_the_worker_catches_up() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var confirmed by mutableStateOf(true)
        var shown: ShownJourneys? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                val slot = remember { mutableStateOf<Worked<Inputs, ShownJourneys>?>(null) }
                shown = rememberShownJourneys(slot, listOf(near, far), here, flipped = emptyList(), fixConfirmed = confirmed).shown
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(setOf(far.key), shown!!.farMeters.keys)
        // The fix is no longer confirmed, the worker not yet run: every journey shows at once (Codex, #593).
        confirmed = false
        composeRule.waitForIdle()
        assertEquals(emptySet<String>(), shown!!.farMeters.keys)
        assertEquals(2, shown!!.journeys.size)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(emptySet<String>(), shown!!.farMeters.keys)
    }

    @Test
    fun another_nearby_set_starts_fresh_never_with_the_last_sets_journeys() {
        // The screen keeps one slot per nearby set (MainActivity): a relocation to another set, with its
        // own departures model, never sees journeys worked out for the last fix stand in (Codex, #593).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var set by mutableStateOf("first")
        var at by mutableStateOf(here)
        var shown: ShownJourneys? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                val slot = remember(set) { mutableStateOf<Worked<Inputs, ShownJourneys>?>(null) }
                shown = rememberShownJourneys(slot, listOf(near, far), at, flipped = emptyList(), fixConfirmed = true).shown
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(setOf(far.key), shown!!.farMeters.keys)
        // Moved to the far journey's end, another nearby set: nothing until its own answer is in.
        set = "second"
        at = Coordinates(0.1, 0.0)
        composeRule.waitForIdle()
        assertNull(shown)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(setOf(near.key), shown!!.farMeters.keys)
    }

    @Test
    fun an_answer_standing_in_for_a_changed_saved_list_isnt_current() {
        // A star or unstar since: the cards keep the last answer, but it isn't current, so nothing is
        // written for the widget from it (Codex, #593).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var saved by mutableStateOf(listOf(near))
        var worked: WorkedJourneys? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                val slot = remember { mutableStateOf<Worked<Inputs, ShownJourneys>?>(null) }
                worked = rememberShownJourneys(slot, saved, here, flipped = emptyList(), fixConfirmed = true)
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertTrue(worked!!.current)
        saved = listOf(near, far)
        composeRule.waitForIdle()
        assertEquals(listOf(near.key), worked!!.shown!!.journeys.map { it.key })
        assertFalse(worked!!.current)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertTrue(worked!!.current)
        assertEquals(2, worked!!.shown!!.journeys.size)
    }

    @Test
    fun nothing_worked_out_before_the_journeys_were_read_stands_in_for_them() {
        // Held until released by hand.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var saved by mutableStateOf<List<FavoriteJourney>?>(null)
        var shown: ShownJourneys? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                val slot = remember { mutableStateOf<Worked<Inputs, ShownJourneys>?>(null) }
                shown = rememberShownJourneys(slot, saved, here, flipped = emptyList(), fixConfirmed = true).shown
            }
        }
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        // Not read yet: none.
        assertNull(shown)
        saved = listOf(near)
        composeRule.waitForIdle()
        // Read, and being worked out: still none, never the "no journeys" answer from before the read.
        assertNull(shown)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(listOf(near.key), shown!!.journeys.map { it.key })
        // A later change keeps the last answer up while the new one is worked out.
        saved = listOf(near, far)
        composeRule.waitForIdle()
        assertEquals(listOf(near.key), shown!!.journeys.map { it.key })
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(2, shown!!.journeys.size)
    }
}
