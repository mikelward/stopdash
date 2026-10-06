package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each place's modes for a header's "Hide ‹mode›" items are worked out with the list's rows, on its
 * worker ([listRowsOf]), less the modes already hidden, so the headers drawn and their items are from
 * one snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ListRowsPlaceModesTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // Victoria's tube platforms and a made-up bus stand beside them, one place.
    private val tube = StopArrivals(
        stopId = "940GZZLUVIC",
        stopName = "Victoria",
        departures = listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(240), "tube")),
        fetchedAt = now,
        clusterId = "HUBVIC",
    )
    private val bus = StopArrivals(
        stopId = "BUSSTAND1",
        stopName = "Victoria",
        departures = emptyList(),
        fetchedAt = now,
        clusterId = "HUBVIC",
        lines = listOf(LineRef("24", "24", "bus")),
    )

    private fun rows(hidden: Set<String>) = listRowsOf(
        DeparturesUiState.Loaded(stops = listOf(tube, bus), fetchedAt = now),
        now,
        stopDistanceMeters = emptyMap(),
        dismissed = emptySet(),
        hiddenModes = hidden,
        alertSequences = emptyMap(),
        journeyDestinationStops = emptyList(),
    )

    @Test
    fun a_places_modes_come_from_its_stops_lines_and_departures() {
        assertEquals(mapOf("HUBVIC" to setOf("tube", "bus")), rows(emptySet()).placeModes)
    }

    @Test
    fun a_hidden_mode_is_offered_no_more() {
        assertEquals(mapOf("HUBVIC" to setOf("tube")), rows(setOf("Bus")).placeModes)
    }
}
