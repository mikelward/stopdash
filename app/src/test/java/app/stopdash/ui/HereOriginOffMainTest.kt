package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.Coordinates
import app.stopdash.domain.LineRef
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.StopLocation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a To… from here starts ([rememberHereOrigin]) is worked out on the worker ([LocalWorker]),
 * never in composition (AGENTS.md *Main thread: read and dispatch only*), and is pending (null),
 * never an empty origin, until it's in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class HereOriginOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Obviously synthetic positions near the origin, never a real place (SPEC *Privacy*).
    private val origin = Coordinates(0.0, 0.0)
    private val bus = StopLocation("A", "A", 0.0005, 0.0, lines = listOf(LineRef("1", "1", "bus")))
    private val tube = StopLocation("B", "B", 0.001, 0.0, lines = listOf(LineRef("red", "Red", "tube")))
    private val ready = NearbyStopsViewModel.State.Ready(
        eager = listOf(
            NearbySelection.NearbyCluster("A", listOf(bus), 55.0),
            NearbySelection.NearbyCluster("B", listOf(tube), 110.0),
        ),
        more = emptyList(),
        distanceMeters = mapOf("A" to 55.0, "B" to 110.0),
        location = origin,
    )

    @Test
    fun the_origin_is_pending_until_the_worker_has_worked_it_out() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var result: List<StopRef>? = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) { result = rememberHereOrigin(ready, emptySet()) }
        }
        composeRule.waitForIdle()
        assertNull(result)
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(setOf("A", "B"), result?.map { it.id }?.toSet())
    }

    @Test
    fun the_stops_are_only_weighed_on_the_worker_thread() {
        // The hidden modes are read once per line weighed: each read notes its thread.
        val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val hidden = object : AbstractSet<String>() {
            private val modes = setOf("bus")
            override val size: Int get() = modes.size
            override fun iterator(): Iterator<String> {
                reads += Thread.currentThread().name.substringBefore(" @")
                return modes.iterator()
            }
        }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "origin-worker") }.asCoroutineDispatcher()
        var result: List<StopRef>? = null
        worker.use {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) { result = rememberHereOrigin(ready, hidden) }
            }
            composeRule.waitUntil(5_000) { result != null }
        }
        // Buses hidden: only the tube stop starts the trip.
        assertEquals(listOf("B"), result?.map { it.id })
        assertTrue(reads.isNotEmpty())
        assertEquals(setOf("origin-worker"), reads.toSet())
    }

    @Test
    fun a_screen_sharing_the_work_has_the_origin_in_its_first_frame() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val work = HereOriginWork()
        val hidden = emptySet<String>()
        var trip by mutableStateOf(false)
        val firstTripFrame = mutableListOf<List<StopRef>?>()
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held) {
                // The list, then the To… it opens: separate slots in composition, one shared holder.
                if (!trip) {
                    rememberHereOrigin(ready, hidden, work)
                } else {
                    val origin = rememberHereOrigin(ready, hidden, work)
                    if (firstTripFrame.isEmpty()) firstTripFrame += origin
                }
            }
        }
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        trip = true
        composeRule.waitForIdle()
        assertEquals(setOf("A", "B"), firstTripFrame.single()?.map { it.id }?.toSet())
    }
}
