package app.stopdash.wear

import app.stopdash.data.WatchTrip
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trip on the tile, in the departures' place while the phone follows it. Stock station names. */
class TileTripTest {
    private val now: Instant = Instant.parse("2026-10-06T08:00:00Z")
    private val elapsedNow = 50_000_000L

    private val trip = WatchTrip(
        title = "Walk to King's Cross St. Pancras",
        detail = "4 min",
        steps = listOf(
            WatchTrip.Step("Walk to King's Cross St. Pancras", walk = true, mode = "walking"),
            WatchTrip.Step("King's Cross St. Pancras → Victoria", "victoria", "Victoria", "tube"),
        ),
        current = 0,
        departures = listOf(
            WatchTrip.Train("victoria", "Victoria", "tube", "Brixton", now.plusSeconds(6 * 60).toEpochMilli()),
            WatchTrip.Train("victoria", "Victoria", "tube", "Brixton", now.plusSeconds(9 * 60).toEpochMilli()),
        ),
        departuresAt = 1,
        sentAt = now.toEpochMilli(),
    )

    private fun held(ago: Duration = Duration.ZERO) = HeldTrip(trip, elapsedNow - ago.toMillis())

    // The departures the tile goes back to once the trip goes, from the instant asked.
    private val departures = { from: Instant, _: Int -> TileSchedule(listOf(TileEntry(from, null, TileFrame.NoStops)), refreshAt = null) }

    @Test
    fun `no trip, or one past its time, leaves the tile to its departures`() {
        assertNull(TileTrip.schedule(null, now, elapsedNow, null, departures))
        assertNull(TileTrip.schedule(held(WatchTripState.GONE_AFTER.plusSeconds(1)), now, elapsedNow, null, departures))
    }

    @Test
    fun `it shows the step and the next ride's trains, as the watch app does`() {
        val first = TileTrip.schedule(held(), now, elapsedNow, null, departures)!!.entries.first().frame as TileFrame.Trip
        assertEquals("Walk to King's Cross St. Pancras", first.title)
        assertEquals("4 min", first.detail)
        assertFalse(first.stale)
        val rows = first.lines.filterIsInstance<TileLine.Departure>().map { it.row }
        assertEquals(listOf("Brixton"), rows.map { it.label })
        assertEquals("6 · 9 min", rows.single().countdown)
    }

    @Test
    fun `its countdowns go down between updates, it reads out of date after two minutes, and goes at fifteen`() {
        val schedule = TileTrip.schedule(held(), now, elapsedNow, null, departures)!!
        assertEquals(now.plus(WatchTripState.GONE_AFTER), schedule.refreshAt)
        val goneAt = now.plus(WatchTripState.GONE_AFTER)
        // Once it goes the timeline runs on to the departures, whether or not the tile is asked
        // to render again then (Codex on #612).
        assertEquals(TileEntry(goneAt, null, TileFrame.NoStops), schedule.entries.last())
        val frames = schedule.entries.dropLast(1).map { it.start to (it.frame as TileFrame.Trip) }
        // A minute on, the countdowns are a minute less.
        val minuteOn = frames.last { (start, _) -> start <= now.plusSeconds(60) }.second
        assertEquals("5 · 8 min", minuteOn.lines.filterIsInstance<TileLine.Departure>().single().row.countdown)
        // Out of date the moment it's past two minutes without an update, never before (Codex on #612).
        val staleAt = now.plus(WatchTripState.STALE_AFTER).plusMillis(1)
        assertTrue(frames.filter { (start, _) -> start < staleAt }.none { it.second.stale })
        assertTrue(frames.filter { (start, _) -> start >= staleAt }.all { it.second.stale })
        assertTrue(frames.any { (start, frame) -> start == staleAt && frame.stale })
        // A train drops off the moment it departs, not at some later step (Codex on #612).
        assertTrue(schedule.entries.any { it.start == now.plusSeconds(6 * 60) })
        // Entries chain without gaps, within the timeline's cap.
        schedule.entries.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertTrue(schedule.entries.size <= TileTimeline.MAX_ENTRIES)
    }

    @Test
    fun `on board, past the step that boards, no trains are listed`() {
        val onBoard = trip.copy(current = 1, departuresAt = 0)
        val frame = TileTrip.frame(onBoard, now, stale = false, screen = null)
        assertTrue(frame.lines.isEmpty())
    }

    @Test
    fun `with room for one line, it's a train, not its pole's header`() {
        val poles = trip.copy(
            departures = trip.departures.map { it.copy(stop = "Stop A") } +
                WatchTrip.Train("victoria", "Victoria", "tube", "Walthamstow Central", now.plusSeconds(7 * 60).toEpochMilli(), stop = "Stop B"),
        )
        // A small screen at a large font: room for one line under the step.
        val frame = TileTrip.frame(poles, now, stale = false, screen = TileScreen(heightDp = 192, fontScale = 2f))
        assertEquals(1, frame.lines.size)
        assertTrue(frame.lines.single() is TileLine.Departure)
    }

    @Test
    fun `a train due far ahead, from a watch clock well behind, costs only the ticks inside the trip`() {
        val farAhead = trip.copy(departures = listOf(trip.departures.first().copy(dueAt = now.plus(Duration.ofDays(3650)).toEpochMilli())))
        val schedule = TileTrip.schedule(HeldTrip(farAhead, elapsedNow), now, elapsedNow, null, departures)!!
        assertTrue(schedule.entries.size <= TileTimeline.MAX_ENTRIES)
    }
}
