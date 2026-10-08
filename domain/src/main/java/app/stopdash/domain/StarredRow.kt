package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The stable identity of a **starred row** — the three parts that uniquely identify a
 * [DepartureRow] (SPEC D8): the [stopId], the [lineId] (a *service* is a line at a stop),
 * and the resolved [directionKey] ([DepartureRow.directionKey]: TfL `direction`, else
 * platform, else destination). Keying on the resolved direction key — not raw `direction`
 * — means a star restores to exactly one row even when TfL omits `direction` and two rows
 * would otherwise share a blank one: stop and service disambiguate across rows, and the
 * resolved key keeps blank-`direction` fallback siblings at one stop apart.
 *
 * A star is **ranking, not membership** (SPEC D8): it reorders its row to the top of the
 * list, it does not decide what is shown — that is the watched-stop set. So this carries
 * only the identity, no display fields; the row it pins to is matched fresh each render.
 * Persisted so a star survives restart; it rides Android backup/transfer with the rest of
 * the user's config (SPEC *Privacy* backup note), never an app-initiated send.
 */
data class StarredRow(
    val stopId: String,
    val lineId: String,
    val directionKey: String,
) {
    companion object {
        /** The star identity of [row] — the same `(stopId, lineId, directionKey)` that keys it. */
        fun of(row: DepartureRow): StarredRow = StarredRow(row.stopId, row.lineId, row.directionKey)
    }
}

/**
 * Pure toggle rule for the starred set, kept out of the store so the membership logic is
 * JVM-testable without DataStore or Android (mirrors [WatchedStops]). The store applies it
 * inside its atomic update.
 */
object Starred {
    /** [current] with [row]'s star flipped: removed if present, added if not. */
    fun toggle(current: Set<StarredRow>, row: StarredRow): Set<StarredRow> =
        if (row in current) current - row else current + row
}

/**
 * The rider's own stops: each starred row's, and each favorite journey's ends (a station's area id
 * too), which a line's map never folds away. Walks both: on a worker only.
 */
@WorkerThread
fun riderStopIds(starred: Set<StarredRow>, journeys: List<FavoriteJourney>): Set<String> = buildSet {
    starred.forEach { add(it.stopId) }
    journeys.forEach { journey ->
        for (end in listOf(journey.from, journey.to)) {
            add(end.stopId)
            if (end.areaId.isNotBlank()) add(end.areaId)
        }
    }
}
