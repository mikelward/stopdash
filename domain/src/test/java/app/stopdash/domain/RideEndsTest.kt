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
        // The middle ride gets off at Vale's station, by every record of it.
        assertEquals(LegEnd.Level.STOP, middle.second.level)
        assertEquals(setOf("910GVALE"), middle.second.places)
        assertTrue("910GVALE" in middle.second.offIds(middle.first))
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

    // Getting off at [station], by every TfL id of it.
    private fun atStation(station: String) = LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf(station)).let { it.copy(ids = hubs.idsOf(it).keys) }

    @Test
    fun `every other ride gets off at the station of the Planner's call`() {
        // A last ride the Planner gets off outside the hub, walking on: at Vale's station.
        val toVale = TripRoute(listOf(ride("910GFROM", "910GVALE"), walk()))
        val (ride, end) = RideEnds.of(listOf(toVale), StepFree.ANY, toHill).ends(toVale).single()
        assertEquals(toVale.legs[0], ride)
        assertEquals(atStation("910GVALE"), end)
        // A trip to a place, which has no hub: every ride at its station, the last too.
        val (middle, last) = RideEnds.of(listOf(viaVale), StepFree.ANY, RideEnds.Destination(hubs, emptySet())).ends(viaVale)
        assertEquals(atStation("910GVALE"), middle.second)
        assertEquals(atStation("1009001:rail"), last.second)
        assertFalse("940GZZLUHIL" in last.second.offIds(last.first))
    }

    @Test
    fun `with no model, on a step-free trip, or for a route not worked out, a ride gets off where the Planner does`() {
        val planned = LegEnd.planned(viaVale, StepFree.ANY)
        assertEquals(planned, RideEnds.of(listOf(viaVale), StepFree.ANY, null).ends(viaVale))
        // A step-free trip: a station's and a hub's other calls aren't checked against today's lifts yet.
        val stepFree = RideEnds.of(listOf(viaVale), StepFree.STATION, toHill)
        assertFalse(stepFree.arrivesByHub)
        assertEquals(LegEnd.planned(viaVale, StepFree.STATION), stepFree.ends(viaVale))
        // A route the ends weren't worked out for (one made since, ending at another call) is as named.
        val other = TripRoute(listOf(ride("910GFROM", "940GZZLUHIL", mode = "tube")))
        assertEquals(LegEnd.planned(other, StepFree.ANY), RideEnds.of(listOf(viaVale), StepFree.ANY, toHill).ends(other))
    }

    @Test
    fun `a ride changing at a station gets off at any record of it, never at the hub's other stations`() {
        // To Vale, changing at Hill's rail station, whose hub holds two tube stations too.
        val toVale = RideEnds.Destination(hubs, setOf("910GVALE"))
        val viaHill = TripRoute(listOf(ride("910GFROM", "910GHILL"), ride("910GHILL", "910GVALE")))
        val (middle, _) = RideEnds.of(listOf(viaHill), StepFree.ANY, toVale).ends(viaHill)
        assertEquals(atStation("1009001:rail"), middle.second)
        val off = middle.second.offIds(middle.first)
        assertTrue("$off", "910GHILL" in off && "910GHILLLL" in off)
        assertFalse("$off", "940GZZLUHIL" in off || "940GZZLUHLE" in off)
        // A ride the Planner gets off at the station's other record reads the same station.
        val viaLow = TripRoute(listOf(ride("910GFROM", "910GHILLLL"), ride("910GHILLLL", "910GVALE")))
        assertEquals(atStation("1009001:rail"), RideEnds.of(listOf(viaHill, viaLow), StepFree.ANY, toVale).ends(viaLow).first().second)
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
        // Mill Lane's stop is outside the destination's hub, so it gets off at that stop: never at High Road's
        // pole, though the Planner named their one area.
        val (ride, millLane) = ends.ends(toMillLane).single()
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("490000005B")), millLane.copy(ids = emptySet()))
        assertTrue("490000005B" in millLane.offIds(ride))
        assertFalse("490000005A" in millLane.offIds(ride))
    }

    // A tube ride to Hill calling at Hill East first, then out of the hub and back (a loop).
    private fun loop(vararg after: TripLeg) = TripRoute(
        listOf(
            ride("940GZZLUFRM", "940GZZLUHIL", mode = "tube").copy(
                toName = "Hill",
                path = listOf("940GZZLUAAA", "940GZZLUHLE", "940GZZLUBBB", "940GZZLUHIL"),
                pathNames = listOf("Aye", "Hill East", "Bee", "Hill"),
                toAt = Coordinates(51.501, -0.121),
            ),
        ) + after,
        fare = TripFare(290),
    )

    @Test
    fun `the Planner's last ride arriving by hub is cut and named at its first call there`() {
        val route = loop(walk())
        val cut = RideEnds.cut(listOf(route), StepFree.ANY, toHill).single()
        val ride = cut.legs.single()
        assertEquals("940GZZLUHLE", ride.toId)
        assertEquals("Hill East", ride.toName)
        // Its stop list ends there, and so its stop count.
        assertEquals(listOf("940GZZLUAAA", "940GZZLUHLE"), ride.path)
        assertEquals(listOf("Aye", "Hill East"), ride.pathNames)
        assertEquals(2, ride.stops)
        // The Planner's end's position and pair go with it; its arrival stays the latest it gets there.
        assertEquals(null, ride.toAt)
        assertEquals("", ride.toArea)
        assertEquals(route.legs[0].arrival, ride.arrival)
        // The Planner priced the train to its old end.
        assertEquals(null, cut.fare)
        // A copy to draw, naming the route it's drawn from: the route as the Planner gave it is never changed.
        assertTrue(cut.drawnFrom === route)
        assertEquals(loop(walk()), route)
        assertEquals("940GZZLUHIL", route.legs[0].toId)
        assertEquals(TripFare(290), route.fare)
        // Worked out again for the cut route, it still arrives by hub, at that call.
        val ends = RideEnds.of(listOf(cut), StepFree.ANY, toHill)
        val (_, end) = ends.ends(cut).single()
        assertEquals(LegEnd.Level.HUB, end.level)
        assertTrue("940GZZLUHLE" in end.offIds(ride))
    }

    @Test
    fun `a bus cut at a pole keeps its fare`() {
        val bus = TripRoute(
            listOf(
                ride("490000009Z", "940GZZLUHIL", mode = "bus").copy(
                    path = listOf("490000001A", "940GZZLUHIL"),
                    pathNames = listOf("Hill Station", "Hill"),
                ),
            ),
            fare = TripFare(175),
        )
        val cut = RideEnds.cut(listOf(bus), StepFree.ANY, toHill).single()
        assertEquals("490000001A", cut.legs.single().toId)
        assertEquals(TripFare(175), cut.fare)
    }

    @Test
    fun `a ride stays as planned where it has no first call in the hubs to move to`() {
        val route = loop()
        // A step-free trip, no model, or no hub to arrive at.
        // The same object, not a copy, as it isn't cut.
        assertTrue(RideEnds.cut(listOf(route), StepFree.STATION, toHill).single() === route)
        assertEquals(listOf(route), RideEnds.cut(listOf(route), StepFree.STATION, toHill))
        assertEquals(listOf(route), RideEnds.cut(listOf(route), StepFree.ANY, null))
        assertEquals(listOf(route), RideEnds.cut(listOf(route), StepFree.ANY, RideEnds.Destination(hubs, setOf("910GVALE"))))
        // Its first call in the hub is its own station (another of its records).
        val own = TripRoute(
            listOf(
                ride("910GFROM", "910GHILL").copy(path = listOf("910GAAA", "910GHILLLL", "910GHILL"), pathNames = listOf("Aye", "Hill LL", "Hill")),
            ),
        )
        assertEquals(listOf(own), RideEnds.cut(listOf(own), StepFree.ANY, toHill))
        // A first call naming the bus stop's area, its two poles: no one TfL id to get off at.
        val area = TripRoute(
            listOf(
                ride("910GFROM", "940GZZLUHIL", mode = "bus").copy(path = listOf("490G000001", "940GZZLUHIL"), pathNames = listOf("Hill Station", "Hill")),
            ),
        )
        assertEquals(listOf(area), RideEnds.cut(listOf(area), StepFree.ANY, toHill))
        // A call the Planner didn't name: nothing to show where the ride gets off.
        val unnamed = TripRoute(listOf(route.legs[0].copy(pathNames = emptyList())))
        assertEquals(listOf(unnamed), RideEnds.cut(listOf(unnamed), StepFree.ANY, toHill))
        // A middle ride ending in the hub changes there, as planned.
        val change = TripRoute(route.legs + ride("940GZZLUHIL", "910GVALE"))
        assertEquals(listOf(change), RideEnds.cut(listOf(change), StepFree.ANY, toHill))
    }

    @Test
    fun `a bus alert past the hub's first call is off the cut ride`() {
        // A bus to Hill calling first at Hill Station's pole, then on through a diverted stretch to its end. Made-up words.
        val sequence = LineSequence(
            listOf(LineRoute("Start - Gamma Terrace", listOf("490000009Z", "490000001A", "490000007B", "940GZZLUHIL"))),
            mapOf("490000009Z" to "Start", "490000001A" to "Hill Station", "490000007B" to "Beta Road", "940GZZLUHIL" to "Gamma Terrace"),
        )
        val alert = LineStatus(
            "l", 6, "Diversion",
            "EXAMPLE - ROUTE L is on diversion. Buses are not serving stops between 'Beta Road' and 'Gamma Terrace'.",
            soleAlert = true,
        )
        val bus = TripRoute(
            listOf(
                ride("490000009Z", "940GZZLUHIL", mode = "bus").copy(
                    path = listOf("490000001A", "490000007B", "940GZZLUHIL"),
                    pathNames = listOf("Hill Station", "Beta Road", "Gamma Terrace"),
                ),
            ),
        )
        // As the Planner gave it, the ride runs through the stretch.
        assertFalse(RouteDisruption.offRide(RideEnds.of(listOf(bus), StepFree.ANY, toHill).ends(bus).single(), alert, sequence))
        // Cut at Hill Station, it gets off before it.
        val cut = RideEnds.cut(listOf(bus), StepFree.ANY, toHill).single()
        assertTrue(RouteDisruption.offRide(RideEnds.of(listOf(cut), StepFree.ANY, toHill).ends(cut).single(), alert, sequence))
    }
}
