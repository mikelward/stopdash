package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Grouped by station, a station's TfL records, placed by its code, are one station ([shownRowsOf], [groupStation]):
 * St Pancras's high-speed platforms and its main record under one heading, without the empty row of the one whose
 * board the other shows, and as near as the nearer (maintainer, 2026-10-30).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class StationRecordsTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")
    private val southeastern = LineRef("southeastern", "Southeastern", "national-rail")

    @Test
    fun `a station's two records are one station, the one without the board adding no empty row`() {
        // Both filed under the station's least record, as the nearby lookup places them ([StationCodeStopFinder]).
        val high = StopArrivals("910GHIGH", "Example International", emptyList(), now, lines = listOf(southeastern), clusterId = "910GHIGH")
        val main = StopArrivals(
            "910GMAIN", "Example International",
            listOf(Departure("southeastern", "Southeastern", "outbound", "Faraway", null, now.plusSeconds(480), "national-rail")),
            now, lines = listOf(southeastern), clusterId = "910GHIGH",
        )
        val meters = mapOf("910GHIGH" to 100.0, "910GMAIN" to 140.0)
        val built = listRowsOf(
            DeparturesUiState.Loaded(
                stops = listOf(high, main), fetchedAt = now,
                lineStatuses = mapOf("southeastern" to LineStatus("southeastern", 9, "Service Alert")),
            ),
            now,
            stopDistanceMeters = meters,
            dismissed = emptySet(),
            hiddenModes = emptySet(),
            alertSequences = emptyMap(),
            journeyDestinationStops = emptyList(),
        )
        // One station either way; before the station model is read, the empty record's row is still in it.
        val apart = shownRowsOf(built, built.marked.rows, emptyList(), starred = emptySet(), stopDistanceMeters = meters, byStation = true)
        assertEquals(1, apart.listCards.groups.map(::groupStation).distinct().size)
        assertEquals(setOf("910GHIGH", "910GMAIN"), apart.rows.mapTo(HashSet()) { it.stopId })
        val codes = mapOf("910GHIGH" to "1009001", "910GMAIN" to "1009001")
        val together = shownRowsOf(
            built, built.marked.rows, emptyList(), starred = emptySet(), stopDistanceMeters = meters, byStation = true,
            stationCodeOf = codes::get,
        )
        assertEquals(listOf("910GMAIN"), together.rows.map { it.stopId })
        val station = together.listCards.groups.map(::groupStation).single()
        // Still as near as the record whose row went.
        assertEquals(100.0, together.listCards.mergedStationMeters.getValue(station), 0.0)
        assertEquals(100.0, together.listCards.mergedPlaceMeters.getValue("910GHIGH"), 0.0)
    }

    @Test
    fun `a record whose one mode's rows read into another still gives that mode's card its distance`() {
        // The nearer record has Elizabeth line trains and an empty Southeastern row the farther one's board fills.
        val elizabeth = LineRef("elizabeth", "Elizabeth line", "elizabeth-line")
        val near = StopArrivals(
            "910GHIGH", "Example International",
            listOf(Departure("elizabeth", "Elizabeth line", "outbound", "Faraway", null, now.plusSeconds(300), "elizabeth-line")),
            now, lines = listOf(southeastern, elizabeth), clusterId = "910GHIGH",
        )
        val far = StopArrivals(
            "910GMAIN", "Example International",
            listOf(Departure("southeastern", "Southeastern", "outbound", "Faraway", null, now.plusSeconds(480), "national-rail")),
            now, lines = listOf(southeastern), clusterId = "910GHIGH",
        )
        val meters = mapOf("910GHIGH" to 100.0, "910GMAIN" to 140.0)
        val built = listRowsOf(
            DeparturesUiState.Loaded(
                stops = listOf(near, far), fetchedAt = now,
                lineStatuses = mapOf("southeastern" to LineStatus("southeastern", 9, "Service Alert")),
            ),
            now,
            stopDistanceMeters = meters,
            dismissed = emptySet(),
            hiddenModes = emptySet(),
            alertSequences = emptyMap(),
            journeyDestinationStops = emptyList(),
        )
        val codes = mapOf("910GHIGH" to "1009001", "910GMAIN" to "1009001")
        val shown = shownRowsOf(
            built, built.marked.rows, emptyList(), starred = emptySet(), stopDistanceMeters = meters, byStation = true,
            stationCodeOf = codes::get,
        )
        assertEquals(setOf("910GHIGH" to "elizabeth-line", "910GMAIN" to "national-rail"), shown.rows.mapTo(HashSet()) { it.stopId to it.mode })
        // The rail card is as near as the nearer record, whose rail row went, though its Elizabeth line rows stay.
        assertEquals(100.0, shown.listCards.mergedStationMeters.getValue(stationKey("910GHIGH", "national-rail")), 0.0)
    }
}
