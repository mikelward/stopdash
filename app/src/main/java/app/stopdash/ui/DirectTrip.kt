package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.stopdash.domain.Coordinates
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineSequence
import app.stopdash.domain.NearestStops
import app.stopdash.domain.TflException
import java.time.Instant
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The routes (both directions) of [lineIds]: absent while loading, null when the load failed (so a
 * departure on it is flagged, not dropped as a "no"). Loaded again each hour, so a route the
 * repository let expire is refetched while the page stays up; the old copy shows meanwhile.
 */
@Composable
internal fun rememberLineSequences(lineIds: List<String>, now: Instant): Map<String, LineSequence?> =
    rememberLineLoads(lineIds, now).sequences

/**
 * [rememberLineSequences]' routes ([sequences]), with the lines whose load is under way ([loading]):
 * a first load, absent from [sequences] meanwhile, and a retry of one that failed, held there as null
 * meanwhile. A retry is a check running again, not one that failed. [version] changes with each load
 * stored, so a route replaced in place still reads as new.
 */
internal class LineLoads(val sequences: Map<String, LineSequence?>, val loading: Set<String>, val version: Int = 0, val loadingVersion: Int = 0)

/** [rememberLineSequences], saying which lines' loads are under way ([LineLoads]). */
@Composable
internal fun rememberLineLoads(lineIds: List<String>, now: Instant): LineLoads {
    val repository = LocalRouteStops.current
    val loaded = remember { mutableStateMapOf<String, LineSequence?>() }
    // The lines loading, one persistent set for each change, a line added or removed without copying
    // the rest, and never changed once handed out: work under way on the worker reads the set of its own
    // moment, never a later one (Codex, #529). What keys on it keys on [loadingVersion], bumped on each
    // change, never on its contents. A load is canceled (and so leaves it) when its line leaves [lineIds].
    val loading = remember { mutableStateOf<PersistentSet<String>>(persistentSetOf()) }
    val loadingVersion = remember { mutableIntStateOf(0) }
    // Bumped on each load stored, a retry's included, so what's worked out from the routes can key on it
    // without comparing them (Codex, PR #520).
    val version = remember { mutableIntStateOf(0) }
    val recheck = now.epochSecond / 3600
    // Each line's load runs on its own: a line added or dropped (a trip's plan landing answer by answer)
    // starts or cancels only its own, never one still wanted, whose fetch would start over. Held as one
    // persistent map, so the worker reads the set of its own moment without a copy.
    val scope = rememberCoroutineScope()
    val worker = LocalWorker.current
    val jobs = remember(repository) { arrayOf(persistentMapOf<String, LineLoad>()) }
    // The lines with a route held in [loaded], as a plain persistent set the worker reads: never the
    // state map itself, off the main thread.
    val heldIds = remember { arrayOf(persistentSetOf<String>()) }
    // The loads run in a scope of the repository's own, so a repository replaced (or the screen gone)
    // stops them with one cancel, never a walk over every line on the main thread (Codex, #602).
    val loadScope = remember(scope, repository) { CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job])) }
    DisposableEffect(loadScope) { onDispose { loadScope.cancel() } }
    LaunchedEffect(repository, lineIds, recheck) {
        val routes = repository ?: return@LaunchedEffect
        // Nothing wanted and nothing running: nothing to work out.
        if (lineIds.isEmpty() && jobs[0].isEmpty()) return@LaunchedEffect
        val running = jobs[0]
        // Which loads to stop and which to start, worked out on the worker: it walks every line
        // (AGENTS.md *Main thread*). Here only what it names is dispatched.
        val routesHeld = heldIds[0]
        val (dropped, needed) = withContext(worker) {
            lineLoadChanges(lineIds, running, recheck) { it in routesHeld && routes.cached(it, "") != null }
        }
        for (lineId in dropped) {
            jobs[0][lineId]?.job?.cancel()
            jobs[0] = jobs[0].remove(lineId)
        }
        // Every line at once: one slow line (a National Rail route can take TfL several seconds)
        // no longer holds up the rest, and each is checked as soon as its own route arrives.
        for (lineId in needed) {
            if (jobs[0][lineId]?.job?.isActive == true) continue
            val held = loaded[lineId]
            // Registered before it starts: one that finishes at once (its route already held) then
            // finds itself the line's load, and so clears it from [loading] (Codex, #602).
            val job = loadScope.launch(start = CoroutineStart.LAZY) {
                loading.value = loading.value.add(lineId)
                loadingVersion.intValue++
                try {
                    loaded[lineId] = (routes.cached(lineId, "") ?: try {
                        routes.load(lineId, "")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TflException) {
                        // Logged (sanitized) by the repository. A day-old copy beats none; with none,
                        // null marks the failure so the page says some routes couldn't be checked.
                        held
                    }).also {
                        heldIds[0] = if (it != null) heldIds[0].add(lineId) else heldIds[0].remove(lineId)
                        version.intValue++
                    }
                } finally {
                    // Unless a load started since for the line (dropped and wanted again) is under way.
                    if (jobs[0][lineId].let { it == null || it.job === coroutineContext[Job] }) {
                        loading.value = loading.value.remove(lineId)
                        loadingVersion.intValue++
                    }
                }
            }
            jobs[0] = jobs[0].put(lineId, LineLoad(job, recheck))
            job.start()
        }
    }
    return LineLoads(lineIds.filter { it in loaded }.associateWith { loaded[it] }, loading.value, version.intValue, loadingVersion.intValue)
}

/** A line's load in [rememberLineLoads], and the hour ([period]) it started in. */
internal class LineLoad(val job: Job, val period: Long)

/**
 * The loads [rememberLineLoads] stops and starts as [lineIds] change: those [running] for a line no
 * longer wanted, and the wanted lines with no load under way and no [current] route held. One already
 * tried this [period] isn't tried again until the next: a failed route is asked about hourly, not again
 * with each line joining (a trip's plan landing answer by answer, Codex, #602). Walks every line: on a
 * worker only.
 */
@WorkerThread
internal fun lineLoadChanges(lineIds: List<String>, running: Map<String, LineLoad>, period: Long, current: (String) -> Boolean): Pair<List<String>, List<String>> {
    val wanted = lineIds.toHashSet()
    val dropped = running.keys.filterNot { it in wanted }
    val needed = lineIds.filter { id ->
        val load = running[id]
        load?.job?.isActive != true && load?.period != period && !current(id)
    }
    return dropped to needed
}

/**
 * The stops a To… from the near-me list starts from, worked out afresh from the current nearby set
 * (SPEC *Finding stops → From… To…*), so a re-locate moves the trip with the rider: the stops the
 * list shows by default ([eager], the nearest of each mode within a mile) plus any stop found within
 * 0.2 mi ([DirectTrips.originIds]), leaving out a stop that serves only [hidden] modes or has no
 * routes. Empty when every nearby stop is hidden.
 */
@WorkerThread
internal fun hereOriginIds(
    eager: List<StopRef>,
    nearby: List<StopRef>,
    distanceMeters: Map<String, Double>,
    hidden: Set<String>,
): List<String> {
    // A stop with no routes has nothing to take anywhere, so it's never an origin.
    fun shown(stop: StopRef) = stop.lines.any { !HiddenModes.isHidden(it, hidden) }
    val candidates = nearby.filter(::shown).mapTo(HashSet()) { it.id }
    return DirectTrips.originIds(
        eager.filter(::shown).map { it.id },
        distanceMeters.filterKeys { it in candidates },
    ).filter { it in candidates || it !in distanceMeters }
}

/**
 * The stops a To… from here starts from ([hereOriginIds]), as stops of [ready]'s set, without [hidden]
 * modes. Worked out on [LocalWorker] (AGENTS.md *Main thread: read and dispatch only*): every nearby
 * stop and its lines are walked. Null until the answer for this set and these modes is in, never the
 * last set's or an empty list, which would read as "nowhere to start" and end the trip.
 */
@Composable
internal fun rememberHereOrigin(
    ready: NearbyStopsViewModel.State.Ready,
    hidden: Set<String>,
    // Where the answer is kept: held above the screens that ask, so the near-me list and the To… it
    // opens share it and the trip has its origin in its first frame (Codex on #544).
    work: HereOriginWork = viewModel(key = "here-origin"),
): List<StopRef>? = rememberWorked(work.slot, Inputs(ready, hidden)) { hereOrigin(ready, hidden) }

/** [rememberHereOrigin]'s last answer, held by a view model store rather than one screen's composition. */
internal class HereOriginWork : ViewModel() {
    internal val slot: MutableState<Worked<Inputs, List<StopRef>>?> = mutableStateOf(null)
}

/** [rememberHereOrigin]'s answer: [hereOriginIds] as [ready]'s own stops. */
@WorkerThread
internal fun hereOrigin(ready: NearbyStopsViewModel.State.Ready, hidden: Set<String>): List<StopRef> {
    val byId = ready.nearbyStops.associateBy { it.id }
    return hereOriginIds(ready.eagerStops, ready.nearbyStops, ready.distanceMeters, hidden).mapNotNull { byId[it] }
}

/**
 * The id that keys a trip's plans, or null when the trip has nowhere to start and ends (every
 * nearby stop hidden). From a *From…* station ([fromStopIds]), one of its own stops; from here, the
 * stop of [anchors] (else [origin]) nearest the rider, so a move to a new nearest stop plans afresh,
 * while the Planner plans from where the rider is. With no stop in range at all ([noneNearby]), a
 * trip from here still plans (Codex on #315), keyed by the position it plans from ([hereAnchor],
 * [hereStartId]), so routes planned from one place are never shown for another (Codex on #439). A
 * stop with no lines, or an interchange's id (the Planner takes neither), is a start only when
 * nothing else is.
 */
internal fun tripStartId(
    origin: List<StopRef>,
    anchors: List<StopRef>,
    fromStopIds: Set<String>,
    distanceMeters: Map<String, Double>,
    noneNearby: Boolean,
    hereAnchor: Coordinates?,
): String? {
    if (origin.isEmpty()) return hereAnchor?.takeIf { noneNearby }?.let(::hereStartId)
    val starts = anchors.ifEmpty { origin }.filter { !it.id.startsWith(HUB_PREFIX) && it.lines.isNotEmpty() }
        .ifEmpty { origin.filterNot { it.id.startsWith(HUB_PREFIX) } }
    return (starts.filter { it.id in fromStopIds }.ifEmpty { starts })
        .minByOrNull { distanceMeters[it.id] ?: Double.MAX_VALUE }?.id ?: origin.first().id
}

/**
 * The re-pick a trip from here refreshes for ([TripViewModel.refreshFor]): the nearby set's
 * [repickId], or, with no stop in range ([noneNearby]), where the rider is now ([here]). That trip has
 * no set to re-pick, and its start never changes, so each new position stands in for a re-pick: a
 * refined or re-taken fix refreshes it, and plans it again once the rider has moved far enough, as a
 * re-pick does for a trip from the list (Codex on #439). The same position, the same id.
 */
internal fun tripRepickId(noneNearby: Boolean, here: Coordinates?, repickId: Long?): Long? =
    if (noneNearby) here?.let { (it.latitude to it.longitude).hashCode().toLong() } else repickId

/**
 * Where a trip from here with no stop in range plans from, for its key ([tripStartId]): [anchor], the
 * position it was keyed by last, until the rider ([here]) is [TripViewModel.REPLAN_MOVE_METERS] or more
 * from it, then [here]. A move that far plans the trip afresh, as a new nearest stop does a trip from
 * the list, so its routes from the place left are never shown, not even while the new plan loads or
 * after it fails (Codex on #439); a smaller one (a refined fix) keeps the trip, refreshed
 * ([tripRepickId]). Null while there's no position.
 */
internal fun hereAnchor(anchor: Coordinates?, here: Coordinates?): Coordinates? {
    if (here == null || anchor == null) return here ?: anchor
    val moved = NearestStops.distanceMeters(anchor.latitude, anchor.longitude, here.latitude, here.longitude)
    return if (moved >= TripViewModel.REPLAN_MOVE_METERS) here else anchor
}

/** The key of a trip from here with no stop in range: the position ([hereAnchor]) it plans from. */
internal fun hereStartId(anchor: Coordinates): String = "$HERE_START@${anchor.latitude},${anchor.longitude}"

// What keys a trip from here with no stop in range, ahead of where it plans from.
private const val HERE_START = "here"

// The Planner takes stop and station ids but not an interchange's.
private const val HUB_PREFIX = "HUB"
