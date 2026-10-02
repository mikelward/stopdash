package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A stop, and the way its rows go there (TfL's `inbound`/`outbound`, blank where not known): where a
 * line's alert can lie wholly behind the stop ([LineStatus.behindAt]).
 */
data class StopWay(val stopId: String, val direction: String)

/**
 * The app's verdict that a bus line's alert lies wholly behind a stop for its buses going [direction]
 * ([DepartureRows.withAlertsBehind]): a diversion they have already left behind. The alert is named by
 * its [fingerprint] ([lineAlertFingerprint], its full words), so a reworded alert is a new one with no
 * verdict. The app reaches it with the line's routes; the widget and the watch, which have neither the
 * routes nor TfL's prose, apply it ([DeparturesSnapshot.withAlertsBehind]).
 */
data class AlertBehind(val lineId: String, val fingerprint: String, val stopId: String, val direction: String) {
    val way: StopWay get() = StopWay(stopId, direction)
}

/**
 * What the app found placing the alerts on its rows ([DepartureRows.alertsBehind]): [weighed], each
 * alert, stop and way it could place (its line's route loaded), and of those the ones [behind] the stop.
 * One weighed and not behind is a verdict disproved: the route or the rows have changed since it was
 * reached. [stops] are every stop the rows were at, and [alerts] every alert on them by line and full
 * words ([lineAlertFingerprint]), placed or not: the store keeps verdicts on these alone, so it mirrors
 * the list as it is, never a place the rider has left or an alert since gone (Codex, PR #471).
 */
data class AlertPlacement(
    val behind: Set<AlertBehind>,
    val weighed: Set<AlertBehind>,
    val stops: Set<String> = emptySet(),
    // Each as (line id, fingerprint).
    val alerts: Set<Pair<String, String>> = emptySet(),
) {
    companion object {
        val NONE = AlertPlacement(emptySet(), emptySet())
    }
}

/**
 * Keeps the app's [AlertBehind] verdicts for the widget and the watch. A seam, as
 * [DismissedAlertsStore] is: the DataStore-backed one is in the `data` layer. [verdicts] emits the
 * current ones at once and on every change; a set this build can't read reads as none, so every alert
 * flags (SPEC principle 2). [record] takes the app's latest placement, best-effort.
 */
interface AlertsBehindStore {
    fun verdicts(): Flow<Set<AlertBehind>>

    suspend fun record(placement: AlertPlacement)

    companion object {
        /** Keeps nothing and holds none: every alert flags, as before the app placed any. */
        val NONE: AlertsBehindStore = object : AlertsBehindStore {
            override fun verdicts(): Flow<Set<AlertBehind>> = flowOf(emptySet())
            override suspend fun record(placement: AlertPlacement) {}
        }
    }
}

/**
 * How long a verdict stands, and when it's refreshed. The store mirrors the list as it is: one at a stop
 * the list no longer shows goes at once, never keeping where the rider has been, and so does one on an
 * alert no longer on its rows, so the same words back later aren't taken as placed until the app places
 * them again. One the app weighs again and no longer finds behind the stop goes too: its route was
 * refreshed, or the stop's rows changed, and keeping it would hide an alert now on the way (Codex, PR
 * #471). Any other stands for [MAX_AGE] after it was last reached, the life of the route data behind it
 * ([RouteStopsRepository.MAX_AGE]).
 * Its alert's words don't change under it (a reworded alert is another). The app reaches the same ones
 * on every frame, so a held verdict is stamped again only once it's [RESTAMP] old: a write an hour at
 * most while nothing changes.
 */
object AlertsBehind {
    val MAX_AGE: Duration = RouteStopsRepository.MAX_AGE
    val RESTAMP: Duration = Duration.ofHours(1)

    /** The verdicts in [stored] still standing at [now]; one stamped ahead of [now] (the clock went back) isn't. */
    fun standing(stored: Map<AlertBehind, Instant>, now: Instant): Set<AlertBehind> =
        stored.filterValues { fresh(it, now) }.keys

    /**
     * [stored] with [placement]'s verdicts added or stamped [now], and those no longer standing dropped:
     * lapsed, at a stop [placement] wasn't at, on an alert not on its rows, or weighed by it and not found
     * behind. Null when that changes nothing worth a write: every one reached is held and younger than
     * [RESTAMP], and none has gone.
     */
    fun recorded(stored: Map<AlertBehind, Instant>, placement: AlertPlacement, now: Instant): Map<AlertBehind, Instant>? {
        val reached = placement.behind
        val kept = stored.filter { (verdict, at) ->
            fresh(at, now) && verdict.stopId in placement.stops && (verdict.lineId to verdict.fingerprint) in placement.alerts &&
                (verdict in reached || verdict !in placement.weighed)
        }
        val due = reached.filter { verdict -> kept[verdict]?.let { Duration.between(it, now) >= RESTAMP } ?: true }
        if (due.isEmpty() && kept.size == stored.size) return null
        return kept + due.associateWith { now }
    }

    private fun fresh(at: Instant, now: Instant): Boolean {
        val age = Duration.between(at, now)
        return !age.isNegative && age < MAX_AGE
    }
}
