package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopArrivals
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen's disruptions row: every tube line, and the lines of the stops within the walking reach. */
class HomeLinesTest {
    private val now = Instant.parse("2026-10-05T08:00:00Z")

    private fun stop(id: String, vararg lines: Pair<String, String>) = StopArrivals(
        id, id,
        lines.map { (line, mode) -> Departure(line, line.uppercase(), "outbound", "Somewhere", null, now.plusSeconds(120), mode) },
        now,
    )

    private val good = HomeLines.TUBE_IDS.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
    private fun tube(statuses: Map<String, LineStatus> = good, at: Instant = now) = HomeLines.Always(statuses, at)
    private val severe = LineStatus("73", 6, "Severe Delays", fullText = "Roadworks")

    @Test
    fun `every tube line and the lines within the walking reach, never one beyond`() {
        val loaded = DeparturesUiState.Loaded(
            stops = listOf(stop("near", "73" to "bus"), stop("far", "38" to "bus")),
            fetchedAt = now,
            lineStatuses = mapOf("73" to severe),
            determinedLineIds = setOf("73", "38"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 120.0, "far" to 900.0), tube(), emptySet(), now)
        assertEquals(HomeLines.TUBE_IDS + "73", row.every.mapTo(HashSet()) { it.leg.lineId })
        // The disrupted one leads the pills and the page; the rest are good services, checked.
        assertEquals(listOf("73"), row.lines.map { it.lineId })
        assertEquals("73", row.every.first().leg.lineId)
        assertFalse(row.checking)
        assertFalse(row.unknown)
    }

    @Test
    fun `a nearby stop's route with no departure is left out, unless the list shows its alert`() {
        val quiet = stop("near", "73" to "bus").copy(lines = listOf(LineRef("73", "73", "bus"), LineRef("n73", "N73", "bus"), LineRef("25", "25", "bus")))
        val suspended = LineStatus("25", 16, "Suspended")
        val loaded = DeparturesUiState.Loaded(listOf(quiet), now, lineStatuses = mapOf("25" to suspended), determinedLineIds = setOf("73", "n73", "25"))
        val ids = HomeLines.row(loaded, mapOf("near" to 50.0), tube(), emptySet(), now).every.map { it.leg.lineId }
        assertTrue("73" in ids)
        assertTrue("25" in ids)
        assertFalse("n73" in ids)
    }

    @Test
    fun `what the list couldn't check is said on the row, farther stops' too, in place of its banner`() {
        // A farther stop's line didn't answer, and a near stop's closure check failed.
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus"), stop("far", "38" to "bus")), now,
            determinedLineIds = setOf("73"), disruptionUnknown = true, stopsDisruptionUnknown = setOf("near"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 50.0, "far" to 900.0), tube(), emptySet(), now)
        assertTrue(row.unknown)
        assertEquals(listOf("38"), row.unknownLines.map { it.lineId })
        assertEquals("near", row.unknownStops)
        // Something it can't name (a departure with no line) still reads unknown, with nothing named.
        val blank = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"), disruptionUnknown = true)
        val unnamed = HomeLines.row(blank, mapOf("near" to 50.0), tube(), emptySet(), now)
        assertTrue(unnamed.unknown)
        assertEquals(emptyList<String>(), unnamed.unknownLines.map { it.lineId })
    }

    @Test
    fun `the chosen networks are always covered, the nearby lines whatever's chosen`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val asked = HomeLines.idsOf(setOf("overground"))
        val checked = HomeLines.Always(asked.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }, now)
        val ids = HomeLines.row(loaded, mapOf("near" to 50.0), checked, emptySet(), now, networks = setOf("overground")).every.map { it.leg.lineId }
        assertEquals(asked + "73", ids.toSet())
        // None chosen: the nearby lines alone.
        assertEquals(listOf("73"), HomeLines.row(loaded, mapOf("near" to 50.0), null, emptySet(), now, networks = emptySet()).every.map { it.leg.lineId })
        // One just chosen that no check has asked about yet is being checked, not unchecked.
        val tubeOnly = HomeLines.Always(good, now, askedFor = HomeLines.TUBE_IDS)
        val justChosen = HomeLines.row(loaded, mapOf("near" to 50.0), tubeOnly, emptySet(), now, networks = setOf("tube", "dlr"))
        assertTrue(justChosen.every.single { it.leg.lineId == "dlr" }.checking)
        assertFalse(justChosen.unknown)
    }

    @Test
    fun `the watched list has no distances, so every stop counts`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("a", "38" to "bus")), now, determinedLineIds = setOf("38"))
        assertTrue(HomeLines.row(loaded, emptyMap(), tube(), emptySet(), now).every.any { it.leg.lineId == "38" })
    }

    @Test
    fun `a dismissed alert leaves the pills, still named on the page`() {
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), tube(), setOf(DismissedAlert.ofLineStatus(severe)), now)
        assertEquals(emptyList<String>(), row.lines.map { it.lineId })
        assertTrue(row.every.single { it.leg.lineId == "73" }.dismissed)
    }

    @Test
    fun `a dismissed alert is named as dismissed, never a good service, even with planned work left`() {
        val planned = app.stopdash.domain.PlannedAlert("Part closure", "Closed next weekend", java.time.LocalDate.parse("2026-10-10"))
        val diversion = LineStatus("73", 6, "Diversions", fullText = "Diverted via another road", planned = listOf(planned))
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to diversion), determinedLineIds = setOf("73"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), tube(), setOf(DismissedAlert.ofLineStatus(diversion)), now)
        // Off the pills, and on the page as the diversion it was, dismissed, just above the good services.
        assertEquals(emptyList<String>(), row.lines.map { it.lineId })
        val line = row.every.single { it.leg.lineId == "73" }
        assertTrue(line.dismissed)
        assertEquals("Diversions", line.status?.description)
        assertTrue(line.disrupted)
        assertEquals("73", row.every.first().leg.lineId)
    }

    @Test
    fun `an alert dismissed every way it runs leaves the pills, and goes just above the good services`() {
        // The list dismisses a row's own way's alert, which the line-wide status needn't match.
        val eastbound = LineStatus("73", 6, "Severe Delays", fullText = "Roadworks eastbound")
        val westbound = LineStatus("73", 6, "Severe Delays", fullText = "Roadworks westbound")
        val lineWide = LineStatus("73", 6, "Severe Delays", fullText = "Roadworks", byDirection = mapOf("outbound" to eastbound, "inbound" to westbound))
        val broken = LineStatus("38", 20, "Service Closed")
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus", "38" to "bus", "55" to "bus")), now,
            lineStatuses = mapOf("73" to lineWide, "38" to broken), determinedLineIds = setOf("73", "38"),
        )
        val dismissed = setOf(DismissedAlert.ofLineStatus(eastbound), DismissedAlert.ofLineStatus(westbound))
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), tube(), dismissed, now)
        assertEquals(listOf("38"), row.lines.map { it.lineId })
        // Live disruption, then the unchecked 55, then the dismissed 73, then the good services.
        val order = row.every.map { it.leg.lineId }
        assertEquals(listOf("38", "55", "73"), order.take(3))
        assertTrue(row.every[2].dismissed)
        assertTrue(row.every.drop(3).all { !it.disrupted && !it.unknown })
        // One way still standing keeps it on the row.
        val oneWay = HomeLines.row(loaded, mapOf("near" to 50.0), tube(), setOf(DismissedAlert.ofLineStatus(eastbound)), now)
        assertTrue("73" in oneWay.lines.map { it.lineId })
    }

    @Test
    fun `a line is never a good service on no current check`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now)
        // Nothing back for the bus, and the tube's check is too old to stand.
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), tube(at = now.minus(Duration.ofMinutes(10))), emptySet(), now)
        assertTrue(row.unknown)
        assertEquals(HomeLines.TUBE_IDS + "73", row.unknownLines.mapTo(HashSet()) { it.lineId })
        assertTrue(row.every.none { it.status != null })
    }

    @Test
    fun `back from the background, the tube's old check reads checking while the refresh runs, not couldn't check`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val old = tube(at = now.minus(Duration.ofMinutes(10)))
        val refreshing = HomeLines.row(loaded, mapOf("near" to 50.0), old, emptySet(), now, refreshing = true)
        assertTrue(row(refreshing, "victoria").checking)
        assertFalse(refreshing.unknown)
        // Once it's done without a newer check, it couldn't be checked.
        assertTrue(row(HomeLines.row(loaded, mapOf("near" to 50.0), old, emptySet(), now), "victoria").unknown)
        // A current check stands while a refresh runs.
        assertFalse(row(HomeLines.row(loaded, mapOf("near" to 50.0), tube(), emptySet(), now, refreshing = true), "victoria").checking)
    }

    private fun row(row: TripRow, id: String) = row.every.single { it.leg.lineId == id }

    @Test
    fun `while the first load checks, its lines and the tube say checking`() {
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, statusPending = true, disruptionUnknown = true, pendingLineIds = setOf("73"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), always = null, emptySet(), now)
        assertTrue(row.checking)
        assertFalse(row.unknown)
        assertTrue(row.every.all { it.checking })
        // Before any list, everything is being checked.
        assertTrue(HomeLines.row(null, emptyMap(), null, emptySet(), now).every.all { it.checking })
    }

    @Test
    fun `a tube line the list shows goes by the list's own check`() {
        val victoria = LineStatus("victoria", 6, "Severe Delays")
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "victoria" to "tube")), now, lineStatuses = mapOf("victoria" to victoria), determinedLineIds = setOf("victoria"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), tube(), emptySet(), now)
        assertEquals(listOf("victoria"), row.lines.map { it.lineId })
        assertEquals(1, row.every.count { it.leg.lineId == "victoria" })
    }

    @Test
    fun `the tube is listed by name`() {
        assertEquals(LineRef("hammersmith-city", "Hammersmith & City", "tube"), HomeLines.TUBE.single { it.id == "hammersmith-city" })
    }
}
