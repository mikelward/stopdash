package app.stopdash.watch

import android.content.Context
import app.stopdash.StopdashDebugLog
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.WatchSyncContract
import app.stopdash.data.logAppSettingsWarning
import app.stopdash.domain.AppSettings
import app.stopdash.widget.resumeWidgetRefreshIfEnabled
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/*
 * The watch's part in the opt-in minute updates (SPEC D5): the watch never fetches on its own, so
 * "Automatically update widget" is what keeps it current while the phone stays in a pocket. The
 * chain goes on, with no widget placed, while a watch in reach has the app, and a watch coming back
 * is what starts it again, as a widget render does for the widget. A watch that is only away, reachable
 * through the cloud, is checked again every few minutes instead ([WatchReach.AWAY]).
 */

/** Where a watch with the StopDash watch app is, as far as the minute updates care. */
internal enum class WatchReach {
    /** Near the phone (`isNearby`): on the rider's wrist, so it gets the minute updates. */
    NEARBY,

    /**
     * Reachable only through the cloud, as a watch left at home online is: not worth a fetch every
     * minute, but worth asking again now and then, since its coming back near the phone may not
     * change the set of reachable watches, which is all the capability listener reports.
     */
    AWAY,

    /** No watch with the app reachable at all: one coming back is a capability change. */
    NONE,
}

/**
 * Whether a watch with the StopDash watch app is in reach now, away, or not there. In reach, not just
 * paired: a refresh for a watch that's off or left at home only waits in the Data Layer, or updates a
 * watch nobody is wearing, while the phone pays for it every minute. No Wearable API on this phone (no
 * Play services, or no Wear OS app) is no watch; anything else that fails throws, for the caller to
 * decide.
 */
internal suspend fun watchReach(context: Context): WatchReach =
    try {
        reachOf(
            Wearable.getCapabilityClient(context.applicationContext)
                .getCapability(WatchSyncContract.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes,
        )
    } catch (e: ApiException) {
        if (e.statusCode != CommonStatusCodes.API_NOT_CONNECTED) throw e
        WatchReach.NONE
    }

/**
 * What the reachable watches [nodes] mean for the minute updates. Reachable alone isn't in reach: a
 * watch left at home on Wi-Fi or cellular stays reachable through the cloud, and the rider isn't
 * wearing it (Codex on #918).
 */
internal fun reachOf(nodes: Iterable<Node>): WatchReach = when {
    nodes.any { it.isNearby } -> WatchReach.NEARBY
    nodes.any() -> WatchReach.AWAY
    else -> WatchReach.NONE
}

/**
 * Brings the minute updates back for a watch in reach, when the setting is on: they wait on a
 * five-minute check while no widget is placed and no watch is in reach, and this ends the wait at once
 * rather than at the next check. A failure is logged, never thrown, and returns false, for a caller
 * that can try again ([WatchLiveRefreshResumeWorker]); the check still catches it within five minutes.
 */
internal suspend fun resumeLiveRefreshForWatch(
    context: Context,
    settings: AppSettings = DataStoreAppSettings.from(context.applicationContext, warn = ::logAppSettingsWarning),
): Boolean =
    try {
        resumeWidgetRefreshIfEnabled(context, settings).also { read ->
            if (!read) StopdashDebugLog.warning("watch: live refresh resume failed: setting unreadable")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StopdashDebugLog.warning("watch: live refresh resume failed: %s", e::class.simpleName)
        false
    }

/**
 * A watch in reach asked for a refresh ([WatchRefreshWorker]): the minute updates come back at once,
 * and a resume that fails is left to [WatchLiveRefreshResumeWorker] to retry, since the request
 * worker has answered the watch and can't repeat itself for this (Codex on #918). The waiting check
 * would bring them back anyway, but up to five minutes later.
 */
internal suspend fun resumeLiveRefreshForWatchRequest(
    context: Context,
    resume: suspend (Context) -> Boolean = { resumeLiveRefreshForWatch(it) },
    retryLater: (Context) -> Unit = { WatchLiveRefreshResumeWorker.enqueue(it) },
) {
    if (!resume(context)) retryLater(context.applicationContext)
}

/**
 * A watch with the app in reach, newly or again ([PhoneWearListenerService]): the current snapshot is
 * republished to it, past the identical-content check, and the minute updates come back if they were
 * waiting while it was away. Both are durable work, enqueued before the listener's callback returns.
 */
internal fun watchAppeared(
    context: Context,
    republish: (Context) -> Unit = { WatchPublishWorker.enqueue(it, force = true) },
    resume: (Context) -> Unit = { WatchLiveRefreshResumeWorker.enqueue(it) },
) {
    val app = context.applicationContext
    republish(app)
    resume(app)
}

/**
 * Brings the minute updates back for a watch that came back ([watchAppeared]), as work WorkManager
 * keeps: the listener's callback returns at once, and Android can tear the service, and the process,
 * down before a coroutine of its own finished, losing the reconnect, the one event that says so at
 * once (Codex on #918). One pending is enough, since it reads the state when it runs. A resume that
 * fails is retried, with WorkManager's backoff, for the same reason (Codex on #918). Lost anyway, the
 * waiting check brings them back within five minutes.
 */
class WatchLiveRefreshResumeWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = resume(applicationContext)

    companion object {
        internal const val UNIQUE_NAME = "watch-live-refresh-resume"

        /** [doWork]'s outcome: done, or a resume that failed, retried. */
        internal suspend fun resume(
            context: Context,
            settings: AppSettings =
                DataStoreAppSettings.from(context.applicationContext, warn = ::logAppSettingsWarning),
        ): ListenableWorker.Result =
            if (resumeLiveRefreshForWatch(context, settings)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WatchLiveRefreshResumeWorker>().build(),
            )
        }
    }
}
