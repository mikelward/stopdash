package app.stopdash.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How big StopDash's own text is (SPEC *Display size*): a factor applied on top of whatever the
 * system's font scale already is, so an accessibility setting made in Android is respected and
 * this only says how much bigger or smaller StopDash should be than everything else.
 *
 * Pure, so the clamping and the pinch math are testable without a Compose host or a device; the
 * Android side is `ui/FontSize.kt` in the app.
 */

/** Smallest offered size (80% of the system's). */
const val MIN_FONT_SCALE = 0.8f

/**
 * Largest offered size (160%). Large enough to help low-vision readers on a glance surface, and
 * bounded so a departure card's one-line countdown still lays out beside its pill at the top of
 * the range (SPEC D8).
 */
const val MAX_FONT_SCALE = 1.6f

/**
 * The size StopDash starts at: the system's own (100%). StopDash respects the platform font-size
 * setting by default and only departs from it once the user pinches or drags to their own size,
 * which is then stored like any other choice. Reversible — one constant. Only the *starting*
 * point; anything already stored is read back unchanged.
 */
const val DEFAULT_FONT_SCALE = 1.0f

/** The settings that decide how StopDash's text is sized, read as one value. */
data class FontSizeSettings(
    val scale: Float = DEFAULT_FONT_SCALE,
    /** Whether a two-finger pinch anywhere in StopDash adjusts [scale]. */
    val pinchEnabled: Boolean = true,
)

/**
 * [scale] brought into the offered range. A non-finite value — a corrupt or hand-edited
 * preference, a degenerate gesture — falls back to the default rather than sizing text to infinity
 * (or to NaN, which lays out as nothing at all).
 */
@MainSafe
fun clampFontScale(scale: Float): Float {
    if (!scale.isFinite()) return DEFAULT_FONT_SCALE
    return scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
}

/**
 * How far the fingers must change their separation before a pinch resizes anything, in dp.
 *
 * A pinch that resizes from the first pixel of movement fires on gestures nobody meant — a
 * two-finger scroll, a phone picked up by its screen. Well above the platform's ~8dp touch slop
 * for that reason, and small enough that anyone deliberately trying a pinch crosses it in the
 * first moment of the gesture, which is what keeps the feature discoverable. Measured on the
 * separation between the fingers rather than on how far either one traveled, so a two-finger drag
 * — both fingers moving together, separation unchanged — never crosses it however far it goes.
 */
const val FONT_SCALE_PINCH_SLOP_DP = 24f

/**
 * How much of the offered range one step of finger movement covers, as an exponent on the
 * gesture's zoom: 1 would track the fingers exactly.
 *
 * The whole range is only 2x wide (80%–160%), so tracking the fingers 1:1 makes the gesture feel
 * like nothing is happening — crossing the range takes a spread the width of the screen. At 2 a
 * 1.4x spread covers the range end to end, which reads as deliberate while still landing where the
 * user meant.
 */
const val FONT_SCALE_PINCH_GAIN = 2f

/**
 * How far past each end a pinch's **fingers** may travel and still be remembered; see
 * [fontScaleAfterZoom]. Enough to absorb an ordinary overshoot, small enough that a wild spread
 * can't bank one the user then has to pinch all the way back through. Expressed as finger movement
 * rather than as font size so that [FONT_SCALE_PINCH_GAIN] changes how fast the text resizes
 * without also changing how forgiving the gesture is.
 */
const val FONT_SCALE_OVERSHOOT = 1.5f

/** The running size a pinch may reach past each end — the overshoot, amplified like any other step. */
val MAX_PINCH_SCALE: Float = MAX_FONT_SCALE * FONT_SCALE_OVERSHOOT.pow(FONT_SCALE_PINCH_GAIN)
val MIN_PINCH_SCALE: Float = MIN_FONT_SCALE / FONT_SCALE_OVERSHOOT.pow(FONT_SCALE_PINCH_GAIN)

/**
 * Whether the fingers have moved far enough apart — or close enough together — for this to be a
 * pinch rather than an accident.
 *
 * [spreadPx] and [startSpreadPx] are the distance between the two fingers now and when they
 * landed; [slopPx] is [FONT_SCALE_PINCH_SLOP_DP] in this screen's pixels. A gesture that has not
 * passed it changes nothing at all, and the caller re-baselines when it does, so the slop is spent
 * rather than applied in one jump.
 */
fun pinchPassedSlop(startSpreadPx: Float, spreadPx: Float, slopPx: Float): Boolean {
    if (!startSpreadPx.isFinite() || !spreadPx.isFinite() || !slopPx.isFinite()) return false
    return abs(spreadPx - startSpreadPx) >= slopPx
}

/**
 * A pinch's running size after one step of the gesture: where it was, times the step's zoom
 * amplified by [FONT_SCALE_PINCH_GAIN].
 *
 * **Continuous** — every step lands wherever the fingers put it, with no steps to snap to. The
 * size the pinch leaves behind is a size the slider can return to exactly, because the slider is
 * continuous too.
 *
 * Deliberately NOT clamped to the offered range: the caller clamps what it shows and stores.
 * Clamping here would make the clamped value the origin of the next step, so a pinch that ran past
 * an end and came back would land somewhere the fingers never asked for — spread past 160%, bring
 * the fingers back to where they started, and the size would arrive at 80% instead of 100%. The
 * overshoot is bounded rather than unbounded so the return trip stays short. The gain keeps that
 * round trip exact, because amplifying each step and amplifying the whole gesture are the same
 * thing: `(z1z2)^n == z1^n * z2^n`.
 *
 * A zoom of zero or less is what a degenerate gesture reports — two fingers landing on the same
 * point — and would collapse the text; keep the size as is.
 */
fun fontScaleAfterZoom(scale: Float, zoom: Float): Float {
    if (!scale.isFinite()) return DEFAULT_FONT_SCALE
    if (!zoom.isFinite() || zoom <= 0f) return scale
    return (scale * zoom.pow(FONT_SCALE_PINCH_GAIN)).coerceIn(MIN_PINCH_SCALE, MAX_PINCH_SCALE)
}

/** The percentage shown beside the slider — 100 is the system's own size. */
fun fontScalePercent(scale: Float): Int = (clampFontScale(scale) * 100).roundToInt()
