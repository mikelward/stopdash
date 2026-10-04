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
import app.stopdash.domain.Dismissed
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGroup
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.PlacedStand
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.RideLines
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteStops
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripClosures
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.onPoles
import app.stopdash.domain.TripTiming
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // The trip page's helpers are called directly here, with no worker to judge trains ahead: judged in place.
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        TripVerdicts.onMiss = TripVerdicts::compute
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        TripVerdicts.onMiss = null
    }

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
        // Where each call planned from, in order.
        val origins = mutableListOf<TripOrigin>()
        // The walking speed each call was timed at, in order.
        val speeds = mutableListOf<WalkingSpeed>()
        // The max walk each call asked for, in order.
        val maxWalks = mutableListOf<MaxWalk>()
        // The step-free level each call asked for, in order.
        val stepFrees = mutableListOf<StepFree>()
        // The trip modes each call asked for, in order.
        val tripModes = mutableListOf<TripModes>()
        override suspend fun journeys(from: TripOrigin, to: TripDestination, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes): List<TripRoute> {
            calls++
            origins += from
            speeds += speed
            maxWalks += maxWalk
            stepFrees += stepFree
            tripModes += modes
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
        // Each fewest-changes-via request: where it planned to and the stop it passed, in order.
        val viaAsked = mutableListOf<Pair<TripDestination, String>>()
        // Its answer per via stop; one in [failFor] fails.
        var byVia: Map<String, List<TripRoute>> = emptyMap()
        override suspend fun fewestChangesVia(from: TripOrigin, to: TripDestination, via: String, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes): List<TripRoute> {
            calls++
            viaAsked += to to via
            if (via in failFor) throw TflException.Offline(null)
            return byVia[via].orEmpty()
        }
    }

    private class FakeClient(val arrivals: MutableMap<String, List<Departure>>) : TflClient {
        val asked = mutableListOf<String>()
        var failStops = emptySet<String>()
        var failStatus = false
        // A stop's next request waits here until the test releases it.
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        override suspend fun arrivals(stopId: String): List<Departure> {
            asked += stopId
            if (stopId in failStops) throw TflException.Offline(null)
            gates.remove(stopId)?.await()
            return arrivals[stopId].orEmpty()
        }
        var omitLines = emptySet<String>()
        // A request asking about any of these fails; these lines are answered as disrupted.
        var failLines = emptySet<String>()
        var disruptedLines = emptySet<String>()
        // Answered with the lookup of which way their alerts go still under way.
        var awaitingLines = emptySet<String>()
        var statusChecks = 0
        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            statusChecks++
            if (failStatus || lineIds.any { it in failLines }) throw TflException.Offline(null)
            return lineIds.filterNot { it in omitLines }.map {
                if (it in disruptedLines) LineStatus(it, 6, "Severe Delays") else LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service")
            }.map { if (it.lineId in awaitingLines) it.copy(awaitingDirections = true) else it }
        }
        // Each stop's closure notices; asking about one in [failDisruptions] fails. Each request's stops, in order.
        var disruptions = emptyMap<String, List<StopDisruption>>()
        var failDisruptions = emptySet<String>()
        val disruptionAsks = mutableListOf<List<String>>()
        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = poleDisruptions(listOf(stopId)).getValue(stopId)
        override suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> {
            disruptionAsks += stopIds
            if (stopIds.any { it in failDisruptions }) throw TflException.Offline(null)
            return stopIds.associateWith { disruptions[it].orEmpty() }
        }
    }

    private fun model(
        planner: JourneyPlanner,
        client: TflClient,
        plans: TripPlans = TripPlans(),
        toIds: List<String> = listOf("C"),
        arrivals: ArrivalsCache = ArrivalsCache(),
        closures: StopClosureCache = StopClosureCache(),
    ) = TripViewModel(
        planner, client, "A", toIds.map { TripDestination.Stop(it) },
        clock = { now }, plans = plans, io = dispatcher, arrivals = arrivals, closureCache = closures,
    )

    // A ride, a walk between two named stations the Planner doesn't place, and a ride on.
    private val onFootWalk = TripLeg(TripLeg.WALKING, "", "", "B1", "B (North)", "B2", "B (South)", at(15), at(18))
    private val onFootRoute = TripRoute(listOf(leg("red", "A", "B1", 5, 15), onFootWalk, leg("blue", "B2", "C", 20, 30)))

    // An index placing the walk's two ends [meters] apart.
    private fun onFootIndex(meters: Double) = app.stopdash.domain.StationIndex(
        listOf(
            app.stopdash.domain.IndexedStation("B1", "B (North)", latitude = 51.5, longitude = -0.12),
            app.stopdash.domain.IndexedStation("B2", "B (South)", latitude = 51.5 + meters / 111_195.0, longitude = -0.12),
        ),
    )

    @Test
    fun `a route's changes on foot are decided with the station index`() = runTest(dispatcher) {
        // About 150 m apart by the index: a change on foot. Both directions: about 400 m, a walk.
        for ((meters, expected) in listOf(150.0 to setOf(onFootWalk), 400.0 to emptySet())) {
            val trip = TripViewModel(
                FakePlanner(listOf(onFootRoute)), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
                clock = { now }, plans = TripPlans(), io = dispatcher, stations = { onFootIndex(meters) },
            )
            trip.refresh()
            advanceUntilIdle()
            assertEquals(listOf(onFootRoute), trip.state.value.routes)
            assertEquals(expected, trip.state.value.changesOnFoot)
        }
    }

    @Test
    fun `a route's changes on foot are decided off the caller's thread`() = runTest(dispatcher) {
        // AGENTS.md *Main-safe by default*: the index is read and the walks measured on the worker,
        // not the main thread the model's updates run on.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }
        val worker = executor.asCoroutineDispatcher()
        // Owned by a store so the model's work can be cancelled before the test ends: left running,
        // a worker resuming onto Main after this test reset it broke whichever test ran next.
        val store = ViewModelStore()
        try {
            val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
            val trip = ViewModelProvider.create(
                store,
                viewModelFactory {
                    initializer {
                        TripViewModel(
                            FakePlanner(listOf(onFootRoute)), FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
                            clock = { now }, plans = TripPlans(), io = worker,
                            stations = {
                                threads += Thread.currentThread().name.substringBefore(" @")
                                onFootIndex(150.0)
                            },
                        )
                    }
                },
            )[TripViewModel::class]
            trip.refresh()
            assertEquals(setOf(onFootWalk), trip.state.first { it.changesOnFoot.isNotEmpty() }.changesOnFoot)
            assertTrue(threads.isNotEmpty())
            assertEquals(setOf("worker"), threads.toSet())
        } finally {
            // Cancel the model, let the worker finish what it holds, then drain what it handed back to
            // Main while this test's Main is still set.
            store.clear()
            executor.shutdown()
            check(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) { "worker didn't stop" }
            advanceUntilIdle()
        }
    }

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

    // A store holding [initial], pruned as the stored one is ([Dismissed.reconcile]).
    private fun reconcilingStore(initial: Set<DismissedAlert>) = object : DismissedAlertsStore {
        val stored = MutableStateFlow(initial)
        override fun dismissed() = stored
        override suspend fun dismiss(alert: DismissedAlert) {
            stored.value = stored.value + alert
        }
        override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
            stored.value = Dismissed.reconcile(stored.value, live, checkedPlaces)
        }
    }

    @Test
    fun `a trip's line check forgets the dismissal of an alert that has ended`() = runTest(dispatcher) {
        val blue = DismissedAlert.ofLineStatus(LineStatus("blue", 6, "Severe Delays"))
        val green = DismissedAlert.ofLineStatus(LineStatus("green", 6, "Severe Delays"))
        val store = reconcilingStore(setOf(blue, green))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = TripViewModel(FakePlanner(listOf(route)), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = store)
        trip.refresh()
        advanceUntilIdle()
        // TfL now reports the blue line good, so its dismissal goes, and the same alert coming back
        // later shows again; the green line, which this trip doesn't check, keeps its (Codex on #367).
        assertEquals(setOf(green), store.stored.value)
    }

    @Test
    fun `a trip's line check forgets a dismissal when TfL answers the line with no status`() = runTest(dispatcher) {
        val blue = DismissedAlert.ofLineStatus(LineStatus("blue", 6, "Severe Delays"))
        val store = reconcilingStore(setOf(blue))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2)))).apply { omitLines = setOf("blue") }
        val trip = TripViewModel(FakePlanner(listOf(route)), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = store)
        trip.refresh()
        advanceUntilIdle()
        // An answer naming nothing for the line is still a verdict on it, as the list counts it (Codex, PR #441).
        assertEquals(emptySet<DismissedAlert>(), store.stored.value)
    }

    @Test
    fun `a trip forgets an ended alert's dismissal on screen even when the store can't be written`() = runTest(dispatcher) {
        val blue = DismissedAlert.ofLineStatus(LineStatus("blue", 6, "Severe Delays"))
        val stored = MutableStateFlow(setOf(blue))
        val store = object : DismissedAlertsStore {
            override fun dismissed() = stored
            override suspend fun dismiss(alert: DismissedAlert) {}
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                throw java.io.IOException("disk full")
            }
        }
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = TripViewModel(FakePlanner(listOf(route)), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = store)
        advanceUntilIdle()
        assertEquals(setOf(blue), trip.dismissed.value)
        trip.refresh()
        advanceUntilIdle()
        // Pruned in memory first, as the list does, so the same alert coming back this session shows
        // (Codex, PR #379).
        assertTrue(trip.dismissed.value.isEmpty())
    }

    @Test
    fun `a trip's finished line check is stored even when the trip is left while it's written`() = runTest(dispatcher) {
        val blue = DismissedAlert.ofLineStatus(LineStatus("blue", 6, "Severe Delays"))
        val stored = MutableStateFlow(setOf(blue))
        val writing = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = stored
            override suspend fun dismiss(alert: DismissedAlert) {}
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                writing.complete(Unit)
                written.await()
                stored.value = Dismissed.reconcile(stored.value, live, checkedPlaces)
            }
        }
        val models = ViewModelStore()
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = ViewModelProvider.create(
            models,
            viewModelFactory {
                initializer { TripViewModel(FakePlanner(listOf(route)), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = store) }
            },
        )[TripViewModel::class]
        trip.refresh()
        advanceUntilIdle()
        assertTrue(writing.isCompleted)
        // The rider leaves the trip while the check's prune is being written: it still lands, so a
        // later trip or the list doesn't read the ended alert's dismissal back (Codex, PR #379).
        models.clear()
        written.complete(Unit)
        advanceUntilIdle()
        assertTrue(stored.value.isEmpty())
    }

    @Test
    fun `a trip keeps a line's dismissal while its alert is live, its check failed, or its alert is being placed`() = runTest(dispatcher) {
        val blue = DismissedAlert.ofLineStatus(LineStatus("blue", 6, "Severe Delays"))
        // Still reported: kept.
        val live = reconcilingStore(setOf(blue))
        val disrupted = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2)))).apply { disruptedLines = setOf("blue") }
        TripViewModel(FakePlanner(listOf(route)), disrupted, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = live).refresh()
        advanceUntilIdle()
        assertEquals(setOf(blue), live.stored.value)
        // Not answered: kept.
        val failed = reconcilingStore(setOf(blue))
        val offline = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2)))).apply { failStatus = true }
        TripViewModel(FakePlanner(listOf(route)), offline, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = failed).refresh()
        advanceUntilIdle()
        assertEquals(setOf(blue), failed.stored.value)
        // Answered before its alerts are placed by direction, which a dismissal of one direction's
        // alert can't be matched against yet: kept until the split lands.
        val awaiting = reconcilingStore(setOf(blue))
        val placing = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2)))).apply { awaitingLines = setOf("blue") }
        TripViewModel(FakePlanner(listOf(route)), placing, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = awaiting).refresh()
        advanceUntilIdle()
        assertEquals(setOf(blue), awaiting.stored.value)
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
    fun `a trip from here plans from the rider's position, and again once they've moved a block`() = runTest(dispatcher) {
        // Synthetic positions (SPEC *Privacy*): the second ~55 m on, the third ~220 m on.
        var at = Coordinates(51.5, -0.12)
        val planner = FakePlanner(listOf(route))
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = TripPlans(), io = dispatcher, origin = { TripOrigin.Here(at) },
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        assertEquals(listOf<TripOrigin>(TripOrigin.Here(Coordinates(51.5, -0.12))), planner.origins)
        // A fix's wander keeps the plan.
        at = Coordinates(51.5005, -0.12)
        trip.refreshFor(2)
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        // A block on, the first walk would be from where they were: planned again from here.
        at = Coordinates(51.502, -0.12)
        trip.refreshFor(3)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        assertEquals(TripOrigin.Here(Coordinates(51.502, -0.12)), planner.origins.last())
    }

    @Test
    fun `a trip from a stop plans from the stop and never re-plans for a move`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refreshFor(1)
        advanceUntilIdle()
        trip.refreshFor(2)
        advanceUntilIdle()
        assertEquals(listOf<TripOrigin>(TripOrigin.Stop("A")), planner.origins)
    }

    @Test
    fun `a reused plan from here re-plans once the rider has moved from where it was made`() = runTest(dispatcher) {
        val plans = TripPlans()
        val destinations = listOf(TripDestination.Stop("C"))
        plans.put("A", destinations, listOf(route), now, TripOrigin.Here(Coordinates(51.5, -0.12)))
        val planner = FakePlanner(listOf(route))
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", destinations, clock = { now }, plans = plans, io = dispatcher,
            origin = { TripOrigin.Here(Coordinates(51.502, -0.12)) },
        )
        // Shown at once from the reused plan, and planned again from where the rider is now.
        assertEquals(listOf(route), trip.state.value.routes)
        trip.refreshFor(1)
        advanceUntilIdle()
        assertEquals(1, planner.calls)
    }

    @Test
    fun `a plan from a stop isn't reused for a trip from here that keys on the same stop, nor the other way`() = runTest(dispatcher) {
        val plans = TripPlans()
        val destinations = listOf(TripDestination.Stop("C"))
        plans.put("A", destinations, listOf(route), now, TripOrigin.Stop("A"))
        assertNull(plans.get("A", destinations, here = true))
        val planner = FakePlanner(listOf(route))
        val here = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", destinations, clock = { now }, plans = plans, io = dispatcher,
            origin = { TripOrigin.Here(Coordinates(51.5, -0.12)) },
        )
        // Nothing to show at once: the stop's plan opens with no walk from the rider.
        assertNull(here.state.value.routes)
        here.refreshFor(1)
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        // And the plan from here doesn't stand in for the stop's.
        assertNotNull(plans.get("A", destinations, here = true))
        assertEquals(now, plans.get("A", destinations)?.second)
    }

    @Test
    fun `an origin the screen sets after the model is made is what the next plan starts from`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = TripPlans(), io = dispatcher, origin = { TripOrigin.Here(Coordinates(51.5, -0.12)) },
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        // A rotation recomposes over the retained model: the new composition's origin replaces the old.
        trip.origin = { TripOrigin.Here(Coordinates(51.502, -0.12)) }
        trip.refreshFor(2)
        advanceUntilIdle()
        assertEquals(TripOrigin.Here(Coordinates(51.502, -0.12)), planner.origins.last())
    }

    @Test
    fun `plans at the rider's walking speed, and again at once when it changes`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val plans = TripPlans()
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = plans, io = dispatcher, walkingSpeed = WalkingSpeed.FAST,
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        assertEquals(listOf(WalkingSpeed.FAST), planner.speeds)
        // The same speed again changes nothing.
        trip.walkingSpeed = WalkingSpeed.FAST
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        trip.walkingSpeed = WalkingSpeed.SLOW
        advanceUntilIdle()
        assertEquals(listOf(WalkingSpeed.FAST, WalkingSpeed.SLOW), planner.speeds)
        // Each pace's plan is kept apart: a trip reopened at another pace isn't shown the other's.
        assertNotNull(plans.get("A", listOf(TripDestination.Stop("C")), speed = WalkingSpeed.FAST))
        assertNull(plans.get("A", listOf(TripDestination.Stop("C")), speed = WalkingSpeed.AVERAGE))
    }

    @Test
    fun `routes timed at the old walking speed aren't shown under a new one`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = TripPlans(), io = dispatcher, walkingSpeed = WalkingSpeed.FAST,
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        assertEquals(listOf(route), trip.state.value.routes)
        // The plan at the new speed fails: the fast plan's routes don't stand in for it.
        planner.failWith = TflException.Offline(null)
        trip.walkingSpeed = WalkingSpeed.SLOW
        assertNull(trip.state.value.routes)
        advanceUntilIdle()
        assertNull(trip.state.value.routes)
        assertNotNull(trip.state.value.planError)
        // Back to the speed a plan was kept for: it's shown at once, without planning again.
        val calls = planner.calls
        trip.walkingSpeed = WalkingSpeed.FAST
        assertEquals(listOf(route), trip.state.value.routes)
        assertNull(trip.state.value.planError)
        advanceUntilIdle()
        assertEquals(calls, planner.calls)
    }

    @Test
    fun `plans under the rider's max walk, and again at once when it changes`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val plans = TripPlans()
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = plans, io = dispatcher, maxWalk = MaxWalk.FIFTEEN,
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        assertEquals(listOf(MaxWalk.FIFTEEN), planner.maxWalks)
        // The same limit again changes nothing.
        trip.maxWalk = MaxWalk.FIFTEEN
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        trip.maxWalk = MaxWalk.SIXTY
        advanceUntilIdle()
        assertEquals(listOf(MaxWalk.FIFTEEN, MaxWalk.SIXTY), planner.maxWalks)
        // At the rider's pace throughout.
        assertEquals(listOf(WalkingSpeed.AVERAGE, WalkingSpeed.AVERAGE), planner.speeds)
        // Each limit's plan is kept apart: a trip reopened under another isn't shown the other's.
        val destinations = listOf(TripDestination.Stop("C"))
        assertNotNull(plans.get("A", destinations, maxWalk = MaxWalk.FIFTEEN))
        assertNotNull(plans.get("A", destinations, maxWalk = MaxWalk.SIXTY))
        assertNull(plans.get("A", destinations, maxWalk = MaxWalk.DEFAULT))
        // Back to a limit a plan was kept for: shown at once, without planning again.
        trip.maxWalk = MaxWalk.FIFTEEN
        assertEquals(listOf(route), trip.state.value.routes)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
    }

    @Test
    fun `every plan waits for the walk options to be read, a pull included`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = TripPlans(), io = dispatcher, optionsLoaded = false,
        )
        trip.refreshFor(1)
        trip.pullRefresh()
        advanceTimeBy(1_000)
        runCurrent()
        // Nothing planned under the defaults, and "Planning…" meanwhile.
        assertEquals(0, planner.calls)
        assertTrue(trip.state.value.planning)
        // The read lands: the options, then the flag, as the screen sets them.
        trip.maxWalk = MaxWalk.SIXTY
        trip.optionsLoaded = true
        advanceUntilIdle()
        // One plan under the rider's own limit serves the first showing and the pull; the change
        // while it waited needed no plan of its own.
        assertEquals(listOf(MaxWalk.SIXTY), planner.maxWalks.distinct())
        assertEquals(listOf(route), trip.state.value.routes)
        assertEquals(planner.maxWalks.size, planner.calls)
        assertTrue(planner.calls <= 2)
    }

    @Test
    fun `a read that never lands delays the plan, then it plans with the defaults and again once read`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val warnings = mutableListOf<String>()
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = TripPlans(), io = dispatcher, optionsLoaded = false, warn = { warnings += it },
        )
        trip.refreshFor(1)
        advanceTimeBy(TripViewModel.OPTIONS_WAIT.toMillis() + 1)
        runCurrent()
        assertEquals(listOf(MaxWalk.DEFAULT), planner.maxWalks)
        assertTrue(warnings.any { it.startsWith("trip options not read") })
        // The read lands late: the trip plans again under the rider's own limit.
        trip.maxWalk = MaxWalk.SIXTY
        trip.optionsLoaded = true
        advanceUntilIdle()
        assertEquals(listOf(MaxWalk.DEFAULT, MaxWalk.SIXTY), planner.maxWalks)
    }

    @Test
    fun `plans at the rider's step-free level, and again at once when it changes`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val plans = TripPlans()
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = plans, io = dispatcher, stepFree = StepFree.STATION,
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        assertEquals(listOf(StepFree.STATION), planner.stepFrees)
        trip.stepFree = StepFree.STATION
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        // Routes planned for another level aren't shown under this one while it plans.
        trip.stepFree = StepFree.FULLY
        assertNull(trip.state.value.routes)
        advanceUntilIdle()
        assertEquals(listOf(StepFree.STATION, StepFree.FULLY), planner.stepFrees)
        // At the rider's walk throughout.
        assertEquals(listOf(MaxWalk.DEFAULT, MaxWalk.DEFAULT), planner.maxWalks)
        val destinations = listOf(TripDestination.Stop("C"))
        assertNotNull(plans.get("A", destinations, stepFree = StepFree.STATION))
        assertNotNull(plans.get("A", destinations, stepFree = StepFree.FULLY))
        assertNull(plans.get("A", destinations, stepFree = StepFree.ANY))
        // Back to a level a plan was kept for: shown at once, without planning again.
        trip.stepFree = StepFree.STATION
        assertEquals(listOf(route), trip.state.value.routes)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
    }

    @Test
    fun `plans over the rider's modes, and again at once when they change`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val plans = TripPlans()
        val bus = ModeGroups.ALL.single { it.key == "bus" }
        val tram = ModeGroups.ALL.single { it.key == "tram" }
        val noBus = TripModes.DEFAULT.with(bus, ride = false)
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = plans, io = dispatcher, tripModes = noBus,
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        assertEquals(listOf(noBus), planner.tripModes)
        // The same choice again plans nothing.
        trip.tripModes = TripModes.DEFAULT.with(bus, ride = false)
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        // Routes planned over other modes aren't shown under these while they plan.
        val noBusOrTram = noBus.with(tram, ride = false)
        trip.tripModes = noBusOrTram
        assertNull(trip.state.value.routes)
        advanceUntilIdle()
        assertEquals(listOf(noBus, noBusOrTram), planner.tripModes)
        val destinations = listOf(TripDestination.Stop("C"))
        assertNotNull(plans.get("A", destinations, modes = noBus))
        assertNotNull(plans.get("A", destinations, modes = noBusOrTram))
        assertNull(plans.get("A", destinations, modes = TripModes.DEFAULT))
        // Back to modes a plan was kept for: shown at once, without planning again.
        trip.tripModes = noBus
        assertEquals(listOf(route), trip.state.value.routes)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
    }

    @Test
    fun `a walk change before the trip starts opens it on the plan kept for the new walk`() = runTest(dispatcher) {
        // The model is made before the walk settings are read: it opens on the defaults' kept plan
        // until they land, then on the rider's own, without planning before it's started.
        val destinations = listOf(TripDestination.Stop("C"))
        val plans = TripPlans()
        val atDefault = TripRoute(listOf(leg("red", "A", "C", 5, 15)))
        plans.put("A", destinations, listOf(atDefault), now)
        plans.put("A", destinations, listOf(route), now, maxWalk = MaxWalk.SIXTY)
        val planner = FakePlanner(listOf(route))
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", destinations,
            clock = { now }, plans = plans, io = dispatcher,
        )
        assertEquals(listOf(atDefault), trip.state.value.routes)
        trip.maxWalk = MaxWalk.SIXTY
        assertEquals(listOf(route), trip.state.value.routes)
        advanceUntilIdle()
        assertEquals(0, planner.calls)
    }

    @Test
    fun `a plan still running when the max walk changes isn't shown under the new one`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route)).apply { delays = mapOf("C" to 1_000L) }
        val plans = TripPlans()
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = plans, io = dispatcher,
        )
        trip.refreshFor(1)
        advanceTimeBy(500)
        runCurrent()
        trip.maxWalk = MaxWalk.SIXTY
        // The first plan answers under the old limit: nothing of it shows.
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(trip.state.value.routes)
        // It's still kept for that limit, and the new one is planned next.
        val destinations = listOf(TripDestination.Stop("C"))
        assertNotNull(plans.get("A", destinations, maxWalk = MaxWalk.DEFAULT))
        advanceUntilIdle()
        assertEquals(listOf(MaxWalk.DEFAULT, MaxWalk.SIXTY), planner.maxWalks)
        assertEquals(listOf(route), trip.state.value.routes)
        assertNotNull(plans.get("A", destinations, maxWalk = MaxWalk.SIXTY))
    }

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

    // A route that rides A to B, then B to S, then walks on to the place (a blank stop id).
    private val changingToPlace = TripRoute(
        listOf(
            leg("red", "A", "B", 5, 15),
            leg("blue", "B", "S", 20, 30),
            TripLeg(mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = "S", fromName = "S", toId = "", toName = "Home", departure = at(30), arrival = at(36)),
        ),
    )

    @Test
    fun `a trip to a place asks once more for one ride via where its fastest route gets off`() = runTest(dispatcher) {
        // The Planner's routes to the place change at B; via S, one line runs the whole way, and
        // the Planner walks on to the place itself.
        val walkOn = changingToPlace.legs.last().copy(departure = at(28), arrival = at(34))
        val direct = TripRoute(listOf(leg("green", "A", "S", 6, 28), walkOn))
        val home = TripDestination.Place(Coordinates(51.5, -0.12), "Home")
        val planner = FakePlanner(emptyList()).apply {
            byDestination = mapOf("Home" to listOf(changingToPlace))
            // Riding as often as the fastest adds nothing.
            byVia = mapOf("S" to listOf(direct, changingToPlace.copy(legs = changingToPlace.legs.map { it.copy(lineName = "again") })))
        }
        val warnings = mutableListOf<String>()
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Place(Coordinates(51.5, -0.12), "Home")),
            clock = { now }, plans = TripPlans(), io = dispatcher, warn = { warnings += it },
        )
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf<Pair<TripDestination, String>>(home to "S"), planner.viaAsked)
        assertEquals(2, planner.calls)
        // The one ride as the Planner gave it, final walk and all.
        assertEquals(listOf(changingToPlace, direct), trip.state.value.routes)
        assertTrue(warnings.contains("trip plan via the fastest route's last stop: 1 of 2 routes ride fewer times"))
    }

    @Test
    fun `a trip to a place stands when asking once more fails, and says so`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(changingToPlace)).apply { failFor = setOf("S") }
        val warnings = mutableListOf<String>()
        val trip = TripViewModel(
            planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Place(Coordinates(51.5, -0.12), "Home")),
            clock = { now }, plans = TripPlans(), io = dispatcher, warn = { warnings += it },
        )
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf(changingToPlace), trip.state.value.routes)
        assertNull(trip.state.value.planError)
        assertFalse(trip.state.value.planIncomplete)
        assertTrue(warnings.contains("trip plan via the fastest route's last stop failed: Offline"))
    }

    @Test
    fun `a trip to a stop, or whose fastest route rides once, asks nothing more`() = runTest(dispatcher) {
        // To a stop the Planner's own fewest changes already plans there.
        val toStop = FakePlanner(listOf(route))
        model(toStop, FakeClient(mutableMapOf())).apply { refresh() }
        advanceUntilIdle()
        assertTrue(toStop.viaAsked.isEmpty())
        // To a place, the fastest riding once: nothing rides fewer times.
        val once = TripRoute(listOf(leg("red", "A", "B", 5, 15), changingToPlace.legs.last().copy(fromId = "B", departure = at(15), arrival = at(20))))
        val toPlace = FakePlanner(listOf(changingToPlace, once))
        TripViewModel(
            toPlace, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Place(Coordinates(51.5, -0.12), "Home")),
            clock = { now }, plans = TripPlans(), io = dispatcher,
        ).refresh()
        advanceUntilIdle()
        assertTrue(toPlace.viaAsked.isEmpty())
        assertEquals(1, toPlace.calls)
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

    // A withheld arrival means the Planner's own departure for a leg is missed: a plan old enough
    // is asked for again at once, so its timetable says when the next one leaves. Once per plan.
    @Test
    fun `a withheld arrival plans again once the plan is old enough, once per plan`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val warnings = mutableListOf<String>()
        val trip = TripViewModel(planner, FakeClient(mutableMapOf()), "A", listOf(TripDestination.Stop("C")), warn = { warnings += it }, clock = { now }, plans = TripPlans(), io = dispatcher)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        val why = TripTiming.Withheld(1, "bus", "9", TripTiming.Reason.INFREQUENT, 2, Duration.ofMinutes(4), null, Duration.ofMinutes(3))
        // A fresh plan isn't asked again: its departures haven't moved on.
        now = now.plus(Duration.ofMinutes(4))
        trip.noteWithheld(mapOf("r" to why))
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        now = now.plus(TripViewModel.REPLAN_WITHHELD)
        trip.noteWithheld(mapOf("r" to why))
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        // The re-plan says which leg and why, even when the per-route line was already logged.
        assertTrue(warnings.any { it.startsWith("trip re-planned (plan 9 min old): arrival withheld at leg 2 (bus 9): infrequent") })
        // The new plan is fresh again: the next withheld tick waits for it to age.
        trip.noteWithheld(mapOf("r" to why))
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        // Nothing withheld asks nothing.
        now = now.plus(TripViewModel.REPLAN_WITHHELD)
        trip.noteWithheld(mapOf("r" to null))
        advanceUntilIdle()
        assertEquals(2, planner.calls)
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

    // With National Rail hidden, only the Overground route from the station is timed, so its board
    // isn't asked for; showing National Rail again brings back the train from the same station,
    // which is fetched again at once, board and all, though its last fetch is still current.
    @Test
    fun `a station's board is left out while National Rail is hidden and fetched once it shows`() = runTest(dispatcher) {
        val station = "910GEXAMPLE"
        val overground = TripRoute(listOf(leg("mildmay", station, "C", 5, 20).copy(mode = "overground")))
        val train = TripRoute(listOf(leg("thameslink", station, "C", 8, 18).copy(mode = "national-rail")))
        val boards = mutableListOf<Boolean>()
        val client = object : TflClient by FakeClient(mutableMapOf()) {
            override suspend fun arrivals(stopId: String, railBoard: Boolean): List<Departure> {
                if (stopId == station) boards += railBoard
                return emptyList()
            }
            override fun hasRailBoard(stopId: String) = stopId == station
        }
        val trip = TripViewModel(
            FakePlanner(listOf(overground, train)), client, "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = TripPlans(), io = dispatcher,
        )
        trip.hiddenModes = setOf("national-rail")
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf(false), boards)

        trip.hiddenModes = emptySet()
        advanceUntilIdle()
        assertEquals(listOf(false, true), boards)
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

    // Six bus routes fill the routes timed; the tube, seventh, is open on screen: its stop is fetched
    // though it's past the cap, so an open route is never left without live times.
    @Test
    fun `the open route's stops are fetched past the cap`() = runTest(dispatcher) {
        val buses = (1..6).map { TripRoute(listOf(leg("b$it", "S$it", "C", 5, 10L + it).copy(mode = "bus"))) }
        val tube = TripRoute(listOf(leg("red", "T", "C", 5, 30)))
        val client = FakeClient(mutableMapOf())
        val trip = TripViewModel(
            FakePlanner(buses + tube), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, plans = TripPlans(), io = dispatcher,
        )
        // Saved whole ([OpenRoute.encode]), as a train through a change is: fetched for by its plan.
        trip.openRoute.value = OpenRoute(routeKey(tube), at = 0, ride = tube.legs.single()).encode()
        trip.refresh()
        advanceUntilIdle()
        assertTrue("T" in client.asked)
    }

    @Test
    fun `the open route is timed past the cap while the plan offers it`() {
        val routes = (0..TripViewModel.MAX_ROUTES).map { i -> TripRoute(listOf(leg("line$i", "A", "C", 5, 10L + i))) }
        val last = routes.last()
        assertFalse(last in TripViewModel.bestOf(routes))
        val kept = TripViewModel.bestOf(routes, keep = routeKey(last))
        assertTrue(last in kept)
        assertEquals(TripViewModel.MAX_ROUTES + 1, kept.size)
        // Its lines load route data too.
        assertTrue("line${TripViewModel.MAX_ROUTES}" in timedLineIds(routes, emptySet(), keep = setOf(routeKey(last))))
        // A key the plan doesn't offer adds nothing.
        assertEquals(TripViewModel.bestOf(routes), TripViewModel.bestOf(routes, keep = "gone"))
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
    fun `the route walking least is timed past the cap`() {
        // Six routes each walking twelve minutes to the train fill the cap; a bus to the station,
        // arriving last, spares nearly all that walk.
        fun walk(minutes: Long) = leg("", "X", "A", 0, minutes).copy(mode = TripLeg.WALKING)
        val walks = (0 until TripViewModel.MAX_ROUTES).map { i -> TripRoute(listOf(walk(12), leg("line$i", "A", "C", 14, 20L + i))) }
        val bus = TripRoute(listOf(walk(2), leg("43", "Q", "A", 3, 12).copy(mode = "bus"), leg("line0", "A", "C", 14, 40)))
        val best = TripViewModel.bestOf(walks + bus)
        assertTrue(bus in best)
        assertEquals(TripViewModel.MAX_ROUTES + 1, best.size)
        // Kept whatever it saves: the live ranking's first card, which it's judged against, may
        // walk farther than the timetable's soonest.
        val shortWalks = (0 until TripViewModel.MAX_ROUTES).map { i -> TripRoute(listOf(walk(3), leg("line$i", "A", "C", 14, 20L + i))) }
        assertTrue(bus in TripViewModel.bestOf(shortWalks + bus))
        // One route more at most: none past the cap when the least walking is already among the soonest.
        val walksLeast = TripRoute(listOf(walk(1), leg("line9", "A", "C", 14, 15)))
        assertEquals(TripViewModel.MAX_ROUTES, TripViewModel.bestOf(walks + walksLeast + bus).size)
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
    fun `a pull plans again at once and fetches every stop afresh`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9))))
        val trip = model(planner, client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        assertEquals(listOf("A", "B"), client.asked.sorted())
        // A minute on, well inside the plan's fifteen and the stops' minute: a tick would do neither.
        now = now.plusSeconds(30)
        trip.pullRefresh()
        assertTrue(trip.pulling.value)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        assertEquals(listOf("A", "A", "B", "B"), client.asked.sorted())
        assertEquals(now, trip.state.value.live.getValue("A").fetchedAt)
        assertFalse(trip.pulling.value)
        // What the pull fetched is from after it: the next tick reuses it.
        trip.refresh()
        advanceUntilIdle()
        assertEquals(4, client.asked.size)
    }

    @Test
    fun `a pull after the clock was set back still asks afresh for what was fetched before it`() = runTest(dispatcher) {
        var setBack = Duration.ZERO
        SteadyClock.source = object : SteadyClock.Source {
            override val frame: SteadyClock.Frame? = null
            override fun offset(): Duration = setBack
        }
        try {
            val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9))))
            val trip = model(FakePlanner(listOf(route)), client)
            trip.refresh()
            advanceUntilIdle()
            assertEquals(listOf("A", "B"), client.asked.sorted())
            // Thirty seconds on, the clock is set back an hour, and the rider pulls: the stops were
            // fetched before the pull, though their steady stamps read later than its wall time.
            setBack = Duration.ofHours(1)
            now = now.plusSeconds(30).minus(Duration.ofHours(1))
            trip.pullRefresh()
            advanceUntilIdle()
            assertEquals(listOf("A", "A", "B", "B"), client.asked.sorted())
        } finally {
            SteadyClock.source = null
        }
    }

    @Test
    fun `a pull during a refresh plans again after it, its indicator up until then`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val client = FakeClient(mutableMapOf())
        val trip = model(planner, client)
        trip.refresh()
        trip.pullRefresh()
        assertTrue(trip.pulling.value)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        // The refresh under way fetched every stop after the pull asked: those are what it asked for.
        assertEquals(listOf("A", "B"), client.asked.sorted())
        assertFalse(trip.pulling.value)
    }

    @Test
    fun `a pull during a refresh fetches the new plan's stops, however recent`() = runTest(dispatcher) {
        // The re-plan the pull asks for boards at D instead, which another screen fetched 20 s ago.
        val fromD = TripRoute(listOf(leg("green", "D", "C", 5, 15)))
        var calls = 0
        val planner = object : JourneyPlanner {
            override suspend fun journeys(from: TripOrigin, to: TripDestination, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes): List<TripRoute> =
                if (calls++ == 0) listOf(route) else listOf(fromD)
        }
        val cache = ArrivalsCache()
        cache.put("D", listOf(train("green", "C", 6)), now.minusSeconds(20))
        val client = FakeClient(mutableMapOf("D" to listOf(train("green", "C", 7))))
        val trip = model(planner, client, arrivals = cache)
        trip.refresh()
        trip.pullRefresh()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(listOf(fromD), trip.state.value.routes)
        // The refresh under way fetched A and B, after the pull asked, so they aren't asked for twice;
        // D, held from before the pull, is asked for again (Codex, PR #373).
        assertEquals(listOf("A", "B", "D"), client.asked.sorted())
        assertEquals(TripViewModel.StopLive(listOf(train("green", "C", 7)), now), trip.state.value.live["D"])
    }

    @Test
    fun `a re-plan from the fresh fix a pull takes fetches its new stop, however recent, under the pull's indicator`() = runTest(dispatcher) {
        // Synthetic positions (SPEC *Privacy*): the fix the pull takes lands ~220 m on, where the
        // plan boards at D, which another screen fetched 20 s before the pull.
        var at = Coordinates(51.5, -0.12)
        val fromD = TripRoute(listOf(leg("green", "D", "C", 5, 15)))
        var calls = 0
        // The re-plan from that fix answers when the test says, so its indicator can be seen.
        val replanned = CompletableDeferred<Unit>()
        val planner = object : JourneyPlanner {
            override suspend fun journeys(from: TripOrigin, to: TripDestination, speed: WalkingSpeed, maxWalk: MaxWalk, stepFree: StepFree, modes: TripModes): List<TripRoute> =
                if (calls++ < 2) listOf(route) else listOf(fromD).also { replanned.await() }
        }
        val cache = ArrivalsCache()
        cache.put("D", listOf(train("green", "C", 6)), now.minusSeconds(20))
        val client = FakeClient(mutableMapOf("D" to listOf(train("green", "C", 7))))
        val trip = TripViewModel(
            planner, client, "A", listOf(TripDestination.Stop("C")),
            clock = { now }, plans = TripPlans(), io = dispatcher, arrivals = cache, origin = { TripOrigin.Here(at) },
        )
        trip.refreshFor(1)
        advanceUntilIdle()
        // The pull's own run ends before the fix lands; its indicator waits on the fix.
        trip.pullRefresh(awaitFix = true)
        advanceUntilIdle()
        assertTrue(trip.pulling.value)
        now = now.plusSeconds(5)
        at = Coordinates(51.502, -0.12)
        trip.refreshFor(2)
        // The re-locate has ended with a re-pick the trip is refreshing for: that run is the pull's.
        trip.fixSettled(2)
        advanceUntilIdle()
        assertEquals(3, calls)
        assertTrue(trip.pulling.value)
        replanned.complete(Unit)
        advanceUntilIdle()
        assertFalse(trip.pulling.value)
        assertEquals(listOf(fromD), trip.state.value.routes)
        // D's arrivals were asked for before the pull: asked for again (maintainer, 2026-09-29).
        assertEquals(1, client.asked.count { it == "D" })
        assertEquals(TripViewModel.StopLive(listOf(train("green", "C", 7)), now), trip.state.value.live["D"])
    }

    @Test
    fun `a pull whose fresh fix brings no new re-pick lets go of its indicator`() = runTest(dispatcher) {
        val trip = model(FakePlanner(listOf(route)), FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9)))))
        trip.refreshFor(1)
        advanceUntilIdle()
        // Settled during the pull's own run: the indicator goes when that run ends.
        trip.pullRefresh(awaitFix = true)
        trip.fixSettled(1)
        assertTrue(trip.pulling.value)
        advanceUntilIdle()
        assertFalse(trip.pulling.value)
        // Settled after it: the indicator goes at once.
        trip.pullRefresh(awaitFix = true)
        advanceUntilIdle()
        assertTrue(trip.pulling.value)
        trip.fixSettled(1)
        assertFalse(trip.pulling.value)
        // A pull from a From… station waits on no fix.
        trip.pullRefresh()
        advanceUntilIdle()
        assertFalse(trip.pulling.value)
    }

    @Test
    fun `a trip taking over from one pulled on plans afresh`() = runTest(dispatcher) {
        // The pull's fresh fix found a new nearest stop: a trip of its own, planned 20 s before the
        // pull with its plan kept for reuse ([kept]; the pulled trip keys its plans elsewhere).
        val planner = FakePlanner(listOf(route))
        val kept = TripPlans()
        val cache = ArrivalsCache()
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9))))
        model(planner, client, kept, arrivals = cache).refresh()
        advanceUntilIdle()
        now = now.plusSeconds(20)
        val at = model(planner, client, TripPlans(), arrivals = cache).pullRefresh()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        assertEquals(4, client.asked.size)
        now = now.plusSeconds(5)
        // Without the pull carried, the trip takes up the plan kept from before it.
        model(planner, client, kept, arrivals = cache).refreshFor(null)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        // Carried, it plans again; the arrivals the pull's own run fetched are from after it, so
        // they're taken up rather than asked for again.
        val next = model(planner, client, kept, arrivals = cache)
        next.carryPull(at)
        // The pull's indicator is up in the trip that took over until its run ends.
        assertTrue(next.pulling.value)
        next.refreshFor(null)
        advanceUntilIdle()
        assertEquals(3, planner.calls)
        assertEquals(4, client.asked.size)
        assertFalse(next.pulling.value)
    }

    @Test
    fun `a pull carried into a trip already showing plans and fetches it afresh, once`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "B", 9))))
        val trip = model(planner, client)
        trip.refreshFor(null)
        advanceUntilIdle()
        now = now.plusSeconds(20)
        trip.carryPull(now)
        assertTrue(trip.pulling.value)
        advanceUntilIdle()
        assertFalse(trip.pulling.value)
        assertEquals(2, planner.calls)
        assertEquals(listOf("A", "A", "B", "B"), client.asked.sorted())
        // The screen carries it on every composition: the same pull again does nothing.
        trip.carryPull(now)
        assertFalse(trip.pulling.value)
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        assertEquals(4, client.asked.size)
    }

    @Test
    fun `a pull's fetch that fails keeps the stop's last arrivals, marked failed`() = runTest(dispatcher) {
        val before = listOf(train("red", "B", 9))
        val client = FakeClient(mutableMapOf("A" to before))
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        now = now.plusSeconds(30)
        client.failStops = setOf("A")
        trip.pullRefresh()
        advanceUntilIdle()
        // Asked for again, and TfL didn't answer: the leg keeps what it last knew rather than go
        // blank, as any failed refresh does (SPEC *When something is wrong*).
        assertEquals(listOf("A", "A", "B", "B"), client.asked.sorted())
        assertEquals(TripViewModel.StopLive(before, now.minusSeconds(30), failed = true), trip.state.value.live["A"])
    }

    @Test
    fun `a pull whose plan fails says why and lets go of its indicator`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refresh()
        advanceUntilIdle()
        planner.failWith = TflException.Offline(null)
        trip.pullRefresh()
        advanceUntilIdle()
        assertNotNull(trip.state.value.planError)
        // The last plan stays up under its error.
        assertEquals(listOf(route), trip.state.value.routes)
        assertFalse(trip.pulling.value)
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
    fun `a status check split across requests keeps what one answered when another fails`() = runTest(dispatcher) {
        // Line ids long enough that TfL needs a request for each (LineStatusBatch).
        val first = "red-" + "x".repeat(150)
        val second = "blue-" + "y".repeat(150)
        val long = TripRoute(listOf(leg(first, "A", "B", 5, 15), leg(second, "B", "C", 20, 30)))
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(long)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(LineStatus.GOOD_SERVICE, trip.state.value.statuses.getValue(second).severity)

        // Now the first line is disrupted, and the request asking about the second fails.
        client.disruptedLines = setOf(first)
        client.failLines = setOf(second)
        trip.refresh()
        advanceUntilIdle()
        val state = trip.state.value
        assertEquals(6, state.statuses.getValue(first).severity)
        // The failed request's line keeps its last status, and the trip says it couldn't check.
        assertEquals(LineStatus.GOOD_SERVICE, state.statuses.getValue(second).severity)
        assertTrue(state.statusFailed)
        // Only that line's check failed: the answered one can still be vouched for.
        assertEquals(setOf(second), state.statusFailedLines)
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
        // A line's work still to come reaches the quiet leg's page too.
        val planned = app.stopdash.domain.PlannedAlert("Part Closure", "No service on Saturday 3 October.", java.time.LocalDate.of(2026, 10, 3))
        val withPlanned = delayed.copy(statuses = delayed.statuses.mapValues { (_, status) -> status.copy(planned = listOf(planned)) })
        assertEquals(listOf(planned), legStatusRow(withPlanned, leg, now).plannedAlerts)
        // The collapsed card notes it too.
        assertEquals(planned, linesPlanned(listOf(leg), withPlanned.statuses))
        assertNull(linesPlanned(listOf(leg), delayed.statuses))
        // Dismissed, it leaves the quiet leg's page (the disruption stays).
        val marked = withDismissedMarked(legStatusRow(withPlanned, leg, now), setOf(app.stopdash.domain.DismissedAlert.ofPlanned("blue", planned)))
        assertTrue(marked.plannedAlerts.isEmpty())
        assertEquals("Minor Delays", marked.status?.description)
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
        // A bus not yet on the poles its route says ([busesSettled]): its route not loaded.
        assertFalse(canStart(TripRoute(listOf(bus)), emptyMap(), originUnconfirmed = false))
        assertTrue(canStart(TripRoute(listOf(bus)), mapOf("1" to routes), originUnconfirmed = false))
        assertFalse(canStart(TripRoute(listOf(bus)), mapOf("1" to routes), originUnconfirmed = true))
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
        // A check that failed and is being asked again reads as checking, never as clean.
        assertEquals(true, statusNote(TripViewModel.State(refreshing = true, statusFailed = true), unchecked = false))
        assertEquals(true, statusNote(TripViewModel.State(refreshing = true, closuresFailed = setOf("A")), unchecked = false))
        assertEquals(true, statusNote(TripViewModel.State(planning = true), unchecked = false, closuresFailed = true))
        // A bus whose line's route is still loading can't be placed yet: its stops are being checked.
        assertEquals(true, statusNote(TripViewModel.State(), unchecked = true, loading = true))
        assertNull(statusNote(TripViewModel.State(), unchecked = false, closuresFailed = false, statusFailed = false, loading = true))
        // A route whose own checks all answered says nothing while another's retry runs.
        assertNull(statusNote(TripViewModel.State(refreshing = true, closuresFailed = setOf("A")), unchecked = false, closuresFailed = false, statusFailed = false))
    }

    @Test
    fun `the couldn't-check note names the lines, then the stops, that couldn't be checked`() {
        val now = at(0)
        val red = TripLeg("tube", "red", "Red", "A", "Alpha", "B", "Bravo", at(10), at(20))
        val bus = TripLeg("bus", "1", "Route 1", "B", "Bravo", "P1", "Papa", at(25), at(35), toArea = "490G0000P")
        val route = TripRoute(listOf(red, bus))
        val estimates = listOf(TripTiming.Estimate(route, TripTiming.Basis.LIVE, at(35), emptyList(), false, now))
        val sequences = mapOf(
            "1" to LineSequence(
                routes = listOf(LineRoute("B ↔ P1", listOf("B", "P1"))),
                stopNames = mapOf("B" to "Bravo", "P1" to "Papa"),
                stopAreas = mapOf("P1" to "490G0000P"),
            ),
        )
        val good = LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service")
        val checked = TripViewModel.State(
            routes = listOf(route),
            statuses = mapOf("red" to good, "1" to good.copy(lineId = "1")),
            closures = mapOf("A" to emptyList(), "B" to emptyList(), "P1" to emptyList()),
        )
        fun names(state: TripViewModel.State, lines: Map<TripLeg, RideLines> = emptyMap(), seqs: Map<String, LineSequence?> = sequences) =
            uncheckedNames(estimates, state, now, seqs, lines)
        // Every line and stop checked: nothing to name.
        assertEquals(emptyList<String>(), names(checked))
        // A line with no status known, one whose check failed, a stop whose check failed: lines first.
        assertEquals(listOf("Red", "Bravo"), names(checked.copy(statusUnknown = setOf("red"), closuresFailed = setOf("B"))))
        assertEquals(listOf("Route 1"), names(checked.copy(statusFailed = true, statusFailedLines = setOf("1"))))
        assertEquals(listOf("Route 1"), names(checked.copy(statuses = checked.statuses - "1")))
        // A stop with no check held yet, or one not yet vouched for.
        assertEquals(listOf("Alpha"), names(checked.copy(closures = checked.closures - "A")))
        assertEquals(listOf("Papa"), names(checked.copy(closuresUnknown = setOf("P1"))))
        // The bus not placed on a pole of its pair (its route failed to load): both its stops, once each.
        assertEquals(listOf("Bravo", "Papa"), names(checked, seqs = mapOf("1" to null)))
        // Still loading, it's being checked, not failing to be; failed or placed, it isn't awaited.
        assertTrue(awaitingRoutes(estimates, emptyMap()))
        assertFalse(awaitingRoutes(estimates, mapOf("1" to null)))
        // A failed route being loaded again is being checked again.
        assertTrue(awaitingRoutes(estimates, mapOf("1" to null), loading = setOf("1")))
        // An older copy held while it reloads still places it.
        assertFalse(awaitingRoutes(estimates, sequences, loading = setOf("1")))
        assertFalse(awaitingRoutes(estimates, sequences))
        // A ride named by no stop pair needs no placing, loaded or not.
        assertFalse(awaitingRoutes(listOf(TripTiming.Estimate(TripRoute(listOf(red)), TripTiming.Basis.LIVE, at(20), emptyList(), false, now)), emptyMap()))
        // Another line a ride shows, never checked, and a pole of its own with no check held.
        val two = bus.copy(lineId = "2", lineName = "Route 2", toId = "P2", toName = "Papa (other side)")
        val lines = mapOf(bus to RideLines(listOf(bus, two), listOf(bus, two)))
        assertEquals(listOf("Route 2", "Papa (other side)"), names(checked, lines))
        // A closed stop is no gap in the check: it's known.
        assertEquals(emptyList<String>(), names(checked.copy(closures = checked.closures + ("B" to listOf(StopDisruption("Station closed due to a power failure"))))))
    }

    @Test
    fun `an opened route says it couldn't check only a stop of its own`() {
        val red = TripLeg("tube", "red", "Red", "A", "A", "B", "B", at(10), at(20))
        val bus = TripLeg("bus", "1", "1", "S", "S", "P1", "P1", at(10), at(20), toArea = "490G0000P")
        // The latest check failed at C, a stop only another route uses, and at P2, the other pole of P1's pair.
        val state = TripViewModel.State(closuresFailed = setOf("C", "P2"), areaPoles = mapOf("490G0000P" to listOf("P1", "P2")))
        assertFalse(routeClosuresFailed(TripRoute(listOf(red)), state, emptyMap()))
        assertNull(statusNote(state, unchecked = false, closuresFailed = routeClosuresFailed(TripRoute(listOf(red)), state, emptyMap())))
        // The list shows every route, so it says so there.
        assertEquals(false, statusNote(state, unchecked = false))
        // A route getting off at C, or at a pole of P2's pair (the bus may stop at P2), can't be vouched for.
        assertTrue(routeClosuresFailed(TripRoute(listOf(red.copy(toId = "C"))), state, emptyMap()))
        assertTrue(routeClosuresFailed(TripRoute(listOf(bus)), state, emptyMap()))
        assertEquals(false, statusNote(state, unchecked = false, closuresFailed = routeClosuresFailed(TripRoute(listOf(bus)), state, emptyMap())))
    }

    @Test
    fun `a check is current until it's as old as a stale countdown, and never once dated after the clock`() {
        val now = at(0)
        assertTrue(checkCurrent(now, now))
        assertTrue(checkCurrent(now.minus(Duration.ofMinutes(4)), now))
        assertFalse(checkCurrent(now.minus(Duration.ofMinutes(6)), now))
        assertFalse(checkCurrent(null, now))
        // Made between the screen clock's ticks: dated a few seconds after it, and current.
        assertTrue(checkCurrent(now.plusSeconds(9), now))
        // Made before the clock was set back: its age can't be told, so it isn't current.
        assertFalse(checkCurrent(now.plus(Duration.ofMinutes(10)), now))
    }

    @Test
    fun `a check is aged by the steady clock, so setting the device's clock doesn't change it`() {
        var offset = Duration.ZERO
        SteadyClock.source = object : SteadyClock.Source {
            override val frame: SteadyClock.Frame? = null
            override fun offset(): Duration = offset
        }
        try {
            val checked = SteadyClock.stamp(at(0))
            // Two minutes on, the clock is set back an hour: the check is two minutes old, current.
            offset = Duration.ofHours(1)
            assertTrue(checkCurrent(checked, at(2).minus(Duration.ofHours(1))))
            // Once the wall clock has caught up with the old stamp, it's an hour old: not current.
            assertFalse(checkCurrent(checked, at(1)))
        } finally {
            SteadyClock.source = null
        }
    }

    @Test
    fun `a pole another line shown uses counts toward whether the route could be checked`() {
        // The Planner's 43 from P1, checked open; the 134, found at P2, runs to the same stop.
        val ride = leg("43", "P1", "Q1", 5, 15).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ")
        val other = leg("134", "P2", "Q1", 6, 16).copy(mode = "bus")
        val route = TripRoute(listOf(ride))
        val shown = mapOf(ride to RideLines(listOf(ride, other), listOf(ride, other)))
        val checked = TripViewModel.State(closures = mapOf("P1" to emptyList(), "P2" to emptyList(), "Q1" to emptyList()))
        assertEquals(setOf("P2", "Q1"), otherLineStops(route, shown))
        assertFalse(otherLineStopsUnchecked(route, checked, shown))
        assertFalse(routeClosuresFailed(route, checked, emptyMap(), shown))
        // P2's check failed: the route can't vouch for the 134, though its own stops are fine.
        val failed = checked.copy(closuresFailed = setOf("P2"))
        assertFalse(routeClosuresFailed(route, failed, emptyMap()))
        assertTrue(routeClosuresFailed(route, failed, emptyMap(), shown))
        // P2 never checked: unchecked, so the note says it's checking, then that it couldn't.
        val unchecked = checked.copy(closures = checked.closures - "P2")
        assertTrue(otherLineStopsUnchecked(route, unchecked, shown))
        assertFalse(otherLineStopsUnchecked(route, unchecked, emptyMap()))
    }

    @Test
    fun `an opened route says it couldn't check only a line of its own`() {
        val red = TripLeg("tube", "red", "Red", "A", "A", "B", "B", at(10), at(20))
        val green = red.copy(lineId = "green", lineName = "Green")
        // The latest status request failed for green, a line only another route rides.
        val state = TripViewModel.State(statusFailed = true, statusFailedLines = setOf("green"))
        assertFalse(routeStatusFailed(TripRoute(listOf(red)), emptyMap(), state))
        assertNull(statusNote(state, unchecked = false, statusFailed = routeStatusFailed(TripRoute(listOf(red)), emptyMap(), state)))
        // The list shows every route, so it says so there.
        assertEquals(false, statusNote(state, unchecked = false))
        // A route riding green, or showing it at a stop it boards at, can't be vouched for.
        assertTrue(routeStatusFailed(TripRoute(listOf(green)), emptyMap(), state))
        val shown = mapOf(red to RideLines(listOf(red, green), listOf(red)))
        assertTrue(routeStatusFailed(TripRoute(listOf(red)), shown, state))
        assertEquals(false, statusNote(state, unchecked = false, statusFailed = routeStatusFailed(TripRoute(listOf(red)), shown, state)))
    }

    @Test
    fun `once a bus is placed, only a failed check at its own pole says the route couldn't be checked`() {
        val route = TripRoute(listOf(plannerBus))
        val poles = mapOf("BG" to listOf("Bn", "Bs"), "CG" to listOf("Cn", "Cs"))
        // The Planner's southbound poles failed; the bus boards and gets off northbound.
        val otherSide = TripViewModel.State(closuresFailed = setOf("Bs", "Cs"), areaPoles = poles)
        assertFalse(routeClosuresFailed(route, otherSide, mapOf("1" to road)))
        // Its route not in yet: it may use either side, so it can't be vouched for.
        assertTrue(routeClosuresFailed(route, otherSide, emptyMap()))
        // Its own pole's check failed.
        assertTrue(routeClosuresFailed(route, otherSide.copy(closuresFailed = setOf("Cn")), mapOf("1" to road)))
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
    fun `a bus leg is timed at the pole its bus uses once that pole is fetched`() {
        // Its northbound buses time it; the other side's southbound ones never did.
        val north = train("1", "C", 4).copy(mode = "bus")
        val state = TripViewModel.State(
            routes = listOf(TripRoute(listOf(plannerBus))),
            live = mapOf("Bn" to TripViewModel.StopLive(listOf(north), now), "Bs" to TripViewModel.StopLive(listOf(train("1", "A", 2).copy(mode = "bus")), now)),
        )
        val onRoad = onPoles(state, mapOf("1" to road))
        assertEquals("Bn", onRoad.routes!!.single().legs.single().fromId)
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
    fun `a bus leg's line page vouches for its stop only as the ranking would`() {
        val checked = mapOf("Bn" to emptyList<StopDisruption>(), "Bs" to emptyList())
        val state = TripViewModel.State(closures = checked, closuresAt = checked.mapValues { now }, areaPoles = mapOf("BG" to listOf("Bn", "Bs")))
        // Not yet placed: every pole TfL listed checked, but it may use one the list missed, so it can't.
        assertTrue(legStopUnchecked(plannerBus, state, now, emptyMap()))
        // Placed on Bn by its route, checked: it can.
        assertFalse(legStopUnchecked(plannerBus, state, now, mapOf("1" to road)))
        // Placed on Bn by its route, whose check failed: still can't.
        assertTrue(legStopUnchecked(plannerBus, state.copy(closuresFailed = setOf("Bn")), now, mapOf("1" to road)))
        // Placed on Bn, checked: only its failed other side, which it doesn't use.
        assertFalse(legStopUnchecked(plannerBus, state.copy(closuresFailed = setOf("Bs")), now, mapOf("1" to road)))
        // Placed on a pole the pair's lookup missed, so never checked: can't.
        assertTrue(legStopUnchecked(plannerBus, state.copy(closures = mapOf("Bs" to emptyList())), now, mapOf("1" to road)))
        // Placed on Bn, last known closed, and its latest check failed: it can't say that's current.
        val closedThen = state.copy(closures = checked + ("Bn" to listOf(StopDisruption("Stop closed"))), closuresFailed = setOf("Bn"))
        assertTrue(legStopUnchecked(plannerBus, closedThen, now, mapOf("1" to road)))
        // Placed on Bn, checked open, but as long ago as a stale countdown: nor that. Its other side's
        // age doesn't count.
        val old = now.minus(Duration.ofMinutes(5))
        assertTrue(legStopUnchecked(plannerBus, state.copy(closuresAt = mapOf("Bn" to old, "Bs" to now)), now, mapOf("1" to road)))
        assertFalse(legStopUnchecked(plannerBus, state.copy(closuresAt = mapOf("Bn" to now, "Bs" to old)), now, mapOf("1" to road)))
        // Its check's time unknown: nor that.
        assertTrue(legStopUnchecked(plannerBus, state.copy(closuresAt = emptyMap()), now, mapOf("1" to road)))
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

    // The Planner boards the northbound bus at a bus station's stand As2, which the line doesn't use
    // (its own stand there is As; neither is in a pair), and gets off at the pair CG.
    private val atStation = road.copy(stopNames = road.stopNames + ("As2" to "A"))
    private val fromStand = plannerBus.copy(fromId = "As2", fromName = "A", fromArea = "", path = listOf("BG", "XG", "CG"))

    @Test
    fun `a bus leg's keys hold as it moves to the route's stands`() {
        // An open route, card or line page stays open as its leg moves to the route's own stand.
        val placed = onPoles(fromStand, mapOf("1" to atStation))
        assertEquals("As", placed.fromId)
        assertEquals(routeKey(TripRoute(listOf(fromStand))), routeKey(TripRoute(listOf(placed))))
        assertEquals(cardKey(TripRoute(listOf(fromStand))), cardKey(TripRoute(listOf(placed))))
        assertEquals(tripLegKey(fromStand), tripLegKey(placed))
        // Between two bus stations, both of its ends moved.
        val stands = fromStand.copy(toArea = "", path = listOf("BG", "XG", "Cn"))
        val both = onPoles(stands, mapOf("1" to atStation))
        assertEquals("As" to "Cn", both.fromId to both.toId)
        assertEquals(routeKey(TripRoute(listOf(stands))), routeKey(TripRoute(listOf(both))))
        assertEquals(cardKey(TripRoute(listOf(stands))), cardKey(TripRoute(listOf(both))))
        assertEquals(tripLegKey(stands), tripLegKey(both))
    }

    private val atRouteStand = PlacedStand("1", "As2", "As")

    @Test
    fun `different stops sharing a name keep different keys`() {
        // Two stands of one bus station share its name: a route from each is its own route, and two
        // lines from them are two cards, as they board at different places.
        val other = fromStand.copy(fromId = "As3")
        assertNotEquals(routeKey(TripRoute(listOf(fromStand))), routeKey(TripRoute(listOf(other))))
        assertNotEquals(cardKey(TripRoute(listOf(fromStand))), cardKey(TripRoute(listOf(other.copy(lineId = "2")))))
        assertNotEquals(tripLegKey(fromStand), tripLegKey(other))
        // Getting off too: the same boarding, alighting at two stops of one name.
        val offAt = fromStand.copy(toArea = "", toId = "Cs", toName = "C")
        assertNotEquals(tripLegKey(offAt), tripLegKey(offAt.copy(toId = "Cx")))
        // Nor do two stops whose names are blank run together.
        val blank = fromStand.copy(fromName = "")
        assertNotEquals(routeKey(TripRoute(listOf(blank))), routeKey(TripRoute(listOf(blank.copy(fromId = "As3")))))
        // A moved leg keeps the stand the Planner named, however often it's placed, so its keys hold.
        val placed = onPoles(fromStand, mapOf("1" to atStation))
        assertEquals(routeKey(TripRoute(listOf(fromStand))), routeKey(TripRoute(listOf(onPoles(placed, mapOf("1" to atStation))))))
    }

    @Test
    fun `a stand the screen boards a bus at is fetched, and the leg moves there once it is`() = runTest(dispatcher) {
        val north = train("1", "C", 4).copy(mode = "bus")
        val client = FakeClient(mutableMapOf("As" to listOf(north)))
        var t = now
        val logged = mutableListOf<String>()
        val trip = TripViewModel(
            FakePlanner(listOf(TripRoute(listOf(fromStand)))), client, "A", listOf(TripDestination.Stop("C")), clock = { t },
            plans = TripPlans(), io = dispatcher, poles = { listOf("Cn", "Cs") }, warn = { logged += it },
        )
        trip.refresh()
        advanceUntilIdle()
        // The trip fetches the Planner's stand by itself, never the route's.
        assertTrue("As2" in client.asked)
        assertFalse("As" in client.asked)
        // Not fetched, the screen keeps the Planner's stand.
        assertEquals("As2", onPoles(trip.state.value, mapOf("1" to atStation)).routes.orEmpty().single().legs.single().fromId)
        trip.boardAt(setOf(atRouteStand))
        advanceUntilIdle()
        assertEquals(listOf(north), trip.state.value.live["As"]?.departures)
        // Logged once, by stop and line ids, for a bug report to show whether the move was made.
        assertEquals(listOf("trip bus 1 boards at the route's stand As, not the Planner's As2"), logged.filter { "route's stand" in it })
        // Fetched, the leg boards there and its bus times it.
        val moved = onPoles(trip.state.value, mapOf("1" to atStation)).routes.orEmpty().single().legs.single()
        assertEquals("As", moved.fromId)
        // Handed over again (the screen does on each change to what it shows), or dropped and handed
        // back (its route ranked out of the shown few and in again), it asks for nothing: no
        // refresh, so no loop of them, and no second log line.
        val asks = client.asked.size
        val checks = client.statusChecks
        trip.boardAt(setOf(atRouteStand))
        trip.boardAt(emptySet())
        trip.boardAt(setOf(atRouteStand))
        advanceUntilIdle()
        assertEquals(asks, client.asked.size)
        assertEquals(checks, client.statusChecks)
        assertEquals(1, logged.count { "route's stand" in it })
        // A later refresh fetches it with the rest.
        t = now.plus(Duration.ofMinutes(2))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(2, client.asked.count { it == "As" })
        // Back once its arrivals are no longer current (a replan's refresh may have run without it),
        // it's fetched again at once, and logged no more.
        trip.boardAt(emptySet())
        t = now.plus(Duration.ofMinutes(4))
        trip.boardAt(setOf(atRouteStand))
        advanceUntilIdle()
        assertEquals(3, client.asked.count { it == "As" })
        assertEquals(1, logged.count { "route's stand" in it })
    }

    @Test
    fun `a bus station leg reads Loading until the stand its bus uses is fetched`() {
        val bus = listOf(train("1", "C", 4).copy(mode = "bus"))
        // The Planner's stand fetched, and empty: its line's route still loading to say which stand.
        val planners = TripViewModel.State(live = mapOf("As2" to TripViewModel.StopLive(emptyList(), now)))
        assertTrue(legLoading(planners, fromStand, emptyMap()))
        // The route says another stand, not yet fetched: still loading, not "–".
        assertTrue(legLoading(planners, fromStand, mapOf("1" to atStation)))
        // Fetched, the leg moves there ([onPoles]) and its times are in.
        val both = planners.copy(live = planners.live + ("As" to TripViewModel.StopLive(bus, now)))
        assertFalse(legLoading(both, onPoles(fromStand, mapOf("1" to atStation)), mapOf("1" to atStation)))
        // The route keeps the Planner's stand: its times are in.
        val calls = atStation.copy(routes = listOf(LineRoute("North", listOf("As2", "As", "Bn", "Xn", "Cn"))))
        assertFalse(legLoading(planners, fromStand, mapOf("1" to calls)))
        // The route failed: the Planner's stand's times stand, as at a stop pair.
        assertFalse(legLoading(planners, fromStand, mapOf("1" to null)))
    }

    @Test
    fun `a stand handed over during a refresh is fetched once that refresh ends`() = runTest(dispatcher) {
        val north = train("1", "C", 4).copy(mode = "bus")
        val client = FakeClient(mutableMapOf("As" to listOf(north)))
        val trip = TripViewModel(
            FakePlanner(listOf(TripRoute(listOf(fromStand)))), client, "A", listOf(TripDestination.Stop("C")), clock = { now },
            plans = TripPlans(), io = dispatcher, poles = { listOf("Cn", "Cs") },
        )
        // The first refresh waits on the Planner's stand.
        val slow = CompletableDeferred<Unit>()
        client.gates["As2"] = slow
        trip.refresh()
        runCurrent()
        trip.boardAt(setOf(atRouteStand))
        runCurrent()
        // Not asked on its own while that refresh is out: one refresh writes at a time.
        assertFalse("As" in client.asked)
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(north), trip.state.value.live["As"]?.departures)
    }

    @Test
    fun `a refresh that fails after a stand is asked for has the last word`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf("As" to listOf(train("1", "C", 4).copy(mode = "bus"))))
        var t = now
        val trip = TripViewModel(
            FakePlanner(listOf(TripRoute(listOf(fromStand)))), client, "A", listOf(TripDestination.Stop("C")), clock = { t },
            plans = TripPlans(), io = dispatcher, poles = { listOf("Cn", "Cs") },
        )
        trip.refresh()
        advanceUntilIdle()
        // The screen hands the stand over; its fetch is slow.
        val slow = CompletableDeferred<Unit>()
        client.gates["As"] = slow
        trip.boardAt(setOf(atRouteStand))
        runCurrent()
        // A refresh asks for it too, later, and fails.
        client.failStops = setOf("As")
        t = now.plus(Duration.ofMinutes(2))
        trip.refresh()
        // The earlier request's answer lands first; the later refresh's failure is what stands.
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, client.asked.count { it == "As" })
        assertEquals(true, trip.state.value.live["As"]?.failed)
    }

    @Test
    fun `a stand whose fetch failed reads as failed, not loading`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        client.failStops = setOf("As")
        val trip = TripViewModel(
            FakePlanner(listOf(TripRoute(listOf(fromStand)))), client, "A", listOf(TripDestination.Stop("C")), clock = { now },
            plans = TripPlans(), io = dispatcher, poles = { listOf("Cn", "Cs") },
        )
        trip.refresh()
        advanceUntilIdle()
        trip.boardAt(setOf(atRouteStand))
        advanceUntilIdle()
        assertEquals(true, trip.state.value.live["As"]?.failed)
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
    fun `a one-way dismissal leaves a trip's cards and status row the other way's alert`() {
        // The line-wide status is the worse way's alert; dismissing it one way keeps the other's.
        val inbound = LineStatus("blue", 6, "Severe Delays")
        val outbound = LineStatus("blue", 9, "Minor Delays")
        val line = inbound.copy(byDirection = mapOf("inbound" to inbound, "outbound" to outbound))
        val dismissed = setOf(DismissedAlert.ofLineStatus(inbound))

        assertEquals("Minor Delays", shownStatuses(mapOf("blue" to line), dismissed)["blue"]?.description)
        // A status-only row: no direction, no trains.
        val statusRow = row(direction = "", destination = "", train = train("blue", "C", 6))
            .copy(upcoming = emptyList(), status = line)
        val marked = withDismissedMarked(statusRow, dismissed)
        assertEquals("Minor Delays", marked.status?.description)
        assertFalse(marked.statusDismissed)

        val both = dismissed + DismissedAlert.ofLineStatus(outbound)
        assertNull(shownStatuses(mapOf("blue" to line), both)["blue"])
        assertTrue(withDismissedMarked(statusRow, both).statusDismissed)
    }

    @Test
    fun `a card takes a line's alerts for the way its ride's trains go`() {
        // Blue's delays are for trains going inbound; the ride's blue trains at B go outbound (train()),
        // where there's only work still to come. Which way applies is LineStatus.alongRides's to say;
        // this is the card handing it the trains along each ride.
        val sequences = mapOf("red" to red, "blue" to blue)
        val work = PlannedAlert("Diversion", "Trains divert from 13 October.", LocalDate.of(2026, 10, 13))
        val inbound = LineStatus("blue", 6, "Severe Delays")
        val outbound = LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(work))
        val line = inbound.copy(planned = listOf(work), byDirection = mapOf("inbound" to inbound, "outbound" to outbound))
        val state = redAndGreenAt(train("red", "End", 6)).copy(statuses = mapOf("blue" to line))
        val card = listOf(checkNotNull(tripEstimates(state, now, Duration.ZERO, sequences)).first())
        assertEquals(outbound, cardStatuses(card, emptyMap(), state, now, sequences).getValue("blue"))
        // Its work dismissed from its row, the card has nothing for blue: the direction is taken first.
        assertNull(shownStatuses(cardStatuses(card, emptyMap(), state, now, sequences), setOf(DismissedAlert.ofPlanned("blue", work)))["blue"])
        // No blue train seen yet at B: the line-wide alert stands.
        assertEquals(line, cardStatuses(card, emptyMap(), state.copy(live = state.live - "B"), now, sequences).getValue("blue"))
    }

    @Test
    fun `a card's planned work is dismissed on its own`() {
        val work = PlannedAlert("Diversion", "Buses divert from 13 October.", LocalDate.of(2026, 10, 13))
        val severe = LineStatus("blue", 6, "Severe Delays", planned = listOf(work))
        // Dismissing the disruption leaves the work to come.
        val afterStatus = shownStatuses(mapOf("blue" to severe), setOf(DismissedAlert.ofLineStatus(severe)))["blue"]
        assertEquals(false, afterStatus?.disrupted)
        assertEquals(listOf(work), afterStatus?.planned)
        // Dismissing the work leaves the disruption, without the calendar.
        val afterWork = shownStatuses(mapOf("blue" to severe), setOf(DismissedAlert.ofPlanned("blue", work)))["blue"]
        assertEquals(true, afterWork?.disrupted)
        assertEquals(emptyList<PlannedAlert>(), afterWork?.planned)
        // Both gone: so is the line.
        val onlyWork = LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(work))
        assertEquals(emptyMap<String, LineStatus>(), shownStatuses(mapOf("blue" to onlyWork), setOf(DismissedAlert.ofPlanned("blue", work))))
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
        // Every stop a line here boards or gets off at, checked open, as the trip holds them.
        closures = listOf("A", "A2", "B", "C", "End").associateWith { emptyList<StopDisruption>() },
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
    fun `a ride another running line takes isn't sunk by its Planner line's status`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val good = { id: String -> LineStatus(id, LineStatus.GOOD_SERVICE, "Good Service") }
        val closed = { id: String -> LineStatus(id, 20, "Service Closed") }
        fun estimate(statuses: Map<String, LineStatus>, others: Boolean = true): TripTiming.Estimate {
            val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3)).copy(statuses = statuses)
            val lines = if (others) rideLines(state.routes.orEmpty(), state, sequences) else emptyMap()
            return tripEstimates(state, now, Duration.ZERO, sequences, lines = lines)!!.single()
        }
        // Red, the Planner's line, closed or unchecked, while green runs between the same stops: the
        // route can be taken, so it isn't ranked below the others (Codex on #309)...
        val redClosed = mapOf("red" to closed("red"), "green" to good("green"), "blue" to good("blue"))
        assertFalse(estimate(redClosed).blocked)
        val redUnchecked = mapOf("green" to good("green"), "blue" to good("blue"))
        assertFalse(estimate(redUnchecked).unchecked)
        // ...as it was with no other line to take the ride.
        assertTrue(estimate(redClosed, others = false).blocked)
        assertTrue(estimate(redUnchecked, others = false).unchecked)
        // Green closed too, or never checked: nothing else takes the ride.
        assertTrue(estimate(redClosed + ("green" to closed("green"))).blocked)
        assertTrue(estimate(mapOf("red" to closed("red"), "blue" to good("blue"))).blocked)
        // Another ride's Planner line is still its own: blue closed sinks the route whatever takes red's.
        assertTrue(estimate(redClosed + ("blue" to closed("blue"))).blocked)
    }

    @Test
    fun `a Planner line another line stands in for doesn't time the ride unless it's running`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val good = { id: String -> LineStatus(id, LineStatus.GOOD_SERVICE, "Good Service") }
        // Red's leftover prediction is sooner than green's train.
        val base = redAndGreenAt(train("red", "End", 1), train("green", "End", 3))
        fun firstTrain(state: TripViewModel.State): Departure? {
            val lines = rideLines(state.routes.orEmpty(), state, sequences)
            return tripEstimates(state, now, Duration.ZERO, sequences, lines = lines)!!.single().legs.firstNotNullOf { it.train }
        }
        fun usable(state: TripViewModel.State): Set<String> {
            val lines = rideLines(state.routes.orEmpty(), state, sequences)
            val card = tripCards(checkNotNull(tripEstimates(state, now, Duration.ZERO, sequences, lines = lines))).single()
            return cardTimes(card, state, now, Duration.ZERO, sequences, lines).shown.filter { it.second }.mapTo(HashSet()) { it.first.lineId }
        }
        // Red running: its train is the first that can take the ride.
        val running = base.copy(statuses = listOf("red", "green", "blue").associateWith(good))
        assertEquals("red", firstTrain(running)?.lineId)
        assertEquals(setOf("red", "green"), usable(running))
        // Red closed, or never checked, while green runs: green keeps the route usable, so green's is
        // the train it's timed from and the only one offered, not red's leftover (Codex on #382).
        val redClosed = base.copy(statuses = mapOf("red" to LineStatus("red", 20, "Service Closed"), "green" to good("green"), "blue" to good("blue")))
        val redUnchecked = base.copy(statuses = mapOf("green" to good("green"), "blue" to good("blue")))
        for (state in listOf(redClosed, redUnchecked)) {
            assertEquals("green", firstTrain(state)?.lineId)
            assertEquals(setOf("green"), usable(state))
        }
        // With no green train to time it either, the ride isn't timed from red's timetable: that's a
        // service that can't be relied on, so the arrival is withheld until a train times it.
        fun estimate(state: TripViewModel.State, others: Boolean = true): TripTiming.Estimate {
            val lines = if (others) rideLines(state.routes.orEmpty(), state, sequences) else emptyMap()
            return tripEstimates(state, now, Duration.ZERO, sequences, lines = lines)!!.single()
        }
        // Green found at A by a train already gone: nothing of its own to time the ride with.
        val noTrains = redAndGreenAt(train("green", "End", -1))
        for (statuses in listOf(redClosed.statuses, redUnchecked.statuses)) {
            val state = noTrains.copy(statuses = statuses)
            assertFalse(estimate(state).blocked)
            assertNull(estimate(state).arrival)
        }
        // Red running, or nothing else to take the ride (it's ranked by red's status then): its
        // timetable stands in as before.
        assertEquals(TripTiming.Basis.ESTIMATED, estimate(noTrains.copy(statuses = running.statuses)).basis)
        assertEquals(TripTiming.Basis.ESTIMATED, estimate(noTrains.copy(statuses = redUnchecked.statuses), others = false).basis)
    }

    @Test
    fun `a line whose latest status check failed can't stand in for the Planner's`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val good = { id: String -> LineStatus(id, LineStatus.GOOD_SERVICE, "Good Service") }
        val redClosed = mapOf("red" to LineStatus("red", 20, "Service Closed"), "green" to good("green"), "blue" to good("blue"))
        val base = redAndGreenAt(train("red", "End", 1), train("green", "End", 3)).copy(statuses = redClosed)
        fun estimate(state: TripViewModel.State): TripTiming.Estimate =
            tripEstimates(state, now, Duration.ZERO, sequences, lines = rideLines(state.routes.orEmpty(), state, sequences))!!.single()
        assertFalse(estimate(base).blocked)
        // Green's good service is kept from before its latest check failed: the last known, not a
        // current one, so red, closed now, sinks the route (Codex on #382)...
        val failed = base.copy(statusFailed = true, statusFailedLines = setOf("green"))
        assertTrue(estimate(failed).blocked)
        // ...and green's trains aren't offered meanwhile, as its line page can't vouch for them.
        val lines = rideLines(failed.routes.orEmpty(), failed, sequences)
        assertEquals(setOf("red"), rideTrains(failed, viaRedOnly.rides.first(), now, sequences, lines)?.map { it.lineId }?.toSet())
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
    fun `another line seen again after a failed check is one its page can't vouch for`() = runTest(dispatcher) {
        val arrivals = mutableMapOf("A" to listOf(train("red", "End", 2), train("green", "End", 3)))
        val client = FakeClient(arrivals)
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        // Green leaves the stop's arrivals, then comes back while every status check fails: its
        // status from before is kept, but its latest check failed, so its page can't stand behind it.
        arrivals["A"] = listOf(train("red", "End", 2))
        now = now.plus(Duration.ofMinutes(2))
        trip.refresh()
        advanceUntilIdle()
        arrivals["A"] = listOf(train("red", "End", 2), train("green", "End", 3))
        client.failStatus = true
        now = now.plus(Duration.ofMinutes(2))
        trip.refresh()
        advanceUntilIdle()
        assertTrue("green" in trip.state.value.statuses)
        assertTrue("green" in trip.state.value.statusFailedLines)
        // Checked again and answered, it can.
        client.failStatus = false
        now = now.plus(Duration.ofMinutes(2))
        trip.refresh()
        advanceUntilIdle()
        assertTrue("green" !in trip.state.value.statusFailedLines)
    }

    @Test
    fun `another line a late check leaves out keeps no status from before`() = runTest(dispatcher) {
        val arrivals = mutableMapOf("A" to listOf(train("red", "End", 2), train("green", "End", 3)))
        val client = FakeClient(arrivals)
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        // Green leaves the stop's arrivals (still checked this once, from the arrivals before).
        arrivals["A"] = listOf(train("red", "End", 2))
        now = now.plus(Duration.ofMinutes(1))
        trip.refresh()
        advanceUntilIdle()
        assertTrue("green" in trip.state.value.statuses)
        // It comes back in a refresh whose first status request fails, and the check of green just
        // after answers without it: the status from before isn't its verdict any more.
        arrivals["A"] = listOf(train("red", "End", 2), train("green", "End", 3))
        client.failLines = setOf("red")
        client.omitLines = setOf("green")
        now = now.plus(Duration.ofMinutes(1))
        trip.refresh()
        advanceUntilIdle()
        assertTrue("green" !in trip.state.value.statuses)
        assertTrue("green" !in trip.state.value.statusesAt)
        assertTrue("red" in trip.state.value.statuses)
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
    fun `another line from a stop not checked open neither times the ride nor shows as catchable`() {
        // Green runs from A2, the other pole of the ride's stop, to the same stop; checked as running.
        val red = viaRedOnly.rides.first()
        val green = red.copy(lineId = "green", lineName = "green", fromId = "A2")
        val good = listOf("red", "green", "blue").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
        val atA2 = LineSequence(routes = listOf(LineRoute("A2 ↔ End", listOf("A2", "B", "End"))), stopNames = this.red.stopNames + ("A2" to "A"))
        val sequences = mapOf("red" to this.red, "green" to atA2, "blue" to blue)
        val lines = mapOf(red to RideLines(listOf(red, green), listOf(red, green)))
        val base = redAndGreenAt(train("red", "End", 6)).let {
            it.copy(statuses = good, live = it.live + ("A2" to TripViewModel.StopLive(listOf(train("green", "End", 3)), now)))
        }
        assertEquals(setOf("red", "green"), rideTrains(base, red, now, sequences, lines)?.map { it.lineId }?.toSet())
        // A2 closed, or not checked yet: green's trains don't time the ride, and its open row shows none.
        val closed = base.copy(closures = base.closures + ("A2" to stationClosed))
        val unchecked = base.copy(closures = base.closures - "A2")
        for (state in listOf(closed, unchecked)) {
            assertEquals(setOf("red"), rideTrains(state, red, now, sequences, lines)?.map { it.lineId }?.toSet())
            assertEquals(emptyList<DepartureRow>(), rideLegRows(lines.getValue(red), state, now, sequences, emptySet()).getValue(green))
        }
        // The Planner's own line is judged where the route is ranked, not here.
        assertTrue(rideLegRows(lines.getValue(red), closed, now, sequences, emptySet()).getValue(red).isNotEmpty())
    }

    @Test
    fun `an open ride shows another line's countdowns only once it's checked as running`() {
        val sequences = mapOf("red" to red, "green" to greenAlike, "blue" to blue)
        val red = viaRedOnly.rides.first()
        val green = red.copy(lineId = "green", lineName = "green")
        val state = redAndGreenAt(train("red", "End", 6), train("green", "End", 3))
        val lines = RideLines(listOf(red, green), listOf(red, green))
        val unchecked = rideLegRows(lines, state, now, sequences, emptySet())
        assertTrue(unchecked.getValue(red).isNotEmpty())
        assertEquals(emptyList<DepartureRow>(), unchecked.getValue(green))
        val good = state.copy(statuses = mapOf("green" to LineStatus("green", LineStatus.GOOD_SERVICE, "Good Service")))
        assertTrue(rideLegRows(lines, good, now, sequences, emptySet()).getValue(green).isNotEmpty())
    }

    @Test
    fun `another line at a pole whose refresh failed doesn't time the ride`() {
        val red = viaRedOnly.rides.first()
        val green = red.copy(lineId = "green", lineName = "green", fromId = "A2")
        val good = listOf("red", "green").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
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
    fun `a ride whose Planner pole failed is boarded on arrival when another line's refreshed trains show it frequent`() {
        // Green rides the same stretch from A2, the other pole of the ride's stop. The rider reaches
        // the stop at 15, past every predicted train and the Planner's red at 6.
        val red = viaRedOnly.rides.first()
        val green = red.copy(lineId = "green", lineName = "green", fromId = "A2")
        val good = listOf("red", "green", "blue").associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
        val atA2 = LineSequence(routes = listOf(LineRoute("A2 ↔ End", listOf("A2", "B", "End"))), stopNames = this.red.stopNames + ("A2" to "A"))
        val sequences = mapOf("red" to this.red, "green" to atA2, "blue" to blue)
        val lines = mapOf(red to RideLines(listOf(red, green), listOf(red, green)))
        val access = Duration.ofMinutes(15)
        fun first(state: TripViewModel.State) = checkNotNull(tripEstimates(state, now, access, sequences, lines = lines)).single()
        // A's refresh failed, red's trains there held; A2's refreshed, green every few minutes.
        val heldRed = TripViewModel.StopLive(listOf(train("red", "End", 2), train("red", "End", 5), train("red", "End", 8)), now, failed = true)
        val base = redAndGreenAt().copy(statuses = good, live = redAndGreenAt().live + ("A" to heldRed))
        val frequent = base.copy(live = base.live + ("A2" to TripViewModel.StopLive(listOf(train("green", "End", 3), train("green", "End", 6), train("green", "End", 9)), now)))
        val estimate = first(frequent)
        assertTrue(rideCurrent(frequent, red, now, lines))
        assertEquals(at(15), estimate.legs.first().board)
        assertFalse(estimate.legs.first().live)
        // Only trains from a stop that refreshed show it frequent: red's held ones may have stopped
        // since, so a single green train leaves the arrival withheld.
        val sparse = base.copy(live = base.live + ("A2" to TripViewModel.StopLive(listOf(train("green", "End", 3)), now)))
        assertEquals(TripTiming.Basis.UNKNOWN, first(sparse).basis)
        assertEquals(TripTiming.Reason.INFREQUENT, first(sparse).withheld?.reason)
        // With A2's refresh failed too, nothing refreshed vouches for the ride.
        val bothFailed = base.copy(live = base.live + ("A2" to TripViewModel.StopLive(frequent.live.getValue("A2").departures, now, failed = true)))
        assertFalse(rideCurrent(bothFailed, red, now, lines))
        assertEquals(TripTiming.Reason.FAILED, first(bothFailed).withheld?.reason)
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

    private val stationClosed = listOf(StopDisruption("Station closed due to strike action"))

    @Test
    fun `checks every stop a route boards or gets off at for closures`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf("A", "B", "C"), client.disruptionAsks.flatten().sorted())
        val state = trip.state.value
        assertEquals(setOf("A", "B", "C"), state.closures.keys)
        assertEquals(emptySet<String>(), state.closuresUnknown)
        assertEquals(TripClosures.Standing.OPEN, TripClosures.standing(route, state.closures, state.closuresUnknown, now))
    }

    @Test
    fun `a new plan's stops are unchecked until their check arrives`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient by FakeClient(mutableMapOf()) {
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                gate.await()
                return emptyList()
            }
        }
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        runCurrent()
        assertTrue(trip.state.value.routes != null)
        // Planned, not yet checked: no route passes as open meanwhile.
        assertEquals(setOf("A", "B", "C"), trip.state.value.closuresUnknown)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), trip.state.value.closuresUnknown)
    }

    @Test
    fun `a closed stop where a route changes ranks it below one that avoids it`() = runTest(dispatcher) {
        val other = TripRoute(listOf(leg("green", "A", "D", 5, 20), leg("blue", "D", "C", 22, 35)))
        val client = FakeClient(mutableMapOf()).apply { disruptions = mapOf("B" to stationClosed) }
        val trip = model(FakePlanner(listOf(route, other)), client)
        trip.refresh()
        advanceUntilIdle()
        val estimates = tripEstimates(trip.state.value, now, Duration.ZERO, emptyMap()).orEmpty()
        // The faster route changes at the closed stop: it goes last, still timed.
        assertEquals(listOf(other, route), estimates.map { it.route })
        assertTrue(estimates.last().blocked)
        assertFalse(estimates.first().blocked)
    }

    @Test
    fun `a failed closure check keeps the last known notices, and a stop with none known stays unchecked`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf()).apply { disruptions = mapOf("B" to stationClosed) }
        val trip = model(FakePlanner(listOf(route)), client)
        client.failDisruptions = setOf("C")
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("C"), trip.state.value.closuresUnknown)
        assertEquals(setOf("C"), trip.state.value.closuresFailed)
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(route, trip.state.value.closures, trip.state.value.closuresUnknown, now))
        // Past the reuse, B's check fails too: its closure stands, claiming nothing new.
        now = now.plus(Duration.ofMinutes(6))
        client.failDisruptions = setOf("B", "C")
        trip.refresh()
        advanceUntilIdle()
        assertEquals(stationClosed, trip.state.value.closures["B"])
        assertEquals(setOf("C"), trip.state.value.closuresUnknown)
        // B's notice stands, but its check failed: nothing vouches for it as current.
        assertEquals(setOf("B", "C"), trip.state.value.closuresFailed)
        assertEquals(false, statusNote(trip.state.value, unchecked = false))
        // They answer: known and current at last.
        client.failDisruptions = emptySet()
        trip.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), trip.state.value.closuresUnknown)
        assertEquals(emptySet<String>(), trip.state.value.closuresFailed)
        assertNull(statusNote(trip.state.value, unchecked = false))
    }

    @Test
    fun `a trip's closure check forgets a dismissed closure that ended at a stop it checked, not an interchange's`() = runTest(dispatcher) {
        // B's closure as its own stop's card keys it, and as a card at an interchange B is in would.
        val card = DepartureRows.across(
            listOf(StopArrivals("B", "", emptyList(), SteadyClock.stamp(now), disruptions = stationClosed)),
            now,
        ).single { it.stopDisruption != null }
        val atB = DismissedAlert.ofStopClosure(card)
        val atHub = DismissedAlert.ofStopClosure(card.copy(hubId = "HUBX"))
        val store = reconcilingStore(setOf(atB, atHub))
        val client = FakeClient(mutableMapOf()).apply { disruptions = mapOf("B" to stationClosed) }
        val trip = TripViewModel(FakePlanner(listOf(route)), client, "A", listOf(TripDestination.Stop("C")), clock = { now }, io = dispatcher, dismissedStore = store)
        trip.refresh()
        advanceUntilIdle()
        // Still closed: both stay dismissed.
        assertEquals(setOf(atB, atHub), store.stored.value)
        // A failed lookup isn't evidence it reopened.
        now = now.plus(Duration.ofMinutes(6))
        client.disruptions = emptyMap()
        client.failDisruptions = setOf("B")
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf(atB, atHub), store.stored.value)
        // Checked clear: B's own dismissal goes, so the same notice coming back shows again, without
        // waiting for the list (Codex on #367). The interchange's stays: the trip didn't look at its
        // other stops, so that's left to the list.
        client.failDisruptions = emptySet()
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf(atHub), store.stored.value)
        assertEquals(setOf(atHub), trip.dismissed.value)
    }

    @Test
    fun `a closure lookup landing after a newer one shows the newer`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        // The list's own lookup of B, asked after the trip's, lands first.
        val client = object : TflClient by FakeClient(mutableMapOf()) {
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId == "B") shared.keep("B", shared.ask(now.plusSeconds(1)), stationClosed)
                return emptyList()
            }
        }
        val trip = model(FakePlanner(listOf(route)), client, closures = shared)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(stationClosed, trip.state.value.closures["B"])
        assertEquals(now.plusSeconds(1) to stationClosed, shared["B"]?.let { it.at to it.notices })
    }

    @Test
    fun `a closure lookup failing after a newer one landed shows the newer, not a failure`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        // The list's own lookup of B, asked after the trip's, lands first; the trip's then fails.
        val client = object : TflClient by FakeClient(mutableMapOf()) {
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId == "B") {
                    shared.keep("B", shared.ask(now.plusSeconds(1)), stationClosed)
                    throw TflException.Offline(null)
                }
                return emptyList()
            }
        }
        val trip = model(FakePlanner(listOf(route)), client, closures = shared)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(stationClosed, trip.state.value.closures["B"])
        assertEquals(emptySet<String>(), trip.state.value.closuresFailed)
    }

    @Test
    fun `a trip shown again takes a newer closure another screen found meanwhile`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val client = FakeClient(mutableMapOf())
        client.failDisruptions = setOf("B")
        val trip = model(FakePlanner(listOf(route)), client, closures = shared)
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(setOf("B"), trip.state.value.closuresFailed)
        val fetches = client.asked.size to client.disruptionAsks.size
        // The list then finds B closed, and D (no stop of this trip's) too.
        shared.keep("B", shared.ask(now), stationClosed)
        shared.keep("D", shared.ask(now), stationClosed)
        // Shown again with its arrivals still fresh: nothing is fetched, but B's closure shows.
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(fetches, client.asked.size to client.disruptionAsks.size)
        assertEquals(stationClosed, trip.state.value.closures["B"])
        assertNull(trip.state.value.closures["D"])
        assertEquals(emptySet<String>(), trip.state.value.closuresFailed)
        assertEquals(emptySet<String>(), trip.state.value.closuresUnknown)
        assertEquals(TripClosures.Standing.CLOSED, TripClosures.standing(route, trip.state.value.closures, trip.state.value.closuresUnknown, now))
        // As old as the other screen's lookup: when it was asked, for the line page's vouch.
        assertEquals(now, trip.state.value.closuresAt["B"])
    }

    @Test
    fun `a trip shown again with fresh arrivals still refreshes closures past their reuse`() = runTest(dispatcher) {
        val cache = ArrivalsCache()
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route)), client, arrivals = cache)
        trip.refreshFor(null)
        advanceUntilIdle()
        val asked = client.disruptionAsks.size
        // Six minutes on, the list has just fetched the boarding stops' arrivals, but the trip's
        // closure checks are past their reuse: shown again, it asks about them afresh.
        now = now.plus(Duration.ofMinutes(6))
        cache.put("A", listOf(train("red", "B", 9)), now.minusSeconds(10))
        cache.put("B", listOf(train("blue", "C", 9)), now.minusSeconds(10))
        trip.refreshFor(null)
        advanceUntilIdle()
        assertTrue(client.disruptionAsks.size > asked)
    }

    @Test
    fun `a stop another screen checked within a few minutes isn't asked about again`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        shared.keep("A", shared.ask(now.minus(Duration.ofMinutes(2))), emptyList())
        shared.keep("B", shared.ask(now.minus(Duration.ofMinutes(2))), stationClosed)
        // A lookup past the reuse is asked again.
        shared.keep("C", shared.ask(now.minus(Duration.ofMinutes(6))), emptyList())
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route)), client, closures = shared)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf("C"), client.disruptionAsks.flatten())
        assertEquals(stationClosed, trip.state.value.closures["B"])
        // And the trip's own lookup is kept for the list.
        assertEquals(now, shared["C"]?.at)
        // Each held as old as the lookup it came from, the reused ones included; each line's status
        // as old as its answer.
        assertEquals(now.minus(Duration.ofMinutes(2)), trip.state.value.closuresAt["B"])
        assertEquals(now, trip.state.value.closuresAt["C"])
        assertTrue(trip.state.value.statuses.isNotEmpty())
        assertEquals(trip.state.value.statuses.keys, trip.state.value.statusesAt.keys)
        assertTrue(trip.state.value.statusesAt.values.all { it == now })
        // And sorted on the day they were fetched, for the screen to tell when they need bringing up to
        // its own (Codex on #519).
        assertEquals(now.atZone(app.stopdash.domain.AlertStart.ZONE).toLocalDate(), trip.state.value.statusesSortedOn)
    }

    @Test
    fun `a bus stop pair's every pole is checked, in one request, and the pair is unchecked until looked up`() = runTest(dispatcher) {
        val bus = TripRoute(
            listOf(
                leg("25", "490000001A", "490000002A", 5, 15).copy(mode = "bus", fromArea = "490G00001", toArea = "490G00002"),
            ),
        )
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient(mutableMapOf())
        val trip = TripViewModel(
            FakePlanner(listOf(bus)), client, "A", listOf(TripDestination.Stop("490000002A")), clock = { now }, plans = TripPlans(), io = dispatcher,
            poles = { area ->
                gate.await()
                if (area == "490G00001") listOf("490000001A", "490000001B") else listOf("490000002A", "490000002B")
            },
        )
        trip.refresh()
        runCurrent()
        assertTrue("490G00001" in trip.state.value.closuresUnknown && "490G00002" in trip.state.value.closuresUnknown)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(listOf("490000001A", "490000001B", "490000002A", "490000002B")), client.disruptionAsks.map { it.sorted() })
        assertEquals(emptySet<String>(), trip.state.value.closuresUnknown)
    }

    @Test
    fun `a route's closure cards are its stops' notices in force, less those dismissed`() {
        val moved = listOf(StopDisruption("Stop moved to the next corner"))
        val later = listOf(StopDisruption("Station closed", validFrom = now.plus(Duration.ofHours(1))))
        val state = TripViewModel.State(closures = mapOf("A" to moved, "B" to stationClosed, "C" to later))
        val cards = routeClosures(route, state, now, emptySet())
        assertEquals(listOf("A", "B"), cards.keys.toList())
        assertEquals("B", cards.getValue("B").stopName)
        val dismissed = setOf(DismissedAlert.ofStopClosure(cards.getValue("B")))
        assertEquals(listOf("A"), routeClosures(route, state, now, dismissed).keys.toList())
        // A stop with two notices carries both on its one card, as the list's does: a moved stop
        // doesn't hide a closure beside it.
        val both = routeClosures(route, state.copy(closures = mapOf("B" to moved + stationClosed)), now, emptySet()).getValue("B")
        assertTrue(both.stopDisruption.orEmpty().contains("Stop moved"))
        assertTrue(both.stopDisruption.orEmpty().contains("Station closed"))
    }

    @Test
    fun `a route's closure card is placed as the list places it, so a dismissal holds on both`() {
        val bus = TripRoute(listOf(leg("25", "P1", "Q1", 5, 15).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ")))
        val state = TripViewModel.State(closures = mapOf("P1" to stationClosed, "Q1" to stationClosed))
        // P1 is in an interchange; Q1's top parent is only its own stop area, not a hub.
        val sequence = LineSequence(
            routes = listOf(LineRoute("P ↔ Q", listOf("P1", "Q1"))),
            stopNames = mapOf("P1" to "P1", "Q1" to "Q1"),
            stopAreas = mapOf("P1" to "490GP", "Q1" to "490GQ"),
            stopHubs = mapOf("P1" to "HUBP", "Q1" to "490GQ"),
        )
        val cards = routeClosures(bus, state, now, emptySet(), mapOf("25" to sequence))
        assertEquals("HUBP", DismissedAlert.ofStopClosure(cards.getValue("P1")).alertKey)
        assertEquals("490GQ", DismissedAlert.ofStopClosure(cards.getValue("Q1")).alertKey)
        // Before the route data loads, a bus stop is still placed by its pair.
        assertEquals("490GQ", DismissedAlert.ofStopClosure(routeClosures(bus, state, now, emptySet()).getValue("Q1")).alertKey)
    }

    @Test
    fun `a change's closure card takes whichever ride's route data came in`() {
        // Q1 is where the 25 gets off and the 73 boards; the 25's route failed to load.
        val change = TripRoute(
            listOf(
                leg("25", "P1", "Q1", 5, 15).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ"),
                leg("73", "Q1", "R1", 18, 30).copy(mode = "bus", fromArea = "490GQ", toArea = "490GR"),
            ),
        )
        val sequence = LineSequence(
            routes = listOf(LineRoute("Q ↔ R", listOf("Q1", "R1"))),
            stopNames = mapOf("Q1" to "Q1", "R1" to "R1"),
            stopHubs = mapOf("Q1" to "HUBQ"),
        )
        val cards = routeClosures(change, TripViewModel.State(closures = mapOf("Q1" to stationClosed)), now, emptySet(), mapOf("25" to null, "73" to sequence))
        assertEquals("HUBQ", DismissedAlert.ofStopClosure(cards.getValue("Q1")).alertKey)
    }

    @Test
    fun `a closure card at a stop only a walk uses takes its interchange from the station index`() {
        // From station F on foot to A, then the red line to B: no ride's route data covers F.
        val walkFirst = TripRoute(listOf(leg("", "F", "A", 0, 5).copy(mode = "walking"), leg("red", "A", "B", 5, 15)))
        val state = TripViewModel.State(closures = mapOf("F" to stationClosed))
        val hubs = mapOf("F" to "HUBF")
        val cards = routeClosures(walkFirst, state, now, emptySet(), mapOf("red" to null)) { hubs[it] }
        assertEquals("HUBF", DismissedAlert.ofStopClosure(cards.getValue("F")).alertKey)
        // A station in no interchange goes by its own id, as the list keys it.
        assertEquals("F", DismissedAlert.ofStopClosure(routeClosures(walkFirst, state, now, emptySet()).getValue("F")).alertKey)
    }

    @Test
    fun `a list card's ride carries where it gets off, where it boards unless the last ride got off there, and where the route ends`() {
        val walked = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("blue", "D", "C", 20, 30)))
        val state = TripViewModel.State(closures = listOf("A", "B", "C", "D", "E").associateWith { stationClosed })
        val closures = routeClosures(TripRoute(walked.legs + leg("", "C", "E", 30, 35).copy(mode = "walking")), state, now, emptySet())
        assertEquals(listOf("A", "B"), rideClosures(walked.rides, 0, "E", closures).map { it.stopId })
        assertEquals(listOf("D", "C", "E"), rideClosures(walked.rides, 1, "E", closures).map { it.stopId })
        // Boarding where the last ride got off: that notice is the last ride's.
        assertEquals(listOf("C"), rideClosures(route.rides, 1, null, closures).map { it.stopId })
        // Starting on foot from a stop: the first ride carries it too.
        val fromF = routeClosures(TripRoute(listOf(leg("", "F", "A", 0, 5).copy(mode = "walking")) + walked.legs), state.copy(closures = state.closures + ("F" to stationClosed)), now, emptySet())
        assertEquals(listOf("F", "A", "B"), rideClosures(walked.rides, 0, null, fromF, starts = "F").map { it.stopId })
        assertEquals(listOf("D", "C"), rideClosures(walked.rides, 1, null, fromF, starts = "F").map { it.stopId })
    }

    @Test
    fun `a shared card's ride carries every line's stop closures, each once`() {
        // The 43 and the 134 go between the same stop pairs, but the 134 boards at its own pole P2.
        val first = TripRoute(listOf(leg("43", "P1", "Q1", 5, 15).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ")))
        val other = TripRoute(listOf(leg("134", "P2", "Q1", 6, 16).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ")))
        val card = listOf(first, other).map { TripTiming.Estimate(it, TripTiming.Basis.LIVE, null, emptyList(), false, now) }
        val state = TripViewModel.State(closures = mapOf("P2" to stationClosed, "Q1" to stationClosed))
        val closures = card.fold(emptyMap<String, DepartureRow>()) { found, estimate -> found + routeClosures(estimate.route, state, now, emptySet()) }
        assertEquals(listOf("Q1", "P2"), cardClosures(card, 0, closures).map { it.stopId })
    }

    @Test
    fun `a stop only the screen names is checked with the rest`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertTrue("S2" !in client.disruptionAsks.flatten())
        // The screen judges a route at S2 (a stand its bus is placed on, say) and at A, the trip's own.
        client.disruptions = mapOf("S2" to stationClosed)
        trip.checkShownStops(setOf("S2", "A"))
        advanceUntilIdle()
        assertEquals(stationClosed, trip.state.value.closures["S2"])
        assertEquals(listOf("S2"), client.disruptionAsks.last())
        // Handed again, nothing is new: nothing is asked.
        val asks = client.disruptionAsks.size
        trip.checkShownStops(setOf("S2", "A"))
        advanceUntilIdle()
        assertEquals(asks, client.disruptionAsks.size)
        // Past its reuse, the next refresh asks about it with the rest.
        now = now.plus(Duration.ofMinutes(6))
        trip.refresh()
        advanceUntilIdle()
        assertTrue("S2" in client.disruptionAsks.drop(asks).flatten())
        // A check of it that fails says so, as any stop's does.
        client.failDisruptions = setOf("Q9")
        trip.checkShownStops(setOf("S2", "Q9"))
        advanceUntilIdle()
        assertTrue("Q9" in trip.state.value.closuresFailed)
    }

    @Test
    fun `a refresh out when a shown stop's own check fails keeps that failure`() = runTest(dispatcher) {
        var gate: CompletableDeferred<Unit>? = null
        val fake = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val client = object : TflClient by fake {
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId != "S2") gate?.await()
                return fake.stopDisruptions(stopId)
            }
        }
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        trip.checkShownStops(setOf("S2"))
        advanceUntilIdle()
        assertEquals(emptyList<StopDisruption>(), trip.state.value.closures["S2"])
        // The screen stops naming it, and a refresh goes out without it...
        trip.checkShownStops(emptySet())
        now = now.plus(Duration.ofMinutes(6))
        gate = CompletableDeferred()
        trip.refresh()
        runCurrent()
        // ...and while it's out, the screen names it again, and its own check fails.
        fake.failDisruptions = setOf("S2")
        trip.checkShownStops(setOf("S2"))
        runCurrent()
        assertTrue("S2" in trip.state.value.closuresFailed)
        gate.complete(Unit)
        advanceUntilIdle()
        // The refresh never asked about it: its failure stands over its older empty check (Codex, PR #375).
        assertTrue("S2" in trip.state.value.closuresFailed)
    }

    @Test
    fun `an older check of a shown stop landing last never undoes a newer one's failure`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var s2Calls = 0
        val fake = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val client = object : TflClient by fake {
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId != "S2") return fake.stopDisruptions(stopId)
                // The first check of S2 is slow and finds nothing; the next one fails at once.
                if (++s2Calls == 1) {
                    gate.await()
                    return emptyList()
                }
                throw TflException.Offline(null)
            }
        }
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refreshFor(null)
        advanceUntilIdle()
        trip.checkShownStops(setOf("S2"))
        runCurrent()
        // Its route hidden and shown again while that check is out: S2 is checked again, and fails.
        trip.checkShownStops(emptySet())
        trip.checkShownStops(setOf("S2"))
        runCurrent()
        assertTrue("S2" in trip.state.value.closuresFailed)
        // The first check lands last: its older "nothing there" doesn't pass S2 off as checked open.
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("S2" in trip.state.value.closuresFailed)
        assertNull(trip.state.value.closures["S2"])
        // Nor once the trip is shown again and takes what the shared cache holds, which is that older
        // lookup: asked before the check that failed, it doesn't answer for it (Codex, PR #375).
        trip.refreshFor(null)
        assertTrue("S2" in trip.state.value.closuresFailed)
        // Nor on the refresh being shown again starts, within the reuse window: S2 is asked again
        // rather than taken from that older lookup, and fails again, so it still counts as failed
        // (Codex, PR #375).
        val calls = s2Calls
        advanceUntilIdle()
        assertEquals(calls + 1, s2Calls)
        assertTrue("S2" in trip.state.value.closuresFailed)
        assertNull(trip.state.value.closures["S2"])
    }

    @Test
    fun `a shown stop that only a route the trip doesn't time also ends at is still checked`() = runTest(dispatcher) {
        // A bus route to S2, hidden, so a refresh never checks it.
        val bus = TripRoute(listOf(leg("43", "A", "S2", 5, 15).copy(mode = "bus"), leg("blue", "S2", "C", 20, 30)))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = model(FakePlanner(listOf(route, bus)), client)
        trip.hiddenModes = setOf("bus")
        trip.refresh()
        advanceUntilIdle()
        assertTrue("S2" !in client.disruptionAsks.flatten())
        // The screen judges a shown route at S2: it's asked about, once and on every refresh after.
        trip.checkShownStops(setOf("S2"))
        advanceUntilIdle()
        assertEquals(listOf("S2"), client.disruptionAsks.last())
        now = now.plus(Duration.ofMinutes(6))
        val asks = client.disruptionAsks.size
        trip.refresh()
        advanceUntilIdle()
        assertTrue("S2" in client.disruptionAsks.drop(asks).flatten())
    }

    @Test
    fun `the stops only the screen can name are where rides are placed and other lines' poles`() {
        // The 43 is placed where the Planner named it, its route calling there; the 134, found at P2,
        // runs to the same stop.
        val ride = leg("43", "P1", "Q1", 5, 15).copy(mode = "bus")
        val other = leg("134", "P2", "Q1", 6, 16).copy(mode = "bus")
        val route = TripRoute(listOf(ride))
        val calls = mapOf("43" to LineSequence(routes = listOf(LineRoute("P ↔ Q", listOf("P1", "Q1"))), stopNames = mapOf("P1" to "P", "Q1" to "Q")))
        assertEquals(setOf("P1", "Q1"), shownStops(route, calls, emptyMap()))
        assertEquals(setOf("P1", "P2", "Q1"), shownStops(route, calls, mapOf(ride to RideLines(listOf(ride, other), listOf(ride)))))
        // Not yet placed, a bus at a bus station's stands names nothing, as at a pair: the Planner's
        // may be a stand the line doesn't use, and the trip already asks about the Planner's stops.
        assertEquals(emptySet<String>(), shownStops(route, emptyMap(), emptyMap()))
        // A bus at a pair not yet placed names nothing at either end: the trip already asks about
        // its pair's poles and the Planner's stops.
        val paired = ride.copy(fromArea = "490GP")
        assertEquals(emptySet<String>(), shownStops(TripRoute(listOf(paired)), mapOf("43" to null), emptyMap()))
        // Once placed, at the bus station stand its route calls at rather than the one the Planner named.
        val atStation = paired.copy(toName = "Bus Station", path = listOf("M1", "Q1"))
        val station = LineSequence(
            routes = listOf(LineRoute("To the station", listOf("P1", "M1", "S2"))),
            stopNames = mapOf("P1" to "P", "M1" to "M", "S2" to "Bus Station"),
            stopAreas = mapOf("P1" to "490GP"),
        )
        assertEquals(setOf("P1", "S2"), shownStops(TripRoute(listOf(atStation)), mapOf("43" to station), emptyMap()))
    }

    @Test
    fun `a ride's other lines carry the closures where they board and get off`() {
        // The 43 is the Planner's, from P1; the 134, found in P2's arrivals, runs from P2 to the same stop.
        val ride = leg("43", "P1", "Q1", 5, 15).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ")
        val other = leg("134", "P2", "Q1", 6, 16).copy(mode = "bus")
        val route = TripRoute(listOf(ride))
        val rideLines = mapOf(ride to RideLines(listOf(ride, other), listOf(ride, other)))
        val state = TripViewModel.State(closures = mapOf("P1" to emptyList(), "P2" to stationClosed, "Q1" to emptyList()))
        // The route's own stops say nothing; the 134's pole is closed.
        assertTrue(routeClosures(route, state, now, emptySet()).isEmpty())
        val closures = routeClosures(route, state, now, emptySet(), rideLines = rideLines)
        assertEquals(listOf("P2"), closures.keys.toList())
        val card = listOf(TripTiming.Estimate(route, TripTiming.Basis.LIVE, null, emptyList(), false, now))
        assertEquals(listOf("P2"), cardClosures(card, 0, closures, rideLines).map { it.stopId })

        // At a change, a stop another line got off at is that ride's, not carried again by the next.
        val next = leg("73", "Q2", "R1", 18, 30).copy(mode = "bus")
        val change = TripRoute(listOf(ride, next))
        val lines = mapOf(ride to RideLines(listOf(ride, other.copy(toId = "Q2")), listOf(ride)))
        val atQ2 = routeClosures(change, state.copy(closures = mapOf("Q2" to stationClosed)), now, emptySet(), rideLines = lines)
        assertEquals(listOf("Q2"), rideClosures(change.rides, 0, null, atQ2) { lines[it]?.legs.orEmpty() }.map { it.stopId })
        assertEquals(emptyList<String>(), rideClosures(change.rides, 1, null, atQ2) { lines[it]?.legs.orEmpty() }.map { it.stopId })
    }

    @Test
    fun `one notice at two poles of a stop area makes one card, where the route first reaches it`() {
        // Off the 25 at Q1, on the 73 at Q2 across the road: TfL gives each pole the area's notice.
        val change = TripRoute(
            listOf(
                leg("25", "P1", "Q1", 5, 15).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ"),
                leg("73", "Q2", "R1", 18, 30).copy(mode = "bus", fromArea = "490GQ", toArea = "490GR"),
            ),
        )
        val state = TripViewModel.State(closures = mapOf("Q1" to stationClosed, "Q2" to stationClosed))
        val cards = routeClosures(change, state, now, emptySet())
        assertEquals(listOf("Q1"), cards.keys.toList())
        // On the list card too: the first ride carries it where it gets off, the second doesn't again.
        val estimate = TripTiming.Estimate(change, TripTiming.Basis.LIVE, null, emptyList(), false, now)
        assertEquals(listOf("Q1"), cardClosures(listOf(estimate), 0, cards).map { it.stopId })
        assertEquals(emptyList<String>(), cardClosures(listOf(estimate), 1, cards).map { it.stopId })
        // Across a shared card's routes, one alighting at each pole: once.
        val atQ1 = TripRoute(listOf(leg("43", "P1", "Q1", 5, 15).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ")))
        val atQ2 = TripRoute(listOf(leg("134", "P1", "Q2", 6, 16).copy(mode = "bus", fromArea = "490GP", toArea = "490GQ")))
        val card = listOf(atQ1, atQ2).map { TripTiming.Estimate(it, TripTiming.Basis.LIVE, null, emptyList(), false, now) }
        val shared = card.fold(emptyMap<String, DepartureRow>()) { found, e -> found + routeClosures(e.route, state, now, emptySet()) }
        assertEquals(1, cardClosures(card, 0, shared).size)
    }
}
