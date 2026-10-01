package app.stopdash.widget

import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.NoTimes
import app.stopdash.domain.RailFeed
import app.stopdash.domain.StopArrivals
import java.time.Instant
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a status-only widget row says where its times would be: synthetic stops only. */
class WidgetNoTimesTest {
    private val now: Instant = Instant.parse("2026-09-24T08:00:00Z")

    // A disrupted line with no times at one stop, its status checked just now.
    private fun statusRow(
        mode: String = "national-rail",
        feed: RailFeed? = RailFeed.NO_KEY,
        fetchedAt: Instant = now.minusSeconds(30),
        arrivalsFresh: Boolean = true,
        geometry: WidgetGeometry? = null,
    ): WidgetRowModel = statusModel(mode, feed, fetchedAt, arrivalsFresh, geometry).rows.single()

    private fun statusModel(
        mode: String = "national-rail",
        feed: RailFeed? = RailFeed.NO_KEY,
        fetchedAt: Instant = now.minusSeconds(30),
        arrivalsFresh: Boolean = true,
        geometry: WidgetGeometry? = null,
    ): WidgetModel {
        val stop = StopArrivals(
            "910GEXAMPLE", "Example", emptyList(), fetchedAt,
            lines = listOf(LineRef("southern", "Southern", mode)),
            railFeed = feed,
            arrivalsFresh = arrivalsFresh,
        )
        val snapshot = DeparturesSnapshot(
            listOf(stop),
            fetchedAt,
            lineStatuses = mapOf("southern" to LineStatusCheck(LineStatus("southern", 6, "Severe Delays"), now)),
        )
        return widgetModel(snapshot, now, geometry = geometry)
    }

    @Test
    fun `a disrupted rail line with no times says why, as the in-app card does`() {
        assertEquals(NoTimes.NO_KEY, statusRow(feed = RailFeed.NO_KEY).noTimes)
        assertEquals(NoTimes.NO_DATA, statusRow(feed = RailFeed.UNAVAILABLE).noTimes)
    }

    @Test
    fun `a line whose source answered with no trains adds nothing`() {
        assertNull(statusRow(feed = RailFeed.LIVE).noTimes)
        assertNull(statusRow(mode = "tube", feed = null).noTimes)
    }

    @Test
    fun `a reason from arrivals now stale isn't shown, though the live status keeps the row`() {
        val row = statusRow(fetchedAt = now.minusSeconds(600))
        assertEquals("southern", row.row.lineId)
        assertNull(row.noTimes)
    }

    @Test
    fun `a status row stacks for the reason it shows, not one it withholds`() {
        val narrow = WidgetGeometry(220.dp, 180.dp)
        assertTrue("the pill, the status and the reason don't share a line", statusRow(geometry = narrow).statusStacked)
        // Its arrivals stale: no reason is shown, and the pill and the status fit one line.
        val withheld = statusRow(fetchedAt = now.minusSeconds(600), geometry = narrow)
        assertNull(withheld.noTimes)
        assertFalse(withheld.statusStacked)
    }

    @Test
    fun `a status row with no reason stacks when its status doesn't fit beside the pill`() {
        // Codex on #457: a Tube line with no predictions has no reason to show, and was judged on a
        // destination's few characters, so "Severe Delays" was cut at the minimum width at 1.3x.
        val row = statusRow(mode = "tube", feed = null, geometry = WidgetGeometry(180.dp, 180.dp, 1.3f))
        assertNull(row.noTimes)
        assertTrue(row.statusStacked)
        assertFalse(statusRow(mode = "tube", feed = null, geometry = WidgetGeometry(300.dp, 180.dp, 1.3f)).statusStacked)
    }

    @Test
    fun `a stacked status row is costed at both its lines`() {
        // The minimum size at 1.3x: its two lines (61dp) don't fit under the full header (52dp), so
        // the compact layout makes room for them rather than clip the status off the bottom.
        val model = statusModel(geometry = WidgetGeometry(180.dp, 110.dp, 1.3f))
        assertTrue(model.compact)
        assertTrue(model.rows.single().statusStacked)
    }

    @Test
    fun `a reason from before a failed refresh isn't shown`() {
        val row = statusRow(arrivalsFresh = false)
        assertEquals("southern", row.row.lineId)
        assertNull(row.noTimes)
    }
}
