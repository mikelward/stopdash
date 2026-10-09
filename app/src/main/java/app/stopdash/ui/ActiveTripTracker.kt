package app.stopdash.ui

import androidx.annotation.VisibleForTesting
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.ToChoice
import app.stopdash.domain.Coordinates
import app.stopdash.domain.Workers
import app.stopdash.domain.Departure
import app.stopdash.domain.DoubledTrains
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LocationFix
import app.stopdash.domain.OffPlan
import app.stopdash.domain.Staleness
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.ReplanOrigin
import app.stopdash.domain.RideLines
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationPlaces
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val stationPlaces: suspend (String) -> StationPlaces = { StationPlaces() },
    // A line's route, placing a ride's stops ([OnTheWay.ridePositions]) so a fix can see the rider
    // already along it ([OnTheWay.seenAlong]); null when it can't be had. The trip's cards already
    // hold it, so it's rarely a request.
    private val lineSequence: suspend (String) -> LineSequence? = { null },
    // A stop area's poles ([StopAreaSource]), for a bus ride's boarding pole: its letter and "towards",
    // so its board is headed as the main view heads that stop ("Stop D"), and the pair's other poles,
    // where another of the ride's lines may board ([NextBoard.others]). Asked once per stop.
    private val stopPoles: suspend (String) -> List<StopLocation> = { emptyList() },
    // The lines a ride of the trip's route may be taken on now, given the boards read at its boarding
    // stop, by stop id (its own pole's, and its pair's others': [NextBoard.boards]): the ones the trip's
    // cards offer ([RideLines.running], [RideLineChecks]), each as it runs the ride, the Planner's first
    // when it's among them. The Planner's alone where nothing else is checked. With them, the lines that
    // went unchecked ([RideLinesNow.uncheckedLines]), which each use says where they could have changed
    // what the trip shows.
    private val rideLines: suspend (TripRoute, TripLeg, Map<String, List<Departure>>) -> RideLinesNow = { _, ride, _ -> RideLinesNow(listOf(ride)) },
    // The modes and lines the rider hides: a pair's other pole isn't read for a hidden line's sake alone.
    private val hidden: () -> Set<String> = { emptySet() },
    // The bundled station index, for placing a walk's ends the Planner didn't and telling whether
    // they're in one interchange when a trip starts ([OnTheWay.changesOnFoot]). Getting it may read
    // the asset, so it's called on [io].
    private val stations: () -> StationIndex = { StationIndex.EMPTY },
    private val clock: () -> Instant = Instant::now,
    // A monotonic clock in ms, for timing a wait the wall clock could be set back during.
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Where the trip is worked out ([lock]'s holders): work that grows with its input (its route walked, a
    // station's entrances measured), never on the caller's thread, which can be the main one (AGENTS.md
    // *Main thread: read and dispatch only*; Codex, PR #521).
    private val compute: CoroutineDispatcher = Workers.compute,
    // A precise fix the app took lately, if it remembers one, with its age (the one the trip was planned
    // from, say): a walk's first distance, before a fix on the walk itself. Takes no location.
    private val remembered: () -> LocationFix? = { null },
    // Whether precise location is still allowed: once it isn't, no fix kept from before is used, and the
    // trip's own is deleted (Codex, #542).
    private val preciseAllowed: () -> Boolean = { true },
    // Where the trip's own last fix is let go once past its use, whether or not anything still follows the
    // trip (docs/PRIVACY.md; Codex, #542).
    private val forgetting: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // Coarse facts only — a line id, an error kind, never a stop or where the rider is going.
    private val warn: (String) -> Unit = {},
    // "Get off soon", once per leg ([OnTheWay.shouldWarn]): whether it was said, so one that
    // couldn't be (notifications off) is tried again on the next refresh.
    private val onGetOffSoon: (ActiveTrip, TripProgress.Riding) -> Boolean = { _, _ -> true },
    // The rider has moved past a leg whose "get off soon" was said (or arrived): it's done with.
    private val onGetOffSoonDone: () -> Unit = {},
    // "Time to board", once for each train waited for ([OnTheWay.shouldBoard]), then kept up to
    // date by each fresh answer ([BoardPost]), with when that answer was had ([updatedAt]'s value),
    // so it lasts exactly as long as the answer stays live ([CURRENT_FOR]): whether it's up, so a
    // first one that couldn't be said is tried on the next refresh, and one that can't be kept up is
    // taken down ([onBoardSoonDone]), not brought back.
    private val onBoardSoon: (ActiveTrip, TripProgress.Waiting, BoardPost, Instant) -> Boolean = { _, _, _, _ -> true },
    // A "time to board" said no longer stands ([OnTheWay.boardStands]): taken down.
    private val onBoardSoonDone: () -> Unit = {},
    // "Route disruption": what's known that may stop a coming leg ([RouteDisruptionChecks.check]),
    // asked after each refresh, given the direction a coming leg's trains are seen going, by leg, and the
    // lines the next board lists, asked about with the trip's own ([RouteDisruption.Found.lines]).
    private val disruptions: suspend (ActiveTrip, TripProgress, Map<Int, String>, Collection<String>) -> RouteDisruption.Found =
        { _, _, _, _ -> RouteDisruption.Found.NONE },
    // Posts what's known, as [DisruptionPost] says, standing until the given time: whether it's up,
    // so one that couldn't be heard is tried on the next refresh, and one that can't be kept up is
    // taken down ([onDisruptionDone]), not brought back.
    private val onDisruption: (ActiveTrip, List<RouteDisruption.Signal>, DisruptionPost, Instant) -> Boolean = { _, _, _, _ -> true },
    // Nothing known is left, or the trip ended: taken down.
    private val onDisruptionDone: () -> Unit = {},
    // What a "route disruption" still showing was posted with ([RouteDisruption.Signal.key]): heard,
    // however the trip was left when the app died.
    private val disruptionsShown: () -> Set<String> = { emptySet() },
) {
    private val _trip = MutableStateFlow<ActiveTrip?>(null)
    val trip: StateFlow<ActiveTrip?> = _trip.asStateFlow()

    private val _progress = MutableStateFlow<TripProgress?>(null)
    val progress: StateFlow<TripProgress?> = _progress.asStateFlow()

    // The newest precise fix seen while following the trip, with when it was taken (on [elapsed]'s
    // clock): a walk's first distance until a fix on the walk places the rider. In memory only, never
    // kept or logged (SPEC *Privacy*).
    // Cleared only if still the one meant ([AtomicReference.compareAndSet]): a timer or expiry never takes
    // away a newer fix kept meanwhile (Codex, #542).
    private val heldFixRef = AtomicReference<Pair<LocationFix, Long>?>(null)
    private var lastFix: Pair<LocationFix, Long>?
        get() = heldFixRef.get()
        set(value) = heldFixRef.set(value)

    // Lets go of [lastFix] once it's past [ESTIMATE_WITHIN]: replaced with each fix kept.
    private var forgetFix: Job? = null

    // The station read for the walk on now, by its leg's index ([OnTheWay.stationWalkedTo]): a fix
    // between refreshes measures to its entrances without a read of its own.
    @Volatile private var walkPlaces: Pair<Int, StationPlaces>? = null

    // Whether the last refresh couldn't reach TfL: the screen says the trip isn't current.
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    // When a refresh last reached TfL for the trip; null until one has. [progress] is only live
    // while this is recent ([isCurrent]).
    private val _updatedAt = MutableStateFlow<Instant?>(null)
    val updatedAt: StateFlow<Instant?> = _updatedAt.asStateFlow()

    // When the step as shown was last answered by TfL, kept through a failed refresh (unlike
    // [updatedAt]) so a surface can keep that answer's time left while it checks (SPEC principle 2)
    // rather than none; null where the step has no answer of its own yet (a new trip, a step moved on,
    // a reroute).
    private val _answeredAt = MutableStateFlow<Instant?>(null)
    val answeredAt: StateFlow<Instant?> = _answeredAt.asStateFlow()

    // The step [answeredAt] answered ([answeredStep]): a step since entered without a lookup of its own
    // (a walk ended into a ride, say) has no answer, however recent the last one (Codex, #611).
    private var answeredFor: List<Any?>? = null

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
    data class NextBoard(
        val ride: TripLeg,
        val departures: List<Departure>,
        val fetchedAt: Instant?,
        val failed: Boolean = false,
        // The boarding pole as TfL lists it ([stopPoles]), for its letter and "towards"; null for a
        // station, or while it can't be read.
        val pole: StopLocation? = null,
        // The boards of the boarding stop pair's other poles, read with [departures]: those where another
        // of the ride's lines may board ([RideLines.polesToRead]), across the road or at another stand.
        val others: List<PoleBoard> = emptyList(),
        // Whether a pole of the pair couldn't be read (its poles, or the board of one that may have a
        // train taking the ride): its trains went unseen, so none found isn't passed off as none there.
        val partial: Boolean = false,
    ) {
        /** Each board read, by its stop id: the ride's own pole's ([departures]), then [others]. */
        val boards: Map<String, List<Departure>>
            get() = LinkedHashMap<String, List<Departure>>().apply {
                put(ride.fromId, departures)
                others.forEach { put(it.pole.id, it.departures) }
            }
    }

    /** A pole of the boarding stop pair other than the ride's own, as TfL lists it, and its board. */
    data class PoleBoard(val pole: StopLocation, val departures: List<Departure>)

    /**
     * What's known to be wrong on the route ahead ([RouteDisruption.Signal], worst first), as the
     * route disruption alert was last posted with: the trip's screen shows the same, in full, so
     * tapping the alert finds where and how (maintainer, 2026-10-01), with how long its evidence
     * stands ([KnownDisruptions.until]). Null once nothing is known.
     */
    /**
     * The coming stations' other notices ([RouteDisruption.StationNote]) and until when they stand, in the
     * steady frame as [KnownDisruptions.until]: shown on the trip's screen, never alerted.
     */
    data class KnownNotes(val notes: List<RouteDisruption.StationNote>, val until: Instant) {
        /** [notes] while they stand at the wall time [now], else none. */
        fun at(now: Instant): List<RouteDisruption.StationNote> = if (SteadyClock.stamp(now).isBefore(until)) notes else emptyList()
    }

    private val _stationNotes = MutableStateFlow<KnownNotes?>(null)
    val stationNotes: StateFlow<KnownNotes?> = _stationNotes.asStateFlow()

    private val _routeDisruptions = MutableStateFlow<KnownDisruptions?>(null)
    val routeDisruptions: StateFlow<KnownDisruptions?> = _routeDisruptions.asStateFlow()

    /**
     * The lines' statuses the last disruption check asked for ([RouteDisruption.LinesChecked]): the trip's
     * coming lines and the next board's, so a train tapped on the board opens its line's page with a
     * status (maintainer, 2026-10-06). Null before a check is made, or once the trip is over; a check that
     * failed has every line asked about without a status.
     */
    private val _lineChecks = MutableStateFlow<RouteDisruption.LinesChecked?>(null)
    val lineChecks: StateFlow<RouteDisruption.LinesChecked?> = _lineChecks.asStateFlow()

    /**
     * What's known wrong on the route ahead ([signals]), standing [until] its evidence goes stale, as
     * the alert's own timeout does: refreshes paused (the app closed on a trip followed only while
     * open) leave nothing shown as current past it (D4; Codex, PR #453).
     */
    data class KnownDisruptions(
        val signals: List<RouteDisruption.Signal>,
        val until: Instant,
        // [signals] as the trip's screen shows them ([RouteDisruption.cards]), worked out with them on
        // [io], so the screen only draws them (Codex on #519).
        val cards: List<RouteDisruption.Signal> = signals,
    ) {
        /**
         * [signals] while they stand at the wall time [now], else none. [until] is in the steady frame
         * ([SteadyClock.stamp]), so a wall clock set back can't keep them up (Codex, PR #453).
         */
        fun at(now: Instant): List<RouteDisruption.Signal> = if (standsAt(now)) signals else emptyList()

        /** [cards] while they stand at [now], as [at]. */
        fun cardsAt(now: Instant): List<RouteDisruption.Signal> = if (standsAt(now)) cards else emptyList()

        private fun standsAt(now: Instant) = SteadyClock.stamp(now).isBefore(until)
    }

    private val _nextBoard = MutableStateFlow<NextBoard?>(null)
    val nextBoard: StateFlow<NextBoard?> = _nextBoard.asStateFlow()

    /**
     * Where the trip would be planned again from while something is known wrong on the route ahead
     * ([routeDisruptions]): the station still ahead on it nearest the rider ([ReplanOrigin], maintainer
     * 2026-10-02), worked out with each check that finds something. Null while nothing is known, or no
     * ride is left to plan.
     */
    private val _replanFrom = MutableStateFlow<ReplanOrigin.Stop?>(null)
    val replanFrom: StateFlow<ReplanOrigin.Stop?> = _replanFrom.asStateFlow()

    // Every train the upcoming ride's boards have listed ([readBoard]), by the stop it was listed at
    // and then its line and TfL's id for it ([seenKey]), for [boardedAlong]: one that has since left may
    // be the rider's. Of one ride only, in memory only.
    private var boardSeenRide: TripLeg? = null
    private val boardSeen = LinkedHashMap<String, LinkedHashMap<String, Departure>>()

    // A train's identity on a board of several lines: TfL's train ids are each line's own (Codex, PR #451).
    private fun seenKey(train: Departure) = "${train.lineId}\u001F${train.vehicleId}"

    // Each entry point that takes it does so on [compute], the hop first and inline, so lint sees it
    // (`WorkerThreadCall`): a refresh's step, a tap's move and a start walk the trip's route (its steps,
    // a ride's stops, a train's calls) and grow with it, so none of it runs on the caller's thread, which
    // is the main one (AGENTS.md *Main thread: read and dispatch only*). The lock keeps one at a time and
    // hands this tracker's own state from one to the next; the flows it publishes to are read from any
    // thread, and its alerts are posted from any.
    private val lock = Mutex()

    // Each bus boarding stop pair's poles once read ([stopPoles]), by the stop asked about; one that
    // couldn't be read isn't kept.
    private val pairs = HashMap<String, List<StopLocation>>()

    // [ride]'s boarding stop pair's poles ([stopPoles]) when it boards at a bus stop, read once: the
    // Planner's pole among them, for its letter, and the others, whose boards may list another of the
    // ride's lines. Empty for a station; null while they can't be read, when its board is headed by
    // its name alone and the failure logged.
    private suspend fun pairOf(ride: TripLeg): List<StopLocation>? {
        if (ride.mode !in POLE_MODES || ride.fromId.isBlank()) return emptyList()
        val stop = ride.fromArea.ifBlank { ride.fromId }
        pairs[stop]?.let { return it }
        return try {
            stopPoles(stop).also { pairs[stop] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: boarding stop lookup failed for line ${ride.lineId}: ${e::class.simpleName}")
            null
        }
    }

    // [ride]'s boarding stop's board, read now, with its pair's other poles' that may list another of
    // the ride's lines ([RideLines.polesToRead], by each line's route, kept a day): an empty pair asks
    // nothing more. A pole that can't be read, or a pair whose poles can't be, leaves the board
    // [NextBoard.partial], said rather than passed off as whole. Throws when the ride's own stop's
    // board can't be read.
    private suspend fun boardOf(ride: TripLeg, fetchedAt: Instant?): NextBoard {
        val departures = arrivals(ride.fromId)
        val pair = pairOf(ride) ?: return NextBoard(ride, departures, fetchedAt, partial = true)
        val hidden = hidden()
        val routes = RideLines.pairLineIds(ride, pair, hidden).associateWith { routeOf(it) }
        var partial = false
        val others = RideLines.polesToRead(ride, pair, routes, hidden).mapNotNull { pole ->
            try {
                PoleBoard(pole, arrivals(pole.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("on the way: pair board lookup failed for line ${ride.lineId}: ${e::class.simpleName}")
                partial = true
                null
            }
        }
        return NextBoard(ride, departures, fetchedAt, pole = pair.firstOrNull { it.id == ride.fromId }, others = others, partial = partial)
    }

    // Each station's point and entrances once read ([stationPlaces]); a failed read isn't kept, so
    // the next refresh asks again. Held for the process only: a station's entrances don't move.
    private val stationPlacesRead = HashMap<String, StationPlaces>()

    // [stopId]'s point and entrances ([stationPlaces]), read once; none while they can't be, so the
    // walk ends on its placed position or its time as before, and the failure is logged.
    private suspend fun placesOf(stopId: String): StationPlaces {
        if (stopId.isBlank()) return StationPlaces()
        stationPlacesRead[stopId]?.let { return it }
        return try {
            stationPlaces(stopId).also { stationPlacesRead[stopId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException.NotFound) {
            // TfL doesn't know the station: asking again won't change that.
            warn("on the way: station entrances lookup failed: NotFound")
            StationPlaces().also { stationPlacesRead[stopId] = it }
        } catch (e: TflException) {
            warn("on the way: station entrances lookup failed: ${e::class.simpleName}")
            StationPlaces()
        }
    }
    private var restored = false
    // Whether the trip shown isn't yet known to be on the device ([keep]).
    private var unsaved = false

    // Whether a "time to board" may be up ([settleBoard]). One kept from before a restart may be up
    // still: notifications outlive the process (for as long as its answer stays live, [BoardPost]).
    private var boardUp = false

    // Whether a "route disruption" may be up ([checkDisruptions]). One heard before a restart may be up
    // still, for as long as its evidence stays current.
    private var disruptionUp = false
    // Until when each signal known stands, by its key, as the alert was last posted or kept with
    // ([checkDisruptions]): for keeping it up with what's left after a dismissal ([dismissDisruptions]).
    private var disruptionStands: Map<String, Instant> = emptyMap()

    // The direction each of this trip's rides was seen going, by its leg ([directionsOf]). In memory
    // only: after a restart a ride's direction is learned again, and meanwhile isn't known.
    private val rideDirections = HashMap<Int, String>()

    /** Read the kept trip, once; a trip started meanwhile wins. */
    /** Reads the kept trip, once; false when it couldn't be read, to be tried again. */
    suspend fun restore(): Boolean = withContext(compute) { lock.withLock { restoreLocked() } }

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
            onBoardSoonDone()
            onDisruptionDone()
            return true
        }
        if (_trip.value == null) {
            // A "route disruption" heard before the restart may still be up: the next check keeps it,
            // or takes it down. What it says is heard, whether or not the trip kept so before the app
            // died or a save failed: it's read back from the notification itself ([disruptionsShown]),
            // so a restart never sounds it again (Codex, PR #441).
            val shown = disruptionsShown()
            disruptionUp = shown.isNotEmpty() || kept.disruptionsHeard.isNotEmpty()
            // A "time to board" said before the restart may still be up: the next fresh answer keeps
            // it, if it is ([BoardPost.KEEP]).
            boardUp = kept.boardWarned.isNotEmpty()
            // A move saved just before the app died, before it could take back the "get off soon"
            // for the leg left ([goTo]): taken back now. Marked on the trip, not guessed from its
            // warning, which also lags an alert said just before the app died (Codex, PR #351).
            val alertTaken = if (kept.alertLeft) {
                onGetOffSoonDone()
                unsaved = true
                kept.copy(alertLeft = false)
            } else {
                kept
            }
            val trip = if (alertTaken.disruptionsHeard.containsAll(shown)) {
                alertTaken
            } else {
                unsaved = true
                alertTaken.copy(disruptionsHeard = alertTaken.disruptionsHeard + shown)
            }
            _trip.value = trip
            _progress.value = withAhead(trip, standing(trip, clock()))
            settleBoard()
        }
        return true
    }

    /**
     * Start [route] to [destinationName], the rider at its first stop by [readyAt]: its first ride's
     * train is picked on the next [refresh]. [destinations], [destinationIds] and [destinationStopId] are
     * the destination as chosen ([ActiveTrip.destinations]).
     */
    suspend fun start(
        route: TripRoute,
        destinationName: String,
        readyAt: Instant,
        destinations: List<TripDestination> = emptyList(),
        destinationIds: Map<String, String> = emptyMap(),
        destinationStopId: String = "",
        // In place of the trip on the way (a route planned again from partway along): it's ended first,
        // under the same lock, a kept one not read yet included ([restoreLocked]), and [onEnded] told.
        // One that can't be ended stays, and nothing is started ([endFailed] says so).
        replacing: Boolean = false,
        onEnded: () -> Unit = {},
    ) = withContext(compute) {
        lock.withLock {
            // One trip at a time: a kept one not read yet, or one on the way, stays. One that couldn't be
            // read may be on the way, so none is started over it; the failure is said ([failed]).
            if (!restoreLocked()) return@withLock
            if (replacing && _trip.value != null) {
                if (!endLocked()) return@withLock
                onEnded()
            }
            if (_trip.value != null) return@withLock
            forgetFixes()
            val now = clock()
            // From the first leg, a walk included: the rider walks it first (maintainer, 2026-09-27), and
            // the ride after it picks its train once the walk is done. The walk from where the rider is
            // to where the route starts ([readyAt]) is a walk too, as the route shows it, whether the
            // Planner's route starts with a ride or a walk of its own.
            val first = route.legs.firstOrNull()
            val trip = if (first != null && readyAt.isAfter(now)) {
                val toStop = TripLeg(TripLeg.WALKING, "", "", "", "", first.fromId, first.fromName, now, readyAt)
                ActiveTrip(TripRoute(listOf(toStop) + route.legs), destinationName, startedAt = now, legStartedAt = now, destinations = destinations, destinationIds = destinationIds, destinationStopId = destinationStopId)
            } else {
                ActiveTrip(route, destinationName, startedAt = now, legIndex = 0, legStartedAt = readyAt, destinations = destinations, destinationIds = destinationIds, destinationStopId = destinationStopId)
            }.let { planned ->
                // Which walks are changes on foot, decided once now and kept with the trip, so its steps
                // never change on the way. Off the caller's thread: it may read the station index.
                planned.copy(onFootChanges = withContext(io) { OnTheWay.changesOnFoot(planned.route, stations()) })
            }
            _updatedAt.value = null
            _answeredAt.value = null
            boardSeenRide = null
            boardSeen.clear()
            rideDirections.clear()
            keep(trip, OnTheWay.advance(trip, null, now).second)
        }
    }

    private val _starting = MutableStateFlow(0)

    /** Starts in flight ([launchStart]): counted from the tap, so a follower doesn't take one for none. */
    val starting: StateFlow<Int> = _starting.asStateFlow()

    /** [start] in [scope], counted in [starting] at once, before the trip is saved. */
    fun launchStart(
        scope: CoroutineScope,
        route: TripRoute,
        destinationName: String,
        readyAt: Instant,
        destinations: List<TripDestination> = emptyList(),
        destinationIds: Map<String, String> = emptyMap(),
        destinationStopId: String = "",
        // In place of the trip on the way ([start]'s [replacing]), [onEnded] told once it's ended.
        replacing: Boolean = false,
        onEnded: () -> Unit = {},
    ): Job {
        _starting.update { it + 1 }
        return scope.launch {
            try {
                start(route, destinationName, readyAt, destinations, destinationIds, destinationStopId, replacing, onEnded)
            } finally {
                _starting.update { it - 1 }
            }
        }
    }

    /**
     * The rider says they're at [to] ([OnTheWay.atStep]): **Next**, or a step tapped. The trip moves
     * there now, and a ride's train is picked at once, as a refresh would.
     */
    suspend fun goTo(from: OnTheWay.Step, to: OnTheWay.Step) = withContext(compute) {
        lock.withLock {
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
            refollow()
            // The step moved to has no answer of its own yet: the last one's isn't passed off as its
            // (a ride's time, its next stop still blank), which waits for the pick below (Codex, PR #384).
            _updatedAt.value = null
            _answeredAt.value = null
            _progress.value = standing(moved, now, picking = true)
            settleBoard()
            val boards = HashMap<TripLeg, Result<NextBoard>>()
            if (step(null, boards)) step(null, boards)
            // What's ahead changed with the step: a stop now behind the rider is no longer theirs to reach.
            checkDisruptions(boards)
        }
    }

    /**
     * The rider takes [branch] for [ride], a way that leaves the plan ([OffPlan], maintainer 2026-10-05):
     * **Take this one** on the board. The trip is rerouted ([OffPlan.take]): a ride to where the branch
     * turns off, then a change there onto the rest of the ride, followed as any route is. Saved before
     * it's made, as [goTo] is: a move that can't be kept isn't made, and says so. Nothing moves when the
     * trip has moved past [ride] meanwhile.
     */
    suspend fun take(ride: TripLeg, branch: OffPlan.Branch) = withContext(compute) {
        lock.withLock {
            val before = _trip.value ?: return@withLock
            if (_progress.value == TripProgress.Arrived) return@withLock
            val index = before.route.legs.indexOf(ride).takeIf { it >= before.legIndex } ?: return@withLock
            val now = clock()
            // Not a fork already behind a rider on board (Codex, #586), nor one judged from an answer too old to
            // say: their train may have passed it since.
            val aboard = index == before.legIndex && (before.boarded || before.onBoardSeen)
            if (aboard && !isCurrent(_updatedAt.value, now)) return@withLock
            // Lost with no calls that placed the train this time: where it is now isn't known (Codex, #586).
            if (aboard && (_progress.value as? TripProgress.Lost)?.placed == false) return@withLock
            if (index == before.legIndex && listOfNotNull(aheadOn(_progress.value, ride), keptAhead(before)).any { branch.forkIndex < it }) return@withLock
            // A "get off soon" said for the train the ride was on names a stop that's no longer theirs: it's
            // taken back once the reroute is saved, as a step moved past it is ([goTo]; Codex, #583).
            val split = OffPlan.take(before, index, branch, now)?.let { at ->
                at.copy(alertLeft = before.warnedLeg == before.legIndex && at.warnedLeg != before.warnedLeg)
            } ?: return@withLock
            val taken = if (aboard) {
                when (val check = passedFork(split, now)) {
                    is ForkCheck.Taken -> check.trip
                    // Where the train is couldn't be read: not rerouted on a guess, and the failed update is
                    // said, so the stale answer stops being offered (Codex, #586).
                    ForkCheck.Failed -> {
                        _failed.value = true
                        _updatedAt.value = null
                        return@withLock
                    }
                    // Not placed, or already past the fork: not rerouted, and the step is worked out afresh,
                    // so the screen shows where the train is now (Codex, #586).
                    ForkCheck.Refused -> {
                        if (step(null, HashMap())) step(null, HashMap())
                        return@withLock
                    }
                }
            } else {
                split
            }
            val boards = HashMap<TripLeg, Result<NextBoard>>()
            if (rerouted(taken, now, boards)) checkDisruptions(boards)
        }
    }

    // [taken], a reroute ([OffPlan.take]), saved and followed from now, under [lock]; false where it
    // couldn't be saved, when nothing moves. [boardOf], a ride whose board this refresh read is the
    // rerouted ride's too, once saved ([rekeyBoard]).
    private suspend fun rerouted(taken: ActiveTrip, now: Instant, boards: HashMap<TripLeg, Result<NextBoard>>, boardOf: TripLeg? = null): Boolean {
        unsaved = true
        val saved = withContext(io) { save(taken) }
        unsaved = !saved
        _notKept.value = !saved
        if (!saved) return false
        if (taken.alertLeft) {
            onGetOffSoonDone()
            unsaved = true
        }
        boardOf?.let { from -> OnTheWay.upcomingRide(taken)?.let { to -> rekeyBoard(from, to, boards) } }
        // The rides' directions are kept by leg, which the split has moved: learned again.
        rideDirections.clear()
        _trip.value = taken.copy(alertLeft = false)
        refollow()
        // The rerouted ride has no answer of its own yet: the last one's isn't passed off as its.
        _updatedAt.value = null
        _answeredAt.value = null
        _progress.value = standing(taken, now)
        settleBoard()
        // A walk to the ride already past its time moves on to it in the first step, and the second
        // picks its train, as [goTo] does (Codex, #583).
        if (step(null, boards)) step(null, boards)
        return true
    }

    /**
     * The branch the trip takes by itself (maintainer, 2026-10-06): the ride still to board, with no
     * train followed, has a fresh board, every train on it checked, and none of the plan's listed, but
     * trains that run its way and turn off before where the rider gets off (the Planner's timetable can
     * route a direct train TfL's live board doesn't list). The rider will take one of those, so the
     * trip goes that way, by the soonest they can catch, as **Take this one** would, and its change
     * there shows the next board. Null where it doesn't.
     */
    private suspend fun branchToTake(trip: ActiveTrip, now: Instant): Pair<Int, OffPlan.Branch>? {
        val ride = OnTheWay.upcomingRide(trip) ?: return null
        if (trip.boarded || trip.onBoardSeen || trip.vehicleId.isNotBlank()) return null
        val board = _nextBoard.value?.takeIf { it.ride == ride && !it.failed && !it.partial && it.others.isEmpty() } ?: return null
        val fetchedAt = board.fetchedAt?.takeIf { !Staleness.isStale(it, now) } ?: return null
        val sequences = withContext(compute) { OnTheWay.boardLineIds(ride, board.departures) }.associateWith { routeOf(it) }
        if (sequences.values.any { it == null }) return null
        val readyAt = OnTheWay.readyAt(trip, _progress.value) ?: now
        return withContext(compute) {
            val own = OnTheWay.boardTrains(ride, board.departures, fetchedAt, sequences, now)
            if (own.trains.isNotEmpty() || own.pending || own.unresolved) return@withContext null
            val branch = OffPlan.branches(ride, board.departures, sequences, now)
                .flatMap { b -> b.trains.filter { !it.expectedArrival.isBefore(readyAt) }.map { b to it.expectedArrival } }
                .minByOrNull { it.second }?.first ?: return@withContext null
            trip.route.legs.indexOf(ride).takeIf { it >= 0 }?.let { it to branch }
        }
    }

    // What the train's calls say of a reroute on board ([take]), read once after the split.
    private sealed interface ForkCheck {
        // Saved as [trip]: still short of the fork, with when it's due there.
        data class Taken(val trip: ActiveTrip) : ForkCheck
        // The calls couldn't be read.
        data object Failed : ForkCheck
        // No calls to place the train by, none on the ride to the fork (it's past it), or no time it's due there.
        data object Refused : ForkCheck
    }

    // [trip], just rerouted on board ([take]), checked against its train's calls: taken only while one of
    // them is on the ride to the fork, so the train is short of it. A train already past the fork has the
    // rider past it too, whichever way it went, and a change there is behind them (Codex, #586). Taken with
    // when the train is due at the fork, from these calls: the step's own read, just after, may find it
    // gone on past, and that time is what moves the rider on to the change then (Codex, #586). Calls that
    // can't be read, none at all, or none that time the fork, don't place it: [ForkCheck].
    private suspend fun passedFork(trip: ActiveTrip, now: Instant): ForkCheck {
        val toFork = trip.leg ?: return ForkCheck.Taken(trip)
        // Counted by where they were seen, not by a train: [OffPlan.take] has judged the fork by that.
        if (trip.vehicleId.isBlank()) return ForkCheck.Taken(trip)
        val calls = try {
            vehicles.vehicleCalls(trip.vehicleId, OnTheWay.followedLine(trip))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: train lookup after a reroute failed for line ${OnTheWay.followedLine(trip)}: ${e::class.simpleName}")
            return ForkCheck.Failed
        }
        if (calls.isEmpty()) return ForkCheck.Refused
        val due = withContext(compute) {
            if (OnTheWay.aheadOnLeg(toFork, calls) < toFork.path.size) OnTheWay.advance(trip, calls, now).first.dueOffAt else null
        }
        return if (due != null) ForkCheck.Taken(trip.copy(dueOffAt = due)) else ForkCheck.Refused
    }

    /**
     * The rider read [shown] on the trip's screen and keeps going (maintainer, 2026-10-03): each kept on
     * the trip as dismissed ([RouteDisruption.Signal.dismissKey]), so neither the screen nor the alert brings it
     * back on this trip, though something new (another stop, a worse status) still is. The alert comes
     * down once nothing undismissed is left; one found since the screen showed [shown] keeps it up, as
     * that alone. Saved before it's made, as [goTo] is: a dismissal that can't be kept isn't made, and
     * says so ([notKept]), so a restart never brings back what the screen had let go (Codex on #519).
     */
    suspend fun dismissDisruptions(shown: List<RouteDisruption.Signal>) = lock.withLock {
        val trip = _trip.value ?: return@withLock
        // Not once it's arrived: one not yet forgotten on the device would be saved back (Codex on #519).
        val progress = _progress.value
        if (progress == null || progress == TripProgress.Arrived) return@withLock
        val known = _routeDisruptions.value
        // Keyed and sorted out on [io], not the caller's (the main) thread.
        // Null when nothing new is dismissed; compared there too, so the caller does no work that grows
        // with the trip's dismissals (Codex on #519).
        val stands = disruptionStands
        // What's left stands as long as its own evidence ([disruptionStands]), not as long as what was let
        // go of: a notice ending soon no longer cuts short a line's alert (Codex on #519).
        val (kept, left) = withContext(io) {
            // Not a branch taken by itself: no card shows it, so Keep going isn't an answer to it (Codex, #633).
            val dismissed = trip.disruptionsDismissed + shown.filter { it !is RouteDisruption.Signal.NoneDirect }.map { it.dismissKey }
            if (dismissed.size == trip.disruptionsDismissed.size) return@withContext null to null
            // Kept going from a stop gone past: it's let go of, and with it the fixes asked for to follow it (Codex, #635).
            val goneOn = shown.any { it is RouteDisruption.Signal.Missed && it.legIndex == trip.pastLeg }
            val after = trip.copy(disruptionsDismissed = dismissed).let { if (goneOn) it.copy(pastLeg = -1, pastAtId = "", pastAtName = "") else it }
            after to known?.let { known ->
                val signals = known.signals.filter { signal -> signal.dismissKey !in dismissed }
                val until = signals.map { stands[it.key] }.takeIf { it.all { own -> own != null } }?.filterNotNull()?.minOrNull()
                // Whether a stop gone past still stands among them, worked out here, off the caller's thread (Codex, #635).
                Triple(signals, until, RouteDisruption.cards(signals)) to signals.any { it is RouteDisruption.Signal.Missed }
            }
        }
        if (kept == null) return@withLock
        unsaved = true
        val saved = withContext(io) { save(kept) }
        unsaved = !saved
        _notKept.value = !saved
        if (!saved) return@withLock
        _trip.value = kept
        val (signals, until, cards) = left?.first ?: Triple(emptyList<RouteDisruption.Signal>(), null, emptyList())
        val missedLeft = left?.second == true
        when {
            signals.isEmpty() || until == null -> takeDisruptionDown()
            // Something found since the screen showed [shown], not dismissed: the alert stays up, as that
            // alone, already heard, so it isn't sounded again (Codex on #519).
            else -> {
                _routeDisruptions.value = KnownDisruptions(signals, SteadyClock.stamp(until), cards)
                // Kept going from a stop gone past: plan again isn't from there under what's left, until the
                // next refresh says where from (Codex, #635).
                if (!missedLeft && trip.pastAtId.isNotBlank() &&
                    _replanFrom.value?.id == trip.pastAtId
                ) _replanFrom.value = null
                if (disruptionUp && !postDisruption(kept, signals, DisruptionPost.KEEP, until)) takeDisruptionDown(known = false)
            }
        }
    }

    /**
     * The rider dismissed a station's [note] on the trip's screen (its ×): kept on the trip as dismissed
     * ([RouteDisruption.StationNote.dismissKeys]), so it isn't shown again on this trip until the notice
     * changes. Saved before it's made, as [dismissDisruptions] is: one that can't be kept isn't made, and
     * says so ([notKept]).
     */
    suspend fun dismissNote(note: RouteDisruption.StationNote) = lock.withLock {
        val trip = _trip.value ?: return@withLock
        val progress = _progress.value
        if (progress == null || progress == TripProgress.Arrived) return@withLock
        // Keyed and added on [io], not the caller's (the main) thread: the key joins TfL's words.
        val kept = withContext(io) {
            (trip.disruptionsDismissed + note.dismissKeys).takeIf { it.size > trip.disruptionsDismissed.size }?.let { trip.copy(disruptionsDismissed = it) }
        } ?: return@withLock
        unsaved = true
        val saved = withContext(io) { save(kept) }
        unsaved = !saved
        _notKept.value = !saved
        if (!saved) return@withLock
        _trip.value = kept
        val known = _stationNotes.value ?: return@withLock
        val answered = _updatedAt.value
        _stationNotes.value = withContext(io) {
            known.notes.filterNot { it.dismissedIn(kept.disruptionsDismissed) }.takeIf { it.isNotEmpty() }
                ?.let { left -> knownNotes(left, null, answered) ?: known.copy(notes = left) }
        }
    }

    /**
     * [notes] standing as long as the first of them ([RouteDisruption.StationNote.until]; else [evidence],
     * the whole check's), and no longer than the trip's own answer ([answered]): none with no notes or no
     * answer. From what's shown, so one dismissed doesn't cut the rest short (Codex, #609).
     */
    private fun knownNotes(notes: List<RouteDisruption.StationNote>, evidence: Instant?, answered: Instant?): KnownNotes? {
        if (notes.isEmpty() || answered == null) return null
        val until = notes.mapNotNull { it.until }.minOrNull() ?: evidence ?: return null
        return KnownNotes(notes, SteadyClock.stamp(minOf(until, answered.plus(CURRENT_FOR))))
    }

    /** End the trip: forgotten here and on the device. */
    suspend fun end(): Boolean = withContext(compute) { lock.withLock { endLocked() } }

    // [end], under [lock].
    private suspend fun endLocked(): Boolean {
        // Forgotten on the device first, as on arrival. One that can't be would come back on the
        // next start, so it isn't ended: it stays, and [endFailed] says so. An arrival already
        // forgotten has nothing left to forget: only what's on screen is let go.
        if (_trip.value != null && !withContext(io) { save(null) }) {
            _endFailed.value = true
            _endFailures.value++
            return false
        }
        _endFailed.value = false
        _endFailures.value = 0
        _trip.value = null
        _stationNotes.value = null
        _lineChecks.value = null
        forgetFixes()
        _progress.value = null
        _nextBoard.value = null
        _failed.value = false
        _updatedAt.value = null
        _answeredAt.value = null
        _notKept.value = false
        unsaved = false
        rideDirections.clear()
        settleBoard()
        takeDisruptionDown()
        return true
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
        withContext(compute) {
            lock.withLock {
                // The fix aged while this waited behind another refresh: one no longer fresh enough is
                // no evidence the rider was left behind ([OnTheWay.sureEnough]).
                // Precise location taken away since the fix came: it isn't used, and nothing kept stays (Codex, #542).
                val allowed = preciseAllowed()
                if (!allowed) {
                    forgetFix?.cancel()
                    lastFix = null
                }
                val fresh = rider?.takeIf { allowed }?.let { fix -> aged(fix, Duration.ofMillis(elapsed() - asked)) }
                // A leg just done (a walk, or a ride straight into another) picks the next ride's train at
                // once: one due before the next refresh is still the rider's to catch.
                // Each boarding stop's board is asked for at most once a refresh, answer or failure,
                // however long TfL takes: the steps share this refresh's attempts.
                val boards = HashMap<TripLeg, Result<NextBoard>>()
                // Each line's route too ([routesRead]).
                routesRead = HashMap()
                try {
                    // The trip's own last fix let go once past its use, whether or not TfL answers (Codex, #542).
                    expireFix()
                    try {
                        if (step(fresh, boards)) step(null, boards)
                    } finally {
                        expireFix()
                    }
                    // None of the plan's trains running, only branches off it: the trip takes one by itself.
                    _trip.value?.let { trip ->
                        val now = clock()
                        branchToTake(trip, now)?.let { (index, branch) ->
                            OffPlan.take(trip, index, branch, now)?.let { taken ->
                                warn("on the way: none of the plan's trains listed; took the branch off at stop ${branch.forkIndex}")
                                // Said until the rider is past the ride to the fork ([ActiveTrip.branchTakenLeg]).
                                val said = OffPlan.takenBySelf(trip, taken, index, branch)
                                // The board just read is the shortened ride's too: from the same stop, not asked for again (Codex, #630).
                                rerouted(said, now, boards, boardOf = trip.route.legs[index])
                            }
                        }
                    }
                    // Seen past where they got off a ride, or back from it (maintainer, 2026-10-06): kept with the trip.
                    notePast(rider?.takeIf { allowed }, asked)
                    // The fix as given, with when: aged once, where it's used, for all the time since (Codex on #479).
                    // Only a fix precise location still allows picks where to plan again from (Codex, #542).
                    checkDisruptions(boards, rider?.takeIf { allowed && preciseAllowed() }, asked)
                } finally {
                    routesRead = null
                }
            }
        }
    }

    // The next board's lines, asked with the trip's own in the same request: a train tapped there opens
    // its line's page with a status (maintainer, 2026-10-06). Only the ride's mode, as the board shows
    // ([OnTheWay.boardLineIds]), never every line at an interchange, which could split the request
    // (Codex, #627). The board's last cut, routes reaching where the rider gets off, needs the routes
    // the screen loads, so a same-mode line that doesn't is still asked. None from a failed board.
    private fun boardLinesNow(): List<String> =
        _nextBoard.value?.takeIf { !it.failed }?.let { board -> OnTheWay.boardLineIds(board.ride, board.boards.values.flatten()) }.orEmpty()

    // Which lines the trip follows now ([RouteDisruption.LinesChecked.following]), said as soon as the trip
    // or its board moves on, not when the next check goes out: a line it no longer follows (the board moved
    // on to the next ride, or failed; a ride left behind) no longer has its last status taken as current,
    // even while this refresh is still on its way to that check (Codex, #627). Called on [compute], where
    // the trip's steps and boards are worked out.
    private fun refollow() {
        val checks = _lineChecks.value ?: return
        val trip = _trip.value
        val following = if (trip == null) emptySet() else (RouteDisruption.comingLines(trip) + boardLinesNow()).toSet()
        if (following != checks.following) _lineChecks.value = checks.copy(following = following)
    }

    /**
     * "Route disruption" (SPEC *On the way*): what's known now that may stop a coming leg
     * ([RouteDisruption.signals]). Something not heard before on this trip is heard
     * ([DisruptionPost.NEW]), and kept on the trip as heard, so a restart doesn't sound it again;
     * while it stays up, each check keeps it up to date silently ([DisruptionPost.KEEP]). Taken down
     * once nothing is known, or the trip has arrived or ended, and never brought back once gone
     * (swiped away, or timed out with its evidence), short of something new. [boards] are this
     * refresh's reads of boarding stops, reused for a change the rider nears ([changeSignal]).
     */
    private suspend fun checkDisruptions(boards: Map<TripLeg, Result<NextBoard>>, rider: LocationFix? = null, asked: Long = elapsed()) {
        val trip = _trip.value
        val progress = _progress.value
        if (trip == null || progress == null || progress == TripProgress.Arrived) {
            _stationNotes.value = null
            _lineChecks.value = null
            takeDisruptionDown()
            return
        }
        val boardLines = boardLinesNow()
        // What this check asks about, said while it's out: a board line's page reads "Checking…" only for
        // a line a check is really asking about, never one the board no longer lists (Codex, #627).
        val asking = (RouteDisruption.comingLines(trip) + boardLines).toSet()
        _lineChecks.value = (_lineChecks.value ?: RouteDisruption.LinesChecked(emptySet(), emptyMap(), null))
            .copy(asking = asking, following = asking)
        val found = try {
            disruptions(trip, progress, directionsOf(trip), boardLines)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unknown, never a signal: what's up comes down rather than stand on no evidence.
            warn("on the way: disruption check failed: ${e::class.simpleName}")
            RouteDisruption.Found.NONE
        }
        // Every line it would have asked about, unchecked, where the check failed before it could say.
        _lineChecks.value = (found.lines ?: RouteDisruption.LinesChecked(asking, emptyMap(), null)).copy(following = asking)
        // No longer than its evidence is current, nor than the trip's own answer stays live ([CURRENT_FOR]
        // from when it was had, [updatedAt]): renewed by each refresh, it comes down by itself once nothing
        // follows the trip (the app closed with no ongoing notification), and at once with a refresh that
        // failed, as the trip's times stop being shown as live then (Codex, PR #441).
        val answered = _updatedAt.value
        // The stations' notes stand as long as their evidence, and no longer than the trip's own answer,
        // whatever the alert does: none while there's no answer to show them against.
        // Those the rider dismissed ([dismissNote]) left out, on [io], never the caller's (the main) thread.
        _stationNotes.value = withContext(io) {
            knownNotes(found.notes.filterNot { it.dismissedIn(trip.disruptionsDismissed) }, found.notesUntil, answered)
        }
        // No train predicted where the rider changes, from a board read for this refresh's answer only:
        // none read once the refresh failed. It stands as long as that answer does.
        val change = answered?.let { changeSignal(trip, progress, boards) }
        // The branch the trip took by itself, none of the plan's trains listed: said as long as that answer.
        val noneDirect = answered?.let { RouteDisruption.noneDirect(trip, progress) }
        // Seen past where they got off a ride ([notePast]): said as long as that answer, as the worst there is.
        val missed = answered?.let { RouteDisruption.missed(trip, progress) }
        // Joined with what else is known there, and what the rider dismissed on the trip's screen
        // ([dismissDisruptions]) left out, neither shown nor alerted again: on [io], not the caller's (the
        // main) thread, as is keying what's new to hear (Codex on #519).
        // Each kept signal stands as long as its own evidence ([RouteDisruption.Found.standsUntil]), and
        // no longer than the trip's own answer: what's kept stands as long as the earliest of them, never
        // as long as one let go of (Codex on #519).
        val (checked, deadline) = withContext(io) {
            val withChange = listOfNotNull(change, noneDirect, missed).fold(found) { all, signal ->
                if (answered != null) all.with(signal, answered.plus(CURRENT_FOR)) else all
            }
            val kept = withChange.copy(signals = withChange.signals.filter { it.dismissKey !in trip.disruptionsDismissed })
            val stands = kept.signals.mapNotNull { signal ->
                withChange.standsUntil(signal)?.let { evidence -> answered?.let { signal.key to minOf(evidence, it.plus(CURRENT_FOR)) } }
            }.toMap()
            val until = if (stands.size == kept.signals.size) stands.values.minOrNull() else null
            Triple(kept, kept.signals.map { it.key }.filter { it !in trip.disruptionsHeard }, RouteDisruption.cards(kept.signals)) to (stands to until)
        }
        val (known, heard, cards) = checked
        val (stands, until) = deadline
        if (known.signals.isEmpty() || until == null) {
            takeDisruptionDown()
            return
        }
        _routeDisruptions.value = KnownDisruptions(known.signals, SteadyClock.stamp(until), cards)
        disruptionStands = stands
        when {
            heard.isNotEmpty() -> if (postDisruption(trip, known.signals, DisruptionPost.NEW, until)) {
                disruptionUp = true
                keep(trip.copy(disruptionsHeard = trip.disruptionsHeard + heard), progress)
            }
            disruptionUp -> if (!postDisruption(trip, known.signals, DisruptionPost.KEEP, until)) takeDisruptionDown(known = false)
        }
        // Only once the alert is out: the routes it may read never hold up what's known (Codex on #479).
        // Seen past a ride's stop, from the stop they're at or heading for: the plan's stops are behind them.
        // Only while that alert stands: once dismissed (Keep going), another alert plans again as it would (Codex, #635).
        val missedKept = known.signals.firstNotNullOfOrNull { it as? RouteDisruption.Signal.Missed }
        val from = missedKept?.let { ReplanOrigin.Stop(it.atId, it.atName) } ?: replanStop(trip, progress, rider, asked)
        // With where the trip goes, worked out here so planning again from it only reads it.
        _replanFrom.value = from?.let { withContext(io) { it.copy(to = ToChoice.of(trip)) } }
    }

    // No train of its line predicted for the ride at a change the rider is a few minutes from
    // ([RouteDisruption.changeNear], [RouteDisruption.unpredicted]), or null. Its board is this refresh's
    // read when it's the trip's next board ([boards]), else read now: one request a refresh, only while
    // such a change is near, which is while riding the ride before it. A read that fails is unknown,
    // never a signal, and isn't asked again this refresh. A trip on the way has no National Rail ride
    // ([OnTheWay.canFollow]), so TfL's board lists every train that may take it.
    private suspend fun changeSignal(
        trip: ActiveTrip,
        progress: TripProgress,
        boards: Map<TripLeg, Result<NextBoard>>,
    ): RouteDisruption.Signal.Unpredicted? {
        val (index, ride) = RouteDisruption.changeNear(trip, progress, clock()) ?: return null
        val read = boards[ride]
        val board = if (read != null) read.getOrNull() ?: return null else try {
            boardOf(ride, fetchedAt = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: change board lookup failed for line ${ride.lineId}: ${e::class.simpleName}")
            return null
        }
        // A pole of the pair that may list a train taking the ride couldn't be read: unknown, never a signal.
        if (board.partial) return null
        // Each of the ride's lines the trip would follow there ([rideLines]) is placed by its own route,
        // kept a day and shared with the trip's cards: another line's train that takes the ride is one
        // predicted (Codex, PR #451), on the board of the pole it boards at. A line left unchecked with a
        // train listed there may have one taking the ride: unknown, never a signal. One with none listed
        // couldn't add one (Codex, PR #460).
        val found = rideLines(trip.route, ride, board.boards)
        if (found.uncheckedOn(board.boards.values.flatten())) return null
        val lines = found.lines
        val departures = OnTheWay.listedFor(ride, board.boards, lines)
        val ids = (listOf(ride.lineId) + departures.filter { OnTheWay.lineOf(lines, it) != null }.map { it.lineId }).distinct()
        return RouteDisruption.unpredicted(index, ride, departures, lines, ids.associateWith { routeOf(it) })
    }

    // The direction each coming ride goes, by its leg, so the line's alert for the other way isn't the
    // rider's. Learned only from the train the trip has taken for the ride (the followed one), read off
    // the ride's board while it lists that train. That's the ride's direction, not only that train's,
    // so it stands for the rest of the ride: once the board is gone (on board), and through the train
    // being dropped or replaced, until a train taken for the ride is seen going another way. Never
    // from the board's other trains (Codex, PR #441): a stop's board lists the line both ways, and
    // where the rider's way has no service, just when its alert matters, it lists only the other.
    // None known, none given, and the line's alerts count both ways ([LineStatus.alongRides]).
    private fun directionsOf(trip: ActiveTrip): Map<Int, String> {
        val board = _nextBoard.value?.takeIf { !it.failed }
        // The followed train's ride is the leg being waited for or ridden, never a later one.
        if (board != null && trip.vehicleId.isNotBlank() && trip.route.legs.getOrNull(trip.legIndex) == board.ride) {
            // The ride's own line's only: another line's own `inbound`/`outbound` needn't be its. A
            // train is told by its line too, since TfL's train ids are each line's own.
            board.departures.firstOrNull { it.vehicleId == trip.vehicleId && it.lineId == board.ride.lineId && OnTheWay.followedLine(trip) == it.lineId }
                ?.direction?.takeIf { it.isNotBlank() }
                ?.let { rideDirections[trip.legIndex] = it }
        }
        // Learned from the ride's own line, it isn't another line's: while another is taken on the leg (its
        // train followed, or ridden by where they were seen: Codex, #459), that leg's is unknown, and its
        // alerts count both ways (Codex, PR #451). Kept for a train of the ride's own line followed again.
        val otherLine = OnTheWay.ridingOn(trip)?.let { it.lineId != trip.leg?.lineId } == true
        return rideDirections.filterKeys { it >= trip.legIndex && !(otherLine && it == trip.legIndex) }
    }

    // Where [trip] would be planned again from ([replanFrom]): its rides ahead placed by their lines'
    // routes, kept a day and shared with the trip's cards, so rarely a request. A route that can't be
    // had leaves its stops unplaced, and the next stop ahead stands in.
    // [rider] is the fix as given at [asked], aged here, once, as it's used: after the routes' reads too.
    private suspend fun replanStop(trip: ActiveTrip, progress: TripProgress, rider: LocationFix?, asked: Long): ReplanOrigin.Stop? {
        val rides = trip.route.legs.withIndex().drop(trip.legIndex)
            .map { (index, leg) -> if (index == trip.legIndex) OnTheWay.ridden(trip) ?: leg else leg }
            .filter { !it.isWalk }
        val ahead = ReplanOrigin.rideAhead(trip, progress)
        // No fix that places the rider: the next stop ahead, whatever the routes say, so none is asked
        // for (Codex, PR #479).
        fun now() = rider?.let { aged(it, Duration.ofMillis(elapsed() - asked)) }?.takeIf(ReplanOrigin::placesRider)
        now() ?: return ReplanOrigin.stopOf(trip, null, emptyMap(), ahead)
        val positions = HashMap<String, Coordinates>()
        for (ride in rides) routeOf(ride.lineId)?.let { positions.putAll(OnTheWay.ridePositions(ride, it)) }
        return ReplanOrigin.stopOf(trip, now(), positions, ahead)
    }

    // [onDisruption] on [io]: the alert's words are put together from every signal known, never on the
    // caller's (the main) thread (Codex on #519). Posting a notification needs no particular thread.
    private suspend fun postDisruption(trip: ActiveTrip, signals: List<RouteDisruption.Signal>, how: DisruptionPost, until: Instant): Boolean =
        withContext(io) { onDisruption(trip, signals, how, until) }

    // [known]: whether what was known goes too (nothing is left, or the trip ended), not only the
    // alert (swiped away), which leaves the trip's screen still showing it.
    private fun takeDisruptionDown(known: Boolean = true) {
        if (known) {
            _routeDisruptions.value = null
            _replanFrom.value = null
        }
        if (!disruptionUp) return
        onDisruptionDone()
        disruptionUp = false
    }

    // [fix] as it stands [waited] later, or null once that makes it too old to act on. One whose age
    // isn't known is taken as it came.
    // Null too once precise location is no longer allowed: every use of a fix ages it first, after
    // whatever it waited behind, so none is used once that's taken away (Codex, #542).
    private fun aged(fix: LocationFix, waited: Duration): LocationFix? {
        if (!preciseAllowed()) return null
        val age = fix.ageMillis ?: return fix
        val now = age + waited.toMillis().coerceAtLeast(0)
        return if (now > OnTheWay.FIX_FRESH_WITHIN_MILLIS) null else fix.copy(ageMillis = now)
    }

    /**
     * A fix seen between refreshes ([app.stopdash.domain.TripFixes]): on a walk, the distance left is
     * measured from it at once, without waiting for the next refresh and its requests (maintainer,
     * 2026-10-04). Nothing else moves on it; the refresh it may bring on does the rest. Never logged
     * or kept.
     */
    suspend fun onFix(fix: LocationFix, arrivedAgoMillis: Long = 0) {
        val received = elapsed()
        // Behind a refresh under way, never beside it: one measured from an older fix would otherwise
        // overwrite this newer distance as it finished (Codex, #542).
        withContext(compute) {
            lock.withLock {
                val trip = _trip.value ?: return@withLock
                // Precise location taken away while it waited: not used, and nothing kept (Codex, #542).
                if (!preciseAllowed()) {
                    forgetFix?.cancel()
                    lastFix = null
                    return@withLock
                }
                // Aged by the wait, so its time taken holds whatever it waited behind.
                val aged = aged(fix, Duration.ofMillis(elapsed() - received)) ?: return@withLock
                val now = clock()
                val arrived = now.minusMillis(elapsed() - received + arrivedAgoMillis)
                // One that came before this trip began isn't this trip's to keep, even for a later walk's
                // estimate: it went with the trip it came on (docs/PRIVACY.md; Codex, #542).
                if (arrived.isBefore(trip.startedAt)) return@withLock
                if (!sawFix(aged)) return@withLock
                // Only a fix that came on this leg of this trip moves its walk: one queued while the trip moved
                // on (a step) came from somewhere the rider no longer is (Codex, #542).
                if (arrived.isBefore(trip.legStartedAt)) return@withLock
                val walking = _progress.value as? TripProgress.Walking ?: return@withLock
                // One that came on the walk but was taken just before it began (on the ride, say) is where
                // the rider likely still is, not where they're seen on it: an estimate, never in place of
                // a distance a fix on the walk gave (Codex, #542).
                val before = now.minusMillis(aged.ageMillis ?: 0).isBefore(trip.legStartedAt)
                if (before && walking.metersLeft != null && !walking.estimated) return@withLock
                val places = walkPlaces?.takeIf { it.first == trip.legIndex }?.second ?: StationPlaces()
                val meters = OnTheWay.metersLeft(trip, aged, places) ?: return@withLock
                _progress.value = walking.copy(metersLeft = meters, estimated = before)
            }
        }
    }

    // Keeps [fix], aged as of [at] (on [elapsed]'s clock), as the newest seen, when precise: whether it
    // was. Its time taken is worked out from when its age held, not when this runs: reads in between
    // would make it seem newer than it is (Codex, #542).
    private fun sawFix(fix: LocationFix, at: Long = elapsed()): Boolean {
        if (fix.isFallback || fix.isCoarse) return false
        val kept = fix to at - (fix.ageMillis ?: 0)
        lastFix = kept
        forgetFix?.cancel()
        forgetFix = forgetting.launch {
            delay((kept.second + ESTIMATE_WITHIN.toMillis() - elapsed()).coerceAtLeast(0) + 1)
            heldFixRef.compareAndSet(kept, null)
        }
        return true
    }

    // The trip's fixes let go: on a start, an end or an arrival, so no coordinate outlives its trip
    // (docs/PRIVACY.md), nor a station read for one trip's walk measures another's (Codex, #542).
    private fun forgetFixes() {
        forgetFix?.cancel()
        forgetFix = null
        lastFix = null
        walkPlaces = null
    }

    // The newest precise fix taken within [ESTIMATE_WITHIN], the trip's own or the app's remembered one,
    // aged to now: where the rider likely still is as a walk begins.
    private fun recentFix(): LocationFix? {
        if (!preciseAllowed()) {
            forgetFix?.cancel()
            lastFix = null
            return null
        }
        expireFix()
        val own = lastFix?.let { (fix, takenAt) -> fix.copy(ageMillis = (elapsed() - takenAt).coerceAtLeast(0)) }
        return listOfNotNull(own, remembered())
            // Sure enough to place the rider, as a fix the trip acts on is ([OnTheWay.FIX_WITHIN_METERS]):
            // one of no stated accuracy, or a vague one, could be far off (Codex, #542).
            .filter { !it.isFallback && !it.isCoarse && (it.ageMillis ?: Long.MAX_VALUE) <= ESTIMATE_WITHIN.toMillis() }
            .filter { fix -> fix.accuracyMeters.let { it != null && it <= OnTheWay.FIX_WITHIN_METERS } }
            .minByOrNull { it.ageMillis ?: Long.MAX_VALUE }
    }

    /** The trip's own last fix held for a walk's estimate, if any: a test's look at what's kept. */
    @VisibleForTesting
    internal fun heldFix(): LocationFix? = lastFix?.first

    // The trip's own last fix dropped once too old to estimate from: never held past its use.
    private fun expireFix() {
        heldFixRef.get()?.let { held -> if (elapsed() - held.second > ESTIMATE_WITHIN.toMillis()) heldFixRef.compareAndSet(held, null) }
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
                _stationNotes.value = null
                _lineChecks.value = null
                forgetFixes()
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
        val places = station?.let { placesOf(it) } ?: StationPlaces()
        // When [seenRider]'s age holds: a use after further reads ages it again ([boardedAlong]).
        val seenAt = elapsed()
        val seenRider = if (station == null) rider?.takeIf { preciseAllowed() } else rider?.let { aged(it, Duration.ofMillis(seenAt - reading)) }
        val now = clock()
        // The train followed coming in, before a fix may have dropped it ([OnTheWay.seen]).
        val followed = _trip.value?.vehicleId.orEmpty()
        val before = _trip.value ?: return false
        var trip = OnTheWay.seen(before, seenRider, now, places)
        // What a fix moved the trip on by, never where: a point or an entrance.
        if (trip.legIndex != before.legIndex) {
            OnTheWay.seenAtStop(before, seenRider, now, places)?.let { warn("on the way: seen at the stop by ${it.label}") }
        }
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
        // The leg TfL answered for in this step, if it did: only a step still on it is that answer's ([answeredAt]).
        var looked: Int? = null
        // Seen along the ride while its train is still awaited: they're on a train that has left the
        // boarding stop, whichever the trip was following (maintainer, 2026-09-29).
        // The leg this step began on, once the fix has moved it: [boardedAlong] can finish the ride.
        val stepLeg = trip.legIndex
        // Still on the walk to a ride, but further on than they could have walked: on a train past a station
        // no fix caught them at. Their walk is done if they're seen along that ride, never otherwise.
        val along = seenRider?.let { rider ->
            boardedAlong(trip, rider, seenAt, now) ?: OnTheWay.pastWalkOnFoot(trip, rider, now)?.let { walked ->
                boardedAlong(walked, rider, seenAt, now)?.takeIf { it.trip.legIndex > trip.legIndex }
            }
        }
        if (along != null) {
            trip = along.trip
            calls = along.calls
            // Their train found by where they were seen, its calls read: the ride's own answer (Codex, #611).
            looked = trip.legIndex.takeIf { along.calls != null && !along.failed }
            // Seen where they get off, the ride is done: a "get off soon" said for it is taken back, as when
            // a fix moves them on above (Codex, PR #449).
            if (before.warnedLeg == before.legIndex && trip.legIndex != before.legIndex) trip = trip.copy(alertLeft = true)
            // On board by where they were seen, but the lookup for their train failed: said (Codex, PR #449).
            if (along.failed) failed = true
            // ...or the boarding stop's board, read this refresh, failed: a train it might have listed went
            // unseen, so their train may be one not looked for (Codex, PR #449).
            if (OnTheWay.ridingUnmatched(trip) && (board?.isFailure == true || board?.getOrNull()?.partial == true)) failed = true
        } else if (OnTheWay.ridingUnmatched(trip)) {
            // Counted by where they were seen ([OnTheWay.advance]), its train looked for only with a fresh
            // fix ([boardedAlong]): nothing to look up.
        } else if (leg != null && !leg.isWalk && OnTheWay.changeUntil(trip, now) == null) {
            try {
                if (trip.vehicleId.isBlank()) {
                    searched = true
                    val found = pick(trip, now, board)
                    val picked = found.picked
                    if (picked != null) {
                        trip = OnTheWay.follow(trip, picked.first, picked.second)
                        calls = picked.third
                    } else if (found.unchecked) {
                        // None found, but a line of the ride went unchecked: a train of it may be theirs, so
                        // the refresh failed rather than finding no train (Codex, PR #459).
                        failed = true
                    }
                } else {
                    calls = vehicles.vehicleCalls(trip.vehicleId, OnTheWay.followedLine(trip))
                }
                looked = trip.legIndex.takeIf { !failed }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                if (trip.vehicleId.isBlank()) {
                    // [pick] passes over a train TfL doesn't know, so this is the boarding stop's board:
                    // a failed update, not a train still being found.
                    warn("on the way: boarding stop not found on line ${leg.lineId}")
                    failed = true
                } else {
                    warn("on the way: train not found on line ${OnTheWay.followedLine(trip)}")
                    // A train not yet boarded that TfL no longer knows: dropped, so the next refresh
                    // picks another. Once on board it stays followed: TfL has only gone quiet on it.
                    if (trip.boarded) failed = true else trip = trip.copy(vehicleId = "", vehicleLeg = null, vehicleOffId = "", boardsAt = null, dueOffAt = null, heldFrom = null)
                }
            } catch (e: TflException) {
                warn("on the way: train lookup failed for line ${if (trip.vehicleId.isBlank()) leg.lineId else OnTheWay.followedLine(trip)}: ${e::class.simpleName}")
                failed = true
            }
        }
        // On board by where they were seen, the ride is counted from location, not TfL: a failed lookup
        // for their train leaves that standing, so the step still moves on with each fix (and says "get
        // off soon"), and only the failure is said (Codex, PR #449).
        val positional = failed && OnTheWay.ridingUnmatched(trip)
        if (failed && !positional) {
            // Nothing new is known: the step stands as it was, not Lost, and the last answer is no
            // longer stood behind on any surface (its time, stops left and get off soon wait).
            _failed.value = true
            _updatedAt.value = null
            // The last answer's time stays only for the step it answered.
            if (answeredStep(trip) != answeredFor) _answeredAt.value = null
            // A "time to board" counting down to a time no longer stood behind comes down with it
            // (D4), for good: it has been heard, and bringing it back could bring back one the rider
            // swiped away, which nothing here can see once the refresh has failed (Codex, PR #440).
            if (boardUp) {
                onBoardSoonDone()
                boardUp = false
            }
            // The step as it was, unless a fix just moved the trip on: left behind (finding a train,
            // never the ride the rider isn't on), or seen at the stop they walked to (waiting there,
            // never still walking).
            val same = trip.vehicleId == before.vehicleId && trip.vehicleLeg == before.vehicleLeg && trip.legIndex == before.legIndex
            keep(trip, _progress.value?.takeIf { same } ?: standing(trip, now))
            return false
        }
        // Still seen at the boarding stop, by the fix this refresh took (aged by the reads since): the
        // train's call there is theirs, however late, not a loop's next lap ([OnTheWay.atBoarding]).
        val atBoarding = OnTheWay.atBoarding(trip, rider?.let { aged(it, Duration.ofMillis(elapsed() - reading)) })
        var (next, progress) = OnTheWay.advance(trip, calls, now, atBoarding)
        // Lost on board: where its calls put the train says which forks are behind it, one it has turned off
        // at included (Codex, #586).
        val lost = progress as? TripProgress.Lost
        if (lost != null && (next.boarded || next.onBoardSeen) && next.legIndex == trip.legIndex && !calls.isNullOrEmpty()) {
            val byCalls = OnTheWay.aheadOnLeg(OnTheWay.ridden(next) ?: lost.leg, calls)
            progress = lost.copy(ahead = maxOf(lost.ahead ?: -1, byCalls), placed = true)
        }
        // How far the walk's end is, from this refresh's fix, or as last seen on the same walk: a refresh
        // without one doesn't blank it. Shown on the trip's screen only, never logged (SPEC *Privacy*).
        // The newest fix seen: a later walk's first distance, as an estimate, until a fix on it ([recentFix]).
        // Asked again after the reads above: precise location may have been taken away meanwhile, and then
        // this fix is neither kept nor measured from (Codex, #542).
        val measurable = seenRider?.takeIf { preciseAllowed() }
        if (seenRider != null && measurable == null) {
            forgetFix?.cancel()
            lastFix = null
        }
        measurable?.let { sawFix(it, at = if (station == null) reading else seenAt) }
        (progress as? TripProgress.Walking)?.let { walking ->
            // The station read for this walk only: one read for a ride got off at is behind the rider.
            // A refresh with no fix reads no station: this walk's read stays, so a fix between refreshes still
            // measures to its entrances (Codex, #542).
            val walkedTo = if (station == null && next.legIndex == before.legIndex) {
                walkPlaces?.takeIf { it.first == next.legIndex }?.second ?: StationPlaces()
            } else {
                places.takeIf { next.legIndex == before.legIndex && OnTheWay.stationWalkedTo(next, now) != null } ?: StationPlaces()
            }
            walkPlaces = next.legIndex to walkedTo
            val seen = withContext(compute) { OnTheWay.metersLeft(next, measurable, walkedTo) }
            val held = (_progress.value as? TripProgress.Walking)?.takeIf { it.leg == walking.leg && it.metersLeft != null }
            // From this refresh's fix; else as last seen on the same walk, so a refresh without one doesn't
            // blank it; else, a walk just begun, estimated from a fix taken lately (maintainer, 2026-10-04).
            // A fix taken before this walk began (the one that moved the trip onto it, say) is where the
            // rider likely is, not where they're seen on it: an estimate, as in [onFix] (Codex, #542).
            val seenBefore = seenRider != null && now.minusMillis(seenRider.ageMillis ?: 0).isBefore(next.legStartedAt)
            progress = when {
                seen != null && !(seenBefore && held != null && !held.estimated) -> walking.copy(metersLeft = seen, estimated = seenBefore)
                held != null -> walking.copy(metersLeft = held.metersLeft, estimated = held.estimated)
                else -> {
                    val guessed = recentFix()?.let { fix -> withContext(compute) { OnTheWay.metersLeft(next, fix, walkedTo) } }
                    walking.copy(metersLeft = guessed, estimated = guessed != null)
                }
            }
        }
        // A train that turned out not to be the rider's: drop it, so the next refresh picks another.
        // Once on board it stays followed: TfL has only gone quiet on it.
        if (progress is TripProgress.Lost && calls != null && !next.boarded) next = next.copy(vehicleId = "", vehicleLeg = null, vehicleOffId = "", dueOffAt = null)
        if (trip.warnedLeg == trip.legIndex && (next.legIndex != trip.legIndex || progress == TripProgress.Arrived)) {
            next = next.copy(alertLeft = true)
        }
        // The train lost on the leg it was said for: the stop it named may not be the rider's now, so
        // it's taken back, and said again once the train is found on the leg. A failed lookup (above)
        // leaves it: nothing new is known, and the stop is still the one planned.
        if (progress is TripProgress.Lost && next.warnedLeg == next.legIndex) {
            next = next.copy(warnedLeg = -1, alertLeft = true)
        }
        // Said by the Planner's time, then seen held short of that stop: taken back, and said again
        // when they're due one stop out from where they were seen (Codex, #572).
        if (OnTheWay.warningWithdrawn(next, progress)) {
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
        // "Time to board" as the train waited for comes in (SPEC *On the way*). While it stands, each
        // fresh answer posts it again, silently, so its countdown follows the train and it lasts only
        // as long as the answer is live ([BoardPost]).
        if (progress is TripProgress.Waiting) {
            val stands = OnTheWay.boardStands(next, progress)
            // Whether it's time is judged by the clock now, after this step's lookups, so a slow one
            // doesn't hold it back a refresh; the answer's own time ([now]) still ages it.
            when {
                OnTheWay.shouldBoard(next, progress, clock()) -> if (onBoardSoon(next, progress, BoardPost.NEW, now)) {
                    next = OnTheWay.saidBoard(next)
                    boardUp = true
                }
                // Gone (swiped away, timed out with the app away, or down with a failed refresh): not
                // brought back. One this answer can't keep up (its train with no time, or past it, or
                // the answer no longer live) is taken down now, not left counting down to its timeout.
                stands && boardUp -> if (!onBoardSoon(next, progress, BoardPost.KEEP, now)) {
                    onBoardSoonDone()
                    boardUp = false
                }
            }
        }
        // The ride ahead changed in this step: a board for one now boarded (or passed) is no longer
        // theirs to board from, and the next ride's (off a train and walking on) is read now, not a
        // refresh later. A failed read is said on the section alone: the step itself stood.
        val ahead = OnTheWay.upcomingRide(next)
        if (_nextBoard.value?.ride != ahead) {
            if (ahead == null) _nextBoard.value = null else fetchBoard(next, now, boards)
            refollow()
        }
        // Counted from where they were seen, nothing shown stands on the failed lookup (no train's time,
        // no arrival: [OnTheWay.eta]), so the step is current; the failure is still said.
        _failed.value = positional
        _updatedAt.value = now
        // Only for the leg answered: one the answer moved the trip on to (off a train straight onto the next
        // ride, say) has had no answer of its own (Codex, #611).
        if (looked == next.legIndex) {
            _answeredAt.value = now
            answeredFor = answeredStep(next)
        } else if (answeredStep(next) != answeredFor) {
            _answeredAt.value = null
        }
        if (progress == TripProgress.Arrived) {
            // Forgotten on the device first: the trip gone from the screen can cancel the caller.
            // One that can't be would come back on the next start, so it's kept, said, and tried
            // again on the next refresh.
            _progress.value = progress
            // Arrived: a "time to board" still up goes now, whether or not forgetting the trip works.
            settleBoard()
            if (!withContext(io) { save(null) }) {
                // Counted once, as End's is, not on each retry: the screen reopens on it once.
                if (!_endFailed.value) _endFailures.value++
                _endFailed.value = true
                return false
            }
            _endFailed.value = false
            _endFailures.value = 0
            _trip.value = null
            _stationNotes.value = null
            _lineChecks.value = null
            forgetFixes()
            // Forgotten: no trip left for an alert to belong to.
            if (next.alertLeft) onGetOffSoonDone()
        } else {
            keep(next, progress)
        }
        // A ride with no train yet, reached now or with its train just dropped, picks one at once.
        val onward = next.legIndex != stepLeg || (followed.isNotBlank() && !next.boarded && !searched)
        return onward && next.leg?.isWalk == false && next.vehicleId.isBlank()
    }

    // [trip] on board the ride the rider was seen along ([OnTheWay.seenAlong]), with its train's calls
    // when it can be told: the most recent of the trains its board listed that has left the boarding
    // stop and is on the ride at or beyond where they were seen ([OnTheWay.boardedOn]). With none found
    // they're on board all the same, by where they were seen ([OnTheWay.onBoardAlong], maintainer,
    // 2026-10-01), with no calls: along the ride's own line, or else along another of its lines, its
    // stops counted on that line's own path (Codex, PR #449). A train is only ever matched against this fresh fix, never
    // against where they were last seen, which says where but not when (maintainer, 2026-10-01). Null
    // when they aren't seen along it. Seen where they get off, the ride is done, with no calls
    // ([OnTheWay.rideDone]). [rider]'s age holds at [seenAt] ([elapsed]).
    private suspend fun boardedAlong(trip: ActiveTrip, rider: LocationFix, seenAt: Long, now: Instant): SeenAlong? {
        // On board by where they were seen, the line they were seen along ([OnTheWay.ridden]).
        val leg = OnTheWay.waitingToBoard(trip, now) ?: OnTheWay.ridden(trip)?.takeIf { OnTheWay.ridingUnmatched(trip) } ?: return null
        // The ride as the Planner gives it, whose lines are offered ([rideLines]).
        val planned = trip.leg ?: return null
        // The ride's own line's route, when it can be had: another of the ride's lines is placed by its
        // own, so one isn't held back by this (Codex, PR #451). On board by where they were seen, it's
        // what places them, so failing to read it is said: their position stands, but the refresh that
        // couldn't move it on isn't passed off as current (Codex, PR #449).
        val sequence = try {
            lineSequence(leg.lineId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: route lookup failed for line ${leg.lineId}: ${e::class.simpleName}")
            if (OnTheWay.ridingUnmatched(trip)) {
                // The fix is still said, placed nowhere: a missed "get off soon" is read off these (Codex, #566).
                warn(OnTheWay.seenAlongNote(trip, leg, null, rider.accuracyMeters))
                return SeenAlong(trip, failed = true)
            }
            null
        }
        // Aged by the board's and the route's reads: after a slow TfL, a fix fresh when the step began
        // may be where the rider was, not where they are, and no proof they boarded (Codex, PR #383).
        val seen = aged(rider, Duration.ofMillis(elapsed() - seenAt)) ?: return null
        val along = sequence?.let { OnTheWay.seenAlong(trip, seen, OnTheWay.ridePositions(leg, it), now) }
        // On board by where they're seen, each fix is all that moves the stop count toward "get off soon":
        // logged where it's settled, on the line that placed it, or as placed nowhere (Codex, #566).
        fun note(on: TripLeg, where: OnTheWay.Along?) {
            if (OnTheWay.ridingUnmatched(trip) || where?.atEnd == true) warn(OnTheWay.seenAlongNote(trip, on, where, seen.accuracyMeters))
        }
        // Whichever train took them there: no train's calls are needed, nor would one still calling
        // there say anything but "get off" to a rider already off (Codex, PR #383).
        if (along?.atEnd == true) {
            note(leg, along)
            return SeenAlong(OnTheWay.rideDone(trip, now))
        }
        // Already on board by where they were seen further on, a fix no further than that keeps where they
        // are and matches nothing: a train placed from it could be one behind them (Codex, PR #449). Seen
        // at a stop is short of being seen past it, though both have the same stop ahead.
        if (along != null && OnTheWay.ridingUnmatched(trip)) {
            val ahead = OnTheWay.ahead(along)
            if (trip.seenAlongStop > ahead || (trip.seenAlongStop == ahead && along.atStop)) {
                note(leg, along)
                // Seen as far as before (a train held at a stop): the Planner's time per stop is counted
                // again from now, not spent standing (Codex, #572). Further back changes nothing.
                return SeenAlong(if (trip.seenAlongStop == ahead) OnTheWay.onBoardAlong(trip, along, now, on = leg) else trip)
            }
        }
        // Gone from the latest board, or due by now: left the stop (the rider, seen away from it, is on one).
        // With no board for the ride (gone once they're on board by where they were seen), only those due
        // by now: later ones, not yet left, would crowd theirs out of the few asked after (Codex, PR #449).
        // Each pole of the pair by its own board ([NextBoard.boards]): one not read the last time has none.
        val latest = _nextBoard.value?.takeIf { it.ride == planned }
        val board = latest?.boards
        val gone = boardSeen.takeIf { boardSeenRide == planned }.orEmpty().mapValues { (stop, seen) ->
            val listed = board?.get(stop)?.mapTo(HashSet(), ::seenKey)
            seen.values.filter { (listed != null && seenKey(it) !in listed) || !it.expectedArrival.isAfter(now) }
        }.filterValues { it.isNotEmpty() }
        // Of the ride's lines the trip would follow ([rideLines]), each placed by its own route and
        // stops: another line can take the ride by other stops between, and the rider is seen along
        // the way its trains go. Only those its own route takes where the rider gets off: another
        // branch's can look like the ride until it turns off, which its calls may not reach yet (Codex,
        // PR #383). A line whose route can't be had places none of its trains.
        // Read off the board and the trains gone from it: a line known only by a train that has left
        // is one of the ride's lines still, its train the one to match (Codex, PR #451). With no board
        // (on board), the stop's board read for its lines when they aren't seen along this one
        // ([linesListed]): never what it listed before, which is kept only for the trains gone from it.
        val routes = hashMapOf<String, LineSequence?>(leg.lineId to sequence)
        val found = mutableListOf<Triple<Departure, TripLeg, OnTheWay.Along>>()
        // Trains that may be theirs but can't be told: met in the walk below, each stops it, naming none.
        val unsure = HashSet<Departure>()
        // Where they're on board by position if no train is found: along the line just checked, or
        // else the first other line of the ride they're seen along (Codex, PR #449).
        var positional = along?.let { leg to it }
        // Whether the stop's board couldn't be read for the ride's lines ([linesListed]), or a pole of its
        // pair couldn't be ([NextBoard.partial]): those lines went unchecked, so the refresh isn't passed off
        // as current (Codex, PR #459).
        var linesFailed = latest?.partial == true
        // Another line is looked at for its trains gone from the board, or, not seen along this one,
        // for where they are: a line's route is a request (held a day), not asked for otherwise.
        if (gone.isNotEmpty() || along == null) {
            val listed = board ?: if (along != null) emptyMap() else {
                val read = linesListed(planned)
                if (read == null || read.partial) linesFailed = true
                read?.boards.orEmpty()
            }
            val boards = (listed.keys + gone.keys).associateWith { stop -> (listed[stop].orEmpty() + gone[stop].orEmpty()).distinctBy(::seenKey) }
            val ride = rideLines(trip.route, planned, boards)
            // Any line unchecked counts here, listed or not: a line of the plan can be ridden with no train of
            // it on the board, and the rider may be on it.
            if (ride.unchecked) linesFailed = true
            // Each gone from the board of the pole its line boards at: across the road, it went the other way.
            val left = OnTheWay.listedFor(planned, gone, ride.lines, read = boards.keys)
            for (on in ride.lines) {
                val trains = left.filter { it.lineId == on.lineId }
                if (trains.isEmpty() && positional != null) continue
                val route = if (on.lineId in routes) routes[on.lineId] else routeOf(on.lineId).also { routes[on.lineId] = it }
                if (route == null) continue
                val onAlong = if (on == leg) {
                    along
                } else {
                    // Aged again by the line checks and this route's read: after a slow TfL, a fix fresh
                    // before them may be where the rider was, and no proof they got off (Codex, PR #451).
                    val fresh = aged(rider, Duration.ofMillis(elapsed() - seenAt)) ?: return null
                    OnTheWay.seenAlong(trip, fresh, OnTheWay.ridePositions(on, route), now, on = on)
                } ?: continue
                if (onAlong.atEnd) {
                    note(on, onAlong)
                    return SeenAlong(OnTheWay.rideDone(trip, now))
                }
                if (positional == null) positional = on to onAlong
                // With one its route can't place (no destination, where the line parts beyond), kept as
                // unknown ([unsure]): it may be theirs (Codex, PR #462).
                val sequences = mapOf(on.lineId to route)
                val taking = OnTheWay.takesRide(on, trains, sequences)
                OnTheWay.mayTakeRide(on, trains, sequences).forEach {
                    if (it !in taking) unsure += it
                    found += Triple(it, on, onAlong)
                }
            }
        }
        // Placed nowhere, their position stands; with the lines unread, that's said, as for a route
        // that couldn't be read above.
        if (positional == null && found.all { it.first in unsure }) {
            note(leg, null)
            return SeenAlong(trip, failed = true).takeIf { linesFailed && OnTheWay.ridingUnmatched(trip) }
        }
        var failed = linesFailed
        // The trains gone from the board first, the latest to leave first; then, none of them theirs or
        // none known, the board at the stop ahead of them ([aheadOf]): after a restart, or for a train that
        // came and went between refreshes, the boarding stop's board never listed theirs, but the stop ahead
        // lists it, arriving. There trains reach it in the order they run: any between them and that stop,
        // then theirs, then those behind. So it's walked soonest first until a train its calls show behind
        // them ([OnTheWay.behind]) marks where theirs ends, or the board does; a few lookups that find neither
        // name no train, rather than one that may be ahead of theirs (Codex, PR #462). Seen at a stop, one
        // behind is still due there, so the latest past them is theirs; between stops, one behind that has
        // left the boarding stop calls next where theirs does, as one ahead does, so only a lone train is
        // told for theirs. Found that way, their ride's time still runs from when they were first seen on
        // board.
        var ahead = false
        var candidates = found.sortedByDescending { it.first.expectedArrival }.take(PICK_TRIES)
        var best: SeenAlong? = null
        var matches = 0
        var bounded = false
        var unknown = false
        // Seen between stops, the newest train gone from the board that's past them: theirs, unless an
        // older one of its line they could have boarded may be at the same spot ([twinOf]).
        var lone: Lone? = null
        trains@ while (true) {
            var lookups = 0
            for ((train, on, onAlong) in candidates) {
                // Gone from the board, it stops the walk short of the board ahead too.
                if (train in unsure) {
                    if (!ahead) break@trains
                    unknown = true
                    break
                }
                if (lookups == PICK_TRIES) break
                lookups++
                val calls = try {
                    vehicles.vehicleCalls(train.vehicleId, train.lineId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TflException.NotFound) {
                    // Gone from TfL's view (at its terminus): not the train of a rider still on their way.
                    // Said, coarsely, as every lookup's failure is: the line, never the train or where.
                    warn("on the way: train not found on line ${train.lineId}")
                    // Arriving at the stop ahead, it may be theirs, gone in a race between the board and
                    // the lookup: the trains either side of it can't then be told apart, so none is named
                    // (Codex, PR #462).
                    if (ahead) {
                        unknown = true
                        break
                    }
                    continue
                } catch (e: TflException) {
                    warn("on the way: train lookup failed for line ${train.lineId}: ${e::class.simpleName}")
                    failed = true
                    break@trains
                }
                // Aged again by each lookup: after a slow one, the fix may be where the rider was, and
                // no proof which of these newer calls they're on (Codex, PR #383).
                aged(rider, Duration.ofMillis(elapsed() - seenAt)) ?: return null
                // A bus can use the other pole of either stop pair from the one the Planner named: the
                // poles and stop areas the ride's route and this train's own know of.
                val known = listOfNotNull(sequence, routes[train.lineId])
                val boardingPoles = known.flatMapTo(HashSet()) { OnTheWay.pairPoles(leg.fromArea, it) }
                val alightingPoles = known.flatMapTo(HashSet()) { OnTheWay.pairPoles(leg.toArea, it) }
                val areas = known.fold(emptyMap<String, String>()) { all, route -> all + route.stopAreas }
                val matched = OnTheWay.boardedOn(
                    trip, train, calls, onAlong.from, now, boardingPoles, alightingPoles, areas, atStop = onAlong.atStop, on = on,
                )
                if (matched == null) {
                    // A train gone from the board with no calls to place it, in a race with TfL's
                    // predictions, may be theirs: neither a train that left before it nor one on the board
                    // ahead is told for theirs (Codex, PR #462).
                    if (!ahead && calls.isEmpty()) break@trains
                    if (!ahead) continue
                    // On the board ahead, only a train its calls show behind them ([OnTheWay.behind])
                    // tells where theirs ends; one they don't place (none, as in a race with TfL's
                    // predictions, or none on the ride) may be theirs, so none is named (Codex, PR #462).
                    if (!OnTheWay.behind(trip, calls, onAlong.from, now, boardingPoles, alightingPoles, areas, atStop = onAlong.atStop, on = on)) {
                        unknown = true
                        break
                    }
                    if (matches > 0) {
                        bounded = true
                        break
                    }
                    continue
                }
                if (!ahead) {
                    // Seen at a stop, one behind them is still due there, so the latest past them is theirs.
                    if (onAlong.atStop) {
                        note(on, onAlong)
                        return SeenAlong(matched.first, calls)
                    }
                    val next = OnTheWay.nextAlong(
                        trip, calls, onAlong.from, now, boardingPoles, alightingPoles, areas, atStop = false, on = on,
                    ) ?: continue
                    lone = Lone(SeenAlong(matched.first, calls), train, on, onAlong, next, routes[on.lineId], boardingPoles, alightingPoles, areas)
                    break@trains
                }
                matches++
                // Due at the stop ahead, not at theirs: when it was there isn't known, so it isn't taken
                // for a loop's next lap calling there (Codex, PR #462).
                best = SeenAlong(matched.first.copy(boardedAt = trip.boardedAt ?: matched.first.boardedAt, boardsAt = null), calls)
            }
            if (ahead) {
                // Every train it lists tried: the board ends where theirs may.
                if (lookups == candidates.size) bounded = true
                best?.takeIf { bounded && !unknown && (positional?.second?.atStop == true || matches == 1) }?.let {
                    positional?.let { (on, where) -> note(on, where) }
                    return it
                }
                break
            }
            val (seenOn, where) = positional ?: break
            ahead = true
            // Only trains its line's route takes where the rider gets off, as for those gone from the board
            // ([OnTheWay.takesRide]): not a short working or another branch's (Codex, PR #462). One its route
            // can't place (no destination, where the line parts beyond) may be theirs: met in the walk, it
            // stops it, as a train its calls don't place does (Codex, PR #462).
            val listed = aheadOf(seenOn, where, routes[seenOn.lineId]) ?: run {
                failed = true
                break@trains
            }
            val sequences = mapOf(seenOn.lineId to routes[seenOn.lineId])
            val taking = OnTheWay.takesRide(seenOn, listed, sequences)
            val maybe = OnTheWay.mayTakeRide(seenOn, listed, sequences)
            // Nor one with no train to look up (Codex, PR #462).
            maybe.filterTo(unsure) { it !in taking || it.vehicleId.isBlank() }
            candidates = maybe.map { Triple(it, seenOn, where) }.sortedBy { it.first.expectedArrival }
        }
        // The newest train past them, alone where it calls next: theirs. With an older one of its line at the
        // same spot, or one not to be told (its calls in a race, its lookup failed, which is said, or too many
        // to look up), neither.
        val twin = lone?.let { twinOf(trip, it, found, now) }
        if (twin?.second == true) failed = true
        // Grown too old over the lookups, it is no proof of where they are now.
        aged(rider, Duration.ofMillis(elapsed() - seenAt)) ?: return null
        if (lone != null && twin?.first == OnTheWay.Twin.APART) {
            note(lone.on, lone.along)
            return lone.seen
        }
        // Seen along one of the ride's lines with none of its trains theirs, or two that can't be told apart:
        // on board all the same, by where they were seen, counted on that line's stops. A failed lookup is
        // said (the refresh failing), but doesn't leave them shown at the platform (Codex, PR #449). Two
        // trains of a line not told apart are both of that line: they're counted on it, not on the first
        // line they were seen along, which can share its way (Codex, PR #465).
        val (seenOn, where) = lone?.let { it.on to it.along } ?: positional ?: run {
            note(leg, null)
            return null
        }
        if (twin != null && !failed) {
            warn("on the way: seen along the ride between stops with two trains that left on line ${seenOn.lineId}: on board by where seen")
        } else if (!failed) {
            warn("on the way: seen along the ride, no train that left found on line ${seenOn.lineId}: on board by where seen")
        }
        warn(OnTheWay.seenAlongNote(trip, seenOn, where, seen.accuracyMeters))
        return SeenAlong(OnTheWay.onBoardAlong(trip, where, now, on = seenOn), failed = failed)
    }

    // What a rider seen along their ride ([boardedAlong]) makes of [trip]: on the train that took them,
    // with its [calls], or on board by where they were seen with none; [failed] when a lookup for their
    // train failed, which the refresh reports.
    private class SeenAlong(val trip: ActiveTrip, val calls: List<VehicleCall>? = null, val failed: Boolean = false)

    // The newest train gone from the board that's past a rider seen between stops ([boardedAlong]): what it
    // makes of the trip, the train, the ride as its line runs it, where they were seen on it, where it
    // calls next ([OnTheWay.nextAlong]), its line's route, and what placed its calls.
    private class Lone(
        val seen: SeenAlong,
        val train: Departure,
        val on: TripLeg,
        val along: OnTheWay.Along,
        val next: Int,
        val route: LineSequence?,
        val boardingPoles: Set<String>,
        val alightingPoles: Set<String>,
        val areas: Map<String, String>,
    )

    // How the older trains of [first]'s line among those gone from the board ([found], any of the ride's
    // lines, newest first) stand to it ([OnTheWay.twinOf]): APART only when each the rider could have
    // boarded ([OnTheWay.boardableFrom]: a board read during a change keeps one due before they could be at
    // the stop, Codex, PR #465) is shown ahead of them, or TfL no longer knows it (gone, at its terminus:
    // past them). Each, not just the next: trains of a line overtake, so one that left earlier can be at
    // their spot behind one that's ahead (Codex, PR #465). A lookup each, only then, and no more than
    // [PICK_TRIES]: with more, any of the rest may be at their spot, so it's unknown. With whether a lookup
    // failed, said by the refresh.
    private suspend fun twinOf(
        trip: ActiveTrip,
        first: Lone,
        found: List<Triple<Departure, TripLeg, OnTheWay.Along>>,
        now: Instant,
    ): Pair<OnTheWay.Twin, Boolean> {
        val newestFirst = found.sortedByDescending { it.first.expectedArrival }
        val from = newestFirst.indexOfFirst { it.first == first.train && it.second == first.on }
        val boardable = OnTheWay.boardableFrom(trip)
        val older = newestFirst.drop(from + 1)
            .filter { it.second.lineId == first.on.lineId && !it.first.expectedArrival.isBefore(boardable) }
            .map { it.first }
        if (older.size > PICK_TRIES) return OnTheWay.Twin.UNKNOWN to false
        for (train in older) {
            val calls = try {
                vehicles.vehicleCalls(train.vehicleId, train.lineId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                warn("on the way: train not found on line ${train.lineId}")
                continue
            } catch (e: TflException) {
                warn("on the way: train lookup failed for line ${train.lineId}: ${e::class.simpleName}")
                return OnTheWay.Twin.UNKNOWN to true
            }
            val twin = OnTheWay.twinOf(
                trip, first.next, calls, first.along.from, now, first.route, first.boardingPoles, first.alightingPoles, first.areas, on = first.on,
            )
            if (twin != OnTheWay.Twin.APART) return twin to false
        }
        return OnTheWay.Twin.APART to false
    }

    // Each line's route as a refresh read it, answer or failure: asked for once a refresh, so a failing
    // TfL brings no second request for it (Codex, #630). Null outside a refresh.
    private var routesRead: HashMap<String, LineSequence?>? = null

    // Where [rider] puts the trip's rider against the ride they last got off ([OnTheWay.lastRideOff]):
    // seen past its stop on its line ([OnTheWay.pastStop]), kept with the trip as where to plan again
    // from; or, seen past it before, back there or on toward the next ride ([OnTheWay.backFromPast]),
    // let go of; so too, fix or none, once they've boarded on from it, so going back never brings it back
    // (Codex, #635). Said in the log by the ride and a stop id, never where the rider is.
    private suspend fun notePast(given: LocationFix?, asked: Long) {
        val trip = _trip.value ?: return
        val progress = _progress.value ?: return
        val settled = OnTheWay.settledPast(trip)
        if (settled != trip) {
            keep(settled, progress)
            return
        }
        val index = OnTheWay.lastRideOff(trip) ?: return
        if (given == null) return
        // The line ridden, where another of the ride's took it (Codex, #635).
        val marked = trip.pastLeg == index && trip.pastAtId.isNotBlank()
        // Back where the trip keeps them placed, looked for before any route read, which can be a slow request
        // the fix would age out waiting on (Codex, #635).
        if (marked) {
            val early = aged(given, Duration.ofMillis(elapsed() - asked))
            val nextAt = listOfNotNull(OnTheWay.rideAfterPast(trip)?.fromAt)
            if (early != null && withContext(compute) { OnTheWay.backFromPast(trip, early, null, nextAt) }) {
                warn("on the way: back from past the stop of ride $index")
                keep(trip.copy(pastLeg = -1, pastAtId = "", pastAtName = ""), progress)
                return
            }
        }
        // The next ride's stop, where the Planner placed it and everywhere its line's routes do, by any id one
        // calls it: a big interchange's platforms can be far from the Planner's point (Codex, #635). Looked for
        // before the gone-past line's route is read, whose read the fix could age out waiting on (Codex, #635).
        val next = if (marked) OnTheWay.rideAfterPast(trip) else null
        val nextAt = next?.let { ride ->
            listOfNotNull(ride.fromAt) + routeOf(ride.lineId)?.let { withContext(compute) { OnTheWay.boardingPlaces(ride, it) } }.orEmpty()
        }.orEmpty()
        if (marked && nextAt.isNotEmpty()) {
            val seen = aged(given, Duration.ofMillis(elapsed() - asked))
            if (seen != null && withContext(compute) { OnTheWay.backFromPast(trip, seen, null, nextAt) }) {
                warn("on the way: back from past the stop of ride $index")
                keep(trip.copy(pastLeg = -1, pastAtId = "", pastAtName = ""), progress)
                return
            }
        }
        // Without it, only going back is looked for, by the places kept with the trip (Codex, #635).
        val route = routeOf(OnTheWay.offRide(trip, index).lineId)
        // Aged by the routes' reads, which can be requests: a fix fresh before them may be where the rider was (Codex, #635).
        val rider = aged(given, Duration.ofMillis(elapsed() - asked)) ?: return
        val now = clock()
        if (marked && withContext(compute) { OnTheWay.backFromPast(trip, rider, route, nextAt) }) {
            warn("on the way: back from past the stop of ride $index")
            keep(trip.copy(pastLeg = -1, pastAtId = "", pastAtName = ""), progress)
            return
        }
        if (route == null) return
        // Kept going from it once: not looked for again on this ride, nor fixes asked for to (Codex, #635).
        if (!marked && RouteDisruption.missedKey(index, OnTheWay.offRide(trip, index).toId) in trip.disruptionsDismissed) return
        // Seen past it, or further on since: where to plan again from moves with their train (Codex, #635).
        // Once seen past it, followed as far on as their train takes them, not only its first few stops (Codex, #635).
        val past = withContext(compute) { OnTheWay.pastStop(trip, rider, route, now, if (marked) Int.MAX_VALUE else OnTheWay.PAST_STOPS, following = marked) } ?: return
        if (marked && past.atId == trip.pastAtId) return
        warn("on the way: seen past the stop of ride $index, by stop ${past.atId}")
        keep(trip.copy(pastLeg = past.rideIndex, pastAtId = past.atId, pastAtName = past.atName), progress)
    }

    // [lineId]'s route ([lineSequence]), or null when it can't be had: a failure said coarsely, by
    // the line and the kind of error.
    private suspend fun routeOf(lineId: String): LineSequence? {
        routesRead?.let { read -> if (lineId in read) return read[lineId] }
        val route = try {
            lineSequence(lineId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: route lookup failed for line $lineId: ${e::class.simpleName}")
            null
        }
        routesRead?.put(lineId, route)
        return route
    }

    // What [pick] found: the train to follow, with the ride as its line runs it and its calls, or none;
    // and whether the boards list a train of a line that went unchecked ([RideLinesNow.uncheckedOn]), or
    // a pole of the pair couldn't be read ([NextBoard.partial]), so none found may be wrong. A line
    // unchecked with no train listed couldn't add one (Codex, PR #460).
    private class Pick(val picked: Triple<Departure, TripLeg, List<VehicleCall>>?, val unchecked: Boolean = false)

    // The soonest train the rider can catch on the trip's leg that runs where they're going, with the
    // ride as its line runs it and its calls; none when none of the first few does (or none is due).
    private suspend fun pick(trip: ActiveTrip, now: Instant, board: Result<NextBoard>?): Pick {
        val leg = trip.leg ?: return Pick(null)
        // On board by the rider's word ([OnTheWay.atStep]): the train they're on is one at the platform
        // when they said so, maybe due a moment before ([OnTheWay.boardableFrom]).
        val readyAt = OnTheWay.boardableFrom(trip).let { if (trip.boarded) it else maxOf(it, now) }
        // This refresh's read of the boards ([fetchBoard]); its failure thrown for [step] to report.
        val read = board?.getOrThrow()?.takeIf { it.ride == leg } ?: boardOf(leg, fetchedAt = null)
        val boards = read.boards
        // On board by their word, only a train at the platform when they said so can be theirs: one due
        // minutes later isn't, so none is followed rather than that one (the step says it can't find it).
        // Of the ride's lines the trip's cards offer ([rideLines]): the Planner's, and another that runs
        // between the same two stops (from the other pole of a bus stop pair, too), checked as running
        // from stops checked open and not avoided. Each train is then judged against the ride as its own
        // line runs it, on the board of the pole that line boards at ([OnTheWay.listedFor]).
        val ride = rideLines(trip.route, leg, boards)
        val lines = ride.lines
        val catchable = OnTheWay.candidates(OnTheWay.listedFor(leg, boards, lines), lines, readyAt)
            .filter { !trip.boarded || !it.expectedArrival.isAfter(trip.legStartedAt.plus(OnTheWay.ON_BOARD_GRACE)) }
        // Only those their own line's route doesn't send another way are asked after, a request each:
        // at a fork the first few can all turn off ([OnTheWay.mayTakeRide]). Without the route, their
        // own calls decide, as before. Each of the ride's lines here costs its route, kept a day and
        // shared with the board's.
        val candidates = if (catchable.isEmpty()) catchable else {
            val routes = catchable.map { it.lineId }.distinct().associateWith { routeOf(it) }
            catchable.filter { train -> OnTheWay.lineOf(lines, train)?.let { OnTheWay.mayTakeRide(it, listOf(train), routes).isNotEmpty() } == true }
        }.take(PICK_TRIES)
        for (train in candidates) {
            val on = OnTheWay.lineOf(lines, train) ?: continue
            val calls = try {
                vehicles.vehicleCalls(train.vehicleId, train.lineId)
            } catch (e: TflException.NotFound) {
                // Still on the board, but TfL no longer knows the train: try the next.
                warn("on the way: train not found on line ${train.lineId}")
                continue
            }
            // On board by their word, it may have just left the stop, its call there gone from TfL's
            // list: then it's judged from where it is ([OnTheWay.runsOn]).
            val runs = OnTheWay.runsAlong(on, calls, heading = train.destination) ||
                (trip.boarded && OnTheWay.runsOn(on, calls, heading = train.destination))
            if (!runs) continue
            // Its own calls may have it leave before the rider can be there, however the board
            // shows it: then it's no train of theirs, and the next is tried now, not next refresh.
            if (OnTheWay.advance(OnTheWay.follow(trip, train, on), calls, now).second is TripProgress.Lost) continue
            return Pick(Triple(train, on, calls))
        }
        return Pick(null, ride.uncheckedOn(boards.values.flatten()) || read.partial)
    }

    // The trains of [line] the board at the stop ahead of a rider seen at [where] on it lists, arriving
    // there: the train they're on among them, for [boardedAlong] to tell by its calls (TODO, *A train the
    // board never listed*). One request a fix, only while their train is unknown, and only for a line
    // whose stops are known by id ([OnTheWay.checkable]): a bus's are stop areas, no stop to read. Nor
    // where the line's routes ([sequence]) bring trains to that stop other than through the boarding
    // stop ([OnTheWay.comesThroughBoarding]): starting there, or joining from another branch, one there
    // can't be theirs, and its calls wouldn't say so. Empty when there's nothing to read; null when the
    // read failed, which is said.
    private suspend fun aheadOf(line: TripLeg, where: OnTheWay.Along, sequence: LineSequence?): List<Departure>? {
        if (!OnTheWay.checkable(line) || !OnTheWay.comesThroughBoarding(line, OnTheWay.ahead(where), sequence)) return emptyList()
        val stop = line.path.getOrNull(OnTheWay.ahead(where)) ?: line.toId
        return try {
            arrivals(stop).filter { it.lineId == line.lineId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: board ahead lookup failed for line ${line.lineId}: ${e::class.simpleName}")
            null
        }
    }

    // The lines [ride]'s boarding stop's boards list now ([boardOf], its pair's other poles' too), for
    // [rideLines], once the rider is on board by where they were seen: the board isn't read on board,
    // and without it only the Planner's line would be offered, so a rider seen along another would be
    // left on the line last kept (Codex, PR #459). Read afresh each time, never what an earlier read
    // listed (kept in memory, or not at all after a restart): a board lists only the lines with a train
    // predicted then, so an earlier one can leave out a line that has one now (Codex, PR #459). A
    // request a fix (and one a pole that may matter), only for one not along the line they're counted
    // on: no more often than the board is read while waiting. Null when the read fails: the Planner's
    // line alone is offered, and the refresh is said to have failed.
    private suspend fun linesListed(ride: TripLeg): NextBoard? =
        try {
            boardOf(ride, fetchedAt = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: ride lines board lookup failed for line ${ride.lineId}: ${e::class.simpleName}")
            null
        }

    // The upcoming ride's board ([nextBoard]), fetched at [now], or null with none upcoming. Asked
    // for once a refresh: a second step reuses [boards]' attempt, answer or failure, so a slow or
    // failing TfL never brings a second request. A fetch that fails keeps the last board, marked
    // failed ([NextBoard.failed]), and hands back the failure for the ride's pick to report.
    private suspend fun fetchBoard(trip: ActiveTrip, now: Instant, boards: MutableMap<TripLeg, Result<NextBoard>>): Result<NextBoard>? {
        val ride = OnTheWay.upcomingRide(trip)
        if (ride == null) {
            _nextBoard.value = null
            refollow()
            return null
        }
        return boards.getOrPut(ride) { readBoard(ride, now) }
    }

    // [from]'s board this refresh, taken as [to]'s, a ride from the same stop ([OffPlan.take]'s first part).
    private fun rekeyBoard(from: TripLeg, to: TripLeg, boards: MutableMap<TripLeg, Result<NextBoard>>) {
        if (from.fromId != to.fromId) return
        val board = boards[from]?.getOrNull() ?: return
        val moved = board.copy(ride = to)
        boards[to] = Result.success(moved)
        if (_nextBoard.value == board) {
            _nextBoard.value = moved
            refollow()
        }
        if (boardSeenRide == from) boardSeenRide = to
    }

    private suspend fun readBoard(ride: TripLeg, now: Instant): Result<NextBoard> =
        try {
            // Stamped by the steady clock, as every fetch is ([SteadyClock]).
            Result.success(boardOf(ride, SteadyClock.stamp(now)).also { board ->
                _nextBoard.value = board
                refollow()
                logDoubled(board, now)
                if (boardSeenRide != ride) {
                    boardSeenRide = ride
                    boardSeen.clear()
                }
                board.boards.forEach { (stop, trains) ->
                    val seen = boardSeen.getOrPut(stop) { LinkedHashMap() }
                    trains.filter { it.vehicleId.isNotBlank() }.forEach { seen[seenKey(it)] = it }
                }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("on the way: next board lookup failed for line ${ride.lineId}: ${e::class.simpleName}")
            // Said on the screen, not left to vanish or pass as current: the last board of this ride
            // kept, marked failed; another ride's board is no board of this one's.
            val last = _nextBoard.value?.takeIf { it.ride == ride }
            _nextBoard.value = last?.copy(failed = true) ?: NextBoard(ride, emptyList(), fetchedAt = null, failed = true)
            refollow()
            Result.failure(e)
        }

    // The suspects last logged of the next board ([logDoubled]), so the same ones read again aren't.
    private var boardDoubled: Set<String> = emptySet()

    // A train the next board lists twice, or two at one platform under a minute apart ([DoubledTrains]):
    // logged as TfL sent them, so a card that then shows more trains than the platform has can be traced
    // to TfL's answer or to the app. Logged once for the same suspects.
    private suspend fun logDoubled(board: NextBoard, now: Instant) {
        val found = withContext(compute) {
            board.boards.mapNotNull { (stop, trains) ->
                DoubledTrains.find(trains.filter { it.mode.equals(board.ride.mode, ignoreCase = true) }, now)?.let { stop to it }
            }
        }
        val key = found.flatMapTo(HashSet()) { (stop, it) -> it.key.map { suspect -> "$stop/$suspect" } }
        if (key != boardDoubled) found.forEach { (stop, it) -> warn("on the way: board at $stop lists ${it.text}") }
        boardDoubled = key
    }

    // Where [trip] stood when last kept, before any answer: on board with its stop, waiting for its
    // train when it was due, or walking. Not Lost, which only an answer can say, but for on board with
    // no train named: the rider said they were on and none at the platform was found then, so it
    // can't find their train, not that they're riding (Codex, PR #384). Unless [picking], where a
    // pick follows at once ([goTo]) to say which.
    // Which step an answer is for: its leg, whether the rider's on board, and the train followed.
    private fun answeredStep(trip: ActiveTrip): List<Any?> = listOf(trip.legIndex, trip.boarded, trip.vehicleId)

    private fun standing(trip: ActiveTrip, now: Instant, picking: Boolean = false): TripProgress {
        val leg = trip.leg ?: return TripProgress.Arrived
        return when {
            leg.isWalk -> OnTheWay.advance(trip, null, now).second
            // On board by where they were seen: riding, counted from there, not a train still to find
            // (Codex, PR #449).
            OnTheWay.ridingUnmatched(trip) -> OnTheWay.advance(trip, null, now).second
            trip.boarded && trip.vehicleId.isBlank() && !picking -> TripProgress.Lost(leg)
            trip.boarded -> TripProgress.Riding(leg, "", null, trip.dueOffAt, false, trip.onBoardSeen)
            OnTheWay.changeUntil(trip, now) != null -> TripProgress.Changing(leg, trip.legStartedAt)
            else -> TripProgress.Waiting(leg, trip.boardsAt, OnTheWay.followedLineName(trip))
        }
    }

    private suspend fun keep(stepped: ActiveTrip, progress: TripProgress) {
        val trip = withAheadKept(stepped, progress)
        if (trip != _trip.value) unsaved = true
        _trip.value = trip
        refollow()
        _progress.value = withAhead(trip, progress)
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
        settleBoard()
    }

    // A rider on board whose train the trip can no longer place ([TripProgress.Lost]) keeps the next stop
    // last known ahead of them on the ride ([TripProgress.Lost.ahead]), from the step before or where they
    // were seen, so a fork already behind them isn't offered or taken (Codex, #586).
    private fun withAhead(trip: ActiveTrip, progress: TripProgress): TripProgress {
        if (progress !is TripProgress.Lost || !(trip.boarded || trip.onBoardSeen)) return progress
        val ahead = listOfNotNull(progress.ahead, aheadOn(_progress.value, progress.leg), keptAhead(trip)).maxOrNull() ?: return progress
        return progress.copy(ahead = ahead)
    }

    // The next stop known ahead on the leg [trip]'s rider is on board, as kept with it ([ActiveTrip.aheadStop])
    // or where they were seen; null with none.
    private fun keptAhead(trip: ActiveTrip): Int? =
        listOfNotNull(trip.aheadStop.takeIf { trip.aheadLeg == trip.legIndex && it >= 0 }, trip.seenAlongStop.takeIf { it >= 0 }).maxOrNull()

    // [trip] keeping the next stop [progress] knows ahead of its rider on board ([ActiveTrip.aheadStop]), so
    // a restart still knows which forks are behind them (Codex, #586). A train doesn't go back: the furthest.
    private fun withAheadKept(trip: ActiveTrip, progress: TripProgress): ActiveTrip {
        // Waiting again (left behind by a train taken to be theirs, or back by their word): what that train
        // reached isn't theirs, so a later train's forks aren't judged by it (Codex, #586).
        if (!trip.boarded && !trip.onBoardSeen) return if (trip.aheadLeg < 0) trip else trip.copy(aheadLeg = -1, aheadStop = -1)
        val leg = trip.leg ?: return trip
        val known = aheadOn(progress, leg) ?: return trip
        val ahead = maxOf(known, trip.aheadStop.takeIf { trip.aheadLeg == trip.legIndex } ?: -1)
        return if (trip.aheadLeg == trip.legIndex && trip.aheadStop == ahead) trip else trip.copy(aheadLeg = trip.legIndex, aheadStop = ahead)
    }

    // The index into [leg]'s path of the next stop ahead of a rider on board it, as [progress] knows it.
    private fun aheadOn(progress: TripProgress?, leg: TripLeg): Int? = when (progress) {
        is TripProgress.Riding -> progress.stopsLeft?.takeIf { sameRide(progress.leg, leg) }?.let { leg.path.size - it }
        is TripProgress.Lost -> progress.ahead?.takeIf { sameRide(progress.leg, leg) }
        else -> null
    }

    // A "time to board" that no longer stands ([OnTheWay.boardStands]) is taken down: the rider
    // boarded, was left behind (the next train has its own), moved on, or the trip ended. It's kept
    // said on the trip, so it isn't said again for the same train.
    private fun settleBoard() {
        if (!boardUp) return
        val trip = _trip.value
        if (trip != null && OnTheWay.boardStands(trip, _progress.value)) return
        onBoardSoonDone()
        boardUp = false
    }

    /** How [onDisruption] posts "route disruption". */
    enum class DisruptionPost {
        /** Something not heard before on this trip is known: heard. */
        NEW,

        /** Kept up to date while it's up, silently; not brought back once gone (swiped, timed out). */
        KEEP,
    }

    /** How [onBoardSoon] posts "time to board". */
    enum class BoardPost {
        /** Said for the first time for its train: heard. */
        NEW,

        /** Kept up to date while it's up, silently; not brought back once gone (swiped, timed out). */
        KEEP,
    }

    companion object {
        /**
         * How old the last answer can be and still be shown as live: a refresh or two missed. Back
         * after the app was away longer, a trip's live details wait for the next answer.
         */
        val CURRENT_FOR: Duration = Duration.ofSeconds(75)

        /**
         * How old a fix may be to estimate a walk's distance left before one on the walk places the
         * rider: about a block's walk at most, so the estimate is never far off (maintainer, 2026-10-04).
         */
        val ESTIMATE_WITHIN: Duration = Duration.ofMinutes(2)

        /** Whether a trip last brought up to date at [updatedAt] is live at [now]. */
        fun isCurrent(updatedAt: Instant?, now: Instant): Boolean =
            updatedAt != null && !now.isAfter(updatedAt.plus(CURRENT_FOR))

        // How many of a leg's soonest trains are looked up to find one running where the rider is going.
        const val PICK_TRIES = 3

        // Modes that board at a lettered pole in the street ([pairOf]); a station's board splits by platform.
        private val POLE_MODES = setOf("bus", "replacement-bus", "coach", "tram")
    }
}
