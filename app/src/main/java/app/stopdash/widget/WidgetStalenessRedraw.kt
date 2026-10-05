package app.stopdash.widget

import android.content.Context
import app.stopdash.StopdashDebugLog
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.Staleness
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException

/** Unique-work name so each scheduled redraw REPLACEs the previous one — at most one pending. */
internal const val WIDGET_STALENESS_WORK = "stopdash-widget-staleness-redraw"

/**
 * The second slot the next boundary goes into while a redraw in [WIDGET_STALENESS_WORK] is running
 * (and the other way round). With more than one boundary ahead (a line check's expiry, then the
 * arrivals'), the render a redraw triggers schedules the next one; REPLACE on the running redraw's
 * own name would cancel it before its render commits, so the successor takes the other slot.
 */
internal const val WIDGET_STALENESS_WORK_NEXT = "stopdash-widget-staleness-redraw-next"

private const val SLOT_KEY = "slot"

/** When a redraw was due, wall-clock millis, so the log can say how late Android ran it. */
private const val DUE_KEY = "due"

/** The slot of the redraw running now in this process, if any; set by [WidgetStalenessWorker]. */
@Volatile
internal var runningStalenessSlot: String? = null

/**
 * Arms (or cancels) the one-shot render-only redraw at the staleness boundary of the snapshot
 * the widget is currently rendering, so a widget left untouched after the app closes flips from
 * live-looking countdowns to the stale `?` treatment on its own (SPEC D4). With the provider's
 * `updatePeriodMillis="0"` the host never re-renders the widget, and [updateAll] is only reached
 * while the app runs — so without this, a closed-app widget can show "Updated just now" and a
 * live countdown indefinitely past the threshold.
 *
 * Called from `provideGlance` — the render path — so *every* way the widget comes to show a
 * snapshot arms the flip from the snapshot it just drew: first add, host rebind, and the app's
 * `updateAll` after a fetch (which re-runs `provideGlance`). It follows that a host with no
 * widget never runs this, so a widgetless user pays no scheduling or wake cost — no separate
 * installed-id guard needed. [snapshotFetchedAt] is null for the no-snapshot empty state (no
 * stamp or countdown to age), which cancels any pending wake.
 *
 * One deferrable, unique wake per snapshot (REPLACE), fired at most once and never rescheduling
 * once stale (the boundary redraw re-renders, finds the snapshot already stale, and cancels) —
 * negligible battery, not a polling cadence (a live *refresh* cadence stays deferred, SPEC D5).
 */
internal fun scheduleStalenessRedrawFor(
    context: Context,
    snapshot: DeparturesSnapshot?,
    now: Instant,
    // A redraw due no later than this whatever the snapshot does: a render that needs to look again
    // (the nearby set it couldn't read). Null for none.
    within: Duration? = null,
) {
    // The snapshot's next change on its own: its staleness boundary, or a line check's expiry
    // (a disruption's mark goes, or a line turns unchecked), whichever is first. The redraw there
    // re-renders and arms the next one, until none is left.
    val boundary = snapshot?.nextBoundary(now)
    val remaining = boundary?.let { JavaDuration.between(now, it).toKotlinDuration() } ?: Duration.ZERO
    applyStalenessRedrawPlan(WorkManager.getInstance(context.applicationContext), redrawIn(remaining, within))
}

/** The sooner of the snapshot's own boundary ([remaining], zero for none) and [within], when given. */
internal fun redrawIn(remaining: Duration, within: Duration?): Duration = when {
    within == null -> remaining
    remaining == Duration.ZERO -> within
    else -> minOf(remaining, within)
}

/** How soon the widget looks again after a nearby set it couldn't read. */
internal val NEARBY_SET_RETRY: Duration = 1.minutes

/** The arrivals-only form of [scheduleStalenessRedrawFor]: the boundary of a snapshot fetched at [snapshotFetchedAt]. */
internal fun scheduleStalenessRedrawFor(context: Context, snapshotFetchedAt: Instant?, now: Instant) {
    val remaining = if (snapshotFetchedAt == null) {
        Duration.ZERO
    } else {
        Staleness.remainingUntilStale(Staleness.age(snapshotFetchedAt, now))
    }
    applyStalenessRedrawPlan(WorkManager.getInstance(context.applicationContext), remaining)
}

/**
 * The scheduling decision over WorkManager, split out to be testable without a Glance host:
 * enqueue the one-shot redraw when the snapshot is not yet stale ([remaining] > 0); otherwise do
 * nothing.
 *
 * It deliberately does **not** cancel on the stale ([remaining] == 0) branch. The boundary redraw
 * itself calls `updateAll`, which re-enters `provideGlance` and reaches here with zero remaining —
 * cancelling `WIDGET_STALENESS_WORK` there would cancel the very worker that is running, before its
 * `provideContent` commits, leaving the old live-looking countdowns installed. Nothing needs
 * cancelling in the stale case anyway: a fired one-shot is already consumed, and the next fresh
 * render REPLACEs it. Cancelling when the widget is *removed* is [cancelWidgetStalenessRedraw],
 * driven from `onDelete`, not from a render.
 */
internal fun applyStalenessRedrawPlan(
    workManager: WorkManager,
    remaining: Duration,
    running: String? = runningStalenessSlot,
) {
    if (remaining == Duration.ZERO) return
    enqueueStalenessRedraw(workManager, remaining, running)
}

/**
 * A render-only redraw at once, for when the device's clock has been set ([WidgetClockChangeReceiver]):
 * the widget's countdowns and age are static text drawn against the clock as it read then, and its
 * next scheduled redraw may be minutes off (Codex, PR #371). It takes the free slot as a boundary's
 * redraw does, and its render arms the next boundary as any render does.
 */
internal fun redrawWidgetNow(workManager: WorkManager, running: String? = runningStalenessSlot) {
    enqueueStalenessRedraw(workManager, Duration.ZERO, running)
}

private fun enqueueStalenessRedraw(workManager: WorkManager, delay: Duration, running: String?) {
    // Into the slot that isn't running a redraw right now, so a successor scheduled from within a
    // redraw's own render never replaces (cancels) it; the other slot's pending wake, if it isn't
    // the running one, is dropped so only one boundary is ever pending.
    val target = if (running == WIDGET_STALENESS_WORK) WIDGET_STALENESS_WORK_NEXT else WIDGET_STALENESS_WORK
    val other = if (target == WIDGET_STALENESS_WORK) WIDGET_STALENESS_WORK_NEXT else WIDGET_STALENESS_WORK
    workManager.enqueueUniqueWork(
        target,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetStalenessWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(SLOT_KEY to target, DUE_KEY to System.currentTimeMillis() + delay.inWholeMilliseconds))
            .build(),
    )
    if (other != running) workManager.cancelUniqueWork(other)
}

/** Cancels a pending staleness redraw — called when the last widget instance is removed. */
internal fun cancelWidgetStalenessRedraw(context: Context) {
    val workManager = WorkManager.getInstance(context.applicationContext)
    workManager.cancelUniqueWork(WIDGET_STALENESS_WORK)
    workManager.cancelUniqueWork(WIDGET_STALENESS_WORK_NEXT)
}

/**
 * Re-renders the widget from the unchanged persisted snapshot; `provideGlance` recomputes the age
 * against the current clock, so a boundary crossing now shows the stale treatment (`?`, "tap to
 * refresh"). Render-only — reads no network and writes no snapshot.
 */
class WidgetStalenessWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            // Marks this slot running while the render it triggers schedules the next boundary
            // ([applyStalenessRedrawPlan]), so that one goes into the other slot.
            runningStalenessSlot = inputData.getString(SLOT_KEY) ?: WIDGET_STALENESS_WORK
            // How late Android ran it: the widget holds its last render meanwhile, so a long delay here
            // is a widget showing times that have passed.
            val due = inputData.getLong(DUE_KEY, 0L)
            if (due > 0L) StopdashDebugLog.info("widget: redraw ran %d s after due", (System.currentTimeMillis() - due) / 1000)
            try {
                redrawWidgets(applicationContext)
            } finally {
                runningStalenessSlot = null
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning(
                "widget staleness redraw failed (attempt ${runAttemptCount + 1}): ${e::class.simpleName}",
            )
            // This is the one boundary wake and it is honesty-critical (SPEC D4), so retry a few
            // times on WorkManager's backoff rather than consuming it on a transient failure; give
            // up after a bound so a persistently failing redraw doesn't loop forever (the next
            // fetch or host rebind will re-render regardless). Sanitized log (SPEC Privacy).
            if (runAttemptCount < MAX_REDRAW_ATTEMPTS) Result.retry() else Result.success()
        }

    private companion object {
        const val MAX_REDRAW_ATTEMPTS = 5
    }
}
