package app.stopdash.domain

import kotlinx.coroutines.withTimeoutOrNull

/**
 * The location fix-selection policy: pure, JVM-testable off a device, and framework-free.
 * The Android layer (`AndroidLocationProvider`) reads the last-known fix and supplies a
 * suspending [FixSelection.resolve] `freshFix` that wraps `LocationManager`; the decision
 * of *which* fix to trust — and when to fail honestly rather than show a stale one — lives
 * here in `app.stopdash.domain` so it can be exercised without Android (SPEC *Finding stops*).
 *
 * The policy exists to fix the field bug where "Finding stops near you…" waited ~5 s and
 * then failed with "couldn't get your location":
 *
 * 1. a **recent** cached fix (age ≤ [freshEnoughMillis]) is returned at once — instant, and
 *    accurate enough for nearby stops (SPEC *don't make the user wait*);
 * 2. otherwise a fresh fix is awaited under [timeoutMillis];
 * 3. if the fresh fix is slow (timeout) or absent, a cached fix is used rather than
 *    failing — but only while it is within [maxFallbackAgeMillis]. A cached fix older than
 *    that is rejected: the user may have traveled since, and showing a previous location's
 *    stops as "near me now" is worse than an honest failure they can retry;
 * 4. with no usable cached fix and no fresh one it returns `null` — the honest
 *    "couldn't get your location".
 *
 * Steps 3 and 4 log a sanitized reason (never a coordinate), so a location failure is
 * diagnosable from the debug log (SPEC principle 2 / *Privacy*).
 */
object FixSelection {
    /**
     * A cached fix newer than this is used at once, without waiting for a fresh one — for
     * "stops near me" an approximate position from the last couple of minutes is as useful
     * as a new fix and far faster (SPEC *don't make the user wait*). Reversible — one
     * constant, pinned by `FixSelectionTest`.
     */
    const val FRESH_ENOUGH_MILLIS = 2 * 60_000L

    /**
     * How long to wait for a fresh fix before falling back to a cached one. Bounds the
     * "Finding stops near you…" spinner so it can't hang on a provider that never answers
     * (the field symptom was a ~5 s wait ending in failure); on timeout the newest cached
     * fix is used instead of failing. Reversible — one constant.
     */
    const val FRESH_FIX_TIMEOUT_MILLIS = 10_000L

    /**
     * How long any one provider is waited on (all are asked at once, see [COARSE_GRACE_MILLIS]). A
     * provider that accepts the request but never calls back stops there rather than holding the
     * whole [FRESH_FIX_TIMEOUT_MILLIS] (Codex). Smaller than the overall timeout, which still caps
     * the wait. Reversible — one constant.
     */
    const val FRESH_FIX_PER_PROVIDER_TIMEOUT_MILLIS = 4_000L

    /**
     * How long a coarse (network/passive) fresh fix is held for an accurate (fused/GPS) one to beat
     * it, when all providers are asked at once. Outdoors GPS usually lands within this and wins;
     * indoors it never does, and the coarse fix is used this long after it arrived rather than
     * after every accurate provider has run out its bound (maintainer bug report, 2026-09-23).
     * Reversible — one constant.
     */
    const val COARSE_GRACE_MILLIS = 2_000L

    /**
     * The oldest a fix a provider hands back as its current one may be and still count as one. The
     * platform bounds a current fix to seconds, but the passive provider has handed back one taken
     * five minutes earlier, which beat fused (2.5 s later, same place) and showed "Approximate
     * location" (bug report, 2026-10-14). An older one counts as no answer from that provider, so
     * the others are still waited for; the last-known fallback stays the place for an old fix.
     * Reversible — one constant.
     */
    const val CURRENT_FIX_MAX_AGE_MILLIS = 30_000L

    /**
     * Whether a fix [ageMillis] old, handed back by a provider as its current one, counts as current:
     * at most [CURRENT_FIX_MAX_AGE_MILLIS]. One older counts as no answer from that provider.
     */
    fun isCurrent(ageMillis: Long): Boolean = ageMillis <= CURRENT_FIX_MAX_AGE_MILLIS

    /**
     * How long the follow-up request to GPS/fused waits after the list was shown from a coarse fix
     * ([LocationProvider.precise]). Longer than [FRESH_FIX_PER_PROVIDER_TIMEOUT_MILLIS]: the list is
     * already up, so this wait blocks nothing, and a GPS that missed the grace often lands a few
     * seconds later. It stays short so a GPS that can't see the sky (underground) gives up soon.
     * Reversible — one constant.
     */
    const val PRECISE_FIX_TIMEOUT_MILLIS = 8_000L

    /**
     * The oldest a cached fix may be to serve as a *fallback* when no fresh fix is
     * available. Larger than [FRESH_ENOUGH_MILLIS] (which gates the instant fast path) —
     * a somewhat-old fix beats failing outright — but bounded, because the user may have
     * traveled since: an hours- or days-old fix would show a previous location's stops as
     * "near me now", which is worse than an honest "couldn't get your location" they can
     * retry. Reversible — one constant.
     */
    const val MAX_FALLBACK_AGE_MILLIS = 30 * 60_000L

    /**
     * Resolves the position to search from, following the policy above. The framework calls
     * are injected as [freshFix] and the already-read [lastKnown]/[lastKnownAgeMillis], so
     * this is pure and runs on the JVM with virtual time.
     */
    suspend fun resolve(
        lastKnown: Coordinates?,
        lastKnownAgeMillis: Long?,
        // Whether the cached fix came from an accurate (GPS/fused) provider. Only consulted
        // when [preferAccurate] is on: a recent *coarse* cached fix then does not take the
        // instant fast path (it stays a fallback below), so precise access isn't defeated by a
        // slightly newer network fix (Codex). Default true keeps the fast path unchanged when
        // the caller doesn't care about accuracy.
        lastKnownIsAccurate: Boolean = true,
        // On when precise (fine) location is granted: prefer an accurate fix over a quick coarse
        // one. Gates the fast path above; the fresh-fix side (accurate providers first) is the
        // caller's. Default off preserves the coarse-only behavior.
        preferAccurate: Boolean = false,
        // Bypass the instant fast path entirely, so a fresh fix is always requested (the cached
        // one stays only a bounded fallback if the fresh attempt fails). Used by a manual
        // re-locate: the rider may have just walked, so even a brand-new cached fix is the
        // *previous* position. Represented explicitly rather than as `freshEnoughMillis = 0`,
        // because ages truncate to whole milliseconds and the fast path accepts `age <=
        // freshEnoughMillis`, so a sub-millisecond (age-0) cache would still short-circuit `0`.
        forceFresh: Boolean = false,
        freshEnoughMillis: Long = FRESH_ENOUGH_MILLIS,
        maxFallbackAgeMillis: Long = MAX_FALLBACK_AGE_MILLIS,
        timeoutMillis: Long = FRESH_FIX_TIMEOUT_MILLIS,
        warn: (String) -> Unit = {},
        // Monotonic milliseconds, to age the cached fix across the fresh-fix wait: awaiting a
        // fresh fix can consume the whole timeout, so a cached fix just inside the cap when the
        // wait began can be over it by the time we fall back (Codex). The default measures no
        // elapsed time (the wait doesn't age the fix) — callers on a device pass a real
        // monotonic source (SystemClock::elapsedRealtime); tests advance it with virtual time.
        elapsedMillis: () -> Long = { 0L },
        // Re-checked before any cached fix is returned. Coarse-location permission can be
        // revoked between the caller's initial check and here (or during the fresh-fix wait);
        // `freshFix` catches the resulting SecurityException as a null, indistinguishable from
        // an ordinary no-fix, so without this the cached coordinates would be handed back and
        // sent to TfL after the user revoked access (Codex P1). Default `{ true }` for the
        // pure tests; the provider passes its real permission check.
        hasPermission: () -> Boolean = { true },
        // Invoked when the fresh fix failed and a bounded last-known fix is returned as the
        // fallback (step 3) — never for the instant fast path (a recent cache), a fresh fix, or a
        // null result. The caller uses it to flag the fix low-confidence (don't re-resolve the
        // nearby set to it; label a set shown from it), so the Underground no-signal case isn't
        // silently presented as a current position (SPEC *Finding stops*, principle 2).
        onFallbackUsed: () -> Unit = {},
        freshFix: suspend () -> Coordinates?,
    ): Coordinates? {
        // The instant fast path returns a recent cached fix without waiting — but when the
        // caller wants precision, a recent *coarse* cached fix must not preempt the fresh
        // precise attempt, or precise access is defeated by a slightly newer network fix
        // (Codex). Such a coarse fix stays a fallback below if the fresh fix fails; it just no
        // longer short-circuits it. With preferAccurate off, any recent cached fix short-circuits.
        if (!forceFresh && lastKnown != null && lastKnownAgeMillis != null &&
            lastKnownAgeMillis <= freshEnoughMillis && (lastKnownIsAccurate || !preferAccurate)
        ) {
            if (!hasPermission()) return permissionRevoked(warn)
            return lastKnown
        }
        val waitStart = elapsedMillis()
        // `withTimeoutOrNull` returns null both when the fresh fix TIMED OUT and when it
        // resolved promptly with no fix — two different field symptoms (a slow provider vs.
        // one that answers "no location") the diagnostic exists to tell apart. So record
        // whether the request actually completed: the flag is set only if `freshFix` returns,
        // which a timeout (which cancels the block) never lets happen.
        var freshResolved = false
        val fresh = withTimeoutOrNull(timeoutMillis) { freshFix().also { freshResolved = true } }
        if (fresh != null) return fresh
        // A revoke during the fresh-fix attempt surfaces as a null fix; never fall back to a
        // cached location the user has just withdrawn access to (Codex P1).
        if (!hasPermission()) return permissionRevoked(warn)
        val freshOutcome = if (freshResolved) "returned no fix" else "timed out after ${timeoutMillis}ms"
        // Age the cached fix by however long the wait actually took, so the cap is honored at
        // the moment of the decision, not at the moment the age was first read.
        val fallbackAgeMillis = lastKnownAgeMillis?.plus((elapsedMillis() - waitStart).coerceAtLeast(0))
        if (lastKnown != null && fallbackAgeMillis != null && fallbackAgeMillis <= maxFallbackAgeMillis) {
            warn("location fix: fresh fix $freshOutcome; using the last known one")
            onFallbackUsed()
            return lastKnown
        }
        if (lastKnown != null) {
            warn("location fix failed: fresh fix $freshOutcome, and the last known one is too old to use")
        } else {
            warn("location fix failed: fresh fix $freshOutcome, no cached fix to fall back on")
        }
        return null
    }

    /** No location, because coarse permission was revoked mid-flow — a cached fix must not be
     *  handed back and sent off the device after the user withdrew access (Codex). */
    private fun permissionRevoked(warn: (String) -> Unit): Coordinates? {
        warn("location fix failed: coarse permission revoked; not using a cached fix")
        return null
    }
}
