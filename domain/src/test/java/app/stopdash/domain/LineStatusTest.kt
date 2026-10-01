package app.stopdash.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LineStatusTest {
    private val today = LocalDate.of(2026, 10, 13)
    private val closure = PlannedAlert("Part Closure", "Closed from 13 October.", today, severity = 5)
    private val later = PlannedAlert("Diversion", "Diverted from 20 October.", LocalDate.of(2026, 10, 20), severity = 0)

    private fun train(direction: String) =
        Departure("blue", "blue", direction, "End", null, java.time.Instant.parse("2026-10-13T08:00:00Z"), "tube")

    @Test
    fun `a line along a trip's rides shows only the alerts for the way they go`() {
        // Blue's delays are for trains going inbound; outbound there's only work still to come.
        val inbound = LineStatus("blue", 6, "Severe Delays")
        val outbound = LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(later))
        val line = inbound.copy(planned = listOf(later), byDirection = mapOf("inbound" to inbound, "outbound" to outbound))
        val statuses = mapOf("blue" to line)
        // Its ride's trains go outbound: the delays aren't this way's, the work to come is.
        assertEquals(outbound, LineStatus.alongRides(statuses, mapOf("blue" to listOf(listOf(train("outbound"), train("outbound"))))).getValue("blue"))
        assertEquals(inbound, LineStatus.alongRides(statuses, mapOf("blue" to listOf(listOf(train("inbound"))))).getValue("blue"))
        // Two rides of it, both outbound, still one way; a train with no direction says nothing.
        assertEquals(outbound, LineStatus.alongRides(statuses, mapOf("blue" to listOf(listOf(train("outbound")), listOf(train("outbound"), train(""))))).getValue("blue"))
        // A ride with no trains seen yet, trains going both ways, rides going opposite ways, or a line
        // with no rides at all: the line-wide status, which hides nothing.
        for (rides in listOf(
            listOf(emptyList()),
            listOf(listOf(train("outbound"), train("inbound"))),
            listOf(listOf(train("outbound")), listOf(train("inbound"))),
            listOf(listOf(train("outbound")), emptyList()),
            emptyList(),
        )) {
            assertEquals(rides.toString(), line, LineStatus.alongRides(statuses, mapOf("blue" to rides)).getValue("blue"))
        }
        // A line whose alerts aren't split by direction is as it was.
        val unsplit = mapOf("blue" to inbound)
        assertSame(unsplit, LineStatus.alongRides(unsplit, mapOf("blue" to listOf(listOf(train("outbound"))))))
    }

    @Test
    fun `work still to come stays planned`() {
        val status = LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(later))
        assertSame(status, status.asOf(today))
    }

    @Test
    fun `work whose day has come is under way`() {
        val status = LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(closure, later))
        val now = status.asOf(today)
        assertEquals(true, now.disrupted)
        assertEquals("Part Closure", now.description)
        assertEquals("Closed from 13 October.", now.fullText)
        assertEquals(listOf(later), now.planned)
    }

    @Test
    fun `the worse of a started planned alert and a current one makes the chip`() {
        val delays = LineStatus("blue", 9, "Minor Delays", fullText = "Minor delays.", planned = listOf(closure))
        assertEquals("Part Closure", delays.asOf(today).description)
        val suspended = LineStatus("blue", 3, "Suspended", fullText = "Suspended.", planned = listOf(closure))
        assertEquals("Suspended", suspended.asOf(today).description)
        assertEquals(emptyList<PlannedAlert>(), suspended.asOf(today).planned)
    }

    @Test
    fun `the closures under way stay, whichever alert a started one makes the worst`() {
        val closures = listOf(PartClosure(3, "Part Suspended", "No trains A to B.", listOf(listOf("A", "B"))))
        // The suspension stays worst: its closure stays.
        val suspended = LineStatus("blue", 3, "Part Suspended", fullText = "No trains A to B.", planned = listOf(closure), closures = closures)
        assertEquals(closures, suspended.asOf(today).closures)
        // The started closure now shows over the part closed, which is still under way: it still places.
        val behind = LineStatus("blue", 11, "Part Closed", fullText = "No trains A to B.", planned = listOf(closure), closures = closures)
        assertEquals("Part Closure", behind.asOf(today).description)
        assertEquals(closures, behind.asOf(today).closures)
    }

    @Test
    fun `a planned part closure that starts joins the closures, placed by its sections`() {
        // Kept across the day it starts (Codex, PR #446): it places on the stretch it was planned for,
        // beside the closure already under way, without waiting for the next check.
        val under = PartClosure(3, "Part Suspended", "No trains X to Y.", listOf(listOf("X", "Y")))
        val planned = closure.copy(closure = PartClosure(5, "Part Closure", "Closed from 13 October.", listOf(listOf("A", "B", "C"))))
        val status = LineStatus("blue", 9, "Minor Delays", fullText = "Minor delays.", planned = listOf(planned, later), closures = listOf(under))
        assertFalse(status.coversRide(listOf("A", "B")))
        val now = status.asOf(today)
        assertEquals(listOf(under, planned.closure), now.closures)
        assertTrue(now.coversRide(listOf("A", "B")))
        assertEquals("Part Closure", now.closureOn(listOf("A", "B"))?.description)
        // Not before its day.
        assertFalse(status.asOf(today.minusDays(1)).coversRide(listOf("A", "B")))
    }

    @Test
    fun `a ride runs through an alert's section only between two of its stops in a row`() {
        val closure = PartClosure(3, "Part Suspended", null, listOf(listOf("B", "C", "D"), listOf("F", "G")))
        assertTrue(closure.coversRide(listOf("A", "B", "C")))
        assertTrue(closure.coversRide(listOf("C", "D", "E")))
        // Up to its edge, or from it: trains still run there.
        assertFalse(closure.coversRide(listOf("A", "B")))
        assertFalse(closure.coversRide(listOf("D", "E", "F")))
        // Calling at one, then elsewhere, then another: not between them.
        assertFalse(closure.coversRide(listOf("B", "X", "D")))
        assertFalse(closure.coversRide(listOf("B")))
        assertFalse(LineStatus("blue", 3, "Part Suspended").coversRide(listOf("B", "C")))
        // Between two sections: from one's edge to the other's.
        assertFalse(closure.coversRide(listOf("D", "F")))
        assertTrue(closure.coversRide(listOf("E", "F", "G")))
        // Through a section the other way round: shut B to D, not D to B.
        assertFalse(closure.coversRide(listOf("E", "D", "C", "B")))
        assertTrue(closure.coversRide(listOf("B", "D")))
        // A loop's section naming a stop twice: B then A is its closing stretch.
        val loop = PartClosure(3, "Part Suspended", null, listOf(listOf("A", "B", "A")))
        assertTrue(loop.coversRide(listOf("B", "A")))
        assertTrue(loop.coversRide(listOf("A", "B")))
        assertFalse(loop.coversRide(listOf("B", "C")))
    }

    @Test
    fun `the worst closure placed on a ride is the one named there`() {
        val closed = PartClosure(11, "Part Closed", "No trains A to C.", listOf(listOf("A", "B", "C")))
        val suspended = PartClosure(3, "Part Suspended", "No trains B to C.", listOf(listOf("B", "C")))
        val elsewhere = PartClosure(2, "Suspended", null, listOf(listOf("X", "Y")))
        val status = LineStatus("blue", 9, "Minor Delays", fullText = "Minor delays.", closures = listOf(closed, suspended, elsewhere))
        assertEquals(suspended, status.closureOn(listOf("A", "B", "C")))
        assertEquals(closed, status.closureOn(listOf("A", "B")))
        assertEquals(null, status.closureOn(listOf("C", "D")))
        val named = status.naming(closed)
        assertEquals(listOf(11, "Part Closed", "No trains A to C."), listOf(named.severity, named.description, named.fullText))
    }

    @Test
    fun `a started fallback alert still ranks below a named one`() {
        val generic = PlannedAlert("Service Alert", "Works from 13 October.", today, severity = 9, isFallback = true)
        val delays = LineStatus("blue", 9, "Minor Delays", fullText = "Minor delays.", planned = listOf(generic))
        assertEquals("Minor Delays", delays.asOf(today).description)
    }

    @Test
    fun `a named alert that starts outranks a fallback already shown`() {
        val diverted = PlannedAlert("Diverted", "Diverted from 13 October.", today, severity = 15)
        val generic = LineStatus("blue", 9, "Service Alert", fullText = "Works.", planned = listOf(diverted), isFallback = true)
        val now = generic.asOf(today)
        assertEquals("Diverted", now.description)
        assertEquals(false, now.isFallback)
    }

    @Test
    fun `each direction is brought up to date too`() {
        val inbound = LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(closure))
        val status = LineStatus("blue", LineStatus.GOOD_SERVICE, "Good Service", byDirection = mapOf("inbound" to inbound))
        assertEquals("Part Closure", status.asOf(today).forDirection("inbound").description)
        val map = mapOf("blue" to status)
        assertEquals("Part Closure", LineStatus.asOf(map, java.time.Instant.parse("2026-10-13T08:00:00Z"))["blue"]?.forDirection("inbound")?.description)
        assertSame(map, LineStatus.asOf(map, java.time.Instant.parse("2026-10-12T08:00:00Z")))
    }
}
