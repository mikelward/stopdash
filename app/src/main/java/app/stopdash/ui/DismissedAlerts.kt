package app.stopdash.ui

import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.TflException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The base backoff before restarting a failed dismissed-set read; doubled each attempt, and reset
 *  after any successful emission. */
private const val DISMISSED_READ_RETRY_MS = 500L

/** The ceiling the dismissed-set read backoff is capped at, so a persistently failing store is
 *  retried forever at a steady, quiet interval rather than giving up (storage can recover later). */
private const val DISMISSED_READ_RETRY_MAX_MS = 30_000L

/** How long a save waits for the stored dismissed set before using the in-memory copy. */
private const val DISMISSED_SAVE_READ_MS = 2_000L

/**
 * [snapshot] with each line check the user dismissed marked, judged on the dismissed set as
 * [store] holds it now rather than on [fallback], this screen's copy of it: straight after a
 * restart that copy can still be empty, and a save built from it would bring back the mark of an
 * alert dismissed long ago. A set that can't be read in time falls back to [fallback], and says so.
 */
internal suspend fun withStoredDismissals(
    snapshot: DeparturesSnapshot,
    store: DismissedAlertsStore,
    fallback: Set<DismissedAlert>,
    warn: (String) -> Unit,
): DeparturesSnapshot {
    if (snapshot.lineStatuses.isEmpty()) return snapshot
    val stored = try {
        withTimeoutOrNull(DISMISSED_SAVE_READ_MS) { store.dismissed().first() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warn("dismissed set read before save failed: ${reason(e)}")
        null
    }
    if (stored == null) warn("dismissed set not read before save; using this screen's copy")
    val dismissed = stored ?: fallback
    val marked = snapshot.lineStatuses.mapValues { (_, check) ->
        if (!check.dismissed && check.dismissedBy(dismissed)) check.copy(dismissed = true) else check
    }
    return if (marked == snapshot.lineStatuses) snapshot else snapshot.copy(lineStatuses = marked)
}

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
 * for the screen to say so rather than let the dismiss tap look broken. A saved line-status
 * dismissal is passed to [widget] too; one that didn't save isn't, so nothing downstream acts on a
 * dismissal the app itself doesn't hold.
 */
internal suspend fun dismissAlert(
    store: DismissedAlertsStore,
    row: DepartureRow,
    io: CoroutineDispatcher,
    failed: MutableStateFlow<Boolean>,
    warn: (String) -> Unit,
    // Where a saved line-status dismissal goes next: the widget's stored snapshot, so the widget
    // and the watch drop the mark at once ([SnapshotStore.dismissLineStatus]).
    widget: SnapshotStore = SnapshotStore.NONE,
) {
    val alert = DismissedAlert.of(row) ?: return
    // A line's status (not a stop closure, which the widget doesn't carry) leaves the widget and
    // the watch too, at once rather than at the next save.
    val lineId = row.status?.lineId?.takeIf { row.stopDisruption == null }
    // One NonCancellable section, like a star: a dismiss tapped just before leaving the page lands
    // in both stores, never in the app's alone.
    withContext(NonCancellable + io) {
        try {
            store.dismiss(alert)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("alert dismiss failed: ${reason(e)}")
            failed.value = true
            // Not saved: the app keeps showing the alert, so the widget and the watch keep it too.
            return@withContext
        }
        if (lineId == null) return@withContext
        try {
            widget.dismissLineStatus(alert)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: the widget's next save marks it anyway, from the dismissed set.
            warn("widget dismissal of line $lineId failed: ${reason(e)}")
        }
    }
}

private fun reason(e: Throwable): String = (e as? TflException)?.message ?: e::class.simpleName.orEmpty()
