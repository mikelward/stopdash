package app.stopdash.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FixDiagnostics
import app.stopdash.domain.FixSelection
import app.stopdash.domain.LocationFix
import app.stopdash.domain.LocationProvider
import app.stopdash.domain.PreciseFixMemory
import app.stopdash.domain.raceFix
import java.time.Duration
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The device's position via the framework [LocationManager] — no Play Services dependency,
 * so nothing is added to what the app ships (SPEC *Cost and reliability*). **Precise where
 * granted**: a coarse fix put the nearest stop up to ~1 km off (a stop half a mile away read
 * as nearest), so LDN Go requests `ACCESS_FINE_LOCATION` and, when it is held, reads GPS as
 * well as the fused/network providers; with only `ACCESS_COARSE_LOCATION` granted it degrades
 * to the coarse-safe providers rather than failing (maintainer, 2026-09-19).
 *
 * This class is only the framework glue: it reads the last-known fix and adapts
 * `getCurrentLocation` into a suspending call. *Which* fix to trust — the recent-cached fast
 * path, the fresh-fix bound, the cached fallback and its age cap, the honest `null` — is the
 * pure policy in [FixSelection], tested off a device. Every failure path leaves a sanitized
 * line in the log (SPEC principle 2 / *Privacy* — a fix could not be obtained, never a
 * coordinate); the previous version logged nothing when `getCurrentLocation` simply returned
 * no fix, which is why that failure was invisible in a bug report.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val warn: (String) -> Unit = {},
    // Where each fix placed the rider, for the in-memory RecentPositions only — never [warn],
    // whose lines are persisted (SPEC *Privacy*).
    private val position: (what: String, at: Coordinates) -> Unit = { _, _ -> },
    // Whether a precise fix taken here is remembered for a later coarse lookup to defer to (near me).
    // Off for a fix that must never be kept, such as a trip's left-behind check (SPEC *On the way*).
    private val remembers: Boolean = true,
) : LocationProvider {
    // Whether [preciseUpdates] last found no accurate provider, so a caller asking again doesn't log
    // it each time (main thread only).
    private var updatesSkipped = false

    // Every location taken expires the remembered precise fix on the way out, whichever return
    // it takes and however long the fix took (the clock moves during the request).
    override suspend fun current(forceFresh: Boolean): LocationFix? =
        try {
            takeCurrent(forceFresh)
        } finally {
            expirePreciseMemory()
        }

    private suspend fun takeCurrent(forceFresh: Boolean): LocationFix? {
        expirePreciseMemory()
        if (!hasLocationPermission()) {
            warn("location fix skipped: location permission not held")
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            warn("location fix failed: no location service")
            return null
        }
        val providers = enabledProviders(manager)
        if (providers.isEmpty()) {
            warn("location fix failed: no location provider enabled")
            return null
        }
        val cached = bestLastKnown(manager, providers)
        // Set when resolve returns the bounded last-known fallback (fresh fix failed) rather than a
        // fresh or recent-cache fix, so the caller can treat that fix as low-confidence.
        var fromFallback = false
        // The fresh fix resolve was handed, with what its provider reported — for the diagnostic.
        var fresh: Located? = null
        val coordinates = FixSelection.resolve(
            lastKnown = cached?.coordinates,
            lastKnownAgeMillis = cached?.ageMillis,
            // With precise granted, prefer an accurate fix: a recent *coarse* cached fix no
            // longer takes the instant fast path (it stays a fallback), and the fresh side
            // tries the accurate providers first — so precise access isn't defeated by a
            // slightly newer network fix (Codex). Off under a coarse-only grant.
            lastKnownIsAccurate = cached?.accurate ?: false,
            // A re-locate on refresh forces a fresh fix: `forceFresh` bypasses the instant fast
            // path entirely (regardless of the cached fix's age), so resolve requests a new
            // position and uses the cached one only as the bounded fallback (maxFallbackAgeMillis).
            // Without this, walking to a new stop and refreshing would re-query TfL at the previous
            // coordinates and show the old area's stops (SPEC *Finding stops*). Passed explicitly
            // rather than as freshEnoughMillis = 0, which a sub-millisecond (age-0) cache defeated.
            forceFresh = forceFresh,
            preferAccurate = hasFineLocationPermission(),
            warn = warn,
            // The same monotonic clock the cached age was measured against, so the fallback
            // cap is honored across the fresh-fix wait (see FixSelection.resolve).
            elapsedMillis = SystemClock::elapsedRealtime,
            // Re-checked before any cached fallback: location permission can be revoked between
            // the check above and here, and a revoked fresh fix returns null, so without this
            // a cached location would be sent to TfL after the user withdrew access.
            hasPermission = ::hasLocationPermission,
            onFallbackUsed = { fromFallback = true },
            freshFix = { requestFreshFix(manager, providers)?.also { fresh = it }?.coordinates },
        )
        if (coordinates != null) logFix(fresh, cached, fromFallback)
        // Coarse: the fix used is a fresh one from network/passive while precise location is
        // granted, i.e. GPS/fused missed the grace. The caller shows it as approximate and asks
        // [precise] for a better one (SPEC *Finding stops*). Under a coarse-only grant a network
        // fix is as good as it gets, so it is not flagged.
        val usedFresh = fresh
        val isCoarse = !fromFallback && usedFresh != null && hasFineLocationPermission() &&
            !isAccurateProvider(usedFresh.provider)
        // A precise fix used here — fresh, recent-cached or fallback — is remembered as of when it
        // was taken, so a coarse fix in the next few minutes can defer to it.
        val used = if (!fromFallback && usedFresh != null) usedFresh else cached?.located
        if (remembers && coordinates != null && used != null && isAccurateProvider(used.provider)) {
            remember(used)
            // An old fallback re-remembered at its own (expired) timestamp is dropped again.
            expirePreciseMemory()
        }
        if (remembers && coordinates != null && isCoarse && usedFresh != null) {
            // The rider hasn't left the coarse fix's circle since the last precise fix: use that.
            val considered = preciseMemory.consider(
                usedFresh.coordinates,
                usedFresh.accuracyMeters,
                SystemClock.elapsedRealtime(),
            )
            // Logged used or not, with how far apart the two fixes are: far outside the circle is
            // what a network fix placing the rider at the wrong station looks like.
            if (considered != null) {
                warn(FixDiagnostics.describeRemembered(considered, usedFresh.accuracyMeters))
                // Only when used: a rejected one isn't where the app placed the rider, and it was
                // recorded when it was taken, so re-recording it would only restart its 15 minutes.
                if (considered.used) {
                    position("remembered precise ${considered.recalled.provider} fix", considered.recalled.coordinates)
                }
            }
            val recalled = considered?.takeIf { it.used }?.recalled
            if (recalled != null) {
                // A recall young enough for the instant fast path stands, as that path's fix would.
                // An older one stays flagged coarse: the rider may have moved within the circle, so
                // the list shows from the precise point but GPS is still asked to confirm it or move
                // it (a forced refresh included), rather than the recall standing unchecked.
                return LocationFix(recalled.coordinates, isFallback = false, isCoarse = !recalled.standsAsCurrent, accuracyMeters = recalled.accuracyMeters)
            }
        }
        return coordinates?.let { LocationFix(it, isFallback = fromFallback, isCoarse = isCoarse, accuracyMeters = used?.accuracyMeters) }
    }

    private fun remember(fix: Located) =
        preciseMemory.remember(
            fix.coordinates,
            fix.elapsedRealtimeNanos / 1_000_000,
            provider = fix.provider,
            accuracyMeters = fix.accuracyMeters,
        )

    // Every location taken expires the remembered precise fix first, whatever path it takes after
    // (a fallback, a coarse fix, no fix at all), so docs/PRIVACY.md's "deleted the next time the
    // app takes a location" holds on every path, not only the one that can recall it.
    private fun expirePreciseMemory() = preciseMemory.expire(SystemClock.elapsedRealtime())

    override suspend fun precise(): Coordinates? = preciseFix()?.coordinates

    override suspend fun preciseWithAccuracy(): LocationFix? = preciseFix()

    /**
     * [precise], with how sure the fix is: GPS/fused only, waited for up to the precise timeout,
     * never a quick coarse fix. For a caller that decides on distance (a trip's left-behind check):
     * with [sureEnough], a fix it turns down (vague, or taken a while ago) doesn't end the wait for
     * the other provider.
     */
    suspend fun preciseFix(sureEnough: ((LocationFix) -> Boolean)? = null): LocationFix? =
        try {
            takePrecise(sureEnough)
        } finally {
            expirePreciseMemory()
        }

    private suspend fun takePrecise(sureEnough: ((LocationFix) -> Boolean)? = null): LocationFix? {
        expirePreciseMemory()
        if (!hasFineLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = enabledProviders(manager).filter(::isAccurateProvider)
        if (providers.isEmpty()) {
            warn("precise fix skipped: no GPS or fused provider enabled")
            return null
        }
        val fix = raceFix(
            providers,
            FixSelection.PRECISE_FIX_TIMEOUT_MILLIS,
            coarseGraceMillis = 0,
            isAccurate = { true },
            onTimeout = { provider -> warn("precise fix: $provider provider timed out") },
            accept = { fix -> sureEnough == null || sureEnough(fix.toFix()) },
        ) { provider ->
            requestFreshFixFrom(manager, provider)
        }
        if (fix == null) {
            warn("precise fix: none arrived")
            return null
        }
        // Checked again: permission can be revoked while GPS was being waited on.
        if (!hasFineLocationPermission()) return null
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
        warn(FixDiagnostics.describe(FixDiagnostics.Source.PRECISE, fix.provider, fix.accuracyMeters, ageMillis))
        position("${FixDiagnostics.Source.PRECISE.label} ${fix.provider} fix", fix.coordinates)
        if (remembers) remember(fix)
        return fix.toFix()
    }

    /**
     * Precise fixes as they come, from one accurate provider (fused where there is one, else GPS)
     * about every [every] while the rider moves [minDistanceMeters], until the flow is cancelled,
     * when the request is removed. For a trip on the way while it's shown (SPEC *On the way*), so
     * nothing is remembered or logged per fix (a warning only if it can't be asked, once until it
     * can be again: a caller asks again every few seconds); none at all without precise location or
     * an accurate provider, when the flow ends at once.
     */
    fun preciseUpdates(every: Duration, minDistanceMeters: Float): Flow<LocationFix> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val provider = manager?.takeIf { hasFineLocationPermission() }?.let { m ->
            enabledProviders(m).filter(::isAccurateProvider).let { if (LocationManager.FUSED_PROVIDER in it) LocationManager.FUSED_PROVIDER else it.firstOrNull() }
        }
        if (manager == null || provider == null) {
            if (manager != null && hasFineLocationPermission() && !updatesSkipped) {
                warn("location updates skipped: no GPS or fused provider enabled")
                updatesSkipped = true
            }
            close()
            return@callbackFlow
        }
        updatesSkipped = false
        val listener = LocationListener { location -> trySend(location.toLocated(provider).toFix()) }
        val request = LocationRequest.Builder(every.toMillis())
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .build()
        try {
            manager.requestLocationUpdates(provider, request, context.mainExecutor, listener)
        } catch (e: SecurityException) {
            // A revoke can race the permission check above: no updates, not a crash.
            warn("location updates failed: permission")
            close()
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            // The provider went away between the check and the request.
            warn("location updates failed: ${e::class.simpleName}")
            close()
            return@callbackFlow
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    // The fix as handed over, with how sure it is and how long ago it was taken.
    private fun Located.toFix() = LocationFix(
        coordinates,
        isFallback = false,
        accuracyMeters = accuracyMeters,
        ageMillis = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000,
    )

    /**
     * A fresh fix from [providers], all asked at once ([raceFix]): an accurate (fused/GPS) fix wins
     * as soon as it arrives, and a coarse (network/passive) one is used after a short grace when no
     * accurate fix beats it — so indoors, where fused and GPS can't see the sky, the network fix is
     * used in about a second instead of after both have run out their bounds. Each provider keeps its
     * own [FixSelection.FRESH_FIX_PER_PROVIDER_TIMEOUT_MILLIS] bound, and the overall [FixSelection]
     * timeout still caps the whole wait.
     */
    private suspend fun requestFreshFix(manager: LocationManager, providers: List<String>): Located? =
        raceFix(
            providers,
            FixSelection.FRESH_FIX_PER_PROVIDER_TIMEOUT_MILLIS,
            FixSelection.COARSE_GRACE_MILLIS,
            // Under a precise grant only fused/GPS count as accurate; under an approximate-only
            // grant every fix is as good as it gets, so the first to arrive wins.
            isAccurate = { provider ->
                !hasFineLocationPermission() ||
                    provider == LocationManager.FUSED_PROVIDER || provider == LocationManager.GPS_PROVIDER
            },
            // Log a provider that hit its per-provider bound, so a hanging provider (fused, most
            // often) still leaves a diagnostic line even when a later provider's no-fix makes the
            // overall result null and `FixSelection` records "returned no fix" (Codex). The
            // provider name is coarse diagnostics, not user data (SPEC *Privacy*).
            onTimeout = { provider -> warn("fresh fix: $provider provider timed out") },
        ) { provider ->
            requestFreshFixFrom(manager, provider)
        }

    /**
     * One fresh fix from [provider] (or `null`). [LocationManager.getCurrentLocation] delivers a
     * single fix representing the present moment (its contract bounds any historical location to
     * the order of seconds, never minutes) and self-cancels, so there is no long-lived listener
     * to leak; the [CancellationSignal] only covers the caller giving up (a [FixSelection]
     * timeout, or the screen going away). The minutes-scale cache a re-locate must bypass is
     * `getLastKnownLocation`, which [FixSelection]'s `forceFresh` already skips.
     */
    private suspend fun requestFreshFixFrom(manager: LocationManager, provider: String): Located? =
        try {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    cont.resume(location?.toLocated(provider))
                }
            }
        } catch (e: CancellationException) {
            // The caller gave up (timeout / screen gone); not a location failure, and
            // swallowing it into `null` would break structured concurrency.
            throw e
        } catch (e: SecurityException) {
            // A revoke can race the permission check above; treat it as no fix, not a crash.
            warn("location fix failed: permission")
            null
        } catch (e: Exception) {
            warn("location fix failed: ${e::class.simpleName}")
            null
        }

    /** A cached last-known fix: its coordinates, age in ms, and whether it came from an
     *  accurate (GPS/fused) provider — the last gates the precise fast path in [FixSelection]. */
    private data class CachedFix(
        val coordinates: Coordinates,
        val ageMillis: Long,
        val accurate: Boolean,
        // What its provider reported, for the diagnostic line (see [logFix]).
        val located: Located,
    )

    /**
     * A fix as its provider reported it: the position, the provider asked, the accuracy radius when
     * the fix carries one (`null`, not the platform's default 0, when it doesn't), and its
     * elapsed-realtime stamp, from which its age is taken.
     */
    private data class Located(
        val coordinates: Coordinates,
        val provider: String,
        val accuracyMeters: Float?,
        val elapsedRealtimeNanos: Long,
    )

    private fun Location.toLocated(provider: String) = Located(
        coordinates = toCoordinates(),
        provider = provider,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )

    /**
     * Log where the fix just used came from, how accurate its provider says it is, and how old it
     * is — never the coordinate (`docs/PRIVACY.md`). The fresh fix when one came back; otherwise the
     * cached one, as the fallback or the instant recent-cache path.
     */
    private fun logFix(fresh: Located?, cached: CachedFix?, fromFallback: Boolean) {
        val (source, fix) = when {
            fresh != null && !fromFallback -> FixDiagnostics.Source.FRESH to fresh
            cached != null -> (
                if (fromFallback) FixDiagnostics.Source.FALLBACK else FixDiagnostics.Source.RECENT_CACHED
                ) to cached.located
            else -> return
        }
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
        warn(FixDiagnostics.describe(source, fix.provider, fix.accuracyMeters, ageMillis))
        position("${source.label} ${fix.provider} fix", fix.coordinates)
    }

    /**
     * The newest last-known fix across the enabled providers — or `null` if none has one — with
     * its age and whether its provider is accurate. Age is from the monotonic elapsed-realtime
     * clock, so it is correct across a wall-clock change. A per-provider lookup that throws is
     * logged (sanitized) and skipped rather than failing the whole read. Newest-overall (not
     * newest-accurate): the accuracy flag lets [FixSelection] decline the fast path for a coarse
     * fix under precise, but a coarse cached fix is still a valid fallback if the fresh fix fails.
     */
    private fun bestLastKnown(manager: LocationManager, providers: List<String>): CachedFix? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        return providers
            .mapNotNull { provider -> lastKnownOrNull(manager, provider)?.let { provider to it } }
            .map { (provider, loc) ->
                CachedFix(
                    coordinates = loc.toCoordinates(),
                    ageMillis = (nowNanos - loc.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000,
                    accurate = isAccurateProvider(provider),
                    located = loc.toLocated(provider),
                )
            }
            .minByOrNull { it.ageMillis }
    }

    /** GPS and fused give a precise fix; network and passive are coarse. */
    private fun isAccurateProvider(provider: String): Boolean =
        provider == LocationManager.GPS_PROVIDER || provider == LocationManager.FUSED_PROVIDER

    private fun lastKnownOrNull(manager: LocationManager, provider: String): Location? =
        try {
            manager.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            warn("last-known lookup failed: permission")
            null
        } catch (e: Exception) {
            warn("last-known lookup failed: ${e::class.simpleName}")
            null
        }

    /** True when either location permission is held — enough to obtain some fix. */
    private fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True when precise location is granted, which is what allows GPS and a precise fix. */
    private fun hasFineLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The candidate providers currently enabled, in the order [locationProviderCandidates]
     * ranks them — GPS included only when precise permission is held, since coarse permission
     * can't drive it.
     */
    private fun enabledProviders(manager: LocationManager): List<String> =
        locationProviderCandidates(hasFineLocationPermission(), Build.VERSION.SDK_INT)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private fun Location.toCoordinates() = Coordinates(latitude, longitude)

    internal companion object {
        // Process-wide, so a recreated screen (and its new provider) still knows the last precise
        // fix. In memory only, never persisted or logged (SPEC *Privacy*).
        @VisibleForTesting
        internal val preciseMemory = PreciseFixMemory()
    }
}

/**
 * The location providers to try, most-accurate first, for a request with [fineGranted] precise
 * permission on API [sdkInt]. Pure (no `LocationManager`) so the ranking is unit-testable off a
 * device; the caller filters this to the ones actually enabled.
 *
 * - **Fused** leads where the platform exposes it (API 31+): it already returns the most
 *   accurate location the caller is permitted.
 * - **GPS** is included **only when precise permission is held** — coarse permission can't drive
 *   it (a request would throw `SecurityException`), and it's the fallback that gives a precise
 *   fix on a device whose fused provider is weak.
 * - **Network** then **passive** are the coarse-safe providers, always tried.
 */
internal fun locationProviderCandidates(fineGranted: Boolean, sdkInt: Int): List<String> =
    buildList {
        if (sdkInt >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        if (fineGranted) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }
