package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.TripLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The line a route page's "View line" opens ([routeLineRow]). */
class RouteLineRowTest {
    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")

    // A Northern line train from King's Cross St. Pancras, a big interchange.
    private val row = DepartureRows.across(
        listOf(
            StopArrivals(
                stopId = "940GZZLUKSX",
                stopName = "King's Cross St. Pancras",
                departures = listOf(Departure("northern", "Northern", "outbound", "Morden", null, now.plus(Duration.ofMinutes(2)), "tube")),
                fetchedAt = now,
            ),
        ),
        now,
    ).first { it.upcoming.isNotEmpty() }

    @Test
    fun `a route page's line keeps its stop as the rider's`() {
        val line = routeLineRow(row, ride = null, unknown = false, checking = false).every.single()
        assertEquals("northern", line.leg.lineId)
        assertEquals(setOf("940GZZLUKSX"), line.riding)
        assertTrue("no stretch to place", line.rides.isEmpty())
        assertFalse(line.unknown)
    }

    @Test
    fun `a trip ride's line keeps where it boards and gets off, and its stretch`() {
        val ride = TripLeg(
            "tube", "northern", "Northern", "940GZZLUKSX", "King's Cross St. Pancras", "940GZZLUBNK", "Bank", now, now.plus(Duration.ofMinutes(8)),
            path = listOf("940GZZLUAGL", "940GZZLUODS", "940GZZLUMGT", "940GZZLUBNK"),
        )
        val line = routeLineRow(row, ride, unknown = false, checking = false).every.single()
        assertEquals(setOf("940GZZLUKSX", "940GZZLUBNK"), line.riding)
        assertEquals(listOf(listOf("940GZZLUKSX", "940GZZLUAGL", "940GZZLUODS", "940GZZLUMGT", "940GZZLUBNK")), line.rides)
        // Another line's ride says nothing of this one's.
        val other = routeLineRow(row, ride.copy(lineId = "victoria"), unknown = false, checking = false).every.single()
        assertEquals(setOf("940GZZLUKSX"), other.riding)
    }

    @Test
    fun `an alert behind the stop is still the line's, and an unchecked line is never good`() {
        val behind = LineStatus("northern", 9, "Minor Delays")
        assertEquals(behind, routeLineRow(row.copy(statusBehind = behind), null, unknown = false, checking = false).every.single().status)
        assertTrue(routeLineRow(row, null, unknown = true, checking = false).every.single().unknown)
        val checking = routeLineRow(row, null, unknown = true, checking = true)
        assertTrue(checking.checking)
        assertFalse("checking, not couldn't check", checking.every.single().unknown)
    }

    @Test
    fun `a status kept from before still says its check couldn't be made`() {
        // A delay the route page kept, its check since failed or gone stale: shown, and doubted (Codex, #623).
        val delays = LineStatus("northern", 9, "Minor Delays")
        val line = routeLineRow(row.copy(status = delays), null, unknown = true, checking = false).every.single()
        assertEquals(delays, line.status)
        assertTrue(line.unknown)
    }

    @Test
    fun `a trip's line is in doubt or checking by its own status alone`() {
        // As the trip's lines page has it (Codex, #623): checked and current, it's neither.
        val good = LineStatus("northern", 10, "Good Service")
        val checked = TripViewModel.State(statuses = mapOf("northern" to good), statusesAt = mapOf("northern" to now))
        assertFalse(tripLineInDoubt(checked, "northern", now))
        assertFalse(tripLineChecking(checked.copy(refreshing = true), "northern", now))
        // Not yet checked: checking while a refresh runs, else couldn't check.
        val none = TripViewModel.State()
        assertTrue(tripLineInDoubt(none, "northern", now))
        assertTrue(tripLineChecking(none.copy(refreshing = true), "northern", now))
        assertTrue(tripLineChecking(none.copy(planning = true), "northern", now))
        assertFalse(tripLineChecking(none, "northern", now))
        // Gone stale, it's in doubt; a refresh out for it, it's checking again.
        assertTrue(tripLineInDoubt(checked, "northern", now.plus(Duration.ofHours(1))))
        assertTrue(tripLineChecking(checked.copy(refreshing = true), "northern", now.plus(Duration.ofHours(1))))
        // Its latest check failed, or TfL left it out: couldn't check, even while another refresh runs.
        val failed = checked.copy(statusFailedLines = setOf("northern"), refreshing = true)
        assertTrue(tripLineInDoubt(failed, "northern", now))
        assertFalse(tripLineChecking(failed, "northern", now))
        val omitted = none.copy(statusOmitted = setOf("northern"), statusUnknown = setOf("northern"), refreshing = true)
        assertTrue(tripLineInDoubt(omitted, "northern", now))
        assertFalse(tripLineChecking(omitted, "northern", now))
        assertTrue("a blank line is never vouched for", tripLineInDoubt(checked, "", now))
    }

    @Test
    fun `the same alert fetched again keeps its map's key, and another closed stretch changes it`() {
        // The map is keyed by what it draws of the alert ([LineMap.alertKey]), so a status fetched again as
        // a new object keeps the map up rather than reading "Loading map…" (Codex, #623).
        val closure = app.stopdash.domain.PartClosure(
            app.stopdash.domain.PlannedAlert.PART_CLOSURE, "Part Closure", "No service between King's Cross St. Pancras and Euston.",
            listOf(listOf("940GZZLUKSX", "940GZZLUEUS")),
        )
        val closed = LineStatus("northern", app.stopdash.domain.PlannedAlert.PART_CLOSURE, "Part Closure", closures = listOf(closure))
        fun key(status: LineStatus) = routeLineRow(row.copy(status = status), null, unknown = false, checking = false).every.single().mapKey
        assertTrue(key(closed) != null)
        assertEquals(key(closed), key(closed.copy(closures = listOf(closure.copy()))))
        val further = closure.copy(sections = listOf(listOf("940GZZLUKSX", "940GZZLUEUS", "940GZZLUWRR")))
        assertTrue(key(closed) != key(closed.copy(closures = listOf(further))))
    }

    @Test
    fun `the page's row stays up only while it says the same, as sure as before`() {
        val delays = LineStatus("northern", 9, "Minor Delays", fullText = "Northern line: minor delays.")
        fun inputs(r: app.stopdash.domain.DepartureRow, unknown: Boolean = false, checking: Boolean = false) = Inputs(r, null, unknown, checking)
        val held = inputs(row.copy(status = delays))
        // The same alert fetched again, a new object: kept.
        assertTrue(sameVerdict(held, inputs(row.copy(status = delays.copy()))))
        // The same alert behind the stop instead: the page shows it the same.
        assertTrue(sameVerdict(held, inputs(row.copy(statusBehind = delays.copy()))))
        // An alert that ended, began, was reworded or dismissed, or a closure that began beside it: not kept.
        assertFalse(sameVerdict(held, inputs(row)))
        assertFalse(sameVerdict(inputs(row), held))
        assertFalse(sameVerdict(held, inputs(row.copy(status = delays.copy(severity = app.stopdash.domain.PlannedAlert.PART_CLOSURE)))))
        assertFalse(sameVerdict(held, inputs(row.copy(status = delays.copy(description = "Severe Delays")))))
        assertFalse(sameVerdict(held, inputs(row.copy(status = delays.copy(fullText = "Northern line: severe delays.")))))
        assertFalse(sameVerdict(held, inputs(row.copy(status = delays, statusDismissed = true))))
        val closure = app.stopdash.domain.PartClosure(app.stopdash.domain.PlannedAlert.PART_CLOSURE, "Part Closure", null, listOf(listOf("940GZZLUKSX", "940GZZLUEUS")))
        assertFalse(sameVerdict(held, inputs(row.copy(status = delays.copy(closures = listOf(closure))))))
        // Less sure, or checking again: not kept.
        assertFalse(sameVerdict(held, inputs(row.copy(status = delays), unknown = true)))
        assertFalse(sameVerdict(held, inputs(row.copy(status = delays), checking = true)))
    }

    @Test
    fun `the page's stand-in is the line's pill alone, claiming no status`() {
        val stand = routeLineStandIn(row.copy(lineName = "")).every.single()
        assertEquals("northern", stand.leg.lineId)
        assertEquals("a blank name goes by the id", "northern", stand.leg.lineName)
        assertTrue(stand.restoring)
        assertEquals(null, stand.status)
        assertFalse(stand.unknown)
    }
}
