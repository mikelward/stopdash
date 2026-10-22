package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.RouteFocus
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
    fun `a route gone from its platform is found at another, the same way`() {
        fun onPlatform(platform: String, destination: String, minutes: Long, direction: String = "outbound") =
            Departure("jubilee", "Jubilee", direction, destination, platform, at(minutes), "tube")
        val tapped = NextTrains(jubilee, listOf(onPlatform("Westbound - Platform 13", "Stanmore", 21))).withGroups(now)
        val key = tapped.groups.single().rows.single().detailKey()
        // The train moved to Platform 14, and the way back leaves from Platform 15.
        val moved = NextTrains(
            jubilee,
            listOf(onPlatform("Westbound - Platform 14", "Stanmore", 22), onPlatform("Eastbound - Platform 15", "Stanmore", 5, "inbound")),
        ).withGroups(now)
        val found = boardRouteOf(moved, key, null, emptySet(), RouteFocus("Stanmore", null), "outbound")
        assertEquals("Westbound - Platform 14", found.row?.upcoming?.single()?.platform)
        // Without the route tapped, or once no platform has it, no row to move to.
        assertNull(boardRouteOf(moved, key, null, emptySet()).row)
        assertNull(boardRouteOf(moved, key, null, emptySet(), RouteFocus("Wembley Park", null), "outbound").row)
    }

    @Test
    fun `a row the board no longer lists is none`() {
        val other = NextTrains(jubilee, listOf(train("Stratford", 5, direction = "inbound"))).withGroups(now)
        assertNull(boardRouteOf(other, key, null, emptySet()).row)
    }

    @Test
    fun `a route with no train left stands on the row last drawn, as of this board`() {
        // A refresh never closes the page (maintainer, 2026-10-22): the row it was drawn with stands for the route,
        // its trains gone, its alert the line's now and its age this board's.
        val drawn = boardRouteOf(board, key, null, emptySet()).row!!
        val read = at(1)
        val other = NextTrains(jubilee, listOf(train("Stratford", 5, direction = "inbound")), fetchedAt = read).withGroups(now)
        val delays = LineStatus("jubilee", 9, "Minor Delays")
        val standing = boardRouteOf(other, key, delays, emptySet(), drawn = drawn)
        assertTrue(standing.gone)
        val row = standing.row!!
        assertEquals(drawn.detailKey(), row.detailKey())
        assertTrue(row.upcoming.isEmpty() && row.untimed.isEmpty())
        assertEquals(read, row.fetchedAt)
        assertEquals("Minor Delays", row.status?.description)
        // Its dismissal marked as of now: dismissed, it says so; the dismissal gone, the alert is back.
        val dismissed = boardRouteOf(other, key, delays, setOf(DismissedAlert.ofLineStatus(delays)), drawn = drawn).row!!
        assertNull(dismissed.status)
        assertTrue(dismissed.statusDismissed)
        val again = boardRouteOf(other, key, delays, emptySet(), drawn = dismissed).row!!
        assertEquals("Minor Delays", again.status?.description)
        assertFalse(again.statusDismissed)
        // A train on it again: its row, not the one standing.
        val back = boardRouteOf(board, key, null, emptySet(), drawn = drawn)
        assertFalse(back.gone)
        assertEquals(3, back.row!!.upcoming.size)
    }

    @Test
    fun `a board still loading has no row yet, rather than one with no train`() {
        val drawn = boardRouteOf(board, key, null, emptySet()).row!!
        val loading = NextTrains(jubilee, emptyList(), pending = true)
        assertNull(boardRouteOf(loading, key, null, emptySet(), drawn = drawn).row)
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
