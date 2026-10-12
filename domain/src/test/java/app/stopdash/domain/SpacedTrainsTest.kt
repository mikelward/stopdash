package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedTrainsTest {
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    private fun train(
        seconds: Long,
        vehicle: String,
        line: String = "victoria",
        platform: String? = "Southbound - Platform 4",
        mode: String = "tube",
        doubtful: Boolean = false,
    ) = Departure(
        lineId = line, lineName = line.replaceFirstChar { it.uppercase() }, direction = "inbound", destination = "Brixton",
        platform = platform, expectedArrival = now.plusSeconds(seconds), mode = mode, vehicleId = vehicle, doubtful = doubtful,
    )

    private fun secondsOf(board: List<Departure>) =
        SpacedTrains.space(board, now).departures.map { Duration.between(now, it.expectedArrival).seconds }

    @Test
    fun `a bunched queue is spaced its line's gap apart, cascading from the soonest`() {
        // As one platform read 0 · 0 · 1 · 2 · 2 min: each Victoria train goes 90 s behind the one ahead.
        val board = listOf(train(20, "1"), train(40, "2"), train(70, "3"), train(130, "4"), train(150, "5"))
        assertEquals(listOf(20L, 110, 200, 290, 380), secondsOf(board))
        val result = SpacedTrains.space(board, now)
        // The soonest isn't moved; each other one is, and nothing else about it changes.
        assertEquals(listOf("2", "3", "4", "5"), result.moved.map { it.first.vehicleId })
        assertEquals(Duration.ofSeconds(70), result.moved.first().second)
        assertEquals(board, result.departures.mapIndexed { i, d -> d.copy(expectedArrival = board[i].expectedArrival) })
    }

    @Test
    fun `a train already far enough behind keeps its own time`() {
        assertEquals(listOf(30L, 200), secondsOf(listOf(train(30, "1"), train(200, "2"))))
    }

    @Test
    fun `each line takes its own gap`() {
        val board = listOf(train(20, "1", line = "northern"), train(40, "2", line = "northern"))
        assertEquals(listOf(20L, 140), secondsOf(board))
    }

    @Test
    fun `a line not yet measured is left as TfL gives it`() {
        val board = listOf(train(20, "1", line = "piccadilly"), train(40, "2", line = "piccadilly"))
        val result = SpacedTrains.space(board, now)
        assertSame(board, result.departures)
        assertTrue(result.moved.isEmpty())
    }

    @Test
    fun `a doubtful train holds no train back and keeps its own time`() {
        val board = listOf(train(20, "1"), train(60, "copy", doubtful = true), train(150, "2"))
        assertEquals(listOf(20L, 60, 150), secondsOf(board))
    }

    @Test
    fun `a train TfL still lists after it was due holds no train back`() {
        assertEquals(listOf(-10L, 30), secondsOf(listOf(train(-10, "gone"), train(30, "next"))))
    }

    @Test
    fun `each platform is its own queue, named with or without its direction`() {
        val board = listOf(
            train(20, "1"), train(40, "2", platform = "Platform 4"),
            train(30, "3", platform = "Northbound - Platform 5"),
        )
        assertEquals(listOf(20L, 110, 30), secondsOf(board))
    }

    @Test
    fun `a platform named by direction alone or unknown, a bus, or a blank mode with no tube line are left alone`() {
        val byDirection = listOf(train(20, "1", platform = "Southbound"), train(40, "2", platform = "Southbound"))
        assertEquals(listOf(20L, 40), secondsOf(byDirection))
        val unknown = listOf(train(20, "1", platform = "Platform Unknown"), train(40, "2", platform = "Platform Unknown"))
        assertEquals(listOf(20L, 40), secondsOf(unknown))
        val buses = listOf(train(20, "1", mode = "bus"), train(40, "2", mode = "bus"))
        assertEquals(listOf(20L, 40), secondsOf(buses))
    }

    @Test
    fun `a blank mode takes its line's`() {
        val board = listOf(train(20, "1"), train(40, "2", mode = ""))
        assertEquals(listOf(20L, 110), secondsOf(board))
    }

    @Test
    fun `the board keeps its given order`() {
        val board = listOf(train(40, "2"), train(20, "1"))
        assertEquals(listOf(110L, 20), secondsOf(board))
    }
}
