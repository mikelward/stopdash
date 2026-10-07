package app.stopdash.telemetry

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.stopdash.domain.UsageEvent

/**
 * Counts [screen] as shown ([UsageEvent.ScreenView]) each time it comes up: as it's composed, as it
 * changes to another, and again each time the app returns to the front on it. Only a hand-off to
 * [UsageEvents], so nothing here waits on the SDK.
 *
 * A screen reported again before the app has left the front, with no other between, is the same visit
 * ([ScreenReports]): a station's page reported while it loads isn't counted again once its list shows.
 */
@Composable
fun ReportScreen(screen: UsageEvent.Screen) {
    val activity = LocalActivity.current
    LifecycleResumeEffect(screen) {
        ScreenReports.shown(screen)
        // Left the front: the next screen up counts afresh, the same one included. Merely leaving
        // composition (the page that takes over from a placeholder), or the activity being made again
        // for a rotation, keeps the last one.
        onPauseOrDispose {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity?.isChangingConfigurations != true) {
                ScreenReports.left()
            }
        }
    }
}

/** The screen last counted while the app has stayed at the front, so one visit counts once. */
internal object ScreenReports {
    @Volatile
    private var last: UsageEvent.Screen? = null

    fun shown(screen: UsageEvent.Screen) {
        if (screen == last) return
        last = screen
        UsageEvents.log(UsageEvent.ScreenView(screen))
    }

    fun left() {
        last = null
    }
}
