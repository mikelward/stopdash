package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic records: a hub HUBA holding stations STA1 and STA2, and a hub HUBB. */
class LegEndTest {
    private val a1 = Resolved(tflId = "910GA1", station = "STA1", hub = "HUBA")
    private val a1Other = Resolved(tflId = "940GA1", station = "STA1", hub = "HUBA")
    private val a2 = Resolved(tflId = "910GA2", station = "STA2", hub = "HUBA")
    private val b = Resolved(tflId = "910GB", station = "STB", hub = "HUBB")
    private val at = Instant.parse("2026-01-01T09:00:00Z")

    private fun ride(to: Resolved, mode: String = "tube") = TripLeg(
        mode = mode, lineId = "l", lineName = "L", fromId = "910GFROM", fromName = "", toId = to.tflId!!, toName = "",
        departure = at, arrival = at.plusSeconds(600), placed = mapOf(to.tflId!! to to),
    )

    private fun walk() = TripLeg(
        mode = TripLeg.WALKING, lineId = "", lineName = "", fromId = "x", fromName = "", toId = "y", toName = "",
        departure = at, arrival = at.plusSeconds(60),
    )

    @Test
    fun `a stop end takes another record of the station but not the hub's other station`() {
        val end = LegEnd.at(a1, StepFree.ANY)
        assertTrue(end.endsAt(a1Other))
        assertFalse(end.endsAt(a2))
    }

    @Test
    fun `a hub end takes every station in the hub`() {
        val end = LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA"))
        assertTrue(end.endsAt(a1))
        assertTrue(end.endsAt(a2))
        assertFalse(end.endsAt(b))
    }

    @Test
    fun `a pole end takes only its own poles`() {
        val area = Resolved(tflId = "490GAREA", station = "S1", hub = "H", stops = setOf("S1", "S2"), poles = setOf("490A"))
        val end = LegEnd(StepFree.ANY, LegEnd.Level.POLE, area.poles)
        assertTrue(end.endsAt(Resolved(tflId = "490A", station = "S1", hub = "H")))
        assertFalse(end.endsAt(Resolved(tflId = "490B", station = "S2", hub = "H")))
    }

    @Test
    fun `a call the model names no stop for ends at its own poles`() {
        val call = Resolved(tflId = "910GA1", station = null, hub = null)
        val end = LegEnd.at(call, StepFree.STATION)
        assertEquals(LegEnd.Level.POLE, end.level)
        assertTrue(end.endsAt(a1))
        assertFalse(end.endsAt(a2))
    }

    @Test
    fun `a split area the model couldn't place matches no child stop or hub`() {
        // Its stops and hubs list every child, for avoids and dismissals only.
        val area = Resolved(
            tflId = "490GAREA", station = null, hub = null,
            stops = setOf("STA1", "STB"), hubs = setOf("HUBA", "HUBB"), poles = setOf("490A", "490B"),
        )
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA")).endsAt(area))
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("STA1")).endsAt(area))
        // As where a ride ends, it ends at its own poles, never at a child stop.
        val end = LegEnd.at(area, StepFree.ANY)
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490A", "490B")), end)
        assertFalse(end.endsAt(a1))
    }

    @Test
    fun `a last ride ending at an unplaced split area keeps the Planner's call`() {
        val area = Resolved(
            tflId = "490GAREA", station = null, hub = null,
            stops = setOf("STA1", "STB"), hubs = setOf("HUBA", "HUBB"), poles = setOf("490A", "490B"),
        )
        val pairs = LegEnd.of(TripRoute(listOf(ride(area, mode = "bus"))), StepFree.ANY, setOf("HUBA"))
        assertEquals(LegEnd.Level.POLE, pairs.single().second.level)
    }

    @Test
    fun `a call naming several records is never where a ride gets off`() {
        // A National Rail CRS naming both of HUBA's stations: the hub agrees, but no one TfL id does.
        val crs = Resolved(tflId = null, station = null, hub = "HUBA", poles = setOf("910GA1", "910GA2"))
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA")).endsAt(crs))
        assertFalse(LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("910GA1")).endsAt(crs))
        assertTrue(LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUBA")).endsAt(a1))
    }

    @Test
    fun `a route's middle ride ends at its call and the last at the destination's hubs`() {
        val first = ride(b)
        val last = ride(a1)
        val pairs = LegEnd.of(TripRoute(listOf(walk(), first, walk(), last)), StepFree.FULLY, setOf("HUBA"))
        assertEquals(listOf(first, last), pairs.map { it.first })
        assertEquals(LegEnd(StepFree.FULLY, LegEnd.Level.STOP, setOf("STB")), pairs[0].second)
        assertEquals(LegEnd(StepFree.FULLY, LegEnd.Level.HUB, setOf("HUBA")), pairs[1].second)
    }

    @Test
    fun `a last ride the Planner gets off outside the destination's hubs ends at its call`() {
        val last = ride(b, mode = "bus")
        val pairs = LegEnd.of(TripRoute(listOf(last, walk())), StepFree.ANY, setOf("HUBA"))
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("STB")), pairs.single().second)
    }

    @Test
    fun `the same leg last in one route and in the middle of another is two pairs`() {
        val leg = ride(a1)
        val asLast = LegEnd.of(TripRoute(listOf(leg)), StepFree.ANY, setOf("HUBA")).single()
        val asMiddle = LegEnd.of(TripRoute(listOf(leg, ride(b))), StepFree.ANY, setOf("HUBB")).first()
        assertTrue(asLast != asMiddle)
    }

    @Test
    fun `a ride's planned end is the station of the Planner's call`() {
        val end = LegEnd.planned(ride(a1), StepFree.STATION)
        assertEquals(LegEnd(StepFree.STATION, LegEnd.Level.STOP, setOf("STA1")), end)
        // Another record of that station still gets the ride off there; the hub's other station doesn't.
        assertTrue(end.endsAt(a1Other))
        assertFalse(end.endsAt(a2))
    }

    @Test
    fun `a ride the model didn't place ends at its own id`() {
        val unplaced = ride(b).copy(placed = emptyMap())
        assertEquals(LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("910GB")), LegEnd.planned(unplaced, StepFree.ANY))
    }

    // Bus 1 north from the pair 490G00000002 to the pair 490G00000001 (each a road's two poles, A
    // northbound, B southbound), then on to a bus station's stands 490000003A and 490000003B, in no
    // pair, which share a name. The Planner names the southbound poles, as placed where they entered.
    private val road = LineSequence(
        routes = listOf(
            LineRoute("North", listOf("490000002A", "490000001A", "490000003A")),
            LineRoute("South", listOf("490000003B", "490000001B", "490000002B")),
        ),
        stopNames = mapOf(
            "490000002A" to "Two", "490000002B" to "Two", "490000001A" to "One", "490000001B" to "One",
            "490000003A" to "Three", "490000003B" to "Three",
        ),
        stopAreas = mapOf(
            "490000002A" to "490G00000002", "490000002B" to "490G00000002",
            "490000001A" to "490G00000001", "490000001B" to "490G00000001",
        ),
    )
    private val pole1B = Resolved(tflId = "490000001B", station = "STOP1", hub = "HUB1")
    private val pair1 = Resolved(tflId = "490G00000001", station = "STOP1", hub = "HUB1", poles = setOf("490000001A", "490000001B"))

    private fun bus(toId: String, toArea: String, placed: Map<String, Resolved>) = TripLeg(
        mode = "bus", lineId = "1", lineName = "1", fromId = "490000002B", fromName = "Two",
        toId = toId, toName = road.stopNames.getValue(toId),
        departure = at, arrival = at.plusSeconds(600), path = listOf("490G00000001", toArea.ifEmpty { toId }).distinct(),
        fromArea = "490G00000002", toArea = toArea, placed = placed,
    )

    // [leg] moved to the poles its bus uses, as the trip holds it once the line's route is in.
    private fun poled(leg: TripLeg): TripLeg = onPoles(leg, mapOf("1" to road)).also { assertTrue(it.plannedToId.isNotEmpty()) }

    @Test
    fun `a bus moved to its route's pole gets off at the Planner's stop`() {
        val ride = poled(bus("490000001B", "490G00000001", mapOf("490000001B" to pole1B, "490G00000001" to pair1)))
        assertEquals("490000001A", ride.toId)
        val end = LegEnd(StepFree.ANY, LegEnd.Level.STOP, setOf("STOP1"))
        assertEquals(end, LegEnd.planned(ride, StepFree.ANY))
        assertTrue(end.endsAt(Resolved(tflId = "490000001A", station = "STOP1", hub = "HUB1")))
        assertEquals(end, LegEnd.of(TripRoute(listOf(ride, walk())), StepFree.ANY, setOf("HUB2")).single().second)
        // As the last ride into the destination's hub, by the pair's hub.
        assertEquals(
            LegEnd(StepFree.ANY, LegEnd.Level.HUB, setOf("HUB1")),
            LegEnd.of(TripRoute(listOf(ride)), StepFree.ANY, setOf("HUB1")).single().second,
        )
    }

    @Test
    fun `a bus moved within a pair the model split gets off at its own pole`() {
        // The pair's poles are in two stops; the Planner's is in STOP1S, the bus's may be in STOP1N.
        val split = pair1.copy(station = "STOP1S", hub = "HUB1", stops = setOf("STOP1N", "STOP1S"), hubs = setOf("HUB1"))
        val ride = poled(bus("490000001B", "490G00000001", mapOf("490000001B" to pole1B.copy(station = "STOP1S"), "490G00000001" to split)))
        val end = LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490000001A"))
        assertEquals(end, LegEnd.planned(ride, StepFree.ANY))
        assertEquals(end, LegEnd.of(TripRoute(listOf(ride)), StepFree.ANY, setOf("HUB1")).single().second)
        assertTrue(end.endsAt(Resolved(tflId = "490000001A", station = "STOP1N", hub = "HUB1")))
        assertFalse(end.endsAt(Resolved(tflId = "490000001B", station = "STOP1S", hub = "HUB1")))
    }

    @Test
    fun `a bus moved to another stand of its bus station gets off at that stand`() {
        val stand = Resolved(tflId = "490000003B", station = "STAND3B", hub = "HUB3")
        val ride = poled(bus("490000003B", "", mapOf("490000003B" to stand)))
        assertEquals("490000003A", ride.toId)
        val end = LegEnd(StepFree.ANY, LegEnd.Level.POLE, setOf("490000003A"))
        assertEquals(end, LegEnd.planned(ride, StepFree.ANY))
        assertEquals(end, LegEnd.of(TripRoute(listOf(ride)), StepFree.ANY, setOf("HUB3")).single().second)
    }
}
