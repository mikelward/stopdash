package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // The train through the change, open: as tapped on the list, and kept whole ([OpenRoute]).
    private fun openThrough(state: TripViewModel.State, sequences: Map<String, LineSequence?> = this.sequences, line: String = "blue"): OpenRoute {
        val trip = withThroughRoutes(state, sequences)
        val through = trip.routes.orEmpty().single { route -> route.rides.size == 1 && route.rides.single().lineId == line }
        return openRouteOf(through, state, sequences)
    }

    @Test
    fun `an open train through is kept with the change it's made from`() {
        val open = openThrough(state(throughIn = 5))
        assertEquals(routeKey(changing), open.plan)
        assertEquals(0, open.at)
        assertEquals("Aston" to "Cole", open.ride?.let { it.fromId to it.toId })
        // Saved and read back whole.
        assertEquals(open, OpenRoute.parse(open.encode()))
        // A planned route is kept by its key alone.
        assertEquals(OpenRoute(routeKey(changing)), openRouteOf(changing, state(throughIn = 5), sequences))
        assertEquals(routeKey(changing), OpenRoute(routeKey(changing)).encode())
    }

    @Test
    fun `an open train through the change stays timed when faster routes crowd out the change`() {
        val open = openThrough(state(throughIn = 5))
        // A re-plan with six routes getting there sooner: the change is past the cap, so nothing
        // makes the train through it...
        val faster = (1..TripViewModel.MAX_ROUTES).map { i ->
            TripRoute(listOf(leg("green", "Fast $i", "Cole", departs = 1, arrives = 4L + i, path = listOf("Cole"))))
        }
        val crowded = state(throughIn = 5).copy(routes = faster + changing)
        val route = open.routeIn(crowded.routes.orEmpty())!!
        val key = routeKey(route)
        assertFalse(withThroughRoutes(crowded, sequences).routes.orEmpty().any { routeKey(it) == key })
        // ...but while it's open, the change it's made from is kept for it, its stops fetched, and
        // the train through is timed from the route kept.
        assertTrue(changing in TripViewModel.bestOf(crowded.routes.orEmpty(), open.plan))
        val trip = withThroughRoutes(crowded, sequences, open = route)
        assertEquals(crowded.routes.orEmpty().map(::routeKey) + key, trip.routes.orEmpty().map(::routeKey))
        assertTrue(tripEstimates(trip, now, Duration.ZERO, sequences, keep = key).orEmpty().any { routeKey(it.route) == key })
    }

    @Test
    fun `an open train through with none predicted stays, its arrival withheld`() {
        val open = openThrough(state(throughIn = 5))
        // Blue to Dale is no longer predicted at Aston: the list leaves the train through off...
        val gone = state(throughIn = null)
        val route = open.routeIn(gone.routes.orEmpty())!!
        assertFalse(listed(gone).any { routeKey(it.route) == routeKey(route) })
        // ...but the plan still offers the change, so it stays open, timed with no train: its
        // arrival is withheld rather than taken from the Planner's times for the change.
        assertFalse(openRouteGone(gone, emptySet(), open))
        val trip = withThroughRoutes(gone, sequences, open = route)
        val planned = gone.routes.orEmpty().flatMapTo(HashSet()) { it.legs }
        val timed = tripEstimates(trip, now, Duration.ZERO, sequences, keep = routeKey(route), planned = planned).orEmpty()
            .single { routeKey(it.route) == routeKey(route) }
        assertEquals(TripTiming.Basis.UNKNOWN, timed.basis)
        assertEquals(null, timed.arrival)
        assertEquals(null, timed.legs.single().train)
    }

    @Test
    fun `an open train through stays open when a new plan offers it as a route of its own`() {
        val open = openThrough(state(throughIn = 5))
        // A re-plan drops the change but plans blue from Aston to Cole itself: the same lines and stops.
        val direct = TripRoute(listOf(leg("blue", "Aston", "Cole", departs = 5, arrives = 13, path = listOf("Beck", "Mead", "Cole"))))
        val replanned = state(throughIn = 5).copy(routes = listOf(direct))
        assertFalse(openRouteGone(replanned, emptySet(), open))
        assertEquals(direct, open.routeIn(replanned.routes.orEmpty()))
        // Kept past the cap by its own key as well as the change's.
        val faster = (1..TripViewModel.MAX_ROUTES).map { i ->
            TripRoute(listOf(leg("green", "Fast $i", "Cole", departs = 1, arrives = 4L + i, path = listOf("Cole"))))
        }
        assertTrue(direct in TripViewModel.bestOf(faster + direct, open.keys))
    }

    @Test
    fun `an open train through closes once the plan no longer offers the change it's made from`() {
        val open = openThrough(state(throughIn = 5))
        val replanned = state(throughIn = 5).copy(routes = listOf(TripRoute(listOf(changing.legs.first()))))
        assertTrue(openRouteGone(replanned, emptySet(), open))
        // A route no plan's change makes keeps nothing past the cap: blue from Beck is a leg of the
        // change, not a route made by joining its rides.
        val elsewhere = TripRoute(listOf(changing.legs.last()))
        val faster = (1..TripViewModel.MAX_ROUTES).map { i ->
            TripRoute(listOf(leg("green", "Fast $i", "Cole", departs = 1, arrives = 4L + i, path = listOf("Cole"))))
        }
        val crowded = state(throughIn = 5).copy(routes = faster + changing)
        val tapped = openRouteOf(elsewhere, crowded, sequences)
        assertEquals(OpenRoute(routeKey(elsewhere)), tapped)
        assertEquals(faster, TripViewModel.bestOf(faster + changing, tapped.plan))
        assertTrue(openRouteGone(crowded, emptySet(), tapped))
    }

    @Test
    fun `an open train through on a line the change doesn't ride closes once that line is hidden`() {
        // Green runs from Aston through Beck to Cole too: a train through the change on a line the
        // route it's made from doesn't ride.
        val withGreen = sequences + ("green" to LineSequence(listOf(LineRoute("green", listOf("Aston", "Beck", "Mead", "Cole"))), names))
        val state = state(throughIn = null).let { s ->
            s.copy(live = s.live + ("Aston" to TripViewModel.StopLive(s.live.getValue("Aston").departures + train("green", "Cole", 5), now)))
        }
        val open = openThrough(state, withGreen, line = "green")
        assertEquals(routeKey(changing), open.plan)
        assertFalse(openRouteGone(state, emptySet(), open))
        // With green hidden it can't be shown whatever the plan offers, so it's gone at once, though
        // the change it's made from, on red and blue, still is.
        assertTrue(openRouteGone(state, setOf(HiddenModes.lineKey("green", "Green")), open))
    }

    @Test
    fun `no train through predicted, no route through`() {
        // Only blue's other branch is predicted at Aston: the through route would be timed on the
        // Planner's times for red, so it isn't offered, and the change stands.
        val listed = listed(state(throughIn = null))
        assertEquals(listOf(listOf("red", "blue")), listed.map { estimate -> estimate.route.rides.map { it.lineId } })
    }
}
