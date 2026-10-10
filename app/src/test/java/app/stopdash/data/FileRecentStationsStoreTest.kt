package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.SearchEntry
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
    fun `an interchange's station name is kept apart from the interchange, with its modes`() {
        val file = File(tmp.root, "recent-stations.json")
        val hub = StationMatch("HUBKGX", "King's Cross & St Pancras International", listOf("national-rail", "tube"))
        val stPancras = StationMatch("HUBKGX", "St Pancras International", listOf("national-rail"), lead = listOf("national-rail"))
        FileRecentStationsStore(file).add(hub)
        FileRecentStationsStore(file).add(stPancras)
        assertEquals(listOf(stPancras, hub), FileRecentStationsStore(file).load())
    }

    @Test
    fun `a removal comes off across a reload, and one that can't be saved says so`() {
        val file = File(tmp.root, "recent-stations.json")
        FileRecentStationsStore(file).add(oxford)
        FileRecentStationsStore(file).add(stop)
        assertTrue(FileRecentStationsStore(file).remove(SearchEntry.Stop(oxford)))
        assertEquals(listOf(stop), FileRecentStationsStore(file).load())
        // The temp file can't be written (a directory stands in its place): the list stays as it was.
        File(file.path + ".tmp").apply { mkdir(); File(this, "x").writeText("") }
        assertFalse(FileRecentStationsStore(file).remove(SearchEntry.Stop(stop)))
        assertEquals(listOf(stop), FileRecentStationsStore(file).load())
    }

    @Test
    fun `a picked place is kept among the stops, in the order picked, across a reload`() {
        val file = File(tmp.root, "recent-destinations.json")
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        val postcode = PlaceHit("SW1A 1AA", Coordinates(51.501, -0.141), PlaceKind.POSTCODE)
        FileRecentStationsStore(file).add(oxford)
        FileRecentStationsStore(file).addPlace(gallery)
        FileRecentStationsStore(file).add(stop)
        FileRecentStationsStore(file).addPlace(postcode)
        assertEquals(
            listOf(SearchEntry.Place(postcode), SearchEntry.Stop(stop), SearchEntry.Place(gallery), SearchEntry.Stop(oxford)),
            FileRecentStationsStore(file).loadPicks(),
        )
        // The stations alone, as a search that lists no places reads them.
        assertEquals(listOf(stop, oxford), FileRecentStationsStore(file).load())
    }

    @Test
    fun `a list written before places were kept reads back as its stations`() {
        val file = File(tmp.root, "recent-stations.json")
            .apply { writeText("""{"stations":[{"id":"940GZZLUOXC","name":"Oxford Circus","modes":["tube"]}]}""") }
        assertEquals(listOf(SearchEntry.Stop(oxford)), FileRecentStationsStore(file).loadPicks())
        // And a place of a kind this build doesn't know reads as a place.
        file.writeText("""{"stations":[{"id":"","name":"Example Gallery","latitude":51.5,"longitude":-0.12,"placeKind":"LANDMARK"}]}""")
        assertEquals(
            listOf(SearchEntry.Place(PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE))),
            FileRecentStationsStore(file).loadPicks(),
        )
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
