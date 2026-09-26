package app.stopdash.domain

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A fresh fix from [providers], all asked **at once** rather than one after another. An
 * **accurate** fix ([isAccurate]: fused/GPS under a precise grant) is returned the moment it
 * arrives. A coarse one (network/passive) is held for up to [coarseGraceMillis] in case an
 * accurate fix follows, then returned. Each provider is bounded by [perProviderTimeoutMillis];
 * one that exceeds it is reported via [onTimeout], so a hanging provider stays diagnosable.
 * An accurate provider's fix that [accept] turns down (too vague for the caller) doesn't end the
 * race: the others keep going, and it's returned only when nothing better comes.
 * Returns `null` only when no provider yields a fix. Generic in the fix type [T], so the caller
 * can carry what the provider reported (its accuracy, age) alongside the position.
 *
 * Asking in parallel is the indoor fix (maintainer bug report, 2026-09-23): tried in turn, a
 * fused and a GPS provider that can't see the sky each ran out their bound (~8 s together)
 * before the network provider — which answers underground in about a second — was even asked,
 * so the near-me list started ~10 s late. Outdoors GPS/fused still win, as before. Pure over
 * [fetch] so it's unit-testable off a device with virtual time;
 * `AndroidLocationProvider` supplies the real `LocationManager`-backed fetch and the provider ranking.
 */
suspend fun <T : Any> raceFix(
    providers: List<String>,
    perProviderTimeoutMillis: Long,
    coarseGraceMillis: Long,
    isAccurate: (String) -> Boolean,
    onTimeout: (String) -> Unit = {},
    accept: (T) -> Boolean = { true },
    fetch: suspend (String) -> T?,
): T? = coroutineScope {
    val results = Channel<Pair<String, T?>>(Channel.UNLIMITED)
    val jobs = providers.map { provider ->
        launch {
            var completed = false
            val fix = withTimeoutOrNull(perProviderTimeoutMillis) {
                fetch(provider).also { completed = true }
            }
            // `withTimeoutOrNull` returns null both for a prompt "no fix" and a timeout; only the
            // latter leaves `completed` unset — the hanging provider worth reporting.
            if (!completed) onTimeout(provider)
            results.send(provider to fix)
        }
    }
    try {
        var remaining = providers.size
        var coarse: T? = null
        // An accurate provider's fix [accept] turned down: kept only in case nothing better comes.
        var vague: T? = null
        while (remaining > 0 && coarse == null) {
            val (provider, fix) = results.receive()
            remaining--
            if (fix == null) continue
            if (isAccurate(provider)) {
                if (accept(fix)) return@coroutineScope fix
                if (vague == null) vague = fix
                continue
            }
            coarse = fix
        }
        if (coarse == null) return@coroutineScope vague
        // A coarse fix is in hand: give the accurate providers a short grace to beat it.
        val accurate = withTimeoutOrNull(coarseGraceMillis) {
            var found: T? = null
            while (remaining > 0 && found == null) {
                val (provider, fix) = results.receive()
                remaining--
                if (fix != null && isAccurate(provider)) {
                    if (accept(fix)) found = fix else if (vague == null) vague = fix
                }
            }
            found
        }
        accurate ?: vague ?: coarse
    } finally {
        // Stop the providers still out: their answer is no longer wanted, and an unfinished
        // request would otherwise hold its location updates (and battery) until its bound.
        jobs.forEach { it.cancel() }
    }
}
