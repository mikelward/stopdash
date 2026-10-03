package app.stopdash.ui

import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.Dismissed
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.LineStatus
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflException
import app.stopdash.domain.lineAlertKey
import java.time.Instant
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

/**
 * Settles the dismissals of the lines a status check gave a verdict on ([answeredIds]) as the list's
 * refresh does ([lineDismissalCheck]): one whose alert TfL no longer reports is forgotten, so the same
 * alert coming back later shows again rather than staying hidden until the list happened to check that
 * line (Codex on #367). [dismissed] as pruned goes to [pruned] first, so a write that fails can't keep
 * the alert hidden there (Codex, PR #379); then the store, a write that outlasts the caller, as a
 * dismissal's does. Best-effort: a failed write is logged with [what] asked, and the next check tries
 * again. The trip's screen and a trip on the way both settle their lines here.
 */
internal suspend fun reconcileLineDismissals(
    dismissed: Set<DismissedAlert>,
    answered: Map<String, LineStatus>,
    answeredIds: Set<String>,
    now: Instant,
    store: DismissedAlertsStore,
    io: CoroutineDispatcher,
    warn: (String) -> Unit,
    what: String,
    pruned: (Set<DismissedAlert>) -> Unit,
) {
    // Every live alert's identity, each line's under way ones included: on [io], never the caller's (the
    // main) thread (Codex on #519).
    val (live, checked) = withContext(io) { lineDismissalCheck(answered, answeredIds, now) }
    reconcileDismissals(dismissed, live, checked, store, io, warn, what, pruned)
}

/**
 * What a line-status check settles dismissals against: the live alerts among the statuses TfL
 * returned ([answered]), and the lines it settles. Those are every line TfL gave a verdict on
 * ([answeredIds], as `LineStatusBatch` counts them), one it answered with no status at all
 * included, as the list counts them (Codex, PR #441). Not a line still waiting on which way its alerts
 * go: a dismissal of one direction's alert can't be matched against it until the split lands.
 */
internal fun lineDismissalCheck(
    answered: Map<String, LineStatus>,
    answeredIds: Set<String>,
    now: Instant,
): Pair<Set<DismissedAlert>, Set<String>> {
    val checked = answeredIds.filterNot { answered[it]?.awaitingDirections == true }.mapTo(HashSet()) { lineAlertKey(it) }
    return DepartureRows.liveLineStatusAlerts(answered, now) to checked
}

/**
 * What a closure check settles dismissals against ([reconcileDismissals]): the live closure alerts
 * among the notices it [found] at each stop (from lookups that succeeded), and the places it settles,
 * each stop as its own place only. An interchange or stop area also holds stops the check didn't look
 * at, so a dismissal made there is left to the list, which sees the whole place, as the list's own
 * check of a journey's destinations leaves it ([MainViewModel]; Codex, PR #441). The trip's screen and
 * a trip on the way both settle their stops here.
 */
internal fun stopDismissalCheck(found: Map<String, List<StopDisruption>>, now: Instant): Pair<Set<DismissedAlert>, Set<String>> {
    val stops = found.map { (id, notices) -> StopArrivals(id, "", emptyList(), SteadyClock.stamp(now), disruptions = notices) }
    return DepartureRows.liveStopClosureAlerts(DepartureRows.across(stops, now)) to found.keys
}

/**
 * Settles [dismissed] against what a check found [live] at the places and lines it [checked]
 * ([Dismissed.reconcile]): in memory first, [pruned] told on [io] of each dismissal to let go of (none,
 * not told), then the store, a write that outlasts the caller. What to let go of rather than what's
 * left, so a dismissal made meanwhile isn't lost (Codex on #519). Best-effort: a failed write is
 * logged with [what] asked. Nothing checked, nothing settled.
 */
internal suspend fun reconcileDismissals(
    dismissed: Set<DismissedAlert>,
    live: Set<DismissedAlert>,
    checked: Set<String>,
    store: DismissedAlertsStore,
    io: CoroutineDispatcher,
    warn: (String) -> Unit,
    what: String,
    pruned: (Set<DismissedAlert>) -> Unit,
) {
    if (checked.isEmpty()) return
    // Settled on [io]: it goes through every dismissal and live alert, never on the caller's (the main)
    // thread (Codex on #519).
    withContext(io) {
        val gone = dismissed - Dismissed.reconcile(dismissed, live, checked)
        if (gone.isNotEmpty()) pruned(gone)
    }
    try {
        withContext(NonCancellable + io) { store.reconcile(live, checked) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warn("$what dismissal reconcile failed: ${e::class.simpleName}")
    }
}

private fun reason(e: Throwable): String = (e as? TflException)?.message ?: e::class.simpleName.orEmpty()
