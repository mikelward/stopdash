package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Rebuilds the widget's persisted snapshot by re-fetching arrivals for exactly the stops it is
 * already showing (SPEC D1: the widget is location-free at refresh, so a refresh needs no
 * location — just the stop set already in the snapshot). Pure over an injected [fetchArrivals]
 * so it is JVM-testable without Android or the network.
 *
 * A stop that fetches fresh gets its new arrivals stamped [now]; a stop whose fetch fails keeps
 * its aged last-good departures and timestamp but is marked `arrivalsFresh = false` — the same
 * invariant the app's own merge holds ([Snapshot] `across`), so the widget's per-row stale
 * withhold ages it honestly and the whole-widget stamp/warning can't read it as fresh (SPEC D4 /
 * principle 2) rather than blanking it. When **no** stop fetched fresh the whole cycle is a
 * no-op — this returns null and nothing is saved, leaving the last-good in place for the next
 * cycle. Line statuses are refreshed separately, by [refreshedLineStatuses], after the arrivals.
 *
 * A stop fetched fresh less than [reuse] before [now] — typically by the app a moment ago, which
 * shares the rate budget — is carried over as it is rather than fetched again. A cycle where every
 * stop was carried over is a no-op too (null): the app's own save already poked the widget.
 */
object WidgetRefresh {
    suspend fun refreshedArrivals(
        prior: DeparturesSnapshot,
        now: Instant,
        reuse: Duration = Duration.ZERO,
        // Arrivals another screen fetched within [ArrivalsCache.TTL] (SPEC *Freshness → Shared
        // arrivals*): taken, at their own fetch time, rather than asked for again.
        shared: ArrivalsCache? = null,
        // The source this refresh fetches from ([TflClient.arrivalsSource]): only arrivals from it are taken.
        source: Any? = null,
        // A fetched stop's National Rail feed after its fetch ([TflClient.railFeed]); null keeps the
        // row's own (a test with no National Rail).
        railFeed: ((stopId: String) -> RailFeed?)? = null,
        fetchArrivals: suspend (stopId: String) -> List<Departure>?,
    ): DeparturesSnapshot? {
        if (prior.stops.isEmpty()) return null
        // A negative age (the clock moved back) is never "recent" — fetch it, rather than trust it.
        // Nor is a station's row from before a National Rail key was added or removed ([source]: the
        // client's [TflClient.arrivalsSource], whether its times are on): "No key" with one, or
        // National Rail times without. A stop with no National Rail feed is the same either way.
        fun sameSource(stop: StopArrivals): Boolean {
            val railOn = source as? Boolean ?: return true
            val feed = stop.railFeed ?: return true
            return (feed != RailFeed.NO_KEY) == railOn
        }
        fun recent(stop: StopArrivals): Boolean {
            val age = Duration.between(stop.fetchedAt, now)
            return stop.arrivalsFresh && !age.isNegative && age < reuse && sameSource(stop)
        }
        // A newer fetch of the stop by another screen, where there is one: taken even over a recent
        // one of the widget's own, so it never shows older times than the app.
        val sharedByStop = prior.stops.map { stop ->
            shared?.recent(stop.stopId, now, source)?.takeIf { it.fetchedAt.isAfter(stop.fetchedAt) }
        }
        // Every stop's arrivals in parallel; the client's shared request pool bounds how many are in
        // flight. [fetchArrivals] returns null on failure rather than throwing, so one stop failing
        // never cancels the others.
        val fetchedByStop = coroutineScope {
            prior.stops.mapIndexed { i, stop ->
                async { if (recent(stop) || sharedByStop[i] != null) null else fetchArrivals(stop.stopId) }
            }.awaitAll()
        }
        var anyFresh = false
        val stops = prior.stops.mapIndexed { i, stop ->
            sharedByStop[i]?.let { entry ->
                anyFresh = true
                // With the National Rail feed that fetch found ("No key" once a key is removed).
                return@mapIndexed stop.copy(
                    departures = entry.departures,
                    fetchedAt = entry.fetchedAt,
                    arrivalsFresh = true,
                    railFeed = entry.railFeed,
                )
            }
            if (recent(stop)) return@mapIndexed stop
            when (val fetched = fetchedByStop[i]) {
                // Keep the aged last-good, but mark it not-fresh so its stale withhold fires and
                // it can't render as fresh within the freshness window (Codex P1 on #56). Its own
                // fetchedAt is preserved and still drives age-based staleness.
                null -> stop.copy(arrivalsFresh = false)
                else -> {
                    anyFresh = true
                    stop.copy(
                        departures = fetched,
                        fetchedAt = now,
                        arrivalsFresh = true,
                        railFeed = if (railFeed != null) railFeed(stop.stopId) else stop.railFeed,
                    )
                }
            }
        }
        if (!anyFresh) return null
        // The whole-screen stamp is the freshest stop's age (matches DeparturesSnapshot).
        // The journeys the app last worked out ride along unchanged (route data isn't refetched here).
        return prior.copy(stops = stops, fetchedAt = stops.maxOf { it.fetchedAt })
    }

    /**
     * [snapshot] with its line statuses re-checked (SPEC D3): every line its stops show, less those
     * checked within [reuse] (the app's own check a moment ago, sharing the rate budget), asked in
     * one [fetchStatuses] call and stamped by [answeredAt] once it returns — when TfL actually
     * answered, so a slow call neither loses a merge to an earlier check nor lands already
     * expiring ([now] decides only what to ask). A line TfL gave no status for gets a no-verdict
     * check ([LineStatusCheck.known] false), so it isn't marked on a verdict nobody gave. A failed call ([fetchStatuses] returns null) leaves
     * the prior checks as they were, to age out at the staleness threshold like a countdown (D4);
     * until a check is made the line reads as unchecked ([DeparturesSnapshot.statusKnown]).
     * Lines no longer shown are dropped either way.
     */
    suspend fun refreshedLineStatuses(
        snapshot: DeparturesSnapshot,
        now: Instant,
        reuse: Duration = Duration.ZERO,
        answeredAt: () -> Instant = { now },
        // Whether the user dismissed this status in the app ([LineStatusCheck.dismissed]), judged on
        // TfL's full answer, as the app's dismissal is.
        dismissed: (LineStatus) -> Boolean = { false },
        fetchStatuses: suspend (lineIds: Set<String>) -> List<LineStatus>?,
    ): DeparturesSnapshot {
        val lines = LineStatusCheck.linesOf(snapshot.stops)
        val kept = snapshot.lineStatuses.filterKeys { it in lines }
        val toAsk = lines.filterTo(HashSet()) { id ->
            val prior = kept[id] ?: return@filterTo true
            val age = Duration.between(prior.checkedAt, now)
            age.isNegative || age >= reuse
        }
        if (toAsk.isEmpty()) return snapshot.copy(lineStatuses = kept)
        val fetched = fetchStatuses(toAsk) ?: return snapshot.copy(lineStatuses = kept)
        val at = answeredAt()
        val returned = fetched.filter { it.lineId in toAsk }.associate { it.lineId to LineStatusCheck(it, at, dismissed = dismissed(it)) }
        // A line asked about that TfL left out gets a no-verdict check, so it replaces the old one
        // here and in the store's merge alike, rather than the old disruption being kept (an absent
        // entry reads as "nothing new") until it ages out.
        val fresh = toAsk.associateWith { returned[it] ?: LineStatusCheck.noVerdict(it, at) }
        return snapshot.copy(lineStatuses = LineStatusCheck.newest(kept, fresh, lines, at))
    }
}
