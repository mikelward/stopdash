package app.stopdash.data

import app.stopdash.domain.BundledStops
import app.stopdash.domain.BusStopIndex
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real bundled stops (`assets/stations/stops.tsv`, built by `scripts/build_stops.py`) give the stop
 * search London's bus stops, and find one at a big interchange quickly. A missing asset fails rather
 * than skips: the search would quietly find no bus stop at all.
 */
class BundledBusStopsTest {
    private val asset = File("src/main/assets/stations/stops.tsv")

    @Test
    fun `the bus stops are all there, and Tottenham Court Road's are found`() {
        assertTrue("bundled stops are missing; run scripts/build_stops.py", asset.exists())
        val stops = BusStopIndex.of(asset.bufferedReader().use { BundledStops.read(it) }.busStops())
        assertTrue("only ${stops.size} bus stops", stops.size >= 15_000)
        val started = System.nanoTime()
        val found = stops.search("tottenham court road")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.name.startsWith("Tottenham Court Road") && it.modes == listOf("bus") && it.latitude != null })
        // A loose ceiling, to catch a search that scans far more than it should, not to time it.
        assertTrue("search took $millis ms", millis < 2_000)
    }
}
