package app.stopdash.data

import app.stopdash.domain.LineStatus
import app.stopdash.domain.PartClosure
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

    // An alert TfL files as planned work, the only kind read for a later start.
    private fun plannedWork(reason: String) =
        status(0, "Special Service", reason).copy(disruption = TflLineDisruptionDto(category = "PlannedWork"))

    private val monday = java.time.Instant.parse("2026-09-28T06:00:00Z")

    @Test
    fun `the week ahead's work is read from its own periods, leaving out what's under way`() {
        // A real date-range answer for three public lines: the Circle closed next weekend, the Elizabeth line
        // closed early on the Sunday and severely delayed now, the Northern line good.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val text = checkNotNull(javaClass.getResource("/fixtures/line_status_week_ahead.json")).readText()
        val lines = json.decodeFromString<List<TflLineDto>>(text).associateBy { it.id }
        assertEquals(setOf("circle", "elizabeth", "northern"), lines.keys)
        val now = java.time.Instant.parse("2026-10-08T06:30:00Z")

        val circle = lines.getValue("circle").workAhead(now).single()
        assertEquals("Part Closure", circle.label)
        assertEquals(java.time.LocalDate.of(2026, 10, 10), circle.startsOn)
        assertTrue(circle.fullText, circle.fullText.contains("Edgware Road"))
        // The Sunday closure is ahead; the delays in force now are the line's own status's to say.
        val elizabeth = lines.getValue("elizabeth").workAhead(now)
        assertEquals(listOf(java.time.LocalDate.of(2026, 10, 11)), elizabeth.map { it.startsOn })
        assertEquals(emptyList<Any>(), lines.getValue("northern").workAhead(now))
        // Once the weekend has begun, it's under way, not to come.
        assertEquals(emptyList<Any>(), lines.getValue("circle").workAhead(java.time.Instant.parse("2026-10-10T09:00:00Z")))
        // The soonest work's own start, which the week's answer is kept no longer than (Codex, #697).
        val start = checkNotNull(lines.getValue("circle").nextWorkStart(now))
        assertEquals(java.time.LocalDate.of(2026, 10, 10), start.atZone(java.time.ZoneId.of("Europe/London")).toLocalDate())
        assertEquals(null, lines.getValue("northern").nextWorkStart(now))
    }

    @Test
    fun `a closure in the week ahead carries the stretch it shuts, for the map`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val text = checkNotNull(javaClass.getResource("/fixtures/line_status_week_ahead.json")).readText()
        val circle = json.decodeFromString<List<TflLineDto>>(text).single { it.id == "circle" }
        val now = java.time.Instant.parse("2026-10-08T06:30:00Z")
        val stretch = listOf("A", "B", "C")
        val placed = circle.workAhead(now) { listOf(AffectedSection("", stretch)) }.single()
        assertEquals(listOf(stretch), placed.closure?.sections)
        // Unplaced where the detail names no stops.
        assertEquals(null, circle.workAhead(now).single().closure)
    }

    @Test
    fun `a week ahead dated so it can't be read fails, never a clean week`() {
        // Work TfL dated unreadably may be work to come: the check fails rather than leave it out (Codex, #697).
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val line = json.decodeFromString<TflLineDto>(
            """{"id": "circle", "name": "Circle", "lineStatuses": [{"statusSeverity": 5, "statusSeverityDescription": "Part Closure",
              "reason": "No service.", "validityPeriods": [{"fromDate": "next Saturday", "toDate": "2026-10-12T04:00:00Z"}]}]}""",
        )
        val failed = runCatching { line.workAhead(java.time.Instant.parse("2026-10-08T06:30:00Z")) }.exceptionOrNull()
        assertTrue("$failed", failed is app.stopdash.domain.TflException.Unreachable)
        // Nor one TfL gave no start at all.
        val startless = json.decodeFromString<TflLineDto>(
            """{"id": "circle", "name": "Circle", "lineStatuses": [{"statusSeverity": 5, "statusSeverityDescription": "Part Closure",
              "reason": "No service.", "validityPeriods": [{"toDate": "2026-10-12T04:00:00Z"}]}]}""",
        )
        val alsoFailed = runCatching { startless.workAhead(java.time.Instant.parse("2026-10-08T06:30:00Z")) }.exceptionOrNull()
        assertTrue("$alsoFailed", alsoFailed is app.stopdash.domain.TflException.Unreachable)
    }

    @Test
    fun `work that hasn't started is planned, not a disruption`() {
        val later = "Road will be closed from 13 Oct 07:00 until 31 Oct 18:00. Buses will be diverted."
        val result = checkNotNull(line(plannedWork(later)).toLineStatus(monday))
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
            line(plannedWork(later), status(0, "Special Service", now)).toLineStatus(monday),
        )
        assertTrue(result.disrupted)
        assertEquals(now, result.fullText)
        assertEquals(later, result.planned.single().fullText)
    }

    @Test
    fun `an alert is the line's sole one only when nothing else is under way`() {
        // Only the worst's words are kept, so a second alert under way means they don't speak for the line (Codex, PR #455).
        val diversion = "Buses diverted via Example Street due to roadworks."
        assertTrue(checkNotNull(line(status(5, "Diversion", diversion)).toLineStatus(monday)).soleAlert)
        // The same alert repeated is still one.
        assertTrue(checkNotNull(line(status(5, "Diversion", diversion), status(5, "Diversion", diversion)).toLineStatus(monday)).soleAlert)
        val both = checkNotNull(line(status(5, "Diversion", diversion), status(6, "Severe Delays", "Severe delays across the route.")).toLineStatus(monday))
        assertFalse(both.soleAlert)
        // The same words under another label are another alert, as a dismissal tells them (Codex on #519).
        assertFalse(checkNotNull(line(status(5, "Diversion", diversion), status(5, "Part Closure", diversion)).toLineStatus(monday)).soleAlert)
        // A good service has no alert to speak for.
        assertFalse(checkNotNull(line(status(10, "Good Service")).toLineStatus(monday)).soleAlert)
    }

    @Test
    fun `every alert under way is kept in its own words, and work still to come isn't`() {
        // So a trip can tell each one's stretch from its ride's (maintainer, 2026-10-03).
        val diversion = "Buses diverted via Example Street due to roadworks."
        val delays = "Severe delays across the route."
        val later = "Road will be closed from 13 Oct 07:00 until 31 Oct 18:00. Buses will be diverted."
        val result = checkNotNull(
            line(status(5, "Diversion", diversion), status(6, "Severe Delays", delays), status(5, "Diversion", diversion), plannedWork(later))
                .toLineStatus(monday),
        )
        assertEquals(setOf(diversion, delays), result.underWay.mapTo(HashSet()) { it.fullText })
        assertEquals(2, result.underWay.size)
        // Once its day comes, the work to come joins them.
        val started = result.asOf(java.time.LocalDate.of(2026, 10, 13))
        assertEquals(setOf(diversion, delays, later), started.underWay.mapTo(HashSet()) { it.fullText })
    }

    @Test
    fun `only planned work is read for a later start`() {
        val later = "Service suspended until further notice. Replacement buses will run from 13 October."
        // A real-time alert is happening now, whatever its text dates (Codex, PR #337).
        val realTime = status(0, "Special Service", later).copy(disruption = TflLineDisruptionDto(category = "RealTime"))
        assertTrue(checkNotNull(line(realTime).toLineStatus(monday)).disrupted)
        // So is one TfL gave no category for: the safe side.
        val uncategorized = status(0, "Special Service", "Road will be closed from 13 Oct until 31 Oct.")
        val result = checkNotNull(line(uncategorized).toLineStatus(monday))
        assertTrue(result.disrupted)
        assertTrue(result.planned.isEmpty())
        // Read case-insensitively, so a change in TfL's casing doesn't turn planned work into a disruption.
        val lower = status(0, "Special Service", "Road will be closed from 13 Oct until 31 Oct.")
            .copy(disruption = TflLineDisruptionDto(category = "plannedwork"))
        assertFalse(checkNotNull(line(lower).toLineStatus(monday)).disrupted)
    }

    @Test
    fun `the category is read from the plain response`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val entry = json.decodeFromString<TflLineStatusEntryDto>(
            """{"statusSeverity": 0, "reason": "x", "disruption": {"category": "PlannedWork", "categoryDescription": "PlannedWork", "affectedRoutes": []}}""",
        )
        assertTrue(entry.isPlannedWork())
        assertFalse(TflLineStatusEntryDto(reason = "x").isPlannedWork())
    }

    @Test
    fun `the detail's affected stops are read, and kept with the shown alert`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // As TfL's detail answers a part suspension: the shut section's stops, ends included.
        val entry = json.decodeFromString<TflLineStatusEntryDto>(
            """{"statusSeverity": 3, "statusSeverityDescription": "Part Suspended", "reason": "No service between A and C.",
               "disruption": {"category": "RealTime", "affectedRoutes": [],
                 "affectedStops": [{"naptanId": "A", "commonName": "A Station"}, {"naptanId": "B"}, {"id": "C"}, {"naptanId": ""}]}}""",
        )
        assertEquals(setOf("A", "B", "C"), entry.affectedStopIds())
        assertEquals(emptySet<String>(), status(3, "Part Suspended", "x").affectedStopIds())

        val delays = status(9, "Minor Delays", "Minor delays.")
        val stops = mapOf(entry.reason to listOf(listOf("A", "B", "C")), delays.reason to listOf(listOf("X", "Y")))
        val result = checkNotNull(
            line(delays, entry).toLineStatus(
                monday,
                sectionsOf = { stops[it.reason].orEmpty().anyWay() },
            ) { entry -> if (entry.reason == delays.reason) setOf("outbound") else null },
        )
        // The suspension is shown, with its own stops, line-wide and the one way it applies alone.
        assertEquals("Part Suspended", result.description)
        assertEquals(listOf(listOf("A", "B", "C")), result.sections)
        assertEquals(listOf(listOf("A", "B", "C")), result.forDirection("inbound").sections)
        // Not looked up: no stops, but still a closure under way.
        val unlooked = checkNotNull(line(entry).toLineStatus(monday))
        assertEquals(emptyList<List<String>>(), unlooked.sections)
        assertEquals(listOf("Part Suspended"), unlooked.closures.map { it.description })
        // With no route's stops given there's no order to split or run the affected stops by: no
        // section, so the alert is never placed.
        assertEquals(emptyList<AffectedSection>(), entry.affectedSections())
    }

    @Test
    fun `every part closure under way places, not only the one shown`() {
        // Two part closures of one rank, on different stretches; and a delay whose detail names stops.
        val first = status(5, "Part Closure", "No service between X and Y.")
        val second = status(5, "Part Closure", "No service between A and C.")
        val delays = status(6, "Severe Delays", "Severe delays between D and E.")
        val stops = mapOf(
            first.reason to listOf(listOf("X", "Y")),
            second.reason to listOf(listOf("A", "B", "C")),
            delays.reason to listOf(listOf("D", "E")),
        )
        val result = checkNotNull(line(first, second, delays).toLineStatus(monday, sectionsOf = { stops[it.reason].orEmpty().anyWay() }))
        assertEquals("No service between X and Y.", result.fullText)
        assertEquals(listOf(listOf("X", "Y"), listOf("A", "B", "C")), result.sections)
        assertTrue(result.coversRide(listOf("A", "B")))
        // A delay shuts nothing, so its stops never place.
        assertFalse(result.coversRide(listOf("D", "E")))
        // Shown behind a worse suspension elsewhere, the closure still places.
        val suspended = status(3, "Part Suspended", "No service between P and Q.")
        val behind = checkNotNull(
            line(suspended, second).toLineStatus(monday, sectionsOf = { if (it.reason == second.reason) listOf(listOf("A", "B", "C")).anyWay() else emptyList() }),
        )
        assertEquals("Part Suspended", behind.description)
        assertTrue(behind.coversRide(listOf("B", "C")))
        // Each kept whole, so the one placed on a ride can be named: its own wording, not the shown one's.
        assertEquals(listOf("Part Closure", "No service between A and C."), behind.closureOn(listOf("B", "C"))?.let { listOf(it.description, it.fullText) })
        // Behind minor delays TfL numbers lower (9 to 11), a part closed still places.
        val closed = status(11, "Part Closed", "No service between A and C.")
        val minor = checkNotNull(
            line(status(9, "Minor Delays", "Minor delays."), closed).toLineStatus(monday, sectionsOf = { if (it.reason == closed.reason) listOf(listOf("A", "B", "C")).anyWay() else emptyList() }),
        )
        assertEquals("Minor Delays", minor.description)
        assertEquals("Part Closed", minor.closureOn(listOf("A", "B"))?.description)
    }

    @Test
    fun `an alert's direction is its own entry's, not another's with the same text`() {
        // A part closed one way, minor delays the other, worded the same: the delays' way carries no
        // closure, which would otherwise stand in for the alert it shows (Codex, PR #446).
        val closed = status(11, "Part Closed", "Engineering work.")
        val delays = status(9, "Minor Delays", "Engineering work.")
        val result = checkNotNull(
            line(closed, delays).toLineStatus(monday) { entry -> if (entry.statusSeverity == 11) setOf("inbound") else setOf("outbound") },
        )
        assertEquals(listOf("Part Closed"), result.forDirection("inbound").closures.map { it.description })
        assertEquals(emptyList<String>(), result.forDirection("outbound").closures.map { it.description })
        assertEquals("Minor Delays", result.forDirection("outbound").description)
    }

    @Test
    fun `a planned part closure keeps its sections for the day it starts`() {
        // Read before it starts, it isn't under way; kept across that day, it places on its section
        // without waiting for the next check (Codex, PR #446). A planned diversion has none to keep.
        val later = "No service between A and C from 13 Oct 07:00 until 31 Oct 18:00."
        val closed = status(5, "Part Closure", later).copy(disruption = TflLineDisruptionDto(category = "PlannedWork"))
        val result = checkNotNull(line(closed).toLineStatus(monday, sectionsOf = { listOf(listOf("A", "B", "C")).anyWay() }))
        assertEquals(emptyList<PartClosure>(), result.closures)
        assertFalse(result.coversRide(listOf("A", "B")))
        val started = result.asOf(java.time.LocalDate.of(2026, 10, 13))
        assertTrue(started.coversRide(listOf("A", "B")))
        assertEquals("Part Closure", started.closureOn(listOf("A", "B"))?.description)
        assertNull(checkNotNull(line(plannedWork("Road will be closed from 13 Oct 07:00. Buses will be diverted.")).toLineStatus(monday)).planned.single().closure)
    }

    @Test
    fun `a direction's status keeps only its own sections, or those of no stated direction`() {
        // One closure both ways, shutting A–B the same way round in both (a bus's one-way loop), but
        // TfL names that stretch on the outbound route only: an inbound ride round the loop isn't
        // placed on it, and with its direction unknown it is (Codex, PR #446).
        val closed = status(5, "Part Closure", "No service between A and B.")
        val outbound = checkNotNull(
            line(closed).toLineStatus(monday, sectionsOf = { listOf(AffectedSection("outbound", listOf("A", "B"))) }) { setOf("inbound", "outbound") },
        )
        assertTrue(outbound.forDirection("outbound").coversRide(listOf("A", "B")))
        assertFalse(outbound.forDirection("inbound").coversRide(listOf("A", "B")))
        assertTrue(outbound.coversRide(listOf("A", "B")))
        // A section on a route TfL gave no direction for counts both ways.
        val unstated = checkNotNull(
            line(closed).toLineStatus(monday, sectionsOf = { listOf(AffectedSection("", listOf("A", "B"))) }) { setOf("inbound", "outbound") },
        )
        assertTrue(unstated.forDirection("inbound").coversRide(listOf("A", "B")))
    }

    @Test
    fun `a diversion read from a catch-all's reason is never placed, though it ranks with a part closure`() {
        val diverted = status(0, "Special Service", "Buses diverted between A and C.")
        val closed = status(5, "Part Closure", "No service between A and C.")
        val sections = { _: TflLineStatusEntryDto -> listOf(listOf("A", "B", "C")).anyWay() }
        val diversion = checkNotNull(line(diverted).toLineStatus(monday, sectionsOf = sections))
        assertEquals("Diversion", diversion.description)
        assertEquals(5, diversion.severity)
        assertEquals(emptyList<List<String>>(), diversion.sections)
        assertEquals(emptyList<PartClosure>(), diversion.closures)
        assertFalse(diversion.coversRide(listOf("A", "B")))
        // A part closure TfL words itself, with the same section, is.
        val closure = checkNotNull(line(closed).toLineStatus(monday, sectionsOf = sections))
        assertTrue(closure.coversRide(listOf("A", "B")))
    }

    @Test
    fun `an alert's sections are its affected stops' unbroken runs along each affected route`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun route(direction: String, vararg stops: String) =
            stops.joinToString(prefix = "{\"direction\": \"$direction\", \"routeSectionNaptanEntrySequence\": [", postfix = "]}") {
                """{"ordinal": 1, "stopPoint": {"naptanId": "$it"}}"""
            }
        // One alert naming two sections, A–B and D–E, on a route that also calls at C between them;
        // the route back lists the same sections the other way; a third route gives no direction. TfL's
        // affected stops are unordered.
        val entry = json.decodeFromString<TflLineStatusEntryDto>(
            """{"statusSeverity": 3, "reason": "No service between A and B, and D and E.",
               "disruption": {"affectedRoutes": [${route("outbound", "A", "B", "C", "D", "E")}, ${route("Inbound", "E", "D", "C", "B", "A")}, ${route("", "X", "A", "B")}],
                 "affectedStops": [{"naptanId": "E"}, {"naptanId": "A"}, {"naptanId": "D"}, {"naptanId": "B"}]}}""",
        )
        // Each run in its route's order and with its route's direction, the way back its own; X isn't
        // affected, so the third route's run is A–B, with no direction.
        assertEquals(
            listOf(
                AffectedSection("outbound", listOf("A", "B")),
                AffectedSection("outbound", listOf("D", "E")),
                AffectedSection("inbound", listOf("E", "D")),
                AffectedSection("inbound", listOf("B", "A")),
                AffectedSection("", listOf("A", "B")),
            ),
            entry.affectedSections(),
        )
        assertEquals(emptyList<AffectedSection>(), status(3, "Part Suspended", "x").affectedSections())
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
        val entry = plannedWork("From 09:00 on Monday 23 March until 17:00 on Monday 26 October, buses divert.")
            .copy(validityPeriods = listOf(TflValidityPeriodDto("2026-03-10T09:00:00Z")))
        val result = checkNotNull(line(entry).toLineStatus(monday))
        assertTrue(result.disrupted)
    }

    @Test
    fun `an unreadable posting date keeps the alert under way, and is reported`() {
        val entry = plannedWork("Road will be closed from 1 December. Buses will be diverted.")
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
        val result = checkNotNull(line(plannedWork(later)).toLineStatus(monday) { setOf("outbound") })
        assertEquals(1, result.forDirection("outbound").planned.size)
        assertTrue(result.forDirection("inbound").planned.isEmpty())
    }

    @Test
    fun `a direction-scoped alert is split out by direction`() {
        val north = "Northbound buses diverted via Street A."
        val south = "Road closed: southbound buses will be diverted."
        val result = checkNotNull(
            line(status(0, "Special Service", north), status(0, "Special Service", south))
                .toLineStatus { entry -> if (entry.reason == north) setOf("inbound") else setOf("outbound") },
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
                .toLineStatus { entry -> if (entry.reason == north) setOf("inbound") else null },
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
        ).toLineStatus { entry -> if ("northbound" in entry.reason) setOf("inbound") else setOf("outbound") }!!
        val north = split.forDirection("inbound")
        // The northbound way has its one alert; the line as a whole has two ([LineStatus.soleAlert]).
        assertTrue(north.soleAlert)
        assertFalse(split.soleAlert)
        // Each with its own alerts under way: the northbound way's one, the line's two.
        assertEquals(north, split.copy(byDirection = emptyMap(), soleAlert = true, underWay = split.underWay.take(1)))
        assertEquals(2, split.underWay.size)
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

// Every stretch the status's closures shut, in the order they came.
private val LineStatus.sections: List<List<String>> get() = closures.flatMap { it.sections }

// Sections TfL gave for routes of no stated direction, counted both ways.
private fun List<List<String>>.anyWay(): List<AffectedSection> = map { AffectedSection("", it) }
