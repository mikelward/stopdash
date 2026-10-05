package app.stopdash.widget

import android.content.Context
import android.os.SystemClock
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.data.WatchRefreshOutcome
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Unique-work name for a tap's refresh: a second tap while one runs joins it rather than queue another. */
internal const val WIDGET_TAP_REFRESH_WORK = "stopdash-widget-tap-refresh"

/**
 * How long after a tap the widget may say "Refreshing…" (SPEC D5). The refresh ends sooner, and its
 * end redraws the widget; the render that shows the note also arms a redraw for when this runs out
 * ([WidgetTapRefresh.noteRemaining]), so a tap whose end never came (the process died, or the job was
 * deferred) can't leave the note up on a widget nothing else redraws.
 */
internal const val TAP_REFRESH_NOTE_MILLIS = 60_000L

/** What the widget's note says about the last tap's refresh, when it says anything. */
internal enum class WidgetTapNote { REFRESHING, RATE_LIMITED, UNREACHABLE, KEY_REJECTED }

/**
 * The last tap's refresh, as the widget's note tells it: running, or failed outright. In memory only:
 * it describes this process's own refresh, and after process death the refresh's rerun redraws
 * without it. Guarded by its monitor; read once per render, off the main thread.
 */
internal object WidgetTapRefresh {
    private var startedAt: Long? = null
    private var failure: WidgetTapNote? = null

    // Counts taps, so a refresh clears "Refreshing…" only when no tap came after it began: a later
    // tap has a refresh of its own queued behind it, which owns the note until it ends.
    private var taps = 0L

    // The stored snapshot the failed refresh left as it was: the note speaks only for that one, so a
    // refresh by the app (a new snapshot) clears it.
    private var failedFor: Instant? = null

    @Synchronized
    fun started(elapsedMillis: Long) {
        taps++
        startedAt = elapsedMillis
        failure = null
        failedFor = null
    }

    /** A refresh begins: it answers every tap so far. Pass the result to [finished]. */
    @Synchronized
    fun begin(): Long = taps

    /**
     * The refresh that [began][begin] at [covered] ended with [outcome], leaving the stored snapshot
     * stamped [snapshotAt] as it was. A tap since then keeps "Refreshing…" for its own refresh.
     */
    @Synchronized
    fun finished(outcome: WatchRefreshOutcome, snapshotAt: Instant?, covered: Long) {
        if (taps != covered) return
        startedAt = null
        failure = when (outcome) {
            WatchRefreshOutcome.RATE_LIMITED -> WidgetTapNote.RATE_LIMITED
            WatchRefreshOutcome.UNREACHABLE -> WidgetTapNote.UNREACHABLE
            WatchRefreshOutcome.KEY_REJECTED -> WidgetTapNote.KEY_REJECTED
            // A partial refresh marks its missing stops in the snapshot ("Some stops out of date").
            else -> null
        }
        failedFor = snapshotAt.takeIf { failure != null }
    }

    /** Forgets the tap: its refresh couldn't even be asked for (logged by the caller). */
    @Synchronized
    fun abandoned() {
        startedAt = null
    }

    /** The note for a render at [elapsedMillis] of the snapshot stamped [snapshotAt]; null when none. */
    @Synchronized
    fun note(elapsedMillis: Long, snapshotAt: Instant?): WidgetTapNote? {
        startedAt?.let { if (elapsedMillis - it in 0 until TAP_REFRESH_NOTE_MILLIS) return WidgetTapNote.REFRESHING }
        return failure?.takeIf { snapshotAt != null && snapshotAt == failedFor }
    }

    /**
     * How long "Refreshing…" may still show at [elapsedMillis], so the render that shows it arms a
     * redraw for then; null when it isn't showing.
     */
    @Synchronized
    fun noteRemaining(elapsedMillis: Long): Duration? {
        val left = TAP_REFRESH_NOTE_MILLIS - (elapsedMillis - (startedAt ?: return null))
        return if (left in 1..TAP_REFRESH_NOTE_MILLIS) left.milliseconds else null
    }

    /**
     * [note] and, while it says "Refreshing…", when that runs out ([noteRemaining]), read together so
     * a refresh ending between the two can't leave a render showing the note with no redraw to end it.
     */
    @Synchronized
    fun noteAndExpiry(elapsedMillis: Long, snapshotAt: Instant?): Pair<WidgetTapNote?, Duration?> {
        val note = note(elapsedMillis, snapshotAt)
        return note to noteRemaining(elapsedMillis).takeIf { note == WidgetTapNote.REFRESHING }
    }

    /** Back to no tap, for tests. */
    @Synchronized
    fun reset() {
        startedAt = null
        failure = null
        failedFor = null
        taps = 0L
    }
}

/**
 * How a tap's refresh joins any already queued: one not yet started will answer the tap ([KEEP]);
 * one running may have read the stops before the tap, so the tap's refresh goes after it ([APPEND]),
 * where it reuses whatever that one just fetched.
 */
internal fun tapRefreshPolicy(queued: List<WorkInfo.State>): ExistingWorkPolicy =
    if (WorkInfo.State.ENQUEUED in queued || WorkInfo.State.BLOCKED in queued) {
        ExistingWorkPolicy.KEEP
    } else {
        ExistingWorkPolicy.APPEND_OR_REPLACE
    }

/**
 * A tap on the widget's header (SPEC D5): refreshes the widget's stored stops in place, as the
 * "Tap to refresh" note promises. The departures below it still open the app.
 */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        requestWidgetRefresh(context)
    }
}

/**
 * Starts a tap's refresh: says "Refreshing…" at once, then runs [WidgetTapRefreshWorker] expedited.
 * Hops to [worker] first: the enqueue writes WorkManager's database and the redraw reads the stores.
 */
internal suspend fun requestWidgetRefresh(
    context: Context,
    worker: CoroutineDispatcher = Dispatchers.Default,
    redraw: suspend (Context) -> Unit = { redrawWidgets(it) },
) = withContext(worker) {
    WidgetTapRefresh.started(SystemClock.elapsedRealtime())
    try {
        val workManager = WorkManager.getInstance(context.applicationContext)
        // Read after the tap is counted, so a refresh seen queued here begins after it and answers it.
        val queued = workManager.getWorkInfosForUniqueWorkFlow(WIDGET_TAP_REFRESH_WORK).first().map { it.state }
        workManager.enqueueUniqueWork(
            WIDGET_TAP_REFRESH_WORK,
            tapRefreshPolicy(queued),
            OneTimeWorkRequestBuilder<WidgetTapRefreshWorker>()
                // The user is looking at the widget: run now if the quota allows, else soon.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        ).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget tap refresh enqueue failed: ${e::class.simpleName}")
        // No refresh is coming, so don't say one is; the widget stays as it was ("Tap to refresh").
        WidgetTapRefresh.abandoned()
    }
    try {
        redraw(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget re-render failed: ${e::class.simpleName}")
    }
}

/**
 * A tap's refresh of the widget's stored stops: the same location-free work as a live-widget cycle
 * and a watch's request ([StoredSnapshotRefresh], one at a time with them), whatever the "refresh
 * widget every minute" setting says, then a redraw that drops "Refreshing…", or says why nothing came.
 */
class WidgetTapRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val covered = WidgetTapRefresh.begin()
        val (outcome, snapshotAt) = try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget tap refresh failed: ${e::class.simpleName}")
            WatchRefreshOutcome.UNREACHABLE to storedAt()
        }
        WidgetTapRefresh.finished(outcome, snapshotAt, covered)
        try {
            redrawWidgets(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget re-render failed: ${e::class.simpleName}")
        }
        return Result.success()
    }

    private suspend fun refresh(): Pair<WatchRefreshOutcome, Instant?> {
        // The keys first, outside the shared lock, and bounded: someone is looking at the widget.
        val keys = readRefreshKeys(applicationContext, boundMillis = SETTINGS_READ_BOUND_MILLIS)
        return StoredSnapshotRefresh.lock.withLock {
            val prior = store().load() ?: return@withLock WatchRefreshOutcome.NO_STOPS to null
            val result = StoredSnapshotRefresh.refresh(applicationContext, prior, keys)
            // A failure fetched no arrivals, so the stored ones keep this stamp (a line check alone may save).
            result.outcome to prior.fetchedAt
        }
    }

    private suspend fun storedAt(): Instant? = try {
        store().load()?.fetchedAt
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("widget snapshot read failed: ${e::class.simpleName}")
        null
    }

    private fun store() = DataStoreSnapshotStore.from(applicationContext, warn = ::logWidgetSnapshotWarning)
}
