package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.ThreadRecorder
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TflException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which lines' route data is loading ([rememberLineLoads]): a retry of a failed load included. Synthetic lines only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LineLoadsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val route = LineSequence(routes = listOf(LineRoute("A ↔ B", listOf("A", "B"))), stopNames = mapOf("A" to "A", "B" to "B"))

    @Test
    fun `a failed route's retry is under way, not failed`() {
        var failing = true
        // Each request after the first waits here until the test lets it answer.
        var gate: CompletableDeferred<Unit>? = null
        val source = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                if (failing) throw TflException.Offline(null)
                gate?.await()
                return route
            }
        }
        val repository = RouteStopsRepository(source)
        var now by mutableStateOf(Instant.parse("2026-09-30T08:00:00Z"))
        var loads: LineLoads? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository) {
                loads = rememberLineLoads(listOf("1"), now)
            }
        }
        composeRule.waitForIdle()
        // Failed: held as null, and nothing under way.
        assertTrue("1" in loads!!.sequences)
        assertNull(loads!!.sequences["1"])
        assertFalse("1" in loads!!.loading)
        // The hourly retry: still null while it runs, but under way.
        failing = false
        val slow = CompletableDeferred<Unit>().also { gate = it }
        now = now.plus(Duration.ofHours(1))
        composeRule.waitForIdle()
        assertNull(loads!!.sequences["1"])
        assertTrue("1" in loads!!.loading)
        // Answered: loaded, and no longer under way.
        slow.complete(Unit)
        composeRule.waitForIdle()
        // Both directions, each answered with the one route.
        assertEquals(route.routes + route.routes, loads!!.sequences["1"]?.routes)
        assertFalse("1" in loads!!.loading)
    }

    @Test
    fun `a line still wanted keeps its load when another joins`() {
        val gate = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val source = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                asked += lineId
                gate.await()
                return route
            }
        }
        val repository = RouteStopsRepository(source)
        var lineIds by mutableStateOf(listOf("1"))
        var loads: LineLoads? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository) {
                loads = rememberLineLoads(lineIds, Instant.parse("2026-09-30T08:00:00Z"))
            }
        }
        composeRule.waitForIdle()
        // A plan's next answer adds a line while the first is still being fetched.
        lineIds = listOf("1", "2")
        composeRule.waitForIdle()
        gate.complete(Unit)
        composeRule.waitForIdle()
        // Line 1 asked once each way, not again: its fetch carried on rather than start over.
        assertEquals(2, asked.count { it == "1" })
        assertEquals(setOf("1", "2"), loads!!.sequences.keys)
        assertTrue(loads!!.loading.isEmpty())
    }

    @Test
    fun `which lines to load is worked out on the worker`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            // The repository's clock is read as a held route is judged current: recorded wherever it runs.
            val threads = ThreadRecorder()
            val at = Instant.parse("2026-09-30T08:00:00Z")
            val source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = route
            }
            val repository = RouteStopsRepository(source, clock = {
                threads.note()
                at
            })
            var now by mutableStateOf(at)
            var loads: LineLoads? = null
            composeRule.setContent {
                CompositionLocalProvider(LocalRouteStops provides repository, LocalWorker provides worker) {
                    loads = rememberLineLoads(listOf("1"), now)
                }
            }
            composeRule.waitUntilWorked(worker.executor) { loads?.sequences?.get("1") != null }
            threads.clear()
            // The hourly recheck judges the held route; it's still current, so nothing loads again.
            now = now.plus(Duration.ofHours(1))
            composeRule.waitForIdle()
            composeRule.waitUntil("recheck", timeoutMillis = 5_000) { threads.threads().isNotEmpty() }
            composeRule.waitForIdle()
            val judged = threads.threads()
            assertTrue("judged on $judged", judged.all { it.startsWith("test-worker") })
            assertTrue(loads!!.loading.isEmpty())
        } finally {
            worker.close()
        }
    }

    @Test
    fun `a trip's lines to load are worked out on the worker`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val at = Instant.parse("2026-09-30T08:00:00Z")
            val reads = ThreadRecorder()
            val leg = app.stopdash.domain.TripLeg("tube", "red", "Red", "A", "A", "C", "C", at, at.plusSeconds(600))
            val routes = object : AbstractList<app.stopdash.domain.TripRoute>() {
                private val items = listOf(app.stopdash.domain.TripRoute(listOf(leg)))
                override val size: Int get() = items.size
                override fun get(index: Int) = items[index]
                override fun iterator(): Iterator<app.stopdash.domain.TripRoute> {
                    reads.note()
                    return items.iterator()
                }
            }
            // A first plan still landing, one answer in.
            val planned = TripViewModel.State(routes = routes, planning = true)
            var lines: List<String>? = null
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    lines = rememberTripLineIds(planned, emptySet(), null)
                }
            }
            composeRule.waitUntilWorked(worker.executor) { lines?.isNotEmpty() == true }
            assertEquals(listOf("red"), lines)
            assertTrue(reads.threads().isNotEmpty())
            assertEquals(setOf("test-worker"), reads.threads().toSet())
        } finally {
            worker.close()
        }
    }

    @Test
    fun `a failed line isn't asked about again as others join, only at the hour`() {
        val asked = mutableListOf<String>()
        val source = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                asked += lineId
                if (lineId == "1") throw TflException.Offline(null)
                return route
            }
        }
        val repository = RouteStopsRepository(source)
        var lineIds by mutableStateOf(listOf("1"))
        var now by mutableStateOf(Instant.parse("2026-09-30T08:00:00Z"))
        var loads: LineLoads? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository) {
                loads = rememberLineLoads(lineIds, now)
            }
        }
        composeRule.waitForIdle()
        val first = asked.count { it == "1" }
        assertTrue(first > 0)
        // A plan's next answers add lines: the failed one isn't asked about with each.
        lineIds = listOf("1", "2")
        composeRule.waitForIdle()
        lineIds = listOf("1", "2", "3")
        composeRule.waitForIdle()
        assertEquals(first, asked.count { it == "1" })
        assertTrue(loads!!.loading.isEmpty())
        // The hourly recheck asks again.
        now = now.plus(Duration.ofHours(1))
        composeRule.waitForIdle()
        assertTrue(asked.count { it == "1" } > first)
    }

    @Test
    fun `a load under way stops when its repository is replaced`() {
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val source = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                started.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    canceled.complete(Unit)
                }
            }
        }
        var repository by mutableStateOf(RouteStopsRepository(source))
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository) {
                rememberLineLoads(listOf("1"), Instant.parse("2026-09-30T08:00:00Z"))
            }
        }
        composeRule.waitForIdle()
        assertTrue(started.isCompleted)
        assertFalse(canceled.isCompleted)
        repository = RouteStopsRepository(object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = route
        })
        composeRule.waitForIdle()
        assertTrue(canceled.isCompleted)
    }
}
