package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import app.stopdash.R
import app.stopdash.domain.Connections
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStop
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** [RouteDetailScreen] picking nothing. */
internal const val PICK_NONE = 0

/** Picking where a favorite ride goes from a row's long press: Back returns to the row. */
internal const val PICK_FROM_ROW = 1

/** Picking where a favorite ride goes from the route page's own menu: Back stops picking. */
internal const val PICK_FROM_PAGE = 2

/**
 * A route row's long press, "Add favorite…" (maintainer, 2026-10-23): given the row's own way of opening its route
 * page, opens it picking where a favorite ride from it goes ([RouteDetailScreen]'s `picking`). Provided by
 * [MainScreen] over its lists; null (a trip's board, a screen with no rides to save) offers none.
 */
internal val LocalAddFavoriteRide = staticCompositionLocalOf<((open: () -> Unit) -> Unit)?> { null }

/**
 * A trip card's long press, "Add favorite": saves the direct ride the card rides ([Journeys.ofLeg]), said as a route
 * page's is ([RideAdds]). Provided by the activity; null (a test) offers none.
 */
internal val LocalAddTripRide = staticCompositionLocalOf<((FavoriteJourney, LineSequence?) -> Unit)?> { null }

/**
 * A ride picked on a trip's route page ("Add favorite ride…" in its menu): saved as a near-me row's route page saves
 * one ([RouteDetailScreen]'s `onPickTo`), said on the trip ([RideAdds]). Provided by the activity; null (a test)
 * offers none.
 */
internal val LocalAddRouteRide = staticCompositionLocalOf<((DepartureRow, RouteStopsUi.Loaded, RouteStop) -> Unit)?> { null }

/**
 * Whether [row] can be a favorite ride's start: a line to ride, rail or bus (the lines a ride follows), and a train
 * whose stops its route page lists. Field reads only.
 */
internal fun rideAddable(row: DepartureRow): Boolean {
    if (row.lineId.isBlank() || row.stopDisruption != null || row.upcoming.isEmpty()) return false
    val mode = row.mode.ifBlank { row.upcoming.first().mode }
    return Connections.isRail(mode, row.lineId) || mode.equals("bus", ignoreCase = true)
}

/**
 * How a ride picked on a route page went, by its ends' names [from] and [to]: [note] null once added, else why it
 * wasn't ([JourneyAddNote.AlreadySaved], [JourneyAddNote.NotSaved]).
 */
data class RideAdded(val from: String, val to: String, val note: JourneyAddNote?)

/**
 * The rides picked on route pages being saved, process-lived as [JourneyAdds] is, so one saved as its page closes, or
 * over a rotation, still says how it went: only the latest pick's outcome, once it's in.
 */
internal object RideAdds {
    val added = MutableStateFlow<RideAdded?>(null)
    private val latest = AtomicLong()

    fun attempt(scope: CoroutineScope, from: String, to: String, add: suspend () -> JourneyAddNote?): Job {
        val mine = latest.incrementAndGet()
        added.value = null
        return scope.launch {
            val outcome = add()
            if (latest.get() == mine) added.value = RideAdded(from, to, outcome)
        }
    }

    /** The list has said it. */
    fun shown() {
        added.value = null
    }
}

/** What the list says of [added]: as Settings' Add says it, or that it was added. */
@Composable
internal fun rideAddedText(added: RideAdded): String = when (val note = added.note) {
    null -> stringResource(R.string.ride_added, added.from, added.to)
    is JourneyAddNote.AlreadySaved -> stringResource(R.string.favorite_journeys_add_already, note.from, note.to)
    else -> stringResource(R.string.favorite_journeys_add_not_saved, added.from, added.to)
}
