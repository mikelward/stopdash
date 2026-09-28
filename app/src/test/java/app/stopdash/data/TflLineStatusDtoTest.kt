package app.stopdash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TflLineStatusDtoTest {
    private fun line(vararg statuses: TflLineStatusEntryDto) =
        TflLineDto(id = "line", name = "Line", lineStatuses = statuses.toList())

    private fun status(severity: Int, description: String, reason: String = "") =
        TflLineStatusEntryDto(
            statusSeverity = severity,
            statusSeverityDescription = description,
            reason = reason,
        )

    private val monday = java.time.Instant.parse("2026-09-28T06:00:00Z")

    @Test
    fun `work that hasn't started is planned, not a disruption`() {
        val later = "Road will be closed from 13 Oct 07:00 until 31 Oct 18:00. Buses will be diverted."
        val result = checkNotNull(line(status(0, "Special Service", later)).toLineStatus(monday))
        assertFalse(result.disrupted)
        assertEquals("Good Service", result.description)
        val planned = result.planned.single()
        assertEquals("Diversion", planned.label)
        assertEquals(java.time.LocalDate.of(2026, 10, 13), planned.startsOn)
        assertTrue(result.hasAlerts)
    }

    @Test
    fun `work under way stays a disruption beside work still to come`() {
        val now = "Buses diverted until 23:00 on Thursday 1 October due to works."
        val later = "Road will be closed from 13 Oct 07:00 until 31 Oct 18:00. Buses will be diverted."
        val result = checkNotNull(
            line(status(0, "Special Service", later), status(0, "Special Service", now)).toLineStatus(monday),
        )
        assertTrue(result.disrupted)
        assertEquals(now, result.fullText)
        assertEquals(later, result.planned.single().fullText)
    }

    @Test
    fun `an alert that can't be dated counts as under way`() {
        val result = checkNotNull(
            line(status(0, "Special Service", "Buses diverted due to a burst water main.")).toLineStatus(monday),
        )
        assertTrue(result.disrupted)
        assertTrue(result.planned.isEmpty())
    }

    @Test
    fun `a missing year is placed after the day TfL posted the alert`() {
        // Posted in March for work from 23 March: under way in September, not next March's.
        val entry = status(0, "Special Service", "From 09:00 on Monday 23 March until 17:00 on Monday 26 October, buses divert.")
            .copy(validityPeriods = listOf(TflValidityPeriodDto("2026-03-10T09:00:00Z")))
        val result = checkNotNull(line(entry).toLineStatus(monday))
        assertTrue(result.disrupted)
    }

    @Test
    fun `an unreadable posting date keeps the alert under way, and is reported`() {
        val entry = status(0, "Special Service", "Road will be closed from 1 December. Buses will be diverted.")
            .copy(validityPeriods = listOf(TflValidityPeriodDto("not a date")))
        val bad = mutableListOf<String>()
        val result = checkNotNull(line(entry).toLineStatus(monday, onBadDate = { bad += it }))
        assertTrue(result.disrupted)
        assertTrue(result.planned.isEmpty())
        assertEquals(listOf("not a date"), bad)
    }

    @Test
    fun `planned work is split by direction like a disruption`() {
        val later = "Road will be closed from 13 Oct until 31 Oct. Southbound buses will be diverted."
        val result = checkNotNull(line(status(0, "Special Service", later)).toLineStatus(monday) { setOf("outbound") })
        assertEquals(1, result.forDirection("outbound").planned.size)
        assertTrue(result.forDirection("inbound").planned.isEmpty())
    }

    @Test
    fun `a direction-scoped alert is split out by direction`() {
        val north = "Northbound buses diverted via Street A."
        val south = "Road closed: southbound buses will be diverted."
        val result = checkNotNull(
            line(status(0, "Special Service", north), status(0, "Special Service", south))
                .toLineStatus { reason -> if (reason == north) setOf("inbound") else setOf("outbound") },
        )
        assertEquals(north, result.forDirection("inbound").fullText)
        assertEquals(south, result.forDirection("outbound").fullText)
        // A row with no direction keeps the line-wide status.
        assertTrue(result.forDirection("").disrupted)
    }

    @Test
    fun `a direction with no alert of its own reads as good service`() {
        val north = "Northbound buses diverted via Street A."
        val result = checkNotNull(
            line(status(0, "Special Service", north)).toLineStatus { setOf("inbound") },
        )
        assertTrue(result.forDirection("inbound").disrupted)
        assertFalse(result.forDirection("outbound").disrupted)
    }

    @Test
    fun `an alert whose direction isn't known counts for both`() {
        val result = checkNotNull(
            line(status(0, "Special Service", "Buses diverted.")).toLineStatus { null },
        )
        // Nothing to split: the line-wide status holds everywhere, and none is stored.
        assertTrue(result.byDirection.isEmpty())
        assertTrue(result.forDirection("outbound").disrupted)
        assertTrue(result.forDirection("inbound").disrupted)
    }

    @Test
    fun `an unknown alert still flags a direction whose known alerts are elsewhere`() {
        val north = "Northbound buses diverted via Street A."
        val unknown = "Buses will be diverted and miss stops."
        val result = checkNotNull(
            line(status(0, "Special Service", north), status(0, "Special Service", unknown))
                .toLineStatus { reason -> if (reason == north) setOf("inbound") else null },
        )
        assertEquals(unknown, result.forDirection("outbound").fullText)
    }

    @Test
    fun `affected directions are read from the detailed response's routes`() {
        val entry = TflLineStatusEntryDto(
            disruption = TflLineDisruptionDto(
                listOf(TflAffectedRouteDto("Outbound"), TflAffectedRouteDto("outbound"), TflAffectedRouteDto("")),
            ),
        )
        assertEquals(setOf("outbound"), entry.affectedDirections())
    }

    @Test
    fun `no status entries is unknown, not good service`() {
        assertNull(line().toLineStatus())
    }

    @Test
    fun `a good service is not disrupted`() {
        val result = line(status(10, "Good Service")).toLineStatus()
        assertFalse(checkNotNull(result).disrupted)
    }

    @Test
    fun `an informative disruption keeps TfL's own wording`() {
        // The Sunday-closure case: a part closure is exactly what a rider needs to see.
        val result = checkNotNull(line(status(5, "Part Closure")).toLineStatus())
        assertTrue(result.disrupted)
        assertEquals("Part Closure", result.description)
    }

    @Test
    fun `takes the worst of several informative statuses`() {
        val result = checkNotNull(
            line(status(10, "Good Service"), status(9, "Minor Delays"), status(2, "Suspended"))
                .toLineStatus(),
        )
        assertEquals("Suspended", result.description)
    }

    @Test
    fun `a bus diversion behind Special Service is named from the reason`() {
        val result = checkNotNull(
            line(status(0, "Special Service", "Buses will be diverted and miss stops."))
                .toLineStatus(),
        )
        assertTrue(result.disrupted)
        assertEquals("Diversion", result.description)
    }

    @Test
    fun `a more severe graded status wins over a diversion catch-all`() {
        // A suspended line is not running, which is worse than a diversion — showing the
        // milder "Diversion" would be misleading, so the suspension wins.
        val result = checkNotNull(
            line(status(0, "Special Service", "Buses diverted."), status(2, "Suspended"))
                .toLineStatus(),
        )
        assertTrue(result.disrupted)
        assertEquals("Suspended", result.description)
    }

    @Test
    fun `a diversion catch-all wins over a less severe coexisting status`() {
        // The catch-all resolves to "Diversion", which outranks minor delays; it must not be
        // discarded just for being a severity-0 Special Service.
        val result = checkNotNull(
            line(status(0, "Special Service", "Buses diverted."), status(9, "Minor Delays"))
                .toLineStatus(),
        )
        assertEquals("Diversion", result.description)
    }

    @Test
    fun `classifies across every coexisting Special Service entry, not just the first`() {
        // A line can carry several Special Service records (the live 43 has three). A later
        // entry's reason must still be seen — the first here names nothing.
        val result = checkNotNull(
            line(
                status(0, "Special Service", "Planned works this weekend."),
                status(0, "Special Service", "Buses will be diverted."),
            ).toLineStatus(),
        )
        assertEquals("Diversion", result.description)
    }

    @Test
    fun `a diversion in any coexisting Special Service entry wins over a keyword-less one`() {
        // One entry names a diversion, another names nothing recognized; the diversion is
        // shown rather than the generic fallback.
        val result = checkNotNull(
            line(
                status(0, "Special Service", "Planned works this weekend."),
                status(0, "Special Service", "Buses on diversion."),
            ).toLineStatus(),
        )
        assertEquals("Diversion", result.description)
    }

    @Test
    fun `an informative status wins over a Service Alert of equal severity`() {
        // A keyword-less catch-all resolves to Service Alert (mildest); a coexisting
        // Minor Delays of the same severity is more informative and must be shown.
        val result = checkNotNull(
            line(status(0, "Special Service", "Planned works."), status(9, "Minor Delays"))
                .toLineStatus(),
        )
        assertEquals("Minor Delays", result.description)
    }

    @Test
    fun `an informative status wins over a Service Alert even at a milder severity`() {
        // "Information" (a catch-all, severity 19) names a coexisting "Diverted" nowhere, so it
        // falls back to Service Alert (synthetic severity 9). The explicit "Diverted" carries a
        // higher — milder — TfL number, but is the named status a rider needs, so it must be
        // shown ahead of the placeholder rather than lost to it by a raw severity comparison.
        val result = checkNotNull(
            line(status(19, "Information", "Timetable leaflets are available."), status(15, "Diverted"))
                .toLineStatus(),
        )
        assertEquals("Diverted", result.description)
    }

    @Test
    fun `a severe status with no wording is not hidden behind a milder named one`() {
        // TfL gave a severe entry (severity 1) no description but a milder "Minor Delays"
        // alongside. The severe one borrows the "Service Alert" label yet must still win —
        // showing "Minor Delays" would hide a closure-level condition (SPEC principle 1).
        val result = checkNotNull(
            line(status(1, ""), status(9, "Minor Delays")).toLineStatus(),
        )
        assertTrue(result.disrupted)
        assertEquals("Service Alert", result.description)
    }

    @Test
    fun `a Special Service with no usable reason stays flagged as a Service Alert`() {
        // The line is disrupted; it must not be turned into a good service (which would show
        // its countdowns as verified-clean, SPEC principle 1). The meaningless "Special
        // Service" is never shown — it reads "Service Alert" until the reason can be parsed.
        val result = checkNotNull(line(status(0, "Special Service")).toLineStatus())
        assertTrue(result.disrupted)
        assertEquals("Service Alert", result.description)
    }

    @Test
    fun `a disruption with a blank description falls back to Service Alert`() {
        val result = checkNotNull(line(status(4, "")).toLineStatus())
        assertTrue(result.disrupted)
        assertEquals("Service Alert", result.description)
    }

    @Test
    fun `retains the shown disruption's full reason text for the detail view`() {
        // The chip is the short label; the detail shows the prose. A graded status carries its
        // reason through to fullText, trimmed.
        val reason = "Victoria line: Severe delays while we fix a signal failure at Victoria."
        val result = checkNotNull(line(status(6, "Severe Delays", "  $reason  ")).toLineStatus())
        assertEquals("Severe Delays", result.description)
        assertEquals(reason, result.fullText)
    }

    @Test
    fun `the reason retained is the chosen entry's, not a coexisting milder one`() {
        // Suspended wins over the diversion catch-all; the retained prose must be the suspension's,
        // so the detail's text matches the chip it sits under rather than a discarded status.
        val result = checkNotNull(
            line(
                status(0, "Special Service", "Buses diverted via London Wall."),
                status(2, "Suspended", "No service while we deal with a fault."),
            ).toLineStatus(),
        )
        assertEquals("Suspended", result.description)
        assertEquals("No service while we deal with a fault.", result.fullText)
    }

    @Test
    fun `a diversion named from the catch-all keeps the catch-all's reason as its text`() {
        val result = checkNotNull(
            line(status(0, "Special Service", "Buses will be diverted and miss stops."))
                .toLineStatus(),
        )
        assertEquals("Diversion", result.description)
        assertEquals("Buses will be diverted and miss stops.", result.fullText)
    }

    @Test
    fun `a good service carries no full text`() {
        assertNull(checkNotNull(line(status(10, "Good Service")).toLineStatus()).fullText)
    }

    @Test
    fun `a disruption TfL gave no reason for carries no full text`() {
        // Nothing to expand beyond the chip label — the detail shows the label alone.
        assertNull(checkNotNull(line(status(4, "Part Suspended")).toLineStatus()).fullText)
    }

    @Test
    fun `dismissing the worse way's alert leaves the other way's on the line-wide status`() {
        // As TfL shapes it: the line-wide status is the worst alert, the northbound one.
        val split = line(
            status(6, "Severe Delays", "Signal failure northbound."),
            status(9, "Minor Delays", "Train fault southbound."),
        ).toLineStatus { reason -> if ("northbound" in reason) setOf("inbound") else setOf("outbound") }!!
        val north = split.forDirection("inbound")
        assertEquals(north, split.copy(byDirection = emptyMap()))
        val snapshot = app.stopdash.domain.DeparturesSnapshot(
            emptyList(), java.time.Instant.EPOCH,
            lineStatuses = mapOf("line" to app.stopdash.domain.LineStatusCheck(split, java.time.Instant.EPOCH)),
        ).withDismissals(setOf(app.stopdash.domain.DismissedAlert.ofLineStatus(north)))
        val live = snapshot.liveLineStatuses(java.time.Instant.EPOCH).getValue("line")
        // A row with no direction, and the southbound one, show the southbound alert; northbound, none.
        assertEquals("Minor Delays", live.description)
        assertEquals(9, live.severity)
        assertEquals(9, live.forDirection("outbound").severity)
        assertEquals(false, live.forDirection("inbound").disrupted)
    }
}
