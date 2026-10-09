package app.stopdash.watch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stopdash.StopdashDebugLog
import app.stopdash.data.WatchRefreshOutcome
import app.stopdash.data.WatchRefreshReply
import app.stopdash.data.WatchSyncContract
import app.stopdash.widget.SETTINGS_READ_BOUND_MILLIS
import app.stopdash.widget.RefreshKeys
import app.stopdash.widget.StoredSnapshotRefresh
import app.stopdash.widget.readRefreshKeys
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * A watch's refresh request (dev-docs/wear-os.md *Refresh*): one bounded refresh of the widget's
 * stops, the same work as a live-widget refresh cycle (following the rider first where *Widget
 * follows you* is on, which may send the phone's position to TfL to find stops), then the outcome sent
 * back to the watch that asked. A refresh that stores something new is published to the watch by
 * [WatchSync] as any snapshot change is; a debounced one (every stop fetched moments ago) resends
 * the current snapshot instead. Every request is answered, and refreshes run one at a time across
 * watches: one right after another finds the stops just fetched and reuses them.
 */
class WatchRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val node = inputData.getString(KEY_NODE) ?: return Result.success()
        val requestId = inputData.getLong(KEY_REQUEST, 0L)
        val outcome = try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StopdashDebugLog.warning("watch: refresh failed: %s", e::class.simpleName)
            WatchRefreshOutcome.UNREACHABLE
        }
        try {
            Wearable.getMessageClient(applicationContext)
                .sendMessage(node, WatchSyncContract.REFRESH_RESULT_PATH, WatchRefreshReply(requestId, outcome).encode())
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The watch times out and says the phone is out of reach, which is then true enough.
            StopdashDebugLog.warning("watch: refresh reply failed: %s", e::class.simpleName)
        }
        return Result.success()
    }

    // One refresh at a time, with every watch and the widget's cycle ([StoredSnapshotRefresh]), which
    // also answers from a recent all-failed refresh rather than repeat it.
    private suspend fun refresh(): WatchRefreshOutcome {
        // The keys first, outside the shared lock, and bounded: someone is waiting on the watch.
        val keys = readRefreshKeys(applicationContext, boundMillis = SETTINGS_READ_BOUND_MILLIS)
        return refreshLocked(keys)
    }

    private suspend fun refreshLocked(keys: RefreshKeys?): WatchRefreshOutcome = StoredSnapshotRefresh.lock.withLock {
        val prior = WatchSync.snapshots(applicationContext).first()
        if (prior == null) return@withLock noStops()
        // An empty snapshot is refreshed too: a widget that follows the rider stores one where there were
        // no stops, and the refresh is what follows them on to where there are (Codex on #711).
        val result = StoredSnapshotRefresh.refresh(applicationContext, prior, keys)
        // Answered for the stops it followed to: none there is "no stops", a failed fetch there is the failure.
        if (result.outcome == WatchRefreshOutcome.NO_STOPS) return@withLock noStops()
        // Publish what's stored now, rather than rely on the app's collector (which may have given
        // up after storage errors). Nothing saved (debounced, all failed, or discarded because the
        // stops changed meanwhile) forces a resend, which the watch may have missed (an old stop
        // set, say); a save publishes as usual, a no-op if the collector already sent it.
        resend(force = !result.saved)
        result.outcome
    }

    // The watch may still hold stops it was never told were removed, or that the phone no longer has at
    // all (its data cleared): send it an empty envelope then.
    private suspend fun noStops(): WatchRefreshOutcome {
        resend(emptyIfNone = true)
        return WatchRefreshOutcome.NO_STOPS
    }

    /**
     * Publishes what is stored; [force] resends it even if unchanged, for an answer that brings no
     * new snapshot of its own. A failed publish is retried by [WatchPublishWorker], since nothing
     * else is certain to prompt one.
     */
    private suspend fun resend(force: Boolean = true, emptyIfNone: Boolean = false) {
        if (WatchSync.publishCurrent(applicationContext, force, emptyIfNone) == WatchPublisher.Outcome.Failed) {
            WatchPublishWorker.enqueue(applicationContext, force, emptyIfNone)
        }
    }

    companion object {
        private const val UNIQUE_PREFIX = "watch-refresh-"
        private const val KEY_NODE = "node"
        private const val KEY_REQUEST = "request"

        /** Asks for a refresh on behalf of the watch [node], answered with its [requestId]. */
        fun enqueue(context: Context, node: String, requestId: Long) {
            val request = OneTimeWorkRequestBuilder<WatchRefreshWorker>()
                .setInputData(workDataOf(KEY_NODE to node, KEY_REQUEST to requestId))
                // The user is looking at the watch: run now if the quota allows, else soon.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                // Per watch, each request queued behind the one running, so every request gets its
                // own answer; a later run finds the stops just fetched and reuses them rather than
                // spending the budget again.
                .enqueueUniqueWork(UNIQUE_PREFIX + node, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
