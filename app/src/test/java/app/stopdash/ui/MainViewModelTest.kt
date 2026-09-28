package app.stopdash.ui

import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.Dismissed
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.HubInfo
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FartherStations
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.WidgetJourney
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneys
import app.stopdash.domain.WidgetJourneysReport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val seeds = listOf(
        StopRef("940GZZLUOXC", "Oxford Circus"),
        StopRef("940GZZLUKSX", "King's Cross St. Pancras"),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

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

        override suspend fun arrivals(stopId: String): List<Departure> {
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
    private class FakeStore(initial: DeparturesSnapshot? = null) : SnapshotStore {
        var stored: DeparturesSnapshot? = initial
        val saves = mutableListOf<DeparturesSnapshot>()
        // When > 0, the next this-many save() calls throw instead of storing — to exercise a
        // shrink-save that fails so the pending-persist is retried, not dropped (Codex, PR #87).
        var failSaves: Int = 0
        override suspend fun load(): DeparturesSnapshot? = stored
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
        // Every line status dismissed through the store (the widget's copy).
        val dismissedLines = mutableListOf<DismissedAlert>()
        override suspend fun dismissLineStatus(alert: DismissedAlert) {
            dismissedLines += alert
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

    @Test
    fun `a cold load shows each stop as it lands and saves only once all are in`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore()
        val vm = viewModel(GatedClient(seeds[1].id, gate), store)
        advanceUntilIdle()

        val partial = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf(seeds[0].id), partial.stops.map { it.stopId })
        assertEquals(listOf(seeds[1].id), partial.pendingStops.map { it.id })
        // Line status waits for every stop, so it isn't vouched for yet.
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
        assertTrue((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)

        // TfL's answer won't change: a refresh doesn't ask again, and the line still isn't clean.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, statusCalls)
        assertTrue((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)
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
    fun `a line status dismissed in the app leaves the widget at once, and later saves keep it unmarked`() = runTest(dispatcher) {
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val dismissedStore = object : DismissedAlertsStore {
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
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = listOf(status)
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val store = FakeStore()
        val vm = MainViewModel(
            client,
            listOf(StopRef("940GZZLUVIC", "Victoria")),
            clock = { now },
            io = dispatcher,
            snapshotStore = store,
            dismissedStore = dismissedStore,
            widgetDismissals = store,
        )
        advanceUntilIdle()
        assertEquals(false, store.stored!!.lineStatuses.getValue("victoria").dismissed)
        val loaded = vm.state.value as DeparturesUiState.Loaded
        val row = DepartureRows.across(loaded.stops, now, loaded.lineStatuses).first { it.status != null }
        vm.dismissAlert(row)
        advanceUntilIdle()
        // At once: the stored check is marked through the store.
        assertEquals(listOf(DismissedAlert.ofLineStatus(severe)), store.dismissedLines)

        // A later save marks it from the dismissed set, so the widget and the watch stay clear.
        vm.refresh()
        advanceUntilIdle()
        val saved = store.stored!!.lineStatuses.getValue("victoria")
        assertTrue(saved.dismissed)
        assertTrue(store.stored!!.liveLineStatuses(now).isEmpty())

        // A changed status is a new alert: marked again.
        status = LineStatus("victoria", 10, "Suspended", "Victoria line: suspended.")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(false, store.stored!!.lineStatuses.getValue("victoria").dismissed)
    }

    @Test
    fun `a save judges dismissals on the stored set, even before this screen has read it`() = runTest(dispatcher) {
        // Straight after a restart: an alert dismissed long ago, and the screen's own read of the
        // dismissed set not yet in.
        val severe = LineStatus("victoria", 6, "Severe Delays", "Victoria line: severe delays.")
        var reads = 0
        val dismissedStore = object : DismissedAlertsStore {
            override fun dismissed(): kotlinx.coroutines.flow.Flow<Set<DismissedAlert>> =
                if (reads++ == 0) kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
                else kotlinx.coroutines.flow.flowOf(setOf(DismissedAlert.ofLineStatus(severe)))
            override suspend fun dismiss(alert: DismissedAlert) {}
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = listOf(severe)
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val store = FakeStore()
        MainViewModel(
            client,
            listOf(StopRef("940GZZLUVIC", "Victoria")),
            clock = { now },
            io = dispatcher,
            snapshotStore = store,
            dismissedStore = dismissedStore,
        )
        advanceUntilIdle()
        assertTrue(store.stored!!.lineStatuses.getValue("victoria").dismissed)
    }

    @Test
    fun `a dismissal tapped just before the model is cleared still reaches the widget`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backing = MutableStateFlow<Set<DismissedAlert>>(emptySet())
        val dismissedStore = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                gate.await()
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
        val severe = LineStatus("victoria", 6, "Severe Delays", "Victoria line: severe delays.")
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = listOf(severe)
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val store = FakeStore()
        val vm = MainViewModel(
            client,
            listOf(StopRef("940GZZLUVIC", "Victoria")),
            clock = { now },
            io = dispatcher,
            snapshotStore = store,
            dismissedStore = dismissedStore,
            widgetDismissals = store,
        )
        advanceUntilIdle()
        val loaded = vm.state.value as DeparturesUiState.Loaded
        vm.dismissAlert(DepartureRows.across(loaded.stops, now, loaded.lineStatuses).first { it.status != null })
        runCurrent()
        // The page closes while the app's own write is still in flight.
        vm.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(DismissedAlert.ofLineStatus(severe)), backing.value)
        assertEquals(listOf(DismissedAlert.ofLineStatus(severe)), store.dismissedLines)
    }

    @Test
    fun `a dismissal that didn't save leaves the widget's mark in place`() = runTest(dispatcher) {
        val dismissedStore = object : DismissedAlertsStore {
            override fun dismissed() = MutableStateFlow<Set<DismissedAlert>>(emptySet())
            override suspend fun dismiss(alert: DismissedAlert) = throw RuntimeException("disk full")
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
        val severe = LineStatus("victoria", 6, "Severe Delays", "Victoria line: severe delays.")
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String) = listOf(departure("victoria", "Victoria", 120))
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = listOf(severe)
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val store = FakeStore()
        val vm = MainViewModel(
            client,
            listOf(StopRef("940GZZLUVIC", "Victoria")),
            clock = { now },
            io = dispatcher,
            snapshotStore = store,
            dismissedStore = dismissedStore,
            widgetDismissals = store,
        )
        advanceUntilIdle()
        val loaded = vm.state.value as DeparturesUiState.Loaded
        vm.dismissAlert(DepartureRows.across(loaded.stops, now, loaded.lineStatuses).first { it.status != null })
        advanceUntilIdle()
        assertTrue(vm.dismissWriteFailed.value)
        assertEquals(emptyList<DismissedAlert>(), store.dismissedLines)
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
    fun `fetches every stop in parallel, merged in stop order`() = runTest(dispatcher) {
        // Each request parks on a gate, so what has started once the scheduler settles is exactly
        // what was issued before any answer came back. A one-at-a-time loop would show one request.
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                started += "arrivals:$stopId"
                gate.await()
                return listOf(departure("victoria", "Victoria", if (stopId == seeds[0].id) 300 else 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                started += "disruptions:$stopId"
                gate.await()
                return emptyList()
            }
        }
        val vm = viewModel(client)
        runCurrent()

        // Every request is in flight at once. Order isn't asserted: it's a best effort, not a contract.
        assertEquals(
            setOf(
                "arrivals:${seeds[0].id}",
                "arrivals:${seeds[1].id}",
                "disruptions:${seeds[0].id}",
                "disruptions:${seeds[1].id}",
            ),
            started.toSet(),
        )
        assertEquals(4, started.size)
        gate.complete(Unit)
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

    // --- The "More" reveal: the retained ViewModel owns both tiers and reconciles across a
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

    private fun shownIds(vm: MainViewModel) = (vm.state.value as DeparturesUiState.Loaded).stops.map { it.stopId }

    private fun twoStopClient() = FakeClient(
        mapOf(
            "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
            "MA" to Result.success(listOf(departure("central", "Central", 200))),
        ),
    )

    // A single-stop bus `more` cluster whose stop declares [lineIds] (a route can repeat across
    // clusters, unlike clusterOf which derives the line id from the stop id).
    private fun busClusterOf(key: String, stopId: String, vararg lineIds: String) =
        NearbySelection.NearbyCluster(
            key = key,
            stops = listOf(
                StopLocation(
                    id = stopId, name = stopId, latitude = 0.0, longitude = 0.0,
                    lines = lineIds.map { LineRef(it, it, "bus") }, clusterId = key,
                ),
            ),
            distanceMeters = 0.0,
        )

    @Test
    fun `reveal reaches through redundant clusters to the first with a new route in one tap`() =
        runTest(dispatcher) {
            // The eager stop already shows routes L1 and L2. The two nearest `more` clusters only
            // repeat those (the near-me list would collapse them to nothing), and the farther one
            // carries a new route L9 — past the two-per-tap page. One tap must reach it, so the new
            // stop appears rather than the tap looking like it did nothing.
            val client = FakeClient(
                mapOf(
                    // The eager stop shows both L1 and L2 live, so the two nearer `more` clusters
                    // (which repeat them) are genuinely redundant.
                    "EAG" to Result.success(listOf(departure("L1", "Dest", 300), departure("L2", "Dest", 300))),
                    "R1" to Result.success(listOf(departure("L1", "Dest", 200))),
                    "R2" to Result.success(listOf(departure("L2", "Dest", 200))),
                    "NEW" to Result.success(listOf(departure("L9", "Dest", 200))),
                ),
            )
            val eager = listOf(StopRef("EAG", "EAG", lines = listOf(LineRef("L1", "L1", "bus"), LineRef("L2", "L2", "bus"))))
            val more = listOf(
                busClusterOf("R1", "R1", "L1"),
                busClusterOf("R2", "R2", "L2"),
                busClusterOf("NEW", "NEW", "L9"),
            )
            val vm = tierVm(client, eager, more)
            advanceUntilIdle()
            assertEquals(listOf("EAG"), shownIds(vm))

            vm.reveal("bus")
            advanceUntilIdle()
            // The farther new-route stop is revealed on this tap, not left for a second one.
            assertTrue("the new-route cluster is revealed in one tap", "NEW" in shownIds(vm))
        }

    @Test
    fun `a route only declared by an eager stop, not shown, does not mask a farther stop that shows it`() =
        runTest(dispatcher) {
            // The eager stop DECLARES L1 and L2 but only has live L1 departures, so L2 is not on
            // screen. A farther stop with L2 must still be revealed — counting the declared-only L2
            // as "shown" would treat it as redundant and never reveal it (the dead tap this fixes).
            val client = FakeClient(
                mapOf(
                    "EAG" to Result.success(listOf(departure("L1", "Dest", 300))), // L2 declared, not live
                    "R1" to Result.success(listOf(departure("L1", "Dest", 200))),
                    "R2" to Result.success(listOf(departure("L1", "Dest", 200))),
                    "N" to Result.success(listOf(departure("L2", "Dest", 200))),
                ),
            )
            val eager = listOf(StopRef("EAG", "EAG", lines = listOf(LineRef("L1", "L1", "bus"), LineRef("L2", "L2", "bus"))))
            val more = listOf(
                busClusterOf("R1", "R1", "L1"),
                busClusterOf("R2", "R2", "L1"),
                busClusterOf("N", "N", "L2"),
            )
            val vm = tierVm(client, eager, more)
            advanceUntilIdle()

            vm.reveal("bus")
            advanceUntilIdle()
            assertTrue("the stop with the not-actually-shown route is revealed", "N" in shownIds(vm))
        }

    @Test
    fun `an expired departure's route does not count as shown, so a farther stop with it live is revealed`() =
        runTest(dispatcher) {
            // The eager stop has an already-departed L1 prediction (no longer rendered) and a live
            // L2. L1 is not on screen, so a farther stop with live L1 beyond the page must still be
            // revealed — counting the expired L1 as shown would treat it as redundant (dead tap).
            val client = FakeClient(
                mapOf(
                    "EAG" to Result.success(listOf(departure("L1", "Dest", -60), departure("L2", "Dest", 300))),
                    "R1" to Result.success(listOf(departure("L2", "Dest", 200))),
                    "R2" to Result.success(listOf(departure("L2", "Dest", 200))),
                    "N" to Result.success(listOf(departure("L1", "Dest", 200))),
                ),
            )
            val eager = listOf(StopRef("EAG", "EAG", lines = listOf(LineRef("L1", "L1", "bus"), LineRef("L2", "L2", "bus"))))
            val more = listOf(
                busClusterOf("R1", "R1", "L2"),
                busClusterOf("R2", "R2", "L2"),
                busClusterOf("N", "N", "L1"),
            )
            val vm = tierVm(client, eager, more)
            advanceUntilIdle()

            vm.reveal("bus")
            advanceUntilIdle()
            assertTrue("the stop with the live route whose earlier prediction expired is revealed", "N" in shownIds(vm))
        }

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
        val load = cards.cards.value[fartherPlace.key]
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
        val load = cards.cards.value[bus.key]
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
        val load = cards.cards.value[bus.key]
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
        assertEquals(FartherLoad.Failed, cards.cards.value[fartherPlace.key])
        fail = false
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        assertTrue(cards.cards.value[fartherPlace.key] is FartherLoad.Open)
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
        val open = cards.cards.value[fartherPlace.key] as FartherLoad.Open
        assertEquals(0.0, open.distanceMeters.getValue("MA"), 1.0)
    }

    @Test
    fun `a farther station no longer offered closes, and its model with it`() = runTest(dispatcher) {
        val cards = fartherCards { listOf(fartherStop) }
        cards.open(fartherPlace, Coordinates(0.0, 0.0))
        advanceUntilIdle()
        val model = openModel(cards)
        cards.retain(emptyList(), Coordinates(0.0, 0.0))
        assertTrue(cards.cards.value.isEmpty())
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
        assertTrue(cards.cards.value.isEmpty())
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
        assertTrue(shown.partialStops.isEmpty())
        assertEquals(null, shown.partialReason)
    }

    @Test
    fun `an opened card's model is re-measured when the rider moves`() = runTest(dispatcher) {
        // The stop sits at the new fix, about 1.1 km from the old one.
        val cards = fartherCards { listOf(fartherStop.copy(latitude = 51.51)) }
        cards.open(fartherPlace, Coordinates(51.5, 0.0))
        advanceUntilIdle()
        assertEquals(1_112.0, openModel(cards).distanceMeters.getValue("MA"), 5.0)
        cards.retain(listOf(fartherPlace), Coordinates(51.51, 0.0))
        assertEquals(0.0, openModel(cards).distanceMeters.getValue("MA"), 1.0)
    }

    @Test
    fun `the shown near stops follow reveals and same-set reconciles`() = runTest(dispatcher) {
        // The farther-station cards count these as reached, so they must track what is loaded,
        // including a reconcile that swaps clusters across the eager/more boundary (Codex P2, PR #226).
        val vm = tierVm(twoStopClient(), listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        assertEquals(listOf("E"), vm.shownNearStops.value.map { it.id })

        vm.reveal("bus")
        advanceUntilIdle()
        assertEquals(setOf("E", "MA"), vm.shownNearStops.value.map { it.id }.toSet())

        // M1 promoted to eager, E demoted to an unrevealed `more` cluster: E is no longer loaded.
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
    fun `reveal fetches the more cluster and drops its More button`() = runTest(dispatcher) {
        val vm = tierVm(twoStopClient(), listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        // Before the tap: only the eager stop is shown, and a "bus" More button is offered.
        assertEquals(setOf("bus"), vm.moreState.value)
        assertEquals(listOf("E"), shownIds(vm))

        vm.reveal("bus")
        advanceUntilIdle()
        // The revealed cluster's stop is fetched and shown; nothing is left to page, so no button.
        assertEquals(setOf("E", "MA"), shownIds(vm).toSet())
        assertTrue(vm.moreState.value.isEmpty())
    }

    @Test
    fun `a More tap during a part-shown cold load finishes it whole`() = runTest(dispatcher) {
        // The first eager stop is slow on the cold load only; "More" is tapped once the other is shown.
        val slow = CompletableDeferred<Unit>()
        var firstAsk = true
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "S" && firstAsk) {
                    firstAsk = false
                    slow.await()
                }
                return listOf(departure("victoria", "Victoria", 120))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("S", "S")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).statusPending)

        vm.reveal("bus")
        advanceUntilIdle()
        val done = vm.state.value as DeparturesUiState.Loaded
        assertEquals(setOf("E", "S", "MA"), done.stops.mapTo(HashSet()) { it.stopId })
        assertTrue(done.pendingStops.isEmpty())
        assertFalse(done.statusPending)
    }

    @Test
    fun `revealing a stop whose notice has resolved prunes its dismissal`() = runTest(dispatcher) {
        // The reconcile hook fires on the incremental "More" path too, not only full refreshes: a
        // revealed stop whose dismissed notice has since cleared must have its stale signature pruned.
        val backing = MutableStateFlow<Set<DismissedAlert>>(setOf(DismissedAlert("M1", "Bus Stop Closed")))
        val store = object : DismissedAlertsStore {
            override fun dismissed() = backing
            override suspend fun dismiss(alert: DismissedAlert) {
                backing.value = Dismissed.dismiss(backing.value, alert)
            }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                backing.value = Dismissed.reconcile(backing.value, live, checkedPlaces)
            }
        }
        // The revealed cluster M1's stop MA returns no disruption now (resolved); FakeClient defaults
        // to an empty (successful) disruption lookup, so the place counts as checked-and-clear.
        val vm = MainViewModel(
            twoStopClient(),
            listOf(StopRef("E", "E")),
            initialMore = listOf(clusterOf("M1", "MA" to "bus")),
            clock = { now },
            io = dispatcher,
            dismissedStore = store,
        )
        advanceUntilIdle()
        // The initial refresh queried only the eager stop E, not M1, so the M1 dismissal is untouched.
        assertEquals(setOf(DismissedAlert("M1", "Bus Stop Closed")), backing.value)

        vm.reveal("bus")
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))
        // Revealing MA (place M1) with a clear disruption lookup reconciles the resolved dismissal out.
        assertEquals(emptySet<DismissedAlert>(), backing.value)
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

    // A client that records every arrivals fetch, so a test can assert a "More" tap fetches only the
    // newly revealed stop and not the ones already shown.
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
    fun `reveal fetches only the newly revealed stop, not the ones already shown`() = runTest(dispatcher) {
        val client = CountingClient(
            mapOf(
                "E" to listOf(departure("victoria", "Victoria", 300)),
                "MA" to listOf(departure("central", "Central", 200)),
            ),
        )
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        // The initial refresh fetched the eager stop once.
        assertEquals(listOf("E"), client.arrivalsCalls)

        vm.reveal("bus")
        advanceUntilIdle()
        // The tap fetched only the newly revealed MA — E is not re-fetched (the point of the
        // incremental reveal: one page of requests per tap, not the whole shown set).
        assertEquals(listOf("E", "MA"), client.arrivalsCalls)
        assertEquals(setOf("E", "MA"), shownIds(vm).toSet())
    }

    @Test
    fun `a revealed stop whose fetch fails leaves the shown ones and flags partial, not an error`() =
        runTest(dispatcher) {
            val client = twoStopClient()
            val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
            advanceUntilIdle()
            assertEquals(listOf("E"), shownIds(vm))

            // The newly revealed stop's first fetch fails: the existing stop stays shown, the state
            // stays Loaded (not Error), and it's flagged partial rather than dropping E or blanking.
            client.failing += "MA"
            vm.reveal("bus")
            advanceUntilIdle()
            val loaded = vm.state.value as DeparturesUiState.Loaded
            assertEquals(listOf("E"), loaded.stops.map { it.stopId })
            assertTrue("the failed reveal is flagged partial", loaded.partialRefresh)
        }

    @Test
    fun `a revealed stop's clean status clears a stale disruption flag`() = runTest(dispatcher) {
        // The line-status verdict for L changes: disrupted at init, Good Service by the reveal.
        val client = object : TflClient {
            var lineDisrupted = true
            override suspend fun arrivals(stopId: String) = listOf(departure("L", "L", 200))
            override suspend fun lineStatuses(lineIds: Collection<String>) =
                if (lineDisrupted) {
                    listOf(status("L", 6, "Severe delays"))
                } else {
                    listOf(LineStatus("L", LineStatus.GOOD_SERVICE, "Good Service"))
                }
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = tierVm(
            client,
            listOf(StopRef("E", "E", lines = listOf(LineRef("L", "L", "bus")))),
            listOf(clusterOf("M1", "MA" to "bus")),
        )
        advanceUntilIdle()
        assertTrue("L starts flagged", (vm.state.value as DeparturesUiState.Loaded).lineStatuses.containsKey("L"))

        // The reveal's batch checks L and gets Good Service; the stale disrupted flag must clear,
        // not linger because the merge only appended disrupted entries.
        client.lineDisrupted = false
        vm.reveal("bus")
        advanceUntilIdle()
        assertFalse(
            "the reveal's clean verdict clears L's stale disruption flag",
            (vm.state.value as DeparturesUiState.Loaded).lineStatuses.containsKey("L"),
        )
    }

    @Test
    fun `an incremental reveal whose merged set is all aged redraws the widget`() = runTest(dispatcher) {
        // With nothing fresh anywhere in the merged set there's nothing to save, but the widget's
        // static RemoteViews must still be poked to recompute staleness (as refresh()'s no-save path
        // does) rather than ageing past the cutoff. E is kept aged (its arrivals fail over a stored
        // snapshot) and the revealed MA fails too, so the merged set carries no fresh arrivals.
        var redraws = 0
        val aged = now.minusSeconds(600)
        val store = FakeStore(DeparturesSnapshot(listOf(stopArrivals("E", "E", 300, aged)), aged))
        val client = FakeClient(
            mapOf(
                "E" to Result.failure(TflException.Offline(null)),
                "MA" to Result.success(listOf(departure("central", "Central", 200))),
            ),
        )
        val vm = MainViewModel(
            client,
            seedStops = listOf(StopRef("E", "E")),
            initialMore = listOf(clusterOf("M1", "MA" to "bus")),
            clock = { now },
            io = dispatcher,
            snapshotStore = store,
            redrawWidget = { redraws++ },
        )
        advanceUntilIdle()
        val before = redraws

        client.failing += "MA"
        vm.reveal("bus")
        advanceUntilIdle()
        assertTrue("the all-aged reveal path redraws the widget", redraws > before)
    }

    @Test
    fun `partial clears when a later reveal recovers an earlier failed stop`() = runTest(dispatcher) {
        val client = FakeClient(
            mapOf(
                "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                "MA" to Result.success(listOf(departure("a", "A", 200))),
                "MB" to Result.success(listOf(departure("b", "B", 200))),
                "MC" to Result.success(listOf(departure("c", "C", 200))),
            ),
        )
        val vm = tierVm(
            client,
            listOf(StopRef("E", "E")),
            listOf(clusterOf("A", "MA" to "bus"), clusterOf("B", "MB" to "bus"), clusterOf("C", "MC" to "bus")),
        )
        advanceUntilIdle()

        // First tap reveals a page (MA + MB); MA's fetch fails, so it's absent and the list is partial.
        client.failing += "MA"
        vm.reveal("bus")
        advanceUntilIdle()
        var loaded = vm.state.value as DeparturesUiState.Loaded
        assertTrue("MA" !in loaded.stops.map { it.stopId })
        assertTrue("a failed reveal is flagged partial", loaded.partialRefresh)

        // Second tap reveals MC and retries the still-missing MA (now succeeding); every revealed stop
        // is present and fresh, so the "some stops couldn't refresh" banner clears — it isn't stuck on
        // from the earlier failure.
        client.failing -= "MA"
        vm.reveal("bus")
        advanceUntilIdle()
        loaded = vm.state.value as DeparturesUiState.Loaded
        assertEquals(setOf("E", "MA", "MB", "MC"), loaded.stops.map { it.stopId }.toSet())
        assertFalse("partial clears once the recovered stop is shown", loaded.partialRefresh)
    }

    @Test
    fun `a successful reveal clears a prior total-failure banner`() = runTest(dispatcher) {
        // A saved aged snapshot, then a refresh that fails entirely (E offline): the aged E is kept
        // and the screen flags "couldn't refresh" (refreshFailure). A later "More" that reaches TfL
        // and gets a fresh stop must clear that banner — it was inherited through current.copy and
        // left stuck, contradicting Loaded's contract that it clears on the next fetch that gets
        // anything (Codex, PR #104).
        val aged = now.minusSeconds(600)
        val store = FakeStore(DeparturesSnapshot(listOf(stopArrivals("E", "E", 300, aged)), aged))
        val client = FakeClient(
            mapOf(
                "E" to Result.failure(TflException.Offline(null)),
                "MA" to Result.success(listOf(departure("central", "Central", 200))),
            ),
            // Both of E's requests fail, so nothing fresh comes back at all — a total failure that
            // sets refreshFailure (an arrivals-only failure would be a partial, with fresh data).
            disruptionsByStop = mapOf("E" to Result.failure(TflException.Offline(null))),
        )
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")), store = store)
        advanceUntilIdle()
        var loaded = vm.state.value as DeparturesUiState.Loaded
        assertEquals(
            "a total-failure refresh over the aged snapshot flags couldn't-refresh",
            DeparturesUiState.Error.Kind.OFFLINE,
            loaded.refreshFailure,
        )

        vm.reveal("bus")
        advanceUntilIdle()
        loaded = vm.state.value as DeparturesUiState.Loaded
        assertEquals(setOf("E", "MA"), loaded.stops.map { it.stopId }.toSet())
        assertNull("the successful reveal clears the stale total-failure banner", loaded.refreshFailure)
    }

    @Test
    fun `a reveal keeps an unverified kept stop flagged status-unknown`() = runTest(dispatcher) {
        // E's arrivals fail, so it's kept aged from the store and never status-verified this cycle —
        // it stays disruption-unknown. A "More" that adds a fully clean stop must NOT clear the
        // screen's "status unknown", because the recompute is over each stop's own provenance: the
        // kept, unchecked E is still unknown even though the newly revealed MA is clean.
        val aged = now.minusSeconds(600)
        val store = FakeStore(DeparturesSnapshot(listOf(stopArrivals("E", "E", 300, aged)), aged))
        val client = FakeClient(
            mapOf(
                "E" to Result.failure(TflException.Offline(null)),
                "MA" to Result.success(listOf(departure("central", "Central", 200))),
            ),
            // MA's line resolves clean; E's "victoria" is left undetermined, so E stays unknown.
            statuses = Result.success(listOf(status("central", LineStatus.GOOD_SERVICE, "Good Service"))),
        )
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")), store = store)
        advanceUntilIdle()
        assertTrue((vm.state.value as DeparturesUiState.Loaded).disruptionUnknown)

        vm.reveal("bus")
        advanceUntilIdle()
        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertEquals(setOf("E", "MA"), loaded.stops.map { it.stopId }.toSet())
        assertTrue(
            "the kept, unchecked stop keeps the screen status-unknown despite the clean reveal",
            loaded.disruptionUnknown,
        )
    }

    @Test
    fun `a reveal persists the merged fresh set even when its own new stops fail`() = runTest(dispatcher) {
        // Finding A: a "More" tap cancels the in-flight refresh (fetchJob.cancel), which can abort that
        // refresh's still-in-flight save. Rather than make the save NonCancellable — which would defeat
        // the relocation guard cancelFetch() relies on — the reveal keys its own save on the MERGED set,
        // not just this batch: so it carries the refresh's just-published fresh stops to disk even when
        // the revealed stop fails. Here E is fetched fresh and the revealed MA fails; the reveal still
        // saves, carrying fresh E, rather than skipping the save and stranding it.
        val store = FakeStore()
        val client = FakeClient(
            mapOf(
                "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                "MA" to Result.success(listOf(departure("central", "Central", 200))),
            ),
        )
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")), store = store)
        advanceUntilIdle()
        val savesAfterInit = store.saves.size

        client.failing += "MA"
        vm.reveal("bus")
        advanceUntilIdle()

        val loaded = vm.state.value as DeparturesUiState.Loaded
        assertEquals("the failed new stop is absent", listOf("E"), loaded.stops.map { it.stopId })
        assertTrue(
            "the reveal persisted the merged set because it still carries fresh arrivals",
            store.saves.size > savesAfterInit,
        )
        assertEquals("the saved snapshot carries the fresh eager stop", now, store.stored?.fetchedAt)
    }

    @Test
    fun `a reveal drops a stale status for a line the batch re-queried but TfL no longer reports`() =
        runTest(dispatcher) {
            // Finding D: E and the revealed MA both serve line L. L is disrupted at init; on the reveal
            // TfL omits L (returns no status). Because the reveal re-queried L (it's in attemptedLineIds)
            // and got no verdict, the stale disrupted entry must drop — the line is now unknown, surfaced
            // via disruptionUnknown, not still flagged from a status this fetch couldn't stand behind.
            val client = object : TflClient {
                var reportL = true
                override suspend fun arrivals(stopId: String) = listOf(departure("L", "L", 200))
                override suspend fun lineStatuses(lineIds: Collection<String>) =
                    if (reportL) listOf(status("L", 6, "Severe delays")) else emptyList()
                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val vm = tierVm(
                client,
                listOf(StopRef("E", "E", lines = listOf(LineRef("L", "L", "bus")))),
                listOf(busClusterOf("M1", "MA", "L")),
            )
            advanceUntilIdle()
            assertTrue("L starts flagged", (vm.state.value as DeparturesUiState.Loaded).lineStatuses.containsKey("L"))

            client.reportL = false
            vm.reveal("bus")
            advanceUntilIdle()
            val loaded = vm.state.value as DeparturesUiState.Loaded
            assertFalse(
                "the reveal re-queried L and TfL omitted it, so the stale disrupted status drops",
                loaded.lineStatuses.containsKey("L"),
            )
            assertTrue("L is now unverified, so the screen says status unknown", loaded.disruptionUnknown)
        }

    @Test
    fun `a reconcile to the same set keeps a revealed cluster fetched`() = runTest(dispatcher) {
        val vm = tierVm(twoStopClient(), listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        vm.reveal("bus")
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))

        // The same nearby set, re-supplied (a plain relocate that keeps the clusters) — the reveal
        // survives, since the ViewModel is keyed on the whole set, not rebuilt.
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))
    }

    @Test
    fun `a revealed cluster survives a promotion into eager and a later demotion`() = runTest(dispatcher) {
        val vm = tierVm(twoStopClient(), listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        vm.reveal("bus")
        advanceUntilIdle()

        // A small move: M1 (MA) crosses into the eager pair, E drops to `more`. The whole cluster
        // set is unchanged, so MA stays fetched — now via the eager tier — while the demoted E
        // leaves the shown set and offers its own "More" button.
        vm.reconcile(newEager = listOf(clusterOf("M1", "MA" to "bus")), newMore = listOf(clusterOf("EC", "E" to "bus")))
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))
        assertTrue("E" !in shownIds(vm))
        assertEquals(setOf("bus"), vm.moreState.value)

        // Move back: M1 demotes to `more` and E returns to eager, still the same whole set. M1's
        // reveal identity was retained while it was eager, so it stays expanded (MA fetched) rather
        // than reverting to a "More" button — the eager/more boundary shift is survived both ways.
        vm.reconcile(newEager = listOf(clusterOf("EC", "E" to "bus")), newMore = listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))
        assertTrue(vm.moreState.value.isEmpty())
    }

    @Test
    fun `a relocation that drops a revealed cluster prunes its stop synchronously`() = runTest(dispatcher) {
        val vm = tierVm(twoStopClient(), listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        vm.reveal("bus")
        advanceUntilIdle()
        assertTrue("MA" in shownIds(vm))

        // Walk on: the fresh fix no longer offers M1. The departed stop must leave the shown list AT
        // ONCE — before the re-fetch coroutine runs — so it can't linger with stale departures.
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList())
        assertTrue("MA" !in shownIds(vm))
        assertTrue("E" in shownIds(vm))
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
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        vm.reveal("bus")
        advanceUntilIdle()
        // E's next refresh fails, so it is kept at its older age and named.
        failE = true
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("E"), (vm.state.value as DeparturesUiState.Loaded).partialStops.values.map { it.name })

        // MA departs; E, still failing, stays named through the pending re-fetch.
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = emptyList())
        val pruned = vm.state.value as DeparturesUiState.Loaded
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
        val vm = tierVm(client, listOf(StopRef("E", "E"), StopRef("F", "F")), listOf(clusterOf("M1", "MA" to "bus")))
        advanceUntilIdle()
        vm.reveal("bus")
        advanceUntilIdle()
        assertEquals(listOf("F"), (vm.state.value as DeparturesUiState.Loaded).partialStops.values.map { it.name })

        // MA departs; F has no row, but it is still fetched and still failing, so it stays named.
        vm.reconcile(newEager = eagerOf("E" to "bus", "F" to "bus"), newMore = emptyList())
        val pruned = vm.state.value as DeparturesUiState.Loaded
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
            listOf(StopRef("E", "E"), StopRef("F", "F"), StopRef("G", "G")),
            initialMore = listOf(clusterOf("M1", "MA" to "bus")),
            clock = { now },
            io = dispatcher,
            stopDistanceMeters = mapOf("E" to 50.0, "F" to 100.0, "G" to 300.0),
        )
        advanceUntilIdle()
        vm.reveal("bus")
        advanceUntilIdle()
        assertEquals(listOf("F", "G"), (vm.state.value as DeparturesUiState.Loaded).partialStops.values.map { it.name })

        // Walk on: G is now the nearer of the two, and MA departs.
        vm.reconcile(
            newEager = eagerOf("E" to "bus", "F" to "bus", "G" to "bus"),
            newMore = emptyList(),
            newDistanceMeters = mapOf("E" to 50.0, "F" to 300.0, "G" to 100.0),
        )
        val pruned = vm.state.value as DeparturesUiState.Loaded
        assertEquals(listOf("G", "F"), pruned.partialStops.values.map { it.name })
    }

    @Test
    fun `a revealed cluster losing a member prunes it synchronously`() = runTest(dispatcher) {
        val client = FakeClient(
            mapOf(
                "E" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                "PA" to Result.success(listOf(departure("central", "Central", 200))),
                "PB" to Result.success(listOf(departure("central", "Central", 260))),
            ),
        )
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "PA" to "bus", "PB" to "bus")))
        advanceUntilIdle()
        vm.reveal("bus")
        advanceUntilIdle()
        assertTrue("PA" in shownIds(vm) && "PB" in shownIds(vm))

        // The same cluster (same key) now has only PA — PB's pole crossed the radius. PB leaves at
        // once; PA stays. Same-key clusters whose MEMBERS changed are reconciled, not just dropped.
        vm.reconcile(newEager = eagerOf("E" to "bus"), newMore = listOf(clusterOf("M1", "PA" to "bus")))
        assertTrue("PB" !in shownIds(vm))
        advanceUntilIdle()
        assertTrue("PA" in shownIds(vm))
        assertTrue("PB" !in shownIds(vm))
    }

    @Test
    fun `a pruned set is persisted even when the refetch gets no fresh arrivals`() = runTest(dispatcher) {
        val store = FakeStore()
        val client = twoStopClient()
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")), store)
        advanceUntilIdle()
        vm.reveal("bus")
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
        val vm = tierVm(client, listOf(StopRef("E", "E")), listOf(clusterOf("M1", "MA" to "bus")), store)
        advanceUntilIdle()
        vm.reveal("bus")
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

            // Unstarred: no destination, nothing kept.
            vm.setJourneyDestinations(emptyList())
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
        vm.reconcile(clusters, emptyList(), mapOf(oxcId to 900.0, ksxId to 100.0))
        // At once, before any refetch runs.
        val now0 = (vm.state.value as DeparturesUiState.Loaded).stops.associateBy { it.stopId }
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
        vm.setJourneyStops(
            listOf(StopRef(ksxId, "King's Cross St. Pancras", lines = listOf(LineRef("northern", "Northern", "tube")))),
        )
        advanceUntilIdle()
        assertTrue("northern" in client.statusCalls.last())
    }

    @Test
    fun `an origin that gains a declared line is fetched again, so its status is checked`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(ksxId, "King's Cross St. Pancras", lines = listOf(LineRef("northern", "Northern", "tube")))))
        advanceUntilIdle()
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
        assertTrue("piccadilly" in client.statusCalls.last())
    }

    @Test
    fun `a nearby stop that becomes a journey origin is fetched again with the journey's lines`() = runTest(dispatcher) {
        val client = ReuseCountingClient()
        val vm = MainViewModel(client, listOf(seeds.first()), clock = { now }, io = dispatcher)
        advanceUntilIdle()
        vm.setJourneyStops(listOf(StopRef(oxcId, "Oxford Circus", lines = listOf(LineRef("central", "Central", "tube")))))
        advanceUntilIdle()
        assertTrue("central" in client.statusCalls.last())
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
        assertTrue("piccadilly" in client.statusCalls.last())
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
}
