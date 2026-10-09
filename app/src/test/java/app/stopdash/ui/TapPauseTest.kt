package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapPauseTest {
    private var now = 1_000L
    private val pause = TapPause { now }

    @Test
    fun `ignores no taps until a handover holds them`() {
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `a hold ignores taps until its animation finishes, however long that takes`() {
        val hold = pause.hold()
        now += TAP_PAUSE_FAILSAFE_MILLIS - 1
        assertTrue(pause.ignoresTaps())
        hold.finish()
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `an animation that ends at once still pauses taps for the floor`() {
        val hold = pause.hold()
        hold.finish()
        now += TAP_PAUSE_FLOOR_MILLIS - 1
        assertTrue(pause.ignoresTaps())
        now += 1
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `a canceled hold frees taps at once, floor and all`() {
        val hold = pause.hold()
        hold.cancel()
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `an animation that never ends stops pausing taps after the failsafe`() {
        pause.hold()
        now += TAP_PAUSE_FAILSAFE_MILLIS - 1
        assertTrue(pause.ignoresTaps())
        now += 1
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `taps stay paused while any hold is unfinished`() {
        val first = pause.hold()
        val second = pause.hold()
        now += TAP_PAUSE_FLOOR_MILLIS
        first.finish()
        assertTrue(pause.ignoresTaps())
        second.cancel()
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `the floor is 200 ms`() {
        // The maintainer's call (2026-10-09): under the time anyone takes to see a change and aim at it.
        assertEquals(200L, TAP_PAUSE_FLOOR_MILLIS)
    }
}

class FadeThroughTest {
    private val steps = (0..100).map { it / 100f }

    @Test
    fun `the loading screen starts opaque and the new screen hidden`() {
        assertEquals(1f, fadeThroughOut(0f), 0f)
        assertEquals(0f, fadeThroughIn(0f), 0f)
    }

    @Test
    fun `it ends with the loading screen gone and the new screen fully in`() {
        assertEquals(0f, fadeThroughOut(1f), 0f)
        assertEquals(1f, fadeThroughIn(1f), 0f)
    }

    @Test
    fun `the new screen starts in only once the loading screen is out`() {
        for (p in steps) {
            if (fadeThroughOut(p) > 0f) assertEquals("at $p", 0f, fadeThroughIn(p), 0f)
        }
        assertTrue(steps.any { fadeThroughOut(it) == 0f && fadeThroughIn(it) == 0f })
    }

    @Test
    fun `each moves one way only`() {
        for ((a, b) in steps.zipWithNext()) {
            assertTrue("out at $b", fadeThroughOut(b) <= fadeThroughOut(a))
            assertTrue("in at $b", fadeThroughIn(b) >= fadeThroughIn(a))
        }
    }
}
