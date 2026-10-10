package app.stopdash.widget

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
