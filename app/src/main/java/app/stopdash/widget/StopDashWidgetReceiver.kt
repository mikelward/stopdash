package app.stopdash.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        widgetKindRemoved(context)
    }
}

/**
 * The broadcast receiver behind the compact widget ([StopDashCompactWidget]), a second entry in the
 * launcher's widget picker. It shares every refresh and redraw with [StopDashWidgetReceiver].
 */
class StopDashCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StopDashCompactWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetPresence.set(true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        widgetKindRemoved(context)
    }
}

/**
 * The last widget of one kind removed: StopDash still has a widget while one of the other kind is
 * placed. Asking the host is a system call, so it is asked off the main thread the receiver runs on,
 * and the answer is kept only if nothing newer was set meanwhile ([WidgetPresence.setIfUnchanged]).
 */
private fun widgetKindRemoved(context: Context) {
    val app = context.applicationContext
    val since = WidgetPresence.generation()
    CoroutineScope(Dispatchers.IO).launch { presenceAfterRemoval(app, since) }
}

/** Whether a StopDash widget of either kind is still placed, kept unless something newer came since [since]. */
internal suspend fun presenceAfterRemoval(
    context: Context,
    since: Long,
    placed: suspend () -> Boolean = { placedWidgetIds(context).isNotEmpty() },
) {
    val any = try {
        placed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unknown: say none, as before there were two kinds; the app asks the host again on return.
        logWidgetSnapshotWarning("widget presence read failed: ${e::class.simpleName}")
        false
    }
    WidgetPresence.setIfUnchanged(any, since)
}

/**
 * The app widget IDs placed for StopDash, of both kinds, asked of the platform by each receiver's
 * component, whose name the manifest pins. Not Glance's `getGlanceIds` / `updateAll`: those find
 * IDs through a saved receiver -> widget-class-name map from an earlier run, and R8 renames
 * [StopDashWidget] in a release build, so a release that changes its name leaves the map pointing
 * at the old one — `getGlanceIds` then says no widget is placed and `updateAll` redraws nothing
 * until the receiver next hears from the host. A system call: run it off the main thread.
 */
internal fun placedWidgetIds(context: Context): IntArray =
    placedWidgetIds(context, StopDashWidgetReceiver::class.java) +
        placedWidgetIds(context, StopDashCompactWidgetReceiver::class.java)

/** The app widget IDs placed for one kind of StopDash widget, by its [receiver]'s component. */
internal fun placedWidgetIds(context: Context, receiver: Class<out GlanceAppWidgetReceiver>): IntArray =
    AppWidgetManager.getInstance(context)
        ?.getAppWidgetIds(ComponentName(context, receiver))
        ?: IntArray(0)
