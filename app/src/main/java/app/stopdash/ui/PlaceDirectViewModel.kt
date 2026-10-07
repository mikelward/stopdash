package app.stopdash.ui

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.Countdown
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.NearbyStopsCache
import app.stopdash.domain.PlaceStops
import app.stopdash.domain.TflException
import app.stopdash.domain.PlaceDirect
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.Staleness
import app.stopdash.domain.StepFree
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TripModes
import app.stopdash.domain.Workers
import app.stopdash.domain.cleanDisruptionBody
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The *Direct* section atop a trip to a place (SPEC *Direct to a place*): the lines from the trip's
 * origin stops that go straight there ([PlaceDirect]). The stops a walk from the place are looked up
 * once ([ends], a [app.stopdash.domain.PlaceStopsFinder]); the origin stops' arrivals come from the
 * shared [arrivals] when recent, else from TfL, and are kept with the list's; each line's route is the
 * same data a trip's legs are checked against ([routes]). Works only while [run] runs, which the screen
 * does while it's shown (no background work); a stop's arrivals past [Staleness.THRESHOLD] are dropped,
 * never shown as live (SPEC D4).
 */
class PlaceDirectViewModel(
    // The place's stops within the given walk of it, in meters ([Inputs.walkMeters]).
    private val ends: suspend (Int) -> List<DirectTrips.End>,
    private val client: TflClient,
    private val routes: RouteStopsRepository,
    // The stations' step-free access (the bundled table), read once when first needed; null if unreadable.
    private val stepFreeAccess: suspend () -> StepFreeAccess? = { null },
    // The lifts out of service now (TfL's lift ids; the app passes its shared [LiftOutages], asked at most
    // every few minutes), taken off the step-free table before it's read, so a platform only a broken
    // lift reaches never counts as step-free.
    private val liftsOut: suspend () -> Set<String> = { emptySet() },
    // Stops' last closure lookups, shared with the other screens (the app passes [StopClosureCache.SHARED]).
    private val closureCache: StopClosureCache = StopClosureCache(),
    private val arrivals: ArrivalsCache = ArrivalsCache(),
    private val clock: () -> Instant = Instant::now,
    // Coarse facts only — a stop or line id, an error kind, never a coordinate (SPEC *Privacy*).
    private val warn: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Workers.compute,
) : ViewModel() {
    /** What the section shows. */
    sealed interface State {
        /** The place's stops or the first arrivals aren't in yet: "Checking…". */
        data object Checking : State

        /** The place's stops couldn't be looked up, or no origin stop's arrivals came back: "Couldn't check". */
        data object Failed : State

        /**
         * The [rows] going there, each with its countdown label worked out at the last tick, or none.
         * [uncertain] when some train or line couldn't be checked, said on the header; [retryable] when
         * that's for something a Retry may get (a stop's arrivals, a line's route, a status or closure
         * that failed to load), so with no rows it reads "Couldn't check" with a Retry, else "None".
         * [checking] while a route loads.
         */
        @Immutable
        data class Ready(val rows: List<ShownRow>, val checking: Boolean, val uncertain: Boolean, val retryable: Boolean = uncertain) : State
    }

    /**
     * A row as drawn: its line, the stop it leaves from, its countdowns ("3 · 8 · 13 min"), and the
     * line's [disruption] ("Severe Delays") when it isn't running a good service, and a stop's closure
     * [notice] ("Bond Street: Station closed"), each said under the row.
     */
    @Immutable
    // [endIds]: the stops near the place its trains reach, by id and station, worked out with the look so a tap
    // opening its ride ([TripViewModel.openDirect]) only reads them; [endKey] the same as one string, so a row
    // that reaches others since a failed tap isn't said to have failed ([TripViewModel.DirectOpening.endKey]).
    data class ShownRow(
        val row: PlaceDirect.Row,
        val times: String,
        val disruption: String? = null,
        val notice: String? = null,
        val endIds: Set<String> = emptySet(),
        val endKey: String = "",
    )

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * What a look works from, as the trip has it: its origin stops and their distances from the rider
     * (a line shows from its nearest stop), the modes and lines hidden, the lines avoided, the step-free
     * level a line's stops must meet, and the kinds of transport the trip rides.
     */
    class Inputs(
        val origin: List<StopRef> = emptyList(),
        val distanceMeters: Map<String, Double> = emptyMap(),
        val hidden: Set<String> = emptySet(),
        val avoided: Set<String> = emptySet(),
        val stepFree: StepFree = StepFree.ANY,
        val tripModes: TripModes = TripModes.DEFAULT,
        // How far from the place a stop may be, by the rider's max walk and pace ([PlaceStops.walkMeters]).
        val walkMeters: Int = PlaceStops.WALK_METERS,
        // Whether the rider's stored choices above are read: until then nothing is looked up, so the
        // defaults never stand in for them (rows with stairs, an avoided line), as the routes wait.
        val loaded: Boolean = true,
    ) {
        // By identity for the collections: the screen hands the same ones until they change, and a
        // comparison of their contents would be work on the main thread that grows with them.
        fun sameAs(other: Inputs) = origin === other.origin && distanceMeters === other.distanceMeters && sameChoice(other)

        /** Whether the rider's choices are [other]'s, whatever the stops: a change to any rules rows out. */
        fun sameChoice(other: Inputs) = hidden === other.hidden && avoided === other.avoided && stepFree == other.stepFree &&
            tripModes === other.tripModes && walkMeters == other.walkMeters && loaded == other.loaded
    }

    // The stops the last published look took, with the inputs it took them under: one at the edge of the trip's reach
    // stays while the rider's fix wavers ([steadyOrigin]). Written only in a look; read by a prune.
    @Volatile
    private var heldOrigin: Pair<Inputs, List<StopRef>>? = null

    @Volatile
    private var inputs = Inputs()

    // The place's stops, once looked up; the stations' step-free table, once read. Only touched in a look.
    // The step-free table with the last lifts out taken off, and which lifts those were.
    private var liftedFor: Set<String>? = null
    private var lifted: StepFreeAccess? = null

    private fun withLifts(base: StepFreeAccess?, out: Set<String>): StepFreeAccess? {
        if (base == null || out.isEmpty()) return base
        if (out != liftedFor) {
            lifted = base.withLiftsOut(out)
            liftedFor = out
        }
        return lifted
    }

    // The place's stops as looked up for each walk (meters around it), with when: kept per walk, so going
    // back to a walk tried before reuses its answer rather than ask TfL again. Only touched in a look.
    private val placeEnds = HashMap<Int, Pair<List<DirectTrips.End>, Instant>>()
    private var access: StepFreeAccess? = null

    // One look at a time, so a look started for a change never lands under an older one's.
    private val looking = Mutex()

    /**
     * The trip's [Inputs] as the screen composes: a change looks again at once, rather than leave rows
     * from stops no longer the trip's, or that the new choice rules out, until the next tick.
     */
    fun setInputs(next: Inputs) {
        val before = inputs
        if (next.sameAs(before)) return
        val choiceChanged = !next.sameChoice(before)
        // With the publish lock, so a look about to publish under the old inputs sees the new ones and
        // stands down, rather than land after this and put its rows back ([publish]).
        synchronized(publishing) {
            inputs = next
            // Rows found under the old choice may break the new one (a line now avoided, stairs now
            // ruled out): none shows until a look under it lands.
            if (choiceChanged) _state.value = State.Checking
        }
        if (!choiceChanged) {
            // Only the trip's stops moved: their own rows stay, those from stops no longer the trip's go
            // at once, on the worker, ahead of the look that finds the new stops' trains.
            viewModelScope.launch(compute) { withoutStopsGone(next) }
        }
        viewModelScope.launch { refresh() }
    }

    private fun withoutStopsGone(next: Inputs) {
        val shown = _state.value as? State.Ready ?: return
        val kept = steadyOrigin(next, heldOrigin).mapTo(HashSet()) { it.id }
        val rows = shown.rows.filter { it.row.fromId in kept }
        // None left: the new stops' trains aren't in yet, so it's Checking, never a "None" no look has found.
        val pruned = if (rows.isEmpty()) State.Checking else shown.copy(rows = rows)
        if (rows.size != shown.rows.size) publish(next) { _state.compareAndSet(shown, pruned) }
    }

    // Guards an input change against a look's publish: the check that the look's inputs are still the
    // trip's and its write of the state happen as one.
    private val publishing = Any()

    // Runs [write] only if [chosen] are still the trip's inputs, atomically with [setInputs].
    private inline fun publish(chosen: Inputs, write: () -> Unit) {
        synchronized(publishing) { if (inputs === chosen) write() }
    }

    /** [Inputs] for a test that runs its looks itself: no look is started. */
    @VisibleForTesting
    internal fun setInputsQuietly(next: Inputs) {
        inputs = next
    }

    private val _pulling = MutableStateFlow(false)

    /** Whether a pull's look is under way, for the trip's pull indicator to hold for. */
    val pulling: StateFlow<Boolean> = _pulling.asStateFlow()

    /** A pull on the trip: looks again now, every stop asked afresh, the indicator held until it lands. */
    fun pullRefresh() {
        _pulling.value = true
        viewModelScope.launch {
            try {
                refresh(pulled = true)
            } finally {
                _pulling.value = false
            }
        }
    }

    /** Refreshes while the screen shows it: arrivals at most every [ArrivalsCache.TTL], countdowns each [TICK]. */
    suspend fun run() {
        // Back on screen after a while: what was shown last may no longer stand, so it stands down before
        // the first look (which may wait on the network) rather than shown as it was.
        withContext(compute) { ageShown() }
        while (true) {
            refresh()
            delay(TICK.toMillis())
        }
    }

    // When the rows on show were published (by the steady clock): back after longer than a tick, they
    // stand down to "Checking…" until a look lands, as their trains, line statuses and stop notices may
    // all have moved on meanwhile; a moment away (a quick switch of app) keeps them.
    @Volatile
    private var shownAt: Instant? = null

    // The route misses last sent to the debug log; read and written only under [looking].
    // So is what the section last said to it ([look]).
    private var reportedVerdict = ""

    // The order the last published look's rows were put in, and under what inputs; also only under [looking].
    private var heldLines: Pair<Inputs, List<String>>? = null
    private var reportedMisses: Set<RouteMiss> = emptySet()

    /** The rows on show, put back to "Checking…" if they were published longer than a [TICK] ago. */
    internal fun ageShown() {
        val shown = _state.value as? State.Ready ?: return
        val at = shownAt
        val age = at?.let { SteadyClock.age(it, clock()) }
        if (age == null || age.isNegative || age > TICK) _state.compareAndSet(shown, State.Checking)
    }

    /** Looks again after a failure: the place's stops, if they were what failed, then the arrivals. */
    fun retry() {
        _state.value = State.Checking
        viewModelScope.launch { refresh() }
    }

    /** One look: the place's stops if not yet in, the origin stops' arrivals, then the rows, on [compute]. */
    internal suspend fun refresh(pulled: Boolean = false): Unit = withContext(compute) { looking.withLock { look(pulled) } }

    // [pulled]: every stop asked afresh, none answered from the shared arrivals, as a pull asks.
    private suspend fun look(pulled: Boolean) {
        if (!inputs.loaded) {
            _state.value = State.Checking
            return
        }
        // Held no longer than the lookup's own cache would hold them ([NearbyStopsCache.MAX_AGE]): a trip
        // left open past that asks the finder again, which answers from its cache or TfL as it would.
        // The rider's walk changed since, they're looked up again for the new one.
        val chosen = inputs
        val walk = chosen.walkMeters
        val heldEnds = placeEnds[walk]?.let { (held, at) ->
            val age = SteadyClock.age(at, clock())
            held.takeIf { !age.isNegative && age < NearbyStopsCache.MAX_AGE }
        }
        val ends = heldEnds ?: try {
            ends(walk).also {
                placeEnds[walk] = it to SteadyClock.stamp(clock())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("place stops lookup failed: ${e::class.simpleName}")
            _state.value = State.Failed
            return
        }
        // A stop the trip's reach just left by a wavering fix stays, so its rows don't come and go ([steadyOrigin]).
        // Held for the next look only if this one publishes ([publish] below), never one a change overtook.
        val stops = steadyOrigin(chosen, heldOrigin)
        // The trip's modes turned off count as hidden here, as they leave the routes.
        val modesOff = ModeGroups.ALL.filterNot(chosen.tripModes::rides).flatMap { it.modes }
        val hidden = AvoidedLines.excluded(chosen.hidden, chosen.avoided) + modesOff
        val level = chosen.stepFree
        if (level != StepFree.ANY && access == null) access = withContext(io) { stepFreeAccess() }
        // With a level to meet, the table as of the lifts out now: the same outages keep the same table.
        val table = if (level == StepFree.ANY) access else withLifts(access, liftsOut())
        // When asked: what a fetch is stamped with.
        val askedAt = clock()
        val fetched = coroutineScope { stops.map { stop -> async { arrivalsOf(stop, askedAt, pulled) } }.awaitAll() }
        // Every shown line's status asked for while its route loads, not after: either can take TfL a
        // couple of seconds, and a row waits on both. A line that turns out to go elsewhere costs only a
        // place in a batched status request.
        val departing = fetched.filterNotNull().flatMap { stop ->
            stop.departures.filterNot { HiddenModes.isHidden(it.mode, it.lineId, hidden) }.map { it.lineId }
        }.filter { it.isNotBlank() }.distinct()
        val (sequences, routesFailed) = coroutineScope {
            val early = async { statusesFor(departing, clock(), pulled) }
            sequencesFor(fetched.filterNotNull(), hidden).also { early.await() }
        }
        // The lines the rows would show, so their status is in hand before they do: a delayed or
        // part-suspended line is said on its row, never shown as running normally.
        val rowLines = PlaceDirect.rows(fetched.filterNotNull(), ends, sequences, chosen.distanceMeters, askedAt, hidden, level, table).rows
        // Each cache judged at the time it's read, not when the look began: arrivals and route loads may
        // have taken a while, and an answer at the edge of its reuse mustn't vouch past it. Those asked for
        // above are fresh, so this asks TfL again only for one that wasn't answered then.
        val held = statusesFor(rowLines.map { it.lineId }, clock(), false)
        // And their stops' closures: where each boards, and the stops near the place it gets off at.
        val closureIds = rowLines.flatMap { row -> listOf(row.fromId) + row.reaches.map { it.id } }.distinct()
        val closuresAt = clock()
        val closures = closureChecks.check(closureIds, closureCache.ask(closuresAt), closuresAt)
        // The rider changed a choice, or moved on, while this look waited: the look that change started
        // is queued behind this one and publishes for it, so this one's rows never stand in for them.
        if (inputs !== chosen) return
        // When shown: the fetches and route loads above may have taken a while, so ages, trains gone and
        // countdowns are worked out from now, never from when the look began.
        val now = clock()
        // As of today in London: planned work that has started since a status was fetched counts now,
        // as every departure surface takes it ([LineStatus.asOf]).
        val statuses = LineStatus.asOf(held, now)
        val fresh = fetched.mapNotNull { stop -> stop?.takeUnless { Staleness.isStale(it.fetchedAt, now) } }
        // The order and the debug-log verdict this look would show: kept only if it publishes, so a look a
        // change overtook never seeds the next one's order with rows the rider never saw.
        var heldNext: List<String>? = null
        var verdictNext: String? = null
        val next = if (ends.isNotEmpty() && stops.isNotEmpty() && fresh.isEmpty()) {
            State.Failed
        } else {
            val result = PlaceDirect.rows(fresh, ends, sequences, chosen.distanceMeters, now, hidden, level, table)
            // A departure whose route couldn't be told goes to the debug log, as the trip's own routes log
            // theirs: once per set, not again every tick.
            if (result.misses != reportedMisses) routes.reportMisses(result.misses)
            reportedMisses = result.misses
            // Worked out again at publish time, a row may board, or reach, a stop the closure check above
            // never asked about (a train gone since let a later one in): unasked counts as unchecked.
            val asked = closureIds.toSet()
            val unchecked = closures.failed + result.rows.flatMap { row -> listOf(row.fromId) + row.reaches.map { it.id } }.filterNot { it in asked }
            val unvouched = result.rows.filter { row ->
                row.lineId !in statuses || row.fromId in unchecked || unverifiedEnds(row, closures.found, unchecked, now)
            }
            val rowsUnvouched = unvouched.isNotEmpty()
            val unvouchedIds = unvouched.mapTo(HashSet()) { it.lineId }
            // The rows on show keep their places; a line newly found goes under them, never above. Held apart
            // from the state, so a stand-down to Checking (back after a while, a failure, a Retry) keeps it.
            val ordered = heldOrder(result.rows, heldLines?.takeIf { it.first.sameChoice(chosen) }?.second.orEmpty()) { it.lineId }
            heldNext = ordered.map { it.lineId }
            // What the section says, and why it says it couldn't check, to the debug log when either changes:
            // a row come or gone, or a caveat, is then traceable to the line and the reason. Ids only.
            val missing = stops.map { it.id } - fresh.mapTo(HashSet()) { it.stopId }
            val why = listOfNotNull(
                "train unplaced (${result.misses.map { it.lineId }.distinct().sorted().joinToString(",")})".takeIf { result.unresolved },
                "no arrivals (${missing.sorted().joinToString(",")})".takeIf { missing.isNotEmpty() },
                "unvouched (${ordered.filter { it.lineId in unvouchedIds }.joinToString(",") { it.lineId }})".takeIf { rowsUnvouched },
                "a route failed".takeIf { routesFailed },
            )
            val verdict = "rows ${ordered.joinToString(",") { "${it.lineId}@${it.fromId}" }.ifEmpty { "none" }}" +
                if (why.isEmpty()) "" else "; unchecked: ${why.joinToString("; ")}"
            verdictNext = verdict
            State.Ready(
                rows = ordered.map { row ->
                    // Each direction its trains run in, by that direction's own verdict where TfL scoped
                    // the alert by direction, as the list's rows take it; the worst of them is said.
                    val status = statuses[row.lineId]?.let { line ->
                        // The worst of them, by the order a line's own disruptions are ranked in
                        // ([mostSevereDisruption]): a named disruption over a fallback, then by severity.
                        row.departures.map { it.direction }.distinct().map(line::forDirection).filter { it.disrupted }
                            .minWithOrNull(compareBy({ it.isFallback }, { it.severity }))
                    }
                    val ends = row.reaches.flatMapTo(HashSet()) { listOf(it.id, it.hubId) }.apply { remove("") }
                    ShownRow(
                        row, Countdown.mergedLabel(row.departures, now), status?.description, closureNotice(row, closures.found, now),
                        ends, ends.sorted().joinToString(","),
                    )
                },
                checking = result.pending,
                // A row whose line's status, or a stop's closures, couldn't be had isn't vouched for either.
                uncertain = result.unresolved || fresh.size < stops.size || rowsUnvouched,
                // A train no Retry can tell (past its board's calling points, a line TfL has no route for)
                // leaves it uncertain but not failed (maintainer, 2026-10-07).
                retryable = routesFailed || fresh.size < stops.size || rowsUnvouched,
            )
        }
        // Checked again as it's written: a change since the check above stands, never this look's rows.
        publish(chosen) {
            if (next is State.Ready) shownAt = SteadyClock.stamp(now)
            heldOrigin = chosen to stops
            heldNext?.let { heldLines = chosen to it }
            verdictNext?.let { verdict ->
                if (verdict != reportedVerdict) warn("direct: $verdict")
                reportedVerdict = verdict
            }
            _state.value = next
        }
    }

    // A stop's notice to say on [row]: its boarding stop's, or, when every stop near the place one of its
    // trains reaches has one, the nearest of those, so a train with nowhere to get off is never shown as fine.
    private fun closureNotice(row: PlaceDirect.Row, found: Map<String, List<StopDisruption>>, now: Instant): String? {
        fun active(id: String) = found[id].orEmpty().firstOrNull { it.isActiveAt(now) }
        // Cleaned as every surface shows a stop's notice ([cleanDisruptionBody]): no repeat of the stop's name.
        fun said(name: String, notice: StopDisruption) = "$name: ${cleanDisruptionBody(notice.description, stopName = name).lineSequence().first()}"
        active(row.fromId)?.let { return said(row.fromName, it) }
        // Train by train: one whose every stop near the place is closed is said, whatever the others reach.
        val stranded = row.trainReaches.firstOrNull { reaches -> reaches.isNotEmpty() && reaches.all { active(it.id) != null } }
            ?: return null
        val end = stranded.first()
        return active(end.id)?.let { said(end.name, it) }
    }

    // Whether, for some train of [row], no stop near the place it reaches is known open but some couldn't be
    // checked: then that train isn't vouched for as having somewhere to get off. A train at a fork reaches
    // only one of its stops, which can't be told, so it's vouched for only when every one is known open;
    // with all of them closed, [closureNotice] says so.
    private fun unverifiedEnds(row: PlaceDirect.Row, found: Map<String, List<StopDisruption>>, unchecked: Set<String>, now: Instant): Boolean {
        fun closed(end: DirectTrips.End) = found[end.id].orEmpty().any { it.isActiveAt(now) }
        // Train by train, as [closureNotice] judges them.
        return row.trainReaches.withIndex().any { (i, reaches) ->
            if (row.trainForks.getOrElse(i) { false }) {
                !reaches.all(::closed) && reaches.any { it.id in unchecked || closed(it) }
            } else {
                reaches.any { it.id in unchecked } && reaches.none { end -> end.id !in unchecked && !closed(end) }
            }
        }
    }

    // The trip's stops' closures, shared with the list and every trip ([StopClosureCache.SHARED] in the
    // app): a stop checked within [DISRUPTION_REUSE] isn't asked about again.
    private val closureChecks = StopClosureChecks(client, closureCache, DISRUPTION_REUSE, io, compute, warn, "direct")

    // The last status of each line asked about, and when: asked again past [STATUS_AGE], or on a pull.
    private val heldStatuses = HashMap<String, Pair<LineStatus, Instant>>()

    // [lineIds]' statuses: held ones while recent, the rest asked of TfL in one batch. A line TfL
    // couldn't be asked about is absent, so its row counts as unchecked.
    private suspend fun statusesFor(lineIds: List<String>, now: Instant, pulled: Boolean): Map<String, LineStatus> {
        // A status still awaiting its directions is asked again on the next look, as the client asks of
        // its callers, so the split verdict lands as soon as it's known.
        val stale = lineIds.filter { id ->
            // Aged by the steady clock it's stamped by ([SteadyClock]): a wall clock set back never keeps
            // an old verdict fresh, and one dated after now is an age that can't be told, so asked again.
            pulled || heldStatuses[id]?.let { (status, at) ->
                val age = SteadyClock.age(at, now)
                status.awaitingDirections || age.isNegative || age > STATUS_AGE
            } ?: true
        }
        if (stale.isNotEmpty()) {
            val results = LineStatusBatch.request(stale) { chunk -> withContext(io) { client.lineStatuses(chunk) } }
            results.failure?.let { warn("direct line status failed for ${results.failed.size} line(s): ${it::class.simpleName}") }
            val answered = results.answers.flatMap { it.value }.associateBy { it.lineId }
            // Only a line TfL gave a status for is checked: one asked about and left out of the answer has
            // no verdict, so its row reads as unchecked, as the list reads such a line.
            stale.forEach { id ->
                val status = answered[id]
                if (status != null) heldStatuses[id] = status to SteadyClock.stamp(now) else heldStatuses.remove(id)
            }
        }
        return lineIds.mapNotNull { id -> heldStatuses[id]?.let { id to it.first } }.toMap()
    }

    // A stop's arrivals: the shared cache's while recent, else fetched and kept for the other screens.
    // Null on a failure, so the stop is left out rather than shown empty or as it last was.
    private suspend fun arrivalsOf(stop: StopRef, now: Instant, pulled: Boolean): StopArrivals? {
        val source = client.arrivalsSource()
        if (!pulled) arrivals.recent(stop.id, now, source)?.let { entry ->
            return StopArrivals(stop.id, stop.name, entry.departures, entry.fetchedAt, hubId = stop.hubId)
        }
        return try {
            // Stamped when asked, as the list stamps its fetches (SPEC D4).
            val at = SteadyClock.stamp(now)
            val generation = arrivals.generation
            val (departures, shared) = withContext(io) {
                val before = client.shareable(stop.id)
                client.arrivals(stop.id).let { it to (before && client.shareable(stop.id) && client.arrivalsSource() == source) }
            }
            val fetchedAt = client.fetchedAt(stop.id) ?: at
            if (shared) arrivals.put(stop.id, departures, fetchedAt, client.railFeed(stop.id), generation, source, client.untimed(stop.id))
            StopArrivals(stop.id, stop.name, departures, fetchedAt, hubId = stop.hubId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Withheld, not the last answer: a refresh that failed never shows the trains it last had as
            // refreshed. The stop counts as unchecked ([State.Ready.uncertain]).
            warn("direct arrivals failed: ${e::class.simpleName} for stop ${stop.id}")
            null
        }
    }

    // Each departing line's route: absent while loading, null when its load failed (DirectTrips.filter);
    // and whether a load failed in a way a Retry may get past (anything but TfL having no route for it).
    private suspend fun sequencesFor(stops: List<StopArrivals>, hidden: Set<String>): Pair<Map<String, LineSequence?>, Boolean> {
        val lineIds = stops.flatMap { stop ->
            stop.departures.filterNot { HiddenModes.isHidden(it.mode, it.lineId, hidden) }.map { it.lineId }
        }.distinct()
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val sequences = coroutineScope {
            lineIds.map { lineId ->
                async {
                    lineId to try {
                        routes.cached(lineId, "") ?: routes.load(lineId, "")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TflException.NotFound) {
                        // TfL has no route for it (Eurostar): no Retry will find one, and the routes say so once.
                        null
                    } catch (e: Exception) {
                        warn("direct route failed: ${e::class.simpleName} for line $lineId")
                        failed.set(true)
                        null
                    }
                }
            }.awaitAll().toMap()
        }
        return sequences to failed.get()
    }

    companion object {
        /** How often the countdowns are worked out again; arrivals are fetched only once past [ArrivalsCache.TTL]. */
        val TICK: Duration = Duration.ofSeconds(15)

        /** How long a line's status stands before it's asked again: as the trip's own statuses refresh. */
        val STATUS_AGE: Duration = Duration.ofMinutes(2)

        /**
         * How far past [DirectTrips.ORIGIN_RADIUS_METERS] a stop the section already started from stays one:
         * past a fix's usual waver, so a stop at the edge isn't in one look and out the next, its rows with it.
         */
        const val KEEP_SLACK_METERS = 50.0

        /**
         * [next]'s origin stops, plus those [held] took under the same choices that the rider is still within
         * [KEEP_SLACK_METERS] past the trip's reach of, by [Inputs.distanceMeters]; one of unknown distance goes.
         */
        @WorkerThread
        internal fun steadyOrigin(next: Inputs, held: Pair<Inputs, List<StopRef>>?): List<StopRef> {
            if (held == null || !held.first.sameChoice(next)) return next.origin
            val ids = next.origin.mapTo(HashSet()) { it.id }
            val kept = held.second.filter { stop ->
                stop.id !in ids && next.distanceMeters[stop.id]?.let { it <= DirectTrips.ORIGIN_RADIUS_METERS + KEEP_SLACK_METERS } == true
            }
            return if (kept.isEmpty()) next.origin else next.origin + kept
        }

        /**
         * [rows] with those already [shown] (by [key]) in the order they're shown in, then the rest in their
         * own order: a row never moves for one found or gone, nor for its trains' order changing.
         */
        @WorkerThread
        internal fun <T> heldOrder(rows: List<T>, shown: List<String>, key: (T) -> String): List<T> {
            if (shown.isEmpty()) return rows
            val at = HashMap<String, Int>().apply { shown.forEachIndexed { i, id -> putIfAbsent(id, i) } }
            val (kept, found) = rows.partition { key(it) in at }
            return kept.sortedBy { at.getValue(key(it)) } + found
        }
    }
}
