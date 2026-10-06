package app.stopdash.ui

import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.Departure
import kotlinx.coroutines.CoroutineDispatcher
import app.stopdash.domain.Workers
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.FoldChoice
import app.stopdash.domain.Dismissed
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.HubInfo
import app.stopdash.domain.HubInfoCache
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FartherStations
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.UntimedTrain
import app.stopdash.domain.WidgetJourney
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneys
import app.stopdash.domain.WidgetJourneysReport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val seeds = listOf(
        StopRef("940GZZLUOXC", "Oxford Circus"),
        StopRef("940GZZLUKSX", "King's Cross St. Pancras"),
    )

    // The worker a cold load's progress is worked out on: the test's own dispatcher, so virtual time
    // drives it as it does the rest.
    private var appCompute: CoroutineDispatcher = Workers.compute

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        appCompute = Workers.compute
        Workers.compute = dispatcher
    }

    @After fun tearDown() {
        Workers.compute = appCompute
        Dispatchers.resetMain()
    }

    private fun departure(lineId: String, lineName: String, offsetSeconds: Long) =
        Departure(
            lineId = lineId,
            lineName = lineName,
            direction = "inbound",
            destination = "Brixton",
            platform = null,
            expectedArrival = now.plusSeconds(offsetSeconds),
            mode = "tube",
        )

    private class FakeClient(
        val byStop: Map<String, Result<List<Departure>>>,
        var statuses: Result<List<LineStatus>> = Result.success(emptyList()),
        val disruptionsByStop: Map<String, Result<List<StopDisruption>>> = emptyMap(),
    ) : TflClient {
        var requestedLineIds: Collection<String>? = null

        // Stop ids to fail regardless of [byStop] — lets a later fetch of an
        // already-succeeded stop fail, so a reconcile's refetch can be made non-authoritative.
        val failing = mutableSetOf<String>()

        // Every stop asked for, in order.
        val arrivalsCalls = mutableListOf<String>()

        override suspend fun arrivals(stopId: String): List<Departure> {
            arrivalsCalls += stopId
            if (stopId in failing) throw RuntimeException("arrivals failed for $stopId")
            return byStop.getValue(stopId).getOrThrow()
        }

        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            requestedLineIds = lineIds
            return statuses.getOrThrow()
        }

        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
            (disruptionsByStop[stopId] ?: Result.success(emptyList())).getOrThrow()
    }

    private fun status(lineId: String, severity: Int, description: String) =
        LineStatus(lineId = lineId, severity = severity, description = description)

    private fun viewModel(
        client: TflClient,
        store: SnapshotStore = SnapshotStore.NONE,
        warn: (String) -> Unit = {},
    ) = MainViewModel(client, seeds, clock = { now }, io = dispatcher, snapshotStore = store, warn = warn)

    /** An in-memory [SnapshotStore] recording every save, seeded with an optional last-good. */
    private class FakeStore(
        initial: DeparturesSnapshot? = null,
        // False: kept for the widget but not restored in-app, as the production store does.
        private val restores: Boolean = true,
    ) : SnapshotStore {
        var stored: DeparturesSnapshot? = initial
        override suspend fun stored(): DeparturesSnapshot? = stored
        val saves = mutableListOf<DeparturesSnapshot>()
        // When > 0, the next this-many save() calls throw instead of storing — to exercise a
        // shrink-save that fails so the pending-persist is retried, not dropped (Codex, PR #87).
        var failSaves: Int = 0
        override suspend fun load(): DeparturesSnapshot? = stored.takeIf { restores }
        // Every widget-journeys report written, with the origins it came with.
        val reports = mutableListOf<WidgetJourneysReport>()
        val reportOrigins = mutableListOf<List<StopArrivals>>()
        // When > 0, the next this-many journeys writes throw.
        var failJourneyWrites: Int = 0
        override suspend fun updateWidgetJourneys(report: WidgetJourneysReport, origins: List<StopArrivals>) {
            if (failJourneyWrites > 0) {
                failJourneyWrites--
                throw RuntimeException("journeys write failed")
            }
            reports += report
            reportOrigins += origins
            stored = WidgetJourneys.apply(stored, report, origins)
        }
        // The app's saves: recorded as given, stored with the pins kept as they are.
        override suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) {
            val journeys = stored?.journeys.orEmpty()
            save(snapshot)
            stored = snapshot.copy(journeys = journeys)
        }
        override suspend fun save(snapshot: DeparturesSnapshot) {
            if (failSaves > 0) {
                failSaves--
                throw RuntimeException("save failed")
            }
            stored = snapshot
            saves += snapshot
        }

        override suspend fun saveIfStopsMatch(
            snapshot: DeparturesSnapshot,
            expectedStopIds: List<String>,
        ): Boolean {
            if (stored?.stops?.map { it.stopId } != expectedStopIds) return false
            stored = snapshot
            saves += snapshot
            return true
        }

        // Line checks stored alone (no arrivals to save beside them), each call's as given.
        val lineStatusUpdates = mutableListOf<Map<String, LineStatusCheck>>()
        override suspend fun updateLineStatuses(checks: Map<String, LineStatusCheck>) {
            lineStatusUpdates += checks
        }

        // A targeted removal, independent of [save] and its [failSaves] — the redesign's point is
        // that a departed stop leaves disk regardless of the re-fetch's save (Codex, PR #87).
        override suspend fun pruneStops(departedStopIds: Collection<String>) {
            val current = stored ?: return
            val kept = current.stops.filterNot { it.stopId in departedStopIds }
            if (kept.size != current.stops.size) {
                stored = DeparturesSnapshot(kept, kept.maxOfOrNull { it.fetchedAt } ?: current.fetchedAt)
            }
        }
    }

    private fun stopArrivals(stopId: String, name: String, offsetSeconds: Long, fetchedAt: Instant) =
        StopArrivals(
            stopId = stopId,
            stopName = name,
            departures = listOf(departure("victoria", "Victoria", offsetSeconds)),
            fetchedAt = fetchedAt,
        )

    @Test
    fun `every stop succeeding yields one soonest-first Loaded snapshot`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(now, state.fetchedAt)
        assertEquals(false, state.partialRefresh)
        // The snapshot carries both stops as fetched; grouping into the soonest-first
        // list is the screen's job (recomputed from the clock), tested in DepartureRows.
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
    }

    @Test
    fun `cancelFetch cancels an in-flight fetch without saving, and clears refreshing`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
            ),
            store = store,
        )
        advanceUntilIdle() // initial load settles
        val savesAfterInit = store.saves.size
        assertEquals(false, vm.refreshing.value)

        // A re-locate cancels the current fetch before resolving the new position, so a fetch
        // for this (soon-to-be-previous) set can't finish and save a fresh snapshot for the old
        // location during the fix window (SPEC D4 / principle 1). The job is canceled on the test
        // dispatcher before its body runs, so it saves nothing.
        vm.refresh()
        assertEquals("a refresh marks the VM busy", true, vm.refreshing.value)
        vm.cancelFetch()
        assertEquals("cancelFetch clears the busy flag at once", false, vm.refreshing.value)
        advanceUntilIdle()
        assertEquals("the canceled fetch saved nothing", savesAfterInit, store.saves.size)
    }

    @Test
    fun `cancelFetch during init cancels the pending initial fetch, saving nothing`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
            ),
            store = store,
        )
        // The init snapshot-load + refresh is still pending on the test dispatcher; a re-locate
        // that cancels before init completes must stop the init-driven fetch, so it can't save a
        // snapshot for the old seed set while the fix is in flight (SPEC D4 / principle 1).
        vm.cancelFetch()
        advanceUntilIdle()
        assertEquals("the init-driven fetch saved nothing after cancelFetch", 0, store.saves.size)
        assertEquals(false, vm.refreshing.value)
    }

    @Test
    fun `one stop failing keeps the others and logs a sanitized reason`() = runTest(dispatcher) {
        val warnings = mutableListOf<String>()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
                // Victoria comes back good so the surviving stop's status is fully determined —
                // the only warning is the failed stop's arrivals, which is what this pins.
                statuses = Result.success(listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
            ),
            warn = { warnings += it },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        // Only the stop that succeeded is in the snapshot, and it's flagged partial so
        // the screen can say so rather than pass an incomplete list off as complete.
        assertEquals(listOf("940GZZLUOXC"), state.stops.map { it.stopId })
        assertTrue(state.partialRefresh)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("940GZZLUKSX"))
    }

    @Test
    fun `a partial refresh names the failed stop and why`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    // TfL answered, but with a server error (a 503).
                    "940GZZLUKSX" to Result.failure(TflException.Unreachable("HTTP 503", null)),
                ),
                statuses = Result.success(listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertTrue(state.partialRefresh)
        assertEquals(listOf("King's Cross St. Pancras"), state.partialStops.values.map { it.name })
        assertEquals(DeparturesUiState.Error.Kind.SERVER, state.partialReason)
    }

    @Test
    fun `stops failing different ways are named nearest first, with no single reason`() = runTest(dispatcher) {
        val vm = MainViewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                    "940GZZLUVIC" to Result.failure(TflException.Unreachable("HTTP 503", null)),
                ),
                statuses = Result.success(listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
            ),
            listOf(
                StopRef("940GZZLUOXC", "Oxford Circus"),
                StopRef("940GZZLUKSX", "King's Cross St. Pancras"),
                StopRef("940GZZLUVIC", "Victoria"),
            ),
            clock = { now },
            io = dispatcher,
            // Victoria is nearer than King's Cross though it comes later in the list.
            stopDistanceMeters = mapOf("940GZZLUOXC" to 50.0, "940GZZLUKSX" to 400.0, "940GZZLUVIC" to 100.0),
        )
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf("Victoria", "King's Cross St. Pancras"), state.partialStops.values.map { it.name })
        // Offline for one, a server error for the other: no one reason is true of both.
        assertEquals(null, state.partialReason)
    }

    @Test
    fun `a failure maps to the reason the banner gives`() {
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, errorKindOf(TflException.Offline(null)))
        assertEquals(DeparturesUiState.Error.Kind.RATE_LIMITED, errorKindOf(TflException.RateLimited(null)))
        // A timeout or dropped connection is a network error, not TfL's.
        assertEquals(DeparturesUiState.Error.Kind.NETWORK, errorKindOf(TflException.Network("transport: X", null)))
        // TfL answered with an error (a 503), or with something that couldn't be read.
        assertEquals(DeparturesUiState.Error.Kind.SERVER, errorKindOf(TflException.Unreachable("HTTP 503", null)))
        // TfL refused the user's own key: only clearing it mends that (SPEC D7).
        assertEquals(DeparturesUiState.Error.Kind.KEY_REJECTED, errorKindOf(TflException.KeyRejected(null)))
    }

    @Test
    fun `the banner's reason is the one every named stop shares, and an unknown one breaks it`() {
        fun loaded(vararg failed: Pair<String, DeparturesUiState.FailedStop>) =
            DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now, partialRefresh = true, partialStops = mapOf(*failed))
        val server = DeparturesUiState.Error.Kind.SERVER
        assertEquals(server, loaded("A" to DeparturesUiState.FailedStop("A", server), "B" to DeparturesUiState.FailedStop("B", server)).partialReason)
        // A retained stop whose cause is unknown (restored from disk) beside one that failed with a
        // known cause: no single reason is true of both.
        assertEquals(null, loaded("A" to DeparturesUiState.FailedStop("A"), "B" to DeparturesUiState.FailedStop("B", server)).partialReason)
        assertEquals(null, loaded().partialReason)
    }

    @Test
    fun `a complete refresh names no stop and no reason`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                ),
                statuses = Result.success(listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertFalse(state.partialRefresh)
        assertTrue(state.partialStops.isEmpty())
        assertEquals(null, state.partialReason)
    }

    @Test
    fun `a total failure after a partial one gives this attempt's reason, not the old one`() = runTest(dispatcher) {
        var failure: Exception? = null
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                failure?.let { throw it }
                if (stopId == "940GZZLUKSX") throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                failure?.let { throw it }
                return emptyList()
            }
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, (vm.state.value as DeparturesUiState.Loaded).partialReason)

        failure = TflException.Unreachable("HTTP 503", null)
        vm.refresh()
        advanceUntilIdle()

        // Still partial, and both banners now give the same, current cause.
        val kept = vm.state.value as DeparturesUiState.Loaded
        assertTrue(kept.partialRefresh)
        assertEquals(DeparturesUiState.Error.Kind.SERVER, kept.refreshFailure)
        assertEquals(DeparturesUiState.Error.Kind.SERVER, kept.partialReason)
    }

    @Test
    fun `a stop's trains with no time reach the list with its arrivals, never among them`() = runTest(dispatcher) {
        val canceled = UntimedTrain(departure("victoria", "Victoria", 240), canceled = true)
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = listOf(departure("victoria", "Victoria", 300))
            override fun untimed(stopId: String): List<UntimedTrain> = listOf(canceled)
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        val stops = (vm.state.value as DeparturesUiState.Loaded).stops
        assertTrue(stops.isNotEmpty())
        assertTrue(stops.all { it.untimed == listOf(canceled) && canceled.train !in it.departures })
    }

    /** Answers every stop at once except [slowStop], whose arrivals wait on [gate]; line status waits on [statusGate]. */
    private inner class GatedClient(
        val slowStop: String,
        val gate: CompletableDeferred<Unit>,
        val statusGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : TflClient {
        override suspend fun arrivals(stopId: String): List<Departure> {
            if (stopId == slowStop) gate.await()
            return listOf(departure("victoria", "Victoria", 120))
        }

        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            statusGate.await()
            return emptyList()
        }

        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
    }

    @Test
    fun `a part-loaded disrupted interchange is titled by its hub`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var hubCalls = 0
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[1].id) gate.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == seeds[0].id) listOf(StopDisruption("Station closed until further notice")) else emptyList()
            override suspend fun hubInfo(hubId: String): HubInfo {
                hubCalls++
                return HubInfo("Oxford Circus Interchange")
            }
        }
        val stops = listOf(seeds[0].copy(hubId = "HUBOXC"), seeds[1])
        val vm = MainViewModel(client, stops, clock = { now }, io = dispatcher)
        advanceUntilIdle()

        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals("Oxford Circus Interchange", partial.stops.single().hubName)
        gate.complete(Unit)
        advanceUntilIdle()
        // The final pass reuses the lookup rather than asking again.
        assertEquals(1, hubCalls)
    }

    @Test
    fun `a refresh's write lets go only of the dismissals it saw`() = runTest(dispatcher) {
        // The store is told the set the refresh settled, so a dismissal made since (of a notice a newer
        // check found back) isn't written away by this older verdict, as it isn't in memory.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val told = mutableListOf<Set<DismissedAlert>>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the refresh's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                told += seen
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces, seen)
            }
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        told.clear()
        closed = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(setOf(DismissedAlert.ofStopClosure(closure))), told)
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a refresh's prune leaves an alert dismissed again since it read the set`() = runTest(dispatcher) {
        // Another check let go of the alert first, the rider dismissed it again, and this check's prune,
        // worked out from the set before, lands after: the tap stands, in memory as in the store.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var again = emptySet<DismissedAlert>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the refresh's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces, stillSeen(seen, since))
            }
            override fun stillSeen(alerts: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) = alerts - again
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        again = setOf(DismissedAlert.ofStopClosure(closure))
        closed = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), vm.dismissed.value)
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)
    }

    @Test
    fun `an alert dismissed again while a refresh's requests are out stays dismissed`() = runTest(dispatcher) {
        // The refresh's verdict is from what it asked; the rider's tap meanwhile is newer, even of the
        // same alert again, so its settling lets go of it neither in memory nor in the store.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var count = 0L
        val counted = HashMap<DismissedAlert, Long>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                counted[alert] = ++count
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the refresh's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces, stillSeen(seen, since))
            }
            override fun mark() = count
            override fun stillSeen(alerts: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) = alerts.filterTo(HashSet()) { (counted[it] ?: 0L) <= since.of(it) }
        }
        var closed = true
        var tapMeanwhile: DismissedAlert? = null
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                tapMeanwhile?.let { store.dismiss(it) }
                return if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
            }
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        val alert = DismissedAlert.ofStopClosure(closure)
        // The notice ends, but while the refresh asks, the rider dismisses the same alert again.
        closed = false
        tapMeanwhile = alert
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(alert), backing.value)
        assertEquals(setOf(alert), vm.dismissed.value)
        // The next refresh, with no tap since it began, lets go of it.
        tapMeanwhile = null
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
        assertEquals(emptySet<DismissedAlert>(), vm.dismissed.value)
    }

    @Test
    fun `a closure answer from before an alert was dismissed again never lets go of it`() = runTest(dispatcher) {
        // Another screen asked about the stop, and found it clear, before the rider dismissed the alert
        // again; the refresh reuses that answer, so its verdict is that old and the tap stands.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var count = 0L
        val counted = HashMap<DismissedAlert, Long>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                counted[alert] = ++count
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the refresh's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces, stillSeen(seen, since))
            }
            override fun mark() = count
            override fun stillSeen(alerts: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) = alerts.filterTo(HashSet()) { (counted[it] ?: 0L) <= since.of(it) }
        }
        val shared = StopClosureCache()
        // Clear once asked again too: only the reused answer's age keeps the tap.
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = dispatcher,
            dismissedStore = store,
            disruptionCache = shared,
            disruptionReuse = java.time.Duration.ofMinutes(5),
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        val alert = DismissedAlert.ofStopClosure(closure)
        // Another screen's lookup, asked with the dismissals so far, finds the stop clear; then the
        // rider dismisses the same alert again, and the list refreshes on that answer.
        closed = false
        shared.keep("490000001A", shared.ask(now, store.mark()), emptyList())
        store.dismiss(alert)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(alert), backing.value)
        assertEquals(setOf(alert), vm.dismissed.value)
    }

    @Test
    fun `a closure answer reused at one place doesn't hold back a fresh one at another`() = runTest(dispatcher) {
        // One stop's answer is reused from before the rider dismissed the other stop's closure; the
        // other is asked afresh and found clear. Its own answer is newer than the tap, so the dismissal
        // goes, however old the reused answer at the first stop.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var count = 0L
        val counted = HashMap<DismissedAlert, Long>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                counted[alert] = ++count
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the refresh's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces, stillSeen(seen, since))
            }
            override fun mark() = count
            override fun stillSeen(alerts: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) = alerts.filterTo(HashSet()) { (counted[it] ?: 0L) <= since.of(it) }
        }
        val shared = StopClosureCache()
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        var clockNow = now
        val vm = MainViewModel(
            client,
            listOf(
                StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("490000002B", "Sample Street", clusterId = "490G000SAMPLE"),
            ),
            clock = { clockNow },
            io = dispatcher,
            compute = dispatcher,
            dismissedStore = store,
            disruptionCache = shared,
            disruptionReuse = java.time.Duration.ofMinutes(5),
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null && it.stopId == "490000002B" }
        // Another screen asks about the first stop before the rider dismisses the second's closure.
        clockNow = now.plusSeconds(400)
        shared.keep("490000001A", shared.ask(clockNow, store.mark()), emptyList())
        vm.dismissAlert(closure)
        advanceUntilIdle()
        val alert = DismissedAlert.ofStopClosure(closure)
        assertEquals(setOf(alert), backing.value)
        // Past the second stop's reuse window, not the first's: the second is asked afresh, found clear.
        closed = false
        clockNow = now.plusSeconds(600)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
        assertEquals(emptySet<DismissedAlert>(), vm.dismissed.value)
    }

    @Test
    fun `a line status reused from before an alert was dismissed again never lets go of it`() = runTest(dispatcher) {
        // The refresh reuses a good-service verdict asked before the rider dismissed the line's alert
        // again (another screen saw it recur): its verdict is that old, so the tap stands.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var count = 0L
        val counted = HashMap<DismissedAlert, Long>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                counted[alert] = ++count
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the refresh's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces, stillSeen(seen, since))
            }
            override fun mark() = count
            override fun stillSeen(alerts: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) = alerts.filterTo(HashSet()) { (counted[it] ?: 0L) <= since.of(it) }
        }
        val severe = LineStatus("victoria", 6, "Severe Delays", "Victoria line: severe delays.")
        var status = severe
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = listOf(status)
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        var clockNow = now
        val vm = MainViewModel(
            client,
            listOf(StopRef("940GZZLUVIC", "Victoria")),
            clock = { clockNow },
            io = dispatcher,
            compute = dispatcher,
            dismissedStore = store,
            lineStatusReuse = java.time.Duration.ofMinutes(5),
        )
        advanceUntilIdle()
        val loaded = vm.state.value as DeparturesUiState.Loaded
        val row = DepartureRows.across(loaded.stops, now, loaded.lineStatuses).first { it.status != null }
        vm.dismissAlert(row)
        advanceUntilIdle()
        val alert = DismissedAlert.ofLineStatus(severe)
        // Past the reuse window, good service is asked and kept: the dismissal goes, as the alert ended.
        status = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        clockNow = now.plusSeconds(600)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
        // Another screen saw it recur and the rider dismissed it there; the next refresh reuses the
        // good-service verdict from before that.
        store.dismiss(alert)
        advanceUntilIdle()
        clockNow = now.plusSeconds(660)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(alert), backing.value)
        assertEquals(setOf(alert), vm.dismissed.value)
    }

    @Test
    fun `an alert dismissed again elsewhere while the list pruned it is taken back`() = runTest(dispatcher) {
        // Another screen's tap lands as this check writes: the store still held the alert, so nothing
        // tells the list; asked once its write is in, the list takes the alert back.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var again = emptySet<DismissedAlert>()
        var tapAs: DismissedAlert? = null
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) = error("the refresh's set is passed")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>, seen: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) {
                tapAs?.let { again = setOf(it) }
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces, stillSeen(seen, since))
            }
            override fun stillSeen(alerts: Set<DismissedAlert>, since: app.stopdash.domain.DismissalMarks) = alerts - again
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        val alert = DismissedAlert.ofStopClosure(closure)
        closed = false
        tapAs = alert
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(alert), backing.value)
        assertEquals(setOf(alert), vm.dismissed.value)
    }

    @Test
    fun `a refresh reconciles dismissals on the worker, not the main thread`() = runTest(dispatcher) {
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        // Each walk through the dismissed set the list holds, by thread: what's left once one is let go
        // of is worked out on the worker too, as it grows with every dismissal (Codex on #519).
        val walked = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing.map { if (it.isEmpty()) it else WalkedSet(it, walked) }
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        // Runs what's handed to it at once until holding, then keeps it until let go, so nothing
        // it's given after the first load can run on the caller.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = worker,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        // Dismissed as the store hands it on, so the list holds the set it emits (a tap here also puts
        // its own equal copy in, which the list then keeps).
        backing.value = setOf(DismissedAlert.ofStopClosure(closure))
        advanceUntilIdle()

        // The notice ends. The worker merges the refresh's stops first; once the list shows them, the
        // refresh's checks are in but not settled: settling them across the board is the worker's to do.
        closed = false
        holding = true
        vm.refresh()
        advanceUntilIdle()
        while (held.isNotEmpty() && !shownClear(vm)) {
            val next = held.toList()
            held.clear()
            for ((context, block) in next) dispatcher.dispatch(context, block)
            advanceUntilIdle()
        }
        assertTrue("nothing handed to the worker", held.isNotEmpty())
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)
        walked.clear()
        // Let go on a thread of the worker's own, noting where each piece ran: still holding, so what's
        // handed to the worker meanwhile runs there too.
        val caller = Thread.currentThread()
        val ranOn = mutableListOf<Thread>()
        val thread = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            while (held.isNotEmpty()) {
                val next = held.toList()
                held.clear()
                thread.submit {
                    for ((_, block) in next) {
                        ranOn += Thread.currentThread()
                        block.run()
                    }
                }.get()
                advanceUntilIdle()
            }
        } finally {
            holding = false
            thread.shutdown()
        }
        assertTrue("$ranOn", ranOn.isNotEmpty() && ranOn.none { it === caller })
        assertTrue("$walked", walked.isNotEmpty() && walked.none { it === caller })
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    // [items], noting the thread of each walk through it in [walked]; a lookup isn't one.
    private class WalkedSet<T>(private val items: Set<T>, private val walked: MutableList<Thread>) : AbstractSet<T>() {
        override val size: Int get() = items.size
        override fun contains(element: T): Boolean = element in items
        override fun iterator(): Iterator<T> = items.iterator().also { walked += Thread.currentThread() }
    }

    @Test
    fun `a cold load shows its last stop without waiting on the line-status check`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val statusGate = CompletableDeferred<Unit>()
        val vm = viewModel(GatedClient(seeds[1].id, gate, statusGate))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val shown = vm.state.value as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), shown.stops.mapTo(HashSet()) { it.stopId })
        assertTrue(shown.pendingStops.isEmpty())
        assertTrue(shown.statusPending)

        statusGate.complete(Unit)
        advanceUntilIdle()
        assertFalse((vm.state.value as DeparturesUiState.Loaded).statusPending)
    }

    // Stations whose lines the nearby lookup declares, as near-me stops carry them.
    private val lined = listOf(
        StopRef("940GZZLUOXC", "Oxford Circus", listOf(LineRef("victoria", "Victoria", "tube"))),
        StopRef("940GZZLUKSX", "King's Cross St. Pancras", listOf(LineRef("circle", "Circle", "tube"))),
    )

    /** A client whose [slowStop]'s arrivals wait on [gate], recording each line-status request. */
    private inner class LinedClient(
        val slowStop: String,
        val gate: CompletableDeferred<Unit>,
        // Each stop's predicted lines; victoria alone when unlisted.
        val predicted: Map<String, List<String>> = emptyMap(),
        val statusOf: (String) -> LineStatus = { status(it, LineStatus.GOOD_SERVICE, "Good Service") },
        val closureFails: Set<String> = emptySet(),
        val arrivalsFail: Boolean = false,
        val statusFails: Boolean = false,
    ) : TflClient {
        val asked = mutableListOf<Set<String>>()

        override suspend fun arrivals(stopId: String): List<Departure> {
            if (arrivalsFail) throw TflException.Offline(null)
            if (stopId == slowStop) gate.await()
            return predicted[stopId].orEmpty().ifEmpty { listOf("victoria") }.map { departure(it, it, 120) }
        }

        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            asked += lineIds.toSet()
            if (statusFails) throw TflException.Unreachable("boom", null)
            return lineIds.map(statusOf)
        }

        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
            if (stopId in closureFails) throw TflException.Unreachable("boom", null)
            return emptyList()
        }
    }

    @Test
    fun `the list settles its dismissals off the caller's thread`() = runTest(dispatcher) {
        // AGENTS.md *Main thread*: every alert under way on a line, in a list that notes each thread
        // reading it, is gone through on the worker when the list settles its dismissals, not on the
        // main thread its loads run on (Codex on #519). Made-up words.
        val worker = TestWorker("worker")
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val read = java.util.Collections.synchronizedList(mutableListOf<String>())
            val alerts = listOf(
                app.stopdash.domain.LineAlert(6, "Severe Delays", "Severe delays northbound."),
                app.stopdash.domain.LineAlert(9, "Minor Delays", "Minor delays southbound."),
            )
            val ready = CompletableDeferred<Unit>().apply { complete(Unit) }
            val client = LinedClient("none", ready, statusOf = {
                if (it == "victoria") LineStatus(it, 6, "Severe Delays", alerts.first().fullText, underWay = Watched(alerts, read))
                else status(it, LineStatus.GOOD_SERVICE, "Good Service")
            })
            val vm = androidx.lifecycle.ViewModelProvider.create(
                store,
                androidx.lifecycle.viewmodel.viewModelFactory { initializer { MainViewModel(client, lined, clock = { now }, io = worker, compute = worker) } },
            )[MainViewModel::class]
            vm.state.first { it is DeparturesUiState.Loaded && !it.statusPending && it.pendingStops.isEmpty() }
            // Let the worker and Main hand the load's settling back and forth until it has run.
            repeat(50) {
                if (read.isNotEmpty()) return@repeat
                worker.flush()
                advanceUntilIdle()
            }
            assertTrue(read.isNotEmpty())
            assertEquals(setOf("worker"), read.toSet())
        } finally {
            store.clear()
            worker.close(this)
        }
    }

    @Test
    fun `a cold load vouches for the declared lines while a stop is still out`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = LinedClient(lined[1].id, gate)
        val vm = MainViewModel(client, lined, clock = { now }, io = dispatcher, alwaysNetworks = { setOf("tube") })
        advanceUntilIdle()

        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf(lined[1].id), partial.pendingStops.map { it.id })
        assertTrue(partial.statusPending)
        // Every line the stops declare was checked alongside the arrivals, so nothing reads unchecked.
        assertEquals(listOf(setOf("victoria", "circle") + HomeLines.TUBE_IDS), client.asked)
        assertFalse(partial.disruptionUnknown)
        assertFalse(partial.checkFailed)
        assertEquals(setOf("victoria", "circle") + HomeLines.TUBE_IDS, partial.determinedLineIds)

        gate.complete(Unit)
        advanceUntilIdle()
        val done = vm.state.value as DeparturesUiState.Loaded
        assertFalse(done.disruptionUnknown)
        assertFalse(done.statusPending)
        // Not asked again once every stop is in: the same one request, only sooner.
        assertEquals(1, client.asked.size)
    }

    @Test
    fun `a disrupted line shows mid-load`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = LinedClient(lined[1].id, gate, statusOf = {
            if (it == "victoria") status(it, 6, "Severe Delays") else status(it, LineStatus.GOOD_SERVICE, "Good Service")
        })
        val vm = MainViewModel(client, lined, clock = { now }, io = dispatcher, alwaysNetworks = { setOf("tube") })
        advanceUntilIdle()

        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(setOf("victoria"), partial.lineStatuses.keys)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("victoria"), (vm.state.value as DeparturesUiState.Loaded).lineStatuses.keys)
    }

    @Test
    fun `a line only a prediction names is checked once every stop is in`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = LinedClient(lined[1].id, gate, predicted = mapOf(lined[0].id to listOf("victoria", "elizabeth")))
        val vm = MainViewModel(client, lined, clock = { now }, io = dispatcher, alwaysNetworks = { setOf("tube") })
        advanceUntilIdle()

        // The declared lines are vouched for, but the predicted-only one isn't asked about yet.
        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf(setOf("victoria", "circle") + HomeLines.TUBE_IDS), client.asked)
        assertTrue(partial.disruptionUnknown)
        assertTrue(partial.statusPending)
        // Not asked yet, so still being checked rather than failed.
        assertFalse(partial.checkFailed)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(setOf("victoria", "circle") + HomeLines.TUBE_IDS, setOf("elizabeth")), client.asked)
        assertFalse((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)
    }

    @Test
    fun `a line check that failed mid-load says it couldn't check`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = LinedClient(lined[1].id, gate, statusFails = true)
        val vm = MainViewModel(client, lined, clock = { now }, io = dispatcher, alwaysNetworks = { setOf("tube") })
        advanceUntilIdle()

        // The request is back, failed, while a stop is still out: not asked again this load.
        val partial = vm.state.value as DeparturesUiState.Loaded
        assertTrue(partial.statusPending)
        assertTrue(partial.disruptionUnknown)
        assertTrue(partial.checkFailed)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, client.asked.size)
        assertTrue((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)
    }

    @Test
    fun `a stop whose closure check failed still reads unchecked mid-load`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = LinedClient(lined[1].id, gate, closureFails = setOf(lined[0].id))
        val vm = MainViewModel(client, lined, clock = { now }, io = dispatcher)
        advanceUntilIdle()

        val partial = vm.state.value as DeparturesUiState.Loaded
        assertTrue(partial.disruptionUnknown)
        assertEquals(setOf(lined[0].id), partial.stopsDisruptionUnknown)
        // That check is back and won't be asked again this load: it couldn't check, not checking.
        assertTrue(partial.checkFailed)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(lined[0].id), (vm.state.value as DeparturesUiState.Loaded).stopsDisruptionUnknown)
    }

    @Test
    fun `a declared line TfL doesn't know doesn't say the check failed mid-load`() = runTest(dispatcher) {
        // A station whose one line TfL has no status for (Eurostar), and another stop still out.
        val stops = listOf(
            StopRef("910GSTPX", "St Pancras International", listOf(LineRef("eurostar", "Eurostar", "national-rail"))),
            lined[1],
        )
        val gate = CompletableDeferred<Unit>()
        val client = LinedClient(
            lined[1].id,
            gate,
            predicted = mapOf("910GSTPX" to listOf("eurostar")),
            statusOf = { if (it == "eurostar") throw TflException.NotFound(null) else status(it, LineStatus.GOOD_SERVICE, "Good Service") },
        )
        val vm = MainViewModel(client, stops, clock = { now }, io = dispatcher)
        advanceUntilIdle()

        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf("910GSTPX"), partial.stops.map { it.stopId })
        assertFalse("eurostar" in partial.determinedLineIds)
        assertFalse(partial.checkFailed)
        assertFalse(partial.disruptionUnknown)
        // Asked about and settled, not asked again by the final pass: its rows don't say "checking".
        assertFalse("eurostar" in partial.pendingLineIds)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)
    }

    @Test
    fun `a cold load whose every stop fails asks nothing about line status`() = runTest(dispatcher) {
        val client = LinedClient(lined[1].id, CompletableDeferred(Unit), arrivalsFail = true)
        val vm = MainViewModel(client, lined, clock = { now }, io = dispatcher)
        advanceUntilIdle()

        assertEquals(DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE), vm.state.value)
        assertTrue(client.asked.isEmpty())
    }

    @Test
    fun `a cold load shows each stop as it lands and saves only once all are in`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore()
        val vm = viewModel(GatedClient(seeds[1].id, gate), store)
        advanceUntilIdle()

        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf(seeds[0].id), partial.stops.map { it.stopId })
        assertEquals(listOf(seeds[1].id), partial.pendingStops.map { it.id })
        // These stops declare no lines, so their status waits for every stop and isn't vouched for yet.
        assertTrue(partial.disruptionUnknown)
        assertFalse(partial.partialRefresh)
        assertTrue(store.saves.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        val done = vm.state.value as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), done.stops.mapTo(HashSet()) { it.stopId })
        assertTrue(done.pendingStops.isEmpty())
        assertEquals(1, store.saves.size)
    }

    @Test
    fun `a line TfL doesn't know is asked about once and stays unchecked`() = runTest(dispatcher) {
        var statusCalls = 0
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("caledonian-sleeper", "Caledonian Sleeper", 300))
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                statusCalls++
                throw TflException.NotFound(null)
            }
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(1, statusCalls)
        val loaded = vm.state.value as DeparturesUiState.Loaded
        // Never determined, so its row reads as unchecked, not clean...
        assertFalse("caledonian-sleeper" in loaded.determinedLineIds)
        // ...but TfL has no status for it to give, so no check failed and no banner says one did.
        assertFalse(loaded.disruptionUnknown)

        // TfL's answer won't change: a refresh doesn't ask again, and the line still isn't clean.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, statusCalls)
        assertFalse("caledonian-sleeper" in (vm.state.value as DeparturesUiState.Loaded).determinedLineIds)
    }

    @Test
    fun `beside a line TfL doesn't know, a line whose check fails still raises the banner`() = runTest(dispatcher) {
        // Eurostar at St Pancras International: TfL has no line for it. Thameslink it does, but its
        // check fails.
        var lines = listOf("eurostar")
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = lines.map { departure(it, it, 300) }
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> =
                if (lineIds.all { it == "eurostar" }) throw TflException.NotFound(null) else throw TflException.Offline(null)
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        assertFalse((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)

        lines = listOf("eurostar", "thameslink")
        vm.forceNextFetch()
        vm.refresh()
        advanceUntilIdle()
        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertEquals(setOf("eurostar", "thameslink"), loaded.stops.flatMap { it.departures }.mapTo(HashSet()) { it.lineId })
        assertTrue(loaded.disruptionUnknown)
    }

    @Test
    fun `a cold load shows a stop's departures while its closure check is still out`() = runTest(dispatcher) {
        val closureGate = CompletableDeferred<Unit>()
        val closure = StopDisruption("Station closed until further notice")
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 300))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId != seeds[0].id) return emptyList()
                closureGate.await()
                return listOf(closure)
            }
        }
        val vm = viewModel(client)
        runCurrent()

        // Every stop's departures are in: painted at once, not held through the grace for the
        // closure check, and that stop is marked as still being checked rather than clear.
        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), partial.stops.mapTo(HashSet()) { it.stopId })
        assertTrue(partial.pendingStops.isEmpty())
        assertEquals(setOf(seeds[0].id), partial.closurePending)
        assertTrue(partial.stops.first { it.stopId == seeds[0].id }.disruptions.isEmpty())

        closureGate.complete(Unit)
        advanceUntilIdle()
        val done = vm.state.value as DeparturesUiState.Loaded
        assertTrue(done.closurePending.isEmpty())
        assertEquals(listOf(closure), done.stops.first { it.stopId == seeds[0].id }.disruptions)
    }

    @Test
    fun `a closure waiting on its hub's name stays pending while other stops report`() = runTest(dispatcher) {
        val hubGate = CompletableDeferred<Unit>()
        val laterStop = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[1].id) laterStop.await()
                return listOf(departure("victoria", "Victoria", 300))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == seeds[0].id) listOf(StopDisruption("Station closed until further notice")) else emptyList()
            override suspend fun hubInfo(hubId: String): HubInfo {
                hubGate.await()
                return HubInfo("Oxford Circus Interchange")
            }
        }
        val vm = MainViewModel(client, listOf(seeds[0].copy(hubId = "HUBOXC"), seeds[1]), clock = { now }, io = dispatcher)
        runCurrent()
        // Its closure is back but its hub's name isn't; another stop lands and reports meanwhile.
        laterStop.complete(Unit)
        runCurrent()
        val meanwhile = vm.state.value as DeparturesUiState.Loaded
        val first = meanwhile.stops.first { it.stopId == seeds[0].id }
        // Never neither: still pending, or already carrying its notice.
        assertTrue(seeds[0].id in meanwhile.closurePending || first.disruptions.isNotEmpty())

        hubGate.complete(Unit)
        advanceUntilIdle()
        val done = vm.state.value as DeparturesUiState.Loaded
        assertTrue(done.closurePending.isEmpty())
        assertTrue(done.stops.first { it.stopId == seeds[0].id }.disruptions.isNotEmpty())
    }

    @Test
    fun `a stop whose closure is already known shows its departures while its hub's name is looked up`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val hubGate = CompletableDeferred<Unit>()
        val laterStop = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[1].id) laterStop.await()
                return listOf(departure("victoria", "Victoria", 300))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            override suspend fun hubInfo(hubId: String): HubInfo {
                hubGate.await()
                return HubInfo("Oxford Circus Interchange")
            }
        }
        // Another screen already found the stop closed, so its closure answer is in hand at once.
        shared.keep(seeds[0].id, shared.ask(now), listOf(StopDisruption("Station closed until further notice")))
        val vm = MainViewModel(
            client, listOf(seeds[0].copy(hubId = "HUBOXC"), seeds[1]), clock = { now }, io = dispatcher,
            disruptionCache = shared, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceTimeBy(FIRST_PAINT_GRACE_MS + 1)
        runCurrent()
        // Its departures are in; only the hub's name, for the closure's title, is still out.
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertTrue(shown.stops.any { it.stopId == seeds[0].id })
        assertTrue(seeds[0].id in shown.closurePending)

        hubGate.complete(Unit)
        laterStop.complete(Unit)
        advanceUntilIdle()
        val done = vm.state.value as DeparturesUiState.Loaded
        assertTrue(done.closurePending.isEmpty())
        assertTrue(done.stops.first { it.stopId == seeds[0].id }.disruptions.isNotEmpty())
    }

    @Test
    fun `a cold load's stops are worked out off the main thread as they land`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        val gate = CompletableDeferred<Unit>()
        // Whether each stop's merge (which asks when its arrivals were fetched) ran on the worker.
        val mergedOnWorker = mutableListOf<Boolean>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[1].id) gate.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            override fun fetchedAt(stopId: String): Instant? {
                mergedOnWorker += onWorker.get()
                return null
            }
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, compute = worker)
        advanceTimeBy(FIRST_PAINT_GRACE_MS + 1)
        runCurrent()

        // The first stop is shown while the second is still out, and was worked out off the main thread.
        assertEquals(listOf(seeds[0].id), (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId })
        assertTrue("$mergedOnWorker", mergedOnWorker.isNotEmpty() && mergedOnWorker.all { it })
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `the screen's journey stops are worked out off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        // Where each walk of the reported stops ran: the first, comparing them with those held, on the
        // worker, never on the caller's (the main) thread.
        val walked = mutableListOf<Boolean>()
        val origin = StopRef("940GZZLUKSX", "King's Cross St. Pancras")
        val reported = object : AbstractList<StopRef>() {
            override val size: Int get() = 1.also { walked += onWorker.get() }
            override fun get(index: Int): StopRef = origin.also { walked += onWorker.get() }
        }
        vm.setJourneyStops(reported)
        assertTrue(walked.isEmpty())
        advanceUntilIdle()
        assertTrue("$walked", walked.firstOrNull() == true)
    }

    @Test
    fun `the screen's journey destinations are worked out off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        // Where each walk of the reported destinations ran: the first, comparing them with those held,
        // on the worker, never on the caller's (the main) thread.
        val walked = mutableListOf<Boolean>()
        val destination = StopRef("940GZZLUKSX", "King's Cross St. Pancras")
        val reported = object : AbstractList<StopRef>() {
            override val size: Int get() = 1.also { walked += onWorker.get() }
            override fun get(index: Int): StopRef = destination.also { walked += onWorker.get() }
        }
        vm.setJourneyDestinations(reported)
        assertTrue(walked.isEmpty())
        advanceUntilIdle()
        assertTrue("$walked", walked.firstOrNull() == true)
    }

    @Test
    fun `which journey destinations to ask about is chosen off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        val askedFor = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>().also { askedFor += stopId }
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        // Where every walk of the reported destinations ran: their check's choice of which to ask walks
        // them too, so none is on the caller's (the main) thread.
        val walked = mutableListOf<Boolean>()
        val destination = StopRef("940GZZLUEUS", "Euston")
        // Each element read is one: a size alone ([List.isEmpty]) walks nothing.
        val reported = object : AbstractList<StopRef>() {
            override val size: Int get() = 1
            override fun get(index: Int): StopRef = destination.also { walked += onWorker.get() }
        }
        vm.setJourneyDestinations(reported)
        advanceUntilIdle()

        assertTrue(destination.id in askedFor)
        assertTrue("$walked", walked.isNotEmpty() && walked.all { it })
    }

    @Test
    fun `a journey destination's cards are worked out off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        val client = ReuseCountingClient()
        client.closures[ksxId] = listOf(StopDisruption("Station closed"))
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        // Where each walk of the destinations ran (each element read; asking whether there are any is
        // no walk): building their cards from the answers walks them on the worker, not the caller's (the
        // main) thread. Matching them to their requests is still the main thread's (TODO.md).
        val walked = mutableListOf<Boolean>()
        val destination = StopRef(ksxId, "King's Cross St. Pancras")
        val reported = object : AbstractList<StopRef>() {
            override val size: Int get() = 1
            override fun get(index: Int): StopRef = destination.also { walked += onWorker.get() }
        }
        vm.setJourneyDestinations(reported)
        advanceUntilIdle()
        assertEquals(listOf(StopDisruption("Station closed")), vm.journeyDestinationStops.value.single().disruptions)
        assertTrue("$walked", walked.lastOrNull() == true)
    }

    @Test
    fun `a lookup kept while the destination cards are worked out is shown, not marked unknown`() = runTest(dispatcher) {
        // A destination already cached isn't asked for; while the worker builds its card, another screen
        // keeps a newer lookup of it. The card shows that, rather than calling the stop unknown.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        fun release() {
            val blocks = held.toList()
            held.clear()
            blocks.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        }
        val client = ReuseCountingClient()
        var current = now
        val shared = StopClosureCache()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { current }, io = dispatcher, compute = worker,
            disruptionCache = shared, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        shared.keep(ksxId, shared.ask(now), listOf(StopDisruption("Station closed")))
        holding = true
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        // The report, then the cards (nothing asked: it's cached), each held on the worker.
        release()
        advanceUntilIdle()
        assertTrue(held.isNotEmpty())
        current = now.plusSeconds(30)
        shared.keep(ksxId, shared.ask(current), listOf(StopDisruption("Station closed until 10:00")))
        holding = false
        release()
        advanceUntilIdle()
        assertEquals(null, client.disruptionCalls[ksxId])
        assertEquals(listOf(StopDisruption("Station closed until 10:00")), vm.journeyDestinationStops.value.single().disruptions)
        assertTrue(vm.journeyDestinationsUnknown.value.isEmpty())
    }

    @Test
    fun `a lookup kept while the cards are worked out wins over the fetch's failure`() = runTest(dispatcher) {
        // The fetch the destination check waited on failed the stop's closure; while the worker builds its
        // card, another screen keeps a lookup of it. The card shows that, rather than calling it unknown.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        fun release() {
            val blocks = held.toList()
            held.clear()
            blocks.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        }
        val client = ReuseCountingClient()
        var current = now
        val shared = StopClosureCache()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { current }, io = dispatcher, compute = worker,
            disruptionCache = shared, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        // A refresh in flight, its closure lookup of the stop to fail; the destination check waits on it.
        client.failingDisruptions += oxcId
        val gate = CompletableDeferred<Unit>()
        client.arrivalsGate = gate
        current = now.plus(DISRUPTION_REUSE).plusSeconds(1)
        vm.refresh()
        runCurrent()
        vm.setJourneyDestinations(listOf(StopRef(oxcId, "Oxford Circus")))
        runCurrent()
        holding = true
        gate.complete(Unit)
        advanceUntilIdle()
        // The refresh's own work on the worker, step by step until it's done.
        while (vm.refreshing.value) {
            release()
            advanceUntilIdle()
        }
        // The cards (nothing asked: it failed in the fetch), held on the worker.
        assertTrue(held.isNotEmpty())
        current = current.plusSeconds(30)
        shared.keep(oxcId, shared.ask(current), listOf(StopDisruption("Station closed")))
        holding = false
        release()
        advanceUntilIdle()
        assertEquals(listOf(StopDisruption("Station closed")), vm.journeyDestinationStops.value.single().disruptions)
        assertTrue(vm.journeyDestinationsUnknown.value.isEmpty())
    }

    @Test
    fun `a destination's answer overtaken by a later failed lookup leaves it unknown`() = runTest(dispatcher) {
        // The destination's request comes back clear; while the worker builds its card, another screen's
        // lookup, asked later, fails. The cache then knows nothing of the stop, so neither does the card.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        fun release() {
            val blocks = held.toList()
            held.clear()
            blocks.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        }
        val client = ReuseCountingClient()
        var current = now
        val shared = StopClosureCache()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { current }, io = dispatcher, compute = worker,
            disruptionCache = shared, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        holding = true
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        // The report, and the choice of what to ask; then its request comes back, and the cards are held.
        while (client.disruptionCalls[ksxId] == null && held.isNotEmpty()) {
            release()
            advanceUntilIdle()
        }
        assertEquals(1, client.disruptionCalls[ksxId])
        assertTrue(held.isNotEmpty())
        current = now.plusSeconds(30)
        shared.settle(ksxId, shared.ask(current), Result.failure(java.io.IOException("offline")))
        holding = false
        release()
        advanceUntilIdle()
        assertEquals(setOf(ksxId), vm.journeyDestinationsUnknown.value)
        assertTrue(vm.journeyDestinationStops.value.isEmpty())
    }

    @Test
    fun `a refresh's stops are merged off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        // Whether each stop's merge (which asks when its arrivals were fetched) ran on the worker.
        val mergedOnWorker = mutableListOf<Boolean>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            override fun fetchedAt(stopId: String): Instant? {
                mergedOnWorker += onWorker.get()
                return null
            }
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        // A refresh over the list shown: no progress reports, only the batch's own merge.
        mergedOnWorker.clear()
        vm.refresh()
        advanceUntilIdle()

        assertEquals(seeds.map { it.id }.toSet(), (vm.state.value as DeparturesUiState.Loaded).stops.mapTo(HashSet()) { it.stopId })
        assertTrue("$mergedOnWorker", mergedOnWorker.isNotEmpty() && mergedOnWorker.all { it })
    }

    @Test
    fun `what a refresh is for, and what each stop needs, are worked out off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        // A journey's origin, noting where each walk of the journey stops runs: the refresh's own setup
        // (the ids it's for, the stops it asks) and each stop's plan (the lines a journey takes from it,
        // for its National Rail board) walk them.
        val walked = mutableListOf<Boolean>()
        val origin = StopRef("940GZZLUEUS", "Euston", lines = listOf(LineRef("victoria", "Victoria", "tube")))
        // Each element read is one: a size alone ([List.isEmpty]) walks nothing.
        val reported = object : AbstractList<StopRef>() {
            override val size: Int get() = 1
            override fun get(index: Int): StopRef = origin.also { walked += onWorker.get() }
        }
        vm.setJourneyStops(reported)
        advanceUntilIdle()
        walked.clear()

        vm.refresh()
        advanceUntilIdle()

        assertTrue(origin.id in (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId })
        assertTrue("$walked", walked.isNotEmpty() && walked.all { it })
    }

    @Test
    fun `a refresh sends nothing until the worker has worked out what to ask`() = runTest(dispatcher) {
        // A worker held on a scheduler of its own, so this (the main) thread can be run to idle without it.
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val held = StandardTestDispatcher(scheduler)
        val asked = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                asked += stopId
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, compute = held)
        repeat(10) {
            scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }
        assertTrue(vm.state.value is DeparturesUiState.Loaded)
        asked.clear()

        vm.refresh()
        advanceUntilIdle()
        // Its setup and each stop's plan wait on the worker: nothing is asked yet.
        assertEquals(emptyList<String>(), asked)

        repeat(10) {
            scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }
        assertEquals(seeds.map { it.id }.toSet(), asked.toSet())
    }

    @Test
    fun `a closure lookup that fails while the worker plans the batch isn't passed over`() = runTest(dispatcher) {
        // Runs what's handed to it at once, or, once holding, keeps it until let go, as a busy worker would.
        val held = mutableListOf<Runnable>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += block else dispatcher.dispatch(context, block)
            }
        }
        val asked = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>().also { asked += stopId }
        }
        val cache = StopClosureCache()
        val vm = MainViewModel(
            client,
            listOf(seeds.first()),
            clock = { now },
            io = dispatcher,
            compute = worker,
            disruptionCache = cache,
            disruptionReuse = java.time.Duration.ofMinutes(5),
        )
        advanceUntilIdle()
        // Checked once; a refresh now would take that answer from the cache.
        assertEquals(listOf(seeds.first().id), asked)
        asked.clear()

        holding = true
        vm.refresh()
        advanceUntilIdle()
        // The refresh's setup, run on the worker as this thread waits.
        held.single().also { held.clear() }.run()
        advanceUntilIdle()
        // Then its plan of each stop, which takes the cached answer.
        held.single().also { held.clear() }.run()
        // Before this thread sends the batch from it, another screen's newer lookup of the stop fails, so
        // the cached answer no longer answers for it.
        cache.settle(seeds.first().id, cache.ask(now), Result.failure(java.io.IOException("offline")))
        holding = false
        advanceUntilIdle()
        held.forEach { it.run() }
        held.clear()
        advanceUntilIdle()

        // Asked again rather than shown as checked on an answer older than its latest check.
        assertEquals(listOf(seeds.first().id), asked)
    }

    @Test
    fun `a closure kept while the worker plans the batch stops a stop being carried over`() = runTest(dispatcher) {
        // Runs what's handed to it at once, or, once holding, keeps it until let go, as a busy worker would.
        val held = mutableListOf<Runnable>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += block else dispatcher.dispatch(context, block)
            }
        }
        val fetched = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120)).also { fetched += stopId }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val cache = StopClosureCache()
        val vm = MainViewModel(
            client,
            listOf(seeds.first()),
            clock = { now },
            io = dispatcher,
            compute = worker,
            arrivalsReuse = java.time.Duration.ofMinutes(5),
            disruptionCache = cache,
        )
        advanceUntilIdle()
        // Fetched moments ago: a refresh now would carry it over unasked.
        assertEquals(listOf(seeds.first().id), fetched)
        fetched.clear()

        holding = true
        vm.refresh()
        advanceUntilIdle()
        // The refresh's setup, then its plan of each stop, run on the worker as this thread waits.
        held.single().also { held.clear() }.run()
        advanceUntilIdle()
        held.single().also { held.clear() }.run()
        // Before this thread sends the batch, another screen keeps a newer closure lookup of the stop:
        // carried over, the stop would show without it.
        cache.keep(seeds.first().id, cache.ask(now), listOf(StopDisruption("Station closed")))
        holding = false
        advanceUntilIdle()
        held.forEach { it.run() }
        held.clear()
        advanceUntilIdle()

        // Fetched again, its closure with it, rather than carried over past the newer lookup.
        assertEquals(listOf(seeds.first().id), fetched)
    }

    @Test
    fun `a newer closure lookup that failed stops a stop being carried over`() = runTest(dispatcher) {
        val fetched = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120)).also { fetched += stopId }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val cache = StopClosureCache()
        val vm = MainViewModel(
            client,
            listOf(seeds.first()),
            clock = { now },
            io = dispatcher,
            arrivalsReuse = java.time.Duration.ofMinutes(5),
            disruptionCache = cache,
        )
        advanceUntilIdle()
        assertEquals(listOf(seeds.first().id), fetched)
        fetched.clear()
        // Another screen's lookup of the stop, asked after the one it shows, fails: its closure is unknown
        // now, and carried over it would read as checked.
        cache.settle(seeds.first().id, cache.ask(now), Result.failure(java.io.IOException("offline")))

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(seeds.first().id), fetched)
    }

    @Test
    fun `a closure lookup that fails while the worker plans the batch stops a stop being carried over`() = runTest(dispatcher) {
        // Runs what's handed to it at once, or, once holding, keeps it until let go, as a busy worker would.
        val held = mutableListOf<Runnable>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += block else dispatcher.dispatch(context, block)
            }
        }
        val fetched = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120)).also { fetched += stopId }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val cache = StopClosureCache()
        val vm = MainViewModel(
            client,
            listOf(seeds.first()),
            clock = { now },
            io = dispatcher,
            compute = worker,
            arrivalsReuse = java.time.Duration.ofMinutes(5),
            disruptionCache = cache,
        )
        advanceUntilIdle()
        assertEquals(listOf(seeds.first().id), fetched)
        fetched.clear()

        holding = true
        vm.refresh()
        advanceUntilIdle()
        // The refresh's setup, then its plan of each stop (carry it over), run on the worker as this
        // thread waits.
        held.single().also { held.clear() }.run()
        advanceUntilIdle()
        held.single().also { held.clear() }.run()
        // Before this thread sends the batch, another screen's newer lookup of the stop fails.
        cache.settle(seeds.first().id, cache.ask(now), Result.failure(java.io.IOException("offline")))
        holding = false
        advanceUntilIdle()
        held.forEach { it.run() }
        held.clear()
        advanceUntilIdle()

        assertEquals(listOf(seeds.first().id), fetched)
    }

    @Test
    fun `poles whose newer closure lookup failed before the plan are asked in one batch`() = runTest(dispatcher) {
        // Runs what's handed to it at once, or, once holding, keeps it until let go, as a busy worker would.
        val held = mutableListOf<Runnable>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += block else dispatcher.dispatch(context, block)
            }
        }
        val batches = mutableListOf<List<String>>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            override suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> {
                batches += stopIds
                return stopIds.associateWith { emptyList() }
            }
        }
        val cache = StopClosureCache()
        val poles = listOf("490000001A", "490000002B")
        val vm = MainViewModel(
            client,
            listOf(
                StopRef(poles[0], "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef(poles[1], "Sample Street", clusterId = "490G000SAMPLE"),
            ),
            clock = { now },
            io = dispatcher,
            compute = worker,
            arrivalsReuse = java.time.Duration.ofMinutes(5),
            disruptionCache = cache,
        )
        advanceUntilIdle()
        assertEquals(listOf(poles), batches)
        batches.clear()

        holding = true
        vm.refresh()
        advanceUntilIdle()
        // The refresh's setup runs on the worker as this thread waits: both poles to carry over.
        held.single().also { held.clear() }.run()
        advanceUntilIdle()
        // Before the worker plans the batch, another screen's newer lookup of each pole fails.
        poles.forEach { cache.settle(it, cache.ask(now), Result.failure(java.io.IOException("offline"))) }
        held.single().also { held.clear() }.run()
        holding = false
        advanceUntilIdle()
        held.forEach { it.run() }
        held.clear()
        advanceUntilIdle()

        // Both asked again, together: one request for the junction, not one per pole.
        assertEquals(listOf(poles), batches)
    }

    @Test
    fun `a closure another screen finds while a refresh is out is shown with it`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val cache = StopClosureCache()
        val (carried, fetched) = seeds.map { it.id }
        // The second stop's first fetch fails, so a refresh moments later asks it again and carries the
        // first over.
        client.failingArrivals += fetched
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, arrivalsReuse = ARRIVALS_REUSE, disruptionCache = cache)
        advanceUntilIdle()
        client.failingArrivals.clear()
        val gate = CompletableDeferred<Unit>()
        client.arrivalsGate = gate
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[carried])
        // While the refresh waits on the second stop, another screen finds the first one closed.
        val closed = StopDisruption("Station closed")
        cache.keep(carried, cache.ask(now), listOf(closed))
        gate.complete(Unit)
        advanceUntilIdle()

        val shown = (vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == carried }
        assertEquals(listOf(closed), shown.disruptions)
    }

    @Test
    fun `a closure check another screen fails while a refresh is out leaves the stop unchecked`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val cache = StopClosureCache()
        val (carried, fetched) = seeds.map { it.id }
        client.failingArrivals += fetched
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, arrivalsReuse = ARRIVALS_REUSE, disruptionCache = cache)
        advanceUntilIdle()
        client.failingArrivals.clear()
        val gate = CompletableDeferred<Unit>()
        client.arrivalsGate = gate
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[carried])
        // While the refresh waits on the second stop, another screen's newer check of the first fails.
        cache.settle(carried, cache.ask(now), Result.failure(java.io.IOException("offline")))
        gate.complete(Unit)
        advanceUntilIdle()

        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertTrue(carried in loaded.stopsDisruptionUnknown)
        assertTrue(loaded.disruptionUnknown)
    }

    @Test
    fun `a closure another screen finds after a refresh's own check failed is shown with it`() = runTest(dispatcher) {
        val (held, failing) = seeds.map { it.id }
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == held) gate.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == failing) throw TflException.RateLimited(null) else emptyList()
        }
        val cache = StopClosureCache()
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, disruptionCache = cache)
        // The second stop's closure check fails while the first stop's departures are still out.
        advanceUntilIdle()
        assertNull(cache[failing])
        // Before the load is shown, another screen finds the second stop closed.
        val closed = StopDisruption("Station closed")
        cache.keep(failing, cache.ask(now), listOf(closed))
        gate.complete(Unit)
        advanceUntilIdle()

        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf(closed), loaded.stops.single { it.stopId == failing }.disruptions)
        assertFalse(failing in loaded.stopsDisruptionUnknown)
    }

    /**
     * A cold load of [seeds] whose second stop's arrivals fail but whose closure check finds it closed,
     * so it's kept for that closure alone, while the first stop's arrivals are held until [settle] has
     * run: what another screen does meanwhile.
     */
    private fun TestScope.closureOnlyLoad(cache: StopClosureCache, settle: (String) -> Unit): DeparturesUiState.Loaded {
        val (held, closedStop) = seeds.map { it.id }
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == held) gate.await()
                if (stopId == closedStop) throw TflException.RateLimited(null)
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == closedStop) listOf(StopDisruption("Station closed")) else emptyList()
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, disruptionCache = cache)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(StopDisruption("Station closed")), cache[closedStop]?.notices)
        settle(closedStop)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        return vm.state.value as DeparturesUiState.Loaded
    }

    @Test
    fun `a stop kept for its closure alone goes when another screen finds it clear meanwhile`() = runTest(dispatcher) {
        val cache = StopClosureCache()
        val closedStop = seeds[1].id
        val loaded = closureOnlyLoad(cache) { cache.keep(it, cache.ask(now), emptyList()) }

        assertFalse(loaded.stops.any { it.stopId == closedStop })
        assertTrue(closedStop in loaded.unavailableStopIds)
    }

    @Test
    fun `a stop kept for its closure alone goes when another screen's check of it fails meanwhile`() = runTest(dispatcher) {
        val cache = StopClosureCache()
        val closedStop = seeds[1].id
        val loaded = closureOnlyLoad(cache) { cache.settle(it, cache.ask(now), Result.failure(java.io.IOException("offline"))) }

        assertFalse(loaded.stops.any { it.stopId == closedStop })
        assertTrue(closedStop in loaded.unavailableStopIds)
    }

    @Test
    fun `a move while a refresh is out isn't undone by it`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val (carried, fetched) = seeds.map { it.id }
        client.failingArrivals += fetched
        val vm = MainViewModel(
            client, seeds, clock = { now }, io = dispatcher, arrivalsReuse = ARRIVALS_REUSE,
            stopDistanceMeters = mapOf(carried to 100.0, fetched to 900.0),
        )
        advanceUntilIdle()
        client.failingArrivals.clear()
        val gate = CompletableDeferred<Unit>()
        client.arrivalsGate = gate
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[carried])
        // While the refresh waits on the second stop, the rider moves: the second is now the nearer.
        vm.remeasure(mapOf(carried to 900.0, fetched to 100.0))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val shown = (vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == carried }
        assertTrue(fetched in shown.nearer.ids)
    }

    @Test
    fun `arrivals another screen fetches while the worker plans the batch are taken over a carry-over`() = runTest(dispatcher) {
        // Runs what's handed to it at once, or, once holding, keeps it until let go, as a busy worker would.
        val held = mutableListOf<Runnable>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += block else dispatcher.dispatch(context, block)
            }
        }
        val fetched = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120)).also { fetched += stopId }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val shared = ArrivalsCache()
        var current = now
        val vm = MainViewModel(
            client,
            listOf(seeds.first()),
            clock = { current },
            io = dispatcher,
            compute = worker,
            arrivalsReuse = java.time.Duration.ofMinutes(5),
            sharedArrivals = shared,
        )
        advanceUntilIdle()
        assertEquals(listOf(seeds.first().id), fetched)
        fetched.clear()
        current = now.plusSeconds(30)

        holding = true
        vm.refresh()
        advanceUntilIdle()
        // The refresh's setup, then its plan of each stop (carry it over), run on the worker as this
        // thread waits.
        held.single().also { held.clear() }.run()
        advanceUntilIdle()
        held.single().also { held.clear() }.run()
        // Before this thread sends the batch, another screen fetches the stop's arrivals afresh.
        shared.put(seeds.first().id, listOf(departure("northern", "Northern", 60)), now.plusSeconds(20))
        holding = false
        advanceUntilIdle()
        held.forEach { it.run() }
        held.clear()
        advanceUntilIdle()

        // Taken, as two screens mustn't show different times, rather than the older carried over.
        assertEquals(emptyList<String>(), fetched)
        val stop = (vm.state.value as DeparturesUiState.Loaded).stops.single()
        assertEquals(listOf("northern"), stop.departures.map { it.lineId })
    }

    @Test
    fun `a cold load all back within the grace paints once, whole`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(GatedClient(seeds[1].id, gate))
        // One stop is in, but the grace isn't up: nothing part-shown yet.
        advanceTimeBy(FIRST_PAINT_GRACE_MS - 100)
        runCurrent()
        assertEquals(DeparturesUiState.Loading, vm.state.value)

        gate.complete(Unit)
        runCurrent()
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), shown.stops.mapTo(HashSet()) { it.stopId })
        assertTrue(shown.pendingStops.isEmpty())
        // Nothing held back paints over it once the grace runs out.
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).pendingStops.isEmpty())
    }

    @Test
    fun `a cold load still out after the grace shows what's in`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(GatedClient(seeds[1].id, gate))
        advanceTimeBy(FIRST_PAINT_GRACE_MS + 1)
        runCurrent()
        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf(seeds[0].id), partial.stops.map { it.stopId })
        assertEquals(listOf(seeds[1].id), partial.pendingStops.map { it.id })
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a refresh over a kept snapshot never part-shows`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(
            DeparturesSnapshot(
                seeds.map { stopArrivals(it.id, it.name, 300, now.minusSeconds(120)) },
                now.minusSeconds(120),
            ),
        )
        val vm = viewModel(GatedClient(seeds[1].id, gate), store)
        advanceUntilIdle()

        // The saved list stays up, whole, until the batch is done.
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertTrue(shown.pendingStops.isEmpty())
        assertEquals(2, shown.stops.size)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a refresh during a part-shown cold load keeps the stops already shown`() = runTest(dispatcher) {
        // Each stop's arrivals wait on its gate, if it has one when asked.
        val gates = mutableMapOf(seeds[1].id to CompletableDeferred<Unit>())
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                gates[stopId]?.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(listOf(seeds[0].id), (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId })

        // A pull to refresh: this time the first stop is slow and the second answers first.
        gates[seeds[0].id] = CompletableDeferred()
        gates.getValue(seeds[1].id).complete(Unit)
        vm.refresh()
        advanceUntilIdle()
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), shown.stops.mapTo(HashSet()) { it.stopId })
        assertTrue(shown.pendingStops.isEmpty())
        assertTrue(shown.statusPending)
        gates.getValue(seeds[0].id).complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a stop carried into a restarted cold load reads as checking until its closure check is back`() = runTest(dispatcher) {
        // Each stop's arrivals wait on its gate, if it has one when asked.
        val gates = mutableMapOf(seeds[1].id to CompletableDeferred<Unit>())
        var closureFails = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                gates[stopId]?.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                // The first check fails, so nothing is cached and the restart asks again.
                if (closureFails) throw TflException.Offline(null)
                return emptyList()
            }
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(listOf(seeds[0].id), (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId })

        // Restarted: the first stop, shown from before, waits on its departures, so its closure
        // check hasn't even gone out yet.
        closureFails = false
        gates[seeds[0].id] = CompletableDeferred()
        vm.refresh()
        runCurrent()
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertTrue(seeds[0].id in shown.closurePending)

        gates.getValue(seeds[0].id).complete(Unit)
        gates.getValue(seeds[1].id).complete(Unit)
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).closurePending.isEmpty())
    }

    @Test
    fun `a stop carried into a restarted cold load reads as checking even with its closure answer cached`() = runTest(dispatcher) {
        // Each stop's arrivals wait on its gate, if it has one when asked.
        val gates = mutableMapOf(seeds[1].id to CompletableDeferred<Unit>())
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                gates[stopId]?.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, disruptionReuse = DISRUPTION_REUSE)
        advanceUntilIdle()
        assertEquals(listOf(seeds[0].id), (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId })

        // Restarted: the first stop's closure answer is cached, but it isn't merged into the row
        // until the stop's departures are back, so the row isn't yet vouched for.
        gates[seeds[0].id] = CompletableDeferred()
        vm.refresh()
        runCurrent()
        assertTrue(seeds[0].id in (vm.state.value as DeparturesUiState.Loaded).closurePending)

        gates.getValue(seeds[0].id).complete(Unit)
        gates.getValue(seeds[1].id).complete(Unit)
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).closurePending.isEmpty())
    }

    @Test
    fun `a carried stop's closure found cleared drops its old notice though its departures failed`() = runTest(dispatcher) {
        // Each stop's arrivals wait on its gate, if it has one when asked.
        val gates = mutableMapOf(seeds[1].id to CompletableDeferred<Unit>())
        var arrivalsFail = false
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                gates[stopId]?.await()
                if (arrivalsFail && stopId == seeds[0].id) throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (closed && stopId == seeds[0].id) listOf(StopDisruption("Station closed until further notice")) else emptyList()
        }
        val vm = viewModel(client)
        advanceTimeBy(FIRST_PAINT_GRACE_MS + 1)
        runCurrent()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == seeds[0].id }.disruptions.isNotEmpty())

        // Restarted: the first stop's departures now fail, but its closure check finds it open again.
        arrivalsFail = true
        closed = false
        vm.refresh()
        runCurrent()
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertFalse(seeds[0].id in shown.closurePending)
        assertTrue(shown.stops.single { it.stopId == seeds[0].id }.disruptions.isEmpty())

        gates.getValue(seeds[1].id).complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a refresh after a cold load is cut short still shows stops as they land`() = runTest(dispatcher) {
        // The app's pull to refresh cancels (cancelFetch) before the next fetch starts.
        val gates = mutableMapOf(seeds[1].id to CompletableDeferred<Unit>())
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                gates[stopId]?.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        vm.cancelFetch()

        gates[seeds[0].id] = CompletableDeferred()
        gates.getValue(seeds[1].id).complete(Unit)
        vm.refresh()
        advanceUntilIdle()
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), shown.stops.mapTo(HashSet()) { it.stopId })
        assertTrue(shown.statusPending)
        gates.getValue(seeds[0].id).complete(Unit)
        advanceUntilIdle()
        assertFalse((vm.state.value as DeparturesUiState.Loaded).statusPending)
    }

    @Test
    fun `a stop that fails mid-load is named at once, not left loading`() = runTest(dispatcher) {
        val statusGate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[1].id) throw TflException.Unreachable("boom", null)
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                statusGate.await()
                return emptyList()
            }
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(client)
        advanceUntilIdle()

        // Line status is still out, but the failure is already known.
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertTrue(shown.statusPending)
        assertTrue(shown.pendingStops.isEmpty())
        assertTrue(shown.partialRefresh)
        assertEquals(setOf(seeds[1].id), shown.partialStops.keys)
        statusGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a stop that fails before any lands is named while the rest load`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[0].id) throw TflException.Unreachable("boom", null)
                gate.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(client)
        advanceUntilIdle()

        val shown = vm.state.value as DeparturesUiState.Loaded
        assertTrue(shown.stops.isEmpty())
        assertEquals(listOf(seeds[1].id), shown.pendingStops.map { it.id })
        assertEquals(setOf(seeds[0].id), shown.partialStops.keys)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(seeds[1].id), (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId })
    }

    @Test
    fun `a cold load whose every stop fails says so before its closure checks finish`() = runTest(dispatcher) {
        val closureGate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = throw TflException.Offline(null)
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                closureGate.await()
                return emptyList()
            }
        }
        val vm = viewModel(client)
        advanceUntilIdle()

        assertEquals(DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE), vm.state.value)
        closureGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE), vm.state.value)
    }

    @Test
    fun `a closure after every stop failed shows at once, within the grace`() = runTest(dispatcher) {
        val closureGate = CompletableDeferred<Unit>()
        val statusGate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = throw TflException.Offline(null)
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                statusGate.await()
                return emptyList()
            }
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId != seeds[0].id) return emptyList()
                closureGate.await()
                return listOf(StopDisruption("Station closed until further notice"))
            }
        }
        val vm = viewModel(client)
        runCurrent()
        assertEquals(DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE), vm.state.value)

        // Still inside the grace: the closure replaces the error at once, not held back.
        closureGate.complete(Unit)
        runCurrent()
        val shown = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf(seeds[0].id), shown.stops.map { it.stopId })
        statusGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a closure shows mid-load even when its stop's arrivals failed`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[0].id) throw TflException.Unreachable("boom", null)
                gate.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == seeds[0].id) listOf(StopDisruption("Station closed until further notice")) else emptyList()
        }
        val vm = viewModel(client)
        advanceUntilIdle()

        val shown = vm.state.value as DeparturesUiState.Loaded
        assertEquals(
            listOf("Station closed until further notice"),
            shown.stops.single { it.stopId == seeds[0].id }.disruptions.map { it.description },
        )
        // Its arrivals still failed, so it's still named.
        assertEquals(setOf(seeds[0].id), shown.partialStops.keys)
        assertEquals(listOf(seeds[1].id), shown.pendingStops.map { it.id })
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a retry that reuses every stop still says it is checking for disruptions`() = runTest(dispatcher) {
        // Cut short during its line-status check, after every stop landed; the retry reuses them all.
        val statusGate = CompletableDeferred<Unit>()
        val vm = MainViewModel(
            GatedClient(seeds[1].id, CompletableDeferred(Unit), statusGate),
            seeds,
            clock = { now },
            io = dispatcher,
            arrivalsReuse = java.time.Duration.ofSeconds(30),
        )
        advanceUntilIdle()
        vm.cancelFetch()
        assertFalse((vm.state.value as DeparturesUiState.Loaded).statusPending)

        vm.refresh()
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).statusPending)
        statusGate.complete(Unit)
        advanceUntilIdle()
        assertFalse((vm.state.value as DeparturesUiState.Loaded).statusPending)
    }

    @Test
    fun `a cold load cut short names the stops it never got`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(GatedClient(seeds[1].id, gate))
        advanceUntilIdle()

        vm.cancelFetch()
        val state = vm.state.value as DeparturesUiState.Loaded
        assertTrue(state.pendingStops.isEmpty())
        assertTrue(state.partialRefresh)
        assertEquals(setOf(seeds[1].id), state.partialStops.keys)
        assertEquals(listOf(seeds[0].id), state.stops.map { it.stopId })
    }

    @Test
    fun `a cold load cut short while a closure check is out says it couldn't check, not checking`() = runTest(dispatcher) {
        val closureGate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 300))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId == seeds[0].id) closureGate.await()
                return emptyList()
            }
        }
        val vm = viewModel(client)
        runCurrent()
        assertEquals(setOf(seeds[0].id), (vm.state.value as DeparturesUiState.Loaded).closurePending)

        // A relocation cancels the fetch: nothing will finish that check, so no spinner stays up.
        vm.cancelFetch()
        val state = vm.state.value as DeparturesUiState.Loaded
        assertTrue(state.closurePending.isEmpty())
        assertTrue(seeds[0].id in state.stopsDisruptionUnknown)
        assertTrue(state.disruptionUnknown)
        closureGate.complete(Unit)
    }

    @Test
    fun `a total failure after a load keeps the aged last-good snapshot`() = runTest(dispatcher) {
        var failing = false
        // A genuine total failure: BOTH the arrivals and the (now-decoupled) disruption
        // request fail, so nothing fresh comes back at all. If only arrivals failed while
        // disruption succeeded, that would be a partial refresh, not this.
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (failing) throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (failing) throw TflException.Offline(null)
                return emptyList()
            }
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        val loaded = vm.state.value
        assertTrue(loaded is DeparturesUiState.Loaded)
        loaded as DeparturesUiState.Loaded

        failing = true
        vm.refresh()
        advanceUntilIdle()

        // The refresh failed outright: the departures the user was reading stay on screen
        // (same aged data, at the same age), but they are now flagged not-fresh and the
        // failure is carried so the screen can say so rather than pass stale rows off as
        // fresh (SPEC D4 / principle 2).
        val kept = vm.state.value
        assertTrue(kept is DeparturesUiState.Loaded)
        kept as DeparturesUiState.Loaded
        assertEquals(loaded.stops.map { it.departures }, kept.stops.map { it.departures })
        assertEquals(loaded.fetchedAt, kept.fetchedAt)
        assertTrue(kept.stops.none { it.arrivalsFresh })
        assertEquals(false, kept.partialRefresh)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, kept.refreshFailure)
    }

    @Test
    fun `a disrupted line is carried in the snapshot, a good-service line is not`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                // Victoria has severe delays; Northern is running well — only the
                // disruption is carried, so a non-null row status always means "flag it".
                statuses = Result.success(
                    listOf(
                        status("victoria", 6, "Severe Delays"),
                        status("northern", LineStatus.GOOD_SERVICE, "Good Service"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(setOf("victoria"), state.lineStatuses.keys)
        assertEquals("Severe Delays", state.lineStatuses.getValue("victoria").description)
        assertEquals(false, state.disruptionUnknown)
    }

    @Test
    fun `a line TfL leaves out reaches the widget as a no-verdict check`() = runTest(dispatcher) {
        val store = FakeStore()
        viewModel(
            FakeClient(
                mapOf("940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300)))),
                statuses = Result.success(emptyList()),
            ),
            store,
        )
        advanceUntilIdle()

        val check = store.saves.last().lineStatuses.getValue("victoria")
        assertFalse(check.known)
        assertEquals(now, check.checkedAt)
    }

    @Test
    fun `a verdict after the clock moves back replaces an earlier omission`() = runTest(dispatcher) {
        val store = FakeStore()
        var time = now
        val client = FakeClient(
            mapOf("940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300)))),
            statuses = Result.success(emptyList()),
        )
        val vm = MainViewModel(client, seeds, clock = { time }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        assertFalse(store.saves.last().lineStatuses.getValue("victoria").known)

        // The clock is set back, then TfL answers: the omission is dated after the verdict, but
        // the verdict is the later answer and must win.
        time = now.minusSeconds(600)
        client.statuses = Result.success(listOf(status("victoria", 6, "Severe Delays")))
        vm.refresh()
        advanceUntilIdle()

        val check = store.saves.last().lineStatuses.getValue("victoria")
        assertTrue(check.known)
        assertEquals(6, check.status.severity)
        assertEquals(time, check.checkedAt)
    }

    @Test
    fun `the widget snapshot carries each shown line's status check, stamped`() = runTest(dispatcher) {
        val store = FakeStore()
        viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                statuses = Result.success(
                    listOf(status("victoria", 6, "Severe Delays"), status("northern", LineStatus.GOOD_SERVICE, "Good Service")),
                ),
            ),
            store,
        )
        advanceUntilIdle()

        val checks = store.saves.last().lineStatuses
        // Good services are kept too, so a newer "good" can replace an older disruption on merge.
        assertEquals(setOf("victoria", "northern"), checks.keys)
        assertTrue(checks.values.all { it.known })
        assertEquals(6, checks.getValue("victoria").status.severity)
        assertEquals(now, checks.getValue("victoria").checkedAt)
    }

    @Test
    fun `a line TfL returned no status for is treated as unknown, not clean`() = runTest(dispatcher) {
        val warnings = mutableListOf<String>()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                // Victoria came back disrupted; Northern's status is absent (the client
                // drops a line with no entries). That undetermined line must flag the
                // screen "status unknown" rather than let Northern read as verified-clean.
                statuses = Result.success(listOf(status("victoria", 6, "Severe Delays"))),
            ),
            warn = { warnings += it },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(setOf("victoria"), state.lineStatuses.keys)
        assertTrue(state.disruptionUnknown)
        // The reason is logged and names the undetermined line, so a persistent
        // "couldn't check for disruptions" is diagnosable from logcat.
        assertTrue(
            "the undetermined line is named in the log",
            warnings.any { it.contains("no status") && it.contains("northern") },
        )
    }

    @Test
    fun `fetches status for declared lines and carries them, so a no-prediction line is known`() =
        runTest(dispatcher) {
            val client = FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    // King's Cross returns no arrivals for its declared Circle line.
                    "940GZZLUKSX" to Result.success(emptyList()),
                ),
                statuses = Result.success(listOf(status("circle", 2, "Suspended"))),
            )
            val vm = MainViewModel(
                client,
                listOf(
                    StopRef("940GZZLUOXC", "Oxford Circus", listOf(LineRef("victoria", "Victoria", "tube"))),
                    StopRef("940GZZLUKSX", "King's Cross St. Pancras", listOf(LineRef("circle", "Circle", "tube"))),
                ),
                clock = { now },
                io = dispatcher,
            )
            advanceUntilIdle()

            // Circle has no prediction, but as a declared line it's still status-checked —
            // that's what lets it surface as a status row rather than vanish.
            assertTrue(client.requestedLineIds!!.contains("circle"))
            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals("Suspended", state.lineStatuses["circle"]?.description)
            // The declared lines travel through to the snapshot for the screen's grouping.
            assertEquals(
                listOf("circle"),
                state.stops.single { it.stopId == "940GZZLUKSX" }.lines.map { it.id },
            )
        }

    @Test
    fun `a departure with a blank line id leaves the disruption state unknown`() = runTest(dispatcher) {
        val warnings = mutableListOf<String>()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    // TfL gave this prediction no line id, so its disruption can't be
                    // checked — it must not read as verified-clean.
                    "940GZZLUKSX" to Result.success(listOf(departure("", "", 120))),
                ),
                // Victoria comes back good, so the only reason for unknown is the blank id.
                statuses = Result.success(listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
            ),
            warn = { warnings += it },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertTrue(state.lineStatuses.isEmpty())
        assertTrue(state.disruptionUnknown)
        // The blank-line-id reason is logged, so this cause is distinguishable in logcat.
        assertTrue(
            "the blank-line-id reason is logged",
            warnings.any { it.contains("no line id") },
        )
    }

    @Test
    fun `a failed status lookup flags disruptionUnknown but keeps the arrivals`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                statuses = Result.failure(TflException.Offline(null)),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        // Arrivals still shown — a failed disruption check must not blank them — but the
        // screen is told their status is unknown rather than passing them off as clean.
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
        assertTrue(state.lineStatuses.isEmpty())
        assertTrue(state.disruptionUnknown)
    }

    @Test
    fun `carries a stop's own disruptions into the snapshot`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(emptyList()),
                ),
                disruptionsByStop = mapOf(
                    "940GZZLUKSX" to Result.success(listOf(StopDisruption("Station closed until further notice"))),
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(
            listOf("Station closed until further notice"),
            state.stops.single { it.stopId == "940GZZLUKSX" }.disruptions.map { it.description },
        )
    }

    @Test
    fun `a closure answered by another screen while departures were out isn't asked again`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val gate = CompletableDeferred<Unit>()
        val closureCalls = mutableListOf<String>()
        val closed = listOf(StopDisruption("Station closed until further notice"))
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[0].id) gate.await()
                return listOf(departure("victoria", "Victoria", 300))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                closureCalls += stopId
                return emptyList()
            }
        }
        var clockNow = now
        val vm = MainViewModel(client, seeds, clock = { clockNow }, io = dispatcher, disruptionCache = shared, disruptionReuse = DISRUPTION_REUSE)
        runCurrent()
        // While this stop's departures are out, another screen (a trip) checks its closure, a few
        // seconds after this fetch began.
        clockNow = now.plusSeconds(5)
        shared.keep(seeds[0].id, shared.ask(clockNow), closed)
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(seeds[0].id in closureCalls)
        assertEquals(closed, (vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == seeds[0].id }.disruptions)
    }

    @Test
    fun `a closure check sent after its departures counts as newer than one another screen sent meanwhile`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[0].id) gate.await()
                return listOf(departure("victoria", "Victoria", 300))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, disruptionCache = shared)
        runCurrent()
        // While this stop's departures are out, another screen's lookup of it is sent and fails.
        shared.settle(seeds[0].id, shared.ask(now), Result.failure(TflException.Offline(null)))
        gate.complete(Unit)
        advanceUntilIdle()

        // This list's check went out after that one, so its answer is the one the cache keeps, and
        // the stop reads as checked, consistently.
        assertTrue(shared[seeds[0].id] != null)
        assertFalse(seeds[0].id in (vm.state.value as DeparturesUiState.Loaded).stopsDisruptionUnknown)
    }

    @Test
    fun `a closure lookup landing after a trip's newer one shows the newer`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val closed = listOf(StopDisruption("Station closed until further notice"))
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = listOf(departure("victoria", "Victoria", 300))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                // A trip's lookup of King's Cross, asked after this one in the same instant, lands first.
                if (stopId == "940GZZLUKSX") shared.keep(stopId, shared.ask(now), closed)
                return emptyList()
            }
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, disruptionCache = shared)
        advanceUntilIdle()
        val state = vm.state.value as DeparturesUiState.Loaded
        assertEquals(closed, state.stops.single { it.stopId == "940GZZLUKSX" }.disruptions)
        assertTrue(state.stops.single { it.stopId == "940GZZLUOXC" }.disruptions.isEmpty())
    }

    @Test
    fun `a closure lookup failing after a trip's newer one landed shows the newer, not a failure`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val closed = listOf(StopDisruption("Station closed until further notice"))
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = listOf(departure("victoria", "Victoria", 300))
            // Every line checked, so only a closure could leave the list unchecked.
            override suspend fun lineStatuses(lineIds: Collection<String>) = listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                // A trip's lookup of King's Cross, asked after this one, lands first; this one fails.
                if (stopId == "940GZZLUKSX") {
                    shared.keep(stopId, shared.ask(now), closed)
                    throw TflException.Offline(null)
                }
                return emptyList()
            }
        }
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, disruptionCache = shared)
        advanceUntilIdle()
        val state = vm.state.value as DeparturesUiState.Loaded
        assertEquals(closed, state.stops.single { it.stopId == "940GZZLUKSX" }.disruptions)
        assertEquals(false, state.disruptionUnknown)
    }

    @Test
    fun `a failed disruption refresh drops the prior closure rather than showing it stale`() =
        runTest(dispatcher) {
            var disruptionFails = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String) =
                    listOf(departure("victoria", "Victoria", 300))

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    if (disruptionFails) throw TflException.Offline(null)
                    return if (stopId == "940GZZLUOXC") {
                        listOf(StopDisruption("Station closed until further notice"))
                    } else {
                        emptyList()
                    }
                }
            }
            val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher)
            advanceUntilIdle()
            val first = vm.state.value
            assertTrue(first is DeparturesUiState.Loaded)
            first as DeparturesUiState.Loaded
            assertEquals(
                listOf("Station closed until further notice"),
                first.stops.single { it.stopId == "940GZZLUOXC" }.disruptions.map { it.description },
            )

            // Next refresh: arrivals still succeed, but the disruption fetch fails. The stale
            // closure is dropped, not shown beside a fresh arrivals stamp; the disruption
            // state is flagged unknown instead (SPEC principle 1).
            disruptionFails = true
            vm.refresh()
            advanceUntilIdle()
            val second = vm.state.value
            assertTrue(second is DeparturesUiState.Loaded)
            second as DeparturesUiState.Loaded
            assertTrue(second.stops.single { it.stopId == "940GZZLUOXC" }.disruptions.isEmpty())
            assertTrue(second.disruptionUnknown)
        }

    @Test
    fun `a failed stop-disruption lookup flags disruptionUnknown, keeping the arrivals`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                // Oxford Circus's stop-disruption lookup fails — we can't verify it isn't
                // closed, so the departures stay but the state is flagged unknown.
                disruptionsByStop = mapOf("940GZZLUOXC" to Result.failure(TflException.Offline(null))),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
        assertTrue(state.disruptionUnknown)
        // Only the stop whose disruption lookup failed is unchecked — per-stop, so its rows
        // read "couldn't check" while the other stop's determined rows stay clean (Codex #100).
        assertEquals(setOf("940GZZLUOXC"), state.stopsDisruptionUnknown)
    }

    @Test
    fun `known-empty stops with failed disruption lookups render empty, not an error`() =
        runTest(dispatcher) {
            // Arrivals succeed but return no departures; the disruption lookups fail. TfL was
            // reached and genuinely showed nothing, so this is an honest empty/unknown state,
            // not a whole-screen network error (SPEC principle 1).
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String) = emptyList<Departure>()

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    throw TflException.Offline(null)
                }
            }
            val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
            assertTrue(state.disruptionUnknown)
            assertNull(state.refreshFailure)
        }

    @Test
    fun `stamps the snapshot from the start of the fetch, not after the request chain`() =
        runTest(dispatcher) {
            val start = now
            var current = start
            // Each request "takes" time, advancing the clock — as a slow TfL or many
            // stops would. The stamp must reflect the start, or the oldest departures read
            // as just-updated and the stale cutoff slips by the whole chain (SPEC D4).
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    current = current.plusSeconds(30)
                    return listOf(departure("victoria", "Victoria", 300))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    current = current.plusSeconds(30)
                    return emptyList()
                }
            }
            val vm = MainViewModel(client, seeds, clock = { current }, io = dispatcher)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(start, state.fetchedAt)
        }

    @Test
    fun `an empty seed yields an empty Loaded state, not a network error`() = runTest(dispatcher) {
        // No watched stops → nothing is fetched and nothing fails, so the screen shows an
        // empty list, not "Can't reach TfL" (TfL was never contacted).
        val vm = MainViewModel(FakeClient(emptyMap()), emptyList(), clock = { now }, io = dispatcher)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertTrue(state.stops.isEmpty())
        assertEquals(false, state.partialRefresh)
        assertNull(state.refreshFailure)
    }

    @Test
    fun `every stop failing on the first load maps the failure to an Error kind`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.RateLimited(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            DeparturesUiState.Error(DeparturesUiState.Error.Kind.RATE_LIMITED),
            vm.state.value,
        )
    }

    @Test
    fun `a stop that fails to refresh keeps its aged rows while a fresh stop updates`() =
        runTest(dispatcher) {
            var current = now
            var failKsx = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (stopId == "940GZZLUKSX" && failKsx) throw TflException.Offline(null)
                    return listOf(departure("victoria", "Victoria", 120))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val vm = MainViewModel(client, seeds, clock = { current }, io = dispatcher)
            advanceUntilIdle()
            val first = vm.state.value
            assertTrue(first is DeparturesUiState.Loaded)
            first as DeparturesUiState.Loaded
            // Both stops fetched at the first load's time.
            assertEquals(setOf(now), first.stops.map { it.fetchedAt }.toSet())

            // Time passes; on the next refresh KSX fails while Oxford Circus succeeds.
            current = now.plusSeconds(120)
            failKsx = true
            vm.refresh()
            advanceUntilIdle()

            val merged = vm.state.value
            assertTrue(merged is DeparturesUiState.Loaded)
            merged as DeparturesUiState.Loaded
            val ageByStop = merged.stops.associate { it.stopId to it.fetchedAt }
            // Oxford Circus refreshed to the new time; King's Cross kept its aged rows at
            // the old time rather than vanishing — the drop-on-partial-refresh class the
            // per-stop snapshot deletes (SPEC D4 / principle 2).
            assertEquals(now.plusSeconds(120), ageByStop.getValue("940GZZLUOXC"))
            assertEquals(now, ageByStop.getValue("940GZZLUKSX"))
            assertTrue(merged.partialRefresh)
            // The whole-screen stamp is the freshest stop's age.
            assertEquals(now.plusSeconds(120), merged.fetchedAt)
        }

    @Test
    fun `every arrivals request failing on first load errors even when stops declare lines`() =
        runTest(dispatcher) {
            // The production seed stops declare lines. If every arrivals request fails on a
            // first load, the stops must NOT be kept on their lines alone and shown as "no
            // departures" — the offline/rate-limit failure has to surface (SPEC principle 1).
            val seedsWithLines = listOf(
                StopRef("940GZZLUOXC", "Oxford Circus", listOf(LineRef("victoria", "Victoria", "tube"))),
                StopRef("940GZZLUKSX", "King's Cross St. Pancras", listOf(LineRef("circle", "Circle", "tube"))),
            )
            val client = FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
            )
            val vm = MainViewModel(client, seedsWithLines, clock = { now }, io = dispatcher)
            advanceUntilIdle()

            assertEquals(
                DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE),
                vm.state.value,
            )
        }

    @Test
    fun `a total failure preserves a prior partial-refresh warning`() = runTest(dispatcher) {
        var oxcFails = false
        var allFail = false
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (allFail || (oxcFails && stopId == "940GZZLUOXC")) throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (allFail) throw TflException.Offline(null)
                return emptyList()
            }
        }
        // A partial refresh first: Oxford Circus fails, King's Cross succeeds.
        oxcFails = true
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher)
        advanceUntilIdle()
        val partial = vm.state.value
        assertTrue(partial is DeparturesUiState.Loaded)
        partial as DeparturesUiState.Loaded
        assertTrue(partial.partialRefresh)

        // Then a total failure: nothing fresh. The kept snapshot is still incomplete, so the
        // partial warning must persist alongside the refresh-failure one, not be cleared.
        allFail = true
        vm.refresh()
        advanceUntilIdle()
        val kept = vm.state.value
        assertTrue(kept is DeparturesUiState.Loaded)
        kept as DeparturesUiState.Loaded
        assertTrue(kept.partialRefresh)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, kept.refreshFailure)
    }

    @Test
    fun `a stop whose arrivals fail still surfaces its fresh disruption`() = runTest(dispatcher) {
        // First load, no prior: King's Cross's arrivals fail but its disruption succeeds
        // with a closure. The decoupled fetch means the closure still surfaces rather than
        // the stop dropping out for want of predictions (the deferred PR #15 finding).
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "940GZZLUKSX") throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == "940GZZLUKSX") {
                    listOf(StopDisruption("Station closed until further notice"))
                } else {
                    emptyList()
                }
        }
        val vm = viewModel(client)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        val ksx = state.stops.single { it.stopId == "940GZZLUKSX" }
        // Arrivals failed with no prior, so it has no departures — but it stays in the
        // snapshot on its disruption alone, so the closed stop is flagged, not dropped.
        assertTrue(ksx.departures.isEmpty())
        assertEquals(
            listOf("Station closed until further notice"),
            ksx.disruptions.map { it.description },
        )
        // Its arrivals were never fetched, so it's flagged not-fresh — a status row can't
        // then claim "No departures" for this stop (only the closure shows).
        assertFalse(ksx.arrivalsFresh)
        // Oxford Circus refreshed and King's Cross's arrivals didn't — a partial refresh.
        assertTrue(state.partialRefresh)
    }

    @Test
    fun `a disrupted hub stop resolves its interchange name once and threads it`() = runTest(dispatcher) {
        // Two members of one interchange, both disrupted. The hub name is looked up once for the
        // shared hub (cached across the members and across refreshes) and threaded onto each
        // stop, so the folded near-me alert titles by the interchange (SPEC *Disruptions*).
        var hubNameCalls = 0
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                listOf(StopDisruption("No step free access"))

            override suspend fun hubInfo(hubId: String): HubInfo {
                hubNameCalls++
                return HubInfo("King's Cross & St Pancras International")
            }
        }
        val hubSeeds = listOf(
            StopRef("910GSTPX", "St Pancras International", hubId = "HUBKGX"),
            StopRef("940GZZLUKSX", "King's Cross St. Pancras", hubId = "HUBKGX"),
        )
        val vm = MainViewModel(client, hubSeeds, clock = { now }, io = dispatcher)
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertTrue(state.stops.all { it.hubId == "HUBKGX" })
        assertTrue(state.stops.all { it.hubName == "King's Cross & St Pancras International" })
        // Resolved once for the shared hub, not once per member.
        assertEquals(1, hubNameCalls)

        // A second refresh reuses the cached name — no further lookup.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, hubNameCalls)
    }

    @Test
    fun `a clear hub stop looks up no hub name`() = runTest(dispatcher) {
        // A hub stop with no disruption needs no title, so no lookup is made — the cost is paid
        // only when there is actually an alert to title.
        var hubNameCalls = 0
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 300))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            override suspend fun hubInfo(hubId: String): HubInfo {
                hubNameCalls++
                return HubInfo("X")
            }
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("910GSTPX", "St Pancras International", hubId = "HUBKGX")),
            clock = { now },
            io = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(0, hubNameCalls)
    }

    @Test
    fun `dismissAlert persists the alert and the dismissed flow reflects it`() = runTest(dispatcher) {
        // A disrupted stop; dismissing its closure row records the (place, notice) identity through
        // the store, and the collected `dismissed` flow re-emits it — the screen filters on that.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = listOf(StopDisruption("Bus Stop Closed"))
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        val closure = DepartureRows.across(state.stops, now).first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()

        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), vm.dismissed.value)
        assertEquals(DismissedAlert("490G000EXAMPLE", "Bus Stop Closed"), DismissedAlert.ofStopClosure(closure))
    }

    @Test
    fun `dismissing one of two concurrent notices at a place keeps the other dismissed`() = runTest(dispatcher) {
        // Two stops share a StopArea, each with a different closure notice — the fold keeps a card
        // for each, so both are shown at one place. Dismissing the second must not un-dismiss the
        // first: a dismiss only adds, so both signatures persist.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = when (stopId) {
                "490000001A" -> listOf(StopDisruption("Bus Stop Closed"))
                else -> listOf(StopDisruption("Lift out of service"))
            }
        }
        val vm = MainViewModel(
            client,
            listOf(
                StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("490000001B", "Example Road", clusterId = "490G000EXAMPLE"),
            ),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()

        val stops = (vm.state.value as DeparturesUiState.Loaded).stops
        val closures = DepartureRows.across(stops, now).filter { it.stopDisruption != null }
        val busStop = closures.first { it.stopDisruption == "Bus Stop Closed" }
        val lift = closures.first { it.stopDisruption == "Lift out of service" }

        vm.dismissAlert(busStop)
        advanceUntilIdle()
        vm.dismissAlert(lift)
        advanceUntilIdle()

        assertEquals(
            setOf(DismissedAlert.ofStopClosure(busStop), DismissedAlert.ofStopClosure(lift)),
            vm.dismissed.value,
        )
    }

    @Test
    fun `an authoritative refresh prunes a dismissal whose notice has resolved`() = runTest(dispatcher) {
        // The safety net (SPEC principle 2 — never hide a warning): once a dismissed closure resolves,
        // its stale (place, text) dismissal must not linger to suppress a later same-text closure. A
        // full, fresh refresh reconciles it out.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        var closed = true
        val client = object : TflClient {
            // Fresh arrivals make the refresh authoritative; the disruption resolves on the second pass.
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)

        closed = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a line status dismissal persists and is pruned once the line recovers, kept while unchecked`() = runTest(dispatcher) {
        // Line alerts share the dismissed set with closures (keyed per line). Dismissing records the
        // line's (severity, label, prose); a refresh whose status lookup fails keeps it; once TfL
        // reports a good service the stale dismissal is pruned so a later recurrence shows again.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        val severe = LineStatus("victoria", 6, "Severe Delays", "Victoria line: severe delays.")
        var status = severe
        var statusFails = false
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                if (statusFails) throw TflException.Unreachable("boom", null)
                return listOf(status)
            }
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("940GZZLUVIC", "Victoria")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val loaded = vm.state.value as DeparturesUiState.Loaded
        val row = DepartureRows.across(loaded.stops, now, loaded.lineStatuses).first { it.status != null }
        vm.dismissAlert(row)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofLineStatus(severe)), backing.value)
        assertEquals(setOf(DismissedAlert.ofLineStatus(severe)), vm.dismissed.value)

        statusFails = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofLineStatus(severe)), backing.value)

        statusFails = false
        status = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a refresh whose disruption lookup failed keeps that place's dismissal`() = runTest(dispatcher) {
        // The disruption endpoint fails on the second pass: the closure drops from the feed (a
        // point-in-time notice isn't aged), but the place is flagged unknown, so its dismissal is
        // retained rather than pruned — persist-until-change survives a transient lookup failure.
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        var disruptionFails = false
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (disruptionFails) throw TflException.Unreachable("boom", null)
                return listOf(StopDisruption("Bus Stop Closed"))
            }
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)

        disruptionFails = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)
    }

    @Test
    fun `a reconcile write failure still prunes the in-memory dismissed set`() = runTest(dispatcher) {
        // The persist fails, but the shown set the screen filters on must still be reconciled, so a
        // resolved notice's stale signature can't suppress a recurrence this session (principle 2).
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                throw java.io.IOException("disk full")
            }
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), vm.dismissed.value)

        closed = false
        vm.refresh()
        advanceUntilIdle()
        // The persist threw, so the store still holds the stale signature...
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)
        // ...but the in-memory set the screen uses is reconciled, so the resolved notice can't suppress.
        assertEquals(emptySet<DismissedAlert>(), vm.dismissed.value)
    }

    // Whether the list shows every stop with no notice: a refresh that found its closures ended is in.
    private fun shownClear(vm: MainViewModel) =
        (vm.state.value as? DeparturesUiState.Loaded)?.stops?.all { it.disruptions.isEmpty() } == true

    @Test
    fun `a refresh's prune is stored even when the list is left while the worker has it`() = runTest(dispatcher) {
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        // Runs what's handed to it at once, or, once holding, keeps it until let go, as a busy worker would.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = worker,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)

        closed = false
        holding = true
        vm.refresh()
        advanceUntilIdle()
        // The worker merges the refresh's stops first; once the list shows them, what it holds next
        // is the refresh's checks, to settle.
        while (held.isNotEmpty() && !shownClear(vm)) {
            val next = held.toList()
            held.clear()
            for ((context, block) in next) dispatcher.dispatch(context, block)
            advanceUntilIdle()
        }
        assertTrue("nothing handed to the worker", held.isNotEmpty())
        // The app is left while the worker still has the refresh's checks: they're settled and stored
        // all the same, so the same notice coming back later isn't hidden.
        vm.viewModelScope.cancel()
        holding = false
        while (held.isNotEmpty()) {
            val next = held.toList()
            held.clear()
            for ((context, block) in next) dispatcher.dispatch(context, block)
            advanceUntilIdle()
        }
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a refresh superseded while the worker has it doesn't settle dismissals`() = runTest(dispatcher) {
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = worker,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()

        // One refresh finds the notice ended; while the worker has its checks, a newer one finds it
        // back. (The worker merges the refresh's stops first, and the list shows them.)
        holding = true
        closed = false
        vm.refresh(automatic = false)
        advanceUntilIdle()
        while (held.isNotEmpty() && !shownClear(vm)) {
            val next = held.toList()
            held.clear()
            for ((context, block) in next) dispatcher.dispatch(context, block)
            advanceUntilIdle()
        }
        assertTrue("nothing handed to the worker", held.isNotEmpty())
        val older = held.toList()
        held.clear()
        closed = true
        vm.forceNextFetch()
        vm.refresh()
        advanceUntilIdle()
        holding = false
        // The newer refresh's checks come in and settle while the worker still has the older one's.
        while (held.isNotEmpty()) {
            val next = held.toList()
            held.clear()
            for ((context, block) in next) dispatcher.dispatch(context, block)
            advanceUntilIdle()
        }
        for ((context, block) in older) dispatcher.dispatch(context, block)
        advanceUntilIdle()
        // The newer refresh has the last word: the notice is live, so its dismissal stays.
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)
    }

    @Test
    fun `a refresh still settles when a newer one is canceled before its checks come in`() = runTest(dispatcher) {
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            compute = worker,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()

        // A refresh finds the notice ended; while the worker has it, a newer refresh starts and is
        // canceled (a re-locate begins) before its own checks come in.
        closed = false
        holding = true
        vm.refresh()
        advanceUntilIdle()
        // The worker merges the refresh's stops first; once the list shows them, what it holds next
        // is the refresh's checks, to settle.
        while (held.isNotEmpty() && !shownClear(vm)) {
            val next = held.toList()
            held.clear()
            for ((context, block) in next) dispatcher.dispatch(context, block)
            advanceUntilIdle()
        }
        assertTrue("nothing handed to the worker", held.isNotEmpty())
        vm.refresh()
        vm.cancelFetch()
        holding = false
        while (held.isNotEmpty()) {
            val next = held.toList()
            held.clear()
            for ((context, block) in next) dispatcher.dispatch(context, block)
            advanceUntilIdle()
        }
        // Nothing newer settled, so the first refresh's verdict stands: the ended notice is forgotten.
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a refresh's prune is stored even when the list is left while it's written`() = runTest(dispatcher) {
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var gate: CompletableDeferred<Unit>? = null
        val writing = CompletableDeferred<Unit>()
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                gate?.let {
                    writing.complete(Unit)
                    it.await()
                }
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        var closed = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) =
                if (closed) listOf(StopDisruption("Bus Stop Closed")) else emptyList()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), backing.value)

        closed = false
        val written = CompletableDeferred<Unit>().also { gate = it }
        vm.refresh()
        advanceUntilIdle()
        assertTrue(writing.isCompleted)
        // The app is left while the prune is being written: it still lands, so the same notice coming
        // back later isn't hidden by a dismissal read back from the store (Codex, PR #379).
        vm.viewModelScope.cancel()
        written.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a transient dismissed-read error recovers so later dismissals still apply`() = runTest(dispatcher) {
        // The first read of the dismissed set throws; the collector must restart rather than die, so
        // a dismiss made after it recovers still hides the card (not silently lost).
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        var reads = 0
        val store = object : DismissedAlertsStore {
            override fun dismissed(): Flow<Set<DismissedAlert>> = flow {
                reads++
                if (reads == 1) throw java.io.IOException("read boom")
                emitAll(backing)
            }
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = listOf(StopDisruption("Bus Stop Closed"))
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        val closure = DepartureRows.across((vm.state.value as DeparturesUiState.Loaded).stops, now)
            .first { it.stopDisruption != null }
        vm.dismissAlert(closure)
        advanceUntilIdle()
        // The collector recovered from the first-read failure, so it observes the dismiss.
        assertEquals(setOf(DismissedAlert.ofStopClosure(closure)), vm.dismissed.value)
    }

    @Test
    fun `a failed dismiss sets the write-failed flag until it is acknowledged`() = runTest(dispatcher) {
        // A DataStore write failure leaves the card visible and the store won't re-emit, so the
        // dismiss tap silently no-ops — the ViewModel raises an acknowledged flag the screen turns
        // into a transient message (SPEC principle 2), same seam as a failed star write.
        val failingStore = object : DismissedAlertsStore {
            override fun dismissed() = MutableStateFlow<Set<DismissedAlert>>(emptySet())
            override suspend fun dismiss(alert: DismissedAlert) {
                throw java.io.IOException("disk full")
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = listOf(StopDisruption("Bus Stop Closed"))
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = failingStore,
        )
        advanceUntilIdle()
        val closure = (vm.state.value as DeparturesUiState.Loaded)
            .let { DepartureRows.across(it.stops, now) }
            .first { it.stopDisruption != null }

        assertFalse(vm.dismissWriteFailed.value)
        vm.dismissAlert(closure)
        advanceUntilIdle()
        assertTrue("a failed write raises the flag", vm.dismissWriteFailed.value)
        vm.dismissWriteFailureShown()
        assertFalse("acknowledging clears it", vm.dismissWriteFailed.value)
    }

    @Test
    fun `a failed hub name lookup leaves the alert titling by the stop, and is retried`() = runTest(dispatcher) {
        // A blank/failed lookup is not cached, so a transient failure never permanently blanks
        // the title: the alert falls back to the stop's own name this cycle and retries next.
        var fail = true
        var hubNameCalls = 0
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = listOf(StopDisruption("Closed"))
            override suspend fun hubInfo(hubId: String): HubInfo {
                hubNameCalls++
                if (fail) throw TflException.Unreachable("boom", null)
                return HubInfo("King's Cross & St Pancras International")
            }
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("940GZZLUKSX", "King's Cross St. Pancras", hubId = "HUBKGX")),
            clock = { now },
            io = dispatcher,
        )
        advanceUntilIdle()
        // Lookup failed → blank hub name; the stop still surfaces its disruption.
        val first = vm.state.value as DeparturesUiState.Loaded
        assertEquals("", first.stops.single().hubName)
        assertEquals(1, hubNameCalls)

        // Not cached, so the next refresh retries — and now succeeds.
        fail = false
        vm.refresh()
        advanceUntilIdle()
        val second = vm.state.value as DeparturesUiState.Loaded
        assertEquals("King's Cross & St Pancras International", second.stops.single().hubName)
        assertEquals(2, hubNameCalls)
    }

    @Test
    fun `fetches every stop's departures in parallel, then its closure check, merged in stop order`() = runTest(dispatcher) {
        // Each request parks on its gate, so what has started once the scheduler settles is exactly
        // what was issued before any answer came back. A one-at-a-time loop would show one request.
        val departuresGate = CompletableDeferred<Unit>()
        val closuresGate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                started += "arrivals:$stopId"
                departuresGate.await()
                return listOf(departure("victoria", "Victoria", if (stopId == seeds[0].id) 300 else 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                started += "disruptions:$stopId"
                closuresGate.await()
                return emptyList()
            }
        }
        val vm = viewModel(client)
        runCurrent()

        // Every stop's departures at once, and no closure check before its stop's departures are back.
        assertEquals(setOf("arrivals:${seeds[0].id}", "arrivals:${seeds[1].id}"), started.toSet())
        assertEquals(2, started.size)

        departuresGate.complete(Unit)
        runCurrent()
        // Then each stop's closure check, both at once.
        assertEquals(setOf("disruptions:${seeds[0].id}", "disruptions:${seeds[1].id}"), started.drop(2).toSet())
        assertEquals(4, started.size)
        closuresGate.complete(Unit)
        advanceUntilIdle()
        val state = vm.state.value as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), state.stops.map { it.stopId }.toSet())
    }

    @Test
    fun `a failing hub lookup is requested once per refresh, not once per member`() = runTest(dispatcher) {
        // Several disrupted stops share one hub whose name lookup fails. Without per-refresh
        // deduplication the fan-out would re-request (and re-time-out) once per member; with it there
        // is one failing call this refresh and every member falls back to its own name, agreeing.
        var hubNameCalls = 0
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = listOf(StopDisruption("Closed"))
            override suspend fun hubInfo(hubId: String): HubInfo {
                hubNameCalls++
                throw TflException.Unreachable("boom", null)
            }
        }
        val hubSeeds = listOf(
            StopRef("910GSTPX", "St Pancras International", hubId = "HUBKGX"),
            StopRef("940GZZLUKSX", "King's Cross St. Pancras", hubId = "HUBKGX"),
        )
        val vm = MainViewModel(client, hubSeeds, clock = { now }, io = dispatcher)
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertTrue(state.stops.all { it.hubName == "" })
        assertEquals(1, hubNameCalls)
    }

    @Test
    fun `a successful snapshot is persisted for the next launch and the widget`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val vm = viewModel(
                FakeClient(
                    mapOf(
                        "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                        "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                    ),
                ),
                store = store,
            )
            advanceUntilIdle()

            val saved = store.saves.last()
            assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), saved.stops.map { it.stopId })
            assertEquals(now, saved.fetchedAt)
        }

    @Test
    fun `an error result does not clobber a previously saved snapshot`() = runTest(dispatcher) {
        // Nothing is stored, and every stop fails on this first load → an Error state with no
        // content. Persisting that would erase whatever the widget last showed, so it must not.
        val store = FakeStore()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
                disruptionsByStop = mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
            ),
            store = store,
        )
        advanceUntilIdle()

        assertTrue(vm.state.value is DeparturesUiState.Error)
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `a refresh whose arrivals all fail still stores the lines it checked for the widget`() =
        runTest(dispatcher) {
            // Every arrivals request fails, but the status one answers: the Victoria line is
            // suspended. There are no arrivals worth saving, but the widget still learns of it now.
            val aged = now.minusSeconds(120)
            val store = FakeStore(
                DeparturesSnapshot(
                    stops = listOf(
                        stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged),
                        stopArrivals("940GZZLUKSX", "King's Cross St. Pancras", 300, aged),
                    ),
                    fetchedAt = aged,
                ),
            )
            val vm = viewModel(
                FakeClient(
                    mapOf(
                        "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                        "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                    ),
                    statuses = Result.success(listOf(status("victoria", 20, "Suspended"))),
                ),
                store = store,
            )
            advanceUntilIdle()

            assertTrue(vm.state.value is DeparturesUiState.Loaded)
            assertTrue("no arrivals were saved", store.saves.isEmpty())
            val check = store.lineStatusUpdates.last().getValue("victoria")
            assertEquals(20, check.status.severity)
            assertEquals(now, check.checkedAt)
        }

    @Test
    fun `a refresh whose arrivals all fail works the line choices out again after storing the statuses`() = runTest(dispatcher) {
        val aged = now.minusSeconds(120)
        val events = mutableListOf<String>()
        val last = DeparturesSnapshot(
            stops = listOf(stopArrivals(oxcId, "Oxford Circus", 300, aged), stopArrivals(ksxId, "King's Cross St. Pancras", 300, aged)),
            fetchedAt = aged,
        )
        val store = object : SnapshotStore by SnapshotStore.NONE {
            override suspend fun load(): DeparturesSnapshot = last
            override suspend fun stored(): DeparturesSnapshot = last
            override suspend fun updateLineStatuses(checks: Map<String, LineStatusCheck>) {
                events += "statuses"
            }
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                if (choicesFor != null) events += "choices"
            }
        }
        MainViewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
                statuses = Result.success(listOf(status("victoria", 20, "Suspended"))),
            ),
            seeds, clock = { aged.plusSeconds(120) }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        // The statuses can change which stop shows a line, so the choices follow them.
        assertTrue("the statuses were stored", "statuses" in events)
        assertEquals("choices", events.last())
    }

    @Test
    fun `a refresh whose arrivals all fail stores an answer that left every line out`() = runTest(dispatcher) {
        // TfL answers the status request but names no line: that's a check too, with no verdict,
        // and it replaces an older disruption the widget holds rather than leaving it standing.
        val aged = now.minusSeconds(120)
        val store = FakeStore(
            DeparturesSnapshot(stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged)), fetchedAt = aged),
        )
        viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
                statuses = Result.success(emptyList()),
            ),
            store = store,
        )
        advanceUntilIdle()
        assertTrue(store.saves.isEmpty())
        val check = store.lineStatusUpdates.last().getValue("victoria")
        assertFalse(check.known)
        assertEquals(now, check.checkedAt)
    }

    @Test
    fun `a cold start whose arrivals all fail checks the lines the widget shows`() = runTest(dispatcher) {
        // Nothing restored in-app and no arrivals, so the refresh has no stops of its own; the widget
        // still shows the Central line, and its suspension reaches it.
        val aged = now.minusSeconds(120)
        val widget = DeparturesSnapshot(
            stops = listOf(StopArrivals("940GZZLUBNK", "Bank", listOf(departure("central", "Central", 300)), aged)),
            fetchedAt = aged,
        )
        val store = FakeStore(widget, restores = false)
        val client = FakeClient(
            mapOf(
                "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
            ),
            statuses = Result.success(listOf(status("central", 20, "Suspended"))),
        )
        viewModel(client, store = store)
        advanceUntilIdle()
        assertEquals(setOf("central"), client.requestedLineIds?.toSet())
        assertTrue(store.saves.isEmpty())
        assertEquals(20, store.lineStatusUpdates.last().getValue("central").status.severity)
    }

    @Test
    fun `a cold start's check of the widget's lines settles their dismissed alerts`() = runTest(dispatcher) {
        // A Central line alert dismissed earlier has since ended: the check for the widget, made with
        // every arrivals request failing, forgets the dismissal, so the alert recurring is shown again.
        val ended = DismissedAlert.ofLineStatus(LineStatus("central", 6, "Severe Delays", "Signal failure."))
        val backing = MutableStateFlow(setOf(ended))
        val dismissals = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        val aged = now.minusSeconds(120)
        val widget = DeparturesSnapshot(
            stops = listOf(StopArrivals("940GZZLUBNK", "Bank", listOf(departure("central", "Central", 300)), aged)),
            fetchedAt = aged,
        )
        MainViewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
                statuses = Result.success(listOf(status("central", LineStatus.GOOD_SERVICE, "Good Service"))),
            ),
            seeds,
            clock = { now },
            io = dispatcher,
            snapshotStore = FakeStore(widget, restores = false),
            dismissedStore = dismissals,
        )
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a refresh whose arrivals and statuses all fail stores nothing`() = runTest(dispatcher) {
        val aged = now.minusSeconds(120)
        val store = FakeStore(
            DeparturesSnapshot(stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged)), fetchedAt = aged),
        )
        viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
                statuses = Result.failure(TflException.Offline(null)),
            ),
            store = store,
        )
        advanceUntilIdle()
        assertTrue(store.saves.isEmpty())
        assertTrue(store.lineStatusUpdates.isEmpty())
    }

    @Test
    fun `a status write in an arrivals outage leaves the widget's redraw to the store`() = runTest(dispatcher) {
        // The store's write re-renders the widget itself, as a save does, so redrawing here too would
        // render it twice every outage cycle. With nothing written, the refresh redraws it instead.
        val aged = now.minusSeconds(120)
        suspend fun writesAndRedraws(statuses: Result<List<LineStatus>>): Pair<Int, Int> {
            val store = FakeStore(
                DeparturesSnapshot(stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged)), fetchedAt = aged),
            )
            var redraws = 0
            MainViewModel(
                FakeClient(
                    mapOf(
                        "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                        "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                    ),
                    statuses = statuses,
                ),
                seeds,
                clock = { now },
                io = dispatcher,
                snapshotStore = store,
                redrawWidget = { redraws++ },
            )
            advanceUntilIdle()
            return store.lineStatusUpdates.size to redraws
        }
        assertEquals("TfL answered: written, and redrawn by the store", 1 to 0, writesAndRedraws(Result.success(listOf(status("victoria", 20, "Suspended")))))
        assertEquals("it didn't: nothing written, redrawn here", 0 to 1, writesAndRedraws(Result.failure(TflException.Offline(null))))
    }

    @Test
    fun `the persisted last-good is restored as the prior a failed refresh falls back to`() =
        runTest(dispatcher) {
            // King's Cross has an aged last-good on disk; Oxford Circus does not. On this
            // launch Oxford Circus refreshes but King's Cross fails outright (arrivals and
            // disruption). Because the restored snapshot is the prior the refresh merges into,
            // King's Cross keeps its aged rows instead of dropping out.
            val aged = now.minusSeconds(120)
            val store = FakeStore(
                DeparturesSnapshot(
                    stops = listOf(stopArrivals("940GZZLUKSX", "King's Cross St. Pancras", 300, aged)),
                    fetchedAt = aged,
                ),
            )
            val vm = viewModel(
                FakeClient(
                    mapOf(
                        "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                        "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                    ),
                    disruptionsByStop = mapOf(
                        "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                    ),
                ),
                store = store,
            )
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(setOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId }.toSet())
            val ksx = state.stops.single { it.stopId == "940GZZLUKSX" }
            // Kept from the restored snapshot at its aged time, not refreshed away.
            assertEquals(aged, ksx.fetchedAt)
            assertTrue(ksx.departures.isNotEmpty())
            assertTrue(state.partialRefresh)
        }

    @Test
    fun `the restored snapshot is marked disruption-unknown until the refresh checks status`() =
        runTest(dispatcher) {
            val aged = now.minusSeconds(120)
            val store = FakeStore(
                DeparturesSnapshot(
                    stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged)),
                    fetchedAt = aged,
                ),
            )
            // A refresh that never completes, so the state settles at the restored snapshot:
            // the restore has not checked line status, so those departures must not read as
            // verified-clean while the check is pending.
            val hangingClient = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> =
                    kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val vm = viewModel(hangingClient, store = store)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(listOf("940GZZLUOXC"), state.stops.map { it.stopId })
            assertTrue(state.disruptionUnknown)
            // The saved snapshot is missing King's Cross (a seed stop), so the restore is
            // flagged partial rather than shown as a complete list.
            assertTrue(state.partialRefresh)
        }

    @Test
    fun `a complete restored snapshot is not flagged partial`() = runTest(dispatcher) {
        val aged = now.minusSeconds(120)
        val store = FakeStore(
            DeparturesSnapshot(
                stops = seeds.map { stopArrivals(it.id, it.name, 300, aged) },
                fetchedAt = aged,
            ),
        )
        val hangingClient = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> =
                kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(hangingClient, store = store)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), state.stops.map { it.stopId }.toSet())
        assertFalse(state.partialRefresh)
    }

    @Test
    fun `a restored snapshot with a carried-stale stop is flagged partial`() = runTest(dispatcher) {
        // All seed stops are present, but one was carried-forward-stale when saved
        // (arrivalsFresh = false), so the snapshot is mixed-age — flag it partial rather than
        // let the freshest-stop stamp pass it off as uniformly fresh.
        val aged = now.minusSeconds(120)
        val store = FakeStore(
            DeparturesSnapshot(
                stops = listOf(
                    stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged),
                    stopArrivals("940GZZLUKSX", "King's Cross St. Pancras", 300, aged)
                        .copy(arrivalsFresh = false),
                ),
                fetchedAt = aged,
            ),
        )
        val hangingClient = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> =
                kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(hangingClient, store = store)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertTrue(state.partialRefresh)
    }

    @Test
    fun `a refresh recovers the last-good from the store when not in a Loaded state`() =
        runTest(dispatcher) {
            // Everything fails (offline). First load with an empty store → Error. Then a
            // snapshot exists on disk (a prior good session), and a later refresh — still
            // offline, still not in a Loaded state — recovers it as the aged last-good rather
            // than staying stuck on the error screen with valid data on disk.
            val store = FakeStore()
            val offline = mapOf(
                "940GZZLUOXC" to Result.failure<List<Departure>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<Departure>>(TflException.Offline(null)),
            )
            val offlineDisruptions = mapOf(
                "940GZZLUOXC" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
            )
            val vm = viewModel(FakeClient(offline, disruptionsByStop = offlineDisruptions), store = store)
            advanceUntilIdle()
            assertTrue(vm.state.value is DeparturesUiState.Error)

            store.stored = DeparturesSnapshot(
                stops = seeds.map { stopArrivals(it.id, it.name, 300, now.minusSeconds(120)) },
                fetchedAt = now.minusSeconds(120),
            )
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(seeds.map { it.id }.toSet(), state.stops.map { it.stopId }.toSet())
            assertEquals(DeparturesUiState.Error.Kind.OFFLINE, state.refreshFailure)
        }

    @Test
    fun `a total-failure refresh from the store keeps the snapshot's incompleteness`() =
        runTest(dispatcher) {
            // First load errors with an empty store. Then an INCOMPLETE snapshot is on disk
            // (King's Cross, a seed stop, is missing — an earlier partial refresh saved only
            // Oxford Circus). A later refresh, still offline, recovers it from the store as
            // the prior — and must carry its incompleteness, so the kept list stays flagged
            // partial rather than passed off as complete (SPEC principle 2). Before the fix
            // the fallback kept only the stops and derived partial from the (Error) previous
            // state, so the warning was dropped.
            val store = FakeStore()
            val offline = mapOf(
                "940GZZLUOXC" to Result.failure<List<Departure>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<Departure>>(TflException.Offline(null)),
            )
            val offlineDisruptions = mapOf(
                "940GZZLUOXC" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
            )
            val vm = viewModel(FakeClient(offline, disruptionsByStop = offlineDisruptions), store = store)
            advanceUntilIdle()
            assertTrue(vm.state.value is DeparturesUiState.Error)

            store.stored = DeparturesSnapshot(
                stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, now.minusSeconds(120))),
                fetchedAt = now.minusSeconds(120),
            )
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(listOf("940GZZLUOXC"), state.stops.map { it.stopId })
            assertTrue(state.partialRefresh)
            assertEquals(DeparturesUiState.Error.Kind.OFFLINE, state.refreshFailure)
        }

    @Test
    fun `a refresh from a non-Loaded state shows the aged store snapshot before the network returns`() =
        runTest(dispatcher) {
            // First load errors (offline, empty store) → Error. Then a snapshot is on disk and
            // a refresh runs while the network HANGS. The aged last-good must be shown at once
            // rather than the spinner held through the hung fetch — valid data on disk must not
            // be hidden behind a stuck spinner (SPEC principle 5).
            val store = FakeStore()
            var hang = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (hang) return kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()
                    throw TflException.Offline(null)
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    if (hang) return kotlinx.coroutines.CompletableDeferred<List<StopDisruption>>().await()
                    throw TflException.Offline(null)
                }
            }
            val vm = viewModel(client, store = store)
            advanceUntilIdle()
            assertTrue(vm.state.value is DeparturesUiState.Error)

            val aged = now.minusSeconds(120)
            store.stored = DeparturesSnapshot(
                stops = seeds.map { stopArrivals(it.id, it.name, 300, aged) },
                fetchedAt = aged,
            )
            hang = true
            vm.refresh()
            advanceUntilIdle()

            // The fetch is suspended (hung) mid-flight, so the state settles at the aged
            // last-good published from the store fallback — not stuck on Loading.
            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(seeds.map { it.id }.toSet(), state.stops.map { it.stopId }.toSet())
            assertEquals(aged, state.fetchedAt)
            assertTrue(vm.refreshing.value)
        }

    @Test
    fun `a total-failure refresh does not overwrite a complete saved snapshot`() =
        runTest(dispatcher) {
            // A complete good snapshot is saved on the first load. Then a total failure carries
            // the aged rows (arrivalsFresh = false) — but that is not authoritative, so it must
            // NOT be persisted over the complete one, or the next launch would restore it as a
            // partial snapshot despite the good data still being on disk.
            var failing = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (failing) throw TflException.Offline(null)
                    return listOf(departure("victoria", "Victoria", 300))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    if (failing) throw TflException.Offline(null)
                    return emptyList()
                }
            }
            val store = FakeStore()
            val vm = viewModel(client, store = store)
            advanceUntilIdle()
            val goodSave = store.saves.last()
            assertEquals(seeds.map { it.id }.toSet(), goodSave.stops.map { it.stopId }.toSet())
            assertTrue(goodSave.stops.all { it.arrivalsFresh })
            val savesBefore = store.saves.size

            failing = true
            vm.refresh()
            advanceUntilIdle()

            // No new save from the failed cycle; the complete snapshot on disk is untouched.
            assertEquals(savesBefore, store.saves.size)
            assertTrue(store.stored!!.stops.all { it.arrivalsFresh })
        }

    @Test
    fun `a cycle with only fresh disruptions and no fresh arrivals does not overwrite the snapshot`() =
        runTest(dispatcher) {
            // A complete good snapshot is saved on the first load. Then arrivals fail for every
            // stop but the disruption calls succeed (empty). anyFreshData would call that
            // authoritative, but there is no fresh ARRIVALS content and disruptions aren't
            // persisted — so saving would only rewrite the snapshot to arrivalsFresh = false
            // and make the next launch restore it as partial. It must be skipped.
            var arrivalsFail = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (arrivalsFail) throw TflException.Offline(null)
                    return listOf(departure("victoria", "Victoria", 300))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val store = FakeStore()
            val vm = viewModel(client, store = store)
            advanceUntilIdle()
            val goodSave = store.saves.last()
            assertTrue(goodSave.stops.all { it.arrivalsFresh })
            val savesBefore = store.saves.size

            // Arrivals fail, disruptions still succeed (empty).
            arrivalsFail = true
            vm.refresh()
            advanceUntilIdle()

            // No new save; the complete snapshot on disk is untouched.
            assertEquals(savesBefore, store.saves.size)
            assertTrue(store.stored!!.stops.all { it.arrivalsFresh })
        }

    @Test
    fun `an empty watched list persists an authoritative empty snapshot`() = runTest(dispatcher) {
        // No stops to fetch → an authoritative "no departures", which must overwrite an
        // obsolete saved snapshot rather than leaving removed stops on disk for the next
        // launch (and the widget) to restore.
        val store = FakeStore(
            DeparturesSnapshot(
                stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, now.minusSeconds(600))),
                fetchedAt = now.minusSeconds(600),
            ),
        )
        val emptyClient = FakeClient(emptyMap())
        val vm = MainViewModel(
            emptyClient,
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            snapshotStore = store,
        )
        advanceUntilIdle()

        assertTrue(vm.state.value is DeparturesUiState.Loaded)
        val saved = store.saves.last()
        assertTrue(saved.stops.isEmpty())
    }

    @Test
    fun `a star tapped just before the model is cleared still lands and reaches the widget`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : app.stopdash.domain.StarredRowsStore {
            val toggled = mutableListOf<app.stopdash.domain.StarredRow>()
            override fun starred() = kotlinx.coroutines.flow.flowOf(app.stopdash.domain.StarredRowSet.Loaded(emptySet()))
            override suspend fun toggle(row: app.stopdash.domain.StarredRow) {
                gate.await()
                toggled += row
            }
        }
        var redraws = 0
        val vm = MainViewModel(
            ReuseCountingClient(), listOf(seeds.first()), clock = { now }, io = dispatcher, starredStore = store,
            redrawWidget = { redraws++ },
        )
        advanceUntilIdle()
        val before = redraws
        vm.toggleStar(row("940GZZLUOXC", "victoria", "inbound"))
        runCurrent()
        // A searched station's page closing clears its model mid-write.
        vm.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, store.toggled.size)
        assertEquals("the widget still redraws with the new star", before + 1, redraws)
    }

    @Test
    fun `a saved star tells the app its row, so the place can be recorded, even as the page closes`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : app.stopdash.domain.StarredRowsStore {
            override fun starred() = kotlinx.coroutines.flow.flowOf(app.stopdash.domain.StarredRowSet.Loaded(emptySet()))
            override suspend fun toggle(row: app.stopdash.domain.StarredRow) = gate.await()
        }
        val told = mutableListOf<String>()
        val vm = MainViewModel(
            ReuseCountingClient(), listOf(seeds.first()), clock = { now }, io = dispatcher, starredStore = store,
            onStarToggled = { told += it.stopId },
        )
        advanceUntilIdle()
        vm.toggleStar(row("940GZZLUOXC", "victoria", "inbound"))
        runCurrent()
        vm.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("940GZZLUOXC"), told)
    }

    @Test
    fun `a star write that fails after its page closed surfaces on the shared list`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val failing = object : app.stopdash.domain.StarredRowsStore {
            override fun starred() = kotlinx.coroutines.flow.flowOf(app.stopdash.domain.StarredRowSet.Loaded(emptySet()))
            override suspend fun toggle(row: app.stopdash.domain.StarredRow) {
                gate.await()
                throw java.io.IOException("disk full")
            }
        }
        val shared = WriteFailures()
        val nearMe = MainViewModel(ReuseCountingClient(), listOf(seeds.first()), clock = { now }, io = dispatcher, writeFailures = shared)
        val station = MainViewModel(
            ReuseCountingClient(), listOf(seeds.first()), clock = { now }, io = dispatcher,
            starredStore = failing, ownsWidgetJourneys = false, writeFailures = shared,
        )
        advanceUntilIdle()
        station.toggleStar(row("940GZZLUOXC", "victoria", "inbound"))
        runCurrent()
        station.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(nearMe.starWriteFailed.value)
    }

    private class FakeStarredStore(
        initial: Set<app.stopdash.domain.StarredRow> = emptySet(),
    ) : app.stopdash.domain.StarredRowsStore {
        private val state =
            kotlinx.coroutines.flow.MutableStateFlow<app.stopdash.domain.StarredRowSet>(
                app.stopdash.domain.StarredRowSet.Loaded(initial),
            )
        override fun starred() = state
        override suspend fun toggle(row: app.stopdash.domain.StarredRow) {
            val current = (state.value as app.stopdash.domain.StarredRowSet.Loaded).starred
            state.value = app.stopdash.domain.StarredRowSet.Loaded(
                app.stopdash.domain.Starred.toggle(current, row),
            )
        }
    }

    private fun row(stopId: String, lineId: String, directionKey: String) = DepartureRow(
        stopId = stopId,
        stopName = "Stop $stopId",
        lineId = lineId,
        lineName = lineId,
        direction = directionKey,
        directionKey = directionKey,
        destination = "Somewhere",
        mode = "tube",
        upcoming = emptyList(),
        fetchedAt = now,
    )

    @Test
    fun `toggleStar stars then unstars a row, reflected in the starred flow`() = runTest(dispatcher) {
        val starredStore = FakeStarredStore()
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = starredStore,
        )
        advanceUntilIdle()
        assertTrue(vm.starred.value.isEmpty())

        val victoria = row("940GZZLUKSX", "victoria", "southbound")
        vm.toggleStar(victoria)
        advanceUntilIdle()
        assertEquals(setOf(app.stopdash.domain.StarredRow.of(victoria)), vm.starred.value)

        vm.toggleStar(victoria)
        advanceUntilIdle()
        assertTrue(vm.starred.value.isEmpty())
    }

    @Test
    fun `toggleStar re-renders the widget via redrawWidget`() = runTest(dispatcher) {
        // The widget pins starred rows from the persisted state, so a star change must poke it
        // to re-render at once rather than waiting for the next fetch (SPEC D8).
        var pokes = 0
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = FakeStarredStore(),
            redrawWidget = { pokes++ },
        )
        advanceUntilIdle()
        vm.toggleStar(row("940GZZLUKSX", "victoria", "southbound"))
        advanceUntilIdle()
        assertEquals("a star change pokes the widget to re-render", 1, pokes)
    }

    @Test
    fun `a widget redraw failure after a star change does not report a star-write failure`() =
        runTest(dispatcher) {
            // The pin persisted and the in-app list re-ordered off the starred flow; the widget is
            // a secondary surface. A redraw failure is logged only — it must not raise the
            // write-failed flag ("couldn't save your pin"), which is reserved for a store failure.
            val store = FakeStarredStore()
            val victoria = app.stopdash.domain.StarredRow("940GZZLUKSX", "victoria", "southbound")
            val vm = MainViewModel(
                FakeClient(emptyMap()),
                seedStops = emptyList(),
                clock = { now },
                io = dispatcher,
                starredStore = store,
                redrawWidget = { throw java.io.IOException("widget host unavailable") },
            )
            advanceUntilIdle()
            vm.toggleStar(row("940GZZLUKSX", "victoria", "southbound"))
            advanceUntilIdle()
            assertTrue("the star still persisted", vm.starred.value.contains(victoria))
            assertFalse("a redraw failure is not a star-write failure", vm.starWriteFailed.value)
        }

    @Test
    fun `a refresh that saves nothing still pokes a widget redraw`() = runTest(dispatcher) {
        // A failed refresh keeps the aged last-good and does NOT save, so nothing pokes the widget
        // via the snapshot store. The widget's RemoteViews are static, so without a redraw its age
        // and countdowns freeze at the last save (SPEC D4). The ViewModel must poke a best-effort
        // redraw on the no-save path so the widget recomputes and withholds stale times.
        var pokes = 0
        val vm = MainViewModel(
            FakeClient(mapOf("940GZZLUKSX" to Result.failure(TflException.Offline(null)))),
            seedStops = listOf(StopRef("940GZZLUKSX", "King's Cross St. Pancras")),
            clock = { now },
            io = dispatcher,
            redrawWidget = { pokes++ },
        )
        advanceUntilIdle()
        assertTrue("a completed refresh with nothing to save redraws the widget", pokes >= 1)
    }

    @Test
    fun `an already-starred set is exposed on the starred flow at once`() = runTest(dispatcher) {
        val victoria = app.stopdash.domain.StarredRow("940GZZLUKSX", "victoria", "southbound")
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = FakeStarredStore(setOf(victoria)),
        )
        advanceUntilIdle()
        assertEquals(setOf(victoria), vm.starred.value)
        assertTrue(vm.starringAvailable.value)
    }

    @Test
    fun `an unavailable star set reports starring unavailable, not an empty set`() = runTest(dispatcher) {
        val unavailableStore = object : app.stopdash.domain.StarredRowsStore {
            override fun starred() =
                kotlinx.coroutines.flow.flowOf(app.stopdash.domain.StarredRowSet.Unavailable)
            override suspend fun toggle(row: app.stopdash.domain.StarredRow) {}
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = unavailableStore,
        )
        advanceUntilIdle()
        // No stars to pin (we can't read them), and the flag is false so the screen hides the
        // control rather than showing every row unfilled (a false "nothing starred" claim).
        assertTrue(vm.starred.value.isEmpty())
        assertFalse(vm.starringAvailable.value)
    }

    @Test
    fun `a failed star toggle sets the write-failed flag until it is acknowledged`() = runTest(dispatcher) {
        // A DataStore write failure (storage full, IO error) leaves the star unchanged and the
        // store won't re-emit, so the tap silently no-ops — the ViewModel raises an acknowledged
        // flag the screen turns into a transient message (SPEC principle 2). Acknowledged, not a
        // one-shot event, so it survives a rotation between the failed tap and the message.
        val failingToggle = object : app.stopdash.domain.StarredRowsStore {
            override fun starred() = kotlinx.coroutines.flow.flowOf(
                app.stopdash.domain.StarredRowSet.Loaded(emptySet()),
            )
            override suspend fun toggle(row: app.stopdash.domain.StarredRow) {
                throw java.io.IOException("disk full")
            }
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = failingToggle,
        )
        advanceUntilIdle()
        assertFalse(vm.starWriteFailed.value)
        vm.toggleStar(row("940GZZLUKSX", "victoria", "southbound"))
        advanceUntilIdle()
        assertTrue("a failed write raises the flag", vm.starWriteFailed.value)
        vm.starWriteFailureShown()
        assertFalse("acknowledging clears it", vm.starWriteFailed.value)
    }

    @Test
    fun `starring is unavailable until the store reports a loaded set`() = runTest(dispatcher) {
        // A store whose flow never emits a set (no read has completed) must leave starring
        // unavailable — enabling it early would show a persisted-starred row as unstarred with
        // a "Pin to top" action, and a tap would toggle the real membership off.
        val neverLoads = object : app.stopdash.domain.StarredRowsStore {
            override fun starred() =
                kotlinx.coroutines.flow.emptyFlow<app.stopdash.domain.StarredRowSet>()
            override suspend fun toggle(row: app.stopdash.domain.StarredRow) {}
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = neverLoads,
        )
        advanceUntilIdle()
        assertFalse(vm.starringAvailable.value)
        assertTrue(vm.starred.value.isEmpty())
    }

    @Test
    fun `a failed starred read leaves starring unavailable and is logged`() = runTest(dispatcher) {
        // A DataStore read failure must not escape the collect and crash the departures screen;
        // it leaves starring in an honest unavailable state (control hidden, nothing pinned)
        // and logs a sanitized reason.
        val warnings = mutableListOf<String>()
        val failingStore = object : app.stopdash.domain.StarredRowsStore {
            override fun starred(): kotlinx.coroutines.flow.Flow<app.stopdash.domain.StarredRowSet> =
                kotlinx.coroutines.flow.flow { throw java.io.IOException("disk read failed") }
            override suspend fun toggle(row: app.stopdash.domain.StarredRow) {}
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = failingStore,
            warn = { warnings += it },
        )
        advanceUntilIdle()
        assertFalse(vm.starringAvailable.value)
        assertTrue(vm.starred.value.isEmpty())
        assertTrue(warnings.any { it.contains("starred set read failed") })
    }

    // --- The near-me tiers: the retained ViewModel owns both and reconciles them across a
    // relocation (SPEC *Finding stops → Near me now*). ---

    // A `more` cluster [key] holding one stop per (id, mode). Coordinates don't matter here (the
    // ViewModel fetches by id and never reads them), so they're the origin.
    private fun clusterOf(key: String, vararg stops: Pair<String, String>): NearbySelection.NearbyCluster =
        NearbySelection.NearbyCluster(
            key = key,
            stops = stops.map { (id, mode) ->
                StopLocation(id = id, name = id, latitude = 0.0, longitude = 0.0, lines = listOf(LineRef("$mode-$id", id, mode)), clusterId = key)
            },
            distanceMeters = 0.0,
        )

    private fun tierVm(
        client: TflClient,
        eager: List<StopRef>,
        more: List<NearbySelection.NearbyCluster>,
        store: SnapshotStore = SnapshotStore.NONE,
    ) = MainViewModel(client, eager, initialMore = more, clock = { now }, io = dispatcher, snapshotStore = store)

    // The eager tier for a reconcile as one single-stop cluster per (id, mode), each its own key.
    private fun eagerOf(vararg stops: Pair<String, String>): List<NearbySelection.NearbyCluster> =
        stops.map { (id, mode) -> clusterOf("ec:$id", id to mode) }

    /**
     * The first state [vm] sets after [act] (a reconcile, worked out on the worker), as the screen
     * sees it: before anything the refetch it starts sets. [onSet] runs as it's set.
     */
    private fun TestScope.firstStateAfter(vm: MainViewModel, onSet: () -> Unit = {}, act: () -> Unit): DeparturesUiState {
        var first: DeparturesUiState? = null
        val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.drop(1).collect { state -> if (first == null) { first = state; onSet() } }
        }
        act()
        runCurrent()
        watch.cancel()
        return checkNotNull(first) { "the reconcile set nothing" }
    }

    private fun shownIds(vm: MainViewModel) = (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId }

    private fun twoStopClient() = FakeClient(
        mapOf(
            "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
            "MA" to Result.success(listOf(departure("central", "Central", 200))),
        ),
    )

    private val fartherPlace = CollapsedPlaces.Place("station:FS", "FS", "Farther", 1_600.0, listOf(LineRef("central", "Central", "tube")))
    private val fartherStop = StopLocation(id = "MA", name = "Farther", latitude = 0.0, longitude = 0.0)

    private fun fartherCards(client: TflClient = twoStopClient(), lookup: suspend (String) -> List<StopLocation>) =
        FartherCardsViewModel(
            stationStops = lookup,
            newModel = { stops, distances ->
                MainViewModel(client, stops, clock = { now }, io = dispatcher, stopDistanceMeters = distances)
            },
            io = dispatcher,
        )

    private fun openModel(cards: FartherCardsViewModel) = cards.model(fartherPlace.key)!!

    @Test
    fun `a pull fetches an opened farther card afresh too`() = runTest(dispatcher) {
        val client = CountingClient(emptyMap())
        val cards = FartherCardsViewModel(
            stationStops = { listOf(fartherStop) },
            newModel = { stops, distances ->
                MainViewModel(client, stops, clock = { now }, io = dispatcher, stopDistanceMeters = distances, arrivalsReuse = ARRIVALS_REUSE)
            },
            io = dispatcher,
        )
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        assertEquals(listOf("MA"), client.arrivalsCalls)
        // Moments later the list's refresh reuses the card's fetch; a pull doesn't.
        cards.refresh()
        advanceUntilIdle()
        assertEquals(listOf("MA"), client.arrivalsCalls)
        cards.forceNextFetch()
        cards.refresh()
        advanceUntilIdle()
        assertEquals(listOf("MA", "MA"), client.arrivalsCalls)
    }

    @Test
    fun `opening a farther card gives its station its own departures`() = runTest(dispatcher) {
        val cards = fartherCards { id ->
            assertEquals("FS", id)
            listOf(fartherStop)
        }
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        val load = cards.picked.value.loads[fartherPlace.key]
        assertTrue("open after the lookup: $load", load is FartherLoad.Open)
        assertEquals(setOf("MA"), (load as FartherLoad.Open).distanceMeters.keys)
        assertEquals(listOf("MA"), shownIds(openModel(cards)))
    }

    @Test
    fun `opening a farther bus card uses the poles it carries, with no lookup`() = runTest(dispatcher) {
        val cards = fartherCards { error("a bus place needs no lookup") }
        val bus = CollapsedPlaces.Place("bus:J1", "", "Farther", 600.0, emptyList(), stops = listOf(fartherStop))
        cards.open(bus, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        val load = cards.picked.value.loads[bus.key]
        assertTrue("open with its own poles: $load", load is FartherLoad.Open)
        assertEquals(listOf("MA"), shownIds(cards.model(bus.key)!!))
    }

    @Test
    fun `an opened bus card whose poles change is rebuilt from the new ones`() = runTest(dispatcher) {
        val cards = fartherCards { error("a bus place needs no lookup") }
        val bus = CollapsedPlaces.Place("bus:J1", "", "Farther", 600.0, emptyList(), stops = listOf(fartherStop))
        cards.open(bus, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        val moved = bus.copy(stops = listOf(fartherStop.copy(id = "E")))
        cards.retain(listOf(moved), Coordinates(0.0, 0.0))
        advanceUntilIdle()
        val load = cards.picked.value.loads[bus.key]
        assertTrue("reopened on the new poles: $load", load is FartherLoad.Open)
        assertEquals(setOf("E"), (load as FartherLoad.Open).distanceMeters.keys)
        assertEquals(listOf("E"), shownIds(cards.model(bus.key)!!))
    }

    @Test
    fun `a failed farther lookup can be tried again`() = runTest(dispatcher) {
        var fail = true
        val cards = fartherCards {
            if (fail) throw java.io.IOException("offline")
            listOf(fartherStop)
        }
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        assertEquals(FartherLoad.Failed, cards.picked.value.loads[fartherPlace.key])
        fail = false
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        assertTrue(cards.picked.value.loads[fartherPlace.key] is FartherLoad.Open)
    }

    @Test
    fun `a lookup finishing after a relocation measures from the new fix`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        // The stop sits at the new fix.
        val cards = fartherCards {
            gate.await()
            listOf(fartherStop.copy(latitude = 51.51))
        }
        cards.open(fartherPlace, Coordinates(51.5, 0.0))
        advanceUntilIdle()
        cards.retain(listOf(fartherPlace), Coordinates(51.51, 0.0))
        gate.complete(Unit)
        advanceUntilIdle()
        val open = cards.picked.value.loads[fartherPlace.key] as FartherLoad.Open
        assertEquals(0.0, open.distanceMeters.getValue("MA"), 1.0)
    }

    @Test
    fun `a relocation while an opened card is measured measures it from the new fix`() = runTest(dispatcher) {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val held = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
        // The stop sits at the new fix.
        val cards = FartherCardsViewModel(
            stationStops = { listOf(fartherStop.copy(latitude = 51.51)) },
            newModel = { stops, distances -> MainViewModel(twoStopClient(), stops, clock = { now }, io = dispatcher, stopDistanceMeters = distances) },
            io = dispatcher,
            compute = held,
        )
        cards.open(fartherPlace, Coordinates(51.5, 0.0))
        advanceUntilIdle()
        scheduler.advanceUntilIdle()
        advanceUntilIdle()
        // The lookup is in; its stops are being measured from the old fix when the rider moves.
        cards.retain(listOf(fartherPlace), Coordinates(51.51, 0.0))
        repeat(10) {
            scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }
        val open = cards.picked.value.loads[fartherPlace.key] as FartherLoad.Open
        assertEquals(0.0, open.distanceMeters.getValue("MA"), 1.0)
        assertEquals(0.0, openModel(cards).distanceMeters.getValue("MA"), 1.0)
    }

    @Test
    fun `a farther station no longer offered closes, and its model with it`() = runTest(dispatcher) {
        val cards = fartherCards { listOf(fartherStop) }
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        val model = openModel(cards)
        cards.retain(emptyList(), Coordinates(0.0, 0.0))
        advanceUntilIdle()
        assertTrue(cards.picked.value.loads.isEmpty())
        assertFalse("the closed card's model is cleared", model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun `a lookup for a card closed meanwhile opens nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val cards = fartherCards {
            gate.await()
            listOf(fartherStop)
        }
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        cards.retain(emptyList(), Coordinates(0.0, 0.0))
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(cards.picked.value.loads.isEmpty())
    }

    @Test
    fun `an opened card's departures show through the list, which keeps its own stamp`() {
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", emptyList(), now)),
            fetchedAt = now,
            unavailableStopIds = setOf("J"),
        )
        val card = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now.minusSeconds(30))),
            fetchedAt = now.minusSeconds(30),
            disruptionUnknown = true,
            lineStatuses = mapOf("central" to LineStatus("central", LineStatus.GOOD_SERVICE, "Good Service")),
            unavailableStopIds = setOf("MB"),
        )
        val shown = withOpenedFarther(
            list,
            listOf(setOf("MA", "MB") to card, setOf("X") to DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE), setOf("Y") to DeparturesUiState.Loading),
        )
        assertEquals(listOf("E", "MA"), shown.stops.map { it.stopId })
        assertEquals(now, shown.fetchedAt)
        assertFalse("a complete card leaves the list whole", shown.partialRefresh)
        assertEquals(setOf("MA"), shown.stopsDisruptionUnknown)
        assertEquals(mapOf("central" to LineStatus("central", LineStatus.GOOD_SERVICE, "Good Service")), shown.lineStatuses)
        // A failed card's stops read as failed, so its card offers a retry; a loading one's don't.
        assertEquals(setOf("J", "MB", "X"), shown.unavailableStopIds)
    }

    @Test
    fun `an opened card's stops with closure checks out keep their pending mark in the list`() {
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", emptyList(), now)),
            fetchedAt = now,
            statusPending = true,
            closurePending = setOf("E"),
        )
        val card = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)),
            fetchedAt = now,
            statusPending = true,
            closurePending = setOf("MA"),
        )
        val shown = withOpenedFarther(list, listOf(setOf("MA") to card))
        assertEquals(setOf("E", "MA"), shown.closurePending)
    }

    @Test
    fun `a card's pending mark doesn't spin on a stop the list already shows checked`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "E", emptyList(), now)), fetchedAt = now)
        // The card also has E, still checking its closure; the list's own E, already checked, is shown.
        val card = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", emptyList(), now), StopArrivals("MA", "Farther", emptyList(), now)),
            fetchedAt = now,
            statusPending = true,
            closurePending = setOf("E", "MA"),
        )
        val shown = withOpenedFarther(list, listOf(setOf("E", "MA") to card))
        assertEquals(setOf("MA"), shown.closurePending)
    }

    @Test
    fun `a row's line still being checked reads as checking though another stop's check failed`() {
        fun row(stopId: String, lineId: String) = DepartureRow(
            stopId = stopId, stopName = stopId, lineId = lineId, lineName = lineId, direction = "outbound",
            directionKey = "outbound", destination = "Example", mode = "tube", upcoming = emptyList(), fetchedAt = now,
        )
        val shown = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", emptyList(), now), StopArrivals("F", "F", emptyList(), now)),
            fetchedAt = now,
            statusPending = true,
            // E's line check came back undetermined; F's line, only a prediction names, isn't asked yet.
            checkFailed = true,
            pendingLineIds = setOf("northern"),
        )
        assertFalse(shown.checkingDisruptionsFor(row("E", "victoria")))
        assertTrue(shown.checkingDisruptionsFor(row("F", "northern")))
        // Once the load is done, nothing is still checking.
        assertFalse(shown.copy(statusPending = false).checkingDisruptionsFor(row("F", "northern")))
    }

    @Test
    fun `a part-shown cold load names the lines whose check is still out`() = runTest(dispatcher) {
        val statusGate = CompletableDeferred<Unit>()
        val laterStop = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[1].id) laterStop.await()
                return listOf(departure("victoria", "Victoria", 300))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                statusGate.await()
                return lineIds.map { status(it, 10, "Good Service") }
            }
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        // The stops declare the Victoria line, so its check goes out early, before every stop is in.
        val vm = MainViewModel(
            client,
            seeds.map { it.copy(lines = listOf(LineRef("victoria", "Victoria", "tube"))) },
            clock = { now },
            io = dispatcher,
        )
        advanceTimeBy(FIRST_PAINT_GRACE_MS + 1)
        runCurrent()
        assertEquals(setOf("victoria"), (vm.state.value as DeparturesUiState.Loaded).pendingLineIds)

        statusGate.complete(Unit)
        runCurrent()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).pendingLineIds.isEmpty())
        laterStop.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a finished list's lines don't read as checking while an opened card loads`() {
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", listOf(departure("victoria", "Victoria", 120)), now)),
            fetchedAt = now,
            disruptionUnknown = true,
        )
        val card = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", listOf(departure("northern", "Northern", 180)), now)),
            fetchedAt = now,
            statusPending = true,
            pendingLineIds = setOf("northern"),
        )
        val shown = withOpenedFarther(list, listOf(setOf("MA") to card))
        assertEquals(setOf("northern"), shown.pendingLineIds)
    }

    @Test
    fun `a card still checking a stop the list already shows doesn't make the list's row read checking`() {
        // The list's own Victoria line check came back undetermined: its row reads "couldn't check".
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", listOf(departure("victoria", "Victoria", 120)), now)),
            fetchedAt = now,
            disruptionUnknown = true,
        )
        // The card also has E, its own check of the line still out, and a stop of its own on another line.
        val card = DeparturesUiState.Loaded(
            stops = listOf(
                StopArrivals("E", "E", listOf(departure("victoria", "Victoria", 120)), now),
                StopArrivals("MA", "Farther", listOf(departure("northern", "Northern", 180)), now),
            ),
            fetchedAt = now,
            statusPending = true,
            pendingLineIds = setOf("victoria", "northern"),
        )
        assertEquals(setOf("northern"), withOpenedFarther(list, listOf(setOf("E", "MA") to card)).pendingLineIds)
    }

    @Test
    fun `a line the list has checked doesn't read as checking for a card still loading it`() {
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", listOf(departure("victoria", "Victoria", 120)), now)),
            fetchedAt = now,
            determinedLineIds = setOf("victoria"),
        )
        val card = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", listOf(departure("victoria", "Victoria", 120), departure("northern", "Northern", 180)), now)),
            fetchedAt = now,
            statusPending = true,
            pendingLineIds = setOf("victoria", "northern"),
        )
        assertEquals(setOf("northern"), withOpenedFarther(list, listOf(setOf("MA") to card)).pendingLineIds)
    }

    @Test
    fun `a list with a closure check out says it's still checking, heading or not`() {
        val shown = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", emptyList(), now)),
            fetchedAt = now,
            statusPending = true,
            closurePending = setOf("E"),
        )
        // Its lines are all checked, but the stop's closure check is still out.
        assertTrue(shown.checkingDisruptions)
        assertFalse(shown.copy(closurePending = emptySet()).checkingDisruptions)
        assertFalse(shown.copy(checkFailed = true).checkingDisruptions)
    }

    @Test
    fun `an opened card part-shown by its own cold load says it is still checking`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "E", emptyList(), now)), fetchedAt = now)
        val loading = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)),
            fetchedAt = now,
            disruptionUnknown = true,
            statusPending = true,
        )
        val shown = withOpenedFarther(list, listOf(setOf("MA", "MB") to loading))
        assertTrue(shown.statusPending)
        assertTrue(shown.disruptionUnknown)
        // Once the card's batch is whole, the list's own state stands again.
        val done = withOpenedFarther(list, listOf(setOf("MA", "MB") to loading.copy(disruptionUnknown = false, statusPending = false)))
        assertFalse(done.statusPending)
        assertFalse(done.disruptionUnknown)
    }

    @Test
    fun `an opened card still loading whose lines are checked doesn't say it's checking`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "E", emptyList(), now)), fetchedAt = now)
        val loading = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)),
            fetchedAt = now,
            pendingStops = listOf(StopRef("MB", "Farther")),
            disruptionUnknown = false,
            statusPending = true,
        )
        val shown = withOpenedFarther(list, listOf(setOf("MA", "MB") to loading))
        assertTrue(shown.statusPending)
        assertFalse(shown.disruptionUnknown)
    }

    @Test
    fun `an opened card still loading whose check failed says it couldn't check`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "E", emptyList(), now)), fetchedAt = now)
        val loading = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)),
            fetchedAt = now,
            pendingStops = listOf(StopRef("MB", "Farther")),
            disruptionUnknown = true,
            statusPending = true,
            checkFailed = true,
        )
        val shown = withOpenedFarther(list, listOf(setOf("MA", "MB") to loading))
        assertTrue(shown.statusPending)
        assertTrue(shown.checkFailed)
        assertFalse(withOpenedFarther(list, listOf(setOf("MA", "MB") to loading.copy(checkFailed = false))).checkFailed)
    }

    @Test
    fun `the list's finished couldn't-check stays that while a card loads`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "E", emptyList(), now)), fetchedAt = now, disruptionUnknown = true)
        val loading = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)),
            fetchedAt = now,
            pendingStops = listOf(StopRef("MB", "Farther")),
            disruptionUnknown = true,
            statusPending = true,
        )
        val shown = withOpenedFarther(list, listOf(setOf("MA", "MB") to loading))
        assertTrue(shown.statusPending)
        assertTrue(shown.checkFailed)
    }

    @Test
    fun `an opened card still loading keeps every one of its stops loading`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "E", emptyList(), now)), fetchedAt = now)
        // One of the card's stops is back with nothing running; the other is still out.
        val loading = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now)),
            fetchedAt = now,
            pendingStops = listOf(StopRef("MB", "Farther")),
            statusPending = true,
        )
        assertEquals(setOf("MA", "MB"), withOpenedFarther(list, listOf(setOf("MA", "MB") to loading)).openedLoadingStopIds)
        assertTrue(withOpenedFarther(list, listOf(setOf("MA", "MB") to loading.copy(statusPending = false))).openedLoadingStopIds.isEmpty())
    }

    @Test
    fun `an opened card whose refresh failed marks the list partial`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("E", "E", emptyList(), now)), fetchedAt = now)
        val failed = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Farther", emptyList(), now.minusSeconds(600))),
            fetchedAt = now.minusSeconds(600),
            refreshFailure = DeparturesUiState.Error.Kind.OFFLINE,
        )
        val incomplete = DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now, partialRefresh = true)
        assertTrue(withOpenedFarther(list, listOf(setOf("MA") to failed)).partialRefresh)
        assertTrue(withOpenedFarther(list, listOf(setOf("MB") to incomplete)).partialRefresh)
        assertEquals(now, withOpenedFarther(list, listOf(setOf("MA") to failed)).fetchedAt)
    }

    @Test
    fun `an opened card's failure makes the banner generic rather than named`() {
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "Near", emptyList(), now, arrivalsFresh = false)),
            fetchedAt = now,
            partialRefresh = true,
            partialStops = mapOf("E" to DeparturesUiState.FailedStop("Near", DeparturesUiState.Error.Kind.SERVER)),
        )
        val offline = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MB", "Farthest", emptyList(), now.minusSeconds(600))),
            fetchedAt = now.minusSeconds(600),
            refreshFailure = DeparturesUiState.Error.Kind.OFFLINE,
        )
        val shown = withOpenedFarther(list, listOf(setOf("MB") to offline))
        assertTrue(shown.partialRefresh)
        // Naming only "Near" would read as if it were the only stop that failed.
        assertTrue(shown.partialStops.isEmpty())
        assertEquals(null, shown.partialReason)

        // With no card failing, the list's own failure stays named.
        val fine = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MB", "Farthest", emptyList(), now)), fetchedAt = now)
        assertEquals(listOf("Near"), withOpenedFarther(list, listOf(setOf("MB") to fine)).partialStops.values.map { it.name })
    }

    @Test
    fun `a stop the list couldn't load but a card shows fresh isn't named`() {
        // The list never loaded MA (a journey origin at the farther station); the card has it fresh.
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", emptyList(), now)),
            fetchedAt = now,
            partialRefresh = true,
            partialStops = mapOf("MA" to DeparturesUiState.FailedStop("Shared", DeparturesUiState.Error.Kind.SERVER)),
        )
        val card = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MA", "Shared", emptyList(), now)), fetchedAt = now)
        val shown = withOpenedFarther(list, listOf(setOf("MA") to card))
        assertFalse("nothing shown is failed any more", shown.partialRefresh)
        assertTrue(shown.partialStops.isEmpty())

        // With another list stop still failed, only that one is named, with the list's reason.
        val twoFailed = list.copy(partialStops = mapOf("MA" to DeparturesUiState.FailedStop("Shared", DeparturesUiState.Error.Kind.SERVER), "MB" to DeparturesUiState.FailedStop("Other", DeparturesUiState.Error.Kind.SERVER)))
        val stillPartial = withOpenedFarther(twoFailed, listOf(setOf("MA") to card))
        assertTrue(stillPartial.partialRefresh)
        assertEquals(listOf("Other"), stillPartial.partialStops.values.map { it.name })
        assertEquals(DeparturesUiState.Error.Kind.SERVER, stillPartial.partialReason)
    }

    @Test
    fun `a list still waiting on a stop stays partial when a card shows its named one fresh`() {
        // After a relocation: MA failed and is named; another stop is pending and can't be.
        val list = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("E", "E", emptyList(), now)),
            fetchedAt = now,
            partialRefresh = true,
            partialStops = mapOf("MA" to DeparturesUiState.FailedStop("Shared", DeparturesUiState.Error.Kind.SERVER)),
            partialUnnamed = true,
        )
        val card = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MA", "Shared", emptyList(), now)), fetchedAt = now)
        val shown = withOpenedFarther(list, listOf(setOf("MA") to card))
        assertTrue("the pending stop keeps the list partial", shown.partialRefresh)
        assertTrue(shown.partialStops.isEmpty())
    }

    @Test
    fun `a card's failure for a stop the list already shows fresh names nothing`() {
        // A journey origin at the farther station: the list shows it, fresh.
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MA", "Shared", emptyList(), now)), fetchedAt = now)
        val card = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Shared", emptyList(), now.minusSeconds(600))),
            fetchedAt = now.minusSeconds(600),
            refreshFailure = DeparturesUiState.Error.Kind.OFFLINE,
        )
        val shown = withOpenedFarther(list, listOf(setOf("MA") to card))
        assertFalse("every stop is shown fresh by the list", shown.partialRefresh)
        assertTrue(shown.partialStops.isEmpty())
        assertEquals(null, shown.partialReason)
        // Nor one that came back incomplete for it.
        val incomplete = DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now, partialRefresh = true)
        assertFalse(withOpenedFarther(list, listOf(setOf("MA") to incomplete)).partialRefresh)
    }

    @Test
    fun `a card's failure marks the list partial only where no fresh copy is shown`() {
        val list = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MA", "Shared", emptyList(), now)), fetchedAt = now)
        val old = now.minusSeconds(600)
        // Failed: MA the list shows fresh, MB only its own older copy.
        val failed = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MA", "Shared", emptyList(), old), StopArrivals("MB", "Farther", emptyList(), old)),
            fetchedAt = old,
            refreshFailure = DeparturesUiState.Error.Kind.OFFLINE,
        )
        assertTrue(withOpenedFarther(list, listOf(setOf("MA", "MB") to failed)).partialRefresh)
        // Incomplete: MB kept at an older age, or not loaded at all.
        val stale = DeparturesUiState.Loaded(
            stops = listOf(StopArrivals("MB", "Farther", emptyList(), old, arrivalsFresh = false)),
            fetchedAt = now,
            partialRefresh = true,
        )
        assertTrue(withOpenedFarther(list, listOf(setOf("MA", "MB") to stale)).partialRefresh)
        val missing = DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now, partialRefresh = true)
        assertTrue(withOpenedFarther(list, listOf(setOf("MA", "MB") to missing)).partialRefresh)
        // Another card showing MB fresh covers it.
        val fresh = DeparturesUiState.Loaded(stops = listOf(StopArrivals("MB", "Farther", emptyList(), now)), fetchedAt = now)
        assertFalse(withOpenedFarther(list, listOf(setOf("MB") to fresh, setOf("MA", "MB") to missing)).partialRefresh)
        // But not once a failed card's older copy is the one shown.
        assertTrue(withOpenedFarther(list, listOf(setOf("MA", "MB") to failed, setOf("MB") to fresh)).partialRefresh)
    }

    @Test
    fun `an opened card's model is re-measured when the rider moves`() = runTest(dispatcher) {
        // The stop sits at the new fix, about 1.1 km from the old one.
        val cards = fartherCards { listOf(fartherStop.copy(latitude = 51.51)) }
        cards.open(fartherPlace, Coordinates(51.5, 0.0))
        advanceUntilIdle()
        assertEquals(1_112.0, openModel(cards).distanceMeters.getValue("MA"), 5.0)
        cards.retain(listOf(fartherPlace), Coordinates(51.51, 0.0))
        advanceUntilIdle()
        assertEquals(0.0, openModel(cards).distanceMeters.getValue("MA"), 1.0)
    }

    @Test
    fun `the shown near stops follow a same-set reconcile`() = runTest(dispatcher) {
        // The farther-station cards count these as reached, so they must track what is loaded,
        // including a reconcile that swaps clusters across the eager/more boundary (Codex P2, PR #226).
        val vm = tierVm(twoStopClient(), listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        assertEquals(listOf("E"), vm.shownNearStops.value.map { it.id })

        // M1 promoted to eager, E demoted to the `more` tier: MA is loaded, and E no longer is.
        vm.reconcile(newEager = listOf(clusterOf("M1", "MA" to "bus")), newMore = listOf(clusterOf("EC", "E" to "tube")))
        advanceUntilIdle()
        assertEquals(listOf("MA"), vm.shownNearStops.value.map { it.id })
    }

    @Test
    fun `farther cards count a shown stop's lines and ids as reached`() {
        val reached = fartherReached(
            listOf(StopRef("S", "S", listOf(LineRef("victoria", "Victoria", "Tube")), clusterId = "C", hubId = "")),
        )
        assertEquals(setOf(setOf("S", "C")), reached.map { it.ids }.toSet())
        // Modes are compared lowercase against the bundled index.
        assertEquals(setOf(FartherStations.Line("tube", "victoria")), reached.single().lines)
    }

    @Test
    fun `a From page's farther cards are kept apart from near me's`() {
        // Each list has its own opened-card state (Codex P1, PR #226).
        assertNotEquals(fartherCardsKey(null), fartherCardsKey("from-list-stores"))
        assertEquals(fartherCardsKey("from-list-stores"), fartherCardsKey("from-list-stores"))
    }

    @Test
    fun `a shown stop whose fetch failed doesn't count as reached`() {
        // Its departures never reached the list, so its line keeps its farther card (Codex P2, PR #226).
        val shown = listOf(StopRef("A", "A"), StopRef("B", "B"))
        assertEquals(listOf(setOf("A")), fartherReached(shown, loadedIds = setOf("A")).map { it.ids })
        // Still loading (no list yet): everything shown counts, so cards don't flash up and vanish.
        assertEquals(2, fartherReached(shown, loadedIds = null).size)
    }

    @Test
    fun `an all-arrivals-failed refresh still prunes a resolved dismissal`() = runTest(dispatcher) {
        // Arrivals all fail with no prior snapshot (an Error state, empty merged), but the disruption
        // lookup succeeded and came back clear — the resolved dismissal must still be pruned so it
        // can't suppress a later same-text closure (SPEC principle 2). The reconcile runs from the
        // batch provenance, not the UI state, so the Error path doesn't skip it.
        val backing = MutableStateFlow<Set<DismissedAlert>>(setOf(DismissedAlert("490G000EXAMPLE", "Bus Stop Closed")))
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> =
                throw TflException.Unreachable("offline", null)
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(
            client,
            listOf(StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        assertTrue("all arrivals failed with no prior → Error", vm.state.value is DeparturesUiState.Error)
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    @Test
    fun `a line waiting on its alerts' directions keeps a one-way dismissal until the split lands`() = runTest(dispatcher) {
        val northbound = LineStatus("141", 6, "Diversion", "Diverted northbound.")
        val dismissal = DismissedAlert.ofLineStatus(northbound)
        val backing = MutableStateFlow(setOf(dismissal))
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        var awaiting = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(
                Departure("141", "141", "outbound", "Example", null, Instant.parse("2026-09-18T08:05:00Z"), mode = "bus"),
            )
            // Unsplit, so the northbound alert isn't among the line's statuses, until the lookup lands.
            override suspend fun lineStatuses(lineIds: Collection<String>) =
                listOf(LineStatus("141", 6, "Diversion", "Diverted both ways.", awaitingDirections = awaiting))
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, listOf(StopRef("490000001A", "Example Road")), clock = { now }, io = dispatcher, dismissedStore = store)
        advanceUntilIdle()
        assertEquals(setOf(dismissal), backing.value)
        // Split now, and the northbound alert isn't there: it ended, so it's forgotten.
        awaiting = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<DismissedAlert>(), backing.value)
    }

    // Records closure lookups: each batched pole request and each single-stop request.
    private class ClosureCountingClient(
        private val poleResult: (List<String>) -> Map<String, List<StopDisruption>>,
    ) : TflClient {
        val poleCalls = mutableListOf<List<String>>()
        val singleCalls = mutableListOf<String>()
        override suspend fun arrivals(stopId: String) = listOf(departureAt(stopId))
        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
            singleCalls += stopId
            return emptyList()
        }
        override suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> {
            poleCalls += stopIds
            return poleResult(stopIds)
        }
        private fun departureAt(stopId: String) = Departure(
            lineId = "141",
            lineName = "141",
            direction = "outbound",
            destination = "Example $stopId",
            platform = null,
            expectedArrival = Instant.parse("2026-09-18T08:05:00Z"),
            mode = "bus",
        )
    }

    @Test
    fun `a junction's poles share one closure request, a station keeps its own`() = runTest(dispatcher) {
        val client = ClosureCountingClient { ids -> mapOf(ids.first() to listOf(StopDisruption("Bus Stop Closed"))) }
        val vm = MainViewModel(
            client,
            listOf(
                StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("490000001B", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("940GZZLUMRH", "Manor House"),
            ),
            clock = { now },
            io = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(listOf(listOf("490000001A", "490000001B")), client.poleCalls)
        assertEquals(listOf("940GZZLUMRH"), client.singleCalls)
        // Each pole keeps only its own notice: the closed pole's sibling stays open.
        val stops = (vm.state.value as DeparturesUiState.Loaded).stops.associateBy { it.stopId }
        assertEquals(listOf(StopDisruption("Bus Stop Closed")), stops.getValue("490000001A").disruptions)
        assertTrue(stops.getValue("490000001B").disruptions.isEmpty())
    }

    @Test
    fun `a junction's pole batch waits for each of its poles' departures`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val poleCalls = mutableListOf<List<String>>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "490000001B") gate.await()
                return emptyList()
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            override suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> {
                poleCalls += stopIds
                return emptyMap()
            }
        }
        MainViewModel(
            client,
            listOf(
                StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("490000001B", "Example Road", clusterId = "490G000EXAMPLE"),
            ),
            clock = { now },
            io = dispatcher,
        )
        runCurrent()
        // One pole's departures are still out, so the batch holding it isn't sent yet.
        assertTrue(poleCalls.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(listOf("490000001A", "490000001B")), poleCalls)
    }

    @Test
    fun `a pole batch answered meanwhile is neither sent nor counted`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val gate = CompletableDeferred<Unit>()
        val poleCalls = mutableListOf<List<String>>()
        val logged = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "490000001B") gate.await()
                return emptyList()
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            override suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> {
                poleCalls += stopIds
                return emptyMap()
            }
        }
        var clockNow = now
        MainViewModel(
            client,
            listOf(
                StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("490000001B", "Example Road", clusterId = "490G000EXAMPLE"),
            ),
            clock = { clockNow },
            io = dispatcher,
            disruptionCache = shared,
            disruptionReuse = DISRUPTION_REUSE,
            elapsedMillis = { 0L },
            logStats = { logged += it },
        )
        runCurrent()
        // While a pole's departures are out, another screen answers both poles.
        clockNow = now.plusSeconds(5)
        shared.keep("490000001A", shared.ask(clockNow), emptyList())
        shared.keep("490000001B", shared.ask(clockNow), emptyList())
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(poleCalls.isEmpty())
        assertTrue(logged.joinToString(), logged.any { "0 closure batch" in it })
    }

    @Test
    fun `a failed pole batch leaves each of its poles' closures unchecked`() = runTest(dispatcher) {
        val client = ClosureCountingClient { throw TflException.Offline(null) }
        val vm = MainViewModel(
            client,
            listOf(
                StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("490000001B", "Example Road", clusterId = "490G000EXAMPLE"),
            ),
            clock = { now },
            io = dispatcher,
        )
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertEquals(setOf("490000001A", "490000001B"), state.stopsDisruptionUnknown)
    }

    @Test
    fun `a hub looked up for a part-loaded stop is counted in the fetch log`() = runTest(dispatcher) {
        val logged = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == seeds[1].id) gate.await()
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == seeds[0].id) listOf(StopDisruption("Station closed until further notice")) else emptyList()
            override suspend fun hubInfo(hubId: String) = HubInfo("Oxford Circus Interchange")
        }
        MainViewModel(
            client,
            listOf(seeds[0].copy(hubId = "HUBOXC"), seeds[1]),
            clock = { now },
            io = dispatcher,
            elapsedMillis = { 0L },
            logStats = { logged += it },
        )
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(logged.toString(), logged.single().contains(", 1 hub)"))
    }

    @Test
    fun `a hub another caller is already looking up is waited on, not counted`() = runTest(dispatcher) {
        val logged = mutableListOf<String>()
        var hubCalls = 0
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == seeds[0].id) listOf(StopDisruption("Station closed until further notice")) else emptyList()
            override suspend fun hubInfo(hubId: String): HubInfo {
                hubCalls++
                return HubInfo("Oxford Circus Interchange")
            }
        }
        // A trip on the way's lookup of the same hub, in flight when the list asks (Codex, #567).
        val hubNames = HubInfoCache()
        val answer = CompletableDeferred<HubInfo>()
        val other = async { hubNames.load("HUBOXC") { answer.await() } }
        advanceUntilIdle()
        MainViewModel(
            client,
            listOf(seeds[0].copy(hubId = "HUBOXC"), seeds[1]),
            clock = { now },
            io = dispatcher,
            elapsedMillis = { 0L },
            logStats = { logged += it },
            hubNames = hubNames,
        )
        advanceUntilIdle()
        answer.complete(HubInfo("Oxford Circus Interchange"))
        other.await()
        advanceUntilIdle()

        assertEquals(0, hubCalls)
        assertTrue(logged.toString(), logged.single().contains(", 0 hub)"))
    }

    @Test
    fun `each fetch logs its request count and time`() = runTest(dispatcher) {
        val logged = mutableListOf<String>()
        val client = ClosureCountingClient { emptyMap() }
        MainViewModel(
            client,
            listOf(
                StopRef("490000001A", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("490000001B", "Example Road", clusterId = "490G000EXAMPLE"),
                StopRef("940GZZLUMRH", "Manor House"),
            ),
            clock = { now },
            io = dispatcher,
            elapsedMillis = { 0L },
            logStats = { logged += it },
        )
        advanceUntilIdle()

        assertTrue(
            logged.toString(),
            logged.contains(
                "departures fetch: 6 requests (3 departures, 1 closure, 1 closure batch, 1 line status, 0 hub) in 0 ms, 0 ms rate-limited",
            ),
        )
    }

    // A busy hub's lines: too long for one Line Status request (LineStatusBatch), so two are sent.
    private val hubLines = listOf(
        "214", "46", "63", "205", "30", "73", "390", "91", "circle", "hammersmith-city", "metropolitan",
        "northern", "piccadilly", "victoria", "london-north-eastern-railway", "great-northern",
        "hull-trains", "grand-central", "thameslink", "southeastern", "eurostar", "east-midlands-railway",
        "avanti-west-coast", "west-midlands-trains", "n63", "n205", "n73", "n91", "lumo", "lioness",
    )

    private open inner class HubLinesClient : TflClient {
        val statusCalls = mutableListOf<Collection<String>>()
        override suspend fun arrivals(stopId: String) = hubLines.mapIndexed { i, id -> departure(id, id, 60L + i) }
        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            statusCalls += lineIds
            return answer(lineIds)
        }
        open fun answer(lineIds: Collection<String>): List<LineStatus> = lineIds.map { LineStatus(it, 10, "Good Service") }
        override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
    }

    @Test
    fun `the tube's lines are checked with the list's own, for the disruptions row`() = runTest(dispatcher) {
        // The list's lines and the tube's go out together: the tube costs no request of its own.
        val client = object : HubLinesClient() {
            override fun answer(lineIds: Collection<String>): List<LineStatus> =
                lineIds.map { if (it == "central") LineStatus(it, 6, "Severe Delays") else LineStatus(it, 10, "Good Service") }
        }
        val vm = MainViewModel(client, listOf(seeds[0].copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))), clock = { now }, io = dispatcher, alwaysNetworks = { setOf("tube") })
        advanceUntilIdle()

        val tube = checkNotNull(vm.always.value)
        assertEquals(HomeLines.TUBE_IDS, tube.statuses.keys)
        assertEquals("Severe Delays", tube.statuses.getValue("central").description)
        assertTrue(HomeLines.TUBE_IDS.all { line -> client.statusCalls.first().contains(line) })
        // The list itself is about its own lines only: a tube line it doesn't show isn't on it.
        assertFalse("central" in (vm.state.value as DeparturesUiState.Loaded).lineStatuses)
    }

    @Test
    fun `the networks the row always covers are asked about as chosen, and none where there's no row`() = runTest(dispatcher) {
        val client = object : HubLinesClient() {}
        val chosen = HomeLines.idsOf(setOf("tube", "dlr"))
        // The choice is expanded on the worker, never the caller: the worker marks what it runs.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatcher.dispatch(context, Runnable { onWorker.set(true); try { block.run() } finally { onWorker.set(false) } })
            }
        }
        val readOnWorker = mutableListOf<Boolean>()
        val vm = MainViewModel(
            client, listOf(seeds[0].copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))), clock = { now }, io = dispatcher,
            compute = worker, alwaysNetworks = { readOnWorker += onWorker.get(); setOf("tube", "dlr") },
        )
        advanceUntilIdle()
        assertEquals(listOf(true), readOnWorker.distinct())
        assertEquals(chosen, checkNotNull(vm.always.value).askedFor)
        assertTrue("dlr" in client.statusCalls.first())
        // A list with no row asks about only its own lines.
        val quiet = object : HubLinesClient() {}
        MainViewModel(quiet, listOf(seeds[0].copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))), clock = { now }, io = dispatcher, alwaysNetworks = { emptySet() })
        advanceUntilIdle()
        assertFalse(quiet.statusCalls.flatten().any { it == "dlr" || it == "central" })
    }

    @Test
    fun `a network just chosen is asked about at once, not at the next refresh`() = runTest(dispatcher) {
        // Holds its answers while [held] is set, so a check can be seen under way.
        var held: CompletableDeferred<Unit>? = null
        val client = object : HubLinesClient() {
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                held?.await()
                return super.lineStatuses(lineIds)
            }
        }
        var networks = setOf("tube")
        var clockNow = now
        val vm = MainViewModel(
            client, listOf(seeds[0].copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))), clock = { clockNow }, io = dispatcher,
            alwaysNetworks = { networks },
        )
        advanceUntilIdle()
        val before = client.statusCalls.size
        networks = setOf("tube", "dlr")
        vm.checkAlways()
        advanceUntilIdle()
        // One request straight away, the new line in it (a test reuses no verdict, so the tube's go too).
        val asked = client.statusCalls.drop(before)
        assertEquals(1, asked.size)
        assertTrue("dlr" in asked.single())
        assertEquals(setOf("dlr") + HomeLines.TUBE_IDS, checkNotNull(vm.always.value).askedFor)
        // Asked again with nothing new chosen (the screen showing again), it sends nothing.
        val after = client.statusCalls.size
        vm.checkAlways()
        advanceUntilIdle()
        assertEquals(after, client.statusCalls.size)
        // Once those verdicts have aged (the row off a while, then on), it asks again, the aged lines read
        // as being asked meanwhile, not as unchecked.
        clockNow = now.plus(Duration.ofMinutes(10))
        val gate = CompletableDeferred<Unit>().also { held = it }
        vm.checkAlways()
        advanceUntilIdle()
        assertFalse("dlr" in checkNotNull(vm.always.value).askedFor)
        // Called again while that one is out (a rotation), it waits and sends nothing more.
        vm.checkAlways()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(after + 1, client.statusCalls.size)
        assertTrue("dlr" in checkNotNull(vm.always.value).askedFor)
        // Called while a refresh is asking about the same aged lines, it waits for that refresh's answer
        // rather than sending its own.
        clockNow = now.plus(Duration.ofMinutes(20))
        val refreshGate = CompletableDeferred<Unit>().also { held = it }
        val dlrAsked = { client.statusCalls.count { "dlr" in it } }
        val beforeRefresh = dlrAsked()
        vm.refresh()
        advanceUntilIdle()
        vm.checkAlways()
        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(beforeRefresh + 1, dlrAsked())
    }

    @Test
    fun `a refresh starting while a settings check is out waits for it and keeps its answer`() = runTest(dispatcher) {
        // Holds its answers while [held] is set; fails a request made while [failing] is set.
        var held: CompletableDeferred<Unit>? = null
        var failing = false
        val client = object : HubLinesClient() {
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                val fail = failing
                held?.await()
                if (fail) throw java.io.IOException("offline")
                return super.lineStatuses(lineIds)
            }
        }
        var networks = setOf("tube")
        var clockNow = now
        val vm = MainViewModel(
            client, listOf(seeds[0].copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))), clock = { clockNow }, io = dispatcher,
            alwaysNetworks = { networks }, lineStatusReuse = LINE_STATUS_REUSE,
        )
        advanceUntilIdle()
        // The settings check goes out and waits on its answer; a refresh starts meanwhile, and would fail.
        networks = setOf("tube", "dlr")
        val gate = CompletableDeferred<Unit>().also { held = it }
        vm.checkAlways()
        advanceUntilIdle()
        val dlrAsked = { client.statusCalls.count { "dlr" in it } }
        failing = true
        vm.refresh()
        advanceUntilIdle()
        // The settings check's answer comes in after the refresh began.
        clockNow = now.plusSeconds(2)
        gate.complete(Unit)
        advanceUntilIdle()
        // The refresh reused the settings check's answer rather than asking again, so its failure never
        // replaced it: the DLR reads as checked.
        assertEquals(1, dlrAsked())
        assertTrue(checkNotNull(vm.always.value).current("dlr", clockNow))
    }

    @Test
    fun `a network chosen during the first load is asked about once that load is in`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = object : HubLinesClient() {
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                gate.await()
                return super.lineStatuses(lineIds)
            }
        }
        var networks = setOf("tube")
        val vm = MainViewModel(
            client, listOf(seeds[0].copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))), clock = { now }, io = dispatcher,
            alwaysNetworks = { networks },
        )
        advanceUntilIdle()
        // The first load has asked about the tube and waits on its answer; the rider chooses the DLR meanwhile.
        networks = setOf("tube", "dlr")
        vm.checkAlways()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(client.statusCalls.any { "dlr" in it })
        assertTrue("dlr" in checkNotNull(vm.always.value).askedFor)
    }

    @Test
    fun `a cold start asks about the stored networks, never the default in their place`() = runTest(dispatcher) {
        val client = object : HubLinesClient() {}
        val stored = CompletableDeferred<Set<String>>()
        val vm = MainViewModel(
            client, listOf(seeds[0].copy(lines = listOf(LineRef("victoria", "Victoria", "tube")))), clock = { now }, io = dispatcher,
            alwaysNetworks = { stored.await() },
        )
        advanceUntilIdle()
        // Not read yet: the status request waits for it rather than asking about the tube.
        assertTrue(client.statusCalls.isEmpty())
        stored.complete(setOf("dlr"))
        advanceUntilIdle()
        assertTrue("dlr" in client.statusCalls.first())
        assertFalse("central" in client.statusCalls.first())
        assertEquals(setOf("dlr"), checkNotNull(vm.always.value).askedFor)
    }

    @Test
    fun `a line status lookup that fails on its first request counts only that request`() = runTest(dispatcher) {
        val logged = mutableListOf<String>()
        val client = object : HubLinesClient() {
            override fun answer(lineIds: Collection<String>): List<LineStatus> = throw TflException.RateLimited(null)
        }
        MainViewModel(client, listOf(seeds[0]), clock = { now }, io = dispatcher, elapsedMillis = { 0L }, logStats = { logged += it })
        advanceUntilIdle()

        assertEquals(1, client.statusCalls.size)
        assertTrue(logged.toString(), logged.single().contains(", 1 line status,"))
    }

    @Test
    fun `a hub's lines are checked in requests TfL accepts, keeping the part it knows`() = runTest(dispatcher) {
        val logged = mutableListOf<String>()
        // TfL knows none of the first request's lines, and all of the second's.
        val client = object : HubLinesClient() {
            override fun answer(lineIds: Collection<String>): List<LineStatus> =
                if ("lioness" in lineIds) super.answer(lineIds) else throw TflException.NotFound(null)
        }
        val vm = MainViewModel(client, listOf(seeds[0]), clock = { now }, io = dispatcher, elapsedMillis = { 0L }, logStats = { logged += it })
        advanceUntilIdle()

        assertEquals(2, client.statusCalls.size)
        client.statusCalls.forEach { assertTrue(it.joinToString(",").length <= LineStatusBatch.MAX_SEGMENT_LENGTH) }
        assertEquals(hubLines.toSet(), client.statusCalls.flatten().toSet())
        assertTrue(logged.toString(), logged.single().contains(", 2 line status,"))
        // The unknown part is never determined, so its rows read unchecked, but TfL had no status for
        // it to give: no check failed, so no banner says one did. It isn't asked about again.
        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertFalse("214" in loaded.determinedLineIds)
        assertTrue("lioness" in loaded.determinedLineIds)
        assertFalse(loaded.disruptionUnknown)
        vm.refresh()
        advanceUntilIdle()
        assertFalse(client.statusCalls.drop(2).flatten().any { it == "214" })
    }

    @Test
    fun `a later line status request failing keeps what the earlier one returned`() = runTest(dispatcher) {
        // The first request answers (with a disruption on 214); the second is rate-limited.
        val client = object : HubLinesClient() {
            var failSecond = true
            override fun answer(lineIds: Collection<String>): List<LineStatus> = when {
                "lioness" in lineIds && failSecond -> throw TflException.RateLimited(null)
                else -> lineIds.map { if (it == "214") LineStatus(it, 6, "Severe Delays") else LineStatus(it, 10, "Good Service") }
            }
        }
        // A reuse window, so the refresh asks only about lines without a fresh verdict.
        val vm = MainViewModel(client, listOf(seeds[0]), clock = { now }, io = dispatcher, lineStatusReuse = LINE_STATUS_REUSE)
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertEquals("Severe Delays", state.lineStatuses.getValue("214").description)
        // The failed part still reads unchecked.
        assertTrue(state.disruptionUnknown)

        // Not an omission: the next refresh asks the failed part again, and only that part.
        client.failSecond = false
        vm.refresh()
        advanceUntilIdle()
        val retried = client.statusCalls.drop(2).flatten()
        assertTrue("lioness" in retried)
        assertFalse("214" in retried)
        assertFalse((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)
    }

    @Test
    fun `an empty answer before a failed request still counts as answered`() = runTest(dispatcher) {
        // TfL answers the first request with no statuses, then rate-limits the second.
        val client = object : HubLinesClient() {
            override fun answer(lineIds: Collection<String>): List<LineStatus> =
                if ("lioness" in lineIds) throw TflException.RateLimited(null) else emptyList()
        }
        val vm = MainViewModel(client, listOf(seeds[0]), clock = { now }, io = dispatcher, lineStatusReuse = LINE_STATUS_REUSE)
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)

        // The empty answer is an omission, not re-asked within the window; the failed part is.
        vm.refresh()
        advanceUntilIdle()
        val retried = client.statusCalls.drop(2).flatten()
        assertTrue("lioness" in retried)
        assertFalse("214" in retried)
    }

    @Test
    fun `lines checked and stops looked up before the clock was set back are reused at their real age`() = runTest(dispatcher) {
        var offset = Duration.ZERO
        SteadyClock.source = object : SteadyClock.Source {
            override val frame: SteadyClock.Frame? = null
            override fun offset(): Duration = offset
        }
        try {
            val client = object : HubLinesClient() {
                var closureCalls = 0
                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    closureCalls++
                    return emptyList()
                }
            }
            var wall = now
            val vm = MainViewModel(
                client, listOf(seeds[0]), clock = { wall }, io = dispatcher,
                lineStatusReuse = LINE_STATUS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            )
            advanceUntilIdle()
            val statusCalls = client.statusCalls.size
            val closureCalls = client.closureCalls
            // Thirty seconds on, the clock is set back an hour: both were made thirty seconds ago, so
            // neither is asked again, where the wall clock would date them an hour in the future.
            offset = Duration.ofHours(1)
            wall = now.plusSeconds(30).minus(Duration.ofHours(1))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(statusCalls, client.statusCalls.size)
            assertEquals(closureCalls, client.closureCalls)
        } finally {
            SteadyClock.source = null
        }
    }

    // A client that records every arrivals fetch, so a test can assert which stops a fetch asked for.
    private class CountingClient(private val byStop: Map<String, List<Departure>>) : TflClient {
        val arrivalsCalls = mutableListOf<String>()
        override suspend fun arrivals(stopId: String): List<Departure> {
            arrivalsCalls += stopId
            return byStop[stopId] ?: emptyList()
        }
        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
    }

    @Test
    fun `a stop another screen just fetched shows at its own age without a request`() = runTest(dispatcher) {
        val shared = ArrivalsCache()
        val earlier = now.minusSeconds(20)
        shared.put(seeds[0].id, listOf(departure("victoria", "Victoria", 300)), earlier)
        val client = CountingClient(mapOf(seeds[1].id to listOf(departure("northern", "Northern", 200))))
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher, sharedArrivals = shared)
        advanceUntilIdle()
        assertEquals(listOf(seeds[1].id), client.arrivalsCalls)
        val stops = (vm.state.value as DeparturesUiState.Loaded).stops.associateBy { it.stopId }
        // Stamped when it was fetched, never passed off as fetched now.
        assertEquals(earlier, stops.getValue(seeds[0].id).fetchedAt)
        assertEquals(listOf("victoria"), stops.getValue(seeds[0].id).departures.map { it.lineId })
        assertEquals(now, stops.getValue(seeds[1].id).fetchedAt)
    }

    @Test
    fun `a newer fetch by another screen replaces the list's own recent one`() = runTest(dispatcher) {
        var current = now
        val shared = ArrivalsCache()
        val client = CountingClient(emptyMap())
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher, sharedArrivals = shared, arrivalsReuse = ARRIVALS_REUSE,
        )
        advanceUntilIdle()
        // Another screen pulls, fetching the stop afresh 10 s on.
        shared.put(seeds[0].id, listOf(departure("victoria", "Victoria", 300)), now.plusSeconds(10))
        current = now.plusSeconds(20)
        vm.refresh()
        advanceUntilIdle()
        val stop = (vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == seeds[0].id }
        assertEquals(now.plusSeconds(10), stop.fetchedAt)
        assertEquals(listOf("victoria"), stop.departures.map { it.lineId })
        // Neither stop was asked for again: one carried over, one from the newer shared fetch.
        assertEquals(2, client.arrivalsCalls.size)
    }

    @Test
    fun `a pull-to-refresh asks for every stop, however recent`() = runTest(dispatcher) {
        val shared = ArrivalsCache()
        shared.put(seeds[0].id, emptyList(), now.minusSeconds(20))
        val client = CountingClient(emptyMap())
        val vm = MainViewModel(
            client, seeds, clock = { now }, io = dispatcher, sharedArrivals = shared, arrivalsReuse = ARRIVALS_REUSE,
        )
        advanceUntilIdle()
        assertEquals(listOf(seeds[1].id), client.arrivalsCalls)
        // A refresh within the minute (the crosshairs, a return to the app) reuses them all...
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(seeds[1].id), client.arrivalsCalls)
        // ...a pull asks afresh for each.
        vm.forceNextFetch()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf(seeds[0].id, seeds[1].id), client.arrivalsCalls.drop(1).toSet())
        assertEquals(3, client.arrivalsCalls.size)
    }

    @Test
    fun `a relocation that drops a cluster prunes its stop before the refetch`() = runTest(dispatcher) {
        val client = twoStopClient()
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("MA", "MA")), emptyList())
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))
        val fetches = client.arrivalsCalls.size

        // Walk on: the fresh fix no longer offers MA's cluster. The departed stop must leave the shown list
        // before the re-fetch runs, so it can't linger with stale departures.
        var fetchesAtPrune = -1
        val pruned = firstStateAfter(vm, onSet = { fetchesAtPrune = client.arrivalsCalls.size }) {
            vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList())
        } as DeparturesUiState.Loaded
        assertEquals(listOf("E"), pruned.stops.map { it.stopId })
        assertEquals("no stop fetched before the prune", fetches, fetchesAtPrune)
        advanceUntilIdle()
        assertTrue("MA" !in shownIds(vm))
    }

    @Test
    fun `a relocation's prune keeps naming a kept stop that failed`() = runTest(dispatcher) {
        var failE = false
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "E" && failE) throw TflException.Offline(null)
                return listOf(departure("central", "Central", 200))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("MA", "MA")), emptyList())
        advanceUntilIdle()
        // E's next refresh fails, so it is kept at its older age and named.
        failE = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("E"), (vm.state.value as DeparturesUiState.Loaded).partialStops.values.map { it.name })

        // MA departs; E, still failing, stays named through the pending re-fetch.
        val pruned = firstStateAfter(vm) { vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList()) }
            as DeparturesUiState.Loaded
        assertTrue(pruned.partialRefresh)
        assertTrue("the replacement fetch is pending", pruned.partialUnnamed)
        assertEquals(listOf("E"), pruned.partialStops.values.map { it.name })
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, pruned.partialReason)
    }

    @Test
    fun `a relocation's prune keeps naming a still-fetched stop that never loaded`() = runTest(dispatcher) {
        val client = FakeClient(
            mapOf(
                "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                "F" to Result.failure(TflException.Offline(null)),
                "MA" to Result.success(listOf(departure("central", "Central", 200))),
            ),
        )
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("F", "F"), StopRef("MA", "MA")), emptyList())
        advanceUntilIdle()
        assertEquals(listOf("F"), (vm.state.value as DeparturesUiState.Loaded).partialStops.values.map { it.name })

        // MA departs; F has no row, but it is still fetched and still failing, so it stays named.
        val pruned = firstStateAfter(vm) { vm.reconcile(newEager = eagerOf("E" to "bus", "F" to "bus"), newMore = emptyList()) }
            as DeparturesUiState.Loaded
        assertEquals(listOf("F"), pruned.partialStops.values.map { it.name })
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, pruned.partialReason)
    }

    @Test
    fun `a relocation's prune names retained failures nearest the new fix first`() = runTest(dispatcher) {
        val client = FakeClient(
            mapOf(
                "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                "F" to Result.failure(TflException.Offline(null)),
                "G" to Result.failure(TflException.Offline(null)),
                "MA" to Result.success(listOf(departure("central", "Central", 200))),
            ),
        )
        val vm = MainViewModel(
            client,
            listOf(StopRef("E", "E"), StopRef("F", "F"), StopRef("G", "G"), StopRef("MA", "MA")),
            clock = { now },
            io = dispatcher,
            stopDistanceMeters = mapOf("E" to 50.0, "F" to 100.0, "G" to 300.0),
        )
        advanceUntilIdle()
        assertEquals(listOf("F", "G"), (vm.state.value as DeparturesUiState.Loaded).partialStops.values.map { it.name })

        // Walk on: G is now the nearer of the two, and MA departs.
        val pruned = firstStateAfter(vm) {
            vm.reconcile(
                newEager = eagerOf("E" to "bus", "F" to "bus", "G" to "bus"),
                newMore = emptyList(),
                newDistanceMeters = mapOf("E" to 50.0, "F" to 300.0, "G" to 100.0),
            )
        } as DeparturesUiState.Loaded
        assertEquals(listOf("G", "F"), pruned.partialStops.values.map { it.name })
    }

    @Test
    fun `a cluster losing a member prunes it before the refetch`() = runTest(dispatcher) {
        val client = FakeClient(
            mapOf(
                "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                "PA" to Result.success(listOf(departure("central", "Central", 200))),
                "PB" to Result.success(listOf(departure("central", "Central", 260))),
            ),
        )
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("PA", "PA"), StopRef("PB", "PB")), emptyList())
        advanceUntilIdle()
        assertTrue("PA" in shownIds(vm) && "PB" in shownIds(vm))

        // The same cluster (same key) now has only PA — PB's pole crossed the radius. PB leaves at
        // once; PA stays. Same-key clusters whose MEMBERS changed are reconciled, not just dropped.
        val pruned = firstStateAfter(vm) {
            vm.reconcile(newEager = eagerOf("E" to "bus") + clusterOf("M1", "PA" to "bus"), newMore = emptyList())
        } as DeparturesUiState.Loaded
        assertTrue("PB" !in pruned.stops.map { it.stopId })
        advanceUntilIdle()
        assertTrue("PA" in shownIds(vm))
        assertTrue("PB" !in shownIds(vm))
    }

    @Test
    fun `a same-set reconcile is worked out on the worker, and a refresh meanwhile waits for it`() = runTest(dispatcher) {
        // AGENTS.md *Main thread*: the reconcile's passes over the tiers and the shown stops wait for the
        // worker. Until it has run nothing is pruned or fetched, and a refresh asked for meanwhile
        // doesn't fetch the old tiers: the reconcile's own refresh fetches the new ones, once.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        var current = now
        val client = twoStopClient()
        val vm = MainViewModel(
            client, listOf(StopRef("E", "E"), StopRef("MA", "MA")), clock = { current }, io = dispatcher, compute = worker,
        )
        advanceUntilIdle()
        // Long enough on that nothing fetched is reused.
        current = now.plusSeconds(600)
        val fetches = client.arrivalsCalls.size

        holding = true
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList())
        vm.refresh()
        advanceUntilIdle()
        assertTrue("nothing pruned until the worker has run", "MA" in shownIds(vm))
        assertEquals("nor fetched", fetches, client.arrivalsCalls.size)
        assertTrue("but it shows as under way", vm.refreshing.value)

        holding = false
        held.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        held.clear()
        advanceUntilIdle()
        assertEquals(listOf("E"), shownIds(vm))
        assertEquals("one refetch, of the new tiers", listOf("E"), client.arrivalsCalls.drop(fetches))
        assertFalse(vm.refreshing.value)
    }

    @Test
    fun `a journey origin the screen reports while a reconcile is on the worker is kept`() = runTest(dispatcher) {
        // The fix holds the journey back, but before its reconcile lands the rider reveals it and the
        // screen reports its origin: that report is newer than the fix's checks, so the origin stays
        // and the reconcile's refresh fetches it.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()

        holding = true
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), dropJourneyStopIds = setOf(ksxId))
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        holding = false
        held.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        held.clear()
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[ksxId])
        assertTrue(ksxId in shownIds(vm))
    }

    @Test
    fun `a new relocation drops a reconcile still on the worker`() = runTest(dispatcher) {
        // The reconcile is for the fix the new relocation replaces: it neither prunes nor fetches.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        val client = twoStopClient()
        val vm = MainViewModel(client, listOf(StopRef("E", "E"), StopRef("MA", "MA")), clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        val fetches = client.arrivalsCalls.size

        holding = true
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList())
        vm.cancelFetch()
        holding = false
        held.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        held.clear()
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))
        assertEquals(fetches, client.arrivalsCalls.size)
        assertFalse(vm.refreshing.value)
    }

    @Test
    fun `a fetch finishing while a reconcile is on the worker leaves it under way`() = runTest(dispatcher) {
        // Holds the one thing handed to it next (the reconcile's plan), and runs the rest at once.
        var held: Pair<CoroutineContext, Runnable>? = null
        var holdNext = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holdNext) {
                    holdNext = false
                    held = context to block
                } else {
                    dispatcher.dispatch(context, block)
                }
            }
        }
        var current = now
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { current }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        current = now.plusSeconds(600)
        val gate = CompletableDeferred<Unit>()
        client.arrivalsGate = gate
        vm.refresh()
        runCurrent()

        // The reconcile goes to the worker while that fetch is still out; the fetch then finishes.
        holdNext = true
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList())
        runCurrent()
        assertNotNull("the plan is on the worker", held)
        client.arrivalsGate = null
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("the reconcile is still under way", vm.refreshing.value)

        held!!.let { (context, block) -> dispatcher.dispatch(context, block) }
        advanceUntilIdle()
        assertFalse(vm.refreshing.value)
    }

    @Test
    fun `a journey report for a superseded reconcile doesn't settle the newer one's wait`() = runTest(dispatcher) {
        // Two same-set fixes back to back, both waiting on the screen's journey report. The report that
        // came while the first was on the worker was for the first fix: the second still waits for its own.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        val nearFetches = client.arrivalCalls.getValue(oxcId)

        holding = true
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), awaitJourneyStops = true)
        advanceUntilIdle()
        vm.journeyStopsReported()
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), awaitJourneyStops = true)
        holding = false
        held.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        held.clear()
        advanceUntilIdle()
        assertEquals("nothing fetched before the newer fix's report", nearFetches, client.arrivalCalls[oxcId])
        assertFalse(vm.refreshing.value)

        vm.journeyStopsReported()
        advanceUntilIdle()
        assertEquals(nearFetches + 1, client.arrivalCalls[oxcId])
    }

    @Test
    fun `a journey report held for stops on the worker doesn't settle a newer reconcile's wait`() = runTest(dispatcher) {
        // The screen's journey stops are on the worker when it says it's reported them, during a fix
        // that waits on that report. A newer fix starts before they're back: the held report was for the
        // first, so the newer one still waits for its own.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        val nearFetches = client.arrivalCalls.getValue(oxcId)

        holding = true
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), awaitJourneyStops = true)
        vm.setJourneyStops(emptyList())
        advanceUntilIdle()
        vm.journeyStopsReported()
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), awaitJourneyStops = true)
        holding = false
        held.forEach { (context, block) -> dispatcher.dispatch(context, block) }
        held.clear()
        advanceUntilIdle()
        assertEquals("nothing fetched before the newer fix's report", nearFetches, client.arrivalCalls[oxcId])
        assertFalse(vm.refreshing.value)

        vm.journeyStopsReported()
        advanceUntilIdle()
        assertEquals(nearFetches + 1, client.arrivalCalls[oxcId])
    }

    @Test
    fun `journey stops reported before a fix don't bring back an origin it holds back`() = runTest(dispatcher) {
        // The screen reports a journey origin; while that's on the worker a fix comes in that holds the
        // origin back. The fix's reconcile, worked out first, drops it; the report's pass, returning after,
        // doesn't bring it back.
        val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        var holding = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (holding) held += context to block else dispatcher.dispatch(context, block)
            }
        }
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, compute = worker)
        advanceUntilIdle()
        val origin = StopRef("940GZZLUKSX", "King's Cross St. Pancras")

        holding = true
        vm.setJourneyStops(listOf(origin))
        advanceUntilIdle()
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), dropJourneyStopIds = setOf(origin.id))
        advanceUntilIdle()
        holding = false
        held.reversed().forEach { (context, block) -> dispatcher.dispatch(context, block) }
        held.clear()
        advanceUntilIdle()
        assertEquals(null, client.arrivalCalls[origin.id])
    }

    @Test
    fun `a same-set reconcile goes through the new tiers on the worker thread`() = runTest(dispatcher) {
        // The new tiers, in a list that notes each thread going through it, are gone through on the
        // worker, never on the main thread the reconcile is asked on.
        val worker = TestWorker("reconcile-worker")
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
            val newEager = NotingList(eagerOf("E" to "bus"), reads)
            val client = twoStopClient()
            val vm = androidx.lifecycle.ViewModelProvider.create(
                store,
                androidx.lifecycle.viewmodel.viewModelFactory {
                    initializer {
                        MainViewModel(client, listOf(StopRef("E", "E"), StopRef("MA", "MA")), clock = { now }, io = worker, compute = worker)
                    }
                },
            )[MainViewModel::class]
            vm.state.first { it is DeparturesUiState.Loaded && it.pendingStops.isEmpty() }
            vm.reconcile(newEager = newEager, newMore = emptyList())
            // Let the worker and Main hand the reconcile back and forth until it has landed.
            repeat(50) {
                if ("MA" !in shownIds(vm)) return@repeat
                worker.flush()
                advanceUntilIdle()
            }
            assertEquals(listOf("E"), shownIds(vm))
            assertTrue(reads.isNotEmpty())
            assertEquals(setOf("reconcile-worker"), reads.toSet())
        } finally {
            store.clear()
            worker.close(this)
        }
    }

    @Test
    fun `a relocation's farther cards are re-measured on the worker thread`() = runTest(dispatcher) {
        // The offered places, in a list that notes each thread going through it, are gone through on
        // the worker, never on the main thread the relocation is reported on.
        val worker = TestWorker("retain-worker")
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val cards = androidx.lifecycle.ViewModelProvider.create(
                store,
                androidx.lifecycle.viewmodel.viewModelFactory {
                    initializer {
                        FartherCardsViewModel(
                            stationStops = { listOf(fartherStop) },
                            newModel = { stops, distances ->
                                MainViewModel(twoStopClient(), stops, clock = { now }, io = dispatcher, stopDistanceMeters = distances)
                            },
                            io = dispatcher,
                            compute = worker,
                        )
                    }
                },
            )[FartherCardsViewModel::class]
            cards.open(fartherPlace, Coordinates(0.0, 0.0))
            repeat(50) {
                if (cards.picked.value.loads[fartherPlace.key] is FartherLoad.Open) return@repeat
                worker.flush()
                advanceUntilIdle()
            }
            val opened = cards.picked.value.loads[fartherPlace.key] as FartherLoad.Open
            assertEquals(0.0, opened.distanceMeters.getValue("MA"), 0.001)
            val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
            cards.retain(NotingList(listOf(fartherPlace), reads), Coordinates(0.001, 0.0))
            // Nothing is worked out on the caller's thread: the card stands as it was until the worker answers.
            assertSame(opened, cards.picked.value.loads[fartherPlace.key])
            repeat(50) {
                if (cards.picked.value.loads[fartherPlace.key] !== opened) return@repeat
                worker.flush()
                advanceUntilIdle()
            }
            val moved = cards.picked.value.loads[fartherPlace.key] as FartherLoad.Open
            assertTrue("measured from the new fix: ${moved.distanceMeters}", moved.distanceMeters.getValue("MA") > 100.0)
            assertTrue(reads.isNotEmpty())
            assertEquals(setOf("retain-worker"), reads.toSet())
        } finally {
            store.clear()
            worker.close(this)
        }
    }

    @Test
    fun `a farther card's model takes a new fix's places on the worker thread`() = runTest(dispatcher) {
        // The model's nearby stops, in a list that notes each thread going through it, are gone
        // through on the worker when a new fix re-measures them.
        val worker = TestWorker("remeasure-worker")
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val reads = java.util.Collections.synchronizedList(mutableListOf<String>())
            val stops = NotingList(listOf(StopRef("E", "E"), StopRef("MA", "MA")), reads)
            val vm = androidx.lifecycle.ViewModelProvider.create(
                store,
                androidx.lifecycle.viewmodel.viewModelFactory {
                    initializer {
                        MainViewModel(
                            twoStopClient(), stops, clock = { now }, io = worker, compute = worker,
                            stopDistanceMeters = mapOf("E" to 100.0, "MA" to 900.0),
                        )
                    }
                },
            )[MainViewModel::class]
            vm.state.first { it is DeparturesUiState.Loaded && it.pendingStops.isEmpty() }
            // The first refresh done, with nothing left of it to run: its own setup goes through the
            // stops on the main thread still (TODO.md), and it isn't what's checked here.
            var rounds = 0
            while (vm.refreshing.value && rounds++ < 50) {
                worker.flush()
                advanceUntilIdle()
            }
            worker.flush()
            advanceUntilIdle()
            assertFalse(vm.refreshing.value)
            reads.clear()
            vm.remeasure(mapOf("E" to 900.0, "MA" to 100.0))
            worker.flush()
            advanceUntilIdle()
            worker.flush()
            advanceUntilIdle()
            assertEquals(mapOf("E" to 900.0, "MA" to 100.0), vm.distanceMeters)
            assertTrue(reads.isNotEmpty())
            assertEquals(setOf("remeasure-worker"), reads.toSet())
        } finally {
            store.clear()
            worker.close(this)
        }
    }

    /**
     * A worker on a real thread named [name], for a test that checks work runs off the caller's.
     * [close] stops it only once nothing is left to hop between it and the test's queue: shutting it
     * down sooner rejects a hop still to come, which kotlinx reruns on [Dispatchers.IO], where nothing
     * waits for it, so it can reach Main after the test has reset it and fail whichever test is next.
     */
    private class TestWorker(name: String) : CoroutineDispatcher() {
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, name) }
        private val inner = executor.asCoroutineDispatcher()
        private val dispatched = java.util.concurrent.atomic.AtomicInteger()
        private val late = java.util.concurrent.atomic.AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (executor.isShutdown) late.incrementAndGet()
            dispatched.incrementAndGet()
            inner.dispatch(context, block)
        }

        /** Waits until everything handed to the worker so far has run. */
        fun flush() {
            executor.submit {}.get()
        }

        /** Hands work back and forth with [scope]'s queue until a round hands the worker none, then stops it. */
        fun close(scope: TestScope) {
            do {
                val before = dispatched.get()
                scope.advanceUntilIdle()
                flush()
                scope.advanceUntilIdle()
            } while (dispatched.get() != before)
            executor.shutdown()
            check(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) { "worker didn't stop" }
            check(late.get() == 0) { "work reached the worker after it stopped" }
        }
    }

    /** [items], noting the thread of each pass over it in [reads]. */
    private class NotingList<T>(private val items: List<T>, private val reads: MutableList<String>) : AbstractList<T>() {
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
        override fun iterator(): Iterator<T> {
            reads += Thread.currentThread().name.substringBefore(" @")
            return items.iterator()
        }
    }

    @Test
    fun `a pruned set is persisted even when the refetch gets no fresh arrivals`() = runTest(dispatcher) {
        val store = FakeStore()
        val client = twoStopClient()
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("MA", "MA")), emptyList(), store)
        advanceUntilIdle()
        assertEquals(setOf("E", "MA"), store.stored!!.stops.map { it.stopId }.toSet())

        // The follow-up refetch fails for every remaining stop (non-authoritative), yet the departed
        // stop is gone from disk anyway — the removal happens at prune time, independent of the
        // refetch's save, so a dropped stop can't linger for the widget worker to poll.
        client.failing += "E"
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList())
        advanceUntilIdle()
        assertEquals(listOf("E"), store.stored!!.stops.map { it.stopId })
    }

    @Test
    fun `a shrink to an error state removes the departed stops from disk`() = runTest(dispatcher) {
        // The whole eager cluster's members change (OLD leaves, NEW arrives). NEW's arrivals AND
        // disruptions both fail and it has no prior or declared lines, so mergeStop emits nothing:
        // merged is empty and the refetch yields an Error, not a Loaded. OLD must still leave disk —
        // the prune-time removal is independent of the refetch's (skipped) save, so OLD can't linger
        // for the widget worker to poll as current (D4).
        val store = FakeStore()
        val client = FakeClient(
            byStop = mapOf("OLD" to Result.success(listOf(departure("victoria", "Victoria", 300)))),
            // NEW is absent from byStop, so its arrivals throw; its disruptions fail too.
            disruptionsByStop = mapOf("NEW" to Result.failure(RuntimeException("disruption failed"))),
        )
        val vm = tierVm(client, listOf(StopRef("OLD", "OLD")), emptyList(), store)
        advanceUntilIdle()
        assertEquals(listOf("OLD"), store.stored!!.stops.map { it.stopId })

        val newEager = listOf(
            NearbySelection.NearbyCluster("ec:NEW", listOf(StopLocation("NEW", "NEW", 0.0, 0.0)), 0.0),
        )
        vm.reconcile(newEager = newEager, newMore = emptyList())
        advanceUntilIdle()
        assertTrue(vm.state.value is DeparturesUiState.Error)
        assertTrue("OLD is cleared from disk, not left for the widget worker", store.stored!!.stops.isEmpty())
    }

    @Test
    fun `a departed stop leaves the widget snapshot even if the refetch save fails`() = runTest(dispatcher) {
        // The prune-time removal is independent of the refetch's save. Drop MA while E still
        // succeeds (so the refetch IS authoritative and does try to save) but make that save throw:
        // MA must still be gone from disk, because it was removed directly at prune time, not by the
        // save. Under the old flag design a failed save stranded MA until a later retry.
        val store = FakeStore()
        val client = twoStopClient()
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("MA", "MA")), emptyList(), store)
        advanceUntilIdle()
        assertEquals(setOf("E", "MA"), store.stored!!.stops.map { it.stopId }.toSet())

        store.failSaves = 1
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList())
        advanceUntilIdle()
        assertEquals(listOf("E"), store.stored!!.stops.map { it.stopId })
    }

    @Test
    fun `pruning every shown stop shows loading, not a trusted empty, until the refetch returns`() =
        runTest(dispatcher) {
            // The whole set's stops change and the replacement's fetch hangs. Every prior stop
            // departs, so `kept` is empty — the screen must show the loading placeholder, not a
            // trusted "No departures" (partialRefresh=false, recent stamp) through the unfinished
            // fetch of stops that haven't been checked yet (SPEC principle 2; Codex).
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (stopId == "NEW") gate.await()
                    return listOf(departure("victoria", "Victoria", 300))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val vm = tierVm(client, listOf(StopRef("E", "E")), emptyList())
            advanceUntilIdle()
            assertEquals(listOf("E"), shownIds(vm))

            val newEager = listOf(
                NearbySelection.NearbyCluster("ec:NEW", listOf(StopLocation("NEW", "NEW", 0.0, 0.0)), 0.0),
            )
            vm.reconcile(newEager = newEager, newMore = emptyList())
            advanceUntilIdle()
            // The refetch is still in flight (NEW hangs) and every prior stop departed — Loading,
            // not a trusted empty Loaded.
            assertTrue(vm.state.value is DeparturesUiState.Loading)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("NEW"), shownIds(vm))
        }

    // --- Reuse: a quick retry refetches only what's missing; a closure check is reused a while ---

    /** A client counting each stop's requests; stops in [failingArrivals] / [failingDisruptions] fail. */
    private inner class ReuseCountingClient : TflClient {
        val arrivalCalls = mutableMapOf<String, Int>()
        val disruptionCalls = mutableMapOf<String, Int>()
        val failingArrivals = mutableSetOf<String>()
        val failingDisruptions = mutableSetOf<String>()
        // A stop's reported closures, returned by its closure check; none by default.
        val closures = mutableMapOf<String, List<StopDisruption>>()

        // Held open while set, so a test can act with a fetch in flight.
        var arrivalsGate: CompletableDeferred<Unit>? = null

        // When part of a stop's arrivals was fetched, if before the ask: a National Rail board
        // another screen fetched, say ([TflClient.fetchedAt]).
        val partFetchedAt = mutableMapOf<String, Instant>()

        override fun fetchedAt(stopId: String): Instant? = partFetchedAt[stopId]

        // Whether each fetch of a stop wanted its National Rail board, in order.
        val railBoards = mutableMapOf<String, MutableList<Boolean>>()

        override suspend fun arrivals(stopId: String, railBoard: Boolean): List<Departure> {
            railBoards.getOrPut(stopId) { mutableListOf() } += railBoard
            return arrivals(stopId)
        }

        // A National Rail station's arrivals can carry a board; no other stop's can.
        override fun hasRailBoard(stopId: String): Boolean = stopId.startsWith("910G")

        override suspend fun arrivals(stopId: String): List<Departure> {
            arrivalCalls.merge(stopId, 1) { a, b -> a + b }
            arrivalsGate?.await()
            if (stopId in failingArrivals) throw TflException.RateLimited(null)
            return listOf(departure("victoria", "Victoria", 300))
        }

        // Each line-status request's lines; [statuses] answers them, [failingStatus] fails them.
        val statusCalls = mutableListOf<Set<String>>()
        var statuses = emptyList<LineStatus>()
        var failingStatus = false
        var unknownStatus = false

        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            statusCalls += lineIds.toSet()
            if (failingStatus) throw TflException.RateLimited(null)
            if (unknownStatus) throw TflException.NotFound(null)
            return statuses.filter { it.lineId in lineIds }
        }

        // Held open while set, so a test can act with a closure check in flight.
        var disruptionsGate: CompletableDeferred<Unit>? = null

        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
            disruptionCalls.merge(stopId, 1) { a, b -> a + b }
            disruptionsGate?.await()
            if (stopId in failingDisruptions) throw TflException.RateLimited(null)
            return closures[stopId].orEmpty()
        }
    }

    @Test
    fun `a change of departure source refetches every stop at once, none carried over`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val key = MutableStateFlow<String?>(null)
        val shared = ArrivalsCache()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, departureSourceChanges = key, sharedArrivals = shared,
        )
        advanceUntilIdle()
        val stop = seeds.first().id
        assertEquals(1, client.arrivalCalls[stop])
        // Another screen's fetch under the old source doesn't stand in for it either.
        shared.put(stop, emptyList(), now)
        // A refresh moments later carries the stop over.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[stop])
        // A key pasted in Settings refetches it at once, reuse window or not.
        key.value = "EXAMPLE"
        advanceUntilIdle()
        assertEquals(2, client.arrivalCalls[stop])
        assertTrue(vm.state.value is DeparturesUiState.Loaded)
        assertNull(shared.get(stop, now))
    }

    private val oxcId = "940GZZLUOXC"
    private val ksxId = "940GZZLUKSX"

    @Test
    fun `a same-set reconcile doesn't fetch journey stops the new fix holds back`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[ksxId])
        val nearFetches = client.arrivalCalls.getValue(oxcId)

        // A relocate keeps the set but takes the journey past a mile: the refresh it starts fetches
        // the near stop again, but not the journey's origin.
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), dropJourneyStopIds = setOf(ksxId))
        advanceUntilIdle()
        assertEquals(nearFetches + 1, client.arrivalCalls[oxcId])
        assertEquals(1, client.arrivalCalls[ksxId])
    }

    @Test
    fun `a same-set reconcile awaiting journey stops refreshes once, with them`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        val nearFetches = client.arrivalCalls.getValue(oxcId)

        // A relocate brings a held-back journey in range: nothing is fetched until the screen reports.
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), awaitJourneyStops = true)
        advanceUntilIdle()
        assertEquals(nearFetches, client.arrivalCalls[oxcId])

        // The screen reports the journey's origin, then that it's done: one refresh, origin included.
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        vm.journeyStopsReported()
        advanceUntilIdle()
        assertEquals(nearFetches + 1, client.arrivalCalls[oxcId])
        assertEquals(1, client.arrivalCalls[ksxId])
    }

    @Test
    fun `an awaited refresh still runs when the report adds no stop`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        val nearFetches = client.arrivalCalls.getValue(oxcId)

        // A bus journey whose origin isn't placed yet: the screen's report changes no stop.
        vm.reconcile(newEager = eagerOf(oxcId to "tube"), newMore = emptyList(), awaitJourneyStops = true)
        vm.journeyStopsReported()
        advanceUntilIdle()
        assertEquals(nearFetches + 1, client.arrivalCalls[oxcId])
        // Nothing left waiting: a later report doesn't refresh again.
        vm.journeyStopsReported()
        advanceUntilIdle()
        assertEquals(nearFetches + 1, client.arrivalCalls[oxcId])
    }

    @Test
    fun `a destination the refresh also fetches is checked once`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        // One journey's far end is another's origin, fetched with the near-me stops.
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        assertEquals(1, client.disruptionCalls[ksxId])
    }

    @Test
    fun `a closure joined from a destination's lookup isn't counted as this fetch's request`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val logged = mutableListOf<String>()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher,
            elapsedMillis = { 0L }, logStats = { logged += it },
        )
        advanceUntilIdle()
        // A trip's destination is the list's own stop: its lookup is out when the list refreshes.
        val gate = CompletableDeferred<Unit>()
        client.disruptionsGate = gate
        vm.setJourneyDestinations(listOf(seeds.first()))
        runCurrent()
        logged.clear()
        vm.refresh()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        // The refresh joined the destination's lookup rather than ask again, so it counts none.
        assertTrue(logged.joinToString(), logged.any { "(1 departures, 0 closure," in it })
    }

    @Test
    fun `a destination added mid-check doesn't re-ask the ones in flight`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        client.disruptionsGate = gate
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        runCurrent()
        // A second route comes in while the first check is waiting on TfL.
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras"), StopRef(oxcId, "Oxford Circus")))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, client.disruptionCalls[ksxId])
        assertEquals(1, client.disruptionCalls[oxcId])
        assertEquals(setOf(ksxId, oxcId), vm.journeyDestinationStops.value.mapTo(HashSet()) { it.stopId })
    }

    @Test
    fun `a destination lookup landing after a trip's newer one shows the newer`() = runTest(dispatcher) {
        val shared = StopClosureCache()
        val closed = listOf(StopDisruption("Station closed until further notice"))
        var clockNow = now
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = listOf(departure("victoria", "Victoria", 300))
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (stopId == ksxId) {
                    // A trip asks after this lookup and is answered first; this one lands later still.
                    shared.keep(stopId, shared.ask(now.plusSeconds(1)), closed)
                    clockNow = now.plusSeconds(2)
                }
                return emptyList()
            }
        }
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { clockNow }, io = dispatcher, disruptionCache = shared)
        advanceUntilIdle()
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        // Stamped as asked, the older lookup doesn't replace the trip's.
        assertEquals(now.plusSeconds(1) to closed, shared[ksxId]?.let { it.at to it.notices })
        assertEquals(closed, vm.journeyDestinationStops.value.single { it.stopId == ksxId }.disruptions)
    }

    @Test
    fun `a refresh during a destination check waits on its request rather than ask again`() =
        refreshDuringDestinationCheck(failing = false)

    @Test
    fun `a destination request the refresh waited on that fails isn't asked again at once`() =
        refreshDuringDestinationCheck(failing = true)

    private fun refreshDuringDestinationCheck(failing: Boolean) = runTest(dispatcher) {
        val client = ReuseCountingClient()
        if (failing) client.failingDisruptions += ksxId
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        client.disruptionsGate = gate
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        runCurrent()
        // The same stop becomes a journey's origin, fetched by the refresh, while its check waits on TfL.
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, client.disruptionCalls[ksxId])
    }

    @Test
    fun `an abandoned destination check's failure is still logged`() = runTest(dispatcher) {
        val warnings = mutableListOf<String>()
        val client = ReuseCountingClient()
        client.failingDisruptions += ksxId
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher, warn = { warnings += it },
        )
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        client.disruptionsGate = gate
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        runCurrent()
        // The journey is unstarred while its check is in flight.
        vm.setJourneyDestinations(emptyList())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, warnings.count { it.startsWith("destination disruption fetch failed for stop $ksxId") })
    }

    @Test
    fun `a destination the refresh failed to check isn't asked again at once`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        client.failingDisruptions += ksxId
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        // One attempt, the refresh's, and the card says it couldn't check.
        assertEquals(1, client.disruptionCalls[ksxId])
        assertEquals(setOf(ksxId), vm.journeyDestinationsUnknown.value)
    }

    @Test
    fun `a destination whose first check fails is unknown, and a cleared closure's dismissal is forgotten`() =
        runTest(dispatcher) {
            val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
            val store = object : DismissedAlertsStore {
                override fun dismissed() = backing
                override suspend fun dismiss(alert: DismissedAlert) {
                    backing.value = Dismissed.dismiss(backing.value, alert)
                }
                override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                    backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
                }
            }
            // A closure dismissed elsewhere in a stop area the destination check never covers.
            val elsewhere = DismissedAlert(alertKey = "490G00000001", contentSignature = "Bus Stop Closed")
            backing.value = setOf(elsewhere)
            val client = ReuseCountingClient()
            client.failingDisruptions += ksxId
            val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, dismissedStore = store)
            advanceUntilIdle()
            val destination = StopRef(ksxId, "King's Cross St. Pancras")
            vm.setJourneyDestinations(listOf(destination))
            advanceUntilIdle()
            // Nothing known: unknown, not passed off as open.
            assertEquals(setOf(ksxId), vm.journeyDestinationsUnknown.value)
            assertTrue(vm.journeyDestinationStops.value.isEmpty())

            // It comes back closed; the user dismisses it.
            client.failingDisruptions -= ksxId
            client.closures[ksxId] = listOf(StopDisruption("Station closed"))
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.journeyDestinationsUnknown.value.isEmpty())
            val closure = DepartureRows.across(vm.journeyDestinationStops.value, now).single { it.stopDisruption != null }
            vm.dismissAlert(closure)
            advanceUntilIdle()
            assertEquals(2, backing.value.size)

            // It clears: the dismissal is forgotten, so the same notice recurring later shows again; the
            // unchecked area's dismissal is left alone.
            client.closures.remove(ksxId)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(setOf(elsewhere), backing.value)
        }

    @Test
    fun `an older destination check settling after a newer refresh found a closure back keeps its dismissal`() =
        runTest(dispatcher) {
            val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
            val store = object : DismissedAlertsStore {
                override fun dismissed() = backing
                override suspend fun dismiss(alert: DismissedAlert) {
                    backing.value = Dismissed.dismiss(backing.value, alert)
                }
                override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                    backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
                }
            }
            // Holds what's handed to it while [holding], as a busy worker would.
            val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
            var holding = false
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (holding) held += context to block else dispatcher.dispatch(context, block)
                }
            }
            // Lets the held work go a round at a time until [done].
            fun letGo(done: () -> Boolean) {
                while (!done() && held.isNotEmpty()) {
                    val next = held.toList()
                    held.clear()
                    for ((context, block) in next) dispatcher.dispatch(context, block)
                    advanceUntilIdle()
                }
            }
            var current = now
            val client = ReuseCountingClient()
            val closed = listOf(StopDisruption("Station closed"))
            client.closures[ksxId] = closed
            val kingsCross = StopRef(ksxId, "King's Cross St. Pancras")
            val vm = MainViewModel(
                client, listOf(seeds.first()), clock = { current }, io = dispatcher, compute = worker,
                disruptionCache = StopClosureCache(), disruptionReuse = DISRUPTION_REUSE, dismissedStore = store,
            )
            advanceUntilIdle()
            // King's Cross is on the board and a journey's far end, with Euston; it's closed, and dismissed.
            vm.setJourneyStops(listOf(kingsCross))
            vm.setJourneyDestinations(listOf(kingsCross, StopRef("940GZZLUEUS", "Euston")))
            advanceUntilIdle()
            val closure = DepartureRows.across(vm.journeyDestinationStops.value, current).single { it.stopDisruption != null }
            vm.dismissAlert(closure)
            advanceUntilIdle()
            val atKsx = backing.value.single()

            // Later, a destination check finds it clear; it's held as it settles.
            current = current.plus(DISRUPTION_REUSE).plusSeconds(1)
            client.closures.remove(ksxId)
            holding = true
            vm.setJourneyDestinations(listOf(kingsCross))
            advanceUntilIdle()
            letGo { vm.journeyDestinationStops.value.singleOrNull()?.disruptions?.isEmpty() == true }
            val destination = held.toList()
            held.clear()

            // A refresh after it finds the closure back and settles; the next destination check it starts
            // is held before it settles.
            current = current.plus(DISRUPTION_REUSE).plusSeconds(1)
            client.closures[ksxId] = closed
            vm.refresh()
            advanceUntilIdle()
            letGo { !vm.refreshing.value }
            assertEquals(setOf(atKsx), backing.value)
            val next = held.toList()
            held.clear()

            // The older destination check settles last: the refresh's newer answer stands, and the dismissal stays.
            held += destination
            letGo { false }
            assertEquals(setOf(atKsx), backing.value)
            held += next
            holding = false
            letGo { false }
            assertEquals(setOf(atKsx), backing.value)
        }

    @Test
    fun `a journey's destination is checked for a closure, reused within the window, and kept through a failure`() =
        runTest(dispatcher) {
            var current = now
            val client = ReuseCountingClient()
            client.closures[ksxId] = listOf(StopDisruption("Station closed"))
            val vm = MainViewModel(
                client, listOf(seeds.first()), clock = { current }, io = dispatcher,
                disruptionReuse = DISRUPTION_REUSE,
            )
            advanceUntilIdle()
            vm.setJourneyDestinations(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
            advanceUntilIdle()
            val checked = vm.journeyDestinationStops.value.single()
            assertEquals(ksxId, checked.stopId)
            assertEquals(listOf(StopDisruption("Station closed")), checked.disruptions)
            // Only its closure is asked for, never its departures.
            assertEquals(null, client.arrivalCalls[ksxId])
            assertEquals(1, client.disruptionCalls[ksxId])

            // A refresh within the reuse window asks again for nothing.
            vm.refresh()
            advanceUntilIdle()
            assertEquals(1, client.disruptionCalls[ksxId])

            // Past it, a failed check keeps the last known closure rather than dropping it.
            current = now.plus(DISRUPTION_REUSE).plusSeconds(1)
            client.failingDisruptions += ksxId
            vm.refresh()
            advanceUntilIdle()
            assertEquals(2, client.disruptionCalls[ksxId])
            assertEquals(listOf(StopDisruption("Station closed")), vm.journeyDestinationStops.value.single().disruptions)
            // And it's unknown now: that closure may be out of date.
            assertEquals(setOf(ksxId), vm.journeyDestinationsUnknown.value)

            // Unstarred: no destination, nothing kept, once worked out on the worker.
            vm.setJourneyDestinations(emptyList())
            advanceUntilIdle()
            assertTrue(vm.journeyDestinationStops.value.isEmpty())
        }

    @Test
    fun `a quick retry after a rate-limited refresh refetches only the stops still missing`() =
        runTest(dispatcher) {
            var current = now
            val client = ReuseCountingClient().apply { failingArrivals += oxcId }
            val vm = MainViewModel(
                client, seeds, clock = { current }, io = dispatcher,
                arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            )
            advanceUntilIdle()
            assertTrue((vm.state.value as DeparturesUiState.Loaded).partialRefresh)

            // Ten seconds later the budget has room again, but not for everything.
            client.failingArrivals.clear()
            current = now.plusSeconds(10)
            vm.refresh()
            advanceUntilIdle()

            assertEquals("the failed stop is retried", 2, client.arrivalCalls[oxcId])
            assertEquals("the stop fetched moments ago isn't", 1, client.arrivalCalls[ksxId])
            assertEquals(1, client.disruptionCalls[ksxId])
            val state = vm.state.value as DeparturesUiState.Loaded
            assertFalse("both stops now have fresh arrivals", state.partialRefresh)
            val byId = state.stops.associateBy { it.stopId }
            // The carried-over stop keeps its own age; the retried one is stamped now (SPEC D4).
            assertEquals(now, byId.getValue(ksxId).fetchedAt)
            assertTrue(byId.getValue(ksxId).arrivalsFresh)
            assertEquals(now.plusSeconds(10), byId.getValue(oxcId).fetchedAt)
        }

    @Test
    fun `a service ending at the rider's nearest stop is hidden, with no status row in its place`() = runTest(dispatcher) {
        val toOxford = departure("victoria", "Victoria", 60).copy(destination = "Oxford Circus", destinationId = oxcId)
        val onward = departure("northern", "Northern", 120)
        val client = FakeClient(
            byStop = mapOf(oxcId to Result.success(emptyList()), ksxId to Result.success(listOf(toOxford, onward))),
            statuses = Result.success(listOf(status("victoria", 6, "Severe Delays"))),
        )
        val vm = MainViewModel(
            client,
            listOf(StopRef(oxcId, "Oxford Circus", clusterId = oxcId), StopRef(ksxId, "King's Cross St. Pancras", lines = listOf(LineRef("victoria", "Victoria", "tube")))),
            clock = { now }, io = dispatcher,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        val state = vm.state.value as DeparturesUiState.Loaded
        val ksx = state.stops.single { it.stopId == ksxId }
        assertTrue("the nearer place is saved with the stop", oxcId in ksx.nearer.ids)
        val rows = DepartureRows.across(state.stops, now, state.lineStatuses).filter { it.stopId == ksxId }
        assertEquals("no timed row and no \"no departures\" row for the hidden line", listOf("northern"), rows.map { it.lineId })
    }

    @Test
    fun `the widget's snapshot keeps the nearby stops' order nearest first, never their distances`() = runTest(dispatcher) {
        val store = FakeStore(restores = false)
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 900.0, ksxId to 100.0),
        )
        advanceUntilIdle()
        assertEquals(listOf(ksxId, oxcId), store.saves.last().nearestFirst)
    }

    @Test
    fun `the widget's snapshot keeps the stop the list shows each line from`() = runTest(dispatcher) {
        val store = FakeStore(restores = false)
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 900.0, ksxId to 100.0),
        )
        advanceUntilIdle()
        // Both stops serve the Victoria line inbound: the list shows it from King's Cross, the nearer.
        assertEquals(listOf(FoldChoice("victoria", "inbound", ksxId)), store.saves.last().nearbyChoices)
    }

    /** A store holding [held] as its snapshot, recording each order and the line choices worked out from it. */
    private class ChoicesStore(val held: DeparturesSnapshot) : SnapshotStore by SnapshotStore.NONE {
        val stored = mutableListOf<Pair<List<String>, List<FoldChoice>?>>()

        override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
            stored += order to choicesFor?.invoke(held)
        }
    }

    @Test
    fun `the line choices are worked out from the stored rows, at startup and on a move`() = runTest(dispatcher) {
        val victoria = listOf(departure("victoria", "Victoria", 300))
        val store = ChoicesStore(
            DeparturesSnapshot(listOf(StopArrivals(oxcId, "Oxford Circus", victoria, now), StopArrivals(ksxId, "King's Cross St. Pancras", victoria, now)), now),
        )
        val stored = store.stored
        val vm = MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        // At startup, before any rows are loaded: from the stored ones, so a first refresh that fails
        // still folds as the app does.
        assertEquals(listOf(oxcId, ksxId) to listOf(FoldChoice("victoria", "inbound", oxcId)), stored.first())
        stored.clear()
        vm.remeasure(mapOf(oxcId to 900.0, ksxId to 100.0))
        advanceUntilIdle()
        assertEquals(listOf(listOf(ksxId, oxcId) to listOf(FoldChoice("victoria", "inbound", ksxId))), stored)
    }

    @Test
    fun `a reconcile that keeps the order still stores the line choices from its new distances`() = runTest(dispatcher) {
        val victoria = listOf(departure("victoria", "Victoria", 300))
        val store = ChoicesStore(
            DeparturesSnapshot(listOf(StopArrivals(oxcId, "Oxford Circus", victoria, now), StopArrivals(ksxId, "King's Cross St. Pancras", victoria, now)), now),
        )
        val vm = MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        store.stored.clear()
        // Same order, new distances: the fold's stop can move without the order moving (crossing the
        // together-slack), so the choices are worked out again rather than left at the last fix's.
        val clusters = seeds.map { NearbySelection.NearbyCluster("c:${it.id}", listOf(StopLocation(it.id, it.name, 0.0, 0.0)), 0.0) }
        vm.reconcile(clusters, emptyList(), mapOf(oxcId to 120.0, ksxId to 880.0))
        advanceUntilIdle()
        assertEquals(listOf(listOf(oxcId, ksxId) to listOf(FoldChoice("victoria", "inbound", oxcId))), store.stored)
    }

    @Test
    fun `the widget's order and line choices are worked out on the io dispatcher`() = runTest(dispatcher) {
        // An io dispatcher that marks the blocks it runs, so the store can tell where it was called from.
        val onIo = ThreadLocal.withInitial { false }
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) =
                dispatcher.dispatch(context) {
                    onIo.set(true)
                    try { block.run() } finally { onIo.set(false) }
                }
        }
        val victoria = listOf(departure("victoria", "Victoria", 300))
        val workedOnIo = mutableListOf<Boolean>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            val held = DeparturesSnapshot(listOf(StopArrivals(oxcId, "Oxford Circus", victoria, now), StopArrivals(ksxId, "King's Cross St. Pancras", victoria, now)), now)
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                choicesFor?.invoke(held)
                workedOnIo += onIo.get()
            }
        }
        val vm = MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = io, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        vm.remeasure(mapOf(oxcId to 900.0, ksxId to 100.0))
        advanceUntilIdle()
        assertEquals(listOf(true, true), workedOnIo)
    }

    @Test
    fun `hiding a mode works the line choices out again, as the list hides it`() = runTest(dispatcher) {
        val victoria = listOf(departure("victoria", "Victoria", 300))
        val store = ChoicesStore(
            DeparturesSnapshot(listOf(StopArrivals(oxcId, "Oxford Circus", victoria, now), StopArrivals(ksxId, "King's Cross St. Pancras", victoria, now)), now),
        )
        var hidden = emptySet<String>()
        val hiddenChanges = MutableStateFlow<Set<String>>(emptySet())
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            hiddenModes = { hidden }, hiddenModeChanges = hiddenChanges,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        store.stored.clear()
        hidden = setOf("tube")
        hiddenChanges.value = hidden
        advanceUntilIdle()
        // The Tube is hidden from the list, so no stop is chosen for it any more.
        assertEquals(listOf(listOf(oxcId, ksxId) to emptyList<FoldChoice>()), store.stored)
    }

    @Test
    fun `a hidden mode that loads just after the startup write still reaches the line choices`() = runTest(dispatcher) {
        val victoria = listOf(departure("victoria", "Victoria", 300))
        val store = ChoicesStore(
            DeparturesSnapshot(listOf(StopArrivals(oxcId, "Oxford Circus", victoria, now), StopArrivals(ksxId, "King's Cross St. Pancras", victoria, now)), now),
        )
        // The startup write reads the setting before it has loaded; by the time the model follows
        // the setting, its current value is already the loaded one, with no change to come.
        var reads = 0
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            hiddenModes = { if (reads++ == 0) emptySet() else setOf("tube") },
            hiddenModeChanges = MutableStateFlow(setOf("tube")),
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        assertEquals(emptyList<FoldChoice>(), store.stored.last().second)
    }

    @Test
    fun `a mode hidden while a refresh is saving still reaches the line choices`() = runTest(dispatcher) {
        val victoria = listOf(departure("victoria", "Victoria", 300))
        var hidden = emptySet<String>()
        val written = mutableListOf<List<FoldChoice>>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            var held: DeparturesSnapshot? = null
            override suspend fun stored(): DeparturesSnapshot? = held
            override suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) {
                held = snapshot
                written += snapshot.nearbyChoices
                // The user hides the Tube while this save is landing, after its choices were worked out.
                hidden = setOf("tube")
            }
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                held?.let { stored -> choicesFor?.invoke(stored)?.let { written += it } }
            }
        }
        MainViewModel(
            FakeClient(byStop = mapOf(oxcId to Result.success(victoria), ksxId to Result.success(victoria))),
            seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            hiddenModes = { hidden },
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        assertEquals(listOf(FoldChoice("victoria", "inbound", oxcId)), written.first())
        // Worked out again once the save landed, with the Tube hidden: no stop is chosen for it.
        assertEquals(emptyList<FoldChoice>(), written.last())
    }

    @Test
    fun `a fix that lands while a refresh is saving still reaches the line choices`() = runTest(dispatcher) {
        val victoria = listOf(departure("victoria", "Victoria", 300))
        val written = mutableListOf<List<FoldChoice>>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            var held: DeparturesSnapshot? = DeparturesSnapshot(
                listOf(StopArrivals(oxcId, "Oxford Circus", victoria, now), StopArrivals(ksxId, "King's Cross St. Pancras", victoria, now)), now,
            )
            override suspend fun stored(): DeparturesSnapshot? = held
            override suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) {
                // Held until the test lets it land, after the fix's own write.
                gate.await()
                held = snapshot
                written += snapshot.nearbyChoices
            }
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                held?.let { stored -> choicesFor?.invoke(stored)?.let { written += it } }
            }
        }
        val vm = MainViewModel(
            FakeClient(byStop = mapOf(oxcId to Result.success(victoria), ksxId to Result.success(victoria))),
            seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        // The rider walks while the save, worked out at the old position, is on its way; the fix's
        // write lands first.
        vm.remeasure(mapOf(oxcId to 900.0, ksxId to 100.0))
        advanceUntilIdle()
        assertEquals(listOf(FoldChoice("victoria", "inbound", ksxId)), written.last())
        gate.complete(Unit)
        advanceUntilIdle()
        // The old position's save landed after it, so the choices are worked out again: King's Cross,
        // the nearer now, has the last word.
        assertEquals(listOf(FoldChoice("victoria", "inbound", ksxId)), written.last())
    }

    @Test
    fun `a move's line choices are worked out from the stops' new nearer places`() = runTest(dispatcher) {
        val vicId = "940GZZLUVIC"
        // King's Cross's Victoria line train ends at Victoria; Oxford Circus's goes on to Brixton.
        val toVictoria = departure("victoria", "Victoria", 60).copy(destination = "Victoria", destinationId = vicId)
        val client = FakeClient(
            byStop = mapOf(
                ksxId to Result.success(listOf(toVictoria)),
                oxcId to Result.success(listOf(departure("victoria", "Victoria", 120))),
                vicId to Result.success(emptyList()),
            ),
        )
        // The stored rows, given no nearer places: the choices give them the fix's own.
        val store = ChoicesStore(
            DeparturesSnapshot(
                listOf(
                    StopArrivals(ksxId, "King's Cross St. Pancras", listOf(toVictoria), now),
                    StopArrivals(oxcId, "Oxford Circus", listOf(departure("victoria", "Victoria", 120)), now),
                    StopArrivals(vicId, "Victoria", emptyList(), now),
                ),
                now,
            ),
        )
        val stored = store.stored
        val vm = MainViewModel(
            client,
            listOf(StopRef(ksxId, "King's Cross St. Pancras"), StopRef(oxcId, "Oxford Circus"), StopRef(vicId, "Victoria")),
            clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(ksxId to 100.0, oxcId to 200.0, vicId to 300.0),
        )
        advanceUntilIdle()
        stored.clear()
        // The rider walks towards Victoria: it's now nearer than King's Cross, whose train ends there,
        // so the list shows the line from Oxford Circus, and the widget must too.
        vm.remeasure(mapOf(vicId to 50.0, ksxId to 100.0, oxcId to 200.0))
        advanceUntilIdle()
        assertEquals(listOf(listOf(vicId, ksxId, oxcId) to listOf(FoldChoice("victoria", "inbound", oxcId))), stored)
    }

    @Test
    fun `a move that changes which stop is nearer stores the new order for the widget at once`() = runTest(dispatcher) {
        val orders = mutableListOf<List<String>>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                orders += order
            }
        }
        val vm = MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        // The order it starts from is stored at once, before any refresh saves it.
        assertEquals(listOf(listOf(oxcId, ksxId)), orders)
        orders.clear()
        // A fix that keeps the order keeps it (the store writes only what changed; the line choices
        // are worked out afresh with it, since they can move with no change of order).
        vm.remeasure(mapOf(oxcId to 120.0, ksxId to 880.0))
        advanceUntilIdle()
        assertEquals(listOf(listOf(oxcId, ksxId)), orders)
        orders.clear()
        // The rider walks: King's Cross is now the nearer one, before any refetch.
        vm.remeasure(mapOf(oxcId to 900.0, ksxId to 100.0))
        advanceUntilIdle()
        assertEquals(listOf(listOf(ksxId, oxcId)), orders)
    }

    @Test
    fun `of two fixes in quick succession, the later order is the one stored`() = runTest(dispatcher) {
        val orders = mutableListOf<List<String>>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                // The first write is slow: the second fix arrives while it's still going.
                if (orders.isEmpty()) gate.await()
                orders += order
            }
        }
        val vm = MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        vm.remeasure(mapOf(oxcId to 900.0, ksxId to 100.0))
        vm.remeasure(mapOf(oxcId to 100.0, ksxId to 900.0))
        gate.complete(Unit)
        advanceUntilIdle()
        // Only the latest is written: the orders a newer fix superseded are skipped, not written late.
        assertEquals(listOf(listOf(oxcId, ksxId)), orders)
    }

    @Test
    fun `a replaced model's order still on its way doesn't land over its successor's`() = runTest(dispatcher) {
        val orders = mutableListOf<List<String>>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                orders += order
            }
        }
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 900.0, ksxId to 100.0),
        )
        // A move makes another model before the first one's write has run.
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        assertEquals(listOf(listOf(oxcId, ksxId)), orders)
    }

    @Test
    fun `a model that doesn't feed the widget doesn't supersede one that does`() = runTest(dispatcher) {
        val orders = mutableListOf<List<String>>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
                orders += order
            }
        }
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        // A farther card made as the rider moves stores nothing for the widget.
        MainViewModel(
            ReuseCountingClient(), seeds, clock = { now }, io = dispatcher,
            stopDistanceMeters = mapOf(oxcId to 900.0, ksxId to 100.0),
        )
        advanceUntilIdle()
        assertEquals(listOf(listOf(oxcId, ksxId)), orders)
    }

    @Test
    fun `a stop carried over after the rider moves takes the new nearer places`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val stored = mutableListOf<Map<String, app.stopdash.domain.Terminating.Nearer>>()
        val store = object : SnapshotStore by SnapshotStore.NONE {
            override suspend fun updateNearer(nearer: Map<String, app.stopdash.domain.Terminating.Nearer>) {
                stored += nearer
            }
        }
        val vm = MainViewModel(
            client, seeds, clock = { now }, io = dispatcher, snapshotStore = store,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
        )
        advanceUntilIdle()
        // The rider walks: King's Cross is now the nearer one, and the refresh reuses both stops.
        val clusters = seeds.map { NearbySelection.NearbyCluster("c:${it.id}", listOf(StopLocation(it.id, it.name, 0.0, 0.0)), 0.0) }
        // The first thing the reconcile sets, before any refetch.
        val now0 = (firstStateAfter(vm) { vm.reconcile(clusters, emptyList(), mapOf(oxcId to 900.0, ksxId to 100.0)) } as DeparturesUiState.Loaded)
            .stops.associateBy { it.stopId }
        assertTrue("the shown stops take the new places before the network", ksxId in now0.getValue(oxcId).nearer.ids)
        advanceUntilIdle()
        assertTrue("and the widget's stored copy, whatever the refetch does", ksxId in stored.last().getValue(oxcId).ids)
        vm.refresh()
        advanceUntilIdle()
        val stops = (vm.state.value as DeparturesUiState.Loaded).stops.associateBy { it.stopId }
        assertTrue(ksxId in stops.getValue(oxcId).nearer.ids)
        assertFalse(oxcId in stops.getValue(ksxId).nearer.ids)
    }

    @Test
    fun `the timer refreshes a far stop every other minute, a user refresh every time`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
            farArrivalsReuse = FAR_ARRIVALS_REUSE,
        )
        advanceUntilIdle()

        // The minute timer: the near stop is refetched, the far one carried over.
        current = now.plusSeconds(60)
        vm.refresh(automatic = true)
        advanceUntilIdle()
        assertEquals(2, client.arrivalCalls[oxcId])
        assertEquals(1, client.arrivalCalls[ksxId])

        // A user refresh past the reuse window refetches both.
        current = now.plusSeconds(120)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(3, client.arrivalCalls[oxcId])
        assertEquals(2, client.arrivalCalls[ksxId])
    }

    @Test
    fun `a far stop showing an older board is still carried over on the timer`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient()
        // King's Cross's board came from another screen's fetch, 20 s before this one.
        client.partFetchedAt[ksxId] = now.minusSeconds(20)
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
            farArrivalsReuse = FAR_ARRIVALS_REUSE,
        )
        advanceUntilIdle()
        val ksx = (vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == ksxId }
        assertEquals("the stop is as old as its board", now.minusSeconds(20), ksx.fetchedAt)

        // The minute timer: at 80 s old, within the far stop's window, it's carried over.
        current = now.plusSeconds(60)
        vm.refresh(automatic = true)
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[ksxId])
    }

    @Test
    fun `hiding National Rail leaves a station's board out at once, and showing it brings it back at once`() = runTest(dispatcher) {
        var hidden = emptySet<String>()
        var current = now
        val stationId = "910GEXAMPLE"
        val busId = "490GEXAMPLE"
        val client = ReuseCountingClient()
        // As picked before National Rail was hidden: the station still declares its rail line.
        val station = StopRef(
            stationId, "Example",
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("thameslink", "Thameslink", "national-rail")),
        )
        val bus = StopRef(busId, "Example Road", lines = listOf(LineRef("1", "1", "bus")))
        val vm = MainViewModel(
            client, listOf(station, bus), clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            hiddenModes = { hidden },
        )
        advanceUntilIdle()
        assertEquals(listOf(true), client.railBoards[stationId])

        // Hidden, with no re-locate: the next fetch leaves the board out all the same.
        hidden = setOf("national-rail")
        current = now.plusSeconds(60)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(true, false), client.railBoards[stationId])
        assertEquals(2, client.arrivalCalls[busId])

        // "Show all" moments later: the station is fetched again at once, board and all, while the
        // bus stop, which never had a board to leave out, is carried over as usual.
        hidden = emptySet()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(true, false, true), client.railBoards[stationId])
        assertEquals(2, client.arrivalCalls[busId])
    }

    @Test
    fun `a starred National Rail journey keeps its station's board with National Rail hidden`() = runTest(dispatcher) {
        val stationId = "910GEXAMPLE"
        val client = ReuseCountingClient()
        val station = StopRef(stationId, "Example", lines = listOf(LineRef("victoria", "Victoria", "tube")))
        val vm = MainViewModel(
            client, listOf(station), clock = { now }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            hiddenModes = { setOf("national-rail") },
        )
        advanceUntilIdle()
        assertEquals(listOf(false), client.railBoards[stationId])
        // Moments later it's carried over as usual.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[stationId])

        // A National Rail journey starred from it: hiding doesn't reach it, so the stop is fetched
        // again at once, board and all, rather than carried over without its trains.
        vm.setJourneyStops(listOf(StopRef(stationId, "Example", lines = listOf(LineRef("thameslink", "Thameslink", "national-rail")))))
        advanceUntilIdle()
        assertEquals(listOf(false, true), client.railBoards[stationId])
    }

    @Test
    fun `starring a National Rail journey at a station already declaring its line fetches the board at once`() = runTest(dispatcher) {
        val stationId = "910GEXAMPLE"
        val thameslink = LineRef("thameslink", "Thameslink", "national-rail")
        val client = ReuseCountingClient()
        // Picked before National Rail was hidden, so it still declares the line the journey takes.
        val station = StopRef(stationId, "Example", lines = listOf(LineRef("victoria", "Victoria", "tube"), thameslink))
        val vm = MainViewModel(
            client, listOf(station), clock = { now }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            hiddenModes = { setOf("national-rail") },
        )
        advanceUntilIdle()
        assertEquals(listOf(false), client.railBoards[stationId])

        vm.setJourneyStops(listOf(StopRef(stationId, "Example", lines = listOf(thameslink))))
        advanceUntilIdle()
        assertEquals(listOf(false, true), client.railBoards[stationId])
    }

    @Test
    fun `walking closer moves a far stop back to every-minute refreshes`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            stopDistanceMeters = mapOf(oxcId to 100.0, ksxId to 900.0),
            farArrivalsReuse = FAR_ARRIVALS_REUSE,
        )
        advanceUntilIdle()

        // A relocation that keeps the same set, now with the far stop within the walking reach.
        val clusters = seeds.map { NearbySelection.NearbyCluster("c:${it.id}", listOf(StopLocation(it.id, it.name, 0.0, 0.0)), 0.0) }
        vm.reconcile(clusters, emptyList(), mapOf(oxcId to 600.0, ksxId to 200.0))
        advanceUntilIdle()
        val ksxCalls = client.arrivalCalls.getValue(ksxId)

        current = now.plusSeconds(60)
        vm.refresh(automatic = true)
        advanceUntilIdle()
        assertEquals(ksxCalls + 1, client.arrivalCalls[ksxId])
    }

    @Test
    fun `a worked-out journey is pinned in the store with its fetched origin`() = runTest(dispatcher) {
        val store = FakeStore()
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        // The fetch saves the origin as journey-only, and never writes pins itself.
        val saved = store.saves.last()
        assertEquals(setOf(ksxId), saved.journeyOnlyStopIds)
        assertTrue(saved.journeys.isEmpty())

        val call = JourneyCall("victoria", "Victoria", null)
        vm.setWidgetJourneys(setOf("j"), listOf(WidgetJourneyCheck("j", ksxId, setOf(call))))
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[ksxId])
        assertEquals(listOf(ksxId), store.reportOrigins.last().map { it.stopId })
        assertEquals(listOf(WidgetJourney(ksxId, setOf(call), "j")), store.stored!!.journeys)

        // A later save keeps the pin.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(WidgetJourney(ksxId, setOf(call), "j")), store.stored!!.journeys)
    }

    @Test
    fun `an unstar reaches the stored pins even when nothing refreshes`() = runTest(dispatcher) {
        val call = JourneyCall("victoria", "Victoria", null)
        val pinned = DeparturesSnapshot(
            stops = listOf(stopArrivals(ksxId, "King's Cross St. Pancras", 600, now.minusSeconds(120))),
            fetchedAt = now.minusSeconds(120),
            journeys = listOf(WidgetJourney(ksxId, setOf(call), "j")),
            journeyOnlyStopIds = setOf(ksxId),
        )
        val store = FakeStore(pinned)
        val client = ReuseCountingClient()
        client.failingArrivals += oxcId
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        assertTrue(store.saves.isEmpty())
        // Still starred, not checked yet: the stored pin stays.
        vm.setWidgetJourneys(setOf("j"), emptyList())
        advanceUntilIdle()
        assertEquals(pinned.journeys, store.stored!!.journeys)
        vm.setWidgetJourneys(emptySet(), emptyList())
        advanceUntilIdle()
        assertTrue(store.stored!!.journeys.isEmpty())
        assertTrue(store.stored!!.stops.isEmpty())
    }

    @Test
    fun `the same report again isn't written twice`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = MainViewModel(ReuseCountingClient(), listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        vm.setWidgetJourneys(setOf("j"), emptyList())
        vm.setWidgetJourneys(setOf("j"), emptyList())
        advanceUntilIdle()
        assertEquals(1, store.reports.size)
    }

    @Test
    fun `the widget's journeys are compared and gathered off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs: the disk's too, as
        // neither is the main thread.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        val store = FakeStore()
        val vm = MainViewModel(ReuseCountingClient(), listOf(seeds.first()), clock = { now }, io = worker, compute = worker, snapshotStore = store)
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        // Each journey check read: comparing a report with the one written, and gathering its origins,
        // read them.
        val read = mutableListOf<Boolean>()
        val check = WidgetJourneyCheck("j", ksxId, setOf(JourneyCall("victoria", "Victoria", null)))
        fun checks() = object : AbstractList<WidgetJourneyCheck>() {
            override val size: Int get() = 1
            override fun get(index: Int): WidgetJourneyCheck = check.also { read += onWorker.get() }
        }
        vm.setWidgetJourneys(setOf("j"), checks())
        advanceUntilIdle()
        // The same again, as an equal list: compared, and not written twice.
        vm.setWidgetJourneys(setOf("j"), checks())
        advanceUntilIdle()

        assertEquals(1, store.reports.size)
        assertEquals(listOf(ksxId), store.reportOrigins.single().map { it.stopId })
        assertTrue("$read", read.isNotEmpty() && read.all { it })
    }

    @Test
    fun `the widget's snapshot is worked out off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs: the disk's too, as
        // neither is the main thread.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        // Each stop's distance, noting where each is read: the widget's order (nearest first) reads them.
        val read = mutableListOf<Boolean>()
        val backing = mapOf(oxcId to 120.0, ksxId to 400.0)
        val distances = object : AbstractMap<String, Double>() {
            override val entries: Set<Map.Entry<String, Double>> get() = backing.entries.also { read += onWorker.get() }
            override fun get(key: String): Double? = backing[key].also { read += onWorker.get() }
            override fun containsKey(key: String): Boolean = backing.containsKey(key).also { read += onWorker.get() }
        }
        val store = FakeStore()
        val vm = MainViewModel(
            ReuseCountingClient(),
            seeds,
            clock = { now },
            io = worker,
            compute = worker,
            snapshotStore = store,
            stopDistanceMeters = distances,
        )
        advanceUntilIdle()
        read.clear()
        val saved = store.saves.size

        vm.refresh()
        advanceUntilIdle()

        assertEquals(saved + 1, store.saves.size)
        assertEquals(listOf(oxcId, ksxId), store.saves.last().nearestFirst)
        assertTrue("$read", read.isNotEmpty() && read.all { it })
    }

    @Test
    fun `a saved snapshot is restored off the main thread`() = runTest(dispatcher) {
        // A worker of its own, on the test's scheduler, that marks the work it runs: the disk's too, as
        // neither is the main thread.
        val onWorker = ThreadLocal.withInitial { false }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context) {
                onWorker.set(true)
                try {
                    block.run()
                } finally {
                    onWorker.set(false)
                }
            }
        }
        // Each stop's distance, noting where each is read: the restored list names its missing stops
        // nearest first (two of them, so there's an order to find).
        val read = mutableListOf<Boolean>()
        val euston = StopRef("940GZZLUEUS", "Euston")
        val backing = mapOf(oxcId to 120.0, ksxId to 400.0, euston.id to 300.0)
        val distances = object : AbstractMap<String, Double>() {
            override val entries: Set<Map.Entry<String, Double>> get() = backing.entries.also { read += onWorker.get() }
            override fun get(key: String): Double? = backing[key].also { read += onWorker.get() }
            override fun containsKey(key: String): Boolean = backing.containsKey(key).also { read += onWorker.get() }
        }
        // Saved with one of the three stops: restored as incomplete, naming the others.
        val store = FakeStore(
            DeparturesSnapshot(stops = listOf(stopArrivals(oxcId, "Oxford Circus", 600, now.minusSeconds(120))), fetchedAt = now.minusSeconds(120)),
        )
        // The refresh after it never comes back, so the restored list stays.
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = CompletableDeferred<List<Departure>>().await()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = MainViewModel(client, seeds + euston, clock = { now }, io = worker, compute = worker, snapshotStore = store, stopDistanceMeters = distances)
        advanceTimeBy(1)
        runCurrent()

        val restored = vm.state.value as DeparturesUiState.Loaded
        assertTrue(restored.partialRefresh)
        assertEquals(listOf(euston.id, ksxId), restored.partialStops.keys.toList())
        assertTrue("$read", read.isNotEmpty() && read.all { it })
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a failed journeys write is retried after the next fetch`() = runTest(dispatcher) {
        val store = FakeStore()
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        store.failJourneyWrites = 1
        val call = JourneyCall("victoria", "Victoria", null)
        vm.setWidgetJourneys(setOf("j"), listOf(WidgetJourneyCheck("j", ksxId, setOf(call))))
        advanceUntilIdle()
        assertTrue(store.reports.isEmpty())
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(WidgetJourney(ksxId, setOf(call), "j")), store.stored!!.journeys)
    }

    @Test
    fun `an older ViewModel's journeys write stands down for a newer one`() = runTest(dispatcher) {
        val store = FakeStore()
        val client = ReuseCountingClient()
        val old = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        // A relocation makes a successor; the old one's report mustn't land after it.
        MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        old.setWidgetJourneys(setOf("j"), emptyList())
        advanceUntilIdle()
        assertTrue(store.reports.isEmpty())
    }

    @Test
    fun `a searched station's model doesn't take the widget's journeys from the near-me one`() = runTest(dispatcher) {
        val store = FakeStore()
        val client = ReuseCountingClient()
        val nearMe = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        // Opening a searched station builds a model beside it that doesn't speak for the widget.
        MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, ownsWidgetJourneys = false)
        advanceUntilIdle()
        nearMe.setWidgetJourneys(setOf("j"), emptyList())
        advanceUntilIdle()
        assertEquals(1, store.reports.size)
    }

    @Test
    fun `an older ViewModel's journeys write in flight lands before a newer one's`() = runTest(dispatcher) {
        val fake = FakeStore()
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<Set<String>>()
        var calls = 0
        val store = object : SnapshotStore by fake {
            override suspend fun updateWidgetJourneys(report: WidgetJourneysReport, origins: List<StopArrivals>) {
                if (calls++ == 0) gate.await()
                order += report.keys
                fake.updateWidgetJourneys(report, origins)
            }
        }
        val client = ReuseCountingClient()
        val old = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        // The old one's write passes its ownership check, then waits on storage...
        old.setWidgetJourneys(setOf("old"), emptyList())
        advanceUntilIdle()
        // ...while a relocation makes a successor, whose write must not land first.
        val successor = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        successor.setWidgetJourneys(setOf("new"), emptyList())
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(setOf("old"), setOf("new")), order)
    }

    @Test
    fun `a fresh journey origin doesn't save the widget when its nearby stops failed`() = runTest(dispatcher) {
        val store = FakeStore()
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher, snapshotStore = store)
        advanceUntilIdle()
        val saves = store.saves.size

        // The nearby stop fails; only the journey origin comes back.
        client.failingArrivals += oxcId
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()

        assertEquals(saves, store.saves.size)

        // Nor once the journey is worked out: the origin's fresh arrivals alone don't stand in for
        // the failed nearby stop.
        val call = JourneyCall("victoria", "Victoria", null)
        vm.setWidgetJourneys(setOf("j"), listOf(WidgetJourneyCheck("j", ksxId, setOf(call))))
        vm.refresh()
        advanceUntilIdle()
        assertTrue(store.saves.drop(saves).none { s -> s.stops.any { it.stopId == oxcId && !it.arrivalsFresh } })
    }

    @Test
    fun `a journey origin whose first fetch failed is marked unavailable, not loading`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        client.failingArrivals += ksxId
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()

        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertTrue(ksxId !in loaded.stops.map { it.stopId })
        assertEquals(setOf(ksxId), loaded.unavailableStopIds)
    }

    @Test
    fun `unstarring a failed journey origin clears the warning it caused`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        client.failingArrivals += ksxId
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        assertTrue(ksxId in (vm.state.value as DeparturesUiState.Loaded).unavailableStopIds)

        vm.setJourneyStops(emptyList())
        advanceUntilIdle()
        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertTrue(loaded.unavailableStopIds.isEmpty())
        assertFalse(loaded.partialRefresh)
    }

    @Test
    fun `setting the same journey origins again doesn't refetch`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[ksxId])
    }

    @Test
    fun `a journey origin's line has its status checked even with no trains predicted`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        val asked = client.statusCalls.size
        vm.setJourneyStops(
            listOf(StopRef(ksxId, "King's Cross St. Pancras", lines = listOf(LineRef("northern", "Northern", "tube")))),
        )
        advanceUntilIdle()
        assertTrue(client.statusCalls.drop(asked).any { "northern" in it })
    }

    @Test
    fun `an origin that gains a declared line is fetched again, so its status is checked`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras", lines = listOf(LineRef("northern", "Northern", "tube")))))
        advanceUntilIdle()
        val asked = client.statusCalls.size
        vm.setJourneyStops(
            listOf(
                StopRef(
                    ksxId,
                    "King's Cross St. Pancras",
                    lines = listOf(LineRef("northern", "Northern", "tube"), LineRef("piccadilly", "Piccadilly", "tube")),
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(client.statusCalls.drop(asked).any { "piccadilly" in it })
    }

    @Test
    fun `a nearby stop that becomes a journey origin is fetched again with the journey's lines`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        val asked = client.statusCalls.size
        vm.setJourneyStops(listOf(StopRef(oxcId, "Oxford Circus", lines = listOf(LineRef("central", "Central", "tube")))))
        advanceUntilIdle()
        assertTrue(client.statusCalls.drop(asked).any { "central" in it })
    }

    @Test
    fun `a line gained by a just-fetched origin is status-checked without refetching its arrivals`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, listOf(seeds.first()), clock = { now }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras", lines = listOf(LineRef("northern", "Northern", "tube")))))
        advanceUntilIdle()
        val asked = client.statusCalls.size
        vm.setJourneyStops(
            listOf(
                StopRef(
                    ksxId,
                    "King's Cross St. Pancras",
                    lines = listOf(LineRef("northern", "Northern", "tube"), LineRef("piccadilly", "Piccadilly", "tube")),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, client.arrivalCalls[ksxId])
        assertTrue(client.statusCalls.drop(asked).any { "piccadilly" in it })
    }

    @Test
    fun `flipping back while the other origin is fetching still fetches the first`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        advanceUntilIdle()

        // Flip to the other end; its fetch is still in flight when the flip back comes.
        val gate = CompletableDeferred<Unit>()
        client.arrivalsGate = gate
        vm.setJourneyStops(listOf(StopRef("940GZZLUHGT", "Highgate")))
        runCurrent()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras")))
        gate.complete(Unit)
        advanceUntilIdle()

        val shown = (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId }
        assertTrue(ksxId in shown)
    }

    @Test
    fun `line status is reused for a while, then asked for again`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient().apply {
            statuses = listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))
        }
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE, lineStatusReuse = LINE_STATUS_REUSE,
        )
        advanceUntilIdle()
        assertEquals(1, client.statusCalls.size)

        // A minute later (the auto-refresh): arrivals refetched, the line's verdict reused.
        current = now.plusSeconds(60)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, client.statusCalls.size)
        assertFalse((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)

        // Past the reuse window it's asked for again.
        current = now.plus(LINE_STATUS_REUSE)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, client.statusCalls.size)
    }

    @Test
    fun `a line status still waiting on its alerts' directions isn't reused`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient().apply {
            statuses = listOf(status("victoria", 6, "Severe Delays").copy(awaitingDirections = true))
        }
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE, lineStatusReuse = LINE_STATUS_REUSE,
        )
        advanceUntilIdle()
        assertEquals(1, client.statusCalls.size)

        // The next refresh asks again, inside the reuse window, to pick up the split status.
        client.statuses = listOf(status("victoria", 6, "Severe Delays"))
        current = now.plusSeconds(60)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, client.statusCalls.size)

        // Now complete, it is reused as usual.
        current = now.plusSeconds(120)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, client.statusCalls.size)
    }

    @Test
    fun `a line TfL left out isn't asked about again within the reuse window, and stays unknown`() = runTest(dispatcher) {
        var current = now
        // TfL answers with no status for any line.
        val client = ReuseCountingClient().apply { statuses = emptyList() }
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE, lineStatusReuse = LINE_STATUS_REUSE,
        )
        advanceUntilIdle()
        assertEquals(1, client.statusCalls.size)

        current = now.plusSeconds(60)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, client.statusCalls.size)
        assertTrue((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)

        current = now.plus(LINE_STATUS_REUSE)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, client.statusCalls.size)
    }

    @Test
    fun `a line TfL no longer recognizes replaces its cached disruption for the widget`() = runTest(dispatcher) {
        var current = now
        val store = FakeStore()
        val client = ReuseCountingClient().apply { statuses = listOf(status("victoria", 6, "Severe Delays")) }
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher, snapshotStore = store,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE, lineStatusReuse = LINE_STATUS_REUSE,
        )
        advanceUntilIdle()
        assertTrue(store.saves.last().lineStatuses.getValue("victoria").known)

        client.unknownStatus = true
        current = now.plus(LINE_STATUS_REUSE)
        vm.refresh()
        advanceUntilIdle()
        val check = store.saves.last().lineStatuses.getValue("victoria")
        assertFalse(check.known)
        assertEquals(current, check.checkedAt)
    }

    @Test
    fun `a failed line status request keeps only the still-fresh verdicts`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient().apply {
            statuses = listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))
        }
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE, lineStatusReuse = LINE_STATUS_REUSE,
        )
        advanceUntilIdle()

        // The cached verdict has expired and the fresh request fails: the line is unknown, not clean.
        client.failingStatus = true
        current = now.plus(LINE_STATUS_REUSE)
        vm.refresh()
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)
    }

    @Test
    fun `a refresh once the reuse window has passed refetches every stop`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()

        current = now.plus(ARRIVALS_REUSE)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(2, client.arrivalCalls[oxcId])
        assertEquals(2, client.arrivalCalls[ksxId])
        assertEquals(now.plus(ARRIVALS_REUSE), (vm.state.value as DeparturesUiState.Loaded).fetchedAt)
    }

    @Test
    fun `a stop whose closure check failed is refetched even when its arrivals are recent`() =
        runTest(dispatcher) {
            var current = now
            val client = ReuseCountingClient().apply { failingDisruptions += ksxId }
            val vm = MainViewModel(
                client, seeds, clock = { current }, io = dispatcher,
                arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            )
            advanceUntilIdle()
            assertTrue(ksxId in (vm.state.value as DeparturesUiState.Loaded).stopsDisruptionUnknown)

            client.failingDisruptions.clear()
            current = now.plusSeconds(10)
            vm.refresh()
            advanceUntilIdle()

            // The failed closure check is never cached, so it's asked again; the stop is re-fetched.
            assertEquals(2, client.disruptionCalls[ksxId])
            assertEquals(2, client.arrivalCalls[ksxId])
            assertTrue((vm.state.value as DeparturesUiState.Loaded).stopsDisruptionUnknown.isEmpty())
        }

    @Test
    fun `a stop's closure check is reused for a few minutes, then asked again`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()

        // A scheduled refresh a minute on refetches arrivals but reuses the closure check.
        current = now.plusSeconds(60)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, client.arrivalCalls[oxcId])
        assertEquals(1, client.disruptionCalls[oxcId])

        current = now.plus(DISRUPTION_REUSE)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(3, client.arrivalCalls[oxcId])
        assertEquals(2, client.disruptionCalls[oxcId])
    }

    @Test
    fun `with no reuse configured every refresh refetches everything`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()

        assertEquals(2, client.arrivalCalls[oxcId])
        assertEquals(2, client.disruptionCalls[oxcId])
    }

    @Test
    fun `a stop restored from disk is refetched, never carried over`() = runTest(dispatcher) {
        // The saved snapshot carries no closure check, so a stop from it can't stand in for a fetch,
        // however recent its departures.
        val restored = DeparturesSnapshot(
            stops = seeds.map { StopArrivals(it.id, it.name, listOf(departure("victoria", "Victoria", 300)), now) },
            fetchedAt = now,
        )
        val client = ReuseCountingClient()
        MainViewModel(
            client, seeds, clock = { now.plusSeconds(5) }, io = dispatcher, snapshotStore = FakeStore(restored),
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()

        assertEquals(1, client.arrivalCalls[oxcId])
        assertEquals(1, client.disruptionCalls[oxcId])
    }

    @Test
    fun `a canceled refresh's unpublished arrivals don't make the shown stops look just fetched`() =
        runTest(dispatcher) {
            var current = now
            var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
            val counting = ReuseCountingClient()
            // Delegates to the counting client, but the line-status request waits on [gate], so a
            // refresh can be canceled after its arrivals came back and before it's published.
            val client = object : TflClient by counting {
                override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                    gate.await()
                    return emptyList()
                }
            }
            val vm = MainViewModel(
                client, seeds, clock = { current }, io = dispatcher,
                arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            )
            advanceUntilIdle()

            // A refresh 55 s on (past the reuse window) gets its arrivals, then stalls on line status
            // and is superseded.
            gate = CompletableDeferred()
            current = now.plusSeconds(55)
            vm.refresh()
            advanceUntilIdle()
            gate.complete(Unit)
            current = now.plusSeconds(60)
            vm.refresh()
            advanceUntilIdle()

            // The screen still showed the first fetch's stops, so the third refresh refetches them.
            assertEquals(3, counting.arrivalCalls[oxcId])
            assertEquals(now.plusSeconds(60), (vm.state.value as DeparturesUiState.Loaded).fetchedAt)
        }

    @Test
    fun `a cached closure check doesn't hide a refresh where every request failed`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()

        // A minute on, every arrivals request is rate-limited; the closure checks come from cache.
        client.failingArrivals += listOf(oxcId, ksxId)
        current = now.plusSeconds(60)
        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value as DeparturesUiState.Loaded
        assertEquals("the real failure is shown", DeparturesUiState.Error.Kind.RATE_LIMITED, state.refreshFailure)
        assertEquals(1, client.disruptionCalls[oxcId])
    }

    @Test
    fun `a refresh that reuses every stop still saves when the previous save was canceled`() =
        runTest(dispatcher) {
            var current = now
            val fake = FakeStore()
            var saveGate = CompletableDeferred<Unit>()
            // The first save stalls, so the next refresh cancels it mid-write.
            val store = object : SnapshotStore by fake {
                override suspend fun save(snapshot: DeparturesSnapshot) {
                    saveGate.await()
                    fake.save(snapshot)
                }
                // The app's refresh saves go through here too.
                override suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) = save(snapshot)
            }
            val client = ReuseCountingClient()
            val vm = MainViewModel(
                client, seeds, clock = { current }, io = dispatcher, snapshotStore = store,
                arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            )
            advanceUntilIdle()
            assertTrue("the first save is still pending", fake.saves.isEmpty())

            saveGate = CompletableDeferred<Unit>().apply { complete(Unit) }
            current = now.plusSeconds(10)
            vm.refresh()
            advanceUntilIdle()

            // Every stop was reused (no new requests), yet the published snapshot reaches disk.
            assertEquals(1, client.arrivalCalls[oxcId])
            assertEquals(1, fake.saves.size)
            assertEquals(seeds.map { it.id }.toSet(), fake.saves.single().stops.map { it.stopId }.toSet())
        }

    @Test
    fun `a clock moved backward never makes an old result look recent`() = runTest(dispatcher) {
        var current = now
        val client = ReuseCountingClient()
        val vm = MainViewModel(
            client, seeds, clock = { current }, io = dispatcher,
            arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
        )
        advanceUntilIdle()

        // The device clock jumps back an hour: the earlier fetch now reads as from the future.
        current = now.minusSeconds(3600)
        vm.refresh()
        advanceUntilIdle()

        assertEquals("arrivals refetched", 2, client.arrivalCalls[oxcId])
        assertEquals("closure check refetched", 2, client.disruptionCalls[oxcId])
    }

    @Test
    fun `a closure cached after the shown fetch stops that stop being carried over`() =
        runTest(dispatcher) {
            var current = now
            var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
            val counting = ReuseCountingClient()
            val client = object : TflClient by counting {
                override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
                    gate.await()
                    return emptyList()
                }
            }
            // A short closure reuse, so the second refresh asks for the closure again.
            val vm = MainViewModel(
                client, seeds, clock = { current }, io = dispatcher,
                arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = Duration.ofSeconds(10),
            )
            advanceUntilIdle()

            // 55 s on (past the reuse window), a refresh finds King's Cross newly closed but its
            // arrivals fail, then stalls on line status and is superseded — its closure is cached but
            // never shown.
            gate = CompletableDeferred()
            current = now.plusSeconds(55)
            counting.failingArrivals += ksxId
            counting.closures[ksxId] = listOf(StopDisruption("Bus Stop Closed"))
            vm.refresh()
            advanceUntilIdle()

            // The clock is then set back, so the shown fetch reads as only 5 s old.
            counting.failingArrivals.clear()
            gate.complete(Unit)
            current = now.plusSeconds(5)
            vm.refresh()
            advanceUntilIdle()

            // The stop isn't carried over past the newer closure: it's refetched and shows it.
            assertEquals(3, counting.arrivalCalls[ksxId])
            val ksx = (vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == ksxId }
            assertTrue(ksx.disruptions.isNotEmpty())
        }

    @Test
    fun `a trip's closure asked in the same instant and landing after the list's stops that stop being carried over`() =
        runTest(dispatcher) {
            val shared = StopClosureCache()
            val closed = listOf(StopDisruption("Station closed until further notice"))
            var tripAsk: StopClosureCache.Ask? = null
            val counting = ReuseCountingClient()
            val client = object : TflClient by counting {
                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    // A trip asks about King's Cross in the same instant, after this lookup.
                    if (stopId == ksxId && tripAsk == null) tripAsk = shared.ask(now)
                    return counting.stopDisruptions(stopId)
                }
            }
            val vm = MainViewModel(
                client, seeds, clock = { now }, io = dispatcher, disruptionCache = shared,
                arrivalsReuse = ARRIVALS_REUSE, disruptionReuse = DISRUPTION_REUSE,
            )
            advanceUntilIdle()
            // The list has shown its own answer; the trip's lands after it, stamped the same instant.
            shared.keep(ksxId, tripAsk!!, closed)
            vm.refresh()
            advanceUntilIdle()

            // King's Cross isn't carried over past the trip's answer: refetched, with the closure the
            // cache holds rather than asked again. Oxford Circus, with nothing newer, still is.
            assertEquals(2, counting.arrivalCalls[ksxId])
            assertEquals(1, counting.disruptionCalls[ksxId])
            assertEquals(1, counting.arrivalCalls[oxcId])
            val ksx = (vm.state.value as DeparturesUiState.Loaded).stops.single { it.stopId == ksxId }
            assertEquals(closed, ksx.disruptions)
        }
}
