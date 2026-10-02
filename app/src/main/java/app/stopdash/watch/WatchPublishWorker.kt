package app.stopdash.watch

import android.content.Context
import app.stopdash.StopdashDebugLog
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.safe
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stopdash.data.WatchRefreshReply
import app.stopdash.data.WatchSyncContract
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

/**
 * Publishes the current snapshot to the watch outside the app's own collector: a bounded retry
 * after a failed publish, and a forced republish when a watch reconnects. One unique job of each
 * kind, latest request wins; it reads the stored state when it runs, so it always sends the newest
 * envelope.
 */
class WatchPublishWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val emptyIfNone = inputData.getBoolean(KEY_EMPTY_IF_NONE, false)
        val outcome = WatchSync.publishCurrent(applicationContext, force, emptyIfNone)
        if (outcome != WatchPublisher.Outcome.Failed) return Result.success()
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        // Given up for now: the next snapshot, star change, app start or reconnect tries again.
        StopdashDebugLog.warning("watch: publish retries exhausted")
        return Result.failure()
    }

    companion object {
        /** Forced and ordinary requests queue apart, so a later ordinary retry never replaces a
         *  pending forced republish (and loses its force); each keeps only its own latest. */
        private const val UNIQUE_NAME = "watch-publish"
        private const val UNIQUE_NAME_FORCED = "watch-publish-forced"

        /** A forced republish that clears a watch when nothing is stored, apart from the rest, so
         *  an ordinary forced one (a reconnect) can't replace it and lose the clearing. */
        private const val UNIQUE_NAME_CLEARING = "watch-publish-clearing"
        private const val KEY_FORCE = "force"
        private const val KEY_EMPTY_IF_NONE = "empty_if_none"
        private const val MAX_ATTEMPTS = 3

        /** [emptyIfNone]: see [WatchPublisher.publish]; only a forced republish asks for it. */
        fun enqueue(context: Context, force: Boolean, emptyIfNone: Boolean = false) {
            val clearing = force && emptyIfNone
            val request = OneTimeWorkRequestBuilder<WatchPublishWorker>()
                .setInputData(workDataOf(KEY_FORCE to force, KEY_EMPTY_IF_NONE to clearing))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            val name = when {
                clearing -> UNIQUE_NAME_CLEARING
                force -> UNIQUE_NAME_FORCED
                else -> UNIQUE_NAME
            }
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * Notices a watch gaining the app or coming back in reach, and republishes the current snapshot
 * to it (forced, past the identical-content check), so a watch never keeps an older snapshot than
 * the phone's just because nothing changed while it was away. Also takes a watch's refresh
 * requests.
 */
class PhoneWearListenerService : WearableListenerService() {
    /** A watch asking for a refresh ([WatchRefreshWorker]); it carries only a request id. */
    override fun onMessageReceived(event: MessageEvent) {
        val requestId = WatchListenerEvents.refreshRequest(event.path, event.data, ::logWatch) ?: return
        WatchRefreshWorker.enqueue(this, event.sourceNodeId, requestId)
        // Acknowledged at once, so the watch knows the phone is in reach while the refresh queues.
        Wearable.getMessageClient(this)
            .sendMessage(event.sourceNodeId, WatchSyncContract.REFRESH_ACK_PATH, WatchRefreshReply.encodeRequest(requestId))
            .addOnFailureListener { e -> StopdashDebugLog.warning("watch: refresh ack failed: %s", e::class.simpleName) }
    }

    /** The rows the watch's complications are set to changed: keep them, and republish if so. */
    override fun onDataChanged(events: DataEventBuffer) {
        var changed = false
        for (event in events) {
            if (ComplicationRowsStore.ingest(this, event.dataItem, deleted = event.type == DataEvent.TYPE_DELETED)) changed = true
        }
        if (changed) WatchPublishWorker.enqueue(this, force = false)
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (WatchListenerEvents.capabilityChanged(info.name, info.nodes.size, ::logWatch)) {
            WatchPublishWorker.enqueue(this, force = true)
        }
    }

    private fun logWatch(line: String) = StopdashDebugLog.watchStatus(line)
}

/**
 * Logs a watch-link [line] ("queued for the watch", "app on 1 paired watch(es)"), marked safe for
 * the off-device rendering: every one is fixed text, a count or an exception class name, never a
 * stop, a node id or anything the watch sent, so Crashlytics sees the state rather than `•••`.
 */
internal fun DebugLog.watchStatus(line: String) = warning("watch: %s", safe(line))

/**
 * What [PhoneWearListenerService] makes of a Data Layer event, and the line it logs, apart from
 * Play services so it's testable. Each logs so a bug report shows whether the watch's side of the
 * link reaches the phone at all; never a node id or anything the watch sent beyond a request id.
 */
internal object WatchListenerEvents {
    /** The request id of a watch's refresh request at [path], or null to ignore the message. */
    fun refreshRequest(path: String, data: ByteArray, log: (String) -> Unit): Long? {
        if (path != WatchSyncContract.REFRESH_PATH) return null
        val requestId = WatchRefreshReply.decodeRequest(data)
        log(if (requestId == null) "refresh request unreadable" else "refresh requested")
        return requestId
    }

    /**
     * Whether a capability change named [name], now on [nodes] paired watches, calls for a forced
     * republish: only StopDash's own, and only when some watch has it. Logs the count, which tells a
     * watch the phone never sees from one it lost.
     */
    fun capabilityChanged(name: String, nodes: Int, log: (String) -> Unit): Boolean {
        if (name != WatchSyncContract.WATCH_CAPABILITY) return false
        log("app on $nodes paired watch(es)")
        return nodes > 0
    }
}
