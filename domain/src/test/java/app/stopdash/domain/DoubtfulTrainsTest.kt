package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubtfulTrainsTest {
    private val now = Instant.parse("2026-10-11T12:58:55Z")

    private fun train(
        seconds: Long,
        destination: String,
        branch: String?,
        vehicle: String,
        platform: String? = "Southbound - Platform 2",
        line: String = "northern",
        mode: String = "tube",
    ) = Departure(
        lineId = line, lineName = "Northern", direction = "inbound", destination = destination, platform = platform,
        expectedArrival = now.plusSeconds(seconds), mode = mode, branch = branch, vehicleId = vehicle,
    )

    private fun doubtfulOf(board: List<Departure>) = DoubtfulTrains.mark(board, now).departures.map { it.doubtful }

    @Test
    fun `a train a minute behind another at its platform is marked, and the one after it isn't`() {
        // As TfL sent one platform: a Battersea train, a "Morden via Bank" one 60 s behind it, and the next
        // train a minute after that, two minutes behind the first.
        val first = train(102, "Battersea Power Station", "Charing X", "123")
        val behind = train(162, "Morden", "Bank", "031")
        val next = train(222, "Battersea Power Station", "Charing X", "063")
        val result = DoubtfulTrains.mark(listOf(first, behind, next), now)
        assertEquals(listOf(false, true, false), result.departures.map { it.doubtful })
        // Nothing else about any of them changes.
        assertEquals(listOf(first, behind, next), result.departures.map { it.copy(doubtful = false) })
        assertEquals(listOf("031"), result.marked.map { it.vehicleId })
        assertTrue(result.marked.single().doubtful)
    }

    @Test
    fun `each is judged against the train believed ahead of it, not a doubtful one`() {
        // 10, 60 and 110 s: the second can't be there; the third is 100 s behind the first, so it can.
        val board = listOf(train(110, "Morden", "Bank", "3"), train(60, "Morden", "Bank", "2"), train(10, "Morden", "Bank", "1"))
        assertEquals(listOf(false, true, false), doubtfulOf(board))
    }

    @Test
    fun `a train TfL still lists after it was due doesn't make the one behind it doubtful`() {
        val gone = train(-20, "Battersea Power Station", "Charing X", "123")
        val next = train(40, "Morden", "Bank", "031")
        assertEquals(listOf(false, false), doubtfulOf(listOf(gone, next)))
    }

    @Test
    fun `one platform named with and without its direction is still one platform`() {
        val board = listOf(train(100, "Battersea Power Station", "Charing X", "1"), train(150, "Morden", "Bank", "2", platform = "Platform 2"))
        assertEquals(listOf(false, true), doubtfulOf(board))
    }

    @Test
    fun `a train TfL gives no mode takes its line's`() {
        val board = listOf(train(100, "Battersea Power Station", "Charing X", "1"), train(150, "Morden", "Bank", "2", mode = ""))
        assertEquals(listOf(false, true), doubtfulOf(board))
        // With no train of the line to say, it's left as it is.
        assertEquals(listOf(false, false), doubtfulOf(board.map { it.copy(mode = "") }))
    }

    @Test
    fun `trains 90 seconds apart are both believed`() {
        assertEquals(listOf(false, false), doubtfulOf(listOf(train(30, "Morden", "Bank", "1"), train(120, "Edgware", null, "2"))))
    }

    @Test
    fun `a minute's gap marks the later train however far out it is`() {
        val board = listOf(train(1200, "Morden", "Bank", "1"), train(1260, "Morden", "Bank", "2"))
        assertEquals(listOf(false, true), doubtfulOf(board))
    }

    @Test
    fun `trains due together at different platforms or on different lines are both believed`() {
        val board = listOf(
            train(60, "Morden", "Bank", "1"),
            train(60, "High Barnet", "Bank", "2", platform = "Northbound - Platform 1"),
            train(65, "Brixton", null, "3", line = "victoria"),
        )
        assertEquals(listOf(false, false, false), doubtfulOf(board))
    }

    @Test
    fun `buses and platforms named by direction alone are left as they are`() {
        val board = listOf(
            train(60, "Archway", null, "LX1", platform = "B", mode = "bus"),
            train(65, "Archway", null, "LX2", platform = "B", mode = "bus"),
            train(60, "Brixton", null, "1", platform = "Southbound"),
            train(65, "Brixton", null, "2", platform = "Southbound"),
        )
        assertEquals(listOf(false, false, false, false), doubtfulOf(board))
    }

    @Test
    fun `a train listed twice has one listing marked, in the board's own order`() {
        val other = train(400, "Edgware", null, "9", platform = "Northbound - Platform 1")
        val first = train(80, "Morden", "Bank", "031")
        val result = DoubtfulTrains.mark(listOf(other, first, first.copy()), now)
        assertEquals(listOf(false, false, true), result.departures.map { it.doubtful })
        assertSame(other, result.departures[0])
        assertSame(first, result.departures[1])
    }

    @Test
    fun `a board with nothing doubtful is returned as given`() {
        val board = listOf(train(30, "Morden", "Bank", "1"), train(300, "Edgware", null, "2"))
        val result = DoubtfulTrains.mark(board, now)
        assertSame(board, result.departures)
        assertTrue(result.marked.isEmpty())
    }

    @Test
    fun `a doubtful train's countdown reads as any other, its parts saying which to gray`() {
        val first = train(102, "Battersea Power Station", "Charing X", "123")
        val behind = train(162, "Morden", "Bank", "031").copy(doubtful = true)
        assertEquals("1 · 2 min", Countdown.mergedLabel(listOf(first, behind), now))
        assertEquals("2 min", Countdown.mergedLabel(listOf(Countdown.Entry.Timed(behind)), now, "Canceled", "Delayed"))
        assertEquals(listOf(Countdown.Part(1, false), Countdown.Part(2, true)), Countdown.parts(listOf(first, behind), now))
        assertFalse(first.doubtful)
    }
}
