package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Rebuilds the widget's persisted snapshot by re-fetching arrivals for exactly the stops it is
 * already showing — just the stop set already in the snapshot, no location. Where the widget
 * follows the rider (SPEC D1), the app's follow step picks that stop set first, from a last known
 * position, and may send it to TfL to find stops; this rebuild never reads one. Pure over an injected [fetchArrivals]
 * so it is JVM-testable without Android or the network.
 *
 * A stop that fetches fresh gets its new arrivals stamped [now]; a stop whose fetch fails keeps
 * its aged last-good departures and timestamp but is marked `arrivalsFresh = false` — the same
 * invariant the app's own merge holds ([Snapshot] `across`), so the widget's per-row stale
 * withhold ages it honestly and the whole-widget stamp/warning can't read it as fresh (SPEC D4 /
 * principle 2) rather than blanking it. When **no** stop fetched fresh the whole cycle is a
 * no-op — this returns null and nothing is saved, leaving the last-good in place for the next
 * cycle. Line statuses are refreshed separately, by [refreshedLineStatuses], after the arrivals —
 * even when none came fresh ([refresh]).
 *
 * A stop fetched fresh less than [reuse] before [now] — typically by the app a moment ago, which
 * shares the rate budget — is carried over as it is rather than fetched again. A cycle where every
 * stop was carried over is a no-op too (null): the app's own save already poked the widget.
 */
object WidgetRefresh {
    /** What one refresh of the stored snapshot came to ([refresh]). */
    sealed interface Outcome {
        /** Fresh arrivals, their lines' statuses checked beside them: [snapshot] is to be saved. */
        data class Save(val snapshot: DeparturesSnapshot) : Outcome

        /**
         * No arrivals came fresh, but TfL answered for the lines: [checks] are to be stored alone,
         * the arrivals left as stored ([SnapshotStore.updateLineStatuses]).
         */
        data class Statuses(val checks: Map<String, LineStatusCheck>) : Outcome

        /** Nothing new: the stored snapshot stands, re-rendered so it ages honestly. */
        data object Unchanged : Outcome
    }

    /**
     * Whether each of [snapshot]'s stops wants its station's National Rail board, with [hidden] hidden
     * ([HiddenModes.wantsRailBoard]): not while National Rail is hidden, unless a pinned journey from
     * the stop calls on a National Rail line there. A pin names its lines only by id ([JourneyCall]),
     * so each takes its mode from the stop's declared lines or its last departures; a pin with no
     * calls shows no trains, so it keeps no board either.
     */
    fun railBoards(snapshot: DeparturesSnapshot, hidden: Set<String>): Map<String, Boolean> {
        val pinned = snapshot.journeys.groupBy({ it.originId }, { journey -> journey.calls.map { it.lineId.lowercase() } })
            .mapValues { (_, ids) -> ids.flatten().toSet() }
        return snapshot.stops.associate { stop ->
            val ids = pinned[stop.stopId].orEmpty()
            val starred = stop.lines.filter { it.id.lowercase() in ids } +
                stop.departures.filter { it.lineId.lowercase() in ids }.map { LineRef(it.lineId, it.lineName, it.mode) }
            stop.stopId to HiddenModes.wantsRailBoard(hidden, starred)
        }
    }

    /**
     * One refresh of [prior]: its arrivals ([refreshedArrivals]), then its lines' statuses
     * ([refreshedLineStatuses]), each step deciding at [clock]'s time then. With fresh arrivals it's a
     * snapshot to save. Without any, the lines are checked anyway, against the arrivals stored: a
     * suspension declared during an arrivals outage (the arrivals requests failing while the status
     * one answers) then reaches the widget at once rather than with the next arrivals it can save,
     * and nothing else changes (SPEC D3). Checks TfL didn't answer (every status request failed, or
     * every line was checked moments ago) leave nothing to store.
     */
    suspend fun refresh(
        prior: DeparturesSnapshot,
        clock: () -> Instant,
        arrivalsReuse: Duration = Duration.ZERO,
        statusReuse: Duration = Duration.ZERO,
        shared: ArrivalsCache? = null,
        source: Any? = null,
        railFeed: ((stopId: String) -> RailFeed?)? = null,
        fetchedAt: ((stopId: String) -> Instant?)? = null,
        fetchStatuses: suspend (lineIds: Set<String>) -> List<LineStatus>?,
        fetchArrivals: suspend (stopId: String) -> List<Departure>?,
    ): Outcome {
        val arrivals = refreshedArrivals(prior, clock(), arrivalsReuse, shared, source, railFeed, fetchedAt, fetchArrivals)
        var answered = false
        val checked = refreshedLineStatuses(arrivals ?: prior, clock(), statusReuse, answeredAt = clock) { ids ->
            fetchStatuses(ids)?.also { answered = true }
        }
        return when {
            arrivals != null -> Outcome.Save(checked)
            answered -> Outcome.Statuses(checked.lineStatuses)
            else -> Outcome.Unchanged
        }
    }

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
        // When the oldest part of a fetched stop's arrivals was fetched ([TflClient.fetchedAt]): a
        // National Rail board another screen fetched moments ago keeps its age. Null for none.
        fetchedAt: ((stopId: String) -> Instant?)? = null,
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
            // By the steady clock, as every fetch is stamped ([SteadyClock]).
            val age = SteadyClock.age(stop.fetchedAt, now)
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
                    val stamp = SteadyClock.stamp(now)
                    stop.copy(
                        departures = fetched,
                        fetchedAt = fetchedAt?.invoke(stop.stopId)?.takeIf { it.isBefore(stamp) } ?: stamp,
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
     * one [fetchStatuses] call per request TfL accepts ([LineStatusBatch.request]) and stamped by
     * [answeredAt] once they return — when TfL actually answered, so a slow call neither loses a
     * merge to an earlier check nor lands already expiring ([now] decides only what to ask). A line
     * TfL gave no status for gets a no-verdict check ([LineStatusCheck.known] false), so it isn't
     * marked on a verdict nobody gave. A failed call ([fetchStatuses] returns null) leaves its lines'
     * prior checks as they were, while the other calls' answers apply, to age out at the staleness
     * threshold like a countdown (D4);
     * until a check is made the line reads as unchecked ([DeparturesSnapshot.statusKnown]).
     * Lines no longer shown are dropped either way.
     */
    suspend fun refreshedLineStatuses(
        snapshot: DeparturesSnapshot,
        now: Instant,
        reuse: Duration = Duration.ZERO,
        answeredAt: () -> Instant = { now },
        fetchStatuses: suspend (lineIds: Set<String>) -> List<LineStatus>?,
    ): DeparturesSnapshot {
        val lines = LineStatusCheck.linesOf(snapshot.stops)
        val kept = snapshot.lineStatuses.filterKeys { it in lines }
        val toAsk = lines.filterTo(HashSet()) { id ->
            val prior = kept[id] ?: return@filterTo true
            // By the steady clock, as every check is stamped ([LineStatusCheck]).
            val age = SteadyClock.age(prior.checkedAt, now)
            // One checked while a lookup of which way its alerts apply was running is asked again:
            // reused, it would show them both ways for the whole reuse window.
            age.isNegative || age >= reuse || prior.status.awaitingDirections
        }
        if (toAsk.isEmpty()) return snapshot.copy(lineStatuses = kept)
        val results = LineStatusBatch.request(toAsk) { chunk -> fetchStatuses(chunk.toSet()) }
        if (!results.anyAnswered) return snapshot.copy(lineStatuses = kept)
        val answered = answeredAt()
        // Stamped by the steady clock, as a fetch is ([SteadyClock]).
        val at = SteadyClock.stamp(answered)
        val returned = results.answers.flatMap { it.value }
            .filter { it.lineId in toAsk }.associate { it.lineId to LineStatusCheck(it, at) }
        // A line asked about that TfL left out gets a no-verdict check, so it replaces the old one
        // here and in the store's merge alike, rather than the old disruption being kept (an absent
        // entry reads as "nothing new") until it ages out. Only lines in a request TfL answered: a
        // failed one's lines keep their prior checks.
        val fresh = results.answeredIds.filter { it in toAsk }.associateWith { returned[it] ?: LineStatusCheck.noVerdict(it, at) }
        return snapshot.copy(lineStatuses = LineStatusCheck.newest(kept, fresh, lines, answered))
    }
}
