package app.stopdash.widget

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DestinationGroup
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.BranchSpoken
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The widget's destination line: it can't measure, so a branch always shows in its short form
 * ([widgetLineLabel]), and [widgetLineSpoken] keeps the full branch for a screen reader.
 */
class WidgetLineLabelTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val departure = Departure("central", "Central", "outbound", "Hainault", null, now.plusSeconds(240), "tube")

    private val row = DepartureRows.across(
        listOf(StopArrivals("940GZZLUOXC", "Oxford Circus", listOf(departure), now)),
        now,
    ).first { it.upcoming.isNotEmpty() }

    @Test
    fun `a shortened branch shows short and is spoken in full`() {
        val group = DestinationGroup("Hainault", "Newbury Park", listOf(departure))
        assertEquals("Hainault/Newbury Pk", widgetLineLabel(row, group))
        assertEquals(BranchSpoken("Newbury Park", terminus = "Hainault"), widgetLineSpoken(row, group))
    }

    @Test
    fun `a branch with nothing to shorten needs no spoken form`() {
        val group = DestinationGroup("Hainault", "Woodford", listOf(departure))
        assertEquals("Hainault/Woodford", widgetLineLabel(row, group))
        assertNull(widgetLineSpoken(row, group))
    }

    @Test
    fun `a row with no branch needs no spoken form`() {
        val group = DestinationGroup("Hainault", null, listOf(departure))
        assertEquals("Hainault", widgetLineLabel(row, group))
        assertNull(widgetLineSpoken(row, group))
    }
}
