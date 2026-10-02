package app.stopdash.ui

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.stopdash.domain.AppSettings
import app.stopdash.domain.FONT_SCALE_PINCH_SLOP_DP
import app.stopdash.domain.FontSizeSettings
import app.stopdash.domain.clampFontScale
import app.stopdash.domain.fontScaleAfterZoom
import app.stopdash.domain.pinchPassedSlop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The text size in force right now, for every screen in the process, warmed from and persisted to
 * [AppSettings] (SPEC *Display size*).
 *
 * Held here rather than on a screen because a pinch resizes the *app*, not the page it happened
 * on: every screen reads this and the theme sizes from it. Compose state, so a pinch, a slider, or
 * the warm read landing recomposes whichever screen is on show without anyone polling.
 *
 * Nothing about the user, the place, or the time is written here — one number and one boolean
 * about how the app draws itself.
 */
internal object FontSizeSetting {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settings: AppSettings = AppSettings.NONE
    private var collectJob: Job? = null

    /**
     * Persistence runs on one coroutine draining this queue in order (started by [warm]), so two
     * quick changes — releasing the slider then tapping Reset — can't reach DataStore on
     * independent coroutines and land out of order, leaving the earlier value written last and
     * restored after process death. FIFO and unbounded: every user action is written, newest last.
     */
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private var writeJob: Job? = null

    private sealed interface Write {
        data class Scale(val value: Float) : Write
        data class Pinch(val value: Boolean) : Write
    }

    /**
     * What the app is drawn at. Starts at the defaults rather than null: unlike a switch, there is
     * no honest "not read yet" way to draw text, so the first frame is the system's own size and
     * [warm] corrects it if the user chose another. That correction is a resize of text already on
     * screen, which is why it is warmed at startup rather than read when a screen opens.
     */
    var current: FontSizeSettings by mutableStateOf(FontSizeSettings())
        private set

    /**
     * Begins reading the stored size into [current], off the main thread, and keeps it live for
     * later writes. Idempotent — a second call is ignored — so it can be called from
     * `MainActivity.onCreate` without stacking collectors. Narrows the first-frame window rather
     * than closing it: a screen composed in the very first instant can still draw at the default
     * and resize a frame later, which is cheaper than holding the frame back on a disk read.
     */
    fun warm(appSettings: AppSettings) {
        settings = appSettings
        if (writeJob == null) {
            writeJob = scope.launch {
                // One consumer, so writes land in send order — the queue is what makes the newest
                // action win. A write buffered before warm (unlikely — the UI can't resize before
                // the activity warms this) is drained here once settings is set above.
                for (write in writes) {
                    try {
                        when (write) {
                            is Write.Scale -> settings.setFontScale(write.value)
                            is Write.Pinch -> settings.setPinchEnabled(write.value)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best-effort: the value is already applied in memory, so a failed write
                        // leaves the app as chosen until a restart re-reads the store. Sanitized
                        // (SPEC *Privacy* / *Error handling*) — no value, just the failure.
                        Log.w("StopDash.FontSize", "font setting write failed: ${e::class.simpleName}")
                    }
                }
            }
        }
        if (collectJob != null) return
        collectJob = scope.launch {
            appSettings.fontSize().collect { current = it }
        }
    }

    /** The size a drag or a pinch settled on: shown at once, persisted in order in the background. */
    fun setScale(scale: Float) {
        val clamped = clampFontScale(scale)
        current = current.copy(scale = clamped)
        writes.trySend(Write.Scale(clamped))
    }

    /** The "Pinch to resize text" switch was set to [enabled]. */
    fun setPinchEnabled(enabled: Boolean) {
        current = current.copy(pinchEnabled = enabled)
        writes.trySend(Write.Pinch(enabled))
    }
}

/**
 * The text size for this composition, wired to [FontSizeSetting].
 *
 * Seeded from [FontSizeSetting.current] — the warmed in-memory value, never a blocking disk read —
 * so the first frame is already the user's size on every screen.
 */
@Composable
internal fun rememberFontSizeState(): FontSizeState {
    val persisted = FontSizeSetting.current
    val state = remember {
        FontSizeState(
            initial = persisted,
            onScaleSettled = { FontSizeSetting.setScale(it) },
            onPinchEnabledChange = { FontSizeSetting.setPinchEnabled(it) },
        )
    }
    LaunchedEffect(state, persisted) { state.onPersisted(persisted) }
    return state
}

/**
 * The text size on screen right now, and how to change it (SPEC *Display size*).
 *
 * Held for the life of a composition so a drag or a pinch resizes the whole app as it happens,
 * with exactly one write when it ends — move-it-live, persist-on-release. A value arriving from
 * the store while the fingers are down is held, not applied, so nothing snaps the text out from
 * under them.
 */
@Stable
class FontSizeState internal constructor(
    initial: FontSizeSettings,
    private val onScaleSettled: (Float) -> Unit,
    private val onPinchEnabledChange: (Boolean) -> Unit,
) {
    /** What every sp text is multiplied by, including mid-gesture. */
    var scale: Float by mutableFloatStateOf(clampFontScale(initial.scale))
        private set

    /** Whether a pinch may change [scale] — the Settings switch. */
    var pinchEnabled: Boolean by mutableStateOf(initial.pinchEnabled)
        private set

    /** A gesture or drag is in flight, so nothing from the store moves the text under the fingers. */
    private var moving = false

    /** A value that arrived from the store while the fingers were down, held rather than dropped. */
    private var deferred: FontSizeSettings? = null

    /** Whether the size has actually moved since the last write, so an empty gesture persists nothing. */
    private var resized = false

    /**
     * A gesture has begun, before it has moved anything. A pinch owns the size from the frame it
     * crosses the slop, and that frame resizes nothing — so marking it moving now stops a value
     * arriving from the store in that window from being applied and then overwritten on release.
     */
    fun startGesture() {
        moving = true
    }

    /** Show [scale] without persisting it: a slider drag, or a pinch in flight. */
    fun preview(scale: Float) {
        moving = true
        resized = true
        this.scale = clampFontScale(scale)
    }

    /** The drag or the pinch ended here: show it and persist where it landed. */
    fun commit(scale: Float) {
        val settled = clampFontScale(scale)
        val held = deferred
        moving = false
        deferred = null
        if (!resized) {
            // Nothing moved the size since the last write, so this gesture has no size of its own:
            // anything held from the store lands whole.
            held?.let {
                this.scale = clampFontScale(it.scale)
                pinchEnabled = it.pinchEnabled
            }
            return
        }
        resized = false
        // Anything that arrived while the fingers were down lands now — except the size, which is
        // what the gesture was about: the user's own value wins, and the write below makes it true.
        held?.let { pinchEnabled = it.pinchEnabled }
        this.scale = settled
        onScaleSettled(settled)
    }

    /** The switch was tapped. */
    fun choosePinch(enabled: Boolean) {
        pinchEnabled = enabled
        onPinchEnabledChange(enabled)
    }

    /**
     * A discrete size choice — the Settings "Reset" button, not a drag: show it and persist it at
     * once. Unlike [commit] there is no gesture to have moved anything, so it always writes.
     */
    fun chooseScale(scale: Float) {
        val clamped = clampFontScale(scale)
        this.scale = clamped
        resized = false
        onScaleSettled(clamped)
    }

    /**
     * The stored settings changed under us — the warm read landing, or the write a [commit] just
     * made. Held, not applied, while a gesture is in flight (applying one mid-drag would snap the
     * text and the slider thumb away from the fingers), and held rather than dropped because it
     * carries the pinch switch as well as the size.
     */
    fun onPersisted(settings: FontSizeSettings) {
        if (moving) {
            deferred = settings
            return
        }
        scale = clampFontScale(settings.scale)
        pinchEnabled = settings.pinchEnabled
    }
}

/**
 * A two-finger pinch anywhere in LDN Go resizes its text (SPEC *Display size*), tracking the
 * fingers as they move and persisting where they stopped once they lift.
 *
 * Handled in the **Initial** pointer pass, and only once a second finger is down: single-finger
 * taps, drags, and scrolls reach the screen untouched, while a pinch that starts inside a
 * scrolling list resizes text instead of being eaten by the scroll — the departures list scrolls,
 * so a Main-pass gesture would often lose. Events are consumed only from the frame the gesture
 * becomes a pinch, so a two-finger scroll that never spreads still scrolls the page.
 */
internal fun Modifier.pinchFontSize(
    enabled: () -> Boolean,
    scale: () -> Float,
    onStart: () -> Unit,
    onPreview: (Float) -> Unit,
    onSettled: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    val slopPx = FONT_SCALE_PINCH_SLOP_DP.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        // The gesture's running size, kept unclamped: what is shown and stored is clamped, but the
        // gesture remembers an overshoot so pinching back returns where the fingers say.
        var pinchScale = 0f
        // The separation the slop is measured against, re-taken whenever the set of fingers changes.
        var startSpread = 0f
        var pointerCount = 1
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                // The switch is read per event, so turning pinch off applies to the next pinch.
                if (pressed >= 2 && enabled()) {
                    val spread = event.pressedSpread()
                    if (pressed != pointerCount || spread <= 0f) {
                        // A frame where a finger arrived or left compares this centroid against one
                        // measured from a different set of pointers — a meaningless jump — and two
                        // fingers on one point have no separation. Only re-baseline the gesture.
                        startSpread = spread
                    } else if (!pinching) {
                        if (pinchPassedSlop(startSpread, spread, slopPx)) {
                            pinching = true
                            pinchScale = scale()
                            // Nothing resizes on this frame: the movement that crossed the slop is
                            // what the slop spent, so the size starts moving from here. The gesture
                            // is announced now because it owns the size from here.
                            onStart()
                        }
                    } else {
                        // Continuous — the text lands wherever the fingers put it and the slider can
                        // return to exactly the same size.
                        pinchScale = fontScaleAfterZoom(pinchScale, event.calculateZoom())
                        onPreview(clampFontScale(pinchScale))
                    }
                    // Only once it is a pinch, so a two-finger scroll below the slop still reaches
                    // the list underneath.
                    if (pinching) event.changes.forEach { it.consume() }
                }
                pointerCount = pressed
            }
        } finally {
            // Clamped like every preview, so releasing leaves the size where the last frame showed
            // it rather than storing an overshoot the screen never drew. In a `finally` so a host
            // that goes away mid-pinch still ends the gesture; settling is the right end for a
            // canceled pinch either way, since the user watched the text resize.
            if (pinching) onSettled(clampFontScale(pinchScale))
        }
    }
}

/**
 * The [FontSizeState] in force, so a window opened on its own (a popup menu, a dialog) can
 * re-establish the size and the gesture — neither the scaled density nor a pointer handler crosses
 * a window boundary on its own. Null outside [app.stopdash.ui.theme.StopDashTheme].
 */
internal val LocalFontSizeState = staticCompositionLocalOf<FontSizeState?> { null }

/**
 * The density before the text-size multiplier, for a control whose own gesture would otherwise be
 * hosted under a density its drag keeps changing. Compose resets a pointer-input handler when the
 * density under it changes, so a size slider — which changes exactly that every frame — dies on
 * its first resizing movement unless it hosts its input at this unscaled density. Null outside the
 * theme.
 */
internal val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

/**
 * Hosts [content] at the unscaled density, for a control that resizes text while being dragged.
 * Only `fontScale` differs between the two densities, so a control with no text of its own — a
 * slider's dp track and thumb — looks identical either way.
 */
@Composable
internal fun StableInputDensity(content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current
    if (base == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalDensity provides base, content = content)
}

/**
 * Re-provides the chosen text size inside a window that does not inherit it (a popup or dialog).
 * Reads the window's own density as its base — the unscaled one — so the multiplier is applied
 * exactly once wherever this is used. No-op outside the theme.
 */
@Composable
internal fun FontSizeWindow(content: @Composable () -> Unit) {
    val state = LocalFontSizeState.current
    val base = LocalDensity.current
    if (state == null) {
        content()
        return
    }
    val scaled = remember(base, state.scale) { Density(base.density, base.fontScale * state.scale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * The pinch, for a surface inside a window the app opens. "Two fingers anywhere in LDN Go" is
 * what the setting promises, so a window that opens its own composition (a popup, a dialog) hosts
 * its own gesture — a pointer handler no more crosses the boundary than a density does. Apply it to
 * the surface *containing* the text so one pinch spans the whole window. No-op outside the theme;
 * never nest two of these (both would apply the same zoom twice).
 */
@Composable
internal fun Modifier.pinchFontSizeHost(): Modifier {
    val state = LocalFontSizeState.current ?: return this
    return pinchFontSize(
        enabled = { state.pinchEnabled },
        scale = { state.scale },
        onStart = state::startGesture,
        onPreview = state::preview,
        onSettled = state::commit,
    )
}

/**
 * [FontSizeWindow] plus [pinchFontSizeHost], for a whole surface in its own window. The gesture is
 * hosted outside the scaled density, because a density change restarts a pointer handler — so a
 * gesture hosted under the size it is changing would die on its own first resize.
 */
@Composable
internal fun FontSizePinchWindow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.pinchFontSizeHost()) {
        FontSizeWindow(content)
    }
}

/**
 * How far apart the fingers currently are, in pixels — twice the mean distance from their
 * centroid, which for the two-finger case is the distance between them.
 *
 * Measured over the pointers pressed **now**, unlike `calculateCentroidSize`, which counts only
 * pointers also pressed on the previous frame and so reports zero on the very frame a second
 * finger lands — exactly where the gesture takes its baseline.
 */
private fun PointerEvent.pressedSpread(): Float {
    var count = 0
    var sum = Offset.Zero
    changes.forEach { if (it.pressed) { sum += it.position; count++ } }
    if (count < 2) return 0f
    val centroid = sum / count.toFloat()
    var distance = 0f
    changes.forEach { if (it.pressed) distance += (it.position - centroid).getDistance() }
    val spread = distance / count * 2f
    return if (spread.isFinite()) spread else 0f
}
