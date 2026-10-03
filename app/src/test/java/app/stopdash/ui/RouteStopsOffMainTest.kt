package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
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
import org.junit.After
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class RouteStopsOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val workerThread = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
    private val worker = workerThread.asCoroutineDispatcher()

    @After
    fun tearDown() {
        worker.close()
    }

    @Test
    fun aTrainsStops_areLoadedAndMatchedOnTheWorker() {
        val threads = mutableListOf<String>()
        // Public names, synthetic ids. The repository works in place ([app.stopdash.domain.Workers]
        // is in-place under test), so the thread it loads on is the one the page handed it to.
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    synchronized(threads) { threads += Thread.currentThread().name }
                    return LineSequence(
                        routes = listOf(LineRoute("Brixton - Walthamstow Central", listOf("s1", "s2", "s3"))),
                        stopNames = mapOf("s1" to "Victoria", "s2" to "Green Park", "s3" to "Walthamstow Central"),
                    )
                }
            },
            io = Dispatchers.Unconfined,
        )
        val now = Instant.parse("2026-09-18T08:00:00Z")
        val next = Departure(
            lineId = "victoria",
            lineName = "Victoria",
            direction = "outbound",
            destination = "Walthamstow Central",
            platform = null,
            expectedArrival = now.plusSeconds(120),
            mode = "tube",
        )
        val row = DepartureRow(
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
        var state: RouteStopsUi = RouteStopsUi.Hidden
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository, LocalWorker provides worker) {
                state = rememberRouteStops(row, next, retry = 0)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { state !is RouteStopsUi.Loading }

        assertTrue("$state", state is RouteStopsUi.Loaded)
        assertEquals(listOf("s1", "s2", "s3"), (state as RouteStopsUi.Loaded).stops.map { it.id })
        assertTrue("loaded on $threads", threads.isNotEmpty() && threads.all { it.startsWith("test-worker") })
    }

    @Test
    fun loading_showsOnlyOnceTheListIsSlow() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RouteStopsSection(state = RouteStopsUi.Loading, railColor = Color.Red, onRetry = {})
        }
        composeRule.mainClock.advanceTimeByFrame()
        // A list worked out within a few frames never flashes "Loading" first.
        composeRule.onNodeWithText("Loading stops…").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(LOADING_NOTE_DELAY_MILLIS + 16)
        composeRule.onNodeWithText("Loading stops…").assertIsDisplayed()
    }
}
