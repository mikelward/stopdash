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
        hold.shown()
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
    fun `the floor runs from when the replacement first shows, not from the hold`() {
        val hold = pause.hold()
        // A stall before the replacement's first frame uses none of the floor.
        now += 1_000
        hold.shown()
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
    fun `once the replacement shows, the failsafe runs from then`() {
        val hold = pause.hold()
        // A stall nearly as long as the failsafe before the first frame cuts nothing short after it.
        now += TAP_PAUSE_FAILSAFE_MILLIS - 100
        hold.shown()
        now += TAP_PAUSE_FLOOR_MILLIS
        assertTrue(pause.ignoresTaps())
        now += TAP_PAUSE_FAILSAFE_MILLIS - TAP_PAUSE_FLOOR_MILLIS - 1
        assertTrue(pause.ignoresTaps())
        now += 1
        assertFalse(pause.ignoresTaps())
    }

    @Test
    fun `taps stay paused while any hold is unfinished`() {
        val first = pause.hold().also { it.shown() }
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

class RevealTest {
    @Test
    fun `everything starts hidden and ends shown`() {
        for (y in listOf(0f, 0.5f, 1f)) {
            assertEquals(0f, revealedAt(0f, y))
            assertEquals(1f, revealedAt(1f, y))
        }
    }

    @Test
    fun `a line comes in before the line below it`() {
        for (progress in listOf(0.2f, 0.4f, 0.5f)) {
            assertTrue(revealedAt(progress, 0.2f) > revealedAt(progress, 0.6f))
        }
    }

    @Test
    fun `the top line is fully in before the bottom one starts`() {
        // 120 of the 300 ms for the top line; the bottom starts at 180.
        assertEquals(1f, revealedAt(0.4f, 0f), 1e-6f)
        assertEquals(0f, revealedAt(0.6f, 1f), 1e-6f)
        assertTrue(revealedAt(0.61f, 1f) > 0f)
    }

    @Test
    fun `the reveal is 300 ms`() {
        // The maintainer's pick (2026-10-09) from the demo.
        assertEquals(300, REVEAL_MILLIS)
    }
}
