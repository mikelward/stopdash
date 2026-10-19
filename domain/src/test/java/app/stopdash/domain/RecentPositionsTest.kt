package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPositionsTest {
    // Obviously-synthetic positions, never a real one (AGENTS.md *Privacy*).
    private val origin = Coordinates(0.0, 0.0)
    private val minute = 60_000L

    @Test
    fun `a position is kept with its label and stamp`() {
        val positions = RecentPositions()
        positions.record("fresh network fix", origin, nowElapsedMillis = 0, stamp = "10:00:00")
        assertEquals(listOf("10:00:00 fresh network fix at 0.00000,0.00000"), positions.recent(minute))
    }

    @Test
    fun `positions older than the TTL are deleted`() {
        val positions = RecentPositions()
        positions.record("old", origin, nowElapsedMillis = 0, stamp = "a")
        positions.record("new", origin, nowElapsedMillis = 10 * minute, stamp = "b")
        assertEquals(2, positions.recent(RecentPositions.TTL_MILLIS - 1).size)
        assertEquals(listOf("b new at 0.00000,0.00000"), positions.recent(RecentPositions.TTL_MILLIS))
        // Deleted, not hidden: asking at an earlier time doesn't bring it back.
        assertEquals(listOf("b new at 0.00000,0.00000"), positions.recent(minute))
    }

    @Test
    fun `only the newest few are kept, however recent`() {
        val positions = RecentPositions()
        repeat(RecentPositions.CAPACITY + 5) { i -> positions.record("fix $i", Coordinates(0.0, i / 1000.0), nowElapsedMillis = i.toLong(), stamp = "t") }
        val kept = positions.recent(RecentPositions.CAPACITY + 5L)
        assertEquals(RecentPositions.CAPACITY, kept.size)
        assertEquals("t fix 5 at 0.00000,0.00500", kept.first())
    }

    @Test
    fun `the same fix heard twice takes one entry`() {
        // A trip's fix that near me follows too comes twice in a moment: once is enough (Codex, #993).
        val positions = RecentPositions()
        positions.record("gps update", origin, nowElapsedMillis = 0, stamp = "a")
        positions.record("movement update ±5 m", origin, nowElapsedMillis = 10, stamp = "a")
        assertEquals(listOf("a gps update at 0.00000,0.00000"), positions.recent(minute))
        // A lookup from that fix, with its stops, is kept beside it: it says what the fix was used for.
        positions.record("lookup: 3 stops", origin, nowElapsedMillis = 15, stamp = "a")
        assertEquals(2, positions.recent(minute).size)
        positions.record("fused update", origin, nowElapsedMillis = 16, stamp = "a")
        positions.recent(minute).let { assertEquals(3, it.size) }
        // Somewhere else, or there again a while later, is a position of its own.
        positions.record("gps update", Coordinates(0.0, 0.001), nowElapsedMillis = 20, stamp = "b")
        positions.record("gps update", Coordinates(0.0, 0.001), nowElapsedMillis = 20 + RecentPositions.SAME_FIX_MILLIS, stamp = "c")
        assertEquals(5, positions.recent(minute).size)
    }

    @Test
    fun `expire on its own deletes what's past the TTL`() {
        val positions = RecentPositions()
        positions.record("fix", origin, nowElapsedMillis = 0, stamp = "t")
        assertEquals(true, positions.expire(RecentPositions.TTL_MILLIS - 1))
        // Gone exactly at the TTL; returns whether any remain, so the app's sweep stops.
        assertEquals(false, positions.expire(RecentPositions.TTL_MILLIS))
        assertEquals(emptyList<String>(), positions.recent(0))
    }

    @Test
    fun `the next expiry is the oldest entry's deadline`() {
        val positions = RecentPositions()
        assertEquals(null, positions.untilNextExpiry(0))
        positions.record("a", origin, nowElapsedMillis = 0, stamp = "t")
        positions.record("b", origin, nowElapsedMillis = minute, stamp = "t")
        assertEquals(RecentPositions.TTL_MILLIS - 2 * minute, positions.untilNextExpiry(2 * minute))
    }
}
