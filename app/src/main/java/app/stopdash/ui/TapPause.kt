package app.stopdash.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The least time taps stay paused after a loading screen gives way, however quickly its animation
 * ends: with animations off it ends at once, and a tap aimed at the loading screen still mustn't open
 * what replaced it.
 */
internal const val TAP_PAUSE_FLOOR_MILLIS = 200L

/** The most time one handover keeps taps paused, so an animation that never ends can't freeze them. */
internal const val TAP_PAUSE_FAILSAFE_MILLIS = 5_000L

/**
 * Ignores taps while a loading screen's handover animation runs ([FadesThroughFromLoading]; SPEC
 * principle 4, hold still). A finger already on its way to the loading screen's Update available
 * button, or tapping it impatiently, would otherwise land on whichever departure row just took that
 * spot. No tap during the animation can have been aimed at the new content: it is still coming in.
 *
 * Each handover takes its own [Hold] when it starts and lets go when its animation finishes, so the
 * pause lasts exactly as long as what the user sees, slowed or stalled, and a handover undone partway
 * frees taps at once without ending another's. Main thread only. [clock] is the monotonic clock in
 * milliseconds, injectable for a test.
 */
@Stable
class TapPause(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private val holds = ArrayList<Hold>(2)

    /** A handover is starting: taps are ignored until its animation [finishes][Hold.finish]. */
    fun hold(): Hold = Hold(clock()).also { holds += it }

    /** Whether a tap landing now is ignored. Constant work: a handover or two at most is ever held. */
    fun ignoresTaps(): Boolean {
        val now = clock()
        holds.removeAll { !it.ignoresAt(now) }
        return holds.isNotEmpty()
    }

    /** One handover's claim on the pause. */
    inner class Hold internal constructor(private val startedAt: Long) {
        private var finished = false

        /** Its animation has ended: taps resume, once [TAP_PAUSE_FLOOR_MILLIS] has passed. */
        fun finish() {
            finished = true
        }

        /** The handover was undone or left the screen: taps resume now, floor and all. */
        fun cancel() {
            holds.remove(this)
        }

        internal fun ignoresAt(now: Long): Boolean {
            val held = now - startedAt
            return held < TAP_PAUSE_FAILSAFE_MILLIS && (!finished || held < TAP_PAUSE_FLOOR_MILLIS)
        }
    }
}

/** The app's [TapPause], from [app.stopdash.StopDashAppRoot]; none in a test that composes a screen alone. */
val LocalTapPause = staticCompositionLocalOf<TapPause?> { null }

/**
 * Drops each touch that starts while [pause] is ignoring taps: the down is consumed before anything
 * beneath sees it, so no tap, press or long press starts. Constant work per event, on the main thread.
 */
internal fun Modifier.ignoresPausedTaps(pause: TapPause): Modifier = pointerInput(pause) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                if (change.changedToDown() && pause.ignoresTaps()) change.consume()
            }
        }
    }
}

/** Last state [FadesThroughFromLoading] composed, written after each composition is applied. */
private class Composed<S>(var state: S)

/**
 * One screen's arrival: [from] the loading screen it replaces, animated by [progress] from 0 to 1, or
 * no handover at all ([from] null, [progress] already 1). Holds the tap pause from the frame it is
 * remembered in, and lets go if it is forgotten first, when the screen changes again or leaves.
 */
private class Handover<S>(val from: S?, private val pause: TapPause?) : RememberObserver {
    val progress = Animatable(if (from != null) 0f else 1f)
    var hold: TapPause.Hold? = null
        private set

    override fun onRemembered() {
        if (from != null) hold = pause?.hold()
    }

    override fun onForgotten() {
        hold?.cancel()
    }

    override fun onAbandoned() = Unit
}

/**
 * [content] for [state], fading through (SPEC *Update indicator*) when a loading screen gives way to
 * something else: the loading screen fades out, then what replaces it fades in while growing from
 * 92%, and taps are ignored until that fade has finished ([TapPause]). A tap aimed at the loading
 * screen opens nothing that took its place, and the user sees why it did nothing. Every other change
 * is instant, as before, and a return to loading frees taps at once.
 *
 * [key] says which states are the same screen, so a state that changes without changing screen
 * updates it in place; it defaults to [loading], one screen for loading and one for the rest.
 */
@Composable
fun <S> FadesThroughFromLoading(
    state: S,
    loading: (S) -> Boolean,
    modifier: Modifier = Modifier,
    key: (S) -> Any? = loading,
    content: @Composable (S) -> Unit,
) {
    val pause = LocalTapPause.current
    val composed = remember { Composed(state) }
    val screen = key(state)
    val handover = remember(screen) {
        val from = composed.state
        Handover(from.takeIf { loading(it) && !loading(state) && key(it) != screen }, pause)
    }
    SideEffect { composed.state = state }
    LaunchedEffect(handover) {
        if (handover.progress.value < 1f) handover.progress.animateTo(1f, tween(FADE_THROUGH_MILLIS, easing = LinearEasing))
        handover.hold?.finish()
    }
    // The loading screen stays, fading out, for the first part of the fade; its composition carries on
    // under its own key rather than starting over.
    val outgoing by remember(handover) {
        derivedStateOf { handover.from?.takeIf { handover.progress.value < FADE_OUT_FRACTION } }
    }
    Box(modifier) {
        for (shown in listOfNotNull(outgoing, state)) {
            val shownKey = key(shown)
            key(shownKey) {
                val layer = if (shownKey == screen) {
                    Modifier.graphicsLayer {
                        val incoming = fadeThroughIn(handover.progress.value)
                        alpha = incoming
                        scaleX = FADE_THROUGH_FROM_SCALE + (1 - FADE_THROUGH_FROM_SCALE) * incoming
                        scaleY = scaleX
                    }
                } else {
                    Modifier.graphicsLayer { alpha = fadeThroughOut(handover.progress.value) }
                }
                Box(layer) { content(shown) }
            }
        }
    }
}

// Material's fade through, its 300 ms squeezed into 200: the outgoing screen fades out over the first
// 60 ms, then the incoming one fades in and grows from 92% over the rest.
internal const val FADE_THROUGH_MILLIS = 200
private const val FADE_OUT_FRACTION = 0.3f
private const val FADE_THROUGH_FROM_SCALE = 0.92f

/** How opaque the outgoing loading screen is at [progress] through the fade. */
internal fun fadeThroughOut(progress: Float): Float =
    1f - FastOutLinearInEasing.transform((progress / FADE_OUT_FRACTION).coerceIn(0f, 1f))

/** How far in (opacity, and the way from 92% to full size) the incoming screen is at [progress]. */
internal fun fadeThroughIn(progress: Float): Float =
    LinearOutSlowInEasing.transform(((progress - FADE_OUT_FRACTION) / (1f - FADE_OUT_FRACTION)).coerceIn(0f, 1f))
