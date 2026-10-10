package app.stopdash.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The broadcast receiver the home-screen host binds to update the widget. Glance handles the
 * `APPWIDGET_UPDATE` plumbing; all this declares is which [GlanceAppWidget] backs it.
 */
class StopDashWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StopDashWidget()

    // The first widget placed and the last removed: what the near-me "Automatically update widget?"
    // card follows ([WidgetPresence]), known here as it happens rather than on the app's next return.
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetPresence.set(true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.set(false)
    }
}

/**
 * The app widget IDs placed for StopDash, asked of the platform by [StopDashWidgetReceiver]'s
 * component, whose name the manifest pins. Not Glance's `getGlanceIds` / `updateAll`: those find
 * IDs through a saved receiver -> widget-class-name map from an earlier run, and R8 renames
 * [StopDashWidget] in a release build, so a release that changes its name leaves the map pointing
 * at the old one — `getGlanceIds` then says no widget is placed and `updateAll` redraws nothing
 * until the receiver next hears from the host. A system call: run it off the main thread.
 */
internal fun placedWidgetIds(context: Context): IntArray =
    AppWidgetManager.getInstance(context)
        ?.getAppWidgetIds(ComponentName(context, StopDashWidgetReceiver::class.java))
        ?: IntArray(0)
