package app.stopdash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd

/**
 * A station tapped on a route page's stop list (maintainer, 2026-10-06): the page to open, by its
 * stop area where known, and the [journey] from the route's boarding stop to it for that page to offer
 * as a favorite. [journey] is null where the route page can't place one (its boarding stop itself, or
 * a line it can't favorite from).
 */
@Immutable
data class RouteStopOpen(
    val stationId: String,
    val name: String,
    val journey: FavoriteJourney?,
    // The stop's interchange (TfL `topMostParentId`, e.g. `HUBKGX`), where it has one: a station page
    // opened from search by its hub is this same place (Codex on #631).
    val hubId: String = "",
) {
    /** Whether this stop is the station page [openStationId] already shows, by its own id or its hub's. */
    fun isOpen(openStationId: String?): Boolean =
        openStationId != null && (openStationId == stationId || hubId.isNotBlank() && openStationId == hubId)
}

/**
 * Opens a route page's tapped station, provided by the activity; null (a test, a trip) leaves taps
 * inert. False when that station is the one already open beneath the route page (its own boarding
 * stop, say), which then closes to show it (Codex on #631).
 */
val LocalOpenRouteStop = compositionLocalOf<((RouteStopOpen) -> Boolean)?> { null }

/**
 * The journey a stop's page offers to favorite, when it was opened from a route page's stop: [saved]
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

/** The stop page's journey to favorite, provided by the activity; null shows no row. */
val LocalStationJourney = compositionLocalOf<StationJourneyState?> { null }

/**
 * Atop a stop's page opened from a route page: the journey there ("Victoria ➔ Warren Street") with
 * **Favorite**, or **Remove favorite** once saved (SPEC *Journeys*). Shown from the page's first frame,
 * so the list under it never moves; only the button's word changes.
 */
@Composable
internal fun StationJourneyRow(state: StationJourneyState, modifier: Modifier = Modifier) {
    val journey = state.journey
    val spoken = stringResource(R.string.journey_title_spoken, journey.from.name, journey.to.name)
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = withArrowIcons(stringResource(R.string.journey_title, journey.from.name, journey.to.name)),
                inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            if (state.failed || state.unavailable) {
                Text(
                    text = stringResource(
                        if (state.unavailable) R.string.favorite_journeys_unavailable else R.string.station_journey_write_failed,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (state.unavailable) {
            TextButton(onClick = state.onRetry, modifier = Modifier.testTag("stationJourneyRetry")) {
                Text(stringResource(R.string.favorite_journeys_retry))
            }
            return@Row
        }
        TextButton(
            onClick = state.onToggle,
            enabled = state.saved != null,
            modifier = Modifier.testTag("stationJourneyToggle"),
        ) {
            Text(stringResource(if (state.saved == true) R.string.action_unstar_journey else R.string.station_journey_add))
        }
    }
}

/** Keeps a tapped route stop across a rotation or process death, as the station it opened is. */
internal val RouteStopOpenSaver: Saver<RouteStopOpen?, Any> = Saver(
    save = { open ->
        open?.let { o ->
            arrayListOf<Any?>(o.stationId, o.name, o.hubId).apply {
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
        RouteStopOpen(
            stationId = v[0] as String,
            name = v[1] as String,
            hubId = v[2] as String,
            journey = if (v.size > 3) FavoriteJourney(end(6), end(11), v[3] as String, v[4] as String, v[5] as String) else null,
        )
    },
)
