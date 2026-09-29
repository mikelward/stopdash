package app.stopdash.domain

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalenessTest {
    @Test
    fun `there is exactly one threshold, pinned here`() {
        // If this value changes, change it deliberately: it is the single policy every
        // surface shares (SPEC D4). The test pins it so a drive-by tweak is caught.
        assertEquals(5.minutes, Staleness.THRESHOLD)
    }

    @Test
    fun `data younger than the threshold is not stale`() {
        assertFalse(Staleness.isStale(0.seconds))
        assertFalse(Staleness.isStale(4.minutes + 59.seconds))
    }

    @Test
    fun `data at or past the threshold is stale`() {
        assertTrue(Staleness.isStale(5.minutes))
        assertTrue(Staleness.isStale(10.minutes))
    }

    @Test
    fun `data stamped ahead of the clock is stale, beyond a moment's margin`() {
        // A screen's clock ticks behind the fetch it shows, and a watch's isn't the phone's.
        assertFalse(Staleness.isStale(-10.seconds))
        assertFalse(Staleness.isStale(-Staleness.CLOCK_SKEW))
        // Further ahead, it was fetched before the clock was set back: its age can't be told.
        assertTrue(Staleness.isStale(-Staleness.CLOCK_SKEW - 1.seconds))
        assertTrue(Staleness.isStale(-60.minutes))
        assertTrue(Staleness.isFromFuture(-60.minutes))
        assertFalse(Staleness.isFromFuture(10.minutes))
        // A caller's own threshold changes only the older side.
        assertTrue(Staleness.isStale(2.minutes, threshold = 1.minutes))
        assertTrue(Staleness.isStale(-60.minutes, threshold = 1.minutes))
        assertFalse(Staleness.isStale(30.seconds, threshold = 1.minutes))
    }

    @Test
    fun `remaining until stale counts down from the threshold`() {
        // The delay a caller schedules a one-shot staleness redraw after (SPEC D4).
        assertEquals(5.minutes, Staleness.remainingUntilStale(0.seconds))
        assertEquals(1.minutes, Staleness.remainingUntilStale(4.minutes))
        assertEquals(1.seconds, Staleness.remainingUntilStale(4.minutes + 59.seconds))
    }

    @Test
    fun `remaining until stale is zero once at or past the threshold`() {
        // Nothing left to flip, so the caller schedules nothing (or cancels a pending wake).
        assertEquals(kotlin.time.Duration.ZERO, Staleness.remainingUntilStale(5.minutes))
        assertEquals(kotlin.time.Duration.ZERO, Staleness.remainingUntilStale(20.minutes))
    }
}
