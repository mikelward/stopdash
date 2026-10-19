package app.stopdash.ui

import app.stopdash.ThreadRecorder
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.BundledStops
import app.stopdash.domain.Coordinates
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.RefusedStops
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StepFree
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripModes
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTime
import app.stopdash.domain.ViaStops
import app.stopdash.domain.WalkingSpeed
import java.io.BufferedReader
import java.io.StringReader
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
import org.junit.Assert.assertNotSame
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
        // The answers via each bus stop towards the destination, by the stop asked via, and those asked.
        var byToward: Map<String, List<TripRoute>> = emptyMap()
        var towardFails: Set<String> = emptySet()
        val towardAsked = mutableListOf<Pair<String, TripTime?>>()
        var threads: ThreadRecorder? = null
        // Each answer waits on this while set, as a slow Planner would.
        var gate: CompletableDeferred<Unit>? = null
        // Each request's destination and the stop it was asked via, and the answers via each stop; one in
        // [notFoundVia] answers with the Planner's 404.
        val vias = mutableListOf<Pair<String, String?>>()
        var byVia: Map<String, List<TripRoute>> = emptyMap()
        var notFoundVia: Set<String> = emptySet()
        override suspend fun journeys(
            from: TripOrigin,
            to: TripDestination,
            speed: WalkingSpeed,
            maxWalk: MaxWalk,
            stepFree: StepFree,
            modes: TripModes,
            at: TripTime?,
            via: String?,
        ): List<TripRoute> {
            threads?.note()
            val key = (to as TripDestination.Stop).id
            synchronized(asked) {
                asked += key to at
                froms += from
                vias += key to via
            }
            gate?.await()
            if (key in failFor) throw TflException.Offline(null)
            if (via != null) {
                if (via in notFoundVia) throw TflException.NotFound(null)
                return byVia[via].orEmpty()
            }
            return byDestination[key].orEmpty()
        }

        override suspend fun quickestVia(
            from: TripOrigin,
            to: TripDestination,
            via: String,
            speed: WalkingSpeed,
            maxWalk: MaxWalk,
            stepFree: StepFree,
            modes: TripModes,
            at: TripTime?,
        ): List<TripRoute> {
            synchronized(towardAsked) { towardAsked += via to at }
            if (via in towardFails) throw TflException.Offline(null)
            return byToward[via].orEmpty()
        }
    }

    private fun model(planner: JourneyPlanner, destinations: List<String> = listOf("C")) =
        LaterTripViewModel(planner, { TripOrigin.Stop("A") }, destinations.map { TripDestination.Stop(it) }, io = dispatcher, compute = dispatcher)

    private val LaterTripViewModel.routes get() = state.value.cards?.map { it.route }

    // The station model's ids for a station picked as C: its other record C2 too, each to the station.
    @Test
    fun `a route riding past another of the station's records is left out where one gets off there no later`() = runTest(dispatcher) {
        val past = TripRoute(listOf(leg("red", "A", "C", 5, 40).copy(path = listOf("C2", "X", "C"))))
        val there = TripRoute(listOf(leg("blue", "A", "C2", 5, 20)))
        val planner = Planner(mapOf("C" to listOf(past, there)))
        val station = mapOf("C" to "S", "C2" to "S")
        val later = LaterTripViewModel(
            planner, { TripOrigin.Stop("A") }, listOf(TripDestination.Stop("C")), io = dispatcher, compute = dispatcher,
            arrivalIds = { station },
        )
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(there), later.routes)
        // Without the model, the other record isn't the destination: both are listed.
        val unmodeled = model(planner)
        unmodeled.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(setOf(past, there), unmodeled.routes!!.toSet())
    }

    // As the trip for now: a station is planned to at every target the model gives it.
    @Test
    fun `a later trip to a station is planned to at every target the model gives it`() = runTest(dispatcher) {
        val there = TripRoute(listOf(leg("blue", "A", "C2", 5, 20)))
        val planner = Planner(mapOf("C2" to listOf(there)))
        val later = LaterTripViewModel(
            planner, { TripOrigin.Stop("A") }, listOf(TripDestination.Stop("C")), io = dispatcher, compute = dispatcher,
            planTargets = { listOf("C", "C2") },
        )
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(setOf("C" to leave, "C2" to leave), planner.asked.toSet())
        assertEquals(listOf(there), later.routes)
    }

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
    fun `a place's routes stand when its request via the last stop fails, said as routes missed`() = runTest(dispatcher) {
        // Two rides, then a walk to the place: the trip asks again via the last ride's stop, and that fails.
        val walk = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "Place D", at(30), at(36))
        val route = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("blue", "B", "C", 20, 30), walk))
        val planner = object : JourneyPlanner {
            override suspend fun journeys(from: TripOrigin, to: TripDestination, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes, at: TripTime?, via: String?) =
                listOf(route)

            override suspend fun fewestChangesVia(from: TripOrigin, to: TripDestination, via: String, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes, at: TripTime?): List<TripRoute> =
                throw TflException.Offline(null)
        }
        val place = TripDestination.Place(Coordinates(51.5, -0.12), "Place D")
        val later = LaterTripViewModel(planner, { TripOrigin.Stop("A") }, listOf(place), io = dispatcher, compute = dispatcher)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(route), later.routes)
        assertTrue(later.state.value.routesMissed)
        // Every station answered: not said as one missed.
        assertFalse(later.state.value.incomplete)
        assertNull(later.state.value.error)
    }

    // Bus stops around the obviously-fake (51.5, -0.12), as the bundled stops file holds them: A (the pair
    // 490G0A) about 200 m south serving the 1, B about 300 m north serving the 2.
    private val bundled = BundledStops.read(
        BufferedReader(
            StringReader(
                listOf(
                    "# stopdash stops v1\tbounds=51.25,-0.65,51.75,0.4",
                    "L\t1\tbus\t1",
                    "L\t2\tbus\t2",
                    "S\t4900000A1\tB\tStop A\t51.498200\t-0.120000\t490G0A\t\tA\tS\t\t1",
                    "S\t4900000B1\tB\tStop B\t51.502700\t-0.120000\t490G0B\t\tB\tN\t\t2",
                ).joinToString("\n"),
            ),
        ),
    )

    // The station X, about 5 km south of the rider: past the reach, so the stops towards it are asked about.
    private val stations = StationIndex(listOf(IndexedStation("X", "X", latitude = 51.455, longitude = -0.12)))

    // The Planner's own route, and what it offers via the stop pair A: the 1 from A to T, then the red line.
    private val own = TripRoute(listOf(leg("red", "S", "X", 10, 45)))
    private val busToTrain = TripRoute(
        listOf(
            TripLeg(TripLeg.WALKING, "", "", "", "", "4900000A1", "Stop A", at(1), at(4)),
            leg("1", "4900000A1", "T", 5, 12, mode = "bus").copy(fromArea = "490G0A"),
            leg("red", "T", "X", 15, 32),
        ),
    )

    private fun fromHere(
        planner: JourneyPlanner,
        warnings: MutableList<String> = mutableListOf(),
        origin: TripOrigin = TripOrigin.Here(Coordinates(51.5, -0.12)),
        stops: BundledStops = bundled,
    ) = LaterTripViewModel(
        planner, { origin }, listOf(TripDestination.Stop("X")), io = dispatcher, compute = dispatcher,
        warn = { warnings += it }, stations = { stations }, bundledStops = { stops }, refused = RefusedStops(),
    )

    @Test
    fun `a later trip from here asks via each bus stop towards the destination, at its time, and lists the bus`() = runTest(dispatcher) {
        val planner = Planner(mapOf("X" to listOf(own))).apply { byToward = mapOf("490G0A" to listOf(busToTrain)) }
        val warnings = mutableListOf<String>()
        val later = fromHere(planner, warnings)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        // A serves the 1 and lies towards X; B is behind the rider. Asked for the time picked, as the plan is.
        assertEquals(listOf("490G0A" to leave), planner.towardAsked)
        assertEquals(listOf(busToTrain.copy(towardStop = true), own), later.routes)
        assertFalse(later.state.value.incomplete)
        assertTrue(warnings.toString(), warnings.contains("later trip plan via stops towards the destination: 1 asked"))
        assertTrue(warnings.toString(), warnings.contains("later trip plan via a stop towards the destination: 1 of 1 routes ride a bus from there"))
        // The log says how many, never where or when.
        assertTrue(warnings.toString(), warnings.none { "490G0A" in it || "4900000A1" in it || "09:00" in it || "2026" in it })
    }

    @Test
    fun `a failed request via a stop towards the destination leaves the plan standing, said as routes missed`() = runTest(dispatcher) {
        val planner = Planner(mapOf("X" to listOf(own))).apply { towardFails = setOf("490G0A") }
        val later = fromHere(planner)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(own), later.routes)
        assertTrue(later.state.value.routesMissed)
        assertFalse(later.state.value.incomplete)
        assertNull(later.state.value.error)
    }

    @Test
    fun `no bundled stops asks via none and says so in the log, the plan standing as complete`() = runTest(dispatcher) {
        // Read once a process, so a Retry couldn't bring them: no incomplete notice the rider can't clear.
        val planner = Planner(mapOf("X" to listOf(own))).apply { byToward = mapOf("490G0A" to listOf(busToTrain)) }
        val warnings = mutableListOf<String>()
        val later = fromHere(planner, warnings, stops = BundledStops.EMPTY)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertTrue(planner.towardAsked.isEmpty())
        assertEquals(listOf(own), later.routes)
        assertFalse(later.state.value.incomplete)
        assertFalse(later.state.value.routesMissed)
        assertTrue(warnings.toString(), warnings.contains("later trip plan via stops towards the destination: no bundled stops"))
    }

    @Test
    fun `the stops towards the destination add to a plan, never make one`() = runTest(dispatcher) {
        // The plan's own request failed: a bus found via a stop is no plan on its own.
        val planner = Planner(emptyMap()).apply {
            failFor = setOf("X")
            byToward = mapOf("490G0A" to listOf(busToTrain))
        }
        val later = fromHere(planner)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertNull(later.routes)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, later.state.value.error)
    }

    @Test
    fun `a hidden bus line's stop is still asked, so showing the line again lists its bus without planning again`() = runTest(dispatcher) {
        val planner = Planner(mapOf("X" to listOf(own))).apply { byToward = mapOf("490G0A" to listOf(busToTrain)) }
        val later = fromHere(planner)
        later.show(LaterTripViewModel.Request(leave, avoidedLines = setOf(AvoidedLines.key("1", "1"))))
        advanceUntilIdle()
        assertEquals(listOf("490G0A" to leave), planner.towardAsked)
        assertEquals(listOf(own), later.routes)
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(listOf(busToTrain.copy(towardStop = true), own), later.routes)
        assertEquals(1, planner.asked.size)
        assertEquals(1, planner.towardAsked.size)
    }

    @Test
    fun `a later trip from a stop asks via no stop towards the destination`() = runTest(dispatcher) {
        val planner = Planner(mapOf("X" to listOf(own))).apply { byToward = mapOf("490G0A" to listOf(busToTrain)) }
        val later = fromHere(planner, origin = TripOrigin.Stop("A"))
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertTrue(planner.towardAsked.isEmpty())
        assertEquals(listOf(own), later.routes)
    }

    @Test
    fun `a recreated screen asking with what it held gets the request the state answers, so its cards show at once`() = runTest(dispatcher) {
        val route = TripRoute(listOf(leg("red", "A", "C", 5, 30)))
        val later = model(Planner(mapOf("C" to listOf(route))))
        val hidden = setOf("bus")
        val avoided = setOf(AvoidedLines.key("73", "73"))
        val modes = TripModes.DEFAULT
        fun asked(time: TripTime = leave, speed: WalkingSpeed = WalkingSpeed.AVERAGE, hiddenModes: Set<String> = hidden) =
            later.request(time, speed, MaxWalk.DEFAULT, StepFree.DEFAULT, modes, hiddenModes, avoided)
        val first = asked()
        later.show(first)
        advanceUntilIdle()
        assertEquals(listOf(route), later.routes)
        // The same parts, the sets the same objects (as a rotation finds them): the request the cards answer.
        assertSame(later.state.value.request, asked())
        // Anything else is a request of its own: another time or walk, or a set that's equal but another object,
        // told apart without comparing its contents.
        assertNotSame(first, asked(time = leave.copy(at = leave.at.plusHours(1))))
        assertNotSame(first, asked(speed = WalkingSpeed.FAST))
        assertNotSame(first, asked(hiddenModes = hashSetOf("bus")))
    }

    @Test
    fun `before any plan a request is new`() = runTest(dispatcher) {
        val later = model(Planner(emptyMap()))
        val request = later.request(leave, WalkingSpeed.AVERAGE, MaxWalk.DEFAULT, StepFree.DEFAULT, TripModes.DEFAULT, emptySet(), emptySet())
        assertEquals(leave, request.time)
        assertNull(later.state.value.request)
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

    @Test
    fun `a later trip via a stop asks every destination via each of its stations`() = runTest(dispatcher) {
        val throughV1 = TripRoute(listOf(leg("red", "A", "V1", 5, 12), leg("blue", "V1", "C", 15, 25)))
        val throughV2 = TripRoute(listOf(leg("green", "A", "V2", 4, 11), leg("brown", "V2", "C", 14, 27)))
        val planner = Planner(mapOf("C" to listOf(TripRoute(listOf(leg("pink", "A", "C", 5, 20)))))).apply {
            byVia = mapOf("V1" to listOf(throughV1), "V2" to listOf(throughV2))
        }
        val later = LaterTripViewModel(
            planner, { TripOrigin.Stop("A") }, listOf(TripDestination.Stop("C")),
            io = dispatcher, compute = dispatcher, via = ViaStops(listOf("V1", "V2")),
        )
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(setOf("C" to "V1", "C" to "V2"), planner.vias.toSet())
        assertEquals(2, planner.vias.size)
        assertEquals(setOf(throughV1, throughV2), later.routes.orEmpty().toSet())
    }

    @Test
    fun `a later trip with no route through its stop lists none, not a failure`() = runTest(dispatcher) {
        val planner = Planner(emptyMap()).apply { notFoundVia = setOf("V1") }
        val later = LaterTripViewModel(planner, { TripOrigin.Stop("A") }, listOf(TripDestination.Stop("C")), io = dispatcher, compute = dispatcher, via = ViaStops(listOf("V1")))
        later.show(LaterTripViewModel.Request(leave))
        advanceUntilIdle()
        assertEquals(emptyList<TripRoute>(), later.routes)
        assertNull(later.state.value.error)
    }
}
