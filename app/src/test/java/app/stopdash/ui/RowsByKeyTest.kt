package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * An open route page finds its row by its identity through an index worked out with the rows ([RowsByKey]),
 * never by walking them in composition (Codex, #854).
 */
class RowsByKeyTest {
    private val now: Instant = Instant.parse("2026-10-15T08:00:00Z")

    private fun row(platform: String, destination: String) = DepartureRow(
        stopId = "910GEXAMPLE",
        stopName = "Example",
        lineId = "great-northern",
        lineName = "Great Northern",
        direction = "",
        directionKey = platform,
        platform = platform,
        destination = destination,
        mode = "national-rail",
        upcoming = listOf(Departure("great-northern", "Great Northern", "", destination, platform, now.plusSeconds(300), "national-rail")),
        fetchedAt = now,
    )

    @Test
    fun `each row is found by its identity`() {
        val one = row("Platform 1", "Far")
        val two = row("Platform 2", "Near")
        val index = RowsByKey.of(listOf(one, two))
        assertSame(one, index[one.detailKey()])
        assertSame(two, index[two.detailKey()])
    }

    @Test
    fun `the first of two rows with one identity is found, as the list has them`() {
        val first = row("Platform 1", "Far")
        val second = row("Platform 1", "Near")
        assertSame(first, RowsByKey.of(listOf(first, second))[first.detailKey()])
    }

    @Test
    fun `a row not among them is not found`() {
        assertNull(RowsByKey.of(listOf(row("Platform 1", "Far")))[row("Platform 3", "Far").detailKey()])
        assertNull(RowsByKey.NONE[row("Platform 1", "Far").detailKey()])
    }
}
