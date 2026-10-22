package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(
        lineId: String = "victoria",
        lineName: String = "Victoria",
        direction: String = "outbound",
        offsetSeconds: Long,
    ) =
        Departure(
            lineId = lineId,
            lineName = lineName,
            direction = direction,
            destination = "Brixton",
            platform = null,
            expectedArrival = now.plusSeconds(offsetSeconds),
            mode = "tube",
        )

    @Test
    fun `a stale line shows its soonest train's time in London as a guess`() {
        // 08:02 UTC on 18 September is 09:02 British Summer Time.
        assertEquals("09:02?", Countdown.staleLabel(listOf(departure(offsetSeconds = 120), departure(offsetSeconds = 600))))
        val winter = departure(offsetSeconds = 0).copy(expectedArrival = Instant.parse("2026-12-01T21:14:00Z"))
        assertEquals("21:14?", Countdown.staleLabel(listOf(winter)))
        assertEquals("?", Countdown.staleLabel(emptyList()))
    }

    @Test
    fun `inside the last minute reads 0 min, otherwise whole minutes`() {
        assertEquals("0 min", Countdown.label(departure(offsetSeconds = 30), now))
        assertEquals("0 min", Countdown.label(departure(offsetSeconds = 59), now))
        assertEquals("1 min", Countdown.label(departure(offsetSeconds = 60), now))
        assertEquals("1 min", Countdown.label(departure(offsetSeconds = 119), now))
        assertEquals("3 min", Countdown.label(departure(offsetSeconds = 180), now))
    }

    @Test
    fun `minutes round down and never go below zero`() {
        assertEquals(4, Countdown.minutes(now.plusSeconds(4 * 60 + 30), now))
        assertEquals(4, Countdown.minutes(now.plusSeconds(4 * 60 + 59), now))
        assertEquals(0, Countdown.minutes(now.plusSeconds(59), now))
        assertEquals(0, Countdown.minutes(now.minusSeconds(30), now))
    }

    @Test
    fun `mergedLabel joins countdowns with the unit written once`() {
        val soon = departure(offsetSeconds = 40) // 0
        val mid = departure(offsetSeconds = 180) // 3 min
        val later = departure(offsetSeconds = 360) // 6 min
        assertEquals("0 · 3 · 6 min", Countdown.mergedLabel(listOf(soon, mid, later), now))
    }

    @Test
    fun `mergedLabel of a single departure matches label`() {
        assertEquals("3 min", Countdown.mergedLabel(listOf(departure(offsetSeconds = 180)), now))
        assertEquals("0 min", Countdown.mergedLabel(listOf(departure(offsetSeconds = 30)), now))
    }

    @Test
    fun `mergedLabel keeps the unit even when every entry is 0`() {
        val a = departure(offsetSeconds = 20)
        val b = departure(offsetSeconds = 50)
        assertEquals("0 · 0 min", Countdown.mergedLabel(listOf(a, b), now))
    }

    @Test
    fun `a countdown's words are the caller's, the unit around the numbers and its joiner between`() {
        val soon = departure(offsetSeconds = 30)
        val mid = departure(offsetSeconds = 180)
        val german = CountdownWords(" | ") { "$it Min." }
        assertEquals("0 | 3 Min.", Countdown.mergedLabel(listOf(soon, mid), now, german))
        val canceled = UntimedTrain(departure(offsetSeconds = 420), canceled = true)
        assertEquals("3 Min. | X", Countdown.mergedLabel(Countdown.entries(listOf(mid), listOf(canceled)), now, "X", "D", german))
        // A unit before its numbers too, for a caller styling each number on its own.
        assertEquals("" to " Min.", german.around)
        assertEquals("ca. " to "", CountdownWords(" · ") { "ca. $it" }.around)
        // The English the app has always written.
        assertEquals("" to " min", CountdownWords.ENGLISH.around)
        assertEquals(Countdown.mergedLabel(listOf(soon, mid), now), Countdown.mergedLabel(listOf(soon, mid), now, CountdownWords.ENGLISH))
    }

    @Test
    fun `mergedLabel of nothing is empty`() {
        assertEquals("", Countdown.mergedLabel(emptyList(), now))
    }

    @Test
    fun `a service reaching zero has departed`() {
        assertFalse(Countdown.hasDeparted(departure(offsetSeconds = 1), now))
        assertTrue(Countdown.hasDeparted(departure(offsetSeconds = 0), now))
        assertTrue(Countdown.hasDeparted(departure(offsetSeconds = -10), now))
    }

    @Test
    fun `upcoming drops departed services and sorts soonest-first`() {
        val gone = departure(lineName = "Northern", offsetSeconds = -30)
        val soon = departure(lineName = "Central", offsetSeconds = 120)
        val later = departure(lineName = "Bakerloo", offsetSeconds = 600)

        val upcoming = Countdown.upcoming(listOf(later, gone, soon), now)

        assertEquals(listOf(soon, later), upcoming)
    }

    @Test
    fun `ties at the same time break by line for a stable order`() {
        val central = departure(lineId = "central", lineName = "Central", offsetSeconds = 120)
        val bakerloo = departure(lineId = "bakerloo", lineName = "Bakerloo", offsetSeconds = 120)

        assertEquals(listOf(bakerloo, central), Countdown.upcoming(listOf(central, bakerloo), now))
    }

    @Test
    fun `trains with no time take their place among the times, as words`() {
        val five = departure(offsetSeconds = 300)
        val twelve = departure(offsetSeconds = 720)
        val canceled = UntimedTrain(departure(offsetSeconds = 420), canceled = true)
        val delayed = UntimedTrain(departure(offsetSeconds = 60), canceled = false)
        val entries = Countdown.entries(listOf(twelve, five), listOf(canceled, delayed))
        assertEquals(
            listOf(Countdown.Entry.Untimed(delayed), Countdown.Entry.Timed(five), Countdown.Entry.Untimed(canceled), Countdown.Entry.Timed(twelve)),
            entries,
        )
        assertEquals("D · 5 · X · 12 min", Countdown.mergedLabel(entries, now, canceled = "X", delayed = "D"))
        // The unit after the last number, wherever that falls; none with no number.
        assertEquals("5 min · X", Countdown.mergedLabel(Countdown.entries(listOf(five), listOf(canceled)), now, "X", "D"))
        assertEquals("X", Countdown.mergedLabel(Countdown.entries(emptyList(), listOf(canceled)), now, "X", "D"))
        // Times alone read as [mergedLabel] always has.
        assertEquals(Countdown.mergedLabel(listOf(five, twelve), now), Countdown.mergedLabel(Countdown.entries(listOf(five, twelve), emptyList()), now, "X", "D"))
        // A timed train first on a tie.
        val tied = UntimedTrain(departure(offsetSeconds = 300), canceled = true)
        assertEquals(Countdown.Entry.Timed(five), Countdown.entries(listOf(five), listOf(tied)).first())
    }

    @Test
    fun `a canceled train goes at its scheduled time, a delayed one stays while its board lists it`() {
        val canceled = UntimedTrain(departure(offsetSeconds = 60), canceled = true)
        val delayed = UntimedTrain(departure(offsetSeconds = 60), canceled = false)
        assertTrue(Countdown.stillShown(canceled, now))
        assertFalse(Countdown.stillShown(canceled, now.plusSeconds(60)))
        // Past its schedule a delayed train hasn't left, which is when it's delayed.
        assertTrue(Countdown.stillShown(delayed, now.plusSeconds(600)))
    }

    @Test
    fun `a board keeps a tube train 30 seconds past its time, grayed at 0, then drops it`() {
        val train = departure(offsetSeconds = 0)
        val kept = Countdown.onBoard(listOf(train), now.plusSeconds(29)).single()
        assertTrue(kept.pastDue)
        assertTrue(kept.grayed)
        assertFalse(kept.mayNotRun)
        assertEquals(listOf(Countdown.Part(0, doubtful = false, pastDue = true)), Countdown.parts(listOf(kept), now.plusSeconds(29)))
        assertTrue(Countdown.onBoard(listOf(train), now.plusSeconds(30)).isEmpty())
        // Still to come: shown as it is, not marked.
        assertFalse(Countdown.onBoard(listOf(departure(offsetSeconds = 20)), now).single().pastDue)
        // A trip still judges it gone at its time.
        assertTrue(Countdown.upcoming(listOf(train), now.plusSeconds(1)).isEmpty())
    }

    @Test
    fun `only a tube train is kept past its time, a blank mode taking its line's`() {
        val bus = departure(lineId = "73", lineName = "73", offsetSeconds = 0).copy(mode = "bus")
        assertTrue(Countdown.onBoard(listOf(bus), now.plusSeconds(10)).isEmpty())
        val blank = departure(offsetSeconds = 0).copy(mode = "")
        val sibling = departure(offsetSeconds = 300)
        assertEquals(listOf(true, false), Countdown.onBoard(listOf(sibling, blank), now.plusSeconds(10)).map { it.pastDue })
        assertTrue(Countdown.onBoard(listOf(blank), now.plusSeconds(10)).isEmpty())
    }

    @Test
    fun `a stale guess is never a train already past its time`() {
        val gone = departure(offsetSeconds = 0).copy(pastDue = true)
        // 08:04 UTC is 09:04 British Summer Time.
        assertEquals("09:04?", Countdown.staleLabel(listOf(gone, departure(offsetSeconds = 240))))
        assertEquals("?", Countdown.staleLabel(listOf(gone)))
    }
}
