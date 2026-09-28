package app.stopdash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.RailFeed
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.Dismissed
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.HubInfo
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
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
import app.stopdash.domain.LoadStats
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.StopDisruptionBatch
import app.stopdash.domain.TflClient
import app.stopdash.domain.stopPlaceKey
import app.stopdash.domain.TflException
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneys
import app.stopdash.domain.WidgetJourneysReport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.launch
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
    // The farther "more" clusters (SPEC *Finding stops → Near me now*), paged in on a per-mode
    // "More" tap. Empty for a watched-stops view or a nearby set with nothing beyond the eager tier.
    initialMore: List<NearbySelection.NearbyCluster> = emptyList(),
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Persists the last-good snapshot across sessions and to the widget. No-op by default so
    // tests and an unwired build run identically minus the restore.
    private val snapshotStore: SnapshotStore = SnapshotStore.NONE,
    // Persists which rows the user has starred (the ranking overlay). No-op by default, so
    // tests and an unwired build run identically minus starring.
    private val starredStore: StarredRowsStore = StarredRowsStore.NONE,
    // Persists which stop-closure alerts the user has dismissed (hidden until their text changes).
    // No-op by default, so tests and an unwired build run identically minus dismissing.
    private val dismissedStore: DismissedAlertsStore = DismissedAlertsStore.NONE,
    // The widget's stored snapshot, which a saved line-status dismissal marks at once so the widget
    // and the watch follow ([dismissAlert]); every screen's model gets it, whether or not it saves
    // the snapshot itself. No-op by default.
    private val widgetDismissals: SnapshotStore = SnapshotStore.NONE,
    // No-op by default: the shared on-device logger is deferred until `docs/PRIVACY.md`
    // describes what it carries (both are their own Phase 1 items), so nothing is logged
    // in production until then. The seam stays for tests and that later wiring.
    private val warn: (String) -> Unit = {},
    // Best-effort widget redraw: poked after a star toggle (so the widget's pinned order updates
    // at once — SPEC D8) AND after a completed refresh that did NOT save (a failed or aged-only
    // cycle), so the static RemoteViews recompute the snapshot's age from the current clock and
    // withhold stale countdowns (SPEC D4) rather than freezing at the last save's stamp. Doesn't
    // persist anything. No-op by default; MainActivity supplies the widget update.
    private val redrawWidget: suspend () -> Unit = {},
    // How recently a stop must have been fetched for a refresh to carry it over without a request,
    // and how long a stop's closure lookup is reused ([recentlyFetched], [disruptionCache]). Zero —
    // always refetch — by default, so tests drive them explicitly; the app passes [ARRIVALS_REUSE]
    // and [DISRUPTION_REUSE].
    private val arrivalsReuse: Duration = Duration.ZERO,
    private val disruptionReuse: Duration = Duration.ZERO,
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
) : ViewModel() {
    // The near-me tiers, updatable IN PLACE so a relocation that keeps the same nearby set can
    // reconcile them without rebuilding this ViewModel (which would drop a revealed expansion —
    // the redesign this reveal is for). The eager tier is fetched and shown at once; a `more`
    // cluster's stops join the fetched set once its key is revealed.
    private var eagerStops: List<StopRef> = seedStops
    private var stopDistanceMeters: Map<String, Double> = stopDistanceMeters

    // Each stop's distance as this model last took it ([remeasure]), for tests.
    internal val distanceMeters: Map<String, Double> get() = stopDistanceMeters
    private var more: List<NearbySelection.NearbyCluster> = initialMore
    private var revealedKeys: Set<String> = emptySet()

    private fun nearbyPlaces(): List<Terminating.Place> {
        val all = eagerStops.map { Triple(it.id, it.clusterId, it.name) } +
            more.flatMap { cluster -> cluster.stops.map { Triple(it.id, it.clusterId, it.name) } }
        return all.mapNotNull { (id, cluster, name) ->
            stopDistanceMeters[id]?.let { Terminating.Place(id, cluster, name, it) }
        }
    }

    // The set actually fetched and shown: the eager tier plus every revealed `more` cluster's
    // stops. DERIVED — so a cluster dropped on a relocation leaves the fetched set automatically,
    // with no parallel list to fall out of sync (the single-owner reveal design).
    private val nearStops: List<StopRef>
        get() = eagerStops + more.asSequence()
            .filter { it.key in revealedKeys }
            .flatMap { cluster -> cluster.stops.asSequence().map { it.toStopRef() } }
            .toList()

    // The origins of the starred journeys (SPEC *Journeys*), fetched alongside the near-me stops so a
    // journey card has its departures. One not already near is fetched but never saved to the widget
    // snapshot, which shows only the nearby set ([journeyOnly]).
    private var journeyStops: List<StopRef> = emptyList()

    // A same-set reconcile's refresh held for the screen's next journey-stop report (see reconcile).
    private var refreshAwaitsJourneyStops = false

    private val fetchedStops: List<StopRef>
        get() {
            val near = nearStops
            val nearIds = near.mapTo(HashSet()) { it.id }
            // A nearby stop that is also a journey origin declares the journey's lines too, so their
            // status is checked (a suspended one with no predictions still shows on the card).
            val journeyLines = journeyStops.associate { it.id to it.lines }
            val merged = near.map { stop ->
                val extra = journeyLines[stop.id] ?: return@map stop
                stop.copy(lines = (stop.lines + extra).distinctBy { it.id })
            }
            return merged + journeyStops.filter { it.id !in nearIds }
        }

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
        )
    }

    /** The [lineStatusCache] entries for the lines [stops] show, as the widget snapshot's checks. */
    private fun widgetLineChecks(stops: List<StopArrivals>): Map<String, LineStatusCheck> =
        LineStatusCheck.linesOf(stops).mapNotNull { id ->
            // A status the user dismissed here is marked dismissed, so the widget and the watch
            // drop its mark too while the line still counts as checked (SPEC *Disruptions*).
            val verdict = lineStatusCache[id]?.let { (at, status) ->
                LineStatusCheck(status, at, dismissed = DismissedAlert.ofLineStatus(status) in _dismissed.value)
            }
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
        if (
            refreshAwaitsJourneyStops || dropped || linesAdded || fetchJob?.isActive == true ||
            stops.any { it.id !in shown }
        ) {
            refresh()
        }
    }

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
    private val destinationRequests = HashMap<String, Deferred<Result<List<StopDisruption>>>>()

    /** Starts a closure request for each of [ids] (bus poles batched, as in [fetchBatch]). */
    private fun requestDestinationDisruptions(ids: List<String>) {
        val (poles, others) = ids.partition(StopDisruptionBatch::isPole)
        val requests = poles.chunked(StopDisruptionBatch.MAX_PER_REQUEST).flatMap { batchIds ->
            val batch = viewModelScope.async {
                runCatchingTfl { withContext(io) { client.poleDisruptions(batchIds) } }
                    .onSuccess { found -> val at = clock(); batchIds.forEach { disruptionCache[it] = at to found[it].orEmpty() } }
                    // Logged here, not by the check: a request can outlive the check that asked for it.
                    .onFailure { e -> batchIds.forEach { warn("destination disruption fetch failed for stop $it: ${reason(e)}") } }
            }
            batchIds.map { id -> id to viewModelScope.async { batch.await().map { it[id].orEmpty() } } }
        } + others.map { id ->
            id to viewModelScope.async {
                runCatchingTfl { withContext(io) { client.stopDisruptions(id) } }
                    .onSuccess { disruptionCache[id] = clock() to it }
                    .onFailure { e -> warn("destination disruption fetch failed for stop $id: ${reason(e)}") }
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
            fun cached(id: String) = disruptionCache[id]?.takeIf { (at, _) -> isWithin(at, now, disruptionReuse) }?.second
            fun failedInFetch(id: String) = joinedFetchAt != null && disruptionFailedAt[id] == joinedFetchAt
            val toAsk = stops.map { it.id }.distinct().filter { cached(it) == null && !failedInFetch(it) }
            requestDestinationDisruptions(toAsk.filter { it !in destinationRequests })
            // Taken before any await: a request that finishes leaves the map.
            val requests = toAsk.associateWith { destinationRequests.getValue(it) }
            val fetched: Map<String, Result<List<StopDisruption>>> = requests.mapValues { (_, request) -> request.await() }
            val prior = _journeyDestinationStops.value.associateBy { it.stopId }
            val failed = HashSet<String>()
            val checked = ArrayList<StopArrivals>()
            _journeyDestinationStops.value = stops.distinctBy { it.id }.mapNotNull { stop ->
                if (cached(stop.id) == null && failedInFetch(stop.id)) {
                    // The fetch logged it already.
                    failed += stop.id
                    return@mapNotNull prior[stop.id]
                }
                val disruptions = cached(stop.id) ?: fetched[stop.id]?.fold(
                    onSuccess = { found -> found.also { disruptionCache[stop.id] = now to it } },
                    onFailure = {
                        // Logged by the request.
                        failed += stop.id
                        return@mapNotNull prior[stop.id]
                    },
                ).orEmpty()
                // Its own stop as the place: a check covers only this stop, so only this stop's
                // dismissal can be reconciled (below) — an area-wide one could stay hidden forever. At
                // worst the same notice on the near-me list is dismissed separately; never hidden.
                StopArrivals(stop.id, stop.name, emptyList(), now, disruptions = disruptions)
                    .also { checked += it }
            }
            // Every failed check is unknown now, even with an earlier result still shown: that result
            // may be out of date, so the card says it couldn't check rather than pass it off as current.
            _journeyDestinationsUnknown.value = failed
            // A dismissed destination closure that has since cleared is forgotten, so the same notice
            // recurring later shows again; a place whose check failed keeps its dismissals.
            reconcileDismissals(stops.distinctBy { it.id }.map { it.copy(clusterId = "", hubId = "") }, checked, emptyMap(), emptySet(), failed)
        }
    }

    private val _state = MutableStateFlow<DeparturesUiState>(DeparturesUiState.Loading)
    val state: StateFlow<DeparturesUiState> = _state.asStateFlow()

    // The "More" buttons to offer: bus while a farther bus cluster is left to page (SPEC *Finding
    // stops → Near me now*). Empty when nothing is left to page.
    private val _moreState = MutableStateFlow(NearbySelection.revealableBuckets(initialMore, emptySet()))
    val moreState: StateFlow<Set<String>> = _moreState.asStateFlow()

    // The near-me stops this model loads ([nearStops]), for the farther-station cards to count as
    // reached. Published from here, the one owner of the tiers, so a reveal or a same-set reconcile
    // that moves a cluster across the eager/more boundary reaches the cards too; the screen's own
    // copy of the tiers goes stale on those.
    private val _shownNearStops = MutableStateFlow(nearStops)
    val shownNearStops: StateFlow<List<StopRef>> = _shownNearStops.asStateFlow()

    private fun publishMore() {
        _moreState.value = NearbySelection.revealableBuckets(more, revealedKeys)
        _shownNearStops.value = nearStops
    }

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

    // Resolved interchange info (hubId → name + member aliases), so a hub with a disruption is
    // looked up once and reused across refreshes and across the stops sharing it (King's Cross and
    // St Pancras both resolve `HUBKGX` from one call). Only a real result is cached here — a failed
    // or blank lookup is retried on a later refresh rather than pinned. Within a single refresh a
    // failure is memoized separately (see `resolveHubInfo`), so a failing hub is not re-requested
    // once per member. In-memory only; hub names are public TfL place names, never persisted.
    private val hubInfoCache = mutableMapOf<String, HubInfo>()

    // Each stop's last SUCCESSFUL stop-level disruption lookup (a closure, a moved stop) and when it
    // was made, reused for [disruptionReuse] rather than re-requested every refresh: a closure
    // changes over hours, and the lookup is half of every stop's request cost against TfL's rate
    // budget. A failure is never cached, so it's retried next refresh. The line status — the fast-
    // moving signal — is still checked every refresh. In-memory only; written and read on the main
    // thread (viewModelScope), like [hubInfoCache].
    private val disruptionCache = mutableMapOf<String, Pair<Instant, List<StopDisruption>>>()

    // When each stop's closure lookup last failed, stamped with its fetch's `now`, and the `now` of
    // the latest [fetchBatch]: a journey destination check that waited on that fetch doesn't ask
    // again for a stop it just failed on (one attempt per refresh, not two, under rate limiting).
    private val disruptionFailedAt = mutableMapOf<String, Instant>()
    private var lastFetchAt: Instant? = null

    // A cold load has been part-shown and no whole batch has landed since. Outlives [cancelFetch]
    // (which tells the screen the load stopped), so the refresh a relocation or pull starts next
    // still shows each stop as it lands rather than holding the rest until the whole batch is in.
    private var coldLoadUnfinished = false

    // Each line's last DETERMINED status (good or disrupted) and when it came back, reused for
    // [lineStatusReuse] so a refresh a minute after the last one needn't re-ask about the same lines.
    // A line TfL gave no status for, or a failed request, is never cached. In-memory, main thread.
    private val lineStatusCache = mutableMapOf<String, Pair<Instant, LineStatus>>()
    // When each line was last asked about and TfL gave no status for it (a no-verdict check for the
    // widget). In-memory, main thread, like the cache.
    private val lineStatusOmitted = mutableMapOf<String, Instant>()
    // Lines TfL answered 404 for ("not recognised": a National Rail service it has no line for).
    // Not asked about again this session — the answer won't change — and never determined, so their
    // rows still read as unchecked rather than clean (SPEC principle 1).
    private val unknownLineIds = HashSet<String>()

    // When each stop's arrivals last came back from a fetch by THIS ViewModel (the cycle's start
    // stamp). What makes a stop eligible to be carried over ([recentlyFetched]): a stop restored from
    // disk is never in it, since the snapshot doesn't persist the closure check a carried-over stop
    // would need. In-memory only; main thread.
    private val arrivalsFetchedAt = mutableMapOf<String, Instant>()

    // The init coroutine that loads the last-good snapshot and then calls refresh(). Tracked so
    // cancelFetch() can stop it too: during its load() the fetchJob isn't assigned yet, so
    // without this a re-locate that cancels mid-init would still let the init-driven refresh()
    // fetch and save the old seed set during the fix window (SPEC D4 / principle 1, Codex).
    private var initLoadJob: Job? = null

    init {
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
        _refreshing.value = false
        // A cold load cut short keeps the stops it showed and names the ones it never got, rather
        // than leave them "Loading" with nothing coming (SPEC principle 2).
        // Its line status was never checked, so that now reads "couldn't check".
        (_state.value as? DeparturesUiState.Loaded)?.takeIf { it.statusPending }?.let { shown ->
            _state.value = shown.copy(
                pendingStops = emptyList(),
                statusPending = false,
                partialRefresh = shown.partialRefresh || shown.pendingStops.isNotEmpty(),
                partialStops = shown.partialStops + shown.pendingStops.associate { it.id to DeparturesUiState.FailedStop(it.name) },
            )
        }
    }

    /**
     * The result of fetching a set of stops: the merged [StopArrivals] and the disruption/freshness
     * flags the caller needs to build state and decide whether to persist. Shared by [refresh] (the
     * whole fetched set) and [fetchIncremental] (only the newly revealed stops), so both fetch, merge
     * and status-check identically and neither drifts from the other.
     */
    private data class FetchBatch(
        val merged: List<StopArrivals>,
        val lineStatuses: Map<String, LineStatus>,
        // The line ids TfL returned a definitive status for in this batch (disrupted OR clean), so a
        // per-line surface can tell "checked, good service" from "never checked" and an incremental
        // merge can REPLACE their prior entries — dropping a now-clean line's stale disrupted flag —
        // rather than only appending the disrupted ones (Codex on #100, PR #104).
        val determinedLineIds: Set<String>,
        // The non-blank line ids this batch actually queried. On an incremental merge it's what lets a
        // line the batch re-checked but TfL then omitted drop out of the merged determined set, rather
        // than keeping a stale "checked" from an earlier fetch.
        val attemptedLineIds: Set<String>,
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
    )

    /**
     * Fetch [stops] (arrivals + disruptions, each merged into its [prior] at age [now]) and check the
     * status of every line they show, returning a [FetchBatch]. Pure of UI state — the caller decides
     * how to turn it into a [DeparturesUiState] and whether to save — so the same fetch serves a
     * whole-set refresh and an incremental reveal.
     */
    // A stop's National Rail feed after its arrivals: as the shared fetch found it, or this client's.
    private fun railFeedOf(stopId: String, departures: List<Departure>?, shared: ArrivalsCache.Entry?): RailFeed? = when {
        departures == null -> null
        shared != null -> shared.railFeed
        else -> client.railFeed(stopId)
    }

    private suspend fun fetchBatch(
        stops: List<StopRef>,
        prior: Map<String, StopArrivals>,
        now: Instant,
        // Stops to carry over from [prior] unchanged, with no request at all — ones fetched moments
        // ago (see [recentlyFetched]). Each keeps its own fetchedAt and arrivalsFresh, so its age and
        // "No departures" claim stay honest (SPEC D4); only the line status is re-checked for it.
        reuse: Set<String> = emptySet(),
        // Called as each stop lands while the rest are still out: the stops so far (arrivals and
        // closure check both back, merged as below but before the line-status check) and the ids
        // still waiting, so a cold load shows each stop as it arrives rather than holding the
        // spinner for the slowest (SPEC *Freshness → Cold load*). Null skips it.
        // [failed] is each stop whose arrivals have failed so far, and how, so the screen names it
        // at once rather than leaving it "Loading".
        onProgress: ((shown: List<StopArrivals>, waiting: Set<String>, failed: Map<String, DeparturesUiState.Error.Kind>) -> Unit)? = null,
        // Whether a stop another screen fetched within [ArrivalsCache.TTL] is taken from
        // [sharedArrivals] rather than asked for; false on a pull-to-refresh, which asks afresh.
        useShared: Boolean = true,
    ): FetchBatch {
        lastFetchAt = now
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
        // Stop ids whose OWN stop-level disruption request failed this batch (a closure/move was
        // never checked) — an axis independent of line status, carried out so a per-stop surface
        // says "couldn't check" for it even when its line was determined (SPEC principle 1).
        val stopsDisruptionUnknown = mutableSetOf<String>()

        // Fan the per-stop requests out in parallel; the client's shared request pool caps how many
        // are in flight, so this is a bounded fan-out, not one connection per stop. Arrivals are
        // launched before disruptions so the departures the user is waiting on tend to go out first
        // when requests queue — a best effort, not a guarantee: IO scheduling and the rate limiter
        // are free to reorder them, and nothing depends on the order (maintainer, PR #121). Each
        // request catches its own failure, so one stop failing never cancels its siblings.
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
        fun reused(stop: StopRef) = stop.id in reuse && prior[stop.id] != null && !newerShared(stop)
        // Which stops' closure results came from [disruptionCache] rather than this batch's request:
        // a cached result is knowledge but not NEWS, so it mustn't count toward [anyFreshData] — a
        // cycle whose every request failed would otherwise pass for a partial refresh and hide the
        // real failure (offline, rate-limited) behind the generic banner (SPEC principle 2).
        val disruptionFromCache = BooleanArray(stops.size)
        // The poles whose closure check went in a shared batch request, and how many batches — for
        // the per-fetch log line.
        val poleBatchIds = HashSet<String>()
        var poleBatchCount = 0
        // The near-me places by distance, for [Terminating]: every eager and "more" stop, revealed or
        // not, since the rider's nearest place may sit in either tier.
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
            if (hubId !in hubInfoCache) hubRequests++
            val info = try {
                resolveHubInfo(hubId)
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
        val (arrivalResults, disruptionResults) = coroutineScope {
            val arrivals = stops.mapIndexed { i, stop ->
                val recent = if (!reused(stop)) sharedFetch[stop.id] else null
                when {
                    reused(stop) -> null
                    recent != null -> {
                        shared[i] = recent
                        CompletableDeferred(Result.success(recent.departures))
                    }
                    else -> async { runCatchingTfl { withContext(io) { client.arrivals(stop.id) } } }
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
                ?.takeIf { (at, _) -> isWithin(at, now, disruptionReuse) }
            // Bus poles still to check share one request per [StopDisruptionBatch.MAX_PER_REQUEST]
            // (a junction is often 4-8 poles, each otherwise its own request against the keyless
            // budget); a failed batch fails each of its poles, as a failed single lookup would.
            val poleBatches = HashMap<String, Deferred<Result<Map<String, List<StopDisruption>>>>>()
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
                        runCatchingTfl { withContext(io) { client.poleDisruptions(ids) } }
                            .onSuccess { found -> ids.forEach { id -> disruptionCache[id] = now to found[id].orEmpty() } }
                            .onFailure { ids.forEach { id -> disruptionFailedAt[id] = now } }
                    }
                    ids.forEach { poleBatches[it] = batch }
                    poleBatchIds += ids
                    poleBatchCount++
                }
            val disruptions: List<Deferred<Result<List<StopDisruption>>>?> = stops.mapIndexed { i, stop ->
                val cached = cachedDisruption(stop)
                val batch = poleBatches[stop.id]
                // A journey destination's check already asking about this stop: wait on it, not ask twice.
                val inFlight = destinationRequests[stop.id]
                when {
                    reused(stop) -> null
                    // Ahead of the cache check: a batch that already finished has written this
                    // pole's fresh result to the cache, which must not read as a cached (not new) one.
                    batch != null -> async { batch.await().map { it[stop.id].orEmpty() } }
                    cached != null -> {
                        disruptionFromCache[i] = true
                        CompletableDeferred(Result.success(cached.second))
                    }
                    inFlight != null -> async { inFlight.await().onFailure { disruptionFailedAt[stop.id] = now } }
                    else -> async {
                        runCatchingTfl { withContext(io) { client.stopDisruptions(stop.id) } }
                            .onSuccess { disruptionCache[stop.id] = now to it }
                            .onFailure { disruptionFailedAt[stop.id] = now }
                    }
                }
            }
            if (onProgress != null) {
                // Each stop as it lands, in stop order. Runs on the caller's (main) dispatcher, so
                // these watchers take turns on [landed], [waiting] and [failed]. A stop whose
                // arrivals failed is named as failed at once, not left "Loading".
                // A stop with a prior (reused, or shown by a cold load this one restarted) shows it
                // until its answer lands, rather than going back to "Loading".
                val landed = arrayOfNulls<StopArrivals>(stops.size)
                val waiting = HashSet<String>()
                val failed = LinkedHashMap<String, DeparturesUiState.Error.Kind>()
                stops.forEachIndexed { i, stop ->
                    landed[i] = prior[stop.id]
                    if (landed[i] == null) waiting += stop.id
                }
                // What's already in hand shows at once, marked still checking — including a batch
                // that reuses every stop and so has no answer to wait for before its line status.
                if (landed.any { it != null }) onProgress(landed.filterNotNull(), waiting.toSet(), failed.toMap())
                stops.forEachIndexed { i, stop ->
                    val arrival = arrivals[i] ?: return@forEachIndexed
                    launch {
                        val departures = arrival.await().getOrElse { e ->
                            waiting -= stop.id
                            failed[stop.id] = kindOf(e)
                            onProgress(landed.filterNotNull(), waiting.toSet(), failed.toMap())
                            null
                        }
                        val stopDisruptions = disruptions[i]?.await()?.getOrNull()
                        // A stop whose arrivals failed still shows a closure as soon as it's known,
                        // as the final pass does (SPEC *Disruptions*); with none, it stays named failed.
                        if (departures == null && stopDisruptions.isNullOrEmpty()) return@launch
                        // A disrupted interchange titles its alert by the hub, as the final pass does
                        // (one lookup per hub per batch — [hubOf] — so that pass doesn't ask again).
                        val hub = if (stop.hubId.isNotBlank() && !stopDisruptions.isNullOrEmpty()) hubOf(stop.hubId) else HubInfo()
                        landed[i] = Snapshot.mergeStop(
                            stopId = stop.id,
                            stopName = stop.name,
                            clusterId = stop.clusterId,
                            lines = stop.lines,
                            freshDepartures = departures,
                            freshDisruptions = stopDisruptions,
                            prior = prior[stop.id],
                            now = shared[i]?.fetchedAt ?: now,
                            hubId = stop.hubId,
                            hubName = hub.name,
                            placeAliases = hub.aliases,
                            stopLetter = stop.stopLetter,
                            bearing = stop.bearing,
                            towards = stop.towards,
                            nearer = Terminating.nearer(stop.id, places),
                            freshRailFeed = railFeedOf(stop.id, departures, shared[i]),
                        )
                        waiting -= stop.id
                        onProgress(landed.filterNotNull(), waiting.toSet(), failed.toMap())
                    }
                }
            }
            arrivals.map { it?.await() } to disruptions.map { it?.await() }
        }

        // Resolve each interchange once, in parallel, only for a hub with a stop that has a fresh
        // disruption to title — the one case a folded alert titles by the interchange and the strip
        // needs its member aliases (SPEC *Disruptions*). Deduplicated up front so a hub shared by
        // several disrupted stops costs one call even when it fails, and every member agrees. A stop
        // with no disruption, or no hub, costs no call and titles by its own name.
        val hubIds = stops.indices
            .filter { i -> stops[i].hubId.isNotBlank() && !disruptionResults[i]?.getOrNull().isNullOrEmpty() }
            .mapTo(LinkedHashSet()) { i -> stops[i].hubId }
        val hubs: Map<String, HubInfo> = coroutineScope {
            hubIds.map { hubId -> async { hubId to hubOf(hubId) } }.awaitAll().toMap()
        }

        // Merge in stop order, so the first error, the logs and the merged list read the same as a
        // one-at-a-time fetch would, whatever order the responses came back in.
        stops.forEachIndexed { i, stop ->
            val arrivalResult = arrivalResults[i]
            val disruptionResult = disruptionResults[i]
            if (arrivalResult == null || disruptionResult == null) {
                // Reused: carried over as it was, neither a fresh result nor a failure — but with the
                // lines it declares now (a journey origin can gain one), so their status is checked.
                // Its nearer places too: the rider may have moved since, and the rows hide by them.
                val nearer = Terminating.nearer(stop.id, places)
                merged += prior.getValue(stop.id).let { p ->
                    if (p.lines == stop.lines && p.nearer == nearer) p else p.copy(lines = stop.lines, nearer = nearer)
                }
                return@forEachIndexed
            }
            val departures = arrivalResult.getOrElse { e ->
                if (firstError == null) firstError = e
                arrivalsErrors[stop.id] = kindOf(e)
                anyArrivalsFailed = true
                warn("arrivals fetch failed for stop ${stop.id}: ${reason(e)}")
                null
            }
            // The places no farther from the rider than this stop, saved with it: the rows hide a
            // service ending at one ([Terminating], [DepartureRows.across]), in the app and widget.
            val nearer = Terminating.nearer(stop.id, places)
            val disruptions = disruptionResult.getOrElse { e ->
                if (firstError == null) firstError = e
                stopsDisruptionUnknown += stop.id
                warn("stop disruption fetch failed for stop ${stop.id}: ${reason(e)}")
                null
            }
            if (departures != null) {
                freshArrivalStopIds += stop.id
                arrivalsFetchedAt[stop.id] = shared[i]?.fetchedAt ?: now
            }
            if (departures != null || (disruptions != null && !disruptionFromCache[i])) anyFreshData = true
            val hub =
                if (stop.hubId.isNotBlank() && !disruptions.isNullOrEmpty()) {
                    hubs[stop.hubId] ?: HubInfo()
                } else {
                    HubInfo()
                }
            Snapshot.mergeStop(
                stopId = stop.id,
                stopName = stop.name,
                clusterId = stop.clusterId,
                lines = stop.lines,
                freshDepartures = departures,
                freshDisruptions = disruptions,
                prior = prior[stop.id],
                now = shared[i]?.fetchedAt ?: now,
                hubId = stop.hubId,
                hubName = hub.name,
                placeAliases = hub.aliases,
                stopLetter = stop.stopLetter,
                bearing = stop.bearing,
                towards = stop.towards,
                nearer = nearer,
                freshRailFeed = railFeedOf(stop.id, departures, shared[i]),
            )?.let { merged += it }
        }

        // Check the status of every line we're about to show, so a disrupted line is
        // marked rather than its countdowns shown as trustworthy (SPEC *Disruptions* /
        // D3). One batched request, off the arrivals path. The set is the stops'
        // declared lines PLUS every predicted line: the declared lines cover a
        // suspended line that returned no predictions (so it can surface as a status
        // row), and the predicted set catches anything a stop didn't declare. A lookup
        // that fails leaves the arrivals shown but flags them "status unknown" rather
        // than passing them off as verified-clean.
        var lineStatuses = emptyMap<String, LineStatus>()
        // The lines TfL returned a status for (good or disrupted), and the non-blank lines this batch
        // queried. Both feed the incremental merge: determined replaces prior verdicts, attempted lets
        // a re-checked-but-now-omitted line drop out of the merged determined set (see [fetchIncremental]).
        var determinedLineIds = emptySet<String>()
        var attemptedLineIds = emptySet<String>()
        var lineStatusRequests = 0
        if (merged.isNotEmpty()) {
            val predictedLineIds = merged.flatMap { it.departures }.map { it.lineId }
            val declaredLineIds = merged.flatMap { it.lines }.map { it.id }
            val lineIds = (predictedLineIds + declaredLineIds)
                .filterTo(mutableSetOf()) { it.isNotBlank() }
            attemptedLineIds = lineIds
            // A departure whose line TfL didn't identify (blank id) can't have its
            // status checked, so its presence alone leaves the disruption state
            // unknown — never shown as verified-clean (SPEC principle 1). This also
            // covers the all-blank case, where no status request is made at all.
            val blankLineIdCount = predictedLineIds.count { it.isBlank() }
            if (blankLineIdCount > 0) {
                // Per-stop attribution (a blank prediction on the stop that showed it) is done
                // below; this logs the batch-wide count so a persistent "couldn't check for
                // disruptions" is diagnosable — a count of unidentifiable predictions, no user data.
                warn("disruption status unknown: $blankLineIdCount prediction(s) had no line id to check")
            }
            // Lines checked within [lineStatusReuse] keep that verdict; only the rest are asked for.
            val cachedStatuses = lineIds.mapNotNull { id ->
                // Not one still waiting on its alerts' directions: asked again, it splits by direction.
                lineStatusCache[id]?.takeIf { (at, status) -> isWithin(at, now, lineStatusReuse) && !status.awaitingDirections }?.second
            }
            // A line TfL left out within the same window isn't asked about again either: it still
            // reads as unchecked, but asking every cycle would spend the rate budget and the radio
            // on an answer that just came back empty.
            val recentlyOmitted = lineIds.filterTo(HashSet()) { id ->
                lineStatusOmitted[id]?.let { at -> isWithin(at, now, lineStatusReuse) } == true
            }
            val toQuery = lineIds - cachedStatuses.mapTo(HashSet()) { it.lineId } - unknownLineIds - recentlyOmitted
            lineStatusRequests = if (toQuery.isNotEmpty()) 1 else 0
            // With nothing left to ask, the cached verdicts stand on their own.
            if (lineIds.isNotEmpty() && toQuery.isEmpty()) {
                lineStatuses = cachedStatuses.filter { it.disrupted }.associateBy { it.lineId }
                determinedLineIds = cachedStatuses.mapTo(mutableSetOf()) { it.lineId }
            }
            if (toQuery.isNotEmpty()) {
                try {
                    val fetched = withContext(io) { client.lineStatuses(toQuery) }
                    // Stamped when TfL answered, not when this batch began: a slow batch neither loses
                    // the store's newest-wins merge to a check made meanwhile nor saves an answer
                    // already near its expiry (SPEC D3/D4).
                    val answeredAt = clock()
                    // The latest answer for a line replaces the other kind outright, so a clock moved
                    // back can't leave a future-dated entry outranking it ([widgetLineChecks]).
                    fetched.forEach {
                        lineStatusCache[it.lineId] = answeredAt to it
                        lineStatusOmitted.remove(it.lineId)
                    }
                    val statuses = cachedStatuses + fetched
                    lineStatuses = statuses.filter { it.disrupted }.associateBy { it.lineId }
                    // A line TfL returned no determinable status for is unknown, not
                    // clean — flag it so those rows aren't shown as verified-clean
                    // (the client drops such lines, so they're absent here).
                    val determined = statuses.mapTo(mutableSetOf()) { it.lineId }
                    determinedLineIds = determined
                    // Asked and left out: remembered, so the widget's copy of an older verdict for it
                    // is replaced by "no verdict" rather than kept ([widgetLineChecks]).
                    toQuery.filterNot { it in determined }.forEach {
                        lineStatusOmitted[it] = answeredAt
                        lineStatusCache.remove(it)
                    }
                    val undetermined = lineIds.filterNot { it in determined }
                    if (undetermined.isNotEmpty()) {
                        // Name the specific lines so a persistent "couldn't check for disruptions" is
                        // diagnosable — a line id is a canned identifier, not user data (SPEC
                        // *Privacy*: line ids are allowed in the log).
                        warn("disruption status unknown: TfL returned no status for line(s) ${undetermined.joinToString(",")}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TflException.NotFound) {
                    // TfL knows none of the lines asked about (it leaves an unknown one out of an
                    // answer that has a known one): remembered, so a refresh doesn't ask again.
                    unknownLineIds += toQuery
                    // Like an omission: an answer with no verdict, which replaces any older one
                    // for the widget rather than letting it keep showing that ([widgetLineChecks]).
                    val answeredAt = clock()
                    toQuery.forEach {
                        lineStatusOmitted[it] = answeredAt
                        lineStatusCache.remove(it)
                    }
                    lineStatuses = cachedStatuses.filter { it.disrupted }.associateBy { it.lineId }
                    determinedLineIds = cachedStatuses.mapTo(mutableSetOf()) { it.lineId }
                    warn("line status: TfL doesn't know line(s) ${toQuery.joinToString(",")}; not asked again")
                } catch (e: Exception) {
                    // Only the cached verdicts are determined, so every line this request was for
                    // reads undetermined — the flag the callers derive is set (SPEC principle 1).
                    lineStatuses = cachedStatuses.filter { it.disrupted }.associateBy { it.lineId }
                    determinedLineIds = cachedStatuses.mapTo(mutableSetOf()) { it.lineId }
                    warn("line status fetch failed for ${toQuery.joinToString(",")}: ${reason(e)}")
                }
            }
        }

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
            attemptedLineIds = attemptedLineIds,
            stopsDisruptionUnknown = stopsDisruptionUnknown,
            anyArrivalsFailed = anyArrivalsFailed,
            anyFreshData = anyFreshData,
            freshArrivalStopIds = freshArrivalStopIds,
            firstError = firstError,
            arrivalsErrors = arrivalsErrors,
        )
    }

    /**
     * Whether [at] is less than [window] before [now]. A negative age — the device clock moved back
     * past [at] — is never within it: it would otherwise read as "just now" until wall time caught
     * up, and keep reusing an old result the whole while.
     */
    private fun isWithin(at: Instant, now: Instant, window: Duration): Boolean {
        val age = Duration.between(at, now)
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
                // Nor may a closure check newer than that fetch be skipped: a later, superseded batch
                // whose arrivals failed can still have cached a new closure for the stop, and carrying
                // the stop over would keep its departures up without it.
                val closureAt = disruptionCache[stop.stopId]?.first
                fetchedAt != null &&
                    fetchedAt == stop.fetchedAt &&
                    (closureAt == null || !closureAt.isAfter(fetchedAt)) &&
                    isWithin(fetchedAt, now, window) &&
                    stop.arrivalsFresh &&
                    stop.stopId !in loaded.stopsDisruptionUnknown
            }
            .mapTo(mutableSetOf()) { it.stopId }

    /**
     * The screen-wide "some shown departures' disruption state is unverified" flag, derived from the
     * merged set and its provenance so [refresh] and an incremental reveal compute it identically and
     * a reveal can't leave it stuck: true when any shown stop's own closure check failed
     * ([stopsDisruptionUnknown]), any shown prediction has no line id to check, or any shown line TfL
     * returned no status for (not in [determinedLineIds]) — never show an unverified line as clean
     * (SPEC principle 1).
     */
    private fun disruptionUnknownOf(
        stops: List<StopArrivals>,
        determinedLineIds: Set<String>,
        stopsDisruptionUnknown: Set<String>,
    ): Boolean =
        stopsDisruptionUnknown.isNotEmpty() ||
            stops.any { s ->
                s.departures.any { it.lineId.isBlank() } ||
                    (s.departures.map { it.lineId } + s.lines.map { it.id })
                        .any { it.isNotBlank() && it !in determinedLineIds }
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
            val batch = fetchBatch(toFetch, prior, now, reuse, useShared = !force, onProgress = if (!coldAtStart) null else { shown, waiting, failed ->
                val current = _state.value
                val coldLoad = current is DeparturesUiState.Loading ||
                    (current is DeparturesUiState.Loaded && (current.statusPending || coldLoadUnfinished)) ||
                    (current is DeparturesUiState.Error && coldLoadUnfinished)
                // The last stop too, so it doesn't wait on the line-status check below. A failure is
                // shown even before any stop lands, while others are still out; once none are (every
                // stop failed) the batch's own verdict follows at once.
                if (coldLoad && shown.isEmpty() && waiting.isEmpty() && failed.isNotEmpty()) {
                    // Every stop's arrivals failed: say so now rather than wait on closure checks
                    // still out. The batch below has the last word (a closure alone still shows).
                    _state.value = DeparturesUiState.Error(toFetch.firstNotNullOf { failed[it.id] })
                    coldLoadUnfinished = true
                    // Said at once, so the grace is over: a closure landing after it shows at once too.
                    inGrace = false
                    heldPartial = null
                } else if (coldLoad && (shown.isNotEmpty() || (failed.isNotEmpty() && waiting.isNotEmpty()))) {
                    val partial = DeparturesUiState.Loaded(
                        stops = shown,
                        fetchedAt = shown.maxOfOrNull { it.fetchedAt } ?: now,
                        // Line status is checked once every stop is in; until then it's unchecked.
                        disruptionUnknown = true,
                        pendingStops = toFetch.filter { it.id in waiting },
                        statusPending = true,
                        // A stop that already failed is named now, with its reason (SPEC principle 2).
                        partialRefresh = failed.isNotEmpty(),
                        partialStops = toFetch.filter { it.id in failed }
                            .associate { it.id to DeparturesUiState.FailedStop(it.name, failed.getValue(it.id)) },
                        unavailableStopIds = failed.keys - shown.mapTo(HashSet()) { it.stopId },
                    )
                    if (inGrace) {
                        heldPartial = partial
                    } else {
                        _state.value = partial
                        coldLoadUnfinished = true
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
            // Screen-wide "status unknown" derives from the merged set and this batch's provenance,
            // so refresh() and an incremental reveal compute it the same way ([disruptionUnknownOf]).
            val disruptionUnknown = disruptionUnknownOf(merged, determinedLineIds, stopsDisruptionUnknown)
            val partial = if (anyFreshData) anyArrivalsFailed else priorPartial

            val newState = when {
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
                            incompleteStops(merged, batch.arrivalsErrors, priorLoaded?.partialStops.orEmpty())
                        } else {
                            emptyMap()
                        },
                        partialUnnamed = partial && unnamedIncomplete(merged),
                        // Nothing fresh came back at all (every request failed) but a prior
                        // snapshot was kept — carry the failure so the screen says "couldn't
                        // refresh" rather than passing the aged rows off as fresh (SPEC D4 /
                        // principle 2). Cleared by the next refresh that gets anything.
                        refreshFailure = if (!anyFreshData && firstError != null) kindOf(firstError) else null,
                        lineStatuses = lineStatuses,
                        disruptionUnknown = disruptionUnknown,
                        determinedLineIds = determinedLineIds,
                        stopsDisruptionUnknown = stopsDisruptionUnknown,
                        unavailableStopIds = toFetch.mapTo(HashSet()) { it.id } - merged.mapTo(HashSet()) { it.stopId },
                    )
                // Nothing came back and nothing failed → there were no stops to fetch
                // (no watched stops yet, or the seed is empty). That's an empty list, not
                // a network error — TfL was never contacted.
                firstError == null -> DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now)
                // Every stop failed on a first load with no prior snapshot to fall back on
                // → an honest error, not an empty or stale list (SPEC principles 1–2).
                else -> DeparturesUiState.Error(kindOf(firstError))
            }
            _state.value = newState
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
                    // persisted during the fix window (SPEC D4 / principle 1). A superseding "More" tap
                    // also cancels here, but that page isn't stranded: fetchIncremental persists the
                    // merged set whenever it carries fresh arrivals — including these just-published
                    // ones carried onto the reveal — so the fix doesn't need a NonCancellable save that
                    // would defeat the relocation guard (Codex, PR #104).
                    // Keeping the stored journey pins, and any stop the widget's live refresh stored
                    // newer (a pinned origin this fetch didn't cover, say).
                    withContext(io) {
                        snapshotStore.saveKeepingJourneys(withStoredDismissals(toSave, dismissedStore, _dismissed.value, warn))
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
                redrawWidgetBestEffort("failed refresh")
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
            reconcileDismissals(fetchedStops, merged, lineStatuses, determinedLineIds, stopsDisruptionUnknown)
        }
        fetchJob = job
        // Clear the in-flight flag only when this job settles — a job superseded by a
        // newer refresh doesn't clear the newer one's indicator.
        job.invokeOnCompletion { if (fetchJob === job) _refreshing.value = false }
        // The journeys' far ends too, after this fetch (which caches any it covers).
        checkJourneyDestinations()
    }

    /**
     * Reveal the next page of [bucket]'s farther clusters (SPEC *Finding stops → Near me now*):
     * add them to the fetched set and fetch **only** the newly revealed stops, merging them in
     * beside the ones already shown, rather than re-fetching the whole set. A no-op when the bucket
     * has nothing left to page; the caller ignores a tap while a relocation's fresh fix is in flight,
     * so a "More" never pages the pre-fix set. Reveal only *adds* stops, so no prune is needed — an
     * already-present stop keeps its rows untouched.
     */
    fun reveal(bucket: String) {
        // The lines already on screen (eager plus revealed), so nextReveal can page THROUGH a run of
        // clusters that only repeat them — the near-me list collapses a route to its nearest stop, so
        // revealing such a cluster shows nothing — to the first farther cluster with a new route.
        val next = NearbySelection.nextReveal(more, bucket, revealedKeys, shownLineIds())
        if (next.isEmpty()) return
        revealedKeys = revealedKeys + next
        publishMore()
        // Fetch only the stops that aren't already shown, merging into the current snapshot, so the
        // Nth "More" tap costs one page of requests, not the whole shown set (TfL request budget).
        // Computed as fetchedStops minus what's on screen — so it also picks up any earlier revealed
        // stop a superseded tap didn't finish fetching, keeping this self-correcting on quick taps.
        val current = _state.value as? DeparturesUiState.Loaded
        if (current == null || current.statusPending || coldLoadUnfinished) {
            // No whole snapshot to merge into yet (still Loading, an Error, or a cold load part-shown)
            // — fall back to a full fetch, which builds the first whole Loaded from the widened set
            // and, mid cold load, keeps showing each stop as it lands.
            refresh()
            return
        }
        val shown = current.stops.mapTo(mutableSetOf()) { it.stopId }
        val newStops = fetchedStops.filterNot { it.id in shown }
        if (newStops.isEmpty()) return
        fetchIncremental(current, newStops)
    }

    /**
     * Fetch [newStops] and merge them into [current], without re-fetching the stops already shown
     * (SPEC *Finding stops → Near me now* — a "More" tap pages one bounded burst, not the whole
     * set). A newly revealed stop whose fetch fails is simply absent (the reveal is flagged partial),
     * never an [DeparturesUiState.Error] — the existing snapshot still stands (SPEC principle 2).
     * The widened set is persisted only when the fetch brought fresh arrivals, so the widget polls
     * the new stops too (matching [refresh]'s save rule); a reveal with nothing durable leaves the
     * saved snapshot as-is.
     */
    private fun fetchIncremental(current: DeparturesUiState.Loaded, newStops: List<StopRef>) {
        fetchJob?.cancel()
        val nearIds = nearStops.mapTo(HashSet()) { it.id }
        val journeyIds = journeyStops.mapTo(HashSet()) { it.id }
        _refreshing.value = true
        val job = viewModelScope.launch {
            val now = clock()
            val prior = current.stops.associateBy { it.stopId }
            val batch = fetchBatch(newStops, prior, now)
            // Merge the newly fetched stops beside the ones already shown, keeping the shown order
            // then appending the new ones; the screen re-sorts by distance, so order here is only for
            // a stable snapshot.
            val byId = LinkedHashMap<String, StopArrivals>()
            for (s in current.stops) byId[s.stopId] = s
            for (s in batch.merged) byId[s.stopId] = s
            val mergedStops = byId.values.toList()
            val mergedIds = byId.keys
            // Merge the disruption provenance across the shown set and the new batch, so the per-line
            // and per-stop route-detail signals — and the screen-wide banner derived from them — stay
            // coherent over a partial (incremental) fetch instead of dropping the shown stops' verdicts.
            // determinedLineIds: keep the shown lines' determinations except ones this batch re-queried
            // (which its result replaces — so a line TfL now omits drops out), then add the batch's.
            val determinedLineIds =
                (current.determinedLineIds - batch.attemptedLineIds) + batch.determinedLineIds
            // stopsDisruptionUnknown: the shown stops still on screen plus the batch's failed stops.
            val stopsDisruptionUnknown =
                (current.stopsDisruptionUnknown + batch.stopsDisruptionUnknown)
                    .filterTo(mutableSetOf()) { it in mergedIds }
            val partial = isIncomplete(mergedStops)
            val newState = current.copy(
                stops = mergedStops,
                fetchedAt = mergedStops.maxOfOrNull { it.fetchedAt } ?: current.fetchedAt,
                // Recompute incompleteness over the merged set rather than OR-ing the prior flag, so a
                // later reveal that retries and recovers an earlier missing stop CLEARS the "some stops
                // couldn't refresh" banner instead of leaving it stuck until a full refresh. A stop
                // still missing (its fetch failed) or carried stale keeps it set (see [isIncomplete]).
                partialRefresh = partial,
                // This batch's failures, and each earlier one a stop the batch didn't fetch still has.
                partialStops = if (partial) {
                    incompleteStops(mergedStops, batch.arrivalsErrors, current.partialStops)
                } else {
                    emptyMap()
                },
                partialUnnamed = partial && unnamedIncomplete(mergedStops),
                // Merge the new batch's line statuses, REPLACING the prior verdict for every line the
                // batch re-queried (attemptedLineIds), not only the ones it definitively determined:
                // a line the batch re-checked but TfL now omits (or whose lookup failed) must drop its
                // stale disrupted entry — it's undetermined now, surfaced via disruptionUnknown — rather
                // than keep flagging a status this fetch couldn't stand behind (Codex, PR #104). Lines
                // the batch didn't touch keep theirs.
                lineStatuses = current.lineStatuses.filterKeys { it !in batch.attemptedLineIds } +
                    batch.lineStatuses,
                // Recompute the screen-wide flag over the merged set and merged provenance (like
                // partialRefresh) rather than OR-ing the prior flag, so a reveal that re-established a
                // previously unknown line/stop CLEARS the "status unknown" banner ([disruptionUnknownOf]).
                disruptionUnknown = disruptionUnknownOf(mergedStops, determinedLineIds, stopsDisruptionUnknown),
                determinedLineIds = determinedLineIds,
                stopsDisruptionUnknown = stopsDisruptionUnknown,
                // Clear a prior total-failure "couldn't refresh" banner once this reveal reaches TfL
                // and gets anything fresh — matching Loaded's contract that the flag clears on the
                // next fetch that gets anything; a reveal that got nothing keeps the prior state.
                refreshFailure = if (batch.anyFreshData) null else current.refreshFailure,
                unavailableStopIds = (current.unavailableStopIds + newStops.map { it.id }) - mergedIds,
            )
            _state.value = newState
            // Persist when the MERGED set carries fresh arrivals — not only when THIS batch did —
            // matching refresh()'s authoritative rule (it saves a partial that has any fresh stop).
            // Keying on the merged set is what lets a superseding "More" tap that canceled a full
            // refresh's still-in-flight save carry that refresh's just-published fresh stops to disk
            // here, so the save stays CANCELLABLE (the relocation guard keeps working) yet the page
            // is never stranded (Codex, PR #104). A reveal whose merged set is all aged (nothing
            // fresh anywhere) saves nothing and just redraws, as before — the merged stops keep their
            // own arrivalsFresh, so this never rewrites a complete snapshot to stale.
            // Judged on the stops the widget keeps, as in refresh().
            val widgetSnapshot = forWidget(DeparturesSnapshot(newState.stops, newState.fetchedAt), nearIds, journeyIds)
            if (widgetJudged(widgetSnapshot.stops, nearIds).any { it.arrivalsFresh }) {
                try {
                    withContext(io) {
                        snapshotStore.saveKeepingJourneys(
                            withStoredDismissals(widgetSnapshot, dismissedStore, _dismissed.value, warn),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot save failed: ${reason(e)}")
                }
            } else {
                // Nothing fresh anywhere in the merged set — like refresh()'s no-save path, poke a
                // best-effort widget redraw so its static RemoteViews recompute staleness from the
                // current clock rather than ageing invisibly past the cutoff (SPEC D4 / principle 2).
                redrawWidgetBestEffort("incremental reveal with no fresh arrivals")
            }
            if (widgetJourneysPending) writeWidgetJourneys()

            // Reconcile dismissals for the stops THIS reveal queried too (not just full refreshes):
            // a "More" tap can surface a previously dismissed place whose notice has since resolved,
            // and its stale signature must be pruned like on a full refresh. Provenance is [newStops]
            // (the queried set); the reconcile is scoped per place to the ones whose disruption lookup
            // succeeded, so it never touches the already-shown stops or a newly-fetched failure.
            reconcileDismissals(newStops, mergedStops, newState.lineStatuses, batch.determinedLineIds, stopsDisruptionUnknown)
        }
        fetchJob = job
        job.invokeOnCompletion { if (fetchJob === job) _refreshing.value = false }
        // A revealed stop may be a journey's far end: its closure, just cached, reaches the card too.
        checkJourneyDestinations()
    }

    /**
     * The ids of the routes actually **on screen right now**. A "More" tap uses this so it can tell a
     * farther cluster that adds a new route from one that only repeats a route already shown (which
     * the near-me dedupe would collapse to nothing).
     *
     * Derived from the rendered rows, not the eager stops' *declared* lines: a stop can declare a
     * line it has no current departure (or disruption) for, so that line isn't on screen — counting
     * it would mark a farther stop that does show it as redundant and never reveal it, the dead tap
     * this fixes (Codex, PR #98). Using the renderer's own output ([DepartureRows.across]) rather
     * than re-deriving "what's shown" keeps this from drifting from the UI as its filters evolve.
     */
    private fun shownLineIds(): Set<String> {
        val loaded = _state.value as? DeparturesUiState.Loaded ?: return emptySet()
        // Derive the shown routes from the SAME rows the screen renders — `DepartureRows.across`
        // against the live clock — rather than reconstructing "what's on screen" from the raw
        // snapshot. That way every filter the renderer applies (departed predictions dropped, a
        // disrupted line's status row suppressed on a stale or carried-forward stop, etc.) is
        // inherited for free, instead of this method drifting from the UI one edge case at a time.
        // Stop-status rows carry a blank lineId and drop out; a timed or line-status row's lineId is
        // a genuinely-shown route.
        return DepartureRows.across(loaded.stops, clock(), loaded.lineStatuses)
            .mapNotNullTo(mutableSetOf()) { it.lineId.takeIf(String::isNotBlank) }
    }

    /**
     * Reconcile the tiers to a fresh fix of the SAME nearby set (both tiers, order-independent — see
     * [NearbyStopsViewModel.State.Ready.clusterSetKey]), keeping a revealed expansion across the
     * relocation. Updates the tiers, drops a revealed cluster the fresh fix no longer offers,
     * **synchronously prunes** a departed revealed stop from the shown state (before the re-fetch,
     * so it can't linger with stale departures through the fetch window — SPEC D4 / principle 1),
     * then re-fetches. A revealed cluster promoted into the eager tier stays fetched (it's eager
     * now) *and* keeps its reveal identity, so a later relocation that demotes it back into *more*
     * keeps it expanded; a member that crossed the radius is pruned here and its replacement
     * fetched by [refresh].
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
    ) {
        val before = fetchedStops.mapTo(mutableSetOf()) { it.id }
        if (dropJourneyStopIds.isNotEmpty()) journeyStops = journeyStops.filter { it.id !in dropJourneyStopIds }
        eagerStops = newEager.flatMap { cluster -> cluster.stops.map { it.toStopRef() } }
        more = newMore
        // Keep a revealed cluster's identity while it is present in EITHER tier. A cluster promoted
        // into the eager tier is still fetched (via eager) AND stays revealed, so a later relocation
        // that demotes it back into *more* while the whole set is unchanged keeps it expanded rather
        // than reverting it to a "More" button (SPEC — a revealed expansion survives an eager/more
        // boundary shift). Intersecting with `newMore` alone would drop it on the promotion and lose
        // that on the demotion. A key kept here that is currently eager doesn't affect paging (it
        // isn't in `more`, so `revealableBuckets`/`nextReveal` never see it).
        val presentKeys = (newEager + newMore).mapTo(mutableSetOf()) { it.key }
        revealedKeys = revealedKeys intersect presentKeys
        publishMore()
        remeasure(newDistanceMeters ?: stopDistanceMeters)
        val departed = before - fetchedStops.mapTo(mutableSetOf()) { it.id }
        if (departed.isNotEmpty()) {
            (_state.value as? DeparturesUiState.Loaded)?.let { loaded ->
                val kept = loaded.stops.filterNot { it.stopId in departed }
                _state.value = if (kept.isEmpty()) {
                    // Every shown stop departed; don't leave a trusted, recent-stamped empty
                    // "No departures" up through the replacement fetch (which hasn't been checked)
                    // — show the loading placeholder until it returns (SPEC principle 2; Codex).
                    DeparturesUiState.Loading
                } else {
                    // The shown set just lost stops and a re-fetch is pending, so it is genuinely
                    // incomplete — flag it partial rather than pass the reduced list off as a
                    // complete, uniformly-fresh whole (SPEC principle 2).
                    loaded.copy(
                        stops = kept,
                        fetchedAt = kept.maxOfOrNull { it.fetchedAt } ?: loaded.fetchedAt,
                        partialRefresh = true,
                        // A still-fetched stop that had failed still has, whether or not it has a row
                        // yet; the stops taking the departed ones' place are pending, not failed, so
                        // they aren't named. Re-sorted by the new fix's distances ([remeasure]).
                        partialStops = fetchedStops.mapTo(HashSet()) { it.id }.let { still ->
                            byDistance(loaded.partialStops.filterKeys { it in still }, stopDistanceMeters)
                        },
                        partialUnnamed = true,
                    )
                }
            }
            // Remove the departed stops from the widget snapshot NOW — at prune time, on a scope
            // that outlives both the re-fetch below and this per-set ViewModel (a different-set
            // relocation discards it via NearbyDeparturesStores.ownerFor). Coupling the removal to
            // the re-fetch's save left a departed stop on disk whenever that save was skipped
            // (a non-authoritative or Error cycle), canceled, or lost with the ViewModel — three
            // findings on one mechanism (#87). A direct, save-independent removal closes the class
            // (SPEC D4 / principle 1).
            pruneDepartedFromWidget(departed)
        }
        if (awaitJourneyStops) refreshAwaitsJourneyStops = true else refresh()
    }

    /**
     * Take each stop's distance from a new fix ([newDistanceMeters]), without refetching: the stops
     * past the walking reach refresh less often, and the shown stops take their new nearer places
     * now, before any refetch returns, so the rows hide by where the rider is rather than where they
     * were ([Terminating]); the widget's stored copy too, whether or not that refetch succeeds.
     * [reconcile] runs it; so does an opened farther card's model when the rider moves.
     */
    fun remeasure(newDistanceMeters: Map<String, Double>) {
        stopDistanceMeters = newDistanceMeters
        (_state.value as? DeparturesUiState.Loaded)?.let { loaded ->
            val places = nearbyPlaces()
            val moved = loaded.stops.map { stop ->
                val nearer = Terminating.nearer(stop.stopId, places)
                if (nearer == stop.nearer) stop else stop.copy(nearer = nearer)
            }
            if (moved != loaded.stops) {
                _state.value = loaded.copy(stops = moved)
                updateWidgetNearer(moved.associate { it.stopId to it.nearer })
            }
        }
    }

    /**
     * The screen has reported its journey stops ([setJourneyStops] ran first, refreshing if they
     * changed): run a refresh a reconcile left waiting on this report, if that didn't already.
     */
    fun journeyStopsReported() {
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
        viewModelScope.launch { dismissAlert(dismissedStore, row, io, _dismissWriteFailed, warn, widgetDismissals) }
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
    ) {
        // Includes each near-me folded card's identity, so its dismissal isn't pruned as not-live.
        val live = DepartureRows.liveStopClosureAlerts(DepartureRows.across(shownStops, clock(), lineStatuses)) +
            DepartureRows.liveLineStatusAlerts(lineStatuses)
        fun placeOf(stop: StopRef) = stopPlaceKey(stop.hubId, stop.clusterId, stop.name, stop.id)
        // A place with any member whose disruption lookup failed this cycle is not fully known, so it
        // is excluded from the checked set and its dismissals are retained.
        val unknownPlaces = queriedStops.asSequence()
            .filter { it.id in stopsDisruptionUnknown }
            .mapTo(mutableSetOf()) { placeOf(it) }
        val checkedPlaces = queriedStops.asSequence()
            .map { placeOf(it) }
            .filterTo(mutableSetOf()) { it !in unknownPlaces } +
            checkedLineIds.map { lineAlertKey(it) }
        // Reconcile the in-memory set first — safe regardless of whether the persist below succeeds.
        val pruned = Dismissed.reconcile(_dismissed.value, live, checkedPlaces)
        if (pruned != _dismissed.value) _dismissed.value = pruned
        try {
            withContext(io) { dismissedStore.reconcile(live, checkedPlaces) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("dismissal reconcile failed: ${reason(e)}")
        }
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
    private fun isIncomplete(stops: List<StopArrivals>): Boolean =
        fetchedStops.any { seed -> stops.none { it.stopId == seed.id } } ||
            stops.any { !it.arrivalsFresh }

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
    ): Map<String, DeparturesUiState.FailedStop> {
        fun failed(id: String, name: String) = id to DeparturesUiState.FailedStop(name, failedNow[id] ?: prior[id]?.reason)
        val byId = stops.associateBy { it.stopId }
        val seeded = fetchedStops.mapNotNull { seed ->
            val shown = byId[seed.id]
            when {
                shown == null -> failed(seed.id, seed.name)
                !shown.arrivalsFresh -> failed(seed.id, shown.stopName.ifBlank { seed.name })
                else -> null
            }
        }
        val seedIds = fetchedStops.mapTo(HashSet()) { it.id }
        val unseeded = stops.filter { it.stopId !in seedIds && !it.arrivalsFresh }.map { failed(it.stopId, it.stopName) }
        // [fetchedStops] puts every eager stop before a revealed farther one, which can be nearer
        // than a sparse mode's eager stop, so order by distance.
        return byDistance((seeded + unseeded).toMap(), stopDistanceMeters)
    }

    /** Whether some stop that makes [stops] incomplete has no name to show ([incompleteStops] drops it). */
    private fun unnamedIncomplete(stops: List<StopArrivals>): Boolean {
        val byId = stops.associateBy { it.stopId }
        val seedIds = fetchedStops.mapTo(HashSet()) { it.id }
        return fetchedStops.any { seed ->
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
     * The durable [hubInfoCache] holds successes across refreshes; a failure is never cached, so the
     * next refresh retries rather than the title being permanently blanked. Within one refresh the
     * caller deduplicates hub ids before calling, so a hub shared by several disrupted stops costs
     * at most one call even when it fails, and every member agrees on the result.
     *
     * Best-effort: a failed lookup returns empty and the alert falls back to the stop's own name.
     * Rethrows [CancellationException] first (structured concurrency). The log carries only the hub
     * id — a public TfL place identifier, like a stop id (SPEC *Privacy*).
     */
    private suspend fun resolveHubInfo(hubId: String): HubInfo {
        hubInfoCache[hubId]?.let { return it }
        val info = try {
            withContext(io) { client.hubInfo(hubId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("hub lookup failed for $hubId: ${reason(e)}")
            HubInfo()
        }
        // A success is durable; an empty isn't cached, so the next refresh retries. Written on the
        // main thread (viewModelScope), so parallel lookups don't race on the map.
        if (info.name.isNotBlank()) hubInfoCache[hubId] = info
        return info
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

/** The user-facing error a failed TfL call maps to: offline, rate-limited, a network error, or else a server one. */
internal fun errorKindOf(e: Throwable?): DeparturesUiState.Error.Kind = when (e) {
    is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
    is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
    is TflException.Network -> DeparturesUiState.Error.Kind.NETWORK
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
private fun byDistance(
    stops: Map<String, DeparturesUiState.FailedStop>,
    distanceMeters: Map<String, Double>,
): Map<String, DeparturesUiState.FailedStop> =
    stops.entries
        .filter { it.value.name.isNotBlank() }
        .sortedBy { distanceMeters[it.key] ?: Double.MAX_VALUE }
        .associateTo(LinkedHashMap()) { it.key to it.value }
