package app.stopdash.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

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
        // Null on a device with no widget support.
        val ids = AppWidgetManager.getInstance(context)
            ?.getAppWidgetIds(ComponentName(context, StopDashWidgetReceiver::class.java))
        if (ids == null || ids.isEmpty()) return
        redrawWidgetNow(WorkManager.getInstance(context.applicationContext))
    }
}
