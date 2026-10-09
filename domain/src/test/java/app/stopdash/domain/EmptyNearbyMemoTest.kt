package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyNearbyMemoTest {
    private var now = Instant.parse("2026-10-09T08:00:00Z")
    private val memo = EmptyNearbyMemo(clock = { now })
    private val here = Coordinates(51.5, -0.12)
    private val stop = StopLocation("940GZZLUWLO", "Waterloo Underground Station", 51.5036, -0.1143, emptyList())

    @Test
    fun `an empty lookup stands nearby until it ages out`() {
        assertFalse(memo.knownEmpty(here))
        memo.record(here, emptyList())
        assertTrue(memo.knownEmpty(here))
        assertTrue(memo.knownEmpty(Coordinates(51.5005, -0.12)))
        now += Duration.ofMinutes(16)
        assertFalse(memo.knownEmpty(here))
        // Forgotten once expired: the clock set back doesn't bring it back.
        now -= Duration.ofMinutes(10)
        assertFalse(memo.knownEmpty(here))
    }

    @Test
    fun `a farther position or a lookup with stops asks again`() {
        memo.record(here, emptyList())
        assertFalse(memo.knownEmpty(Coordinates(51.51, -0.12)))
        memo.record(here, listOf(stop))
        assertFalse(memo.knownEmpty(here))
    }

    @Test
    fun `an expired lookup is forgotten without another lookup`() {
        memo.record(here, emptyList())
        now += Duration.ofMinutes(16)
        memo.forgetExpired()
        // Gone: the clock set back doesn't bring it back.
        now -= Duration.ofMinutes(10)
        assertFalse(memo.knownEmpty(here))
    }
}
