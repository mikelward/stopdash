package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.DismissalMarks
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.HubInfo
import app.stopdash.domain.HubInfoCache
import app.stopdash.domain.TripClosures
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.Staleness
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.TflClient
import app.stopdash.domain.TripProgress
import java.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a trip on the way's "Route disruption" alert goes by (SPEC *On the way*), asked on each of the
 * trip's refreshes: the coming lines' statuses, one batched request to TfL ([LineStatusBatch]); the
 * stops still to reach, through the closure checks the trip's screen and the list share ([closures],
 * each stop reused for five minutes); and the rider's dismissals ([dismissed]), so what they cleared
 * on the trip stays cleared here. Only a check that succeeded is evidence: a line or stop whose check
 * failed is left out ([RouteDisruption.signals]), and nothing is claimed for it.
 */
internal class RouteDisruptionChecks(
    private val client: TflClient,
    private val closures: StopClosureChecks,
    private val closureCache: StopClosureCache,
    // Interchanges' names, shared with the list ([HubInfoCache.SHARED]), for titling a station's note.
    private val hubNames: HubInfoCache,
    // Where an interchange's names are looked up, past the check that wanted them: never waited on.
    private val background: CoroutineScope,
    // The rider's dismissals, read on each check and settled against the lines it answered.
    private val dismissedStore: DismissedAlertsStore,
    // A line's route (the day's), for where a stop sits ([RouteDisruption.StopPlace]); null when it can't be had.
    private val sequence: suspend (String) -> LineSequence?,
    // A stop's interchange from the bundled index, where no route of the trip's names one.
    private val hubOf: (String) -> String?,
    private val clock: () -> Instant,
    private val io: CoroutineDispatcher,
    // Coarse facts only: an error kind, a count, never a stop or line the rider is going by.
    private val warn: (String) -> Unit,
) {
    /**
     * Checks [trip] as it stands ([progress]): [directions] is the direction a coming leg's trains are
     * seen going, by leg, where one is known ([LineStatus.forDirection]).
     */
    suspend fun check(trip: ActiveTrip, progress: TripProgress?, directions: Map<Int, String>): RouteDisruption.Found {
        if (progress == null || progress == TripProgress.Arrived) return RouteDisruption.Found.NONE
        val lines = RouteDisruption.comingLines(trip)
        val stops = RouteDisruption.comingStops(trip, progress).map { it.value }.distinctBy { it.id }
        val now = clock()
        // The dismissals so far, before anything is asked: one made after is newer than this check's
        // verdict, so its settling never lets go of it ([reconcileDismissals]).
        val ticket = closureCache.ask(now, dismissedStore.mark())
        val (statuses, checked) = coroutineScope {
            val statuses = async { statuses(lines) }
            val checked = async { closures.check(stops.map { it.id }, ticket, now) }
            statuses.await() to checked.await()
        }
        // Each stop as old as its own answer, a reused lookup perhaps (each stop is its own place,
        // [stopDismissalCheck]); the lines, asked afresh, as old as this check.
        val since = DismissalMarks(ticket.dismissals, checked.dismissals)
        var cleared = try {
            dismissedStore.dismissed().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Read back as nothing dismissed: the alert may then say what the rider cleared, never hide what they didn't.
            warn("on the way: dismissals unreadable: ${e::class.simpleName}")
            emptySet()
        }
        // Only checks still current count ([Staleness]): a stop's reused lookup ages from when it was asked.
        val at = clock()
        val current = checked.found.filterKeys { id -> checked.at[id]?.let { !Staleness.isStale(it, at) } == true }
        // Placed only where a notice needs it: a check with nothing to show asks no route (Codex, PR #441).
        val places = places(trip, stops.filter { !current[it.id].isNullOrEmpty() })
        // A dismissed alert TfL no longer reports is forgotten, as the trip's screen and the list do, so
        // the same alert coming back later is heard again even when nothing else checks its line or stop
        // (Codex, PR #441): each line answered, and each stop checked, as its own place, settled against
        // what this check found live.
        // Every live alert's identity, each line's under way ones included: on [io], never the caller's
        // (the main) thread (Codex on #519).
        val (live, checkedPlaces) = withContext(io) {
            val lineCheck = statuses?.let { lineDismissalCheck(it.statuses, it.answered, at) } ?: (emptySet<DismissedAlert>() to emptySet())
            val stopCheck = stopDismissalCheck(current, at)
            (lineCheck.first + stopCheck.first) to (lineCheck.second + stopCheck.second)
        }
        reconcileDismissals(
            cleared, live, checkedPlaces, dismissedStore, io, warn, "on the way", since,
            pruned = { gone -> cleared = cleared - gone },
            restored = { back -> cleared = cleared + back },
        )
        // The routes of the coming bus lines whose alert could be left out for naming only stops off
        // the ride ([RouteDisruption.offRide]), read exactly as [RouteDisruption.signals] reads them —
        // the same day, direction and dismissals — so one that can't change the answer costs no route;
        // each asked for once, from the shared cache (Codex, PR #455).
        val alerted = withContext(io) { RouteDisruption.routesWanted(trip, statuses?.statuses.orEmpty(), directions, cleared, at) }
        // At once, not in turn: the check waits on all of them, and the route repository already caps
        // its own concurrent fetches (Codex, PR #455).
        val sequences = coroutineScope { alerted.map { line -> async { lookUp(line)?.let { line to it } } }.awaitAll() }
            .filterNotNull().toMap()
        // The coming stations' other notices, shown on the trip's screen, never alerted (maintainer,
        // 2026-10-04): found first unnamed, to know which interchanges to name.
        val unnamed = withContext(io) { RouteDisruption.stationNotes(trip, progress, current, places, cleared, at) }
        val notePlaces = withContext(io) { named(unnamed, places) }
        var leftOff = 0
        // Every alert of every coming line weighed against the ride, and how long what's found stands: on
        // [io], never the caller's (the main) thread (Codex on #519).
        val found = withContext(io) {
            val signals = RouteDisruption.signals(trip, progress, statuses?.statuses.orEmpty(), directions, current, places, cleared, at, sequences) { leftOff++ }
            fun staleAt(stamp: Instant) = at.plus(Staleness.remainingUntilStale(SteadyClock.age(stamp, at).toKotlinDuration()).toJavaDuration())
            // Each note stands no longer than its stop's check, nor than its notices in force there.
            val notes = (if (notePlaces === places) unnamed else RouteDisruption.stationNotes(trip, progress, current, notePlaces, cleared, at))
                // Each of its notices as long as the latest check of the stops listing it, and the note as long
                // as the first of them (Codex, #567).
                .map { note ->
                    // Each stop vouches for a notice as long as both its check and its own listing of it last;
                    // a notice stands on the latest of its stops, and the note on the first notice to go
                    // (Codex, #567). One with no checked stop vouching stands no longer than now.
                    val notices = note.support.map { stops ->
                        stops.mapNotNull { (id, ends) -> checked.at[id]?.let(::staleAt)?.let { stale -> ends?.let { minOf(it, stale) } ?: stale } }
                            .maxOrNull() ?: at
                    }
                    note.copy(until = notices.minOrNull() ?: at)
                }
            val notesUntil = notes.mapNotNull { it.until }.minOrNull()
            if (signals.isEmpty()) return@withContext RouteDisruption.Found(emptyList(), null, notes = notes, notesUntil = notesUntil)
            // Stale no later than the oldest check behind a signal.
            val stamps = signals.mapNotNull { signal ->
                when (signal) {
                    is RouteDisruption.Signal.Line -> statuses?.at
                    is RouteDisruption.Signal.Stop -> checked.at[signal.stopId]
                    // A change's board is the tracker's to read, never this check's.
                    is RouteDisruption.Signal.Unpredicted -> null
                }
            }
            val stale = stamps.minOrNull()?.let(::staleAt)
            // Nor past the end of a notice in force at a stop it names: one ending changes what that
            // stop's card says, so it isn't left standing as current until a check notices (Codex, PR #441).
            val ends = signals.filterIsInstance<RouteDisruption.Signal.Stop>()
                .flatMap { current[it.stopId].orEmpty() }
                .filter { it.isActiveAt(at) }
                .mapNotNull { it.validTo }
            // Each signal's own: its check's, and for a stop, the end of a notice in force there, so letting
            // go of one doesn't leave the rest standing only as long as it would have (Codex on #519).
            val stands = signals.mapNotNull { signal ->
                when (signal) {
                    is RouteDisruption.Signal.Line -> statuses?.at?.let(::staleAt)
                    is RouteDisruption.Signal.Stop -> listOfNotNull(
                        checked.at[signal.stopId]?.let(::staleAt),
                        current[signal.stopId].orEmpty().filter { it.isActiveAt(at) }.mapNotNull { it.validTo }.minOrNull(),
                    ).minOrNull()
                    is RouteDisruption.Signal.Unpredicted -> null
                }?.let { signal.key to it }
            }.toMap()
            RouteDisruption.Found(signals, listOfNotNull(stale, ends.minOrNull()).minOrNull(), stands, notes, notesUntil)
        }
        // Said, never quietly dropped (principle 1): which stops it named stays out of the log.
        if (leftOff > 0) warn("on the way: $leftOff line alert(s) left out, naming only stops off the ride")
        return found
    }

    // What a check of the coming lines' statuses found: the [statuses] TfL returned, the lines it gave a
    // verdict on ([answered], a status or none), and when ([at], by the steady clock, as a fetch is stamped).
    private class LineCheck(val statuses: Map<String, LineStatus>, val answered: Set<String>, val at: Instant)

    // The [lines]' statuses, or null when none was answered. A line whose group failed is left out:
    // unknown, not running normally.
    private suspend fun statuses(lines: List<String>): LineCheck? {
        if (lines.isEmpty()) return null
        val results = LineStatusBatch.request(lines) { chunk -> withContext(io) { client.lineStatuses(chunk) } }
        results.failure?.let { warn("on the way: line status failed for ${results.failed.size} line(s): ${it::class.simpleName}") }
        if (!results.anyAnswered) return null
        return LineCheck(results.answers.flatMap { it.value }.associateBy { it.lineId }, results.answeredIds, SteadyClock.stamp(clock()))
    }

    // Where each of [stops] sits, as the trip's closure cards place it ([routeClosures]): its interchange
    // and stop area from the route of a ride that calls there, else the bundled index and a bus stop's pair.
    private suspend fun places(trip: ActiveTrip, stops: List<TripClosures.End>): Map<String, RouteDisruption.StopPlace> {
        val rides = trip.route.rides
        // Each line's route asked for once a check, a failed one included (Codex, PR #441).
        val sequences = HashMap<String, LineSequence?>()
        return stops.associate { end ->
            val lines = rides.filter { it.fromId == end.id || it.toId == end.id }.map { it.lineId }.distinct()
            val route = lines.firstNotNullOfOrNull { line ->
                if (line !in sequences) sequences[line] = lookUp(line)
                sequences[line]
            }
            val hub = (route?.stopHubs?.get(end.id) ?: hubOf(end.id))?.takeIf { it.startsWith(HUB_PREFIX) }.orEmpty()
            end.id to RouteDisruption.StopPlace(area = route?.stopAreas?.get(end.id) ?: end.area, hub = hub)
        }
    }

    // [places] with the names of each interchange a note is at ([HubInfo]), so the note is titled by it
    // and leads with none of them (Codex, #567): only those already known ([hubNames], the list's too).
    // One not yet known is looked up in the [background] for a later check, never waited on, so naming
    // a note never holds up an alert (Codex, #567); until then the note keeps the stop's own name.
    private fun named(notes: List<RouteDisruption.StationNote>, places: Map<String, RouteDisruption.StopPlace>): Map<String, RouteDisruption.StopPlace> {
        val hubs = notes.mapNotNullTo(LinkedHashSet()) { note -> places[note.stopId]?.hub?.takeIf { it.isNotEmpty() } }
        if (hubs.isEmpty()) return places
        val infos = hubs.mapNotNull { hub -> hubNames[hub]?.let { hub to it } }.toMap()
        hubs.filter { it !in infos }.forEach { hub -> background.launch { hubNames.load(hub) { hubInfo(hub) } } }
        if (infos.isEmpty()) return places
        return places.mapValues { (_, place) -> infos[place.hub]?.let { place.copy(hubName = it.name, aliases = it.aliases) } ?: place }
    }

    // An interchange's names from TfL ([TflClient.hubInfo]); empty when it can't be had, so a later
    // check asks again.
    private suspend fun hubInfo(hubId: String): HubInfo =
        try {
            withContext(io) { client.hubInfo(hubId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("on the way: hub lookup failed: ${e::class.simpleName}")
            HubInfo()
        }

    private suspend fun lookUp(line: String): LineSequence? =
        try {
            sequence(line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Placed as best it can be without it: a dismissal made where the route placed it may not match.
            warn("on the way: route unreadable: ${e::class.simpleName}")
            null
        }

    private companion object {
        const val HUB_PREFIX = "HUB"
    }
}
