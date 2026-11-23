package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and locations only. */
class LocatedTrainsTest {
    private val t0 = Instant.parse("2026-11-23T09:00:00Z")

    private fun train(vehicle: String, dueIn: Long, at: Instant = t0, platform: String = "Southbound - Platform 2", mode: String = "tube") =
        Departure(
            lineId = "northern", lineName = "Northern", direction = "inbound", destination = "Morden",
            platform = platform, expectedArrival = at.plusSeconds(dueIn), mode = mode, vehicleId = vehicle,
        )

    // From "Left Station A", trains took 150 s when just there, 110 s 40 s in, 70 s 80 s in.
    private val timings = LocationTimings(
        mapOf(
            LocationTimings.key("S", "northern", "2", "left station a") to mapOf(0 to 150, 2 to 110, 4 to 70),
            LocationTimings.key("S", "northern", "2", "approaching station s") to mapOf(0 to 40),
        ),
    )

    @Test
    fun `a location reads as the recorder keys it`() {
        assertEquals("left station a", LocationTimings.stateOf("  Left Station A Platform 4 "))
        assertEquals("at station a", LocationTimings.stateOf("At Station A,"))
        assertEquals("", LocationTimings.stateOf(""))
    }

    @Test
    fun `an estimate is by how long the train has been there, in steps`() {
        assertEquals(Duration.ofSeconds(110), timings.estimate("S", "northern", "2", "left station a", Duration.ofSeconds(45)))
        assertNull(timings.estimate("S", "northern", "2", "left station a", Duration.ofSeconds(25)))
        assertNull(timings.estimate("S", "northern", "1", "left station a", Duration.ZERO))
    }

    @Test
    fun `a train due by TfL whose location says it's still away keeps its time and gains a latest`() {
        val located = LocatedTrains()
        // TfL says due now; the train has just left Station A.
        val first = located.retime("S", t0, listOf(train("1", 0)), listOf("Left Station A Platform 4"), timings)
        val shown = first.departures.single()
        assertEquals(t0, shown.expectedArrival)
        assertEquals(t0.plusSeconds(150), shown.upTo)
        assertEquals("0–2", Countdown.timeText(shown, t0))
        assertEquals(Duration.ofSeconds(150), first.moved.single().second)
        // 40 s on, still "Left Station A" and still due now by TfL: its latest 110 s from then.
        val later = t0.plusSeconds(40)
        val second = located.retime("S", later, listOf(train("1", 0, later)), listOf("Left Station A"), timings)
        assertEquals(later.plusSeconds(110), second.departures.single().upTo)
        // On to its next place, where trains came in 40 s: too near its own time for a range.
        val on = t0.plusSeconds(60)
        assertNull(located.retime("S", on, listOf(train("1", 0, on)), listOf("Approaching Station S"), timings).departures.single().upTo)
    }

    @Test
    fun `only a train due within a minute by TfL, at a tube platform with an id, gains a range`() {
        val located = LocatedTrains()
        // Due in 90 s by TfL: its countdown hasn't stalled at due, whatever its location says.
        val farther = located.retime("S", t0, listOf(train("1", 90)), listOf("Left Station A"), timings)
        assertNull(farther.departures.single().upTo)
        assertTrue(farther.moved.isEmpty())
        val untouched = listOf(train("", 0), train("3", 0, platform = "Southbound - Platform Unknown"), train("4", 0, mode = "bus"))
        assertEquals(untouched, located.retime("S", t0, untouched, List(3) { "Left Station A" }, timings).departures)
        // At the platform, or nowhere TfL says: TfL's own time alone.
        assertNull(located.retime("S", t0, listOf(train("5", 0)), listOf("At Platform"), timings).departures.single().upTo)
        assertNull(located.retime("S", t0, listOf(train("6", 0)), listOf(""), timings).departures.single().upTo)
        // With no table, nothing changes.
        assertNull(LocatedTrains().retime("S", t0, listOf(train("7", 0)), listOf("Left Station A"), LocationTimings.EMPTY).departures.single().upTo)
    }

    @Test
    fun `an older answer's trains keep the latest the newest one gave them`() {
        val located = LocatedTrains()
        located.retime("S", t0, listOf(train("1", 0)), listOf("Left Station A"), timings)
        // An older cache copy, its places unknown: the train keeps "0–2" while its latest is still to come.
        val older = located.kept("S", t0.plusSeconds(20), listOf(train("1", 0), train("2", 300)))
        assertEquals(t0.plusSeconds(150), older.first().upTo)
        assertNull(older.last().upTo)
        // Past its latest, nothing is kept.
        assertNull(located.kept("S", t0.plusSeconds(200), listOf(train("1", 0))).single().upTo)
    }

    @Test
    fun `a surface with no grace shows a ranged train past its time, and its countdown changes at both ends`() {
        val ranged = train("1", 0).copy(upTo = t0.plusSeconds(150))
        assertEquals(listOf(ranged), Countdown.shown(listOf(ranged, train("2", -10)), t0.plusSeconds(30)))
        assertEquals(listOf(t0, t0.plusSeconds(150)), Countdown.changesAt(ranged))
    }

    @Test
    fun `a train unseen a while starts over at its location`() {
        val located = LocatedTrains()
        located.retime("S", t0, listOf(train("1", 0)), listOf("Left Station A"), timings)
        val back = t0.plus(LocatedTrains.FORGET).plusSeconds(45)
        val again = located.retime("S", back, listOf(train("1", 0, back)), listOf("Left Station A"), timings)
        assertEquals(back.plusSeconds(150), again.departures.single().upTo)
    }

    @Test
    fun `a range is written from the train's own minutes to its latest, and a countdown writes it so`() {
        val ranged = train("1", 0).copy(upTo = t0.plusSeconds(150))
        assertEquals("0–2", Countdown.timeText(ranged, t0))
        assertEquals("0–2 · 5 min", Countdown.mergedLabel(listOf(ranged, train("2", 300)), t0))
        assertEquals(listOf("0–2", "5"), Countdown.parts(listOf(ranged, train("2", 300)), t0).map { it.text })
        // A latest no later in whole minutes than the train's own: its minutes alone.
        assertEquals("0", Countdown.timeText(train("1", 0).copy(upTo = t0.plusSeconds(50)), t0))
        assertNull(Countdown.upToMinutes(train("1", 0), t0))
    }

    @Test
    fun `a board keeps a train past TfL's time while its location says it may still come, not grayed`() {
        val ranged = train("1", 0).copy(upTo = t0.plusSeconds(150))
        // A minute past TfL's time, past the grace for one due: still on the board, as a train to come.
        val later = t0.plusSeconds(60)
        val shown = Countdown.onBoard(listOf(ranged), later).single()
        assertEquals(false, shown.pastDue)
        assertEquals("0\u20131", Countdown.timeText(shown, later))
        // Past its latest, it goes as any train does.
        assertTrue(Countdown.onBoard(listOf(ranged), t0.plusSeconds(200)).isEmpty())
        assertTrue(Countdown.goneFromBoard(ranged, t0.plusSeconds(200)))
        assertEquals(false, Countdown.goneFromBoard(ranged, later))
    }
}
