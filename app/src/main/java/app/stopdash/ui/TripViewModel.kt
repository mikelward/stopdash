package app.stopdash.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.LineStatus
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripRoute
import app.stopdash.domain.withoutDetours
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A trip with a change (SPEC *Trips with a change*): TfL's Journey Planner's routes from [fromId] to
 * [destinations], and the live arrivals and line statuses that time them. The plan is held in memory for
 * [PLAN_REUSE] and planned again after that; live times refresh on each [refresh], which the screen
 * calls on the list's own foreground tick, so nothing runs while the trip isn't on screen.
 *
 * A failure keeps what was last known: a failed plan shows its reason over the last plan, a failed
 * stop keeps its last arrivals (aged, so the screen withholds them once stale, SPEC D4).
 */
class TripViewModel(
    private val planner: JourneyPlanner,
    private val client: TflClient,
    val fromId: String,
    // Where the trip is planned to (SPEC *Trips with a change* / D9): usually a stop, or — for a
    // complex — one per station and one of its bus stops, each asked in parallel and the answers
    // merged; or a single place at a coordinate (a favorite, a resolved postcode), which TfL routes
    // to with a final walk leg. A place has no stop to fetch arrivals at, so only ridden stops are
    // (SPEC D9).
    val destinations: List<TripDestination>,
    private val clock: () -> Instant = Instant::now,
    // Coarse facts only — a stop id, an error kind, never a coordinate (SPEC *Privacy*).
    private val warn: (String) -> Unit = {},
    // Plans kept for the process, so a trip reopened within [PLAN_REUSE] shows its plan at once
    // rather than ask the Planner again. Memory only: never saved, gone with the process.
    private val plans: TripPlans = TripPlans.SHARED,
    // Requests and their decoding run off the main thread, as the other screens' do.
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // The stops' last arrivals, shared with the other screens (the app passes [ArrivalsCache.SHARED]):
    // a boarding stop fetched within [ArrivalsCache.TTL] shows at once and isn't asked for again.
    private val arrivals: ArrivalsCache = ArrivalsCache(),
    // Emits when where departures come from changes (a National Rail key added or removed): the
    // current value first, then each change, as the list's model takes it.
    departureSourceChanges: Flow<Any?> = emptyFlow(),
    // A bus stop pair's poles ("490G…", a road's two sides): a bus leg's boarding stop is fetched on
    // every pole of its pair, since the one the Planner names can be the side the bus doesn't use.
    // None by default (a test); the app looks them up once a day.
    private val poles: suspend (String) -> List<String> = { emptyList() },
    // Keeps the open route ([openRoute]) across process death, from the activity's saved state: the
    // screen's own saved state is gone while something else (the licenses) covers it. On the device.
    private val savedState: SavedStateHandle = SavedStateHandle(),
    // The service alerts the user dismissed, shared with every screen (SPEC *Disruptions*): a leg's
    // line page offers the same dismiss the list's does. None kept by default (a test).
    private val dismissedStore: DismissedAlertsStore = DismissedAlertsStore.NONE,
    // The activity's failed-write flags, so a dismiss that didn't take is said on whichever screen
    // shows next, as the list's are.
    writeFailures: WriteFailures = WriteFailures(),
    // Every stop of the destination, for dropping routes that pass it ([withoutDetours]):
    // [destinations] keep one of a complex's bus stops for the Planner, but a route that passes any of
    // them has reached it. Each id maps to its stop, so a bus stop's poles and its "490G…" area
    // are one stop when one route passes it and another gets off there.
    destinationIds: Map<String, String> = stopIds(destinations).associateWith { it },
) : ViewModel() {
    /** One boarding stop's last arrivals and when they were fetched; [failed] when the last fetch failed. */
    data class StopLive(val departures: List<Departure>, val fetchedAt: Instant, val failed: Boolean = false)

    data class State(
        val routes: List<TripRoute>? = null,
        val plannedAt: Instant? = null,
        val planning: Boolean = false,
        val planError: DeparturesUiState.Error.Kind? = null,
        val live: Map<String, StopLive> = emptyMap(),
        val statuses: Map<String, LineStatus> = emptyMap(),
        // The last line-status check failed: the statuses shown (if any) are older, so the trip
        // says it couldn't check for disruptions rather than pass its lines off as running normally.
        val statusFailed: Boolean = false,
        // The routes' lines with no status known: TfL left them out of its answer, or the check
        // failed before any was known. They can't be vouched for as running (ranked unchecked).
        val statusUnknown: Set<String> = emptySet(),
        val refreshing: Boolean = false,
        // The last plan reached only some of the trip's stops (a complex's other stations failed):
        // its routes stand, and the trip says it couldn't plan to every station, with a retry.
        val planIncomplete: Boolean = false,
        // Each bus stop pair's poles the trip has looked up (and so fetches): a leg may board at one
        // before its arrivals are in, reading "Loading" meanwhile. A pair whose lookup failed is absent.
        val areaPoles: Map<String, List<String>> = emptyMap(),
        // Every id of the destination, each to its stop, for leaving out the routes that pass it
        // ([shownRoutes]); empty in a state built without them.
        val destinationStops: Map<String, String> = emptyMap(),
    ) {
        /**
         * [routes] as shown: without those riding a [hidden] mode, then without the detours
         * ([withoutDetours]) the rest beat. In that order, so a route the rider hid never takes out
         * one they can see; the plan itself keeps every route, so showing a mode again brings back
         * what it beat.
         */
        fun shownRoutes(hidden: Set<String>): List<TripRoute>? = routes
            ?.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, it.lineId, hidden) } }
            ?.let { withoutDetours(it, destinationStops.keys, destinationStops) }
    }

    private val _state = MutableStateFlow(
        (plans.get(fromId, destinations)?.let { (routes, at) -> State(routes = routes, plannedAt = at, statusUnknown = linesOf(routes)) } ?: State())
            .copy(destinationStops = stopIds(destinations).associateWith { it } + destinationIds),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Modes the rider hid: routes riding them are neither shown nor fetched for. Set by the screen.
     * A change that times routes not timed before (a mode shown again, one hidden that beat a
     * detour ([State.shownRoutes]), or one hidden that lets a later route into the soonest few)
     * fetches their live times at once rather than on the next tick.
     */
    var hiddenModes: Set<String> = emptySet()
        set(value) {
            if (value == field) return
            // The routes timed and fetched for: those shown, within the cap ([bestOf]), since a
            // hidden mode can also move a route already shown into the soonest few.
            val before = bestOf(_state.value.shownRoutes(field).orEmpty())
            field = value
            if (_state.value.routes != null && bestOf(_state.value.shownRoutes(value).orEmpty()).any { route -> before.none { it === route } }) refresh()
        }

    /**
     * The route open on screen ([routeKey]), held here rather than by the screen, so it stays open
     * across anything that takes the screen out of composition while the trip is kept: the licenses
     * About opens, say, even if the process is recreated meanwhile.
     */
    val openRoute: MutableState<String?> = object : MutableState<String?> {
        private val held = mutableStateOf(savedState.get<String>(KEY_OPEN_ROUTE))
        override var value: String?
            get() = held.value
            set(key) {
                held.value = key
                savedState[KEY_OPEN_ROUTE] = key
            }
        override fun component1() = value
        override fun component2(): (String?) -> Unit = { value = it }
    }

    // The saved handle can outlive this trip (it's the activity's, by the model's key): a trip
    // planned afresh must not open this one's route.
    override fun onCleared() {
        savedState.remove<String>(KEY_OPEN_ROUTE)
    }

    // A refresh asked for while one runs: run once more when it ends, so a re-pick (a new fix, a
    // mode shown again) is never dropped until the next tick.
    private var again = false

    /**
     * Plans if there is no plan under [PLAN_REUSE] old, then refreshes the live times. One at a
     * time; a call during a refresh queues one more after it.
     */
    fun refresh() = start(replan = false)

    // Whether the screen has asked for its first refresh, and the last re-pick it refreshed for.
    private var started = false
    private var seenRepick: Long? = null

    /**
     * The screen's refresh on showing, and on each re-pick of the nearby set ([repickId]): once per
     * model and per new re-pick, so a configuration change that recreates the screen over this
     * retained model doesn't fetch everything again.
     */
    fun refreshFor(repickId: Long?) {
        if (started && repickId == seenRepick) {
            // Shown again (a rotation, a return): take any newer arrivals another screen fetched
            // meanwhile, and refresh only if a boarding stop's are still over [ArrivalsCache.TTL]
            // old, rather than fetch everything again or wait for the minute tick.
            // The routes a refresh fetches for ([refreshLive]): a route not shown, or past the cap,
            // is never fetched, so its stops would read as stale on every return.
            val routes = _state.value.shownRoutes(hiddenModes)?.let(::bestOf) ?: return
            _state.update { it.copy(live = cached(routes, it.live)) }
            val now = clock()
            val live = _state.value.live
            if (stopsOf(routes).any { id -> live[id]?.let { !recentEnough(it, now) } ?: true }) refresh()
            return
        }
        started = true
        seenRepick = repickId
        refresh()
    }

    private val _dismissed = MutableStateFlow<Set<DismissedAlert>>(emptySet())

    /** The dismissed service alerts, followed from the shared store; empty until read. */
    val dismissed: StateFlow<Set<DismissedAlert>> = _dismissed.asStateFlow()

    private val _dismissWriteFailed = writeFailures.dismiss

    /** Whether a dismiss didn't persist, until the screen says so ([dismissWriteFailureShown]). */
    val dismissWriteFailed: StateFlow<Boolean> = _dismissWriteFailed.asStateFlow()

    /** Dismisses [row]'s line alert, as the list's line page does. */
    fun dismissAlert(row: DepartureRow) {
        viewModelScope.launch { dismissAlert(dismissedStore, row, io, _dismissWriteFailed, warn) }
    }

    fun dismissWriteFailureShown() {
        _dismissWriteFailed.value = false
    }

    // Bumped on each departure source change: a refresh's arrivals asked for before it are dropped.
    private var sourceGeneration = 0

    init {
        viewModelScope.launch { followDismissed(dismissedStore, _dismissed, warn) }
        // Arrivals fetched under the old source no longer stand: they're dropped (the trip reads
        // "Loading" rather than show them), and the next time the screen shows this retained trip it
        // fetches afresh rather than wait for the minute tick. Nothing is fetched here, since the
        // trip may not be on screen.
        viewModelScope.launch {
            departureSourceChanges.drop(1).collect {
                // Nor may the shared arrivals, or a fetch still out, put them back (the list clears
                // the shared ones too; clearing twice only costs a fetch).
                sourceGeneration++
                arrivals.clear()
                _state.update { it.copy(live = emptyMap()) }
                started = false
            }
        }
    }

    /** Plans again now, after a failure. During a refresh, plans again once it ends. */
    fun retry() = start(replan = true)

    // A Retry tapped while a refresh runs: plan again when it ends rather than drop the tap.
    private var replanAgain = false

    private fun start(replan: Boolean) {
        if (job?.isActive == true) {
            again = true
            if (replan) replanAgain = true
            return
        }
        job = viewModelScope.launch {
            run(replan)
            while (again) {
                again = false
                val next = replanAgain
                replanAgain = false
                run(next)
            }
        }
    }

    private suspend fun run(replan: Boolean) {
        val plannedAt = _state.value.plannedAt
        val expired = plannedAt == null || Duration.between(plannedAt, clock()) >= PLAN_REUSE
        // After a failed plan only Retry plans again: the tick never retries it in a loop.
        val failed = _state.value.planError != null
        if (replan || (!failed && (expired || _state.value.routes == null))) plan()
        refreshLive()
    }

    private suspend fun plan() {
        _state.update { it.copy(planning = true) }
        // A first plan shows each stop's answer as it lands, so routes appear without waiting on
        // the slowest. A re-plan keeps the last plan until every stop has answered or failed, so
        // routes (and an open one) don't come and go as the answers land.
        val progressive = _state.value.routes == null
        val gathered = mutableListOf<TripRoute>()
        var answered = 0
        var failure: TflException? = null
        try {
            coroutineScope {
                for (destination in destinations) {
                    launch {
                        val routes = try {
                            withContext(io) { planner.journeys(fromId, destination) }
                        } catch (e: TflException) {
                            // Neither end is logged: together they're a trip the rider chose (a
                            // destination coordinate least of all, SPEC *Privacy*).
                            warn("trip plan failed: ${e::class.simpleName}")
                            failure = failure ?: e
                            return@launch
                        }
                        answered++
                        gathered += routes
                        // Nothing yet from any stop keeps "Planning…" (or the last plan) rather than
                        // say there's no route while others are still answering.
                        if (!progressive || gathered.isEmpty()) return@launch
                        // Every route stays in the plan; a detour another stop's answer beats is
                        // left out where it's shown ([State.shownRoutes]).
                        val shown = gathered.toList()
                        // A first answer after a failed plan clears its error: the routes it brings stand,
                        // timed at once from any boarding stop's arrivals another screen just fetched.
                        _state.update { it.copy(routes = shown, planError = null, statusUnknown = unknownLines(shown, it), live = cached(shown, it.live)) }
                    }
                }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(planning = false) }
            throw e
        }
        val failed = failure
        // Failed only when no stop answered: one that answered with no route is still an answer.
        if (answered == 0 && failed != null) {
            _state.update { it.copy(planning = false, planError = errorKindOf(failed)) }
            return
        }
        val routes = gathered.toList()
        // A route to one of the destination's stops that rides through another and comes back isn't
        // shown when another route gets off there no later: the rider would get off the first time.
        val visible = State(routes = routes, destinationStops = _state.value.destinationStops).shownRoutes(hiddenModes).orEmpty()
        val shown = routes.count { route -> route.rides.none { HiddenModes.isHidden(it.mode, it.lineId, hiddenModes) } }
        if (shown > visible.size) warn("journey planner: ${shown - visible.size} of $shown routes pass the destination")
        val at = clock()
        // Only a whole plan is kept for reuse: a partial one is planned again on the next open.
        if (failed == null) plans.put(fromId, destinations, routes, at)
        // A new plan's lines are unchecked until their status arrives: none passes as running
        // normally meanwhile (its last known status, if held, stands).
        _state.update {
            it.copy(
                routes = routes,
                plannedAt = at,
                planning = false,
                planError = null,
                planIncomplete = failed != null,
                statusUnknown = unknownLines(routes, it),
            )
        }
    }

    private suspend fun refreshLive() {
        // Routes riding a hidden mode aren't shown, so their stops and lines aren't fetched either.
        // Only the routes the screen times (the soonest few of those shown) are fetched for.
        val routes = _state.value.shownRoutes(hiddenModes)?.let(::bestOf) ?: return
        val lines = routes.flatMap { route -> route.rides.map { it.lineId } }.distinct()
        // Arrivals another screen fetched since show at once; only a stop not fetched within
        // [ArrivalsCache.TTL] is asked for again. Refreshing from the start, so the trip reads as
        // checking while its bus stop pairs are looked up too.
        _state.update { it.copy(refreshing = true, live = cached(routes, it.live)) }
        try {
            // Each bus stop pair's poles first (once a day, usually from the file cache): every one is fetched.
            lookUpPoles(routes)
            _state.update { it.copy(live = cached(routes, it.live)) }
            val now = clock()
            // A departure source changed while this refresh's arrivals are out: they're from the old one.
            val source = sourceGeneration
            val stops = stopsOf(routes).filter { id -> _state.value.live[id]?.let { !recentEnough(it, now) } ?: true }
            coroutineScope {
                val statuses = async { fetchStatuses(lines) }
                val live = stops.map { id -> async { id to fetchStop(id) } }.awaitAll()
                val fetched = statuses.await()
                val current = source == sourceGeneration
                _state.update { state ->
                    state.copy(
                        live = if (!current) state.live else state.live + live.associate { (id, stop) -> id to (stop ?: state.live[id]?.copy(failed = true) ?: StopLive(emptyList(), Instant.EPOCH, failed = true)) },
                        statuses = fetched ?: state.statuses,
                        statusFailed = fetched == null,
                        statusUnknown = lines.filterTo(HashSet()) { it !in (fetched ?: state.statuses) },
                    )
                }
            }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    // [live] with each of [routes]' boarding stops whose shared arrivals are newer than those held.
    // Fetched within [ArrivalsCache.TTL] of [now], and not failed: not asked for again. Dated after now
    // (the clock set back) is an age that can't be told, so asked for again.
    private fun recentEnough(held: StopLive, now: Instant): Boolean {
        val age = Duration.between(held.fetchedAt, now)
        return !held.failed && !age.isNegative && age < ArrivalsCache.TTL
    }

    // Each bus stop pair's poles, looked up once per model ([poles]); a failed lookup is asked again
    // on the next refresh, the Planner's own pole standing meanwhile.
    private val areaPoles = HashMap<String, List<String>>()

    private suspend fun lookUpPoles(routes: List<TripRoute>) {
        val areas = routes.flatMap { route -> route.rides.map { it.fromArea } }.filter { it.isNotBlank() && it !in areaPoles }.distinct()
        if (areas.isEmpty()) return
        coroutineScope {
            areas.map { area ->
                async {
                    area to try {
                        withContext(io) { poles(area) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TflException) {
                        warn("trip stop pair lookup failed: ${e::class.simpleName} for stop $area")
                        null
                    }
                }
            }.awaitAll()
        }.forEach { (area, found) -> if (found != null) areaPoles[area] = found }
        _state.update { it.copy(areaPoles = areaPoles.toMap()) }
    }

    // The stops [routes] board at: each ride's own, and every pole of a bus stop pair it boards at.
    private fun stopsOf(routes: List<TripRoute>): List<String> =
        (boardingStops(routes) + routes.flatMap { route -> route.rides.flatMap { areaPoles[it.fromArea].orEmpty() } }).distinct()

    private fun cached(routes: List<TripRoute>, live: Map<String, StopLive>): Map<String, StopLive> {
        val now = clock()
        val newer = stopsOf(routes).mapNotNull { id ->
            val entry = arrivals.get(id, now, client.arrivalsSource()) ?: return@mapNotNull null
            val held = live[id]
            if (held != null && !entry.fetchedAt.isAfter(held.fetchedAt)) return@mapNotNull null
            id to StopLive(entry.departures, entry.fetchedAt)
        }
        return if (newer.isEmpty()) live else live + newer
    }

    // Null on a failure, so the last arrivals stay (aged) rather than blank the leg.
    private suspend fun fetchStop(stopId: String): StopLive? =
        try {
            // Stamped when asked, as the list stamps its fetches (SPEC D4); kept for the other screens
            // unless another client could answer differently.
            val at = clock()
            val generation = arrivals.generation
            val source = client.arrivalsSource()
            val (departures, shared) = withContext(io) {
                val before = client.shareable(stopId)
                client.arrivals(stopId).let { it to (before && client.shareable(stopId) && client.arrivalsSource() == source) }
            }
            if (shared) arrivals.put(stopId, departures, at, client.railFeed(stopId), generation, source)
            StopLive(departures, at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("trip arrivals failed: ${e::class.simpleName} for stop $stopId")
            null
        }

    // Null on a failure: the last statuses stay rather than pass the lines off as running normally.
    private suspend fun fetchStatuses(lineIds: List<String>): Map<String, LineStatus>? =
        try {
            withContext(io) { client.lineStatuses(lineIds) }.associateBy { it.lineId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("trip line status failed: ${e::class.simpleName}")
            null
        }

    companion object {
        private const val KEY_OPEN_ROUTE = "openRoute"

        // The stops among [destinations]; a place has none, so no route to it is a detour.
        private fun stopIds(destinations: List<TripDestination>): List<String> =
            destinations.filterIsInstance<TripDestination.Stop>().map { it.id }

        private fun boardingStops(routes: List<TripRoute>): List<String> =
            routes.flatMap { route -> route.rides.map { it.fromId } }.filter { it.isNotBlank() }.distinct()

        private fun linesOf(routes: List<TripRoute>): Set<String> =
            routes.flatMapTo(HashSet()) { route -> route.rides.map { it.lineId } }

        private fun unknownLines(routes: List<TripRoute>, state: State): Set<String> =
            linesOf(routes).filterTo(HashSet()) { it !in state.statuses }

        /**
         * The routes worth timing among [routes] (the plan's, less those riding a hidden mode, so a
         * hidden mode's routes never crowd out the rest): the [MAX_ROUTES] that arrive soonest by the
         * Planner's timetable, each with all its timetable variants (a later one can still be caught
         * when an earlier one can't). Each kept route's boarding stops are fetched on every refresh,
         * so the cap bounds the requests a complex's several answers add.
         */
        internal fun bestOf(routes: List<TripRoute>): List<TripRoute> {
            val keys = routes.sortedBy { it.legs.lastOrNull()?.arrival ?: Instant.MAX }
                .map(::routeKey).distinct().take(MAX_ROUTES).toSet()
            return routes.filter { routeKey(it) in keys }
        }

        /** How many distinct routes a trip times at most. */
        const val MAX_ROUTES = 6

        /** How long a plan is reused before the Planner is asked again. */
        val PLAN_REUSE: Duration = Duration.ofMinutes(15)
    }
}

/**
 * The last few trips' plans, in memory for the process (SPEC *Trips with a change*: a plan is reused
 * if the same trip reopens within 15 minutes). Never written to storage; bounded to [MAX] trips.
 */
class TripPlans {
    private val plans = LinkedHashMap<String, Pair<List<TripRoute>, Instant>>()

    @Synchronized
    fun get(fromId: String, destinations: List<TripDestination>): Pair<List<TripRoute>, Instant>? = plans[key(fromId, destinations)]

    @Synchronized
    fun put(fromId: String, destinations: List<TripDestination>, routes: List<TripRoute>, at: Instant) {
        val key = key(fromId, destinations)
        plans.remove(key)
        plans[key] = routes to at
        while (plans.size > MAX) plans.remove(plans.keys.first())
    }

    // A stop keys by id; a place keys by its coordinate and its name, so the same trip reopened within
    // the reuse window finds its plan — but a place renamed (same spot) doesn't, since its cached
    // route's final walk leg carries the old name (KtorTflClient stamps it in), and a stale label
    // beats no reuse only when it's right.
    private fun key(fromId: String, destinations: List<TripDestination>) =
        "$fromId>${destinations.joinToString(",") { destKey(it) }}"

    private fun destKey(destination: TripDestination) = when (destination) {
        is TripDestination.Stop -> destination.id
        is TripDestination.Place -> "${destination.coordinate.latitude},${destination.coordinate.longitude}|${destination.name}"
    }

    companion object {
        const val MAX = 8
        val SHARED = TripPlans()
    }
}
