package app.stopdash.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTimeTest {
    private val leave = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 17, 9, 5))

    private fun leg(departs: String, arrives: String) = TripLeg(
        mode = "tube", lineId = "victoria", lineName = "Victoria",
        fromId = "940GZZLUKSX", fromName = "King's Cross", toId = "940GZZLUVIC", toName = "Victoria",
        departure = Instant.parse(departs), arrival = Instant.parse(arrives),
    )

    private fun route(departs: String, arrives: String) = TripRoute(listOf(leg(departs, arrives)))

    @Test
    fun `gives the Planner its date, time and whether the trip leaves or arrives then`() {
        assertEquals("20261017", leave.plannerDate)
        assertEquals("0905", leave.plannerTime)
        assertEquals("Departing", leave.plannerTimeIs)
        assertEquals("Arriving", leave.copy(kind = TripTime.Kind.ARRIVE).plannerTimeIs)
    }

    @Test
    fun `reads back what it saved, and nothing from what it didn't`() {
        assertEquals(leave, TripTime.parse(leave.encode()))
        val arrive = TripTime(TripTime.Kind.ARRIVE, LocalDateTime.of(2026, 12, 31, 23, 45))
        assertEquals(arrive, TripTime.parse(arrive.encode()))
        assertNull(TripTime.parse(null))
        assertNull(TripTime.parse(""))
        assertNull(TripTime.parse("SOON@2026-10-17T09:05"))
        assertNull(TripTime.parse("LEAVE@tomorrow"))
    }

    @Test
    fun `an unreadable saved time is logged by its failure alone, never the time it held`() {
        val warned = mutableListOf<String>()
        assertNull(TripTime.parse(null, warned::add))
        assertTrue(warned.isEmpty())
        assertNull(TripTime.parse("SOON@2026-10-17T09:05", warned::add))
        assertNull(TripTime.parse("LEAVE@2026-10-17T9:5", warned::add))
        assertNull(TripTime.parse("2026-10-17T09:05", warned::add))
        assertEquals(3, warned.size)
        assertTrue(warned.none { "2026" in it || "09" in it })
        assertEquals(leave, TripTime.parse(leave.encode(), warned::add))
        assertEquals(3, warned.size)
    }

    @Test
    fun `is past once its minute in London has gone by, not before`() {
        // 09:05 BST is 08:05 UTC.
        assertFalse(leave.isPast(Instant.parse("2026-10-17T08:04:00Z")))
        assertFalse(leave.isPast(Instant.parse("2026-10-17T08:05:59Z")))
        assertTrue(leave.isPast(Instant.parse("2026-10-17T08:06:00Z")))
    }

    @Test
    fun `in the hour the clocks go back, a time is past only once its second reading is`() {
        // 25 Oct 2026: London's 01:00-01:59 happens twice, first in BST (00:00Z-00:59Z), then in GMT.
        val repeated = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 25, 1, 30))
        // 01:10 the first time round, and the second: 01:30 is still ahead either way.
        assertFalse(repeated.isPast(Instant.parse("2026-10-25T00:10:00Z")))
        assertFalse(repeated.isPast(Instant.parse("2026-10-25T01:10:00Z")))
        assertTrue(repeated.isPast(Instant.parse("2026-10-25T01:31:00Z")))
    }

    @Test
    fun `in the hour the clocks go back, a route keeping to either reading of the time is kept`() {
        val leaveAt = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 25, 1, 30))
        assertTrue(leaveAt.keeps(route("2026-10-25T00:30:00Z", "2026-10-25T00:50:00Z")))
        assertTrue(leaveAt.keeps(route("2026-10-25T01:30:00Z", "2026-10-25T01:50:00Z")))
        assertFalse(leaveAt.keeps(route("2026-10-25T00:29:00Z", "2026-10-25T00:50:00Z")))
        val arriveBy = leaveAt.copy(kind = TripTime.Kind.ARRIVE)
        assertTrue(arriveBy.keeps(route("2026-10-25T00:10:00Z", "2026-10-25T00:30:00Z")))
        assertTrue(arriveBy.keeps(route("2026-10-25T01:10:00Z", "2026-10-25T01:30:00Z")))
        assertFalse(arriveBy.keeps(route("2026-10-25T01:11:00Z", "2026-10-25T01:31:00Z")))
    }

    @Test
    fun `the Planner's answer is read from the time itself leaving, and from an hour before arriving`() {
        val leaveAt = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 25, 1, 30))
        // Leaving in the repeated hour: from its first reading, as the Planner offers journeys from it on.
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), leaveAt.readFrom)
        // Arriving by 02:00 (after the clocks went back): from an hour before.
        val arriveBy = TripTime(TripTime.Kind.ARRIVE, LocalDateTime.of(2026, 10, 25, 2, 0))
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"), arriveBy.readFrom)
        // Any other day, the time as it is in London.
        assertEquals(Instant.parse("2026-10-17T08:05:00Z"), leave.readFrom)
    }

    @Test
    fun `a time the clocks skip going forward is picked as the time it becomes`() {
        // 29 March 2026: London goes from 01:00 straight to 02:00, so 01:30 is never on a clock.
        val skipped = TripTime.of(TripTime.Kind.LEAVE, LocalDate.of(2026, 3, 29), LocalTime.of(1, 30))
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), skipped.at)
        assertEquals("0230", skipped.plannerTime)
        // At 02:15 that night it's still to come, as what's asked for and shown says.
        assertFalse(skipped.isPast(Instant.parse("2026-03-29T01:15:00Z")))
        // Any other time, the repeated hour's included, is itself.
        assertEquals(LocalDateTime.of(2026, 10, 25, 1, 30), TripTime.of(TripTime.Kind.LEAVE, LocalDate.of(2026, 10, 25), LocalTime.of(1, 30)).at)
        assertEquals(leave, TripTime.of(TripTime.Kind.LEAVE, LocalDate.of(2026, 10, 17), LocalTime.of(9, 5)))
    }

    @Test
    fun `only the hour the clocks skip going forward is skipped`() {
        val spring = LocalDate.of(2026, 3, 29)
        assertTrue(TripTime.skipped(spring, LocalTime.of(1, 0)))
        assertTrue(TripTime.skipped(spring, LocalTime.of(1, 59)))
        assertFalse(TripTime.skipped(spring, LocalTime.of(0, 59)))
        assertFalse(TripTime.skipped(spring, LocalTime.of(2, 0)))
        // The hour the clocks go back happens twice, so it's on the clock.
        assertFalse(TripTime.skipped(LocalDate.of(2026, 10, 25), LocalTime.of(1, 30)))
        assertFalse(TripTime.skipped(LocalDate.of(2026, 3, 30), LocalTime.of(1, 30)))
    }

    @Test
    fun `offers today and the six days after it, by London's date`() {
        // 23:30 UTC on the 16th is already the 17th in London (BST).
        val days = TripTime.days(Instant.parse("2026-10-16T23:30:00Z"))
        assertEquals(TripTime.DAYS, days.size)
        assertEquals(LocalDate.of(2026, 10, 17), days.first())
        assertEquals(LocalDate.of(2026, 10, 23), days.last())
    }

    @Test
    fun `suggests leaving at the next quarter hour in London`() {
        // 09:01 BST rounds up to 09:15; a quarter hour itself stays; the seconds don't count.
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 15), TripTime.suggested(Instant.parse("2026-10-17T08:01:00Z")).at)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 30), TripTime.suggested(Instant.parse("2026-10-17T08:30:40Z")).at)
        // Past the last quarter, into the next day.
        assertEquals(LocalDateTime.of(2026, 10, 18, 0, 0), TripTime.suggested(Instant.parse("2026-10-17T22:50:00Z")).at)
        assertEquals(TripTime.Kind.LEAVE, TripTime.suggested(Instant.parse("2026-10-17T08:01:00Z")).kind)
    }

    @Test
    fun `a route keeps to the time by leaving no sooner, or arriving no later`() {
        // 09:00 BST is 08:00 UTC.
        val nine = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 17, 9, 0))
        assertTrue(nine.keeps(route("2026-10-17T08:00:00Z", "2026-10-17T08:30:00Z")))
        assertTrue(nine.keeps(route("2026-10-17T08:10:00Z", "2026-10-17T08:40:00Z")))
        assertFalse(nine.keeps(route("2026-10-17T07:59:00Z", "2026-10-17T08:20:00Z")))
        val byNine = nine.copy(kind = TripTime.Kind.ARRIVE)
        assertTrue(byNine.keeps(route("2026-10-17T07:20:00Z", "2026-10-17T08:00:00Z")))
        assertFalse(byNine.keeps(route("2026-10-17T07:40:00Z", "2026-10-17T08:01:00Z")))
        assertFalse(nine.keeps(TripRoute(emptyList())))
    }

    @Test
    fun `leaving at a time lists the soonest to get there first`() {
        val late = route("2026-10-17T08:05:00Z", "2026-10-17T08:50:00Z")
        val soon = route("2026-10-17T08:10:00Z", "2026-10-17T08:40:00Z")
        val sameEarlier = route("2026-10-17T08:00:00Z", "2026-10-17T08:40:00Z")
        assertEquals(listOf(sameEarlier, soon, late), TripTime.ordered(listOf(late, soon, sameEarlier), leave))
    }

    @Test
    fun `arriving by a time lists the latest to leave first`() {
        val arrive = leave.copy(kind = TripTime.Kind.ARRIVE)
        val early = route("2026-10-17T07:20:00Z", "2026-10-17T08:00:00Z")
        val late = route("2026-10-17T07:40:00Z", "2026-10-17T08:05:00Z")
        val lateSooner = route("2026-10-17T07:40:00Z", "2026-10-17T08:01:00Z")
        assertEquals(listOf(lateSooner, late, early), TripTime.ordered(listOf(early, late, lateSooner), arrive))
    }
}
