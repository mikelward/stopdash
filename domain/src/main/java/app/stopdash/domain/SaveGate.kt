package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Which stops decide whether a refresh is worth storing, for the app's refresh and the widget's
 * alike (maintainer, 2026-10-10): the nearby ones [nearIds] whenever any were asked for, even if
 * none came back, so a journey origin's fresh arrivals alone never store a refresh whose nearby
 * stops failed. The stored nearby stops then keep their last good times, unmarked, until they age
 * out (SPEC D4), rather than reading "out of date" after one failed refresh underground. With no
 * nearby stops asked for, every stop counts.
 */
object SaveGate {
    @WorkerThread
    fun judged(stops: List<StopArrivals>, nearIds: Set<String>): List<StopArrivals> =
        if (nearIds.isEmpty()) stops else stops.filter { it.stopId in nearIds }

    /** [snapshot]'s nearby stops: every stop but the journey-only ones. */
    @WorkerThread
    fun nearIds(snapshot: DeparturesSnapshot): Set<String> =
        snapshot.stops.mapTo(HashSet()) { it.stopId } - snapshot.journeyOnlyStopIds + snapshot.missingStopIds
}
