package app.stopdash.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.AlertStart
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.FinalStop
import app.stopdash.domain.TflException
import app.stopdash.domain.NearestStops
import app.stopdash.domain.PlacedStand
import app.stopdash.domain.TripClosures
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripLeg
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.StationIndex
import app.stopdash.domain.stampOf
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.TripTiming
import app.stopdash.domain.mergedRoutes
import app.stopdash.domain.withoutDetours
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A trip with a change (SPEC *Trips with a change*): TfL's Journey Planner's routes from [origin] to
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
    // Keys the trip's plans for reuse ([TripPlans]): a *From…* station's stop, or from here the stop
    // nearest the rider, so a move to a new nearest stop is a new trip.
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
    // Where the Planner plans from, read at each plan: from here the rider's latest fix, so TfL walks
    // from where they are to whichever stop serves the trip best (SPEC *Trips with a change*); from a
    // *From…* station, its stop. The stop [fromId] by default. The screen replaces it ([origin]) each
    // time it composes over this retained model, so it never reads a discarded composition's fix.
    origin: () -> TripOrigin = { TripOrigin.Stop(fromId) },
    // How fast the rider walks when the trip opens (their setting); [walkingSpeed] follows a change.
    walkingSpeed: WalkingSpeed = WalkingSpeed.AVERAGE,
    // The longest walk the rider will take when the trip opens (their setting); [maxWalk] follows a change.
    maxWalk: MaxWalk = MaxWalk.DEFAULT,
    // How step-free the routes must be when the trip opens (their setting); [stepFree] follows a change.
    stepFree: StepFree = StepFree.DEFAULT,
    // Which kinds of transport the routes may ride when the trip opens (their setting); [tripModes]
    // follows a change.
    tripModes: TripModes = TripModes.DEFAULT,
    // Whether the walking speed, max walk, step-free level and trip modes have been read from storage
    // when the trip opens; [optionsLoaded] follows. Every plan waits for it ([plan]).
    optionsLoaded: Boolean = true,
    // Each stop's last closure lookup, shared with the list (the app passes [StopClosureCache.SHARED]):
    // a stop the list or another trip checked within [closureReuse] isn't asked about again.
    private val closureCache: StopClosureCache = StopClosureCache(),
    private val closureReuse: Duration = DISRUPTION_REUSE,
    // The bundled station index, for which of the routes' walks are changes on foot ([State.changesOnFoot]):
    // read on [io], as it may read the asset.
    private val stations: () -> StationIndex = { StationIndex.EMPTY },
) : ViewModel() {
    /** Where the next plan starts ([TripOrigin]); set by the screen on every composition. */
    var origin: () -> TripOrigin = origin

    /**
     * How fast the rider walks ([WalkingSpeed]): every walk the Planner offers is timed at it, and so
     * is which trains each route can make. Set by the screen from the setting; a change once a plan is
     * held plans again at once, since each walk and connection was timed at the old pace.
     */
    var walkingSpeed: WalkingSpeed = walkingSpeed
        set(value) {
            if (value == field) return
            field = value
            optionsChanged()
        }

    /**
     * The longest walk the Planner may offer ([MaxWalk]), timed at [walkingSpeed]. Set by the screen
     * from the setting; a change once a plan is held plans again at once, since it changes which
     * routes the Planner offers.
     */
    var maxWalk: MaxWalk = maxWalk
        set(value) {
            if (value == field) return
            field = value
            optionsChanged()
        }

    /**
     * Whether the walking speed, max walk, step-free level and trip modes have been read from storage. Set by the
     * screen after them, on every composition. Every plan waits for it, whatever asked for the plan
     * (the first showing, the tick, a pull, Retry), so no route is planned under the defaults in
     * place of the rider's own choice; a read that never lands delays a plan by [OPTIONS_WAIT] at
     * most, then it plans with what it has and plans again once the read lands.
     */
    var optionsLoaded: Boolean
        get() = _optionsLoaded.value
        set(value) {
            _optionsLoaded.value = value
        }
    private val _optionsLoaded = MutableStateFlow(optionsLoaded)

    // While a plan waits for [optionsLoaded]: it snapshots the options once they land, so a change
    // meanwhile needs no plan of its own.
    private var awaitingOptions = false

    /**
     * How step-free the routes must be ([StepFree]): the Planner offers only routes with that much
     * step-free access. Set by the screen from the setting; a change plans again at once, as a walk
     * change does, since the routes it offers change with it.
     */
    var stepFree: StepFree = stepFree
        set(value) {
            if (value == field) return
            field = value
            optionsChanged()
        }

    /**
     * Which kinds of transport the routes may ride ([TripModes]): the Planner offers none riding a
     * group turned off. Set by the screen from the setting; a change plans again at once, as a walk
     * change does, since the routes it offers change with it.
     */
    var tripModes: TripModes = tripModes
        set(value) {
            if (value == field) return
            field = value
            optionsChanged()
        }

    // The walking speed, the walk limit, the step-free level or the trip modes changed: the plan
    // shown was made for the old ones.
    private fun optionsChanged() {
        // Routes planned for the old options aren't shown under the new ones, even while the new plan
        // runs or if it fails: the plan kept for these stands in, or none ("Planning…"). Before the
        // trip starts too, since the settings are read from storage after the model is made: it opens
        // on the plan kept for the rider's own options, not the defaults'.
        val here = origin() is TripOrigin.Here
        val kept = plans.get(fromId, destinations, here, walkingSpeed, maxWalk, stepFree, tripModes)
        plannedFrom = plans.origin(fromId, destinations, here, walkingSpeed, maxWalk, stepFree, tripModes)
        _state.update {
            it.copy(
                routes = kept?.first,
                plannedAt = kept?.second,
                planError = null,
                planIncomplete = false,
                statusUnknown = kept?.let { (routes, _) -> unknownLines(routes, it) } ?: emptySet(),
                closuresUnknown = kept?.let { (routes, _) -> unknownClosures(routes, it) } ?: emptySet(),
                live = kept?.let { (routes, _) -> cached(routes, it.live) } ?: it.live,
            )
        }
        // Before the trip is first started there's no plan running to replace: the first takes them,
        // as does one still waiting for them to be read.
        if (job != null && !awaitingOptions) start(replan = kept == null)
    }

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
        // The lines whose latest status check failed (all asked, when none was answered): a line
        // page can't vouch for one of them, though an answered line on the same trip it can.
        val statusFailedLines: Set<String> = emptySet(),
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
        // Each stop checked for closures ([TripClosures.ends]: where the routes board and get off, a
        // bus stop pair's every pole) and its notices, from its last successful check: a failed
        // check keeps the last known, claiming nothing new.
        val closures: Map<String, List<StopDisruption>> = emptyMap(),
        // The stops with no check known yet (not checked, or failed with none known), and the stop
        // pairs whose poles aren't looked up yet: a route getting on or off at one can't be vouched
        // for as open ([TripClosures.standing]).
        val closuresUnknown: Set<String> = emptySet(),
        // The stops whose latest check failed: their last known notices stand, but nothing vouches
        // for them as current, so the trip says it couldn't check, as after a failed status check.
        val closuresFailed: Set<String> = emptySet(),
        // When each stop's held closure check was asked ([StopClosureCache.Lookup.at]) and each line's
        // held status answered, stamped by the steady clock ([SteadyClock], aged by [checkCurrent]).
        // A held check is ranked by whatever its age, as a failed one is, but a
        // line's page vouches only while both are as young as a countdown it would show ([Staleness]):
        // a trip shown again, say, holds checks from before, while its fresh re-check is out.
        val closuresAt: Map<String, Instant> = emptyMap(),
        val statusesAt: Map<String, Instant> = emptyMap(),
        // The earliest day in London any of [statuses] was sorted on ([LineStatus.asOf]): one sorted on
        // an earlier day than the screen's may show work that has since started as still to come, so the
        // screen waits for them to be brought up to its day (Codex on #519). Kept as they're merged, never
        // worked out from them; null with none.
        val statusesSortedOn: LocalDate? = null,
        // The routes' walks that are changes on foot ([OnTheWay.changesOnFoot]), as a trip started on
        // one decides them: the walk legs themselves, so a route made from a planned one (a train
        // through a change) finds its walks too. Decided off the main thread as the routes come in;
        // empty until then, the walks shown as walks.
        val changesOnFoot: Set<TripLeg> = emptySet(),
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
        (plans.get(fromId, destinations, origin() is TripOrigin.Here, walkingSpeed, maxWalk, stepFree, tripModes)?.let { (routes, at) -> State(routes = routes, plannedAt = at, statusUnknown = linesOf(routes), closuresUnknown = unknownClosures(routes, State())) } ?: State())
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
            val before = bestOf(_state.value.shownRoutes(field).orEmpty(), openKeys())
            field = value
            if (_state.value.routes != null && bestOf(_state.value.shownRoutes(value).orEmpty(), openKeys()).any { route -> before.none { it === route } }) refresh()
        }

    /**
     * The route open on screen ([OpenRoute.encode]), held here rather than by the screen, so it stays open
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

    // The planned routes the open one can be ([OpenRoute.keys]): fetched for past the cap ([bestOf]),
    // with their boarding stops' every pole, where a train through a change boards too.
    private fun openKeys(): Set<String> = OpenRoute.parse(openRoute.value)?.keys.orEmpty()

    // The routes a refresh fetches for and checks ([refreshLive]): those shown, less a hidden mode's,
    // the soonest few and the open one ([bestOf]).
    private fun timedRoutes(): List<TripRoute>? = _state.value.shownRoutes(hiddenModes)?.let { bestOf(it, openKeys()) }

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
        // The re-pick a pull from here was waiting on: the run it starts below serves the pull, and
        // takes its indicator down when it ends.
        if (started && repickId != seenRepick) awaitingFix = false
        // A first showing, or a new re-pick, that finds the rider [REPLAN_MOVE_METERS] or more from where
        // the plan walks from: its first walk is from there, so the trip plans again from here rather
        // than time it wrong (a plan reused from earlier, or one made before they walked on).
        if ((!started || repickId != seenRepick) && movedFromPlan()) {
            started = true
            seenRepick = repickId
            start(replan = true)
            return
        }
        if (started && repickId == seenRepick) {
            // Shown again (a rotation, a return): take any newer arrivals another screen fetched
            // meanwhile, and refresh only if a boarding stop's are still over [ArrivalsCache.TTL]
            // old, rather than fetch everything again or wait for the minute tick.
            // The routes a refresh fetches for ([refreshLive]): a route not shown, or past the cap,
            // is never fetched, so its stops would read as stale on every return.
            val routes = timedRoutes() ?: return
            // Closures too: a stop the list found closed since this trip's last check shows at once.
            _state.update { sharedClosures(routes, it.copy(live = cached(routes, it.live))) }
            val now = clock()
            val live = _state.value.live
            val arrivalsStale = stopsOf(routes).any { id -> live[id]?.let { !recentEnough(it, now) } ?: true }
            // Or a stop's closure check is past [closureReuse] (or failed): what the trip holds for it
            // no longer stands as current, so it's asked again. Until the answer lands the route is
            // still ranked by it, as by a failed one, but its line's page doesn't vouch on it
            // ([State.closuresAt]).
            val closuresStale = _state.value.closuresFailed.isNotEmpty() ||
                (closureStops(routes, _state.value.areaPoles) + shownStops).any { id ->
                    closureCache[id]?.let { SteadyClock.age(it.at, now).let { age -> age.isNegative || age >= closureReuse } } ?: true
                }
            if (arrivalsStale || closuresStale) refresh()
            return
        }
        started = true
        seenRepick = repickId
        refresh()
    }

    // Where the plan shown was planned from; null until one is (a reused plan carries its own).
    private var plannedFrom: TripOrigin? = plans.origin(fromId, destinations, origin() is TripOrigin.Here, walkingSpeed, maxWalk, stepFree, tripModes)

    private fun movedFromPlan(): Boolean {
        val from = (plannedFrom as? TripOrigin.Here)?.coordinate ?: return false
        val now = (origin() as? TripOrigin.Here)?.coordinate ?: return false
        return NearestStops.distanceMeters(from.latitude, from.longitude, now.latitude, now.longitude) >= REPLAN_MOVE_METERS
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
        // Which of the routes' walks are changes on foot, decided off the main thread each time the
        // routes change (with the station index, as a trip started on one decides them).
        viewModelScope.launch {
            _state.map { it.routes.orEmpty() }.distinctUntilChanged().collectLatest { routes ->
                val walks = withContext(io) {
                    val index = stations()
                    routes.flatMapTo(HashSet()) { route -> OnTheWay.changesOnFoot(route, index).map { route.legs[it] } }
                }
                _state.update { it.copy(changesOnFoot = walks) }
            }
        }
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

    // Each route whose arrival was last withheld, by its key, to the leg and reason logged for it.
    private val withheldLogged = HashMap<String, String>()

    /**
     * Logs why each route's arrival is withheld ([TripTiming.Withheld]), once per route while the
     * same leg withholds it for the same reason, so the minute tick doesn't repeat it (SPEC principle
     * 2: a withheld arrival leaves its reason). [withheld] maps each timed route's key to its reason,
     * or null when its arrival shows.
     *
     * A withheld arrival means the rider can't make the Planner's own departure for a leg, and its
     * live trains don't say when the next leaves; the Planner's timetable does, from a fresh plan. So
     * one plan at least [REPLAN_WITHHELD] old is planned again at once (maintainer, 2026-09-27),
     * rather than keep "Arrival unknown" until [PLAN_REUSE] runs out: once per plan, so a withheld
     * arrival asks at most every [REPLAN_WITHHELD], never in a loop.
     */
    fun noteWithheld(withheld: Map<String, TripTiming.Withheld?>) {
        for ((key, why) in withheld) {
            if (why == null) {
                withheldLogged.remove(key)
                continue
            }
            val same = "${why.leg}:${why.lineId}:${why.reason}"
            if (withheldLogged.put(key, same) != same) warn("trip arrival withheld: ${why.describe()}")
        }
        withheld.values.firstNotNullOfOrNull { it }?.let(::replanWithheld)
    }

    // The plan (by when it was made) last planned again for a withheld arrival: once per plan.
    private var replannedFrom: Instant? = null

    // Says which leg and why on every re-plan: the per-route line above is logged once per reason,
    // so a withheld arrival that outlives a re-plan would otherwise leave the next one unexplained.
    private fun replanWithheld(why: TripTiming.Withheld) {
        val state = _state.value
        val at = state.plannedAt ?: return
        // A failed plan waits for Retry; one in flight will bring its own departures.
        if (state.planError != null || state.planning) return
        if (at == replannedFrom || Duration.between(at, clock()) < REPLAN_WITHHELD) return
        replannedFrom = at
        warn("trip re-planned (plan ${Duration.between(at, clock()).toMinutes()} min old): arrival withheld at ${why.describe()}")
        start(replan = true)
    }

    /** Plans again now, after a failure. During a refresh, plans again once it ends. */
    fun retry() = start(replan = true)

    private val _pulling = MutableStateFlow(false)

    /** Whether a pull on the routes ([pullRefresh]) is still planning or fetching: its indicator. */
    val pulling: StateFlow<Boolean> = _pulling.asStateFlow()

    // When the rider last pulled: a plan made, or arrivals asked for, before then are never reused in
    // place of asking again, however recent (maintainer, 2026-09-29). Every live refresh fetches such
    // a stop rather than reuse it within [ArrivalsCache.TTL], for as long as the trip is open, not
    // only in the pull's own run: a refresh already under way when the pull came, or a re-plan after
    // it (the fresh fix the pull takes from here, a walking speed changed), would otherwise put a new
    // stop's older arrivals up (Codex, PR #373). A fetch that fails keeps them, marked failed, as any
    // failed refresh does: the trip never blanks a leg for want of fresh data. Replaced by the next
    // pull, here or carried from the trip this one took over from ([carryPull]).
    private var pulledAt: Instant? = null

    // A pull from here waiting on the fresh fix it took: its indicator holds past its own run until
    // the trip has refreshed for the re-pick that fix brings ([refreshFor]), which can plan again
    // after the pull's run has ended (Codex, PR #373), or the re-locate ends without one
    // ([fixSettled]).
    private var awaitingFix = false

    /**
     * A pull on the routes (maintainer, 2026-09-29): the rider asking for the latest, so the trip plans
     * again now, whatever the plan's age, from the same start to the same place at the same pace, then
     * fetches every boarding stop's arrivals afresh, as the list's pull does. During a refresh, both
     * run once it ends. [awaitFix] when the pull also takes a fresh fix, whose re-pick the indicator
     * waits for. Returns when the pull came, for the screen to [carryPull] into a trip that takes this
     * one's place.
     */
    fun pullRefresh(awaitFix: Boolean = false): Instant {
        val at = clock()
        _pulling.value = true
        pulledAt = at
        awaitingFix = awaitFix
        start(replan = true)
        return at
    }

    /**
     * The re-locate a pull from here took has ended, [repickId] the latest re-pick. One this trip has
     * already refreshed for means the re-locate brought none (it failed, or was superseded), so the
     * pull has nothing more to wait for: its indicator goes once any run ends. A new one is left to
     * [refreshFor].
     */
    fun fixSettled(repickId: Long?) {
        if (!awaitingFix || repickId != seenRepick) return
        awaitingFix = false
        if (job?.isActive != true) _pulling.value = false
    }

    /**
     * A pull at [at] on the trip this one took over from: the fresh fix a pull from here takes can
     * find a new nearest stop, which is a trip of its own (Codex, PR #373). Nothing from before [at]
     * is reused here either, a plan kept for reuse included, so this trip plans and fetches afresh:
     * at once if it's already showing, else on its first refresh, with the pull's indicator up
     * until that run ends (Codex, PR #373): the trip it replaced is gone, and the fix that moved it
     * has landed. The screen sets it on every composition, so a pull no later than this trip's own
     * does nothing.
     */
    fun carryPull(at: Instant) {
        if (pulledAt?.isBefore(at) == false) return
        pulledAt = at
        _pulling.value = true
        if (started) refresh()
    }

    // A Retry tapped while a refresh runs: plan again when it ends rather than drop the tap.
    private var replanAgain = false

    private fun start(replan: Boolean) {
        if (job?.isActive == true) {
            again = true
            if (replan) replanAgain = true
            return
        }
        job = viewModelScope.launch {
            try {
                run(replan)
                while (again) {
                    again = false
                    val next = replanAgain
                    replanAgain = false
                    run(next)
                }
            } finally {
                // A pull asked for during this run is served by it, or the trip is gone: either way
                // its indicator goes, unless the pull still waits on its fresh fix.
                _pulling.value = awaitingFix
            }
        }
    }

    private suspend fun run(replan: Boolean) {
        val plannedAt = _state.value.plannedAt
        val expired = plannedAt == null || Duration.between(plannedAt, clock()) >= PLAN_REUSE
        // After a failed plan only Retry plans again: the tick never retries it in a loop.
        val failed = _state.value.planError != null
        // A plan from before the latest pull: one kept for reuse, when the pull was on the trip this
        // one took over from ([carryPull]).
        val beforePull = pulledAt?.let { pull -> plannedAt == null || plannedAt.isBefore(pull) } == true
        if (replan || (!failed && (expired || _state.value.routes == null || beforePull))) plan()
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
        // The rider's own options, not the defaults, once read: "Planning…" meanwhile.
        if (!optionsLoaded) {
            awaitingOptions = true
            try {
                if (withTimeoutOrNull(OPTIONS_WAIT.toMillis()) { _optionsLoaded.first { it } } == null) {
                    warn("trip options not read in ${OPTIONS_WAIT.seconds} s: planning with defaults")
                }
            } finally {
                awaitingOptions = false
            }
        }
        // One origin for every destination's request, so the answers merge as one plan from one place.
        val from = origin()
        val speed = walkingSpeed
        val limit = maxWalk
        val access = stepFree
        val modes = tripModes
        // Whether the rider has changed an option since this plan started: its routes are for the old ones.
        fun optionsChangedSince() = speed != walkingSpeed || limit != maxWalk || access != stepFree || modes != tripModes
        // What has landed so far, shown on a first plan.
        fun showGathered() {
            // Nothing yet from any stop keeps "Planning…" (or the last plan) rather than
            // say there's no route while others are still answering.
            // A plan for a walk the rider has since changed from isn't shown.
            if (!progressive || gathered.isEmpty() || optionsChangedSince()) return
            // Every route stays in the plan; a detour another stop's answer beats is
            // left out where it's shown ([State.shownRoutes]).
            val shown = gathered.toList()
            // A first answer after a failed plan clears its error: the routes it brings stand,
            // timed at once from any boarding stop's arrivals another screen just fetched.
            _state.update { it.copy(routes = shown, planError = null, statusUnknown = unknownLines(shown, it), closuresUnknown = unknownClosures(shown, it), live = cached(shown, it.live)) }
        }
        try {
            coroutineScope {
                for (destination in destinations) {
                    launch {
                        val routes = try {
                            withContext(io) { planner.journeys(from, destination, speed, limit, access, modes) }
                        } catch (e: TflException) {
                            // Neither end is logged: together they're a trip the rider chose (a
                            // destination coordinate least of all, SPEC *Privacy*).
                            warn("trip plan failed: ${e::class.simpleName}")
                            failure = failure ?: e
                            return@launch
                        }
                        answered++
                        gathered += routes
                        showGathered()
                        // To a place, asked once more for the fewest changes via where the fastest
                        // route gets off its last ride ([FinalStop]): the one bus the whole way,
                        // which the Planner can pass over for a long walk.
                        val finalStop = (if (destination is TripDestination.Place) FinalStop.of(routes) else null) ?: return@launch
                        val answer = try {
                            withContext(io) { planner.fewestChangesVia(from, destination, finalStop.stopId, speed, limit, access, modes) }
                        } catch (e: TflException) {
                            // The plan stands without it, as it does without either of its own two
                            // requests. The stop isn't logged: it's a way to the rider's place.
                            warn("trip plan via the fastest route's last stop failed: ${e::class.simpleName}")
                            return@launch
                        }
                        val fewer = finalStop.fewerRides(answer)
                        warn("trip plan via the fastest route's last stop: ${fewer.size} of ${answer.size} routes ride fewer times")
                        // A route already planned (the same legs at the same times) stays once.
                        gathered += mergedRoutes(gathered.toList(), fewer).drop(gathered.size)
                        showGathered()
                    }
                }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(planning = false) }
            throw e
        }
        val failed = failure
        if (optionsChangedSince()) {
            // The rider changed the walk while this ran: its routes are for the old one. A whole plan
            // is still kept for that walk; the new one is planned next ([start]'s loop).
            if (failed == null) plans.put(fromId, destinations, gathered.toList(), clock(), from, speed, limit, access, modes)
            _state.update { it.copy(planning = false) }
            return
        }
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
        if (failed == null) plans.put(fromId, destinations, routes, at, from, speed, limit, access, modes)
        plannedFrom = from
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
                closuresUnknown = unknownClosures(routes, it),
            )
        }
    }

    private suspend fun refreshLive() {
        // Routes riding a hidden mode aren't shown, so their stops and lines aren't fetched either.
        // Only the routes the screen times (the soonest few of those shown, and the open one) are
        // fetched for.
        val routes = timedRoutes() ?: return
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
            val stops = stopsOf(routes).filter { needsFetch(it, now) }
            // And the other lines at the rides' boarding stops ([rideLineIds]), in the same request: one
            // of them times a ride only once it's checked as running ([rideTrains]). Only the plan's own
            // lines count toward the trip's "couldn't check" note.
            val others = rideLineIds(routes, _state.value, hiddenModes).filterNot { it in lines }
            coroutineScope {
                val statuses = async { fetchStatuses(lines + others) }
                // Where the routes board and get off, for a closure or a moved stop (SPEC *Trips with a change*).
                val asked = (closureStops(routes, areaPoles) + shownStops).distinct()
                val closures = async { checkClosures(asked) }
                val live = stops.map { id -> async { id to fetchStop(id) } }.awaitAll()
                val fetched = statuses.await()
                val checked = closures.await()
                val latest = checked.latest()
                val current = source == sourceGeneration
                _state.update { state ->
                    val next = state.copy(
                        live = if (!current) state.live else state.live + live.associate { (id, stop) -> id to (stop ?: state.live[id]?.copy(failed = true) ?: StopLive(emptyList(), Instant.EPOCH, failed = true)) },
                        // A failed request's lines keep their older statuses; the answered ones replace.
                        statuses = fetched?.let { it.statuses + state.statuses.filterKeys { id -> id in it.failed } } ?: state.statuses,
                        statusesAt = fetched?.let { state.statusesAt + it.answeredAt() } ?: state.statusesAt,
                        // A failed request's lines keep theirs, sorted when they were.
                        statusesSortedOn = fetched?.let { if (it.failed.isEmpty()) it.sortedOn else earlier(state.statusesSortedOn, it.sortedOn) } ?: state.statusesSortedOn,
                        statusFailed = fetched == null || fetched.failed.isNotEmpty(),
                        statusFailedLines = fetched?.failed ?: (lines + others).toSet(),
                        statusUnknown = lines.filterTo(HashSet()) { it !in (fetched?.statuses ?: state.statuses) },
                        // A stop whose check failed keeps its last known notices. Settled only for the
                        // stops no check since has asked about ([ClosureCheck.latest]): one checked
                        // again meanwhile keeps that check's verdict, and one it didn't ask about
                        // keeps its own while the screen still names it (Codex, PR #375).
                        closures = state.closures + checked.found.filterKeys { it in latest },
                        closuresAt = state.closuresAt + checked.at.filterKeys { it in latest },
                        closuresFailed = state.closuresFailed.filterTo(HashSet()) { if (it in asked) it !in latest else it in shownStops } +
                            checked.failed.filter { it in latest },
                    )
                    next.copy(closuresUnknown = unknownClosures(next.routes.orEmpty(), next))
                }
                fetched?.let { reconcileLineDismissals(it) }
                reconcileStopDismissals(checked)
            }
            // Lines first seen in this refresh's arrivals: checked now rather than a tick later, so one
            // isn't shown for a minute with nothing said of its status. Merged in; a failure leaves
            // them unchecked, so they neither show as catchable nor time a route, and once this refresh ends
            // the trip says it couldn't check them ([RideLines.unchecked]) rather than still checking.
            // Their latest check settles their line pages too: one that failed can't be vouched for by
            // a status an earlier refresh kept.
            val late = rideLineIds(routes, _state.value, hiddenModes).filterNot { it in lines || it in others }
            if (late.isNotEmpty() && source == sourceGeneration) {
                val found = fetchStatuses(late)
                // Every line asked about takes this check's verdict, as the first check's lines do: an
                // answer replaces its status, a failure keeps the old one (marked failed below), and one
                // left out of the answer keeps none, rather than one an earlier check kept.
                fun <V> judged(held: Map<String, V>, f: StatusCheck) = held.filterKeys { id -> id !in late || id in f.failed }
                _state.update {
                    it.copy(
                        statuses = found?.let { f -> judged(it.statuses, f) + f.statuses } ?: it.statuses,
                        statusesAt = found?.let { f -> judged(it.statusesAt, f) + f.answeredAt() } ?: it.statusesAt,
                        statusesSortedOn = found?.let { f -> earlier(it.statusesSortedOn, f.sortedOn) } ?: it.statusesSortedOn,
                        statusFailedLines = it.statusFailedLines - late.toSet() + (found?.failed ?: late.toSet()),
                    )
                }
                found?.let { reconcileLineDismissals(it) }
            }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    // Whether stop [id]'s arrivals are to be asked for: none held, failed, or past [ArrivalsCache.TTL]
    // ([recentEnough]); or not fetched since the last pull, however recent, as the rider asked for the
    // latest. The pull is the wall clock's, a fetch the steady clock's ([SteadyClock.toWall]).
    private fun needsFetch(id: String, now: Instant): Boolean {
        val since = pulledAt
        // A station fetched without its board, National Rail being hidden, once it's shown again.
        if (id in boardSkipped && HiddenModes.wantsRailBoard(hiddenModes)) return true
        return _state.value.live[id]?.let { !recentEnough(it, now) || (since != null && SteadyClock.toWall(it.fetchedAt).isBefore(since)) } ?: true
    }

    // The National Rail stations last fetched without their board ([HiddenModes.wantsRailBoard]): no
    // route timed then rode National Rail, since those riding a hidden mode aren't timed. One is
    // fetched again once National Rail shows, so a route boarding a train there gets its times.
    private val boardSkipped: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // [live] with each of [routes]' boarding stops whose shared arrivals are newer than those held.
    // Fetched within [ArrivalsCache.TTL] of [now], and not failed: not asked for again. Dated after now
    // (the clock set back) is an age that can't be told, so asked for again.
    private fun recentEnough(held: StopLive, now: Instant): Boolean {
        val age = SteadyClock.age(held.fetchedAt, now)
        return !held.failed && !age.isNegative && age < ArrivalsCache.TTL
    }

    // Each bus stop pair's poles, looked up once per model ([poles]); a failed lookup is asked again
    // on the next refresh, the Planner's own pole standing meanwhile.
    private val areaPoles = HashMap<String, List<String>>()

    private suspend fun lookUpPoles(routes: List<TripRoute>) {
        // Where a bus gets off too: the pole it uses there is checked for a closure.
        val areas = routes.flatMap { route -> route.rides.flatMap { listOf(it.fromArea, it.toArea) } }.filter { it.isNotBlank() && it !in areaPoles }.distinct()
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

    // The stops [routes] board at: each ride's own, every pole of a bus stop pair it boards at, and
    // each stand the screen boards a bus at in place of the Planner's ([boardAt]).
    private fun stopsOf(routes: List<TripRoute>): List<String> =
        (boardingStops(routes) + routes.flatMap { route -> route.rides.flatMap { areaPoles[it.fromArea].orEmpty() } } + placedStands).distinct()

    // The stands the screen boards buses at in place of the one the Planner named ([boardAt]).
    private var placed: Set<PlacedStand> = emptySet()
    private val placedStands: Set<String> get() = placed.mapTo(HashSet()) { it.standId }

    // Every placing the screen has handed over to this trip, still shown or not ([boardAt]).
    private val placedEver = HashSet<PlacedStand>()

    /**
     * The bus stations' stands the screen boards buses at in place of the one the Planner named
     * ([PlacedStand]: it can name a stand the line doesn't use, so no bus of the line is ever
     * predicted there). Fetched with the routes' own boarding stops on every refresh ([stopsOf]); the
     * screen moves the leg to a stand only once it has been fetched ([onPoles]).
     *
     * A stand the screen starts boarding at (new, or back after its route left the shown few) is
     * fetched by a refresh of its own when its arrivals aren't current ([needsFetch], as a refresh
     * judges every stop), so every write to the live times comes from one refresh at a time and the
     * last one asked has the last word (Codex, #398): a refresh under way runs once more when it
     * ends. A stand already current, or handed over again unchanged, asks for nothing, so the screen
     * handing them over on each change to what it shows can't loop refreshes: at most one per stand
     * each time its arrivals go stale. Each placing is logged once (stop and line ids only), and a
     * failed fetch of its stand by [fetchStop].
     */
    fun boardAt(stands: Set<PlacedStand>) {
        val active = placedStands
        placed = stands
        stands.filter { placedEver.add(it) }
            .forEach { warn("trip bus ${it.lineId} boards at the route's stand ${it.standId}, not the Planner's ${it.plannerId}") }
        val now = clock()
        if (stands.any { it.standId !in active && needsFetch(it.standId, now) }) refresh()
    }

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
            // Stamped when asked, as the list stamps its fetches (SPEC D4), and by the steady clock
            // ([SteadyClock]); kept for the other screens unless another client could answer differently.
            val at = SteadyClock.stamp(clock())
            val generation = arrivals.generation
            val source = client.arrivalsSource()
            // With National Rail hidden no route timed rides it, so a station's board isn't asked for.
            val board = HiddenModes.wantsRailBoard(hiddenModes)
            val (departures, shared) = withContext(io) {
                val before = client.shareable(stopId)
                client.arrivals(stopId, board).let { it to (before && client.shareable(stopId) && client.arrivalsSource() == source) }
                    .also { if (!board && client.hasRailBoard(stopId)) boardSkipped += stopId else boardSkipped -= stopId }
            }
            // Dated by their oldest part: a National Rail board another screen fetched moments ago
            // keeps its age ([TflClient.fetchedAt]).
            val fetchedAt = client.stampOf(stopId, at)
            // Its board's trains with no time kept with them for the list, though no trip times them.
            if (shared) arrivals.put(stopId, departures, fetchedAt, client.railFeed(stopId), generation, source, client.untimed(stopId))
            StopLive(departures, fetchedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("trip arrivals failed: ${e::class.simpleName} for stop $stopId")
            null
        }

    /**
     * Each stop's latest closure check's place in line ([StopClosureCache.ask]), whichever check it
     * was (a refresh's, or the screen's stops on their own, [checkShownStops]): only that check's
     * verdict settles the stop ([ClosureCheck.latest]), so an older one landing late never replaces
     * it — a success over a newer failure, say, which would pass the stop off as checked open (Codex,
     * PR #375) — and a failure stops counting only once a lookup asked after it has come in
     * ([sharedClosures]).
     */
    private val closureAsks = HashMap<String, StopClosureCache.Ask>()

    // How the trip's stops are checked for closures: as a trip on the way checks its own.
    private val closureChecks = StopClosureChecks(client, closureCache, closureReuse, io, warn, "trip")

    // The stops of [this] check that no check since has asked about: the ones its verdict settles.
    private fun ClosureCheck.latest(): Set<String> = ids.filterTo(HashSet()) { closureAsks[it] === ask }

    // The stops the screen judges the routes at ([checkShownStops]), asked about on every refresh.
    private var shownStops: Set<String> = emptySet()

    /**
     * The stops the screen judges the routes at ([stops]), among them some the trip doesn't ask about
     * by itself ([closureStops], the Planner's stops and their pairs' poles): a stand a bus is placed
     * on in place of the one the Planner named, or a pole another line a ride shows boards or gets
     * off at. Every one is checked for a closure with the rest on every refresh, all of them kept
     * whichever routes a refresh times (Codex, PR #375), so a route isn't left unvouched for at a stop
     * only the screen can name; those new to the trip that the routes it times don't name are checked
     * at once.
     */
    fun checkShownStops(stops: Set<String>) {
        val added = stops - shownStops
        shownStops = stops
        val own = timedRoutes()?.let { closureStops(it, areaPoles) }.orEmpty().toSet()
        val asked = added - own
        if (asked.isEmpty()) return
        viewModelScope.launch {
            val checked = checkClosures(asked.toList())
            val latest = checked.latest()
            _state.update {
                val next = it.copy(
                    closures = it.closures + checked.found.filterKeys { id -> id in latest },
                    closuresAt = it.closuresAt + checked.at.filterKeys { id -> id in latest },
                    closuresFailed = it.closuresFailed - latest + checked.failed.filter { id -> id in latest },
                )
                // The routes can have changed while it was out, making one of these their own.
                next.copy(closuresUnknown = unknownClosures(next.routes.orEmpty(), next))
            }
            reconcileStopDismissals(checked)
        }
    }

    /**
     * [state] with [routes]' closure stops as [closureCache] now holds them: this trip's own last
     * answer or a newer one another screen found (the list, say, after this trip's check failed), so
     * a trip shown again takes it without waiting for a refresh. A stop this trip never checked takes
     * one only within [closureReuse], as a refresh would; a failed one stops counting as failed only
     * once a lookup asked after this trip's check has come in.
     */
    private fun sharedClosures(routes: List<TripRoute>, state: State): State {
        val now = clock()
        val held = (closureStops(routes, state.areaPoles) + shownStops).distinct().mapNotNull { id ->
            val lookup = closureCache[id] ?: return@mapNotNull null
            val recent = SteadyClock.age(lookup.at, now).let { !it.isNegative && it < closureReuse }
            (id to lookup).takeIf { id in state.closures || recent }
        }.toMap()
        if (held.isEmpty()) return state
        // A failed stop stops counting as failed once a lookup asked after its own latest check is in.
        val failed = state.closuresFailed.filterTo(HashSet()) { id -> closureAsks[id]?.let { closureCache.since(id, it) == null } ?: true }
        val next = state.copy(
            closures = state.closures + held.mapValues { it.value.notices },
            closuresAt = state.closuresAt + held.mapValues { it.value.at },
            closuresFailed = failed,
        )
        return next.copy(closuresUnknown = unknownClosures(next.routes.orEmpty(), next))
    }

    // What a closure check learned ([found], each asked [at]), and the stops whose request failed.
    // Which stops it was for ([ids]), and its place in line ([ask]).
    private class ClosureCheck(
        val found: Map<String, List<StopDisruption>>,
        val at: Map<String, Instant>,
        val failed: Set<String>,
        val ids: List<String>,
        val ask: StopClosureCache.Ask,
    )

    /**
     * The closure notices of each of [ids] this check could learn: from [closureCache] when looked up
     * within [closureReuse], else asked for (a bus stop's poles several to a request, as the list
     * asks them) and kept there. A stop whose request failed, with no later lookup of it kept, is
     * named in [ClosureCheck.failed], so its last known notices stand, or it stays unknown; the
     * failure is logged, never claimed as open.
     */
    private suspend fun checkClosures(ids: List<String>): ClosureCheck {
        val now = clock()
        // This check's place in line ([StopClosureCache.ask]), taken before any request is sent, and
        // each stop's latest check from now on ([closureAsks]).
        val ticket = closureCache.ask(now)
        for (id in ids) closureAsks[id] = ticket
        val checked = closureChecks.check(ids, ticket, now)
        return ClosureCheck(checked.found, checked.at, checked.failed, ids, ticket)
    }

    // What a status check found: the statuses TfL returned, answered [at], the lines it gave a verdict
    // on ([answered], a status or none), and the lines in a request that failed.
    private class StatusCheck(val statuses: Map<String, LineStatus>, val answered: Set<String>, val failed: Set<String>, val at: Instant, val sortedOn: LocalDate) {
        // Each returned line's answer time, for [State.statusesAt].
        fun answeredAt(): Map<String, Instant> = statuses.mapValues { at }
    }

    // One request per group TfL accepts (LineStatusBatch), each with its own outcome. Null when none
    // was answered: the last statuses stay rather than pass the lines off as running normally.
    // Settles the dismissals of the stops [check] found, each as its own place ([stopDismissalCheck]),
    // so a closure dismissed on the trip shows again when it recurs, without waiting for the list to
    // check that stop (Codex on #367). Only the stops no later check has asked about since: an older
    // answer landing late isn't evidence over a newer one.
    private suspend fun reconcileStopDismissals(check: ClosureCheck) {
        val latest = check.latest()
        val (live, checked) = stopDismissalCheck(check.found.filterKeys { it in latest }, clock())
        reconcileDismissals(_dismissed.value, live, checked, dismissedStore, io, warn, "trip") { gone ->
            // What's let go of, from what's dismissed now: one made meanwhile stays (Codex on #519).
            _dismissed.update { it - gone }
        }
    }

    // Settles the dismissals of the lines [check] answered ([reconcileLineDismissals]).
    private suspend fun reconcileLineDismissals(check: StatusCheck) {
        val answered = check.statuses.filterKeys { it !in check.failed }
        reconcileLineDismissals(_dismissed.value, answered, check.answered, clock(), dismissedStore, io, warn, "trip") { gone ->
            // What's let go of, from what's dismissed now: one made meanwhile stays (Codex on #519).
            _dismissed.update { it - gone }
        }
    }

    private suspend fun fetchStatuses(lineIds: List<String>): StatusCheck? {
        // The day they're sorted on, read before they're asked for: never later than it was.
        val sortedOn = clock().atZone(AlertStart.ZONE).toLocalDate()
        val results = LineStatusBatch.request(lineIds) { chunk -> withContext(io) { client.lineStatuses(chunk) } }
        results.failure?.let { warn("trip line status failed for ${results.failed.size} line(s): ${it::class.simpleName}") }
        if (results.unknown.isNotEmpty()) warn("trip line status: TfL doesn't know ${results.unknown.size} line(s)")
        if (!results.anyAnswered) return null
        // Stamped by the steady clock, as a fetch is ([SteadyClock]).
        return StatusCheck(results.answers.flatMap { it.value }.associateBy { it.lineId }, results.answeredIds, results.failed.toSet(), SteadyClock.stamp(clock()), sortedOn)
    }

    // The earlier of [held] (none with no statuses held) and [day].
    private fun earlier(held: LocalDate?, day: LocalDate): LocalDate = if (held != null && held.isBefore(day)) held else day

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

        // The stops [routes] are checked at for closures ([TripClosures.ends]), with every pole of
        // each stop pair they name that [areaPoles] has looked up: the one a bus uses may be the other.
        private fun closureStops(routes: List<TripRoute>, areaPoles: Map<String, List<String>>): List<String> =
            routes.flatMap { route ->
                TripClosures.ends(route).flatMap { end -> listOf(end.id) + areaPoles[end.area].orEmpty() }
            }.distinct()

        // [routes]' stops with no closure check known in [state], and the stop pairs whose poles
        // aren't looked up yet (so the pole a bus uses may not have been asked about).
        private fun unknownClosures(routes: List<TripRoute>, state: State): Set<String> =
            closureStops(routes, state.areaPoles).filterTo(HashSet()) { it !in state.closures } +
                routes.flatMap { route -> TripClosures.ends(route).map { it.area } }.filter { it.isNotEmpty() && it !in state.areaPoles }

        /**
         * The routes worth timing among [routes] (the plan's, less those riding a hidden mode, so a
         * hidden mode's routes never crowd out the rest): the [MAX_ROUTES] that arrive soonest by the
         * Planner's timetable, each with all its timetable variants (a later one can still be caught
         * when an earlier one can't). Each kept route's boarding stops are fetched on every refresh,
         * so the cap bounds the requests a complex's several answers add. The route open on screen
         * ([keep], by [routeKey]: for a train through a change, the planned route it's made from too,
         * [OpenRoute.keys]) is kept past the cap while [routes] offer it, so live times that move it
         * out of the soonest few never close it under the rider. So is the route walking least: the bus
         * to the station that spares the walk there arrives later, and would otherwise be cut before
         * its *Least walking* card could show (maintainer, 2026-10-03). Whether it walks enough less
         * is judged on the live ranking ([routeLabels]), whose first card may not be the timetable's
         * soonest, so it's kept whatever it saves. One route more at most, so the cap still bounds
         * the requests.
         */
        internal fun bestOf(routes: List<TripRoute>, keep: Collection<String>): List<TripRoute> {
            val soonest = routes.sortedBy { it.legs.lastOrNull()?.arrival ?: Instant.MAX }
            // Of routes walking as little, the soonest: so a plan with no walks adds none past the cap.
            val leastWalking = soonest.minByOrNull { it.walking }
            val keys = soonest.map(::routeKey).distinct().take(MAX_ROUTES).toSet() + keep + listOfNotNull(leastWalking?.let(::routeKey))
            return routes.filter { routeKey(it) in keys }
        }

        /** [bestOf], keeping the one route [keep] names. */
        internal fun bestOf(routes: List<TripRoute>, keep: String? = null): List<TripRoute> = bestOf(routes, listOfNotNull(keep))

        /** How many distinct routes a trip times at most. */
        const val MAX_ROUTES = 6

        /** How long a plan is reused before the Planner is asked again. */
        val PLAN_REUSE: Duration = Duration.ofMinutes(15)

        // The longest a plan waits for the walk options to be read ([optionsLoaded]): the settings
        // holder's own bound on a slow read, so a stuck read delays a plan but never blocks it.
        val OPTIONS_WAIT: Duration = Duration.ofSeconds(2)

        /**
         * How far the rider moves from where a trip from here was planned before it plans again: its
         * first walk is from there (SPEC *Trips with a change*). A block or so, well past a fix's wander.
         */
        const val REPLAN_MOVE_METERS = 150.0

        /**
         * How old a plan must be before a withheld arrival plans again ([noteWithheld]): long enough
         * that a fresh plan's departures have moved on, and a cap on how often it asks.
         */
        val REPLAN_WITHHELD: Duration = Duration.ofMinutes(5)
    }
}

/**
 * The last few trips' plans, in memory for the process (SPEC *Trips with a change*: a plan is reused
 * if the same trip reopens within 15 minutes). Never written to storage; bounded to [MAX] trips.
 */
class TripPlans {
    private class Held(val routes: List<TripRoute>, val at: Instant, val from: TripOrigin?)

    private val plans = LinkedHashMap<String, Held>()

    // [here]: whether the plan starts from the rider's position rather than the stop [fromId]. The two
    // are kept apart, since the same nearest stop can be a From… station's own stop, and a plan from
    // here opens with a walk from the rider that one from the stop doesn't have.
    // [speed], [maxWalk], [stepFree] and [modes]: the walking speed the plan was timed at, and the walk
    // limit, step-free level and modes it was planned under; a plan under other options is another plan.
    @Synchronized
    fun get(
        fromId: String,
        destinations: List<TripDestination>,
        here: Boolean = false,
        speed: WalkingSpeed = WalkingSpeed.AVERAGE,
        maxWalk: MaxWalk = MaxWalk.DEFAULT,
        stepFree: StepFree = StepFree.DEFAULT,
        modes: TripModes = TripModes.DEFAULT,
    ): Pair<List<TripRoute>, Instant>? = plans[key(fromId, destinations, here, speed, maxWalk, stepFree, modes)]?.let { it.routes to it.at }

    /** Where the plan [get] returns was planned from: from here, the rider's position then. */
    @Synchronized
    fun origin(
        fromId: String,
        destinations: List<TripDestination>,
        here: Boolean = false,
        speed: WalkingSpeed = WalkingSpeed.AVERAGE,
        maxWalk: MaxWalk = MaxWalk.DEFAULT,
        stepFree: StepFree = StepFree.DEFAULT,
        modes: TripModes = TripModes.DEFAULT,
    ): TripOrigin? = plans[key(fromId, destinations, here, speed, maxWalk, stepFree, modes)]?.from

    @Synchronized
    fun put(
        fromId: String,
        destinations: List<TripDestination>,
        routes: List<TripRoute>,
        at: Instant,
        from: TripOrigin? = null,
        speed: WalkingSpeed = WalkingSpeed.AVERAGE,
        maxWalk: MaxWalk = MaxWalk.DEFAULT,
        stepFree: StepFree = StepFree.DEFAULT,
        modes: TripModes = TripModes.DEFAULT,
    ) {
        val key = key(fromId, destinations, from is TripOrigin.Here, speed, maxWalk, stepFree, modes)
        plans.remove(key)
        plans[key] = Held(routes, at, from)
        while (plans.size > MAX) plans.remove(plans.keys.first())
    }

    // A stop keys by id; a place keys by its coordinate and its name, so the same trip reopened within
    // the reuse window finds its plan — but a place renamed (same spot) doesn't, since its cached
    // route's final walk leg carries the old name (KtorTflClient stamps it in), and a stale label
    // beats no reuse only when it's right.
    private fun key(
        fromId: String,
        destinations: List<TripDestination>,
        here: Boolean,
        speed: WalkingSpeed,
        maxWalk: MaxWalk,
        stepFree: StepFree,
        modes: TripModes,
    ) = "${if (here) "here@" else ""}$fromId>${destinations.joinToString(",") { destKey(it) }}~${speed.name}~${maxWalk.name}~${stepFree.name}~${modes.key}"

    private fun destKey(destination: TripDestination) = when (destination) {
        is TripDestination.Stop -> destination.id
        is TripDestination.Place -> "${destination.coordinate.latitude},${destination.coordinate.longitude}|${destination.name}"
    }

    companion object {
        const val MAX = 8
        val SHARED = TripPlans()
    }
}
