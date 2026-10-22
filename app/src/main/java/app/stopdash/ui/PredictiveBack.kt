package app.stopdash.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A back gesture under way over a page: how far the rider has swiped, 0 to 1, and from which edge, for
 * the page to follow ([followsBack]). One per page, shared by its Back handlers ([PredictiveBack]).
 */
@Stable
internal class BackFollow internal constructor(private val scope: CoroutineScope) {
    /** How far the page follows the gesture: it eases back to 0 if the gesture is canceled. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** The gesture came from the screen's right edge, so the page shifts left. */
    var fromRight by mutableStateOf(false)
        private set

    // Each gesture's own: an ease back from one canceled stops once another begins.
    private var gesture = 0

    internal fun track(event: BackEventCompat, first: Boolean) {
        if (first) {
            gesture++
            fromRight = event.swipeEdge == BackEventCompat.EDGE_RIGHT
        }
        progress = event.progress
    }

    /** The gesture went through: the page has gone, or stays where its Back did something else. */
    internal fun completed() {
        gesture++
        progress = 0f
    }

    /** The gesture was canceled: the page eases back into place. */
    internal fun canceled() {
        val mine = ++gesture
        scope.launch {
            animate(progress, 0f) { value, _ -> if (gesture == mine) progress = value }
        }
    }
}

@Composable
internal fun rememberBackFollow(): BackFollow {
    val scope = rememberCoroutineScope()
    return remember(scope) { BackFollow(scope) }
}

/**
 * A page's Back, as [androidx.activity.compose.BackHandler], whose gesture [follow] tracks for the page
 * to follow ([followsBack]). A Back with no gesture (a back arrow, three-button navigation) goes at once.
 */
@Composable
internal fun PredictiveBack(follow: BackFollow, enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        var followed = false
        try {
            events.collect { event ->
                follow.track(event, first = !followed)
                followed = true
            }
            currentOnBack()
            follow.completed()
        } catch (e: CancellationException) {
            // Canceled, or the page left mid-gesture: either way it isn't going back.
            if (followed) follow.canceled()
            throw e
        }
    }
}

/**
 * Material's full-screen predictive back (SPEC *Back*): the page shrinks to [BACK_MIN_SCALE], shifts
 * toward the edge swiped from and rounds its corners, over a [scrim] that deepens as it goes, so a swipe
 * reads as leaving and can still be taken back. The page under it isn't drawn, so nothing it does starts.
 */
internal fun Modifier.followsBack(follow: BackFollow, scrim: Color = Color.Black): Modifier = this
    .drawBehind {
        val progress = follow.progress
        if (progress > 0f) drawRect(scrim, alpha = BACK_SCRIM_ALPHA * progress)
    }
    .graphicsLayer {
        val progress = follow.progress
        if (progress > 0f) {
            val scale = lerp(1f, BACK_MIN_SCALE, progress)
            scaleX = scale
            scaleY = scale
            val room = (size.width * (1f - BACK_MIN_SCALE) / 2f - BACK_EDGE_GAP.toPx()).coerceAtLeast(0f)
            translationX = room * progress * if (follow.fromRight) -1f else 1f
            shape = RoundedCornerShape(BACK_CORNER * progress)
            clip = true
        }
    }

/**
 * A page whose Back ([onBack]) the back gesture is seen to follow ([followsBack]). It takes Back only where
 * nothing in [content] does: an inner page's handler, made later, goes first, as with BackHandler.
 */
@Composable
internal fun PredictiveBackPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val follow = rememberBackFollow()
    PredictiveBack(follow, enabled, onBack)
    Box(modifier.followsBack(follow)) { content() }
}

internal const val BACK_MIN_SCALE = 0.9f
private const val BACK_SCRIM_ALPHA = 0.32f
private val BACK_EDGE_GAP = 8.dp
private val BACK_CORNER = 24.dp
