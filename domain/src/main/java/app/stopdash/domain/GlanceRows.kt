package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant

/**
 * The rows a glance surface (the widget, the watch's tile, app and complication) draws, and the stop
 * each line is shown from among them. One place for both, so the stops the app and the widget's own
 * refresh choose for each line are chosen from exactly the rows the widget draws: each separate copy
 * of these rows drifted from the drawing in its own way (Codex on #748).
 */
object GlanceRows {
    /**
     * [stops]' rows as a glance surface draws them, with [lineStatuses]: one row per direction rather
     * than per platform (a split row costs a line of a tight budget), and a line's status row kept over
     * stale arrivals, since its check may be newer than they are.
     */
    @WorkerThread
    fun of(stops: List<StopArrivals>, now: Instant, lineStatuses: Map<String, LineStatus>): List<DepartureRow> =
        DepartureRows.across(stops, now, lineStatuses, splitPlatforms = false, statusRowsWhenStale = true)

    /**
     * The stop each line of [snapshot]'s [nearby] stops is shown from at [distances]
     * ([DepartureRows.nearbyChoices]), from the rows a glance surface draws: with the user's
     * [dismissals] and the app's [verdicts] that an alert lies behind a stop applied, as the widget
     * applies them before drawing, and [hidden] modes left out. Saved with the snapshot (never the
     * distances), so the widget and the watch fold each line where the app's list does.
     */
    @WorkerThread
    fun choices(
        snapshot: DeparturesSnapshot,
        nearby: Collection<String>,
        distances: Map<String, Double>,
        now: Instant,
        hidden: Set<String>,
        dismissals: Dismissals,
        verdicts: Set<AlertBehind>,
    ): List<FoldChoice> {
        val ids = nearby as? Set<String> ?: nearby.toHashSet()
        val near = snapshot.stops.filter { it.stopId in ids && it.stopId in distances }
        if (near.isEmpty()) return emptyList()
        val shown = snapshot.copy(stops = near).withDismissals(dismissals).withAlertsBehind(verdicts)
        val rows = HiddenModes.rows(of(shown.stops, now, shown.liveLineStatuses(now)), hidden)
        // The dismissed stop closures too, which the line checks' marks don't carry.
        return DepartureRows.nearbyChoices(rows, distances, dismissals.active)
    }
}
