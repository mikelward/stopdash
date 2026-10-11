package app.stopdash.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Redraws the widget when the device's clock is set (SPEC *Freshness*). Its countdowns and age are
 * static text drawn against the clock as it read then, and nothing else redraws it until its next
 * boundary, which may be minutes off (Codex, PR #371); a redraw draws them against the clock as it
 * reads now, from the stored snapshot, with no request. Only while a widget is placed, so a user
 * without one pays nothing for a clock setting.
 */
class WidgetClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED) return
        // Asking the host which widgets are placed is a system call per kind, so it runs off the main
        // thread the receiver is called on, with the broadcast held open until it's done (Codex on #759).
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clockChanged(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The widget keeps its old countdowns until its next scheduled redraw.
                logWidgetSnapshotWarning("widget clock-change redraw failed: ${e::class.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }
}

/** The clock was set: a redraw now while a widget is placed, the host asked on [io]. */
internal suspend fun clockChanged(context: Context, io: CoroutineDispatcher = Dispatchers.IO) {
    if (withContext(io) { placedWidgetIds(context).isEmpty() }) return
    redrawWidgetNow(WorkManager.getInstance(context))
}
