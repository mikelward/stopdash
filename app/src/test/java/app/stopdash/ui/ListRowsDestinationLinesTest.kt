package app.stopdash.ui

import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopDisruption
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A ride's far end as the ride reads it ([ListRows.destinationClosuresFor], [ListRows.destinationDisruptedFor]):
 * TfL files a station's notices for every line there, so a notice about other lines only is no part of a ride.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ListRowsDestinationLinesTest {
    private val now: Instant = Instant.parse("2026-10-26T08:00:00Z")
    private val farEndId = "940GZZLUKSX"
    private val circle = StopDisruption("Circle Line: No service between Aldgate and Edgware Road.")

    private fun rows(vararg notices: StopDisruption) = listRowsOf(
        DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now),
        now,
        stopDistanceMeters = emptyMap(),
        dismissed = emptySet(),
        hiddenModes = emptySet(),
        alertSequences = emptyMap(),
        journeyDestinationStops = listOf(StopArrivals(farEndId, "King's Cross St. Pancras", emptyList(), now, disruptions = notices.toList())),
    )

    @Test
    fun a_notice_about_another_line_is_no_part_of_the_ride() {
        val rows = rows(circle)
        assertTrue(rows.destinationClosuresFor(listOf("northern")).isEmpty())
        assertFalse(rows.destinationDisruptedFor(listOf(farEndId), listOf("northern")))
        // A ride on that line still has it.
        assertEquals(1, rows.destinationClosuresFor(listOf("circle")).size)
        assertTrue(rows.destinationDisruptedFor(listOf(farEndId), listOf("circle")))
    }

    @Test
    fun the_ride_keeps_its_own_lines_notices_and_those_naming_no_line() {
        val rows = rows(circle, StopDisruption("Northern line: lifts out of order."), StopDisruption("Station closed."))
        val text = rows.destinationClosuresFor(listOf("northern")).single().stopDisruption.orEmpty()
        assertTrue(text.contains("lifts out of order"))
        assertTrue(text.contains("Station closed"))
        assertFalse(text.contains("Circle"))
    }

    @Test
    fun a_ride_with_no_lines_known_keeps_every_notice() {
        val rows = rows(circle)
        assertTrue(rows.destinationClosuresFor(emptyList()) === rows.destinationClosures)
        assertTrue(rows.destinationDisruptedFor(listOf(farEndId), emptyList()))
    }
}
