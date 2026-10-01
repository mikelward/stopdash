package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.RideLines
import app.stopdash.domain.Staleness
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripClosures
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The lines a ride may be taken on now ([lines], [RideLineChecks.running]), and those a check that
 * failed could have added ([uncheckedLines], by id): a line's route, its status, or one of its stops
 * couldn't be read. Such a line isn't offered, and the trip falls back on the Planner's, but it may be
 * running: the trip went without it, and says so where it could have changed what it shows (SPEC *On
 * the way*). Which use that is is each caller's to judge: one picking from a board, only where the
 * board lists a train of it ([uncheckedOn]).
 */
data class RideLinesNow(val lines: List<TripLeg>, val uncheckedLines: Set<String> = emptySet()) {
    /** Whether any line went unchecked. */
    val unchecked: Boolean get() = uncheckedLines.isNotEmpty()

    /** Whether [departures] list a train of a line that went unchecked: one that might have been offered. */
    fun uncheckedOn(departures: List<Departure>): Boolean = departures.any { it.lineId in uncheckedLines }
}

/**
 * The lines a ride of a trip on the way may be taken on now (SPEC *On the way*): the ones the trip's
 * cards offer, by the same rule ([RideLines.of], [RideLines.running]). That's the Planner's, and
 * another line whose route runs between the same two stops, once it's checked as running (one
 * batched status request, [LineStatusBatch]) from stops checked open (the closure checks the screens
 * share, [closures]), and isn't one the rider avoids ([hidden]). Each comes as it runs the ride, its
 * own stops between, so a train is checked against its own line's. A ride with no other line asks
 * nothing; a line whose check fails isn't offered, so the trip falls back on the Planner's line, and
 * that's said ([RideLinesNow.unchecked]), never passed off as checked.
 */
internal class RideLineChecks(
    private val client: TflClient,
    private val closures: StopClosureChecks,
    private val closureCache: StopClosureCache,
    // A line's route (the day's): which lines run between the ride's two stops, and by which stops.
    private val sequence: suspend (String) -> LineSequence?,
    // The modes and lines the rider hides, an avoided line among them.
    private val hidden: () -> Set<String>,
    private val clock: () -> Instant,
    private val io: CoroutineDispatcher,
    // Coarse facts only: an error kind, a count, never a stop or line the rider is going by.
    private val warn: (String) -> Unit,
) {
    // Each line's status as last answered (null: TfL gave none) and when, by the steady clock: a trip
    // refreshes more often than a line's status changes, so one answer serves a few refreshes.
    private val statuses = HashMap<String, Pair<LineStatus?, Instant>>()
    private val lock = Mutex()

    /**
     * The lines [ride] of [route] may be taken on now, given the [boards] read at its boarding stop, by
     * stop id: its own pole's, and those of its stop pair's other poles the trip read
     * ([RideLines.polesToRead]), where another line may board. The Planner's first when it's among them
     * ([RideLines.vouched]), and those that went unchecked.
     */
    suspend fun running(route: TripRoute, ride: TripLeg, boards: Map<String, List<Departure>>): RideLinesNow {
        val hidden = hidden()
        // The stops the trip read, as the cards' stop pair ([RideLines.of]'s areaPoles): its own and its pair's.
        val areaPoles = if (ride.fromArea.isBlank()) emptyMap() else mapOf(ride.fromArea to boards.keys.toList())
        val unread = HashSet<String>()
        val sequences = RideLines.lineIds(listOf(route), boards, areaPoles, hidden).associateWith { lookUp(it, unread) }
        val lines = RideLines.of(listOf(route), boards, areaPoles, sequences, hidden)[ride] ?: RideLines.only(ride)
        // Another line of this ride ([RideLines.candidateIds]) whose route couldn't be read isn't known to
        // run it, so it can't be offered. One asked after for another ride of the plan bears on that
        // ride, not this one (Codex, PR #460).
        val routeUnread = RideLines.candidateIds(ride, listOf(route), boards, areaPoles, hidden).filterTo(HashSet()) { it in unread }
        // The Planner's alone: nothing to check, as the trip followed before.
        if (lines.legs.size == 1) return RideLinesNow(lines.legs, routeUnread)
        val now = clock()
        val ticket = closureCache.ask(now)
        // The other lines' own stops: the Planner's line is judged where the route is ranked, as on the cards.
        val stops = lines.legs.drop(1).flatMap { listOf(it.fromId, it.toId) }.distinct()
        val (asked, checked) = coroutineScope {
            val asked = async { statusesOf(lines.legs.map { it.lineId }.distinct()) }
            val checked = async { closures.check(stops, ticket, now) }
            asked.await() to checked.await()
        }
        val (known, unanswered) = asked
        val at = clock()
        // Only checks still current count ([Staleness]): a stop's reused lookup ages from when it was asked.
        val current = checked.found.filterKeys { id -> checked.at[id]?.let { !Staleness.isStale(it, at) } == true }
        val running = lines.running(LineStatus.asOf(known, at), TripClosures.opens(current, checked.failed, at))
        // The lines an answer that couldn't be had could have added: the same rule
        // ([RideLines.running]) again, with every status not answered taken as a good service and every
        // stop not checked now as open. Asking the rule, rather than mirroring it, keeps every case it
        // decides: the Planner's line left out for its own unknown status (Codex, PR #460), a line known
        // not to run whatever its stops say (Codex, PR #460), and any it comes to decide later.
        val unknownStops = stops.filter { it in checked.failed || it !in current }
        val hoped = lines.running(
            LineStatus.asOf(known + unanswered.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }, at),
            TripClosures.opens(current + unknownStops.associateWith { emptyList() }, emptySet(), at),
        )
        val offered = running.mapTo(HashSet()) { it.lineId }
        return RideLinesNow(running, routeUnread + hoped.map { it.lineId }.filterNot { it in offered })
    }

    // [ids]' statuses, each reused for [STATUS_REUSE] and no longer; a line whose check failed is
    // left out, its last answer with it once that's older: unknown, so not checked as running. A line
    // TfL is down for isn't offered on an answer from before (Codex, PR #451). With them, the ids with
    // no answer to go on (TfL saying it knows no status for a line is an answer).
    private suspend fun statusesOf(ids: List<String>): Pair<Map<String, LineStatus>, Set<String>> = lock.withLock {
        val now = clock()
        val due = ids.filter { id -> statuses[id]?.let { (_, at) -> SteadyClock.age(at, now) >= STATUS_REUSE } ?: true }
        if (due.isNotEmpty()) {
            val results = LineStatusBatch.request(due) { chunk -> withContext(io) { client.lineStatuses(chunk) } }
            results.failure?.let { warn("on the way: ride line status failed for ${results.failed.size} line(s): ${it::class.simpleName}") }
            val stamp = SteadyClock.stamp(clock())
            val answered = results.answers.flatMap { it.value }.associateBy { it.lineId }
            results.answeredIds.forEach { id -> statuses[id] = answered[id] to stamp }
        }
        val at = clock()
        val answered = ids.associateWith { id -> statuses[id]?.takeIf { (_, stamp) -> SteadyClock.age(stamp, at) < STATUS_REUSE } }
        answered.mapNotNull { (id, answer) -> answer?.first?.let { id to it } }.toMap() to
            answered.filterValues { it == null }.keys
    }

    // [line]'s route, or null without one; [unread] gains it when the read failed.
    private suspend fun lookUp(line: String, unread: MutableSet<String>): LineSequence? =
        try {
            sequence(line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            // Without its route the line isn't known to run between the two stops: not offered.
            warn("on the way: route lookup failed for line $line: ${e::class.simpleName}")
            unread += line
            null
        }

    private companion object {
        // How long a line's status serves the trip's refreshes before it's asked again.
        val STATUS_REUSE: Duration = Duration.ofSeconds(60)
    }
}
