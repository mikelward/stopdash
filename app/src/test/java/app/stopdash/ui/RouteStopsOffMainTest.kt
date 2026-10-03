package app.stopdash.ui

import androidx.compose.runtime.Composable
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
import app.stopdash.domain.TripLeg
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
        // Public names, synthetic ids. The repository works in place, so the thread it loads on is
        // the one the page handed it to.
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
            compute = Dispatchers.Unconfined,
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

    // Public names, synthetic ids.
    private fun victoriaLine(clock: () -> Instant = { Instant.now() }) = RouteStopsRepository(
        object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                routes = listOf(LineRoute("Brixton - Walthamstow Central", listOf("s1", "s2", "s3"))),
                stopNames = mapOf("s1" to "Victoria", "s2" to "Green Park", "s3" to "Walthamstow Central"),
            )
        },
        io = Dispatchers.Unconfined,
        compute = Dispatchers.Unconfined,
        clock = clock,
    )

    /** The first frame of [stops] on a page's first visit and on its reopening. */
    private fun firstFramesOnReopen(
        repository: RouteStopsRepository,
        // What happens while the page is closed.
        meanwhile: () -> Unit = {},
        stops: @Composable () -> RouteStopsUi,
    ): List<RouteStopsUi> {
        // Released by hand, so a page's first frame is what it has before working anything out.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        var open by mutableStateOf(true)
        val firstFrames = mutableListOf<RouteStopsUi>()
        var opened = 0
        var state: RouteStopsUi = RouteStopsUi.Hidden
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides repository, LocalWorker provides held) {
                if (open) {
                    state = stops()
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
        meanwhile()
        opened = 1
        open = true
        composeRule.waitForIdle()
        return firstFrames
    }

    private val trainAt = Instant.parse("2026-09-18T08:00:00Z")
    private val nextTrain = Departure(
        lineId = "victoria", lineName = "Victoria", direction = "outbound", destination = "Walthamstow Central",
        platform = null, expectedArrival = trainAt.plusSeconds(120), mode = "tube",
    )
    private val trainRow = DepartureRow(
        stopId = "s1", stopName = "Victoria", lineId = "victoria", lineName = "Victoria", direction = "outbound",
        directionKey = "outbound", destination = "Walthamstow Central", mode = "tube", upcoming = listOf(nextTrain), fetchedAt = trainAt,
    )

    @Test
    fun aTrainReopenedAfterItsRouteExpired_waitsForItsCurrentStops() {
        var now = trainAt
        val firstFrames = firstFramesOnReopen(victoriaLine { now }, meanwhile = { now = now.plusSeconds(25 * 3600) }) {
            rememberRouteStops(trainRow, nextTrain, retry = 0)
        }

        // A day on, the remembered list isn't vouched for: Loading, not yesterday's stops.
        assertEquals(RouteStopsUi.Loading, firstFrames[1])
    }

    @Test
    fun aTrainReopenedAfterItsLineWasRefetchedForAnother_waitsForItsCurrentStops() {
        var now = trainAt
        val repository = victoriaLine { now }
        val firstFrames = firstFramesOnReopen(
            repository,
            meanwhile = {
                // A day on, another page fetches the line afresh: fresh routes, but not the ones
                // this train's remembered list came from.
                now = now.plusSeconds(25 * 3600)
                kotlinx.coroutines.runBlocking { repository.load("victoria", "outbound") }
            },
        ) { rememberRouteStops(trainRow, nextTrain, retry = 0) }

        assertEquals(RouteStopsUi.Loading, firstFrames[1])
    }

    @Test
    fun aReopenedTrain_showsItsStopsInItsFirstFrame() {
        val now = Instant.parse("2026-09-18T08:00:00Z")
        val next = Departure(
            lineId = "victoria", lineName = "Victoria", direction = "outbound", destination = "Walthamstow Central",
            platform = null, expectedArrival = now.plusSeconds(120), mode = "tube",
        )
        val row = DepartureRow(
            stopId = "s1", stopName = "Victoria", lineId = "victoria", lineName = "Victoria", direction = "outbound",
            directionKey = "outbound", destination = "Walthamstow Central", mode = "tube", upcoming = listOf(next), fetchedAt = now,
        )
        val firstFrames = firstFramesOnReopen(victoriaLine()) { rememberRouteStops(row, next, retry = 0) }

        assertEquals(RouteStopsUi.Loading, firstFrames[0])
        assertTrue("${firstFrames[1]}", firstFrames[1] is RouteStopsUi.Loaded)
    }

    @Test
    fun aLegTakingAnotherPath_doesNotOpenWithTheFirstLegsStops() {
        val departs = Instant.parse("2026-09-18T08:05:00Z")
        val viaGreenPark = TripLeg(
            "tube", "victoria", "Victoria", "s1", "Victoria", "s3", "Walthamstow Central",
            departs, departs.plusSeconds(600), path = listOf("s2", "s3"), headings = listOf("Walthamstow Central"),
        )
        // Same line, ends and times, another way: a different leg.
        val direct = viaGreenPark.copy(path = listOf("s3"))
        var leg = viaGreenPark
        val firstFrames = firstFramesOnReopen(victoriaLine(), meanwhile = { leg = direct }) {
            rememberLegRouteStops(leg, retry = 0)
        }

        assertEquals(RouteStopsUi.Loading, firstFrames[1])
    }

    @Test
    fun aLegReplannedToAnotherTerminus_listsTheNewBranch() {
        // Two branches from A that part after B: the leg's heading says which one it runs.
        val forked = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                    routes = listOf(LineRoute("A - Y", listOf("A", "B", "C", "Y")), LineRoute("A - Z", listOf("A", "B", "D", "Z"))),
                    stopNames = mapOf("A" to "A", "B" to "B", "C" to "C", "D" to "D", "Y" to "Y", "Z" to "Z"),
                )
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        val departs = Instant.parse("2026-09-18T08:05:00Z")
        val toY = TripLeg("tube", "fork", "Fork", "A", "A", "B", "B", departs, departs.plusSeconds(300), path = listOf("B"), headings = listOf("Y"))
        var leg by mutableStateOf(toY)
        var state: RouteStopsUi = RouteStopsUi.Hidden
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides forked, LocalWorker provides Dispatchers.Unconfined) {
                state = rememberLegRouteStops(leg, retry = 0)
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("A", "B", "C", "Y"), (state as RouteStopsUi.Loaded).stops.map { it.id })

        // Re-planned in place: same line, ends and path, now heading for Z.
        leg = toY.copy(headings = listOf("Z"))
        composeRule.waitForIdle()
        assertEquals(listOf("A", "B", "D", "Z"), (state as RouteStopsUi.Loaded).stops.map { it.id })
    }

    @Test
    fun aLegReplannedWithACorrectedName_isWorkedOutAfresh() {
        // No headings: the leg's terminus comes from its end's name, so a corrected name is another list.
        val forked = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = LineSequence(
                    routes = listOf(LineRoute("A - Y", listOf("A", "B", "C", "Y")), LineRoute("A - Z", listOf("A", "B", "D", "Z"))),
                    stopNames = mapOf("A" to "A", "B" to "B", "C" to "C", "D" to "D", "Y" to "Y", "Z" to "Z"),
                )
            },
            io = Dispatchers.Unconfined,
            compute = Dispatchers.Unconfined,
        )
        val departs = Instant.parse("2026-09-18T08:05:00Z")
        val leg0 = TripLeg("tube", "fork", "Fork", "A", "A", "C", "C", departs, departs.plusSeconds(300))
        var leg by mutableStateOf(leg0)
        var state: RouteStopsUi = RouteStopsUi.Hidden
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides forked, LocalWorker provides Dispatchers.Unconfined) {
                state = rememberLegRouteStops(leg, retry = 0)
            }
        }
        composeRule.waitForIdle()
        val before = state

        // Re-planned in place with only a name corrected: a new leg, so its list is worked out again.
        leg = leg0.copy(toName = "C (corrected)")
        composeRule.waitForIdle()
        assertTrue("$before", before is RouteStopsUi.Loaded || before is RouteStopsUi.Unavailable)
        assertTrue("$state", state !== before)
    }
}
