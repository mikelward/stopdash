package app.stopdash.domain

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure font-size math (SPEC *Display size*): clamping, the pinch slop, the continuous
 * pinch step, and the percentage readout. No Compose host or device — that side is
 * `ui/FontSize.kt`.
 */
class FontSizeTest {

    @Test
    fun `clamp leaves an in-range scale untouched`() {
        assertEquals(1.0f, clampFontScale(1.0f), 0f)
        assertEquals(1.3f, clampFontScale(1.3f), 0f)
        assertEquals(MIN_FONT_SCALE, clampFontScale(MIN_FONT_SCALE), 0f)
        assertEquals(MAX_FONT_SCALE, clampFontScale(MAX_FONT_SCALE), 0f)
    }

    @Test
    fun `clamp pulls an out-of-range scale to the nearest end`() {
        assertEquals(MIN_FONT_SCALE, clampFontScale(0.1f), 0f)
        assertEquals(MAX_FONT_SCALE, clampFontScale(9f), 0f)
    }

    @Test
    fun `clamp falls back to the default for a non-finite scale`() {
        // A corrupt or hand-edited preference, or a degenerate gesture — never NaN/Infinity, which
        // would lay text out as nothing or size it to infinity.
        assertEquals(DEFAULT_FONT_SCALE, clampFontScale(Float.NaN), 0f)
        assertEquals(DEFAULT_FONT_SCALE, clampFontScale(Float.POSITIVE_INFINITY), 0f)
        assertEquals(DEFAULT_FONT_SCALE, clampFontScale(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `percent reads 100 at the system size and clamps first`() {
        assertEquals(100, fontScalePercent(1.0f))
        assertEquals(80, fontScalePercent(MIN_FONT_SCALE))
        assertEquals(160, fontScalePercent(MAX_FONT_SCALE))
        // Rounded to the nearest whole percent.
        assertEquals(123, fontScalePercent(1.234f))
        // Out of range is clamped before the readout, so the slider label never shows an
        // impossible size.
        assertEquals(160, fontScalePercent(5f))
        assertEquals(80, fontScalePercent(0f))
    }

    @Test
    fun `a step moves one whole percent from the percent shown`() {
        assertEquals(1.01f, stepFontScale(1.0f, 1), 0f)
        assertEquals(0.99f, stepFontScale(1.0f, -1), 0f)
        // A size between two percentages (a drag or a pinch left it there) steps from the one shown:
        // 1.153 reads 115%, so plus lands on 116%, not 116.3%.
        assertEquals(1.16f, stepFontScale(1.153f, 1), 0f)
        assertEquals(1.14f, stepFontScale(1.153f, -1), 0f)
        assertEquals(116, fontScalePercent(stepFontScale(1.153f, 1)))
    }

    @Test
    fun `a step stays in the offered range`() {
        assertEquals(MAX_FONT_SCALE, stepFontScale(MAX_FONT_SCALE, 1), 0f)
        assertEquals(MIN_FONT_SCALE, stepFontScale(MIN_FONT_SCALE, -1), 0f)
        assertEquals(MAX_FONT_SCALE, stepFontScale(1.595f, 1), 0f)
        assertEquals(MIN_FONT_SCALE, stepFontScale(0.805f, -1), 0f)
    }

    @Test
    fun `a step from a non-finite size starts at the default`() {
        assertEquals(DEFAULT_FONT_SCALE + 0.01f, stepFontScale(Float.NaN, 1), 1e-6f)
        assertEquals(DEFAULT_FONT_SCALE - 0.01f, stepFontScale(Float.POSITIVE_INFINITY, -1), 1e-6f)
    }

    @Test
    fun `slop is not passed until the fingers change separation by the slop distance`() {
        val slop = 24f
        assertFalse(pinchPassedSlop(startSpreadPx = 100f, spreadPx = 110f, slopPx = slop))
        assertFalse(pinchPassedSlop(startSpreadPx = 100f, spreadPx = 100f, slopPx = slop))
        // Passed once the change reaches the slop, spreading apart or…
        assertTrue(pinchPassedSlop(startSpreadPx = 100f, spreadPx = 124f, slopPx = slop))
        // …closing together (it is the absolute change).
        assertTrue(pinchPassedSlop(startSpreadPx = 100f, spreadPx = 76f, slopPx = slop))
    }

    @Test
    fun `slop is never passed on a non-finite input`() {
        assertFalse(pinchPassedSlop(Float.NaN, 200f, 24f))
        assertFalse(pinchPassedSlop(100f, Float.NaN, 24f))
        assertFalse(pinchPassedSlop(100f, 200f, Float.NaN))
    }

    @Test
    fun `a zoom step scales by the gain-amplified zoom`() {
        // zoom^gain, gain = 2: a 1.2x spread grows the running size by 1.44x.
        assertEquals(1.0f * 1.2f.pow(FONT_SCALE_PINCH_GAIN), fontScaleAfterZoom(1.0f, 1.2f), 1e-4f)
        // A zoom of exactly 1 leaves the size where it was.
        assertEquals(1.1f, fontScaleAfterZoom(1.1f, 1.0f), 0f)
        // Shrinking.
        assertTrue(fontScaleAfterZoom(1.0f, 0.9f) < 1.0f)
    }

    @Test
    fun `a zoom step is bounded by the overshoot, not the offered range`() {
        // Deliberately allowed past the offered range so a run-past-and-return lands where the
        // fingers asked, but bounded to the overshoot so the return trip stays short.
        assertEquals(MAX_PINCH_SCALE, fontScaleAfterZoom(MAX_FONT_SCALE, 100f), 1e-4f)
        assertEquals(MIN_PINCH_SCALE, fontScaleAfterZoom(MIN_FONT_SCALE, 0.001f), 1e-4f)
        assertTrue("overshoot exceeds the offered max", MAX_PINCH_SCALE > MAX_FONT_SCALE)
        assertTrue("overshoot undercuts the offered min", MIN_PINCH_SCALE < MIN_FONT_SCALE)
    }

    @Test
    fun `a degenerate or non-finite zoom keeps the size, and a non-finite scale resets it`() {
        // Two fingers on the same point report zoom <= 0 — keep the size rather than collapse it.
        assertEquals(1.2f, fontScaleAfterZoom(1.2f, 0f), 0f)
        assertEquals(1.2f, fontScaleAfterZoom(1.2f, -1f), 0f)
        assertEquals(1.2f, fontScaleAfterZoom(1.2f, Float.NaN), 0f)
        // A non-finite running size falls back to the default rather than propagating.
        assertEquals(DEFAULT_FONT_SCALE, fontScaleAfterZoom(Float.NaN, 1.1f), 0f)
    }

    @Test
    fun `a pinch out and back lands exactly where it started`() {
        // The gain keeps the round trip exact — amplifying each step and amplifying the whole
        // gesture are the same thing — as long as it stays inside the overshoot bound.
        val start = 1.0f
        val out = fontScaleAfterZoom(start, 1.3f)
        val back = fontScaleAfterZoom(out, 1f / 1.3f)
        assertEquals(start, back, 1e-4f)
    }

    @Test
    fun `the overshoot bounds are the offered range amplified by the gain`() {
        assertEquals(MAX_FONT_SCALE * FONT_SCALE_OVERSHOOT.pow(FONT_SCALE_PINCH_GAIN), MAX_PINCH_SCALE, 1e-4f)
        assertEquals(MIN_FONT_SCALE / FONT_SCALE_OVERSHOOT.pow(FONT_SCALE_PINCH_GAIN), MIN_PINCH_SCALE, 1e-4f)
    }

    @Test
    fun `the default settings start at the system size with pinch on`() {
        val defaults = FontSizeSettings()
        assertEquals(DEFAULT_FONT_SCALE, defaults.scale, 0f)
        assertTrue(defaults.pinchEnabled)
    }

    @Test
    fun `at the default size the list's headings and cards draw at the system's own size`() {
        // The default rose to 105% (maintainer, 2026-10-24); the dense board stays as it was at 100%.
        assertEquals(1.05f, DEFAULT_FONT_SCALE, 0f)
        assertEquals(1f, DEFAULT_FONT_SCALE * CARD_TEXT_SCALE, 1e-6f)
    }
}
