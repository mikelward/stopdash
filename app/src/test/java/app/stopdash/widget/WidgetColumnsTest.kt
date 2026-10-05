package app.stopdash.widget

import androidx.compose.ui.unit.dp
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.StopArrivals
import app.stopdash.ui.BudgetedRow
import app.stopdash.ui.GroupHeader
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A widget wide enough for two columns splits its rows between them ([widgetColumns]); a phone's stays
 * one. Public station names only.
 */
class WidgetColumnsTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun row(destination: String) = DepartureRow(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        lineId = "victoria",
        lineName = "Victoria",
        direction = "inbound",
        directionKey = "inbound",
        destination = destination,
        mode = "tube",
        upcoming = emptyList(),
        fetchedAt = now,
    )

    private fun budgeted(destination: String, header: String? = null) =
        BudgetedRow(row(destination), emptyList(), header?.let { GroupHeader(it, it) })

    // Each row costs 10, a header 5 more.
    private fun cost(row: BudgetedRow) = 10 + if (row.header != null) 5 else 0

    @Test
    fun `rows split where the taller column is shortest`() {
        val rows = listOf(budgeted("A"), budgeted("B"), budgeted("C"), budgeted("D"))
        val (kept, at) = widgetColumns(rows, budget = 30, header = 5, cost = ::cost)!!
        assertEquals(rows, kept)
        assertEquals(2, at)
    }

    @Test
    fun `a place that runs on into the second column has its header again`() {
        val rows = listOf(budgeted("A", header = "King's Cross St. Pancras"), budgeted("B"), budgeted("C"), budgeted("D"))
        val (kept, at) = widgetColumns(rows, budget = 30, header = 5, cost = ::cost)!!
        assertEquals(2, at)
        assertEquals("King's Cross St. Pancras", kept[2].header?.text)
        assertNull(kept[1].header)
    }

    @Test
    fun `rows that don't split into two that fit aren't split`() {
        val rows = listOf(budgeted("A"), budgeted("B"), budgeted("C"), budgeted("D"), budgeted("E"))
        assertNull(widgetColumns(rows, budget = 20, header = 5, cost = ::cost))
        assertNull(widgetColumns(listOf(budgeted("A")), budget = 30, header = 5, cost = ::cost))
    }

    @Test
    fun `two columns start past a phone's widest widget`() {
        assertEquals(480.dp, WIDGET_TWO_COLUMN_WIDTH)
        assertTrue(WIDGET_TWO_COLUMN_WIDTH > 412.dp)
        assertEquals(244.dp, widgetColumnWidth(WIDGET_TWO_COLUMN_WIDTH))
    }

    private fun snapshot(): DeparturesSnapshot {
        fun dep(lineId: String, destination: String, offsetSeconds: Long) =
            Departure(lineId, lineId.replaceFirstChar { it.uppercase() }, "inbound", destination, null, now.plusSeconds(offsetSeconds), "tube")
        return DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "940GZZLUKSX",
                    "King's Cross St. Pancras",
                    listOf(dep("victoria", "Brixton", 60), dep("piccadilly", "Heathrow Terminal 5", 120), dep("piccadilly", "Cockfosters", 240)),
                    now,
                    disruptions = emptyList(),
                ),
                StopArrivals(
                    "940GZZLUEUS",
                    "Euston",
                    listOf(dep("northern", "Morden", 90), dep("northern", "Edgware", 300)),
                    now,
                    disruptions = emptyList(),
                ),
            ),
            fetchedAt = now,
            lineStatuses = listOf("victoria", "piccadilly", "northern").associateWith {
                LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now)
            },
        )
    }

    @Test
    fun `a tablet's widget puts its rows in two columns, a phone's in one`() {
        val tablet = widgetModel(snapshot(), now, geometry = WidgetGeometry(760.dp, 400.dp))
        assertNotNull(tablet.columnBreak)
        val at = tablet.columnBreak!!
        assertTrue(at in 1 until tablet.rows.size)
        // Each column opens under its place's name.
        assertNotNull(tablet.rows[0].header)
        assertNotNull(tablet.rows[at].header)
        val phone = widgetModel(snapshot(), now, geometry = WidgetGeometry(380.dp, 400.dp))
        assertNull(phone.columnBreak)
        assertEquals(phone.rows.map { it.row.destination }, tablet.rows.map { it.row.destination })
    }

    // A nearer place's sooner trains aren't dropped to make the columns split: the rows are chosen
    // again by priority, so a later train goes first.
    @Test
    fun `a split never drops a sooner place than a row it keeps`() {
        fun dep(lineId: String, destination: String, s: Long) =
            Departure(lineId, lineId, "inbound", destination, null, now.plusSeconds(s), "tube")
        val stops = listOf(
            StopArrivals("940GZZLUKSX", "King's Cross St. Pancras", listOf(dep("victoria", "Brixton", 60), dep("piccadilly", "Heathrow Terminal 5", 120), dep("piccadilly", "Cockfosters", 240), dep("northern", "High Barnet", 150)), now, disruptions = emptyList()),
            StopArrivals("940GZZLUEUS", "Euston", listOf(dep("northern", "Edgware", 90), dep("northern", "Battersea Power Station", 210), dep("northern", "Morden", 300)), now, disruptions = emptyList()),
            StopArrivals("940GZZLUTCR", "Tottenham Court Road", listOf(dep("central", "Epping", 100), dep("central", "Ealing Broadway", 200)), now, disruptions = emptyList()),
        )
        val lines = listOf("victoria", "piccadilly", "northern", "central")
        val snapshot = DeparturesSnapshot(stops, now, lineStatuses = lines.associateWith { LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now) })
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(480.dp, 260.dp))
        assertNotNull(model.columnBreak)
        // Central (due in 100 s) shows ahead of the later High Barnet train (150 s).
        assertTrue(model.rows.any { it.row.lineId == "central" })
    }

    // One branching service with more destinations than one column holds can't split (it's one row):
    // it's chosen again for one column, so its lines never run past the bottom (Codex on #601).
    @Test
    fun `a lone row too tall for one column is chosen for one`() {
        val destinations = listOf("Morden", "Kennington", "Battersea Power Station", "Edgware", "High Barnet", "Mill Hill East", "Golders Green", "Archway")
        val stops = listOf(
            StopArrivals(
                "940GZZLUEUS", "Euston",
                destinations.mapIndexed { i, d -> Departure("northern", "Northern", "inbound", d, null, now.plusSeconds(60L * (i + 1)), "tube") },
                now, disruptions = emptyList(),
            ),
        )
        val snapshot = DeparturesSnapshot(stops, now, lineStatuses = mapOf("northern" to LineStatusCheck(LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"), now)))
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(760.dp, 180.dp))
        assertNull(model.columnBreak)
        val drawn = model.rows.sumOf { row -> row.groups.size } * widgetLineHeight()
        assertTrue(drawn <= widgetRowsHeight(180.dp))
    }

    // A wide widget that keeps one column (a lone row can't split) costs its rows at the whole width: a
    // row whose times stack at a column's width at a large font doesn't at the widget's (Codex on #601).
    @Test
    fun `one column on a wide widget is costed at its whole width`() {
        fun dep(destination: String, s: Long) = Departure("northern", "Northern", "inbound", destination, null, now.plusSeconds(s), "tube")
        val stops = listOf(
            StopArrivals(
                "940GZZLUEUS", "Euston",
                listOf(dep("Morden", 60), dep("Morden", 240), dep("Morden", 480), dep("Edgware", 120), dep("Edgware", 300), dep("Edgware", 540)),
                now, disruptions = emptyList(),
            ),
        )
        val snapshot = DeparturesSnapshot(stops, now, lineStatuses = mapOf("northern" to LineStatusCheck(LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"), now)))
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(WIDGET_TWO_COLUMN_WIDTH, 400.dp, 1.3f))
        assertNull(model.columnBreak)
        // The same lines would stack at a column's width.
        assertTrue(widgetRowStacked(listOf("1 · 4 · 8 min"), false, widgetColumnWidth(WIDGET_TWO_COLUMN_WIDTH), 1.3f))
        assertEquals(listOf(false, false), model.rows.single().stackedLines)
    }

    // Two places that each fill a column exactly show whole: the second starts with its own header, so
    // no room is held back for one repeated (Codex on #601).
    @Test
    fun `two places that each fill a column show whole`() {
        val line = widgetLineHeight()
        // Tall enough for exactly four lines of rows: a header and three departures.
        val height = (100..400).first { widgetRowsHeight(it.dp) >= 4 * line }.dp
        fun dep(lineId: String, destination: String, s: Long) =
            Departure(lineId, lineId, "inbound", destination, null, now.plusSeconds(s), "tube")
        val stops = listOf(
            StopArrivals("940GZZLUKSX", "King's Cross St. Pancras", listOf(dep("victoria", "Brixton", 60), dep("piccadilly", "Cockfosters", 120), dep("hammersmith-city", "Barking", 180)), now, disruptions = emptyList()),
            StopArrivals("940GZZLUEUS", "Euston", listOf(dep("northern", "Morden", 90), dep("victoria", "Walthamstow Central", 150), dep("northern", "Edgware", 210)), now, disruptions = emptyList()),
        )
        val lines = listOf("victoria", "piccadilly", "hammersmith-city", "northern")
        val snapshot = DeparturesSnapshot(stops, now, lineStatuses = lines.associateWith { LineStatusCheck(LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service"), now) })
        val model = widgetModel(snapshot, now, geometry = WidgetGeometry(760.dp, height))
        // Every departure line, the Northern's two to Euston's one row among them.
        assertEquals(6, model.rows.sumOf { it.groups.size })
        assertEquals("Euston", model.rows[model.columnBreak!!].row.stopName)
    }

    @Test
    fun `two columns show what one twice as tall would`() {
        // A widget too short for every row in one column shows them all across two.
        val short = widgetModel(snapshot(), now, geometry = WidgetGeometry(380.dp, 180.dp))
        val wide = widgetModel(snapshot(), now, geometry = WidgetGeometry(760.dp, 180.dp))
        assertTrue(wide.rows.size > short.rows.size)
        assertNotNull(wide.columnBreak)
    }
}
