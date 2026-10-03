package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A train's stop list is loaded and matched on the page's worker ([LocalWorker]), never the main
 * thread (AGENTS.md *Main thread: read and dispatch only*): matching walks every route of the line.
 * A reopened page shows the list it had in its first frame ([RouteStopsMemo]). Public names,
 * synthetic ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class RouteStopsOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val next = Departure(
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        destination = "Walthamstow Central",
        platform = null,
        expectedArrival = now.plusSeconds(120),
        mode = "tube",
    )
    private val row = DepartureRow(
        stopId = "s1",
        stopName = "Victoria",
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        directionKey = "outbound",
        destination = "Walthamstow Central",
        mode = "tube",
        upcoming = listOf(next),
        fetchedAt = now,
    )
    private val line = LineSequence(
        routes = listOf(LineRoute("Brixton - Walthamstow Central", listOf("s1", "s2", "s3"))),
        stopNames = mapOf("s1" to "Victoria", "s2" to "Green Park", "s3" to "Walthamstow Central"),
    )

    private fun repository(threads: MutableList<String>) = RouteStopsRepository(
        object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                synchronized(threads) { threads += Thread.currentThread().name }
                return line
            }
        },
        io = Dispatchers.Unconfined,
        // In place, so the thread it loads on is the one the page handed it to.
        compute = Dispatchers.Unconfined,
    )

    @Test
    fun aTrainsStops_areLoadedAndMatchedOnTheWorker() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val threads = mutableListOf<String>()
            val repository = repository(threads)
            var state: RouteStopsUi = RouteStopsUi.Hidden
            composeRule.setContent {
                CompositionLocalProvider(LocalRouteStops provides repository, LocalWorker provides worker) {
                    state = rememberRouteStops(row, next, retry = 0)
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { state !is RouteStopsUi.Loading }

            assertEquals(listOf("s1", "s2", "s3"), (state as RouteStopsUi.Loaded).stops.map { it.id })
            // Debug coroutines append " @coroutine#n" to the name; the thread is what matters.
            assertTrue("loaded on $threads", threads.isNotEmpty() && threads.all { it.startsWith("test-worker") })
        } finally {
            worker.close()
        }
    }

    @Test
    fun loading_showsOnlyOnceTheListIsSlow() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RouteStopsSection(state = RouteStopsUi.Loading, railColor = Color.Red, onRetry = {})
        }
        composeRule.mainClock.advanceTimeByFrame()
        // A list worked out within a few frames never flashes "Loading" first, nor is it read out.
        composeRule.onNodeWithText("Loading stops…").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(LOADING_NOTE_DELAY_MILLIS + 16)
        composeRule.onNodeWithText("Loading stops…").assertIsDisplayed()
    }

    @Test
    fun aReopenedTrain_showsItsStopsInItsFirstFrame() {
        val repository = repository(mutableListOf())
        // Released by hand, so the reopened page's first frame is what it has before working anything out.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var open by mutableStateOf(true)
        val firstFrames = mutableListOf<RouteStopsUi>()
        var opened = 0
        var state: RouteStopsUi = RouteStopsUi.Hidden
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository, LocalWorker provides held) {
                if (open) {
                    state = rememberRouteStops(row, next, retry = 0)
                    if (firstFrames.size == opened) firstFrames += state
                }
            }
        }
        // First visit: nothing worked out yet, then the list.
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertTrue("$state", state is RouteStopsUi.Loaded)

        open = false
        composeRule.waitForIdle()
        opened = 1
        open = true
        composeRule.waitForIdle()

        assertEquals(RouteStopsUi.Loading, firstFrames[0])
        assertTrue("${firstFrames[1]}", firstFrames[1] is RouteStopsUi.Loaded)
    }
}
