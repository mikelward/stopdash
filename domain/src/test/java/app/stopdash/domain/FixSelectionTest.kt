package app.stopdash.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fix-selection policy ([FixSelection.resolve]) that fixes the field bug where "Finding
 * stops near you…" waited ~5 s and then failed with "couldn't get your location": a recent
 * cached fix is used at once, a fresh fix is bounded, and a slow/absent fresh fix falls back
 * to a cached one — but only while it is recent enough, so an old fix isn't shown as "near me
 * now". The framework glue (`getCurrentLocation` / `getLastKnownLocation`) is injected as
 * fakes, so the policy is exercised on the JVM with virtual time — no device or Robolectric.
 * Coordinates are obviously-synthetic fixtures, never a real position (SPEC *Privacy*).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FixSelectionTest {
    private val cached = Coordinates(1.0, 2.0)
    private val fresh = Coordinates(3.0, 4.0)
    private val freshEnough = 120_000L
    private val maxFallback = 1_800_000L // 30 min
    private val timeout = 10_000L

    @Test
    fun `a recent cached fix is used at once, without requesting a fresh one`() = runTest {
        var freshRequested = false
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 30_000L,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { freshRequested = true; fresh }
        assertEquals(cached, result)
        assertEquals("a recent cached fix skips the fresh request", false, freshRequested)
    }

    @Test
    fun `forceFresh requests a new fix even with a recent cached one`() = runTest {
        // A re-locate on refresh passes forceFresh so a recent cached fix can't short-circuit —
        // otherwise walking to a new stop and refreshing would re-resolve for the previous
        // position. The cached fix stays only a bounded fallback.
        var freshRequested = false
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 30_000L, // recent — would have taken the fast path normally
            forceFresh = true,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { freshRequested = true; fresh }
        assertEquals(fresh, result)
        assertTrue("force-fresh requests a new fix despite a recent cache", freshRequested)
    }

    @Test
    fun `forceFresh bypasses the cache even for a zero-age fix`() = runTest {
        // The boundary the old `freshEnoughMillis = 0` sentinel missed: an age that truncates to
        // 0ms satisfied `age <= 0` and took the fast path, so forceFresh must be represented
        // explicitly rather than as a threshold. A brand-new cached fix must still not short-
        // circuit a forced re-locate.
        var freshRequested = false
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 0L,
            forceFresh = true,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { freshRequested = true; fresh }
        assertEquals(fresh, result)
        assertTrue("force-fresh requests a new fix even for a zero-age cache", freshRequested)
    }

    @Test
    fun `forceFresh still falls back to a recent cached fix when the fresh fix fails`() = runTest {
        // Bypassing the fast path doesn't discard the cache: if the forced fresh fix returns
        // nothing, a within-cap cached fix is still a valid fallback rather than a hard failure.
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 30_000L,
            forceFresh = true,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { null }
        assertEquals(cached, result)
    }

    @Test
    fun `a stale cached fix waits for a fresh fix and uses it`() = runTest {
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 300_000L,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { fresh }
        assertEquals(fresh, result)
    }

    @Test
    fun `a fresh fix that returns null falls back to a cached fix within the age cap`() = runTest {
        val logs = mutableListOf<String>()
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 300_000L, // 5 min, within the 30 min fallback cap
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = { logs += it },
        ) { null }
        assertEquals(cached, result)
        assertTrue("the fallback to a cached fix is logged", logs.any { it.contains("last known") })
        // A prompt no-fix is reported as such, not as a timeout it wasn't.
        assertTrue("a prompt no-fix reads as 'returned no fix'", logs.any { it.contains("returned no fix") })
        assertTrue("a prompt no-fix does not claim a timeout", logs.none { it.contains("timed out") })
    }

    @Test
    fun `a fresh fix slower than the bound falls back to a cached fix within the age cap`() = runTest {
        val logs = mutableListOf<String>()
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 300_000L,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = { logs += it },
        ) { delay(timeout + 5_000L); fresh }
        assertEquals(cached, result)
        assertTrue(logs.any { it.contains("last known") })
        // A genuine timeout is reported as a timeout, distinct from a prompt no-fix.
        assertTrue("a slow fix reads as 'timed out'", logs.any { it.contains("timed out") })
    }

    @Test
    fun `a cached fix older than the fallback cap is rejected rather than shown as current`() = runTest {
        val logs = mutableListOf<String>()
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = maxFallback + 60_000L, // beyond the cap
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = { logs += it },
        ) { null }
        assertNull("an old cached fix is not used as the current location", result)
        assertTrue("the too-old rejection is logged", logs.any { it.contains("too old") })
    }

    @Test
    fun `no cached fix and no fresh fix returns null and logs the failure`() = runTest {
        val logs = mutableListOf<String>()
        val result = FixSelection.resolve(
            lastKnown = null,
            lastKnownAgeMillis = null,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = { logs += it },
        ) { null }
        assertNull(result)
        // No cached fix and the fresh request resolved with none: the failure names both,
        // distinct from a timeout.
        assertTrue("the total failure is logged", logs.any { it.contains("no cached fix to fall back on") })
        assertTrue("a prompt no-fix reads as such", logs.any { it.contains("returned no fix") })
    }

    @Test
    fun `no cached fix uses a fresh one when available`() = runTest {
        val result = FixSelection.resolve(
            lastKnown = null,
            lastKnownAgeMillis = null,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { fresh }
        assertEquals(fresh, result)
    }

    @Test
    fun `a cached fix just inside the cap is rejected once the wait pushes it over`() = runTest {
        // Cached fix is 5s inside the 30-min cap when the wait starts; the fresh request then
        // consumes the full 10s timeout, so by the fallback decision the fix is 5s over the cap.
        val logs = mutableListOf<String>()
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = maxFallback - 5_000L,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = { logs += it },
            elapsedMillis = { testScheduler.currentTime },
        ) { delay(timeout + 1_000L); fresh } // never resolves before the timeout
        assertNull("a fix aged past the cap by the wait is not used as current", result)
        assertTrue("the too-old rejection is logged", logs.any { it.contains("too old") })
    }

    @Test
    fun `a cached fix inside the cap is still used when the wait is short`() = runTest {
        // Same fix, but the fresh request returns null immediately — the wait ages it by ~0,
        // so it stays within the cap and is used (the recheck must not over-reject).
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = maxFallback - 5_000L,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
            elapsedMillis = { testScheduler.currentTime },
        ) { null }
        assertEquals(cached, result)
    }

    @Test
    fun `the default bounds resolve red without an explicit argument`() = runTest {
        // The provider calls resolve() with only lastKnown/warn/freshFix, relying on the
        // FRESH_ENOUGH/FALLBACK/TIMEOUT defaults — pin that a recent cached fix is taken
        // and a stale one is rejected under the defaults, so a silent default drift is caught.
        val recent = FixSelection.resolve(cached, FixSelection.FRESH_ENOUGH_MILLIS - 1, warn = {}) { fresh }
        assertEquals(cached, recent)
        val tooOld = FixSelection.resolve(cached, FixSelection.MAX_FALLBACK_AGE_MILLIS + 1, warn = {}) { null }
        assertNull(tooOld)
    }

    @Test
    fun `preferAccurate declines the fast path for a recent coarse cached fix and requests a fresh one`() = runTest {
        // Precise granted, and the recent cached fix is coarse (network) — it must not preempt
        // the fresh precise attempt, or precise access is defeated by a slightly newer coarse fix.
        var freshRequested = false
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 30_000L, // recent — would fast-path if it were accurate
            lastKnownIsAccurate = false,
            preferAccurate = true,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { freshRequested = true; fresh }
        assertEquals(fresh, result)
        assertEquals("a recent coarse fix does not skip the fresh precise request", true, freshRequested)
    }

    @Test
    fun `preferAccurate still fast-paths a recent accurate cached fix`() = runTest {
        var freshRequested = false
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 30_000L,
            lastKnownIsAccurate = true,
            preferAccurate = true,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { freshRequested = true; fresh }
        assertEquals(cached, result)
        assertEquals("a recent accurate fix is used at once", false, freshRequested)
    }

    @Test
    fun `preferAccurate falls back to the recent coarse cached fix when the fresh fix fails`() = runTest {
        // Declined the fast path (coarse under precise), the fresh fix then returns nothing —
        // the coarse cached fix is still a valid fallback within the cap rather than a failure.
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 30_000L,
            lastKnownIsAccurate = false,
            preferAccurate = true,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = {},
        ) { null }
        assertEquals(cached, result)
    }

    @Test
    fun `a revoked permission is not backfilled with a cached fix`() = runTest {
        // Permission revoked mid-flow: the fresh fix returns null (its SecurityException was
        // swallowed), and the cached fix is within the cap — but access is gone, so no
        // location may be returned or sent off the device.
        val logs = mutableListOf<String>()
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = 300_000L,
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = { logs += it },
            hasPermission = { false },
        ) { null }
        assertNull("a cached fix isn't sent after the user revoked location access", result)
        assertTrue("the revoke is logged", logs.any { it.contains("revoked") })
    }

    @Test
    fun `a revoked permission blocks even a recent cached fix, without a fresh request`() = runTest {
        val logs = mutableListOf<String>()
        val result = FixSelection.resolve(
            lastKnown = cached,
            lastKnownAgeMillis = freshEnough - 1, // recent enough for the fast path
            freshEnoughMillis = freshEnough,
            maxFallbackAgeMillis = maxFallback,
            timeoutMillis = timeout,
            warn = { logs += it },
            hasPermission = { false },
        ) { error("fresh fix must not be requested when permission is already gone") }
        assertNull(result)
        assertTrue(logs.any { it.contains("revoked") })
    }

    @Test
    fun `onFallbackUsed fires only when the stale last-known fix is the result`() = runTest {
        // The callback the caller uses to mark a fix low-confidence. It must fire when a fresh fix
        // fails and a bounded cached fix is returned — but NOT for a fresh fix, the recent-cache
        // fast path, or a null result — or the "location approximate / didn't update" signal would
        // misfire on a good fix (SPEC *Finding stops*).
        suspend fun fallbackFlag(
            lastKnownAgeMillis: Long?,
            forceFresh: Boolean = false,
            freshFix: suspend () -> Coordinates?,
        ): Boolean {
            var used = false
            FixSelection.resolve(
                lastKnown = if (lastKnownAgeMillis == null) null else cached,
                lastKnownAgeMillis = lastKnownAgeMillis,
                forceFresh = forceFresh,
                freshEnoughMillis = freshEnough,
                maxFallbackAgeMillis = maxFallback,
                timeoutMillis = timeout,
                warn = {},
                onFallbackUsed = { used = true },
                freshFix = freshFix,
            )
            return used
        }

        // Fresh fix succeeds → not a fallback.
        assertEquals(false, fallbackFlag(lastKnownAgeMillis = 300_000L, forceFresh = true) { fresh })
        // Recent cache fast path → not a fallback.
        assertEquals(false, fallbackFlag(lastKnownAgeMillis = 30_000L) { error("not requested") })
        // No cached fix and no fresh fix → null result, not a fallback.
        assertEquals(false, fallbackFlag(lastKnownAgeMillis = null) { null })
        // Fresh fix fails but a bounded cached fix is used → fallback.
        assertEquals(true, fallbackFlag(lastKnownAgeMillis = 300_000L, forceFresh = true) { null })
    }

    @Test
    fun `a fix handed back as current counts as one only while it's at most half a minute old`() {
        assertTrue(FixSelection.isCurrent(0))
        assertTrue(FixSelection.isCurrent(FixSelection.CURRENT_FIX_MAX_AGE_MILLIS))
        assertFalse(FixSelection.isCurrent(FixSelection.CURRENT_FIX_MAX_AGE_MILLIS + 1))
        // The bug report's: a passive fix five minutes old.
        assertFalse(FixSelection.isCurrent(309_000))
    }
}
