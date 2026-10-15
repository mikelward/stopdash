package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Synthetic stops and lines only: `490…` ids for London's, `210…` for one outside it. */
class BusFareTest {
    private val t = Instant.parse("2026-10-15T03:00:00Z")

    private fun at(minutes: Long) = t.plusSeconds(minutes * 60)

    private fun bus(line: String, from: String, to: String, boards: Long = 0, mode: String = "bus") =
        TripLeg(mode, line, line, from, from, to, to, at(boards), at(boards + 10))

    private fun walk(from: String, to: String, at: Long = 0) = TripLeg(TripLeg.WALKING, "", "", from, from, to, to, at(at), at(at + 2))

    private fun route(vararg legs: TripLeg, pence: Int? = null) = TripRoute(legs.toList(), pence?.let { TripFare(it) })

    @Test
    fun `a bus route left unpriced takes the bus fare the plan's routes on its line were priced at`() {
        // The report's shape: one bus the whole way, unpriced, beside priced routes changing from that same bus.
        val priced = route(walk("X", "490000001A"), bus("1", "490000001A", "490000002B"), bus("2", "490000002B", "490000003C", boards = 15), pence = 175)
        val other = route(bus("1", "490000004D", "490000002B"), bus("3", "490000002B", "490000005E", boards = 20), pence = 175)
        val unpriced = route(walk("X", "490000001A"), bus("1", "490000001A", "490000006F"), walk("490000006F", "Y", at = 40))
        val filled = BusFare.filled(listOf(priced, other, unpriced))
        assertEquals(TripFare(175), filled[2].fare)
        // The priced routes are as the Planner priced them.
        assertSame(priced, filled[0])
        assertSame(other, filled[1])
    }

    @Test
    fun `nothing changes when every route is priced`() {
        val routes = listOf(route(bus("1", "490000001A", "490000002B"), pence = 175))
        assertSame(routes, BusFare.filled(routes))
    }

    @Test
    fun `a bus line the plan never priced stays unpriced`() {
        // It may not take pay as you go at all.
        val priced = route(bus("1", "490000001A", "490000002B"), pence = 175)
        val unpriced = route(bus("9", "490000001A", "490000006F"))
        assertNull(BusFare.filled(listOf(priced, unpriced))[1].fare)
    }

    @Test
    fun `a route with a ride that isn't a bus stays unpriced`() {
        // A tube part's fare turns on its zones and the time of day.
        val priced = route(bus("1", "490000001A", "490000002B"), bus("red", "940GZZ0001", "940GZZ0002", boards = 15, mode = "tube"), pence = 300)
        val busPriced = route(bus("1", "490000001A", "490000002B"), pence = 175)
        val unpriced = route(bus("1", "490000001A", "490000002B"), bus("red", "940GZZ0001", "940GZZ0003", boards = 15, mode = "tube"))
        assertNull(BusFare.filled(listOf(priced, busPriced, unpriced))[2].fare)
    }

    @Test
    fun `a bus to or from a stop outside London stays unpriced`() {
        val priced = route(bus("1", "490000001A", "490000002B"), pence = 175)
        assertNull(BusFare.filled(listOf(priced, route(bus("1", "490000001A", "210000000X"))))[1].fare)
        assertNull(BusFare.filled(listOf(priced, route(bus("1", "210000000X", "490000002B"))))[1].fare)
    }

    @Test
    fun `a bus boarded an hour or more after the first is a second fare, so the route stays unpriced`() {
        val priced = route(bus("1", "490000001A", "490000002B"), bus("2", "490000002B", "490000003C", boards = 15), pence = 175)
        fun later(boards: Long) = route(bus("1", "490000001A", "490000002B"), bus("2", "490000002B", "490000005E", boards = boards))
        assertEquals(TripFare(175), BusFare.filled(listOf(priced, later(59)))[1].fare)
        assertNull(BusFare.filled(listOf(priced, later(60)))[1].fare)
    }

    @Test
    fun `priced bus routes that disagree leave it unpriced`() {
        val routes = listOf(
            route(bus("1", "490000001A", "490000002B"), pence = 175),
            route(bus("2", "490000002B", "490000003C"), pence = 185),
            route(bus("1", "490000001A", "490000006F")),
        )
        assertNull(BusFare.filled(routes)[2].fare)
    }

    @Test
    fun `a priced route on two fares is no measure of one`() {
        // Its second bus boarded past the hour: whatever it was priced at, it isn't the one bus fare.
        val routes = listOf(
            route(bus("1", "490000001A", "490000002B"), bus("2", "490000002B", "490000003C", boards = 70), pence = 350),
            route(bus("1", "490000001A", "490000002B"), pence = 175),
            route(bus("1", "490000001A", "490000006F")),
        )
        assertEquals(TripFare(175), BusFare.filled(routes)[2].fare)
    }

    @Test
    fun `a walk on its own stays unpriced`() {
        val routes = listOf(route(bus("1", "490000001A", "490000002B"), pence = 175), route(walk("X", "Y")))
        assertNull(BusFare.filled(routes)[1].fare)
    }
}
