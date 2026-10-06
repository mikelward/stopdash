package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each line's stop nearest the rider within walking reach, which its map keeps on the page. */
class NearestByLineTest {
    // Big interchanges stand in for where the rider is.
    private val kingsCross = "940GZZLUKSX"
    private val euston = "940GZZLUEUS"
    private val waterloo = "940GZZLUWLO"
    private val distances = mapOf(kingsCross to 300.0, euston to 150.0, waterloo to 600.0)
    private val now = Instant.parse("2026-10-06T08:00:00Z")

    private fun station(id: String, vararg lines: String) = StopLocation(id, id, 0.0, 0.0, lines.map { LineRef(it, it, "tube") })

    private fun arrivals(id: String, vararg lines: String) =
        StopArrivals(id, id, lines.map { Departure(it, it, "outbound", "Somewhere", null, now.plusSeconds(120), "tube") }, now)

    @Test
    fun `each line takes its nearest stop within reach, from every stop's own data`() {
        val stops = listOf(station(kingsCross, "northern", "victoria"), station(euston, "northern"), station(waterloo, "jubilee"))
        assertEquals(mapOf("northern" to euston, "victoria" to kingsCross), NearestByLine.byLine(stops, distances))
        // A stop with no distance never counts.
        assertEquals(emptyMap<String, String>(), NearestByLine.byLine(stops, emptyMap()))
    }

    @Test
    fun `the nearer of the fetched list's and the stops' own data wins`() {
        // Euston, nearer, known only from the stops' data (its times not fetched); Waterloo beyond reach.
        val merged = NearestByLine.merged(
            listOf(arrivals(kingsCross, "victoria", "northern")),
            known = mapOf("northern" to euston, "jubilee" to waterloo),
            distances = distances,
        )
        assertEquals(mapOf("victoria" to kingsCross, "northern" to euston), merged)
        // A farther stop from the stops' data never displaces a nearer fetched one.
        assertEquals(
            mapOf("northern" to euston),
            NearestByLine.merged(listOf(arrivals(euston, "northern")), mapOf("northern" to kingsCross), distances),
        )
    }

    @Test
    fun `a list with no position keeps none`() {
        assertEquals(emptyMap<String, String>(), NearestByLine.merged(listOf(arrivals(kingsCross, "victoria")), mapOf("northern" to euston), emptyMap()))
    }
}
