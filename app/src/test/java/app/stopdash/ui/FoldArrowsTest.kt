package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [foldArrows]: where a fold rail's one-way arrows go. Lengths are in dp, as the drawing's own sizes are:
 * a 20dp arrow (8dp head, 12dp tail), an 8dp gap, and fold rails from the shortest row's 32dp up.
 */
class FoldArrowsTest {
    private val head = 8f
    private val whole = 20f
    private val gap = 8f

    // The span an arrow covers along a rail [length] long, centered [at] the way along.
    private fun span(length: Float, at: Float, tails: Boolean): ClosedFloatingPointRange<Float> {
        val half = (if (tails) whole else head) / 2
        return (length * at - half)..(length * at + half)
    }

    @Test
    fun `two arrows on one rail never overlap and stay on it, whatever its length`() {
        var length = 32f
        while (length <= 200f) {
            val placed = foldArrows(length, both = true, whole = whole, gap = gap)
            val down = span(length, placed.downAt, placed.tails)
            val up = span(length, placed.upAt, placed.tails)
            assertTrue("apart at $length dp: $down, $up", down.endInclusive <= up.start)
            assertTrue("on the rail at $length dp", down.start >= 0f && up.endInclusive <= length)
            length += 1f
        }
    }

    @Test
    fun `a roomy rail spreads the two whole arrows, a short one keeps heads alone`() {
        // The shortest fold row's inset rail: heads alone, as before arrows had tails.
        assertEquals(FoldArrows(1f / 3, 2f / 3, tails = false), foldArrows(32f, both = true, whole, gap))
        // Room for two whole arrows and the gap between: spread to a quarter and three quarters.
        val roomy = foldArrows(56f, both = true, whole, gap)
        assertEquals(FoldArrows(1f / 4, 3f / 4, tails = true), roomy)
        assertTrue(span(56f, roomy.upAt, tails = true).start - span(56f, roomy.downAt, tails = true).endInclusive >= gap)
    }

    @Test
    fun `one arrow alone keeps its tail even on the shortest rail`() {
        val alone = foldArrows(32f, both = false, whole, gap)
        assertTrue(alone.tails)
        assertTrue(span(32f, alone.downAt, tails = true).start >= 0f)
        assertTrue(span(32f, alone.upAt, tails = true).endInclusive <= 32f)
        assertFalse(foldArrows(55f, both = true, whole, gap).tails)
    }
}
