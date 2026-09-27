package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stopdash.R
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.Coordinates
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.StopGroup
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RideLines
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteStops
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic stops and lines only. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now: Instant = Instant.parse("2026-09-26T08:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun at(minutes: Long) = Instant.parse("2026-09-26T08:00:00Z").plus(Duration.ofMinutes(minutes))

    private fun leg(lineId: String, from: String, to: String, departs: Long, arrives: Long) = TripLeg(
        mode = "tube",
        lineId = lineId,
        lineName = lineId,
        fromId = from,
        fromName = from,
        toId = to,
        toName = to,
        departure = at(departs),
        arrival = at(arrives),
        path = listOf(to),
    )

    private val route = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("blue", "B", "C", 20, 30)))

    private fun train(lineId: String, destination: String, inMinutes: Long) = Departure(
        lineId = lineId,
        lineName = lineId,
        direction = "outbound",
        destination = destination,
        platform = null,
        expectedArrival = at(inMinutes),
        mode = "tube",
    )

    private class FakePlanner(var routes: List<TripRoute>) : JourneyPlanner {
        var calls = 0
        var failWith: TflException? = null
        // Per destination, where a test plans to several; else [routes] for any.
        var byDestination: Map<String, List<TripRoute>> = emptyMap()
        var failFor: Set<String> = emptySet()
        var delays: Map<String, Long> = emptyMap()
        override suspend fun journeys(fromId: String, to: TripDestination): List<TripRoute> {
            calls++
            // Keyed by the stop id (or a place's name), matching how these tests plan by destination.
            val key = when (to) {
                is TripDestination.Stop -> to.id
                is TripDestination.Place -> to.name
            }
            delays[key]?.let { delay(it) }
            failWith?.let { throw it }
            if (key in failFor) throw TflException.Offline(null)
            return byDestination[key] ?: routes
        }
    }

    private class FakeClient(val arrivals: MutableMap<String, List<Departure>>) : TflClient {
        val asked = mutableListOf<String>()
        var failStops = emptySet<String>()
        var failStatus = false
        override suspend fun arrivals(stopId: String): List<Departure> {
            asked += stopId
            if (stopId in failStops) throw TflException.Offline(null)
            return arrivals[stopId].orEmpty()
        }
        var omitLines = emptySet<String>()
        var statusChecks = 0
        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            statusChecks++
            if (failStatus) throw TflException.Offline(null)
            return lineIds.filterNot { it in omitLines }.map { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
        }
        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
    }

    private fun model(
        planner: JourneyPlanner,
        client: TflClient,
        plans: TripPlans = TripPlans(),
        toIds: List<String> = listOf("C"),
        arrivals: ArrivalsCache = ArrivalsCache(),
    ) = TripViewModel(
        planner, client, "A", toIds.map { TripDestination.Stop(it) },
        clock = { now }, plans = plans, io = dispatcher, arrivals = arrivals,
    )

    @Test
    fun `the open route outlasts the process and is forgotten with the trip`() {
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        val trip = ViewModelProvider.create(
            store,
            viewModelFactory {
                initializer { TripViewModel(FakePlanner(listOf(route)), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")), io = dispatcher, savedState = saved) }
            },
        )[TripViewModel::class]
        trip.openRoute.value = "red>blue"
        // A recreated process restores the handle: the new model opens the same route.
        val restored = TripViewModel(
            FakePlanner(listOf(route)), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")), io = dispatcher,
            savedState = SavedStateHandle(mapOf("openRoute" to saved.get<String>("openRoute"))),
        )
        assertEquals("red>blue", restored.openRoute.value)
        // A trip let go takes its route with it, so the next trip sharing the handle opens none.
        store.clear()
        assertNull(saved.get<String>("openRoute"))
    }

    @Test
    fun `a trip's alert dismissals are the shared store's, and a failed one is said`() = runTest(dispatcher) {
        val stored = MutableStateFlow(emptySet<DismissedAlert>())
        var failing = false
        val store = object : DismissedAlertsStore {
            override fun dismissed() = stored
            override suspend fun dismiss(alert: DismissedAlert) {
                if (failing) throw java.io.IOException("disk full")
                stored.value = stored.value + alert
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
        val failures = WriteFailures()
        val trip = TripViewModel(
            FakePlanner(listOf(route)), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")), io = dispatcher,
            dismissedStore = store, writeFailures = failures,
        )
        val delayed = LineStatus("blue", 9, "Minor Delays")
        val row = legStatusRow(TripViewModel.State(statuses = mapOf("blue" to delayed)), route.legs[1], now)
        trip.dismissAlert(row)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofLineStatus(delayed)), trip.dismissed.value)
        failing = true
        trip.dismissAlert(row.copy(status = delayed.copy(description = "Severe Delays", severity = 6)))
        advanceUntilIdle()
        // Shared with the list's models, so whichever screen shows next says so.
        assertTrue(trip.dismissWriteFailed.value)
        assertTrue(failures.dismiss.value)
        trip.dismissWriteFailureShown()
        assertFalse(trip.dismissWriteFailed.value)
    }

    @Test
    fun `a boarding stop another screen just fetched shows at once and isn't asked for again`() = runTest(dispatcher) {
        val cache = ArrivalsCache()
        val cached = listOf(train("red", "B", 6))
        cache.put("A", cached, now.minusSeconds(20))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9)), "B" to listOf(train("blue", "C", 20))))
        val trip = model(FakePlanner(listOf(route)), client, arrivals = cache)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(TripViewModel.StopLive(cached, now.minusSeconds(20)), trip.state.value.live["A"])
        assertEquals(listOf("B"), client.asked)
        // What the trip fetched is there for the other screens.
        assertEquals(now, cache.get("B", now)?.fetchedAt)
    }

    @Test
    fun `a National Rail key change drops a kept trip's times and fetches them when it's shown again`() = runTest(dispatcher) {
        val key = MutableStateFlow<String?>(null)
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9))))
        val trip = TripViewModel(
            FakePlanner(listOf(route)), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, plans = TripPlans(), io = dispatcher,
            departureSourceChanges = key,
        )
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(2, client.asked.size)
        key.value = "EXAMPLE"
        advanceUntilIdle()
        // Nothing fetched under the old key stays up, and nothing is fetched while the trip is away.
        assertTrue(trip.state.value.live.isEmpty())
        assertEquals(2, client.asked.size)
        // Shown again for the same re-pick, it fetches afresh.
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(4, client.asked.size)
    }

    @Test
    fun `a trip's fetch still out when the National Rail key changes isn't shown`() = runTest(dispatcher) {
        val key = MutableStateFlow<String?>(null)
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient by FakeClient(mutableMapOf()) {
            override suspend fun arrivals(stopId: String): List<Departure> {
                gate.await()
                return listOf(train("red", "B", 9))
            }
        }
        val trip = TripViewModel(
            FakePlanner(listOf(route)), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, plans = TripPlans(), io = dispatcher,
            departureSourceChanges = key,
        )
        trip.refresh()
        runCurrent()
        key.value = "EXAMPLE"
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(trip.state.value.live.isEmpty())
    }

    @Test
    fun `a boarding stop fetched after the clock, set back since, is asked for again`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9))))
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("A", "B"), client.asked.toSet())
        now = now.minusSeconds(120)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(4, client.asked.size)
        assertEquals(now, trip.state.value.live.getValue("A").fetchedAt)
    }

    @Test
    fun `a kept trip shown again takes newer arrivals another screen fetched meanwhile`() = runTest(dispatcher) {
        val cache = ArrivalsCache()
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9))))
        val trip = model(FakePlanner(listOf(route)), client, arrivals = cache)
        trip.refreshFor(null)
        advanceUntilIdle()
        val newer = listOf(train("red", "B", 7))
        cache.put("A", newer, now.plusSeconds(20))
        now = now.plusSeconds(30)
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(newer, trip.state.value.live.getValue("A").departures)
        // Without asking TfL again: both stops are under a minute old.
        assertEquals(2, client.asked.size)
        // Shown again two minutes on, with nothing newer to take: the stops are asked for again.
        now = now.plusSeconds(120)
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(4, client.asked.size)
    }

    @Test
    fun `a boarding stop fetched a minute ago or more is asked for again`() = runTest(dispatcher) {
        val cache = ArrivalsCache()
        cache.put("A", listOf(train("red", "B", 6)), now.minus(ArrivalsCache.TTL))
        val fresh = listOf(train("red", "B", 9))
        val client = FakeClient(mutableMapOf("A" to fresh))
        val trip = model(FakePlanner(listOf(route)), client, arrivals = cache)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("A", "B"), client.asked.toSet())
        assertEquals(TripViewModel.StopLive(fresh, now), trip.state.value.live["A"])
    }

    // To a complex's other station, D, by another line.
    private val toD = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("green", "B", "D", 18, 25)))

    @Test
    fun `a complex is planned to each of its stops and the answers merged`() = runTest(dispatcher) {
        val planner = FakePlanner(emptyList()).apply { byDestination = mapOf("C" to listOf(route), "D" to listOf(toD)) }
        val plans = TripPlans()
        val trip = model(planner, FakeClient(mutableMapOf()), plans, toIds = listOf("C", "D"))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        assertEquals(setOf(route, toD), trip.state.value.routes?.toSet())
        assertFalse(trip.state.value.planIncomplete)
        assertEquals(setOf(route, toD), plans.get("A", listOf("C", "D").map { TripDestination.Stop(it) })?.first?.toSet())
    }

    @Test
    fun `plans to a place at a coordinate and fetches no arrivals there`() = runTest(dispatcher) {
        // A route that rides A to B, then walks onto the place — a coordinate arrival, so a blank stop id.
        val toPlace = TripRoute(
            listOf(
                leg("red", "A", "B", 5, 15),
                TripLeg(
                    mode = TripLeg.WALKING, lineId = "", lineName = "",
                    fromId = "B", fromName = "B", toId = "", toName = "Home",
                    departure = at(20), arrival = at(26),
                ),
            ),
        )
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 6))))
        val planner = FakePlanner(listOf(toPlace))
        val trip = TripViewModel(
            planner, client, "A", listOf(TripDestination.Place(Coordinates(51.5, -0.12), "Home")),
            clock = { now }, plans = TripPlans(), io = dispatcher,
        )
        trip.refresh()
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        assertEquals(listOf(toPlace), trip.state.value.routes)
        // Only the ridden boarding stop is asked; the destination coordinate has no stop to fetch (SPEC D9).
        assertEquals(listOf("A"), client.asked)
    }

    @Test
    fun `a place's plan isn't reused after it's renamed`() {
        val plans = TripPlans()
        val coord = Coordinates(51.5, -0.12)
        plans.put("A", listOf(TripDestination.Place(coord, "Home")), listOf(route), now)
        // Renamed, same spot: the cached route's walk leg carries the old label, so it isn't reused.
        assertNull(plans.get("A", listOf(TripDestination.Place(coord, "Mum's"))))
        assertNotNull(plans.get("A", listOf(TripDestination.Place(coord, "Home"))))
    }

    // Planned to the complex's far stop, E, a route that rides through C, one stop on to F, and comes
    // back is no way there when a route gets off at C sooner: the rider would take that one. It's
    // dropped, and the log says how many.
    @Test
    fun `a route through the destination is dropped when another gets off there no later`() = runTest(dispatcher) {
        val onward = leg("red", "B", "F", 18, 30).copy(path = listOf("C", "F"))
        val back = TripRoute(listOf(leg("blue", "A", "B", 5, 15), onward, leg("bus", "F", "E", 33, 40)))
        val planner = FakePlanner(emptyList()).apply { byDestination = mapOf("C" to listOf(route), "E" to listOf(back)) }
        val warnings = mutableListOf<String>()
        val trip = TripViewModel(planner, FakeClient(mutableMapOf()), "A", listOf("C", "E").map { TripDestination.Stop(it) }, warn = { warnings += it }, clock = { now }, plans = TripPlans(), io = dispatcher)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf(route), trip.state.value.shownRoutes(emptySet()))
        assertTrue(warnings.any { it.contains("1 of 2 routes pass the destination") })
        // The plan keeps it: only where it's shown is it left out.
        assertEquals(setOf(route, back), trip.state.value.routes?.toSet())
    }

    // A withheld arrival leaves its reason in the log once, not on every minute tick; a new reason,
    // or the route withheld again after it showed, is logged again.
    @Test
    fun `a withheld arrival is logged once per reason`() {
        val warnings = mutableListOf<String>()
        val trip = TripViewModel(FakePlanner(listOf(route)), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")), warn = { warnings += it }, clock = { now }, plans = TripPlans(), io = dispatcher)
        fun why(reason: TripTiming.Reason, predictions: Int = 2) =
            TripTiming.Withheld(1, "bus", "9", reason, predictions, Duration.ofMinutes(4), null, Duration.ofMinutes(3))
        trip.noteWithheld(mapOf("r" to why(TripTiming.Reason.INFREQUENT)))
        trip.noteWithheld(mapOf("r" to why(TripTiming.Reason.INFREQUENT, predictions = 3)))
        assertEquals(listOf("trip arrival withheld: leg 2 (bus 9): infrequent, 2 predicted, last 4 min before reach; Planner's missed by 3 min"), warnings)
        trip.noteWithheld(mapOf("r" to why(TripTiming.Reason.NO_LIVE)))
        trip.noteWithheld(mapOf("r" to null))
        trip.noteWithheld(mapOf("r" to why(TripTiming.Reason.NO_LIVE)))
        assertEquals(3, warnings.size)
    }

    // A bus pole planned to keeps its stop area as its stop: the planning target doesn't make it a
    // stop of its own, apart from the sibling pole across the road.
    @Test
    fun `a planned pole stays one stop with its sibling`() {
        val trip = TripViewModel(
            FakePlanner(emptyList()), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("P1")), clock = { now }, plans = TripPlans(), io = dispatcher,
            destinationIds = mapOf("P1" to "G", "P2" to "G", "G" to "G"),
        )
        assertEquals("G", trip.state.value.destinationStops["P1"])
    }

    // The only route beating the detour rides a mode the rider hid: the detour is all they can see,
    // so it stays, and comes and goes with the setting without a re-plan.
    @Test
    fun `a hidden route doesn't take out a detour the rider can see`() = runTest(dispatcher) {
        val onward = leg("red", "B", "F", 18, 30).copy(path = listOf("C", "F"))
        val back = TripRoute(listOf(leg("blue", "A", "B", 5, 15), onward, leg("bus", "F", "E", 33, 40)))
        val byBus = TripRoute(listOf(leg("25", "A", "C", 5, 20).copy(mode = "bus")))
        val planner = FakePlanner(emptyList()).apply { byDestination = mapOf("C" to listOf(byBus), "E" to listOf(back)) }
        val client = FakeClient(mutableMapOf())
        val trip = TripViewModel(planner, client, "A", listOf("C", "E").map { TripDestination.Stop(it) }, clock = { now }, plans = TripPlans(), io = dispatcher)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf(byBus), trip.state.value.shownRoutes(emptySet()))
        assertEquals(listOf(back), trip.state.value.shownRoutes(setOf("bus")))
        // Hiding buses brings the detour back: its change stops' times are fetched at once.
        assertTrue("B" !in client.asked)
        trip.hiddenModes = setOf("bus")
        advanceUntilIdle()
        assertTrue("B" in client.asked)
    }

    // A detour another route beats is never fetched for, so a return to the screen right after a
    // refresh fetches nothing again.
    @Test
    fun `a return to the screen doesn't refetch for a route that isn't shown`() = runTest(dispatcher) {
        val onward = leg("red", "B", "F", 18, 30).copy(path = listOf("C", "F"))
        val back = TripRoute(listOf(leg("blue", "A", "B", 5, 15), onward, leg("bus", "F", "E", 33, 40)))
        val direct = TripRoute(listOf(leg("green", "D", "C", 5, 20)))
        val planner = FakePlanner(emptyList()).apply { byDestination = mapOf("C" to listOf(direct), "E" to listOf(back)) }
        val client = FakeClient(mutableMapOf())
        val trip = TripViewModel(planner, client, "A", listOf("C", "E").map { TripDestination.Stop(it) }, clock = { now }, plans = TripPlans(), io = dispatcher)
        trip.refreshFor(null)
        advanceUntilIdle()
        assertTrue("B" !in client.asked)
        val asked = client.asked.size
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(asked, client.asked.size)
    }

    // Six bus routes fill the routes timed; hiding buses lets the seventh, a tube, in, and its stop
    // is fetched at once, though it was in the plan (and shown) all along.
    @Test
    fun `hiding a mode fetches a route it lets into the soonest few`() = runTest(dispatcher) {
        val buses = (1..6).map { TripRoute(listOf(leg("b$it", "S$it", "C", 5, 10L + it).copy(mode = "bus"))) }
        val tube = TripRoute(listOf(leg("red", "T", "C", 5, 30)))
        val client = FakeClient(mutableMapOf())
        val trip = TripViewModel(
            FakePlanner(buses + tube), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, plans = TripPlans(), io = dispatcher,
        )
        trip.refresh()
        advanceUntilIdle()
        assertTrue("T" !in client.asked)
        trip.hiddenModes = setOf("bus")
        advanceUntilIdle()
        assertTrue("T" in client.asked)
    }

    // The route getting off at C arrives after the detour gets to E: the detour is the quicker way
    // there, so both stand.
    @Test
    fun `a route through the destination stays when the one getting off there is later`() = runTest(dispatcher) {
        val onward = leg("red", "B", "F", 18, 22).copy(path = listOf("C", "F"))
        val back = TripRoute(listOf(leg("blue", "A", "B", 5, 15), onward, leg("bus", "F", "E", 23, 26)))
        val planner = FakePlanner(emptyList()).apply { byDestination = mapOf("C" to listOf(route), "E" to listOf(back)) }
        val trip = TripViewModel(planner, FakeClient(mutableMapOf()), "A", listOf("C", "E").map { TripDestination.Stop(it) }, clock = { now }, plans = TripPlans(), io = dispatcher)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf(route, back), trip.state.value.routes?.toSet())
    }

    // A complex's second bus stop, G, isn't planned to (one bus stop stands for them all), so no
    // route gets off there: a detour through it may be the only way the plan found, and it stays.
    @Test
    fun `a route through a destination stop nothing else gets off at stays`() = runTest(dispatcher) {
        val onward = leg("red", "B", "F", 18, 30).copy(path = listOf("G", "F"))
        val back = TripRoute(listOf(leg("blue", "A", "B", 5, 15), onward, leg("bus", "F", "E", 33, 40)))
        val planner = FakePlanner(emptyList()).apply { byDestination = mapOf("C" to listOf(route), "E" to listOf(back)) }
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf("C", "E").map { TripDestination.Stop(it) }, clock = { now }, plans = TripPlans(), io = dispatcher,
            destinationIds = listOf("C", "E", "G").associateWith { it },
        )
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf(route, back), trip.state.value.routes?.toSet())
    }

    @Test
    fun `a stop that fails leaves the others' routes, says so, and isn't kept for reuse`() = runTest(dispatcher) {
        val planner = FakePlanner(emptyList()).apply {
            byDestination = mapOf("C" to listOf(route), "D" to listOf(toD))
            failFor = setOf("D")
        }
        val plans = TripPlans()
        val trip = model(planner, FakeClient(mutableMapOf()), plans, toIds = listOf("C", "D"))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf(route), trip.state.value.routes)
        assertTrue(trip.state.value.planIncomplete)
        assertNull(trip.state.value.planError)
        assertNull(plans.get("A", listOf("C", "D").map { TripDestination.Stop(it) }))
    }

    @Test
    fun `a stop with no routes answering first doesn't say there are none while others plan`() = runTest(dispatcher) {
        val planner = FakePlanner(emptyList()).apply {
            byDestination = mapOf("C" to emptyList(), "D" to listOf(toD))
            delays = mapOf("D" to 1_000)
        }
        val trip = model(planner, FakeClient(mutableMapOf()), toIds = listOf("C", "D"))
        trip.refresh()
        advanceTimeBy(500)
        runCurrent()
        assertNull(trip.state.value.routes)
        assertTrue(trip.state.value.planning)
        advanceUntilIdle()
        assertEquals(listOf(toD), trip.state.value.routes)
    }

    @Test
    fun `a stop answering with no route while another fails is a partial plan, not a failure`() = runTest(dispatcher) {
        val planner = FakePlanner(emptyList()).apply {
            byDestination = mapOf("C" to emptyList())
            failFor = setOf("D")
        }
        val trip = model(planner, FakeClient(mutableMapOf()), toIds = listOf("C", "D"))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(emptyList<TripRoute>(), trip.state.value.routes)
        assertTrue(trip.state.value.planIncomplete)
        assertNull(trip.state.value.planError)
    }

    @Test
    fun `hidden modes' routes don't crowd the shown ones out of the cap`() {
        val buses = (0 until TripViewModel.MAX_ROUTES).map { i ->
            TripRoute(listOf(leg("bus$i", "A", "C", 5, 10L + i).copy(mode = "bus")))
        }
        val tube = TripRoute(listOf(leg("red", "A", "C", 5, 40)))
        val state = TripViewModel.State(routes = buses + tube)
        assertEquals(listOf(tube), tripEstimates(state, now, Duration.ZERO, emptyMap(), hidden = setOf("bus"))?.map { it.route })
    }

    @Test
    fun `only the timed routes' lines load route data`() {
        val routes = (0..TripViewModel.MAX_ROUTES).map { i -> TripRoute(listOf(leg("line$i", "A", "C", 5, 10L + i))) }
        val lines = timedLineIds(routes, emptySet())
        assertEquals(TripViewModel.MAX_ROUTES, lines.size)
        assertFalse("line${TripViewModel.MAX_ROUTES}" in lines)
    }

    @Test
    fun `route data loads for a plan once it settles, not for each answer as it lands`() {
        val landing = TripViewModel.State(routes = listOf(route), planning = true)
        assertEquals(listOf("old"), sequenceLineIds(landing, emptySet(), settled = listOf("old")))
        assertEquals(listOf("red", "blue"), sequenceLineIds(landing.copy(planning = false), emptySet(), settled = listOf("old")))
        // A re-plan keeps the last plan until it lands whole: its lines load at once, even with none
        // settled (a screen shown again over a retained trip).
        val replanning = landing.copy(plannedAt = now)
        assertEquals(listOf("red", "blue"), sequenceLineIds(replanning, emptySet(), settled = emptyList()))
    }

    @Test
    fun `a re-plan keeps the last plan until every stop has answered`() = runTest(dispatcher) {
        val planner = FakePlanner(emptyList()).apply { byDestination = mapOf("C" to listOf(toD), "D" to listOf(route)) }
        val trip = model(planner, FakeClient(mutableMapOf()), toIds = listOf("C", "D"))
        trip.refresh()
        advanceUntilIdle()
        // C answers at once, D a second later: the last plan stands meanwhile, not C's routes alone.
        planner.delays = mapOf("D" to 1_000)
        trip.retry()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(trip.state.value.planning)
        assertEquals(setOf(route, toD), trip.state.value.routes?.toSet())
        advanceUntilIdle()
        assertFalse(trip.state.value.planning)
    }

    @Test
    fun `a retry's first routes clear the failed plan's error`() = runTest(dispatcher) {
        val planner = FakePlanner(emptyList()).apply { failFor = setOf("C", "D") }
        val trip = model(planner, FakeClient(mutableMapOf()), toIds = listOf("C", "D"))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, trip.state.value.planError)
        planner.failFor = emptySet()
        planner.byDestination = mapOf("C" to listOf(route), "D" to listOf(toD))
        planner.delays = mapOf("D" to 1_000)
        trip.retry()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(route), trip.state.value.routes)
        assertNull(trip.state.value.planError)
        advanceUntilIdle()
    }

    @Test
    fun `every stop failing is a failed plan`() = runTest(dispatcher) {
        val planner = FakePlanner(emptyList()).apply { failFor = setOf("C", "D") }
        val trip = model(planner, FakeClient(mutableMapOf()), toIds = listOf("C", "D"))
        trip.refresh()
        advanceUntilIdle()
        assertNull(trip.state.value.routes)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, trip.state.value.planError)
    }

    @Test
    fun `the merged plan keeps the routes arriving soonest, with their variants`() {
        val routes = (0 until TripViewModel.MAX_ROUTES + 2).map { i ->
            TripRoute(listOf(leg("line$i", "A", "C", 5, 20L + i)))
        }
        val later = TripRoute(routes[0].legs.map { it.copy(departure = it.departure.plusSeconds(600), arrival = it.arrival.plusSeconds(600)) })
        val best = TripViewModel.bestOf(routes.reversed() + later)
        assertEquals(TripViewModel.MAX_ROUTES, best.map(::routeKey).distinct().size)
        assertTrue(later in best)
        assertFalse(routes.last() in best)
    }

    @Test
    fun `a recreated screen over the same model doesn't fetch again, a new re-pick does`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val client = FakeClient(mutableMapOf())
        val trip = model(planner, client)
        trip.refreshFor(null)
        advanceUntilIdle()
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        assertEquals(listOf("A", "B"), client.asked.sorted())
        // A re-pick refreshes, asking again for arrivals past their minute.
        now = now.plus(ArrivalsCache.TTL)
        trip.refreshFor(7)
        advanceUntilIdle()
        assertEquals(listOf("A", "A", "B", "B"), client.asked.sorted())
    }

    @Test
    fun `plans, then fetches each boarding stop's arrivals and the lines' status`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = model(planner, client)
        trip.refresh()
        advanceUntilIdle()
        val state = trip.state.value
        assertEquals(listOf(route), state.routes)
        assertEquals(listOf("A", "B"), client.asked.sorted())
        assertEquals(1, state.live.getValue("A").departures.size)
        assertEquals(setOf("red", "blue"), state.statuses.keys)
    }

    @Test
    fun `reuses a plan for fifteen minutes, then plans again`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refresh()
        advanceUntilIdle()
        now = now.plus(Duration.ofMinutes(14))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        now = now.plus(Duration.ofMinutes(1))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
    }

    @Test
    fun `a refresh asked for during one runs once more after it`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        trip.refresh()
        advanceUntilIdle()
        // The queued refresh ran: its statuses were checked again, but the two boarding stops,
        // fetched just now, weren't.
        assertEquals(2, client.statusChecks)
        assertEquals(2, client.asked.size)
    }

    @Test
    fun `a Retry tapped during a refresh plans again after it`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refresh()
        trip.retry()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
    }

    @Test
    fun `a trip reopened within fifteen minutes reuses its plan`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val plans = TripPlans()
        model(planner, FakeClient(mutableMapOf()), plans).refresh()
        advanceUntilIdle()
        now = now.plus(Duration.ofMinutes(10))
        val reopened = model(planner, FakeClient(mutableMapOf()), plans)
        assertEquals(listOf(route), reopened.state.value.routes)
        reopened.refresh()
        advanceUntilIdle()
        assertEquals(1, planner.calls)
    }

    @Test
    fun `a failed plan says why over the last plan, and Retry plans again`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refresh()
        advanceUntilIdle()
        planner.failWith = TflException.Offline(null)
        trip.retry()
        advanceUntilIdle()
        assertEquals(listOf(route), trip.state.value.routes)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, trip.state.value.planError)
        // The tick doesn't retry a failed plan on its own.
        planner.failWith = null
        now = now.plus(Duration.ofMinutes(20))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        trip.retry()
        advanceUntilIdle()
        assertNull(trip.state.value.planError)
        assertEquals(3, planner.calls)
    }

    @Test
    fun `a failed stop keeps its last arrivals, marked failed`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        val fetchedAt = now
        client.failStops = setOf("A")
        now = now.plus(Duration.ofMinutes(1))
        trip.refresh()
        advanceUntilIdle()
        val stop = trip.state.value.live.getValue("A")
        assertTrue(stop.failed)
        assertEquals(fetchedAt, stop.fetchedAt)
        assertEquals(1, stop.departures.size)
    }

    @Test
    fun `a failed line-status check keeps the last statuses and says so`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(false, trip.state.value.statusFailed)
        client.failStatus = true
        trip.refresh()
        advanceUntilIdle()
        assertTrue(trip.state.value.statusFailed)
        assertEquals(setOf("red", "blue"), trip.state.value.statuses.keys)
    }

    @Test
    fun `a route riding a hidden mode is left out`() {
        val bus = TripRoute(listOf(leg("red", "A", "C", 2, 40).copy(mode = "bus")))
        val state = TripViewModel.State(routes = listOf(route, bus))
        assertEquals(listOf(route), tripEstimates(state, now, Duration.ZERO, emptyMap(), hidden = setOf("bus"))?.map { it.route })
    }

    @Test
    fun `an unconfirmed position caps every route at estimated`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf(
                "A" to TripViewModel.StopLive(listOf(train("red", "End", 2)), now),
                "B" to TripViewModel.StopLive(listOf(train("blue", "C", 16)), now),
            ),
        )
        val sequences = mapOf("red" to red, "blue" to blue)
        assertEquals(TripTiming.Basis.LIVE, tripEstimates(state, now, Duration.ZERO, sequences)?.single()?.basis)
        assertEquals(
            TripTiming.Basis.ESTIMATED,
            tripEstimates(state, now, Duration.ZERO, sequences, originUnconfirmed = true)?.single()?.basis,
        )
    }

    @Test
    fun `a new plan's lines are unchecked until their status arrives`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        client.failStatus = true
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("red", "blue"), trip.state.value.statusUnknown)
        client.failStatus = false
        trip.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), trip.state.value.statusUnknown)
    }

    @Test
    fun `a line TfL leaves out of its status answer is unchecked`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        client.omitLines = setOf("blue")
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("blue"), trip.state.value.statusUnknown)
        val estimate = checkNotNull(tripEstimates(trip.state.value, now, Duration.ZERO, emptyMap())).single()
        assertTrue(estimate.unchecked)
    }

    @Test
    fun `a hidden mode's stops aren't fetched`() = runTest(dispatcher) {
        val bus = TripRoute(listOf(leg("red", "X", "C", 2, 40).copy(mode = "bus")))
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route, bus)), client)
        trip.hiddenModes = setOf("bus")
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), client.asked.sorted())
    }

    @Test
    fun `ways riding the same lines but changing elsewhere are cards of their own`() {
        // The same lines changing at another stop, the Planner arriving later: each card names where
        // its rides get off, so both are offered, best first.
        val elsewhere = TripRoute(listOf(leg("red", "A", "X", 5, 18), leg("blue", "X", "C", 22, 35)))
        val state = TripViewModel.State(routes = listOf(elsewhere, route))
        val estimates = checkNotNull(tripEstimates(state, now, Duration.ZERO, emptyMap()))
        assertEquals(listOf(listOf(route), listOf(elsewhere)), tripCards(estimates).map { card -> card.map { it.route } })
        // Other lines are another card.
        val green = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("green", "B", "C", 20, 32)))
        assertEquals(2, tripCards(checkNotNull(tripEstimates(state.copy(routes = listOf(route, green)), now, Duration.ZERO, emptyMap()))).size)
    }

    @Test
    fun `routes differing only in their first line between the same stops share a card`() {
        val viaGreen = TripRoute(listOf(leg("green", "A", "B", 6, 16), leg("blue", "B", "C", 20, 30)))
        val otherStop = TripRoute(listOf(leg("yellow", "Z", "B", 6, 16), leg("blue", "B", "C", 20, 30)))
        val offElsewhere = TripRoute(listOf(leg("pink", "A", "Y", 6, 16), leg("blue", "Y", "C", 20, 31)))
        val otherLine = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("purple", "B", "C", 20, 31)))
        val state = TripViewModel.State(routes = listOf(route, viaGreen, otherStop, offElsewhere, otherLine))
        val cards = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, emptyMap())))
        // Red or green from A to B, then blue: one card, the best first. From another stop, off at
        // another, or on to another line: cards of their own.
        assertEquals(4, cards.size)
        assertEquals(listOf(route, viaGreen), cards.single { it.size == 2 }.map { it.route })
    }

    @Test
    fun `routes changing at different stations after the first ride don't share a card`() {
        // Red or green to B, then blue, but green's route changes from blue to pink at Y, red's at X:
        // the card names each ride's stop, so they can't both be it.
        val redViaX = TripRoute(listOf(leg("red", "A", "B", 5, 12), leg("blue", "B", "X", 14, 18), leg("pink", "X", "C", 20, 26)))
        val greenViaY = TripRoute(listOf(leg("green", "A", "B", 6, 13), leg("blue", "B", "Y", 15, 20), leg("pink", "Y", "C", 22, 28)))
        val state = TripViewModel.State(routes = listOf(redViaX, greenViaY))
        val cards = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, emptyMap())))
        assertEquals(listOf(listOf(redViaX), listOf(greenViaY)), cards.map { card -> card.map { it.route } })
    }

    @Test
    fun `a line sharing a leg also shows in the shared card`() {
        // Red is quickest changing at X, and also rides to B with green: its X card, then the B card
        // with red beside green.
        val redViaX = TripRoute(listOf(leg("red", "A", "X", 4, 12), leg("blue", "X", "C", 14, 24)))
        val viaGreen = TripRoute(listOf(leg("green", "A", "B", 6, 16), leg("blue", "B", "C", 20, 30)))
        val state = TripViewModel.State(routes = listOf(redViaX, route, viaGreen))
        val cards = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, emptyMap())))
        assertEquals(listOf(listOf(redViaX), listOf(route, viaGreen)), cards.map { card -> card.map { it.route } })
    }

    @Test
    fun `a leg with no trains opens to its line's disruption, never a good service`() {
        val leg = route.legs[1]
        val good = TripViewModel.State(statuses = mapOf("blue" to LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service")))
        assertNull(legStatusRow(good, leg, now).status)
        val delayed = TripViewModel.State(statuses = mapOf("blue" to LineStatus("blue", 9, "Minor Delays")))
        assertEquals("Minor Delays", legStatusRow(delayed, leg, now).status?.description)
    }

    @Test
    fun `journeys that ride alike are one route`() {
        val later = TripRoute(route.legs.map { it.copy(departure = it.departure.plusSeconds(600), arrival = it.arrival.plusSeconds(600)) })
        val state = TripViewModel.State(routes = listOf(route, later))
        assertEquals(1, tripEstimates(state, now, Duration.ZERO, emptyMap())?.size)
    }

    @Test
    fun `a later timetable slot times a route whose earlier one is missed`() {
        val later = TripRoute(route.legs.map { it.copy(departure = it.departure.plusSeconds(600), arrival = it.arrival.plusSeconds(600)) })
        val state = TripViewModel.State(routes = listOf(route, later))
        // Six minutes from the first stop: the 5-minute slot is gone, the 15-minute one is not.
        val estimate = checkNotNull(tripEstimates(state, now, Duration.ofMinutes(6), emptyMap())).single()
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(40), estimate.arrival)
    }

    @Test
    fun `several destinations shorten each name alike before eliding`() {
        assertEquals(
            listOf("Crystal Palace, West Croydon", "Crystal Palace, W. Croydon", "Crystal P., W. Croydon"),
            destinationsLadder(listOf("Crystal Palace", "West Croydon")),
        )
    }

    @Test
    fun `a capped first-leg row keeps the first train the rider can reach`() {
        val trains = listOf(train("red", "End", 1), train("red", "End", 2), train("red", "End", 3), train("red", "End", 9))
        val shown = shownTrains(trains, reachable = at(5))
        assertEquals(listOf(at(2) to false, at(3) to false, at(9) to true), shown.map { (d, ok) -> d.expectedArrival to ok })
    }

    @Test
    fun `another branch's train is shown grayed among the usable ones`() {
        val other = train("red", "Elsewhere", 4)
        val trains = listOf(train("red", "End", 2), other, train("red", "End", 6))
        val shown = shownTrains(trains, reachable = at(0), usable = { it != other })
        assertEquals(listOf(at(2) to true, at(4) to false, at(6) to true), shown.map { (d, ok) -> d.expectedArrival to ok })
    }

    @Test
    fun `a leg shows only the trains that run along its route, not another branch's`() {
        // Riding blue from B toward C (the fork past B2): a train for C and one for D are due.
        val fork = LineSequence(
            routes = listOf(
                LineRoute("B ↔ C", listOf("B", "B2", "C")),
                LineRoute("B ↔ D", listOf("B", "B2", "D")),
                LineRoute("C ↔ B", listOf("C", "B2", "B")),
            ),
            stopNames = mapOf("B" to "B", "B2" to "B2", "C" to "C", "D" to "D"),
        )
        val leg = TripLeg("tube", "blue", "blue", "B", "B", "C", "C", at(20), at(30), path = listOf("B2", "C"))
        val toC = train("blue", "C", 6)
        val toD = train("blue", "D", 4)
        val both = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(toD, toC), now)))
        assertEquals(listOf(toC), legRows(both, leg, now, mapOf("blue" to fork)).flatMap { it.upcoming })
        // With only the other branch's train due, none: it doesn't take the rider to C.
        val onlyD = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(toD), now)))
        assertEquals(emptyList<Departure>(), legRows(onlyD, leg, now, mapOf("blue" to fork)).flatMap { it.upcoming })
    }

    @Test
    fun `a route starts only once its origin is confirmed and its bus poles are placed`() {
        val tube = TripLeg("tube", "blue", "blue", "B", "B", "C", "C", at(20), at(30), path = listOf("C"))
        val bus = TripLeg("bus", "1", "1", "B", "B", "C", "C", at(20), at(30), path = listOf("C"), fromArea = "490G0000B")
        val routes = LineSequence(routes = listOf(LineRoute("B ↔ C", listOf("B", "C"))), stopNames = mapOf("B" to "B", "C" to "C"))
        assertTrue(canStart(TripRoute(listOf(tube)), emptyMap(), originUnconfirmed = false))
        // Being found again: the route may change with the new fix.
        assertFalse(canStart(TripRoute(listOf(tube)), emptyMap(), originUnconfirmed = true))
        // A bus named by its stop pair, its route not loaded (or failed): the pole may be the wrong side.
        assertFalse(canStart(TripRoute(listOf(bus)), emptyMap(), originUnconfirmed = false))
        assertFalse(canStart(TripRoute(listOf(bus)), mapOf("1" to null), originUnconfirmed = false))
        assertTrue(canStart(TripRoute(listOf(bus)), mapOf("1" to routes), originUnconfirmed = false))
        // The route loaded, but its bus uses the pair's other pole, not yet looked up and placed:
        // the Planner's pole may be the wrong side of the road.
        val otherSide = LineSequence(
            routes = listOf(LineRoute("B2 ↔ C", listOf("B2", "C"))),
            stopNames = mapOf("B" to "B", "B2" to "B", "C" to "C"),
            stopAreas = mapOf("B" to "490G0000B", "B2" to "490G0000B"),
        )
        assertFalse(canStart(TripRoute(listOf(bus)), mapOf("1" to otherSide), originUnconfirmed = false))
        assertTrue(canStart(TripRoute(listOf(bus.copy(fromId = "B2"))), mapOf("1" to otherSide), originUnconfirmed = false))
        // Boarding at a stand in no pair, alighting at a roadside pair: its alighting pole matters too.
        val toPair = TripLeg("bus", "1", "1", "S", "S", "C", "C", at(20), at(30), toArea = "490G0000C")
        val farSide = LineSequence(
            routes = listOf(LineRoute("S ↔ C2", listOf("S", "C2"))),
            stopNames = mapOf("S" to "S", "C" to "C", "C2" to "C"),
            stopAreas = mapOf("C" to "490G0000C", "C2" to "490G0000C"),
        )
        assertFalse(canStart(TripRoute(listOf(toPair)), emptyMap(), originUnconfirmed = false))
        assertFalse(canStart(TripRoute(listOf(toPair)), mapOf("1" to farSide), originUnconfirmed = false))
        assertTrue(canStart(TripRoute(listOf(toPair.copy(toId = "C2"))), mapOf("1" to farSide), originUnconfirmed = false))
    }

    @Test
    fun `lines being checked say so, and only a finished check says it couldn't`() {
        val refreshing = TripViewModel.State(refreshing = true)
        assertEquals(true, statusNote(refreshing, unchecked = true))
        assertNull(statusNote(refreshing, unchecked = false))
        // A plan still landing hasn't checked its lines yet: checking, not failed.
        assertEquals(true, statusNote(TripViewModel.State(planning = true), unchecked = true))
        assertEquals(false, statusNote(TripViewModel.State(), unchecked = true))
        assertEquals(false, statusNote(TripViewModel.State(statusFailed = true), unchecked = false))
        assertNull(statusNote(TripViewModel.State(), unchecked = false))
    }

    @Test
    fun `on a loop only a train leaving for the leg's next stop is usable`() {
        // A loop through B, X, C, Y and back to B: riding B to C via X, both ways reach C.
        val loop = LineSequence(
            routes = listOf(
                LineRoute("Clockwise", listOf("B", "X", "C", "Y", "Z")),
                LineRoute("Anticlockwise", listOf("B", "Y", "C", "X", "W")),
            ),
            stopNames = mapOf("B" to "B", "X" to "X", "C" to "C", "Y" to "Y", "Z" to "Z", "W" to "W"),
        )
        val leg = TripLeg("tube", "loop", "loop", "B", "B", "C", "C", at(20), at(30), path = listOf("X", "C"))
        val clockwise = train("loop", "Z", 6)
        val anticlockwise = train("loop", "W", 2)
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(clockwise, anticlockwise), now)))
        assertEquals(listOf(clockwise), legTrains(state, leg, now, mapOf("loop" to loop)))
    }

    // Bus 1 both ways along a road: the stop pair BG's poles Bn (northbound) and Bs (southbound).
    private val road = LineSequence(
        routes = listOf(
            LineRoute("North", listOf("As", "Bn", "Xn", "Cn")),
            LineRoute("South", listOf("Cs", "Xs", "Bs", "Ad")),
        ),
        stopNames = mapOf("As" to "A", "Bn" to "B", "Xn" to "X", "Cn" to "C", "Cs" to "C", "Xs" to "X", "Bs" to "B", "Ad" to "A"),
        stopAreas = mapOf("Bn" to "BG", "Bs" to "BG", "Xn" to "XG", "Xs" to "XG", "Cn" to "CG", "Cs" to "CG"),
    )

    // The Planner rides north from BG to CG by way of XG, naming the southbound pole of each pair.
    private val plannerBus = TripLeg(
        "bus", "1", "1", "Bs", "B", "Cs", "C", at(20), at(30), path = listOf("XG", "CG"), fromArea = "BG", toArea = "CG",
    )

    @Test
    fun `a bus leg boards at the pole its bus uses, not the other side the Planner named`() {
        val placed = onPoles(plannerBus, mapOf("1" to road))
        assertEquals("Bn", placed.fromId)
        assertEquals("Cn", placed.toId)
        // Its route not in yet, or failed: as the Planner named it.
        assertEquals(plannerBus, onPoles(plannerBus, emptyMap()))
        assertEquals(plannerBus, onPoles(plannerBus, mapOf("1" to null)))
        // Its northbound buses then time it; the other side's southbound ones never did.
        val north = train("1", "C", 4).copy(mode = "bus")
        val state = TripViewModel.State(
            routes = listOf(TripRoute(listOf(plannerBus))),
            live = mapOf("Bn" to TripViewModel.StopLive(listOf(north), now), "Bs" to TripViewModel.StopLive(listOf(train("1", "A", 2).copy(mode = "bus")), now)),
        )
        val onRoad = onPoles(state, mapOf("1" to road))
        assertEquals(listOf(north), legTrains(onRoad, onRoad.routes!!.single().legs.single(), now, mapOf("1" to road)))
        // The route keeps its key, so an open one stays open once its poles are known.
        assertEquals(routeKey(state.routes!!.single()), routeKey(onRoad.routes!!.single()))
        // A pole the trip never fetches (its pair's lookup failed) isn't boarded at: the Planner's stands.
        val unfetched = state.copy(live = state.live - "Bn")
        assertEquals("Bs", onPoles(unfetched, mapOf("1" to road)).routes!!.single().legs.single().fromId)
        // One looked up and being fetched is, reading "Loading" until its arrivals are in.
        val pending = onPoles(unfetched.copy(areaPoles = mapOf("BG" to listOf("Bn", "Bs"))), mapOf("1" to road))
        val leg = pending.routes!!.single().legs.single()
        assertEquals("Bn", leg.fromId)
        assertTrue(legLoading(pending, leg, mapOf("1" to road)))
    }

    @Test
    fun `a bus leg's line page lists the stops from the pole its bus uses`() {
        // Its route loaded only for the page (the trip's load failed): the Planner's pole is the
        // other side of the road, which the northbound route never calls at.
        val stops = legStops(plannerBus, road) as RouteStopsUi.Loaded
        assertEquals(listOf("Bn", "Xn", "Cn"), stops.stops.map { it.id })
    }

    @Test
    fun `a bus leg's open page keeps its key once its pole is worked out`() {
        // Opened from its "Loading" row at the Planner's pole; its route then places it at the other.
        val state = TripViewModel.State(routes = listOf(TripRoute(listOf(plannerBus))))
        val placed = onPoles(plannerBus, mapOf("1" to road))
        assertEquals(
            tripDetailKey(plannerBus, legStatusRow(state, plannerBus, now)),
            tripDetailKey(placed, legStatusRow(state, placed, now)),
        )
    }

    @Test
    fun `a bus leg to a stop in no pair gets off at the route's stop of that name`() {
        // The Planner rides north to a bus station's stand Ds, which only the southbound route uses;
        // the northbound route's own stand there is Dn. Neither is in a pair.
        val station = road.copy(
            routes = listOf(LineRoute("North", listOf("As", "Bn", "Xn", "Dn")), LineRoute("South", listOf("Ds", "Xs", "Bs", "Ad"))),
            stopNames = road.stopNames + mapOf("Dn" to "D", "Ds" to "D"),
        )
        val toStation = plannerBus.copy(toId = "Ds", toName = "D", toArea = "", path = listOf("XG", "Ds"))
        val placed = onPoles(toStation, mapOf("1" to station))
        assertEquals("Bn", placed.fromId)
        assertEquals("Dn", placed.toId)
        // Its route keeps its key, so an open one stays open once its stops are known.
        assertEquals(routeKey(TripRoute(listOf(toStation))), routeKey(TripRoute(listOf(placed))))
        // A route calling at the Planner's own stop gets off there, not at an earlier stop of its name.
        val twice = station.copy(routes = listOf(LineRoute("North", listOf("As", "Bn", "Xn", "Dn", "Ds"))))
        assertEquals("Ds", onPoles(toStation, mapOf("1" to twice)).toId)
        // Two stops of the name along the route, and not the Planner's own: no single answer, as named.
        val loop = station.copy(routes = listOf(LineRoute("North", listOf("As", "Bn", "Xn", "Dn", "Yn", "Dx"))))
        assertEquals(toStation, onPoles(toStation, mapOf("1" to loop.copy(stopNames = loop.stopNames + ("Dx" to "D")))))
        // A stop of another name is no match: as the Planner named it.
        assertEquals(toStation.copy(toName = "E"), onPoles(toStation.copy(toName = "E"), mapOf("1" to station)))
    }

    @Test
    fun `a bus stop pair's buses wait for the route to say which side the bus uses`() {
        val state = TripViewModel.State(live = mapOf("Bs" to TripViewModel.StopLive(listOf(train("1", "A", 2).copy(mode = "bus")), now)))
        assertEquals(emptyList<Departure>(), pendingTrains(state, plannerBus, now, emptyMap()))
        // Its row and an opened route's card read "Loading" meanwhile, not "–".
        assertTrue(legLoading(state, plannerBus, emptyMap()))
        assertFalse(legLoading(state, plannerBus, mapOf("1" to road)))
        // Nor while its poles are still being looked up; after a failed lookup, the Planner's pole shows.
        assertTrue(legLoading(state.copy(refreshing = true), plannerBus, mapOf("1" to road)))
        assertFalse(legLoading(state.copy(refreshing = true, areaPoles = mapOf("BG" to listOf("Bs"))), plannerBus, mapOf("1" to road)))
    }

    @Test
    fun `a bus leg's boarding stop is fetched on every pole of its pair`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        val trip = TripViewModel(
            FakePlanner(listOf(TripRoute(listOf(plannerBus)))), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, plans = TripPlans(), io = dispatcher,
            poles = { area -> if (area == "BG") listOf("Bn", "Bs") else emptyList() },
        )
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("Bs", "Bn"), client.asked.toSet())
    }

    @Test
    fun `a trip reads as checking while its bus stop pairs are looked up`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val trip = TripViewModel(
            FakePlanner(listOf(TripRoute(listOf(plannerBus)))), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")), clock = { now },
            plans = TripPlans(), io = dispatcher, poles = { gate.await(); listOf("Bn", "Bs") },
        )
        trip.refresh()
        runCurrent()
        assertTrue(trip.state.value.refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(trip.state.value.refreshing)
    }

    @Test
    fun `a bus leg's path by stop pair still tells which way a bus leaves`() {
        // The Planner names a bus leg's path by stop pair ("XG"); the route lists the poles in it.
        val loop = LineSequence(
            routes = listOf(
                LineRoute("Clockwise", listOf("B", "Xn", "C", "Yn", "Z")),
                LineRoute("Anticlockwise", listOf("B", "Ys", "C", "Xs", "W")),
            ),
            stopNames = mapOf("B" to "B", "Xn" to "X", "Xs" to "X", "C" to "C", "Yn" to "Y", "Ys" to "Y", "Z" to "Z", "W" to "W"),
            stopAreas = mapOf("Xn" to "XG", "Xs" to "XG", "Yn" to "YG", "Ys" to "YG"),
        )
        val leg = TripLeg("bus", "loop", "loop", "B", "B", "C", "C", at(20), at(30), path = listOf("XG", "CG"))
        val clockwise = train("loop", "Z", 6).copy(mode = "bus")
        val anticlockwise = train("loop", "W", 2).copy(mode = "bus")
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(clockwise, anticlockwise), now)))
        assertEquals(listOf(clockwise), legTrains(state, leg, now, mapOf("loop" to loop)))
    }

    @Test
    fun `a bus whose blind names an area and leaves the other way round a loop isn't usable`() {
        // The Planner rides B to C by way of X; this pole's buses loop the other way, by way of Y.
        val loop = LineSequence(
            routes = listOf(LineRoute("Loop", listOf("B", "Ys", "C", "Xn", "Z"))),
            stopNames = mapOf("B" to "B", "Ys" to "Y", "C" to "C", "Xn" to "X", "Z" to "Z"),
            stopAreas = mapOf("Xn" to "XG", "Ys" to "YG"),
        )
        val leg = TripLeg("bus", "loop", "loop", "B", "B", "C", "C", at(20), at(30), path = listOf("XG", "C"))
        val bus = train("loop", "Town Centre", 4).copy(mode = "bus")
        assertEquals(false, leavesAlongLeg(bus, leg, mapOf("loop" to loop)))
    }

    @Test
    fun `while a line's route loads, its trains toward the Planner's terminus show as on the main screen`() {
        val leg = TripLeg("tube", "blue", "blue", "B", "B", "C", "C", at(20), at(30), path = listOf("C"), headings = listOf("End"))
        val toEnd = train("blue", "End", 4)
        val alsoOut = train("blue", "Elsewhere", 6)
        val back = train("blue", "Start", 5).copy(direction = "inbound")
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(toEnd, alsoOut, back), now)))
        assertEquals(listOf(toEnd, alsoOut), pendingTrains(state, leg, now, emptyMap()))
        // Once the route has loaded (or failed), the checked trains take over.
        assertEquals(emptyList<Departure>(), pendingTrains(state, leg, now, mapOf("blue" to blue)))
        assertEquals(emptyList<Departure>(), pendingTrains(state, leg, now, mapOf("blue" to null)))
        // No terminus to match, and both directions here: none rather than a guess.
        assertEquals(emptyList<Departure>(), pendingTrains(state, leg.copy(headings = emptyList()), now, emptyMap()))
        // National Rail gives no direction: only trains heading for the terminus, not the whole board.
        val board = listOf(toEnd, alsoOut, back).map { it.copy(direction = "") }
        val rail = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(board, now)))
        assertEquals(listOf(board[0]), pendingTrains(rail, leg, now, emptyMap()))
        assertEquals(emptyList<Departure>(), pendingTrains(rail, leg.copy(headings = emptyList()), now, emptyMap()))
        // A train with no destination isn't shown under the Planner's terminus.
        val blank = train("blue", "", 3)
        val pole = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(blank, toEnd), now)))
        assertEquals(listOf(toEnd), pendingTrains(pole, leg, now, emptyMap()))
        // A station whose snapshot holds only the other direction's trains: none, not the wrong way.
        val wrongWay = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(back), now)))
        assertEquals(emptyList<Departure>(), pendingTrains(wrongWay, leg, now, emptyMap()))
        // A bus pole serves one way: its buses show even with no destination matching the terminus.
        val busLeg = leg.copy(mode = "bus", headings = listOf("Town Centre"))
        val buses = listOf(toEnd, alsoOut).map { it.copy(mode = "bus") }
        val busPole = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(buses, now)))
        assertEquals(buses, pendingTrains(busPole, busLeg, now, emptyMap()))
    }

    @Test
    fun `before the route check only a bus to the terminus on no named branch shows plain`() {
        val viaBank = train("134", "Morden", 2).copy(mode = "bus", branch = "Bank")
        val plain = train("134", "Morden", 4).copy(mode = "bus")
        val shortWorking = train("134", "Kennington", 3).copy(mode = "bus")
        val leg = TripLeg("bus", "134", "134", "B", "B", "C", "C", at(20), at(30), path = listOf("C"), headings = listOf("Morden"))
        assertEquals(setOf(viaBank, shortWorking), uncheckedPending(listOf(viaBank, plain, shortWorking), leg))
        // An opened route's card leaves the doubtful ones out until the route check vouches for them.
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(viaBank, plain, shortWorking), now)))
        assertEquals(listOf(plain), pendingCardTrains(state, leg, now, emptyMap()))
        // A rail service to the terminus may still run fast past the rider's stop: checked first.
        val express = train("thameslink", "Brighton", 4).copy(direction = "")
        val railLeg = leg.copy(mode = "national-rail", lineId = "thameslink", headings = listOf("Brighton"))
        assertEquals(setOf(express), uncheckedPending(listOf(express), railLeg))
        val rail = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(express), now)))
        assertEquals(emptyList<Departure>(), pendingCardTrains(rail, railLeg, now, emptyMap()))
        // A list card's line row shows it grayed meanwhile, so the row it opens holds it too; the
        // open route's rows leave it out.
        assertEquals(emptyList<DepartureRow>(), legRows(rail, railLeg, now, emptyMap()))
        assertEquals(listOf(express), legRows(rail, railLeg, now, emptyMap(), withUnchecked = true).flatMap { it.upcoming })
    }

    @Test
    fun `a train still being checked that leaves too soon is read as can't catch`() {
        val soon = train("blue", "End", 2)
        val later = train("blue", "End", 8)
        val reachable = at(5)
        assertEquals(R.string.trip_train_unusable_description, trainDescription(soon, false, checking = true, reachable))
        assertEquals(R.string.trip_train_checking_description, trainDescription(later, false, checking = true, reachable))
        assertEquals(R.string.trip_train_unusable_description, trainDescription(later, false, checking = false, reachable))
        assertEquals(R.string.trip_train_description, trainDescription(later, true, checking = false, reachable))
    }

    @Test
    fun `the Planner's terminus matches a board's qualified name for it`() {
        // The Planner's "Stratford" is the board's "Stratford (London)".
        val board = train("mildmay", "Stratford (London)", 4).copy(direction = "")
        val leg = TripLeg("overground", "mildmay", "mildmay", "B", "B", "C", "C", at(20), at(30), path = listOf("C"), headings = listOf("Stratford"))
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(board), now)))
        assertEquals(listOf(board), pendingTrains(state, leg, now, emptyMap()))
        // Another station that merely starts the same isn't it.
        val other = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(board.copy(destination = "Stratford International")), now)))
        assertEquals(emptyList<Departure>(), pendingTrains(other, leg, now, emptyMap()))
    }

    @Test
    fun `a train whose route can't be followed says so rather than pass as no live train`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf("B" to TripViewModel.StopLive(listOf(train("blue", "C", 16)), now)),
        )
        val estimates = checkNotNull(tripEstimates(state, now, Duration.ZERO, emptyMap()))
        // The blue line's route is still loading.
        assertEquals(TripMessage.CHECKING, tripCheckState(state, estimates, now, emptyMap()))
        // It failed to load.
        assertEquals(TripMessage.INCOMPLETE, tripCheckState(state, estimates, now, mapOf("blue" to null)))
        assertNull(tripCheckState(state, estimates, now, mapOf("blue" to blue)))
        // Neither a loading nor a failed route names a train: the fetch logs a failure itself.
        assertTrue(tripMisses(state, estimates, now, emptyMap()).isEmpty())
        assertTrue(tripMisses(state, estimates, now, mapOf("blue" to null)).isEmpty())
        assertTrue(tripMisses(state, estimates, now, mapOf("blue" to blue)).isEmpty())
    }

    @Test
    fun `two legs on one line from one stop are told apart by where they get off`() {
        val one = TripLeg("bus", "43", "43", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
        val other = one.copy(toId = "D", toName = "D", path = listOf("B", "D"))
        assertTrue(tripLegKey(one) != tripLegKey(other))
        // A re-plan moves a leg's times, not which leg it is.
        assertEquals(tripLegKey(one), tripLegKey(one.copy(departure = at(9), arrival = at(19))))
    }

    private fun row(direction: String, destination: String, train: Departure) = DepartureRow(
        stopId = "B", stopName = "B", lineId = "blue", lineName = "Blue", direction = direction,
        directionKey = direction, destination = destination, mode = "tube", upcoming = listOf(train), fetchedAt = now,
    )

    @Test
    fun `a bus leg keeps its key when its route moves it to the other side of the road`() {
        val planned = TripLeg(
            "bus", "43", "43", "A1", "A", "B1", "B", at(5), at(15),
            path = listOf("M", "B1"), fromArea = "GA", toArea = "GB",
        )
        val route = LineSequence(
            routes = listOf(LineRoute("A ↔ B", listOf("A2", "M", "B2"))),
            stopNames = mapOf("A2" to "A", "M" to "M", "B2" to "B"),
            stopAreas = mapOf("A2" to "GA", "B2" to "GB"),
        )
        val placed = onPoles(planned, mapOf("43" to route))
        // The route's own poles, the other side of the road from the Planner's...
        assertEquals("A2" to "B2", placed.fromId to placed.toId)
        // ...and still the leg the page was opened for.
        assertEquals(tripLegKey(planned), tripLegKey(placed))
    }

    @Test
    fun `a dismissed alert is left off the cards but a good service or a new alert is not`() {
        val severe = LineStatus("blue", 6, "Severe Delays")
        val good = LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service")
        val statuses = mapOf("blue" to severe, "red" to good)
        assertEquals(mapOf("red" to good), shownStatuses(statuses, setOf(DismissedAlert.ofLineStatus(severe))))
        // A different alert on the same line (its wording changed) is a new one: it shows.
        val worse = LineStatus("blue", 20, "Service Closed")
        assertEquals(worse, shownStatuses(mapOf("blue" to worse), setOf(DismissedAlert.ofLineStatus(severe)))["blue"])
        assertEquals(statuses, shownStatuses(statuses, emptySet()))
    }

    @Test
    fun `a line row opens the row holding the train it leads with`() {
        val northbound = train("blue", "C", 6)
        val southbound = train("blue", "D", 3).copy(direction = "outbound")
        val rows = listOf(
            row(direction = "outbound", destination = "D", train = southbound),
            row(direction = "inbound", destination = "C", train = northbound),
        )
        assertEquals("C", legRowFor(rows, northbound)?.destination)
        assertEquals("D", legRowFor(rows, null)?.destination)
        assertNull(legRowFor(emptyList(), northbound))
    }

    @Test
    fun `a train whose path won't resolve is named for the log`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf("B" to TripViewModel.StopLive(listOf(train("blue", "Nowhere", 16)), now)),
        )
        val estimates = checkNotNull(tripEstimates(state, now, Duration.ZERO, emptyMap()))
        val sequences = mapOf("blue" to blue)
        assertEquals(TripMessage.INCOMPLETE, tripCheckState(state, estimates, now, sequences))
        assertEquals(setOf(RouteMiss("blue", "B", RouteStops.Resolution.NoMatch)), tripMisses(state, estimates, now, sequences))
    }

    // A line forking after B: on to C, or to D.
    private val blue = LineSequence(
        routes = listOf(
            LineRoute("B ↔ C", listOf("B", "C")),
            LineRoute("B ↔ D", listOf("B", "D")),
        ),
        stopNames = mapOf("B" to "B", "C" to "C", "D" to "D"),
    )

    private val red = LineSequence(
        routes = listOf(LineRoute("A ↔ End", listOf("A", "B", "End"))),
        stopNames = mapOf("A" to "A", "B" to "B", "End" to "End"),
    )

    @Test
    fun `a card's first-ride times gray only what leaves before the rider reaches the stop`() {
        // Red at 6 is quick to B and times the best route; green at 3 is slower but catchable after a
        // 2 min walk: it mustn't be grayed because the best route boards later.
        val viaRed = TripRoute(listOf(leg("red", "A", "B", 6, 11), leg("blue", "B", "C", 13, 23)))
        val viaGreen = TripRoute(listOf(leg("green", "A", "B", 3, 18), leg("blue", "B", "C", 20, 30)))
        val green = LineSequence(routes = listOf(LineRoute("A ↔ End", listOf("A", "B", "End"))), stopNames = red.stopNames)
        val sequences = mapOf("red" to red, "green" to green, "blue" to blue)
        val state = TripViewModel.State(
            routes = listOf(viaRed, viaGreen),
            live = mapOf(
                "A" to TripViewModel.StopLive(listOf(train("red", "End", 6), train("green", "End", 3), train("green", "End", 1)), now),
                "B" to TripViewModel.StopLive(listOf(train("blue", "C", 13), train("blue", "C", 20)), now),
            ),
        )
        val access = Duration.ofMinutes(2)
        val card = tripCards(checkNotNull(tripEstimates(state, now, access, sequences))).single()
        assertEquals(viaRed, card.first().route)
        val times = cardTimes(card, state, now, access, sequences)
        assertEquals(listOf(at(1) to false, at(3) to true, at(6) to true), times.shown.map { (train, catchable) -> train.expectedArrival to catchable })
        assertEquals(at(2), times.reachable)
    }

    // Red and green both run A → B → End, calling at nothing between A and B; red is the Planner's.
    private val greenAlike = LineSequence(routes = listOf(LineRoute("A ↔ End", listOf("A", "B", "End"))), stopNames = red.stopNames)
    private val viaRedOnly = TripRoute(listOf(leg("red", "A", "B", 6, 11), leg("blue", "B", "C", 13, 23)))
    private fun redAndGreenAt(vararg trains: Departure) = TripViewModel.State(
        routes = listOf(viaRedOnly),
        live = mapOf(
            "A" to TripViewModel.StopLive(trains.toList(), now),
            "B" to TripViewModel.StopLive(listOf(train("blue", "C", 10), train("blue", "C", 20)), now),
        ),
    )

    @Test
    fun `a line serving the same two stops rides beside the Planner's, as an equal`() {
        // Green isn't in the plan: its trains at A are how it's found, and its route says it runs to B.
        // Every line checked as running, so green's trains may time the ride.
        val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3)).copy(
            statuses = listOf("red", "green", "blue").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") },
        )
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val lines = rideLines(state.routes.orEmpty(), state, sequences)
        val ride = viaRedOnly.rides.first()
        assertEquals(listOf("red", "green"), lines.getValue(ride).legs.map { it.lineId })
        assertEquals(listOf("red", "green"), lines.getValue(ride).timed.map { it.lineId })
        // Its route is loaded for that: a line at a ride's boarding stop is asked for.
        assertTrue("green" in sequenceLineIds(state, emptySet(), emptyList()))
        // One pill for both, and both lines' trains on the first-ride row.
        val card = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, sequences, lines = lines))).single()
        assertEquals(listOf("red", "green"), cardRideLines(card, 0, lines).map { it.lineId })
        assertEquals(listOf(at(3), at(6)), cardTimes(card, state, now, Duration.ZERO, sequences, lines).shown.map { it.first.expectedArrival })
        // Green at 3 reaches B at 8, in time for blue at 10; red at 6 reaches it at 11, after it.
        val alone = tripEstimates(state, now, Duration.ZERO, sequences, lines = emptyMap())!!.single()
        val together = tripEstimates(state, now, Duration.ZERO, sequences, lines = lines)!!.single()
        assertTrue(together.arrival!!.isBefore(alone.arrival))
    }

    @Test
    fun `another line times a ride only once it's checked as running`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val ride = viaRedOnly.rides.first()
        fun timedLines(statuses: Map<String, LineStatus>): Set<String> {
            val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3)).copy(statuses = statuses)
            return rideTrains(state, ride, now, sequences, rideLines(state.routes.orEmpty(), state, sequences))
                .orEmpty().mapTo(HashSet()) { it.lineId }
        }
        val good = LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service")
        // Unchecked, green's trains don't time the ride; checked and running, they do; suspended, not.
        assertEquals(setOf("red"), timedLines(mapOf("red" to good)))
        assertEquals(setOf("red", "green"), timedLines(mapOf("red" to good, "green" to good.copy(lineId = "green"))))
        assertEquals(setOf("red"), timedLines(mapOf("red" to good, "green" to LineStatus("green", 20, "Service Closed"))))
    }

    @Test
    fun `another line at a boarding stop has its status checked in the same refresh, apart from the plan's`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2), train("green", "End", 3))))
        val trip = model(FakePlanner(listOf(route)), client)
        // Green is first seen in this refresh's arrivals, and checked straight after, not a tick later.
        trip.refresh()
        advanceUntilIdle()
        assertTrue("green" in trip.state.value.statuses)
        // Left out of TfL's answer, it isn't one of the plan's lines to say couldn't be checked.
        client.omitLines = setOf("green")
        val fresh = model(FakePlanner(listOf(route)), client)
        fresh.refresh()
        advanceUntilIdle()
        assertTrue("green" !in fresh.state.value.statuses)
        assertEquals(emptySet<String>(), fresh.state.value.statusUnknown)
    }

    @Test
    fun `another line's trains read as checking on the card until it's checked as running`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val good = listOf("red", "blue").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
        val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3)).copy(statuses = good)
        val lines = rideLines(state.routes.orEmpty(), state, sequences)
        val card = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, sequences, lines = lines))).single()
        val unchecked = cardTimes(card, state, now, Duration.ZERO, sequences, lines)
        val green = unchecked.shown.single { it.first.lineId == "green" }
        assertEquals(false, green.second)
        assertTrue(green.first in unchecked.checking)
        val checked = state.copy(statuses = good + ("green" to LineStatus("green", LineStatus.GOOD_SERVICE, "Good Service")))
        assertEquals(true, cardTimes(card, checked, now, Duration.ZERO, sequences, lines).shown.single { it.first.lineId == "green" }.second)
    }

    @Test
    fun `a quiet line sits under its own stop's group, or a header of its own`() {
        val row = { stopId: String -> DepartureRows.forStop(stopId, "Stop", listOf(train("red", "End", 2)), now).single() }
        val north = StopGroup("Stop", "north", true, listOf(row("Pn")))
        val atPn = leg("green", "Pn", "B", 5, 15)
        val atPs = leg("amber", "Ps", "B", 5, 15)
        val (placed, rest) = placeQuiet(listOf(north), listOf(atPn, atPs))
        assertEquals(listOf(atPn), placed.getValue(north))
        assertEquals(listOf(listOf(atPs)), rest)
    }

    @Test
    fun `an open ride shows another line's countdowns only once it's checked as running`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val red = viaRedOnly.rides.first()
        val green = red.copy(lineId = "green", lineName = "green")
        val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3))
        val unchecked = rideLegRows(red, listOf(red, green), state, now, sequences, emptySet())
        assertTrue(unchecked.getValue(red).isNotEmpty())
        assertEquals(emptyList<DepartureRow>(), unchecked.getValue(green))
        val good = state.copy(statuses = mapOf("green" to LineStatus("green", LineStatus.GOOD_SERVICE, "Good Service")))
        assertTrue(rideLegRows(red, listOf(red, green), good, now, sequences, emptySet()).getValue(green).isNotEmpty())
    }

    @Test
    fun `another line at a pole whose refresh failed doesn't time the ride`() {
        val red = viaRedOnly.rides.first()
        val green = red.copy(lineId = "green", lineName = "green", fromId = "A2")
        val good = mapOf("green" to LineStatus("green", LineStatus.GOOD_SERVICE, "Good Service"))
        val base = redAndGreenAt(train("red", "End", 6)).copy(statuses = good)
        val lines = mapOf(red to RideLines(listOf(red, green), listOf(red, green)))
        val atA2 = LineSequence(routes = listOf(LineRoute("A2 ↔ End", listOf("A2", "B", "End"))), stopNames = this.red.stopNames + ("A2" to "A"))
        val sequences = mapOf("red" to this.red, "green" to atA2, "blue" to blue)
        val fresh = base.copy(live = base.live + ("A2" to TripViewModel.StopLive(listOf(train("green", "End", 3)), now)))
        assertEquals(setOf("red", "green"), rideTrains(fresh, red, now, sequences, lines)?.map { it.lineId }?.toSet())
        val failed = base.copy(live = base.live + ("A2" to TripViewModel.StopLive(listOf(train("green", "End", 3)), now, failed = true)))
        assertEquals(setOf("red"), rideTrains(failed, red, now, sequences, lines)?.map { it.lineId }?.toSet())
        // Nor counts toward the predictions a withheld arrival reports: the count describes the
        // trains that timed the ride.
        assertEquals(ridePredicted(fresh, red, now, lines) - 1, ridePredicted(failed, red, now, lines))
    }

    @Test
    fun `a failed refresh at another line's pole is named like the Planner's own`() {
        // Green boards at the other pole of the ride's stop, whose refresh failed: its held trains
        // mustn't read as fresh, so that pole is named in the banner too.
        val red = viaRedOnly.rides.first()
        val green = red.copy(lineId = "green", lineName = "green", fromId = "A2", fromName = "A (other side)")
        val state = redAndGreenAt(train("red", "End", 6)).let { it.copy(live = it.live + ("A2" to TripViewModel.StopLive(emptyList(), now, failed = true))) }
        val lines = mapOf(red to RideLines(listOf(red, green), listOf(red)))
        val estimates = checkNotNull(tripEstimates(state, now, Duration.ZERO, mapOf("red" to this.red, "blue" to blue), lines = lines))
        assertEquals(listOf("A (other side)"), failedStops(estimates, state, lines))
        assertEquals(emptyList<String>(), failedStops(estimates, state, emptyMap()))
    }

    @Test
    fun `a ride's stop count shows only where every line takes that many`() {
        val red = leg("red", "A", "B", 5, 15).copy(path = listOf("B"))
        assertEquals(1, rideStops(listOf(red, red.copy(lineId = "green"))))
        assertNull(rideStops(listOf(red, red.copy(lineId = "green", path = listOf("Z", "B")))))
    }

    @Test
    fun `a card offers to hide every line its pill names`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3))
        val lines = rideLines(state.routes.orEmpty(), state, sequences)
        val card = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, sequences, lines = lines))).single()
        assertEquals(listOf("red", "green", "blue"), cardLines(card, lines).map { it.id })
    }

    @Test
    fun `a quiet line goes under only the first of its stop's groups`() {
        // A stop split by platform is two groups for one stop: the line shows once, not under both.
        val row = { stopId: String -> DepartureRows.forStop(stopId, "Stop", listOf(train("red", "End", 2)), now).single() }
        val one = StopGroup("Stop", "Platform 1", true, listOf(row("Pn")))
        val two = StopGroup("Stop", "Platform 2", true, listOf(row("Pn")))
        val atPn = leg("green", "Pn", "B", 5, 15)
        val (placed, rest) = placeQuiet(listOf(one, two), listOf(atPn))
        assertEquals(listOf(atPn), placed.getValue(one))
        assertEquals(emptyList<TripLeg>(), placed.getValue(two))
        assertEquals(emptyList<List<TripLeg>>(), rest)
    }

    @Test
    fun `a line reaching the same stop another way shows but doesn't time the ride`() {
        // Green calls at Z first: its trains are real and shown, but red's time on board isn't green's.
        val longWay = LineSequence(routes = listOf(LineRoute("A ↔ End", listOf("A", "Z", "B", "End"))), stopNames = red.stopNames + ("Z" to "Z"))
        val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3))
        val sequences = mapOf("red" to red, "green" to longWay, "blue" to blue)
        val lines = rideLines(state.routes.orEmpty(), state, sequences).getValue(viaRedOnly.rides.first())
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals(listOf("red"), lines.timed.map { it.lineId })
    }

    @Test
    fun `a line at another stop, missing the change, hidden, or not yet loaded isn't one`() {
        val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3))
        val ride = viaRedOnly.rides.first()
        val cases = mapOf(
            // It boards at A2, which the trip doesn't fetch: another stop, even if nearby.
            "another stop" to mapOf("green" to greenAlike.copy(routes = listOf(LineRoute("A2 ↔ B", listOf("A2", "B"))))),
            "misses the change" to mapOf("green" to greenAlike.copy(routes = listOf(LineRoute("A ↔ End", listOf("A", "End"))))),
            "not loaded" to emptyMap(),
        )
        for ((why, sequences) in cases) {
            assertEquals(why, listOf("red"), rideLines(state.routes.orEmpty(), state, sequences + ("red" to red)).getValue(ride).legs.map { it.lineId })
        }
        val hidden = rideLines(state.routes.orEmpty(), state, mapOf("red" to red, "green" to greenAlike), hidden = setOf(HiddenModes.lineKey("green", "Green")))
        assertEquals(listOf("red"), hidden.getValue(ride).legs.map { it.lineId })
    }

    @Test
    fun `a road's two poles are one stop for another line`() {
        // Bus 2 stops at the northbound pole of the pair the Planner's bus 1 boards at, and at a pole of
        // the pair it gets off at, calling at the same pair between.
        val two = road.copy(routes = listOf(LineRoute("North", listOf("Bn", "Xn", "Cn"))))
        val ride = onPoles(plannerBus, mapOf("1" to road))
        val state = TripViewModel.State(
            routes = listOf(TripRoute(listOf(ride))),
            live = mapOf("Bn" to TripViewModel.StopLive(listOf(train("1", "C", 4).copy(mode = "bus"), train("2", "C", 2).copy(mode = "bus")), now)),
            areaPoles = mapOf("BG" to listOf("Bn", "Bs")),
        )
        val lines = rideLines(state.routes.orEmpty(), state, mapOf("1" to road, "2" to two)).getValue(ride)
        assertEquals(listOf("1", "2"), lines.legs.map { it.lineId })
        assertEquals("Bn" to "Cn", lines.legs[1].let { it.fromId to it.toId })
        assertEquals(listOf("1", "2"), lines.timed.map { it.lineId })
    }

    @Test
    fun `a card's first-ride times wait for every line before showing any`() {
        // Red and green from the same stop pair: red's route is known, green's isn't yet, so which
        // side its buses use isn't either. Red's times alone would read as the whole card's.
        fun fromPair(route: TripRoute) = TripRoute(listOf(route.legs[0].copy(fromArea = "A")) + route.legs.drop(1))
        val viaRed = fromPair(TripRoute(listOf(leg("red", "A", "B", 6, 11), leg("blue", "B", "C", 13, 23))))
        val viaGreen = fromPair(TripRoute(listOf(leg("green", "A", "B", 3, 18), leg("blue", "B", "C", 20, 30))))
        val state = TripViewModel.State(
            routes = listOf(viaRed, viaGreen),
            live = mapOf("A" to TripViewModel.StopLive(listOf(train("red", "End", 6), train("green", "End", 3)), now)),
        )
        val loading = mapOf("red" to red, "blue" to blue)
        val card = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, loading))).single()
        assertEquals(2, card.size)
        val times = cardTimes(card, state, now, Duration.ZERO, loading)
        assertTrue(times.loading)
        assertTrue(times.shown.isEmpty())
        // Once green's route is in too, both show.
        val green = LineSequence(routes = listOf(LineRoute("A ↔ End", listOf("A", "B", "End"))), stopNames = red.stopNames)
        val loaded = cardTimes(card, state, now, Duration.ZERO, loading + ("green" to green))
        assertEquals(listOf(at(3), at(6)), loaded.shown.map { it.first.expectedArrival })
    }

    @Test
    fun `a leg counts only trains whose route calls where the rider gets off`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf("B" to TripViewModel.StopLive(listOf(train("blue", "D", 16), train("blue", "C", 18)), now)),
        )
        val trains = legTrains(state, route.legs[1], now, mapOf("blue" to blue))
        assertEquals(listOf(at(18)), trains?.map { it.expectedArrival })
    }

    @Test
    fun `stale arrivals time nothing`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf("A" to TripViewModel.StopLive(listOf(train("red", "End", 20)), now.minus(Duration.ofMinutes(6)))),
        )
        assertNull(legTrains(state, route.legs[0], now, mapOf("red" to red)))
    }

    @Test
    fun `times each route from live trains and ranks them`() {
        val slow = TripRoute(listOf(leg("red", "A", "C", 2, 40)))
        val state = TripViewModel.State(
            routes = listOf(slow, route),
            live = mapOf(
                "A" to TripViewModel.StopLive(listOf(train("red", "End", 2)), now),
                "B" to TripViewModel.StopLive(listOf(train("blue", "C", 16)), now),
            ),
        )
        val estimates = checkNotNull(tripEstimates(state, now, Duration.ZERO, mapOf("red" to red, "blue" to blue)))
        // The two-leg route is live throughout; the one-leg route's train doesn't reach C, so it's estimated.
        assertEquals(listOf(route, slow), estimates.map { it.route })
        assertEquals(TripTiming.Basis.LIVE, estimates[0].basis)
        assertEquals(at(26), estimates[0].arrival)
    }
}
