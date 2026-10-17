package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * A National Rail train as one station's board with calling points shows it ([RailBoardSource.runs]),
 * for following the train a trip's rider is on (SPEC *On the way*). Darwin names no train the way TfL
 * does: a train is known by the station it was picked at and when it was due to leave there
 * ([RailFollow.Key]), which every later board names among its stops before ([previous]).
 *
 * [atCrs] is the board's station; [scheduled] and [expected] its departure there, [expected] null
 * where the board gives no estimate: [canceled] there, or "Delayed". [previous] are its stops before,
 * each by its scheduled time there, [RailPoint.left] where the board gives when it was there, else with
 * its estimate, on the portion that runs from its first origin to the board's station; [joining] each
 * other portion that joins it on the way, by its own stops (Darwin gives one list per origin), kept apart,
 * since a rider on one doesn't pass the other's stops ([RailFollow.find], Codex, #883). [next] those it
 * calls at after, through [destinationCrs], each with its estimate where the board gives one (canceled
 * stops left out, so they end where it now ends, one cut short included). [throughTerminus] where the
 * board gave its stops after here at all, and each stop on its way by its code, so [next] is every one it
 * still calls at; false for a train canceled here whose board gave none, which says nothing of where it
 * ends, and for one with a stop on its way Darwin has no code for ("???"), passed over in [previous],
 * [joining] and [next], which could be a trip's stop (Codex, #883). Public network data.
 */
data class RailRun(
    val atCrs: String,
    val atName: String,
    val lineId: String,
    val scheduled: Instant,
    val expected: Instant?,
    val platform: String?,
    val destinationCrs: String,
    val previous: List<RailPoint>,
    val next: List<RailPoint>,
    val canceled: Boolean = false,
    val joining: List<List<RailPoint>> = emptyList(),
    val throughTerminus: Boolean = true,
)

/**
 * A board's [runs] and how old the board already was when it arrived ([age]: the relay's `Age`, zero
 * straight from National Rail), kept with the list so a board listing none is dated too ([RailBoard.age]).
 */
data class RailRuns(val runs: List<RailRun>, val age: Duration = Duration.ZERO)

/**
 * A stop on a [RailRun]: its station code and cleaned name, when it was due there, and when it's expected.
 * One before the board's station is [left] where the board gives when it was there (Darwin's actual time,
 * [expected] being that time, or null for "No report"): the only word that the train has gone from it.
 */
data class RailPoint(val crs: String, val name: String, val scheduled: Instant?, val expected: Instant?, val left: Boolean = false)

/**
 * How a National Rail train is followed (SPEC *On the way*): the id a trip keeps for it
 * ([ActiveTrip.vehicleId]), and its calls read off a board ([calls]), the shape TfL's
 * `/Vehicle/{id}/Arrivals` gives a TfL train's, so the trip follows it the same way.
 */
object RailFollow {
    private const val PREFIX = "rail:"

    /**
     * What identifies a followed train: where it was picked ([fromCrs]), when it was due to leave there,
     * its terminus, and the line it runs on ([RailRun.lineId], its operator's): two trains can share the
     * other three on one board, another operator's on another platform (Codex, #883).
     */
    data class Key(val fromCrs: String, val scheduled: Instant, val destinationCrs: String, val lineId: String)

    /** The id a trip follows a train by ([Departure.vehicleId]) from the board at [fromCrs]. */
    fun idOf(fromCrs: String, scheduled: Instant, destinationCrs: String, lineId: String): String =
        "$PREFIX$fromCrs/${scheduled.epochSecond}/$destinationCrs/$lineId"

    /** [id]'s [Key], or null when it isn't a National Rail train's ([idOf]): a TfL vehicle's. */
    fun keyOf(id: String): Key? {
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split('/')
        if (parts.size != 4 || parts.any { it.isBlank() }) return null
        val seconds = parts[1].toLongOrNull() ?: return null
        return Key(parts[0], Instant.ofEpochSecond(seconds), parts[2], parts[3])
    }

    /**
     * The train [key] names among [runs], one station's board, on the same line, to the same terminus and
     * due to leave the station it was picked at when it was then: there, by its departure; elsewhere, by that station
     * among the stops it called at, whose time a board gives as its departure there (Darwin's
     * `previousCallingPoints`), the same timetable event. Only that one time, never the nearest: a
     * train due there a minute or two either side is another train (Codex, #883). Two that fit are
     * neither, never a guess.
     */
    @WorkerThread
    fun find(runs: List<RailRun>, key: Key): RailRun? =
        runs.filter { run ->
            run.destinationCrs == key.destinationCrs && run.lineId == key.lineId &&
                key.scheduled == if (run.atCrs == key.fromCrs) run.scheduled else before(run, key.fromCrs).lastOrNull { it.crs == key.fromCrs }?.scheduled
        }.singleOrNull()

    /**
     * [run]'s calls from its board, soonest first, for the train picked at [fromCrs]: the stops between there
     * and the board's station it hasn't left by the board's word ([RailPoint.left]), a board listing a train
     * before it gets there (Codex, #883); the board's station, which it's still to leave as the board lists
     * it; then each stop on with an estimate, up to the first without one (a time past it can't be known).
     * Null with no time at a stop it hasn't left before the board's station, or at the board's station
     * ("Delayed", or [RailRun.canceled] where it was picked): its later calls alone would say it has gone on
     * past (Codex, #883). Canceled at a station further on, it's left out: Darwin cancels a call at one
     * station, and a train that won't call there can still run on to the stops the board gives after
     * (Codex, #883). Where those run through where it now ends with an estimate at each, the last
     * [VehicleCall.endsRun]: every call it has left, so a stop missing from them, past one it was cut short
     * at included, is one it won't call at, never one not yet known (maintainer, 2026-10-16). Empty where its
     * run ended before the board's station, every stop from there canceled (Codex, #883). [stopIdsFor] gives every TfL id of a station code, as the trip's stops are named ([callAt]); a
     * station with none is matched by its name.
     */
    @WorkerThread
    fun calls(run: RailRun, fromCrs: String, stopIdsFor: (String) -> Set<String>): List<VehicleCall>? {
        val before = notLeft(run, fromCrs)
        if (before.any { it.expected == null }) return null
        val here = if (run.canceled && run.atCrs != fromCrs) {
            emptyList()
        } else {
            listOf(callAt(run.atCrs, run.atName, run.platform, run.expected ?: return null, stopIdsFor))
        }
        val calls = before.map { callAt(it.crs, it.name, null, it.expected!!, stopIdsFor) } + here + ahead(run.next, stopIdsFor)
        val all = run.throughTerminus && run.next.all { it.expected != null }
        // None left, an empty list carries no mark: every stop on canceled, its run ended before here, it's empty;
        // with what's past here unknown, nothing is known (Codex, #883).
        if (calls.isEmpty()) return if (all) emptyList() else null
        return if (all) calls.dropLast(1) + calls.last().copy(endsRun = true) else calls
    }

    // [run]'s stops from [fromCrs] up to the board's station that it hasn't left: those after the last the board
    // says it has. Those before [fromCrs] are no part of the ride, so none of what's followed.
    private fun notLeft(run: RailRun, fromCrs: String): List<RailPoint> {
        if (run.atCrs == fromCrs) return emptyList()
        val before = before(run, fromCrs)
        val from = before.indexOfLast { it.crs == fromCrs }.coerceAtLeast(0)
        return before.drop(from).takeLastWhile { !it.left }
    }

    // [run]'s stops before its board's station as the train picked at [fromCrs] makes them: the portion it was
    // picked on, then, past that portion's own stops, the first one's on from where they joined, which runs on to
    // the board's station. Never another portion's, whose stops a rider on this one doesn't pass (Codex, #883).
    private fun before(run: RailRun, fromCrs: String): List<RailPoint> {
        if (run.joining.isEmpty() || run.previous.any { it.crs == fromCrs }) return run.previous
        val portion = run.joining.firstOrNull { list -> list.any { it.crs == fromCrs } } ?: return run.previous
        val end = portion.last().scheduled ?: return portion
        return portion + run.previous.filter { it.scheduled?.isAfter(end) == true }
    }

    // [points]' calls up to the first without an estimate.
    private fun ahead(points: List<RailPoint>, stopIdsFor: (String) -> Set<String>): List<VehicleCall> =
        points.takeWhile { it.expected != null }.map { callAt(it.crs, it.name, null, it.expected!!, stopIdsFor) }

    // A call at the station with [crs], by its one TfL id where it has one, and by every id it has
    // ([VehicleCall.stationIds]): St Pancras's three are one station to a train, whichever of them the
    // trip's stop is named by.
    private fun callAt(crs: String, name: String, platform: String?, at: Instant, stopIdsFor: (String) -> Set<String>): VehicleCall {
        val ids = stopIdsFor(crs)
        return VehicleCall(ids.singleOrNull().orEmpty(), name, platform, at, ids)
    }

    /**
     * Where to ask after [run]'s train, picked at [fromCrs], at [now]: among the stations it hasn't left as
     * its board says, the same its calls are made from ([calls]): those before the board's own it hasn't
     * left, so a station a board further on said it's held at is never lost (Codex, #883), the board's own,
     * and each stop on, less its terminus, which no departure board lists it at. A board lists a train from
     * well before it gets there, and says which stops before it the train hasn't left ([RailPoint.left]), so
     * [ahead] are asked every refresh: the first it isn't due to have left by its last estimates (one with none says nothing either
     * way), or the last before its terminus once it's due to have left them all, which lists it wherever it
     * is before then, and the one past it, for one that left a little early. [behind] are the stations
     * before that, in the order it calls, where a train held past its estimates may be while a board further
     * on, ten trains long, leaves it out (Codex, #883): asked one a refresh in turn, so a train no board
     * lists costs a few requests a refresh, never one per station (Codex, #883).
     */
    @WorkerThread
    fun toAsk(run: RailRun, fromCrs: String, now: Instant): Asks {
        val stops = notLeft(run, fromCrs).map { it.crs to it.expected } + (run.atCrs to run.expected) +
            run.next.filter { it.crs != run.destinationCrs }.map { it.crs to it.expected }
        val due = stops.indexOfFirst { (_, at) -> at != null && at.isAfter(now) }.let { if (it < 0) stops.lastIndex else it }
        val ahead = stops.drop(due).take(2).map { it.first }.distinct()
        return Asks(ahead, stops.take(due).map { it.first }.distinct().filterNot { it in ahead })
    }

    /** The boards [toAsk] names: [ahead] every refresh, [behind] one a refresh in turn. */
    data class Asks(val ahead: List<String>, val behind: List<String>)

    /**
     * How many minutes on, from [now], to start the board at the station a train is due to leave at [at]
     * (Darwin's `timeOffset`): none for one due within [LEAD] and a [STEP], else the latest step that's at
     * least [LEAD] before it, at most [MAX_OFFSET]. A board with calling points lists ten trains, and ten to
     * its terminus can leave before one picked from the departures list's twenty, an hour out, so that one is
     * asked for from near its time (Codex, #883). In steps, so the trains a pick asks after share a read.
     */
    fun offsetMinutes(at: Instant, now: Instant): Int {
        val ahead = Duration.between(now, at).minus(LEAD).toMinutes()
        if (ahead < STEP) return 0
        return (ahead / STEP * STEP).toInt().coerceAtMost(MAX_OFFSET)
    }

    private val LEAD: Duration = Duration.ofMinutes(10)
    private const val STEP = 15L

    // The latest step within Darwin's `timeOffset`, which goes to 119.
    private const val MAX_OFFSET = 105
}

/**
 * The trains a trip follows, TfL's and National Rail's (SPEC *On the way*): a National Rail train's id
 * ([RailFollow.keyOf]) is answered from the boards ([runs]); any other goes to TfL ([tfl]).
 *
 * Darwin has no one place a train's calls are listed as it runs, so its calls come from the board at
 * the station it's due to leave next: that board lists it, with its stops on and their estimates,
 * until it leaves. Each train's last board is kept ([last]) to say where to ask ([RailFollow.toAsk]): the
 * board at the station it's due at next by its estimates, which lists it wherever it is before then, the
 * one past it, and one station before that in turn ([turn]), until one lists it. That's usually one board,
 * at most three; a check whether a train is the rider's asks only the first ([candidateCalls]). Whichever lists it says where it is: a board names the stops it has left by when it was
 * there ([RailPoint.left]), so a train is gone from a station only on a later board's word, never for a
 * board leaving it out, which a board of ten trains can for one still there (Codex, #883). Listed on none
 * of them, where it is isn't known: a failed update, past its last board on its way to its terminus too. With none known (the app restarted after it left the station it was
 * picked at), it's [TflException.NotFound], as TfL says of a train it no longer knows, once the board
 * where it was picked reaches past it; short of it, unknown, a failed update. With no key ([runs] null),
 * a failed update too, never a train no longer known: a key still being read back after a restart is
 * none yet, and the train picked before it is the one to go on with (Codex, #883). A station's board is asked toward the train's terminus, and where it was picked from near when
 * it's due there ([RailFollow.offsetMinutes]), so it lists the train among the next few, and a board read is reused for [REUSE_FOR] by any train it answers, so the trains a pick asks
 * after (a request each with TfL) share one; an answer is as old as the read it came from ([readAt]).
 */
class RailVehicles(
    private val tfl: VehicleSource,
    // A station's board with calling points toward a terminus, by their codes, from so many minutes on
    // ([RailFollow.offsetMinutes]); null with no key.
    private val runs: suspend (String, String, Int) -> RailRuns?,
    // Every TfL stop id of a station code, as a trip names its stops.
    private val stopIdsFor: (String) -> Set<String>,
    private val clock: () -> Instant = Instant::now,
    // Coarse facts only: a station code and an error kind, never where the rider gets off.
    private val warn: (String) -> Unit = {},
) : VehicleSource {
    private val last = ConcurrentHashMap<String, RailRun>()

    // For a train no board has listed since, which of the stations [RailFollow.Asks.behind] to ask next.
    private val turn = ConcurrentHashMap<String, Int>()

    // When each train's [last] board was read, in the steady frame ([SteadyClock]): what its calls are as old
    // as ([readAt]), however the clock is set meanwhile.
    private val listedAt = ConcurrentHashMap<String, Instant>()

    private class Read(val at: Instant, val runs: List<RailRun>)

    private val reads = ConcurrentHashMap<Triple<String, String, Int>, Read>()

    override suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall> = calls(vehicleId, lineId, candidate = false)

    // A check whether it's the rider's train asks one board, where it's due next or was picked, never the
    // search a train being followed gets, so a refresh checking a few trains asks a board after each, never
    // three (Codex, #883; maintainer, 2026-10-16). One not listed there is unknown this time.
    override suspend fun candidateCalls(vehicleId: String, lineId: String): List<VehicleCall> = calls(vehicleId, lineId, candidate = true)

    private suspend fun calls(vehicleId: String, lineId: String, candidate: Boolean): List<VehicleCall> {
        val key = RailFollow.keyOf(vehicleId)
            ?: return if (candidate) tfl.candidateCalls(vehicleId, lineId) else tfl.vehicleCalls(vehicleId, lineId)
        // Now in the steady frame ([SteadyClock]), for every comparison and stamp here: set back after a board was
        // read, the wall clock would make the stations it has passed read as still to come, and leave the boards
        // further on unasked until it caught up (Codex, #883).
        val now = SteadyClock.stamp(clock())
        val known = last[vehicleId]
        val asks = known?.let { RailFollow.toAsk(it, key.fromCrs, now) }
        val behind = asks?.behind.orEmpty()
        val next = turn[vehicleId] ?: 0
        val ask = asks?.let {
            if (candidate) it.ahead.take(1) else it.ahead + listOfNotNull(behind.getOrNull(next % behind.size.coerceAtLeast(1)))
        } ?: listOf(key.fromCrs)
        var picked: List<RailRun>? = null
        // The first board that failed: one station's board failing says nothing of the others, any of which may
        // list it (Codex, #883), so it's the answer only where none does.
        var failed: TflException? = null
        for (crs in ask) {
            // Where it was picked, from near when it's due there: an hour out, ten others to its terminus can be
            // listed first (Codex, #883). Elsewhere it's due soon, among the first.
            val offset = if (crs == key.fromCrs) RailFollow.offsetMinutes(key.scheduled, now) else 0
            val read = try {
                read(crs, key.destinationCrs, offset, now)
            } catch (e: TflException) {
                failed = failed ?: e
                continue
            }
            if (crs == key.fromCrs) picked = read.runs
            val found = RailFollow.find(read.runs, key) ?: continue
            last[vehicleId] = found
            listedAt[vehicleId] = read.at
            turn -= vehicleId
            if (found.canceled) warn("on the way: national rail train canceled at ${found.atCrs}")
            // Listed with no time here: canceled where it was picked, so not one to board there, as TfL says of a
            // train it no longer knows; or "Delayed", here or at a stop it hasn't left before, nothing known, a
            // failed update. Canceled at a station further on, it calls on past it ([RailFollow.calls], Codex, #883).
            val calls = RailFollow.calls(found, key.fromCrs, stopIdsFor) ?: run {
                if (found.canceled && found.atCrs == key.fromCrs) throw TflException.NotFound(null)
                throw TflException.Unreachable("no estimate on its way to ${found.atCrs}", null)
            }
            // Its run ended before here, with no call left to say so: a train no longer known, as TfL says of one
            // that has finished, never an empty list a trip can read as the rider having got off (Codex, #883).
            if (calls.isEmpty()) {
                warn("on the way: national rail train's run ended before ${found.atCrs}")
                throw TflException.NotFound(null)
            }
            return calls
        }
        if (known == null) {
            // The board where it was picked, the one asked, failed: that board's failure, as before.
            failed?.let { throw it }
            // Never seen, it's gone only where the board where it was picked reached past it, listing one due there
            // after it. A board that stops short of it, ten listed before it or its time beyond the window Darwin
            // gives, says nothing of it (Codex, #883), and nor does its time having gone by, as for a train held
            // there (maintainer, 2026-10-16; Codex, #883): unknown, asked again next refresh.
            val reached = picked.orEmpty().any { it.scheduled.isAfter(key.scheduled) }
            if (!reached) {
                warn("on the way: national rail train not yet within its board at ${key.fromCrs}")
                throw TflException.Unreachable("not yet within its board", null)
            }
            warn("on the way: national rail train not listed at ${key.fromCrs}")
            throw TflException.NotFound(null)
        }
        // On no board asked: held where a full board leaves it out, not listed further on yet, or past its last
        // board. Which isn't known, so it's never taken to have gone anywhere (Codex, #883). The next refresh asks
        // the next station behind in turn.
        if (!candidate && behind.isNotEmpty()) turn[vehicleId] = (next + 1) % behind.size
        // A board that failed is the update's failure, as the board's own (offline, rate-limited…).
        failed?.let { throw it }
        warn("on the way: national rail train not listed from ${known.atCrs} on")
        throw TflException.Unreachable("not listed", null)
    }

    // A National Rail train's calls are as old as the board that last listed it; a TfL one's, TfL's say.
    override fun readAt(vehicleId: String): Instant? =
        if (RailFollow.keyOf(vehicleId) != null) listedAt[vehicleId] else tfl.readAt(vehicleId)

    // [crs]'s board toward [towards] from [offset] minutes on, as read within [REUSE_FOR], else read now; [now]
    // and each read's time in the steady frame, so a read's age is its real one.
    private suspend fun read(crs: String, towards: String, offset: Int, now: Instant): Read {
        val key = Triple(crs, towards, offset)
        reads[key]?.takeIf { now.isBefore(it.at.plus(REUSE_FOR)) && !now.isBefore(it.at) }?.let { return it }
        val runs = try {
            runs(crs, towards, offset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: national rail board failed at $crs: ${e::class.simpleName}")
            throw e
        } ?: run {
            // No key to ask by: no word on the train, so a failed update, never one no longer known, which would
            // give up the train picked before a restart while its stored key is still being read back (Codex, #883).
            warn("on the way: national rail board at $crs not asked: no key")
            throw TflException.Unreachable("no national rail key", null)
        }
        reads.entries.removeIf { !now.isBefore(it.value.at.plus(REUSE_FOR)) }
        // Dated by the board's own age, not the ask, listing trains or none (Codex, #944).
        return Read(now.minus(runs.age), runs.runs).also { reads[key] = it }
    }

    companion object {
        /** How long one board's read answers every train it lists: a pick's few, one refresh. */
        val REUSE_FOR: Duration = Duration.ofSeconds(20)
    }
}
