package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RouteRideTest {
    private val departs = Instant.parse("2026-10-09T08:00:00Z")
    private val victoria = listOf(
        RouteStop("940GZZLUVIC", "Victoria"),
        RouteStop("940GZZLUGPK", "Green Park"),
        RouteStop("940GZZLUOXC", "Oxford Circus"),
        RouteStop("940GZZLUWRR", "Warren Street"),
    )

    @Test
    fun `a ride runs from the boarding stop to the one tapped`() {
        val route = RouteRide.to(
            victoria, "940GZZLUOXC", "tube", "victoria", "Victoria", departs, "Walthamstow Central",
            positions = mapOf("940GZZLUVIC" to (51.4965 to -0.1447), "940GZZLUOXC" to (51.5152 to -0.1418)),
        )!!
        val leg = route.legs.single()
        assertEquals("940GZZLUVIC", leg.fromId)
        assertEquals("Victoria", leg.fromName)
        assertEquals("940GZZLUOXC", leg.toId)
        assertEquals("Oxford Circus", leg.toName)
        assertEquals(listOf("940GZZLUGPK", "940GZZLUOXC"), leg.path)
        assertEquals(listOf("Green Park", "Oxford Circus"), leg.pathNames)
        assertEquals(listOf("Walthamstow Central"), leg.headings)
        assertEquals(departs, leg.departure)
        assertEquals(departs.plus(Duration.ofMinutes(4)), leg.arrival)
        assertEquals(Coordinates(51.4965, -0.1447), leg.fromAt)
        assertEquals(Coordinates(51.5152, -0.1418), leg.toAt)
        // A tube station has no stop pair to stand for.
        assertEquals("", leg.toArea)
    }

    @Test
    fun `a bus keeps its end's stop area`() {
        val stops = listOf(RouteStop("490000001A", "Stop A"), RouteStop("490000002B", "Stop B"))
        val leg = RouteRide.to(stops, "490000002B", "bus", "73", "73", departs, "Oxford Circus", areas = mapOf("490000002B" to "490G00000002"))!!
            .legs.single()
        assertEquals("490G00000002", leg.toArea)
    }

    @Test
    fun `no ride to the boarding stop, one off the list, or with no line`() {
        assertNull(RouteRide.to(victoria, "940GZZLUVIC", "tube", "victoria", "Victoria", departs, ""))
        assertNull(RouteRide.to(victoria, "940GZZLUKXX", "tube", "victoria", "Victoria", departs, ""))
        assertNull(RouteRide.to(victoria, "940GZZLUOXC", "tube", "", "Victoria", departs, ""))
    }

    @Test
    fun `on a loop, a stop called at twice is ridden to the first time after boarding`() {
        // A loop back through its first stop, then on: the boarding stop's own place at the head never counts.
        val loop = listOf(
            RouteStop("490000001A", "Stop A"),
            RouteStop("490000002B", "Stop B"),
            RouteStop("490000003C", "Stop C"),
            RouteStop("490000001A", "Stop A"),
            RouteStop("490000002B", "Stop B"),
        )
        val toB = RouteRide.to(loop, "490000002B", "bus", "c1", "C1", departs, "")!!.legs.single()
        assertEquals(listOf("490000002B"), toB.path)
        val backToA = RouteRide.to(loop, "490000001A", "bus", "c1", "C1", departs, "")!!.legs.single()
        assertEquals(listOf("490000002B", "490000003C", "490000001A"), backToA.path)
    }

    @Test
    fun `a tapped call on a loop is the one ridden to, back to the boarding stop included`() {
        val loop = listOf(
            RouteStop("490000001A", "Stop A"),
            RouteStop("490000002B", "Stop B"),
            RouteStop("490000003C", "Stop C"),
            RouteStop("490000001A", "Stop A"),
            RouteStop("490000002B", "Stop B"),
        )
        val secondB = RouteRide.to(loop, "490000002B", "bus", "c1", "C1", departs, "", toIndex = 4)!!.legs.single()
        assertEquals(4, secondB.path.size)
        val backToA = RouteRide.to(loop, "490000001A", "bus", "c1", "C1", departs, "", toIndex = 3)!!.legs.single()
        assertEquals(listOf("490000002B", "490000003C", "490000001A"), backToA.path)
        // An index that isn't that stop's falls back to its first call after boarding; the head never counts.
        assertEquals(listOf("490000002B"), RouteRide.to(loop, "490000002B", "bus", "c1", "C1", departs, "", toIndex = 2)!!.legs.single().path)
        assertEquals("490000003C", RouteRide.to(loop, "490000003C", "bus", "c1", "C1", departs, "", toIndex = 0)!!.legs.single().toId)
    }
}
