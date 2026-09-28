package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A train running on through a change is offered as a route of its own, and a route whose change buys
 * nothing is left off the list (SPEC *Trips with a change*). Synthetic lines and stops only.
 */
class TripThroughRoutesTest {
    private val now = Instant.parse("2026-09-28T14:00:00Z")

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private fun leg(lineId: String, from: String, to: String, departs: Long, arrives: Long, path: List<String>, change: Long = 0) =
        TripLeg("tube", lineId, lineId, from, from, to, to, at(departs), at(arrives), path = path, changeAfter = Duration.ofMinutes(change))

    private fun train(lineId: String, destination: String, inMinutes: Long) =
        Departure(lineId = lineId, lineName = lineId, direction = "outbound", destination = destination, platform = null, expectedArrival = at(inMinutes), mode = "tube")

    // Red one stop Aston → Beck, change, blue on to Cole; blue also runs from Aston through Beck to Cole.
    private val changing = TripRoute(
        listOf(
            leg("red", "Aston", "Beck", departs = 1, arrives = 3, path = listOf("Beck"), change = 4),
            leg("blue", "Beck", "Cole", departs = 9, arrives = 15, path = listOf("Mead", "Cole")),
        ),
    )

    private val names = listOf("Aston", "Beck", "Mead", "Cole", "Dale", "Ely", "Red End").associateWith { it }
    private val sequences: Map<String, LineSequence?> = mapOf(
        "red" to LineSequence(listOf(LineRoute("red", listOf("Aston", "Beck", "Red End"))), names),
        // Blue's other branch leaves at Beck for Ely: its trains reach Beck but never Cole.
        "blue" to LineSequence(
            listOf(
                LineRoute("to Dale", listOf("Aston", "Beck", "Mead", "Cole", "Dale")),
                LineRoute("to Ely", listOf("Aston", "Beck", "Ely")),
            ),
            names,
        ),
    )

    private val good = LineStatus.GOOD_SERVICE
    private fun state(throughIn: Long?) = TripViewModel.State(
        routes = listOf(changing),
        plannedAt = now,
        live = mapOf(
            "Aston" to TripViewModel.StopLive(listOfNotNull(train("red", "Red End", 1), train("blue", "Ely", 2), throughIn?.let { train("blue", "Dale", it) }), now),
            "Beck" to TripViewModel.StopLive(listOf(train("blue", "Dale", 9)), now),
        ),
        statuses = mapOf("red" to LineStatus("red", good, "Good Service"), "blue" to LineStatus("blue", good, "Good Service")),
    )

    private fun listed(state: TripViewModel.State): List<TripTiming.Estimate> {
        val trip = withThroughRoutes(state, sequences)
        val estimates = tripEstimates(trip, now, Duration.ZERO, sequences).orEmpty()
        val planned = state.routes.orEmpty().flatMapTo(HashSet()) { it.legs }
        return TripTiming.withoutSlowerChanges(TripTiming.withoutUnvouchedLegs(estimates, planned))
    }

    @Test
    fun `a train running through the change is its own route, and the change is dropped when it buys nothing`() {
        // Blue to Dale leaves Aston in 5 min, 8 min on board: at Cole by 13, before the change gets
        // there (red at 1, Beck at 3, the change to 7, blue at 9, Cole at 15).
        val listed = listed(state(throughIn = 5))
        val route = listed.single().route
        assertEquals(listOf("blue"), route.rides.map { it.lineId })
        assertEquals("Aston" to "Cole", route.rides.single().let { it.fromId to it.toId })
        // Timed from the train that goes there, not blue's other branch leaving sooner.
        assertEquals(at(13), listed.single().arrival)
    }

    @Test
    fun `a change that's faster stays, first, with the train through after it`() {
        // Blue to Dale leaves Aston in 20: at Cole by 28, after the change's 15.
        val listed = listed(state(throughIn = 20))
        assertEquals(listOf(listOf("red", "blue"), listOf("blue")), listed.map { estimate -> estimate.route.rides.map { it.lineId } })
    }

    @Test
    fun `no train through predicted, no route through`() {
        // Only blue's other branch is predicted at Aston: the through route would be timed on the
        // Planner's times for red, so it isn't offered, and the change stands.
        val listed = listed(state(throughIn = null))
        assertEquals(listOf(listOf("red", "blue")), listed.map { estimate -> estimate.route.rides.map { it.lineId } })
    }
}
