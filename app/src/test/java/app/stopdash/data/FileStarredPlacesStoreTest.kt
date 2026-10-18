package app.stopdash.data

import app.stopdash.domain.Pick
import app.stopdash.domain.StationMatch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Example stop ids and names only. */
class FileStarredPlacesStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val area = StationMatch("490G00000001", "Example Road", listOf("bus"))
    private val station = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))

    @Test
    fun `a reconcile records the new places and keeps only the starred stops`() {
        val file = File(tmp.root, "starred-places.json")
        FileStarredPlacesStore(file).reconcile(setOf("490000000001A"), mapOf("490000000001A" to area))
        FileStarredPlacesStore(file).reconcile(setOf("490000000001A", "940GZZLUOXC"), mapOf("940GZZLUOXC" to station))
        assertEquals(mapOf("490000000001A" to area, "940GZZLUOXC" to station), FileStarredPlacesStore(file).load())
        FileStarredPlacesStore(file).reconcile(setOf("940GZZLUOXC"))
        assertEquals(mapOf("940GZZLUOXC" to station), FileStarredPlacesStore(file).load())
        // A place for a stop no longer starred is not recorded.
        FileStarredPlacesStore(file).reconcile(setOf("940GZZLUOXC"), mapOf("490000000001A" to area))
        assertEquals(mapOf("940GZZLUOXC" to station), FileStarredPlacesStore(file).load())
    }

    @Test
    fun `an unparseable file loads as empty and is deleted`() {
        val file = File(tmp.root, "starred-places.json").apply { writeText("{") }
        assertEquals(emptyMap<String, StationMatch>(), FileStarredPlacesStore(file).load())
        assertFalse(file.exists())
    }

    @Test
    fun `a file says its version, and one from before versions reads as the first`() {
        val file = File(tmp.root, "starred-places.json")
        FileStarredPlacesStore(file).reconcile(setOf("940GZZLUOXC"), mapOf("940GZZLUOXC" to station))
        assertTrue(file.readText().contains("\"version\":2"))
        file.writeText("""{"places":[{"stopId":"940GZZLUOXC","id":"940GZZLUOXC","name":"Oxford Circus","modes":["tube"]}]}""")
        assertEquals(mapOf("940GZZLUOXC" to station), FileStarredPlacesStore(file).load())
    }

    @Test
    fun `a newer build's file loads as empty and is never written over`() {
        // Its place in a shape this build can't read.
        val newer = """{"version":3,"places":[{"stopId":"940GZZLUOXC","id":"940GZZLUOXC","name":{"en":"Oxford Circus"}}]}"""
        val file = File(tmp.root, "starred-places.json").apply { writeText(newer) }
        assertEquals(emptyMap<String, StationMatch>(), FileStarredPlacesStore(file).load())
        FileStarredPlacesStore(file).reconcile(setOf("490000000001A"), mapOf("490000000001A" to area))
        assertEquals(newer, file.readText())
    }

    @Test
    fun `the level a place was picked at is kept, and absent from a file written before levels`() {
        val file = File(tmp.root, "starred-places.json")
        val picked = station.copy(level = Pick.Level.STATION)
        FileStarredPlacesStore(file).reconcile(setOf("940GZZLUOXC"), mapOf("940GZZLUOXC" to picked))
        assertEquals(mapOf("940GZZLUOXC" to picked), FileStarredPlacesStore(file).load())
        file.writeText("""{"version":1,"places":[{"stopId":"940GZZLUOXC","id":"940GZZLUOXC","name":"Oxford Circus","modes":["tube"]}]}""")
        assertEquals(mapOf("940GZZLUOXC" to station), FileStarredPlacesStore(file).load())
    }
}
