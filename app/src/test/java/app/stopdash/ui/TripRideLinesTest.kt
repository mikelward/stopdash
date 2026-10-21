package app.stopdash.ui

import app.stopdash.domain.LegEnd
import app.stopdash.domain.RideEnds
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineRoute
import app.stopdash.domain.Departure
import app.stopdash.domain.RideLines
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The trip page's ride lines looked up by each ride's end ([TripRideLines]). Synthetic stops and lines. */
class TripRideLinesTest {
    private val t = Instant.parse("2026-10-20T08:00:00Z")

    private fun leg(line: String, from: String, to: String) = TripLeg(
        mode = "tube", lineId = line, lineName = line, fromId = from, fromName = from, toId = to, toName = to,
        departure = t, arrival = t,
    )

    // Red from A to B, last on one route and followed by blue on the other.
    private val red = leg("red", "A", "B")
    private val alone = TripRoute(listOf(red))
    private val onward = TripRoute(listOf(red, leg("blue", "B", "C")))

    // Red getting off at a different place on each route, as arriving by hub may have it.
    private val atHub = LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUB1"))
    private val atStop = LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("B"))
    private val ends: (TripRoute) -> List<Pair<TripLeg, LegEnd>> = { route ->
        route.rides.map { ride -> ride to if (ride == red && route == alone) atHub else atStop }
    }

    @Test
    fun `a ride two routes share with different ends keeps each end's lines`() {
        val lines = rideLines(listOf(alone, onward), TripViewModel.State(routes = listOf(alone, onward)), StepFree.ANY, emptyMap(), ends = ends)
        // Not folded into one by the ride: each route's end is a key of its own.
        assertNotNull(lines[red to atHub])
        assertNotNull(lines[red to atStop])
        assertEquals(listOf(red to atHub), lines.endsOf(alone))
    }

    @Test
    fun `each route looks its ride's lines up by its own end`() {
        val green = leg("green", "A2", "B")
        val pink = leg("pink", "A3", "B")
        val lines = TripRideLines(
            mapOf(
                (red to atHub) to RideLines(listOf(red, green), listOf(red, green)),
                (red to atStop) to RideLines(listOf(red, pink), listOf(red)),
            ),
            ends,
        )
        assertEquals(listOf("red", "green"), lines.of(alone, red).legs.map { it.lineId })
        assertEquals(listOf("red", "pink"), lines.of(onward, red).legs.map { it.lineId })
        // Through the page's own lookups: a card's pill, and the poles another line shown uses.
        fun card(route: TripRoute) = listOf(TripTiming.Estimate(route, TripTiming.Basis.LIVE, t, emptyList(), false, t))
        assertEquals(listOf("red", "green"), cardRideLines(card(alone), 0, lines).map { it.lineId })
        assertEquals(listOf("red", "pink"), cardRideLines(card(onward), 0, lines).map { it.lineId })
        assertEquals(setOf("A2", "B"), otherLineStops(alone, lines))
        assertEquals(setOf("A3", "B"), otherLineStops(onward, lines))
        // A ride none were worked out for has just its Planner's line.
        assertEquals(listOf("blue"), lines.of(onward, onward.rides.last()).legs.map { it.lineId })
    }

    @Test
    fun `a line page keeps the end of the route it was found under`() {
        val lines = TripRideLines(
            mapOf(
                (red to atHub) to RideLines(listOf(red), listOf(red)),
                (red to atStop) to RideLines(listOf(red), listOf(red)),
            ),
            ends,
        )
        // Found under the route getting off at the stop: not the first ride holding it, at the hub.
        assertEquals(red to atStop, lines.holding(red, atStop)?.first)
        assertEquals(red to atHub, lines.holding(red, atHub)?.first)
        // An end no ride holding it has falls back to the first that does.
        val elsewhere = LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("Z"))
        assertEquals(red to atHub, lines.holding(red, elsewhere)?.first)
    }

    // A last ride from A to Hill's rail station, and a line from A to Hill's tube station.
    private val toHillRail = TripLeg(
        mode = "tube", lineId = "red", lineName = "red", fromId = "A", fromName = "A", toId = "910GHILL", toName = "Hill",
        departure = t, arrival = t, path = listOf("910GHILL"),
    )
    private val toHill = TripRoute(listOf(toHillRail))
    private val toHillTube = mapOf(
        "green" to LineSequence(routes = listOf(LineRoute("r", listOf("A", "940GZZLUHIL"))), stopNames = mapOf("A" to "A", "940GZZLUHIL" to "Hill (tube)")),
    )
    private val atA = TripViewModel.State(
        routes = listOf(toHill),
        live = mapOf("A" to TripViewModel.StopLive(listOf(Departure("green", "green", "", "Hill", null, t, mode = "tube")), t)),
    )

    @Test
    fun `a card's last ride arriving by hub shows a line to another station of the destination's hub`() {
        val ends = RideEnds.of(listOf(toHill), StepFree.ANY, HillModel.toHill)
        val lines = rideLines(listOf(toHill), atA, StepFree.ANY, toHillTube, ends = ends::ends)
        // Green reaches Hill at its tube station: shown beside red, named where it gets off.
        val green = lines.of(toHill, toHillRail).legs.drop(1).single()
        assertEquals("940GZZLUHIL", green.toId)
        assertEquals("Hill (tube)", green.toName)
        assertEquals(listOf("red", "green"), cardRideLines(listOf(TripTiming.Estimate(toHill, TripTiming.Basis.LIVE, t, emptyList(), false, t)), 0, lines).map { it.lineId })
        // Getting off where the Planner does, or on a step-free trip, it isn't.
        assertEquals(listOf("red"), rideLines(listOf(toHill), atA, StepFree.ANY, toHillTube).of(toHill, toHillRail).legs.map { it.lineId })
        val stepFree = RideEnds.of(listOf(toHill), StepFree.STATION, HillModel.toHill)
        assertEquals(listOf("red"), rideLines(listOf(toHill), atA, StepFree.STATION, toHillTube, ends = stepFree::ends).of(toHill, toHillRail).legs.map { it.lineId })
    }

    @Test
    fun `a train through a change reaching another station of the destination's hub is a route, arriving by hub`() {
        // Red A to M, then blue M to Hill's rail station; green from A runs on through M to Hill's tube station.
        val toM = toHillRail.copy(toId = "M", toName = "M", path = listOf("M"), arrival = t.plusSeconds(120))
        val blue = toHillRail.copy(lineId = "blue", lineName = "blue", fromId = "M", fromName = "M", departure = t.plusSeconds(300), arrival = t.plusSeconds(600))
        val changing = TripRoute(listOf(toM, blue))
        val green = mapOf("green" to LineSequence(routes = listOf(LineRoute("r", listOf("A", "M", "940GZZLUHIL"))), stopNames = emptyMap()))
        val state = atA.copy(routes = listOf(changing))
        val ends = RideEnds.of(listOf(changing), StepFree.ANY, HillModel.toHill)
        assertEquals(listOf(changing), withThroughRoutes(state, StepFree.ANY, green).routes)
        val through = withThroughRoutes(state, StepFree.ANY, green, ends = ends::ends).routes.orEmpty().drop(1).single()
        assertEquals("940GZZLUHIL", through.legs.single().toId)
    }

    @Test
    fun `a train through a change to another record of the last ride's station keeps the walk on`() {
        // Red A to M, then blue M to B and a walk on to a place; green from A runs on through M to Bl, another
        // record of B's station, where the last ride gets off at its station ([RideEnds], no hub to arrive at).
        val toM = toHillRail.copy(toId = "M", toName = "M", path = listOf("M"), arrival = t.plusSeconds(120))
        val blue = toHillRail.copy(lineId = "blue", lineName = "blue", fromId = "M", fromName = "M", toId = "B", toName = "B", path = listOf("B"),
            departure = t.plusSeconds(300), arrival = t.plusSeconds(600))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "B", "B", "P", "Place", t.plusSeconds(600), t.plusSeconds(900))
        val changing = TripRoute(listOf(toM, blue, walkOn))
        val atStation = LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("STATIONB"), ids = setOf("B", "Bl"))
        val ends = { route: TripRoute -> route.rides.map { it to if (it == blue) atStation else LegEnd.planned(it, StepFree.ANY) } }
        val green = mapOf("green" to LineSequence(routes = listOf(LineRoute("r", listOf("A", "M", "Bl"))), stopNames = emptyMap()))
        val state = atA.copy(routes = listOf(changing))
        // Getting off at the Planner's call alone, green doesn't reach it.
        assertEquals(listOf(changing), withThroughRoutes(state, StepFree.ANY, green).routes)
        // At its station, green is a way through to Bl, and the rider, not arriving by hub, still walks on.
        val through = withThroughRoutes(state, StepFree.ANY, green, ends = ends).routes.orEmpty().drop(1).single()
        assertEquals(listOf("green", ""), through.legs.map { it.lineId })
        assertEquals("Bl", through.legs.first().toId)
        assertEquals(walkOn, through.legs.last())
    }
}
