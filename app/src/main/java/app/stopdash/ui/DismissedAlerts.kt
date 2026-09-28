package app.stopdash.ui

import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.TflException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The base backoff before restarting a failed dismissed-set read; doubled each attempt, and reset
 *  after any successful emission. */
private const val DISMISSED_READ_RETRY_MS = 500L

/** The ceiling the dismissed-set read backoff is capped at, so a persistently failing store is
 *  retried forever at a steady, quiet interval rather than giving up (storage can recover later). */
private const val DISMISSED_READ_RETRY_MAX_MS = 30_000L

/**
 * Follows [store]'s dismissed alerts into [into], for as long as the caller runs. A read failure
 * fails safe to "nothing dismissed" (every alert shown) — the same direction the store's empty
 * fallback takes, so a set we can't read never hides a card. A transient error RESTARTS the
 * collection with capped backoff rather than terminating it: a dead collector would silently stop
 * dismiss from taking effect (a later write would update the store with no one listening). The
 * backoff never gives up (storage can recover later) and resets after any good emission, so
 * occasional, non-consecutive failures don't ratchet it to the ceiling.
 */
internal suspend fun followDismissed(
    store: DismissedAlertsStore,
    into: MutableStateFlow<Set<DismissedAlert>>,
    warn: (String) -> Unit,
) {
    var backoff = DISMISSED_READ_RETRY_MS
    while (true) {
        try {
            store.dismissed().collect {
                into.value = it
                backoff = DISMISSED_READ_RETRY_MS
            }
            break
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            into.value = emptySet()
            warn("dismissed set read failed, retrying: ${reason(e)}")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(DISMISSED_READ_RETRY_MAX_MS)
        }
    }
}

/**
 * Records [row]'s alert as dismissed in [store]; a row with no dismissable alert does nothing. A
 * write that didn't take sets [failed] — the store won't re-emit, so the card would silently stay —
 * for the screen to say so rather than let the dismiss tap look broken.
 */
internal suspend fun dismissAlert(
    store: DismissedAlertsStore,
    row: DepartureRow,
    io: CoroutineDispatcher,
    failed: MutableStateFlow<Boolean>,
    warn: (String) -> Unit,
) {
    val alert = DismissedAlert.of(row) ?: return
    try {
        // NonCancellable, like a star: a dismiss tapped just before leaving the page still lands.
        withContext(NonCancellable + io) { store.dismiss(alert) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warn("alert dismiss failed: ${reason(e)}")
        failed.value = true
    }
}

private fun reason(e: Throwable): String = (e as? TflException)?.message ?: e::class.simpleName.orEmpty()
