package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic trains, as TfL listed them at one platform. */
class TwiceListedTest {
    private val now = Instant.parse("2026-11-24T16:08:00Z")

    private fun train(seconds: Long, vehicle: String, line: String = "piccadilly", platform: String = "Eastbound - Platform 2", mode: String = "tube") =
        Departure(
            lineId = line, lineName = line, direction = "inbound", destination = "Cockfosters", platform = platform,
            expectedArrival = now.plusSeconds(seconds), mode = mode, vehicleId = vehicle,
        )

    @Test
    fun `a train listed twice at one platform is kept once, at its sooner time`() {
        // As TfL listed Heathrow Terminals 2 & 3's eastbound platform: each train twice.
        val board = listOf(train(9, "313"), train(39, "313"), train(99, "335"), train(220, "335"))
        val result = TwiceListed.drop(board)
        assertEquals(listOf(9L, 99L), result.departures.map { it.expectedArrival.epochSecond - now.epochSecond })
        assertEquals(listOf(39L, 220L), result.dropped.map { it.expectedArrival.epochSecond - now.epochSecond })
    }

    @Test
    fun `one TfL places at the platform is the one kept`() {
        val there = train(-10, "313").copy(atPlatform = true)
        assertEquals(listOf(there), TwiceListed.drop(listOf(train(-20, "313"), there)).departures)
    }

    @Test
    fun `different trains, platforms, lines, modes or no id are left alone`() {
        val board = listOf(
            train(9, "313"), train(39, "314"),
            train(60, "313", platform = "Westbound - Platform 1"),
            train(70, "313", line = "district"),
            train(80, "313", platform = "Platform Unknown"),
            train(90, "", mode = "tube"), train(95, ""),
            train(100, "313", mode = "bus", platform = "Eastbound - Platform 2"),
        )
        val result = TwiceListed.drop(board)
        assertSame(board, result.departures)
        assertTrue(result.dropped.isEmpty())
    }
}
