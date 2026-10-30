package app.stopdash.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Synthetic stops at the obviously-fake (51.5, -0.12); never a real stop. */
class StationCodeStopFinderTest {
    private val stops = listOf(
        StopLocation("910GHIGH", "Example International", 51.5, -0.12, clusterId = "910GHIGH"),
        StopLocation("910GMAIN", "Example International", 51.5, -0.12, clusterId = "910GMAIN"),
        StopLocation("490000001A", "Example Road", 51.5, -0.12, clusterId = "490G000001"),
    )
    private val finder = object : StopFinder {
        override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>) = stops
    }

    @Test
    fun `a station's records are filed under one place, anything else as it was`() {
        val placed = StationCodeStopFinder.placed(stops) { id -> "910GHIGH".takeIf { id.startsWith("910G") } }
        assertEquals(listOf("910GHIGH", "910GHIGH", "490G000001"), placed.map { it.clusterId })
        assertSame("a stop the model doesn't hold is untouched", stops[2], placed[2])
    }

    @Test
    fun `without the model, every stop is as the lookup gave it`() = runTest {
        assertSame(stops, StationCodeStopFinder(finder, model = { null }, compute = Dispatchers.Unconfined).nearbyStops(51.5, -0.12, 500))
    }

    @Test
    fun `a lookup the cache answers, kept unplaced, is placed as a fresh one is`() = runTest {
        // Kept by an older build, or while the model loaded: each record under its own cluster.
        val cache = NearbyStopsCache(clock = { java.time.Instant.parse("2026-10-30T12:00:00Z") })
        cache.put(51.5, -0.12, 500, StopFinder.DEFAULT_NEARBY_STOP_TYPES, stops)
        val unreachable = object : StopFinder {
            override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>) =
                error("answered from the cache")
        }
        val hubs = requireNotNull(
            Hubs.of(
                StationIndex(
                    listOf("910GHIGH", "910GMAIN").map { id ->
                        IndexedStation(id, "Example International", listOf("national-rail"), "HUBTWIN", 51.5, -0.12,
                            icsCode = "1009001", station = "1009001:rail", modelHub = "HUBTWIN")
                    } + IndexedStation("HUBTWIN", "Example International", listOf("national-rail")),
                    revision = "twin",
                ),
                BundledStops.read(java.io.BufferedReader(java.io.StringReader("# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=twin\nL\t1\tbus\t1"))),
            ),
        )
        val placed = StationCodeStopFinder(CachingStopFinder(unreachable, cache), model = { hubs }, compute = Dispatchers.Unconfined)
            .nearbyStops(51.5, -0.12, 500)
        assertEquals(listOf("910GHIGH", "910GHIGH", "490G000001"), placed.map { it.clusterId })
    }
}
