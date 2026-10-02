package app.stopdash.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a line's page marks for its alert (SPEC *Disruptions*), read with [AlertStops]: the listed
 * stations the alert names ([named]), the stops on the train's list it touches ([stretch], for their
 * ⚠s), and where it is, beside the chip ([places]).
 *
 * Worked out by [of] off the caller's thread. [AlertStops] builds a pattern per station name, and
 * per pair of them for a stretch ("between A and B"), so the work grows with the route and the
 * alert's length: a long bus route under a wordy alert took the page's first frame past Android's
 * limit and froze the app. The page shows at once and fills these in when they're ready.
 */
data class AlertMarks(
    val named: List<RouteStop> = emptyList(),
    val stretch: Set<String> = emptySet(),
    val places: List<String> = emptyList(),
) {
    companion object {
        /** Nothing marked: no alert, or not read yet. */
        val NONE = AlertMarks()

        /**
         * The marks for [alertText] (the alert the page tells of) and [statusText] (the one flagging
         * the row; null when it's an alert behind the stop, which flags no stop). [trainStops] is the
         * train's own list when shown, else null, and [lineStops] the line's stations otherwise;
         * [wholeRoute] the route [trainStops] is part of, from its first stop. [shortName] is how a
         * stop is called beside the chip. Runs on [worker], never the caller's thread
         * (AGENTS.md *Main-safe by default*).
         */
        suspend fun of(
            alertText: String?,
            statusText: String?,
            hasStatus: Boolean,
            trainStops: List<RouteStop>?,
            lineStops: List<RouteStop>,
            wholeRoute: List<RouteStop>,
            shortName: (RouteStop) -> String,
            worker: CoroutineDispatcher = Dispatchers.Default,
        ): AlertMarks = withContext(worker) {
            if (alertText == null) return@withContext NONE
            val listed = trainStops ?: lineStops
            val ids = AlertStops.mentioned(alertText, listed)
            val named = listed.filter { it.id in ids }
            val stretch = when {
                !hasStatus -> emptySet()
                trainStops != null -> AlertStops.affected(statusText, trainStops)
                else -> named.mapTo(HashSet()) { it.id }
            }
            val onList = trainStops?.let { AlertStops.runs(stretch, it, shortName) }.orEmpty()
            val places = onList.ifEmpty { AlertStops.runs(AlertStops.affected(alertText, wholeRoute), wholeRoute, shortName) }
                .ifEmpty { named.map(shortName).distinct() }
            AlertMarks(named, stretch, places)
        }
    }
}
