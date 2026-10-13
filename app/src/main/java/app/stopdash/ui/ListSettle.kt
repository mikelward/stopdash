package app.stopdash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds the [TapPause] while a list's tap targets move on their own (SPEC principle 4, hold still): a
 * refresh re-sorting the cards, a card or notice coming or going and pushing the rest, a banner above
 * pushing the whole list, or a card's rows trading places ([SlidesInPlace]). A tap aimed at one target
 * mustn't open whichever slid into its place. The user's own scroll moves everything too, but by their
 * hand, so it never pauses taps.
 *
 * Told by the slides themselves, not by where things are placed: the list's items slide by
 * [placementSpec], whose every start holds taps for that slide's length, and a card's rows by
 * [SlidesInPlace]. The list slides an item whenever a re-sort or a card coming or going moves it, by any
 * amount, all of them together when the first one goes (Codex, #818), and never for a scroll. Told where
 * items are placed too ([placed]), for what moves without sliding: an item appearing wholly on screen
 * among items already there, not by a scroll, and the list itself moving ([listPlaced]). Once taps are
 * held, any item placed somewhere new keeps them held: a slide interrupted by the next re-sort goes on
 * by the list's own spring, which the slide's start never hears of (Codex, #818). Taps resume once
 * nothing is sliding and nothing has moved for [STILL_FRAMES] frames, or after [TAP_PAUSE_FLOOR_MILLIS]
 * from the first move, whichever is later.
 *
 * Main thread only. Constant work per item placed, per slide and per frame, and frames are watched only
 * while the list is being placed.
 */
internal class ListSettle(
    private val pause: TapPause?,
    private val scope: CoroutineScope,
    private val scrolling: () -> Boolean,
) {
    // The items placed so far, where each was last placed: to tell one new to the list from one already
    // in it, and one still moving from one at rest.
    private val placed = HashMap<Any, Float>()

    // This frame's placements: whether an item already there was placed, or a new one wholly on screen.
    private var continuing = false
    private var appeared = false
    private var listY = Float.NaN
    private var movedThisFrame = false
    private var stillFrames = 0
    private var watching: Job? = null

    // Whether the list has moved within the last few frames, and how many slides are under way.
    private var listMoving = false
    private var sliding = 0
    private var hold: TapPause.Hold? = null

    /**
     * How the list's items slide to new places, over [CARD_MOVE_MILLIS] as a trip's cards do: each slide
     * the list starts holds taps until a slide as long, on the same frame clock, has run its course.
     */
    val placementSpec: FiniteAnimationSpec<IntOffset> = StartsWith(tween(CARD_MOVE_MILLIS.toInt())) {
        slideStarted()
        scope.launch { Animatable(0f).animateTo(1f, tween(CARD_MOVE_MILLIS.toInt())) }.invokeOnCompletion { slideEnded() }
    }

    /** The list itself was placed at [y] on screen: a banner above it coming or going moves every target. */
    fun listPlaced(y: Float) {
        if (!listY.isNaN() && y != listY) moved()
        listY = y
    }

    /** The item [key] was placed at [y] in a list [viewport] high, [height] high itself. */
    fun placed(key: Any, y: Float, height: Int, viewport: Int) {
        val last = placed.put(key, y)
        val known = last != null
        // Still on its way while taps are held: they wait for it, whatever is moving it.
        if (hold != null && known && last != y) {
            movedThisFrame = true
            listMoving = true
        }
        if (!scrolling()) {
            if (known) {
                continuing = true
            } else if (y >= 0f && y + height <= viewport) {
                // New, and wholly on screen: a scroll brings one in at an edge.
                appeared = true
            }
            // Something new among targets that were already there; a list drawn afresh is all new.
            if (appeared && continuing) moved()
        }
        watch()
    }

    /** A group outside a list changed size ([SlideGroup]): whatever is laid out after it has moved. */
    fun resized() = moved()

    /** The item [key] left the list. */
    fun gone(key: Any) {
        placed.remove(key)
    }

    /** An item or row has started sliding to its new place; taps wait until it [ends][slideEnded]. */
    fun slideStarted() {
        sliding++
        take()
    }

    fun slideEnded() {
        sliding--
        release()
    }

    /** The list left the screen: taps resume at once. */
    fun dispose() {
        watching?.cancel()
        hold?.cancel()
        hold = null
    }

    private fun moved() {
        movedThisFrame = true
        listMoving = true
        take()
        watch()
    }

    private fun take() {
        // Shown at once: the move is in the frame being placed now.
        if (hold == null) hold = pause?.hold()?.also { it.shown() }
    }

    private fun release() {
        if (listMoving || sliding > 0) return
        hold?.finish()
        hold = null
    }

    private fun watch() {
        if (watching?.isActive == true) return
        watching = scope.launch {
            // Timed on frames, not delay(): the frame clock is the one a test drives.
            do {
                withFrameMillis { }
            } while (frame())
        }
    }

    /** At the start of a frame, after the last frame's placement: whether to keep watching. */
    private fun frame(): Boolean {
        continuing = false
        appeared = false
        if (movedThisFrame) stillFrames = 0 else stillFrames++
        movedThisFrame = false
        if (stillFrames < STILL_FRAMES) return true
        listMoving = false
        release()
        return false
    }
}

/** How many frames a list holds still before taps resume: one placed without a move can fall between two that move. */
internal const val STILL_FRAMES = 2

/** [spec], calling [onStart] as each animation by it starts, which is when one vectorizes it. */
private class StartsWith(
    private val spec: FiniteAnimationSpec<IntOffset>,
    private val onStart: () -> Unit,
) : FiniteAnimationSpec<IntOffset> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<IntOffset, V>): VectorizedFiniteAnimationSpec<V> {
        onStart()
        return spec.vectorize(converter)
    }
}

/** The [ListSettle] of the list being drawn, for its cards' rows ([SlidesInPlace]); none outside one. */
internal val LocalListSettle = staticCompositionLocalOf<ListSettle?> { null }

@Composable
internal fun rememberListSettle(state: LazyListState): ListSettle {
    val pause = LocalTapPause.current
    val scope = rememberCoroutineScope()
    val settle = remember(pause, scope, state) { ListSettle(pause, scope) { state.isScrollInProgress } }
    DisposableEffect(settle) { onDispose { settle.dispose() } }
    return settle
}

/**
 * A [LazyColumn] whose items slide to their new places when it re-sorts, or when one comes or goes, as
 * a trip's cards do ([CARD_MOVE_MILLIS]), and whose taps wait while anything in it moves on its own
 * ([ListSettle]). Each item's [content] is drawn inside a box of its own that carries the slide.
 */
@Composable
internal fun SettlingLazyColumn(
    modifier: Modifier,
    state: LazyListState,
    contentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical,
    content: LazyListScope.() -> Unit,
) {
    val settle = rememberListSettle(state)
    CompositionLocalProvider(LocalListSettle provides settle) {
        LazyColumn(
            modifier = modifier.onPlaced { settle.listPlaced(it.positionInRoot().y) },
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
        ) {
            SettlingListScope(this, settle).content()
        }
    }
}

/** [list], with each item's content wrapped to slide and to say where it is ([Settling]). */
private class SettlingListScope(private val list: LazyListScope, private val settle: ListSettle) : LazyListScope by list {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val settle = settle
        list.item(key, contentType) { Settling(key, settle) { content() } }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val settle = settle
        list.items(count, key, contentType) { index -> Settling(key?.invoke(index), settle) { itemContent(index) } }
    }
}

@Composable
private fun LazyItemScope.Settling(key: Any?, settle: ListSettle, content: @Composable () -> Unit) {
    Box(
        Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = settle.placementSpec)
            .then(
                if (key == null) {
                    Modifier
                } else {
                    Modifier.onPlaced { item ->
                        settle.placed(key, item.positionInParent().y, item.size.height, item.parentLayoutCoordinates?.size?.height ?: 0)
                    }
                },
            ),
    ) {
        content()
    }
    if (key != null) DisposableEffect(settle, key) { onDispose { settle.gone(key) } }
}

/**
 * [content], whose rows ([SlidesInPlace]) share one tap-pause hold while any of them slides: the list's
 * where it's drawn in one ([SettlingLazyColumn]), else one of its own, so a card outside a list (a
 * trip's board) holds once however many of its rows move together (Codex, #818). Outside a list it
 * also holds taps when it changes size, a row coming or going, since what's laid out after it jumps
 * (Codex, #818); in a list, the list slides that.
 */
@Composable
internal fun SlideGroup(content: @Composable () -> Unit) {
    if (LocalListSettle.current != null) {
        content()
    } else {
        val pause = LocalTapPause.current
        val scope = rememberCoroutineScope()
        // Never placed in a list, so only its rows' slides ([ListSettle.slideStarted]) move it.
        val settle = remember(pause, scope) { ListSettle(pause, scope) { false } }
        DisposableEffect(settle) { onDispose { settle.dispose() } }
        val sized = remember { Sized() }
        Box(Modifier.onSizeChanged { if (sized.once) settle.resized() else sized.once = true }) {
            CompositionLocalProvider(LocalListSettle provides settle, content = content)
        }
    }
}

/** Whether a [SlideGroup] has been sized yet: its first size moves nothing. */
private class Sized {
    var once = false
}

/** Where a [SlidesInPlace] is laid out, and where it's drawn on its way there. */
private class Slide {
    // State, so a new place re-runs the offset: moving a row in its parent doesn't, on its own.
    var target by mutableFloatStateOf(0f)
    var aimedAt = Float.NaN
    var shown: Animatable<Float, *>? = null
}

/**
 * [content], sliding from where it was to where it's laid out when it moves within its parent, as a
 * card's rows do when a refresh has them trade places, over [CARD_MOVE_MILLIS], with taps held while
 * it slides through the [ListSettle] it's drawn in ([SlideGroup]). Drawn where it was until the slide
 * starts, so it never jumps there first.
 */
@Composable
internal fun SlidesInPlace(content: @Composable ColumnScope.() -> Unit) {
    val settle = LocalListSettle.current
    val scope = rememberCoroutineScope()
    val slide = remember { Slide() }
    Column(
        Modifier
            .onPlaced { slide.target = it.positionInParent().y }
            .offset {
                val target = slide.target
                val shown = slide.shown ?: Animatable(target).also {
                    slide.shown = it
                    slide.aimedAt = target
                }
                if (target != slide.aimedAt) {
                    slide.aimedAt = target
                    // Held from this frame, the first the row is laid out in its new place.
                    settle?.slideStarted()
                    scope.launch { shown.animateTo(target, tween(CARD_MOVE_MILLIS.toInt())) }.invokeOnCompletion { settle?.slideEnded() }
                }
                IntOffset(0, (shown.value - target).roundToInt())
            },
        content = content,
    )
}
