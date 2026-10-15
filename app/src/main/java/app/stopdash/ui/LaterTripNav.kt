package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.stopdash.StopdashDebugLog
import app.stopdash.domain.TripTime

/**
 * Where a trip stands between now and a later time (SPEC *Trips with a change → A later trip*): the
 * time picked, if any ([later]), whose list then shows in place of the trip for now; and, once one of
 * its routes is opened live, the later trip it came from ([openedFrom]) with that route's key
 * ([openedKey]), so closing the route goes back to that list. Each step is a new value; nothing here
 * plans or fetches.
 */
internal data class LaterTripNav(
    val later: TripTime? = null,
    val openedFrom: TripTime? = null,
    val openedKey: String? = null,
) {
    /** A time picked: its list, in place of anything open for now. */
    fun picked(time: TripTime) = LaterTripNav(later = time)

    /** A route of the later list opened in the trip for now, live. */
    fun opened(key: String) = LaterTripNav(openedFrom = later, openedKey = key)

    /** Back, or "Leave now", from the later list: the trip for now. */
    fun leftLater() = LaterTripNav()

    /** The route opened from the later list closed, however: back to that list. Nothing to go back to otherwise. */
    fun routeClosed() = if (openedFrom != null) LaterTripNav(later = openedFrom) else this

    companion object {
        /** Kept over a rotation and a recreated process, as the trip's own open route is. */
        val Saver: Saver<LaterTripNav, Any> = saver { StopdashDebugLog.warning("trip: %s", it) }

        // An unreadable saved time goes to [warn] (by its failure, not the time) before the trip plans for now.
        internal fun saver(warn: (String) -> Unit): Saver<LaterTripNav, Any> = listSaver(
            save = { listOf(it.later?.encode(), it.openedFrom?.encode(), it.openedKey) },
            restore = { LaterTripNav(TripTime.parse(it[0], warn), TripTime.parse(it[1], warn), it[2]) },
        )
    }
}

/**
 * Whether the trip for now's plan, in [state], says the route [key] isn't on offer now: only a settled plan
 * says so. One still landing, or one that failed, may yet offer it, so a route closed then (Back while it
 * plans) just goes back, saying nothing. A pass over every route: on a worker.
 */
@WorkerThread
internal fun notPlannedForNow(state: TripViewModel.State, key: String?): Boolean {
    if (state.planning || state.planError != null) return false
    val routes = state.routes ?: return false
    return routes.none { routeKey(it) == key }
}
