package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Which of a refresh's stops the widget stores, and how they're classed, for the app's refresh and
 * the widget's own alike: the nearby stops [nearIds] and the journey origins [journeyIds], kept
 * positively (so an origin a flip has since dropped can't slip through a fetch in flight). An origin
 * that isn't nearby is journey-only: the widget shows just its journey's departures. A nearby stop
 * with no row at all is missing, so the widget doesn't read the rest as complete. The nearby stops
 * are ordered nearest the rider first from [distances], so a line shows once, from its nearest stop.
 */
object SnapshotPlan {
    data class Laid(
        val stops: List<StopArrivals>,
        val journeyOnlyStopIds: Set<String>,
        val missingStopIds: Set<String>,
        val nearestFirst: List<String>,
    )

    @WorkerThread
    fun laidOut(
        stops: List<StopArrivals>,
        nearIds: Set<String>,
        journeyIds: Set<String>,
        distances: Map<String, Double>,
    ): Laid {
        val kept = stops.filter { it.stopId in nearIds || it.stopId in journeyIds }
        val keptIds = kept.mapTo(HashSet()) { it.stopId }
        return Laid(
            stops = kept,
            journeyOnlyStopIds = keptIds - nearIds,
            missingStopIds = nearIds - keptIds,
            nearestFirst = NearbyLayout.nearestFirst(kept.map { it.stopId }.filter { it in nearIds }, distances),
        )
    }
}
