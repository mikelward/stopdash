package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.ThreadRecorder
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.OffPlan
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TripLeg
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The next ride's trains ([rememberNextTrains]) are worked out on the page's worker ([LocalWorker]),
 * never the main thread (AGENTS.md *Main thread: read and dispatch only*), as a timeline composition
 * only picks from: each train drops off the moment it departs, with nothing worked out then.
 * Synthetic stops only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class NextTrainsOffMainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long): Instant = now.plusSeconds(minutes * 60)

    // The ride from A to C; the red line's route calls at both (and at A's other pole, A2), the blue
    // line's turns off before C.
    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(0), at(10))
    private val red = LineSequence(listOf(LineRoute("A2-C", listOf("A2", "A", "B", "C"))), mapOf("A2" to "A", "A" to "A", "B" to "B", "C" to "C"))
    private val blue = LineSequence(listOf(LineRoute("A-Z", listOf("A", "Z"))), mapOf("A" to "A", "Z" to "Z"))
    private val green = LineSequence(listOf(LineRoute("A-C", listOf("A", "C"))), mapOf("A" to "A", "C" to "C"))
    private val routes = mapOf<String, LineSequence?>("red" to red, "blue" to blue, "green" to green)

    private fun train(minutes: Long, line: String = "red") =
        Departure(line, line.replaceFirstChar { it.uppercase() }, "outbound", "C", null, at(minutes), "tube", vehicleId = "EXAMPLE")

    private fun board(vararg trains: Departure, others: List<ActiveTripTracker.PoleBoard> = emptyList()) =
        ActiveTripTracker.NextBoard(ride, trains.toList(), fetchedAt = now, others = others)

    private fun times(next: NextTrains) = next.trains.map { it.expectedArrival }

    @Test
    fun each_train_drops_off_as_it_departs_and_another_line_s_way_elsewhere_never_shows() {
        val timeline = trainsTimeline(board(train(5), train(3), train(4, "blue")), routes, now)
        assertEquals(listOf(at(3), at(5)), times(timeline.at(now)))
        assertEquals(listOf(at(3), at(5)), times(timeline.at(at(3).minusMillis(1))))
        // Gone the moment it's due, as a refresh would drop it.
        assertEquals(listOf(at(5)), times(timeline.at(at(3))))
        assertEquals(emptyList<Instant>(), times(timeline.at(at(5))))
        assertEquals(emptyList<Instant>(), times(timeline.at(at(60))))
        // The rows go with them, drawn as the board draws them.
        assertEquals(1, timeline.at(now).groups.size)
        assertTrue(timeline.at(at(5)).groups.isEmpty())
        // And each card's route rows with them, so the card draws them rather than work them out.
        val shown = timeline.at(now)
        assertEquals(shown.groups, shown.cards.map { it.group })
        assertEquals(listOf(listOf(listOf(at(3), at(5)))), shown.cards.map { card -> card.lines.map { row -> row.flatMap { line -> line.times.map { it.expectedArrival } } } })
        assertEquals(shown.cards.map { it.lines }, timeline.entry(0)?.cards?.map { it.lines })
    }

    @Test
    fun a_branch_off_the_ride_is_listed_apart_and_its_trains_drop_off_as_they_depart() {
        // The red line forks after B: on to C, the ride's way, or to Y (maintainer, 2026-10-05).
        val forked = LineSequence(
            listOf(LineRoute("A ↔ C", listOf("A", "B", "C")), LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        val toY = train(2).copy(destination = "Y", vehicleId = "EXAMPLE2")
        val board = ActiveTripTracker.NextBoard(ride.copy(path = listOf("B", "C")), listOf(train(5), toY), fetchedAt = now)
        val timeline = trainsTimeline(board, mapOf("red" to forked), now)
        assertEquals(listOf(at(5)), times(timeline.at(now)))
        val off = timeline.at(now).offPlan.single()
        assertEquals("Y", off.heading)
        assertEquals("B", off.branch.forkName)
        assertEquals(listOf(at(2)), off.trains.map { it.expectedArrival })
        // Its train gone, the branch is still there to take: TfL's labels can be wrong.
        assertTrue(timeline.at(at(2)).offPlan.single().trains.isEmpty())
    }

    @Test
    fun the_rides_own_line_is_loaded_with_none_of_its_trains_listed() {
        // TfL lists none of the ride's line's trains: its route still finds the branches off it (Codex, #583).
        assertEquals(listOf("blue", "red"), nextBoardLineIds(ride, listOf(train(3, "blue"))))
        assertEquals(listOf("red"), nextBoardLineIds(ride, emptyList()))
        assertEquals(listOf("red"), nextBoardLineIds(ride, listOf(train(3))))
        // A bus has no branches to find.
        assertEquals(emptyList<String>(), nextBoardLineIds(ride.copy(mode = "bus"), emptyList()))
    }

    @Test
    fun off_plan_rows_are_a_bounded_few_and_count_as_trains_listed() {
        // A line with many branches off the ride: the card lists the nearest few, no more.
        val many = (1..10).map { i -> OffPlan.Branch("Y$i", forkIndex = 0, forkName = "B") }
        val rows = offPlanRows(ride, many)
        assertEquals(MAX_OFF_PLAN_ROWS, rows.size)
        assertEquals("Y1", rows.first().heading)
        // Only branches off the plan listed is still something listed: a failed refresh keeps showing them.
        assertFalse(NextTrains(ride, emptyList(), failed = true, offPlan = rows).none)
        assertTrue(NextTrains(ride, emptyList(), failed = true).none)
    }

    @Test
    fun only_the_entry_in_force_and_the_next_carry_their_rows() {
        // A busy board's later entries aren't all grouped ahead of time (Codex on #557): the window moves
        // with the entry in force, and the next one's rows are always ready when it starts.
        val timeline = trainsTimeline(board(train(3), train(5), train(8)), routes, now)
        assertEquals(1, timeline.entry(0)!!.groups.size)
        assertEquals(listOf(at(5), at(8)), times(timeline.entry(1)!!))
        assertEquals(1, timeline.entry(1)!!.groups.size)
        assertNull(timeline.entry(2))
        val moved = timeline.around(1)
        assertNull(moved.entry(0))
        assertEquals(listOf(at(8)), times(moved.entry(2)!!))
        assertEquals(1, moved.entry(2)!!.groups.single().rows.size)
    }

    @Test
    fun another_pole_s_trains_are_listed_under_their_own_header() {
        val pole = StopLocation("A2", "A", 51.5, -0.12, stopLetter = "K")
        val timeline = trainsTimeline(board(train(3), others = listOf(ActiveTripTracker.PoleBoard(pole, listOf(train(6))))), routes, now)
        val next = timeline.at(now)
        assertEquals(listOf(at(6)), next.others.single().trains.map { it.expectedArrival })
        assertEquals(2, next.groups.size)
        // The other pole's train departs later than the ride's own: each drops at its own time.
        assertEquals(listOf(at(6)), timeline.at(at(4)).others.single().trains.map { it.expectedArrival })
        assertTrue(timeline.at(at(4)).trains.isEmpty())
    }

    @Test
    fun the_board_s_trains_are_worked_out_on_the_worker_and_the_last_board_s_hold_meanwhile() {
        // Held until released by hand: worked out on the main thread, the trains would be there at once.
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = routes.getValue(lineId)!!
            },
        )
        var shown by mutableStateOf(board(train(3), train(5)))
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                next = rememberNextTrains(shown, now, ride = ride)
            }
        }
        composeRule.waitForIdle()
        // The route has loaded but nothing's been worked out: loading, not trains found on the main thread.
        assertTrue(next!!.pending)
        assertTrue(next!!.trains.isEmpty())
        settle(scheduler)
        assertFalse(next!!.pending)
        assertEquals(listOf(at(3), at(5)), times(next!!))
        assertEquals(at(3), next!!.nextDue)
        // A new board: the last one's trains hold while its own are worked out, rather than "Loading",
        // but the trip isn't timed by the last board's train, which the new one may no longer list
        // (Codex on #557).
        shown = board(train(7))
        composeRule.waitForIdle()
        assertEquals(listOf(at(3), at(5)), times(next!!))
        assertNull(next!!.nextDue)
        settle(scheduler)
        assertEquals(listOf(at(7)), times(next!!))
        assertEquals(at(7), next!!.nextDue)
    }

    @Test
    fun a_new_route_topology_works_the_board_s_cards_out_again() {
        // The topology is replaced once its patterns load (MainActivity): the same board's cards are
        // grouped again under it, on the worker, never left under the one they were first worked out
        // with (Codex, #588).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = routes.getValue(lineId)!!
            },
        )
        val shown = board(train(3), train(5))
        var topology by mutableStateOf(app.stopdash.domain.RouteTopology.EMPTY)
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository, LocalRouteTopology provides topology) {
                next = rememberNextTrains(shown, now, ride = ride)
            }
        }
        composeRule.waitForIdle()
        settle(scheduler)
        val first = next!!.cards
        assertTrue(first.isNotEmpty())
        topology = app.stopdash.domain.RouteTopology(emptyMap())
        composeRule.waitForIdle()
        // The last rows stand in while the worker groups them again.
        assertSame(first, next!!.cards)
        settle(scheduler)
        assertNotSame(first, next!!.cards)
        assertEquals(listOf(at(3), at(5)), times(next!!))
    }

    @Test
    fun every_pass_over_the_board_runs_on_the_worker_thread_never_the_caller_s() {
        // A real worker thread of its own, and a board noting the thread of every pass over its trains:
        // composition only reads what the worker publishes (AGENTS.md *Main thread*; Codex on #557).
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "trains-worker") }
        val worker = executor.asCoroutineDispatcher()
        try {
            val reads = ThreadRecorder()
            val shown = ActiveTripTracker.NextBoard(ride, NotingList(listOf(train(3), train(5)), reads), fetchedAt = now)
            val repository = repository()
            var next: NextTrains? = null
            composeRule.setContent {
                CompositionLocalProvider(LocalWorker provides worker, LocalRouteStops provides repository) {
                    next = rememberNextTrains(shown, now, ride = ride)
                }
            }
            composeRule.waitUntilWorked(executor) { next?.trains?.isNotEmpty() == true }
            assertEquals(listOf(at(3), at(5)), times(next!!))
            assertTrue(reads.threads().isNotEmpty())
            assertEquals(setOf("trains-worker"), reads.threads().toSet())
        } finally {
            executor.shutdown()
        }
    }

    /** [items], noting the thread of each pass over it in [reads]. */
    private class NotingList<T>(private val items: List<T>, private val reads: ThreadRecorder) : AbstractList<T>() {
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
        override fun iterator(): Iterator<T> {
            reads.note()
            return items.iterator()
        }
    }

    @Test
    fun a_route_load_under_way_is_canceled_once_the_board_goes() {
        // The rider seen on board clears the board: its lines' route loads stop with it, rather than run
        // on, and be rechecked, through the ride (Codex on #557).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var canceled = false
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    started.complete(Unit)
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        canceled = true
                    }
                }
            },
        )
        var shown by mutableStateOf<ActiveTripTracker.NextBoard?>(board(train(3)))
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                rememberNextTrains(shown, now, ride = ride)
            }
        }
        settle(scheduler)
        assertTrue(started.isCompleted)
        assertFalse(canceled)
        shown = null
        settle(scheduler)
        assertTrue(canceled)
    }

    @Test
    fun the_clock_set_back_brings_back_the_trains_still_to_come() {
        // Wall time, as the countdowns keep it: set back after a train went, the board shows it again
        // once the worker has the entry for now, and never the later entry meanwhile (Codex on #557).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = repository()
        val shown = board(train(3), train(5), train(8))
        var clock by mutableStateOf(now)
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                next = rememberNextTrains(shown, clock, ride = ride)
            }
        }
        settle(scheduler)
        clock = at(4)
        settle(scheduler)
        assertEquals(listOf(at(5), at(8)), times(next!!))
        // Back before the first train's time, still within the timeline.
        clock = at(2)
        composeRule.waitForIdle()
        assertTrue(next!!.pending)
        settle(scheduler)
        assertEquals(listOf(at(3), at(5), at(8)), times(next!!))
        // Back before the timeline began: worked out afresh from then.
        clock = at(9)
        settle(scheduler)
        assertTrue(next!!.trains.isEmpty())
        clock = now.minusSeconds(60)
        settle(scheduler)
        assertEquals(listOf(at(3), at(5), at(8)), times(next!!))
        assertFalse(next!!.pending)
    }

    @Test
    fun a_board_worked_out_afresh_for_the_clock_set_back_logs_what_it_couldn_t_place() {
        // A train with no line went before the board was first worked out; the clock set back before
        // then brings it back, unchecked, and the log says why (Codex on #557).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val warnings = mutableListOf<String>()
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = routes.getValue(lineId)!!
            },
            warn = { warnings += it },
            compute = held,
        )
        val shown = board(train(3, ""), train(8))
        var clock by mutableStateOf(at(4))
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                next = rememberNextTrains(shown, clock, ride = ride)
            }
        }
        settle(scheduler)
        assertEquals(listOf(at(8)), times(next!!))
        assertTrue(warnings.none { "no line id" in it })
        clock = now
        settle(scheduler)
        assertTrue(next!!.unresolved)
        assertEquals(1, warnings.count { "no line id" in it })
    }

    @Test
    fun a_ride_is_known_by_its_line_stops_and_times_not_its_path() {
        assertTrue(sameRide(ride, ride.copy(path = listOf("A", "B", "C"), pathNames = listOf("A", "B", "C"))))
        assertFalse(sameRide(ride, ride.copy(toId = "B")))
        assertFalse(sameRide(ride, ride.copy(lineId = "blue")))
        assertFalse(sameRide(ride, ride.copy(departure = at(1))))
        assertFalse(sameRide(ride, null))
        assertTrue(sameRide(null, null))
    }

    @Test
    fun a_line_back_on_the_board_after_one_without_it_is_checked_against_its_route() {
        // Green's route was loaded for the first board, and the second board has no green train. When
        // the third brings one back, its trains are worked out with green's route, never with the
        // second board's lines, which would leave it out for good (Codex on #557).
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = repository()
        var shown by mutableStateOf(board(train(3), train(4, "green")))
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                next = rememberNextTrains(shown, now, ride = ride)
            }
        }
        settle(scheduler)
        assertEquals(listOf(at(3), at(4)), times(next!!))
        shown = board(train(5))
        settle(scheduler)
        assertEquals(listOf(at(5)), times(next!!))
        // Green's train is the soonest: the trip's fallback train is it, not the one after (Codex on #557).
        shown = board(train(8), train(7, "green"))
        settle(scheduler)
        assertEquals(listOf(at(7), at(8)), times(next!!))
        assertFalse(next!!.pending)
        assertEquals(at(7), next!!.nextDue)
    }

    /** Lets the held worker and the main thread hand work back and forth (line ids, routes, trains) until both are done. */
    private fun settle(scheduler: TestCoroutineScheduler) {
        repeat(5) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    private fun repository() = RouteStopsRepository(
        object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = routes.getValue(lineId)!!
        },
    )

    @Test
    fun a_partly_read_first_board_says_so_while_its_trains_are_worked_out() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = repository()
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                next = rememberNextTrains(board(train(3)).copy(partial = true), now, ride = ride)
            }
        }
        composeRule.waitForIdle()
        // Loading, and a pole left unread said already, not only once the worker has run (Codex on #557).
        assertTrue(next!!.pending)
        assertTrue(next!!.failed)
    }

    @Test
    fun a_failed_refresh_is_said_at_once_while_the_last_board_s_trains_hold() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = repository()
        var shown by mutableStateOf(board(train(3), train(5)))
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                next = rememberNextTrains(shown, now, ride = ride)
            }
        }
        composeRule.waitForIdle()
        settle(scheduler)
        assertFalse(next!!.failed)
        // The refresh failed: said before the worker has looked at the new board (Codex on #557).
        shown = shown.copy(failed = true)
        composeRule.waitForIdle()
        assertTrue(next!!.failed)
        assertEquals(listOf(at(3), at(5)), times(next!!))
    }

    @Test
    fun the_next_entry_takes_over_as_a_train_departs_and_a_longer_gap_is_looked_up_on_the_worker() {
        val scheduler = TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val repository = repository()
        var shown by mutableStateOf(board(train(3), train(5), train(8)))
        var clock by mutableStateOf(now)
        var next: NextTrains? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWorker provides held, LocalRouteStops provides repository) {
                next = rememberNextTrains(shown, clock, ride = ride)
            }
        }
        composeRule.waitForIdle()
        settle(scheduler)
        assertEquals(listOf(at(3), at(5), at(8)), times(next!!))
        // The first train departs: the next entry shows at once, with the worker still held.
        clock = at(3)
        composeRule.waitForIdle()
        assertEquals(listOf(at(5), at(8)), times(next!!))
        assertEquals(1, next!!.groups.size)
        settle(scheduler)
        assertEquals(listOf(at(5), at(8)), times(next!!))
        // Two more gone at once (the page back after a while): loading, never a train that's gone,
        // until the worker finds the entry for now.
        clock = at(9)
        composeRule.waitForIdle()
        assertTrue(next!!.pending)
        assertTrue(next!!.trains.isEmpty())
        // A refresh that failed meanwhile is said while it catches up (Codex on #557).
        shown = shown.copy(failed = true)
        composeRule.waitForIdle()
        assertTrue(next!!.pending)
        assertTrue(next!!.failed)
        settle(scheduler)
        assertFalse(next!!.pending)
        assertTrue(next!!.trains.isEmpty())
    }
}
