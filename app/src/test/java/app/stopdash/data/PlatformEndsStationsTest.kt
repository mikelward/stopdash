package app.stopdash.data

import app.stopdash.domain.PlatformEnds
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every station the where-to-stand table names is a real one, by the id TfL gives it, as the bundled stops
 * (`assets/stations/stops.tsv`) list it: an entry under a wrong id never matches a ride, and says nothing at a
 * station it was read for (Codex, #1268: Caledonian Road under 940GZZLUCRD, not 940GZZLUCAR).
 */
class PlatformEndsStationsTest {
    private val asset = File("src/main/assets/stations/stops.tsv")

    @Test
    fun `every station the table names is a bundled stop`() {
        assertTrue("bundled stops are missing; run scripts/build_stops.py", asset.exists())
        val known = asset.readLines().filter { it.startsWith("S\t") }.map { it.split('\t')[1] }.toSet()
        val named = PlatformEnds.TABLE.flatMap { p ->
            listOf(p.stationId, p.endA, p.endB, p.arriving.orEmpty()) + p.ways.keys.map { it.substringAfter('>', "") }
        } + PlatformEnds.EXITS.keys
        assertEquals(emptyList<String>(), named.filter { it.isNotBlank() && it !in known }.distinct())
    }
}
