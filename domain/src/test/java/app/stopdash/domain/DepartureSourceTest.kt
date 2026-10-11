package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one rule every surface takes a stop's departures by: kept, another screen's, or fetched. */
class DepartureSourceTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")
    private val held = StopArrivals("940GZZLUKSX", "King's Cross St. Pancras", emptyList(), now.minusSeconds(20))
    private fun entry(ageSeconds: Long) = ArrivalsCache.Entry(emptyList(), now.minusSeconds(ageSeconds))

    @Test
    fun `a newer fetch by another screen is taken, even over departures that would be kept`() {
        val newer = entry(5)
        assertEquals(DepartureSource.Choice.Shared(newer), DepartureSource.choose(held, newer, reusable = true))
        assertEquals(DepartureSource.Choice.Shared(newer), DepartureSource.choose(held, newer, reusable = false))
        assertEquals(DepartureSource.Choice.Shared(newer), DepartureSource.choose(null, newer, reusable = false))
    }

    @Test
    fun `one no newer than what's shown is never taken`() {
        // As old: the fetch already shown. Older: it would show older times than before.
        for (age in listOf(20L, 40L)) {
            assertEquals(DepartureSource.Choice.Kept, DepartureSource.choose(held, entry(age), reusable = true))
            assertEquals(DepartureSource.Choice.Fetch, DepartureSource.choose(held, entry(age), reusable = false))
        }
    }

    @Test
    fun `with nothing to take, departures are kept when reusable and fetched otherwise`() {
        assertEquals(DepartureSource.Choice.Kept, DepartureSource.choose(held, null, reusable = true))
        assertEquals(DepartureSource.Choice.Fetch, DepartureSource.choose(held, null, reusable = false))
        assertEquals("nothing held to keep", DepartureSource.Choice.Fetch, DepartureSource.choose(null, null, reusable = true))
    }

    @Test
    fun `fetched departures are as old as their oldest part`() {
        assertEquals(now, DepartureSource.stamp(now, null))
        assertEquals(now.minusSeconds(30), DepartureSource.stamp(now, now.minusSeconds(30)))
        assertEquals("never newer than asked", now, DepartureSource.stamp(now, now.plusSeconds(5)))
    }
}
