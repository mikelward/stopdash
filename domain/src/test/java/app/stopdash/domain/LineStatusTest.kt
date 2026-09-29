package app.stopdash.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
