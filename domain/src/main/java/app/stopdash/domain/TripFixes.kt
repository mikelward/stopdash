package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

    fun offer(fix: LocationFix) {
        _latest.value = Seen(fix, elapsed(), count.incrementAndGet())
    }

    /** Drops the fix held, if any: used, passed over, or no longer watched for. */
    fun clear() {
        _latest.value = null
    }

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
 * during the gap is passed over for the next: by then it's that much older, and while the rider
 * moves another follows within seconds.
 */
suspend fun awaitRefresh(
    fixes: StateFlow<TripFixes.Seen?>,
    every: Duration,
    minGap: Duration = ON_THE_WAY_MIN_GAP,
): TripFixes.Seen? = withTimeoutOrNull(every.toMillis()) {
    delay(minGap.toMillis())
    val since = fixes.value?.seq ?: 0
    fixes.first { it != null && it.seq > since }
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
                while (true) {
                    updates().collect { fix ->
                        // Judged for what the trip wants now: a fix too vague for it would only wake a
                        // refresh that can't use it.
                        val current = trip.value ?: return@collect
                        OnTheWay.usableFix(fix, current, now())?.let(fixes::offer)
                    }
                    // Ended with location still wanted: precise location, or the provider, isn't
                    // there now. Asked again, so one allowed or switched on mid-trip is used (Codex, #458).
                    delay(WANTS_FIX_RECHECK.toMillis())
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
    take: suspend (ActiveTrip?) -> LocationFix?,
): LocationFix? {
    val pushed = seen?.let(fixes::aged)
    // The fix held is used by this refresh or passed over for the one it takes, and dropped either
    // way: a position isn't kept past the refresh it was for (Codex, #458).
    fixes.clear()
    if (pushed != null && trip != null && OnTheWay.wantsFix(trip, now)) {
        OnTheWay.usableFix(pushed, trip, now)?.let { return it }
    }
    return if (mayTake) take(trip) else null
}
