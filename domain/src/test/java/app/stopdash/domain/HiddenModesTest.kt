package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class HiddenModesTest {
    private fun stop(id: String, vararg modes: String) = StopLocation(
        id = id,
        name = id,
        latitude = 0.0,
        longitude = 0.0,
        lines = modes.map { LineRef("$it-$id", it, it) },
    )

    @Test
    fun `a stop serving only hidden modes is dropped, a mixed one keeps its other lines`() {
        val kept = HiddenModes.stops(
            listOf(stop("busOnly", "bus"), stop("mixed", "overground", "national-rail"), stop("tube", "tube")),
            setOf("bus", "national-rail"),
        )
        assertEquals(listOf("mixed", "tube"), kept.map { it.id })
        assertEquals(listOf("overground"), kept.first().lines.map { it.mode })
    }

    @Test
    fun `a stop with no lines has no mode to hide, and nothing hidden changes nothing`() {
        val bare = stop("bare")
        assertEquals(listOf(bare), HiddenModes.stops(listOf(bare), setOf("bus")))
        val stops = listOf(stop("a", "bus"))
        assertTrue(HiddenModes.stops(stops, emptySet()) === stops)
    }

    @Test
    fun `a hidden mode's rows go, a stop closure stays, and modes match whatever their case`() {
        val now = Instant.EPOCH
        fun row(mode: String, closure: String? = null) = DepartureRow(
            stopId = "490000001A", stopName = "Example", lineId = mode, lineName = mode, direction = "",
            directionKey = "", destination = "", mode = mode, upcoming = emptyList(), fetchedAt = now,
            stopDisruption = closure,
        )
        val rows = listOf(row("bus"), row("tube"), row("bus", closure = "Stop closed"))
        assertEquals(listOf("tube", "bus"), HiddenModes.rows(rows, setOf("BUS")).map { it.mode })
        assertEquals("Stop closed", HiddenModes.rows(rows, setOf("bus")).last().stopDisruption)
    }

    @Test
    fun `a hidden line goes by itself, its mode's other lines stay`() {
        val now = Instant.EPOCH
        fun row(lineId: String, mode: String) = DepartureRow(
            stopId = "940GZZLUXXX", stopName = "Example", lineId = lineId, lineName = lineId, direction = "",
            directionKey = "", destination = "", mode = mode, upcoming = emptyList(), fetchedAt = now,
        )
        val hidden = setOf(HiddenModes.lineKey("Northern", "Northern line"))
        val rows = listOf(row("northern", "tube"), row("victoria", "tube"), row("northern", "bus"))
        // Matched by line id, whatever its case; the mode isn't hidden, so the other tube line stays.
        assertEquals(listOf("victoria"), HiddenModes.rows(rows, hidden).map { it.lineId })
        assertTrue(HiddenModes.isHidden(LineRef("northern", "Northern", "tube"), hidden))
        assertFalse(HiddenModes.isHidden("tube", hidden))
        assertFalse(HiddenModes.isLineHidden("", hidden))
        assertEquals(listOf("Northern line"), HiddenModes.hiddenLineLabels(hidden))
    }

    @Test
    fun `a stop serving only hidden lines is dropped, as one serving only hidden modes is`() {
        val stops = listOf(stop("busOnly", "bus"), stop("mixed", "bus", "tram"))
        val hidden = setOf(HiddenModes.lineKey("bus-busOnly", "1"), HiddenModes.lineKey("bus-mixed", "2"))
        val kept = HiddenModes.stops(stops, hidden)
        assertEquals(listOf("mixed"), kept.map { it.id })
        assertEquals(listOf("tram"), kept.single().lines.map { it.mode })
    }

    @Test
    fun `a West Midlands Trains line hidden before the rename is named London Northwestern`() {
        // Its entry was saved with TfL's parent-company name; the banner names it as its pill reads.
        val hidden = setOf(HiddenModes.lineKey("west-midlands-trains", "West Midlands Trains"))
        assertEquals(listOf("London Northwestern Railway"), HiddenModes.hiddenLineLabels(hidden))
        assertEquals(true, HiddenModes.isLineHidden("west-midlands-trains", hidden))
    }
}
