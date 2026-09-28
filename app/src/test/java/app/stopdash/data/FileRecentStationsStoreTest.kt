package app.stopdash.data

import app.stopdash.domain.StationMatch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Public station names and example stop ids only. */
class FileRecentStationsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
    private val stop = StationMatch("490000000001A", "Example Road", listOf("bus"))

    @Test
    fun `opens are kept newest first across a reload`() {
        val file = File(tmp.root, "recent-stations.json")
        FileRecentStationsStore(file).add(oxford)
        FileRecentStationsStore(file).add(stop)
        FileRecentStationsStore(file).add(oxford)
        assertEquals(listOf(oxford, stop), FileRecentStationsStore(file).load())
    }

    @Test
    fun `a cleared stop stays gone across a reload`() {
        val file = File(tmp.root, "recent-stations.json")
        FileRecentStationsStore(file).add(oxford)
        FileRecentStationsStore(file).add(stop)
        FileRecentStationsStore(file).remove(oxford.id)
        assertEquals(listOf(stop), FileRecentStationsStore(file).load())
    }

    @Test
    fun `a clear that can't be written says so`() {
        // Its directory is a plain file, so nothing can be written under it.
        val file = File(tmp.newFile("not-a-directory"), "recent-stations.json")
        assertEquals(false, FileRecentStationsStore(file).remove(oxford.id))
    }

    @Test
    fun `an unparseable file loads as empty and is deleted`() {
        val file = File(tmp.root, "recent-stations.json").apply { writeText("not json") }
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<StationMatch>(), FileRecentStationsStore(file, warn = { warnings += it }).load())
        assertFalse(file.exists())
        assertTrue(warnings.single().startsWith("recent stations unparseable"))
    }

    @Test
    fun `no file is an empty list`() {
        assertEquals(emptyList<StationMatch>(), FileRecentStationsStore(File(tmp.root, "none.json")).load())
    }
}
