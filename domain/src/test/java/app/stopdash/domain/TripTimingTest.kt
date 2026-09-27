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
    fun `a leg with no live train falls back to the Planner while its departure is reachable`() {
        val live = mapOf(0 to listOf(train("red", 2)))
        val estimate = TripTiming.estimate(twoLegs, now, Duration.ZERO, { live[it] })
        // Ready at 15, the Planner's blue leaves at 20.
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertFalse(estimate.legs[1].live)
        assertEquals(at(30), estimate.arrival)
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
    fun `the walk to the first stop is estimated conservatively`() {
        assertEquals(Duration.ZERO, TripTiming.accessWalk(0.0))
        // 400 m * 1.4 / 1.1 m/s = 509 s: rounded up to 9 min.
        assertEquals(Duration.ofMinutes(9), TripTiming.accessWalk(400.0))
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
