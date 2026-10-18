package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusStopIndexTest {
    // Synthetic stops and positions only (AGENTS.md *Privacy*).
    private fun stop(id: String, name: String, lat: Double = 51.5, lon: Double = -0.12) =
        StopLocation(id = id, name = name, latitude = lat, longitude = lon, lines = listOf(LineRef("1", "1", "bus")))

    private fun index(vararg stops: StopLocation) = BusStopIndex.of(stops.toList())

    @Test
    fun `finds stops by name with TfL's id, the bus mode, their position, and the stop level`() {
        val stops = index(stop("4900A", "Zeta Road"), stop("4900B", "Alpha Lane"))
        assertEquals(2, stops.size)
        assertEquals(listOf(StationMatch("4900A", "Zeta Road", listOf("bus"), 51.5, -0.12, level = Pick.Level.STOP)), stops.search("zeta"))
    }

    @Test
    fun `a name's stops come together, better matches first, then shorter names`() {
        val stops = index(
            stop("4900C", "Lower Zeta Road"),
            stop("4900B", "Zeta Road Garage"),
            stop("4900D", "Zeta Road"),
            stop("4900A", "Zeta Road"),
        )
        assertEquals(listOf("4900A", "4900D", "4900B", "4900C"), stops.search("zeta").map { it.id })
    }

    @Test
    fun `a name's stops nearest where the trip starts come first, and the limit keeps those`() {
        // Synthetic stops of one name, the farthest first in id order.
        val stops = index(stop("4900A", "Zeta Road", 51.55), stop("4900B", "Zeta Road", 51.52), stop("4900C", "Zeta Road", 51.501))
        val start = Coordinates(51.5, -0.12)
        assertEquals(listOf("4900C", "4900B", "4900A"), stops.search("zeta", near = start).map { it.id })
        assertEquals(listOf("4900C"), stops.search("zeta", limit = 1, near = start).map { it.id })
        assertEquals(listOf("4900A", "4900B", "4900C"), stops.search("zeta").map { it.id })
    }

    @Test
    fun `the limit caps the stops, and a short query finds none`() {
        val stops = index(*Array(5) { stop("4900$it", "Zeta Road") })
        assertEquals(3, stops.search("zeta", limit = 3).size)
        assertTrue(stops.search("z").isEmpty())
    }

    @Test
    fun `a checkpoint that throws stops a scan`() {
        val stops = index(*Array(BusStopIndex.CHECKPOINT_EVERY * 2) { stop("4900$it", "Zeta Road $it") })
        val thrown = runCatching { stops.search("zeta", checkpoint = { throw IllegalStateException("superseded") }) }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
    }
}
