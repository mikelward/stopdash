package app.stopdash.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The near-me fix is taken only with the app in front (SPEC *Finding stops*). With location allowed
 * only while the app is in use, Android gives an app it doesn't count in front no fix at all, not even
 * the last one it holds. A fix asked for then came back as "couldn't get your location" with location
 * working: it replaced the list, took the near-me stops off the widget, and was still up when the app
 * came back (bug report, 2026-10-16). Just after an unlock, too, Android can take a moment to count an
 * app on screen as in front. So the fix waits: for the app to be on screen, then for Android to count
 * it in front, and once it has been on screen [SETTLE_MILLIS] without that, it is asked for anyway and
 * what comes back is shown as it is. Android's count alone isn't enough: a trip's foreground service
 * counts as in front with nothing on screen, and the near-me fix is never a background one (Codex).
 */
object InFront {
    /** How long the app may be on screen without Android counting it in front before the fix is asked for anyway. */
    const val SETTLE_MILLIS = 2_000L

    /** How often [await] looks again while the app is on screen but not yet counted in front. */
    const val CHECK_EVERY_MILLIS = 250L

    /**
     * Returns once the app is on screen ([onScreen]) and [inFront] says Android counts it in front, or
     * once it has been on screen for [settleMillis] without that. Every way out has the app on screen at
     * that moment, looked at after Android has answered. While the app is off screen it waits
     * on [onScreen] alone, whatever Android's count says, so a fix asked for while the app is away costs
     * nothing until it's back. [inFront] asks Android, so call this off the main thread.
     */
    suspend fun await(
        inFront: () -> Boolean,
        onScreen: StateFlow<Boolean>,
        warn: (String) -> Unit = {},
        settleMillis: Long = SETTLE_MILLIS,
        checkEveryMillis: Long = CHECK_EVERY_MILLIS,
    ) {
        // On screen both before and after Android is asked: the app can leave while it's asked (Codex).
        if (onScreen.value && inFront() && onScreen.value) return
        warn("location fix put off: the app isn't in front")
        while (true) {
            onScreen.first { it }
            when (withTimeoutOrNull(settleMillis) { settle(inFront, onScreen, checkEveryMillis) }) {
                true -> return
                // Off screen again before Android counted it in front: wait for the next return.
                false -> continue
                null -> {
                    // Off screen since the last look: not settled on screen, so wait for the next return.
                    if (!onScreen.value) continue
                    warn("location fix: the app is on screen but still not in front after ${settleMillis / 1_000} s; asking anyway")
                    return
                }
            }
        }
    }

    // True once Android counts the app in front with it still on screen, false if it leaves the screen first.
    private suspend fun settle(inFront: () -> Boolean, onScreen: StateFlow<Boolean>, checkEveryMillis: Long): Boolean {
        while (!inFront()) {
            if (!onScreen.value) return false
            delay(checkEveryMillis)
        }
        // Looked at again after Android's answer, as on the way in.
        return onScreen.value
    }
}
