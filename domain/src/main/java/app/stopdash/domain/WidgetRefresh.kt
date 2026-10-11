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
        // A journey-only stop's longer window on the timer ([refreshedArrivals]).
        farArrivalsReuse: Duration = Duration.ZERO,
        shared: ArrivalsCache? = null,
        source: Any? = null,
        railFeed: ((stopId: String) -> RailFeed?)? = null,
        fetchedAt: ((stopId: String) -> Instant?)? = null,
        // Each line's last answer, shared with the other surfaces ([refreshedLineStatuses]).
        sharedLines: LineStatusCache? = null,
        fetchStatuses: suspend (lineIds: Set<String>) -> List<LineStatus>?,
        fetchArrivals: suspend (stopId: String) -> List<Departure>?,
    ): Outcome {
        val arrivals = refreshedArrivals(prior, clock(), arrivalsReuse, farArrivalsReuse, shared, source, railFeed, fetchedAt, fetchArrivals)
        val base = arrivals ?: prior
        val checked = refreshedLineStatuses(base, clock(), statusReuse, answeredAt = clock, shared = sharedLines, fetchStatuses = fetchStatuses)
        return when {
            arrivals != null -> Outcome.Save(checked)
            // A line answered now, by TfL or by another surface's check in the shared cache: stored,
            // so the widget doesn't keep drawing the check it replaced (Codex on #765).
            checked.answersNew(base) -> Outcome.Statuses(checked.lineStatuses)
            else -> Outcome.Unchanged
        }
    }

    suspend fun refreshedArrivals(
        prior: DeparturesSnapshot,
        now: Instant,
        reuse: Duration = Duration.ZERO,
        // On the timer, a journey-only stop (one the rider isn't near) is carried over for this longer
        // window, as the app carries over a stop past the walking reach ([ArrivalsReuse]). Zero (the
        // default) treats every stop alike, as a tap or a watch's request does.
        farReuse: Duration = Duration.ZERO,
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
            val window = ArrivalsReuse.window(reuse, farReuse, automatic = true, isFar = stop.stopId in prior.journeyOnlyStopIds)
            return stop.arrivalsFresh && !age.isNegative && age < window && sameSource(stop)
        }
        // Kept, taken from another screen's fetch, or asked, by the rule the app's list uses too
        // ([DepartureSource]): a newer fetch by another screen is taken even over a recent one of the
        // widget's own, so it never shows older times than the app.
        val choices = prior.stops.map { stop ->
            DepartureSource.choose(stop, shared?.recent(stop.stopId, now, source), reusable = recent(stop))
        }
        // Every stop's arrivals in parallel; the client's shared request pool bounds how many are in
        // flight. [fetchArrivals] returns null on failure rather than throwing, so one stop failing
        // never cancels the others.
        val fetchedByStop = coroutineScope {
            prior.stops.mapIndexed { i, stop ->
                async { if (choices[i] == DepartureSource.Choice.Fetch) fetchArrivals(stop.stopId) else null }
            }.awaitAll()
        }
        // Worth storing only when something came back ([anyNew]) and a nearby stop's arrivals are fresh
        // ([SaveGate]): fetched now, or carried over as fetched moments ago, as the app counts them.
        val judged = SaveGate.judged(prior.stops, SaveGate.nearIds(prior)).mapTo(HashSet()) { it.stopId }
        var anyNew = false
        var anyFresh = false
        val stops = prior.stops.mapIndexed { i, stop ->
            when (val choice = choices[i]) {
                is DepartureSource.Choice.Shared -> {
                    anyNew = true
                    if (stop.stopId in judged) anyFresh = true
                    // With the National Rail feed that fetch found ("No key" once a key is removed).
                    val entry = choice.entry
                    return@mapIndexed Snapshot.withArrivals(stop, entry.departures, entry.fetchedAt, entry.railFeed)
                }
                DepartureSource.Choice.Kept -> {
                    if (stop.stopId in judged) anyFresh = true
                    return@mapIndexed stop
                }
                DepartureSource.Choice.Fetch -> Unit
            }
            // A failed fetch keeps the aged last-good, marked not-fresh so its stale withhold fires
            // and it can't render as fresh within the freshness window (Codex P1 on #56); its own
            // fetchedAt still drives age-based staleness. The app's list merges by the same rule.
            val fetched = fetchedByStop[i]
            if (fetched != null) {
                anyNew = true
                if (stop.stopId in judged) anyFresh = true
            }
            val stamp = SteadyClock.stamp(now)
            Snapshot.withArrivals(
                stop,
                fetched,
                fetchedAt = DepartureSource.stamp(stamp, fetchedAt?.invoke(stop.stopId)),
                railFeed = if (railFeed != null) railFeed(stop.stopId) else stop.railFeed,
            )
        }
        if (!anyNew || !anyFresh) return null
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
        // Each line's last answer, whichever surface asked ([LineStatusCache.SHARED] in the app): one
        // that still stands and is newer than the snapshot's own is taken rather than asked again, and
        // this refresh's answers are kept there for the others. Null (tests) shares none.
        shared: LineStatusCache? = null,
        fetchStatuses: suspend (lineIds: Set<String>) -> List<LineStatus>?,
    ): DeparturesSnapshot {
        val lines = LineStatusCheck.linesOf(snapshot.stops)
        // A request another surface has out for these lines is waited on, its answers then taken below
        // rather than asked again alongside it; read as of after the wait, as they're stamped then.
        var waited = shared?.awaitAsking(lines) == true
        while (true) {
            // Taken before the cache is read, so an answer landing after it is read rather than asked again.
            val since = shared?.mark() ?: 0L
            val readAt = if (waited) answeredAt() else now
            val taken = if (shared == null) emptyMap() else lines.mapNotNull { id ->
                when (val held = shared[id]) {
                    // Taken within the window even while it waits on its alerts' directions: newer, it
                    // replaces the snapshot's own check, and still being asked again below (Codex on #765).
                    is LineStatusCache.Held.Verdict -> held.takeIf { LineStatusReuse.stands(it.at, readAt, reuse) }
                        ?.let { id to LineStatusCheck(it.status, it.at) }
                    is LineStatusCache.Held.Omitted ->
                        held.takeIf { LineStatusReuse.stands(it.at, readAt, reuse) }?.let { id to LineStatusCheck.noVerdict(id, it.at) }
                    null -> null
                }
            }.toMap()
            val kept = LineStatusCheck.newest(snapshot.lineStatuses, taken, lines, readAt)
            // Asked again unless its last check still stands, by the rule the app's list uses too.
            val toAsk = lines.filterTo(HashSet()) { id ->
                val prior = kept[id] ?: return@filterTo true
                !LineStatusReuse.stands(prior.checkedAt, readAt, reuse, prior.status.awaitingDirections)
            }
            if (toAsk.isEmpty()) return snapshot.copy(lineStatuses = kept)
            // The request, marked as under way so another surface waits on it rather than asking too, its
            // answers held for the others before the mark comes off.
            suspend fun request(): Pair<Instant, Map<String, LineStatusCheck>>? {
                // Taken before asking, so a later request's answers aren't replaced by these landing after them.
                val ticket = shared?.ticket() ?: 0L
                val results = LineStatusBatch.request(toAsk) { chunk -> fetchStatuses(chunk.toSet()) }
                if (!results.anyAnswered) return null
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
                shared?.let { cache ->
                    fresh.forEach { (id, check) ->
                        val answer = if (check.known) {
                            LineStatusCache.Held.Verdict(check.checkedAt, check.status)
                        } else {
                            // A line TfL doesn't know stays marked so: the app then stops asking about it rather
                            // than reading it as a check that failed (Codex on #765).
                            LineStatusCache.Held.Omitted(check.checkedAt, unrecognized = id in results.unknown)
                        }
                        cache.set(id, answer, ticket)
                    }
                }
                return answered to fresh
            }
            val ran = if (shared == null) {
                request()
            } else {
                when (val asked = shared.asking(toAsk, since) { request() }) {
                    is LineStatusCache.Asked.Ran -> asked.value
                    // Another surface claimed a line first: its answers are read on the next pass.
                    LineStatusCache.Asked.Waited -> {
                        waited = true
                        continue
                    }
                }
            }
            val (answered, fresh) = ran ?: return snapshot.copy(lineStatuses = kept)
            return snapshot.copy(lineStatuses = LineStatusCheck.newest(kept, fresh, lines, answered))
        }
    }

    /** Whether any line's check here is one [before] didn't have: a new answer, however it was had. */
    fun DeparturesSnapshot.answersNew(before: DeparturesSnapshot): Boolean =
        lineStatuses.any { (id, check) -> before.lineStatuses[id] != check }
}
