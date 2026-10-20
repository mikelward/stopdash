package app.stopdash.ui

import app.stopdash.domain.LegEnd
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
}
