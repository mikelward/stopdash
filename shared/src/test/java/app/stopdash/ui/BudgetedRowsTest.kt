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

    // One place reads as implied by default; a caller's grouping that names it (the full widget)
    // gets its header, costed as one.
    @Test
    fun `a single place has no header unless the caller's grouping names it`() {
        val rows = listOf(row("victoria", "Brixton"), row("jubilee", "Stratford"))
        assertTrue(select(rows, budget = 3).all { it.header == null })
        val named = BudgetedRows.select(rows, budget = 3, maxTimes = 3, topology = RouteTopology.EMPTY, grouped = { r ->
            app.stopdash.domain.StopGrouping.groupByStop(r, warningsLead = false).map { it.copy(showHeader = true) }
        })
        assertEquals(listOf("Example Stop", null), named.map { it.header?.text })
        assertEquals("the header takes a line", 2, named.size)
        val tight = BudgetedRows.select(rows, budget = 2, maxTimes = 3, topology = RouteTopology.EMPTY, grouped = { r ->
            app.stopdash.domain.StopGrouping.groupByStop(r, warningsLead = false).map { it.copy(showHeader = true) }
        })
        assertEquals("header and one row fit two lines", listOf("victoria"), tight.map { it.row.lineId })
    }

    // Grouped by station, a station the cap leaves alone keeps its name: the rows that didn't fit are
    // at another station, so the one shown isn't the only place, as grouped by stop already judges.
    @Test
    fun `grouped by station, a station the cap leaves alone is still named`() {
        val here = row("victoria", "Brixton").copy(clusterId = "HUBA")
        val there = row("central", "Ealing").copy(stopId = "490000002B", stopName = "Other Stop", clusterId = "HUBB")
        val byStation: (List<DepartureRow>) -> List<app.stopdash.domain.StopGroup> =
            { app.stopdash.domain.StopGrouping.groupByStation(it, warningsLead = false) }
        val both = BudgetedRows.select(listOf(here, there), budget = 4, maxTimes = 3, topology = RouteTopology.EMPTY, grouped = byStation)
        assertEquals(listOf("Example Stop", "Other Stop"), both.mapNotNull { it.header?.text })
        val capped = BudgetedRows.select(listOf(here, there), budget = 2, maxTimes = 3, topology = RouteTopology.EMPTY, grouped = byStation)
        assertEquals(listOf("victoria"), capped.map { it.row.lineId })
        assertEquals("Example Stop", capped.single().header?.text)
    }

    // A group running on into the next band is named once; two groups that share a title but are
    // different stops (same name and towards, another bearing) keep a header each (Codex on #760).
    // A header's platform and pole are in the words the surface passes (its language), seen and heard.
    @Test
    fun `a header's qualifier is in the surface's words`() {
        val a = row("victoria", "Brixton")
        val b = row("jubilee", "Stratford")
        val german = HeaderWords({ "Gleis $it" }, { "Haltestelle $it" }, { "Richtung $it" }, emptyMap())
        fun headers(words: HeaderWords) = BudgetedRows.select(listOf(a, b), budget = 4, maxTimes = 3, topology = RouteTopology.EMPTY, grouped = { rows ->
            listOf(
                app.stopdash.domain.StopGroup("Example Stop", "k1", showHeader = true, rows = listOf(a), qualifier = app.stopdash.domain.StopQualifier.Platform("2", "Northbound")),
                app.stopdash.domain.StopGroup("Example Stop", "k2", showHeader = true, rows = listOf(b), qualifier = app.stopdash.domain.StopQualifier.BusStop("D", "Example Cross")),
            ).map { g -> g.copy(rows = g.rows.filter { it in rows }) }.filter { it.rows.isNotEmpty() }
        }, words = words).map { it.header?.text to it.header?.spoken }
        assertEquals(
            listOf(
                "Example Stop – Gleis 2" to "Example Stop, Gleis 2, Northbound",
                "Example Stop – Haltestelle D" to "Example Stop, Haltestelle D, Richtung Example Cross",
            ),
            headers(german),
        )
        // The default is the English every surface has always written.
        assertEquals(
            listOf(
                "Example Stop – Platform 2" to "Example Stop, Platform 2, Northbound",
                "Example Stop – Stop D" to "Example Stop, Stop D, towards Example Cross",
            ),
            headers(HeaderWords.ENGLISH),
        )
    }

    @Test
    fun `a header repeats only for a different group, not a continuation`() {
        val a = row("victoria", "Brixton")
        val b = row("jubilee", "Stratford")
        fun group(key: String, row: DepartureRow) =
            app.stopdash.domain.StopGroup("Example Stop", key, showHeader = true, rows = listOf(row), placeKey = "Example Stop")
        fun headers(secondKey: String) = BudgetedRows.select(listOf(a, b), budget = 4, maxTimes = 3, topology = RouteTopology.EMPTY, grouped = { rows ->
            listOf(group("k1", a), group(secondKey, b)).map { g -> g.copy(rows = g.rows.filter { it in rows }) }.filter { it.rows.isNotEmpty() }
        }).map { it.header?.text }
        assertEquals(listOf("Example Stop", null), headers("k1"))
        // A group with a spoken form of its own is said that way; one without, by its name.
        val spoken = BudgetedRows.select(listOf(a, b), budget = 4, maxTimes = 3, topology = RouteTopology.EMPTY, grouped = { rows ->
            listOf(group("k1", a).copy(stopName = "Park ➔ Hill", spokenName = "Park to Hill"), group("k2", b))
                .map { g -> g.copy(rows = g.rows.filter { it in rows }) }.filter { it.rows.isNotEmpty() }
        }).map { it.header?.text to it.header?.spoken }
        assertEquals(listOf("Park ➔ Hill" to "Park to Hill", "Example Stop" to "Example Stop"), spoken)
        assertEquals(listOf("Example Stop", "Example Stop"), headers("k2"))
    }

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
