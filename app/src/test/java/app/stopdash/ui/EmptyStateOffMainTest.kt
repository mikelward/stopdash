package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.ThreadRecorder
import app.stopdash.domain.StopArrivals
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Whether an empty list can be trusted ([rememberEmptyStateUncertain]): the pass over the stops runs
 * on the worker once per state ([LocalWorker]), never in composition (AGENTS.md *Main thread: read
 * and dispatch only*). A new state is untrusted until its pass is in; each tick after that is exact
 * at once, from the two stamps it found and the state's flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class EmptyStateOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fetched = Instant.parse("2026-01-01T12:00:00Z")
    private val stop = StopArrivals("A", "A", emptyList(), fetched)

    @Test
    fun a_new_state_is_untrusted_until_its_pass_is_in_and_each_tick_is_exact_at_once() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var state by mutableStateOf(DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = fetched))
        var now by mutableStateOf(fetched.plusSeconds(10))
        var uncertain: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { uncertain = rememberEmptyStateUncertain(state, now) }
        }
        composeRule.waitForIdle()
        assertEquals("untrusted before its pass is in", true, uncertain)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals("a fresh stop: trusted", false, uncertain)

        // An hour on, the stop is stale: told at once, with the worker held.
        now = fetched.plusSeconds(3_600)
        composeRule.waitForIdle()
        assertEquals(true, uncertain)
        now = fetched.plusSeconds(20)
        composeRule.waitForIdle()
        assertEquals(false, uncertain)

        // The same stops, now partial: told at once too.
        state = state.copy(partialRefresh = true)
        composeRule.waitForIdle()
        assertEquals(true, uncertain)

        // New stops: untrusted until their pass is in, never the last answer standing in.
        state = DeparturesUiState.Loaded(stops = listOf(stop.copy(stopId = "B")), fetchedAt = fetched)
        composeRule.waitForIdle()
        assertEquals(true, uncertain)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(false, uncertain)
    }

    @Test
    fun the_oldest_and_newest_stamps_stand_for_every_stop() {
        val stops = listOf(
            stop.copy(stopId = "B", fetchedAt = fetched.plusSeconds(30)),
            stop,
            stop.copy(stopId = "C", fetchedAt = fetched.plusSeconds(60)),
        )
        val stamps = stopStamps(stops)
        assertEquals(fetched, stamps.oldest)
        assertEquals(fetched.plusSeconds(60), stamps.newest)
        val state = DeparturesUiState.Loaded(stops = stops, fetchedAt = fetched.plusSeconds(60))
        // Each answer as going through every stop gives it, an hour either side.
        for (seconds in listOf(-3_600L, 0L, 90L, 600L, 3_600L)) {
            val now = fetched.plusSeconds(seconds)
            val everyStop = stops.any { app.stopdash.domain.Staleness.isStale(it.fetchedAt, now) }
            assertEquals("at $seconds s", everyStop, emptyStateUncertain(state, stamps, now))
        }
    }

    @Test
    fun the_stops_are_only_aged_on_the_worker_thread() {
        // The stops, in a list noting each thread going through it.
        val reads = ThreadRecorder()
        val stops = object : AbstractList<StopArrivals>() {
            override val size: Int get() = 1
            override fun get(index: Int): StopArrivals = stop
            override fun iterator(): Iterator<StopArrivals> {
                reads.note()
                return listOf(stop).iterator()
            }
        }
        val state = DeparturesUiState.Loaded(stops = stops, fetchedAt = fetched)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "empty-worker") }.asCoroutineDispatcher()
        var answers = 0
        var uncertain = true
        worker.use {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    uncertain = rememberEmptyStateUncertain(state, fetched.plusSeconds(10))
                    if (!uncertain) answers++
                }
            }
            composeRule.waitUntil(5_000) { answers > 0 }
        }
        assertFalse(uncertain)
        assertTrue(reads.threads().isNotEmpty())
        assertEquals(setOf("empty-worker"), reads.threads().toSet())
    }
}
