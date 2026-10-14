package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedTrainsTest {
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    private fun train(
        seconds: Long,
        vehicle: String,
        line: String = "victoria",
        platform: String? = "Southbound - Platform 5",
        mode: String = "tube",
    ) = Departure(
        lineId = line, lineName = line, direction = "outbound", destination = "Brixton", platform = platform,
        expectedArrival = now.plusSeconds(seconds), mode = mode, vehicleId = vehicle,
    )

    private fun secondsOf(board: List<Departure>) = SpacedTrains.space(board, now).departures.map { it.expectedArrival.epochSecond - now.epochSecond }

    @Test
    fun `a bunched queue is shown a line's gap apart, each behind the one ahead as moved`() {
        // 0 · 0 · 1 · 2 · 2 min at one platform, as TfL timed a Victoria line queue.
        val board = listOf(train(10, "1"), train(20, "2"), train(70, "3"), train(130, "4"), train(140, "5"))
        val result = SpacedTrains.space(board, now)
        assertEquals(listOf(10L, 110L, 210L, 310L, 410L), result.departures.map { it.expectedArrival.epochSecond - now.epochSecond })
        // The front train keeps its time; each moved one is said, with how far, for the debug log.
        assertEquals(listOf("2", "3", "4", "5"), result.moved.map { it.first.vehicleId })
        assertEquals(listOf(90L, 140L, 180L, 270L), result.moved.map { it.second.seconds })
        // Nothing else about them changes: a moved time isn't grayed.
        assertEquals(board, result.departures.mapIndexed { i, d -> d.copy(expectedArrival = board[i].expectedArrival) })
        assertTrue(result.departures.none { it.grayed })
    }

    @Test
    fun `a train already a gap behind keeps its time`() {
        val board = listOf(train(30, "1"), train(130, "2"), train(400, "3"))
        val result = SpacedTrains.space(board, now)
        assertSame(board, result.departures)
        assertTrue(result.moved.isEmpty())
    }

    @Test
    fun `each line takes its own gap, and the lines left alone keep TfL's times`() {
        assertEquals(listOf(10L, 110L), secondsOf(listOf(train(10, "1", line = "northern"), train(30, "2", line = "northern"))))
        assertEquals(listOf(10L, 110L), secondsOf(listOf(train(10, "1", line = "central"), train(30, "2", line = "central"))))
        for (line in listOf("piccadilly", "bakerloo", "circle", "hammersmith-city")) {
            assertEquals(line, listOf(10L, 100L), secondsOf(listOf(train(10, "1", line = line), train(30, "2", line = line))))
        }
        for (line in listOf("jubilee", "metropolitan", "district", "waterloo-city", "elizabeth")) {
            assertNull(line, SpacedTrains.gapFor(line))
            assertEquals(line, listOf(10L, 30L), secondsOf(listOf(train(10, "1", line = line), train(30, "2", line = line))))
        }
        assertEquals(Duration.ofSeconds(100), SpacedTrains.gapFor("victoria"))
    }

    @Test
    fun `trains at different platforms or of different lines don't hold each other back`() {
        val board = listOf(
            train(10, "1"),
            train(20, "2", platform = "Northbound - Platform 6"),
            train(30, "3", line = "piccadilly", platform = "Southbound - Platform 5"),
        )
        assertEquals(listOf(10L, 20L, 30L), secondsOf(board))
    }

    @Test
    fun `one platform named with and without its direction is still one platform`() {
        assertEquals(listOf(10L, 110L), secondsOf(listOf(train(10, "1"), train(30, "2", platform = "Platform 5"))))
    }

    @Test
    fun `buses, platforms named by direction alone or unknown, and other modes are left as they are`() {
        val board = listOf(
            train(10, "LX1", line = "victoria", platform = "B", mode = "bus"),
            train(20, "LX2", line = "victoria", platform = "B", mode = "bus"),
            train(10, "1", platform = "Southbound"),
            train(20, "2", platform = "Southbound"),
            train(10, "3", line = "victoria", platform = "Platform 1", mode = "overground"),
            train(20, "4", line = "victoria", platform = "Platform 1", mode = "overground"),
            // TfL's placeholder for a train in either direction: no one platform to queue at.
            train(10, "5", platform = "Platform Unknown"),
            train(20, "6", platform = "Platform Unknown"),
        )
        assertEquals(listOf(10L, 20L, 10L, 20L, 10L, 20L, 10L, 20L), secondsOf(board))
    }

    @Test
    fun `a train TfL gives no mode takes its line's`() {
        assertEquals(listOf(10L, 110L), secondsOf(listOf(train(10, "1"), train(30, "2", mode = ""))))
    }

    @Test
    fun `a train TfL still lists after it was due holds nothing back`() {
        assertEquals(listOf(-20L, 30L), secondsOf(listOf(train(-20, "1"), train(30, "2"))))
    }

    @Test
    fun `the board keeps its order, each listing spaced by identity`() {
        // Listed out of time order, and one train listed twice: each listing has its own place in the queue.
        val first = train(40, "1")
        val board = listOf(train(280, "9"), first, first.copy(), train(10, "0"))
        val result = SpacedTrains.space(board, now).departures
        assertEquals(listOf(310L, 110L, 210L, 10L), result.map { it.expectedArrival.epochSecond - now.epochSecond })
        assertEquals(listOf("9", "1", "1", "0"), result.map { it.vehicleId })
        // The train behind them is pushed back too, though TfL timed it well clear of the first.
        assertEquals(listOf("9", "1", "1"), SpacedTrains.space(board, now).moved.map { it.first.vehicleId })
    }

    @Test
    fun `a pair keeps its marks, and holds the queue behind it as TfL listed it`() {
        val marked = DoubtfulTrains.mark(listOf(train(10, "1"), train(40, "2"), train(150, "3")), now).departures
        val result = SpacedTrains.space(marked, now).departures
        assertEquals(listOf(10L, 110L, 210L), result.map { it.expectedArrival.epochSecond - now.epochSecond })
        // TfL timed the first two 30 s apart: either may be the one that doesn't come, judged on TfL's
        // times, not the spaced ones.
        assertEquals(listOf(true, true, false), result.map { it.paired })
        assertEquals(listOf(false, true, false), result.map { it.doubtful })
        // The third, moved but not paired, is shown as any other.
        assertEquals(listOf(true, true, false), result.map { it.grayed })
    }
}
