package app.stopdash.ui

import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long a press waits before its ripple shows: the platform's tap timeout, as a clickable row in a
 * scrolling list waits, so a touch that turns into a scroll shows none.
 */
internal const val PRESS_RIPPLE_DELAY_MILLIS = 100L

/**
 * Shows the press a clickable row shows, on a row that takes its tap and long press by
 * `detectTapGestures` instead: pass it as that detector's `onPress`, and draw [interactions] with
 * `Modifier.indication`. A row that answers a press with nothing to see looks as if the tap missed.
 * The ripple waits [PRESS_RIPPLE_DELAY_MILLIS]; a tap lifted sooner still flashes one, as a
 * clickable's does. A press cut short (a scroll, the row leaving) takes its ripple with it.
 */
internal suspend fun PressGestureScope.showPress(at: Offset, interactions: MutableInteractionSource) {
    var press: PressInteraction.Press? = null
    try {
        val released = coroutineScope {
            val shown = launch {
                delay(PRESS_RIPPLE_DELAY_MILLIS)
                val pressed = PressInteraction.Press(at)
                press = pressed
                interactions.emit(pressed)
            }
            tryAwaitRelease().also { shown.cancel() }
        }
        val pressed = press
        when {
            pressed != null -> interactions.emit(if (released) PressInteraction.Release(pressed) else PressInteraction.Cancel(pressed))
            released -> PressInteraction.Press(at).let {
                interactions.emit(it)
                interactions.emit(PressInteraction.Release(it))
            }
        }
    } catch (e: CancellationException) {
        // The detector stopped mid-press: no ripple stays drawn for a press nothing will end.
        press?.let { interactions.tryEmit(PressInteraction.Cancel(it)) }
        throw e
    }
}
