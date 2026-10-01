package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.STATUS_DIRECTION_KEY
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a row's least is what select would have to draw for it`() {
        // A line costs 2, a status under it 1, and a status alone 3.
        val costs = LineCosts(line = { _, _ -> 2 }, status = { _, alone -> if (alone) 3 else 1 })
        fun least(row: DepartureRow) = BudgetedRows.least(row, maxTimes = 3, topology = RouteTopology.EMPTY, costs = costs)
        assertEquals(2, least(row("jubilee", "Stratford")))
        // Codex on #457: a disrupted row's first line draws its status under it, so it's 3, not 2.
        val severe = LineStatus("victoria", 6, "Severe Delays")
        assertEquals(3, least(row("victoria", "Brixton", status = severe)))
        // Or its status alone, when that's less.
        val cheapAlone = LineCosts(line = { _, _ -> 2 }, status = { _, alone -> if (alone) 2 else 1 })
        assertEquals(2, BudgetedRows.least(row("victoria", "Brixton", status = severe), 3, RouteTopology.EMPTY, cheapAlone))
        assertEquals(3, least(row("victoria", status = severe)))
        assertNull("nothing to show", least(row("victoria")))
    }

    @Test
    fun `each row costs what its own lines take`() {
        // A row costing two units a line beside one costing one, in a budget of five units.
        val tall = row("northern", "Morden", "Kennington", "Battersea")
        val short = row("jubilee", "Stratford")
        val costs = LineCosts(header = 1, line = { r, _ -> if (r.lineId == "northern") 2 else 1 })
        val chosen = BudgetedRows.select(listOf(tall, short), 5, maxTimes = 3, topology = RouteTopology.EMPTY, costs = costs)
        // Six units for the tall row's three lines: it gives up one (saving two), and the short row
        // fits in what's left.
        assertEquals(listOf("northern", "jubilee"), chosen.map { it.row.lineId })
        assertEquals(2, chosen.first().groups.size)
        // A row first in priority keeps its lines over a later one: with four units, the tall row's
        // two lines take them all.
        val tight = BudgetedRows.select(listOf(tall, short), 4, 3, RouteTopology.EMPTY, costs = costs)
        assertEquals(listOf("northern"), tight.map { it.row.lineId })
        assertEquals(2, tight.single().groups.size)
        // A disrupted row with too little room for its countdown shows its status at the status's cost.
        val delayed = row("victoria", "Brixton", status = LineStatus("victoria", 6, "Severe Delays"))
        val statusAlone = BudgetedRows.select(
            listOf(delayed), 1, 3, RouteTopology.EMPTY,
            costs = LineCosts(line = { _, _ -> 3 }, status = { _, _ -> 1 }),
        ).single()
        assertEquals(emptyList<Any>(), statusAlone.groups)
    }

    @Test
    fun `a header shows only where the next departure fits under it as the least it's drawn`() {
        // Two places, so each would get a header. The next departure is a status drawn alone that
        // costs 80 (two stacked lines); a header is 42. In 108, header and status don't fit, so the
        // headers go and the disruption shows, rather than be dropped for a later row (Codex on #457).
        val suspended = row("waterloo-city", status = LineStatus("waterloo-city", 5, "Suspended"))
        val elsewhere = row("jubilee", "Stratford").copy(stopId = "490000002B", stopName = "Other Stop", clusterId = "Other Stop")
        val costs = LineCosts(header = 42, line = { _, _ -> 42 }, status = { _, alone -> if (alone) 80 else 42 })
        fun pick(budget: Int) = BudgetedRows.select(listOf(suspended, elsewhere), budget, 3, RouteTopology.EMPTY, costs = costs)
        val tight = pick(108)
        assertEquals(listOf("waterloo-city"), tight.map { it.row.lineId })
        assertEquals(null, tight.single().header)
        // With room for a header over it, both places are named.
        val roomy = pick(122 + 84)
        assertEquals(listOf("waterloo-city", "jubilee"), roomy.map { it.row.lineId })
        assertTrue(roomy.all { it.header != null })
    }

    @Test
    fun `with one line, a disrupted row with departures shows as its status alone`() {
        val delayed = row("victoria", "Brixton", status = LineStatus("victoria", 6, "Severe Delays"))
        val chosen = select(listOf(delayed), budget = 1).single()
        assertEquals(emptyList<Any>(), chosen.groups)
        assertEquals("victoria", chosen.row.lineId)
    }
}
