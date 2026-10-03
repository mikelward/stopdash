package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import app.stopdash.domain.Coordinates
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineSequence
import app.stopdash.domain.NearestStops
import app.stopdash.domain.TflException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
internal class LineLoads(val sequences: Map<String, LineSequence?>, val loading: Set<String>, val version: Int = 0)

/** [rememberLineSequences], saying which lines' loads are under way ([LineLoads]). */
@Composable
internal fun rememberLineLoads(lineIds: List<String>, now: Instant): LineLoads {
    val repository = LocalRouteStops.current
    val loaded = remember { mutableStateMapOf<String, LineSequence?>() }
    val loading = remember { mutableStateMapOf<String, Unit>() }
    // Bumped on each load stored, a retry's included, so what's worked out from the routes can key on it
    // without comparing them (Codex, PR #520).
    val version = remember { mutableIntStateOf(0) }
    val recheck = now.epochSecond / 3600
    LaunchedEffect(repository, lineIds, recheck) {
        val routes = repository ?: return@LaunchedEffect
        // Every line at once: one slow line (a National Rail route can take TfL several seconds)
        // no longer holds up the rest, and each is checked as soon as its own route arrives.
        coroutineScope {
            for (lineId in lineIds) {
                val held = loaded[lineId]
                if (held != null && routes.cached(lineId, "") != null) continue
                launch {
                    loading[lineId] = Unit
                    try {
                        loaded[lineId] = (routes.cached(lineId, "") ?: try {
                            routes.load(lineId, "")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: TflException) {
                            // Logged (sanitized) by the repository. A day-old copy beats none; with none,
                            // null marks the failure so the page says some routes couldn't be checked.
                            held
                        }).also { version.intValue++ }
                    } finally {
                        loading.remove(lineId)
                    }
                }
            }
        }
    }
    return LineLoads(lineIds.filter { it in loaded }.associateWith { loaded[it] }, lineIds.filterTo(HashSet()) { it in loading }, version.intValue)
}

/**
 * The stops a To… from the near-me list starts from, worked out afresh from the current nearby set
 * (SPEC *Finding stops → From… To…*), so a re-locate moves the trip with the rider: the stops the
 * list shows by default ([eager], the nearest of each mode within a mile) plus any stop found within
 * 0.2 mi ([DirectTrips.originIds]), leaving out a stop that serves only [hidden] modes or has no
 * routes. Empty when every nearby stop is hidden.
 */
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
