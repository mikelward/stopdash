package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic times only. */
class HeadwayTest {
    private val now = Instant.parse("2026-09-26T08:00:00Z")

    private fun at(vararg minutes: Long) = minutes.map { now.plus(Duration.ofMinutes(it)) }

    @Test
    fun `the middle half of the gaps sets the range, not a bunched pair or one long gap`() {
        // Near a line's start: gaps 1, 1, 1, 8, 2. The 8 is one long wait, not how the line runs.
        assertEquals(Headway.Range(1, 2), Headway.of(at(0, 1, 2, 3, 11, 13)))
        // Gaps 3, 3, 3, 4, 5, in any order: the widest one left out.
        assertEquals(Headway.Range(3, 4), Headway.of(at(20, 2, 5, 9, 14, 17)))
        // Three gaps: from the middle one to the widest.
        assertEquals(Headway.Range(2, 6), Headway.of(at(0, 2, 4, 10)))
    }

    @Test
    fun `an even service reads as one number`() {
        assertEquals(Headway.Range(4, 4), Headway.of(at(1, 5, 9, 13)))
    }

    @Test
    fun `too few trains say nothing`() {
        assertNull(Headway.of(at()))
        assertNull(Headway.of(at(3)))
        assertNull(Headway.of(at(3, 7)))
        // Repeats are one train.
        assertNull(Headway.of(at(3, 3, 7)))
    }

    @Test
    fun `gaps round to whole minutes, at least one`() {
        val times = listOf(now, now.plusSeconds(20), now.plusSeconds(40), now.plusSeconds(60))
        assertEquals(Headway.Range(1, 1), Headway.of(times))
        assertEquals(Headway.Range(3, 3), Headway.of(listOf(now, now.plusSeconds(150), now.plusSeconds(300))))
    }
}
