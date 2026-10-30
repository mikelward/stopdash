package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StationRowsTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private fun train(destination: String, minutes: Long, vehicle: String = "", line: String = "southeastern") =
        Departure(line, line, "outbound", destination, null, now.plusSeconds(minutes * 60), "national-rail", vehicleId = vehicle)

    private fun row(stopId: String, line: String, trains: List<Departure>, mode: String = "national-rail", feed: RailFeed? = null) =
        DepartureRow(
            stopId = stopId, stopName = "Example", lineId = line, lineName = line, direction = "outbound",
            directionKey = if (trains.isEmpty()) STATUS_DIRECTION_KEY else "outbound", destination = trains.firstOrNull()?.destination.orEmpty(),
            mode = mode, upcoming = trains, fetchedAt = now, railFeed = feed,
        )

    private val codes = mapOf("910GMAIN" to "1009001", "910GHIGH" to "1009001", "910GOTHER" to "1009002")

    @Test
    fun `a record's empty row for a line another record of its station shows goes`() {
        // The high-speed record nearest: its line's board is under the main record, so its own row is empty.
        val empty = row("910GHIGH", "southeastern", emptyList())
        val trains = row("910GMAIN", "southeastern", listOf(train("Faraway", 8)))
        val merged = StationRows.merged(listOf(empty, trains), codes::get)
        assertEquals(listOf("910GMAIN"), merged.map { it.stopId })
        assertEquals("1009001", merged.single().stationCode)
    }

    @Test
    fun `a line only one record lists keeps its empty row, so its disruption still shows`() {
        val thameslink = row("910GMAIN", "thameslink", emptyList(), feed = RailFeed.UNAVAILABLE)
        val southeastern = row("910GHIGH", "southeastern", listOf(train("Faraway", 8)))
        assertEquals(listOf("910GMAIN", "910GHIGH"), StationRows.merged(listOf(thameslink, southeastern), codes::get).map { it.stopId })
    }

    @Test
    fun `two records' empty rows for one line are one`() {
        val first = row("910GHIGH", "southeastern", emptyList())
        val second = row("910GMAIN", "southeastern", emptyList())
        assertEquals(listOf("910GHIGH"), StationRows.merged(listOf(first, second), codes::get).map { it.stopId })
    }

    @Test
    fun `a train two records list shows once, under the record nearest`() {
        val a = row("910GHIGH", "elizabeth", listOf(train("Abbey Wood", 4, "v1", "elizabeth"), train("Shenfield", 7, line = "elizabeth")), mode = "elizabeth-line")
        val b = row("910GMAIN", "elizabeth", listOf(train("Abbey Wood", 4, "v1", "elizabeth"), train("Shenfield", 7, line = "elizabeth"), train("Abbey Wood", 12, "v2", "elizabeth")), mode = "elizabeth-line")
        val merged = StationRows.merged(listOf(a, b), codes::get)
        assertEquals(listOf(2, 1), merged.map { it.upcoming.size })
        assertEquals("v2", merged[1].upcoming.single().vehicleId)
        // A row whose every train the other lists goes.
        assertEquals(listOf("910GHIGH"), StationRows.merged(listOf(a, a.copy(stopId = "910GMAIN")), codes::get).map { it.stopId })
    }

    @Test
    fun `a starred row keeps its trains, a nearer record's copy of them giving way`() {
        val near = row("910GHIGH", "elizabeth", listOf(train("Abbey Wood", 4, "v1", "elizabeth")), mode = "elizabeth-line")
        val starredRow = row("910GMAIN", "elizabeth", listOf(train("Abbey Wood", 4, "v1", "elizabeth")), mode = "elizabeth-line")
        val star = StarredRow.of(starredRow)
        val merged = StationRows.merged(listOf(near, starredRow), codes::get) { StarredRow.of(it) == star }
        assertEquals(listOf("910GMAIN"), merged.map { it.stopId })
        // A starred empty row stays too, beside the record whose trains say its line.
        val empty = row("910GHIGH", "southeastern", emptyList())
        val trains = row("910GMAIN", "southeastern", listOf(train("Faraway", 8)))
        assertEquals(
            listOf("910GHIGH", "910GMAIN"),
            StationRows.merged(listOf(empty, trains), codes::get) { it.stopId == "910GHIGH" }.map { it.stopId },
        )
    }

    @Test
    fun `other stations, and stops the model doesn't hold, are left as they are`() {
        val here = row("910GMAIN", "southeastern", listOf(train("Faraway", 8)))
        val other = row("910GOTHER", "southeastern", emptyList())
        val bus = row("490000001A", "19", emptyList(), mode = "bus")
        val merged = StationRows.merged(listOf(here, other, bus), codes::get)
        assertEquals(listOf("910GMAIN", "910GOTHER", "490000001A"), merged.map { it.stopId })
        assertSame(bus, merged[2])
        assertEquals("", merged[2].stationCode)
    }
}
