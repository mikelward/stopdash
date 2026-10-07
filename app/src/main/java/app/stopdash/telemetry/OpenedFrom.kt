package app.stopdash.telemetry

import android.content.Intent
import app.stopdash.GetOffSoonAlert
import app.stopdash.PlaceShortcuts
import app.stopdash.domain.UsageEvent

/**
 * Names what opened the app in its activity intent, for the [UsageEvent.Opened] that counts it: the
 * widget's taps put [OPENED_FROM_WIDGET] here, since its intent is otherwise a plain start.
 */
const val EXTRA_OPENED_FROM = "app.stopdash.extra.OPENED_FROM"

/** [EXTRA_OPENED_FROM]'s value on the widget's taps. */
const val OPENED_FROM_WIDGET = "widget"

/**
 * What opened the app with [intent]: the widget, a trip's notification, a place's launcher shortcut,
 * or the app's icon. Read before the activity takes its own extras off the intent. A start from
 * Recents re-delivers the intent the task began with, marks and all, so it's counted as none of them.
 */
fun openedFrom(intent: Intent?): UsageEvent.OpenedFrom = when {
    intent == null -> UsageEvent.OpenedFrom.OTHER
    intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 -> UsageEvent.OpenedFrom.OTHER
    intent.getStringExtra(EXTRA_OPENED_FROM) == OPENED_FROM_WIDGET -> UsageEvent.OpenedFrom.WIDGET
    intent.getBooleanExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, false) -> UsageEvent.OpenedFrom.NOTIFICATION
    intent.action == PlaceShortcuts.ACTION_ROUTE_TO_PLACE -> UsageEvent.OpenedFrom.SHORTCUT
    intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) -> UsageEvent.OpenedFrom.LAUNCHER
    else -> UsageEvent.OpenedFrom.OTHER
}
