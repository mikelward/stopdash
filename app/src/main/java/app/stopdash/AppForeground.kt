package app.stopdash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import app.stopdash.domain.InFront
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether StopDash is on screen, and whether Android counts it in front: what a near-me fix waits for
 * ([InFront], SPEC *Finding stops*). On screen is an activity of the app started, told by the activity
 * callbacks [install] registers; in front is the process as Android ranks it, which is what location
 * allowed only while in use is given to, and can lag the activity just after an unlock.
 */
internal object AppForeground {
    private val _onScreen = MutableStateFlow(false)

    /** Whether an activity of the app is started, so on screen. */
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    // Started activities, counted on the main thread where the callbacks come. Started rather than
    // resumed: one hands over to the next without a gap, and a screen's first locate, at its start,
    // doesn't wait for its resume.
    private var started = 0

    /** Starts telling [onScreen] for [app], from [StopdashApp] as the process starts, with nothing resumed yet. */
    fun install(app: Application) {
        started = 0
        _onScreen.value = false
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    @VisibleForTesting
    internal val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
            _onScreen.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            _onScreen.value = started > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * Whether Android counts the app in front: a process the user can see, which location allowed while
     * in use is given to. A foreground service counts too, so [InFront] asks this only with the app on
     * screen. Asks Android, so off the main thread. If it can't say, the app counts as in front, so a fix
     * is asked for as it was before this check.
     */
    @WorkerThread
    fun inFront(): Boolean = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (e: RuntimeException) {
        logLocationWarning("in-front check failed: ${e::class.simpleName}")
        true
    }

    /** [InFront.await] over this process: returns once the app is in front, or has settled on screen. */
    suspend fun await() = InFront.await(::inFront, onScreen, ::logLocationWarning)
}
