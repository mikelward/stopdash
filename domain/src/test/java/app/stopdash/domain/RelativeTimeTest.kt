package app.stopdash.domain

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    @Test
    fun `under a minute is just now`() {
        assertEquals(RelativeAge.JustNow, RelativeTime.of(0.seconds))
        assertEquals(RelativeAge.JustNow, RelativeTime.of(59.seconds))
    }

    @Test
    fun `minutes are truncated, not rounded`() {
        assertEquals(RelativeAge.MinutesAgo(1), RelativeTime.of(60.seconds))
        assertEquals(RelativeAge.MinutesAgo(1), RelativeTime.of(119.seconds))
        assertEquals(RelativeAge.MinutesAgo(2), RelativeTime.of(2.minutes))
    }

    @Test
    fun `an hour or more is in hours`() {
        assertEquals(RelativeAge.MinutesAgo(59), RelativeTime.of(59.minutes))
        assertEquals(RelativeAge.HoursAgo(1), RelativeTime.of(60.minutes))
        assertEquals(RelativeAge.HoursAgo(3), RelativeTime.of(3.hours + 20.minutes))
    }
}
