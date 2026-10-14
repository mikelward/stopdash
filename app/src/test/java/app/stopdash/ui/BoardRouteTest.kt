package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.TripLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The board's row for a train's route tapped on the way ([boardRouteOf]), as its route page shows it. */
class BoardRouteTest {
    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))
    private val jubilee = TripLeg("tube", "jubilee", "Jubilee", "940GZZLUSTD", "Stratford", "940GZZLUCYF", "Canary Wharf", at(26), at(33))
    private fun train(destination: String, minutes: Long, direction: String = "outbound") =
        Departure("jubilee", "Jubilee", direction, destination, null, at(minutes), "tube")

    private val board = NextTrains(jubilee, listOf(train("Stanmore", 21), train("Stanmore", 24), train("Stanmore", 42))).withGroups(now)
    private val key = board.groups.single().rows.single().detailKey()

    @Test
    fun `finds the tapped row with every train on it`() {
        val row = boardRouteOf(board, key, null, emptySet()).row
        assertNotNull(row)
        assertEquals(listOf(at(21), at(24), at(42)), row!!.upcoming.map { it.expectedArrival })
        assertNull(row.status)
    }

    @Test
    fun `a row the board no longer lists is none`() {
        val other = NextTrains(jubilee, listOf(train("Stratford", 5, direction = "inbound"))).withGroups(now)
        assertNull(boardRouteOf(other, key, null, emptySet()).row)
    }

    @Test
    fun `carries this way's alert where it's disrupted, and its work to come`() {
        val weekend = PlannedAlert("Part Closure", "Saturday, no service between Baker Street and Stanmore.", LocalDate.of(2026, 10, 10))
        val outbound = LineStatus("jubilee", 9, "Minor Delays", planned = listOf(weekend))
        val inbound = LineStatus("jubilee", 6, "Severe Delays")
        val line = LineStatus("jubilee", 6, "Severe Delays", byDirection = mapOf("outbound" to outbound, "inbound" to inbound))
        val row = boardRouteOf(board, key, line, emptySet()).row!!
        assertEquals("Minor Delays", row.status?.description)
        assertEquals(listOf(weekend), row.plannedAlerts)
        // A good service flags nothing, its work to come still listed.
        val good = LineStatus("jubilee", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(weekend))
        val clean = boardRouteOf(board, key, good, emptySet()).row!!
        assertNull(clean.status)
        assertFalse(clean.statusDismissed)
        assertEquals(listOf(weekend), clean.plannedAlerts)
    }

    @Test
    fun `honors the near-me list's dismissals`() {
        val weekend = PlannedAlert("Part Closure", "Saturday, no service between Baker Street and Stanmore.", LocalDate.of(2026, 10, 10))
        val later = PlannedAlert("Part Closure", "Saturday 17 October, no service between Stratford and West Ham.", LocalDate.of(2026, 10, 17))
        val delays = LineStatus("jubilee", 9, "Minor Delays", planned = listOf(weekend, later))
        val row = boardRouteOf(board, key, delays, setOf(DismissedAlert.ofLineStatus(delays), DismissedAlert.ofPlanned("jubilee", weekend))).row!!
        // Dismissed, never read as a clean line.
        assertNull(row.status)
        assertTrue(row.statusDismissed)
        assertEquals(listOf(later), row.plannedAlerts)
    }
}
