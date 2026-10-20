package app.stopdash.ui

import app.stopdash.domain.Resolved
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouteTest {
    private val at = Instant.parse("2026-01-01T09:00:00Z")

    private fun leg(from: String, to: String, start: Long, end: Long) =
        TripLeg("tube", "red", "Red", from, from, to, to, at.plusSeconds(start), at.plusSeconds(end))

    private fun place(id: String) = Resolved(id, "station:$id", "HUB")

    @Test
    fun `a train through a change read back from saved state takes the plan's places for its stops`() {
        val first = leg("A", "B", 0, 300).copy(placed = mapOf("A" to place("A"), "B" to place("B")))
        val second = leg("B", "C", 360, 600).copy(placed = mapOf("B" to place("B"), "C" to place("C")))
        val plan = TripRoute(listOf(first, second))
        // The train through, as saved and parsed: unplaced.
        val through = OpenRoute.parse(OpenRoute(routeKey(plan), at = 0, ride = leg("A", "C", 0, 540), key = "through").encode())!!
        val joined = through.routeIn(listOf(plan))!!.legs.single()
        assertEquals("HUB", joined.placeOf("A").hub)
        assertEquals("HUB", joined.placeOf("C").hub)
    }
}
