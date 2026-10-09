package app.stopdash.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.RouteStop
import java.time.Instant

/**
 * A stop tapped on a route page's stop list (maintainer, 2026-10-09): its details open over the route
 * page, headed by the departure tapped ([line] from [fromName], towards [towards]) with **Go** and a star
 * for the [journey] there (SPEC *Route detail*). [stopId] is the stop the train calls
 * at; [stationId] its stop area where it has one. [journey] is null where the route page can't place one
 * (its boarding stop itself, or a line it can't favorite from).
 */
@Immutable
data class RouteStopOpen(
    val stationId: String,
    val name: String,
    val journey: FavoriteJourney?,
    // The stop's interchange (TfL `topMostParentId`, e.g. `HUBKGX`), where it has one.
    val hubId: String = "",
    val stopId: String = stationId,
    val line: LineRef? = null,
    val fromName: String = "",
    val towards: String = "",
    // Where the route page placed it; null where it didn't.
    val position: Coordinates? = null,
    // The route's boarding stop itself, at the head of the list, which there's no riding to: no Go. A loop's later
    // call there, tapped on the list, isn't it.
    val boarding: Boolean = false,
    // What Go rides there, worked out from the page's stop list as the details open; not saved, so a page
    // brought back after the process was gone offers none.
    val go: RouteGo? = null,
)

/**
 * What a stop's **Go** needs from the route page it was tapped on ([RouteRide.to]): its stop list, as
 * loaded, and the departure tapped. Read on the worker, never in composition.
 */
@Immutable
class RouteGo(
    val stops: List<RouteStop>,
    val positions: Map<String, Pair<Double, Double>>,
    val areas: Map<String, String>,
    val departs: Instant,
    // The tapped stop's place in [stops] where the tap knew it (a loop calls at some stops twice); null goes by its id.
    val at: Int? = null,
)

/** Opens a route page's tapped stop's details over it, provided by the activity; null (a test) leaves taps inert. */
val LocalOpenRouteStop = compositionLocalOf<((RouteStopOpen) -> Unit)?> { null }

/**
 * The journey a stop's details offer to favorite, when they were opened from a route page's stop: [saved]
 * is null until the favorites are read (or while unreadable), so the button waits rather than guess;
 * [failed] says the last change didn't save.
 */
@Immutable
class StationJourneyState(
    val journey: FavoriteJourney,
    val saved: Boolean?,
    val failed: Boolean,
    // The favorites couldn't be read (a disk error, or a newer StopDash's file): said, with [onRetry],
    // rather than leave Favorite disabled as if still loading.
    val unavailable: Boolean = false,
    val onRetry: () -> Unit = {},
    val onToggle: () -> Unit,
)


/**
 * Keeps a tapped route stop across a rotation or process death, as the details it opened are; its [RouteStopOpen.go]
 * isn't kept. Saved as "v2", then its ids and names, its line (blank where none), where it is (null where
 * unknown), whether it's the boarding stop, then its journey's where it has one. A save from before (the
 * station alone) restores as that.
 */
internal val RouteStopOpenSaver: Saver<RouteStopOpen?, Any> = Saver(
    save = { open ->
        open?.let { o ->
            arrayListOf<Any?>(
                "v2", o.stationId, o.name, o.hubId, o.stopId, o.line?.id.orEmpty(), o.line?.name.orEmpty(), o.line?.mode.orEmpty(),
                o.fromName, o.towards, o.position?.latitude, o.position?.longitude, o.boarding,
            ).apply {
                o.journey?.let { j ->
                    addAll(listOf(j.lineId, j.lineName, j.mode))
                    for (end in listOf(j.from, j.to)) addAll(listOf(end.stopId, end.name, end.latitude, end.longitude, end.areaId))
                }
            }
        }
    },
    restore = { saved ->
        val v = saved as List<*>
        fun end(at: Int) = JourneyEnd(v[at] as String, v[at + 1] as String, v[at + 2] as Double?, v[at + 3] as Double?, v[at + 4] as String)
        if (v.firstOrNull() == "v2") {
            val lat = v[10] as Double?
            val lon = v[11] as Double?
            RouteStopOpen(
                stationId = v[1] as String,
                name = v[2] as String,
                hubId = v[3] as String,
                stopId = v[4] as String,
                line = (v[5] as String).takeIf { it.isNotEmpty() }?.let { LineRef(it, v[6] as String, v[7] as String) },
                fromName = v[8] as String,
                towards = v[9] as String,
                position = if (lat != null && lon != null) Coordinates(lat, lon) else null,
                boarding = v[12] as Boolean,
                journey = if (v.size > 13) FavoriteJourney(end(16), end(21), v[13] as String, v[14] as String, v[15] as String) else null,
            )
        } else {
            RouteStopOpen(
                stationId = v[0] as String,
                name = v[1] as String,
                hubId = v[2] as String,
                journey = if (v.size > 3) FavoriteJourney(end(6), end(11), v[3] as String, v[4] as String, v[5] as String) else null,
            )
        }
    },
)
