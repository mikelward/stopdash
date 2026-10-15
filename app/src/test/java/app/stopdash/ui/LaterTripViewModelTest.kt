package app.stopdash.ui

import app.stopdash.ThreadRecorder
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.Coordinates
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripModes
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTime
import app.stopdash.domain.WalkingSpeed
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic stops and lines only. */
@OptIn(ExperimentalCoroutinesApi::class)
class LaterTripViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val leave = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 17, 9, 0))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun at(minutes: Long) = Instant.parse("2026-10-17T08:00:00Z").plus(Duration.ofMinutes(minutes))

    private fun leg(lineId: String, from: String, to: String, departs: Long, arrives: Long, mode: String = "tube") = TripLeg(
        mode = mode, lineId = lineId, lineName = lineId, fromId = from, fromName = from, toId = to, toName = to,
        departure = at(departs), arrival = at(arrives), path = listOf(to),
    )

    private class Planner(val byDestination: Map<String, List<TripRoute>>) : JourneyPlanner {
        val asked = mutableListOf<Pair<String, TripTime?>>()
        val froms = mutableListOf<TripOrigin>()
        var failFor: Set<String> = emptySet()
        var threads: ThreadRecorder? = null
        // Each answer waits on this while set, as a slow Planner would.
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun journeys(
            from: TripOrigin,
            to: TripDestination,
            speed: WalkingSpeed,
            maxWalk: MaxWalk,
            stepFree: StepFree,
            modes: TripModes,
            at: TripTime?,
        ): List<TripRoute> {
            threads?.note()
            val key = (to as TripDestination.Stop).id
            synchronized(asked) {
                asked += key to at
                froms += from
            }
            gate?.await()
            if (key in failFor) throw TflException.Offline(null)
            return byDestination[key].orEmpty()
        }
    }

    private fun model(planner: JourneyPlanner, destinations: List<String> = listOf("C")) =
        LaterTripViewModel(planner, { TripOrigin.Stop("A") }, destinations.map { TripDestination.Stop(it) }, io = dispatcher, compute = dispatcher)

    private val LaterTripViewModel.routes get() = state.value.cards?.map { it.route }

    @Test
    fun `plans every stop for the time picked and lists the soonest there first`() = runTest(dispatcher) {
        val late = TripRoute(listOf(leg("red", "A", "C", 5, 40)))
        val soon = TripRoute(listOf(leg("blue", "A", "C2", 10, 30)))
        val planner = Planner(mapOf("C" to listOf(late), "C2" to listOf(soon)))
        val later = model(planner, listOf("C", "C2"))
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(setOf("C" to leave, "C2" to leave), planner.asked.toSet())
        assertEquals(listOf(soon, late), later.routes)
        assertEquals(leave, later.state.value.request?.time)
        assertFalse(later.state.value.planning)
        assertNull(later.state.value.error)
    }

    @Test
    fun `a route leaving before a leave-at time, or arriving after an arrive-by one, isn't listed`() = runTest(dispatcher) {
        // [leave] is 09:00 in London, 08:00 UTC: at(0).
        val before = TripRoute(listOf(leg("red", "A", "C", -5, 20)))
        val after = TripRoute(listOf(leg("blue", "A", "C", 5, 30)))
        val leaving = model(Planner(mapOf("C" to listOf(before, after))))
        leaving.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(after), leaving.routes)
        val arriving = model(Planner(mapOf("C" to listOf(TripRoute(listOf(leg("red", "A", "C", -40, -5))), TripRoute(listOf(leg("blue", "A", "C", -20, 5)))))))
        arriving.show(LaterTripViewModel.Request(leave.copy(kind = TripTime.Kind.ARRIVE)))
        advanceUntilIdle()
        assertEquals(listOf("red"), arriving.routes?.map { it.legs.single().lineId })
    }

    @Test
    fun `arriving by a time lists the latest to leave first`() = runTest(dispatcher) {
        val early = TripRoute(listOf(leg("red", "A", "C", 0, 50)))
        val late = TripRoute(listOf(leg("blue", "A", "C", 20, 55)))
        val later = model(Planner(mapOf("C" to listOf(early, late))))
        // Arriving by 10:00: both get there in time.
        later.show(LaterTripViewModel.Request(TripTime(TripTime.Kind.ARRIVE, leave.at.plusHours(1))))
        advanceUntilIdle()
        assertEquals(listOf(late, early), later.routes)
    }

    @Test
    fun `each card carries its keys and rows, worked out with the plan`() = runTest(dispatcher) {
        val walk = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "Place D", at(30), at(36))
        val route = TripRoute(listOf(leg("red", "A", "C", 5, 30), walk))
        val later = model(Planner(mapOf("C" to listOf(route))))
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        val card = later.state.value.cards!!.single()
        assertEquals(routeKey(route), card.routeKey)
        assertEquals("${routeKey(route)}@${at(5)}-${at(30)},${at(30)}-${at(36)}", card.listKey)
        // 08:05 UTC is 09:05 in London (BST).
        assertEquals("09:05", card.departs)
        assertEquals("09:36", card.arrives)
        assertEquals(31, card.minutes)
        assertEquals(
            listOf(
                LaterTripViewModel.Row(walk = false, time = "09:05", lineId = "red", lineName = "red", mode = "tube", toName = "C", minutes = 25),
                LaterTripViewModel.Row(walk = true, time = "09:30", lineId = "", lineName = "", mode = TripLeg.WALKING, toName = "Place D", minutes = 6),
            ),
            card.rows,
        )
    }

    @Test
    fun `a mode hidden or a line avoided lists the plan again without planning again`() = runTest(dispatcher) {
        val bus = TripRoute(listOf(leg("73", "A", "C", 5, 30, mode = "bus")))
        val tube = TripRoute(listOf(leg("red", "A", "C", 5, 35)))
        val planner = Planner(mapOf("C" to listOf(bus, tube)))
        val later = model(planner)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(bus, tube), later.routes)
        later.show(LaterTripViewModel.Request(leave, hiddenModes = setOf("bus")))
        advanceUntilIdle()
        assertEquals(listOf(tube), later.routes)
        later.show(LaterTripViewModel.Request(leave, avoidedLines = setOf(AvoidedLines.key("red", "red"))))
        advanceUntilIdle()
        assertEquals(listOf(bus), later.routes)
        assertEquals(1, planner.asked.size)
    }

    @Test
    fun `another time, or another walk, plans afresh, and the same request doesn't`() = runTest(dispatcher) {
        val planner = Planner(mapOf("C" to listOf(TripRoute(listOf(leg("red", "A", "C", 5, 30))))))
        val later = model(planner)
        later.show(LaterTripViewModel.Request(leave))
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(1, planner.asked.size)
        val sunday = leave.copy(at = leave.at.plusDays(1))
        later.show(LaterTripViewModel.Request(sunday))
        advanceUntilIdle()
        assertEquals("C" to sunday, planner.asked.last())
        later.show(LaterTripViewModel.Request(sunday, walkingSpeed = WalkingSpeed.FAST))
        advanceUntilIdle()
        assertEquals(3, planner.asked.size)
        assertEquals(sunday, later.state.value.request?.time)
    }

    @Test
    fun `a plan starts from the origin set last, not the one the model was made with`() = runTest(dispatcher) {
        val planner = Planner(mapOf("C" to listOf(TripRoute(listOf(leg("red", "A", "C", 5, 30))))))
        val later = model(planner)
        // A recreated screen hands the surviving model its own composition's fix.
        later.origin = { TripOrigin.Stop("B") }
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf<TripOrigin>(TripOrigin.Stop("B")), planner.froms)
    }

    @Test
    fun `routes leaving together onto different trains are two cards, the same route twice is one`() = runTest(dispatcher) {
        val first = leg("red", "A", "B", 5, 15)
        val sooner = TripRoute(listOf(first, leg("blue", "B", "C", 20, 30)))
        val later = TripRoute(listOf(first, leg("blue", "B", "C", 25, 35)))
        val model = model(Planner(mapOf("C" to listOf(sooner, later, sooner.copy()))))
        model.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        val cards = model.state.value.cards!!
        assertEquals(listOf(sooner, later), cards.map { it.route })
        assertEquals(2, cards.map { it.listKey }.toSet().size)
    }

    @Test
    fun `new choices for the same time take down what the old ones planned until the new plan is in`() = runTest(dispatcher) {
        val route = TripRoute(listOf(leg("red", "A", "C", 5, 30)))
        val planner = Planner(mapOf("C" to listOf(route)))
        val later = model(planner)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(route), later.routes)
        planner.gate = CompletableDeferred()
        later.show(LaterTripViewModel.Request(leave, stepFree = StepFree.entries.last()))
        advanceUntilIdle()
        // Planned under the old step-free level: never shown, nor openable, under the new one.
        assertNull(later.state.value.cards)
        assertTrue(later.state.value.planning)
        planner.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(route), later.routes)
    }

    @Test
    fun `a retry keeps the same request's cards up while it plans`() = runTest(dispatcher) {
        val route = TripRoute(listOf(leg("red", "A", "C", 5, 30)))
        val planner = Planner(mapOf("C" to listOf(route)))
        planner.failFor = setOf("C2")
        val later = model(planner, listOf("C", "C2"))
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertTrue(later.state.value.incomplete)
        planner.gate = CompletableDeferred()
        later.retry()
        advanceUntilIdle()
        assertTrue(later.state.value.planning)
        assertEquals(listOf(route), later.routes)
        // The notice over them stays too, so the cards it sits above don't move.
        assertTrue(later.state.value.incomplete)
        planner.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(later.state.value.planning)
    }

    @Test
    fun `a retry after a failure keeps saying why while it plans`() = runTest(dispatcher) {
        val planner = Planner(mapOf("C" to listOf(TripRoute(listOf(leg("red", "A", "C", 5, 30))))))
        planner.failFor = setOf("C")
        val later = model(planner)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        planner.failFor = emptySet()
        planner.gate = CompletableDeferred()
        later.retry()
        advanceUntilIdle()
        assertTrue(later.state.value.planning)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, later.state.value.error)
        planner.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(later.state.value.error)
    }

    @Test
    fun `a request is only ever itself, and the state answers the very one handed on last`() = runTest(dispatcher) {
        val route = TripRoute(listOf(leg("red", "A", "C", 5, 30)))
        val later = model(Planner(mapOf("C" to listOf(route))))
        val first = LaterTripViewModel.Request(leave)
        // Equal parts, another request: the screen tells them apart by reference, never by walking sets.
        val again = LaterTripViewModel.Request(leave)
        assertNotEquals(first, again)
        later.show(first)
        advanceUntilIdle()
        assertSame(first, later.state.value.request)
        // A request asked again with the same parts plans nothing new, and the state answers it.
        later.show(LaterTripViewModel.Request(leave, hiddenModes = setOf("bus")))
        later.show(again)
        advanceUntilIdle()
        assertSame(again, later.state.value.request)
        assertEquals(listOf(route), later.routes)
    }

    @Test
    fun `what the state answers is always the request handed on last, every choice in it`() = runTest(dispatcher) {
        val bus = TripRoute(listOf(leg("73", "A", "C", 5, 30, mode = "bus")))
        val tube = TripRoute(listOf(leg("red", "A", "C", 5, 35)))
        val planner = Planner(mapOf("C" to listOf(bus, tube)))
        planner.gate = CompletableDeferred()
        val later = model(planner)
        val first = LaterTripViewModel.Request(leave)
        later.show(first)
        advanceUntilIdle()
        assertEquals(first, later.state.value.request)
        // A mode hidden while the plan is still out: the same plan, answering the new request once it's in.
        val hidden = LaterTripViewModel.Request(leave, hiddenModes = setOf("bus"))
        later.show(hidden)
        advanceUntilIdle()
        assertEquals(hidden, later.state.value.request)
        assertTrue(later.state.value.planning)
        planner.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(hidden, later.state.value.request)
        assertEquals(listOf(tube), later.routes)
        assertEquals(1, planner.asked.size)
        // New choices: the old request's routes no longer answer the one handed on.
        val stairFree = hidden.copy(stepFree = StepFree.entries.last())
        planner.gate = CompletableDeferred()
        later.show(stairFree)
        advanceUntilIdle()
        assertEquals(stairFree, later.state.value.request)
        assertNull(later.state.value.cards)
        planner.gate!!.complete(Unit)
    }

    @Test
    fun `no stop answering is a failure with nothing listed, some is an incomplete plan`() = runTest(dispatcher) {
        val route = TripRoute(listOf(leg("red", "A", "C", 5, 30)))
        val planner = Planner(mapOf("C" to listOf(route)))
        planner.failFor = setOf("C", "C2")
        val failed = model(planner, listOf("C", "C2"))
        failed.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertNull(failed.routes)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, failed.state.value.error)
        planner.failFor = setOf("C2")
        val partial = model(planner, listOf("C", "C2"))
        partial.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(route), partial.routes)
        assertTrue(partial.state.value.incomplete)
        assertNull(partial.state.value.error)
    }

    @Test
    fun `a place's routes stand when its request via the last stop fails, said as an incomplete plan`() = runTest(dispatcher) {
        // Two rides, then a walk to the place: the trip asks again via the last ride's stop, and that fails.
        val walk = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "Place D", at(30), at(36))
        val route = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("blue", "B", "C", 20, 30), walk))
        val planner = object : JourneyPlanner {
            override suspend fun journeys(from: TripOrigin, to: TripDestination, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes, at: TripTime?) =
                listOf(route)

            override suspend fun fewestChangesVia(from: TripOrigin, to: TripDestination, via: String, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes, at: TripTime?): List<TripRoute> =
                throw TflException.Offline(null)
        }
        val place = TripDestination.Place(Coordinates(51.5, -0.12), "Place D")
        val later = LaterTripViewModel(planner, { TripOrigin.Stop("A") }, listOf(place), io = dispatcher, compute = dispatcher)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(route), later.routes)
        assertTrue(later.state.value.incomplete)
        assertNull(later.state.value.error)
    }

    @Test
    fun `a retry plans the same request again`() = runTest(dispatcher) {
        val planner = Planner(mapOf("C" to listOf(TripRoute(listOf(leg("red", "A", "C", 5, 30))))))
        planner.failFor = setOf("C")
        val later = model(planner)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertNotNull(later.state.value.error)
        planner.failFor = emptySet()
        later.retry()
        advanceUntilIdle()
        assertEquals(listOf("C" to leave, "C" to leave), planner.asked)
        assertEquals(1, later.routes?.size)
        assertNull(later.state.value.error)
    }

    @Test
    fun `a request handed on from the caller's thread is planned and listed off it`() {
        // A real single-thread caller standing in for the main thread, as the screen's effect is.
        val main = Executors.newSingleThreadExecutor { Thread(it, "caller") }
        val io = Executors.newSingleThreadExecutor { Thread(it, "io") }
        val mainDispatcher = main.asCoroutineDispatcher()
        // The worker, noting each block it runs on its own thread.
        val workerThreads = ThreadRecorder()
        val workerPool = Executors.newSingleThreadExecutor { Thread(it, "worker") }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                workerPool.execute {
                    workerThreads.note()
                    block.run()
                }
            }
        }
        Dispatchers.setMain(mainDispatcher)
        try {
            val planner = Planner(mapOf("C" to listOf(TripRoute(listOf(leg("red", "A", "C", 5, 30))))))
            planner.threads = ThreadRecorder()
            val later = LaterTripViewModel(
                planner, { TripOrigin.Here(Coordinates(51.5, -0.12)) }, listOf(TripDestination.Stop("C")),
                io = io.asCoroutineDispatcher(), compute = worker,
            )
            main.submit { later.show(LaterTripViewModel.Request(leave, hiddenModes = setOf("bus"), avoidedLines = setOf("73"))) }.get()
            runBlocking { withTimeout(5_000) { later.state.first { it.cards != null } } }
            assertEquals(listOf("io"), planner.threads!!.threads())
            assertTrue("${workerThreads.threads()}", workerThreads.threads().isNotEmpty() && workerThreads.threads().all { it == "worker" })
        } finally {
            Dispatchers.setMain(dispatcher)
            main.shutdown()
            io.shutdown()
            workerPool.shutdown()
        }
    }
}
