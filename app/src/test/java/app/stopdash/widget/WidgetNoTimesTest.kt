package app.stopdash.widget

import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.NoTimes
import app.stopdash.domain.RailFeed
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    ): WidgetRowModel {
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
        return widgetModel(snapshot, now).rows.single()
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
    fun `a reason from before a failed refresh isn't shown`() {
        val row = statusRow(arrivalsFresh = false)
        assertEquals("southern", row.row.lineId)
        assertNull(row.noTimes)
    }
}
