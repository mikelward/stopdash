package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPlacesTest {
    // Synthetic ids and coordinates only (SPEC *Privacy*); 0.001° of latitude is ~111 m.
    private fun stop(id: String, name: String, lat: Double?, lon: Double? = 0.0, hub: String = "", area: String = "") =
        PlaceStop(id, name, lat, lon, hub, area)

    @Test
    fun `same-named stops within the radius are one place, and farther ones are not`() {
        val places = StopPlaces.group(
            listOf(
                stop("a1", "Example Gardens", 0.0),
                // ~111 m away, under another stop area: still the same place.
                stop("a2", "Example Gardens", 0.001, area = "490GOTHER"),
                // ~1.1 km away: another place of the same name.
                stop("far", "Example Gardens", 0.01),
            ),
        )
        assertEquals(places["a1"], places["a2"])
        assertNotEquals(places["a1"], places["far"])
    }

    @Test
    fun `a station's bus stops join it, one named after it and one with a road added`() {
        val places = StopPlaces.group(
            listOf(
                stop("940GZZLUXXX", "Example Underground Station", 0.0, hub = "HUBXXX"),
                stop("490P1", "Example Station", 0.0005),
                stop("490P2", "Example / High Road", 0.001),
                stop("490P3", "High Road", 0.0015),
            ),
        )
        val station = places.getValue("940GZZLUXXX")
        assertEquals("HUBXXX", station.id)
        assertEquals("Example", station.name)
        assertEquals(station, places["490P1"])
        assertEquals(station, places["490P2"])
        // The road's own stop, ~55 m on: a part of "Example / High Road", so the same junction.
        assertEquals(station, places["490P3"])
    }

    @Test
    fun `a TfL interchange and a stop area join their members whatever the names`() {
        val places = StopPlaces.group(
            listOf(
                stop("s1", "One Name", null, hub = "HUBABC"),
                stop("s2", "Other Name", null, hub = "HUBABC"),
                stop("p1", "North Side", null, area = "490GPAIR"),
                stop("p2", "South Side", null, area = "490GPAIR"),
            ),
        )
        assertEquals(StopPlace("HUBABC", "One Name"), places["s1"])
        assertEquals(places["s1"], places["s2"])
        assertEquals("490GPAIR", places.getValue("p1").id)
        assertEquals(places["p1"], places["p2"])
    }

    @Test
    fun `a stop without a position joins only by interchange or area`() {
        val places = StopPlaces.group(listOf(stop("x", "Example Gardens", null), stop("y", "Example Gardens", 0.0)))
        assertNotEquals(places["x"], places["y"])
        assertEquals("x", places.getValue("x").id)
    }

    @Test
    fun `a stop given twice keeps what either source knew`() {
        val places = StopPlaces.group(
            listOf(
                stop("a", "Example Gardens", null),
                stop("a", "", 0.0, area = "490GPAIR"),
                stop("b", "Example Gardens", 0.001),
            ),
        )
        assertEquals(places["a"], places["b"])
        assertEquals("490GPAIR", places.getValue("b").id)
    }

    @Test
    fun `the name index joins exactly the pairs a comparison of every pair would`() {
        // Synthetic names and positions: whole names, "/"-joined parts, and near and far copies.
        val names = listOf("Alpha", "Beta", "Alpha / Beta", "Gamma Station", "Gamma", "Beta / Delta", "Delta", "Alphabet")
        val stops = (0 until 60).map { i -> stop("s$i", names[i % names.size], (i % 7) * 0.0012) }
        val places = StopPlaces.group(stops)
        // Brute force: join every same-named pair within the radius, transitively.
        val parent = IntArray(stops.size) { it }
        fun root(i: Int): Int = if (parent[i] == i) i else root(parent[i]).also { parent[i] = it }
        for (i in stops.indices) for (j in i + 1 until stops.size) {
            val a = stops[i]
            val b = stops[j]
            if (StopPlaces.sameName(a.name, b.name) &&
                NearestStops.distanceMeters(a.latitude!!, 0.0, b.latitude!!, 0.0) <= StopPlaces.RADIUS_METERS
            ) parent[root(i)] = root(j)
        }
        for (i in stops.indices) for (j in stops.indices) {
            assertEquals("s$i ~ s$j", root(i) == root(j), places[stops[i].id] == places[stops[j].id])
        }
    }

    @Test
    fun `names match whole, cleaned, or as one part of a joined name`() {
        assertTrue(StopPlaces.sameName("Example Station", "Example"))
        assertTrue(StopPlaces.sameName("Example", "example / High Road"))
        assertTrue(StopPlaces.sameName("St. Mary's", "St Marys"))
        assertFalse(StopPlaces.sameName("Church Street", "Church Road"))
        assertFalse(StopPlaces.sameName("Example", "Example Park"))
        assertFalse(StopPlaces.sameName("", "Example"))
    }
}
