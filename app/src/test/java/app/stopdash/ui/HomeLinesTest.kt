package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import app.stopdash.ThreadRecorder
import app.stopdash.domain.Departure
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopArrivals
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen's disruptions row: every line the screen shows, and the lines the rider chose; every other rail line under *Other*. */
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
    fun `every tube line and every listed stop's lines however far, the other rail lines under Other`() {
        val loaded = DeparturesUiState.Loaded(
            stops = listOf(stop("near", "73" to "bus"), stop("far", "38" to "bus")),
            fetchedAt = now,
            lineStatuses = mapOf("73" to severe),
            determinedLineIds = setOf("73", "38"),
        )
        val row = tubeRow(loaded, mapOf("near" to 120.0, "far" to 900.0), tube(), emptySet(), now)
        // The far stop is on the screen too, so its line is watched (maintainer, 2026-10-17).
        assertEquals(HomeLines.TUBE_IDS + "73" + "38", row.watchedIds.toSet())
        // The disrupted one leads the pills and the page; the rest are good services, checked.
        assertEquals(listOf("73"), row.lines.map { it.lineId })
        assertEquals("73", row.every.first().leg.lineId)
        // Every other rail line follows under Other, in Settings' order, checking until the page asks about them,
        // which the card never waits on.
        assertEquals((HomeLines.RAIL_IDS - HomeLines.TUBE_IDS).toList(), row.otherIds)
        assertTrue(row.every.filter { it.watched != true }.all { it.checking })
        assertFalse(row.checking)
        assertFalse(row.unknown)
    }

    @Test
    fun `the pills go worst first, the rider's own ahead of the rest when as bad`() {
        // The tube's Central and a bus at a near stop, both with severe delays; the tube's lines come first
        // in TfL's order, the bus only after them.
        val central = LineStatus("central", 6, "Severe Delays")
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to central)), emptySet(), now, networks = setOf("tube"))
        assertEquals(listOf("73", "central"), row.lines.map { it.lineId })
        assertEquals(listOf("73", "central"), row.every.take(2).map { it.leg.lineId })
        // A worse one far away leads the pills (maintainer, 2026-10-07): the near one, milder, goes after it,
        // and "+N" takes the mildest first. The page lists them the same way: a line on a network the rider
        // chose is watched too (2026-10-17).
        val suspended = LineStatus("central", 16, "Suspended")
        val worse = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to suspended)), emptySet(), now, networks = setOf("tube"))
        assertEquals(listOf("central", "73"), worse.lines.map { it.lineId })
        assertEquals(listOf("central", "73"), worse.every.take(2).map { it.leg.lineId })
        // A starred row's line counts as the rider's own while its stop is in the list, far away or not.
        val victoria = LineStatus("victoria", 20, "Service Closed")
        val withFar = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus"), stop("far", "central" to "tube")), now,
            lineStatuses = mapOf("73" to severe, "central" to central), determinedLineIds = setOf("73", "central"),
        )
        val starred = setOf(app.stopdash.domain.StarredRow("far", "central", "outbound"))
        val fav = HomeLines.row(withFar, mapOf("near" to 50.0, "far" to 2000.0), tube(good + ("central" to central) + ("victoria" to victoria)), emptySet(), now, networks = setOf("tube"), starred = starred)
        // Both the rider's own and as bad as each other: by name, the 73 before the Central. Every line here is
        // watched (near the rider, starred, or on the network they chose), so the page lists the pills in their order.
        assertTrue(fav.lines.map { it.lineId }.let { it.indexOf("73") < it.indexOf("central") })
        assertEquals(HomeLines.RAIL_IDS - HomeLines.TUBE_IDS, fav.otherIds.toSet())
        assertEquals(fav.lines.map { it.lineId }, fav.every.filter { it.disrupted }.map { it.leg.lineId })
        // A star whose stop has left the list ranks nothing: its line goes with the network's (Codex, #640).
        val goneStar = setOf(app.stopdash.domain.StarredRow("elsewhere", "central", "outbound"))
        val left = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to central) + ("victoria" to victoria)), emptySet(), now, networks = setOf("tube"), starred = goneStar)
        assertTrue(left.lines.map { it.lineId }.let { it.indexOf("73") < it.indexOf("central") })
        assertEquals(setOf("73", "central", "victoria"), left.lines.mapTo(HashSet()) { it.lineId })
        // So does any line a favorite journey rides, not only the one it was starred from.
        val ridden = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to central) + ("victoria" to victoria)), emptySet(), now, networks = setOf("tube"), journeyLines = setOf("central"))
        assertTrue(ridden.lines.map { it.lineId }.let { it.indexOf("73") < it.indexOf("central") })
        assertEquals(ridden.lines.map { it.lineId }, ridden.every.filter { it.disrupted && it.watched == true }.map { it.leg.lineId })
        // A dismissed one is never a pill, however near.
        val gone = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to central)), setOf(DismissedAlert.ofLineStatus(severe)), now, networks = setOf("tube"))
        assertEquals(listOf("central"), gone.lines.map { it.lineId })
    }

    @Test
    fun `the rider's starred stops ride with the row, for a line's map to keep`() {
        // Big interchanges stand in for the rider's own.
        val starred = setOf(app.stopdash.domain.StarredRow("940GZZLUKSX", "victoria", "outbound"))
        val journey = app.stopdash.domain.FavoriteJourney(
            from = app.stopdash.domain.JourneyEnd("940GZZLUBNK", "Bank", areaId = "HUBBAN"),
            to = app.stopdash.domain.JourneyEnd("940GZZLUWLO", "Waterloo"),
            lineId = "northern",
        )
        val row = HomeLines.row(null, emptyMap(), tube(good), emptySet(), now, networks = setOf("tube"), starred = starred, journeys = listOf(journey))
        assertEquals(setOf("940GZZLUKSX", "940GZZLUBNK", "HUBBAN", "940GZZLUWLO"), row.starredStops)
    }

    @Test
    fun `each line's map keeps its station nearest the rider, within reach and only where there's a position`() {
        // Big interchanges stand in for where the rider is.
        val loaded = DeparturesUiState.Loaded(
            listOf(
                stop("940GZZLUKSX", "northern" to "tube", "victoria" to "tube"),
                stop("940GZZLUEUS", "northern" to "tube"),
                stop("940GZZLUWLO", "jubilee" to "tube"),
            ),
            now,
            determinedLineIds = setOf("northern", "victoria", "jubilee"),
        )
        val distances = mapOf("940GZZLUKSX" to 300.0, "940GZZLUEUS" to 150.0, "940GZZLUWLO" to 2000.0)
        val nearest = HomeLines.row(loaded, distances, tube(), emptySet(), now, networks = setOf("tube")).every.associate { it.leg.lineId to it.nearby }
        assertEquals(setOf("940GZZLUEUS"), nearest["northern"])
        assertEquals(setOf("940GZZLUKSX"), nearest["victoria"])
        // Beyond the walking reach, or a line with no stop near: nothing kept.
        assertEquals(emptySet<String>(), nearest["jubilee"])
        assertEquals(emptySet<String>(), nearest["central"])
        // The watched list has no position to measure from.
        assertTrue(HomeLines.row(loaded, emptyMap(), tube(), emptySet(), now, networks = setOf("tube")).every.all { it.nearby.isEmpty() })
    }

    @Test
    fun `a near station whose times aren't fetched still counts as a line's nearest, by its own data`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("940GZZLUKSX", "victoria" to "tube")), now, determinedLineIds = setOf("victoria"))
        // Euston, nearer, in the tier not fetched yet; Waterloo beyond the walking reach.
        val distances = mapOf("940GZZLUKSX" to 300.0, "940GZZLUEUS" to 150.0, "940GZZLUWLO" to 2000.0)
        val stops = mapOf("victoria" to "940GZZLUEUS", "northern" to "940GZZLUEUS", "jubilee" to "940GZZLUWLO")
        val nearest = HomeLines.row(loaded, distances, tube(), emptySet(), now, networks = setOf("tube"), nearestStops = stops).every.associate { it.leg.lineId to it.nearby }
        assertEquals(setOf("940GZZLUEUS"), nearest["victoria"])
        assertEquals(setOf("940GZZLUEUS"), nearest["northern"])
        assertEquals(emptySet<String>(), nearest["jubilee"])
    }

    @Test
    fun `a nearby stop's route with no departure is left out, unless the list shows its alert`() {
        val quiet = stop("near", "73" to "bus").copy(lines = listOf(LineRef("73", "73", "bus"), LineRef("n73", "N73", "bus"), LineRef("25", "25", "bus")))
        val suspended = LineStatus("25", 16, "Suspended")
        val loaded = DeparturesUiState.Loaded(listOf(quiet), now, lineStatuses = mapOf("25" to suspended), determinedLineIds = setOf("73", "n73", "25"))
        val ids = tubeRow(loaded, mapOf("near" to 50.0), tube(), emptySet(), now).every.map { it.leg.lineId }
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
        val row = tubeRow(loaded, mapOf("near" to 50.0, "far" to 900.0), tube(), emptySet(), now)
        assertTrue(row.unknown)
        assertEquals(listOf("38"), row.unknownLines.map { it.lineId })
        assertEquals("near", row.unknownStops)
        // Something it can't name (a departure with no line) still reads unknown, with nothing named.
        val blank = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"), disruptionUnknown = true)
        val unnamed = tubeRow(blank, mapOf("near" to 50.0), tube(), emptySet(), now)
        assertTrue(unnamed.unknown)
        assertEquals(emptyList<String>(), unnamed.unknownLines.map { it.lineId })
    }

    @Test
    fun `the chosen networks are always covered, the nearby lines whatever's chosen`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val asked = HomeLines.idsOf(setOf("overground"))
        val checked = HomeLines.Always(asked.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }, now)
        val ids = tubeRow(loaded, mapOf("near" to 50.0), checked, emptySet(), now, networks = setOf("overground")).watchedIds
        assertEquals(asked + "73", ids.toSet())
        // None chosen: the nearby lines alone.
        assertEquals(listOf("73"), tubeRow(loaded, mapOf("near" to 50.0), null, emptySet(), now, networks = emptySet()).watchedIds)
        // One just chosen that no check has asked about yet is being checked, not unchecked.
        val tubeOnly = HomeLines.Always(good, now, askedFor = HomeLines.TUBE_IDS)
        val justChosen = tubeRow(loaded, mapOf("near" to 50.0), tubeOnly, emptySet(), now, networks = setOf("tube", "dlr"))
        assertTrue(justChosen.every.single { it.leg.lineId == "dlr" }.checking)
        assertFalse(justChosen.unknown)
        // Nor is one with no check published yet, the list done: not yet asked, never unchecked.
        val unasked = tubeRow(loaded, mapOf("near" to 50.0), null, emptySet(), now, networks = setOf("dlr"))
        assertTrue(unasked.every.single { it.leg.lineId == "dlr" }.checking)
        assertFalse(unasked.unknown)
    }

    @Test
    fun `the watched list has no distances, so every stop counts`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("a", "38" to "bus")), now, determinedLineIds = setOf("38"))
        assertTrue(tubeRow(loaded, emptyMap(), tube(), emptySet(), now).every.any { it.leg.lineId == "38" })
    }

    @Test
    fun `a dismissed alert leaves the pills, still named on the page`() {
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73"),
        )
        val row = tubeRow(loaded, mapOf("near" to 50.0), tube(), setOf(DismissedAlert.ofLineStatus(severe)), now)
        assertEquals(emptyList<String>(), row.lines.map { it.lineId })
        assertTrue(row.every.single { it.leg.lineId == "73" }.dismissed)
        // Still a disruption, one the card doesn't count: it says "No watched disruptions", not "No line disruptions".
        assertTrue(row.linesDisrupted)
        assertFalse(tubeRow(loaded.copy(lineStatuses = emptyMap()), mapOf("near" to 50.0), tube(), emptySet(), now).linesDisrupted)
    }

    @Test
    fun `a dismissed alert is named as dismissed, never a good service, even with planned work left`() {
        val planned = app.stopdash.domain.PlannedAlert("Part closure", "Closed next weekend", java.time.LocalDate.parse("2026-10-10"))
        val diversion = LineStatus("73", 6, "Diversions", fullText = "Diverted via another road", planned = listOf(planned))
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to diversion), determinedLineIds = setOf("73"),
        )
        val row = tubeRow(loaded, mapOf("near" to 50.0), tube(), setOf(DismissedAlert.ofLineStatus(diversion)), now)
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
        val row = tubeRow(loaded, mapOf("near" to 50.0), tube(), dismissed, now)
        assertEquals(listOf("38"), row.lines.map { it.lineId })
        // Live disruption, then the unchecked 55, then the dismissed 73, then the good services.
        val order = row.every.map { it.leg.lineId }
        assertEquals(listOf("38", "55", "73"), order.take(3))
        assertTrue(row.every[2].dismissed)
        assertTrue(row.every.drop(3).all { !it.disrupted && !it.unknown })
        // One way still standing keeps it on the row.
        val oneWay = tubeRow(loaded, mapOf("near" to 50.0), tube(), setOf(DismissedAlert.ofLineStatus(eastbound)), now)
        assertTrue("73" in oneWay.lines.map { it.lineId })
    }

    @Test
    fun `a worse alert dismissed while a milder one stands is named beside it, and keys its map`() {
        // Severe delays one way, minor the other; the rider dismissed the severe, as on a trip (Codex, #606).
        val severe = LineStatus("73", 6, "Severe Delays", fullText = "Roadworks eastbound")
        val minor = LineStatus("73", 9, "Minor Delays", fullText = "Roadworks westbound")
        val lineWide = severe.copy(byDirection = mapOf("outbound" to severe, "inbound" to minor))
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to lineWide), determinedLineIds = setOf("73"),
        )
        val line = row(tubeRow(loaded, mapOf("near" to 50.0), tube(), setOf(DismissedAlert.ofLineStatus(severe)), now), "73")
        assertFalse(line.dismissed)
        assertEquals("Minor Delays", line.status?.description)
        assertEquals("Severe Delays", line.quieted?.description)
        assertEquals(LineMap.alertKey(line.status, line.quieted), line.mapKey)
        // Nothing dismissed: nothing named beside it.
        assertNull(row(tubeRow(loaded, mapOf("near" to 50.0), tube(), emptySet(), now), "73").quieted)
    }

    @Test
    fun `a line is never a good service on no current check`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now)
        // Nothing back for the bus, and the tube's check is too old to stand.
        val row = tubeRow(loaded, mapOf("near" to 50.0), tube(at = now.minus(Duration.ofMinutes(10))), emptySet(), now)
        assertTrue(row.unknown)
        assertEquals(HomeLines.TUBE_IDS + "73", row.unknownLines.mapTo(HashSet()) { it.lineId })
        assertTrue(row.every.none { it.status != null })
    }

    @Test
    fun `each always-covered line stands on its own check's age, an aged one never taking a current one down`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val good = { id: String -> LineStatus(id, LineStatus.GOOD_SERVICE, "Good Service") }
        // The DLR's verdict is current; the Overground's (since dropped from the choice) has aged.
        val overground = HomeLines.idsOf(setOf("overground"))
        val ids = overground + "dlr"
        val stamps = overground.associateWith { now.minus(Duration.ofMinutes(10)) } + ("dlr" to now.minus(Duration.ofMinutes(1)))
        val always = HomeLines.Always(ids.associateWith(good), stamps.values.min(), askedFor = ids, stamps = stamps)
        val dlr = row(tubeRow(loaded, mapOf("near" to 50.0), always, emptySet(), now, networks = setOf("dlr")), "dlr")
        assertFalse(dlr.unknown)
        assertFalse(dlr.checking)
    }

    @Test
    fun `back from the background, the tube's old check reads checking while the refresh runs, not couldn't check`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val old = tube(at = now.minus(Duration.ofMinutes(10)))
        val refreshing = tubeRow(loaded, mapOf("near" to 50.0), old, emptySet(), now, refreshing = true)
        assertTrue(row(refreshing, "victoria").checking)
        assertFalse(refreshing.unknown)
        // Once it's done without a newer check, it couldn't be checked.
        assertTrue(row(tubeRow(loaded, mapOf("near" to 50.0), old, emptySet(), now), "victoria").unknown)
        // A current check stands while a refresh runs.
        assertFalse(row(tubeRow(loaded, mapOf("near" to 50.0), tube(), emptySet(), now, refreshing = true), "victoria").checking)
    }

    private fun row(row: TripRow, id: String) = row.every.single { it.leg.lineId == id }

    // The lines under *Watched*, in the page's order, and under *Other*, which every rail line the rider doesn't
    // watch is (maintainer, 2026-10-17).
    private val TripRow.watchedIds: List<String> get() = every.filter { it.watched == true }.map { it.leg.lineId }
    private val TripRow.otherIds: List<String> get() = every.filter { it.watched != true }.map { it.leg.lineId }

    // The row as most of these read it: the tube always covered, unless a test chooses otherwise.
    private fun tubeRow(
        loaded: DeparturesUiState.Loaded?,
        distances: Map<String, Double>,
        always: HomeLines.Always?,
        dismissed: Set<DismissedAlert>,
        now: Instant,
        refreshing: Boolean = false,
        networks: Set<String> = setOf("tube"),
    ) = HomeLines.row(loaded, distances, always, dismissed, now, refreshing, networks)

    @Test
    fun `while the first load checks, its lines and the tube say checking`() {
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, statusPending = true, disruptionUnknown = true, pendingLineIds = setOf("73"),
        )
        val row = tubeRow(loaded, mapOf("near" to 50.0), always = null, emptySet(), now)
        assertTrue(row.checking)
        assertFalse(row.unknown)
        assertTrue(row.every.all { it.checking })
        // Before any list, everything is being checked.
        assertTrue(tubeRow(null, emptyMap(), null, emptySet(), now).every.all { it.checking })
    }

    @Test
    fun `a tube line the list shows goes by the list's own check`() {
        val victoria = LineStatus("victoria", 6, "Severe Delays")
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "victoria" to "tube")), now, lineStatuses = mapOf("victoria" to victoria), determinedLineIds = setOf("victoria"),
        )
        val row = tubeRow(loaded, mapOf("near" to 50.0), tube(), emptySet(), now)
        assertEquals(listOf("victoria"), row.lines.map { it.lineId })
        assertEquals(1, row.every.count { it.leg.lineId == "victoria" })
    }

    @Test
    fun `no network is covered until the rider chooses`() {
        assertEquals(emptySet<String>(), HomeLines.DEFAULT_NETWORKS)
    }

    @Test
    fun `a favorite's line is covered wherever its stop is, with no network chosen`() {
        // A journey from a far station on the Victoria line, and a starred bus at another far stop, both
        // fetched with the list (a journey's origin declares its line), nothing near.
        val victoria = LineStatus("victoria", 6, "Severe Delays")
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("origin", "victoria" to "tube"), stop("pole", "38" to "bus"), stop("other", "73" to "bus")),
            now,
            lineStatuses = mapOf("victoria" to victoria),
            determinedLineIds = setOf("victoria", "38", "73"),
        )
        val journey = app.stopdash.domain.FavoriteJourney(
            app.stopdash.domain.JourneyEnd("origin", "Origin"), app.stopdash.domain.JourneyEnd("end", "End"), "victoria", "Victoria", "tube",
        )
        val starred = setOf(app.stopdash.domain.StarredRow("pole", "38", "outbound"))
        val far = mapOf("origin" to 3000.0, "pole" to 2000.0, "other" to 2500.0)
        val row = HomeLines.row(loaded, far, null, emptySet(), now, starred = starred, journeyLines = setOf("victoria"), journeys = listOf(journey))
        // The favorites' lines, and every other line the list shows, checked by the list; never a whole network.
        assertEquals(setOf("victoria", "38", "73"), row.watchedIds.toSet())
        assertEquals(listOf("victoria"), row.lines.map { it.lineId })
        assertFalse(row.checking)
        assertFalse(row.unknown)
        // Before the list is in, the journey's line still shows, as checking.
        val cold = HomeLines.row(null, emptyMap(), null, emptySet(), now, journeyLines = setOf("victoria"), journeys = listOf(journey))
        assertEquals(listOf("victoria"), cold.watchedIds)
        assertTrue(row(cold, "victoria").checking)
    }

    @Test
    fun `a journey no fetched stop vouches for is judged by the always-covered check, a star only in the list`() {
        // A far journey not yet revealed (so not fetched): checking until its check is in, then judged by it,
        // never "Unknown". A starred row whose stop has left the list stays off the row: a star stores no line
        // name or mode, and ranks only within the list (Codex, #640).
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val starred = setOf(app.stopdash.domain.StarredRow("gone", "38", "outbound"))
        val journey = app.stopdash.domain.FavoriteJourney(
            app.stopdash.domain.JourneyEnd("far", "Far"), app.stopdash.domain.JourneyEnd("end", "End"), "elizabeth", "Elizabeth line", "elizabeth-line",
        )
        val near = mapOf("near" to 50.0)
        val waiting = HomeLines.row(loaded, near, null, emptySet(), now, starred = starred, journeyLines = setOf("elizabeth"), journeys = listOf(journey))
        assertEquals(setOf("73", "elizabeth"), waiting.watchedIds.toSet())
        assertTrue(row(waiting, "elizabeth").checking)
        assertFalse(waiting.unknown)
        val part = LineStatus("elizabeth", 3, "Part Suspended")
        val judged = HomeLines.row(loaded, near, HomeLines.Always(mapOf("elizabeth" to part), now), emptySet(), now, starred = starred, journeyLines = setOf("elizabeth"), journeys = listOf(journey))
        assertEquals(listOf("elizabeth"), judged.lines.map { it.lineId })
        assertFalse(judged.checking)
        assertFalse(judged.unknown)
    }

    @Test
    fun `a favorite ride's line is watched wherever the rider is, checking until asked, then the rider's own`() {
        // The rider at a bus stop; a favorite ride on the Northern from a station far away, whose line is watched
        // though no stop near the rider serves it. The watched lines are those near the rider, their rides', the
        // trip's on the way and the ones they chose, no longer a favorite place's (maintainer, 2026-10-17).
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus"), stop("far", "38" to "bus")), now,
            lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73"), disruptionUnknown = true,
        )
        val distances = mapOf("near" to 50.0, "far" to 900.0)
        val ride = app.stopdash.domain.FavoriteJourney(
            app.stopdash.domain.JourneyEnd("a", "A"), app.stopdash.domain.JourneyEnd("b", "B"), "northern", "Northern", "tube",
        )
        val waiting = HomeLines.row(loaded, distances, null, emptySet(), now, journeyLines = setOf("northern"), journeys = listOf(ride))
        assertTrue(row(waiting, "northern").checking)
        assertEquals(true, row(waiting, "northern").watched)
        // Once asked: as bad as the near bus, the ride's line ranks with it as the rider's own, ahead of a chosen
        // line far away just as bad.
        val northern = LineStatus("northern", 6, "Severe Delays")
        val central = LineStatus("central", 6, "Severe Delays")
        val judged = HomeLines.row(
            loaded, distances, HomeLines.Always(mapOf("northern" to northern, "central" to central), now), emptySet(), now,
            networks = setOf("central"), journeyLines = setOf("northern"), journeys = listOf(ride),
            tripLines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        assertEquals(listOf("73", "northern", "central"), judged.lines.map { it.lineId })
        assertEquals(setOf("73", "38", "northern", "victoria", "central"), judged.everyWatched.orEmpty().toSet())
        // A farther stop's bus the list couldn't check is on the screen, so watched, and named unchecked.
        assertEquals(true, row(judged, "38").watched)
        assertTrue(row(judged, "38").unknown)
        assertFalse(row(judged, "northern").checking)
    }

    @Test
    fun `the page lists the watched lines first, the pills still worst first`() {
        // A minor delay on the bus near the rider, a severe one on a favorite ride's line, and a suspension far
        // away on a line the rider chose to watch; a farther stop's bus the list couldn't check is on the screen,
        // so watched too, and the lines not watched are every other rail line (maintainer, 2026-10-17).
        val minor = LineStatus("73", 9, "Minor Delays")
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus"), stop("far", "38" to "bus")), now,
            lineStatuses = mapOf("73" to minor), determinedLineIds = setOf("73"), disruptionUnknown = true,
        )
        val ride = app.stopdash.domain.FavoriteJourney(
            app.stopdash.domain.JourneyEnd("a", "A"), app.stopdash.domain.JourneyEnd("b", "B"), "northern", "Northern", "tube",
        )
        val statuses = mapOf("northern" to LineStatus("northern", 6, "Severe Delays"), "central" to LineStatus("central", 2, "Suspended"))
        val judged = HomeLines.row(
            loaded, mapOf("near" to 50.0, "far" to 900.0), HomeLines.Always(statuses, now), emptySet(), now,
            networks = setOf("central"), journeyLines = setOf("northern"), journeys = listOf(ride),
        )
        // The pills as before: the worst first, wherever it is.
        assertEquals(listOf("central", "northern", "73"), judged.lines.map { it.lineId })
        // The page: the watched lines first, the chosen one among them, in that order, then the rest of the rail
        // lines, as Settings' chips go, none asked about yet.
        assertEquals(listOf("central", "northern", "73", "38"), judged.watchedIds)
        assertEquals(listOf("central", "northern", "73", "38"), judged.everyWatched)
        assertEquals(HomeLines.RAIL.map { it.id } - setOf("central", "northern"), judged.otherIds)
        assertEquals(judged.watchedIds + judged.otherIds, judged.every.map { it.leg.lineId })
    }

    @Test
    fun `the other rail lines are judged once asked, worst first then as the chips go, never on the card`() {
        // The page asked about every rail line (maintainer, 2026-10-17): the Jubilee's suspended and the DLR's
        // delayed under Other, the rest good; none of them the rider's, so none is a pill.
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val statuses = HomeLines.RAIL_IDS.associateWith { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") } +
            ("jubilee" to LineStatus("jubilee", 16, "Suspended")) + ("dlr" to LineStatus("dlr", 9, "Minor Delays"))
        val asked = HomeLines.row(loaded, mapOf("near" to 50.0), HomeLines.Always(statuses, now), emptySet(), now)
        assertEquals(listOf("73"), asked.watchedIds)
        assertEquals(listOf("jubilee", "dlr") + (HomeLines.RAIL.map { it.id } - setOf("jubilee", "dlr")), asked.otherIds)
        assertTrue(asked.every.none { it.checking || it.unknown })
        assertTrue(asked.lines.isEmpty())
        assertFalse(asked.checking)
        assertFalse(asked.unknown)
        // The card says there's something to see (its ⓘ), not that no line is disrupted.
        assertTrue(asked.linesDisrupted)
        // Not answered for: couldn't be checked, said on the page, never on the card.
        val missed = HomeLines.row(loaded, mapOf("near" to 50.0), HomeLines.Always(emptyMap(), now, askedFor = HomeLines.RAIL_IDS), emptySet(), now)
        assertTrue(row(missed, "jubilee").unknown)
        assertTrue(missed.linesUnknown)
        assertFalse(missed.unknown)
        assertTrue(missed.unknownLines.isEmpty())
        // Being asked again (a refresh under way): checking, and still not the card's "Checking…".
        val again = HomeLines.row(loaded, mapOf("near" to 50.0), HomeLines.Always(emptyMap(), now, askedFor = HomeLines.RAIL_IDS), emptySet(), now, refreshing = true)
        assertTrue(row(again, "jubilee").checking)
        assertFalse(again.checking)
    }

    @Test
    fun `a farther card's line is watched, checking until the screen's check asks about it, then a pill`() {
        // The Elizabeth line on a farther station's card, its stop not fetched (maintainer, 2026-10-17).
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val screen = HomeLines.ScreenLines(listOf(LineRef("73", "73", "bus"), LineRef("elizabeth", "Elizabeth line", "elizabeth-line")), farIds = setOf("elizabeth"))
        val waiting = HomeLines.row(loaded, mapOf("near" to 50.0), null, emptySet(), now, screen = screen)
        assertEquals(setOf("73", "elizabeth"), waiting.watchedIds.toSet())
        assertTrue(row(waiting, "elizabeth").checking)
        assertTrue(waiting.checking)
        val part = LineStatus("elizabeth", 3, "Part Suspended")
        val judged = HomeLines.row(loaded, mapOf("near" to 50.0), HomeLines.Always(mapOf("elizabeth" to part), now), emptySet(), now, screen = screen)
        assertEquals(listOf("elizabeth"), judged.lines.map { it.lineId })
        assertFalse(judged.checking)
        // A line the screen doesn't show (a hidden mode's) isn't watched, though the list fetched it.
        val hidden = HomeLines.row(
            DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus"), stop("rail", "thameslink" to "national-rail")), now, determinedLineIds = setOf("73", "thameslink")),
            mapOf("near" to 50.0, "rail" to 80.0), null, emptySet(), now, screen = HomeLines.ScreenLines(listOf(LineRef("73", "73", "bus"))),
        )
        assertEquals(listOf("73"), hidden.watchedIds)
        assertTrue(hidden.every.none { it.leg.lineId == "thameslink" })
        // Nor does the card say it couldn't check, when that hidden line is all the list couldn't (Codex, #930).
        val stops = listOf(stop("near", "73" to "bus"), stop("rail", "thameslink" to "national-rail"))
        val unchecked = DeparturesUiState.Loaded(stops, now, determinedLineIds = setOf("73"), disruptionUnknown = true)
        val distances = mapOf("near" to 50.0, "rail" to 80.0)
        val shownBus = HomeLines.ScreenLines(listOf(LineRef("73", "73", "bus")))
        assertFalse(HomeLines.row(unchecked, distances, null, emptySet(), now, screen = shownBus).unknown)
        // A drawn row with no line to check, or a stop whose closure check failed, still does.
        val lineless = HomeLines.ScreenLines(listOf(LineRef("73", "73", "bus")), lineless = true)
        assertTrue(HomeLines.row(unchecked, distances, null, emptySet(), now, screen = lineless).unknown)
        val closure = DeparturesUiState.Loaded(stops, now, determinedLineIds = setOf("73", "thameslink"), disruptionUnknown = true, stopsDisruptionUnknown = setOf("near"))
        assertTrue(HomeLines.row(closure, distances, null, emptySet(), now, screen = shownBus).unknown)
    }

    @Test
    fun `with the lines near the rider turned off, only their own and the chosen lines are watched`() {
        // A near bus stop and a tube station on the screen, and a farther card's Elizabeth line; a starred bus at the
        // stop, a favorite ride on the Northern far away, the trip's Victoria and a chosen Central (maintainer,
        // 2026-10-21: a rider may want only their favorite rides watched).
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus", "38" to "bus"), stop("station", "jubilee" to "tube")), now,
            lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73", "38", "jubilee"),
        )
        val distances = mapOf("near" to 50.0, "station" to 200.0)
        val screen = HomeLines.ScreenLines(
            listOf(LineRef("73", "73", "bus"), LineRef("38", "38", "bus"), LineRef("jubilee", "Jubilee", "tube"), LineRef("elizabeth", "Elizabeth line", "elizabeth-line")),
            farIds = setOf("elizabeth"),
        )
        val starred = setOf(app.stopdash.domain.StarredRow("near", "38", "outbound"))
        val ride = app.stopdash.domain.FavoriteJourney(
            app.stopdash.domain.JourneyEnd("a", "A"), app.stopdash.domain.JourneyEnd("b", "B"), "northern", "Northern", "tube",
        )
        fun watched(nearby: Boolean, loaded: DeparturesUiState.Loaded) = HomeLines.row(
            loaded, distances, null, emptySet(), now, networks = setOf("central"), starred = starred,
            journeyLines = setOf("northern"), journeys = listOf(ride), tripLines = listOf(LineRef("victoria", "Victoria", "tube")),
            screen = screen, watchNearby = nearby,
        )
        // On, the default: every line the screen shows too, the near bus's delays a pill.
        val on = watched(true, loaded)
        assertEquals(setOf("central", "73", "38", "jubilee", "elizabeth", "northern", "victoria"), on.watchedIds.toSet())
        assertEquals(listOf("73"), on.lines.map { it.lineId })
        // Off: the star's bus, the ride's line, the trip's and the chosen one, the near bus's delays left to the list.
        val off = watched(false, loaded)
        assertEquals(setOf("central", "38", "northern", "victoria"), off.watchedIds.toSet())
        assertTrue(off.lines.isEmpty())
        // A rail line near the rider goes under Other, as any other the rider doesn't watch does; a bus not at all.
        assertTrue(off.otherIds.containsAll(listOf("jubilee", "elizabeth")))
        assertTrue(off.every.none { it.leg.lineId == "73" })
        // What the list couldn't check is the list's banner's to say once the row doesn't watch it, though a line of
        // the rider's own that went unchecked still reads unknown on the row.
        val closure = DeparturesUiState.Loaded(
            loaded.stops, now, determinedLineIds = setOf("73", "38", "jubilee"), disruptionUnknown = true, stopsDisruptionUnknown = setOf("station"),
        )
        assertTrue(watched(true, closure).unknown)
        assertFalse(watched(false, closure).unknown)
        val starUnchecked = DeparturesUiState.Loaded(loaded.stops, now, determinedLineIds = setOf("73", "jubilee"), disruptionUnknown = true)
        assertTrue(row(watched(false, starUnchecked), "38").unknown)
        assertTrue(watched(false, starUnchecked).unknown)
    }

    @Test
    fun `what the screen shows is unchecked only for a drawn line, a stop's closure check or a row with no line`() {
        // The list's flag is up for a line the stop only declares, TfL giving it no status (a Eurostar at a big
        // station, maintainer, 2026-10-21): nothing on the screen shows it, so nothing it shows is unchecked.
        val stops = listOf(stop("near", "73" to "bus"))
        val shown = HomeLines.ScreenLines(listOf(LineRef("73", "73", "bus")))
        val declared = DeparturesUiState.Loaded(stops, now, determinedLineIds = setOf("73"), disruptionUnknown = true)
        assertFalse(shown.uncheckedIn(declared))
        // A drawn line with no verdict is.
        assertTrue(shown.uncheckedIn(DeparturesUiState.Loaded(stops, now, determinedLineIds = emptySet(), disruptionUnknown = true)))
        // A farther card's line isn't the list's to check: the row's own check asks about it.
        val far = HomeLines.ScreenLines(listOf(LineRef("73", "73", "bus"), LineRef("elizabeth", "Elizabeth line", "elizabeth-line")), farIds = setOf("elizabeth"))
        assertFalse(far.uncheckedIn(declared))
        // A stop whose closure check failed is, as is a drawn row with no line.
        assertTrue(shown.uncheckedIn(DeparturesUiState.Loaded(stops, now, determinedLineIds = setOf("73"), disruptionUnknown = true, stopsDisruptionUnknown = setOf("near"))))
        assertTrue(HomeLines.ScreenLines(listOf(LineRef("73", "73", "bus")), lineless = true).uncheckedIn(declared))
        // Never with the list's flag down, nor while its first checks are still out.
        assertFalse(shown.uncheckedIn(DeparturesUiState.Loaded(stops, now, determinedLineIds = emptySet())))
        assertFalse(shown.uncheckedIn(DeparturesUiState.Loaded(stops, now, determinedLineIds = emptySet(), disruptionUnknown = true, statusPending = true)))
    }

    @Test
    fun `the screen's lines are its rows', its journey cards' and its farther cards', each card's line by the nearest card`() {
        fun departureRow(stopId: String, lineId: String, mode: String) = app.stopdash.domain.DepartureRow(
            stopId = stopId, stopName = stopId, lineId = lineId, lineName = lineId.uppercase(), direction = "outbound",
            directionKey = "outbound", destination = "Somewhere", mode = mode, upcoming = emptyList(), fetchedAt = now,
        )
        fun card(key: String, meters: Double, vararg lines: LineRef) =
            FartherCard(app.stopdash.domain.CollapsedPlaces.Place(key, key, key, meters, lines.toList()))
        val victoria = LineRef("victoria", "Victoria", "tube")
        val screen = HomeLines.ScreenLines.of(
            rows = listOf(departureRow("a", "73", "bus"), departureRow("b", "73", "bus")),
            cardRows = listOf(departureRow("c", "northern", "tube")),
            farther = listOf(card("far", 2000.0, victoria), card("near", 900.0, victoria, LineRef("dlr", "DLR", "dlr"))),
        )
        assertEquals(listOf("73", "northern", "victoria", "dlr"), screen.lines.map { it.id })
        assertFalse(screen.lineless)
        assertTrue(HomeLines.ScreenLines.of(listOf(departureRow("a", "", "bus")), emptyList(), emptyList()).lineless)
        // A stop's own notice has no line and needs none: never a row with no line, which the list's flag never
        // counts either (maintainer, 2026-10-21: a station's lift or toilets notice kept the banner up).
        val notice = departureRow("a", "", "").copy(stopDisruption = "Lift out of service")
        assertFalse(HomeLines.ScreenLines.of(listOf(notice, departureRow("a", "73", "bus")), emptyList(), emptyList()).lineless)
        // Only the cards' own are asked about beside the list's: the rows' lines are the list's to check.
        assertEquals(setOf("victoria", "dlr"), screen.farIds)
        // An opened card shows its departures as rows, not its lines (Codex, #930): only the rows' lines count.
        // Opened with nothing listed, it still stands as its card, its lines with it.
        val station = app.stopdash.domain.StopLocation("940GZZLUKSX", "King's Cross", 0.0, 0.0, listOf(victoria, LineRef("northern", "Northern", "tube")))
        val opened = FartherCard(
            app.stopdash.domain.CollapsedPlaces.Place("station:kx", "kx", "King's Cross", 1200.0, listOf(victoria, LineRef("northern", "Northern", "tube"))),
            FartherLoad.Open(listOf(station), mapOf(station.id to 1200.0)),
        )
        val withRows = HomeLines.ScreenLines.of(listOf(departureRow("940GZZLUKSX", "victoria", "tube")), emptyList(), listOf(opened))
        assertEquals(listOf("victoria"), withRows.lines.map { it.id })
        assertTrue(withRows.farIds.isEmpty())
        val nothingListed = HomeLines.ScreenLines.of(emptyList(), emptyList(), listOf(opened))
        assertEquals(listOf("victoria", "northern"), nothingListed.lines.map { it.id })
        assertEquals(mapOf("victoria" to 900.0, "dlr" to 900.0), screen.meters)
    }

    @Test
    fun `each picker chip's colors are its line's pill's, in the picker's order`() {
        // Worked out with which chips are on, off the main thread (Codex, #923): a tube line solid, a named
        // Overground line hollow, as its pill is.
        val surface = Color(0xFFFFFBFE)
        val pills = HomeLines.pickerPills(surface)
        assertEquals(HomeLines.PICKER.map { it.second.size }, pills.map { it.size })
        HomeLines.PICKER.forEachIndexed { g, (_, lines) ->
            lines.forEachIndexed { i, line -> assertEquals(pillColors(line.name, line.id, line.mode, surface), pills[g][i]) }
        }
        val victoria = HomeLines.PICKER[0].second.indexOfFirst { it.id == "victoria" }
        assertTrue(pills[0][victoria] is PillColors.Solid)
        assertTrue(pills[1].all { it is PillColors.Hollow })
    }

    @Test
    fun `the trip on the way's lines are covered wherever it runs, checking until asked, then the rider's own`() {
        // The rider at a bus stop, on the way by two lines no stop near them serves (maintainer, 2026-10-17).
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73"))
        val near = mapOf("near" to 50.0)
        val trip = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("elizabeth", "Elizabeth line", "elizabeth-line"))
        val chosen = setOf("northern")
        val waiting = HomeLines.row(loaded, near, null, emptySet(), now, networks = chosen, tripLines = trip)
        assertEquals(setOf("73", "victoria", "elizabeth", "northern"), waiting.watchedIds.toSet())
        assertTrue(row(waiting, "victoria").checking)
        assertFalse(waiting.unknown)
        // Once asked: as bad as the near bus, the trip's lines rank with it as the rider's own, ahead of a
        // chosen line far away just as bad.
        val statuses = listOf("victoria", "elizabeth", "northern").associateWith { LineStatus(it, 6, "Severe Delays") }
        val judged = HomeLines.row(loaded, near, HomeLines.Always(statuses, now), emptySet(), now, networks = chosen, tripLines = trip)
        assertEquals(listOf("73", "elizabeth", "victoria", "northern"), judged.lines.map { it.lineId })
        assertEquals("Elizabeth line", row(judged, "elizabeth").leg.lineName)
        assertFalse(judged.checking)
        // With no trip on the way they're not covered at all.
        val none = HomeLines.row(loaded, near, HomeLines.Always(statuses, now), emptySet(), now, networks = chosen)
        assertEquals(listOf("73", "northern"), none.lines.map { it.lineId })
    }

    @Test
    fun `the trip on the way's lines are walked on the worker, and no trip covers none`() {
        // The screen collects this; the walk over the trip's legs runs on the worker (AGENTS.md *Main thread*).
        val readOn = ThreadRecorder()
        val leg = app.stopdash.domain.TripLeg("tube", "victoria", "Victoria", "a", "A", "b", "B", now, now.plusSeconds(600))
        val legs = object : AbstractList<app.stopdash.domain.TripLeg>() {
            override val size get() = 1
            override fun get(index: Int) = leg.also { readOn.note() }
        }
        val trip = app.stopdash.domain.ActiveTrip(app.stopdash.domain.TripRoute(legs), "B", startedAt = now)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }
        try {
            val lines = kotlinx.coroutines.runBlocking {
                val read = flowOf(ActiveTripTracker.KeptTrip.READ)
                HomeLines.tripLines(flowOf(trip), flowOf(null), read, executor.asCoroutineDispatcher()).toList() +
                    HomeLines.tripLines(flowOf(null), flowOf(null), read, executor.asCoroutineDispatcher()).toList()
            }
            assertEquals(listOf(HomeLines.TripLines(listOf(LineRef("victoria", "Victoria", "tube"))), HomeLines.TripLines.NONE), lines)
            assertTrue(readOn.threads().isNotEmpty())
            assertTrue(readOn.threads().all { it.startsWith("worker") })
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `a trip that has arrived covers no line, though it's kept until it can be forgotten`() {
        // Arrived, the tracker keeps the trip while forgetting it on the device fails, to try again (Codex, #920).
        val leg = app.stopdash.domain.TripLeg("tube", "victoria", "Victoria", "a", "A", "b", "B", now, now.plusSeconds(600))
        val trip = app.stopdash.domain.ActiveTrip(app.stopdash.domain.TripRoute(listOf(leg)), "B", startedAt = now)
        fun linesWhen(progress: app.stopdash.domain.TripProgress?) = kotlinx.coroutines.runBlocking {
            HomeLines.tripLines(flowOf(trip), flowOf(progress), flowOf(ActiveTripTracker.KeptTrip.READ), kotlinx.coroutines.Dispatchers.Unconfined)
                .toList().single()
        }
        assertEquals(listOf("victoria"), linesWhen(null)?.lines?.map { it.id })
        assertEquals(HomeLines.TripLines.NONE, linesWhen(app.stopdash.domain.TripProgress.Arrived))
    }

    @Test
    fun `the trip on the way isn't taken for none before it's read, and a trip that can't be read is unknown`() {
        // On a restart the tracker's trip reads null until the kept trip is read (Codex, #920).
        fun linesWhen(kept: ActiveTripTracker.KeptTrip) = kotlinx.coroutines.runBlocking {
            HomeLines.tripLines(flowOf(null), flowOf(null), flowOf(kept), kotlinx.coroutines.Dispatchers.Unconfined).toList().single()
        }
        assertNull(linesWhen(ActiveTripTracker.KeptTrip.UNREAD))
        assertEquals(HomeLines.TripLines(emptyList(), unread = true), linesWhen(ActiveTripTracker.KeptTrip.UNREADABLE))
        assertEquals(HomeLines.TripLines.NONE, linesWhen(ActiveTripTracker.KeptTrip.READ))
        // The row says it couldn't check, never a clean none, while the trip can't be read.
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to LineStatus("73", LineStatus.GOOD_SERVICE, "Good Service")), determinedLineIds = setOf("73"))
        assertFalse(HomeLines.row(loaded, mapOf("near" to 50.0), null, emptySet(), now).unknown)
        assertTrue(HomeLines.row(loaded, mapOf("near" to 50.0), null, emptySet(), now, tripUnread = true).unknown)
    }

    @Test
    fun `a favorite journey's line, nearest first, leads chosen lines as bad`() {
        // The rider watches King's Cross's tube lines by choice; all closed overnight. Their favorite journey rides
        // the Northern from a listed station 1 km off: it leads, not Circle by name (maintainer, 2026-10-09).
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus"), stop("far", "northern" to "tube")), now, determinedLineIds = setOf("73"))
        val distances = mapOf("near" to 50.0, "far" to 1000.0)
        val chosen = setOf("circle", "hammersmith-city", "metropolitan", "northern")
        val closed = chosen.associateWith { LineStatus(it, 20, "Service Closed") }
        val judged = HomeLines.row(loaded, distances, HomeLines.Always(closed, now), emptySet(), now, networks = chosen, journeyLines = setOf("northern"))
        assertEquals(listOf("northern", "circle", "hammersmith-city", "metropolitan"), judged.lines.map { it.lineId })
        // With no journey, the chosen lines rank by their nearest listed stop: the Northern's still first.
        val nearest = HomeLines.row(loaded, distances, HomeLines.Always(closed, now), emptySet(), now, networks = chosen)
        assertEquals(listOf("northern", "circle", "hammersmith-city", "metropolitan"), nearest.lines.map { it.lineId })
        // A nearer stop whose times aren't fetched counts too, however far (Codex, #710): the Circle's, 900 m off
        // on a farther card, puts it first; the Metropolitan's at 1.2 km, after the Northern.
        val unfetched = HomeLines.row(
            loaded, distances, HomeLines.Always(closed, now), emptySet(), now,
            networks = chosen, lineMeters = mapOf("circle" to 900.0, "metropolitan" to 1200.0),
        )
        assertEquals(listOf("northern", "circle", "metropolitan", "hammersmith-city"), unfetched.lines.map { it.lineId })
        // On the screen, a card's line is the rider's own too (maintainer, 2026-10-17), by the card's distance: the
        // Circle's at 900 m goes first, then the Northern's listed stop, then the Metropolitan's card.
        val circle = LineRef("circle", "Circle", "tube")
        val metropolitan = LineRef("metropolitan", "Metropolitan", "tube")
        val screen = HomeLines.ScreenLines(
            listOf(LineRef("73", "73", "bus"), LineRef("northern", "Northern", "tube"), circle, metropolitan),
            meters = mapOf("circle" to 900.0, "metropolitan" to 1200.0),
        )
        val onScreen = HomeLines.row(loaded, distances, HomeLines.Always(closed, now), emptySet(), now, networks = chosen, screen = screen)
        assertEquals(listOf("circle", "northern", "metropolitan", "hammersmith-city"), onScreen.lines.map { it.lineId })
    }

    @Test
    fun `a star counts at its own stop, not at another fetched stop the line serves`() {
        // The 38 starred at a stop gone from the list; another far stop, fetched, also serves it, though the screen
        // doesn't show it there (a hidden mode, say): that stop doesn't stand in for the star (Codex, #640).
        // Starred at the fetched stop, it does.
        val loaded = DeparturesUiState.Loaded(listOf(stop("other", "38" to "bus")), now, determinedLineIds = setOf("38"))
        val far = mapOf("other" to 2000.0)
        val none = HomeLines.ScreenLines(emptyList())
        val elsewhere = HomeLines.row(loaded, far, null, emptySet(), now, starred = setOf(app.stopdash.domain.StarredRow("gone", "38", "outbound")), screen = none)
        assertTrue(elsewhere.every.none { it.leg.lineId == "38" })
        val here = HomeLines.row(loaded, far, null, emptySet(), now, starred = setOf(app.stopdash.domain.StarredRow("other", "38", "outbound")), screen = none)
        assertEquals(listOf("38"), here.watchedIds)
    }

    @Test
    fun `the journeys' lines are walked on the worker, never the collector's thread`() {
        // AGENTS.md *Main thread*: the screen collects this from a LaunchedEffect; the walk over every saved
        // journey runs on the worker (Codex, #640). The list notes the thread each journey is read on.
        val journey = app.stopdash.domain.FavoriteJourney(
            app.stopdash.domain.JourneyEnd("a", "A"), app.stopdash.domain.JourneyEnd("b", "B"), "elizabeth", "Elizabeth line", "elizabeth-line",
        )
        val readOn = ThreadRecorder()
        val journeys = object : AbstractList<app.stopdash.domain.FavoriteJourney>() {
            override val size get() = 2
            override fun get(index: Int) = journey.also { readOn.note() }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }
        try {
            val ids = kotlinx.coroutines.runBlocking {
                HomeLines.journeyLineIds(flowOf(journeys, null), executor.asCoroutineDispatcher()).toList()
            }
            assertEquals(listOf(setOf("elizabeth"), emptySet()), ids)
            assertTrue(readOn.threads().isNotEmpty())
            assertTrue(readOn.threads().all { it.startsWith("worker") })
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `the rider's stops are walked on the worker, and an unreadable set counts as none`() {
        // A trip's lines page collects this in composition; the walk over the starred set runs on the worker.
        val readOn = ThreadRecorder()
        val row = app.stopdash.domain.StarredRow("940GZZLUKSX", "victoria", "southbound")
        val starred = object : AbstractSet<app.stopdash.domain.StarredRow>() {
            override val size get() = 1
            override fun iterator(): Iterator<app.stopdash.domain.StarredRow> = listOf(row).iterator().also { readOn.note() }
        }
        val journey = app.stopdash.domain.FavoriteJourney(
            app.stopdash.domain.JourneyEnd("940GZZLUEUS", "Euston"), app.stopdash.domain.JourneyEnd("940GZZLUWLO", "Waterloo"), "northern",
        )
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }
        try {
            fun stopsOf(set: app.stopdash.domain.StarredRowSet) = kotlinx.coroutines.runBlocking {
                HomeLines.riderStops(flowOf(set), flowOf(listOf(journey)), executor.asCoroutineDispatcher()).toList()
            }
            assertEquals(listOf(setOf("940GZZLUKSX", "940GZZLUEUS", "940GZZLUWLO")), stopsOf(app.stopdash.domain.StarredRowSet.Loaded(starred)))
            assertEquals(listOf(setOf("940GZZLUEUS", "940GZZLUWLO")), stopsOf(app.stopdash.domain.StarredRowSet.Unavailable))
            assertTrue(readOn.threads().isNotEmpty())
            assertTrue(readOn.threads().all { it.startsWith("worker") })
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `as bad as each other, lines go by name last, a route by its number`() {
        // The 134 and the 43 near the rider, and the Bakerloo and Central lines, all closed for the night: the
        // rider's own first, each group by name, the 43 ahead of the 134 (maintainer, 2026-10-07).
        val closed = { id: String -> LineStatus(id, 20, "Service Closed") }
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "134" to "bus", "43" to "bus")), now,
            lineStatuses = mapOf("134" to closed("134"), "43" to closed("43")), determinedLineIds = setOf("134", "43"),
        )
        val always = HomeLines.Always(HomeLines.TUBE_IDS.associateWith { if (it == "central" || it == "bakerloo") closed(it) else LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }, now)
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), always, emptySet(), now, networks = setOf("tube"))
        assertEquals(listOf("43", "134", "bakerloo", "central"), row.lines.map { it.lineId })
        assertTrue(HomeLines.compareNatural("43", "134") < 0)
        assertTrue(HomeLines.compareNatural("N29", "N5") > 0)
        assertTrue(HomeLines.compareNatural("bakerloo", "Central") < 0)
        assertTrue(HomeLines.compareNatural("007", "7") != 0)
    }

    @Test
    fun `a line picked on its own is covered, with whole networks, in the row's order`() {
        assertEquals(listOf("victoria", "liberty", "dlr"), HomeLines.linesOf(setOf("dlr", "liberty", "victoria")).map { it.id })
        // A network's key covers every one of its lines; a line of it picked as well isn't listed twice.
        val ids = HomeLines.linesOf(setOf("tube", "victoria", "elizabeth")).map { it.id }
        assertEquals(HomeLines.TUBE.map { it.id } + "elizabeth", ids)
        // An id no network has is left out.
        assertEquals(emptyList<LineRef>(), HomeLines.linesOf(setOf("73")))
    }

    @Test
    fun `the summary page's chips pick lines one by one, grouped, none a whole network`() {
        // Under the near-me list's mode groups, the DLR and the Elizabeth line with the Tube, every
        // line once.
        assertEquals(listOf("tube", "overground", "tram"), HomeLines.PICKER.map { it.first.key })
        assertEquals(listOf("dlr", "elizabeth"), HomeLines.PICKER[0].second.takeLast(2).map { it.id })
        assertEquals(HomeLines.Network.OVERGROUND.lines, HomeLines.PICKER[1].second)
        assertEquals(HomeLines.Network.entries.flatMap { it.lines }.toSet(), HomeLines.PICKER.flatMap { it.second }.toSet())
        assertEquals(HomeLines.PICKER.sumOf { it.second.size }, HomeLines.PICKER.flatMap { it.second }.toSet().size)
        fun shown(chosen: Set<String>, id: String): Boolean {
            val covered = HomeLines.covered(chosen)
            HomeLines.PICKER.forEachIndexed { g, (_, lines) -> lines.forEachIndexed { i, line -> if (line.id == id) return covered[g][i] } }
            error("no chip for $id")
        }
        val tube = HomeLines.TUBE.map { it.id }
        // Nothing chosen: every chip off; a line picks itself, a single-line network's too.
        assertTrue(HomeLines.covered(emptySet()).flatten().none { it })
        assertEquals(setOf("victoria"), HomeLines.toggle(emptySet(), "victoria"))
        assertEquals(setOf("dlr"), HomeLines.toggle(emptySet(), "dlr"))
        // A line picked: on, its neighbors not; off again on a tap.
        assertTrue(shown(setOf("victoria"), "victoria"))
        assertFalse(shown(setOf("victoria"), "central"))
        assertEquals(emptySet<String>(), HomeLines.toggle(setOf("victoria"), "victoria"))
        // Every line picked one by one stays that way: no chip folds them into the network.
        assertEquals(tube.toSet(), HomeLines.toggle((tube - "victoria").toSet(), "victoria"))
        // A network an older build chose: every line on; one turned off leaves the others, one by one.
        assertTrue(shown(setOf("tube"), "central"))
        assertTrue(shown(setOf("dlr"), "dlr"))
        assertEquals((tube - "central").toSet(), HomeLines.toggle(setOf("tube"), "central"))
        // The network and one of its lines both held: that line still turns off (Codex, #642).
        assertEquals((tube - "central").toSet(), HomeLines.toggle(setOf("tube", "central"), "central"))
        assertEquals(emptySet<String>(), HomeLines.toggle(setOf("dlr"), "dlr"))
        // Another network's choice is left alone throughout; an id no network has changes nothing.
        assertEquals(setOf("dlr", "victoria"), HomeLines.toggle(setOf("dlr"), "victoria"))
        assertEquals(setOf("dlr"), HomeLines.toggle(setOf("dlr"), "73"))
    }

    @Test
    fun `a line's tap is counted as that one line, even under an older build's network`() {
        fun counted(before: Set<String>, lineId: String) = HomeLines.lineChanges(before, HomeLines.toggle(before, lineId)).map { it.params["setting_value"] }
        assertEquals(listOf("added"), counted(emptySet(), "victoria"))
        assertEquals(listOf("removed"), counted(setOf("victoria"), "victoria"))
        // The tube's key turned into its other lines one by one: one line off, not ten on.
        assertEquals(listOf("removed"), counted(setOf("tube"), "central"))
        assertEquals(listOf("removed"), counted(setOf("dlr"), "dlr"))
        // A key or id no network has isn't a line.
        assertEquals(emptyList<String>(), HomeLines.lineChanges(setOf("tram"), setOf("tram", "73")).map { it.params["setting_value"] })
    }

    @Test
    fun `the tube is listed by name`() {
        assertEquals(LineRef("hammersmith-city", "Hammersmith & City", "tube"), HomeLines.TUBE.single { it.id == "hammersmith-city" })
    }
}
