package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.TflException
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.time.Duration
import java.time.Instant
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
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Coarse facts only — a line id, an error kind, never a stop or where the rider is going.
    private val warn: (String) -> Unit = {},
    // "Get off soon", once per leg ([OnTheWay.shouldWarn]): whether it was said, so one that
    // couldn't be (notifications off) is tried again on the next refresh.
    private val onGetOffSoon: (ActiveTrip, TripProgress.Riding) -> Boolean = { _, _ -> true },
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

    private val lock = Mutex()
    private var restored = false
    // Whether the trip shown isn't yet known to be on the device ([keep]).
    private var unsaved = false

    /** Read the kept trip, once; a trip started meanwhile wins. */
    suspend fun restore() = lock.withLock { restoreLocked() }

    private suspend fun restoreLocked() {
        if (restored) return
        // Read before it counts as restored: a read cut short (the activity recreated) is tried again.
        val kept = withContext(io) { load() }
        restored = true
        if (kept == null) return
        if (_trip.value == null) {
            _trip.value = kept
            _progress.value = standing(kept, clock())
        }
    }

    /**
     * Start [route] to [destinationName], the rider at its first stop by [readyAt]: its first ride's
     * train is picked on the next [refresh].
     */
    suspend fun start(route: TripRoute, destinationName: String, readyAt: Instant) = lock.withLock {
        // One trip at a time: a kept one not read yet, or one on the way, stays.
        restoreLocked()
        if (_trip.value != null) return@withLock
        val now = clock()
        // From the first leg, a walk included: the rider walks it first (maintainer, 2026-09-27), and
        // the ride after it picks its train once the walk is done.
        val trip = ActiveTrip(route, destinationName, startedAt = now, legIndex = 0, legStartedAt = readyAt)
        _updatedAt.value = null
        keep(trip, OnTheWay.advance(trip, null, now).second)
    }

    /** End the trip: forgotten here and on the device. */
    suspend fun end(): Boolean = lock.withLock {
        // Forgotten on the device first, as on arrival. One that can't be would come back on the
        // next start, so it isn't ended: it stays, and [endFailed] says so.
        if (!withContext(io) { save(null) }) {
            _endFailed.value = true
            _endFailures.value++
            return@withLock false
        }
        _endFailed.value = false
        _endFailures.value = 0
        _trip.value = null
        _progress.value = null
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
     */
    suspend fun refresh() = lock.withLock {
        var trip = _trip.value ?: return@withLock
        var failed = false
        // At most one leg change per refresh: a leg reached here picks its train on the next one.
        val now = clock()
        val leg = trip.leg
        var calls: List<VehicleCall>? = null
        if (leg != null && !leg.isWalk) {
            try {
                if (trip.vehicleId.isBlank()) {
                    val picked = pick(trip, now)
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
                warn("on the way: train not found on line ${leg.lineId}")
                // A train not yet boarded that TfL no longer knows: dropped, so the next refresh picks
                // another. Once on board it stays followed: TfL has only gone quiet on it.
                if (trip.boarded) failed = true else trip = trip.copy(vehicleId = "", boardsAt = null, dueOffAt = null)
            } catch (e: TflException) {
                warn("on the way: train lookup failed for line ${leg.lineId}: ${e::class.simpleName}")
                failed = true
            }
        }
        if (failed) {
            // Nothing new is known: the step stands as it was (shown as not current), not Lost.
            _failed.value = true
            keep(trip, _progress.value ?: standing(trip, now))
            return@withLock
        }
        var (next, progress) = OnTheWay.advance(trip, calls, now)
        // A train that turned out not to be the rider's: drop it, so the next refresh picks another.
        // Once on board it stays followed: TfL has only gone quiet on it.
        if (progress is TripProgress.Lost && calls != null && !next.boarded) next = next.copy(vehicleId = "", dueOffAt = null)
        if (progress is TripProgress.Riding && OnTheWay.shouldWarn(next, progress) && onGetOffSoon(next, progress)) {
            next = OnTheWay.warned(next)
        }
        _failed.value = false
        _updatedAt.value = now
        if (progress == TripProgress.Arrived) {
            // Forgotten on the device first: the trip gone from the screen can cancel the caller.
            // One that can't be would come back on the next start, so it's kept, said, and tried
            // again on the next refresh.
            _progress.value = progress
            if (!withContext(io) { save(null) }) {
                _endFailed.value = true
                return@withLock
            }
            _endFailed.value = false
            _trip.value = null
        } else {
            keep(next, progress)
        }
    }

    // The soonest train the rider can catch on the trip's leg that runs where they're going, with
    // its calls; null when none of the first few does (or none is due).
    private suspend fun pick(trip: ActiveTrip, now: Instant): Pair<Departure, List<VehicleCall>>? {
        val leg = trip.leg ?: return null
        val readyAt = maxOf(trip.legStartedAt, now)
        val candidates = OnTheWay.candidates(arrivals(leg.fromId), leg, readyAt).take(PICK_TRIES)
        for (train in candidates) {
            val calls = try {
                vehicles.vehicleCalls(train.vehicleId, leg.lineId)
            } catch (e: TflException.NotFound) {
                // Still on the board, but TfL no longer knows the train: try the next.
                warn("on the way: train not found on line ${leg.lineId}")
                continue
            }
            if (OnTheWay.runsAlong(leg, calls, heading = train.destination)) return train to calls
        }
        return null
    }

    // Where [trip] stood when last kept, before any answer: on board with its stop, waiting for its
    // train when it was due, or walking. Not Lost, which only an answer can say.
    private fun standing(trip: ActiveTrip, now: Instant): TripProgress {
        val leg = trip.leg ?: return TripProgress.Arrived
        return when {
            leg.isWalk -> OnTheWay.advance(trip, null, now).second
            trip.boarded -> TripProgress.Riding(leg, "", null, trip.dueOffAt, false)
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
    }
}
