package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * The last nearby lookup that found no stops, so a background refresh in a place with none (a phone
 * left at home beyond any route) doesn't ask TfL again every cycle: [NearbyStopsCache] keeps only
 * non-empty results, since the app shouldn't hold "no stops" against a rider who opens it. Within
 * [withinMeters] of that lookup and [maxAge] of it, the answer stands. Held in memory only, never
 * stored, so it adds no position to the device's storage (SPEC *Privacy*).
 */
class EmptyNearbyMemo(
    private val clock: () -> Instant = Instant::now,
    private val withinMeters: Double = NearbyStopsCache.REUSE_WITHIN_METERS,
    private val maxAge: Duration = MAX_AGE,
) {
    private var last: Pair<Coordinates, Instant>? = null

    /** Whether a lookup at [at] is known to find nothing. */
    @Synchronized
    fun knownEmpty(at: Coordinates): Boolean {
        forgetExpired()
        val (where, _) = last ?: return false
        return NearestStops.distanceMeters(at.latitude, at.longitude, where.latitude, where.longitude) <= withinMeters
    }

    /**
     * Forgets a lookup older than [maxAge]. The follow calls it before anything else, so an expired
     * position goes at the next widget refresh even when there's no fix (location taken away, say);
     * with no refresh at all it goes with the process. No timer clears it sooner: the app's own nearby
     * cache already keeps lookup positions on disk for a day (docs/PRIVACY.md), so one in memory isn't
     * worth a wakeup (Codex on #711).
     */
    @Synchronized
    fun forgetExpired() {
        val whenAt = last?.second ?: return
        val age = Duration.between(whenAt, clock())
        if (age.isNegative || age > maxAge) last = null
    }

    /** Notes what a lookup at [at] found: an empty result is remembered, any stops forget it. */
    @Synchronized
    fun record(at: Coordinates, stops: List<StopLocation>) {
        last = if (stops.isEmpty()) at to clock() else null
    }

    companion object {
        /** Long enough to spare a stationary phone, short enough that a new route or stop shows up. */
        val MAX_AGE: Duration = Duration.ofMinutes(15)
    }
}
