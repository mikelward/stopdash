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
}
