package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLinesTest {
    // Synthetic lines and stops (SPEC *Privacy*): red is the Planner's, A → B with nothing between.
    private val at = Instant.parse("2026-09-27T08:00:00Z")

    private fun leg(lineId: String, from: String, to: String, path: List<String> = listOf(to), mode: String = "tube") =
        TripLeg(mode, lineId, lineId, from, from, to, to, at, at.plusSeconds(600), path = path)

    private fun train(lineId: String, mode: String = "tube") =
        Departure(lineId = lineId, lineName = lineId, direction = "outbound", destination = "End", platform = null, expectedArrival = at, mode = mode)

    private fun sequence(vararg stops: String) = LineSequence(routes = listOf(LineRoute("route", stops.toList())), stopNames = stops.associateWith { it })

    private val ride = leg("red", "A", "B")
    private val route = TripRoute(listOf(ride))
    private val atA = mapOf("A" to listOf(train("red"), train("green")))

    private fun linesOf(sequences: Map<String, LineSequence?>, hidden: Set<String> = emptySet(), arrivals: Map<String, List<Departure>> = atA) =
        RideLines.of(listOf(route), arrivals, emptyMap(), sequences, hidden).getValue(ride)

    @Test
    fun `a line at the ride's boarding stop that runs to its getting-off stop is an equal`() {
        val lines = linesOf(mapOf("green" to sequence("A", "B", "End")))
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals(listOf("red", "green"), lines.timed.map { it.lineId })
        assertEquals(listOf("green"), RideLines.lineIds(listOf(route), atA, emptyMap()))
    }

    @Test
    fun `a line reaching the stop another way is shown but doesn't time the ride`() {
        val lines = linesOf(mapOf("green" to sequence("A", "Z", "B")))
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals(listOf("red"), lines.timed.map { it.lineId })
    }

    @Test
    fun `another stop, a route missing the change, no route, another mode or a hidden line is no equal`() {
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A2", "B"))).legs.map { it.lineId })
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A", "End"))).legs.map { it.lineId })
        assertEquals(listOf("red"), linesOf(emptyMap()).legs.map { it.lineId })
        val bus = mapOf("A" to listOf(train("green", mode = "bus")))
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A", "B")), arrivals = bus).legs.map { it.lineId })
        val hidden = setOf(HiddenModes.lineKey("green", "Green"))
        assertEquals(listOf("red"), linesOf(mapOf("green" to sequence("A", "B")), hidden = hidden).legs.map { it.lineId })
    }

    @Test
    fun `a road's two poles are one stop`() {
        // The Planner's bus boards at pole Bs of pair BG; bus 2 at the other pole, Bn, fetched with it.
        val bus = leg("1", "Bs", "Cn", path = listOf("XG", "CG"), mode = "bus").copy(fromArea = "BG", toArea = "CG")
        val two = LineSequence(
            routes = listOf(LineRoute("north", listOf("Bn", "Xn", "Cn2"))),
            stopNames = mapOf("Bn" to "B", "Xn" to "X", "Cn2" to "C"),
            stopAreas = mapOf("Bn" to "BG", "Xn" to "XG", "Cn2" to "CG"),
        )
        val lines = RideLines.of(
            listOf(TripRoute(listOf(bus))),
            mapOf("Bn" to listOf(train("2", mode = "bus"))),
            mapOf("BG" to listOf("Bs", "Bn")),
            mapOf("2" to two),
        ).getValue(bus)
        assertEquals(listOf("1", "2"), lines.legs.map { it.lineId })
        assertEquals("Bn" to "Cn2", lines.legs[1].let { it.fromId to it.toId })
        assertEquals(listOf("1", "2"), lines.timed.map { it.lineId })
    }

    @Test
    fun `another line counts only once checked as running, the Planner's always`() {
        val green = leg("green", "A", "B")
        val good = LineStatus("green", LineStatus.GOOD_SERVICE, "Good Service")
        assertTrue(RideLines.checked(ride, ride, emptyMap()))
        assertFalse(RideLines.checked(green, ride, emptyMap()))
        assertTrue(RideLines.checked(green, ride, mapOf("green" to good)))
        assertFalse(RideLines.checked(green, ride, mapOf("green" to LineStatus("green", 20, "Service Closed"))))
    }

    @Test
    fun `a ride's running lines and its unchecked ones follow the same rule`() {
        val lines = linesOf(mapOf("green" to sequence("A", "B", "End")))
        val good = mapOf("green" to LineStatus("green", LineStatus.GOOD_SERVICE, "Good Service"))
        val closed = mapOf("green" to LineStatus("green", 20, "Service Closed"))
        assertEquals(listOf("red"), lines.running(emptyMap()).map { it.lineId })
        assertEquals(listOf("red"), lines.timedRunning(closed).map { it.lineId })
        assertEquals(listOf("red", "green"), lines.running(good).map { it.lineId })
        assertEquals(listOf("red", "green"), lines.timedRunning(good).map { it.lineId })
        // Unknown is unchecked; known, even closed, isn't: that one says why it doesn't count.
        assertEquals(setOf("green"), RideLines.unchecked(listOf(lines), emptyMap()))
        assertEquals(emptySet<String>(), RideLines.unchecked(listOf(lines), closed))
    }

    @Test
    fun `a line whose route runs through a sibling id of the same station is an equal`() {
        // Green's departures are listed under A, but its route calls at A1: the same station, by
        // interchange and name, as TfL does at some stations with several sets of platforms.
        val green = LineSequence(
            routes = listOf(LineRoute("route", listOf("A1", "B", "End"))),
            stopNames = mapOf("A" to "Alpha", "A1" to "Alpha", "B" to "B", "End" to "End"),
            stopHubs = mapOf("A" to "HUB", "A1" to "HUB"),
        )
        val lines = linesOf(mapOf("green" to green))
        assertEquals(listOf("red", "green"), lines.legs.map { it.lineId })
        assertEquals("A" to "B", lines.legs[1].let { it.fromId to it.toId })
        assertEquals(listOf("red", "green"), lines.timed.map { it.lineId })
    }
}
