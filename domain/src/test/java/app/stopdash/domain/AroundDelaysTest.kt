package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic stops and lines only. */
class AroundDelaysTest {
    private val now = Instant.parse("2026-10-08T14:00:00Z")

    private fun leg(lineId: String, mode: String, departs: Long, arrives: Long) = TripLeg(
        mode = mode,
        lineId = lineId,
        lineName = lineId,
        fromId = "A",
        fromName = "A",
        toId = "B",
        toName = "B",
        departure = now.plus(Duration.ofMinutes(departs)),
        arrival = now.plus(Duration.ofMinutes(arrives)),
        path = emptyList(),
    )

    private fun walk(departs: Long, arrives: Long) = leg("", TripLeg.WALKING, departs, arrives)

    private val tubeThenBus = TripRoute(listOf(walk(0, 3), leg("northern", "tube", 5, 20), leg("43", "bus", 22, 35)))
    private val railThenTube = TripRoute(listOf(walk(0, 5), leg("thameslink", "national-rail", 8, 16), leg("northern", "tube", 18, 30), leg("43", "bus", 32, 40)))

    @Test
    fun `only severe delays count as delayed`() {
        val statuses = listOf(LineStatus("northern", 6, "Severe Delays"), LineStatus("victoria", 9, "Minor Delays"), LineStatus("43", 10, "Good Service"))
        assertEquals(setOf("northern"), AroundDelays.delayed(statuses))
        // Severe delays behind a worse alert shown (a part closure) still count.
        val closedAndDelayed = LineStatus(
            "central", 5, "Part Closure",
            underWay = listOf(LineAlert(5, "Part Closure", "Closed between A and B."), LineAlert(6, "Severe Delays", "Severe delays.")),
        )
        assertEquals(setOf("central"), AroundDelays.delayed(listOf(closedAndDelayed)))
        // A part closure alone doesn't.
        assertEquals(emptySet<String>(), AroundDelays.delayed(listOf(closedAndDelayed.copy(underWay = closedAndDelayed.underWay.take(1)))))
    }

    @Test
    fun `the soonest route is the one arriving first by the Planner's times`() {
        assertEquals(tubeThenBus, AroundDelays.soonest(listOf(railThenTube, tubeThenBus)))
        assertNull(AroundDelays.soonest(emptyList()))
        assertEquals(setOf("northern", "43"), AroundDelays.linesOf(tubeThenBus))
    }

    @Test
    fun `the delayed rides' modes are left out, only those still planned over`() {
        assertEquals(setOf("tube"), AroundDelays.modesToLeaveOut(tubeThenBus, setOf("northern"), TripModes.DEFAULT))
        // Nothing delayed on the route, or nothing delayed at all: nothing to ask.
        assertEquals(emptySet<String>(), AroundDelays.modesToLeaveOut(tubeThenBus, setOf("victoria"), TripModes.DEFAULT))
        assertEquals(emptySet<String>(), AroundDelays.modesToLeaveOut(tubeThenBus, emptySet(), TripModes.DEFAULT))
        // Its group already off (a stored choice the route predates): already left out.
        val noUnderground = TripModes(setOf("tube"))
        assertEquals(emptySet<String>(), AroundDelays.modesToLeaveOut(tubeThenBus, setOf("northern"), noUnderground))
    }

    @Test
    fun `a bus-only trip leaves the bus out and still plans over what no group holds`() {
        // Only buses ride: leaving the bus out still leaves the cable car and replacement buses to ride.
        val onlyBus = ModeGroups.ALL.filter { it.key != "bus" }.fold(TripModes.DEFAULT) { m, g -> m.with(g, ride = false) }
        assertEquals(setOf("bus"), AroundDelays.modesToLeaveOut(tubeThenBus, setOf("43"), onlyBus))
    }
}
