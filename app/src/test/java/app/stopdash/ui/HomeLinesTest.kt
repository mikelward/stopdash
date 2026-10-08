package app.stopdash.ui

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
        val row = tubeRow(loaded, mapOf("near" to 120.0, "far" to 900.0), tube(), emptySet(), now)
        assertEquals(HomeLines.TUBE_IDS + "73", row.every.mapTo(HashSet()) { it.leg.lineId })
        // The disrupted one leads the pills and the page; the rest are good services, checked.
        assertEquals(listOf("73"), row.lines.map { it.lineId })
        assertEquals("73", row.every.first().leg.lineId)
        assertFalse(row.checking)
        assertFalse(row.unknown)
    }

    @Test
    fun `the pills go worst first, the rider's own ahead of the rest when as bad, as the page does`() {
        // The tube's Central and a bus at a near stop, both with severe delays; the tube's lines come first
        // in TfL's order, the bus only after them.
        val central = LineStatus("central", 6, "Severe Delays")
        val loaded = DeparturesUiState.Loaded(
            listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73"),
        )
        val row = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to central)), emptySet(), now, networks = setOf("tube"))
        assertEquals(listOf("73", "central"), row.lines.map { it.lineId })
        assertEquals(listOf("73", "central"), row.every.take(2).map { it.leg.lineId })
        // A worse one far away leads the pills as it leads the page (maintainer, 2026-10-07): the near one,
        // milder, goes after it, and "+N" takes the mildest first.
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
        // Both the rider's own and as bad as each other: by name, the 73 before the Central; the pills in the page's order.
        assertTrue(fav.lines.map { it.lineId }.let { it.indexOf("73") < it.indexOf("central") })
        assertEquals(fav.every.filter { it.disrupted }.map { it.leg.lineId }, fav.lines.map { it.lineId })
        // A star whose stop has left the list ranks nothing: its line goes with the network's (Codex, #640).
        val goneStar = setOf(app.stopdash.domain.StarredRow("elsewhere", "central", "outbound"))
        val left = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to central) + ("victoria" to victoria)), emptySet(), now, networks = setOf("tube"), starred = goneStar)
        assertTrue(left.lines.map { it.lineId }.let { it.indexOf("73") < it.indexOf("central") })
        assertEquals(setOf("73", "central", "victoria"), left.lines.mapTo(HashSet()) { it.lineId })
        // So does any line a favorite journey rides, not only the one it was starred from.
        val ridden = HomeLines.row(loaded, mapOf("near" to 50.0), tube(good + ("central" to central) + ("victoria" to victoria)), emptySet(), now, networks = setOf("tube"), journeyLines = setOf("central"))
        assertTrue(ridden.lines.map { it.lineId }.let { it.indexOf("73") < it.indexOf("central") })
        assertEquals(ridden.every.filter { it.disrupted }.map { it.leg.lineId }, ridden.lines.map { it.lineId })
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
        val ids = tubeRow(loaded, mapOf("near" to 50.0), checked, emptySet(), now, networks = setOf("overground")).every.map { it.leg.lineId }
        assertEquals(asked + "73", ids.toSet())
        // None chosen: the nearby lines alone.
        assertEquals(listOf("73"), tubeRow(loaded, mapOf("near" to 50.0), null, emptySet(), now, networks = emptySet()).every.map { it.leg.lineId })
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
        // The favorites' lines, checked by the list; never a line no favorite rides, nor a whole network.
        assertEquals(setOf("victoria", "38"), row.every.mapTo(HashSet()) { it.leg.lineId })
        assertEquals(listOf("victoria"), row.lines.map { it.lineId })
        assertFalse(row.checking)
        assertFalse(row.unknown)
        // Before the list is in, the journey's line still shows, as checking.
        val cold = HomeLines.row(null, emptyMap(), null, emptySet(), now, journeyLines = setOf("victoria"), journeys = listOf(journey))
        assertEquals(listOf("victoria"), cold.every.map { it.leg.lineId })
        assertTrue(cold.every.single().checking)
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
        assertEquals(setOf("73", "elizabeth"), waiting.every.mapTo(HashSet()) { it.leg.lineId })
        assertTrue(row(waiting, "elizabeth").checking)
        assertFalse(waiting.unknown)
        val part = LineStatus("elizabeth", 3, "Part Suspended")
        val judged = HomeLines.row(loaded, near, HomeLines.Always(mapOf("elizabeth" to part), now), emptySet(), now, starred = starred, journeyLines = setOf("elizabeth"), journeys = listOf(journey))
        assertEquals(listOf("elizabeth"), judged.lines.map { it.lineId })
        assertFalse(judged.checking)
        assertFalse(judged.unknown)
    }

    @Test
    fun `a favorite place's station lines are covered wherever the rider is, checking until asked, then the rider's own`() {
        // The rider at a bus stop; a favorite place by Euston, whose stations' lines the row covers though no
        // stop near the rider serves them (maintainer, 2026-10-07).
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, lineStatuses = mapOf("73" to severe), determinedLineIds = setOf("73"))
        val near = mapOf("near" to 50.0)
        val place = listOf(LineRef("northern", "Northern", "tube"), LineRef("london-northwestern", "London Northwestern Railway", "national-rail"))
        val waiting = HomeLines.row(loaded, near, null, emptySet(), now, placeLines = place)
        assertEquals(setOf("73", "northern", "london-northwestern"), waiting.every.mapTo(HashSet()) { it.leg.lineId })
        assertTrue(row(waiting, "northern").checking)
        assertFalse(waiting.unknown)
        // Once asked: as bad as the near bus, a place's line ranks with it as the rider's own, by name, ahead of a
        // network's line far away just as bad.
        val northern = LineStatus("northern", 6, "Severe Delays")
        val central = LineStatus("central", 6, "Severe Delays")
        val judged = HomeLines.row(
            loaded, near, HomeLines.Always(mapOf("northern" to northern, "london-northwestern" to LineStatus("london-northwestern", LineStatus.GOOD_SERVICE, "Good Service"), "central" to central), now),
            emptySet(), now, networks = setOf("central"), placeLines = place,
        )
        assertEquals(listOf("73", "northern", "central"), judged.lines.map { it.lineId })
        assertEquals("Northern", row(judged, "northern").leg.lineName)
        assertFalse(judged.checking)
    }

    @Test
    fun `favorite places never read leave the row unknown, never a clean none`() {
        val loaded = DeparturesUiState.Loaded(listOf(stop("near", "73" to "bus")), now, determinedLineIds = setOf("73"))
        val good = HomeLines.row(loaded, mapOf("near" to 50.0), null, emptySet(), now)
        assertFalse(good.unknown)
        val unread = HomeLines.row(loaded, mapOf("near" to 50.0), null, emptySet(), now, placesUnread = true)
        assertTrue(unread.unknown)
    }

    @Test
    fun `a star counts at its own stop, not at another fetched stop the line serves`() {
        // The 38 starred at a stop gone from the list; another far stop, fetched, also serves it: that stop
        // doesn't stand in for the star (Codex, #640). Starred at the fetched stop, it does.
        val loaded = DeparturesUiState.Loaded(listOf(stop("other", "38" to "bus")), now, determinedLineIds = setOf("38"))
        val far = mapOf("other" to 2000.0)
        val elsewhere = HomeLines.row(loaded, far, null, emptySet(), now, starred = setOf(app.stopdash.domain.StarredRow("gone", "38", "outbound")))
        assertTrue(elsewhere.every.none { it.leg.lineId == "38" })
        val here = HomeLines.row(loaded, far, null, emptySet(), now, starred = setOf(app.stopdash.domain.StarredRow("other", "38", "outbound")))
        assertEquals(listOf("38"), here.every.map { it.leg.lineId })
    }

    @Test
    fun `a place's station lines follow the places, the last standing while they can't be read, worked on the worker`() {
        // A place at King's Cross (a stock stand-in: a station, not anyone's home); Victoria's line far from it.
        val index = app.stopdash.domain.StationIndex(
            listOf(
                app.stopdash.domain.IndexedStation("940GZZLUKSX", "King's Cross St. Pancras", latitude = 51.5308, longitude = -0.1238, lines = mapOf("tube" to listOf("northern"))),
                app.stopdash.domain.IndexedStation("940GZZLUVIC", "Victoria", latitude = 51.4965, longitude = -0.1447, lines = mapOf("tube" to listOf("district"))),
            ),
            lineNames = mapOf("northern" to "Northern"),
        )
        val place = app.stopdash.domain.FavoritePlace("p", app.stopdash.domain.FavoriteKind.CUSTOM, "Place", app.stopdash.domain.Coordinates(51.5308, -0.1238))
        val readOn = ThreadRecorder()
        val warned = mutableListOf<String>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }
        try {
            val sets = flowOf(
                app.stopdash.domain.FavoritePlacesSet.Unavailable,
                app.stopdash.domain.FavoritePlacesSet.Loaded(listOf(place)),
                app.stopdash.domain.FavoritePlacesSet.Unavailable,
                app.stopdash.domain.FavoritePlacesSet.Loaded(emptyList()),
                app.stopdash.domain.FavoritePlacesSet.Loaded(listOf(place)),
                app.stopdash.domain.FavoritePlacesSet.Discarded,
            )
            val lines = kotlinx.coroutines.runBlocking {
                HomeLines.placeLines(sets, { index.also { readOn.note() } }, executor.asCoroutineDispatcher(), warned::add).toList()
            }
            val northern = listOf(LineRef("northern", "Northern", "tube"))
            // Unreadable before any were read: none, marked unread. Read: the station's line. Unreadable again: the
            // last stands. No places, then one again, then a discarded file: what each says.
            assertEquals(listOf(emptyList(), northern, northern, emptyList(), northern, emptyList()), lines.map { it.lines })
            assertEquals(listOf(true, false, false, false, false, false), lines.map { it.unread })
            // No station index to read: the places' lines can't be named, so unread, never "no lines there".
            val noIndex = kotlinx.coroutines.runBlocking {
                HomeLines.placeLines(
                    flowOf(app.stopdash.domain.FavoritePlacesSet.Loaded(listOf(place))), { app.stopdash.domain.StationIndex.EMPTY }, executor.asCoroutineDispatcher(),
                ).toList()
            }
            assertEquals(listOf(true), noIndex.map { it.unread })
            assertEquals(2, warned.size) // the two unreadable reads
            assertTrue(readOn.threads().isNotEmpty())
            assertTrue(readOn.threads().all { it.startsWith("worker") })
        } finally {
            executor.shutdown()
        }
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
        // The Tube and the Overground under their names, the single-line networks together under Other,
        // every line once.
        assertEquals(listOf(HomeLines.Network.TUBE, HomeLines.Network.OVERGROUND, null), HomeLines.PICKER.map { it.first })
        assertEquals(listOf("elizabeth", "dlr", "tram"), HomeLines.PICKER.last().second.map { it.id })
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
        fun counted(before: Set<String>, lineId: String) = HomeLines.lineChanges(before, HomeLines.toggle(before, lineId)).map { it.params["value"] }
        assertEquals(listOf("added"), counted(emptySet(), "victoria"))
        assertEquals(listOf("removed"), counted(setOf("victoria"), "victoria"))
        // The tube's key turned into its other lines one by one: one line off, not ten on.
        assertEquals(listOf("removed"), counted(setOf("tube"), "central"))
        assertEquals(listOf("removed"), counted(setOf("dlr"), "dlr"))
        // A key or id no network has isn't a line.
        assertEquals(emptyList<String>(), HomeLines.lineChanges(setOf("tram"), setOf("tram", "73")).map { it.params["value"] })
    }

    @Test
    fun `the tube is listed by name`() {
        assertEquals(LineRef("hammersmith-city", "Hammersmith & City", "tube"), HomeLines.TUBE.single { it.id == "hammersmith-city" })
    }
}
