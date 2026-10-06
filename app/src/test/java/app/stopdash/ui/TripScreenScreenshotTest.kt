package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.data.TflRouteSequenceDto
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.Departure
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PartClosure
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TripClosures
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.WalkingSpeed
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trip with a change (SPEC *Trips with a change*): the routes, a route leg by leg, and the
 * planning and failed states. A trip between two well-known stations; the times and the Jubilee
 * delay are made up. No user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private fun leg(
        mode: String,
        lineId: String,
        lineName: String,
        from: Pair<String, String>,
        to: Pair<String, String>,
        departs: Long,
        arrives: Long,
        stops: Int,
        change: Long = 0,
    ) = TripLeg(
        mode = mode,
        lineId = lineId,
        lineName = lineName,
        fromId = from.first,
        fromName = from.second,
        toId = to.first,
        toName = to.second,
        departure = at(departs),
        arrival = at(arrives),
        path = List(stops - 1) { "stop$it" } + to.first,
        changeAfter = Duration.ofMinutes(change),
    )

    private val highbury = "910GHGHI" to "Highbury & Islington"
    private val whitechapel = "910GWCHAPEL" to "Whitechapel"
    private val whitechapelXr = "910GWCHAPXR" to "Whitechapel"
    private val canaryWharfXr = "910GCANWHRF" to "Canary Wharf"
    private val canadaWater = "910GCNDAW" to "Canada Water"
    private val canadaWaterTube = "940GZZLUCWR" to "Canada Water"
    private val canaryWharf = "940GZZLUCYF" to "Canary Wharf"
    private val stratford = "910GSTFD" to "Stratford (London)"
    private val stratfordTube = "940GZZLUSTD" to "Stratford"

    private val viaWhitechapel = TripRoute(
        listOf(
            leg("overground", "windrush", "Windrush", highbury, whitechapel, 3, 16, 6, change = 3),
            leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr, canaryWharfXr, 19, 23, 2),
        ),
    )
    private val viaCanadaWater = TripRoute(
        listOf(
            leg("overground", "windrush", "Windrush", highbury, canadaWater, 3, 21, 9, change = 3),
            leg("tube", "jubilee", "Jubilee", canadaWaterTube, canaryWharf, 24, 26, 1),
        ),
    )
    private val viaStratford = TripRoute(
        listOf(
            leg("overground", "mildmay", "Mildmay", highbury, stratford, 5, 21, 6, change = 6),
            leg("tube", "jubilee", "Jubilee", stratfordTube, canaryWharf, 28, 37, 4),
        ),
    )

    private fun train(lineId: String, lineName: String, mode: String, destination: String, inMinutes: Long, platform: String) =
        Departure(
            lineId = lineId,
            lineName = lineName,
            direction = "outbound",
            destination = destination,
            platform = platform,
            expectedArrival = at(inMinutes),
            mode = mode,
        )

    private val live = mapOf(
        highbury.first to TripViewModel.StopLive(
            listOf(
                train("windrush", "Windrush", "overground", "Crystal Palace", 3, "Platform 2"),
                train("windrush", "Windrush", "overground", "West Croydon", 7, "Platform 2"),
                train("windrush", "Windrush", "overground", "Crystal Palace", 11, "Platform 2"),
                train("mildmay", "Mildmay", "overground", "Stratford (London)", 5, "Platform 7"),
            ),
            now,
        ),
        whitechapelXr.first to TripViewModel.StopLive(
            listOf(
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 14, "Platform A"),
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 18, "Platform A"),
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 24, "Platform A"),
            ),
            now,
        ),
        canadaWaterTube.first to TripViewModel.StopLive(
            listOf(train("jubilee", "Jubilee", "tube", "Stratford", 25, "Eastbound - Platform 2")),
            now,
        ),
        stratfordTube.first to TripViewModel.StopLive(
            listOf(train("jubilee", "Jubilee", "tube", "Stanmore", 29, "Westbound - Platform 13")),
            now,
        ),
    )

    private val sequences = mapOf(
        "windrush" to LineSequence(
            routes = listOf(
                LineRoute("Highbury ↔ Crystal Palace", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW", "910GCRYSTLP")),
                LineRoute("Highbury ↔ West Croydon", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW", "910GWCROYDN")),
            ),
            stopNames = mapOf(
                "910GHGHI" to "Highbury & Islington",
                "910GWCHAPEL" to "Whitechapel",
                "910GCNDAW" to "Canada Water",
                "910GCRYSTLP" to "Crystal Palace",
                "910GWCROYDN" to "West Croydon",
            ),
        ),
        "mildmay" to LineSequence(
            routes = listOf(LineRoute("Highbury ↔ Stratford", listOf("910GHGHI", "910GSTFD"))),
            stopNames = mapOf("910GHGHI" to "Highbury & Islington", "910GSTFD" to "Stratford (London)"),
        ),
        "elizabeth" to LineSequence(
            routes = listOf(LineRoute("Whitechapel ↔ Abbey Wood", listOf("910GWCHAPXR", "910GCANWHRF", "910GABWDXR"))),
            stopNames = mapOf("910GWCHAPXR" to "Whitechapel", "910GCANWHRF" to "Canary Wharf", "910GABWDXR" to "Abbey Wood"),
        ),
        "jubilee" to LineSequence(
            routes = listOf(
                LineRoute("Canada Water ↔ Stratford", listOf("940GZZLUCWR", "940GZZLUCYF", "940GZZLUSTD")),
                LineRoute("Stratford ↔ Stanmore", listOf("940GZZLUSTD", "940GZZLUCYF", "940GZZLUCWR", "940GZZLUSTM")),
            ),
            stopNames = mapOf(
                "940GZZLUCWR" to "Canada Water",
                "940GZZLUCYF" to "Canary Wharf",
                "940GZZLUSTD" to "Stratford",
                "940GZZLUSTM" to "Stanmore",
            ),
        ),
    )

    private val source = object : RouteSequenceSource {
        override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
            sequences.getValue(lineId)
    }

    // Every stop [routes] board or get off at, checked with nothing to report, as a trip holds them
    // once its closure check has answered.
    private fun checkedOpen(vararg routes: TripRoute): Map<String, List<StopDisruption>> =
        routes.flatMap(TripClosures::ends).associate { it.id to emptyList() }

    private val planned = TripViewModel.State(
        routes = listOf(viaStratford, viaCanadaWater, viaWhitechapel),
        plannedAt = now,
        live = live,
        closures = checkedOpen(viaStratford, viaCanadaWater, viaWhitechapel),
        statuses = mapOf(
            "jubilee" to LineStatus("jubilee", 9, "Minor Delays"),
            "windrush" to LineStatus("windrush", LineStatus.GOOD_SERVICE, "Good Service"),
        ),
        statusesAt = mapOf("jubilee" to now, "windrush" to now),
    )

    private fun show(
        state: TripViewModel.State,
        routeStops: RouteStopsRepository = RouteStopsRepository(source),
        menu: AppMenuActions? = null,
        access: Duration = Duration.ofMinutes(2),
        ends: TripEnds? = null,
        // The page's worker; the app's own unless a test holds one.
        worker: CoroutineDispatcher? = null,
        // Where a test swaps in a later state, as the page's loads land; [state] if none.
        held: MutableState<TripViewModel.State>? = null,
        onListShown: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides (worker ?: LocalWorker.current)) {
                    // No outer provider: the screen checks its trains against the repository it's given.
                    TripScreen(
                        title = "To Canary Wharf",
                        state = held?.value ?: state,
                        now = now,
                        access = access,
                        routeStops = routeStops,
                        onBack = {},
                        onRetry = {},
                        menu = menu,
                        ends = ends,
                        onListShown = onListShown,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun trains_are_judged_on_the_page_worker_never_in_composition() {
        // A worker held shut: until it runs, no train can be judged nor any route timed, so no time shows.
        // Had composition judged or timed them on the main thread, the times would show anyway.
        val gate = CountDownLatch(1)
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        threads.execute { gate.await() }
        val worker = threads.asCoroutineDispatcher()
        try {
            // The clock held, so the list's wait ([REVEAL_CAP_MILLIS]) doesn't run out while the worker is.
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf",
                            state = planned,
                            now = now,
                            access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source),
                            onBack = {},
                            onRetry = {},
                        )
                    }
                }
            }
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onNodeWithText("Checking routes…").assertIsDisplayed()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)

            gate.countDown()
            composeRule.mainClock.autoAdvance = true
            // The page's whole frame waits on the worker ([tripFrame]): no route is timed until it runs.
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("27 min · ~08:29").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("Checking routes…").assertCountEquals(0)
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun a_cards_times_are_worked_out_on_the_worker_and_drawn_against_their_own_time() {
        // The worker on a thread of its own, which the test can hold: worked out on the main thread, a
        // card's times would move with the clock however that thread was held.
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var shownAt by mutableStateOf(now)
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf",
                            state = planned,
                            now = shownAt,
                            access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source),
                            onBack = {},
                            onRetry = {},
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("3 · 7 · 11 min").fetchSemanticsNodes().size == 2
            }

            // A tick on, with the worker's thread held: the cards keep their last times, drawn against
            // the time they were worked out for, never a newer clock.
            threads.execute { gate.await() }
            shownAt = now.plusSeconds(10)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("3 · 7 · 11 min").assertCountEquals(2)
            composeRule.onAllNodesWithText("2 · 6 · 10 min").assertCountEquals(0)
            // So do the routes' own times, the whole page from one frame ([tripFrame]).
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(1)

            // Released, the worker works them out for the new time.
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("2 · 6 · 10 min").fetchSemanticsNodes().size == 2
            }
            composeRule.onAllNodesWithText("3 · 7 · 11 min").assertCountEquals(0)
            composeRule.onAllNodesWithText("26 min · ~08:29").assertCountEquals(1)
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun the_route_headers_are_worked_out_on_the_page_worker_never_in_composition() {
        // A worker held shut: until it runs, the page has no frame ([tripFrame]), so no card and no
        // header. Had composition labeled them, the headers would show at once.
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        threads.execute { gate.await() }
        try {
            show(planned, worker = worker)
            composeRule.onAllNodesWithTag("routeLabel").assertCountEquals(0)
            // Released, the worker labels the cards.
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("routeLabel").fetchSemanticsNodes().size == 2 }
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun new_cards_wait_for_their_own_headers_never_the_last_ones() {
        // The clock moving on with the worker held: the last cards stand in whole, each under its own
        // header, rather than the last headers landing on cards ranked otherwise. A new plan's frame waits
        // for the worker: a route it dropped never lingers.
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var state by mutableStateOf(planned)
        var shownAt by mutableStateOf(now)
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf",
                            state = state,
                            now = shownAt,
                            access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source),
                            onBack = {},
                            onRetry = {},
                        )
                    }
                }
            }
            fun fastestAbove(text: String): Boolean {
                val fastest = composeRule.onAllNodesWithText("Fastest").fetchSemanticsNodes().singleOrNull() ?: return false
                val card = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().firstOrNull() ?: return false
                val headers = composeRule.onAllNodesWithTag("routeLabel").fetchSemanticsNodes().map { it.boundsInRoot.top }
                // The nearest header above the card is Fastest's.
                return headers.filter { it < card.boundsInRoot.top }.maxOrNull() == fastest.boundsInRoot.top
            }
            // Headed, and its times in (each from its own run on the worker).
            composeRule.waitUntil(timeoutMillis = 5_000) { fastestAbove("27 min · ~08:29") }

            threads.execute { gate.await() }
            shownAt = now.plusSeconds(10)
            composeRule.waitForIdle()
            assertTrue("the last cards stand in under their own headers", fastestAbove("27 min · ~08:29"))
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("routeLabel").fetchSemanticsNodes().size == 2 }

            // A new plan dropping the fastest route, the worker held again: its cards wait for their frame,
            // which heads them ([a_replans_cards_arrive_with_their_headers]); the dropped route never lingers.
            val replanned = CountDownLatch(1)
            threads.execute { replanned.await() }
            try {
                state = planned.copy(routes = listOf(viaStratford, viaWhitechapel))
                composeRule.waitForIdle()
                composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
                composeRule.onAllNodesWithTag("routeLabel").assertCountEquals(0)
            } finally {
                replanned.countDown()
            }
            composeRule.waitForIdle()

            // No routes left, the worker held again: the last cards don't stand beside "No routes".
            val held = CountDownLatch(1)
            threads.execute { held.await() }
            try {
                state = planned.copy(routes = emptyList())
                composeRule.waitForIdle()
                composeRule.onAllNodesWithText("38 min · ~08:40").assertCountEquals(0)
                composeRule.onAllNodesWithTag("routeLabel").assertCountEquals(0)
            } finally {
                held.countDown()
            }
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun a_replans_cards_arrive_with_their_headers() {
        // A re-plan with the same options (a pull, a plan past its reuse) once the list shows: its cards
        // and their headers come in one frame ([tripFrame]), never the cards unheaded for a worker run
        // and then headed and re-sorted under the rider (Codex, #543). The worker steps one run at a
        // time, and the list is looked at after each.
        // Runs go straight through until the test takes over, then one at a time when it says.
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val stepping = java.util.concurrent.atomic.AtomicBoolean(false)
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val stepped = java.util.concurrent.Executor { task -> if (stepping.get()) queued.add(task) else threads.execute(task) }
        val worker = stepped.asCoroutineDispatcher()
        var state by mutableStateOf(planned)
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf",
                            state = state,
                            now = now,
                            access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source),
                            onBack = {},
                            onRetry = {},
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("routeLabel").fetchSemanticsNodes().size == 2 }
            composeRule.waitForIdle()

            stepping.set(true)
            state = planned.copy(routes = listOf(viaStratford, viaWhitechapel))
            composeRule.waitForIdle()
            fun newListShown() = composeRule.onAllNodesWithText("27 min · ~08:29").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("tripRoutes").fetchSemanticsNodes().isNotEmpty()
            var runs = 0
            while (!newListShown()) {
                val next = queued.poll()
                assertTrue("the new plan's list shows within the worker's runs", next != null && runs++ < 50)
                // One run, to its end, then a look at the list.
                threads.submit(next!!).get()
                composeRule.waitForIdle()
            }
            assertTrue("the new cards come headed", composeRule.onAllNodesWithTag("routeLabel").fetchSemanticsNodes().isNotEmpty())
        } finally {
            stepping.set(false)
            generateSequence { queued.poll() }.forEach { threads.execute(it) }
            threads.shutdown()
        }
    }

    @Test
    fun another_trips_frame_never_stands_in_for_this_one() {
        // A re-locate moves the trip to another model while the screen stays put (Codex, #529): with the
        // worker held, the last trip's routes don't stand in for the new trip's.
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var trip by mutableStateOf("a" to planned)
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf",
                            state = trip.second,
                            journey = trip.first,
                            now = now,
                            access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source),
                            onBack = {},
                            onRetry = {},
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("27 min · ~08:29").fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            trip = "b" to TripViewModel.State(planning = true)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
            // The same trip's frame still stands in: back to the first trip, its routes show at once.
            trip = "a" to planned
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(1)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun try_again_while_a_plan_runs_never_plans_again() {
        // A retry begins while the worker is held: the frame from before still draws Try again, but a
        // second tap, judged by the live state, plans nothing more (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var state by mutableStateOf(TripViewModel.State(planError = DeparturesUiState.Error.Kind.OFFLINE))
        var retries = 0
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf", state = state, now = now, access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source), onBack = {}, onRetry = { retries++ },
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            composeRule.onNodeWithText("Try again").performClick()
            assertEquals(1, retries)
            state = state.copy(planning = true)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().singleOrNull()?.let {
                composeRule.onNodeWithText("Try again").performClick()
            }
            composeRule.waitForIdle()
            assertEquals(1, retries)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun back_closes_a_route_asked_for_before_its_frame_is_in() {
        // A card tapped while the worker is held: Back closes the route asked for, never the trip (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val openRoute = mutableStateOf<String?>(null)
        var backs = 0
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source), onBack = { backs++ }, onRetry = {}, openRoute = openRoute,
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("27 min · ~08:29").fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            composeRule.onNodeWithText("27 min · ~08:29").performClick()
            composeRule.waitForIdle()
            assertTrue(openRoute.value != null)
            // The list's frame doesn't stand in for the route asked for: the tap never seems to do nothing,
            // the list left tappable (Codex, #529).
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
            composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            assertEquals(0, backs)
            assertEquals(null, openRoute.value)
            // Closed, the list's frame in hand shows at once.
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(1)
            // The on-screen arrow too (Codex, #529).
            composeRule.onNodeWithText("27 min · ~08:29").performClick()
            composeRule.waitForIdle()
            assertTrue(openRoute.value != null)
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()
            assertEquals(0, backs)
            assertEquals(null, openRoute.value)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun the_lines_loading_key_by_their_version_never_their_contents() {
        // The frame keys on the version, bumped as a load starts or ends, never on the set's contents
        // compared in composition, and the set is one per change, never changed once handed out (Codex, #529).
        val loads = kotlinx.coroutines.CompletableDeferred<Unit>()
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    loads.await()
                    return source.routeSequence(lineId, direction)
                }
            },
        )
        var tick by mutableStateOf(0)
        val seen = mutableListOf<LineLoads>()
        composeRule.setContent {
            CompositionLocalProvider(LocalRouteStops provides routeStops) {
                tick // Read, so a change composes this again.
                seen += rememberLineLoads(listOf("jubilee"), now)
            }
        }
        composeRule.waitForIdle()
        val loading = seen.last()
        assertEquals(setOf("jubilee"), loading.loading.toSet())
        tick++
        composeRule.waitForIdle()
        org.junit.Assert.assertSame(loading.loading, seen.last().loading)
        assertEquals(loading.loadingVersion, seen.last().loadingVersion)
        loads.complete(Unit)
        composeRule.waitForIdle()
        assertEquals(emptySet<String>(), seen.last().loading.toSet())
        assertTrue(seen.last().loadingVersion > loading.loadingVersion)
        // The set handed out before stays as it was: a frame worked out from it while the load ended reads
        // its own moment's, never the line gone from under it (Codex, #529).
        assertEquals(setOf("jubilee"), loading.loading.toSet())
    }

    @Test
    fun an_answer_under_way_never_replaces_the_one_wanted_again() {
        // A's answer is in; B's is worked out while the key goes back to A: B's never replaces A's,
        // which is the one wanted (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val stored = java.util.Collections.synchronizedList(mutableListOf<String>())
        val backing = mutableStateOf<Worked<String, String>?>(null)
        val slot = object : androidx.compose.runtime.MutableState<Worked<String, String>?> by backing {
            override var value: Worked<String, String>?
                get() = backing.value
                set(it) {
                    it?.let { stored += it.key }
                    backing.value = it
                }
        }
        var key by mutableStateOf("a")
        var shown: String? = null
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    val k = key
                    shown = rememberWorked(slot, k) {
                        if (k == "b") gate.await()
                        k
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { "a" in stored }
            key = "b"
            composeRule.waitForIdle()
            key = "a"
            composeRule.waitForIdle()
            gate.countDown()
            threads.submit {}.get()
            composeRule.waitForIdle()
            assertEquals(listOf("a"), stored.toList())
            assertEquals("a", shown)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun a_slot_runs_one_at_a_time_and_only_the_key_wanted_next() {
        // On a worker of many threads, keys changing while a run is out wait for it, and only the one
        // wanted when it ends is worked out: never several of a slot's runs at once (Codex, #529).
        val threads = Executors.newFixedThreadPool(4) { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val ran = java.util.Collections.synchronizedList(mutableListOf<String>())
        val running = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val slot = mutableStateOf<Worked<String, String>?>(null)
        var key by mutableStateOf("a")
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    val k = key
                    rememberWorked(slot, k) {
                        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        ran += k
                        if (k == "a") gate.await()
                        running.decrementAndGet()
                        k
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { "a" in ran }
            for (next in listOf("b", "c", "d")) {
                key = next
                composeRule.waitForIdle()
            }
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) { slot.value?.key == "d" }
            assertEquals(listOf("a", "d"), ran.toList())
            assertEquals(1, most.get())
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun an_answer_that_cant_stand_in_never_replaces_one_that_can() {
        // A's answer is in and may stand in for C; B's, worked out while the key moves on to C, may not:
        // it never replaces A's, which stands until C's is in (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val stored = java.util.Collections.synchronizedList(mutableListOf<String>())
        val backing = mutableStateOf<Worked<String, String>?>(null)
        val slot = object : androidx.compose.runtime.MutableState<Worked<String, String>?> by backing {
            override var value: Worked<String, String>?
                get() = backing.value
                set(it) {
                    it?.let { stored += it.key }
                    backing.value = it
                }
        }
        var key by mutableStateOf("a")
        var shown: String? = null
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    val k = key
                    shown = rememberWorked(slot, k, keep = { held, wanted -> held == "a" && wanted == "c" }) {
                        if (k == "b") {
                            started.countDown()
                            gate.await()
                        }
                        k
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { "a" in stored }
            key = "b"
            composeRule.waitForIdle()
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            key = "c"
            composeRule.waitForIdle()
            assertEquals("a", shown)
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) { "c" in stored }
            assertEquals(listOf("a", "c"), stored.toList())
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun a_replaced_slots_work_is_canceled() {
        // Another list's slots in place of these (a new set of stops): the old slot's work under way is
        // canceled, never landing beside the new slot's, as its key moving on wouldn't be (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val old = mutableStateOf<Worked<String, String>?>(null)
        val new = mutableStateOf<Worked<String, String>?>(null)
        var replaced by mutableStateOf(false)
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    val slot = if (replaced) new else old
                    val first = !replaced
                    rememberWorked(slot, "k") {
                        if (first) {
                            started.countDown()
                            gate.await()
                        }
                        if (first) "old" else "new"
                    }
                }
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            replaced = true
            composeRule.waitForIdle()
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) { new.value != null }
            threads.submit {}.get()
            composeRule.waitForIdle()
            assertEquals(null, old.value)
            assertEquals("new", new.value?.value)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun an_answer_under_way_lands_though_its_key_moved_on() {
        // A worker slower than the clock ticks still lands answers: one under way isn't canceled by a
        // newer key, it's stored for the key it was worked out for (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val stored = java.util.Collections.synchronizedList(mutableListOf<String>())
        val backing = mutableStateOf<Worked<String, String>?>(null)
        val slot = object : androidx.compose.runtime.MutableState<Worked<String, String>?> by backing {
            override var value: Worked<String, String>?
                get() = backing.value
                set(it) {
                    it?.let { stored += it.key }
                    backing.value = it
                }
        }
        var key by mutableStateOf("a")
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker) {
                    val k = key
                    rememberWorked(slot, k) {
                        if (k == "a") {
                            started.countDown()
                            gate.await()
                        }
                        k
                    }
                }
            }
            // A's run under way before the key moves on: one not yet started is skipped, rightly.
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            key = "b"
            composeRule.waitForIdle()
            key = "c"
            composeRule.waitForIdle()
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) { "c" in stored }
            assertEquals(listOf("a", "c"), stored.toList())
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun a_frame_stands_in_only_as_confirmed_and_only_for_a_tick() {
        // With the worker held, a frame judged live-confirmed doesn't stand in once a re-locate starts,
        // nor one more than a tick old (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var relocating by mutableStateOf(false)
        var hidden by mutableStateOf(emptySet<String>())
        var dismissed by mutableStateOf(emptySet<DismissedAlert>())
        var maxWalk by mutableStateOf(MaxWalk.DEFAULT)
        var state by mutableStateOf(planned)
        var shownAt by mutableStateOf(now)
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf", state = state, now = shownAt, access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, relocating = relocating, hiddenModes = hidden, dismissed = dismissed, maxWalk = maxWalk,
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("27 min · ~08:29").fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            relocating = true
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
            relocating = false
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(1)
            // Nor once the rider hides a mode: the routes it left out wait for their own frame.
            hidden = setOf("bus")
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
            hidden = emptySet()
            composeRule.waitForIdle()
            // Nor once an alert is dismissed: its warnings wait for a frame without them (Codex, #529).
            dismissed = setOf(DismissedAlert.ofLineStatus(planned.statuses.getValue("jubilee")))
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
            dismissed = emptySet()
            composeRule.waitForIdle()
            // Nor once a planning option changes: the routes planned under the old one wait (Codex, #529).
            maxWalk = MaxWalk.entries.first { it != MaxWalk.DEFAULT }
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
            maxWalk = MaxWalk.DEFAULT
            composeRule.waitForIdle()
            // Nor once a refresh fails: the frame that showed its trains as live waits for one that says it
            // couldn't (Codex, #529).
            state = planned.copy(statusFailed = true, failures = planned.failures + 1)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
            state = planned
            composeRule.waitForIdle()
            // A tick on, it stands in; two, it doesn't (Codex, #529).
            shownAt = now.plusSeconds(10)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(1)
            shownAt = now.plusSeconds(20)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    // A train going, the stops turning stale, and a closure starting, all within the next tick: the held
    // frame still stands in for that tick rather than leave the page without one until its own is in, a
    // flash at each (maintainer: no flicker; Codex, #529). Two ticks on it doesn't.
    @Test
    fun a_frame_stands_in_for_its_next_tick_across_what_turns_in_it() {
        val stop = planned.live.getValue(highbury.first)
        val soon = train("victoria", "Victoria", "tube", "Brixton", 0, "Platform 3").copy(expectedArrival = now.plusSeconds(5))
        assertHeldFrameGoesBy(
            planned.copy(
                live = planned.live.mapValues { it.value.copy(fetchedAt = now.minusSeconds(295)) } +
                    (highbury.first to stop.copy(departures = stop.departures + soon, fetchedAt = now.minusSeconds(295))),
                closures = planned.closures + ("EXAMPLE" to listOf(StopDisruption("Station closed", validFrom = now.plusSeconds(5)))),
            ),
        )
    }

    // With the worker held, [aging]'s frame stands in a tick (10 s) on and not two (20 s) on.
    private fun assertHeldFrameGoesBy(aging: TripViewModel.State) {
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var shownAt by mutableStateOf(now)
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf", state = aging, now = shownAt, access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("27 min · ~08:29").fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            shownAt = now.plusSeconds(10)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(1)
            shownAt = now.plusSeconds(20)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("27 min · ~08:29").assertCountEquals(0)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun the_open_routes_walk_is_drawn_against_its_frames_own_access() {
        // The walk to the first stop changes (a new walking speed) while the worker is held: the open
        // route's "Walk to" row never shows a walk its times weren't worked out for. The last frame,
        // worked out for the old walk, doesn't stand in (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var access by mutableStateOf(Duration.ofMinutes(2))
        val openRoute = mutableStateOf<String?>(openRouteOf(planned.routes!!.first(), planned, emptyMap()).encode())
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf", state = planned, now = now, access = access,
                            routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, openRoute = openRoute,
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("(~2 min)", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            access = Duration.ofMinutes(5)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("(~5 min)", substring = true).assertCountEquals(0)
            composeRule.onAllNodesWithText("(~2 min)", substring = true).assertCountEquals(0)
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("(~5 min)", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun the_first_route_is_headed_fastest_and_the_rest_other() {
        // Every route here rides twice, so the first card is fastest, none is simplest, and the
        // other two share one "Other" header.
        show(planned)
        val headers = composeRule.onAllNodesWithTag("routeLabel")
        headers.assertCountEquals(2)
        headers[0].assertTextEquals("Fastest")
        headers[1].assertTextEquals("Other")
        headers[0].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        headers[1].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onAllNodesWithText("Simplest").assertCountEquals(0)
        // Fastest over the first card, the one arriving soonest; Other between it and the rest.
        val fastest = headers[0].getUnclippedBoundsInRoot()
        val other = headers[1].getUnclippedBoundsInRoot()
        val first = composeRule.onNodeWithText("27 min · ~08:29").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithText("28 min · ~08:30").getUnclippedBoundsInRoot()
        val third = composeRule.onNodeWithText("38 min · ~08:40").getUnclippedBoundsInRoot()
        assertTrue(fastest.bottom <= first.top && first.bottom <= other.top && other.bottom <= second.top && second.top < third.top)
    }

    @Test
    fun trip_routes() {
        show(planned)
        // Via Canada Water: the 3 min Windrush (a 2 min walk to it), 18 min on, 3 to change, the
        // Jubilee at 25, 2 min on: 27 min. Via Whitechapel misses the Elizabeth line at 18 for 24.
        composeRule.onNodeWithText("27 min · ~08:29").assertIsDisplayed()
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
        composeRule.onNodeWithText("38 min · ~08:40").assertIsDisplayed()
        // Screen readers hear each first-leg time with its destination.
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().assertExists()
        captureSnapshot("trip-routes.png")
    }

    @Test
    fun trip_routes_walking_speed() {
        // The walking speed heads the routes (maintainer, 2026-09-28), the max walk under it
        // (2026-09-30); a pick of either is reported to its setting.
        var chosen: WalkingSpeed? = null
        var chosenMaxWalk: MaxWalk? = null
        var chosenStepFree: StepFree? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    walkingSpeed = WalkingSpeed.AVERAGE,
                    onWalkingSpeedChange = { chosen = it },
                    maxWalk = MaxWalk.THIRTY,
                    onMaxWalkChange = { chosenMaxWalk = it },
                    stepFree = StepFree.ANY,
                    onStepFreeChange = { chosenStepFree = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walking speed").assertIsDisplayed()
        composeRule.onNodeWithText("Max walk").assertIsDisplayed()
        composeRule.onNodeWithTag("maxWalk").assertContentDescriptionEquals("Max walk, 30 min")
        captureSnapshot("trip-routes-walking-speed.png")
        composeRule.onNodeWithTag("walkingSpeed").performClick()
        composeRule.onNodeWithTag("walkingSpeed-FAST").performClick()
        assertEquals(WalkingSpeed.FAST, chosen)
        // Every limit is offered, 60 minutes the longest.
        composeRule.onNodeWithTag("maxWalk").performClick()
        MaxWalk.entries.forEach { composeRule.onNodeWithTag("maxWalk-${it.name}").assertExists() }
        // Open, styled as the overflow menu is (maintainer, 2026-10-03): the whole screen, its own window included.
        if (capturing()) com.github.takahirom.roborazzi.captureScreenRoboImage("src/test/snapshots/images/trip-routes-max-walk-menu.png")
        composeRule.onNodeWithText("60 min").assertIsDisplayed()
        composeRule.onNodeWithTag("maxWalk-SIXTY").performClick()
        assertEquals(MaxWalk.SIXTY, chosenMaxWalk)
        // Step-free under them: the maintainer's three levels, by their names, each but Any saying
        // what it's for, so a rider with luggage sees Station suits them too.
        composeRule.onNodeWithTag("stepFree").assertContentDescriptionEquals("Step-free, Any").performClick()
        StepFree.entries.forEach { composeRule.onNodeWithTag("stepFree-${it.name}").assertExists() }
        composeRule.onNodeWithTag("stepFree-ANY").assertTextEquals("Any")
        composeRule.onNodeWithTag("stepFree-STATION")
            .assertTextEquals("Station", "Street to platform, for luggage or a buggy")
        composeRule.onNodeWithTag("stepFree-FULLY").assertTextEquals("Fully", "Onto the train too, for a wheelchair")
        composeRule.onNodeWithTag("stepFree-FULLY").performClick()
        assertEquals(StepFree.FULLY, chosenStepFree)
    }

    @Test
    fun an_open_route_shows_no_search_choices() {
        // The pickers and chips choose among routes: on the list, not on the route chosen (maintainer,
        // 2026-10-04). Back on the list, they're there again.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                    onMaxWalkChange = {},
                    onStepFreeChange = {},
                    onTripModesChange = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("walkingSpeed").assertExists()
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        listOf("walkingSpeed", "maxWalk", "stepFree", "tripModes").forEach { composeRule.onAllNodesWithTag(it).assertCountEquals(0) }
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        listOf("walkingSpeed", "maxWalk", "stepFree", "tripModes").forEach { composeRule.onNodeWithTag(it).assertExists() }
    }

    @Test
    fun trip_walk_pickers_wait_for_the_stored_choices() {
        // Until the walking speed and max walk are read, neither picker shows a value or opens, so a
        // pick can't be saved over a choice not yet read.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                    onMaxWalkChange = {},
                    onStepFreeChange = {},
                    onTripModesChange = {},
                    planOptionsLoaded = false,
                )
            }
        }
        composeRule.onNodeWithTag("walkingSpeed").assertIsNotEnabled().assertContentDescriptionEquals("Walking speed, –")
        composeRule.onNodeWithTag("maxWalk").assertIsNotEnabled().assertContentDescriptionEquals("Max walk, –")
        composeRule.onNodeWithTag("stepFree").assertIsNotEnabled().assertContentDescriptionEquals("Step-free, –")
        // No mode chip reads as riding, nor responds, until the rider's choice is read.
        ModeGroups.ALL.forEach { composeRule.onNodeWithTag("tripMode-${it.key}").assertIsNotEnabled().assertIsNotSelected() }
    }

    @Test
    fun trip_mode_chips() {
        // One chip per kind of transport under the pickers, by the list's hide-mode names, selected
        // while the trip rides it; a tap turns it off or on, but the last one riding stays on.
        val group = { key: String -> ModeGroups.ALL.single { it.key == key } }
        var modes by mutableStateOf(TripModes.DEFAULT.with(group("bus"), ride = false))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                    onMaxWalkChange = {},
                    onStepFreeChange = {},
                    tripModes = modes,
                    onTripModesChange = { modes = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tripMode-tube").assertTextEquals("Tube & DLR").assertIsSelected()
        composeRule.onNodeWithTag("tripMode-train").assertTextEquals("Train").assertIsSelected()
        composeRule.onNodeWithTag("tripMode-bus").assertTextEquals("Bus").assertIsNotSelected()
        ModeGroups.ALL.forEach { composeRule.onNodeWithTag("tripMode-${it.key}").assertExists() }
        captureSnapshot("trip-routes-modes.png")
        // Off, then on again.
        composeRule.onNodeWithTag("tripMode-train").performClick()
        assertEquals(TripModes(setOf("bus", "train")), modes)
        composeRule.onNodeWithTag("tripMode-train").assertIsNotSelected()
        composeRule.onNodeWithTag("tripMode-bus").performClick()
        assertEquals(TripModes(setOf("train")), modes)
        composeRule.onNodeWithTag("tripMode-bus").assertIsSelected()
        // Down to one: a tap on it changes nothing, since a trip riding nothing has no route.
        modes = ModeGroups.ALL.filter { it.key != "tram" }.fold(TripModes.DEFAULT) { m, g -> m.with(g, ride = false) }
        composeRule.onNodeWithTag("tripMode-tram").performClick()
        composeRule.onNodeWithTag("tripMode-tram").assertIsSelected()
        assertEquals(TripModes(ModeGroups.ALL.map { it.key }.toSet() - "tram"), modes)
    }

    @Test
    fun trip_from_to_bar() {
        // The routes keep the To… search's From/To bar (maintainer, 2026-09-28): each end a tap to change.
        var changedFrom = 0
        var changedTo = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    menu = AppMenuActions(updateAvailable = false, onOpenAppListing = {}, onSendBugReport = {}, onOpenLicenses = {}),
                    walkingSpeed = WalkingSpeed.AVERAGE,
                    onWalkingSpeedChange = {},
                    ends = TripEnds(fromStation = null, toName = "Canary Wharf", onChangeFrom = { changedFrom++ }, onChangeTo = { changedTo++ }),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fromField").assertContentDescriptionEquals("From Here")
        composeRule.onNodeWithTag("toField").assertContentDescriptionEquals("To Canary Wharf")
        // The bar says where the trip goes, so the title doesn't say it again.
        composeRule.onAllNodesWithText("To Canary Wharf").assertCountEquals(0)
        // The app's overflow stays, at the From row's end.
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_more)).assertIsDisplayed()
        captureSnapshot("trip-from-to.png")
        composeRule.onNodeWithTag("fromField").performClick()
        assertEquals(1, changedFrom)
        composeRule.onNodeWithTag("toField").performClick()
        assertEquals(1, changedTo)
    }

    @Test
    fun trip_from_to_bar_from_a_station() {
        // From the station the routes start at, so no walk to it.
        show(
            planned,
            access = Duration.ZERO,
            ends = TripEnds(fromStation = "Highbury & Islington", toName = "Canary Wharf", onChangeFrom = {}, onChangeTo = {}),
        )
        composeRule.onNodeWithTag("fromField").assertContentDescriptionEquals("From Highbury & Islington")
        captureSnapshot("trip-from-to-station.png")
    }

    @Test
    fun pulling_the_routes_down_asks_for_them_again() {
        var pulled = 0
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = planned,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onPullRefresh = { pulled++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tripRoutes").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(1, pulled)
        // The routes stay up while the new plan and times come in.
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
        // An open route is one choice already made: no pull there, its times refresh on the tick.
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onAllNodesWithTag("tripRoutesPull").assertCountEquals(0)
    }

    @Test
    fun an_open_route_is_titled_and_back_returns_to_the_from_to_bar() {
        show(planned, ends = TripEnds(fromStation = null, toName = "Canary Wharf", onChangeFrom = {}, onChangeTo = {}))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        // One route open: its legs under the trip's title, not ends to change.
        composeRule.onNodeWithText("To Canary Wharf").assertIsDisplayed()
        composeRule.onAllNodesWithTag("tripEndsBar").assertCountEquals(0)
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.action_back)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tripEndsBar").assertIsDisplayed()
    }

    @Test
    fun an_open_route_stays_open_through_a_new_plan_that_offers_it() {
        // A plan made again (a walking speed changed in Settings, say): no routes while it runs, then
        // the new plan, which still offers the route through Whitechapel, so it opens again. The
        // route's page shows none of the trip's choices (maintainer, 2026-10-04).
        val state = mutableStateOf(planned)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = state.value,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    walkingSpeed = WalkingSpeed.AVERAGE,
                    onWalkingSpeedChange = {},
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onAllNodesWithText("Walking speed").assertCountEquals(0)
        state.value = planned.copy(routes = null, planning = true)
        composeRule.waitForIdle()
        // Nor while the new plan runs (Codex, #545).
        composeRule.onAllNodesWithText("Walking speed").assertCountEquals(0)
        state.value = planned
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun an_open_route_a_new_plan_drops_stays_closed_when_a_later_plan_offers_it_again() {
        val state = mutableStateOf(planned)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf",
                    state = state.value,
                    now = now,
                    access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source),
                    onBack = {},
                    onRetry = {},
                    onWalkingSpeedChange = {},
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        // A finished plan without the route: back to the list.
        state.value = planned.copy(routes = listOf(viaStratford, viaCanadaWater))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
        // A later plan offering it again (the old pace's plan, say) leaves the rider on the list.
        state.value = planned
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
    }

    @Test
    fun a_walking_speed_that_did_not_save_is_said_once() {
        var shown = 0
        // The page's work inline: the clock idling waits out runs on a real worker, and the note mustn't
        // time out while it does.
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides inline) {
                    TripScreen(
                        title = "To Canary Wharf",
                        state = planned,
                        now = now,
                        access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source),
                        onBack = {},
                        onRetry = {},
                        onWalkingSpeedChange = {},
                        walkingSpeedWriteFailed = true,
                        onWalkingSpeedWriteFailureShown = { shown++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Couldn't save that", substring = true).assertIsDisplayed()
        assertEquals(1, shown)
    }

    // Three rides whose Planner train on the last is missed with no live one known, on a narrow
    // screen: the pills leave room beside them for a word of the arrival, not all of it.
    @Test
    @Config(qualifiers = "en-rGB-w411dp-h720dp-420dpi")
    fun a_route_arrival_that_does_not_fit_beside_its_pills_is_shown_whole() {
        val threeRides = TripRoute(
            listOf(
                leg("overground", "windrush", "Windrush", highbury, whitechapel, 3, 16, 6, change = 3),
                leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr, canaryWharfXr, 19, 23, 2, change = 5),
                leg("tube", "jubilee", "Jubilee", canaryWharf, canadaWaterTube, 32, 34, 1),
            ),
        )
        show(planned.copy(routes = listOf(threeRides)))
        val text = composeRule.activity.getString(R.string.trip_arrival_unknown)
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        val layout = results.single()
        // One line, nothing cut: clipped at a word, "Arrival unknown" read as "Arrival" (and
        // "est. 44 min · 10:29" as "est. 44 min").
        assertEquals(1, layout.lineCount)
        assertEquals(text.length, layout.getLineEnd(0))
        assertTrue("arrival ellipsized", !layout.isLineEllipsized(0))
        assertTrue("arrival clipped", layout.getLineRight(0) - layout.getLineLeft(0) <= layout.size.width + 1f)
    }

    // Under each card's header, a row per ride with where it gets off, so two routes on the same
    // lines read apart by where they change.
    @Test
    fun a_route_card_names_where_each_ride_gets_off() {
        show(planned.copy(routes = listOf(viaCanadaWater, viaWhitechapel)))
        val stops = composeRule.onAllNodes(hasTestTag("rideStops"), useUnmergedTree = true)
        stops.assertCountEquals(2)
        composeRule.onAllNodesWithText("Canada Water", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Whitechapel", useUnmergedTree = true).onFirst().assertExists()
        composeRule.onAllNodesWithText("Canary Wharf", useUnmergedTree = true).assertCountEquals(2)
        // A later ride says how often its line runs, from its live trains: the Elizabeth line at 14,
        // 18 and 24 is every 4 to 6 minutes. The Jubilee, with one train known, says nothing.
        // ↻ for "every", to save width, read out as the word.
        composeRule.onAllNodesWithText("↻ 4–6 min", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Every 4 to 6 min", useUnmergedTree = true).assertCountEquals(1)
        // Above the rides, the walk to where each starts: 2 min, so a train sooner than that reads
        // as grayed for a reason. It takes the place of "From ‹stop›" in the top row.
        composeRule.onAllNodes(hasTestTag("walkToStart"), useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Walk to Highbury & Islington (~2 min)", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("From", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    // A first stop right there has no walk to show: the top row says where the trip starts instead.
    @Test
    fun a_route_card_with_no_walk_says_where_it_starts() {
        show(planned.copy(routes = listOf(viaCanadaWater, viaWhitechapel)), access = Duration.ZERO)
        composeRule.onAllNodes(hasTestTag("walkToStart"), useUnmergedTree = true).assertCountEquals(0)
        // "From" drawn beside the stop, not in it, so a narrow row cuts the stop's name, never "From".
        composeRule.onAllNodesWithText("From ", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("Highbury & Islington", useUnmergedTree = true).assertCountEquals(2)
    }

    // A trip's stop names shorten as the main screen's destinations do: whole words first, each
    // part of a slash-separated name alike, the full name kept for a screen reader.
    @Test
    fun a_stop_name_shortens_before_it_is_cut() {
        val name = "Shepherd's Bush Market / Wood Lane"
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Column {
                    Box(Modifier.width(400.dp).testTag("wide")) { ShortenedName(name, androidx.compose.material3.MaterialTheme.typography.bodyLarge) }
                    Box(Modifier.width(160.dp).testTag("narrow")) { ShortenedName(name, androidx.compose.material3.MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        composeRule.onNode(hasText(name) and hasAnyAncestor(hasTestTag("wide")), useUnmergedTree = true).assertExists()
        val narrow = composeRule.onNode(hasText("Wood Ln") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true)
        narrow.assertExists()
        // Too narrow even for the floor, each place elides on its own: "Wood Ln" is drawn whole,
        // not lost behind one trailing "…". The full name stays the screen-reader label.
        composeRule.onNode(hasTestTag("narrow"), useUnmergedTree = true).onChild().assert(hasContentDescription(name))
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNode(hasText("Wood Ln") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        assertTrue("Wood Ln elided", !results.single().isLineEllipsized(0))
    }

    // A slash-separated name with nothing to shorten still splits, rather than eliding once at the end.
    @Test
    fun a_stop_name_with_nothing_to_shorten_still_keeps_each_place() {
        val name = "Kensington / Hammersmith"
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.width(120.dp).testTag("narrow")) { ShortenedName(name, androidx.compose.material3.MaterialTheme.typography.bodyLarge) }
            }
        }
        // Each place is its own text, so neither is lost behind the other's "…".
        composeRule.onNode(hasText("Kensington") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasText("Hammersmith") and hasAnyAncestor(hasTestTag("narrow")), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasTestTag("narrow"), useUnmergedTree = true).onChild().assert(hasContentDescription(name))
    }

    // The arrival is as large as the first ride's times (maintainer, 2026-09-27): when the trip gets
    // there matters as much as when it leaves.
    @Test
    fun a_route_cards_arrival_is_as_large_as_its_times() {
        show(planned)
        fun fontSizeOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): androidx.compose.ui.unit.TextUnit {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
            return results.single().layoutInput.style.fontSize
        }
        val arrival = fontSizeOf(composeRule.onAllNodes(hasTestTag("tripArrival"), useUnmergedTree = true).onFirst())
        val times = fontSizeOf(composeRule.onAllNodes(hasTestTag("firstRideTimes"), useUnmergedTree = true).onFirst())
        assertEquals(times, arrival)
    }

    @Test
    fun an_arrivals_range_ends_in_minutes_within_its_hour() {
        val arrival = Instant.parse("2026-09-26T10:26:00Z") // 11:26 in London
        assertEquals("34", arrivalEnd(arrival, Duration.ofMinutes(8)))
        assertEquals("12:04", arrivalEnd(Instant.parse("2026-09-26T10:56:00Z"), Duration.ofMinutes(8)))
        // Any whole minute is a range, so the order estimates rank in can be read off them; under a
        // minute there's none.
        assertEquals(null, arrivalEnd(arrival, Duration.ofSeconds(50)))
        assertEquals("27", arrivalEnd(arrival, Duration.ofMinutes(1)))
        assertEquals("28", arrivalEnd(arrival, Duration.ofMinutes(2)))
        // Across the autumn clock change the hour repeats: 01:58 BST + 8 min is 01:06 GMT, shown whole.
        assertEquals("01:06", arrivalEnd(Instant.parse("2026-10-25T00:58:00Z"), Duration.ofMinutes(8)))
    }

    // Real predictions leave seconds on both halves: 16m30s away with 1m40s of slack is 18m10s at the
    // latest, so the range reads 16–18 min beside 11:16–18, not 16–17.
    @Test
    fun an_arrivals_range_rounds_its_latest_minutes_once() {
        val duration = Duration.ofMinutes(16).plusSeconds(30)
        val slack = Duration.ofMinutes(1).plusSeconds(40)
        assertEquals(18, latestMinutes(duration, slack))
        assertEquals(16, latestMinutes(duration, Duration.ZERO))
        assertEquals(3, latestMinutes(null, Duration.ofMinutes(3)))
        val arrival = Instant.parse("2026-09-26T10:16:30Z") // 11:16:30 in London
        assertEquals("18", arrivalEnd(arrival, slack))
    }

    @Test
    fun a_route_cards_first_ride_times_are_part_of_the_card() {
        show(planned.copy(statuses = planned.statuses + ("windrush" to LineStatus("windrush", 6, "Severe Delays"))))
        // The disrupted Windrush warns on each card's row: two cards start on the Windrush.
        assertEquals(2, composeRule.onAllNodesWithContentDescription("Severe Delays").fetchSemanticsNodes().size)
        val glyphs = composeRule.onAllNodesWithContentDescription("Severe Delays", useUnmergedTree = true).fetchSemanticsNodes()
        // Just before the row's times, as the main screen puts it (maintainer, 2026-09-28): each ⚠
        // ends left of the times on its own row, past the row's middle, after the stop's name.
        val times = composeRule.onAllNodesWithTag("firstRideTimes", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val width = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        glyphs.map { it.boundsInRoot }.forEach { glyph ->
                val row = times.single { it.top < glyph.bottom && glyph.top < it.bottom }
            assertTrue(glyph.right <= row.left)
            assertTrue(glyph.left > width / 2)
        }
        captureSnapshot("trip-routes-disrupted.png")
        // The first ride's times are part of the card (maintainer, 2026-09-27): none opens its line's
        // page, and tapped, they open the card's route.
        val details = composeRule.activity.getString(R.string.departure_details)
        composeRule.onAllNodes(
            SemanticsMatcher("opens its line's page") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == details },
        ).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
        composeRule.onNodeWithTag("tripLegs").assertExists()
    }

    @Test
    fun a_route_cards_warning_is_only_for_the_way_its_ride_goes() {
        // The Windrush's delays are for trains the other way: the ride's trains go outbound (train()).
        val severe = LineStatus("windrush", 6, "Severe Delays")
        val good = LineStatus("windrush", LineStatus.GOOD_SERVICE, "Good Service")
        show(planned.copy(statuses = planned.statuses + ("windrush" to severe.copy(byDirection = mapOf("inbound" to severe, "outbound" to good)))))
        composeRule.onAllNodesWithContentDescription("Severe Delays").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().assertExists()
    }

    @Test
    fun a_route_cards_long_press_offers_every_legs_mode() {
        val hidden = mutableListOf<String>()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, onHideMode = { hidden += it },
                )
            }
        }
        composeRule.waitForIdle()
        // Anywhere on a card, a long press opens the card's menu: one per card.
        val more = composeRule.activity.getString(R.string.more_actions)
        val menus = composeRule.onAllNodes(
            SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        )
        assertEquals(3, menus.fetchSemanticsNodes().size)
        // The first card rides the Windrush then the Jubilee: both groups, not just the first leg's.
        menus.onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide all train services").assertIsDisplayed()
        composeRule.onNodeWithText("Hide all Tube & DLR services").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("tube"), hidden)
        // Each leg's line too, by itself.
        menus.onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide Windrush line").assertIsDisplayed()
        composeRule.onNodeWithText("Hide Jubilee line").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("tube", HiddenModes.lineKey("jubilee", "Jubilee line")), hidden)
    }

    @Test
    fun a_line_avoided_from_a_cards_long_press_leaves_its_routes_out_until_its_chip_is_tapped() {
        var avoided by mutableStateOf(emptySet<String>())
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, onHideMode = {},
                    onTripModesChange = {},
                    avoidedLines = avoided,
                    onAvoidLine = { avoided = avoided + it },
                    onStopAvoiding = { avoided = avoided - it },
                )
            }
        }
        composeRule.waitForIdle()
        val more = composeRule.activity.getString(R.string.more_actions)
        val menus = composeRule.onAllNodes(
            SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        )
        assertEquals(3, menus.fetchSemanticsNodes().size)
        // Nothing avoided, no chips.
        composeRule.onNodeWithTag("avoidedLines").assertDoesNotExist()
        // The first card rides the Windrush then the Jubilee: each can be avoided, beside its hide.
        menus.onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Avoid Windrush line").assertIsDisplayed()
        composeRule.onNodeWithText("Avoid Jubilee line").performClick()
        composeRule.waitForIdle()
        val jubilee = AvoidedLines.key("jubilee", "Jubilee line")
        assertEquals(setOf(jubilee), avoided)
        // Both routes riding it are left out, and its chip atop the routes says so.
        assertEquals(1, menus.fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("avoidedLines").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop avoiding Jubilee line").assertIsDisplayed()
        captureSnapshot("trip-routes-avoiding.png")
        // A tap on the chip stops avoiding it: the routes come back, and the chips go.
        composeRule.onNodeWithContentDescription("Stop avoiding Jubilee line").performClick()
        composeRule.waitForIdle()
        assertEquals(emptySet<String>(), avoided)
        assertEquals(3, menus.fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("avoidedLines").assertDoesNotExist()
    }

    @Test
    fun an_avoided_line_is_no_hidden_line_on_a_trip() {
        // Avoided, the Jubilee leaves the trip by its own chip: the hidden banner, and its Show all,
        // are for what's hidden from every list.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    avoidedLines = setOf(AvoidedLines.key("jubilee", "Jubilee line")),
                    onStopAvoiding = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.modes_show_all)).assertDoesNotExist()
        composeRule.onNodeWithText("Avoiding").assertIsDisplayed()
        // Only the route by the Elizabeth line is left.
        composeRule.onAllNodesWithText("Jubilee", substring = true).assertCountEquals(1)
    }

    // Whitechapel's Overground to its Elizabeth line: a walk between two rides.
    private val onFootWalk = TripLeg(TripLeg.WALKING, "", "", whitechapel.first, "Whitechapel (Overground)", whitechapelXr.first, "Whitechapel (Elizabeth line)", at(16), at(19))
    private val viaWhitechapelOnFoot = TripRoute(
        listOf(
            leg("overground", "windrush", "Windrush", highbury, whitechapel.first to "Whitechapel (Overground)", 3, 16, 6),
            onFootWalk,
            leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr.first to "Whitechapel (Elizabeth line)", canaryWharfXr, 21, 25, 2),
        ),
    )

    private fun showOnFoot(changesOnFoot: Set<TripLeg>) {
        val state = TripViewModel.State(
            routes = listOf(viaWhitechapelOnFoot), plannedAt = now, closures = checkedOpen(viaWhitechapelOnFoot), changesOnFoot = changesOnFoot,
        )
        val openRoute = mutableStateOf<String?>(openRouteOf(viaWhitechapelOnFoot, state, emptyMap()).encode())
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = state, now = now, access = Duration.ZERO,
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun a_route_s_change_on_foot_shows_as_its_change() {
        // Decided a change on foot: no "walk to", the change's minutes instead.
        showOnFoot(setOf(onFootWalk))
        composeRule.onNodeWithText("┊  3 min to change").assertIsDisplayed()
        composeRule.onNodeWithText("Walk to", substring = true).assertDoesNotExist()
    }

    @Test
    fun a_route_s_walk_not_a_change_on_foot_stays_a_walk() {
        // Both directions: not decided one, a walk.
        showOnFoot(emptySet())
        composeRule.onNodeWithText("Walk to Whitechapel (Elizabeth line)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("min to change", substring = true).assertDoesNotExist()
    }

    @Test
    fun trip_route_legs() {
        show(planned)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onNodeWithText("┊  3 min to change").assertIsDisplayed()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        captureSnapshot("trip-route-legs.png")
    }

    @Test
    fun an_open_routes_later_leg_says_how_often_its_line_runs() {
        show(planned)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        // The Elizabeth line, ridden after the change, says how often it runs instead of counting
        // down (maintainer, 2026-09-29): its trains at 14, 18 and 24 are every 4 to 6 minutes, the
        // figure its row on the list's card gives, and none of its countdowns show.
        composeRule.onAllNodesWithText("↻ 4–6 min", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Every 4 to 6 min", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("14 · 18 · 24 min", useUnmergedTree = true).assertCountEquals(0)
        // The next ride still counts down, reached after the 2 min walk: its train in 3 min is caught,
        // nothing grays, and its times are read as shown.
        assertEquals(emptyList<String>(), grayedTimes("3 · 11 min"))
        assertTrue(SemanticsProperties.ContentDescription !in composeRule.onNodeWithText("3 · 11 min").fetchSemanticsNode().config)
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun an_open_rides_rows_come_with_its_frame_never_after_its_stop_count() {
        // Worked out on the page's worker with the rest of the open route ([TripOpenView.rides]), never
        // in composition and never in a run of their own: the worker steps one run at a time, and no
        // frame shows the ride's stop count without its rows above it (Codex, #584).
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = java.util.concurrent.Executor { queued.add(it) }.asCoroutineDispatcher()
        try {
            show(planned, worker = worker)
            fun step() {
                queued.poll()?.let { threads.submit(it).get() }
                composeRule.waitForIdle()
            }
            fun listed() = composeRule.onAllNodesWithText("28 min · ~08:30").fetchSemanticsNodes().isNotEmpty()
            repeat(200) { if (!listed()) step() }
            assertTrue("the route is listed", listed())
            composeRule.onNodeWithText("28 min · ~08:30").performClick()
            composeRule.waitForIdle()
            var opened = false
            repeat(200) {
                if (opened) return@repeat
                val counted = composeRule.onAllNodesWithText("6 stops to Whitechapel").fetchSemanticsNodes().isNotEmpty()
                val rows = composeRule.onAllNodesWithText("3 · 11 min").fetchSemanticsNodes().isNotEmpty()
                assertTrue("the ride's stop count shows only with its rows", !counted || rows)
                opened = counted
                step()
            }
            assertTrue("the route opened", opened)
        } finally {
            threads.shutdown()
        }
    }

    @Test
    fun an_open_routes_next_ride_counts_down_after_a_walk_to_it() {
        // A route starting with a walk leg of the Planner's own: the ride after it is the rider's next,
        // so it counts down, and its trains gone before the rider gets there are grayed.
        val walkFirst = TripRoute(
            listOf(
                TripLeg(
                    mode = "walking", lineId = "", lineName = "", fromId = "", fromName = "Here",
                    toId = whitechapelXr.first, toName = whitechapelXr.second, departure = now, arrival = at(16),
                ),
                leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr, canaryWharfXr, 19, 23, 2),
            ),
        )
        show(planned.copy(routes = listOf(walkFirst)), access = Duration.ZERO)
        composeRule.onNodeWithTag("rideStops", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        // On the Elizabeth line's platform at 16: its train in 14 min leaves too soon.
        assertEquals(listOf("14"), grayedTimes("14 · 18 · 24 min"))
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun an_open_routes_later_leg_with_too_few_trains_known_shows_no_times() {
        show(planned)
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 stop to Canary Wharf").assertIsDisplayed()
        // One Jubilee train known at Canada Water says nothing of how often the line runs, and its
        // countdown would say nothing the rider can use there: the row shows neither.
        composeRule.onAllNodesWithText("↻", substring = true, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("25 min", useUnmergedTree = true).assertCountEquals(0)
    }

    // The times a countdown shows grayed: those it styles apart from the rest of its label.
    private fun grayedTimes(label: String): List<String> {
        val text = composeRule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        return text.spanStyles.map { text.text.substring(it.start, it.end) }
    }

    @Test
    fun an_open_route_stays_open_when_six_faster_routes_crowd_it_out_of_the_list() {
        val state = mutableStateOf(planned)
        showWith(state)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        // A settled plan with six routes arriving sooner: the list would time only those, but the
        // open route is still offered, so it stays timed and open.
        val faster = (1..TripViewModel.MAX_ROUTES).map { i ->
            TripRoute(listOf(leg("tube", "jubilee", "Jubilee", "940GFAST$i" to "Stop $i", canaryWharf, 3, 8L + i, 3)))
        }
        state.value = planned.copy(routes = faster + planned.routes.orEmpty())
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun an_open_route_whose_mode_is_hidden_stays_closed_when_the_mode_is_shown_again() {
        val hidden = mutableStateOf(emptySet<String>())
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    hiddenModes = hidden.value,
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        // Its Elizabeth line hidden: the route isn't shown, so the list is, and the route is closed.
        hidden.value = setOf("elizabeth-line")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
        // Shown again, it's back in the list, not reopened over it.
        hidden.value = emptySet()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertDoesNotExist()
    }

    @Test
    fun an_open_train_through_a_change_shows_at_once_while_a_restore_reloads_its_route_data() {
        // Red Aston → Beck, change, blue on to Cole; blue to Dale also runs from Aston through Beck
        // to Cole, and one leaves in 5 min: a route of its own, with no change ([withThroughRoutes]).
        val names = listOf("Aston", "Beck", "Mead", "Cole", "Dale", "Red End").associateWith { it }
        val lines = mapOf(
            "red" to LineSequence(listOf(LineRoute("red", listOf("Aston", "Beck", "Red End"))), names),
            "blue" to LineSequence(listOf(LineRoute("to Dale", listOf("Aston", "Beck", "Mead", "Cole", "Dale"))), names),
        )
        val changing = TripRoute(
            listOf(
                leg("tube", "red", "Red", "Aston" to "Aston", "Beck" to "Beck", 1, 3, 1, change = 4),
                leg("tube", "blue", "Blue", "Beck" to "Beck", "Cole" to "Cole", 9, 15, 2),
            ),
        )
        val trip = TripViewModel.State(
            routes = listOf(changing),
            plannedAt = now,
            closures = checkedOpen(changing),
            live = mapOf(
                "Aston" to TripViewModel.StopLive(listOf(train("red", "Red", "tube", "Red End", 1, "Platform 1"), train("blue", "Blue", "tube", "Dale", 5, "Platform 2")), now),
                "Beck" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 9, "Platform 2")), now),
            ),
            statuses = mapOf(
                "red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"),
                "blue" to LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service"),
            ),
        )
        val through = withThroughRoutes(trip, lines).routes.orEmpty().single { routeKey(it) != routeKey(changing) }
        val key = openRouteOf(through, trip, lines).encode()
        // Restored with the train through open, before its lines' route data has reloaded.
        val loads = kotlinx.coroutines.CompletableDeferred<Unit>()
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    loads.await()
                    return lines.getValue(lineId)
                }
            },
        )
        val openRoute = mutableStateOf<String?>(key)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Cole", state = trip, now = now, access = Duration.ZERO,
                    routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.waitForIdle()
        // Kept whole, so shown at once: its trains still to come, as nothing has made it yet.
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        loads.complete(Unit)
        composeRule.waitForIdle()
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    @Test
    fun a_leg_moved_to_another_pole_is_another_placement() {
        // The frame's guard ([placementOf]): the same routes rebuilt (a refresh) are the same number, so
        // a held frame still stands in; a leg boarding or getting off elsewhere (a stop pair placed, a
        // stand fetched, a route refreshed) is another, so it doesn't (Codex, #529).
        val rebuilt = planned.copy(routes = planned.routes!!.map { route -> route.copy(legs = route.legs.map { it.copy() }) })
        assertEquals(placementOf(planned), placementOf(rebuilt))
        val first = planned.routes!!.first()
        val moved = planned.copy(routes = listOf(first.copy(legs = listOf(first.legs.first().copy(fromId = "elsewhere")) + first.legs.drop(1))) + planned.routes!!.drop(1))
        assertNotEquals(placementOf(planned), placementOf(moved))
        val alighting = planned.copy(routes = listOf(first.copy(legs = listOf(first.legs.first().copy(toId = "elsewhere")) + first.legs.drop(1))) + planned.routes!!.drop(1))
        assertNotEquals(placementOf(planned), placementOf(alighting))
        // The number [placed] publishes with the placed routes is theirs (Codex, #529).
        val (placedState, number) = placed(planned, emptyMap())
        assertEquals(placementOf(placedState), number)
    }

    @Test
    fun an_open_train_through_a_change_stays_open_while_its_stop_pairs_other_pole_answers() {
        // Red boards at a stop pair, Aston and Aston 2; blue to Dale runs through Beck to Cole from
        // Aston 2 alone, so it's that pole's arrivals that predict the train through.
        val names = listOf("Aston", "Aston2", "Beck", "Mead", "Cole", "Dale", "Red End").associateWith { it }
        val lines = mapOf(
            "red" to LineSequence(listOf(LineRoute("red", listOf("Aston", "Beck", "Red End"))), names),
            "blue" to LineSequence(listOf(LineRoute("to Dale", listOf("Aston2", "Beck", "Mead", "Cole", "Dale"))), names),
        )
        val changing = TripRoute(
            listOf(
                leg("tube", "red", "Red", "Aston" to "Aston", "Beck" to "Beck", 1, 3, 1, change = 4).copy(fromArea = "AstonPair"),
                leg("tube", "blue", "Blue", "Beck" to "Beck", "Cole" to "Cole", 9, 15, 2),
            ),
        )
        val statuses = mapOf(
            "red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"),
            "blue" to LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service"),
        )
        val throughTrain = "Aston2" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 5, "Platform 2")), now)
        val refreshed = TripViewModel.State(
            routes = listOf(changing),
            plannedAt = now,
            closures = checkedOpen(changing),
            live = mapOf(
                "Aston" to TripViewModel.StopLive(listOf(train("red", "Red", "tube", "Red End", 1, "Platform 1")), now),
                "Beck" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 9, "Platform 2")), now),
                throughTrain,
            ),
            statuses = statuses,
            areaPoles = mapOf("AstonPair" to listOf("Aston", "Aston2")),
        )
        val through = withThroughRoutes(refreshed, lines).routes.orEmpty().single { routeKey(it) != routeKey(changing) }
        val key = openRouteOf(through, refreshed, lines).encode()
        // Restored with the train through open: the Planner's own poles have answered (from the shared
        // arrivals), the other pole's not yet.
        val state = mutableStateOf(refreshed.copy(live = refreshed.live - throughTrain.first))
        val openRoute = mutableStateOf<String?>(key)
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = lines.getValue(lineId)
            },
        )
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Cole", state = state.value, now = now, access = Duration.ZERO,
                    routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.waitForIdle()
        // No train through predicted yet: shown all the same, and not closed.
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        state.value = refreshed
        composeRule.waitForIdle()
        assertEquals(key, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    // Red Aston → Beck, change, blue on to Cole; blue to Dale also runs from Aston through Beck to
    // Cole: a route of its own when one is predicted at Aston ([withThroughRoutes]). [throughIn] is
    // when that train leaves (none predicted when null), in arrivals fetched at [fetchedAt].
    private val throughNames = listOf("Aston", "Beck", "Mead", "Cole", "Dale", "Red End").associateWith { it }
    private val throughLines = mapOf(
        "red" to LineSequence(listOf(LineRoute("red", listOf("Aston", "Beck", "Red End"))), throughNames),
        "blue" to LineSequence(listOf(LineRoute("to Dale", listOf("Aston", "Beck", "Mead", "Cole", "Dale"))), throughNames),
    )
    private val throughChanging = TripRoute(
        listOf(
            leg("tube", "red", "Red", "Aston" to "Aston", "Beck" to "Beck", 1, 3, 1, change = 4),
            leg("tube", "blue", "Blue", "Beck" to "Beck", "Cole" to "Cole", 9, 15, 2),
        ),
    )

    private fun throughTrip(throughIn: Long?, fetchedAt: Instant = now) = TripViewModel.State(
        routes = listOf(throughChanging),
        plannedAt = now,
        closures = checkedOpen(throughChanging),
        live = mapOf(
            "Aston" to TripViewModel.StopLive(
                listOfNotNull(
                    train("red", "Red", "tube", "Red End", 1, "Platform 1"),
                    throughIn?.let { train("blue", "Blue", "tube", "Dale", it, "Platform 2") },
                ),
                fetchedAt,
            ),
            "Beck" to TripViewModel.StopLive(listOf(train("blue", "Blue", "tube", "Dale", 9, "Platform 2")), fetchedAt),
        ),
        statuses = mapOf(
            "red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"),
            "blue" to LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service"),
        ),
    )

    // The train through, open ([OpenRoute]), as tapped on the list while one was predicted.
    private val throughKey by lazy {
        val trip = throughTrip(throughIn = 5)
        val through = withThroughRoutes(trip, throughLines).routes.orEmpty().single { routeKey(it) != routeKey(throughChanging) }
        openRouteOf(through, trip, throughLines).encode()
    }

    private fun showThrough(state: MutableState<TripViewModel.State>, openRoute: MutableState<String?>) {
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = throughLines.getValue(lineId)
            },
        )
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Cole", state = state.value, now = now, access = Duration.ZERO,
                    routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun each_listed_routes_open_route_is_worked_out_as_one_tapped_would_be() {
        // What the frame works out for each card ([openRoutesOf], the board gone over once) is what
        // [openRouteOf] gives for it alone: a train through a change opens with the route it's made from.
        val trip = throughTrip(throughIn = 5)
        val routes = withThroughRoutes(trip, throughLines).routes.orEmpty()
        val opens = openRoutesOf(routes, trip, throughLines)
        assertEquals(routes.map { routeKey(it) }.distinct().toSet(), opens.keys)
        routes.forEach { assertEquals(openRouteOf(it, trip, throughLines), opens.getValue(routeKey(it))) }
        assertEquals(throughKey, opens.values.single { it.encode() == throughKey }.encode())
    }

    @Test
    fun a_held_through_card_opens_the_route_it_was_drawn_as() {
        // Newer arrivals drop the train through the change while the worker is held: the card still
        // shown opens what it was drawn as, from its own frame, not a bare key the newer state can't
        // place, which would close at once (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var state by mutableStateOf(throughTrip(throughIn = 5))
        val openRoute = mutableStateOf<String?>(null)
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = throughLines.getValue(lineId)
            },
        )
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Cole", state = state, now = now, access = Duration.ZERO,
                            routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("13 min · ~08:15").fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            // The same plan, newer arrivals only, none listing the train through: the frame stands in.
            state = state.copy(live = emptyMap())
            composeRule.waitForIdle()
            composeRule.onNodeWithText("13 min · ~08:15").performClick()
            composeRule.waitForIdle()
            assertEquals(throughKey, openRoute.value)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun an_open_train_through_a_change_stays_open_on_a_return_to_stale_arrivals() {
        // A retained trip shown again, its arrivals aged past standing: the route shows, its train
        // withheld until the return's refresh lands.
        val state = mutableStateOf(throughTrip(throughIn = 5, fetchedAt = now.minus(Duration.ofMinutes(10))))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        assertEquals(throughKey, openRoute.value)
        state.value = state.value.copy(refreshing = true)
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        // The return's refresh lands, and the train is still coming.
        state.value = throughTrip(throughIn = 5)
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    @Test
    fun an_open_train_through_a_change_stays_open_with_a_dash_once_its_train_is_no_longer_predicted() {
        val state = mutableStateOf(throughTrip(throughIn = 5))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        composeRule.onNodeWithText("Arrival unknown", substring = true).assertDoesNotExist()
        // The plan still offers the change it runs through, so the route stays (maintainer,
        // 2026-09-29): its line reads "–", as a line with no trains does on the list, and its arrival
        // is withheld rather than taken from the Planner's times for the change.
        state.value = throughTrip(throughIn = null)
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        composeRule.onNodeWithText("Arrival unknown", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("–").fetchSemanticsNodes().isNotEmpty())
        captureSnapshot("trip-through-no-train.png")
        // Predicted again: timed from it once more.
        state.value = throughTrip(throughIn = 5)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Arrival unknown", substring = true).assertDoesNotExist()
    }

    @Test
    fun an_open_train_through_a_change_no_longer_predicted_is_off_the_list_closed_while_the_worker_is_held() {
        // Kept only as the one open, its train not predicted: closed while the worker is held, the list
        // the frame in hand shows leaves it off, as the next frame does (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val state = throughTrip(throughIn = null)
        val openRoute = mutableStateOf<String?>(throughKey)
        val routeStops = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence = throughLines.getValue(lineId)
            },
        )
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Cole", state = state, now = now, access = Duration.ZERO,
                            routeStops = routeStops, onBack = {}, onRetry = {}, openRoute = openRoute,
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("3 stops to Cole").fetchSemanticsNodes().isNotEmpty()
            }
            threads.execute { gate.await() }
            openRoute.value = null
            composeRule.waitForIdle()
            val held = composeRule.onAllNodesWithTag("tripArrival", useUnmergedTree = true).fetchSemanticsNodes().size
            gate.countDown()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("tripArrival", useUnmergedTree = true).assertCountEquals(held)
            assertEquals(1, held)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun an_open_train_through_a_change_stays_open_when_a_new_plan_offers_it_as_a_route_of_its_own() {
        val state = mutableStateOf(throughTrip(throughIn = 5))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        // A re-plan drops the change but plans blue from Aston to Cole itself: the same lines and stops.
        val direct = TripRoute(listOf(leg("tube", "blue", "Blue", "Aston" to "Aston", "Cole" to "Cole", 5, 13, 3)))
        state.value = throughTrip(throughIn = 5).copy(routes = listOf(direct))
        composeRule.waitForIdle()
        assertEquals(throughKey, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
    }

    @Test
    fun an_open_train_through_a_change_closes_for_good_once_the_plan_drops_its_change() {
        val state = mutableStateOf(throughTrip(throughIn = 5))
        val openRoute = mutableStateOf<String?>(throughKey)
        showThrough(state, openRoute)
        composeRule.onNodeWithText("3 stops to Cole").assertIsDisplayed()
        // A new plan without the change: closed, and not reopened when a later plan offers it again.
        state.value = throughTrip(throughIn = 5).copy(routes = listOf(TripRoute(listOf(throughChanging.legs.first()))))
        composeRule.waitForIdle()
        assertEquals(null, openRoute.value)
        state.value = throughTrip(throughIn = 5)
        composeRule.waitForIdle()
        assertEquals(null, openRoute.value)
        composeRule.onNodeWithText("3 stops to Cole").assertDoesNotExist()
    }

    private fun showWith(state: MutableState<TripViewModel.State>) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = state.value, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun an_open_route_starts_on_the_way() {
        var started: TripRoute? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = { started = it },
                )
            }
        }
        // The list itself offers no Start: only an open route does.
        composeRule.onNodeWithText("Start").assertDoesNotExist()
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").performClick()
        composeRule.runOnIdle { assertEquals(viaCanadaWater, started) }
    }

    @Test
    fun start_waits_while_the_origin_is_found_again() {
        // A re-locate in flight: the route may change with the new fix, so it can't be started yet.
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    relocating = true, onStart = {},
                )
            }
        }
        // Its times read as estimates meanwhile.
        composeRule.onAllNodesWithText("27 min", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun a_route_with_a_national_rail_train_says_it_cant_be_followed() {
        // The same route, its first train a National Rail one: its departures name no train to follow.
        val byRail = TripRoute(listOf(viaCanadaWater.legs[0].copy(mode = "national-rail"), viaCanadaWater.legs[1]))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(routes = listOf(byRail)), now = now,
                    access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = {},
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        // Worked out off the main thread ([rememberStartCheck]): Start holds its place until then.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Can't follow National Rail trains yet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Can't follow National Rail trains yet").assertIsDisplayed()
    }

    @Test
    fun a_trip_already_on_the_way_is_opened_not_replaced() {
        var started = false
        var opened = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = { started = true }, onOpenTrip = { opened = true },
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start").assertDoesNotExist()
        composeRule.onNodeWithText("Open current trip").performClick()
        composeRule.runOnIdle {
            assertTrue(opened)
            assertFalse(started)
        }
    }

    /** Waits for Start to be enabled: whether it can start is worked out off the main thread ([rememberStartCheck]). */
    private fun waitForStart() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Start").fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config }
        }
    }

    @Test
    fun a_route_can_replace_the_trip_on_the_way_once_confirmed() {
        var started = false
        var replaced: TripRoute? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = { started = true }, onOpenTrip = {}, onReplaceTrip = { replaced = it },
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open current trip").assertIsDisplayed()
        // Start asks first: ending the trip on the way can't be undone. Cancel leaves it be.
        waitForStart()
        composeRule.onNodeWithText("Start").performClick()
        composeRule.onNodeWithText("Replace current trip?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Replace current trip?").assertDoesNotExist()
        composeRule.runOnIdle { assertNull(replaced) }
        composeRule.onNodeWithText("Start").performClick()
        composeRule.onNodeWithText("Replace").performClick()
        composeRule.runOnIdle {
            assertNotNull(replaced)
            assertFalse(started)
        }
    }

    @Test
    fun the_replace_question_goes_with_the_trip_it_asked_about() {
        var onTheWay by mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = {},
                    onOpenTrip = if (onTheWay) ({}) else null,
                    onReplaceTrip = if (onTheWay) ({}) else null,
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        waitForStart()
        composeRule.onNodeWithText("Start").performClick()
        composeRule.onNodeWithText("Replace current trip?").assertIsDisplayed()
        // The trip on the way arrives while it's asked, then the rider starts this one: not asked again.
        onTheWay = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Replace current trip?").assertDoesNotExist()
        onTheWay = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Replace current trip?").assertDoesNotExist()
    }

    @Test
    fun the_paired_buttons_grow_together_for_a_large_font() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StopDashTheme(dynamicColor = false) {
                    TripScreen(
                        title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                        onStart = {}, onOpenTrip = {}, onReplaceTrip = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        // "Open current trip" wraps at twice the font size: both grow past 56dp, as one, unclipped.
        val open = composeRule.onNodeWithText("Open current trip").fetchSemanticsNode().boundsInRoot
        val start = composeRule.onNodeWithText("Start").fetchSemanticsNode().boundsInRoot
        val minPx = with(composeRule.density) { 56.dp.toPx() }
        assertTrue("open ${open.height} > $minPx", open.height > minPx)
        assertEquals(open.height, start.height, 0.5f)
        assertEquals(open.top, start.top, 0.5f)
    }

    @Test
    fun the_replace_question_survives_a_configuration_change() {
        val restoration = StateRestorationTester(composeRule)
        var replaced: TripRoute? = null
        restoration.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    onStart = {}, onOpenTrip = {}, onReplaceTrip = { replaced = it },
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        waitForStart()
        composeRule.onNodeWithText("Start").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Replace").performClick()
        composeRule.runOnIdle { assertNotNull(replaced) }
    }

    @Test
    fun an_open_routes_header_warns_just_before_its_arrival() {
        show(planned)
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        // One ⚠ for the disrupted Jubilee, just before the arrival (maintainer, 2026-09-28), not
        // beside its pill: on the arrival's line, ending left of it.
        val arrival = composeRule.onAllNodesWithText("27 min · ~08:29", useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        val glyph = composeRule.onAllNodesWithContentDescription("Minor Delays", useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }.single { it.top < arrival.bottom && arrival.top < it.bottom }
        assertTrue(glyph.right <= arrival.left)
        assertTrue(arrival.left - glyph.right < 40f)
    }

    @Test
    fun trip_leg_opens_its_line() {
        show(planned)
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        // The Jubilee leg's row opens the line's page, with its service alert, as the main screen's does.
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        composeRule.onAllNodesWithText("Minor Delays", substring = true).onFirst().assertExists()
        captureSnapshot("trip-leg-line.png")
    }

    @Test
    fun a_line_page_stays_open_while_the_page_waits_for_its_frame() {
        // A re-locate starts with the worker held: the last frame can't stand in, but the line page open
        // isn't judged gone without one, and shows again once the new frame is in (Codex, #529).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        var relocating by mutableStateOf(false)
        try {
            composeRule.setContent {
                StopDashTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalWorker provides worker) {
                        TripScreen(
                            title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                            routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, relocating = relocating,
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("27 min · ~08:29").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("27 min · ~08:29").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText("Stratford").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("Stratford").performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
            threads.execute { gate.await() }
            relocating = true
            composeRule.waitForIdle()
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Minor Delays", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        } finally {
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun a_leg_page_dismisses_its_line_alert_as_the_list_does() {
        val dismissedRows = mutableListOf<DepartureRow>()
        val dismissed = androidx.compose.runtime.mutableStateOf(emptySet<DismissedAlert>())
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    dismissed = dismissed.value, onDismissAlert = { dismissedRows += it },
                )
            }
        }
        composeRule.onNodeWithText("27 min · ~08:29").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        // The Jubilee's Minor Delays carries the list page's ×, dismissing it line-wide.
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.alert_dismiss)).performClick()
        assertEquals(listOf("jubilee"), dismissedRows.map { it.lineId })
        // Once dismissed, the page keeps its times and says so, rather than claim a clean line.
        dismissed.value = setOf(DismissedAlert.ofLineStatus(planned.statuses.getValue("jubilee")))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_alert_dismissed)).assertExists()
    }

    @Test
    fun a_no_trains_leg_whose_alert_was_dismissed_still_opens() {
        // No Jubilee trains at Canada Water, and its Minor Delays dismissed: the leg still opens its
        // line's stops, saying the alert was dismissed rather than dropping the page.
        val dismissed = setOf(DismissedAlert.ofLineStatus(planned.statuses.getValue("jubilee")))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(live = planned.live - canadaWaterTube.first), now = now,
                    access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                    dismissed = dismissed, onDismissAlert = {},
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_alert_dismissed)).assertExists()
    }

    @Test
    fun a_route_getting_off_at_a_closed_station_goes_last_and_says_why() {
        // The Elizabeth line's Canary Wharf station closed: the route getting off there ranks below
        // the others, still timed, its ride's ⚠ naming the stop; opened, the closure card is where it
        // gets off, as the list's closure card is.
        // Every line checked as running, so only the closure moves the route.
        val running = planned.copy(statuses = planned.statuses + ("elizabeth" to LineStatus("elizabeth", LineStatus.GOOD_SERVICE, "Good Service")))
        fun arrivals() = composeRule.onAllNodes(hasTestTag("tripArrival"), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        }
        val state = androidx.compose.runtime.mutableStateOf(running)
        showWith(state)
        assertEquals(listOf("27 min · ~08:29", "28 min · ~08:30", "38 min · ~08:40"), arrivals())
        state.value = running.copy(closures = running.closures + (canaryWharfXr.first to listOf(StopDisruption("Canary Wharf Station: Station closed due to a power failure"))))
        composeRule.waitForIdle()
        assertEquals(listOf("27 min · ~08:29", "38 min · ~08:40", "28 min · ~08:30"), arrivals())
        composeRule.onNodeWithContentDescription("Canary Wharf: Station closed due to a power failure", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        composeRule.onNodeWithText("Station closed due to a power failure", substring = true).assertExists()
        captureSnapshot("trip-route-closure.png")
    }

    @Test
    fun a_cards_ride_notices_are_worked_out_with_its_frame_never_in_composition() {
        // Each ride's stop notices come with the card's frame ([TripCardView.rideClosures]), worked
        // out on the page's worker: held shut, no card and no ⚠; released, the card comes with its ⚠.
        val running = planned.copy(
            statuses = planned.statuses + ("elizabeth" to LineStatus("elizabeth", LineStatus.GOOD_SERVICE, "Good Service")),
            closures = planned.closures + (canaryWharfXr.first to listOf(StopDisruption("Canary Wharf Station: Station closed due to a power failure"))),
        )
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        threads.execute { gate.await() }
        val notice = "Canary Wharf: Station closed due to a power failure"
        try {
            show(running, worker = worker)
            composeRule.onAllNodesWithContentDescription(notice, useUnmergedTree = true).assertCountEquals(0)
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("28 min · ~08:30").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(notice, useUnmergedTree = true).assertExists()
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    // A note other than "checking" holds a moment before it's drawn ([NOTE_SETTLE_MILLIS]).
    private fun settleNote() {
        composeRule.mainClock.advanceTimeBy(NOTE_SETTLE_MILLIS)
        composeRule.waitForIdle()
    }

    @Test
    fun a_route_list_says_it_couldnt_check_only_for_a_stop_a_route_shown_uses() {
        // Every line checked as running, the routes' own and the others at their stops.
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val unknown = composeRule.activity.getString(R.string.trip_disruptions_unknown)
        // The failed check was for a stop no route shown uses (one of a hidden mode's, say).
        val state = androidx.compose.runtime.mutableStateOf(running.copy(closuresFailed = setOf("940GZZLUNONE")))
        showWith(state)
        composeRule.onNodeWithText(unknown, substring = true).assertDoesNotExist()
        // One a shown route gets off at: it can't vouch for that route, and names the stop.
        state.value = running.copy(closuresFailed = setOf(highbury.first))
        settleNote()
        inRow("Unknown:").assertExists()
        inRow(highbury.second).assertExists()
    }

    // The disruptions row over the routes when it says [text]: the row is heard whole, every line and
    // stop named, so it's matched by what it says rather than by what fits on its one line.
    private fun inRow(text: String) =
        composeRule.onNode(hasTestTag("tripDisruptions") and hasContentDescription(text, substring = true))

    @Test
    fun a_route_list_says_it_couldnt_check_only_for_a_line_a_route_shown_rides() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val unknown = composeRule.activity.getString(R.string.trip_disruptions_unknown)
        // The failed or unanswered line is one no route shown rides (a hidden mode's, say).
        val state = androidx.compose.runtime.mutableStateOf(
            running.copy(statusFailed = true, statusFailedLines = setOf("hidden"), statusUnknown = setOf("hidden")),
        )
        showWith(state)
        composeRule.onNodeWithText(unknown, substring = true).assertDoesNotExist()
        // One a shown route rides: it can't vouch for that route, and names the line.
        // "Unknown:" and the line's pill (maintainer, 2026-10-04).
        state.value = running.copy(statusFailed = true, statusFailedLines = setOf("windrush"))
        settleNote()
        inRow("Unknown:").assertExists()
        inRow("Windrush").assertExists()
        state.value = running.copy(statusUnknown = setOf("windrush"))
        settleNote()
        inRow("Windrush").assertExists()
        // A line and a stop together: the line's pill, then the stop.
        state.value = running.copy(statusUnknown = setOf("windrush"), closuresFailed = setOf(highbury.first))
        settleNote()
        inRow("Windrush").assertExists()
        inRow(highbury.second).assertExists()
    }

    @Test
    fun a_leg_page_vouches_once_its_line_and_its_stop_are_checked() {
        // Windrush checked as running and Highbury & Islington checked open: nothing to report.
        show(planned.copy(closuresAt = mapOf(highbury.first to now)))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertExists()
    }

    @Test
    fun a_leg_page_vouches_for_its_line_when_only_another_lines_check_failed() {
        // The latest check failed for another line's request, but answered Windrush.
        show(
            planned.copy(
                closuresAt = mapOf(highbury.first to now),
                statusFailed = true,
                statusFailedLines = setOf("jubilee"),
            ),
        )
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertExists()
    }

    @Test
    fun a_leg_page_at_a_closed_stop_never_calls_it_clean() {
        // Windrush checked as running, but Highbury & Islington checked and closed.
        show(planned.copy(closures = planned.closures + (highbury.first to listOf(StopDisruption("Station closed due to a power failure")))))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertDoesNotExist()
    }

    @Test
    fun a_leg_page_whose_checks_are_as_old_as_a_stale_countdown_claims_nothing() {
        // Checked running and open, but longer ago than a countdown is trusted: a trip shown again
        // holds them while its re-check is out, and ranks by them, but its page doesn't vouch.
        val old = now.minus(Duration.ofMinutes(6))
        val checked = planned.copy(closuresAt = mapOf(highbury.first to now))
        val state = androidx.compose.runtime.mutableStateOf(checked.copy(closuresAt = mapOf(highbury.first to old)))
        showWith(state)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        val unknown = composeRule.activity.getString(R.string.disruptions_unknown)
        composeRule.onNodeWithText(unknown).assertExists()
        // The same for an old status check.
        state.value = checked.copy(statusesAt = planned.statusesAt + ("windrush" to old))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(unknown).assertExists()
        // Both checked again: it vouches.
        state.value = checked
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_no_disruption)).assertExists()
    }

    @Test
    fun a_leg_page_whose_stop_is_not_yet_checked_claims_nothing() {
        show(planned.copy(closures = planned.closures - highbury.first, closuresUnknown = setOf(highbury.first)))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disruptions_unknown)).assertExists()
    }

    @Test
    fun a_leg_page_whose_stop_check_just_failed_claims_nothing() {
        // Highbury & Islington checked open before, but its latest check failed.
        show(planned.copy(closuresFailed = setOf(highbury.first)))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Crystal Palace").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disruptions_unknown)).assertExists()
    }

    @Test
    fun a_leg_opened_after_a_failed_status_check_claims_nothing() {
        // The last statuses are held, but the latest check failed: the line's page can't vouch for it.
        show(planned.copy(statusFailed = true, statusFailedLines = setOf("mildmay", "jubilee", "windrush")))
        composeRule.onNodeWithText("27 min · ~08:29", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.disruptions_unknown)).assertExists()
    }

    @Test
    fun an_open_ride_shows_every_line_between_its_stops_even_with_no_train() {
        // A made-up second line serving Highbury & Islington and Canada Water as Windrush does, in the
        // plan by a route of its own and with no train due: its own row on the open ride, which opens
        // its line's page as the Planner's line's row does.
        val testLine = LineSequence(
            routes = listOf(LineRoute("Highbury ↔ Canada Water", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW"))),
            stopNames = sequences.getValue("windrush").stopNames,
        )
        val withTestLine = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                if (lineId == "testline") testLine else sequences.getValue(lineId)
        }
        val byTestLine = TripRoute(listOf(viaCanadaWater.legs[0].copy(lineId = "testline", lineName = "Test Line"), viaCanadaWater.legs[1]))
        show(planned.copy(routes = listOf(viaCanadaWater, byTestLine)), routeStops = RouteStopsRepository(withTestLine))
        // The card's pill names both lines as one ("Windrush or Test Line").
        composeRule.onAllNodes(
            hasClickAction() and hasContentDescription("Test Line", substring = true) and hasContentDescription("Jubilee") and !hasTestTag("tripDisruptions"),
        ).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Test Line"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
    }

    @Test
    fun another_line_not_yet_checked_shows_its_row_and_opens_its_page() {
        // As above, but the made-up line has a train due and no status known: the open ride shows no
        // countdown for it, only its row, and that row still opens its line's page.
        val testLine = LineSequence(
            routes = listOf(LineRoute("Highbury ↔ Canada Water", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW"))),
            stopNames = sequences.getValue("windrush").stopNames,
        )
        val withTestLine = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                if (lineId == "testline") testLine else sequences.getValue(lineId)
        }
        val byTestLine = TripRoute(listOf(viaCanadaWater.legs[0].copy(lineId = "testline", lineName = "Test Line"), viaCanadaWater.legs[1]))
        val atHighbury = live.getValue(highbury.first)
        val withTrain = live + (highbury.first to atHighbury.copy(departures = atHighbury.departures + train("testline", "Test Line", "overground", "Canada Water", 4, "Platform 2")))
        show(planned.copy(routes = listOf(viaCanadaWater, byTestLine), live = withTrain), routeStops = RouteStopsRepository(withTestLine))
        composeRule.onAllNodes(
            hasClickAction() and hasContentDescription("Test Line", substring = true) and hasContentDescription("Jubilee") and !hasTestTag("tripDisruptions"),
        ).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Test Line"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
    }

    @Test
    fun a_leg_with_no_trains_opens_to_its_stops() {
        // No live trains at Canada Water: the Jubilee leg's row opens its line's stops all the same.
        show(planned.copy(live = planned.live - canadaWaterTube.first))
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        composeRule.onNodeWithText("Canary Wharf").assertExists()
    }

    @Test
    fun a_dismissed_line_alert_leaves_the_trip_cards_as_it_leaves_the_list() {
        val severe = LineStatus("windrush", 6, "Severe Delays")
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(statuses = planned.statuses + ("windrush" to severe)),
                    now = now, access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source),
                    onBack = {}, onRetry = {}, dismissed = setOf(DismissedAlert.ofLineStatus(severe)),
                )
            }
        }
        composeRule.waitForIdle()
        // Dismissed: neither the pill nor the line row warns, as the list's rows don't.
        composeRule.onAllNodesWithContentDescription("Severe Delays").assertCountEquals(0)
        // The trains still show.
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().assertExists()
    }

    @Test
    fun a_leg_with_no_trains_offers_hide_as_the_list_does() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(live = planned.live - canadaWaterTube.first),
                    now = now, access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source),
                    onBack = {}, onRetry = {}, onHideMode = {},
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        // The Jubilee leg, with no trains to show, still carries the list row's long-press menu.
        val more = composeRule.activity.getString(R.string.more_actions)
        composeRule.onNode(
            hasAnyDescendant(hasContentDescription("Jubilee")) and
                SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        ).assertExists()
    }

    @Test
    fun a_hide_from_a_leg_offers_undo_as_the_list_does() {
        var hidden: String? = null
        var unhidden: String? = null
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned.copy(live = planned.live - canadaWaterTube.first),
                    now = now, access = Duration.ofMinutes(2), routeStops = RouteStopsRepository(source),
                    onBack = {}, onRetry = {}, onHideMode = { hidden = it }, onUnhideMode = { unhidden = it },
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        val more = composeRule.activity.getString(R.string.more_actions)
        composeRule.onNode(
            hasAnyDescendant(hasContentDescription("Jubilee")) and
                SemanticsMatcher("long-presses to its menu") { it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == more },
        ).performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithText("Hide all Tube & DLR services").performClick()
        composeRule.waitForIdle()
        assertEquals("tube", hidden)
        composeRule.onNodeWithText("Tube & DLR hidden").assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals("tube", unhidden)
    }

    @Test
    fun a_hide_that_did_not_save_is_said_on_the_trip() {
        var acknowledged = 0
        // Inline, as a_walking_speed_that_did_not_save_is_said_once: the note mustn't time out while
        // the clock idling waits on a real worker.
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides inline) {
                    TripScreen(
                        title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                        hiddenModesWriteFailed = true, onHiddenModesWriteFailureShown = { acknowledged++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.hidden_modes_write_failed)).assertExists()
        assertEquals(1, acknowledged)
    }

    @Test
    fun a_no_trains_leg_whose_route_is_unknown_logs_why() {
        val warnings = mutableListOf<String>()
        // TfL knows no route for the line: the page says "unavailable", and the log says why, once.
        val unknown = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                    if (lineId == "jubilee") throw TflException.NotFound(null) else sequences.getValue(lineId)
            },
            warn = { warnings += it },
        )
        show(planned.copy(live = planned.live - canadaWaterTube.first), routeStops = unknown)
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf("route stops unavailable for line jubilee at stop ${canadaWaterTube.first}: line not known to TfL"),
            warnings.filter { it.startsWith("route stops unavailable") },
        )
    }

    @Test
    fun a_leg_whose_refresh_failed_opens_as_stale() {
        // The Jubilee stop's last refresh failed: its held arrivals open with the stale caveat.
        val failed = planned.live + (canadaWaterTube.first to planned.live.getValue(canadaWaterTube.first).copy(failed = true))
        show(planned.copy(live = failed))
        composeRule.onNodeWithText("27 min · ~08:29", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stratford").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.route_detail_status_stale)).assertExists()
    }

    @Test
    fun a_no_trains_page_closes_once_trains_come() {
        val trip = androidx.compose.runtime.mutableStateOf(planned.copy(live = planned.live - canadaWaterTube.first))
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = trip.value, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {},
                )
            }
        }
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("Windrush") and hasContentDescription("Jubilee"))
            .onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Jubilee"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(0)
        // Its trains come: the page opened from the no-trains row closes, back to the route.
        trip.value = planned
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("tripLegs")).assertCountEquals(1)
    }

    @Test
    fun a_failed_replan_shows_over_an_open_route() {
        show(planned.copy(planError = DeparturesUiState.Error.Kind.OFFLINE))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't plan the trip: You're offline").assertIsDisplayed()
    }

    @Test
    fun a_first_ride_with_no_train_yet_says_its_times_are_loading() {
        show(planned.copy(routes = listOf(viaStratford, viaCanadaWater), live = emptyMap()))
        // The boarding stop's arrivals aren't in yet: the first ride's times say so rather than show
        // a dash, beside the stop it gets off at.
        composeRule.onAllNodes(hasText("Loading") and hasAnyAncestor(hasTestTag("rideStops")), useUnmergedTree = true)
            .assertCountEquals(2)
        composeRule.onAllNodesWithText("Canada Water", useUnmergedTree = true).onFirst().assertExists()
    }

    // Either bus from one stop to Canada Water, then the Jubilee (one card, a cut 47/188 pill), beside
    // the route via Whitechapel; shown on [worker] if given.
    private fun showSharedFirstLeg(
        worker: CoroutineDispatcher? = null,
        held: MutableState<TripViewModel.State>? = null,
    ) {
        val busStop = "490000001A" to "Surrey Docks"
        val busStation = "490000002B" to "Canada Water Bus Station"
        fun bus(line: String, departs: Long) = TripRoute(
            listOf(
                leg("bus", line, line, busStop, busStation, departs, departs + 6, 3, change = 4),
                leg("tube", "jubilee", "Jubilee", canadaWaterTube, canaryWharf, departs + 11, departs + 13, 1),
            ),
        )
        val busRoute = { line: String -> LineRoute(line, listOf(busStop.first, "stop0", "stop1", busStation.first)) }
        val busSequences = sequences + mapOf(
            "47" to LineSequence(routes = listOf(busRoute("47")), stopNames = mapOf(busStop.first to "Surrey Docks", busStation.first to "Canada Water")),
            "188" to LineSequence(routes = listOf(busRoute("188")), stopNames = mapOf(busStop.first to "Surrey Docks", busStation.first to "Canada Water")),
        )
        val busLive = live + (
            busStop.first to TripViewModel.StopLive(
                listOf(
                    train("47", "47", "bus", "Catford", 4, "Stop A"),
                    train("188", "188", "bus", "North Greenwich", 6, "Stop A"),
                    train("47", "47", "bus", "Catford", 12, "Stop A"),
                    train("188", "188", "bus", "North Greenwich", 15, "Stop A"),
                ),
                now,
            )
            )
        val state = planned.copy(
            routes = listOf(bus("47", 4), bus("188", 6), viaWhitechapel),
            live = busLive,
            statuses = planned.statuses + listOf("47", "188").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") },
        )
        show(
            state,
            routeStops = RouteStopsRepository(
                object : RouteSequenceSource {
                    override suspend fun routeSequence(lineId: String, direction: String): LineSequence = busSequences.getValue(lineId)
                },
            ),
            worker = worker,
            held = held?.also { it.value = state },
        )
    }

    /** Each `rideStopName`'s left edge, in drawing order: two cards, each a walk row and two rides. */
    private fun stopLefts() =
        composeRule.onAllNodes(hasTestTag("rideStopName"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left }

    private fun List<Float>.inOneColumn() = max() - min() <= 1f

    @Test
    fun trip_shared_first_leg() {
        // One card, a cut 47/188 pill, and both buses' times together on the first ride's row
        // (maintainer, 2026-09-27).
        showSharedFirstLeg()
        composeRule.onNodeWithContentDescription("47 or 188").assertIsDisplayed()
        // The soonest three the rider can reach (a 2 min walk), whichever bus: 47, 188, 47.
        composeRule.onNodeWithText("4 · 6 · 12 min", useUnmergedTree = true).assertExists()
        // A screen reader still hears where each goes.
        composeRule.onAllNodesWithContentDescription("Catford", substring = true, useUnmergedTree = true).onFirst().assertExists()
        composeRule.onAllNodesWithContentDescription("North Greenwich", substring = true, useUnmergedTree = true).onFirst().assertExists()
        // Every stop name on a card starts in one place, the cut pill's row and the walk too, though
        // the cut pill is wider than a lone one; each card on its own pills, not the list's widest
        // (maintainer, 2026-10-04).
        composeRule.waitUntil(timeoutMillis = 5_000) { stopLefts().size == 6 }
        composeRule.waitUntil(timeoutMillis = 5_000) { stopLefts().take(3).inOneColumn() }
        assertTrue("the lone pills' card lines up: ${stopLefts()}", stopLefts().drop(3).inOneColumn())
        // The cut pill widens only its own card's column.
        assertTrue("the cards share a column: ${stopLefts()}", stopLefts()[0] > stopLefts()[3] + 1f)
        captureSnapshot("trip-shared-first-leg.png")
    }

    @Test
    fun the_pill_column_is_measured_on_the_page_worker_never_in_composition() {
        // A worker held shut: until it runs, the page has no frame ([tripFrame]) and draws no rows, so
        // composition measures no pill. Released, the rows come with every stop in one column.
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        threads.execute { gate.await() }
        try {
            showSharedFirstLeg(worker)
            assertEquals(0, stopLefts().size)
            // Released, the worker works out the frame and measures the pills: the card's stops in one column.
            gate.countDown()
            composeRule.waitUntil(timeoutMillis = 5_000) { stopLefts().size == 6 && stopLefts().take(3).inOneColumn() }
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun a_cards_pill_column_is_in_before_its_rows_show() {
        // Each card's pill column is measured behind "Checking routes…", so the list first shows with
        // every card's stops in one column, never at a lone pill's width and then shifting sideways as
        // the cut pill is measured (Codex, #543). The worker steps one run at a time, and the list is
        // looked at after each.
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = java.util.concurrent.Executor { queued.add(it) }.asCoroutineDispatcher()
        // Frames by hand: an idling clock would run out the reveal's cap ([REVEAL_CAP_MILLIS]) while the
        // worker waits on the test, and show the list for that instead.
        composeRule.mainClock.autoAdvance = false
        try {
            showSharedFirstLeg(worker)
            while (stopLefts().size < 6) {
                assertTrue("the list shows within the reveal's cap", composeRule.mainClock.currentTime < REVEAL_CAP_MILLIS)
                // One run, to its end, if one is waiting; then a frame, then a look at the list.
                queued.poll()?.let { threads.submit(it).get() }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            assertTrue("the cut pill's card lines up as it first shows: ${stopLefts()}", stopLefts().take(3).inOneColumn())
            assertTrue("each card on its own pills: ${stopLefts()}", stopLefts()[0] > stopLefts()[3] + 1f)
        } finally {
            generateSequence { queued.poll() }.forEach { threads.execute(it) }
            threads.shutdown()
        }
    }

    @Test
    fun a_cards_stops_hold_still_while_the_page_loads() {
        // The page's loads land one after another (a line's status, its route, a stop's trains), each
        // a new state. While the worker answers, the stops keep their columns: the rows shuffled
        // sideways for the first few seconds when each landing dropped the column's width until the
        // worker measured it again (maintainer, 2026-10-04).
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val held = mutableStateOf(planned)
        try {
            showSharedFirstLeg(worker, held)
            composeRule.waitUntil(timeoutMillis = 5_000) { stopLefts().size == 6 && stopLefts().take(3).inOneColumn() }
            val settled = stopLefts()

            // A status checked again lands with the worker held.
            threads.execute { gate.await() }
            held.value = held.value.copy(statusesAt = held.value.statusesAt.mapValues { now.plusSeconds(30) })
            composeRule.waitForIdle()
            assertEquals(settled, stopLefts())

            // Released, the worker works the card out again; nothing it changes moves a stop.
            gate.countDown()
            composeRule.waitForIdle()
            threads.submit {}.get()
            composeRule.waitForIdle()
            assertEquals(settled, stopLefts())
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun the_disruption_note_holds_still_while_the_checks_land() {
        // The page's checks land one after another, and a moment between two can read as checked, or as
        // couldn't check: the note held its word through those, rather than blinking on and off with the
        // cards jumping under it (maintainer, 2026-10-04).
        // Every line checked as running, the routes' own and the others at their stops.
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val checking = running.copy(refreshing = true, statuses = running.statuses - "jubilee", statusUnknown = setOf("jubilee"))
        val between = checking.copy(refreshing = false)
        val held = mutableStateOf(checking)
        // The worker inline, so the row's lines and stops are never behind the cards here: what's held
        // is the check's word alone.
        show(checking, held = held, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Checking…").assertExists()
        composeRule.mainClock.autoAdvance = false

        // A moment between two checks: still checking, never "couldn't check".
        held.value = between
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        inRow("Checking…").assertExists()
        inRow("Unknown").assertDoesNotExist()
        held.value = checking
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000)
        inRow("Checking…").assertExists()
        inRow("Unknown").assertDoesNotExist()

        // Every line checked: "None" once that holds, not the moment it reads so, in the same row.
        val rowTop = composeRule.onNodeWithTag("tripDisruptions").fetchSemanticsNode().boundsInRoot.top
        held.value = running
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        inRow("Checking…").assertExists()
        composeRule.mainClock.advanceTimeBy(2_000)
        inRow("None").assertExists()
        inRow("Checking…").assertDoesNotExist()
        inRow("Unknown").assertDoesNotExist()
        assertEquals(rowTop, composeRule.onNodeWithTag("tripDisruptions").fetchSemanticsNode().boundsInRoot.top)

        // A check that starts again says so at once.
        held.value = checking
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        inRow("Checking…").assertExists()

        // And one that couldn't check says so once that holds.
        held.value = between
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000)
        inRow("Unknown").assertIsDisplayed()
        inRow("Checking…").assertDoesNotExist()
    }

    @Test
    fun the_disruptions_row_never_holds_none_over_a_disruption_the_worker_has_not_caught_up_with() {
        // Every line checked as running, the routes' own and the others at their stops.
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val threads = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val worker = threads.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        val held = mutableStateOf(running)
        try {
            show(running, worker = worker, held = held)
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodes(hasTestTag("tripDisruptions") and hasContentDescription("None", substring = true)).fetchSemanticsNodes().isNotEmpty() }

            // The Jubilee turns disrupted while the worker is held: the row's "None" is from the cards
            // before. It may stand a moment, as a card's last times do, but then says it's checking.
            threads.execute { gate.await() }
            composeRule.mainClock.autoAdvance = false
            held.value = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(2_000)
            inRow("Checking…").assertExists()
            inRow("None").assertDoesNotExist()

            // Released, the worker catches up: the Jubilee's pill, and no "None".
            gate.countDown()
            composeRule.mainClock.autoAdvance = true
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodes(hasTestTag("tripDisruptions") and hasContentDescription("Checking…", substring = true)).fetchSemanticsNodes().isEmpty() }
            inRow("None").assertDoesNotExist()
            inRow("Jubilee").assertExists()

            // Back to running with the worker held again: the Jubilee's pill is for the cards before, so
            // once the row says it's checking, the pill goes with it.
            val gate2 = CountDownLatch(1)
            try {
                threads.execute { gate2.await() }
                composeRule.mainClock.autoAdvance = false
                held.value = running
                composeRule.waitForIdle()
                composeRule.mainClock.advanceTimeBy(2_000)
                inRow("Checking…").assertExists()
                inRow("Jubilee").assertDoesNotExist()
            } finally {
                gate2.countDown()
            }
        } finally {
            // Opened whatever happened, so a failed check can't leave the worker's thread waiting.
            gate.countDown()
            worker.close()
        }
    }

    @Test
    fun the_disruptions_row_holds_a_line_clearing_before_it_says_none() {
        // Every line checked as running, the routes' own and the others at their stops.
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        val held = mutableStateOf(disrupted)
        // The worker inline, so the row's lines are always the current cards': what's held is the change.
        show(disrupted, held = held, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Jubilee").assertExists()

        // The Jubilee clears: its pill stays a moment, and "None" waits out the hold (Codex, #543).
        composeRule.mainClock.autoAdvance = false
        held.value = running
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        inRow("Jubilee").assertExists()
        inRow("None").assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(2_000)
        inRow("Jubilee").assertDoesNotExist()
        inRow("None").assertExists()
    }

    @Test
    fun a_trip_list_waits_for_its_routes_then_holds_still() {
        // Every line's route answers at once but the Jubilee's, which waits on the test; fetched and
        // merged inline, with the page's worker inline too, so the list follows the answer, not a thread.
        val jubilee = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gated = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                if (lineId == "jubilee") jubilee.await()
                return sequences.getValue(lineId)
            }
        }
        val inline = kotlinx.coroutines.Dispatchers.Unconfined
        val held = mutableStateOf(planned)
        composeRule.mainClock.autoAdvance = false
        show(planned, routeStops = RouteStopsRepository(gated, io = inline, compute = inline), worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher(), held = held)
        composeRule.mainClock.advanceTimeBy(1_000)
        // A route still loading: the list waits, and says why, rather than settle under the rider
        // (maintainer, 2026-10-04: never move what they're reading or about to tap).
        composeRule.onNodeWithText("Checking routes…").assertExists()
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)

        // The route lands: the list appears, settled.
        jubilee.complete(Unit)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        composeRule.onNodeWithText("Checking routes…").assertDoesNotExist()

        // A refresh after that keeps the list where it is: it never hides again.
        held.value = planned.copy(refreshing = true)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
    }

    @Test
    fun a_trip_list_says_once_how_long_it_took_to_show_and_what_it_waited_for() {
        // A slow trip page says where the time went: one debug-log line when the list first shows,
        // never again on a refresh, and naming no stop or line.
        val lines = mutableListOf<String>()
        val held = mutableStateOf(planned)
        show(planned, held = held, onListShown = { lines += it })
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("tripRoutes").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines.single(), Regex("^trip list shown after \\d+ ms \\(routes in at \\d+ ms\\), last waited for .+$").matches(lines.single()))
        assertTrue(lines.single(), planned.routes.orEmpty().flatMap { it.legs }.none { it.fromId in lines.single() || it.lineId.isNotBlank() && " ${it.lineId} " in lines.single() })
        // A refresh landing: the list stays shown, and says nothing more.
        held.value = planned.copy(refreshing = true)
        composeRule.waitForIdle()
        held.value = planned.copy(refreshing = false)
        composeRule.waitForIdle()
        assertEquals(1, lines.size)
    }

    @Test
    fun a_trip_list_names_a_line_it_couldnt_check_on_its_disruptions_row() {
        // The Jubilee's route fails once the test says so: the routes can't all be checked, and the
        // disruptions row says so once it has held ([NOTE_SETTLE_MILLIS]), never a banner over the list.
        val jubilee = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gated = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                if (lineId == "jubilee") {
                    jubilee.await()
                    throw app.stopdash.domain.TflException.Offline(null)
                }
                return sequences.getValue(lineId)
            }
        }
        val inline = kotlinx.coroutines.Dispatchers.Unconfined
        composeRule.mainClock.autoAdvance = false
        show(planned, routeStops = RouteStopsRepository(gated, io = inline, compute = inline), worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        composeRule.mainClock.advanceTimeBy(1_000)
        jubilee.complete(Unit)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_500)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        inRow("Unknown:").assertExists()
        composeRule.onNodeWithText("Some routes couldn't be checked").assertDoesNotExist()
    }

    @Test
    fun a_plan_starting_afresh_never_shows_the_last_plans_couldnt_check() {
        // The Jubilee's route fails: the routes can't all be checked, and the disruptions row says so.
        val failing = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                if (lineId == "jubilee") throw app.stopdash.domain.TflException.Offline(null)
                return sequences.getValue(lineId)
            }
        }
        val inline = kotlinx.coroutines.Dispatchers.Unconfined
        val held = mutableStateOf(planned)
        composeRule.mainClock.autoAdvance = false
        show(planned, routeStops = RouteStopsRepository(failing, io = inline, compute = inline), worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher(), held = held)
        composeRule.mainClock.advanceTimeBy(3_000)
        inRow("Unknown:").assertExists()
        // A new plan: no cards while it's planned. The last plan's failure never stands over its
        // placeholder for the row's hold (Codex, #543).
        held.value = planned.copy(routes = null, planning = true)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        inRow("Unknown:").assertDoesNotExist()
    }

    @Test
    fun a_line_avoided_takes_its_couldnt_check_with_it() {
        // The Jubilee's route fails: the routes can't all be checked, and the disruptions row says so.
        val failing = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                if (lineId == "jubilee") throw app.stopdash.domain.TflException.Offline(null)
                return sequences.getValue(lineId)
            }
        }
        val inline = kotlinx.coroutines.Dispatchers.Unconfined
        val avoided = mutableStateOf(emptySet<String>())
        val state = mutableStateOf(planned)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()) {
                    TripScreen(
                        title = "To Canary Wharf", state = state.value, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(failing, io = inline, compute = inline), onBack = {}, onRetry = {},
                        avoidedLines = avoided.value,
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(3_000)
        inRow("Unknown:").assertExists()
        // The Jubilee avoided, and the trip planned without it: the route left was all checked, and the
        // row's "Unknown:" goes at once, never standing over it for its hold (Codex, #543).
        avoided.value = setOf("jubilee")
        state.value = planned.copy(routes = listOf(viaWhitechapel))
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        inRow("Unknown:").assertDoesNotExist()
    }

    @Test
    fun a_trip_list_waits_for_every_route_even_once_one_has_failed() {
        // The Jubilee's route fails at once; the Windrush's waits on the test. One failure already says
        // "couldn't be checked", but the Windrush's route can still change the cards (Codex, #543).
        val windrush = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gated = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                if (lineId == "jubilee") throw app.stopdash.domain.TflException.Offline(null)
                if (lineId == "windrush") windrush.await()
                return sequences.getValue(lineId)
            }
        }
        val inline = kotlinx.coroutines.Dispatchers.Unconfined
        composeRule.mainClock.autoAdvance = false
        show(planned, routeStops = RouteStopsRepository(gated, io = inline, compute = inline), worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
        windrush.complete(Unit)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
    }

    @Test
    fun a_trip_list_waits_again_for_a_plan_starting_afresh() {
        val held = mutableStateOf(planned)
        composeRule.mainClock.autoAdvance = false
        show(planned, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher(), held = held)
        composeRule.mainClock.advanceTimeBy(REVEAL_CAP_MILLIS + 1_000)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        // No cards, as a process recreated starts (its plans are memory-only), then a plan landing:
        // its cards wait to settle, not show as they land (Codex, #543).
        held.value = planned.copy(routes = null, planning = true)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        held.value = planned.copy(planning = true)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
        composeRule.onNodeWithText("Checking routes…").assertExists()
    }

    @Test
    fun the_disruptions_row_is_as_tall_saying_none_as_with_a_pill() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        val held = mutableStateOf(disrupted)
        show(disrupted, held = held, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Jubilee").assertExists()
        val withPill = composeRule.onNodeWithTag("tripDisruptions").fetchSemanticsNode().size.height
        held.value = running
        composeRule.waitForIdle()
        settleNote()
        inRow("None").assertExists()
        // A pill coming or going never moves the cards under the row (Codex, #543).
        assertEquals(withPill, composeRule.onNodeWithTag("tripDisruptions").fetchSemanticsNode().size.height)
    }

    @Test
    @Config(qualifiers = "en-rGB-w240dp-h914dp-420dpi")
    fun the_disruptions_row_stays_one_line_with_more_than_fits() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val one = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        // Every line disrupted, on a narrow screen: more pills than one line holds.
        val every = running.copy(statuses = lines.associateWith { LineStatus(it, 9, "Minor Delays") })
        val held = mutableStateOf(one)
        show(one, held = held, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Jubilee").assertExists()
        val withPill = composeRule.onNodeWithTag("tripDisruptions").fetchSemanticsNode().size.height
        held.value = every
        composeRule.waitForIdle()
        settleNote()
        // One line high still (maintainer, 2026-10-04): what doesn't fit is counted, never wrapped.
        assertEquals(withPill, composeRule.onNodeWithTag("tripDisruptions").fetchSemanticsNode().size.height)
        // A "+N" shows (the row measures others it doesn't place, to choose what fits).
        assertTrue(composeRule.onAllNodes(hasText("+", substring = true) and hasAnyAncestor(hasTestTag("tripDisruptions")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // Heard whole: every line named, not only the pills that fit.
        inRow("Jubilee").assertExists()
        inRow("Windrush").assertExists()
    }

    @Test
    fun the_disruptions_row_opens_every_line_with_its_status() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        show(disrupted, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Jubilee").assertExists()
        composeRule.onNodeWithTag("tripDisruptions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Line status").assertExists()
        // The disrupted line heads the page, the rest under it with a good service.
        composeRule.onNode(hasText("Minor Delays") and hasAnyAncestor(hasTestTag("tripLines")), useUnmergedTree = true).assertExists()
        composeRule.onAllNodes(hasText("Good service") and hasAnyAncestor(hasTestTag("tripLines")), useUnmergedTree = true)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun a_walking_trip_s_lines_page_says_it_has_no_lines_not_checking() {
        composeRule.setContent { StopDashTheme { Surface { TripLinesContent(TripRow(checking = false)) } } }
        composeRule.onNodeWithText("No lines on this trip").assertExists()
        composeRule.onAllNodes(hasText("Checking…")).assertCountEquals(0)
    }

    @Test
    fun a_line_turning_disrupted_never_moves_the_lines_under_it() {
        fun line(id: String, name: String, status: LineStatus) =
            TripLine(leg("tube", id, name, "A" to "King's Cross", "B" to "Euston", 0, 10, 2), status)
        val good = TripRow(
            checking = false,
            every = listOf(line("jubilee", "Jubilee", LineStatus("jubilee", LineStatus.GOOD_SERVICE, "Good Service")), line("victoria", "Victoria", LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
        )
        var row by mutableStateOf(good)
        composeRule.setContent { StopDashTheme { Surface { TripLinesContent(row) } } }
        val below = { composeRule.onNode(hasContentDescription("Victoria"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        val before = below()
        // The Jubilee's check lands disrupted, with TfL's reason: it opens only on a tap (Codex, #559).
        row = TripRow(checking = false, every = listOf(line("jubilee", "Jubilee", LineStatus("jubilee", 6, "Severe Delays", fullText = "Jubilee line: Severe delays while we fix a signal failure.")), good.every[1]))
        composeRule.waitForIdle()
        assertEquals(before, below())
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertDoesNotExist()
        // Only on its own page, a tap away.
        composeRule.onAllNodes(hasClickAction() and hasText("Severe Delays")).assertCountEquals(0)
    }

    @Test
    fun a_disrupted_line_opens_its_reason_on_a_page_and_back_returns_to_the_lines() {
        fun line(id: String, name: String, status: LineStatus) =
            TripLine(leg("tube", id, name, "A" to "King's Cross", "B" to "Euston", 0, 10, 2), status)
        val row = TripRow(
            checking = false,
            every = listOf(
                line("jubilee", "Jubilee", LineStatus("jubilee", 6, "Severe Delays", fullText = "Jubilee line: Severe delays while we fix a signal failure.")),
                line("victoria", "Victoria", LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")),
            ),
        )
        composeRule.setContent { StopDashTheme { TripLinesPage(row, onClose = {}) } }
        composeRule.onNodeWithText("Line status").assertExists()
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertDoesNotExist()
        composeRule.onNodeWithText("Severe Delays").performClick()
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertExists()
        composeRule.onAllNodes(hasContentDescription("Victoria"), useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Line status").assertExists()
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertDoesNotExist()
    }

    @Test
    fun a_restored_lines_page_shows_nothing_until_its_order_is_in() {
        val row = TripRow(checking = false, stops = "Euston", every = emptyList())
        composeRule.setContent { StopDashTheme { Surface { TripLinesContent(row, lines = null) } } }
        // The notices under the lines wait with them (Codex, #559).
        composeRule.onNodeWithText("Stop notices: Euston").assertDoesNotExist()
    }

    @Test
    fun a_restored_lines_page_shows_its_saved_lines_at_once() {
        val row = TripRow(checking = false, stops = "Euston", every = emptyList())
        val pills = listOf("jubilee", "Jubilee", "tube", "victoria", "Victoria", "tube")
        composeRule.setContent { StopDashTheme { Surface { TripLinesContent(row, lines = null, pending = pills) } } }
        // Never a blank page (Codex, #559): each saved line in its saved place, claiming no status.
        composeRule.onAllNodes(hasContentDescription("Jubilee"), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("Checking…"), useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Good service"), useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithText("Stop notices: Euston").assertDoesNotExist()
    }

    @Test
    fun the_lines_page_carries_the_app_menu() {
        val menu = AppMenuActions(updateAvailable = false, onOpenAppListing = {}, onSendBugReport = {}, onOpenLicenses = {})
        composeRule.setContent {
            StopDashTheme { CompositionLocalProvider(LocalAppMenu provides menu) { TripLinesPage(TripRow(checking = false), onClose = {}) } }
        }
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_more)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_send_bug_report)).assertExists()
    }

    @Test
    fun a_line_s_page_dismisses_its_alert_from_the_overflow() {
        // Maintainer, 2026-10-06: an overflow action on the line's own page, not a × that reads as close.
        // A disruption without TfL's reason opens a page too, for the action.
        val severe = LineStatus("jubilee", 6, "Severe Delays")
        val row = TripRow(
            checking = false,
            every = listOf(
                TripLine(leg("tube", "jubilee", "Jubilee", "A" to "King's Cross", "B" to "Euston", 0, 10, 2), severe),
                TripLine(leg("tube", "victoria", "Victoria", "A" to "King's Cross", "B" to "Euston", 0, 10, 2), LineStatus("victoria", 9, "Minor Delays"), dismissed = true),
            ),
        )
        val dismissed = mutableListOf<LineStatus>()
        val menu = AppMenuActions(updateAvailable = false, onOpenAppListing = {}, onSendBugReport = {}, onOpenLicenses = {})
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalAppMenu provides menu, LocalWorker provides inline) {
                    TripLinesPage(row, onClose = {}, dismissal = LineAlertDismissal({ dismissed += it }, false, {}, androidx.compose.runtime.mutableIntStateOf(0)))
                }
            }
        }
        val more = composeRule.activity.getString(R.string.menu_more)
        val dismiss = composeRule.activity.getString(R.string.alert_dismiss)
        // The list's own overflow has no line to dismiss.
        composeRule.onNodeWithContentDescription(more).performClick()
        composeRule.onNodeWithText(dismiss).assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        composeRule.onNodeWithText("Severe Delays").performClick()
        composeRule.onNodeWithContentDescription(more).performClick()
        composeRule.onNodeWithText(dismiss).performClick()
        assertEquals(listOf(severe), dismissed)
    }

    @Test
    fun a_dismiss_that_didn_t_save_is_said_on_the_lines_page() {
        // The page covers the screen whose snackbar would say it (Codex, #603): it says it itself, while
        // that screen holds its own word ([LineAlertDismissal.pagesOpen]) until the page closes.
        var failed by mutableStateOf(false)
        var acknowledged = 0
        val open = androidx.compose.runtime.mutableIntStateOf(0)
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme {
                CompositionLocalProvider(LocalWorker provides inline) {
                    TripLinesPage(TripRow(checking = false), onClose = {}, dismissal = LineAlertDismissal({}, failed, { acknowledged++; failed = false }, open))
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, open.intValue)
        failed = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.dismiss_write_failed)).assertExists()
        assertEquals(1, acknowledged)
    }

    @Test
    fun the_lines_pages_menu_closes_it_and_opens_the_disruptions_settings() {
        var closed = 0
        var opened = 0
        val menu = AppMenuActions(
            updateAvailable = false, onOpenAppListing = {}, onSendBugReport = {}, onOpenLicenses = {},
            onOpenDisruptionsSettings = { opened++ },
        )
        composeRule.setContent {
            StopDashTheme { CompositionLocalProvider(LocalAppMenu provides menu) { TripLinesPage(TripRow(checking = false), onClose = { closed++ }) } }
        }
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_more)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_settings)).performClick()
        assertEquals("the page closes, so Settings isn't opened under it", 1, closed)
        assertEquals(1, opened)
    }

    @Test
    fun without_somewhere_to_open_settings_the_lines_page_offers_none() {
        val menu = AppMenuActions(updateAvailable = false, onOpenAppListing = {}, onSendBugReport = {}, onOpenLicenses = {})
        composeRule.setContent {
            StopDashTheme { CompositionLocalProvider(LocalAppMenu provides menu) { TripLinesPage(TripRow(checking = false), onClose = {}) } }
        }
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_more)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_send_bug_report)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_settings)).assertDoesNotExist()
    }

    @Test
    fun a_reason_page_restored_while_the_trip_checks_again_never_shows_its_saved_reason() {
        fun line(id: String, name: String, status: LineStatus) =
            TripLine(leg("tube", id, name, "A" to "King's Cross", "B" to "Euston", 0, 10, 2), status)
        var row by mutableStateOf(
            TripRow(
                checking = false,
                every = listOf(line("jubilee", "Jubilee", LineStatus("jubilee", 6, "Severe Delays", fullText = "Jubilee line: Severe delays while we fix a signal failure."))),
            ),
        )
        val held = java.util.concurrent.Executor { }.asCoroutineDispatcher()
        val restoration = StateRestorationTester(composeRule)
        var worker: CoroutineDispatcher by mutableStateOf(java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        restoration.setContent { StopDashTheme { CompositionLocalProvider(LocalWorker provides worker) { TripLinesPage(row, onClose = {}) } } }
        composeRule.onNodeWithText("Severe Delays").performClick()
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertExists()
        // The app closed and came back: the trip checks again from nothing (Codex, #559).
        worker = held
        row = TripRow.CHECKING
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertDoesNotExist()
        composeRule.onNodeWithText("Checking…").assertExists()
    }

    @Test
    fun a_reason_page_stays_up_through_a_rotation() {
        fun line(id: String, name: String, status: LineStatus) =
            TripLine(leg("tube", id, name, "A" to "King's Cross", "B" to "Euston", 0, 10, 2), status)
        val row = TripRow(
            checking = false,
            every = listOf(
                line("jubilee", "Jubilee", LineStatus("jubilee", 6, "Severe Delays", fullText = "Jubilee line: Severe delays while we fix a signal failure."))
                    .copy(quieted = LineStatus("jubilee", 3, "Part Suspended", fullText = "No service between two stations.")),
            ),
        )
        val held = java.util.concurrent.Executor { }.asCoroutineDispatcher()
        val restoration = StateRestorationTester(composeRule)
        var worker: CoroutineDispatcher by mutableStateOf(java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        restoration.setContent { StopDashTheme { CompositionLocalProvider(LocalWorker provides worker) { TripLinesPage(row, onClose = {}) } } }
        composeRule.onNodeWithText("Severe Delays").performClick()
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertExists()
        // Rotated, with the worker that applies the saved order held: the reason page stays, titled,
        // never the lines for a moment (Codex, #559).
        worker = held
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Line status").assertDoesNotExist()
        composeRule.onNodeWithText("Jubilee").assertExists()
        // The reason the rider was reading stays (Codex, #559).
        composeRule.onNodeWithText("Jubilee line: Severe delays while we fix a signal failure.").assertExists()
        // So does the dismissed alert's reason under it (Codex, #559).
        composeRule.onNodeWithText("No service between two stations.").assertExists()
    }

    @Test
    fun trip_lines() {
        fun line(mode: String, id: String, name: String) = leg(mode, id, name, "A" to "King's Cross", "B" to "Euston", 0, 10, 2)
        val row = TripRow(
            checking = false,
            every = listOf(
                TripLine(
                    line("tube", "jubilee", "Jubilee"),
                    LineStatus("jubilee", 6, "Severe Delays", fullText = "Jubilee line: Severe delays while we fix a signal failure. Tickets are accepted on local buses."),
                ),
                TripLine(line("tube", "victoria", "Victoria"), LineStatus("victoria", 9, "Minor Delays"), dismissed = true),
                TripLine(line("tube", "northern", "Northern"), null, unknown = true),
                TripLine(line("tube", "central", "Central"), LineStatus("central", LineStatus.GOOD_SERVICE, "Good Service")),
            ),
            unknown = true,
        )
        composeRule.setContent { StopDashTheme { Surface { TripLinesContent(row, modifier = Modifier.padding(vertical = 16.dp)) } } }
        composeRule.onNodeWithText("Couldn't check").assertIsDisplayed()
        captureSnapshot("trip-lines.png")
    }

    // The real Northern line from a recorded TfL sequence, with its ends' positions as TfL gives them
    // live (this recording was trimmed before the app read them), so it stands north up as on a phone.
    private val northernLine: LineSequence by lazy {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<TflRouteSequenceDto>(checkNotNull(javaClass.getResource("/fixtures/route_sequence_northern_outbound.json")).readText())
            .toLineSequence()
            .copy(
                stopPositions = mapOf(
                    "940GZZLUEGW" to (51.61365 to -0.27493),
                    "940GZZLUHBT" to (51.65054 to -0.1943),
                    "940GZZLUMHL" to (51.60823 to -0.20999),
                    "940GZZLUMDN" to (51.40214 to -0.19484),
                    "940GZZBPSUST" to (51.47993 to -0.14214),
                ),
            )
    }

    private val districtLine: LineSequence by lazy {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<TflRouteSequenceDto>(checkNotNull(javaClass.getResource("/fixtures/route_sequence_district.json")).readText())
            .toLineSequence()
    }

    // The Northern line's part suspension as TfL worded it, its closed stretch placed by TfL's sections,
    // shut both ways: once each way round.
    private val suspensionText =
        "Northern Line: No service between Kennington and Battersea Power Station while we fix a faulty train at Nine Elms. GOOD SERVICE on the rest of the line."
    private val northernPartSuspended = LineStatus(
        "northern", 3, "Part Suspended",
        fullText = suspensionText,
        closures = listOf(
            PartClosure(
                3, "Part Suspended", suspensionText,
                listOf(listOf("940GZZLUKNG", "940GZZNEUGST", "940GZZBPSUST"), listOf("940GZZBPSUST", "940GZZNEUGST", "940GZZLUKNG")),
            ),
        ),
    )

    // A line's own page off the lines page, its map drawn from [sequence]; waits until [shown] is drawn.
    private fun showLinePage(line: TripLine, sequence: LineSequence, shown: String, starred: Set<String> = emptySet()) {
        val repository = RouteStopsRepository(object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence = sequence
        })
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalRouteStops provides repository) {
                    Surface { TripLineReason(line, starred = starred) }
                }
            }
        }
        // Once the map is in: the page is a lazy list, so [shown] is drawn once scrolled to.
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Loading map…").fetchSemanticsNodes().isEmpty() && hasLine(shown) }
    }

    // Whether the line page's list holds [text], scrolled to it where it does: a lazy list composes only what's on screen.
    private fun hasLine(text: String): Boolean {
        val list = composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        if (list.isEmpty()) return false
        // performScrollToNode fails when no item holds [text]: that answers no.
        return runCatching { composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }.isSuccess
    }

    private fun lineMapRow(text: String): SemanticsNodeInteraction {
        assertTrue("$text on the page", hasLine(text))
        return composeRule.onNodeWithText(text)
    }

    private fun northernLeg(from: Pair<String, String> = "" to "", to: Pair<String, String> = "" to "") =
        leg("tube", "northern", "Northern", from, to, 0, 10, 2)

    @Test
    fun a_closure_off_the_riders_stops_folds_saying_how_bad_and_opens_on_a_tap() {
        // King's Cross St. Pancras starred: a big interchange, standing in for the rider's own.
        showLinePage(TripLine(northernLeg(), northernPartSuspended), northernLine, shown = "King's Cross St. Pancras ★", starred = setOf("940GZZLUKSX"))
        // The Battersea branch folded, saying there's no service in it without naming where (maintainer, 2026-10-06).
        assertFalse(hasLine("Nine Elms"))
        assertFalse(hasLine("Battersea Power Station"))
        lineMapRow("2 stations \u26D4")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Has a closure"))
        // Where the closure begins, said to a screen reader as well as drawn (Codex, #606).
        lineMapRow("Kennington").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Next to a closure"))
        lineMapRow("2 stations \u26D4").performClick()
        composeRule.waitUntil(10_000) { hasLine("Nine Elms") }
        composeRule.onAllNodesWithText("No service").assertCountEquals(2)
        lineMapRow("Fold").performClick()
        composeRule.waitUntil(10_000) { !hasLine("Nine Elms") }
    }

    @Test
    fun a_closure_of_one_station_folds_with_its_run_naming_none() {
        // A made-up closure shutting Mornington Crescent alone, off any ride: it folds with the plain
        // stations around it on the Charing Cross branch, the fold saying how many and how bad, not
        // where (maintainer, 2026-10-06).
        val shut = listOf("940GZZLUCTN", "940GZZLUMTC", "940GZZLUEUS")
        val status = LineStatus("northern", 3, "Part Suspended", closures = listOf(PartClosure(3, "Part Suspended", null, listOf(shut, shut.asReversed()))))
        showLinePage(TripLine(northernLeg(), status), northernLine, shown = "9 stations \u26D4")
        assertFalse(hasLine("Mornington Crescent"))
        assertTrue("the junction it begins beside stays", hasLine("Camden Town"))
    }

    @Test
    fun a_closure_on_the_stretch_the_trip_rides_shows_in_full() {
        // A made-up closure between Angel and Moorgate, on a ride from King's Cross St. Pancras to Bank.
        val shut = listOf("940GZZLUAGL", "940GZZLUODS", "940GZZLUMGT")
        val words = "No service between Angel and Moorgate."
        val status = LineStatus(
            "northern", 3, "Part Suspended", fullText = words,
            closures = listOf(PartClosure(3, "Part Suspended", words, listOf(shut, shut.asReversed()))),
        )
        // The Planner's path, every stop it calls at after boarding.
        val leg = northernLeg("940GZZLUKSX" to "King's Cross St. Pancras", "940GZZLUBNK" to "Bank").copy(path = shut + "940GZZLUBNK")
        showLinePage(TripLine(leg, status), northernLine, shown = "Old Street")
        composeRule.onAllNodesWithText("No service").assertCountEquals(1)
        lineMapRow("Edgware · High Barnet · Mill Hill East")
    }

    @Test
    fun a_closure_dismissed_while_a_milder_alert_stands_is_still_drawn() {
        // The page names the suspension the rider dismissed beside the minor delays: its map draws it (Codex, #606).
        val line = TripLine(northernLeg(), LineStatus("northern", 9, "Minor Delays"), quieted = northernPartSuspended)
        showLinePage(line, northernLine, shown = "2 stations \u26D4")
        lineMapRow("2 stations \u26D4").performClick()
        composeRule.waitUntil(10_000) { hasLine("Nine Elms") }
        composeRule.onAllNodesWithText("No service").assertCountEquals(2)
    }

    @Test
    fun a_fold_opened_stays_open_when_the_page_is_restored() {
        val repository = RouteStopsRepository(object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence = northernLine
        })
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalRouteStops provides repository) {
                    Surface { TripLineReason(TripLine(northernLeg(), northernPartSuspended)) }
                }
            }
        }
        composeRule.waitUntil(10_000) { hasLine("Burnt Oak to Chalk Farm") }
        lineMapRow("Burnt Oak to Chalk Farm").performClick()
        composeRule.waitUntil(10_000) { hasLine("Colindale") }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(10_000) { hasLine("Colindale") }
        composeRule.onNodeWithText("Fold").assertExists()
    }

    @Test
    fun a_closure_the_map_cant_place_is_said_rather_than_drawn_as_unaffected() {
        // A made-up bus whose way back calls in another order (so it's left off the map), shut only at a
        // stop of that way back, and named only by it: the page says the closure isn't on the map.
        val names = mapOf("A1" to "Alpha", "A2" to "Alpha", "B1" to "Beta", "B2" to "Beta", "C1" to "Gamma", "C2" to "Gamma", "D1" to "Delta", "D2" to "Delta", "X2" to "Xray")
        val bus = LineSequence(
            routes = listOf(LineRoute("", listOf("A1", "B1", "C1", "D1"), "outbound"), LineRoute("", listOf("D2", "X2", "B2", "C2", "A2"), "inbound")),
            stopNames = names,
            stopAreas = names.keys.associateWith { it.take(1).lowercase() },
        )
        val words = "Buses are not calling at Xray."
        val status = LineStatus("b", 3, "Part Suspended", fullText = words, closures = listOf(PartClosure(3, "Part Suspended", words, listOf(listOf("D2", "X2")))))
        showLinePage(TripLine(leg("bus", "b", "B", "" to "", "" to "", 0, 10, 2), status), bus, shown = "This closure isn't on the map")
        composeRule.onNodeWithText("Alpha").assertExists()
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp-h914dp-night-420dpi")
    fun line_page_part_suspended() {
        showLinePage(TripLine(northernLeg(), northernPartSuspended), northernLine, shown = "2 stations \u26D4", starred = setOf("940GZZLUKSX"))
        captureSnapshot("line-page-part-suspended.png")
    }

    @Test
    fun line_page_good_service() {
        // A trip riding the Northern from King's Cross St. Pancras to Bank: both its stops stay out of the folds.
        val line = TripLine(northernLeg("940GZZLUKSX" to "King's Cross St. Pancras", "940GZZLUBNK" to "Bank"), LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"))
        showLinePage(line, northernLine, shown = "Show all stations")
        composeRule.onNodeWithText("King's Cross St. Pancras").assertExists()
        composeRule.onNodeWithText("Show all stations").assertExists()
        captureSnapshot("line-page-good-service.png")
    }

    @Test
    fun line_page_district() {
        val line = TripLine(leg("tube", "district", "District", "" to "", "" to "", 0, 10, 2), LineStatus("district", LineStatus.GOOD_SERVICE, "Good Service"))
        showLinePage(line, districtLine, shown = "Show all stations")
        captureSnapshot("line-page-district.png")
    }

    @Test
    fun an_open_route_shows_its_settled_disruptions_at_once() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        show(disrupted, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Jubilee").assertExists()
        // The trip has settled: opening a route draws its row as it is, never "Checking…" held for a
        // moment first (Codex, #543).
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        // A few frames for the route's own row to be worked out, well inside the hold.
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
        composeRule.onNodeWithTag("tripDisruptions").assertExists()
        inRow("Checking…").assertDoesNotExist()
    }

    @Test
    fun another_journeys_open_route_never_shows_the_last_ones_disruptions() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        val state = mutableStateOf(disrupted)
        val journey = mutableStateOf("A>B")
        // A route riding the Jubilee open, as the trip holds it.
        val openRoute = mutableStateOf<String?>(openRouteOf(viaCanadaWater, disrupted, emptyMap()).encode())
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides inline) {
                    TripScreen(
                        title = "To Canary Wharf", state = state.value, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, journey = journey.value,
                        openRoute = openRoute,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        inRow("Jubilee").assertExists()
        // A new nearest stop: another journey, whose trip already has another route open.
        composeRule.mainClock.autoAdvance = false
        journey.value = "C>B"
        state.value = running
        openRoute.value = openRouteOf(viaWhitechapel, running, emptyMap()).encode()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        // Its row is its own, never the last journey's Jubilee held for a moment (Codex, #543).
        inRow("Jubilee").assertDoesNotExist()
    }

    @Test
    fun the_disruptions_row_comes_back_as_it_was_from_an_open_route() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        show(disrupted, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Jubilee").assertExists()
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        // The route open: the list has left the screen.
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
        // Back to the list: the row is as it was, never "Checking…" while it settles again (Codex, #543).
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        inRow("Jubilee").assertExists()
        inRow("Checking…").assertDoesNotExist()
    }

    @Test
    fun a_plan_starting_afresh_never_shows_the_last_plans_disruptions() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        val held = mutableStateOf(disrupted)
        show(disrupted, held = held, worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher())
        inRow("Jubilee").assertExists()
        // A new plan: no cards while it's planned, then its own, with nothing disrupted.
        composeRule.mainClock.autoAdvance = false
        held.value = running.copy(routes = null, planning = true)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        held.value = running
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        // The last plan's Jubilee never stands over the new cards (Codex, #543).
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        inRow("Jubilee").assertDoesNotExist()
    }

    @Test
    fun a_trip_list_shows_its_disruptions_as_it_appears() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        composeRule.mainClock.autoAdvance = false
        // The routes loaded inline too, so the frames the list takes don't hang on another thread's pace.
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        show(disrupted, routeStops = RouteStopsRepository(source, io = inline, compute = inline), worker = inline)
        composeRule.mainClock.advanceTimeBy(100)
        // The row is worked out behind the placeholder: the list appears with it, never with "Checking…"
        // that gives way to pills under the rider a moment later (Codex, #543).
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        inRow("Jubilee").assertExists()
        inRow("Checking…").assertDoesNotExist()
    }

    @Test
    fun a_plan_switched_to_never_shows_the_last_plans_disruptions() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        val state = mutableStateOf(disrupted)
        val speed = mutableStateOf(WalkingSpeed.AVERAGE)
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides inline) {
                    TripScreen(
                        title = "To Canary Wharf", state = state.value, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, walkingSpeed = speed.value,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        inRow("Jubilee").assertExists()
        // Another pace's plan, kept from before, replaces the cards at once, with no gap without them.
        composeRule.mainClock.autoAdvance = false
        speed.value = WalkingSpeed.SLOW
        state.value = running
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        // The last plan's Jubilee never stands over the new plan's cards (Codex, #543).
        inRow("Jubilee").assertDoesNotExist()
    }

    @Test
    fun a_journey_switched_to_waits_and_never_shows_the_last_journeys_disruptions() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        val state = mutableStateOf(disrupted)
        val journey = mutableStateOf("A>B")
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides inline) {
                    TripScreen(
                        title = "To Canary Wharf", state = state.value, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, journey = journey.value,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        inRow("Jubilee").assertExists()
        // A new nearest stop: another journey, its plan cached, so its cards come at once with the
        // same options (Codex, #543).
        composeRule.mainClock.autoAdvance = false
        journey.value = "C>B"
        state.value = running.copy(refreshing = true)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        // It waits behind its placeholder rather than show its cards raw under the last journey's row.
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
        state.value = running
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        inRow("Jubilee").assertDoesNotExist()
    }

    @Test
    fun a_line_avoided_never_leaves_its_pill_in_the_disruptions_row() {
        val lines = planned.routes.orEmpty().flatMap { route -> route.rides.map { it.lineId } } +
            planned.live.values.flatMap { stop -> stop.departures.map { it.lineId } }
        val running = planned.copy(statuses = lines.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") })
        val disrupted = running.copy(statuses = running.statuses + ("jubilee" to LineStatus("jubilee", 9, "Minor Delays")))
        val avoided = mutableStateOf(emptySet<String>())
        val inline = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides inline) {
                    TripScreen(
                        title = "To Canary Wharf", state = disrupted, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, avoidedLines = avoided.value,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        inRow("Jubilee").assertExists()
        // The Jubilee avoided: only the Whitechapel route is left, and the row never shows the
        // Jubilee's pill over it (Codex, #543).
        composeRule.mainClock.autoAdvance = false
        avoided.value = setOf("jubilee")
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        inRow("Jubilee").assertDoesNotExist()
    }

    @Test
    fun a_plan_switched_to_still_shows_within_its_cap() {
        // No route ever loads: each plan's list shows by its cap, the one switched to as well (Codex, #543).
        val never = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gated = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                never.await()
                return sequences.getValue(lineId)
            }
        }
        val inline = kotlinx.coroutines.Dispatchers.Unconfined
        val speed = mutableStateOf(WalkingSpeed.AVERAGE)
        val worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides worker) {
                    TripScreen(
                        title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(gated, io = inline, compute = inline), onBack = {}, onRetry = {},
                        walkingSpeed = speed.value,
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        speed.value = WalkingSpeed.SLOW
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(REVEAL_CAP_MILLIS + 1_000)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
    }

    @Test
    fun a_trip_list_waits_for_its_work_again_after_a_rotation() {
        // No route ever loads, so the list shows only by its cap.
        val never = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gated = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                never.await()
                return sequences.getValue(lineId)
            }
        }
        val inline = kotlinx.coroutines.Dispatchers.Unconfined
        val worker = java.util.concurrent.Executor { it.run() }.asCoroutineDispatcher()
        // A rotation as composition sees it: the screen leaves and comes back, its saved state kept
        // ([androidx.compose.runtime.saveable.SaveableStateHolder]), everything only remembered lost.
        val shown = mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalWorker provides worker) {
                    if (shown.value) {
                        holder.SaveableStateProvider("trip") {
                            TripScreen(
                                title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                                routeStops = RouteStopsRepository(gated, io = inline, compute = inline), onBack = {}, onRetry = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(REVEAL_CAP_MILLIS + 1_000)
        composeRule.onNodeWithTag("tripRoutes").assertExists()
        shown.value = false
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        shown.value = true
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)
        // The list's work went with the composition, so the list waits for it again rather than come
        // back as raw cards that settle under the rider (Codex, #543).
        composeRule.onAllNodes(hasTestTag("tripRoutes")).assertCountEquals(0)
    }

    @Test
    fun a_cut_pill_warns_of_each_disrupted_line() {
        val busStop = "490000001A" to "Surrey Docks"
        val busStation = "490000002B" to "Canada Water Bus Station"
        fun bus(line: String, departs: Long) = TripRoute(
            listOf(
                leg("bus", line, line, busStop, busStation, departs, departs + 6, 3, change = 4),
                leg("tube", "jubilee", "Jubilee", canadaWaterTube, canaryWharf, departs + 11, departs + 13, 1),
            ),
        )
        show(
            planned.copy(
                routes = listOf(bus("47", 4), bus("188", 6)),
                statuses = planned.statuses + mapOf(
                    "47" to LineStatus("47", 9, "Minor Delays"),
                    "188" to LineStatus("188", 6, "Severe Delays"),
                ),
            ),
            // The buses' routes aren't needed here: none loads, and the pill stands on the plan.
            routeStops = RouteStopsRepository(
                object : RouteSequenceSource {
                    override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                        sequences[lineId] ?: LineSequence(routes = emptyList(), stopNames = emptyMap())
                },
            ),
        )
        // One ⚠ beside the cut pill, reading out both lines' details.
        composeRule.onNodeWithContentDescription("47: Minor Delays; 188: Severe Delays").assertExists()
    }

    @Test
    fun a_cut_pill_reads_as_its_combined_label() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.semantics(mergeDescendants = true) {}.testTag("row")) {
                    SharedLinePill(listOf(LineRef("47", "47", "bus"), LineRef("188", "188", "bus")), "47 or 188")
                }
            }
        }
        // A screen reader hears "47 or 188" once, not the segments' codes as well.
        val config = composeRule.onNodeWithTag("row").fetchSemanticsNode().config
        assertEquals(listOf("47 or 188"), config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() })
        assertTrue(config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty())
    }

    @Test
    fun a_cut_pill_keeps_its_lines_in_order_right_to_left() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SharedLinePill(listOf(LineRef("victoria", "Victoria", "tube"), LineRef("central", "Central", "tube")), "either")
                }
            }
        }
        // Painted left to right, so the first line's code stays over its own (left) segment.
        val first = composeRule.onNodeWithText("VIC", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithText("CEN", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(first.left < second.left)
    }

    @Test
    fun cut_pills_beside_lone_pills() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Surface {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun bus(vararg ids: String) = ids.map { LineRef(it, it, "bus") }
                        LinePill("390", "390", "bus")
                        LinePill("Northern", "northern", "tube")
                        SharedLinePill(bus("43", "134"), "43 or 134")
                        SharedLinePill(bus("47", "188"), "47 or 188")
                        SharedLinePill(bus("4", "N20"), "4 or N20")
                        SharedLinePill(listOf(LineRef("victoria", "Victoria", "tube"), LineRef("piccadilly", "Piccadilly", "tube")), "either")
                        SharedLinePill(bus("43", "134", "263"), "any")
                        SharedLinePill(bus("47", "188", "199", "225"), "any")
                    }
                }
            }
        }
        captureSnapshot("cut-pills.png", heightPx = 800)
    }

    @Test
    fun a_cut_pills_segments_take_their_own_codes_width() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                SharedLinePill(listOf(LineRef("43", "43", "bus"), LineRef("134", "134", "bus")), "43 or 134")
            }
        }
        // A two-digit route takes less room than a three-digit one (maintainer, 2026-09-28).
        fun width(code: String) = composeRule.onNodeWithText(code, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue(width("43") < width("134"))
    }

    @Test
    fun a_cut_pills_one_character_code_still_gets_a_two_character_width() {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Column {
                    SharedLinePill(listOf(LineRef("4", "4", "bus"), LineRef("N20", "N20", "bus")), "4 or N20")
                    SharedLinePill(listOf(LineRef("43", "43", "bus"), LineRef("N20", "N20", "bus")), "43 or N20")
                }
            }
        }
        // "4/N20" read too tight: a segment is never narrower than a two-character code
        // (maintainer, 2026-09-28).
        fun width(code: String) = composeRule.onNodeWithText(code, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue(width("4") > 0.dp)
        assertTrue(kotlin.math.abs(width("4").value - width("43").value) < 1f)
    }

    @Test
    fun a_cut_pill_fits_the_room_it_has() {
        val lines = listOf("188", "199").map { LineRef(it, it, "bus") }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.width(48.dp)) {
                    SharedLinePill(lines, "188 or 199", Modifier.testTag("cut"))
                }
            }
        }
        // Two codes in 48dp: both shrink alike, so each label stays over its own color rather than
        // the second being squeezed out.
        val bounds = composeRule.onNodeWithTag("cut").getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= 48.dp)
        val widths = lines.map { line ->
            composeRule.onNodeWithText(line.name, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        }
        assertTrue(widths.all { it > 0.dp })
        assertTrue(widths.maxOf { it.value } - widths.minOf { it.value } < 1f)
    }

    @Test
    fun a_cut_pill_for_three_or_more_lines_names_the_first_then_more() {
        val lines = listOf("47", "188", "199", "225").map { LineRef(it, it, "bus") }
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                Box(Modifier.semantics(mergeDescendants = true) {}.testTag("row")) {
                    SharedLinePill(lines, "47, 188, 199 or 225")
                }
            }
        }
        // "47/…": the first line, then "…" for the rest, so the pill stays two parts wide
        // (maintainer, 2026-09-28); a screen reader still hears every line.
        assertEquals(listOf("47", "…"), cutPillCodes(lines))
        assertEquals(listOf("43", "134"), cutPillCodes(listOf(LineRef("43", "43", "bus"), LineRef("134", "134", "bus"))))
        val config = composeRule.onNodeWithTag("row").fetchSemanticsNode().config
        assertEquals(listOf("47, 188, 199 or 225"), config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() })
    }

    @Test
    fun a_trip_has_the_app_overflow() {
        var reported = 0
        show(planned, menu = AppMenuActions(updateAvailable = true, onOpenAppListing = {}, onSendBugReport = { reported++ }, onOpenLicenses = {}))
        // The update dot, as on the list, and the menu's report and About.
        composeRule.onNodeWithTag(UPDATE_AVAILABLE_DOT_TAG, useUnmergedTree = true).assertExists()
        // The button and its dot; the open menu is a popup window of its own, which this capture of
        // the activity's window doesn't draw.
        captureSnapshot("trip-overflow.png")
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.menu_more)).performClick()
        val update = composeRule.onNodeWithText(composeRule.activity.getString(R.string.update_available))
            .getUnclippedBoundsInRoot()
        val about = composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_about))
            .getUnclippedBoundsInRoot()
        // "Update available" sits last, so the screen's own items keep their positions.
        assertTrue(update.top > about.top)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.menu_send_bug_report)).performClick()
        assertEquals(1, reported)
    }

    @Test
    fun an_open_route_outlasts_the_screen_leaving() {
        val openRoute = mutableStateOf<String?>(null)
        val showing = mutableStateOf(true)
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                // Taken out of composition and back, as an overlay (the licenses) does.
                if (showing.value) {
                    TripScreen(
                        title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                        routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, openRoute = openRoute,
                    )
                }
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        showing.value = false
        composeRule.waitForIdle()
        showing.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun an_open_route_is_restored_after_the_process_is_recreated() {
        val restoration = StateRestorationTester(composeRule)
        val openRoute = mutableStateOf<String?>(null)
        restoration.setContent {
            StopDashTheme(dynamicColor = false) {
                TripScreen(
                    title = "To Canary Wharf", state = planned, now = now, access = Duration.ofMinutes(2),
                    routeStops = RouteStopsRepository(source), onBack = {}, onRetry = {}, openRoute = openRoute,
                )
            }
        }
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        // A new trip model, as after a kill: it starts with no route open.
        openRoute.value = null
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
    }

    @Test
    fun a_pull_on_the_routes_is_remembered_across_a_configuration_change() {
        val restoration = StateRestorationTester(composeRule)
        var lastPull: MutableState<Instant?>? = null
        restoration.setContent { lastPull = rememberLastPull("C") }
        composeRule.runOnIdle { lastPull!!.value = now }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(now, lastPull!!.value) }
    }

    @Test
    fun trip_planning() {
        show(TripViewModel.State(planning = true))
        composeRule.onNodeWithText("Planning…").assertIsDisplayed()
        captureSnapshot("trip-planning.png")
    }

    @Test
    fun trip_plan_failed() {
        show(TripViewModel.State(planError = DeparturesUiState.Error.Kind.OFFLINE))
        composeRule.onNodeWithText("Couldn't plan the trip: You're offline").assertIsDisplayed()
        captureSnapshot("trip-plan-failed.png")
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        if (!capturing()) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
