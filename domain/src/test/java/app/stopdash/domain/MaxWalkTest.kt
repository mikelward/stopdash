package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaxWalkTest {
    @Test
    fun `reads back what was stored, and 20 minutes otherwise`() {
        MaxWalk.entries.forEach { assertEquals(it, MaxWalk.fromStored(it.name)) }
        assertEquals(MaxWalk.TWENTY, MaxWalk.fromStored(null))
        assertEquals(MaxWalk.TWENTY, MaxWalk.fromStored("NINETY"))
    }

    @Test
    fun `offers the limits the maintainer chose, shortest first`() {
        assertEquals(listOf(10, 15, 20, 30, 45, 60), MaxWalk.entries.map { it.minutes })
    }

    private val now = Instant.parse("2026-11-01T10:00:00Z")

    private fun leg(mode: String, from: String, to: String, departs: Long, arrives: Long) = TripLeg(
        mode, if (mode == TripLeg.WALKING) "" else "northern", "", from, from, to, to,
        now.plus(Duration.ofMinutes(departs)), now.plus(Duration.ofMinutes(arrives)),
    )

    // A walk to the station, a ride, and a walk on to the place: each walk as long as given.
    private fun route(first: Long, last: Long) = TripRoute(
        listOf(
            leg(TripLeg.WALKING, "", "A", 0, first),
            leg("tube", "A", "B", first, first + 20),
            leg(TripLeg.WALKING, "B", "Place", first + 20, first + 20 + last),
        ),
    )

    @Test
    fun `allows a route only while every walk is within the limit, the limit itself included`() {
        assertTrue(MaxWalk.FIFTEEN.allows(route(15, 15)))
        // The walk from the rider, or the walk on to the place, past it (maintainer's report, 2026-11-01).
        assertFalse(MaxWalk.FIFTEEN.allows(route(16, 2)))
        assertFalse(MaxWalk.FIFTEEN.allows(route(2, 19)))
        assertTrue(MaxWalk.TWENTY.allows(route(16, 19)))
    }

    @Test
    fun `leaves out routes walking past the limit, even where none is within it`() {
        val short = route(2, 2)
        val long = route(16, 19)
        assertEquals(listOf(short), MaxWalk.FIFTEEN.within(listOf(long, short)))
        assertEquals(emptyList<TripRoute>(), MaxWalk.FIFTEEN.within(listOf(long)))
    }
}
