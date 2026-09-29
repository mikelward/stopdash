package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Coordinates
import app.stopdash.domain.Departure
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LocationFix
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The trip on the way (SPEC *On the way*), process-wide: started from a trip's open route, followed on
 * each [refresh] from its train's calls ([OnTheWay]), and kept on the device ([save]) so it outlives
 * the app being closed. Its [trip] and [progress] feed the trip's screen and the main view's card.
 * The caller drives [refresh] (about every 30 s while shown); nothing here schedules itself.
 */
class ActiveTripTracker(
    // The kept trip, read once on [restore]; blocking, run on [io].
    private val load: () -> ActiveTrip?,
    // Keep the trip, or forget it (null): whether that worked; blocking, run on [io].
    private val save: (ActiveTrip?) -> Boolean,
    // A stop's departures, for picking a leg's train.
    private val arrivals: suspend (String) -> List<Departure>,
    private val vehicles: VehicleSource,
    // Where a station can be walked into, by its stop id ([OnTheWay.seen]): its entrances as well as
    // its one published point. Asked once per station while the rider walks to it.
    private val entrances: suspend (String) -> List<Coordinates> = { emptyList() },
    // A line's route, placing a ride's stops ([OnTheWay.ridePositions]) so a fix can see the rider
    // already along it ([OnTheWay.seenAlong]); null when it can't be had. The trip's cards already
    // hold it, so it's rarely a request.
    private val lineSequence: suspend (String) -> LineSequence? = { null },
    private val clock: () -> Instant = Instant::now,
    // A monotonic clock in ms, for timing a wait the wall clock could be set back during.
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Coarse facts only — a line id, an error kind, never a stop or where the rider is going.
    private val warn: (String) -> Unit = {},
    // "Get off soon", once per leg ([OnTheWay.shouldWarn]): whether it was said, so one that
    // couldn't be (notifications off) is tried again on the next refresh.
    private val onGetOffSoon: (ActiveTrip, TripProgress.Riding) -> Boolean = { _, _ -> true },
    // The rider has moved past a leg whose "get off soon" was said (or arrived): it's done with.
    private val onGetOffSoonDone: () -> Unit = {},
) {
    private val _trip = MutableStateFlow<ActiveTrip?>(null)
    val trip: StateFlow<ActiveTrip?> = _trip.asStateFlow()

    private val _progress = MutableStateFlow<TripProgress?>(null)
    val progress: StateFlow<TripProgress?> = _progress.asStateFlow()

    // Whether the last refresh couldn't reach TfL: the screen says the trip isn't current.
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    // When a refresh last reached TfL for the trip; null until one has. [progress] is only live
    // while this is recent ([isCurrent]).
    private val _updatedAt = MutableStateFlow<Instant?>(null)
    val updatedAt: StateFlow<Instant?> = _updatedAt.asStateFlow()

    // Whether the trip couldn't be saved on the device (a restart may lose it, or bring it back out of date): said, not hidden.
    private val _notKept = MutableStateFlow(false)
    val notKept: StateFlow<Boolean> = _notKept.asStateFlow()

    // Whether End trip couldn't forget the trip on the device, so it's still on the way.
    private val _endFailed = MutableStateFlow(false)
    val endFailed: StateFlow<Boolean> = _endFailed.asStateFlow()

    // How many times End (or an arrival) couldn't forget the trip: a new count for each, so a
    // screen that reopens on one reopens on the next too.
    private val _endFailures = MutableStateFlow(0)
    val endFailures: StateFlow<Int> = _endFailures.asStateFlow()

    /**
     * The departures at the boarding stop of the ride the rider is on their way to
     * ([OnTheWay.upcomingRide]), fetched with each refresh while they walk, change or wait, so the
     * trip's screen shows every train that takes them on ([OnTheWay.boardTrains]). Null while riding,
     * and until the first fetch. [failed] when the last fetch couldn't reach TfL, so the screen says so
     * (principle 2): the last board is kept, its [fetchedAt] aging it into stale (D4), and with none
     * read yet [fetchedAt] is null and there are no departures.
     */
    data class NextBoard(val ride: TripLeg, val departures: List<Departure>, val fetchedAt: Instant?, val failed: Boolean = false)

    private val _nextBoard = MutableStateFlow<NextBoard?>(null)
    val nextBoard: StateFlow<NextBoard?> = _nextBoard.asStateFlow()

    // Every train the upcoming ride's board has listed ([readBoard]), by TfL's id for it, for
    // [boardedAlong]: one that has since left may be the rider's. Of one ride only, in memory only.
    private var boardSeenRide: TripLeg? = null
    private val boardSeen = LinkedHashMap<String, Departure>()

    private val lock = Mutex()

    // Each station's entrances once read ([entrances]); a failed read isn't kept, so the next
    // refresh asks again. Held for the process only: a station's entrances don't move.
    private val stationEntrances = HashMap<String, List<Coordinates>>()

    // [stopId]'s entrances ([entrances]), read once; none while they can't be, so the walk ends on
    // its placed position or its time as before, and the failure is logged.
    private suspend fun entrancesOf(stopId: String): List<Coordinates> {
        if (stopId.isBlank()) return emptyList()
        stationEntrances[stopId]?.let { return it }
        return try {
            entrances(stopId).also { stationEntrances[stopId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException.NotFound) {
            // TfL doesn't know the station: asking again won't change that.
            warn("on the way: station entrances lookup failed: NotFound")
            emptyList<Coordinates>().also { stationEntrances[stopId] = it }
        } catch (e: TflException) {
            warn("on the way: station entrances lookup failed: ${e::class.simpleName}")
            emptyList()
        }
    }
    private var restored = false
    // Whether the trip shown isn't yet known to be on the device ([keep]).
    private var unsaved = false

    /** Read the kept trip, once; a trip started meanwhile wins. */
    /** Reads the kept trip, once; false when it couldn't be read, to be tried again. */
    suspend fun restore(): Boolean = lock.withLock { restoreLocked() }

    private suspend fun restoreLocked(): Boolean {
        if (restored) return true
        // Read before it counts as restored: a read cut short (the activity recreated), or one that
        // failed, is tried again. A trip that couldn't be read is kept, not taken for none.
        val kept = try {
            withContext(io) { load() }
        } catch (e: IOException) {
            warn("on the way: kept trip unreadable: ${e::class.simpleName}")
            _failed.value = true
            return false
        }
        restored = true
        _failed.value = false
        if (kept == null) {
            // No trip on the way: an alert left from one ended just before the app died goes too.
            onGetOffSoonDone()
            return true
        }
        if (_trip.value == null) {
            // A move saved just before the app died, before it could take back the "get off soon"
            // for the leg left ([goTo]): taken back now. Marked on the trip, not guessed from its
            // warning, which also lags an alert said just before the app died (Codex, PR #351).
            val trip = if (kept.alertLeft) {
                onGetOffSoonDone()
                unsaved = true
                kept.copy(alertLeft = false)
            } else {
                kept
            }
            _trip.value = trip
            _progress.value = standing(trip, clock())
        }
        return true
    }

    /**
     * Start [route] to [destinationName], the rider at its first stop by [readyAt]: its first ride's
     * train is picked on the next [refresh].
     */
    suspend fun start(route: TripRoute, destinationName: String, readyAt: Instant) = lock.withLock {
        // One trip at a time: a kept one not read yet, or one on the way, stays. One that couldn't be
        // read may be on the way, so none is started over it; the failure is said ([failed]).
        if (!restoreLocked()) return@withLock
        if (_trip.value != null) return@withLock
        val now = clock()
        // From the first leg, a walk included: the rider walks it first (maintainer, 2026-09-27), and
        // the ride after it picks its train once the walk is done. The walk from where the rider is
        // to where the route starts ([readyAt]) is a walk too, as the route shows it, whether the
        // Planner's route starts with a ride or a walk of its own.
        val first = route.legs.firstOrNull()
        val trip = if (first != null && readyAt.isAfter(now)) {
            val toStop = TripLeg(TripLeg.WALKING, "", "", "", "", first.fromId, first.fromName, now, readyAt)
            ActiveTrip(TripRoute(listOf(toStop) + route.legs), destinationName, startedAt = now, legStartedAt = now)
        } else {
            ActiveTrip(route, destinationName, startedAt = now, legIndex = 0, legStartedAt = readyAt)
        }
        _updatedAt.value = null
        boardSeenRide = null
        boardSeen.clear()
        keep(trip, OnTheWay.advance(trip, null, now).second)
    }

    private val _starting = MutableStateFlow(0)

    /** Starts in flight ([launchStart]): counted from the tap, so a follower doesn't take one for none. */
    val starting: StateFlow<Int> = _starting.asStateFlow()

    /** [start] in [scope], counted in [starting] at once, before the trip is saved. */
    fun launchStart(scope: CoroutineScope, route: TripRoute, destinationName: String, readyAt: Instant): Job {
        _starting.update { it + 1 }
        return scope.launch {
            try {
                start(route, destinationName, readyAt)
            } finally {
                _starting.update { it - 1 }
            }
        }
    }

    /**
     * The rider says they're at [to] ([OnTheWay.atStep]): **Next**, or a step tapped. The trip moves
     * there now, and a ride's train is picked at once, as a refresh would.
     */
    suspend fun goTo(from: OnTheWay.Step, to: OnTheWay.Step) = lock.withLock {
        val before = _trip.value ?: return@withLock
        if (_progress.value == TripProgress.Arrived) return@withLock
        // Asked from step [from], as the screen showed it: a refresh that moved the trip on while the
        // tap waited makes it stale, and acting on it could send the trip back (Codex, PR #351).
        if (OnTheWay.stepOf(before) != from) return@withLock
        val now = clock()
        // Never onto an arrival, which would forget the trip past any undoing ([OnTheWay.canGoTo]).
        if (!OnTheWay.canGoTo(before, to, now)) return@withLock
        // Leaving a leg whose "get off soon" was said, the move is saved marked as owing its
        // take-back, which a restart settles if the app dies before it's done ([restore]). On board
        // the same ride it was said for, it still stands.
        val moved = OnTheWay.atStep(before, to, now).let { at ->
            at.copy(alertLeft = before.warnedLeg == before.legIndex && at.warnedLeg != before.warnedLeg)
        }
        // Saved before it's made: a move that can't be kept isn't made, and says so ([notKept]). A
        // move made but not kept would leave the "get off soon" at odds with the trip a restart
        // brings back, with no way to tell a taken-back alert from one the rider tapped away
        // (Codex, PR #351). One cut short may or may not have landed, so it's saved again later.
        unsaved = true
        val saved = withContext(io) { save(moved) }
        unsaved = !saved
        _notKept.value = !saved
        if (!saved) return@withLock
        // The "get off soon" said for the leg left is done with: the stop it named isn't where they
        // are. Its mark is cleared in the next save.
        if (moved.alertLeft) {
            onGetOffSoonDone()
            unsaved = true
        }
        _trip.value = moved.copy(alertLeft = false)
        // The step moved to has no answer of its own yet: the last one's isn't passed off as its
        // (a ride's time, its next stop still blank), which waits for the pick below (Codex, PR #384).
        _updatedAt.value = null
        _progress.value = standing(moved, now, picking = true)
        val boards = HashMap<TripLeg, Result<NextBoard>>()
        if (step(null, boards)) step(null, boards)
    }

    /** End the trip: forgotten here and on the device. */
    suspend fun end(): Boolean = lock.withLock {
        // Forgotten on the device first, as on arrival. One that can't be would come back on the
        // next start, so it isn't ended: it stays, and [endFailed] says so. An arrival already
        // forgotten has nothing left to forget: only what's on screen is let go.
        if (_trip.value != null && !withContext(io) { save(null) }) {
            _endFailed.value = true
            _endFailures.value++
            return@withLock false
        }
        _endFailed.value = false
        _endFailures.value = 0
        _trip.value = null
        _progress.value = null
        _nextBoard.value = null
        _failed.value = false
        _updatedAt.value = null
        _notKept.value = false
        unsaved = false
        true
    }

    /**
     * Bring the trip up to date: pick its leg's train if none is followed (the soonest the rider can
     * catch that runs where they're going), fetch the followed train's calls, and move the trip on.
     * A trip that has arrived is forgotten, its [progress] left at [TripProgress.Arrived] to say so.
     * [rider], a fix taken when [OnTheWay.wantsFix], shows a rider left behind by their train, and
     * the next one is picked ([OnTheWay.seen]).
     */
    suspend fun refresh(rider: LocationFix? = null) {
        val asked = elapsed()
        lock.withLock {
            // The fix aged while this waited behind another refresh: one no longer fresh enough is
            // no evidence the rider was left behind ([OnTheWay.sureEnough]).
            val fresh = rider?.let { fix -> aged(fix, Duration.ofMillis(elapsed() - asked)) }
            // A leg just done (a walk, or a ride straight into another) picks the next ride's train at
            // once: one due before the next refresh is still the rider's to catch.
            // Each boarding stop's board is asked for at most once a refresh, answer or failure,
            // however long TfL takes: the steps share this refresh's attempts.
            val boards = HashMap<TripLeg, Result<NextBoard>>()
            if (step(fresh, boards)) step(null, boards)
        }
    }

    // [fix] as it stands [waited] later, or null once that makes it too old to act on. One whose age
    // isn't known is taken as it came.
    private fun aged(fix: LocationFix, waited: Duration): LocationFix? {
        val age = fix.ageMillis ?: return fix
        val now = age + waited.toMillis().coerceAtLeast(0)
        return if (now > OnTheWay.FIX_FRESH_WITHIN_MILLIS) null else fix.copy(ageMillis = now)
    }

    // One step of [refresh]: at most one leg change. True when it left a ride with no train yet,
    // reached by that change or its train dropped, so another step picks one.
    private suspend fun step(rider: LocationFix?, boards: MutableMap<TripLeg, Result<NextBoard>>): Boolean {
        if (_trip.value == null) return false
        if (_progress.value == TripProgress.Arrived) {
            // Arrived, but not yet forgotten on the device: only that's tried again, not the ride,
            // which a train round again on its next lap could seem to restart.
            if (withContext(io) { save(null) }) {
                _endFailed.value = false
                _endFailures.value = 0
                _trip.value = null
            }
            return false
        }
        // A station's entrances, read (once) before anything else: the step then takes its time, and
        // ages the fix by the read, after TfL has answered, so neither a walk's end nor a fix's
        // freshness is judged at a moment already past (Codex, PR #352).
        val reading = elapsed()
        // The station walked to, or the one a train nearly there gets off at ([OnTheWay.seen]).
        val station = if (rider != null) {
            _trip.value?.let { OnTheWay.stationWalkedTo(it, clock())?.fromId ?: OnTheWay.stationRiddenTo(it, clock())?.toId }
        } else {
            null
        }
        val entrances = station?.let { entrancesOf(it) }.orEmpty()
        // When [seenRider]'s age holds: a use after further reads ages it again ([boardedAlong]).
        val seenAt = elapsed()
        val seenRider = if (station == null) rider else rider?.let { aged(it, Duration.ofMillis(seenAt - reading)) }
        val now = clock()
        // The train followed coming in, before a fix may have dropped it ([OnTheWay.seen]).
        val followed = _trip.value?.vehicleId.orEmpty()
        val before = _trip.value ?: return false
        var trip = OnTheWay.seen(before, seenRider, now, entrances)
        // Left behind by a train get off soon was already said for: the stop it named was that
        // train's, so it's taken back, and said again for the next train in its time.
        // Seen off at their stop, too: the alert has done its job.
        // Taken back once this step is saved ([keep]), not before: a restart before then brings back
        // the leg it was said for, which would count it as said and never say it again (Codex, PR #359).
        if (before.warnedLeg == before.legIndex && (trip.warnedLeg != before.warnedLeg || trip.legIndex != before.legIndex)) {
            trip = trip.copy(alertLeft = true)
        }
        var failed = false
        val leg = trip.leg
        // The board where the rider boards next, fetched once a refresh: shown on the trip's screen,
        // and the one a train is picked from.
        val board = fetchBoard(trip, now, boards)
        var calls: List<VehicleCall>? = null
        // While a change runs, no train is picked or asked about: one picked now could be revised to
        // leave before the change is done, and be taken for the rider's.
        // Whether this step already looked for a train on its leg: none found, it isn't looked for
        // again at once (a TfL request each), only on the next refresh.
        var searched = false
        // Seen along the ride while its train is still awaited: they're on a train that has left the
        // boarding stop, whichever the trip was following (maintainer, 2026-09-29).
        // The leg this step began on, once the fix has moved it: [boardedAlong] can finish the ride.
        val stepLeg = trip.legIndex
        val along = seenRider?.let { boardedAlong(trip, it, seenAt, now) }
        if (along != null) {
            trip = along.first
            calls = along.second
        } else if (leg != null && !leg.isWalk && OnTheWay.changeUntil(trip, now) == null) {
            try {
                if (trip.vehicleId.isBlank()) {
                    searched = true
                    val picked = pick(trip, now, board)
                    if (picked != null) {
                        trip = OnTheWay.follow(trip, picked.first)
                        calls = picked.second
                    }
                } else {
                    calls = vehicles.vehicleCalls(trip.vehicleId, leg.lineId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                if (trip.vehicleId.isBlank()) {
                    // [pick] passes over a train TfL doesn't know, so this is the boarding stop's board:
                    // a failed update, not a train still being found.
                    warn("on the way: boarding stop not found on line ${leg.lineId}")
                    failed = true
                } else {
                    warn("on the way: train not found on line ${leg.lineId}")
                    // A train not yet boarded that TfL no longer knows: dropped, so the next refresh
                    // picks another. Once on board it stays followed: TfL has only gone quiet on it.
                    if (trip.boarded) failed = true else trip = trip.copy(vehicleId = "", vehicleOffId = "", boardsAt = null, dueOffAt = null)
                }
            } catch (e: TflException) {
                warn("on the way: train lookup failed for line ${leg.lineId}: ${e::class.simpleName}")
                failed = true
            }
        }
        if (failed) {
            // Nothing new is known: the step stands as it was, not Lost, and the last answer is no
            // longer stood behind on any surface (its time, stops left and get off soon wait).
            _failed.value = true
            _updatedAt.value = null
            // The step as it was, unless a fix just moved the trip on: left behind (finding a train,
            // never the ride the rider isn't on), or seen at the stop they walked to (waiting there,
            // never still walking).
            val same = trip.vehicleId == before.vehicleId && trip.legIndex == before.legIndex
            keep(trip, _progress.value?.takeIf { same } ?: standing(trip, now))
            return false
        }
        var (next, progress) = OnTheWay.advance(trip, calls, now)
        // A train that turned out not to be the rider's: drop it, so the next refresh picks another.
        // Once on board it stays followed: TfL has only gone quiet on it.
        if (progress is TripProgress.Lost && calls != null && !next.boarded) next = next.copy(vehicleId = "", vehicleOffId = "", dueOffAt = null)
        if (trip.warnedLeg == trip.legIndex && (next.legIndex != trip.legIndex || progress == TripProgress.Arrived)) {
            next = next.copy(alertLeft = true)
        }
        // The train lost on the leg it was said for: the stop it named may not be the rider's now, so
        // it's taken back, and said again once the train is found on the leg. A failed lookup (above)
        // leaves it: nothing new is known, and the stop is still the one planned.
        if (progress is TripProgress.Lost && next.warnedLeg == next.legIndex) {
            next = next.copy(warnedLeg = -1, alertLeft = true)
        }
        // Said again, silently, when the stop's time moves, so the alert's deadline follows it.
        val saidAt = (_progress.value as? TripProgress.Riding)?.takeIf { next.warnedLeg == next.legIndex }?.getOffAt
        val moved = progress is TripProgress.Riding && progress.getOffSoon && next.warnedLeg == next.legIndex &&
            progress.getOffAt != saidAt
        if (progress is TripProgress.Riding && (OnTheWay.shouldWarn(next, progress) || moved) && onGetOffSoon(next, progress)) {
            // There is one alert: this one replaces any still to be taken back.
            next = OnTheWay.warned(next).copy(alertLeft = false)
        }
        // The ride ahead changed in this step: a board for one now boarded (or passed) is no longer
        // theirs to board from, and the next ride's (off a train and walking on) is read now, not a
        // refresh later. A failed read is said on the section alone: the step itself stood.
        val ahead = OnTheWay.upcomingRide(next)
        if (_nextBoard.value?.ride != ahead) {
            if (ahead == null) _nextBoard.value = null else fetchBoard(next, now, boards)
        }
        _failed.value = false
        _updatedAt.value = now
        if (progress == TripProgress.Arrived) {
            // Forgotten on the device first: the trip gone from the screen can cancel the caller.
            // One that can't be would come back on the next start, so it's kept, said, and tried
            // again on the next refresh.
            _progress.value = progress
            if (!withContext(io) { save(null) }) {
                // Counted once, as End's is, not on each retry: the screen reopens on it once.
                if (!_endFailed.value) _endFailures.value++
                _endFailed.value = true
                return false
            }
            _endFailed.value = false
            _endFailures.value = 0
            _trip.value = null
            // Forgotten: no trip left for an alert to belong to.
            if (next.alertLeft) onGetOffSoonDone()
        } else {
            keep(next, progress)
        }
        // A ride with no train yet, reached now or with its train just dropped, picks one at once.
        val onward = next.legIndex != stepLeg || (followed.isNotBlank() && !next.boarded && !searched)
        return onward && next.leg?.isWalk == false && next.vehicleId.isBlank()
    }

    // [trip] on board the train the rider was seen along the ride on ([OnTheWay.seenAlong]), with its
    // calls: the most recent of the trains its board listed that has left the boarding stop and is on
    // the ride at or beyond where they were seen ([OnTheWay.boardedOn]). Null when they aren't seen
    // along it, or none of the few asked after is theirs: the trip then waits as it was, claiming
    // nothing (principle 1). Seen where they get off, the ride is done, with no calls ([OnTheWay.rideDone]).
    // [rider]'s age holds at [seenAt] ([elapsed]).
    private suspend fun boardedAlong(trip: ActiveTrip, rider: LocationFix, seenAt: Long, now: Instant): Pair<ActiveTrip, List<VehicleCall>?>? {
        val leg = OnTheWay.waitingToBoard(trip, now) ?: return null
        val sequence = try {
            lineSequence(leg.lineId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: route lookup failed for line ${leg.lineId}: ${e::class.simpleName}")
            null
        } ?: return null
        // Aged by the board's and the route's reads: after a slow TfL, a fix fresh when the step began
        // may be where the rider was, not where they are, and no proof they boarded (Codex, PR #383).
        val seen = aged(rider, Duration.ofMillis(elapsed() - seenAt)) ?: return null
        val along = OnTheWay.seenAlong(trip, seen, OnTheWay.ridePositions(leg, sequence), now) ?: return null
        // Whichever train took them there: no train's calls are needed, nor would one still calling
        // there say anything but "get off" to a rider already off (Codex, PR #383).
        if (along.atEnd) return OnTheWay.rideDone(trip, now) to null
        // A bus can use the other pole of either stop pair from the one the Planner named.
        val boardingPoles = OnTheWay.pairPoles(leg.fromArea, sequence)
        val alightingPoles = OnTheWay.pairPoles(leg.toArea, sequence)
        // Gone from the latest board, or due by now: left the stop (the rider, seen away from it, is on one).
        val listed = _nextBoard.value?.takeIf { it.ride == leg }?.departures.orEmpty().mapTo(HashSet()) { it.vehicleId }
        val gone = boardSeen.values.takeIf { boardSeenRide == leg }.orEmpty()
            .filter { it.vehicleId !in listed || !it.expectedArrival.isAfter(now) }
        // The ride's own line's, as every train the trip follows is, and only those its route takes
        // where the rider gets off: another branch's can look like the ride until it turns off, which
        // its calls may not reach yet (Codex, PR #383).
        val left = OnTheWay.takesRide(leg, gone.filter { it.lineId == leg.lineId }, mapOf(leg.lineId to sequence))
            .sortedByDescending { it.expectedArrival }
            .take(PICK_TRIES)
        for (train in left) {
            val calls = try {
                vehicles.vehicleCalls(train.vehicleId, train.lineId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // Gone from TfL's view (at its terminus): not the train of a rider still on their way.
                // Said, coarsely, as every lookup's failure is: the line, never the train or where.
                warn("on the way: train not found on line ${train.lineId}")
                continue
            } catch (e: TflException) {
                warn("on the way: train lookup failed for line ${train.lineId}: ${e::class.simpleName}")
                return null
            }
            // Aged again by each lookup: after a slow one, the fix may be where the rider was, and
            // no proof which of these newer calls they're on (Codex, PR #383).
            aged(rider, Duration.ofMillis(elapsed() - seenAt)) ?: return null
            OnTheWay.boardedOn(trip, train, calls, along.from, now, boardingPoles, alightingPoles, sequence.stopAreas, along.atStop)
                ?.let { return it.first to calls }
        }
        warn("on the way: seen along the ride, but no train that left found on line ${leg.lineId}")
        return null
    }

    // The soonest train the rider can catch on the trip's leg that runs where they're going, with
    // its calls; null when none of the first few does (or none is due).
    private suspend fun pick(trip: ActiveTrip, now: Instant, board: Result<NextBoard>?): Pair<Departure, List<VehicleCall>>? {
        val leg = trip.leg ?: return null
        // On board by the rider's word ([OnTheWay.atStep]): the train they're on is one at the platform
        // when they said so, maybe due a moment before.
        val readyAt = if (trip.boarded) trip.legStartedAt.minus(ON_BOARD_GRACE) else maxOf(trip.legStartedAt, now)
        // This refresh's read of the board ([fetchBoard]); its failure thrown for [step] to report.
        val departures = board?.getOrThrow()?.takeIf { it.ride == leg }?.departures ?: arrivals(leg.fromId)
        // On board by their word, only a train at the platform when they said so can be theirs: one due
        // minutes later isn't, so none is followed rather than that one (the step says it can't find it).
        val candidates = OnTheWay.candidates(departures, leg, readyAt)
            .filter { !trip.boarded || !it.expectedArrival.isAfter(trip.legStartedAt.plus(ON_BOARD_GRACE)) }
            .take(PICK_TRIES)
        for (train in candidates) {
            val calls = try {
                vehicles.vehicleCalls(train.vehicleId, leg.lineId)
            } catch (e: TflException.NotFound) {
                // Still on the board, but TfL no longer knows the train: try the next.
                warn("on the way: train not found on line ${leg.lineId}")
                continue
            }
            // On board by their word, it may have just left the stop, its call there gone from TfL's
            // list: then it's judged from where it is ([OnTheWay.runsOn]).
            val runs = OnTheWay.runsAlong(leg, calls, heading = train.destination) ||
                (trip.boarded && OnTheWay.runsOn(leg, calls, heading = train.destination))
            if (!runs) continue
            // Its own calls may have it leave before the rider can be there, however the board
            // shows it: then it's no train of theirs, and the next is tried now, not next refresh.
            if (OnTheWay.advance(OnTheWay.follow(trip, train), calls, now).second is TripProgress.Lost) continue
            return train to calls
        }
        return null
    }

    // The upcoming ride's board ([nextBoard]), fetched at [now], or null with none upcoming. Asked
    // for once a refresh: a second step reuses [boards]' attempt, answer or failure, so a slow or
    // failing TfL never brings a second request. A fetch that fails keeps the last board, marked
    // failed ([NextBoard.failed]), and hands back the failure for the ride's pick to report.
    private suspend fun fetchBoard(trip: ActiveTrip, now: Instant, boards: MutableMap<TripLeg, Result<NextBoard>>): Result<NextBoard>? {
        val ride = OnTheWay.upcomingRide(trip)
        if (ride == null) {
            _nextBoard.value = null
            return null
        }
        return boards.getOrPut(ride) { readBoard(ride, now) }
    }

    private suspend fun readBoard(ride: TripLeg, now: Instant): Result<NextBoard> =
        try {
            // Stamped by the steady clock, as every fetch is ([SteadyClock]).
            Result.success(NextBoard(ride, arrivals(ride.fromId), SteadyClock.stamp(now)).also { board ->
                _nextBoard.value = board
                if (boardSeenRide != ride) {
                    boardSeenRide = ride
                    boardSeen.clear()
                }
                board.departures.filter { it.vehicleId.isNotBlank() }.forEach { boardSeen[it.vehicleId] = it }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: next board lookup failed for line ${ride.lineId}: ${e::class.simpleName}")
            // Said on the screen, not left to vanish or pass as current: the last board of this ride
            // kept, marked failed; another ride's board is no board of this one's.
            val last = _nextBoard.value?.takeIf { it.ride == ride }
            _nextBoard.value = last?.copy(failed = true) ?: NextBoard(ride, emptyList(), fetchedAt = null, failed = true)
            Result.failure(e)
        }

    // Where [trip] stood when last kept, before any answer: on board with its stop, waiting for its
    // train when it was due, or walking. Not Lost, which only an answer can say, but for on board with
    // no train named: the rider said they were on and none at the platform was found then, so it
    // can't find their train, not that they're riding (Codex, PR #384). Unless [picking], where a
    // pick follows at once ([goTo]) to say which.
    private fun standing(trip: ActiveTrip, now: Instant, picking: Boolean = false): TripProgress {
        val leg = trip.leg ?: return TripProgress.Arrived
        return when {
            leg.isWalk -> OnTheWay.advance(trip, null, now).second
            trip.boarded && trip.vehicleId.isBlank() && !picking -> TripProgress.Lost(leg)
            trip.boarded -> TripProgress.Riding(leg, "", null, trip.dueOffAt, false)
            OnTheWay.changeUntil(trip, now) != null -> TripProgress.Changing(leg, trip.legStartedAt)
            else -> TripProgress.Waiting(leg, trip.boardsAt)
        }
    }

    private suspend fun keep(trip: ActiveTrip, progress: TripProgress) {
        if (trip != _trip.value) unsaved = true
        _trip.value = trip
        _progress.value = progress
        // Saved again after a save that failed, or was cut short, though unchanged, so it's kept
        // once it can be.
        if (unsaved) {
            val saved = withContext(io) { save(trip) }
            unsaved = !saved
            _notKept.value = !saved
        }
        // A "get off soon" done with ([ActiveTrip.alertLeft]) is taken back once the trip that says
        // so is on the device, so a restart can't bring back a trip whose alert is gone; the mark
        // is cleared in the next save.
        if (trip.alertLeft && !unsaved) {
            onGetOffSoonDone()
            _trip.value = trip.copy(alertLeft = false)
            unsaved = true
        }
    }

    companion object {
        /**
         * How old the last answer can be and still be shown as live: a refresh or two missed. Back
         * after the app was away longer, a trip's live details wait for the next answer.
         */
        val CURRENT_FOR: Duration = Duration.ofSeconds(75)

        /** Whether a trip last brought up to date at [updatedAt] is live at [now]. */
        fun isCurrent(updatedAt: Instant?, now: Instant): Boolean =
            updatedAt != null && !now.isAfter(updatedAt.plus(CURRENT_FOR))

        // How many of a leg's soonest trains are looked up to find one running where the rider is going.
        const val PICK_TRIES = 3

        // How far from when the rider said they're on board the train they boarded can be due, either
        // way: a train at the platform, due a moment ago or about to leave.
        val ON_BOARD_GRACE: Duration = Duration.ofMinutes(1)
    }
}
