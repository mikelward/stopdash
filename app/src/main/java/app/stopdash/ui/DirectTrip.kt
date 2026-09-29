package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineSequence
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
internal fun rememberLineSequences(lineIds: List<String>, now: Instant): Map<String, LineSequence?> {
    val repository = LocalRouteStops.current
    val loaded = remember { mutableStateMapOf<String, LineSequence?>() }
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
                    loaded[lineId] = routes.cached(lineId, "") ?: try {
                        routes.load(lineId, "")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TflException) {
                        // Logged (sanitized) by the repository. A day-old copy beats none; with none,
                        // null marks the failure so the page says some routes couldn't be checked.
                        held
                    }
                }
            }
        }
    }
    return lineIds.filter { it in loaded }.associateWith { loaded[it] }
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
