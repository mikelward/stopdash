package app.stopdash.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.data.WatchSyncContract
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Unique-work name for an unlock's refresh: unlocks while one waits to run join it. */
internal const val UNLOCK_REFRESH_WORK = "stopdash-unlock-refresh"

/**
 * Refreshes the widget's stored stops when the device is unlocked (SPEC D5), so the widget and a
 * paired watch show live times rather than "Tap to refresh" when someone looks at them. Android
 * hasn't delivered the unlock broadcast to a manifest receiver since 8.0, so it's heard only while
 * the app's process is running (after the app or widget was last used, until Android reclaims it):
 * registered in [app.stopdash.StopdashApp], for the life of the process. Silent: no "Refreshing…".
 * One fetch per unlock at most, and none for stops fetched moments ago ([StoredSnapshotRefresh]);
 * none with no widget placed and no watch to show it.
 */
object UnlockRefresh {
    /** Listens for unlocks for the life of the process. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        ContextCompat.registerReceiver(
            appContext,
            UnlockReceiver(),
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Asks for one refresh; an unlock while one waits to run joins it. */
    internal fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNLOCK_REFRESH_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UnlockRefreshWorker>()
                // Someone has just unlocked to look: run now if the quota allows, else soon.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}

/** Hears an unlock and hands the refresh to WorkManager, which runs it off the main thread. */
internal class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        try {
            UnlockRefresh.enqueue(context)
        } catch (e: IllegalStateException) {
            // WorkManager not ready (a test, or a process mid-teardown): the next unlock asks again.
            logWidgetSnapshotWarning("unlock refresh enqueue failed: ${e::class.simpleName}")
        }
    }
}

/**
 * Whether an unlock's refresh has anywhere to show: a placed widget, else a watch with the app. The
 * watch is asked only when no widget is placed, since that asks Play services. Either check failing
 * counts as none, logged: a lost refresh, never a crash.
 */
internal suspend fun unlockRefreshWanted(widgetPlaced: suspend () -> Boolean, watchHasApp: suspend () -> Boolean): Boolean {
    suspend fun ask(what: String, check: suspend () -> Boolean): Boolean = try {
        check()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logWidgetSnapshotWarning("unlock refresh: $what check failed: ${e::class.simpleName}")
        false
    }
    return ask("widget") { widgetPlaced() } || ask("watch") { watchHasApp() }
}

/**
 * An unlock's refresh: the same location-free refresh of the stored stops as a widget tap's and a
 * watch's request ([StoredSnapshotRefresh], one at a time with them), then a redraw. A saved
 * snapshot reaches a paired watch through [app.stopdash.watch.WatchSync], as any does.
 */
class UnlockRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!unlockRefreshWanted(::widgetPlaced, ::watchHasApp)) return Result.success()
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("unlock refresh failed: ${e::class.simpleName}")
        }
        try {
            StopDashWidget().updateAll(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget re-render failed: ${e::class.simpleName}")
        }
        return Result.success()
    }

    private suspend fun refresh() {
        // The keys first, outside the shared lock, and bounded: someone has just unlocked to look.
        val keys = readRefreshKeys(applicationContext, boundMillis = SETTINGS_READ_BOUND_MILLIS)
        StoredSnapshotRefresh.lock.withLock {
            // Nothing stored yet (the app never loaded a list): nothing to refresh.
            val prior = DataStoreSnapshotStore.from(applicationContext, warn = ::logWidgetSnapshotWarning).load() ?: return@withLock
            if (prior.stops.isEmpty()) return@withLock
            StoredSnapshotRefresh.refresh(applicationContext, prior, keys)
        }
    }

    private suspend fun widgetPlaced(): Boolean =
        GlanceAppWidgetManager(applicationContext).getGlanceIds(StopDashWidget::class.java).isNotEmpty()

    private suspend fun watchHasApp(): Boolean = try {
        Wearable.getCapabilityClient(applicationContext)
            .getCapability(WatchSyncContract.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
            .await()
            .nodes
            .isNotEmpty()
    } catch (e: ApiException) {
        // No Wearable API on this phone: no watch, not a failure worth a warning on every unlock.
        if (e.statusCode != CommonStatusCodes.API_NOT_CONNECTED) throw e
        false
    }
}
