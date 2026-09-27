package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.STATUS_DIRECTION_KEY
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the glanceable surfaces budget a disruption (SPEC D3): its status line counts, and is kept. */
class BudgetedRowsTest {
    private val now: Instant = Instant.parse("2026-09-20T08:00:00Z")

    private fun row(lineId: String, vararg destinations: String, status: LineStatus? = null) = DepartureRow(
        stopId = "490000001A",
        stopName = "Example Stop",
        lineId = lineId,
        lineName = lineId,
        direction = "inbound",
        directionKey = if (destinations.isEmpty()) STATUS_DIRECTION_KEY else "inbound",
        destination = destinations.firstOrNull().orEmpty(),
        mode = "tube",
        upcoming = destinations.mapIndexed { i, d ->
            Departure(lineId, lineId, "inbound", d, null, now.plusSeconds(60L * (i + 1)), "tube")
        },
        fetchedAt = now,
        status = status,
    )

    private fun select(rows: List<DepartureRow>, budget: Int) =
        BudgetedRows.select(rows, budget, maxTimes = 3, topology = RouteTopology.EMPTY)

    @Test
    fun `a disrupted row costs its status line too`() {
        val delayed = row("victoria", "Brixton", status = LineStatus("victoria", 6, "Severe Delays"))
        val plain = row("jubilee", "Stratford")
        assertEquals(listOf("victoria"), select(listOf(delayed, plain), budget = 2).map { it.row.lineId })
        assertEquals(listOf("victoria", "jubilee"), select(listOf(delayed, plain), budget = 3).map { it.row.lineId })
    }

    @Test
    fun `a status-only row is shown on one line`() {
        val suspended = row("waterloo-city", status = LineStatus("waterloo-city", 5, "Suspended"))
        val chosen = select(listOf(suspended, row("jubilee", "Stratford")), budget = 2)
        assertEquals(listOf("waterloo-city", "jubilee"), chosen.map { it.row.lineId })
        assertEquals(emptyList<Any>(), chosen.first().groups)
    }

    @Test
    fun `a branching disrupted row gives up destination lines, never its status`() {
        val delayed = row("northern", "Morden", "Kennington", "Battersea", status = LineStatus("northern", 6, "Minor Delays"))
        val chosen = select(listOf(delayed), budget = 2).single()
        assertEquals(1, chosen.groups.size)
    }

    @Test
    fun `a row with no status and no departures is still left out`() {
        assertEquals(emptyList<BudgetedRow>(), select(listOf(row("victoria")), budget = 4))
    }

    @Test
    fun `with one line, a disrupted row with departures shows as its status alone`() {
        val delayed = row("victoria", "Brixton", status = LineStatus("victoria", 6, "Severe Delays"))
        val chosen = select(listOf(delayed), budget = 1).single()
        assertEquals(emptyList<Any>(), chosen.groups)
        assertEquals("victoria", chosen.row.lineId)
    }
}
