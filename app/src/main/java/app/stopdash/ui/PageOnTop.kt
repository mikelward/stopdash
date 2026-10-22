package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.CoroutineScope

/**
 * Whether the page composing here is the one the rider is on: on top of the app's pages, a window over it aside.
 * A page can be composed without being on top, drawn under the page a back gesture is taking away, for a gesture
 * the rider may still let go of (`dev-docs/navigation3.md`). What that page would start for the rider (a location
 * fix, a refresh owed on coming back, a report, a window or a snackbar) waits for this, so a glimpse of it starts
 * nothing. True where nothing provides it: a page that composes only while it's on top, as every page does today.
 */
internal val LocalPageOnTop = compositionLocalOf { true }

/**
 * [LaunchedEffect] of [keys] that runs [block] only while the page is on top ([LocalPageOnTop]), and again as it
 * comes back on top: a glimpse of the page starts nothing.
 */
@Composable
internal fun OnTopEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val onTop = LocalPageOnTop.current
    LaunchedEffect(onTop, *keys) { if (onTop) block() }
}

/**
 * [onStart] as the page is started and on top ([LocalPageOnTop]), and [onStop] as either ends: a
 * [LifecycleStartEffect] a glimpse of the page doesn't start.
 */
@Composable
internal fun StartedWhileOnTop(onStart: () -> Unit, onStop: () -> Unit) {
    val onTop = LocalPageOnTop.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    LifecycleStartEffect(onTop) {
        if (onTop) start()
        onStopOrDispose { if (onTop) stop() }
    }
}
