package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class TripTimingTest {
    private val now = Instant.parse("2026-09-26T08:00:00Z")

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private fun leg(
        lineId: String,
        from: String,
        to: String,
        departs: Long,
        arrives: Long,
        change: Long = 0,
        mode: String = "tube",
    ) = TripLeg(
        mode = mode,
        lineId = lineId,
        lineName = lineId,
        fromId = from,
        fromName = from,
        toId = to,
        toName = to,
        departure = at(departs),
        arrival = at(arrives),
        path = listOf(to),
        changeAfter = Duration.ofMinutes(change),
    )

    private fun walk(from: String, to: String, departs: Long, arrives: Long) =
        leg("", from, to, departs, arrives, mode = TripLeg.WALKING)

    private fun train(lineId: String, inMinutes: Long) = Departure(
        lineId = lineId,
        lineName = lineId,
        direction = "outbound",
        destination = "End",
        platform = null,
        expectedArrival = at(inMinutes),
        mode = "tube",
    )

    private val twoLegs = TripRoute(
        listOf(
            leg("red", "A", "B", departs = 5, arrives = 15, change = 3),
            leg("blue", "B", "C", departs = 20, arrives = 30),
        ),
    )

    @Test
    fun `works the arrival out leg by leg from the first reachable trains`() {
        val live = mapOf(0 to listOf(train("red", 2)), 1 to listOf(train("blue", 14), train("blue", 16), train("blue", 22)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] })
        // Red at 2, 10 min run: B at 12, 3 min change: ready at 15. Blue at 14 is missed; 16 is caught.
        assertEquals(TripTiming.Basis.LIVE, estimate.basis)
        assertEquals(at(16), estimate.legs[1].board)
        assertEquals(at(26), estimate.arrival)
        assertEquals(Duration.ofMinutes(26), estimate.duration)
    }

    @Test
    fun `the walk to the first stop grays trains the rider can't reach`() {
        val live = mapOf(0 to listOf(train("red", 2), train("red", 7)), 1 to listOf(train("blue", 21)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ofMinutes(4), { live[it] })
        assertEquals(at(7), estimate.legs[0].board)
        assertEquals(at(31), estimate.arrival)
    }

    @Test
    fun `each leg is ready when the one before gets the rider there`() {
        val live = mapOf(0 to listOf(train("red", 2)), 1 to listOf(train("blue", 14), train("blue", 16)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ofMinutes(1), { live[it] })
        // The first leg once the walk to its stop is done; red at 2 reaches B at 12, plus 3 min to
        // change: the blue train at 14 leaves too soon, the one at 16 doesn't.
        assertEquals(at(1), TripTiming.readyAt(estimate, Duration.ofMinutes(1), 0))
        assertEquals(at(15), TripTiming.readyAt(estimate, Duration.ofMinutes(1), 1))
        // No leg past the route's end.
        assertNull(TripTiming.readyAt(estimate, Duration.ofMinutes(1), 3))
    }

    @Test
    fun `a leg after a withheld one has no time to be ready by`() {
        // Red has no train in reach and its Planner departure has passed: withheld, so blue can't be timed.
        val live = mapOf(0 to emptyList<Departure>(), 1 to listOf(train("blue", 21)))
        val estimate = TripTiming.estimate(twoLegs, at(6), Duration.ZERO, { live[it] })
        assertNull(estimate.legs[0].arrive)
        assertNull(TripTiming.readyAt(estimate, Duration.ZERO, 1))
    }

    @Test
    fun `a leg with no live train falls back to the Planner while its departure is reachable`() {
        val live = mapOf(0 to listOf(train("red", 2)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] })
        // Ready at 15, the Planner's blue leaves at 20.
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertFalse(estimate.legs[1].live)
        assertEquals(at(30), estimate.arrival)
    }

    // The Planner can plan a trip leaving later than the rider does: a first ride caught sooner than
    // it planned reaches the change well before the Planner's next train.
    @Test
    fun `a frequent line whose Planner train leaves a gap after the rider gets there is boarded on arrival`() {
        // Ready for blue at 15; the Planner's blue leaves at 40, and blue runs every 3 min, its
        // predictions ending before the rider gets there.
        val late = TripRoute(listOf(twoLegs.legs[0], leg("blue", "B", "C", departs = 40, arrives = 50)))
        val live = mapOf(0 to listOf(train("red", 2)), 1 to listOf(0L, 3L, 6L, 9L).map { train("blue", it) })
        val estimate = TripTiming.estimate(late, now, Duration.ZERO, { live[it] })
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(15), estimate.legs[1].board)
        assertFalse(estimate.legs[1].live)
        assertEquals(at(25), estimate.arrival)
        // The wait it assumes away is up to blue's gap between trains, 3 min: never the Planner's 25.
        assertEquals(Duration.ofMinutes(3), estimate.slack)
        assertEquals(at(28), estimate.latest)
    }

    @Test
    fun `a Planner train within a gap of the rider getting there still times the leg`() {
        // Ready for blue at 15, the Planner's leaves at 17: within blue's 3 min gap, so it may well be
        // the next one, and its own time stands.
        val soon = TripRoute(listOf(twoLegs.legs[0], leg("blue", "B", "C", departs = 17, arrives = 27)))
        val live = mapOf(0 to listOf(train("red", 2)), 1 to listOf(0L, 3L, 6L, 9L).map { train("blue", it) })
        val estimate = TripTiming.estimate(soon, now, Duration.ZERO, { live[it] })
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(17), estimate.legs[1].board)
        assertEquals(at(27), estimate.arrival)
        assertEquals(Duration.ZERO, estimate.slack)
        // Nor does a line whose predictions don't show it running every few minutes lose the
        // Planner's train, however far off it is: nothing says a train comes sooner.
        val late = TripRoute(listOf(twoLegs.legs[0], leg("blue", "B", "C", departs = 40, arrives = 50)))
        val sparse = mapOf(0 to listOf(train("red", 2)), 1 to listOf(train("blue", 1), train("blue", 14)))
        val planned = TripTiming.estimate(late, now, Duration.ZERO, { sparse[it] })
        assertEquals(at(40), planned.legs[1].board)
        assertEquals(at(50), planned.arrival)
    }

    @Test
    fun `a missed Planner departure withholds the arrival rather than guess a wait`() {
        val live = mapOf(0 to listOf(train("red", 12)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] })
        // Red at 12 reaches B at 22, ready at 25: the Planner's blue at 20 is gone, and none is live.
        assertEquals(TripTiming.Basis.UNKNOWN, estimate.basis)
        assertNull(estimate.arrival)
        assertNull(estimate.duration)
    }

    @Test
    fun `a frequent line past its live predictions is boarded on arrival, as an estimate`() {
        // Red at 12 reaches B ready at 25; blue's predictions end at 22, and the Planner's blue at 20 is gone.
        val live = mapOf(0 to listOf(train("red", 12)), 1 to listOf(train("blue", 14), train("blue", 18), train("blue", 22)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] })
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(25), estimate.legs[1].board)
        assertFalse(estimate.legs[1].live)
        assertEquals(at(35), estimate.arrival)
        // The wait it assumes away is up to the line's gap between trains: 4 min.
        assertEquals(Duration.ofMinutes(4), estimate.slack)
        assertEquals(at(39), estimate.latest)
        // Not a line with no trains (not running, or done for the night), nor one whose arrivals failed.
        assertEquals(TripTiming.Basis.UNKNOWN, TripTiming.estimate(twoLegs, now, Duration.ZERO, { if (it == 0) live[0] else emptyList() }).basis)
        assertEquals(TripTiming.Basis.UNKNOWN, TripTiming.estimate(twoLegs, now, Duration.ZERO, { if (it == 0) live[0] else null }).basis)
        // Nor one whose predictions don't show it running every few minutes: too few trains, or too
        // far apart, may be the night's last.
        for (sparse in listOf(listOf(8L), listOf(8L, 12L), listOf(2L, 14L, 22L))) {
            val thin = mapOf(0 to listOf(train("red", 12)), 1 to sparse.map { train("blue", it) })
            assertEquals(sparse.toString(), TripTiming.Basis.UNKNOWN, TripTiming.estimate(twoLegs, now, Duration.ZERO, { thin[it] }).basis)
        }
        // Nor one whose last refresh failed, its earlier arrivals held.
        assertEquals(TripTiming.Basis.UNKNOWN, TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] }, current = { it != 1 }).basis)
        // Nor one not running, whose last predictions may outlive it.
        assertEquals(TripTiming.Basis.UNKNOWN, TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] }, notRunning = setOf("blue")).basis)
        // Nor an infrequent one, where the wait could matter.
        val rail = TripRoute(listOf(twoLegs.legs[0], twoLegs.legs[1].copy(mode = "national-rail")))
        assertEquals(TripTiming.Basis.UNKNOWN, TripTiming.estimate(rail, now, Duration.ZERO, { live[it] }).basis)
    }

    @Test
    fun `a frequent line past its predictions is judged only on trains from stops that refreshed`() {
        // Red at 12 reaches B ready at 25, past the Planner's blue at 20. Blue's own stop failed, its
        // trains held; another line riding the leg refreshed with trains every three minutes.
        val held = listOf(train("blue", 14), train("blue", 18), train("blue", 22))
        val other = listOf(train("green", 15), train("green", 18), train("green", 21))
        val live = mapOf(0 to listOf(train("red", 12)), 1 to held + other)
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] }, refreshed = { if (it == 1) other else live.getValue(it) })
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(25), estimate.legs[1].board)
        // The wait it assumes away is up to the refreshed trains' gap, not the held ones' 4 min.
        assertEquals(Duration.ofMinutes(3), estimate.slack)
        // Held trains alone never show it frequent: they may have stopped since.
        val sparse = listOf(train("green", 15))
        val thin = mapOf(0 to listOf(train("red", 12)), 1 to held + sparse)
        assertEquals(TripTiming.Basis.UNKNOWN, TripTiming.estimate(twoLegs, now, Duration.ZERO, { thin[it] }, refreshed = { if (it == 1) sparse else thin.getValue(it) }).basis)
    }

    // TfL predicts a bus only about half an hour ahead: one every eight minutes shows two.
    @Test
    fun `a frequent bus past its live predictions is boarded on arrival, with a range`() {
        val bus = TripRoute(listOf(twoLegs.legs[0], twoLegs.legs[1].copy(mode = "bus")))
        // Red at 12 reaches B ready at 25; the bus's two predictions end at 22, the Planner's at 20 gone.
        val live = mapOf(0 to listOf(train("red", 12)), 1 to listOf(train("blue", 14), train("blue", 22)))
        val estimate = TripTiming.estimate(bus, now, Duration.ZERO, { live[it] })
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(25), estimate.legs[1].board)
        assertEquals(at(35), estimate.arrival)
        // Two predictions can't say the gap between buses: the wait is up to the longest a frequent one has.
        assertEquals(TripTiming.FREQUENT_MAX_GAP, estimate.slack)
        // Not one bus alone, nor two too far apart, which may be the night's last.
        for (sparse in listOf(listOf(22L), listOf(8L, 22L))) {
            val thin = mapOf(0 to listOf(train("red", 12)), 1 to sparse.map { train("blue", it) })
            assertEquals(sparse.toString(), TripTiming.Basis.UNKNOWN, TripTiming.estimate(bus, now, Duration.ZERO, { thin[it] }).basis)
        }
        // A tram still isn't, however its predictions look: a wait there can be long.
        val tram = TripRoute(listOf(twoLegs.legs[0], twoLegs.legs[1].copy(mode = "tram")))
        assertEquals(TripTiming.Basis.UNKNOWN, TripTiming.estimate(tram, now, Duration.ZERO, { live[it] }).basis)
    }

    // Near a line's start TfL predicts only the trains already running, so a line every few minutes
    // can show nothing past a quarter of an hour out, all day.
    @Test
    fun `a frequent line whose predictions end soon is still boarded on arrival`() {
        val live = mapOf(
            0 to listOf(train("red", 12)),
            1 to listOf(0L, 1L, 2L, 3L, 11L, 13L).map { train("blue", it) },
        )
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] })
        // Ready at 25, past the last prediction at 13 and the Planner's blue at 20.
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(25), estimate.legs[1].board)
        assertEquals(at(35), estimate.arrival)
        // Its longer typical gap, the middle half of them, is 2 min.
        assertEquals(Duration.ofMinutes(2), estimate.slack)
    }

    // A longer wait for a frequent line carries through the connections after it: it can miss the
    // next leg's train, so the latest arrival is timed again from there, not the gap added at the end.
    @Test
    fun `a frequent line's longest wait carries through later connections`() {
        // The rider reaches red at 10, past its predictions (every 3 min) and the Planner's 5: boarded
        // at 10, at B at 20, ready at 23 for blue at 24, there at 34.
        val live = mapOf(0 to listOf(0L, 3L, 6L, 9L).map { train("red", it) }, 1 to listOf(train("blue", 24), train("blue", 30)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ofMinutes(10), { live[it] })
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(34), estimate.arrival)
        // Waiting the whole 3 min gap for red, the rider is ready at 26, misses blue at 24 and takes
        // the 30: there at 40, not 37.
        assertEquals(Duration.ofMinutes(6), estimate.slack)
        assertEquals(at(40), estimate.latest)
        // With no later blue known, the longer wait misses the only one: no latest to give.
        val only = mapOf(0 to live.getValue(0), 1 to listOf(train("blue", 24)))
        val open = TripTiming.estimate(twoLegs, now, Duration.ofMinutes(10), { only[it] })
        assertEquals(at(34), open.arrival)
        assertNull(open.slack)
        assertNull(open.latest)
    }

    @Test
    fun `an arrival timed from trains or the Planner has no slack`() {
        val live = mapOf(0 to listOf(train("red", 2)), 1 to listOf(train("blue", 16)))
        assertEquals(Duration.ZERO, TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] }).slack)
        // Nor one the Planner times, nor one withheld.
        assertEquals(Duration.ZERO, TripTiming.estimate(twoLegs, now, Duration.ZERO, { if (it == 0) live[0] else null }).slack)
        val withheld = TripTiming.estimate(twoLegs, now, Duration.ZERO, { if (it == 0) listOf(train("red", 12)) else null })
        assertEquals(Duration.ZERO, withheld.slack)
        assertNull(withheld.latest)
    }

    @Test
    fun `a line runs frequently when its predicted trains come every few minutes`() {
        assertTrue(TripTiming.runsFrequently(listOf(9L, 1L, 5L).map { train("blue", it) }))
        assertTrue(TripTiming.runsFrequently(listOf(0L, 10L, 20L).map { train("blue", it) }))
        assertFalse(TripTiming.runsFrequently(listOf(0L, 10L, 21L).map { train("blue", it) }))
        assertFalse(TripTiming.runsFrequently(listOf(1L, 2L).map { train("blue", it) }))
        // One train listed twice isn't two: two distinct times say nothing of the gap between trains.
        assertFalse(TripTiming.runsFrequently(listOf(1L, 1L, 4L).map { train("blue", it) }))
        assertFalse(TripTiming.runsFrequently(emptyList()))
    }

    @Test
    fun `a walk between stations takes the Planner's minutes`() {
        val route = TripRoute(
            listOf(
                leg("red", "A", "B", departs = 5, arrives = 15),
                walk("B", "B2", departs = 15, arrives = 20),
                leg("blue", "B2", "C", departs = 22, arrives = 30),
            ),
        )
        val live = mapOf(0 to listOf(train("red", 3)), 2 to listOf(train("blue", 19), train("blue", 21)))
        val estimate = TripTiming.estimate(route, now, Duration.ZERO, { live[it] })
        // Red at 3 reaches B at 13, the walk ends at 18: blue at 19 is caught.
        assertEquals(at(19), estimate.legs[2].board)
        assertEquals(at(27), estimate.arrival)
        assertEquals(listOf("red", "blue"), route.rides.map { it.lineId })
    }

    @Test
    fun `a line whose status couldn't be checked marks the route unchecked`() {
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { null }, unknown = setOf("red"))
        assertTrue(estimate.unchecked)
        assertFalse(estimate.blocked)
    }

    @Test
    fun `a line not running blocks the route`() {
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { null }, notRunning = setOf("blue"))
        assertTrue(estimate.blocked)
    }

    @Test
    fun `a ride another line takes is ranked by that line, not its Planner line`() {
        // Blue (leg 1) is closed or unchecked, but another running line takes that ride.
        val covered = TripTiming.estimate(twoLegs, now, Duration.ZERO, { null }, notRunning = setOf("blue"), otherLine = { it == 1 })
        assertFalse(covered.blocked)
        val checked = TripTiming.estimate(twoLegs, now, Duration.ZERO, { null }, unknown = setOf("blue"), otherLine = { it == 1 })
        assertFalse(checked.unchecked)
        // Another line on red's ride (leg 0) doesn't stand in for blue's.
        assertTrue(TripTiming.estimate(twoLegs, now, Duration.ZERO, { null }, notRunning = setOf("blue"), otherLine = { it == 0 }).blocked)
        assertTrue(TripTiming.estimate(twoLegs, now, Duration.ZERO, { null }, unknown = setOf("blue"), otherLine = { it == 0 }).unchecked)
        // Withheld with nothing to time blue's ride, the reason is its other lines' want of trains,
        // not blue not running: the ride no longer waits on blue.
        val red = mapOf(0 to listOf(train("red", 6)))
        fun withheld(otherLine: (Int) -> Boolean) = TripTiming.estimate(
            twoLegs, now, Duration.ZERO, { red[it] }, notRunning = setOf("blue"), timetabled = { it == 0 }, otherLine = otherLine,
        ).withheld?.reason
        assertEquals(TripTiming.Reason.NO_LIVE, withheld { it == 1 })
        assertEquals(TripTiming.Reason.NOT_RUNNING, withheld { false })
    }

    @Test
    fun `a closed stop blocks the route and an unchecked one marks it unchecked, whatever its lines`() {
        val live = mapOf(0 to listOf(train("red", 6)), 1 to listOf(train("blue", 22)))
        val closed = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] }, stops = TripClosures.Standing.CLOSED)
        assertTrue(closed.blocked)
        assertFalse(closed.unchecked)
        // Still timed: a closure ranks the route, it doesn't withhold its arrival.
        assertEquals(TripTiming.Basis.LIVE, closed.basis)
        val unchecked = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] }, stops = TripClosures.Standing.UNCHECKED)
        assertTrue(unchecked.unchecked)
        assertFalse(unchecked.blocked)
        val open = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] })
        assertEquals(listOf(open, unchecked, closed), TripTiming.rank(listOf(closed, unchecked, open)))
    }

    @Test
    fun `ranks usable before unchecked before blocked, live before estimated before withheld, then earliest`() {
        fun estimate(basis: TripTiming.Basis, arrival: Long?, blocked: Boolean = false) =
            TripTiming.Estimate(twoLegs, basis, arrival?.let(::at), emptyList(), blocked, now)
        val blockedLive = estimate(TripTiming.Basis.LIVE, 10, blocked = true)
        val earlyEstimate = estimate(TripTiming.Basis.ESTIMATED, 20)
        val lateLive = estimate(TripTiming.Basis.LIVE, 30)
        val earlyLive = estimate(TripTiming.Basis.LIVE, 25)
        val unknown = estimate(TripTiming.Basis.UNKNOWN, null)
        val uncheckedLive = TripTiming.Estimate(twoLegs, TripTiming.Basis.LIVE, at(5), emptyList(), false, now, unchecked = true)
        assertEquals(
            listOf(earlyLive, lateLive, earlyEstimate, unknown, uncheckedLive, blockedLive),
            TripTiming.rank(listOf(blockedLive, uncheckedLive, unknown, earlyEstimate, lateLive, earlyLive)),
        )
    }

    @Test
    fun `an unchecked route a live train times ranks on its arrival, one with none below the checked`() {
        // The Planner's other way, checked and open but slower, timed from its timetable.
        val slower = TripTiming.estimate(TripRoute(listOf(leg("blue", "A", "C", departs = 5, arrives = 50))), now, Duration.ZERO, { null })
        assertEquals(TripTiming.Basis.ESTIMATED, slower.basis)
        // A stop along the way couldn't be checked, but a live red train times the first ride.
        val seen = TripTiming.estimate(
            twoLegs, now, Duration.ZERO, { if (it == 0) listOf(train("red", 6)) else null }, stops = TripClosures.Standing.UNCHECKED,
        )
        assertTrue(seen.unchecked)
        assertFalse(seen.doubted)
        assertEquals(TripTiming.Basis.ESTIMATED, seen.basis)
        assertEquals(at(30), seen.arrival)
        assertEquals(listOf(seen, slower), TripTiming.rank(listOf(slower, seen)))
        // With no live train to time it by, it stays below every route checked and open.
        val blind = TripTiming.estimate(twoLegs, now, Duration.ZERO, { null }, stops = TripClosures.Standing.UNCHECKED)
        assertTrue(blind.doubted)
        assertEquals(listOf(slower, blind), TripTiming.rank(listOf(blind, slower)))
        // So does a line whose status couldn't be checked, the same way.
        val lineSeen = TripTiming.estimate(twoLegs, now, Duration.ZERO, { if (it == 0) listOf(train("red", 6)) else null }, unknown = setOf("blue"))
        assertEquals(listOf(lineSeen, slower), TripTiming.rank(listOf(slower, lineSeen)))
        // Arriving together, the one checked comes first.
        val checked = TripTiming.estimate(twoLegs, now, Duration.ZERO, { if (it == 0) listOf(train("red", 6)) else null })
        assertEquals(listOf(checked, seen), TripTiming.rank(listOf(seen, checked)))
        // Still never blocked-tier, and a route that can't be ridden stays last.
        val closed = TripTiming.estimate(twoLegs, now, Duration.ZERO, { if (it == 0) listOf(train("red", 6)) else null }, stops = TripClosures.Standing.CLOSED)
        assertEquals(listOf(seen, slower, blind, closed), TripTiming.rank(listOf(closed, blind, slower, seen)))
    }

    @Test
    fun `an estimate ranks above a live route only when it beats it even at its latest`() {
        // A live first ride, then a frequent line boarded as the rider gets there, against a route
        // live throughout (maintainer, 2026-09-30).
        val timed = listOf(TripTiming.LegTiming(at(5), at(15), null, live = true), TripTiming.LegTiming(at(17), at(22), null, live = false))
        fun estimated(arrival: Long, slack: Long?, legs: List<TripTiming.LegTiming> = timed) = TripTiming.Estimate(
            twoLegs, TripTiming.Basis.ESTIMATED, at(arrival), legs, false, now, slack = slack?.let { Duration.ofMinutes(it) },
        )
        val live = TripTiming.Estimate(twoLegs, TripTiming.Basis.LIVE, at(35), emptyList(), false, now)
        // Latest 32, before 35: first however the wait falls.
        val faster = estimated(22, 10)
        assertEquals(listOf(faster, live), TripTiming.rank(listOf(live, faster)))
        // Latest 35 or 38: it could be no sooner, so the live route stays first.
        assertEquals(listOf(live, estimated(25, 10)), TripTiming.rank(listOf(estimated(25, 10), live)))
        assertEquals(listOf(live, estimated(28, 10)), TripTiming.rank(listOf(estimated(28, 10), live)))
        // No latest to give (a longer wait could miss a connection): below, however early.
        assertEquals(listOf(live, estimated(10, null)), TripTiming.rank(listOf(estimated(10, null), live)))
        // Timed from the timetable alone, with no live train: below, however early.
        val planned = estimated(10, 0, legs = timed.map { it.copy(live = false) })
        assertEquals(listOf(live, planned), TripTiming.rank(listOf(planned, live)))
        // Estimates keep their own order: one arriving later never passes an earlier one to get
        // ahead, however sure its own latest.
        val later = estimated(24, 0)
        val earlier = estimated(20, 20)
        assertEquals(listOf(live, earlier, later), TripTiming.rank(listOf(later, live, earlier)))
        // And a route that can't be ridden stays last, whatever its timing.
        val blocked = faster.copy(blocked = true)
        assertEquals(listOf(live, blocked), TripTiming.rank(listOf(blocked, live)))
    }

    @Test
    fun `of two routes arriving together, the one with fewer changes ranks first`() {
        val direct = TripRoute(listOf(leg("blue", "A", "C", departs = 5, arrives = 30)))
        val changing = TripTiming.Estimate(twoLegs, TripTiming.Basis.LIVE, at(30), emptyList(), false, now)
        val straight = TripTiming.Estimate(direct, TripTiming.Basis.LIVE, at(30), emptyList(), false, now)
        assertEquals(listOf(straight, changing), TripTiming.rank(listOf(changing, straight)))
    }

    @Test
    fun `a route with more changes is kept only when it's faster`() {
        val direct = TripRoute(listOf(leg("blue", "A", "C", departs = 5, arrives = 30)))
        fun estimate(route: TripRoute, arrival: Long?, basis: TripTiming.Basis = TripTiming.Basis.LIVE, blocked: Boolean = false, unchecked: Boolean = false) =
            TripTiming.Estimate(route, basis, arrival?.let(::at), emptyList(), blocked, now, unchecked = unchecked)
        val straight = estimate(direct, 30)
        // No later, or the same time: the change buys nothing.
        assertEquals(listOf(straight), TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35), straight)))
        assertEquals(listOf(straight), TripTiming.withoutSlowerChanges(listOf(straight, estimate(twoLegs, 30))))
        // Sooner: worth the change.
        val faster = estimate(twoLegs, 25)
        assertEquals(listOf(faster, straight), TripTiming.withoutSlowerChanges(listOf(faster, straight)))
        // Only a route stood behind as far beats one: an estimate, a blocked or an unchecked route
        // never hides a live one, and a withheld arrival is never compared.
        assertEquals(2, TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35), estimate(direct, 30, TripTiming.Basis.ESTIMATED))).size)
        assertEquals(2, TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35), estimate(direct, 30, blocked = true))).size)
        assertEquals(2, TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35), estimate(direct, 30, unchecked = true))).size)
        assertEquals(2, TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, null, TripTiming.Basis.UNKNOWN), straight)).size)
        assertEquals(2, TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35), estimate(direct, null, TripTiming.Basis.UNKNOWN))).size)
        // The tiers as ranked: an unchecked route that can be ridden beats a slower one that can't,
        // and a checked route beats an unchecked one even when it's only estimated.
        val uncheckedDirect = estimate(direct, 30, unchecked = true)
        assertEquals(listOf(uncheckedDirect), TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35, blocked = true), uncheckedDirect)))
        val estimatedDirect = estimate(direct, 30, TripTiming.Basis.ESTIMATED)
        assertEquals(listOf(estimatedDirect), TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35, unchecked = true), estimatedDirect)))
        // Stricter than the ranking: an unchecked route a live train times, ranked among the checked,
        // still never leaves off a route checked open.
        val seenDirect = TripTiming.Estimate(
            direct, TripTiming.Basis.LIVE, at(30), listOf(TripTiming.LegTiming(at(5), at(30), null, true)), false, now, unchecked = true,
        )
        assertFalse(seenDirect.doubted)
        assertEquals(2, TripTiming.withoutSlowerChanges(listOf(estimate(twoLegs, 35), seenDirect)).size)
    }

    @Test
    fun `a leg the Planner didn't plan counts only on a live train`() {
        val through = leg("blue", "A", "C", departs = 5, arrives = 30)
        val route = TripRoute(listOf(walk("Here", "A", 0, 2), through))
        val planned = twoLegs.legs.toSet()
        fun timed(train: Departure?) = TripTiming.Estimate(
            route, TripTiming.Basis.ESTIMATED, at(30),
            listOf(TripTiming.LegTiming(now, at(2), null, false), TripTiming.LegTiming(at(5), at(30), train, train != null)),
            false, now,
        )
        val caught = timed(train("blue", 5))
        assertEquals(listOf(caught), TripTiming.withoutUnvouchedLegs(listOf(caught), planned))
        // On the Planner times it carries as a placeholder: dropped.
        assertTrue(TripTiming.withoutUnvouchedLegs(listOf(timed(null)), planned).isEmpty())
        // A planned leg falls back to the Planner's times as ever.
        val plannedRoute = TripTiming.Estimate(twoLegs, TripTiming.Basis.ESTIMATED, at(30), emptyList(), false, now)
        assertEquals(listOf(plannedRoute), TripTiming.withoutUnvouchedLegs(listOf(plannedRoute), planned))
    }

    @Test
    fun `a leg the Planner didn't plan is timed only by a live train`() {
        // The ride's Planner times are a placeholder: they'd time the route as estimated from them.
        val through = leg("blue", "A", "C", departs = 5, arrives = 30)
        val route = TripRoute(listOf(walk("Here", "A", 0, 2), through))
        val withTrain = TripTiming.estimate(route, now, Duration.ZERO, { if (it == 1) listOf(train("blue", 6)) else null }, timetabled = { it != 1 })
        assertEquals(TripTiming.Basis.LIVE, withTrain.basis)
        assertEquals(at(31), withTrain.arrival)
        // None predicted: the arrival is withheld, saying why, rather than timed from the placeholder.
        val none = TripTiming.estimate(route, now, Duration.ZERO, { if (it == 1) emptyList() else null }, timetabled = { it != 1 })
        assertEquals(TripTiming.Basis.UNKNOWN, none.basis)
        assertEquals(null, none.arrival)
        assertEquals(null, none.legs[1].board)
        assertEquals(TripTiming.Reason.NO_TRAINS, none.withheld?.reason)
        // A planned leg with no train falls back to the Planner's times as ever.
        val planned = TripTiming.estimate(route, now, Duration.ZERO, { if (it == 1) emptyList() else null })
        assertEquals(TripTiming.Basis.ESTIMATED, planned.basis)
        assertEquals(at(30), planned.arrival)
    }

    @Test
    fun `the walk to the first stop is estimated at the Planner's pace`() {
        assertEquals(Duration.ZERO, TripTiming.accessWalk(0.0))
        // 400 m * 1.4 / 1.25 m/s = 448 s: rounded up to 8 min.
        assertEquals(Duration.ofMinutes(8), TripTiming.accessWalk(400.0))
        // A walk the Planner timed at 18 min on foot (1130 m in a straight line, measured 2026-09-28)
        // reads 22, nearer it than the old fixed pace's 24: a straight line can't tell how direct the
        // streets are, so the pace matches the Planner's over many walks rather than each one.
        assertEquals(Duration.ofMinutes(22), TripTiming.accessWalk(1130.0))
    }

    @Test
    fun `the walk to the first stop is timed at the rider's walking speed`() {
        // Medium is the default.
        assertEquals(TripTiming.accessWalk(400.0), TripTiming.accessWalk(400.0, WalkingSpeed.AVERAGE))
        // 400 m * 1.4 / 0.9 m/s = 622 s: 11 min; / 1.6 m/s = 350 s: 6 min.
        assertEquals(Duration.ofMinutes(11), TripTiming.accessWalk(400.0, WalkingSpeed.SLOW))
        assertEquals(Duration.ofMinutes(6), TripTiming.accessWalk(400.0, WalkingSpeed.FAST))
        // No walk is no walk at any pace.
        assertEquals(Duration.ZERO, TripTiming.accessWalk(0.0, WalkingSpeed.SLOW))
    }

    @Test
    fun `picks out lines not running`() {
        val statuses = listOf(
            LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"),
            LineStatus("blue", 2, "Suspended"),
            LineStatus("green", 6, "Severe Delays"),
        )
        assertEquals(setOf("blue"), TripTiming.notRunning(statuses))
    }

    @Test
    fun `a withheld arrival says which leg and why`() {
        // Red at 2, an 11 min run and a 3 min change: ready for the bus at 16, past both its
        // predictions (1 and 12, too far apart to read as frequent) and the Planner's 12.
        val route = TripRoute(
            listOf(
                leg("red", "A", "B", departs = 1, arrives = 12, change = 3),
                leg("9", "B", "C", departs = 12, arrives = 20, mode = "bus"),
            ),
        )
        val live = mapOf(0 to listOf(train("red", 2)), 1 to listOf(train("9", 1), train("9", 12)))
        val estimate = TripTiming.estimate(route, now, Duration.ZERO, { live[it] })
        assertEquals(TripTiming.Basis.UNKNOWN, estimate.basis)
        val withheld = estimate.withheld!!
        assertEquals(1, withheld.leg)
        assertEquals(TripTiming.Reason.INFREQUENT, withheld.reason)
        assertEquals(2, withheld.predictions)
        assertEquals(Duration.ofMinutes(4), withheld.lastBefore)
        assertEquals(Duration.ofMinutes(11), withheld.gap)
        assertEquals(Duration.ofMinutes(4), withheld.missedBy)
        assertEquals(
            "leg 2 (bus 9): infrequent, 2 predicted, last 4 min before reach, gap 11 min; Planner's missed by 4 min",
            withheld.describe(),
        )
    }

    @Test
    fun `a withheld arrival names no live times, a failed refresh, and a line not running apart`() {
        val live = mapOf(0 to listOf(train("red", 2)))
        // Ready for blue at 16, after the Planner's blue at 10 and any train predicted.
        val route = TripRoute(listOf(leg("red", "A", "B", departs = 1, arrives = 12, change = 3), leg("blue", "B", "C", departs = 10, arrives = 30)))
        fun reason(trains: List<Departure>?, notRunning: Set<String> = emptySet(), current: Boolean = true) =
            TripTiming.estimate(route, now, Duration.ZERO, { if (it == 0) live[0] else trains }, notRunning, current = { it == 0 || current }).withheld?.reason
        assertEquals(TripTiming.Reason.NO_LIVE, reason(null))
        assertEquals(TripTiming.Reason.NO_TRAINS, reason(emptyList()))
        assertEquals(TripTiming.Reason.FAILED, reason(listOf(train("blue", 3)), current = false))
        assertEquals(TripTiming.Reason.NOT_RUNNING, reason(listOf(train("blue", 3)), notRunning = setOf("blue")))
        // A first fetch that failed with nothing held reads as failed, not as no live times.
        assertEquals(TripTiming.Reason.FAILED, reason(null, current = false))
    }

    @Test
    fun `trains predicted but not vouched for are told apart from none predicted`() {
        val live = mapOf(0 to listOf(train("red", 2)))
        val route = TripRoute(listOf(leg("red", "A", "B", departs = 1, arrives = 12, change = 3), leg("blue", "B", "C", departs = 10, arrives = 30)))
        fun withheld(trains: List<Departure>?, predicted: Int) = TripTiming.estimate(
            route, now, Duration.ZERO, { if (it == 0) live[0] else trains }, predicted = { if (it == 0) 1 else predicted },
        ).withheld!!
        // Its route still loading: three predicted, none vouched to call at C.
        assertEquals(TripTiming.Reason.NOT_VOUCHED, withheld(emptyList(), 3).reason)
        assertEquals(3, withheld(emptyList(), 3).predictions)
        assertEquals(TripTiming.Reason.NOT_VOUCHED, withheld(null, 3).reason)
        assertEquals(TripTiming.Reason.NO_TRAINS, withheld(emptyList(), 0).reason)
    }

    @Test
    fun `an arrival not withheld carries no reason`() {
        val live = mapOf(0 to listOf(train("red", 2)), 1 to listOf(train("blue", 16)))
        assertNull(TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] }).withheld)
    }
}
