package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.RailFeed
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.FoldChoice
import app.stopdash.domain.DismissalMarks
import app.stopdash.domain.Dismissed
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.HubInfo
import app.stopdash.domain.HubInfoCache
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.lineAlertKey
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.Snapshot
import app.stopdash.domain.Terminating
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StarredRowSet
import app.stopdash.domain.StarredRowsStore
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.stampOf
import app.stopdash.domain.LoadStats
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.StopDisruptionBatch
import app.stopdash.domain.TflClient
import app.stopdash.domain.UntimedTrain
import app.stopdash.domain.stopPlaceKey
import app.stopdash.domain.TflException
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneys
import app.stopdash.domain.WidgetJourneysReport
import app.stopdash.domain.WidgetRefresh
import app.stopdash.domain.Workers
import app.stopdash.telemetry.UsageEvents
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A stop to show, until Phase 2's watched-stops persistence replaces the seed set.
 * [lines] is the stop's served lines, carried so a disrupted line with no predictions
 * still surfaces as a status row (SPEC *Departures*); empty means only predicted lines
 * are known for the stop.
 */
data class StopRef(
    val id: String,
    val name: String,
    val lines: List<LineRef> = emptyList(),
    // The stop's cluster (TfL `stationNaptan` else display name) for per-place grouping (SPEC D8);
    // blank groups the stop alone. Set from the nearby lookup ([StopLocation.clusterId]).
    val clusterId: String = "",
    // The stop's interchange (TfL `hubNaptanCode`, [StopLocation.hubId]): `HUBKGX` ties King's
    // Cross and St Pancras. Blank for a stop in no hub. When the stop has a disruption, its hub
    // name is resolved so the near-me alert titles by the interchange (SPEC *Disruptions*).
    val hubId: String = "",
    // The bus pole's letter, bearing, and "towards" ([StopLocation]), for the per-pole bus header
    // (SPEC D8). Blank for a station or a letter-less bus stop, and for a watched stop (whose seed
    // carries none yet — a follow-up).
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
)

/** How recently a stop's arrivals must have come back for a refresh to carry it over without a
 *  request (see MainViewModel.recentlyFetched): the shared [ArrivalsCache.TTL], so the list reuses
 *  its own fetches as it does another screen's. Under the 60 s auto-refresh, so a scheduled refresh
 *  still refetches everything; a re-locate or return to the app soon after reuses them, and a
 *  pull-to-refresh never does ([MainViewModel.forceNextFetch]). */
internal val ARRIVALS_REUSE: Duration = ArrivalsCache.TTL

/** How long a stop's successful closure lookup is reused (see MainViewModel.disruptionCache). */
internal val DISRUPTION_REUSE: Duration = Duration.ofMinutes(5)

/**
 * How long a line's status is reused before it's asked for again: 90 s, so the 60 s auto-refresh
 * re-checks line status every other cycle rather than every one. A suspension still shows within
 * about two minutes, well inside the 5-minute staleness window (SPEC *Refresh*).
 */
internal val LINE_STATUS_REUSE: Duration = Duration.ofSeconds(90)

/**
 * How long the auto-refresh carries over a stop past the walking reach (500 m): 90 s, so it's
 * refetched every other minute rather than every minute. Its countdowns stay within about two
 * minutes old, well inside the 5-minute staleness window, and it's refetched on any user refresh.
 */
internal val FAR_ARRIVALS_REUSE: Duration = Duration.ofSeconds(90)

/**
 * How long a cold load with nothing on screen holds back its first partial list (SPEC *Freshness →
 * Cold load*): most loads are back well inside it and paint once, whole; a slower stop then shows
 * as a "Loading" card rather than holding the rest.
 */
internal const val FIRST_PAINT_GRACE_MS = 2_000L

/**
 * Owns the departures snapshot the screen renders (SPEC staleness contract): the fetch
 * runs off the main thread in [viewModelScope]; the screen only ever reads [state].
 * A failed stop is logged and skipped so one bad stop doesn't blank the others; only
 * when *every* stop fails does the screen show an [DeparturesUiState.Error], mapped
 * from the failure so it reads honestly (offline / rate-limited / can't-reach-TfL)
 * rather than as an empty list (SPEC principles 1–2).
 *
 * [clock] and [io] are injected so the state machine is JVM-testable with a fixed clock
 * and a test dispatcher; [warn] is the sanitized failure log (the shared on-device
 * logger lands with its own Phase 1 item — until then this is the seam it plugs into).
 */
class MainViewModel(
    private val client: TflClient,
    seedStops: List<StopRef>,
    // The farther "more" clusters (SPEC *Finding stops → Near me now*): not fetched, but places the
    // rider may be at for [Terminating]. Empty for a watched-stops view or a nearby set with nothing
    // beyond the eager tier.
    initialMore: List<NearbySelection.NearbyCluster> = emptyList(),
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Where work that grows with the board runs (AGENTS.md *Main thread: read and dispatch only*):
    // a cold load's progress as its stops land ([fetchBatch]'s onProgress), and the dismissal checks.
    // [viewModelScope] is the main thread, which only publishes what's worked out here.
    private val compute: CoroutineDispatcher = Workers.compute,
    // Persists the last-good snapshot across sessions and to the widget. No-op by default so
    // tests and an unwired build run identically minus the restore.
    private val snapshotStore: SnapshotStore = SnapshotStore.NONE,
    // Persists which rows the user has starred (the ranking overlay). No-op by default, so
    // tests and an unwired build run identically minus starring.
    private val starredStore: StarredRowsStore = StarredRowsStore.NONE,
    // Persists which stop-closure alerts the user has dismissed (hidden until their text changes).
    // No-op by default, so tests and an unwired build run identically minus dismissing.
    private val dismissedStore: DismissedAlertsStore = DismissedAlertsStore.NONE,
    // No-op by default: the shared on-device logger is deferred until `docs/PRIVACY.md`
    // describes what it carries (both are their own Phase 1 items), so nothing is logged
    // in production until then. The seam stays for tests and that later wiring.
    private val warn: (String) -> Unit = {},
    // Best-effort widget redraw: poked after a star toggle (so the widget's pinned order updates
    // at once — SPEC D8) AND after a completed refresh that wrote nothing to the store (a failed
    // or aged-only cycle; a store write pokes the widget itself), so the static RemoteViews recompute the snapshot's age from the current clock and
    // withhold stale countdowns (SPEC D4) rather than freezing at the last save's stamp. Doesn't
    // persist anything. No-op by default; MainActivity supplies the widget update.
    private val redrawWidget: suspend () -> Unit = {},
    // How recently a stop must have been fetched for a refresh to carry it over without a request,
    // and how long a stop's closure lookup is reused ([recentlyFetched], [disruptionCache]). Zero —
    // always refetch — by default, so tests drive them explicitly; the app passes [ARRIVALS_REUSE]
    // and [DISRUPTION_REUSE].
    private val arrivalsReuse: Duration = Duration.ZERO,
    private val disruptionReuse: Duration = Duration.ZERO,
    // Each stop's last SUCCESSFUL stop-level disruption lookup (a closure, a moved stop) and when it
    // was made, reused for [disruptionReuse] rather than re-requested every refresh: a closure
    // changes over hours, and the lookup is half of every stop's request cost against TfL's rate
    // budget. A failure is never cached, so it's retried next refresh. The line status — the fast-
    // moving signal — is still checked every refresh. The app shares one with a trip
    // ([StopClosureCache.SHARED]), so each reuses the other's lookups; a test gets its own.
    private val disruptionCache: StopClosureCache = StopClosureCache(),
    // How long a line's determined status is reused ([lineStatusCache]); zero (always ask) by
    // default for tests, [LINE_STATUS_REUSE] in the app.
    private val lineStatusReuse: Duration = Duration.ZERO,
    // Each nearby stop's distance from the fix this set was resolved at (empty for a watched list),
    // and how long an automatic refresh carries over a stop past the walking reach
    // ([NearbySelection.EAGER_RADIUS_METERS]) — [FAR_ARRIVALS_REUSE] in the app, zero in tests.
    // Updated by [reconcile] when a relocation keeps the set, so walking across the 500 m line moves
    // a stop between the near and far bands.
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    private val farArrivalsReuse: Duration = Duration.ZERO,
    // The stops' last arrivals, shared with the other screens (SPEC *Freshness → Shared arrivals*):
    // a stop another screen fetched within [ArrivalsCache.TTL] is shown from it rather than asked for
    // again. Null (tests) reads none; the app passes [ArrivalsCache.SHARED].
    private val sharedArrivals: ArrivalsCache? = null,
    // Resolved interchange info (hubId → name + member aliases), so a hub with a disruption is
    // looked up once and reused across refreshes and across the stops sharing it (King's Cross and
    // St Pancras both resolve `HUBKGX` from one call). Only a real result is cached here — a failed
    // or blank lookup is retried on a later refresh rather than pinned. Within a single refresh a
    // failure is memoized separately (see `resolveHubInfo`), so a failing hub is not re-requested
    // once per member. Shared with a trip on the way ([HubInfoCache.SHARED] in
    // the app); a test gets its own.
    private val hubNames: HubInfoCache = HubInfoCache(),
    // Monotonic milliseconds for timing a fetch, and the shared rate limiter's running total of
    // time spent waiting — both only feed the per-fetch debug-log line ([LoadStats]).
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val rateWaitMillis: () -> Long = { 0L },
    // Sink for the per-fetch stats line — kept apart from [warn], which carries only failures.
    private val logStats: (String) -> Unit = {},
    // Whether this list speaks for the widget's journey pins. The near-me list does; a searched
    // station's (SPEC *Finding stops*) doesn't, and must not take the widget-journeys turn from the
    // near-me model it sits beside, or that model's later journey writes would stand down.
    ownsWidgetJourneys: Boolean = true,
    // Where a failed star or dismiss write is reported ([starWriteFailed], [dismissWriteFailed]).
    // Its own by default; the app passes one shared by the near-me list and a searched station's
    // page, so a write that fails after the station page has closed (the write outlives it — see
    // [toggleStar]) still surfaces on the next list shown rather than on a model no screen reads.
    writeFailures: WriteFailures = WriteFailures(),
    // Told of each saved star toggle, with its row, inside the same must-finish write: the app
    // records the row's place so "Find a station" can list the star by name later (SPEC *Finding
    // stops*). Handles its own failures; nothing by default.
    private val onStarToggled: suspend (DepartureRow) -> Unit = {},
    // A setting that changes what a stop's departures include (the National Rail key): its current
    // value is ignored, and each later change refetches every stop at once, none carried over, so
    // adding or clearing the key shows without waiting for the next auto-refresh.
    departureSourceChanges: Flow<Any?> = emptyFlow(),
    // The modes and lines the rider has hidden right now (SPEC *Finding stops → Hiding a mode*): with
    // National Rail among them, a station's board isn't asked for where nothing shown runs on it.
    private val hiddenModes: () -> Set<String> = { emptySet() },
    // [hiddenModes] as it changes: its current value is ignored, and each later change works the
    // widget's line choices out again, since a hidden mode changes which stop the list shows a line from.
    hiddenModeChanges: Flow<Any?> = emptyFlow(),
    // Usage events, categories only (UsageEvent): a star or unstar, never which row. Sent only while
    // the rider has opted in.
    private val usage: (UsageEvent) -> Unit = UsageEvents::log,
) : ViewModel() {
    // The near-me tiers, updatable IN PLACE so a relocation that keeps the same nearby set can
    // reconcile them without rebuilding this ViewModel. The eager tier is fetched and shown; the
    // `more` tier only says where else the rider may be ([nearbyPlaces]).
    private var eagerStops: List<StopRef> = seedStops
    private var stopDistanceMeters: Map<String, Double> = stopDistanceMeters

    // Each stop's distance as this model last took it ([remeasure]), for tests.
    internal val distanceMeters: Map<String, Double> get() = stopDistanceMeters
    private var more: List<NearbySelection.NearbyCluster> = initialMore

    private fun nearbyPlaces(): List<Terminating.Place> = nearbyPlacesOf(eagerStops, more, stopDistanceMeters)

    // The near-me set actually fetched and shown: the eager tier.
    private val nearStops: List<StopRef>
        get() = eagerStops

    // The origins of the starred journeys (SPEC *Journeys*), fetched alongside the near-me stops so a
    // journey card has its departures. One not already near is fetched but never saved to the widget
    // snapshot, which shows only the nearby set ([journeyOnly]).
    private var journeyStops: List<StopRef> = emptyList()

    // A same-set reconcile's refresh held for the screen's next journey-stop report (see reconcile).
    private var refreshAwaitsJourneyStops = false

    private val fetchedStops: List<StopRef>
        get() = fetchedOf(nearStops, journeyStops)

    // A same-set reconcile worked out on the worker and not yet applied: a refresh waits for it
    // ([reconcile]), and the screen's journey-stop report is noted for it.
    private var reconcileJob: Job? = null
    private var reconcilePending = false
    private var reportedDuringReconcile = false
    // The journey stops the screen reported while it was on the worker: newer than its journey checks.
    private var journeyStopsSetDuringReconcile = false

    // The widget's journey pins (SPEC *Journeys*) live in the stored snapshot, not here: each report
    // from the screen is applied to what is stored, atomically ([SnapshotStore.updateWidgetJourneys]),
    // and the app's own saves leave them alone — so no copy held here can go stale against another
    // writer's. The latest report, and whether it still needs writing (a write failed).
    private var widgetJourneysReport: WidgetJourneysReport? = null
    private var widgetJourneysPending = false
    // This ViewModel's place in line: a newer one (a relocation made it) owns the widget's journeys,
    // so this one's writes stand down rather than land an older report over the newer one's.
    private val widgetJourneysGeneration = if (ownsWidgetJourneys) WidgetJourneysWrites.next() else NO_WIDGET_JOURNEYS

    /**
     * [snapshot] as the widget may show it: the nearby stops [nearIds] and the journey origins
     * [journeyIds], both captured when the fetch began — kept positively, so an origin a flip has
     * since dropped can't slip through a fetch in flight. A journey origin that isn't nearby is
     * marked journey-only: the widget shows just its journey's departures, and the store keeps it
     * only while a pin starts from it.
     */
    private fun forWidget(
        snapshot: DeparturesSnapshot,
        nearIds: Set<String>,
        journeyIds: Set<String>,
    ): DeparturesSnapshot {
        val kept = snapshot.stops.filter { it.stopId in nearIds || it.stopId in journeyIds }
        return DeparturesSnapshot(
            stops = kept,
            fetchedAt = snapshot.fetchedAt,
            journeyOnlyStopIds = kept.mapTo(HashSet()) { it.stopId } - nearIds,
            // A nearby stop with no arrivals at all failed with nothing to fall back on
            // ([Snapshot.mergeStop] drops it), so the widget must not read the rest as complete.
            missingStopIds = nearIds - kept.mapTo(HashSet()) { it.stopId },
            // Each kept line's last determined status, stamped with when TfL gave it, so the widget
            // marks a disrupted service and withholds the mark at the staleness threshold (SPEC D3/D4).
            lineStatuses = widgetLineChecks(kept),
            // Nearest the rider now first, so the widget shows a line once, from its nearest stop, as
            // this list does. The nearby stops only: a journey-only origin isn't one the rider is near.
            nearestFirst = nearestFirstOf(kept.map { it.stopId }.filter { it in nearIds }, stopDistanceMeters),
        )
    }

    /** The [lineStatusCache] entries for the lines [stops] show, as the widget snapshot's checks. */
    private fun widgetLineChecks(stops: List<StopArrivals>): Map<String, LineStatusCheck> =
        LineStatusCheck.linesOf(stops).mapNotNull { id ->
            val verdict = lineStatusCache[id]?.let { (at, status) -> LineStatusCheck(status, at) }
            val omitted = lineStatusOmitted[id]?.let { LineStatusCheck.noVerdict(id, it) }
            // At most one is held: each answer clears the other kind, so the latest wins whatever
            // the clock did in between.
            (verdict ?: omitted)?.let { id to it }
        }.toMap()

    /**
     * Whether a fetch's widget snapshot is worth saving: judged on the nearby stops whenever any were
     * asked for — even if none came back — so a journey origin's fresh arrivals alone never save
     * failed nearby stops over the last good ones.
     */
    private fun widgetJudged(stops: List<StopArrivals>, nearIds: Set<String>): List<StopArrivals> =
        if (nearIds.isEmpty()) stops else stops.filter { it.stopId in nearIds }

    /**
     * The starred journeys' [keys], their cards' latest [checks], and the stop each is shown from
     * ([shownFrom], its direction — a journey flipped while its route can't place it yet reports no
     * check, so its old pin goes until one does), for the widget's pins ([WidgetJourneys.apply]).
     */
    fun setWidgetJourneys(
        keys: Set<String>,
        checks: List<WidgetJourneyCheck>,
        shownFrom: Map<String, String> = emptyMap(),
        // Each settled journey's boarding keys, its own and its neighboring poles' ([WidgetJourneysReport.boarding]).
        boarding: Map<String, Set<String>> = emptyMap(),
    ) {
        val report = WidgetJourneysReport(keys, checks, shownFrom, boarding)
        if (report == widgetJourneysReport) return
        widgetJourneysReport = report
        writeWidgetJourneys()
    }

    /**
     * Applies the latest report to the stored snapshot, with the fetched copy of each checked
     * journey's origin (one the store doesn't hold yet joins with it). Writes run one at a time,
     * process-wide, each taking the report current when it runs; a failure is retried after the
     * next fetch.
     */
    private fun writeWidgetJourneys() {
        widgetJourneysPending = true
        viewModelScope.launch {
            WidgetJourneysWrites.lock.withLock {
                if (!widgetJourneysPending || WidgetJourneysWrites.latest() != widgetJourneysGeneration) return@withLock
                val report = widgetJourneysReport ?: return@withLock
                val originIds = report.checks.mapTo(HashSet()) { it.originId }
                val origins = (_state.value as? DeparturesUiState.Loaded)?.stops.orEmpty().filter { it.stopId in originIds }
                widgetJourneysPending = false
                try {
                    withContext(io) { snapshotStore.updateWidgetJourneys(report, origins) }
                } catch (e: CancellationException) {
                    widgetJourneysPending = true
                    throw e
                } catch (e: Exception) {
                    widgetJourneysPending = true
                    warn("widget journeys save failed: ${reason(e)}")
                }
            }
        }
    }

    /**
     * The journey origins to fetch alongside the near-me stops (SPEC *Journeys*). A new origin not
     * yet fetched starts a refresh; the same set again, or one already on hand, doesn't. A fetch in
     * flight is for the old set and would replace the state without these origins (a quick flip
     * and flip back), so it is superseded too. So is a dropped origin (an unstar): the refresh
     * prunes it, and with it any "couldn't refresh" warning only that stop caused.
     */
    fun setJourneyStops(stops: List<StopRef>) {
        if (stops.toSet() == journeyStops.toSet()) return
        if (reconcilePending) journeyStopsSetDuringReconcile = true
        val newIds = stops.mapTo(HashSet()) { it.id }
        val nearIds = nearStops.mapTo(HashSet()) { it.id }
        val dropped = journeyStops.any { it.id !in newIds && it.id !in nearIds }
        // A fetched stop that now declares a line it didn't (an origin's route came in after its first
        // fetch, or a nearby stop became an origin) is fetched again, so that line's status — a
        // suspension with no predictions — is checked.
        val declaredBefore = fetchedStops.associate { s -> s.id to s.lines.mapTo(HashSet()) { it.id } }
        journeyStops = stops
        val linesAdded = fetchedStops.any { s ->
            s.id in declaredBefore && !declaredBefore.getValue(s.id).containsAll(s.lines.map { it.id })
        }
        val shown = (_state.value as? DeparturesUiState.Loaded)?.stops?.mapTo(HashSet()) { it.stopId }.orEmpty()
        // A station whose board was left out that a newly starred National Rail journey now needs it
        // at, even one already declaring the journey's line: its trains show at once.
        val hidden = hiddenModes()
        val boardWanted = boardSkipped.any { HiddenModes.wantsRailBoard(hidden, starredLines(it)) }
        if (
            refreshAwaitsJourneyStops || dropped || linesAdded || boardWanted || fetchJob?.isActive == true ||
            stops.any { it.id !in shown }
        ) {
            refresh()
        }
    }

    // The lines the starred journeys take from [stopId]: hiding doesn't reach them.
    private fun starredLines(stopId: String): List<LineRef> = journeyStops.filter { it.id == stopId }.flatMap { it.lines }

    // The far ends of the starred journeys as shown (SPEC *Journeys*): only their stop-level
    // disruptions (a closure, a moved stop) are checked, not their departures, so a journey card can
    // say its destination is closed. Checked with every refresh, reusing [disruptionCache].
    private var journeyDestinations: List<StopRef> = emptyList()
    private var destinationJob: Job? = null
    private val _journeyDestinationStops = MutableStateFlow<List<StopArrivals>>(emptyList())

    /** Each journey destination's last successful disruption check, as a departure-less stop. */
    val journeyDestinationStops: StateFlow<List<StopArrivals>> = _journeyDestinationStops.asStateFlow()

    // The destinations whose check failed with no earlier success to fall back on: unknown, which the
    // card says rather than pass them off as open (SPEC principle 2).
    private val _journeyDestinationsUnknown = MutableStateFlow<Set<String>>(emptySet())
    val journeyDestinationsUnknown: StateFlow<Set<String>> = _journeyDestinationsUnknown.asStateFlow()

    /** The journeys' far ends to check for a closure; a change checks them at once. */
    fun setJourneyDestinations(stops: List<StopRef>) {
        if (stops.toSet() == journeyDestinations.toSet()) return
        journeyDestinations = stops
        val ids = stops.mapTo(HashSet()) { it.id }
        _journeyDestinationStops.value = _journeyDestinationStops.value.filter { it.stopId in ids }
        _journeyDestinationsUnknown.value = _journeyDestinationsUnknown.value.filterTo(HashSet()) { it in ids }
        checkJourneyDestinations()
    }

    // Each destination closure request in flight. Run in [viewModelScope], not the check's own job, so
    // a check restarted by a changed destination set (routes arriving one by one) waits on the same
    // request rather than cancel it and ask again. A success is cached as it lands; each leaves this
    // map when done, so a failure is asked again next time.
    private val destinationRequests = HashMap<String, Deferred<Result<StopClosureCache.Lookup>>>()

    /** Starts a closure request for each of [ids] (bus poles batched, as in [fetchBatch]). */
    private fun requestDestinationDisruptions(ids: List<String>) {
        val (poles, others) = ids.partition(StopDisruptionBatch::isPole)
        val requests = poles.chunked(StopDisruptionBatch.MAX_PER_REQUEST).flatMap { batchIds ->
            val batch = viewModelScope.async {
                // In line as asked, not as answered: a lookup asked later (a trip's) is the newer.
                val ask = disruptionCache.ask(clock(), dismissedStore.mark())
                val answer = runCatchingTfl { withContext(io) { client.poleDisruptions(batchIds) } }
                    // Logged here, not by the check: a request can outlive the check that asked for it.
                    .onFailure { e -> batchIds.forEach { warn("destination disruption fetch failed for stop $it: ${reason(e)}") } }
                // Each shown as the cache settles it: a later lookup (a trip's) wins over this one,
                // failed or not.
                batchIds.associateWith { id -> disruptionCache.settle(id, ask, answer.map { it[id].orEmpty() }) }
            }
            batchIds.map { id -> id to viewModelScope.async { batch.await().getValue(id) } }
        } + others.map { id ->
            id to viewModelScope.async {
                val ask = disruptionCache.ask(clock(), dismissedStore.mark())
                val answer = runCatchingTfl { withContext(io) { client.stopDisruptions(id) } }
                    .onFailure { e -> warn("destination disruption fetch failed for stop $id: ${reason(e)}") }
                disruptionCache.settle(id, ask, answer)
            }
        }
        for ((id, request) in requests) {
            destinationRequests[id] = request
            request.invokeOnCompletion { if (destinationRequests[id] === request) destinationRequests.remove(id) }
        }
    }

    /**
     * Checks each journey destination's stop-level disruption: from [disruptionCache] within
     * [disruptionReuse], else asked for (bus poles batched, as in [fetchBatch]). A failed check keeps
     * the destination's last known result and is logged; the card makes no claim either way.
     */
    private fun checkJourneyDestinations() {
        destinationJob?.cancel()
        val stops = journeyDestinations
        if (stops.isEmpty()) return
        val fetch = fetchJob
        destinationJob = viewModelScope.launch {
            // A destination the fetch in flight also covers (another journey's origin, a nearby stop)
            // is checked once: after it, from the cache it filled.
            // One that fetch just failed on isn't asked again at once: it counts as failed here too.
            val joinedFetchAt = if (fetch?.isActive == true) {
                fetch.join()
                lastFetchAt
            } else {
                null
            }
            val now = clock()
            // The dismissals so far, before anything is asked ([reconcileDismissals]), and each stop's
            // answer's own as it's taken: a cached or shared one may be older than this check.
            val since = dismissedStore.mark()
            val stopAsks = HashMap<String, StopClosureCache.Ask>()
            fun lookup(id: String) = disruptionCache[id]?.takeIf { isWithin(it.at, now, disruptionReuse) }
            fun cached(id: String) = lookup(id)?.notices
            fun failedInFetch(id: String) = joinedFetchAt != null && disruptionFailedAt[id] == joinedFetchAt
            val toAsk = stops.map { it.id }.distinct().filter { cached(it) == null && !failedInFetch(it) }
            requestDestinationDisruptions(toAsk.filter { it !in destinationRequests })
            // Taken before any await: a request that finishes leaves the map.
            val requests = toAsk.associateWith { destinationRequests.getValue(it) }
            val fetched: Map<String, Result<StopClosureCache.Lookup>> = requests.mapValues { (_, request) -> request.await() }
            val prior = _journeyDestinationStops.value.associateBy { it.stopId }
            val failed = HashSet<String>()
            val checked = ArrayList<StopArrivals>()
            _journeyDestinationStops.value = stops.distinctBy { it.id }.mapNotNull { stop ->
                if (cached(stop.id) == null && failedInFetch(stop.id)) {
                    // The fetch logged it already.
                    failed += stop.id
                    return@mapNotNull prior[stop.id]
                }
                val disruptions = lookup(stop.id)?.also { stopAsks[stop.id] = it.ask }?.notices ?: fetched[stop.id]?.fold(
                    // Kept by the request as it landed; this is what the cache held then.
                    onSuccess = { found ->
                        stopAsks[stop.id] = found.ask
                        found.notices
                    },
                    onFailure = {
                        // Logged by the request.
                        failed += stop.id
                        return@mapNotNull prior[stop.id]
                    },
                ).orEmpty()
                // Its own stop as the place: a check covers only this stop, so only this stop's
                // dismissal can be reconciled (below) — an area-wide one could stay hidden forever. At
                // worst the same notice on the near-me list is dismissed separately; never hidden.
                StopArrivals(stop.id, stop.name, emptyList(), SteadyClock.stamp(now), disruptions = disruptions)
                    .also { checked += it }
            }
            // Every failed check is unknown now, even with an earlier result still shown: that result
            // may be out of date, so the card says it couldn't check rather than pass it off as current.
            _journeyDestinationsUnknown.value = failed
            // A dismissed destination closure that has since cleared is forgotten, so the same notice
            // recurring later shows again; a place whose check failed keeps its dismissals.
            reconcileDismissals(stops.distinctBy { it.id }.map { it.copy(clusterId = "", hubId = "") }, checked, emptyMap(), emptySet(), failed, since, destinationSettles, stopAsks)
        }
    }

    private val _state = MutableStateFlow<DeparturesUiState>(DeparturesUiState.Loading)
    val state: StateFlow<DeparturesUiState> = _state.asStateFlow()

    // The near-me stops this model loads ([nearStops]), for the farther-station cards to count as
    // reached. Published from here, the one owner of the tiers, so a same-set reconcile that moves a
    // cluster across the eager/more boundary reaches the cards too; the screen's own copy of the
    // tiers goes stale on those.
    private val _shownNearStops = MutableStateFlow(nearStops)
    val shownNearStops: StateFlow<List<StopRef>> = _shownNearStops.asStateFlow()

    // Drives the pull-to-refresh indicator (SPEC D6); true only while a fetch is in flight.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // The starred set the screen pins to the top (SPEC D8). Collected from the store so a
    // toggle re-orders the list at once; an Unavailable set (a newer-version file this build
    // can't read) pins nothing rather than guessing — the stars are preserved on disk.
    private val _starred = MutableStateFlow<Set<StarredRow>>(emptySet())
    val starred: StateFlow<Set<StarredRow>> = _starred.asStateFlow()

    // The service alerts the user has dismissed (SPEC *Disruptions*): the screen drops a matching
    // stop-status row and a matching line status. Collected from the store so a dismiss hides the card at once, and a
    // reworded notice (a new signature) is no longer matched and reappears. An unreadable set reads
    // empty — a dismissed card returns, never a warning hidden (fails safe).
    private val _dismissed = MutableStateFlow<Set<DismissedAlert>>(emptySet())
    val dismissed: StateFlow<Set<DismissedAlert>> = _dismissed.asStateFlow()

    // Whether the star control should be offered at all. Starts false — the store's first
    // read hasn't arrived, so we don't yet know which rows are starred; enabling the control
    // before then would show a persisted-starred row as unstarred with a "Pin to top" action,
    // and a tap in that window would toggle the real persisted membership *off* (SPEC
    // principle 2). It turns true on the first [StarredRowSet.Loaded]. It stays false when the
    // stored set is a newer-schema file this build can't read ([StarredRowSet.Unavailable]) or
    // when the read flow fails: the stars exist (or their state is unknown) but we can't show
    // which rows are starred, so the screen hides the control rather than rendering every star
    // unfilled — the false "nothing is starred" claim [StarredRowSet.Unavailable] exists to
    // prevent — on a control whose taps would be a no-op or unsafe anyway.
    private val _starringAvailable = MutableStateFlow(false)
    val starringAvailable: StateFlow<Boolean> = _starringAvailable.asStateFlow()

    // Set when a star write failed (storage full, an IO error) so the screen can show a
    // transient message — a tap that didn't take otherwise reads as the app being broken
    // (SPEC principle 2: do the safe thing and say so). An *acknowledged* StateFlow, not a
    // one-shot event: the ViewModel outlives a configuration change, so the flag survives a
    // rotation that happens between the failed tap and the screen showing the message (a
    // replay-0 event would be lost in that gap). The screen calls [starWriteFailureShown]
    // once it has surfaced it, which clears the flag so it isn't shown again.
    private val _starWriteFailed = writeFailures.star
    val starWriteFailed: StateFlow<Boolean> = _starWriteFailed.asStateFlow()

    // Same seam as [starWriteFailed], for a failed alert dismissal: a tap that didn't persist
    // leaves the card visible, so the screen surfaces a snackbar rather than let the dismiss
    // look broken. Acknowledged StateFlow (survives a rotation between the tap and the message),
    // cleared by [dismissWriteFailureShown].
    private val _dismissWriteFailed = writeFailures.dismiss
    val dismissWriteFailed: StateFlow<Boolean> = _dismissWriteFailed.asStateFlow()

    private var fetchJob: Job? = null

    // The refreshes' and journey-destination checks' settlements of dismissals ([reconcileDismissals]):
    // one whose checks came in before a newer one's leaves the dismissals to that one. A newer check
    // canceled before its own came in takes no turn, so the older one still settles.
    private val refreshSettles = Turns()
    private val destinationSettles = Turns()

    // When each stop's closure lookup last failed, stamped with its fetch's `now`, and the `now` of
    // the latest [fetchBatch]: a journey destination check that waited on that fetch doesn't ask
    // again for a stop it just failed on (one attempt per refresh, not two, under rate limiting).
    private val disruptionFailedAt = mutableMapOf<String, Instant>()
    private var lastFetchAt: Instant? = null

    // A cold load has been part-shown and no whole batch has landed since. Outlives [cancelFetch]
    // (which tells the screen the load stopped), so the refresh a relocation or pull starts next
    // still shows each stop as it lands rather than holding the rest until the whole batch is in.
    private var coldLoadUnfinished = false

    // Each line's last DETERMINED status (good or disrupted) and when it came back (stamped by the
    // steady clock, [SteadyClock]), reused for [lineStatusReuse] so a refresh a minute after the last
    // one needn't re-ask about the same lines.
    // A line TfL gave no status for, or a failed request, is never cached. In-memory, main thread.
    private val lineStatusCache = mutableMapOf<String, Pair<Instant, LineStatus>>()
    // The dismissed alerts' count ([DismissedAlertsStore.mark]) each cached status was asked at: a
    // refresh settling dismissals on a reused verdict is as old as it. In-memory, main thread, like it.
    private val lineStatusMarks = mutableMapOf<String, Long>()
    // When each line was last asked about and TfL gave no status for it (a no-verdict check for the
    // widget). In-memory, main thread, like the cache.
    private val lineStatusOmitted = mutableMapOf<String, Instant>()
    // Lines TfL answered 404 for ("not recognised": a National Rail service it has no line for, such
    // as Eurostar). Not asked about again this session — the answer won't change — and never
    // determined, so their rows still read as unchecked rather than clean (SPEC principle 1). They
    // don't raise the screen-wide banner, though: nothing could check them, so it would sit on every
    // list near such a station and say nothing about a check that did fail ([disruptionUnknownOf]).
    private val unknownLineIds = HashSet<String>()

    // When each stop's arrivals last came back from a fetch by THIS ViewModel (the cycle's start
    // stamp). What makes a stop eligible to be carried over ([recentlyFetched]): a stop restored from
    // disk is never in it, since the snapshot doesn't persist the closure check a carried-over stop
    // would need. In-memory only; main thread.
    private val arrivalsFetchedAt = mutableMapOf<String, Instant>()

    // The National Rail stations whose last fetch left their board out, National Rail being hidden
    // ([HiddenModes.wantsRailBoard]). One wanting it again ("Show all", a National Rail journey starred
    // from it) isn't carried over, so its trains show at once. In-memory only; main thread.
    private val boardSkipped = HashSet<String>()

    // Which closure lookup each stop shows ([StopClosureCache.Lookup.ask]): one asked after it that the
    // cache has since kept is newer, so the stop isn't carried over past it ([recentlyFetched]).
    // In-memory only; main thread.
    private val closureShown = mutableMapOf<String, StopClosureCache.Ask>()

    // The init coroutine that loads the last-good snapshot and then calls refresh(). Tracked so
    // cancelFetch() can stop it too: during its load() the fetchJob isn't assigned yet, so
    // without this a re-locate that cancels mid-init would still let the init-driven refresh()
    // fetch and save the old seed set during the fix window (SPEC D4 / principle 1, Codex).
    private var initLoadJob: Job? = null

    init {
        // The order this model starts from, stored at once: a model made after a move (a new process,
        // say) starts from the new distances, and its first refresh may fail before saving. The store
        // writes only a change ([SnapshotStore.updateNearestFirst]).
        // The line choices are worked out again from the stored rows, so a first refresh that fails
        // leaves the widget folding as the app does, not by the order alone.
        updateWidgetNearestFirst(widgetChoicesInput(stopDistanceMeters))
        viewModelScope.launch {
            departureSourceChanges.drop(1).collect {
                arrivalsFetchedAt.clear()
                // Arrivals fetched under the old source (a National Rail key added or removed) no
                // longer stand, on this screen or any other.
                sharedArrivals?.clear()
                forceNextFetch()
                refresh()
            }
        }
        viewModelScope.launch {
            // A read failure (DataStore IOException, a non-corruption disk error) must not
            // escape and crash the departures screen as it starts. Handle it explicitly:
            // rethrow cancellation (structured concurrency), log sanitized, and leave starring
            // in an honest unavailable state (control hidden, nothing pinned) — the same shape
            // as an Unavailable set, since a failed read equally means we can't say which rows
            // are starred (SPEC principle 2 / error-handling rule).
            try {
                starredStore.starred().collect { set ->
                    when (set) {
                        is StarredRowSet.Loaded -> {
                            _starred.value = set.starred
                            _starringAvailable.value = true
                        }
                        StarredRowSet.Unavailable -> {
                            _starred.value = emptySet()
                            _starringAvailable.value = false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _starred.value = emptySet()
                _starringAvailable.value = false
                warn("starred set read failed: ${reason(e)}")
            }
        }
        // Every alert shown until the dismissed set is read; followed for the model's life.
        viewModelScope.launch { followDismissed(dismissedStore, _dismissed, warn) }
        // The widget's line choices read the dismissed alerts and the hidden modes as the list does,
        // so each change to either (the dismissed set's first read included, which lands after the
        // order was first stored) works them out again from the stored rows. Each emission is
        // compared with what the last write actually read, not dropped by position: a value that
        // loaded between the startup write's read and this subscription arrives as the first
        // emission and still counts (Codex on #550), while one the last write already used (or
        // will, while its read is still to come) writes nothing, so a replaced model doesn't
        // write over its successor's order.
        viewModelScope.launch {
            merge(_dismissed, hiddenModeChanges).collect {
                val used = choiceFiltersUsed ?: return@collect
                if (!used.sameAs(ChoiceFilters(_dismissed.value, hiddenModes()))) {
                    updateWidgetNearestFirst(widgetChoicesInput(stopDistanceMeters))
                }
            }
        }
        // Show the persisted last-good at once (a stamped placeholder, aged), then refresh.
        // The read is off the main thread and the first frame is already the Loading
        // placeholder, so nothing blocks on the DataStore read (SPEC snapshot-render). The
        // restored snapshot becomes the `prior` the refresh merges into, so a stop that then
        // fails to refresh keeps its aged rows rather than dropping out.
        initLoadJob = viewModelScope.launch {
            val restored = try {
                withContext(io) { snapshotStore.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("snapshot restore failed: ${reason(e)}")
                null
            }
            if (restored != null && _state.value is DeparturesUiState.Loading) {
                _state.value = restoredLoaded(restored)
            }
            refresh()
        }
    }

    /**
     * Cancel any in-flight fetch without starting a new one. Used when a re-locate begins: a
     * fetch already running for this (soon-to-be-previous) set must not finish first and save a
     * freshly stamped snapshot during the fix window (SPEC D4 / principle 1). Clears the
     * refreshing flag so an indicator started by that fetch doesn't stick on.
     */
    fun cancelFetch() {
        // Cancel the init pipeline too: it calls refresh() after its snapshot load(), and during
        // that load fetchJob isn't set yet, so cancelling only fetchJob would let the init-driven
        // fetch run and save during a re-locate's fix window.
        initLoadJob?.cancel()
        fetchJob?.cancel()
        // A same-set reconcile still on the worker is for the fix this relocation replaces: it must
        // not apply that fix's stops and refresh in the new one's window (Codex on #548).
        reconcileJob?.cancel()
        reconcilePending = false
        reportedDuringReconcile = false
        journeyStopsSetDuringReconcile = false
        _refreshing.value = false
        // A cold load cut short keeps the stops it showed and names the ones it never got, rather
        // than leave them "Loading" with nothing coming (SPEC principle 2).
        // Its line status was never checked, so that now reads "couldn't check", as does a closure
        // check still out: nothing will finish it, so its spinner mustn't stay up.
        (_state.value as? DeparturesUiState.Loaded)?.takeIf { it.statusPending }?.let { shown ->
            _state.value = shown.copy(
                pendingStops = emptyList(),
                closurePending = emptySet(),
                stopsDisruptionUnknown = shown.stopsDisruptionUnknown + shown.closurePending,
                disruptionUnknown = shown.disruptionUnknown || shown.closurePending.isNotEmpty(),
                statusPending = false,
                partialRefresh = shown.partialRefresh || shown.pendingStops.isNotEmpty(),
                partialStops = shown.partialStops + shown.pendingStops.associate { it.id to DeparturesUiState.FailedStop(it.name) },
            )
        }
    }

    /**
     * One line-status check's outcome ([checkLines]): the verdicts in hand for the lines [asked]
     * about, whether from the cache or TfL.
     */
    private data class LineCheck(
        // Every line this check was for, whether answered, cached, left out or failed: a later check
        // in the same batch asks only about the rest.
        val asked: Set<String>,
        // The determined statuses (good or disrupted) among [asked].
        val statuses: List<LineStatus>,
        // Whether TfL answered a request, even with nothing for any line ([FetchBatch.statusAnswered]).
        val answered: Boolean,
        // The requests it sent, for the per-fetch log line.
        val requests: Int,
        // The lines TfL doesn't know ([unknownLineIds]) as of this check: a copy, so a cold load's
        // progress can be worked out off the main thread without reading the live set.
        val unknown: Set<String> = emptySet(),
        // The dismissed-alerts count each line's verdict was asked at, a reused one's included
        // ([lineStatusMarks]), by line id.
        val dismissals: Map<String, Long> = emptyMap(),
    ) {
        val determined: Set<String> get() = statuses.mapTo(HashSet()) { it.lineId }
        val disrupted: Map<String, LineStatus> get() = statuses.filter { it.hasAlerts }.associateBy { it.lineId }
    }

    /** A stop as [fetchBatch] merged it off the main thread, and its fetch stamp when it was asked for. */
    private class MergedStop(val stop: StopArrivals?, val fetchedAt: Instant?)

    /** A cold load so far, as [fetchBatch] reports it to its onProgress while stops are still out. */
    private data class BatchProgress(
        // The stops landed (their arrivals back, their closure check maybe still out), or shown from before.
        val shown: List<StopArrivals>,
        // The stops still out.
        val waiting: Set<String>,
        // Each stop whose arrivals failed, and how, so the screen names it at once.
        val failed: Map<String, DeparturesUiState.Error.Kind>,
        // The stops whose own closure check failed, which stay "couldn't check" whatever their lines.
        val closureUnknown: Set<String>,
        // The check of the lines the stops declare, once it's back; null while it's out or unsent.
        val lines: LineCheck?,
        // The landed stops whose own closure check is still out: shown, with a spinner where a
        // closure notice would be, until it's back.
        val closurePending: Set<String> = emptySet(),
    )

    /**
     * The result of fetching a set of stops: the merged [StopArrivals] and the disruption/freshness
     * flags [refresh] needs to build state and decide whether to persist.
     */
    private data class FetchBatch(
        val merged: List<StopArrivals>,
        val lineStatuses: Map<String, LineStatus>,
        // The line ids TfL returned a definitive status for in this batch (disrupted OR clean), so a
        // per-line surface can tell "checked, good service" from "never checked" (Codex on #100).
        val determinedLineIds: Set<String>,
        // Whether TfL answered a line-status request this batch, even with nothing for any line: its
        // omissions are checks too ([lineStatusOmitted]), which the widget must learn of.
        val statusAnswered: Boolean,
        // Stop ids whose OWN stop-level disruption request failed this batch — an axis independent of
        // line status (a stop's line can be determined while its closure was never checked), so a
        // per-stop surface says "couldn't check" for it (SPEC principle 1, Codex on #100).
        val stopsDisruptionUnknown: Set<String>,
        val anyArrivalsFailed: Boolean,
        val anyFreshData: Boolean,
        // The stops whose arrivals came back this batch — so the widget's save can ask whether a
        // stop it actually shows is fresh, not just any stop (a journey origin isn't on it).
        val freshArrivalStopIds: Set<String>,
        val firstError: Throwable?,
        // Each stop whose ARRIVALS failed this batch, and how — why it "couldn't be refreshed", for the
        // partial banner. Distinct from [firstError], which a disruption failure can set.
        val arrivalsErrors: Map<String, DeparturesUiState.Error.Kind>,
        // Which closure lookup each stop this batch checked shows ([StopClosureCache.Lookup.ask]):
        // taken into [closureShown] only once the batch is published, since one superseded before
        // then shows nothing.
        val closureAsks: Map<String, StopClosureCache.Ask>,
        // The dismissed-alerts count each line's verdict was asked at, by line id, for each of the
        // batch's line checks ([LineCheck.dismissals]).
        val lineDismissals: List<Map<String, Long>>,
        // The lines TfL doesn't know, as this batch's last line check copied them ([LineCheck.unknown]),
        // so the list can be worked out off the main thread without copying the live set there.
        val unknownLineIds: Set<String> = emptySet(),
    )

    /**
     * Fetch [stops] (arrivals + disruptions, each merged into its [prior] at age [now]) and check the
     * status of every line they show, returning a [FetchBatch]. Pure of UI state — the caller decides
     * how to turn it into a [DeparturesUiState] and whether to save.
     */
    // A stop's National Rail feed after its arrivals: as the shared fetch found it, or this client's.
    private fun railFeedOf(stopId: String, departures: List<Departure>?, shared: ArrivalsCache.Entry?): RailFeed? = when {
        departures == null -> null
        shared != null -> shared.railFeed
        else -> client.railFeed(stopId)
    }

    // A stop's trains with no time after its arrivals ([TflClient.untimed]), found as [railFeedOf] is.
    private fun untimedOf(stopId: String, departures: List<Departure>?, shared: ArrivalsCache.Entry?): List<UntimedTrain> = when {
        departures == null -> emptyList()
        shared != null -> shared.untimed
        else -> client.untimed(stopId)
    }

    private suspend fun fetchBatch(
        stops: List<StopRef>,
        prior: Map<String, StopArrivals>,
        now: Instant,
        // Stops to carry over from [prior] unchanged, with no request at all — ones fetched moments
        // ago (see [recentlyFetched]). Each keeps its own fetchedAt and arrivalsFresh, so its age and
        // "No departures" claim stay honest (SPEC D4); only the line status is re-checked for it.
        reuse: Set<String> = emptySet(),
        // Called as each stop lands while the rest are still out, and when the check of the lines
        // the stops declare comes back ([BatchProgress]), so a cold load shows each stop as it
        // arrives rather than holding the spinner for the slowest (SPEC *Freshness → Cold load*).
        // Null skips it.
        // Called on [compute] (one at a time, in order), so the caller works its state out there and
        // hops to the main thread only to publish it.
        onProgress: (suspend (BatchProgress) -> Unit)? = null,
        // Whether a stop another screen fetched within [ArrivalsCache.TTL] is taken from
        // [sharedArrivals] rather than asked for; false on a pull-to-refresh, which asks afresh.
        useShared: Boolean = true,
    ): FetchBatch {
        lastFetchAt = now
        // The caller's thread (the main one), and one [compute] worker at a time for the progress
        // reports, so they're worked out in order, off the main thread.
        val caller = checkNotNull(currentCoroutineContext()[ContinuationInterceptor])
        val serial = compute.limitedParallelism(1)
        // This batch's fetches, stamped by the steady clock ([SteadyClock]) so setting the device's
        // clock doesn't change how old they read.
        val stamp = SteadyClock.stamp(now)
        val startedAt = elapsedMillis()
        val waitedBefore = rateWaitMillis()
        val merged = mutableListOf<StopArrivals>()
        var firstError: Throwable? = null
        val arrivalsErrors = mutableMapOf<String, DeparturesUiState.Error.Kind>()
        var anyArrivalsFailed = false
        // True once any request returned fresh data (arrivals or disruption, any stop):
        // the difference between a partial refresh (keep the fresh, age the rest) and a
        // total failure (nothing new — keep the whole aged snapshot and say so).
        var anyFreshData = false
        // The stops whose ARRIVALS returned (fresh durable content). Distinct from
        // anyFreshData because disruptions aren't persisted: a cycle where every arrivals
        // request failed but a disruption returned has nothing durable to save, so it must
        // not overwrite a complete saved snapshot with carried arrivalsFresh=false rows.
        val freshArrivalStopIds = mutableSetOf<String>()
        val closureAsks = HashMap<String, StopClosureCache.Ask>()
        // Stop ids whose OWN stop-level disruption request failed this batch (a closure/move was
        // never checked) — an axis independent of line status, carried out so a per-stop surface
        // says "couldn't check" for it even when its line was determined (SPEC principle 1).
        val stopsDisruptionUnknown = mutableSetOf<String>()

        // Fan the per-stop requests out in parallel; the client's shared request pool caps how many
        // are in flight, so this is a bounded fan-out, not one connection per stop. The order is
        // fixed, not best effort: every stop's departures request is sent first, and a stop's
        // closure request only once its own departures request has settled (a batch of bus poles,
        // once all of its poles' have), so the departures the rider is waiting on never queue
        // behind a closure check, and a stop shows as soon as its departures are in while its
        // closure check is still out (SPEC *Freshness → Cold load*). Each request catches its own
        // failure, so one stop failing never cancels its siblings.
        // A reused stop costs no request; its results are never read (it's carried over below).
        // Each stop's arrivals another screen fetched within the TTL, where newer than this list's
        // own ([sharedArrivals]): taken over a carry-over, so two screens don't show different times.
        // A stop not carried over takes one no older than its own; one carried over, only a newer one.
        val source = client.arrivalsSource()
        val sharedFetch = if (!useShared || sharedArrivals == null) emptyMap() else stops.mapNotNull { stop ->
            val entry = sharedArrivals.recent(stop.id, now, source) ?: return@mapNotNull null
            val held = prior[stop.id]
            if (held != null && entry.fetchedAt.isBefore(held.fetchedAt)) null else stop.id to entry
        }.toMap()
        fun newerShared(stop: StopRef) = sharedFetch[stop.id]?.let { entry ->
            prior[stop.id]?.let { entry.fetchedAt.isAfter(it.fetchedAt) } ?: true
        } ?: false
        val hidden = hiddenModes()
        fun railBoard(stop: StopRef) = HiddenModes.wantsRailBoard(hidden, starredLines(stop.id))
        fun reused(stop: StopRef) = stop.id in reuse && prior[stop.id] != null && !newerShared(stop) &&
            !(stop.id in boardSkipped && railBoard(stop))
        // The stations this batch fetched without their board (see [boardSkipped]); written off the
        // main thread, where the client can tell a station from any other stop.
        val skippedNow = ConcurrentHashMap.newKeySet<String>()
        // Which stops' closure results came from [disruptionCache] rather than this batch's request:
        // a cached result is knowledge but not NEWS, so it mustn't count toward [anyFreshData] — a
        // cycle whose every request failed would otherwise pass for a partial refresh and hide the
        // real failure (offline, rate-limited) behind the generic banner (SPEC principle 2).
        val disruptionFromCache = BooleanArray(stops.size)
        // The poles whose closure check went in a shared batch request, and how many batches — for
        // the per-fetch log line.
        val poleBatchIds = HashSet<String>()
        var poleBatchCount = 0
        // The near-me places by distance, for [Terminating]: every eager and "more" stop, since the
        // rider's nearest place may sit in either tier.
        val places = nearbyPlaces()
        // One interchange lookup per hub per batch, shared by the early per-stop reveal and the final
        // pass, so members asking at once — or a failed lookup, which isn't cached — cost one call.
        val hubLookups = HashMap<String, CompletableDeferred<HubInfo>>()
        // The hub lookups that went to TfL this batch (not the cache), for the per-fetch log line —
        // counted where they start, since the early reveal can warm the cache before the final pass.
        var hubRequests = 0
        suspend fun hubOf(hubId: String): HubInfo {
            hubLookups[hubId]?.let { return it.await() }
            val lookup = CompletableDeferred<HubInfo>()
            hubLookups[hubId] = lookup
            val info = try {
                // Counted only when this batch sends it, not when it waits on another's (Codex, #567).
                resolveHubInfo(hubId) { hubRequests++ }
            } catch (e: CancellationException) {
                lookup.cancel()
                throw e
            }
            lookup.complete(info)
            return info
        }
        // Each stop's arrivals taken from [sharedArrivals] (another screen's fetch within the TTL):
        // merged at their own fetch time, not this batch's, so they're never passed off as newer.
        val shared = arrayOfNulls<ArrivalsCache.Entry>(stops.size)
        // The lines the stops declare, checked alongside their arrivals rather than once the slowest
        // is back, so a cold load can vouch for them while a stop is still out (SPEC *Freshness →
        // Cold load*). A line only a prediction names is checked after the merge, below.
        val declaredLineIds = stops.flatMap { it.lines }.map { it.id }.filterTo(HashSet()) { it.isNotBlank() }
        val (arrivalResults, disruptionResults, earlyLines) = coroutineScope {
            val arrivals = stops.mapIndexed { i, stop ->
                val recent = if (!reused(stop)) sharedFetch[stop.id] else null
                when {
                    reused(stop) -> null
                    recent != null -> {
                        shared[i] = recent
                        CompletableDeferred(Result.success(recent.departures))
                    }
                    else -> {
                        val board = railBoard(stop)
                        async {
                            runCatchingTfl {
                                withContext(io) {
                                    client.arrivals(stop.id, board).also {
                                        if (!board && client.hasRailBoard(stop.id)) skippedNow += stop.id
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Sent once there's something to show it for — a stop's arrivals back, or a stop shown
            // from before — and not at all when every stop fails, which shows no line (the same
            // requests as checking after the merge, only sooner). After the arrivals are launched,
            // so the departures still tend to go out first.
            val linesGo = CompletableDeferred<Boolean>()
            val earlyCheck = async { if (linesGo.await()) checkLines(declaredLineIds, now) else null }
            when {
                declaredLineIds.isEmpty() -> linesGo.complete(false)
                stops.any { prior[it.id] != null } -> linesGo.complete(true)
                else -> arrivals.filterNotNull().forEach { arrival ->
                    launch { if (arrival.await().isSuccess) linesGo.complete(true) }
                }
            }
            // Fetch each stop's disruption independently of its arrivals (a closure, a moved stop),
            // so a closed stop is flagged rather than shown with catchable-looking departures — and a
            // stop whose *arrivals* failed still surfaces its available closure rather than dropping
            // out entirely (SPEC *Disruptions*). Per stop, or per batch of bus poles (below), off the
            // render path. A lookup that fails falls back to the aged disruption and flags the state unknown
            // rather than passing the stop off as verified-clear.
            // A lookup that succeeded within [disruptionReuse] is reused rather than re-requested.
            fun cachedDisruption(stop: StopRef) = disruptionCache[stop.id]
                ?.takeIf { isWithin(it.at, now, disruptionReuse) }
            // Bus poles still to check share one request per [StopDisruptionBatch.MAX_PER_REQUEST]
            // (a junction is often 4-8 poles, each otherwise its own request against the keyless
            // budget); a failed batch fails each of its poles, as a failed single lookup would.
            val poleBatches = HashMap<String, Deferred<Map<String, Result<StopClosureCache.Lookup>>>>()
            // Each stop's departures request, by stop id: what its closure request waits on.
            val arrivalOf = HashMap<String, Deferred<Result<List<Departure>>>>()
            stops.forEachIndexed { i, stop -> arrivals[i]?.let { arrivalOf.putIfAbsent(stop.id, it) } }
            // The poles a batch found answered by then, from the cache or another screen's lookup
            // while it waited on their departures: not news, as a cached answer isn't.
            val poleFromCache = HashSet<String>()
            stops
                .filter {
                    !reused(it) && cachedDisruption(it) == null && it.id !in destinationRequests &&
                        StopDisruptionBatch.isPole(it.id)
                }
                .map { it.id }
                .distinct()
                .chunked(StopDisruptionBatch.MAX_PER_REQUEST)
                .forEach { ids ->
                    val batch = async {
                        // Departures first: sent once each of its poles' departures request has settled.
                        ids.forEach { id -> arrivalOf[id]?.await() }
                        // Looked at again after the wait: another screen may have answered for a pole
                        // meanwhile, and its answer is reused rather than asked for again.
                        val known = HashMap<String, Result<StopClosureCache.Lookup>>()
                        for (id in ids) {
                            // Aged by the clock now, not the batch's start (nor before an earlier pole's
                            // wait below): an answer from meanwhile is newer, and would read as from the future.
                            val cached = disruptionCache[id]?.takeIf { isWithin(it.at, clock(), disruptionReuse) }
                            val inFlight = destinationRequests[id]
                            when {
                                cached != null -> {
                                    known[id] = Result.success(cached)
                                    poleFromCache += id
                                }
                                inFlight != null -> {
                                    // Another lookup's answer, not this fetch's own, as a cache hit is.
                                    poleFromCache += id
                                    known[id] = inFlight.await().onFailure { disruptionFailedAt[id] = now }
                                }
                            }
                        }
                        val toAsk = ids.filter { it !in known }
                        // Its place in line ([StopClosureCache.ask]) taken as it's sent, after the wait, so
                        // a lookup another screen sent meanwhile counts as the older one.
                        val ask = disruptionCache.ask(clock(), dismissedStore.mark())
                        // Counted only when sent: every pole answered meanwhile costs no request.
                        if (toAsk.isNotEmpty()) poleBatchCount++
                        val answer = if (toAsk.isEmpty()) null else runCatchingTfl { withContext(io) { client.poleDisruptions(toAsk) } }
                        // As the cache settles each: a later lookup (a trip's) wins over this one,
                        // failed or not.
                        ids.associateWith { id ->
                            known[id] ?: disruptionCache.settle(id, ask, answer!!.map { it[id].orEmpty() })
                                .onFailure { disruptionFailedAt[id] = now }
                        }
                    }
                    ids.forEach { poleBatches[it] = batch }
                    poleBatchIds += ids
                }
            val disruptions: List<Deferred<Result<StopClosureCache.Lookup>>?> = stops.mapIndexed { i, stop ->
                val cached = cachedDisruption(stop)
                val batch = poleBatches[stop.id]
                // A journey destination's check already asking about this stop: wait on it, not ask twice.
                val inFlight = destinationRequests[stop.id]
                when {
                    reused(stop) -> null
                    // Ahead of the cache check: a batch that already finished has written this
                    // pole's fresh result to the cache, which must not read as a cached (not new) one.
                    batch != null -> async {
                        batch.await().getValue(stop.id).also { if (stop.id in poleFromCache) disruptionFromCache[i] = true }
                    }
                    cached != null -> {
                        disruptionFromCache[i] = true
                        CompletableDeferred(Result.success(cached))
                    }
                    inFlight != null -> {
                        // Answered by that lookup, not a request of this fetch's own.
                        disruptionFromCache[i] = true
                        async { inFlight.await().onFailure { disruptionFailedAt[stop.id] = now } }
                    }
                    else -> async {
                        // Departures first: sent once this stop's departures request has settled.
                        arrivals[i]?.await()
                        // Looked at again after the wait: another screen may have answered meanwhile,
                        // and its answer is reused rather than asked for again.
                        // Aged by the clock now, not the batch's start, as the pole batch does.
                        val answered = disruptionCache[stop.id]?.takeIf { isWithin(it.at, clock(), disruptionReuse) }
                        val asking = destinationRequests[stop.id]
                        when {
                            answered != null -> {
                                disruptionFromCache[i] = true
                                Result.success(answered)
                            }
                            asking != null -> {
                                disruptionFromCache[i] = true
                                asking.await().onFailure { disruptionFailedAt[stop.id] = now }
                            }
                            else -> {
                                // Its place in line taken as it's sent, after the wait, as the pole batch's.
                                val ask = disruptionCache.ask(clock(), dismissedStore.mark())
                                disruptionCache.settle(stop.id, ask, runCatchingTfl { withContext(io) { client.stopDisruptions(stop.id) } })
                                    .onFailure { disruptionFailedAt[stop.id] = now }
                            }
                        }
                    }
                }
            }
            if (onProgress != null) {
                // Each stop as it lands, in stop order. Run on one [compute] worker at a time
                // ([serial]), off the main thread, so these watchers take turns on [landed],
                // [waiting] and [failed]; only the hub lookup ([hubOf]) goes back to the caller's
                // thread. A stop whose arrivals failed is named as failed at once, not left "Loading".
                // A stop with a prior (reused, or shown by a cold load this one restarted) shows it
                // until its answer lands, rather than going back to "Loading".
                val landed = arrayOfNulls<StopArrivals>(stops.size)
                val waiting = HashSet<String>()
                val failed = LinkedHashMap<String, DeparturesUiState.Error.Kind>()
                val closureUnknown = HashSet<String>()
                val closurePending = HashSet<String>()
                var lines: LineCheck? = null
                suspend fun report() = onProgress(
                    BatchProgress(landed.filterNotNull(), waiting.toSet(), failed.toMap(), closureUnknown.toSet(), lines, closurePending.toSet()),
                )
                launch(serial) {
                    stops.forEachIndexed { i, stop ->
                        landed[i] = prior[stop.id]
                        if (landed[i] == null) {
                            waiting += stop.id
                        } else if (disruptions[i] != null) {
                            // Shown from before with its closure answer still to merge (a check follows the
                            // stop's departures; even a cached answer waits on them): pending from the first
                            // report, never passed off as checked.
                            closurePending += stop.id
                        }
                    }
                    // What's already in hand shows at once, marked still checking — including a batch
                    // that reuses every stop and so has no answer to wait for before its line status.
                    if (landed.any { it != null }) report()
                }
                // The declared lines' verdicts as soon as they're back, so the stops shown stop
                // reading "checking" without waiting for another to land.
                launch(serial) {
                    lines = earlyCheck.await() ?: return@launch
                    report()
                }
                stops.forEachIndexed { i, stop ->
                    val arrival = arrivals[i] ?: return@forEachIndexed
                    launch(serial) {
                        val departures = arrival.await().getOrElse { e ->
                            waiting -= stop.id
                            failed[stop.id] = kindOf(e)
                            report()
                            null
                        }
                        // The stop as the final pass would merge it, with [stopDisruptions] as its notices.
                        fun merged(stopDisruptions: List<StopDisruption>?, hub: HubInfo) = Snapshot.mergeStop(
                            stopId = stop.id,
                            stopName = stop.name,
                            clusterId = stop.clusterId,
                            lines = stop.lines,
                            freshDepartures = departures,
                            freshDisruptions = stopDisruptions,
                            prior = prior[stop.id],
                            now = shared[i]?.fetchedAt ?: client.stampOf(stop.id, stamp),
                            hubId = stop.hubId,
                            hubName = hub.name,
                            placeAliases = hub.aliases,
                            stopLetter = stop.stopLetter,
                            bearing = stop.bearing,
                            towards = stop.towards,
                            nearer = Terminating.nearer(stop.id, places),
                            freshRailFeed = railFeedOf(stop.id, departures, shared[i]),
                            freshUntimed = untimedOf(stop.id, departures, shared[i]),
                        )
                        val closureCheck = disruptions[i]
                        // Its departures in, the stop shows at once, its closure check pending, rather
                        // than wait on that check (SPEC *Freshness → Cold load*).
                        if (departures != null && closureCheck != null && !closureCheck.isCompleted) {
                            landed[i] = merged(null, HubInfo())
                            waiting -= stop.id
                            closurePending += stop.id
                            report()
                        }
                        val closure = closureCheck?.await()
                        // Its closure unchecked, the stop can't read as clear of one, as the final pass
                        // says (SPEC principle 1).
                        if (closure?.isFailure == true) closureUnknown += stop.id
                        val stopDisruptions = closure?.getOrNull()?.notices
                        // A stop whose arrivals failed still shows a closure as soon as it's known,
                        // as the final pass does (SPEC *Disruptions*); with none, it stays named failed.
                        if (departures == null && stopDisruptions.isNullOrEmpty()) {
                            // A stop shown from before is checked now, one way or the other. A fresh
                            // "none" drops the closure it was shown with, as the final pass does.
                            if (stopDisruptions != null && landed[i] != null) landed[i] = merged(stopDisruptions, HubInfo())
                            val wasPending = closurePending.remove(stop.id)
                            if (closure?.isFailure == true || wasPending) report()
                            return@launch
                        }
                        // A disrupted interchange titles its alert by the hub, as the final pass does
                        // (one lookup per hub per batch — [hubOf] — so that pass doesn't ask again).
                        val hub = if (stop.hubId.isNotBlank() && !stopDisruptions.isNullOrEmpty()) {
                            // Its departures shown while the hub's name is looked up, closure pending,
                            // rather than left "Loading" on a slow lookup.
                            if (departures != null && stop.id !in closurePending) {
                                landed[i] = merged(null, HubInfo())
                                waiting -= stop.id
                                closurePending += stop.id
                                report()
                            }
                            // On the caller's thread, where the batch's other hub lookups are kept.
                            withContext(caller) { hubOf(stop.hubId) }
                        } else {
                            HubInfo()
                        }
                        landed[i] = merged(stopDisruptions, hub)
                        // Pending until its row carries the answer, past any hub lookup above, so no
                        // report between them shows the stop with neither spinner nor notice.
                        closurePending -= stop.id
                        waiting -= stop.id
                        report()
                    }
                }
            }
            val arrivalResults = arrivals.map { it?.await() }
            // No stop's arrivals came back (and none was shown from before): the check isn't sent.
            linesGo.complete(arrivalResults.any { it?.isSuccess == true })
            Triple(arrivalResults, disruptions.map { it?.await() }, earlyCheck.await())
        }

        // Resolve each interchange once, in parallel, only for a hub with a stop that has a fresh
        // disruption to title — the one case a folded alert titles by the interchange and the strip
        // needs its member aliases (SPEC *Disruptions*). Deduplicated up front so a hub shared by
        // several disrupted stops costs one call even when it fails, and every member agrees. A stop
        // with no disruption, or no hub, costs no call and titles by its own name.
        val hubIds = stops.indices
            .filter { i -> stops[i].hubId.isNotBlank() && !disruptionResults[i]?.getOrNull()?.notices.isNullOrEmpty() }
            .mapTo(LinkedHashSet()) { i -> stops[i].hubId }
        val hubs: Map<String, HubInfo> = coroutineScope {
            hubIds.map { hubId -> async { hubId to hubOf(hubId) } }.awaitAll().toMap()
        }

        // Each stop merged, with its fetch stamp, worked out off the main thread ([compute]): a merge
        // walks the stop's departures, and the line ids gathered after it walk every stop's. Only the
        // bookkeeping below (the first error, the logs, the stamps kept) runs here.
        val mergedOf = withContext(compute) {
            stops.indices.map { i ->
                val stop = stops[i]
                val nearer = Terminating.nearer(stop.id, places)
                val arrivalResult = arrivalResults[i]
                val disruptionResult = disruptionResults[i]
                if (arrivalResult == null || disruptionResult == null) {
                    // Reused: carried over as it was, neither a fresh result nor a failure — but with the
                    // lines it declares now (a journey origin can gain one), so their status is checked.
                    // Its nearer places too: the rider may have moved since, and the rows hide by them.
                    val carried = prior.getValue(stop.id).let { p ->
                        if (p.lines == stop.lines && p.nearer == nearer) p else p.copy(lines = stop.lines, nearer = nearer)
                    }
                    return@map MergedStop(carried, fetchedAt = null)
                }
                val departures = arrivalResult.getOrNull()
                val disruptions = disruptionResult.getOrNull()?.notices
                // As old as the oldest part of its arrivals (a National Rail board another screen fetched,
                // say), both on screen and in what a later refresh carries over by: the two must match.
                val fetchedAt = shared[i]?.fetchedAt ?: client.stampOf(stop.id, stamp)
                val hub = if (stop.hubId.isNotBlank() && !disruptions.isNullOrEmpty()) hubs[stop.hubId] ?: HubInfo() else HubInfo()
                MergedStop(
                    Snapshot.mergeStop(
                        stopId = stop.id,
                        stopName = stop.name,
                        clusterId = stop.clusterId,
                        lines = stop.lines,
                        freshDepartures = departures,
                        freshDisruptions = disruptions,
                        prior = prior[stop.id],
                        now = fetchedAt,
                        hubId = stop.hubId,
                        hubName = hub.name,
                        placeAliases = hub.aliases,
                        stopLetter = stop.stopLetter,
                        bearing = stop.bearing,
                        towards = stop.towards,
                        nearer = nearer,
                        freshRailFeed = railFeedOf(stop.id, departures, shared[i]),
                        freshUntimed = untimedOf(stop.id, departures, shared[i]),
                    ),
                    fetchedAt,
                )
            }
        }

        // Kept in stop order, so the first error, the logs and the merged list read the same as a
        // one-at-a-time fetch would, whatever order the responses came back in.
        stops.forEachIndexed { i, stop ->
            val arrivalResult = arrivalResults[i]
            val disruptionResult = disruptionResults[i]
            val mergedStop = mergedOf[i]
            if (arrivalResult == null || disruptionResult == null) {
                merged += checkNotNull(mergedStop.stop)
                return@forEachIndexed
            }
            val departures = arrivalResult.getOrElse { e ->
                if (firstError == null) firstError = e
                arrivalsErrors[stop.id] = kindOf(e)
                anyArrivalsFailed = true
                warn("arrivals fetch failed for stop ${stop.id}: ${reason(e)}")
                null
            }
            val closure = disruptionResult.getOrElse { e ->
                if (firstError == null) firstError = e
                stopsDisruptionUnknown += stop.id
                warn("stop disruption fetch failed for stop ${stop.id}: ${reason(e)}")
                null
            }
            closure?.let { closureAsks[stop.id] = it.ask }
            val disruptions = closure?.notices
            if (departures != null) {
                freshArrivalStopIds += stop.id
                arrivalsFetchedAt[stop.id] = checkNotNull(mergedStop.fetchedAt)
                if (stop.id in skippedNow) boardSkipped += stop.id else boardSkipped -= stop.id
            }
            if (departures != null || (disruptions != null && !disruptionFromCache[i])) anyFreshData = true
            mergedStop.stop?.let { merged += it }
        }

        // Check the status of every line we're about to show, so a disrupted line is
        // marked rather than its countdowns shown as trustworthy (SPEC *Disruptions* /
        // D3). The set is the stops' declared lines PLUS every predicted line: the
        // declared lines cover a suspended line that returned no predictions (so it can
        // surface as a status row), and the predicted set catches anything a stop didn't
        // declare. The declared ones were checked alongside the arrivals ([earlyLines]);
        // only a line a prediction alone names is asked about here. A lookup that fails
        // leaves the arrivals shown but flags them "status unknown" rather than passing
        // them off as verified-clean.
        var lineStatuses = emptyMap<String, LineStatus>()
        // The lines TfL returned a status for (good or disrupted).
        var determinedLineIds = emptySet<String>()
        var lateLines: LineCheck? = null
        if (merged.isNotEmpty()) {
            // Gathered off the main thread, as the merge is: every stop's departures and lines. Nothing
            // else touches [merged] meanwhile.
            val (lineIds, blankLineIdCount) = withContext(compute) {
                val predictedLineIds = merged.flatMap { it.departures }.map { it.lineId }
                val shownDeclared = merged.flatMap { it.lines }.map { it.id }
                (predictedLineIds + shownDeclared).filterTo(mutableSetOf()) { it.isNotBlank() } to
                    predictedLineIds.count { it.isBlank() }
            }
            // A departure whose line TfL didn't identify (blank id) can't have its
            // status checked, so its presence alone leaves the disruption state
            // unknown — never shown as verified-clean (SPEC principle 1). This also
            // covers the all-blank case, where no status request is made at all.
            if (blankLineIdCount > 0) {
                // Per-stop attribution (a blank prediction on the stop that showed it) is done
                // below; this logs the batch-wide count so a persistent "couldn't check for
                // disruptions" is diagnosable — a count of unidentifiable predictions, no user data.
                warn("disruption status unknown: $blankLineIdCount prediction(s) had no line id to check")
            }
            val rest = lineIds - earlyLines?.asked.orEmpty()
            if (rest.isNotEmpty()) lateLines = checkLines(rest, now)
            // Only the lines shown: the early check can have covered a stop that then showed nothing.
            val statuses = listOfNotNull(earlyLines, lateLines).flatMap { it.statuses }.filter { it.lineId in lineIds }
            lineStatuses = statuses.filter { it.hasAlerts }.associateBy { it.lineId }
            determinedLineIds = statuses.mapTo(mutableSetOf()) { it.lineId }
        }
        val statusAnswered = listOfNotNull(earlyLines, lateLines).any { it.answered }
        val lineStatusRequests = listOfNotNull(earlyLines, lateLines).sumOf { it.requests }

        logStats(
            LoadStats.describe(
                LoadStats.Requests(
                    departures = arrivalResults.count { it != null },
                    closures = stops.indices.count { i ->
                        !reused(stops[i]) && !disruptionFromCache[i] && stops[i].id !in poleBatchIds
                    },
                    closureBatches = poleBatchCount,
                    lineStatus = lineStatusRequests,
                    hubs = hubRequests,
                ),
                elapsedMillis = elapsedMillis() - startedAt,
                rateWaitMillis = rateWaitMillis() - waitedBefore,
            ),
        )
        return FetchBatch(
            merged = merged,
            lineStatuses = lineStatuses,
            determinedLineIds = determinedLineIds,
            statusAnswered = statusAnswered,
            stopsDisruptionUnknown = stopsDisruptionUnknown,
            anyArrivalsFailed = anyArrivalsFailed,
            anyFreshData = anyFreshData,
            freshArrivalStopIds = freshArrivalStopIds,
            closureAsks = closureAsks,
            lineDismissals = listOfNotNull(earlyLines, lateLines).map { it.dismissals },
            firstError = firstError,
            arrivalsErrors = arrivalsErrors,
            // The latest check's copy holds every line known unknown by then. With no check at all, no
            // stop shows a line to check, so none is needed.
            unknownLineIds = (lateLines ?: earlyLines)?.unknown.orEmpty(),
        )
    }

    /**
     * Check the status of [lineIds] at [now]: lines checked within [lineStatusReuse] keep that
     * verdict, and only the rest are asked for, in as many requests as TfL accepts
     * ([LineStatusBatch]). Updates the caches as the answers come back. A request that fails leaves
     * its lines undetermined, so they read unchecked rather than clean (SPEC principle 1).
     */
    private suspend fun checkLines(lineIds: Set<String>, now: Instant): LineCheck {
        // Not one still waiting on its alerts' directions: asked again, it splits by direction.
        val cachedStatuses = lineIds.mapNotNull { id ->
            lineStatusCache[id]?.takeIf { (at, status) -> isWithin(at, now, lineStatusReuse) && !status.awaitingDirections }?.second
        }
        // A line TfL left out within the same window isn't asked about again either: it still
        // reads as unchecked, but asking every cycle would spend the rate budget and the radio
        // on an answer that just came back empty.
        val recentlyOmitted = lineIds.filterTo(HashSet()) { id ->
            lineStatusOmitted[id]?.let { at -> isWithin(at, now, lineStatusReuse) } == true
        }
        val toQuery = lineIds - cachedStatuses.mapTo(HashSet()) { it.lineId } - unknownLineIds - recentlyOmitted
        val cachedDismissals = cachedStatuses.associate { it.lineId to (lineStatusMarks[it.lineId] ?: 0L) }
        // With nothing left to ask, the cached verdicts stand on their own.
        if (toQuery.isEmpty()) {
            return LineCheck(lineIds, cachedStatuses, answered = false, requests = 0, unknown = unknownLineIds.toSet(), dismissals = cachedDismissals)
        }
        var requests = 0
        // The dismissals counted before anything is asked: one counted after is newer than the answers.
        val askedAt = dismissedStore.mark()
        return try {
            // One call per request TfL accepts, each with its own outcome (LineStatusBatch), so
            // the stats count only what was sent and one part failing or unknown doesn't
            // discard the verdicts the others returned.
            val results = LineStatusBatch.request(toQuery) { chunk -> withContext(io) { client.lineStatuses(chunk) } }
            requests = results.requests
            val fetched = results.answers.flatMap { it.value }
            val unknown = results.unknown
            val failed = results.failed
            // Nothing answered at all: the whole lookup failed, as before it was split.
            if (!results.anyAnswered) throw checkNotNull(results.failure)
            if (failed.isNotEmpty()) {
                // Not answered, so not an omission: those lines read unchecked and are asked
                // again next refresh, while the verdicts the other requests returned stand.
                warn("line status fetch failed for ${failed.joinToString(",")}: ${results.failure?.let(::reason)}")
            }
            if (unknown.isNotEmpty()) {
                // TfL knows none of these lines: remembered, so a refresh doesn't ask again,
                // and marked omitted below like any line asked and left out.
                unknownLineIds += unknown
                warn("line status: TfL doesn't know line(s) ${unknown.joinToString(",")}; not asked again")
            }
            // Stamped when TfL answered, not when this batch began: a slow batch neither loses
            // the store's newest-wins merge to a check made meanwhile nor saves an answer
            // already near its expiry (SPEC D3/D4). By the steady clock, as a fetch is
            // ([SteadyClock]).
            val answeredAt = SteadyClock.stamp(clock())
            // The latest answer for a line replaces the other kind outright, so a clock moved
            // back can't leave a future-dated entry outranking it ([widgetLineChecks]).
            fetched.forEach {
                lineStatusCache[it.lineId] = answeredAt to it
                lineStatusMarks[it.lineId] = askedAt
                lineStatusOmitted.remove(it.lineId)
            }
            // A line TfL returned no determinable status for is unknown, not clean — flag it so
            // those rows aren't shown as verified-clean (the client drops such lines, so they're
            // absent here).
            val statuses = cachedStatuses + fetched
            val determined = statuses.mapTo(mutableSetOf()) { it.lineId }
            // Asked and left out: remembered, so the widget's copy of an older verdict for it
            // is replaced by "no verdict" rather than kept ([widgetLineChecks]).
            toQuery.filterNot { it in determined || it in failed }.forEach {
                lineStatusOmitted[it] = answeredAt
                lineStatusCache.remove(it)
                lineStatusMarks.remove(it)
            }
            // This check's unknown and failed lines were just named above.
            val undetermined = lineIds.filterNot { it in determined || it in unknown || it in failed }
            if (undetermined.isNotEmpty()) {
                // Name the specific lines so a persistent "couldn't check for disruptions" is
                // diagnosable — a line id is a canned identifier, not user data (SPEC
                // *Privacy*: line ids are allowed in the log).
                warn("disruption status unknown: TfL returned no status for line(s) ${undetermined.joinToString(",")}")
            }
            val dismissals = cachedDismissals + fetched.associate { it.lineId to askedAt }
            LineCheck(lineIds, statuses, answered = true, requests = requests, unknown = unknownLineIds.toSet(), dismissals = dismissals)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the cached verdicts are determined, so every line this request was for
            // reads undetermined — the flag the callers derive is set (SPEC principle 1).
            warn("line status fetch failed for ${toQuery.joinToString(",")}: ${reason(e)}")
            LineCheck(lineIds, cachedStatuses, answered = false, requests = requests, unknown = unknownLineIds.toSet(), dismissals = cachedDismissals)
        }
    }

    /**
     * Whether [at], a check's steady stamp ([SteadyClock]), is less than [window] before the wall
     * time [now], aged by the steady clock so setting the device's clock doesn't change it. A
     * negative age (a stamp from before the clock was set back, across a reboot) is never within it:
     * it would otherwise read as "just now" and keep reusing an old result the whole while.
     */
    private fun isWithin(at: Instant, now: Instant, window: Duration): Boolean {
        val age = SteadyClock.age(at, now)
        return !age.isNegative && age < window
    }

    /**
     * The stops in [loaded] a refresh at [now] can carry over without a request: this ViewModel
     * fetched their arrivals less than [arrivalsReuse] ago ([arrivalsFetchedAt]), the stop shown is
     * that fetch's result (not an older one a canceled batch left behind), and their own closure
     * check didn't fail. A failed or carried-aged stop, or one
     * whose closure check failed, is always refetched — those are what a quick retry is for — and so
     * is a stop restored from disk, which lacks the unpersisted closure check.
     */
    private fun recentlyFetched(loaded: DeparturesUiState.Loaded, now: Instant, automatic: Boolean = false): Set<String> =
        loaded.stops
            .filter { stop ->
                // On the timer, a stop past the walking reach is refreshed less often: its departures
                // matter once the rider is closer, and the rate budget is better spent on the near ones.
                val far = (stopDistanceMeters[stop.stopId] ?: 0.0) > NearbySelection.EAGER_RADIUS_METERS
                val window = if (automatic && far) maxOf(arrivalsReuse, farArrivalsReuse) else arrivalsReuse
                // The shown stop must BE that fetch (same stamp): a batch canceled after its
                // arrivals came back but before it was published leaves a newer stamp here than the
                // stop on screen, and carrying that older stop over would pass it off as just fetched.
                val fetchedAt = arrivalsFetchedAt[stop.stopId]
                // Nor may a closure lookup asked after the one shown be skipped: a later, superseded
                // batch whose arrivals failed, or a trip, can have cached a newer closure for the stop,
                // and carrying the stop over would keep its departures up without it. Told by the
                // lookups' order ([StopClosureCache.since]), not their clock, which two can share.
                val shownClosure = closureShown[stop.stopId]
                fetchedAt != null &&
                    fetchedAt == stop.fetchedAt &&
                    shownClosure != null && disruptionCache.since(stop.stopId, shownClosure) == null &&
                    // Aged by the steady clock the fetch is stamped by ([SteadyClock]).
                    SteadyClock.age(fetchedAt, now).let { !it.isNegative && it < window } &&
                    stop.arrivalsFresh &&
                    stop.stopId !in loaded.stopsDisruptionUnknown
            }
            .mapTo(mutableSetOf()) { it.stopId }

    /**
     * The screen-wide "some shown departures' disruption state is unverified" flag, derived from the
     * merged set and its provenance: true when any shown stop's own closure check failed
     * ([stopsDisruptionUnknown]), any shown prediction has no line id to check, or any shown line TfL
     * returned no status for (not in [determinedLineIds]) — never show an unverified line as clean
     * (SPEC principle 1). A line TfL doesn't know ([unknownLineIds]) isn't counted: TfL has no
     * status for it to give, so no check failed; its own row still reads as unchecked.
     */
    private fun disruptionUnknownOf(
        stops: List<StopArrivals>,
        determinedLineIds: Set<String>,
        stopsDisruptionUnknown: Set<String>,
        // The lines TfL doesn't know: the live set on the main thread, a check's copy off it.
        unknown: Set<String>,
    ): Boolean =
        stopsDisruptionUnknown.isNotEmpty() ||
            stops.any { s ->
                s.departures.any { it.lineId.isBlank() } ||
                    (s.departures.map { it.lineId } + s.lines.map { it.id })
                        .any { it.isNotBlank() && it !in determinedLineIds && it !in unknown }
            }

    /**
     * The lines [shown] names whose status check is still out: not yet asked about by [lines]. One
     * it asked about is settled, determined or not (a failed request, a line TfL gave no status or
     * doesn't know): the final pass asks only about the rest.
     */
    private fun pendingLinesOf(shown: List<StopArrivals>, lines: LineCheck?): Set<String> {
        val pending = HashSet<String>()
        for (stop in shown) {
            stop.departures.mapTo(pending) { it.lineId }
            stop.lines.mapTo(pending) { it.id }
        }
        pending -= ""
        if (lines != null) pending -= lines.asked
        return pending
    }

    /**
     * Whether a check already back left something in [progress]'s shown stops unchecked for good this
     * load: a stop's closure check failed, a departure has no line to check, or a line the early check
     * asked about came back undetermined (a failed request, or TfL gave it no status). None of these
     * is asked again before the load finishes, so the banner says it couldn't check, not that it's
     * checking ([DeparturesUiState.Loaded.checkFailed]). A line not asked about yet is still pending,
     * and one TfL doesn't know (the check's [LineCheck.unknown]) failed no check.
     */
    private fun checkFailedOf(shown: List<StopArrivals>, progress: BatchProgress): Boolean {
        val lines = progress.lines
        return progress.closureUnknown.isNotEmpty() ||
            shown.any { stop ->
                stop.departures.any { it.lineId.isBlank() } ||
                    (lines != null &&
                        (stop.departures.map { it.lineId } + stop.lines.map { it.id })
                            .any { it in lines.asked && it !in lines.determined && it !in lines.unknown })
            }
    }

    // Set by [forceNextFetch]: the next [refresh] asks for every stop afresh.
    private var forceNext = false

    /**
     * Makes the next [refresh] — this one's, or the one a relocation's [reconcile] runs — ask TfL for
     * every stop, reusing neither this list's recent fetches nor another screen's: a pull-to-refresh
     * (SPEC *Freshness → Shared arrivals*).
     */
    fun forceNextFetch() {
        forceNext = true
    }

    /**
     * Re-fetch every fetched stop and swap in a fresh snapshot; safe to call repeatedly. An
     * [automatic] refresh (the on-screen timer, not the user) also carries over a far stop fetched
     * within [farArrivalsReuse] — see [recentlyFetched].
     */
    fun refresh(automatic: Boolean = false) {
        // A same-set reconcile on its way refreshes once it lands, with the new tiers; one now would
        // fetch the old ones.
        if (reconcilePending) return
        // A pull-to-refresh asks afresh for every stop, reusing none ([forceNextFetch]).
        val force = forceNext
        forceNext = false
        refreshAwaitsJourneyStops = false
        fetchJob?.cancel()
        val previous = _state.value
        // Keep the last-good list on screen while refreshing; only show the spinner
        // when there's nothing yet, so a manual refresh doesn't flash a blank screen.
        if (previous !is DeparturesUiState.Loaded) {
            _state.value = DeparturesUiState.Loading
        }
        _refreshing.value = true
        val job = viewModelScope.launch {
            // Stamp each stop from the START of the fetch, not after the request chain, so
            // a slow TfL or many stops can't report the oldest departures as "just updated"
            // or push the staleness cutoff out by the chain's duration (SPEC D4). Merging
            // into the prior snapshot per stop is what keeps a failed stop's aged rows
            // rather than dropping the stop wholesale, so each stop carries its own age.
            val now = clock()
            // The dismissals so far, before anything is asked: one made after is newer than what this
            // refresh learns, so its settling never lets go of it ([reconcileDismissals]).
            val since = dismissedStore.mark()
            // Prior to merge into, plus whether it was already incomplete. The in-memory
            // last-good if we have one (its partialRefresh is already accurate), else the
            // persisted snapshot read from disk (completeness derived the same way the init
            // restore does). Falling back to the store — not just the in-memory state — means
            // a refresh that runs before (or races) the init restore, e.g. a manual refresh
            // during the disk read, still merges into the last-good and keeps aged rows on
            // failure, rather than falling to an Error that discards data still valid on disk
            // (SPEC principle 2). Carrying the completeness too keeps a total-failure refresh
            // from clearing the "some stops couldn't be refreshed" warning on an
            // already-incomplete snapshot recovered from the store.
            val priorLoaded = previous as? DeparturesUiState.Loaded
            val priorStops: List<StopArrivals>
            val priorPartial: Boolean
            if (priorLoaded != null) {
                priorStops = priorLoaded.stops
                priorPartial = priorLoaded.partialRefresh
            } else {
                val loaded = try {
                    withContext(io) { snapshotStore.load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot restore failed: ${reason(e)}")
                    null
                }
                priorStops = loaded?.stops ?: emptyList()
                priorPartial = loaded != null && isIncomplete(loaded.stops)
                // Show the aged last-good at once rather than holding the spinner through the
                // whole fetch: this branch runs only when the state wasn't Loaded (a refresh
                // that raced or replaced the init restore), and if the network then hangs a
                // Loading spinner would hide valid data already read from disk (SPEC principle
                // 5). Same construction as the init restore, so both restore paths reach the
                // screen identically. The fetch below then replaces it.
                if (loaded != null) {
                    _state.value = restoredLoaded(loaded)
                }
            }
            val prior = priorStops.associateBy { it.stopId }
            // Fetch the whole set, merged into the prior at this cycle's stamp (see [fetchBatch]) —
            // except stops fetched moments ago, carried over as they are. A retry soon after a
            // rate-limited refresh then spends the budget left only on the stops still missing,
            // rather than refetching every stop and hitting the limit again.
            val reuse = if (priorLoaded != null && !force) recentlyFetched(priorLoaded, now, automatic) else emptySet()
            // The nearby stops this fetch is for: the only ones the widget may be given below.
            val nearIds = nearStops.mapTo(HashSet()) { it.id }
            val journeyIds = journeyStops.mapTo(HashSet()) { it.id }
            val toFetch = fetchedStops
            // Only while there's nothing else to show — a cold load with no saved snapshot, or one
            // already part-shown this way. A kept snapshot stays on screen until the batch is done.
            // Never saved: a part-loaded list isn't a snapshot (SPEC D4).
            val coldAtStart = previous !is DeparturesUiState.Loaded || previous.statusPending || coldLoadUnfinished
            // With nothing on screen yet, the first partial list waits up to [FIRST_PAINT_GRACE_MS]
            // (the loading stamp shows meanwhile), so a typical load paints once, whole, rather than
            // stop by stop; a stop still out then shows as a card (SPEC *Freshness → Cold load*).
            var inGrace = _state.value is DeparturesUiState.Loading
            var heldPartial: DeparturesUiState.Loaded? = null
            val graceJob = if (!inGrace) null else launch {
                delay(FIRST_PAINT_GRACE_MS)
                inGrace = false
                val partial = heldPartial ?: return@launch
                heldPartial = null
                if (_state.value is DeparturesUiState.Loading) {
                    _state.value = partial
                    coldLoadUnfinished = true
                }
            }
            // This (main) thread, where the state is published; the progress itself is worked out on
            // [compute], one report at a time, in order ([fetchBatch]).
            val publisher = checkNotNull(currentCoroutineContext()[ContinuationInterceptor])
            val batch = fetchBatch(toFetch, prior, now, reuse, useShared = !force, onProgress = if (!coldAtStart) null else { progress ->
                val (shown, waiting, failed) = progress
                // Worked out here, off the main thread: whichever the screen turns out to show.
                val allFailed = shown.isEmpty() && waiting.isEmpty() && failed.isNotEmpty()
                val firstFailure = if (allFailed) toFetch.firstNotNullOf { failed[it.id] } else null
                val partial = if (allFailed || (shown.isEmpty() && (failed.isEmpty() || waiting.isEmpty()))) {
                    null
                } else {
                    DeparturesUiState.Loaded(
                        stops = shown,
                        fetchedAt = shown.maxOfOrNull { it.fetchedAt } ?: SteadyClock.stamp(now),
                        // The lines the stops declare are vouched for once their check is back; until
                        // then, and for a line only a prediction names (checked once every stop is
                        // in), they're unchecked, as is a stop whose own closure check failed.
                        lineStatuses = progress.lines?.disrupted.orEmpty(),
                        determinedLineIds = progress.lines?.determined.orEmpty(),
                        disruptionUnknown = progress.lines?.let { disruptionUnknownOf(shown, it.determined, progress.closureUnknown, it.unknown) } ?: true,
                        stopsDisruptionUnknown = progress.closureUnknown,
                        checkFailed = checkFailedOf(shown, progress),
                        pendingStops = toFetch.filter { it.id in waiting },
                        closurePending = progress.closurePending,
                        pendingLineIds = pendingLinesOf(shown, progress.lines),
                        statusPending = true,
                        // A stop that already failed is named now, with its reason (SPEC principle 2).
                        partialRefresh = failed.isNotEmpty(),
                        partialStops = toFetch.filter { it.id in failed }
                            .associate { it.id to DeparturesUiState.FailedStop(it.name, failed.getValue(it.id)) },
                        unavailableStopIds = failed.keys - shown.mapTo(HashSet()) { it.stopId },
                    )
                }
                // Published on the main thread, which only decides what to show.
                withContext(publisher) {
                    val current = _state.value
                    val coldLoad = current is DeparturesUiState.Loading ||
                        (current is DeparturesUiState.Loaded && (current.statusPending || coldLoadUnfinished)) ||
                        (current is DeparturesUiState.Error && coldLoadUnfinished)
                    // The last stop too, so it doesn't wait on the line-status check below. A failure is
                    // shown even before any stop lands, while others are still out; once none are (every
                    // stop failed) the batch's own verdict follows at once.
                    if (coldLoad && firstFailure != null) {
                        // Every stop's arrivals failed: say so now rather than wait on closure checks
                        // still out. The batch below has the last word (a closure alone still shows).
                        _state.value = DeparturesUiState.Error(firstFailure)
                        coldLoadUnfinished = true
                        // Said at once, so the grace is over: a closure landing after it shows at once too.
                        inGrace = false
                        heldPartial = null
                    } else if (coldLoad && partial != null) {
                        // Every stop's departures in (or failed): painted now, whole, rather than held for
                        // closure checks still out, which fill in where they land.
                        if (inGrace && waiting.isNotEmpty()) {
                            heldPartial = partial
                        } else {
                            inGrace = false
                            heldPartial = null
                            _state.value = partial
                            coldLoadUnfinished = true
                        }
                    }
                }
            })
            // Done within the grace: the whole batch paints below, once.
            graceJob?.cancel()
            val merged = batch.merged
            val firstError = batch.firstError
            val anyArrivalsFailed = batch.anyArrivalsFailed
            val anyFreshData = batch.anyFreshData
            val lineStatuses = batch.lineStatuses
            val determinedLineIds = batch.determinedLineIds
            val stopsDisruptionUnknown = batch.stopsDisruptionUnknown
            val partial = if (anyFreshData) anyArrivalsFailed else priorPartial
            // The list worked out off the main thread ([compute]), from what this fetch was for: the stops
            // it asked for ([toFetch]), how far each is, and the lines TfL doesn't know as its line
            // check copied them, so nothing is copied or rebuilt here first.
            val seeds = toFetch
            val distances = stopDistanceMeters
            val unknown = batch.unknownLineIds
            val newState = withContext(compute) { when {
                merged.isNotEmpty() ->
                    // Grouping into rows is the screen's job, recomputed from the live
                    // clock (SPEC D4) — the snapshot is the merged stops, each at its age.
                    DeparturesUiState.Loaded(
                        stops = merged,
                        // The whole-screen "last updated" stamp is the freshest stop's age;
                        // per-row withhold uses each stop's own age (SPEC D4).
                        fetchedAt = merged.maxOf { it.fetchedAt },
                        // Some stops shown are fresh and at least one couldn't be refreshed
                        // (kept aged) — say so, rather than pass a mixed-age list off as one
                        // fresh whole. On a total failure (nothing fresh) the merged snapshot
                        // is the prior one unchanged, so inherit its partial flag rather than
                        // clearing it — an already-incomplete list stays incomplete, and that
                        // warning must not be dropped just because the refresh also failed.
                        // priorPartial carries that flag whether the prior was in-memory or
                        // recovered from the store, so a store-recovered incomplete snapshot
                        // stays flagged too.
                        partialRefresh = partial,
                        // Which stops, and why, so the banner can say "Oxford Circus: server error" rather
                        // than leave the rider guessing (SPEC principle 6). A stop that failed this
                        // attempt gets this attempt's reason; one carried over unfetched keeps its own.
                        partialStops = if (partial) {
                            incompleteStops(merged, batch.arrivalsErrors, priorLoaded?.partialStops.orEmpty(), seeds, distances)
                        } else {
                            emptyMap()
                        },
                        partialUnnamed = partial && unnamedIncomplete(merged, seeds),
                        // Nothing fresh came back at all (every request failed) but a prior
                        // snapshot was kept — carry the failure so the screen says "couldn't
                        // refresh" rather than passing the aged rows off as fresh (SPEC D4 /
                        // principle 2). Cleared by the next refresh that gets anything.
                        refreshFailure = if (!anyFreshData && firstError != null) kindOf(firstError) else null,
                        lineStatuses = lineStatuses,
                        // Screen-wide "status unknown" derives from the merged set and this batch's
                        // provenance ([disruptionUnknownOf]).
                        disruptionUnknown = disruptionUnknownOf(merged, determinedLineIds, stopsDisruptionUnknown, unknown),
                        determinedLineIds = determinedLineIds,
                        stopsDisruptionUnknown = stopsDisruptionUnknown,
                        unavailableStopIds = toFetch.mapTo(HashSet()) { it.id } - merged.mapTo(HashSet()) { it.stopId },
                    )
                // Nothing came back and nothing failed → there were no stops to fetch
                // (no watched stops yet, or the seed is empty). That's an empty list, not
                // a network error — TfL was never contacted.
                firstError == null -> DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = SteadyClock.stamp(now))
                // Every stop failed on a first load with no prior snapshot to fall back on
                // → an honest error, not an empty or stale list (SPEC principles 1–2).
                else -> DeparturesUiState.Error(kindOf(firstError))
            } }
            _state.value = newState
            closureShown += batch.closureAsks
            coldLoadUnfinished = false

            // Persist the new last-good so a later launch — and the widget — render it before
            // any fetch, but only when this cycle was authoritative: it returned fresh
            // ARRIVALS (the durable content), or it was the authoritative *empty* (no stops to
            // fetch — e.g. the watched list was emptied), which must overwrite a now-obsolete
            // saved snapshot rather than leaving removed stops on disk for the next launch and
            // the widget to resurrect. A cycle with no fresh arrivals — a total failure, or
            // one where only a disruption returned (disruptions aren't persisted) — has no
            // durable content to save, and saving it would rewrite every stop to
            // `arrivalsFresh = false` and so degrade a previously-complete saved snapshot into
            // one that restores as partial. Best-effort, off the render path. (Removing a
            // departed stop from the widget snapshot is NOT done here — it happens at prune time
            // in [reconcile], independent of this save, so a failed/canceled refresh can't strand
            // it; see [pruneDepartedFromWidget].)
            // A stop carried over as recently fetched ([recentlyFetched]) is durable content too — its
            // arrivals came from a successful fetch moments ago — so a refresh that reuses every stop
            // still saves. Otherwise a refresh that cancels the previous one's save and then reuses
            // its stops would leave the widget and next launch on the older snapshot on disk.
            // Judged on the stops the widget keeps ([forWidget], [widgetJudged]): a journey origin's
            // fresh arrivals must not make a save that rewrites the nearby stops as failed and stamps
            // them "just now".
            val widgetSnapshot = (newState as? DeparturesUiState.Loaded)
                ?.let { forWidget(DeparturesSnapshot(it.stops, it.fetchedAt), nearIds, journeyIds) }
            val widgetStops = widgetSnapshot?.stops.orEmpty()
            val judged = widgetJudged(widgetStops, nearIds)
            val carriedFresh = judged.any { it.stopId in reuse && it.arrivalsFresh }
            val freshNear = judged.any { it.stopId in batch.freshArrivalStopIds }
            val authoritative = freshNear || carriedFresh || (widgetStops.isEmpty() && firstError == null)
            val toSave: DeparturesSnapshot? = widgetSnapshot?.takeIf { authoritative }
            if (toSave != null) {
                try {
                    // Deliberately CANCELLABLE: cancelFetch() is the relocation guard — it cancels this
                    // job before a fresh fix so the soon-to-be-previous location's snapshot is NOT
                    // persisted during the fix window (SPEC D4 / principle 1).
                    // Keeping the stored journey pins, and any stop the widget's live refresh stored
                    // newer (a pinned origin this fetch didn't cover, say).
                    // With the stop the near-me list shows each line from, worked out here off the
                    // main thread, so the widget and the watch show it from the same one.
                    val loaded = newState as DeparturesUiState.Loaded
                    val distances = stopDistanceMeters
                    val dismissedNow = _dismissed.value
                    val hiddenUsed = withContext(io) {
                        val hidden = hiddenModes()
                        val choices = widgetChoicesOf(loaded.stops, loaded.lineStatuses, nearIds, distances, now, dismissedNow, hidden)
                        snapshotStore.saveKeepingJourneys(toSave.copy(nearbyChoices = choices))
                        hidden
                    }
                    // A fix or a filter that changed while this save was on its way had its own write,
                    // which may have landed first and been overwritten here. So when any input the
                    // choices were worked out from has moved on (each compared by reference, so this
                    // costs nothing on the main thread), they're worked out again from the rows just
                    // saved and the inputs as they are now (Codex on #550).
                    if (stopDistanceMeters !== distances ||
                        !ChoiceFilters(dismissedNow, hiddenUsed).sameAs(ChoiceFilters(_dismissed.value, hiddenModes()))
                    ) {
                        updateWidgetNearestFirst(widgetChoicesInput(stopDistanceMeters))
                    }
                    // save() pokes the widget itself (WidgetSnapshotStore), so it re-renders with
                    // the fresh snapshot; no separate redraw needed on this path.
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot save failed: ${reason(e)}")
                }
            } else {
                // No save this cycle — a failed refresh (kept the aged last-good) or an error.
                // The widget's RemoteViews are static: without a redraw they keep showing the age
                // and countdowns from the last save, ageing invisibly and never crossing into the
                // withheld "?" state (SPEC D4 / principle 2). Poke a best-effort redraw so it
                // recomputes from the current clock, without overwriting the last-good snapshot.
                // The lines this refresh checked are stored first, alone, the arrivals left as
                // stored: a suspension declared during an arrivals outage reaches the widget now
                // rather than with the next arrivals worth saving (SPEC D3).
                // Gated on TfL having answered, not on any line being determined: an answer that
                // left every line out is a check too, replacing an older stored disruption.
                // With no stops of its own to check (a cold start whose arrivals all failed), it
                // checks the lines the widget shows instead, from the stored snapshot.
                // That write pokes the widget itself (WidgetSnapshotStore), as a save does, even
                // when it fails; so only a cycle that wrote nothing redraws it here, not both.
                val checks = widgetSnapshot?.lineStatuses.orEmpty()
                var wrote = false
                val write: suspend (Map<String, LineStatusCheck>) -> Unit = {
                    wrote = true
                    withContext(io) { snapshotStore.updateLineStatuses(it) }
                    // The line choices read the statuses (a warning's row, say), so they're worked
                    // out again from the stored rows with the statuses just stored (Codex on #550).
                    updateWidgetNearestFirst(widgetChoicesInput(stopDistanceMeters))
                }
                try {
                    if (widgetSnapshot == null) {
                        checkStoredLines(write)
                    } else if (batch.statusAnswered && checks.isNotEmpty()) {
                        write(checks)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot line status save failed: ${reason(e)}")
                }
                if (!wrote) redrawWidgetBestEffort("failed refresh")
            }
            // A journeys write that failed is retried now the fetch is done.
            if (widgetJourneysPending) writeWidgetJourneys()

            // Reconcile dismissals against this cycle's notices, from the batch directly rather than
            // the UI state — a total arrivals failure with no prior yields an Error state and an empty
            // [merged] even though the disruption checks were authoritative, and a resolved closure on
            // that path must still be pruned (SPEC principle 2). This refresh queries the full watched
            // set ([fetchedStops]); the reconcile is scoped per place to only the queried stops whose
            // disruption lookup succeeded (see reconcileDismissals), so it prunes a resolved notice
            // without touching a place that failed to refresh or belongs to a different nearby set.
            // Each place and line as old as its own answer, a reused lookup or line status perhaps: a
            // dismissal counted after stays.
            reconcileDismissals(
                fetchedStops, merged, lineStatuses, determinedLineIds, stopsDisruptionUnknown, since, refreshSettles,
                stopAsks = batch.closureAsks,
                lineMarks = batch.lineDismissals,
            )
        }
        fetchJob = job
        // Clear the in-flight flag only when this job settles — a job superseded by a
        // newer refresh doesn't clear the newer one's indicator.
        // Nor does a fetch that finishes while a reconcile is on the worker: that reconcile is under way
        // and its own refresh clears it (Codex on #548).
        job.invokeOnCompletion { if (fetchJob === job && !reconcilePending) _refreshing.value = false }
        // The journeys' far ends too, after this fetch (which caches any it covers).
        checkJourneyDestinations()
    }

    /**
     * Reconcile the tiers to a fresh fix of the SAME nearby set (both tiers, order-independent — see
     * [NearbyStopsViewModel.State.Ready.clusterSetKey]) without rebuilding this model. Worked out on
     * the worker (AGENTS.md *Main thread: read and dispatch only*), then applied at once on the main
     * thread: updates the tiers, **prunes** a stop that left the eager tier from the shown state
     * **before the re-fetch starts**, so it can't linger with stale departures through the fetch
     * window (SPEC D4 / principle 1), then re-fetches, which fetches any stop that joined it. A newer
     * reconcile supersedes one still being worked out, and a refresh asked for meanwhile waits for it.
     */
    fun reconcile(
        newEager: List<NearbySelection.NearbyCluster>,
        newMore: List<NearbySelection.NearbyCluster>,
        // Each stop's distance from the new fix (see [refresh]'s far-stop carry-over); null keeps the old.
        newDistanceMeters: Map<String, Double>? = null,
        // Journey stops the new fix holds back (a journey now over a mile away, SPEC *Journeys*):
        // dropped before this reconcile's refresh, so it doesn't fetch them once on the old list
        // ahead of the screen reporting the new one.
        dropJourneyStopIds: Set<String> = emptySet(),
        // A journey the new fix brings back within a mile: its stops aren't known until the screen
        // builds its card, so the refresh waits for the screen's next report ([setJourneyStops],
        // then [journeyStopsReported]) and runs once with them, rather than now and again then.
        awaitJourneyStops: Boolean = false,
        // Both of those, worked out on the worker with the rest when the caller's are a pass of its own.
        @WorkerThread journeyChanges: () -> JourneyChanges = { JourneyChanges(dropJourneyStopIds, awaitJourneyStops) },
    ) {
        reconcileJob?.cancel()
        // A report noted for a superseded reconcile was for its fix, not this one's (Codex on #548).
        reportedDuringReconcile = false
        journeyStopsSetDuringReconcile = false
        reconcilePending = true
        // Busy from here, not only once the refetch starts, so the pull-to-refresh indicator and the
        // foreground and timer checks see the relocation under way while the worker has it (Codex on #548).
        _refreshing.value = true
        reconcileJob = viewModelScope.launch {
            var changes: JourneyChanges? = null
            while (true) {
                // Read here, worked out there; applied only if nothing changed in between, else again.
                val eager0 = eagerStops
                val journey0 = journeyStops
                val dist0 = stopDistanceMeters
                val state0 = _state.value
                val asked = changes
                // Journey stops the screen reported since this began are what it shows now (a journey
                // revealed or opened meanwhile, say): the checks, worked out from before, drop none of
                // them (Codex on #548).
                val reportedSince = journeyStopsSetDuringReconcile
                val (worked, plan) = withContext(compute) {
                    val worked = asked ?: journeyChanges()
                    val drop = if (reportedSince) emptySet() else worked.drop
                    worked to planReconcile(eager0, journey0, dist0, state0, newEager, newMore, newDistanceMeters ?: dist0, drop)
                }
                changes = worked
                if (eagerStops !== eager0 || journeyStops !== journey0 || stopDistanceMeters !== dist0 || _state.value !== state0) continue
                journeyStops = plan.journeyStops
                eagerStops = plan.eagerStops
                more = newMore
                _shownNearStops.value = nearStops
                // Stored when it changes, so the widget folds by where the rider is now whether or not
                // the refresh that follows succeeds (Codex on #473); the distances themselves never are.
                // New distances store fresh choices even when the order holds: crossing the 50 m
                // together-slack moves the fold's stop without moving the order (Codex on #550).
                if (plan.nearestFirst != null || newDistanceMeters != null) {
                    updateWidgetNearestFirst(widgetChoicesInput(newDistanceMeters ?: dist0), order = plan.nearestFirst)
                }
                stopDistanceMeters = newDistanceMeters ?: dist0
                plan.state?.let { _state.value = it }
                plan.nearer?.let(::updateWidgetNearer)
                // Remove the departed stops from the widget snapshot NOW — at prune time, on a scope
                // that outlives both the re-fetch below and this per-set ViewModel (a different-set
                // relocation discards it via NearbyDeparturesStores.ownerFor). Coupling the removal to
                // the re-fetch's save left a departed stop on disk whenever that save was skipped
                // (a non-authoritative or Error cycle), canceled, or lost with the ViewModel — three
                // findings on one mechanism (#87). A direct, save-independent removal closes the class
                // (SPEC D4 / principle 1).
                if (plan.departed.isNotEmpty()) pruneDepartedFromWidget(plan.departed)
                break
            }
            reconcilePending = false
            val reported = reportedDuringReconcile
            reportedDuringReconcile = false
            journeyStopsSetDuringReconcile = false
            if (changes?.await == true && !reported) {
                refreshAwaitsJourneyStops = true
                _refreshing.value = false
            } else {
                refresh()
            }
        }
    }

    /** What a same-set [reconcile] changes about the starred journeys' stops. */
    class JourneyChanges(val drop: Set<String>, val await: Boolean)

    /** A same-set [reconcile] worked out: what it sets, each null when it changes nothing. */
    private class ReconcilePlan(
        val eagerStops: List<StopRef>,
        val journeyStops: List<StopRef>,
        val departed: Set<String>,
        val state: DeparturesUiState?,
        val nearestFirst: List<String>?,
        val nearer: Map<String, Terminating.Nearer>?,
    )

    /** [reconcile]'s passes over the tiers and the shown stops, from what it read. */
    @WorkerThread
    private fun planReconcile(
        eager: List<StopRef>,
        journey: List<StopRef>,
        oldDistances: Map<String, Double>,
        shown: DeparturesUiState,
        newEager: List<NearbySelection.NearbyCluster>,
        newMore: List<NearbySelection.NearbyCluster>,
        distances: Map<String, Double>,
        drop: Set<String>,
    ): ReconcilePlan {
        val before = fetchedOf(eager, journey).mapTo(HashSet()) { it.id }
        val newJourney = if (drop.isEmpty()) journey else journey.filter { it.id !in drop }
        val newEagerStops = newEager.flatMap { cluster -> cluster.stops.map { it.toStopRef() } }
        val near = newEagerStops.map { it.id }
        val order = nearestFirstOf(near, distances)
        val nearestFirst = order.takeIf { it != nearestFirstOf(near, oldDistances) }
        val fetched = fetchedOf(newEagerStops, newJourney).mapTo(HashSet()) { it.id }
        val departed = before - fetched
        var state: DeparturesUiState? = null
        var nearer: Map<String, Terminating.Nearer>? = null
        (shown as? DeparturesUiState.Loaded)?.let { loaded ->
            val moved = remeasured(loaded, nearbyPlacesOf(newEagerStops, newMore, distances))
            if (moved != null) {
                state = moved
                nearer = moved.stops.associate { it.stopId to it.nearer }
            }
            if (departed.isNotEmpty()) {
                val current = moved ?: loaded
                val kept = current.stops.filterNot { it.stopId in departed }
                state = if (kept.isEmpty()) {
                    // Every shown stop departed; don't leave a trusted, recent-stamped empty
                    // "No departures" up through the replacement fetch (which hasn't been checked)
                    // — show the loading placeholder until it returns (SPEC principle 2; Codex).
                    DeparturesUiState.Loading
                } else {
                    // The shown set just lost stops and a re-fetch is pending, so it is genuinely
                    // incomplete — flag it partial rather than pass the reduced list off as a
                    // complete, uniformly-fresh whole (SPEC principle 2).
                    current.copy(
                        stops = kept,
                        fetchedAt = kept.maxOfOrNull { it.fetchedAt } ?: current.fetchedAt,
                        partialRefresh = true,
                        // A still-fetched stop that had failed still has, whether or not it has a row
                        // yet; the stops taking the departed ones' place are pending, not failed, so
                        // they aren't named. Sorted by the new fix's distances.
                        partialStops = byDistance(current.partialStops.filterKeys { it in fetched }, distances),
                        partialUnnamed = true,
                    )
                }
            }
        }
        return ReconcilePlan(newEagerStops, newJourney, departed, state, nearestFirst, nearer)
    }

    /**
     * Take each stop's distance from a new fix ([newDistanceMeters]), without refetching: the stops
     * past the walking reach refresh less often, and the shown stops take their new nearer places
     * now, before any refetch returns, so the rows hide by where the rider is rather than where they
     * were ([Terminating]); the widget's stored copy too, whether or not that refetch succeeds.
     * [reconcile] runs it; so does an opened farther card's model when the rider moves. The shown
     * stops' places are worked out on [compute], over the nearby stops as held now, and published if
     * no newer fix has come since; one worked out from a state replaced meanwhile is worked out again.
     */
    fun remeasure(newDistanceMeters: Map<String, Double>) {
        stopDistanceMeters = newDistanceMeters
        val asked = ++remeasures
        // The nearest-first order and the stop each line shows from, stored at every fix so the widget
        // folds by where the rider is now whether or not the refresh that follows succeeds (Codex on
        // #473); the distances themselves are never stored. The choices can change with no change of
        // order (a route's directions kept together within a few meters); the store writes only what
        // changed.
        val choicesFrom = widgetChoicesInput(newDistanceMeters)
        updateWidgetNearestFirst(choicesFrom)
        viewModelScope.launch {
            while (true) {
                val loaded = _state.value as? DeparturesUiState.Loaded ?: return@launch
                val moved = withContext(compute) {
                    val places = nearbyPlacesOf(choicesFrom.eager, choicesFrom.more, newDistanceMeters)
                    remeasured(loaded, places)?.let { it to it.stops.associate { stop -> stop.stopId to stop.nearer } }
                }
                // A newer fix's own pass owns the places now.
                if (asked != remeasures) return@launch
                // The state changed while this was out (a refresh landed): worked out again from it.
                if (_state.value !== loaded) continue
                moved?.let { (state, nearer) ->
                    _state.value = state
                    updateWidgetNearer(nearer)
                }
                return@launch
            }
        }
    }

    // How many fixes [remeasure] has taken: a pass for an older one doesn't publish.
    private var remeasures = 0

    /**
     * What the widget's order and line choices are worked out from at [distances]: this model's nearby
     * stops as held, read here and handed over whole, so nothing walks them on the caller's thread.
     */
    private fun widgetChoicesInput(distances: Map<String, Double>) = WidgetChoicesInput(distances, nearStops, more)

    /**
     * The screen has reported its journey stops ([setJourneyStops] ran first, refreshing if they
     * changed): run a refresh a reconcile left waiting on this report, if that didn't already.
     */
    fun journeyStopsReported() {
        if (reconcilePending) {
            reportedDuringReconcile = true
            return
        }
        if (refreshAwaitsJourneyStops) refresh()
    }

    /**
     * Remove [departed] from the persisted widget snapshot, off this ViewModel's lifecycle. Launched
     * under [NonCancellable] so it completes even if a following different-set relocation cancels
     * [viewModelScope] (`ownerFor` clears the old set's store) before the write lands — the whole
     * point is that the removal does not depend on the re-fetch's save or this ViewModel surviving.
     * Best-effort like the snapshot save: a failure is logged, sanitized, and swallowed.
     */
    /** Store [nearer] in the widget snapshot, off this ViewModel's lifecycle, like [pruneDepartedFromWidget]. */
    private fun updateWidgetNearer(nearer: Map<String, Terminating.Nearer>) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable + io) { snapshotStore.updateNearer(nearer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("widget snapshot nearer update failed: ${reason(e)}")
            }
        }
    }

    /**
     * Store [order] in the widget snapshot, off this ViewModel's lifecycle, like [updateWidgetNearer],
     * with the stop each line shows from worked out again from the stored rows ([choicesFrom]), off
     * the main thread, each stop given its nearer places at these distances first, as the list's are.
     * The order is worked out there too, from [choicesFrom], unless [order] is given (a reconcile's
     * plan has it); an empty one stores nothing.
     */
    /**
     * The dismissed alerts and hidden modes a widget write read, to tell a change from a repeat. Compared
     * by reference, not by contents, so the check on the main thread costs nothing however many alerts
     * have been dismissed (Codex on #550): each setting hands out the same set until it changes, and a
     * new set with the same contents costs only a repeat write.
     */
    private class ChoiceFilters(val dismissed: Set<DismissedAlert>, val hidden: Set<String>) {
        fun sameAs(other: ChoiceFilters) = dismissed === other.dismissed && hidden === other.hidden
    }

    // What the latest widget write read; null until the first has read them.
    private var choiceFiltersUsed: ChoiceFilters? = null

    private fun updateWidgetNearestFirst(choicesFrom: WidgetChoicesInput, order: List<String>? = null) {
        // A model that doesn't feed the widget (a farther card, a station's page) stores nothing, so it
        // takes no number: one would supersede the near-me model's write and write nothing itself.
        if (snapshotStore === SnapshotStore.NONE) return
        val asked = NearestFirstWrites.asked.incrementAndGet()
        viewModelScope.launch {
            try {
                val now = clock()
                val dismissedNow = _dismissed.value
                val hidden = hiddenModes()
                choiceFiltersUsed = ChoiceFilters(dismissedNow, hidden)
                val choicesFor: (DeparturesSnapshot) -> List<FoldChoice> = { stored ->
                    val places = nearbyPlacesOf(choicesFrom.eager, choicesFrom.more, choicesFrom.distances)
                    val stops = stored.stops.map { stop ->
                        val nearer = Terminating.nearer(stop.stopId, places)
                        if (nearer == stop.nearer) stop else stop.copy(nearer = nearer)
                    }
                    widgetChoicesOf(stops, stored.liveLineStatuses(now), choicesFrom.eager.map { it.id }, choicesFrom.distances, now, dismissedNow, hidden)
                }
                withContext(NonCancellable + io) {
                    val nearestFirst = order ?: nearestFirstOf(choicesFrom.eager.map { it.id }, choicesFrom.distances)
                    if (nearestFirst.isEmpty()) return@withContext
                    NearestFirstWrites.lock.withLock {
                        if (asked == NearestFirstWrites.asked.get()) snapshotStore.updateNearestFirst(nearestFirst, choicesFor)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("widget snapshot order update failed: ${reason(e)}")
            }
        }
    }

    private fun pruneDepartedFromWidget(departed: Set<String>) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable + io) { snapshotStore.pruneStops(departed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("widget snapshot prune failed: ${reason(e)}")
            }
        }
    }

    /**
     * Flip [row]'s star (SPEC D8) — pin it to the top of the list, or unpin it — and persist
     * the change. Off the main thread; the [starred] flow re-emits from the store, so the list
     * re-orders without this touching UI state directly. Best-effort: a write failure is logged
     * and the set is unchanged (a preserved [StarredRowSet.Unavailable] is a no-op in the store).
     */
    fun toggleStar(row: DepartureRow) {
        usage(UsageEvent.Tapped(if (StarredRow.of(row) in _starred.value) UsageEvent.Tap.UNSTAR else UsageEvent.Tap.STAR))
        viewModelScope.launch {
            // NonCancellable for the whole action: the tap is the user's decision, and it must land
            // — and reach the widget — even if this model is cleared the next moment (a searched
            // station's page closing right after a star). A canceled write would drop the star with
            // no failure to show; a canceled redraw would leave the widget's pinned order stale.
            withContext(NonCancellable + io) {
                try {
                    starredStore.toggle(StarredRow.of(row))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("star toggle failed: ${reason(e)}")
                    // The write didn't take and the store won't re-emit, so the star silently
                    // stays as it was — tell the user rather than let the tap look broken.
                    _starWriteFailed.value = true
                    return@withContext
                }
                onStarToggled(row)
                // The pin is persisted and the in-app list has already re-ordered off [starred].
                // The widget redraw is a separate, secondary surface: re-render it now so the star
                // change shows without waiting for the next fetch (it pins starred rows from the
                // persisted state — SPEC D8), but a redraw failure is logged only — it must not
                // report "couldn't save your pin," which is reserved for an actual store failure.
                redrawWidgetBestEffort("star change")
            }
        }
    }

    /**
     * Dismiss [row]'s service alert (SPEC *Disruptions*) — its stop closure, else its line's status —
     * hiding it until its content changes, and persist it. Off the main thread; the [dismissed] flow
     * re-emits from the store, so the alert disappears without this touching UI state directly. A
     * no-op for a row carrying no alert. Best-effort: a write failure is logged and the alert stays;
     * the widget shows no alerts, so it needs no redraw.
     */
    fun dismissAlert(row: DepartureRow) {
        viewModelScope.launch { dismissAlert(dismissedStore, row, io, _dismissWriteFailed, warn, _dismissed) }
    }

    /**
     * Prune dismissals whose notice is no longer in the feed — so a resolved incident's stale
     * `(place, text)` dismissal can't later suppress a new same-text closure (SPEC principle 2 —
     * never hide a warning). Scoped to places actually checked this cycle: a place is prunable only
     * when every one of its stops was **queried** ([queriedStops], the requested set — not the merged
     * UI set, which drops a stop whose arrivals failed with no prior even when its disruption came
     * back clear) and its disruption lookup succeeded (not in [stopsDisruptionUnknown]). A place not
     * queried this cycle (a different nearby set — the store is shared) or with any unknown member is
     * left alone, so a still-valid dismissal never lapses because its place wasn't looked at. Line
     * dismissals are scoped the same way, to the lines whose status TfL returned this cycle
     * ([checkedLineIds]): a line whose lookup failed or wasn't queried keeps its dismissal.
     *
     * The reconciled set is applied to the in-memory [_dismissed] **before** the persist, so it holds
     * even if the store write fails: an unverified stale signature can't suppress a recurrence this
     * session (the persisted set self-heals on the next successful reconcile; a restart is bounded by
     * the day-expiry follow-up). Best-effort; a no-op write when nothing changed.
     */
    private suspend fun reconcileDismissals(
        queriedStops: List<StopRef>,
        shownStops: List<StopArrivals>,
        lineStatuses: Map<String, LineStatus>,
        checkedLineIds: Set<String>,
        stopsDisruptionUnknown: Set<String>,
        // The store's [DismissedAlertsStore.mark] before the check asked anything: a dismissal made
        // after is newer than its verdict, so it's never let go of, in memory or in the store.
        since: Long,
        // This kind of check's settlements, of which this is now the newest; null for one nothing
        // supersedes.
        turns: Turns? = null,
        // The closure lookup each stop's answer came from, by stop id: one older than [since] (a reused
        // or shared lookup) settles only the dismissals made before it ([StopClosureCache.Ask.dismissals]).
        stopAsks: Map<String, StopClosureCache.Ask> = emptyMap(),
        // The mark each line's status was asked at, by line id, for each line check the verdicts came from.
        lineMarks: List<Map<String, Long>> = emptyList(),
    ) {
        val now = clock()
        // Still the newest once the worker hands the verdict back; one superseded meanwhile leaves the
        // dismissals to the newer one. Once applied in memory, it's written too, so the two agree.
        val current = turns?.take() ?: { true }
        // Once the refresh's checks are in, settling them outlasts the list, as the write below does:
        // leaving while the worker has them would otherwise keep an ended notice's dismissal stored.
        withContext(NonCancellable) { settleDismissals(queriedStops, shownStops, lineStatuses, checkedLineIds, stopsDisruptionUnknown, now, since, stopAsks, lineMarks, current) }
    }

    // [reconcileDismissals]'s work, run to the end once begun.
    private suspend fun settleDismissals(
        queriedStops: List<StopRef>,
        shownStops: List<StopArrivals>,
        lineStatuses: Map<String, LineStatus>,
        checkedLineIds: Set<String>,
        stopsDisruptionUnknown: Set<String>,
        now: Instant,
        mark: Long,
        stopAsks: Map<String, StopClosureCache.Ask>,
        lineMarks: List<Map<String, Long>>,
        current: () -> Boolean,
    ) {
        val dismissed = _dismissed.value
        // Worked out across the whole board, so off the main thread: every live alert, each line's under
        // way ones included, and every dismissal (Codex on #519).
        val (since, settled) = withContext(compute) {
            // Includes each near-me folded card's identity, so its dismissal isn't pruned as not-live.
            val live = DepartureRows.liveStopClosureAlerts(DepartureRows.across(shownStops, now, lineStatuses)) +
                DepartureRows.liveLineStatusAlerts(lineStatuses, now)
            fun placeOf(stop: StopRef) = stopPlaceKey(stop.hubId, stop.clusterId, stop.name, stop.id)
            // A place with any member whose disruption lookup failed this cycle is not fully known, so it
            // is excluded from the checked set and its dismissals are retained.
            val unknownPlaces = queriedStops.asSequence()
                .filter { it.id in stopsDisruptionUnknown }
                .mapTo(mutableSetOf()) { placeOf(it) }
            val checkedPlaces = queriedStops.asSequence()
                .map { placeOf(it) }
                .filterTo(mutableSetOf()) { it !in unknownPlaces } +
                // A line still waiting on which way its alerts apply isn't split yet, so a dismissal of
                // one direction's alert can't be matched against it: retained until the split lands.
                checkedLineIds.filterNot { lineStatuses[it]?.awaitingDirections == true }.map { lineAlertKey(it) }
            // Each place as old as its oldest stop's answer, each line as its own; never newer than the check.
            val marks = HashMap<String, Long>()
            for (stop in queriedStops) stopAsks[stop.id]?.let { marks.merge(placeOf(stop), minOf(mark, it.dismissals), ::minOf) }
            for (check in lineMarks) for ((line, at) in check) marks.merge(lineAlertKey(line), minOf(mark, at), ::minOf)
            // What's let go of, from what's dismissed now, so one made meanwhile stays (Codex on #519).
            DismissalMarks(mark, marks) to Triple(live, checkedPlaces, dismissed - Dismissed.reconcile(dismissed, live, checkedPlaces))
        }
        val (live, checkedPlaces, gone) = settled
        if (!current()) return
        // Reconcile the in-memory set first — safe regardless of whether the persist below succeeds.
        // The set left is worked out on the worker too, as it grows with every dismissal, from the set as
        // it is then, so a dismissal landed meanwhile stays (Codex on #519). Less any dismissed again
        // since the mark, as one step with any tap's count and add ([DismissedAlertsStore.prune]): a tap
        // meanwhile is the rider's newer word.
        if (gone.isNotEmpty()) {
            withContext(compute) { dismissedStore.prune(gone, since) { still -> _dismissed.update { it - still } } }
        }
        try {
            // NonCancellable, as a dismissal's write is: leaving while it's written would otherwise
            // leave the ended notice's dismissal stored, to hide the same notice coming back (Codex,
            // PR #379).
            // Only what was dismissed when it settled: one made since stays stored, as in memory.
            withContext(NonCancellable + io) { dismissedStore.reconcile(live, checkedPlaces, dismissed, since) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("dismissal reconcile failed: ${reason(e)}")
        }
        // One dismissed again on another screen while this pruned, which left the store as it was and so
        // told this set nothing, is taken back ([settledBack]).
        if (gone.isNotEmpty()) {
            withContext(compute) {
                settledBack(dismissedStore, gone, since).takeIf { it.isNotEmpty() }?.let { back -> _dismissed.update { it + back } }
            }
        }
    }

    /**
     * Checks the lines of the stored widget snapshot ([SnapshotStore.stored]) and stores what TfL
     * answers, as the widget's own refresh does with no arrivals fresh ([WidgetRefresh.refresh]): for
     * a refresh that held no stops to check, so a suspension declared during an arrivals outage still
     * reaches the widget, and the dismissals of those lines' alerts are reconciled against the answers.
     * Lines checked within [lineStatusReuse] aren't asked again; a failed request stores nothing.
     * What's answered is stored through [write].
     */
    private suspend fun checkStoredLines(write: suspend (Map<String, LineStatusCheck>) -> Unit) {
        // The dismissals so far, before anything is asked ([reconcileDismissals]).
        val since = dismissedStore.mark()
        val stored = withContext(io) { snapshotStore.stored() } ?: return
        var answered: List<LineStatus>? = null
        val checked = WidgetRefresh.refreshedLineStatuses(stored, clock(), reuse = lineStatusReuse, answeredAt = clock) { ids ->
            try {
                withContext(io) { client.lineStatuses(ids) }.also { answered = answered.orEmpty() + it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("widget line status failed for ${ids.size} line(s): ${reason(e)}")
                null
            }
        }
        val found = answered ?: return
        write(checked.lineStatuses)
        // The answers settle the dismissals of those lines' alerts, as a refresh's own check does
        // ([reconcileDismissals]): one TfL now reports changed or ended is forgotten.
        reconcileDismissals(
            queriedStops = emptyList(),
            shownStops = emptyList(),
            lineStatuses = found.filter { it.hasAlerts }.associateBy { it.lineId },
            checkedLineIds = found.mapTo(HashSet()) { it.lineId },
            stopsDisruptionUnknown = emptySet(),
            since = since,
            turns = refreshSettles,
        )
    }

    /**
     * Redraw the widget, best-effort: a secondary surface, so a failure is logged (sanitized) and
     * swallowed — it never fails the primary operation. Rethrows [CancellationException] first so
     * structured concurrency isn't broken. [context] names the trigger for the log line.
     */
    private suspend fun redrawWidgetBestEffort(context: String) {
        try {
            redrawWidget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("widget redraw after $context failed: ${reason(e)}")
        }
    }

    /** Called by the screen once it has surfaced the star-write failure, so it isn't shown again. */
    fun starWriteFailureShown() {
        _starWriteFailed.value = false
    }

    /** Called by the screen once it has surfaced the dismiss-write failure, so it isn't shown again. */
    fun dismissWriteFailureShown() {
        _dismissWriteFailed.value = false
    }

    /**
     * Whether a saved snapshot is incomplete relative to the fetched set — a fetched stop is missing
     * (an earlier partial refresh saved only the stops that succeeded), or a stop is present
     * but was carried-forward-stale when saved (`arrivalsFresh == false`), so the snapshot is
     * mixed-age. Either way it's shown as partial rather than passed off as a complete,
     * uniformly-fresh whole (SPEC principle 2). A restored stop is in exactly one of three
     * states — present-and-fresh, present-and-carried-stale, or missing — so this is the
     * complete incompleteness test.
     */
    private fun isIncomplete(stops: List<StopArrivals>, seeds: List<StopRef> = fetchedStops): Boolean {
        val shown = stops.mapTo(HashSet()) { it.stopId }
        return seeds.any { it.id !in shown } || stops.any { !it.arrivalsFresh }
    }

    /**
     * The stops that make [stops] incomplete (see [isIncomplete]) — missing, or kept at an older
     * age — nearest first ([byDistance]), each with why: its failure in [failedNow] if this fetch
     * tried it, else the reason it already had in [prior], else unknown. Empty when none has a
     * name, and the banner falls back to its generic wording.
     */
    private fun incompleteStops(
        stops: List<StopArrivals>,
        failedNow: Map<String, DeparturesUiState.Error.Kind> = emptyMap(),
        prior: Map<String, DeparturesUiState.FailedStop> = emptyMap(),
        // The stops asked for, and their distances: passed in where this runs off the main thread.
        seeds: List<StopRef> = fetchedStops,
        distances: Map<String, Double> = stopDistanceMeters,
    ): Map<String, DeparturesUiState.FailedStop> {
        fun failed(id: String, name: String) = id to DeparturesUiState.FailedStop(name, failedNow[id] ?: prior[id]?.reason)
        val byId = stops.associateBy { it.stopId }
        val seeded = seeds.mapNotNull { seed ->
            val shown = byId[seed.id]
            when {
                shown == null -> failed(seed.id, seed.name)
                !shown.arrivalsFresh -> failed(seed.id, shown.stopName.ifBlank { seed.name })
                else -> null
            }
        }
        val seedIds = seeds.mapTo(HashSet()) { it.id }
        val unseeded = stops.filter { it.stopId !in seedIds && !it.arrivalsFresh }.map { failed(it.stopId, it.stopName) }
        // [fetchedStops] is in tier order, not distance order (a journey's stop comes after every
        // near one, and one cluster's stop can be farther than the next cluster's), so order by distance.
        return byDistance((seeded + unseeded).toMap(), distances)
    }

    /** Whether some stop that makes [stops] incomplete has no name to show ([incompleteStops] drops it). */
    private fun unnamedIncomplete(stops: List<StopArrivals>, seeds: List<StopRef> = fetchedStops): Boolean {
        val byId = stops.associateBy { it.stopId }
        val seedIds = seeds.mapTo(HashSet()) { it.id }
        return seeds.any { seed ->
            val shown = byId[seed.id]
            (shown == null && seed.name.isBlank()) ||
                (shown != null && !shown.arrivalsFresh && shown.stopName.isBlank() && seed.name.isBlank())
        } || stops.any { it.stopId !in seedIds && !it.arrivalsFresh && it.stopName.isBlank() }
    }

    /**
     * The aged last-good [DeparturesUiState.Loaded] to show from a restored [snapshot] before
     * any network. A saved snapshot can be incomplete (a missing stop, or a carried-stale
     * one), shown as partial rather than passed off as a complete, fresh whole (see
     * [isIncomplete]). It hasn't been status-checked, so it's flagged disruption-unknown — a
     * service suspended since the snapshot isn't shown as normal until the refresh
     * re-establishes status (SPEC principle 1); stop closures aren't persisted at all
     * (point-in-time; see PersistedSnapshot), so there are none to resurrect. Both restore
     * paths — init, and a refresh that beats or replaces it — build the state here, so the
     * aged snapshot always reaches the screen the same way (SPEC principle 5). The immediate
     * refresh recomputes everything once it completes.
     */
    private fun restoredLoaded(snapshot: DeparturesSnapshot): DeparturesUiState.Loaded =
        DeparturesUiState.Loaded(
            stops = snapshot.stops,
            fetchedAt = snapshot.fetchedAt,
            partialRefresh = isIncomplete(snapshot.stops),
            // Which stops, not why: the reason isn't persisted with the snapshot, so each is unknown.
            partialStops = incompleteStops(snapshot.stops),
            partialUnnamed = isIncomplete(snapshot.stops) && unnamedIncomplete(snapshot.stops),
            disruptionUnknown = true,
        )

    /**
     * The interchange [hubId]'s name + member aliases, from cache or a one-time [TflClient.hubInfo]
     * lookup, so a folded near-me disruption alert titles by the interchange and the strip can drop
     * a redundant leading name in any member spelling (SPEC *Disruptions*).
     *
     * The durable [hubNames] holds successes across refreshes; a failure is never cached, so the
     * next refresh retries rather than the title being permanently blanked. Within one refresh the
     * caller deduplicates hub ids before calling, so a hub shared by several disrupted stops costs
     * at most one call even when it fails, and every member agrees on the result.
     *
     * Best-effort: a failed lookup returns empty and the alert falls back to the stop's own name.
     * Rethrows [CancellationException] first (structured concurrency). The log carries only the hub
     * id — a public TfL place identifier, like a stop id (SPEC *Privacy*).
     */
    private suspend fun resolveHubInfo(hubId: String, sent: () -> Unit = {}): HubInfo =
        // A success is durable; an empty isn't cached, so the next refresh retries. One already in
        // flight (a trip on the way's, say) is waited on rather than asked again.
        hubNames.load(hubId) {
            sent()
            try {
                withContext(io) { client.hubInfo(hubId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("hub lookup failed for $hubId: ${reason(e)}")
                HubInfo()
            }
        }

    /**
     * Runs one TfL [block], capturing an ordinary failure as a [Result] so a parallel sibling isn't
     * canceled by it. [CancellationException] is rethrown, never captured (structured concurrency).
     */
    private suspend inline fun <T> runCatchingTfl(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun reason(e: Throwable): String =
        (e as? TflException)?.message ?: e::class.simpleName.orEmpty()

    private fun kindOf(e: Throwable?): DeparturesUiState.Error.Kind = errorKindOf(e)
}

/**
 * The user-facing error a failed TfL call maps to: offline, rate-limited, a network error, the user's
 * key rejected, or else a server one.
 */
internal fun errorKindOf(e: Throwable?): DeparturesUiState.Error.Kind = when (e) {
    is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
    is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
    is TflException.Network -> DeparturesUiState.Error.Kind.NETWORK
    is TflException.KeyRejected -> DeparturesUiState.Error.Kind.KEY_REJECTED
    else -> DeparturesUiState.Error.Kind.SERVER
}

/**
 * The widget-journeys writes of every [MainViewModel] in the process: one lock, so no two
 * interleave, and a generation per ViewModel, so only the newest one's writes land — one cleared by
 * a relocation can't land its older report over its successor's.
 */
/**
 * The acknowledged flags for a failed star or alert-dismiss write (see [MainViewModel.starWriteFailed]),
 * shareable between models so a failure reaches whichever screen is shown next.
 */
class WriteFailures {
    val star = MutableStateFlow(false)
    val dismiss = MutableStateFlow(false)
}

/**
 * Settlements of one kind, newest last ([take]): a settlement is current while no newer one has
 * taken a turn. Taken and read on the main thread.
 */
internal class Turns {
    private var latest = 0L

    /** A new turn, and whether it's still the newest. */
    fun take(): () -> Boolean {
        val mine = ++latest
        return { latest == mine }
    }
}

/** The generation of a model that never writes the widget's journeys: no real turn is ever this. */
private const val NO_WIDGET_JOURNEYS = -1L

internal object WidgetJourneysWrites {
    val lock = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()

    fun next(): Long = generation.incrementAndGet()

    fun latest(): Long = generation.get()
}

/**
 * Failed [stops] nearest first by [distanceMeters], dropping blank names. A stop with no distance
 * (a watched or journey stop) keeps its place after those with one (a stable sort).
 */
/**
 * The stops a model fetches: the nearby ones, each declaring its journeys' lines too so their status
 * is checked (a suspended one with no predictions still shows on the card), then the journey origins
 * not already near.
 */
private fun fetchedOf(near: List<StopRef>, journeyStops: List<StopRef>): List<StopRef> {
    val nearIds = near.mapTo(HashSet()) { it.id }
    val journeyLines = journeyStops.associate { it.id to it.lines }
    val merged = near.map { stop ->
        val extra = journeyLines[stop.id] ?: return@map stop
        stop.copy(lines = (stop.lines + extra).distinctBy { it.id })
    }
    return merged + journeyStops.filter { it.id !in nearIds }
}

/** Where else the rider may be: both tiers' stops, each with its distance. */
private fun nearbyPlacesOf(
    eager: List<StopRef>,
    more: List<NearbySelection.NearbyCluster>,
    distanceMeters: Map<String, Double>,
): List<Terminating.Place> {
    val all = eager.map { Triple(it.id, it.clusterId, it.name) } +
        more.flatMap { cluster -> cluster.stops.map { Triple(it.id, it.clusterId, it.name) } }
    return all.mapNotNull { (id, cluster, name) ->
        distanceMeters[id]?.let { Terminating.Place(id, cluster, name, it) }
    }
}

/** [loaded] with each stop's nearer places taken from [places], or null when none moved. */
private fun remeasured(loaded: DeparturesUiState.Loaded, places: List<Terminating.Place>): DeparturesUiState.Loaded? {
    val moved = loaded.stops.map { stop ->
        val nearer = Terminating.nearer(stop.stopId, places)
        if (nearer == stop.nearer) stop else stop.copy(nearer = nearer)
    }
    return if (moved != loaded.stops) loaded.copy(stops = moved) else null
}

private fun byDistance(
    stops: Map<String, DeparturesUiState.FailedStop>,
    distanceMeters: Map<String, Double>,
): Map<String, DeparturesUiState.FailedStop> =
    stops.entries
        .filter { it.value.name.isNotBlank() }
        .sortedBy { distanceMeters[it.key] ?: Double.MAX_VALUE }
        .associateTo(LinkedHashMap()) { it.key to it.value }

/** What [MainViewModel] works the widget's line choices out from: the nearby stops and their distances. */
private class WidgetChoicesInput(
    val distances: Map<String, Double>,
    val eager: List<StopRef>,
    val more: List<NearbySelection.NearbyCluster>,
)

/**
 * The stop the near-me list shows each line from ([DepartureRows.nearbyChoices]), as [listRowsOf]
 * folds it: the nearby stops' rows less the hidden modes, with the dismissed alerts. Saved with the
 * widget's snapshot so the widget and the watch show each line from the same stop. On a worker.
 */
@WorkerThread
private fun widgetChoicesOf(
    stops: List<StopArrivals>,
    lineStatuses: Map<String, LineStatus>,
    nearIds: Collection<String>,
    distances: Map<String, Double>,
    now: Instant,
    dismissed: Set<DismissedAlert>,
    hidden: Set<String>,
): List<FoldChoice> {
    // As a set here, on the worker: the caller only hands over what it already holds.
    val ids = nearIds as? Set<String> ?: nearIds.toHashSet()
    val near = stops.filter { it.stopId in ids && it.stopId in distances }
    if (near.isEmpty()) return emptyList()
    val rows = HiddenModes.rows(DepartureRows.across(near, now, lineStatuses), hidden)
    return DepartureRows.nearbyChoices(rows, distances, dismissed)
}

/** [ids] that have a distance in [distanceMeters], nearest first (a tie by id, so the order is stable). */
internal fun nearestFirstOf(ids: Collection<String>, distanceMeters: Map<String, Double>): List<String> =
    ids.filter { it in distanceMeters }.sortedWith(compareBy<String> { distanceMeters.getValue(it) }.thenBy { it })

/**
 * Every nearest-first order asked for in this process, numbered, and the lock their writes take in turn:
 * a write a newer one has superseded is skipped, so an older order can't land last, from this model or
 * one cleared on a move whose write outlived it (Codex on #473). Process-wide because those writes are.
 */
private object NearestFirstWrites {
    val asked = java.util.concurrent.atomic.AtomicLong()
    val lock = Mutex()
}
