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
    fun `of trains a minute apart at a platform, all are paired and each after the first doubtful`() {
        // As TfL sent one platform: a Battersea train, a "Morden via Bank" one 60 s behind it, and the next
        // train a minute after that, two minutes behind the first.
        val first = train(102, "Battersea Power Station", "Charing X", "123")
        val behind = train(162, "Morden", "Bank", "031")
        val next = train(222, "Battersea Power Station", "Charing X", "063")
        val result = DoubtfulTrains.mark(listOf(first, behind, next), now)
        // Each is a minute behind the one ahead, so of each two either may be the one that doesn't come,
        // and a trip counts on the first.
        assertEquals(listOf(true, true, true), result.departures.map { it.paired })
        assertEquals(listOf(false, true, true), result.departures.map { it.doubtful })
        // Nothing else about any of them changes.
        assertEquals(listOf(first, behind, next), result.departures.map { it.copy(doubtful = false, paired = false) })
        assertEquals(listOf("123", "031", "063"), result.marked.map { it.vehicleId })
        // Without the train between, the third is two minutes behind the first: neither is marked.
        val apart = listOf(first, next)
        assertSame(apart, DoubtfulTrains.mark(apart, now).departures)
    }

    @Test
    fun `either of two trains a minute or less apart may not run, the earlier too`() {
        val earlier = train(60, "Morden", "Bank", "1")
        val later = train(120, "Edgware", null, "2")
        val result = DoubtfulTrains.mark(listOf(earlier, later), now).departures
        assertEquals(listOf(true, true), result.map { it.paired })
        assertEquals(listOf(false, true), result.map { it.doubtful })
        assertEquals(listOf(true, true), result.map { it.mayNotRun })
        assertEquals(listOf(true, true), result.map { it.grayed })
    }

    @Test
    fun `trains more than a minute apart are both believed`() {
        // 61 to 89 s apart was mostly two real trains in the recordings: neither is grayed.
        for (gap in listOf(61L, 75L, 89L, 90L)) {
            val result = DoubtfulTrains.mark(listOf(train(60, "Morden", "Bank", "1"), train(60 + gap, "Edgware", null, "2")), now).departures
            assertEquals("$gap s", listOf(false, false), result.map { it.paired })
            assertEquals("$gap s", listOf(false, false), result.map { it.doubtful })
            assertEquals("$gap s", listOf(false, false), result.map { it.grayed })
        }
    }

    @Test
    fun `a pair is marked only when the first of it is due within five minutes`() {
        // As far as the recordings looked: the first due inside 5 minutes, the second may be just past it.
        val near = DoubtfulTrains.mark(listOf(train(280, "Morden", "Bank", "1"), train(330, "Edgware", null, "2")), now).departures
        assertEquals(listOf(true, true), near.map { it.paired })
        assertEquals(listOf(false, true), near.map { it.doubtful })
        val far = DoubtfulTrains.mark(listOf(train(300, "Morden", "Bank", "1"), train(340, "Edgware", null, "2")), now).departures
        assertEquals(listOf(false, false), far.map { it.paired })
        // Nor is the later of a far pair doubtful: a trip counts on it as on any other.
        assertEquals(listOf(false, false), far.map { it.doubtful })
        assertEquals(listOf(false, false), far.map { it.grayed })
    }

    @Test
    fun `a pair is judged at one platform of one line, like a doubtful train`() {
        val board = listOf(
            train(60, "Morden", "Bank", "1"),
            train(70, "High Barnet", "Bank", "2", platform = "Northbound - Platform 1"),
            train(80, "Brixton", null, "3", line = "victoria"),
            train(60, "Archway", null, "LX1", platform = "B", mode = "bus"),
            train(65, "Archway", null, "LX2", platform = "B", mode = "bus"),
        )
        assertEquals(listOf(false, false, false, false, false), DoubtfulTrains.mark(board, now).departures.map { it.paired })
    }

    @Test
    fun `a train TfL still lists after it was due pairs with nothing`() {
        val gone = train(-20, "Battersea Power Station", "Charing X", "123")
        val next = train(30, "Morden", "Bank", "031")
        assertEquals(listOf(false, false), DoubtfulTrains.mark(listOf(gone, next), now).departures.map { it.paired })
    }

    @Test
    fun `each is judged against the train just ahead of it, in TfL's time order`() {
        // 10, 60 and 110 s, listed latest first: each is under a minute behind the one ahead of it.
        val board = listOf(train(110, "Morden", "Bank", "3"), train(60, "Morden", "Bank", "2"), train(10, "Morden", "Bank", "1"))
        assertEquals(listOf(true, true, false), doubtfulOf(board))
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
    fun `a minute's gap twenty minutes out marks neither train`() {
        // TfL's whole-minute times out there would pair most real trains.
        val board = listOf(train(1200, "Morden", "Bank", "1"), train(1260, "Morden", "Bank", "2"))
        assertEquals(listOf(false, false), doubtfulOf(board))
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
            // TfL's placeholder for a train in either direction: no one platform, so no pair.
            train(60, "Morden", "Bank", "3", platform = "Platform Unknown"),
            train(65, "Edgware", null, "4", platform = "Platform Unknown"),
        )
        assertEquals(listOf(false, false, false, false, false, false), doubtfulOf(board))
        assertEquals(List(6) { false }, DoubtfulTrains.mark(board, now).departures.map { it.paired })
    }

    @Test
    fun `a train listed twice has one listing marked, in the board's own order`() {
        val other = train(400, "Edgware", null, "9", platform = "Northbound - Platform 1")
        val first = train(80, "Morden", "Bank", "031")
        val result = DoubtfulTrains.mark(listOf(other, first, first.copy()), now)
        assertEquals(listOf(false, false, true), result.departures.map { it.doubtful })
        // Due together, either listing may be the one that isn't there: both are paired.
        assertEquals(listOf(false, true, true), result.departures.map { it.paired })
        assertSame(other, result.departures[0])
    }

    @Test
    fun `a train listed on two lines at one platform is grayed on both, and the train beside it is not`() {
        // As TfL listed one platform: a Hammersmith & City train, and 30 s behind it one train listed both as
        // a Hammersmith & City train and as a Metropolitan one, with no destination yet.
        val ahead = train(60, "Hammersmith", null, "201", platform = "Westbound - Platform 2", line = "hammersmith-city")
        val asHammersmith = train(90, "", null, "123", platform = "Westbound - Platform 2", line = "hammersmith-city")
        val asMetropolitan = train(90, "", null, "123", platform = "Platform 2", line = "metropolitan")
        val result = DoubtfulTrains.mark(listOf(ahead, asHammersmith, asMetropolitan), now)
        // It may run as either line, so both listings are grayed and a trip counts on another train first.
        assertEquals(listOf(false, true, true), result.departures.map { it.grayed })
        assertEquals(listOf(false, true, true), result.departures.map { it.doubtful })
        assertEquals(result.departures.drop(1), result.onTwoLines)
        assertEquals(result.departures.drop(1), result.marked)
        // The train ahead isn't paired with the listing 30 s behind it: that may not be its line's at all.
        assertSame(ahead, result.departures[0])
    }

    @Test
    fun `a train on two lines is grayed however far out it is`() {
        // Its line isn't settled at any distance, unlike a close pair, which is judged within five minutes.
        val board = listOf(
            train(900, "", null, "123", platform = "Platform 2", line = "district"),
            train(900, "", null, "123", platform = "Platform 2", line = "hammersmith-city"),
        )
        assertEquals(listOf(true, true), DoubtfulTrains.mark(board, now).departures.map { it.grayed })
    }

    @Test
    fun `one id on two lines due far apart is two trains`() {
        // An id is unique within a line only: two lines' trains at one platform minutes apart can share one.
        val board = listOf(
            train(60, "Hammersmith", null, "123", platform = "Platform 2", line = "hammersmith-city"),
            train(121, "Uxbridge", null, "123", platform = "Platform 2", line = "metropolitan"),
        )
        val result = DoubtfulTrains.mark(board, now)
        assertSame(board, result.departures)
        assertTrue(result.onTwoLines.isEmpty())
        // A minute apart, they're one train TfL hasn't settled the line of.
        val close = board.mapIndexed { i, d -> if (i == 1) d.copy(expectedArrival = now.plusSeconds(120)) else d }
        assertEquals(listOf(true, true), DoubtfulTrains.mark(close, now).departures.map { it.grayed })
    }

    @Test
    fun `one id on two lines at different platforms is two trains`() {
        // A train's id is unique within its line only: trains of two lines at different platforms can share one.
        val board = listOf(
            train(60, "Brixton", null, "123", platform = "Southbound - Platform 5", line = "victoria"),
            train(70, "Cockfosters", null, "123", platform = "Northbound - Platform 6", line = "piccadilly"),
        )
        val result = DoubtfulTrains.mark(board, now)
        assertSame(board, result.departures)
        assertTrue(result.onTwoLines.isEmpty())
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
        // Paired, it may not run as a doubtful one may.
        val paired = Countdown.parts(listOf(first.copy(paired = true)), now).single()
        assertEquals(Countdown.Part(1, true), paired)
        assertTrue(paired.grayed)
        assertFalse(Countdown.parts(listOf(first), now).single().grayed)
    }
}
