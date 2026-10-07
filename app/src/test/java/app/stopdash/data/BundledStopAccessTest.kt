package app.stopdash.data

import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.linksOf
import app.stopdash.domain.stopAccessFor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A station's step-free access on its details, worked out from the real bundled station index and
 * step-free table together, so the policy is checked against how TfL actually files the stations
 * (Codex on #678). Public interchanges only; a missing asset fails rather than skips.
 */
class BundledStopAccessTest {
    private val indexAsset = File("src/main/assets/stations/station_index.json")
    private val tableAsset = File("src/main/assets/stations/step_free.json")

    private fun access(stopId: String, lineId: String?, mode: String): StepFreeLevel? {
        assertTrue("bundled station index is missing", indexAsset.exists())
        assertTrue("bundled step-free table is missing", tableAsset.exists())
        val index = StationIndexStore.parse(indexAsset.readText())
        val table = StepFreeStore.parse(tableAsset.readText())
        return stopAccessFor(table, emptySet(), stopId, index.linksOf(stopId), lineId, mode).level
    }

    @Test
    fun `a tube station reads its own lines`() {
        assertEquals(StepFreeLevel.LEVEL, access("940GZZLUKSX", "victoria", "tube"))
        // Off the line, the level every line through it meets.
        assertEquals(StepFreeLevel.LEVEL, access("940GZZLUKSX", null, "tube"))
        assertEquals(StepFreeLevel.NONE, access("940GZZLUEUS", "victoria", "tube"))
    }

    @Test
    fun `a National Rail operator takes the station's National Rail platforms`() {
        assertEquals(StepFreeLevel.RAMP, access("910GKNGX", "great-northern", "national-rail"))
    }

    @Test
    fun `a station under two ids answers only where every id serving the line is described`() {
        // St Pancras's high-speed id carries the table's entry; the main id, which also serves Southeastern
        // and alone serves East Midlands, carries none: unknown, never the other id's platforms.
        assertEquals(null, access("910GSTPX", "east-midlands-railway", "national-rail"))
        assertEquals(null, access("910GSTPADOM", "southeastern", "national-rail"))
    }
}
