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
    }

    @Test
    fun `a line runs frequently when its predicted trains come every few minutes`() {
        assertTrue(TripTiming.runsFrequently(listOf(9L, 1L, 5L).map { train("blue", it) }))
        assertTrue(TripTiming.runsFrequently(listOf(0L, 10L, 20L).map { train("blue", it) }))
        assertFalse(TripTiming.runsFrequently(listOf(0L, 10L, 21L).map { train("blue", it) }))
        assertFalse(TripTiming.runsFrequently(listOf(1L, 2L).map { train("blue", it) }))
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
}
