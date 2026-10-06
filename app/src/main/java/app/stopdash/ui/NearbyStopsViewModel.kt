package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteShortcuts
import app.stopdash.domain.FixRefinement
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LocationFix
import app.stopdash.domain.LocationProvider
import app.stopdash.domain.MoveFollow
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.NearestByLine
import app.stopdash.domain.NearestStops
import app.stopdash.domain.StopFinder
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.Workers
import java.time.Duration
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Why the shown nearby set's location is low-confidence, for the departures view's banner
 * (SPEC *Finding stops*, principle 2). [APPROXIMATE]: the set was resolved from a last-known
 * fallback fix (a fresh fix failed), so it may be a previous position. [UPDATE_FAILED]: a
 * re-locate couldn't get a fresh fix, so the set already shown was kept rather than jumping to a
 * stale one. [COARSE]: the set was resolved from a fresh but coarse (network) fix because GPS
 * didn't answer in time; a precise fix is being asked for, and the banner clears when it confirms
 * the set or the set moves to it. All offer a "try again" (a re-locate); all clear once a fresh
 * precise fix resolves.
 */
enum class LocationBanner { APPROXIMATE, UPDATE_FAILED, COARSE }

/**
 * The location gate in front of the departures view (SPEC *Finding stops*, D1): it turns
 * "where is the device" into the nearby [StopRef]s [MainViewModel] then shows departures
 * for. Kept separate from the departures state machine on purpose — finding stops is the
 * action that sends the user's location off the device (via [StopFinder]), so it is confined
 * to this class's [locate]/[relocate] and never bleeds into the departures state machine
 * (mirrors the [StopFinder]/[app.stopdash.domain.TflClient] split).
 *
 * Location is taken only on an explicit find: [locate] on screen open (and after a grant or a
 * retry), and [relocate] on a **user-adjacent** departures refresh — the refresh control, a
 * pull-to-refresh, or a return to the foreground — since the user may have walked since the last
 * fix (SPEC *Finding stops*, D1). Only the automatic on-screen tick is **location-free**, reusing
 * the nearby set this class last resolved so an always-open surface keeps countdowns live without
 * a timed location send. This keeps location on-demand and foreground; the cancel-on-relocate
 * invariant then stops a location-free refresh from fetching (and persisting) the previous set's
 * departures while a re-locate is in flight.
 *
 * Every non-happy outcome is a distinct, honest state rather than an empty list (SPEC
 * principles 1–2): the permission isn't held ([State.PermissionRequired]), there's no fix
 * ([State.NoLocation]), TfL couldn't be reached ([State.Failed]), or there simply are no
 * stops in range ([State.Empty]). [io] is injected for a test dispatcher; [warn] is the
 * sanitized failure log — it never carries a coordinate (SPEC *Privacy*).
 */
class NearbyStopsViewModel(
    private val location: LocationProvider,
    private val finder: StopFinder,
    private val radiusMeters: Int = NearbySelection.OUTER_RADIUS_METERS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Where the stops a lookup found are worked over (picked, measured, described): work that grows
    // with the stops in range, never on the caller's thread, which is the main one (AGENTS.md *Main
    // thread: read and dispatch only*).
    private val compute: CoroutineDispatcher = Workers.compute,
    private val warn: (String) -> Unit = {},
    // Where each lookup was made from, for the in-memory RecentPositions only — never [warn],
    // whose lines are persisted (SPEC *Privacy*). A searched station's area leaves it unset.
    private val position: (what: String, at: Coordinates) -> Unit = { _, _ -> },
    // The transport modes the user has hidden: a stop serving only those isn't picked, so it costs
    // no request (SPEC *Finding stops → Hiding a mode*). Read at each resolve.
    private val hiddenModes: suspend () -> Set<String> = { emptySet() },
    // A searched station's own stops, when this set stands at that station (From…): they are where
    // the rider is taken to be, so they're 0 m away rather than their distance from its middle.
    private val anchorStopIds: Set<String> = emptySet(),
    // Usage events, categories and counts only (UsageEvent): how a fix went, how many stops of each
    // mode were found. Near me only; a searched station's area reports none.
    private val usage: (UsageEvent) -> Unit = {},
    // A monotonic clock in milliseconds, timing a fix for [usage] and how long a shown set has stood.
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    // Location updates while the list is on screen, to follow a rider who walks off ([MoveFollow]):
    // started when the surface is, stopped with it, so they never outlive the foreground. None by
    // default (tests, a searched station's area, which stands where the station is).
    private val moves: () -> Flow<LocationFix> = { emptyFlow() },
) : ViewModel() {
    sealed interface State {
        /** The location permission isn't held yet — the screen asks for it. */
        data object PermissionRequired : State

        /** Resolving: a fix and the nearby lookup are in flight — show a placeholder. */
        data object Locating : State

        /**
         * Located, with the near-me set as its two tiers (SPEC *Finding stops → Near me now*):
         * [eager] — the nearest [NearbySelection.CLUSTERS_PER_MODE] clusters of each mode, fetched
         * and shown at once — and [more] — the farther clusters, distance-ordered, not fetched:
         * what the farther bus cards draw on, and places the rider may be at. Both are carried (not
         * just eager) so the retained departures view can key on the whole nearby set and reconcile
         * the tiers across a relocation, rather than being rebuilt whenever the eager set's
         * identity shifts.
         *
         * [distanceMeters] (`stopId` → meters from the fix) spans **both** tiers, so a stop an opened
         * card brings in is collapsed and ordered the same way an eager one is (a line served by
         * several adjacent stops shows once, from its nearest). It stays in memory for that render
         * and never reaches a log or the persisted snapshot (SPEC *Privacy*).
         *
         * [location] is the exact fix these stops and distances were resolved from, kept in
         * memory alongside them so the **consent-gated bug report** can file the coordinate and
         * the distances from one and the same fix (they would otherwise disagree if it re-fetched
         * a fresh position at report time). A trip from here also plans from it, sending it to
         * TfL's Journey Planner as the trip's start (SPEC *Trips with a change*). Like
         * [distanceMeters] it never reaches a log or the persisted snapshot; otherwise it leaves the
         * device only inside a report the user has explicitly consented to share (SPEC *Privacy*).
         */
        data class Ready(
            val eager: List<NearbySelection.NearbyCluster>,
            val more: List<NearbySelection.NearbyCluster>,
            val distanceMeters: Map<String, Double>,
            val location: Coordinates,
            // The hidden modes and lines these stops were picked without ([shownAgainSincePick]).
            val pickedHidden: Set<String> = emptySet(),
        ) : State {
            // The values below are worked out once, as the set is made (on the view model's worker,
            // [resolveFrom]), never on each read: the screen reads them in composition.

            /** The eager tier flattened to the stops shown and fetched at once. */
            val eagerStops: List<StopRef> = eager.flatMap { c -> c.stops.map { it.toStopRef() } }

            /** [eagerStops]' ids: what the widget and the watch show ([widgetNearbySet]). */
            val eagerStopIds: Set<String> = eagerStops.mapTo(HashSet()) { it.id }

            /**
             * Every resolved nearby stop, both tiers — what [distanceMeters] spans. The bug report
             * uses this rather than just [eagerStops] so a report still carries the farther stops
             * the list's farther cards offer (SPEC *Finding stops*).
             */
            val nearbyStops: List<StopRef> = (eager + more).flatMap { c -> c.stops.map { it.toStopRef() } }

            /**
             * Each line's stop nearest the rider within walking reach, from both tiers' stop data, so a
             * near station whose times aren't fetched yet still counts: what a line's map keeps on the
             * page ([NearestByLine.byLine]).
             */
            val nearestStopByLine: Map<String, String> = NearestByLine.byLine((eager + more).flatMap { it.stops }, distanceMeters)

            /**
             * Order-independent identity of the WHOLE nearby set (both tiers), so a relocation that
             * only reorders the same clusters — or shifts one across the eager/more boundary while
             * every cluster stays in range — is recognized as the same set and reconciles the
             * retained departures view in place, rather than rebuilding it.
             */
            val clusterSetKey: String = (eager + more).map { it.key }.sorted().joinToString(",")
        }

        /** Permission held but no position available (location off, or no fix yet) — so, unlike
         *  [Empty] and [Failed], there is no fix to carry for a bug report. */
        data object NoLocation : State

        /**
         * The nearby lookup reached TfL and failed — shown by kind, like the departures error.
         * [location] is the fix the failed lookup was made with, retained (in memory) so a bug
         * report sent from this gate can carry where the failure happened — the context a
         * "can't reach TfL" report needs (SPEC *Privacy*: consent-gated report only).
         */
        data class Failed(val kind: DeparturesUiState.Error.Kind, val location: Coordinates) : State

        /**
         * Located successfully, but TfL returned no stops within [radiusMeters]. [location] is the
         * fix that found nothing nearby, retained (in memory) so a bug report from this gate can
         * carry where "no stops nearby" was reported (SPEC *Privacy*: consent-gated report only).
         */
        data class Empty(val location: Coordinates) : State
    }

    private val _state = MutableStateFlow<State>(State.PermissionRequired)
    val state: StateFlow<State> = _state.asStateFlow()

    // True while a background [relocate] is resolving (the departures screen stays up), so the
    // caller can keep its refresh indicator on for the whole re-locate. Without this a slow fix
    // would let pull-to-refresh retract at once and the retained rows read as current even
    // though the set may still change (SPEC principle 2). [locate] doesn't set it — it shows the
    // Locating gate instead.
    private val _relocating = MutableStateFlow(false)
    val relocating: StateFlow<Boolean> = _relocating.asStateFlow()

    // Whether the shown nearby set is backed by a low-confidence location, and why — for the
    // departures view's banner (SPEC *Finding stops*, principle 2: don't present a stale/previous
    // position as current). [LocationBanner.APPROXIMATE] when the set was resolved from a last-known
    // fallback fix (a fresh fix failed — the Underground no-signal case); [LocationBanner.UPDATE_FAILED]
    // when a re-locate couldn't get a fresh fix and kept the current set rather than jumping to a
    // stale one. Null when the fix is fresh (or the gate is showing, which speaks for itself).
    private val _locationBanner = MutableStateFlow<LocationBanner?>(null)
    val locationBanner: StateFlow<LocationBanner?> = _locationBanner.asStateFlow()

    /**
     * Where the rider is, and whether that is known closely enough to act on, for the shown set:
     * [from] is the location the set was resolved from ([State.Ready.location]); [at] is the rider's
     * best position for it — the fix itself, or the precise fix that later confirmed a coarse set in
     * place without moving it; [accurate] is the fix's own confidence
     * ([FavoriteShortcuts.isAccurate] on its reported accuracy; a confirming precise fix counts).
     *
     * A consumer that must not act on a rough position — the favorite chips' "already there" test —
     * reads this rather than inferring precision from the absence of a [locationBanner]: an
     * approximate-only grant is always rough yet shows no banner (Codex). It is replaced in the same
     * step as the state it describes, so a new attempt in flight leaves the old set with its own fix.
     * In memory only, never logged or persisted (SPEC *Privacy*).
     */
    data class RiderFix(val from: Coordinates, val at: Coordinates, val accurate: Boolean)

    private val _riderFix = MutableStateFlow<RiderFix?>(null)
    val riderFix: StateFlow<RiderFix?> = _riderFix.asStateFlow()

    private fun riderFixFor(next: State, fix: LocationFix): RiderFix? =
        shownLocation(next)?.let { RiderFix(it, fix.coordinates, FavoriteShortcuts.isAccurate(fix.accuracyMeters)) }

    /**
     * A finished re-pick of a shown set ([relocate] or [refilter]): the set shown [before] it and
     * the one after (the same one when a re-locate kept it). A view that shows part of the set (the
     * To… trip from here) compares the two to decide whether its own part is unchanged and wants a
     * refresh. Held here, not in the view, so the outcome survives a configuration change that
     * lands mid-re-pick. [id] is unique per re-pick, for a view to remember which it has handled.
     */
    data class Repick(val id: Long, val before: State.Ready, val after: State.Ready)

    private val _repicked = MutableStateFlow<Repick?>(null)
    val repicked: StateFlow<Repick?> = _repicked.asStateFlow()

    private fun repicked(before: State, after: State) {
        if (before is State.Ready && after is State.Ready) {
            _repicked.value = Repick(maxOf(System.nanoTime(), (_repicked.value?.id ?: 0) + 1), before, after)
        }
    }

    /**
     * A precise fix that arrived after the shown set was resolved from a coarse one, and is far
     * enough from it to move the set ([FixRefinement]). The departures view applies it through
     * [applyRefinement], the same cancel-then-re-pick path a refresh takes, so a stale fetch can't
     * re-stamp the old set. [from] is the coarse fix it replaces; [id] is unique per refinement.
     */
    data class Refinement(
        val id: Long,
        val from: Coordinates,
        val precise: Coordinates,
        // The precise fix's own reported accuracy, carried to the fix the move re-resolves from.
        val preciseAccuracyMeters: Float? = null,
    )

    private val _refinement = MutableStateFlow<Refinement?>(null)
    val refinement: StateFlow<Refinement?> = _refinement.asStateFlow()

    // The follow-up request for a precise fix after a coarse one, canceled by any new locate or
    // relocate (whose own fix supersedes it). A refilter keeps the same fix, so it leaves it running.
    private var refineJob: Job? = null

    // The coarse fix the shown outcome (a list or "no stops nearby") was resolved from, while it
    // still awaits a precise fix: the one record of "owed a follow-up", independent of the banner
    // (an empty outcome has none) and of whether a request is running (it may be paused).
    private var refineFrom: Coordinates? = null

    // Whether the nearby surface is on screen and started. The follow-up asks for a GPS fix only
    // while it is (foreground-only location); one owed while it isn't waits for [resumeRefining].
    // Inactive until the surface first composes: a locate can run with an overlay restored on top.
    private var surfaceActive = false

    // Following the rider's moves while the surface is active ([followMoves]); null while it isn't.
    private var followJob: Job? = null

    // When the shown set was found from a fix (on [elapsedMillis]), so a move follows at most once a
    // minute ([MoveFollow.MIN_GAP_MILLIS]). A refilter keeps the same fix, so it keeps this too.
    private var foundAtMillis = 0L

    // The in-flight resolve, canceled before a new one starts so a superseded lookup can't
    // finish last and overwrite the newer result (e.g. a quick double-tap on Try again).
    private var locateJob: Job? = null

    // The relocation that was under way when the app last left the foreground ([leftForeground]).
    private var leftBehind: Job? = null

    // Whether [leftBehind] has since ended without a set to show ([locateAfterLeftBehind]).
    private var leftBehindLost = false

    /**
     * The app left the foreground. A relocation still under way was started from where the rider was
     * then, so a return mustn't wait on it ([relocatingSinceLeft]): the return re-locates, and its
     * fresh fix supersedes the older one (Codex on #220). It's left to run meanwhile, since the
     * activity also stops for a rotation, which has no return to replace it.
     */
    fun leftForeground() {
        leftBehind = locateJob?.takeIf { _relocating.value }
        leftBehindLost = false
    }

    /**
     * The app came back to the foreground with no set shown because the relocation left running as it
     * went ([leftForeground]) ended without one: no fix (likely, with the app away), a failed lookup,
     * or nothing nearby. That was from where the rider was then, and a gate has no set for a return
     * to re-locate, so locate afresh, with the gate, forcing a fresh fix (Codex on #390). True when it
     * did; false when there's nothing owed, and the gate stands as it is.
     */
    fun locateAfterLeftBehind(): Boolean {
        val owed = leftBehindLost && _state.value !is State.Ready
        leftBehindLost = false
        if (owed) locateWith(forceFresh = true)
        return owed
    }

    /**
     * Whether a relocation a return to the foreground can wait on is under way: one started since
     * the app last left ([leftForeground]). One started before then is from where the rider was when
     * they left, and its result would be shown as current after they may have moved.
     */
    fun relocatingSinceLeft(): Boolean = _relocating.value && locateJob !== leftBehind

    /**
     * Resolve the nearby stops. Call once the location permission is held (on open if
     * already granted, or straight after the user grants it), and again for a retry. Safe to
     * call repeatedly — each call cancels any in-flight resolve and supersedes the last state.
     */
    fun locate() = locateWith(forceFresh = false)

    private fun locateWith(forceFresh: Boolean) {
        locateJob?.cancel()
        stopRefining()
        // The Locating gate is shown instead of an in-place refresh, so clear the re-locate
        // indicator (a relocate this supersedes must not leave it stuck on).
        _relocating.value = false
        _state.value = State.Locating
        locateJob = viewModelScope.launch {
            val fix = currentFix(forceFresh = forceFresh)
            if (fix == null) {
                _state.value = State.NoLocation
                _locationBanner.value = null
                _riderFix.value = null
                return@launch
            }
            val next = resolveFrom(fix.coordinates)
            _state.value = next
            foundAtMillis = elapsedMillis()
            // The rider's fix for the new outcome, replaced as the outcome is applied — not when the
            // attempt starts: the old set stays on screen meanwhile and still stands on its own (Codex).
            _riderFix.value = riderFixFor(next, fix)
            // Label a set shown from a low-confidence (last-known fallback) fix as approximate, and
            // one from a coarse fix as coarse while a precise one is asked for; clear otherwise (a
            // fresh fix, or a gate/error state that speaks for itself).
            _locationBanner.value = bannerFor(next, fix)
            if (fix.isCoarse && !fix.isFallback) shownLocation(next)?.let(::refine)
        }
    }

    /**
     * Re-resolve the nearby set for a user-initiated refresh from the departures view (SPEC
     * *Finding stops* — a refresh re-locates). It is [locate] **without the intermediate
     * [State.Locating]**: the departures screen stays up during the (usually instant) resolve
     * instead of flashing back to the gate spinner on every pull-to-refresh.
     *
     * The departures refresh is **sequenced after** the fix, not run in parallel with it, so a
     * refresh never fetches (and stamps a fresh snapshot for) the *previous* location's stops
     * while the user has moved on — which the widget would then present as current. The outcome
     * decides who fetches:
     * - **same set** (the fix confirms the stops already shown): the per-set departures
     *   ViewModel isn't recreated, so [onSameSet] refreshes it in place — the one path that
     *   re-fetches the on-screen set, and only once the fix has confirmed it.
     * - **a new set** (walked to the next station): emitted as [State.Ready]; the caller swaps
     *   in a fresh per-set ViewModel that fetches on its own init, so the old set is never
     *   re-fetched here.
     * - **a low-confidence fix over a shown set** (the Underground case — a fresh fix failed):
     *   don't jump to the stale position; keep the set already shown and flag it
     *   [LocationBanner.UPDATE_FAILED]. This is the same-set path for [onSameSet] — the caller
     *   canceled the retained set's fetch before re-locating, so it is restarted in place rather
     *   than left hung on its spinner.
     * - **[State.Empty] / [State.NoLocation] / [State.Failed]**: propagated honestly (SPEC
     *   principles 1–2) — the gate replaces the list rather than leaving a stale set on screen
     *   (cards omit the stop name, so a stale set is indistinguishable from the real one).
     */
    fun relocate(onSameSet: (State.Ready) -> Unit = {}) =
        // Force a fresh fix: the user may have walked since the last one, and a cached fix
        // would re-resolve for the previous position (see LocationProvider.current).
        relocateWith(onSameSet) { currentFix(forceFresh = true) }

    /**
     * Moves the shown set to [refinement]'s precise fix, as a [relocate] with that fix in hand, so
     * the caller's cancel-then-reconcile discipline is the same. Does nothing when the set shown
     * is no longer the one [refinement] was worked out for (a relocate or locate got there first).
     */
    fun applyRefinement(refinement: Refinement, onSameSet: (State.Ready) -> Unit = {}) {
        // Only the refinement still on offer: one a locate, relocate or pause has since withdrawn is
        // superseded, even while the old set is still shown (a relocation in flight hasn't replaced
        // it yet), and applying it would cancel the newer relocation.
        val current = _refinement.value?.id == refinement.id
        if (current) _refinement.value = null
        val shown = _state.value
        if (!current || shownLocation(shown) != refinement.from) {
            // Nothing to move, but the caller canceled the shown set's fetch first: restart it.
            if (shown is State.Ready) onSameSet(shown)
            return
        }
        relocateWith(onSameSet) {
            LocationFix(refinement.precise, isFallback = false, accuracyMeters = refinement.preciseAccuracyMeters)
        }
    }

    private fun relocateWith(onSameSet: (State.Ready) -> Unit, fixFor: suspend () -> LocationFix?) {
        locateJob?.cancel()
        stopRefining()
        val current = _state.value
        val job = viewModelScope.launch {
            val fix = fixFor()
            when {
                fix == null -> {
                    _state.value = State.NoLocation
                    _locationBanner.value = null
                    _riderFix.value = null
                }
                // Don't jump (SPEC *Finding stops*): a re-locate that could only get a low-confidence
                // last-known fix keeps the set already shown rather than re-resolving to a stale/
                // previous position (the Underground case, where the wrong-station stops would look
                // current). The banner says the location didn't update; the set and its state are
                // left as they were.
                fix.isFallback && current is State.Ready -> {
                    _locationBanner.value = LocationBanner.UPDATE_FAILED
                    repicked(current, current)
                    // A re-locate arrives with the retained set's in-flight fetch already canceled
                    // (the caller cancels before re-locating, so a superseded fetch can't stamp the
                    // old set's departures). We're keeping that set, so restart its fetch in place —
                    // otherwise a fetch canceled mid-load never resumes and the departures screen
                    // hangs on its spinner (which would also hide this banner, shown only once loaded).
                    onSameSet(current)
                }
                else -> {
                    val next = resolveFrom(fix.coordinates)
                    // Always emit the fresh outcome. Even when the cluster set is unchanged, the fresh
                    // fix may have moved within it, so the per-stop distances differ — and the list
                    // uses distanceMeters to pick which adjacent stop represents each line and to
                    // order rows (SPEC *Finding stops → Near me now*), so the old distances would
                    // dedupe/sort by the previous position. Emitting a same-set Ready does not
                    // recreate the departures ViewModel (its store is keyed on the whole cluster
                    // set), so this is a plain recompose plus an in-place reconcile, not a rebuild.
                    _state.value = next
                    foundAtMillis = elapsedMillis()
                    // Replaced with this fix's outcome, as in [locate].
                    _riderFix.value = riderFixFor(next, fix)
                    _locationBanner.value = bannerFor(next, fix)
                    repicked(current, next)
                    if (fix.isCoarse && !fix.isFallback) shownLocation(next)?.let(::refine)
                    if (
                        next is State.Ready && current is State.Ready &&
                        next.clusterSetKey == current.clusterSetKey
                    ) {
                        // Same nearby set (both tiers, order-independent): reconcile the retained
                        // departures ViewModel in place — update its tiers and re-fetch — sequenced
                        // after the fix (never fetched in parallel with it).
                        onSameSet(next)
                    }
                }
            }
            // Left running as the app went, and ended without a set: a return locates afresh.
            if (coroutineContext[Job] === leftBehind && _state.value !is State.Ready) leftBehindLost = true
        }
        locateJob = job
        _relocating.value = true
        // Clear only when this is still the active resolve: a newer relocate keeps the indicator
        // on (it owns it now), and a locate() that supersedes this already cleared it.
        job.invokeOnCompletion { if (locateJob === job) _relocating.value = false }
    }

    /**
     * Whether something the shown set was picked without has been shown again since ([hidden] is the
     * hidden set now), and nothing under way will pick it up: a re-locate or re-pick in flight reads
     * the hidden set as it gets there. A page that didn't show it again itself — Settings' Hidden list
     * did, while the page was out of view — re-picks ([refilter]) when this is true, so its stops come
     * back as "Show all" brings them (SPEC *Finding stops → Hiding a mode*). False with no set shown.
     */
    fun shownAgainSincePick(hidden: Set<String>): Boolean {
        if (locateJob?.isActive == true) return false
        val picked = (_state.value as? State.Ready)?.pickedHidden ?: return false
        return picked.any { !HiddenModes.isHidden(it, hidden) }
    }

    /**
     * Re-picks the nearby set from the fix already shown, after the hidden modes changed: showing a
     * mode again brings its stops back, hiding one drops the stops that served only it. No new fix,
     * and the lookup is a cache hit, so it costs no request; the departures for any newly picked
     * stop are fetched as for a relocation. When the re-pick keeps the same places (a mixed place
     * whose lines or tier changed), [onSameSet] reconciles the retained departures, as [relocate]'s
     * does. Does nothing while no set is shown.
     */
    fun refilter(onSameSet: (State.Ready) -> Unit = {}) {
        val shown = _state.value
        val fix = when (shown) {
            is State.Ready -> shown.location
            is State.Empty -> shown.location
            else -> return
        }
        locateJob?.cancel()
        // Supersedes any relocation in flight, whose completion no longer owns the indicator (it
        // clears only while it's the active resolve), so clear it here, as [locate] does.
        _relocating.value = false
        locateJob = viewModelScope.launch {
            val next = resolveFrom(fix)
            _state.value = next
            repicked(shown, next)
            if (next is State.Ready && shown is State.Ready && next.clusterSetKey == shown.clusterSetKey) onSameSet(next)
        }
    }

    private fun bannerFor(next: State, fix: LocationFix): LocationBanner? = when {
        // "No stops nearby" from a coarse fix is flagged too: the fix may be what missed them.
        next is State.Empty && fix.isCoarse && !fix.isFallback -> LocationBanner.COARSE
        // And from a last-known fix, as the list is: a trip from here plans from it, so it says the
        // position may be out of date, and the place chips don't take it as where the rider is (Codex
        // on #439). The gate itself names only a coarse fix, which a precise one can still correct.
        next is State.Empty && fix.isFallback -> LocationBanner.APPROXIMATE
        next !is State.Ready -> null
        fix.isFallback -> LocationBanner.APPROXIMATE
        fix.isCoarse -> LocationBanner.COARSE
        else -> null
    }

    /**
     * The fix a located outcome was resolved from: a list ([State.Ready]) or "no stops nearby"
     * ([State.Empty]), either of which a coarse fix can get wrong and a precise one can correct.
     */
    private fun shownLocation(state: State): Coordinates? = when (state) {
        is State.Ready -> state.location
        is State.Empty -> state.location
        else -> null
    }

    /**
     * The nearby surface left the screen (an overlay replaced it, or the app went to the
     * background): stop asking for a precise fix, and don't start one until [resumeRefining], so
     * the GPS request never outlives the surface (SPEC *Finding stops*, foreground-only location).
     * That includes a locate still in flight, whose coarse outcome then only records the debt. A
     * refinement already offered but not yet applied is dropped back to a debt too: the rider may
     * travel while away, so a precise fix from before is not one to move to on return (a return
     * from the background re-locates anyway; any other return asks GPS afresh).
     */
    fun pauseRefining() {
        surfaceActive = false
        refineJob?.cancel()
        refineJob = null
        followJob?.cancel()
        followJob = null
        _refinement.value?.let { offered ->
            _refinement.value = null
            refineFrom = offered.from
        }
    }

    /**
     * The nearby surface is back on screen: ask for the precise fix the shown outcome is still owed,
     * if any, unless one is already being asked for or has been offered.
     */
    fun resumeRefining() {
        surfaceActive = true
        if (followJob?.isActive != true) followJob = followMoves()
        if (refineJob?.isActive == true || _refinement.value != null) return
        val from = refineFrom ?: return
        if (shownLocation(_state.value) == from) refine(from)
    }

    /**
     * Follows the rider while the list is on screen (SPEC *Finding stops*): a location update far
     * enough from where the shown list was found, sure enough and at most once a minute
     * ([MoveFollow]), is offered as a [Refinement], which the departures view applies as it does a
     * precise follow-up, through the same cancel-then-re-pick path a refresh takes. Nothing is
     * offered while a resolve is under way (its own fix supersedes), while a precise follow-up is
     * owed (it is asking already), or while a move is on offer but not yet applied.
     *
     * Updates are asked for only while a list is shown: a failure or "no location" has nothing to
     * move, so the GPS request would cost battery for nothing. Asking afresh each time a list
     * appears also picks up a permission granted since the last ask, which a request that closed
     * for want of it never would. A fix that came within the minute waits it out; a newer sure fix
     * replaces it, or cancels it if the rider is back near the list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun followMoves(): Job = viewModelScope.launch {
        try {
            _state.map { shownLocation(it) != null }
                .distinctUntilChanged()
                .flatMapLatest { shown -> if (shown) moves() else emptyFlow() }
                // Each one in the bug report's recent-positions window (memory only, never the
                // log), so a report about the list not following shows what it was given.
                .onEach { fix -> position(movementLabel(fix), fix.coordinates) }
                // Only a sure fix replaces one waiting out the minute: a far one takes its place, a
                // near one (the rider came back) cancels it, and an unsure one leaves it be.
                .filter(MoveFollow::isSure)
                .collectLatest { fix ->
                    // Its age runs from here, through every wait below (Codex, #485).
                    val arrived = elapsedMillis()
                    // Busy with a resolve, or with a move on offer: the newest sure fix waits it out
                    // and is judged against what it leaves, rather than being dropped. The request
                    // has already counted it toward its delivery distance, so a rider who stops
                    // might send no other (Codex, #485).
                    awaitNotBusy()
                    // Past the freshness bound after waiting, it no longer says where the rider is
                    // now, so a fresh precise fix is asked for in its place: a rider who stopped
                    // sends no update of their own.
                    var current = fix.agedSince(arrived).takeIf(MoveFollow::isSure)
                        ?: freshFix() ?: return@collectLatest
                    // A precise follow-up owed to the shown set is answered by a sure update, as its
                    // own fix would have answered it: one still asking is cancelled (this fix is as
                    // good, and a replayed one may never come again), and one that gave up no longer
                    // shuts updates out.
                    val owed = refineFrom
                    if (owed != null && shownLocation(_state.value) == owed) {
                        refineJob?.cancel()
                        refineJob = null
                        answerRefinement(owed, current)
                        return@collectLatest
                    }
                    val waitFrom = elapsedMillis()
                    val wait = followAfter(current) ?: return@collectLatest
                    if (wait > 0) {
                        delay(wait)
                        current = current.agedSince(waitFrom).takeIf(MoveFollow::isSure)
                            ?: freshFix() ?: return@collectLatest
                    }
                    // Again after the wait: a pull or a resolve may have moved the list meanwhile.
                    if (followAfter(current) != 0L) return@collectLatest
                    offerMove(current)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Updates that failed to start leave the list where it is; a pull still moves it.
            warn("location updates failed: ${e::class.simpleName}")
        }
    }

    // [this] as it stands now, having waited since [sinceMillis] on [elapsedMillis].
    private fun LocationFix.agedSince(sinceMillis: Long): LocationFix =
        copy(ageMillis = (ageMillis ?: 0) + (elapsedMillis() - sinceMillis))

    /** A fresh precise fix for a move that waited too long on its own, or null with none. */
    private suspend fun freshFix(): LocationFix? = try {
        withContext(io) { location.preciseWithAccuracy() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The move waits for the next update instead; following carries on.
        warn("location for a move failed: ${e::class.simpleName}")
        null
    }

    // How a movement update reads in the recent-positions window: no coordinate (that's alongside).
    private fun movementLabel(fix: LocationFix): String =
        "movement update" + (fix.accuracyMeters?.let { " ±${it.roundToLong()} m" } ?: "")

    /** Returns once no resolve is under way and no move is on offer. */
    private suspend fun awaitNotBusy() {
        while (true) {
            val resolving = locateJob
            if (resolving?.isActive == true) {
                resolving.join()
                continue
            }
            if (_refinement.value != null) {
                _refinement.first { it == null }
                continue
            }
            return
        }
    }

    /** [MoveFollow.followAfterMillis] for the list shown now, or null with none or one busy. */
    private fun followAfter(fix: LocationFix): Long? {
        val shownFrom = shownLocation(_state.value) ?: return null
        if (locateJob?.isActive == true || refineFrom != null || _refinement.value != null) return null
        return MoveFollow.followAfterMillis(shownFrom, fix, elapsedMillis() - foundAtMillis)
    }

    private fun offerMove(fix: LocationFix) {
        val shownFrom = shownLocation(_state.value) ?: return
        // Coarse facts only, never a coordinate (SPEC Privacy).
        val moved = NearestStops.distanceMeters(shownFrom.latitude, shownFrom.longitude, fix.coordinates.latitude, fix.coordinates.longitude)
        warn("following a move: ${moved.roundToLong()} m from where the list was found")
        _refinement.value = Refinement(
            maxOf(System.nanoTime(), (_refinement.value?.id ?: 0) + 1),
            from = shownFrom,
            precise = fix.coordinates,
            preciseAccuracyMeters = fix.accuracyMeters,
        )
    }

    private fun stopRefining() {
        refineJob?.cancel()
        refineJob = null
        refineFrom = null
        _refinement.value = null
    }

    /**
     * Asks for a precise fix after the set (or "no stops nearby") was shown from the coarse fix
     * [shownFrom] (SPEC *Finding stops*). If one arrives while that outcome is still shown: close enough ([FixRefinement]) and it
     * confirms the set, so the banner clears; farther and it's offered as a [Refinement] for the
     * departures view to move the set to. None in time and the banner stays, with its Try again.
     */
    private fun refine(shownFrom: Coordinates) {
        refineFrom = shownFrom
        refineJob?.cancel()
        refineJob = null
        if (!surfaceActive) return
        refineJob = viewModelScope.launch {
            val preciseFix = try {
                withContext(io) { location.preciseWithAccuracy() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No coordinate in the log (SPEC Privacy); the coarse set and its banner stay.
                warn("precise fix failed: ${e::class.simpleName}")
                null
            } ?: return@launch
            if (shownLocation(_state.value) != shownFrom) return@launch
            answerRefinement(shownFrom, preciseFix)
        }
    }

    /**
     * Settles the precise follow-up owed to the outcome shown from [shownFrom] with [preciseFix]:
     * confirmed in place (the coarse banner clears), or offered as a move. From the follow-up's own
     * fix, or from a sure movement update once that follow-up has given up (Codex, #485).
     */
    private fun answerRefinement(shownFrom: Coordinates, preciseFix: LocationFix) {
        val precise = preciseFix.coordinates
        // Answered either way: confirmed, or offered as a move (which relocates).
        refineFrom = null
        // An empty outcome has no list to confirm: any better fix is worth a new lookup, since
        // near the edge of the radius even a short move can bring a stop into range.
        val emptyShown = _state.value is State.Empty
        if (FixRefinement.shouldMove(shownFrom, precise) || (emptyShown && precise != shownFrom)) {
            _refinement.value = Refinement(
                maxOf(System.nanoTime(), (_refinement.value?.id ?: 0) + 1),
                from = shownFrom,
                precise = precise,
                preciseAccuracyMeters = preciseFix.accuracyMeters,
            )
        } else if (_locationBanner.value == LocationBanner.COARSE) {
            // Where the rider is, per the precise fix that confirmed the set in place — accurate
            // only on its own reported accuracy, as GPS can answer vaguely too (Codex).
            _riderFix.value = RiderFix(
                from = shownFrom,
                at = precise,
                accurate = FavoriteShortcuts.isAccurate(preciseFix.accuracyMeters),
            )
            _locationBanner.value = null
        }
    }

    /**
     * The device fix (with its [LocationFix.isFallback] confidence), or `null` — the "couldn't get
     * your location" outcome — with cancellation rethrown and any other failure logged coarsely
     * (never a coordinate, SPEC *Privacy*). Shared by [locate] and [relocate], which then decide
     * what a fallback fix means (label vs. don't-jump).
     */
    private suspend fun currentFix(forceFresh: Boolean): LocationFix? {
        val started = elapsedMillis()
        val fix = try {
            withContext(io) { location.current(forceFresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No coordinate in the log — only that a fix couldn't be obtained (SPEC Privacy).
            warn("location fix failed: ${e::class.simpleName}")
            null
        }
        val outcome = when {
            fix == null -> UsageEvent.FixOutcome.FAILED
            fix.isFallback -> UsageEvent.FixOutcome.LAST_KNOWN
            else -> UsageEvent.FixOutcome.FRESH
        }
        usage(UsageEvent.LocationFix(outcome, fix?.accuracyMeters, Duration.ofMillis(elapsedMillis() - started)))
        return fix
    }

    /**
     * Resolve a known coordinate [fix] to the nearby set, as one of the terminal [State]s
     * (Ready / Empty / Failed). Every failure is a distinct honest state, never an empty list
     * (SPEC principles 1–2), and [warn] carries only the coarse reason, never a coordinate.
     */
    private suspend fun resolveFrom(fix: Coordinates): State {
        val found = try {
            withContext(io) { finder.nearbyStops(fix.latitude, fix.longitude, radiusMeters) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // TflException.message is the coarse kind (offline / rate-limited / …), never
            // the coordinate that was queried (SPEC Privacy).
            warn("nearby stops lookup failed: ${(e as? TflException)?.message ?: e::class.simpleName}")
            return State.Failed(kindOf(e), location = fix)
        }
        val hidden = hiddenModes()
        // Every stop in range picked, measured and described on [compute], told back here.
        val picked = withContext(compute) { picked(found, hidden, fix) }
        usage(picked.usage)
        warn(picked.warning)
        position(picked.position, fix)
        return picked.state
    }

    // A lookup's outcome ([resolveFrom]): the set, with what it tells [usage], [warn] and [position].
    private class Picked(val state: State, val usage: UsageEvent, val warning: String, val position: String)

    // [found] worked over for [resolveFrom], on its worker: picked without [hidden], measured from
    // [fix], and described for the log and RecentPositions.
    @WorkerThread
    private fun picked(found: List<StopLocation>, hidden: Set<String>, fix: Coordinates): Picked {
        // How many of each mode are near, hidden ones included: what's around, not what's shown.
        val counted = UsageEvent.NearbyStops(found)
        // A hidden mode's stops aren't picked, so they cost no request — unless that would leave
        // nothing at all: then the full set is picked and the list, filtered by mode, says what's
        // hidden rather than claiming nothing runs nearby (SPEC principle 2).
        // A searched station's own stops stand where the rider is taken to be (From…), so they're
        // 0 m away for picking as well as for showing: placed at the fix before either.
        val placed = if (anchorStopIds.isEmpty()) {
            found
        } else {
            found.map { if (it.id in anchorStopIds) it.copy(latitude = fix.latitude, longitude = fix.longitude) else it }
        }
        val shown = HiddenModes.stops(placed, hidden)
        val result = NearbySelection.selectClusters(
            shown, fix.latitude, fix.longitude, outerRadiusMeters = radiusMeters,
        ).takeIf { it.eager.isNotEmpty() }
            ?: NearbySelection.selectClusters(placed, fix.latitude, fix.longitude, outerRadiusMeters = radiusMeters)
        // Eager empty means no stop with a route in range (each present mode contributes its
        // nearest; a route-less stop is never eager and has nothing to show) — nothing nearby runs.
        if (result.eager.isEmpty()) {
            val warning = "nearby: no stops in range (${found.size} found)"
            // Route-less stops still land in `more`: which ones, and how far, is what explains an
            // empty list, so they go with the position to RecentPositions (never the log).
            val routeless = result.more.flatMap { it.stops }
            val described = if (routeless.isEmpty()) {
                "nearby lookup (no stops)"
            } else {
                "nearby lookup (no stops with routes): " + routeless.joinToString {
                    val meters = if (it.id in anchorStopIds) {
                        0.0
                    } else {
                        NearestStops.distanceMeters(fix.latitude, fix.longitude, it.latitude, it.longitude)
                    }
                    "${it.id} ${meters.roundToLong()} m"
                } + ","
            }
            return Picked(State.Empty(location = fix), counted, warning, described)
        }
        // The anchors were moved only for picking: the set keeps their real positions (a stop's
        // map opens where it stands), and their distance is set to 0 below.
        val real = if (anchorStopIds.isEmpty()) emptyMap() else found.associateBy { it.id }
        fun restored(clusters: List<NearbySelection.NearbyCluster>) =
            if (real.isEmpty()) {
                clusters
            } else {
                clusters.map { c ->
                    // Only the position goes back: the picked copy's lines stay (a hidden mode's are off it).
                    c.copy(
                        stops = c.stops.map { picked ->
                            real[picked.id]?.let { picked.copy(latitude = it.latitude, longitude = it.longitude) } ?: picked
                        },
                    )
                }
            }
        val eager = restored(result.eager)
        val more = restored(result.more)
        // Distance per stop, over BOTH tiers (in memory only), so a stop an opened card brings in is
        // collapsed and ordered like an eager one — the departures list shows a line once, from its nearest stop
        // (SPEC *Finding stops → Near me now*). Never persisted or logged; kept in RecentPositions (below).
        val distances = (eager + more)
            .flatMap { it.stops }
            .associate {
                it.id to if (it.id in anchorStopIds) {
                    0.0
                } else {
                    NearestStops.distanceMeters(fix.latitude, fix.longitude, it.latitude, it.longitude)
                }
            }
        // Which stops a fix produced, and how far each is, go with its position to RecentPositions
        // only: several stop distances pin the position down (stops' positions are public), so the
        // persisted log gets the counts alone (maintainer, 2026-09-25).
        val eagerStops = eager.flatMap { it.stops }
        val moreStops = more.flatMap { it.stops }
        val warning = "nearby: ${eagerStops.size} stops (+${moreStops.size} more)"
        fun listed(stops: List<StopLocation>) =
            stops.joinToString { "${it.id} ${distances[it.id]?.roundToLong() ?: "?"} m" }
        val described = "nearby lookup: " + listed(eagerStops) +
            (if (moreStops.isEmpty()) "" else "; more: " + listed(moreStops)) + ","
        val ready = State.Ready(
            eager = eager,
            more = more,
            distanceMeters = distances,
            // The exact fix, retained in memory so the consent-gated bug report files the
            // coordinate and these distances from one and the same fix (SPEC *Privacy*).
            location = fix,
            pickedHidden = hidden,
        )
        return Picked(ready, counted, warning, described)
    }

    private fun kindOf(e: Throwable): DeparturesUiState.Error.Kind = errorKindOf(e)
}

/** The departures-view [StopRef] a nearby [StopLocation] maps to — the coordinate is dropped
 *  (it stays in the selection/distance math, never reaching the departures VM or a log). */
internal fun StopLocation.toStopRef() =
    StopRef(
        id = id, name = name, lines = lines, clusterId = clusterId, hubId = hubId,
        stopLetter = stopLetter, bearing = bearing, towards = towards,
    )

/**
 * The nearby stops the widget and the watch should show from the stored departures, for this state:
 * the stops fetched at once ([NearbyStopsViewModel.State.Ready.eagerStops], the set the departures
 * view saves), and none where the app has no stops for where the rider is: TfL found none, the lookup
 * failed, there's no fix, or location isn't allowed ([locationAllowed] false). Leaving the old set
 * there would show the place the rider may have left as live (Codex on #474). Null — leave the stored
 * set as it is — only while a locate is under way, or before the first one, when permission is held.
 */
internal fun NearbyStopsViewModel.State.widgetNearbySet(locationAllowed: Boolean): Set<String>? = when (this) {
    is NearbyStopsViewModel.State.Ready -> eagerStopIds
    is NearbyStopsViewModel.State.Empty,
    is NearbyStopsViewModel.State.Failed,
    NearbyStopsViewModel.State.NoLocation -> emptySet()
    // Also the state before the first locate, permission held or not: only no permission means no stops.
    NearbyStopsViewModel.State.PermissionRequired -> if (locationAllowed) null else emptySet()
    NearbyStopsViewModel.State.Locating -> null
}
