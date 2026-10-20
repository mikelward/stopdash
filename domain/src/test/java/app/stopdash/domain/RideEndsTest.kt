package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the trip page's rides get off ([RideEnds]), resolved through the synthetic Hill model ([HillHubs]). */
class RideEndsTest {
    private val at = Instant.parse("2026-01-01T09:00:00Z")
    private val hubs = HillHubs.model
    private val toHill = RideEnds.Destination(hubs, setOf("HUBEXA"))

    private fun ride(fromId: String, toId: String, mode: String = "national-rail") = TripLeg(
        mode = mode, lineId = "l", lineName = "L", fromId = fromId, fromName = "", toId = toId, toName = "",
        departure = at, arrival = at.plusSeconds(600),
    )

    private fun walk() = TripLeg(
        mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = "x", fromName = "", toId = "y", toName = "",
        departure = at, arrival = at.plusSeconds(60),
    )

    // Vale, then a change to Hill's rail station and a walk on.
    private val viaVale = TripRoute(listOf(ride("910GFROM", "910GVALE"), ride("910GVALE", "910GHILL"), walk()))

    @Test
    fun `a last ride the Planner gets off in the destination's hubs arrives anywhere in them`() {
        val ends = RideEnds.of(listOf(viaVale), StepFree.ANY, toHill)
        assertTrue(ends.arrivesByHub)
        val (middle, last) = ends.ends(viaVale)
        // The middle ride gets off where the Planner does.
        assertEquals(LegEnd.planned(viaVale.legs[0], StepFree.ANY), middle.second)
        // The last at its first call in Hill's hub: its other station and its bus stop too.
        assertEquals(LegEnd.Level.HUB, last.second.level)
        assertEquals(setOf("HUBEXA"), last.second.places)
        assertTrue("940GZZLUHIL" in last.second.offIds(last.first))
        assertTrue("910GHILLLL" in last.second.offIds(last.first))
        assertTrue("490000001A" in last.second.offIds(last.first))
        // The same ride timed anew (another departure) reads the same end.
        val retimed = TripRoute(viaVale.legs.map { it.copy(departure = it.departure.plusSeconds(120)) })
        assertEquals(last.second, ends.ends(retimed).last().second)
    }

    @Test
    fun `every other ride gets off where the Planner does`() {
        val planned = LegEnd.planned(viaVale, StepFree.ANY)
        // A last ride the Planner gets off outside the hub, walking on.
        val toVale = TripRoute(listOf(ride("910GFROM", "910GVALE"), walk()))
        assertEquals(LegEnd.planned(toVale, StepFree.ANY), RideEnds.of(listOf(toVale), StepFree.ANY, toHill).ends(toVale))
        // No model or no hub to arrive at (a trip to a place).
        assertEquals(planned, RideEnds.of(listOf(viaVale), StepFree.ANY, null).ends(viaVale))
        assertEquals(planned, RideEnds.of(listOf(viaVale), StepFree.ANY, RideEnds.Destination(hubs, emptySet())).ends(viaVale))
        // A step-free trip: the hub's other calls aren't checked against today's lifts yet.
        val stepFree = RideEnds.of(listOf(viaVale), StepFree.STATION, toHill)
        assertFalse(stepFree.arrivesByHub)
        assertEquals(LegEnd.planned(viaVale, StepFree.STATION), stepFree.ends(viaVale))
        // A route the ends weren't worked out for (one made since, ending at another call) is as named.
        val other = TripRoute(listOf(ride("910GFROM", "940GZZLUHIL", mode = "tube")))
        assertEquals(LegEnd.planned(other, StepFree.ANY), RideEnds.of(listOf(viaVale), StepFree.ANY, toHill).ends(other))
    }

    @Test
    fun `rides to one split area arrive by hub only where their own position puts them`() {
        // Both end at area 490G000005 with no pole: the model picks its stop by where each gets off.
        fun toArea(lat: Double) = TripRoute(
            listOf(ride("490000001A", "490G000005", mode = "bus").copy(toAt = Coordinates(lat, -0.12))),
        )
        val toHighRoad = toArea(51.5199)
        val toMillLane = toArea(51.5202)
        val ends = RideEnds.of(listOf(toHighRoad, toMillLane), StepFree.ANY, RideEnds.Destination(hubs, setOf("490000005A")))
        assertEquals(LegEnd.Level.HUB, ends.ends(toHighRoad).last().second.level)
        // Mill Lane's stop is outside the destination's hub, so it gets off where the Planner does.
        assertEquals(LegEnd.planned(toMillLane, StepFree.ANY), ends.ends(toMillLane))
    }
}
