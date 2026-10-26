package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The fixes seen while a trip on the way is shown (SPEC *On the way*), each one a refresh can act on
 * at once rather than wait for the timer: fed by [watchTripFixes] while the app is in the foreground
 * and the trip wants a fix, read by whichever loop follows the trip (the activity's, or the
 * service's). Only the latest is held, and only until the refresh it's for, or until location stops
 * being watched: a position is used and dropped, never kept (`docs/PRIVACY.md`).
 */
class TripFixes(private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** A [fix] that came at [at] (on [elapsed]'s clock), the [seq]th since this started. */
    data class Seen(val fix: LocationFix, val at: Long, val seq: Long)

    private val count = AtomicLong()
    private val _latest = MutableStateFlow<Seen?>(null)
    val latest: StateFlow<Seen?> = _latest.asStateFlow()

    // Each fix as it comes, to whoever is collecting then: none is replayed, so none is held for a later
    // collector, and a newer one replaces one not yet taken, but a [clear] can't take one away first.
    private val _each = MutableSharedFlow<Seen>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Every fix offered, as it comes, whether or not a refresh has taken it from [latest] since: a
     * walk's distance follows each one ([ActiveTripTracker.onFix] in `:app`).
     */
    val each: SharedFlow<Seen> = _each.asSharedFlow()

    fun offer(fix: LocationFix) {
        val seen = Seen(fix, elapsed(), count.incrementAndGet())
        _latest.value = seen
        _each.tryEmit(seen)
    }

    /** Drops the fix held, if any: used, passed over, or no longer watched for. */
    fun clear() {
        _latest.value = null
    }

    /** How long ago [seen] came, on [elapsed]'s clock. */
    fun waited(seen: Seen): Long = (elapsed() - seen.at).coerceAtLeast(0)

    /** [seen]'s fix, aged by how long ago it came, so a refresh judges how fresh it is now. */
    fun aged(seen: Seen): LocationFix =
        seen.fix.copy(ageMillis = seen.fix.ageMillis?.plus((elapsed() - seen.at).coerceAtLeast(0)))
}

/**
 * The least time between two refreshes a fix brings on: each costs TfL requests, and a rider on the
 * move sends a fix every few seconds. The trip's timer still refreshes with none.
 */
val ON_THE_WAY_MIN_GAP: Duration = Duration.ofSeconds(10)

/**
 * How often location is asked for while a shown trip wants a fix ([watchTripFixes]), and how far the
 * rider must have moved for another: a rider standing still sends none, and the timer serves them.
 */
val ON_THE_WAY_FIX_EVERY: Duration = Duration.ofSeconds(5)
const val ON_THE_WAY_FIX_DISTANCE_METERS = 10f

/** How often [watchTripFixes] asks again whether the trip still wants a fix, which time alone can end. */
private val WANTS_FIX_RECHECK: Duration = Duration.ofSeconds(10)

/**
 * Waits for a trip's next refresh, called as the last one ends: [every] later, or sooner with the
 * first fix [fixes] brings once [minGap] has passed. That fix, or null for the timer. One that came
 * during the gap (or while the last refresh ran, which cleared the one before it, [refreshFix]) wakes
 * the refresh as the gap ends: underground another may not come for minutes, and a fix there is the
 * best word on where the rider is (maintainer, 2026-10-23: one at Camden Town 2 s after a refresh was
 * passed over, and stale by the next). [handled] is the fix the last refresh woke for, if any: never
 * waking another. A new trip on the way in [trip] (one started in place of
 * the trip refreshed, which started at [from]) ends the wait at once, with null: its boards and train
 * are read now, not up to [every] later (maintainer, 2026-10-04). [from] is read before that refresh,
 * so a trip started while it ran wakes this too.
 */
suspend fun awaitRefresh(
    fixes: StateFlow<TripFixes.Seen?>,
    every: Duration,
    minGap: Duration = ON_THE_WAY_MIN_GAP,
    trip: StateFlow<ActiveTrip?>? = null,
    from: Instant? = null,
    handled: TripFixes.Seen? = null,
    // Hunting for a sure fix since the signal came back ([SignalReturns.hunting]): each gap's end wakes a refresh, with
    // null, so it takes a fix of its own (maintainer, 2026-10-26: out of a tunnel, the next fix waited out the timer while
    // the rider rode on, still shown where they boarded; on a marginal signal the first try may fail too).
    signal: SignalReturns? = null,
): TripFixes.Seen? = withTimeoutOrNull(every.toMillis()) {
    val fix = flow {
        delay(minGap.toMillis())
        val since = handled?.seq ?: 0
        emit(fixes.first { it != null && it.seq > since })
    }
    val back = signal?.let { returns ->
        flow<TripFixes.Seen?> {
            delay(minGap.toMillis())
            returns.until.first { returns.hunting() }
            emit(null)
        }
    }
    val replaced = trip?.filter { it != null && it.startedAt != from }?.map<ActiveTrip?, TripFixes.Seen?> { null }
    merge(*listOfNotNull(fix, back, replaced).toTypedArray()).first()
}

/**
 * Whether a trip on the way is hunting for a sure fix since the phone's connection came back ([awaitRefresh]): out of a
 * tunnel a fix and TfL are worth asking at once, and again each [ON_THE_WAY_MIN_GAP] while the signal is marginal,
 * until a sure fix comes ([settled]), the connection goes again, or [HUNT_FOR] passes. Fed by the app's network watch;
 * only whether there's a connection, never which network.
 */
class SignalReturns(private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val _until = MutableStateFlow<Long?>(null)

    /** Until when (on [elapsed]'s clock) the hunt runs, or null with none. */
    val until: StateFlow<Long?> = _until.asStateFlow()

    // Whether the connection is down now: only a return from down starts a hunt, not each network the phone switches to.
    private var lost = false

    /** The connection gone: no network, or none that reaches the internet. No fix is hunted for offline. */
    @Synchronized
    fun lost() {
        lost = true
        _until.value = null
    }

    /** The connection reaching the internet again: a hunt starts if it was lost. */
    @Synchronized
    fun back() {
        if (!lost) return
        lost = false
        _until.value = elapsed() + HUNT_FOR.toMillis()
    }

    /** A sure fix came: the hunt is over. */
    @Synchronized
    fun settled() {
        _until.value = null
    }

    /** Whether the hunt runs now. */
    fun hunting(): Boolean = _until.value?.let { elapsed() < it } == true

    companion object {
        /** How long a hunt runs at most: a dozen tries, each a GPS wait, before the trip's timer takes over again. */
        val HUNT_FOR: Duration = Duration.ofMinutes(2)
    }
}

/**
 * Hands [fixes] each fix from [updates] that's of use to the trip on the way, for as long as it
 * wants one ([OnTheWay.wantsFix], asked again on each change of the trip and every few seconds, as
 * time alone can end it), and nothing otherwise: location is only asked for while a fix could
 * move the trip on. Run while the trip is shown (the app in the foreground), where being current
 * comes first (maintainer, 2026-10-01).
 */
suspend fun watchTripFixes(
    trip: StateFlow<ActiveTrip?>,
    fixes: TripFixes,
    updates: () -> Flow<LocationFix>,
    now: () -> Instant = Instant::now,
) {
    val ticks = flow {
        while (true) {
            emit(Unit)
            delay(WANTS_FIX_RECHECK.toMillis())
        }
    }
    combine(trip, ticks) { current, _ -> current != null && OnTheWay.wantsFix(current, now()) }
        .distinctUntilChanged()
        .collectLatest { wanted ->
            if (!wanted) return@collectLatest
            try {
                // Updates that end while location is still wanted (precise location, or the provider,
                // isn't there now) are asked for again, so one allowed or switched on mid-trip is used.
                askedAgainWhenEnded(WANTS_FIX_RECHECK, updates).collect { fix ->
                    // Judged for what the trip wants now: a fix too vague for it would only wake a
                    // refresh that can't use it. On board the train followed, a vague one still bounds
                    // where they could be ([OnTheWay.placingFix]), as the refresh's own does (Codex, #1204), and awaiting
                    // a train, one may see them gone from where it boards ([OnTheWay.leavingFix]).
                    val current = trip.value ?: return@collect
                    OnTheWay.fixOfUse(fix, current, now())?.let(fixes::offer)
                }
            } finally {
                // No longer watched (no fix wanted, or the app left): the last position goes with it.
                fixes.clear()
            }
        }
}

/**
 * The fix a refresh woken by [seen] acts on: its fix, aged to now, when still of use to [trip]; or,
 * woken by the timer or by one no longer of use, one [take]n as the timer's refresh would, when the
 * caller [mayTake] its own. A fix seen while the trip is shown was taken by the app in the
 * foreground, so it's acted on either way: a service started before precise location was allowed
 * may not take one of its own, yet still uses what the open app sees (Codex, #458).
 */
suspend fun refreshFix(
    seen: TripFixes.Seen?,
    fixes: TripFixes,
    trip: ActiveTrip?,
    now: Instant,
    mayTake: Boolean = true,
    // Told when the fix handed on is sure and fresh ([OnTheWay.usableFix]), ending its hunt for one: an older one, kept
    // only to check the train followed ([OnTheWay.placingFix]), places no one now (Codex, #1235).
    signal: SignalReturns? = null,
    take: suspend (ActiveTrip?) -> LocationFix?,
): LocationFix? = chooseFix(seen, fixes, trip, now, mayTake, take).also { fix ->
    if (fix != null && OnTheWay.usableFix(fix) != null) signal?.settled()
}

private suspend fun chooseFix(
    seen: TripFixes.Seen?,
    fixes: TripFixes,
    trip: ActiveTrip?,
    now: Instant,
    mayTake: Boolean,
    take: suspend (ActiveTrip?) -> LocationFix?,
): LocationFix? {
    val pushed = seen?.let(fixes::aged)
    // The fix held is used by this refresh or passed over for the one it takes, and dropped either
    // way: a position isn't kept past the refresh it was for (Codex, #458).
    fixes.clear()
    if (pushed != null && trip != null && OnTheWay.wantsFix(trip, now)) {
        OnTheWay.usableFix(pushed, trip, now)?.let { return it }
    }
    val taken = if (mayTake) take(trip) else null
    // Too old for that, it may still place a rider on board, against the train followed ([OnTheWay.placingFix]):
    // underground the only one for minutes (maintainer, 2026-10-23). The younger of it and one taken. Aged again
    // after the take, which can wait on GPS for seconds: older by then, it lets fewer trains go (Codex, #1173).
    val placing = seen?.let(fixes::aged)?.let { fix -> trip?.let { OnTheWay.placingFix(fix, it) } }
    // Too vague for either, one seen may still show a rider awaiting a train gone from where it boards
    // ([OnTheWay.leavingFix]), while still fresh enough: only with nothing better (maintainer, 2026-10-26).
    return listOfNotNull(taken, placing).minByOrNull { it.ageMillis ?: 0 }
        ?: seen?.let(fixes::aged)?.let { fix -> trip?.let { OnTheWay.leavingFix(fix, it, now) } }
}
