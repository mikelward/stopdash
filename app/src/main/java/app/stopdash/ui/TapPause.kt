package app.stopdash.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The least time taps stay paused after a loading screen's replacement first shows, however quickly
 * its animation ends: with animations off it ends at once, and a tap aimed at the loading screen still
 * mustn't open what replaced it.
 */
internal const val TAP_PAUSE_FLOOR_MILLIS = 200L

/**
 * The most time one handover keeps taps paused, from its replacement's first frame (or from the hold,
 * if that frame never comes), so an animation that never ends can't freeze them.
 */
internal const val TAP_PAUSE_FAILSAFE_MILLIS = 5_000L

/**
 * Ignores taps while a loading screen's handover animation runs ([RevealsAfterLoading]; SPEC
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
        private var shownAt: Long? = null
        private var finished = false

        /**
         * The animation's first frame, the first the replacement shows in: the floor runs from here,
         * not from the frame the handover was composed in, which a stall can hold back from the screen.
         */
        fun shown() {
            if (shownAt == null) shownAt = clock()
        }

        /** Its animation has ended: taps resume once [TAP_PAUSE_FLOOR_MILLIS] has passed since it was [shown]. */
        fun finish() {
            shown()
            finished = true
        }

        /** The handover was undone or left the screen: taps resume now, floor and all. */
        fun cancel() {
            holds.remove(this)
        }

        internal fun ignoresAt(now: Long): Boolean {
            // Before the replacement first shows, a tap can only be aimed at the loading screen; the
            // failsafe bounds a first frame that never comes.
            val shown = shownAt ?: return now - startedAt < TAP_PAUSE_FAILSAFE_MILLIS
            // From then on the animation and the floor, the failsafe timed from the same frame.
            if (now - shown >= TAP_PAUSE_FAILSAFE_MILLIS) return false
            return !finished || now - shown < TAP_PAUSE_FLOOR_MILLIS
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

/** Last state [RevealsAfterLoading] composed, written after each composition is applied. */
private class Composed<S>(var state: S)

/**
 * One screen's arrival: hidden at first and revealed by [progress] from 0 to 1 if it takes a loading
 * screen's place ([revealing]), fully shown otherwise. Holds the tap pause from the frame it is
 * remembered in, and lets go if it is forgotten first, when the screen changes again or leaves.
 */
private class Handover(private val revealing: Boolean, private val pause: TapPause?) : RememberObserver {
    val progress = Animatable(if (revealing) 0f else 1f)
    var hold: TapPause.Hold? = null
        private set

    override fun onRemembered() {
        if (revealing) hold = pause?.hold()
    }

    override fun onForgotten() {
        hold?.cancel()
    }

    override fun onAbandoned() = Unit
}

/**
 * [content] for [state], revealed row by row (SPEC *Update indicator*) when a loading screen gives
 * way to something else: the loading screen goes at once, and what replaces it comes in from the top
 * down, each line fading in a beat after the one above, and taps are ignored until that reveal has
 * finished ([TapPause]). A tap aimed at the loading screen opens nothing that took its place, and the
 * user sees why it did nothing. Every other change is instant, as before, and a return to loading
 * frees taps at once.
 *
 * [key] says which states are the same screen, so a state that changes without changing screen
 * updates it in place; it defaults to [loading], one screen for loading and one for the rest.
 */
@Composable
fun <S> RevealsAfterLoading(
    state: S,
    loading: (S) -> Boolean,
    modifier: Modifier = Modifier,
    key: (S) -> Any? = loading,
    content: @Composable (S) -> Unit,
) {
    val pause = LocalTapPause.current
    val composed = remember { Composed(state) }
    val screen = key(state)
    // A screen that takes a loading screen's place starts hidden, in the very frame it first draws;
    // any other starts fully shown.
    val handover = remember(screen) {
        val from = composed.state
        Handover(loading(from) && !loading(state) && key(from) != screen, pause)
    }
    SideEffect { composed.state = state }
    LaunchedEffect(handover) {
        if (handover.progress.value < 1f) {
            // Called on each of its frames, the first included even with animations off; the first starts the floor.
            handover.progress.animateTo(1f, tween(REVEAL_MILLIS, easing = LinearEasing)) { handover.hold?.shown() }
        }
        handover.hold?.finish()
    }
    Box(modifier.revealsTopDown { handover.progress.value }) {
        key(screen) { content(state) }
    }
}

/** How long the row-by-row reveal takes, the maintainer's pick (2026-10-09) from the demo. */
internal const val REVEAL_MILLIS = 300

// How much of the height one line takes to fade in, as the reveal's edge sweeps down: two thirds,
// so a line fades over 120 ms of the 300 and the next starts a beat behind it, as in the demo.
private const val REVEAL_BAND = 2f / 3f

/** Where the reveal's soft edge ends, as a fraction of the height, at [progress] from 0 to 1. */
private fun revealEdge(progress: Float) = progress * (1 + REVEAL_BAND)

/** How shown a line at [y] (a fraction of the height, 0 at the top) is at [progress]. */
internal fun revealedAt(progress: Float, y: Float): Float = ((revealEdge(progress) - y) / REVEAL_BAND).coerceIn(0f, 1f)

/**
 * Draws the content with everything below a soft edge hidden, the edge sweeping from above the top
 * at [progress] 0 to past the bottom at 1. Drawn into its own layer only while revealing, so the
 * mask has something to cut and the settled screen costs nothing extra.
 */
private fun Modifier.revealsTopDown(progress: () -> Float): Modifier =
    graphicsLayer {
        compositingStrategy = if (progress() < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithContent {
        drawContent()
        val shown = progress()
        if (shown < 1f) {
            // Fully shown above edge - band and hidden below edge, as [revealedAt] says.
            val edge = revealEdge(shown) * size.height
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = edge - REVEAL_BAND * size.height, endY = edge),
                blendMode = BlendMode.DstIn,
            )
        }
    }
