package app.stopdash.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StationBundleTest {
    private fun asset(name: String) = listOf(File("../app/src/main/assets/stations/$name"), File("app/src/main/assets/stations/$name"))
        .first { it.exists() }

    private val stops by lazy { asset("stops.tsv").bufferedReader().use { BundledStops.read(it) } }
    private val index by lazy { StationIndexFile.parse(asset("station_index.json").readText()) }

    private val hubs by lazy { Hubs.of(index, stops) ?: error("the bundled files build no model") }

    private fun bytesOf(stops: BundledStops, hubs: Hubs = this.hubs, index: StationIndex = this.index) =
        ByteArrayOutputStream().also { StationBundle.write(index, stops, hubs, it) }.toByteArray()

    @Test
    fun `the bundled files read back from the bundle as they are built in place`() {
        val read = StationBundle.read(ByteArrayInputStream(bytesOf(stops)))
        assertTrue(read.stops.sameAs(stops))
        assertTrue(read.index.stations.size > 1_000)
        assertEquals(index.stations, read.index.stations)
        // Each station's maps in the file's order, which screens list its lines in (Map.equals ignores order).
        assertTrue(index.stations.any { it.lines.size > 1 })
        assertEquals(
            index.stations.map { listOf(it.lines.keys, it.routeEnds.keys, it.terminusEnds.keys).map(Set<String>::toList) },
            read.index.stations.map { listOf(it.lines.keys, it.routeEnds.keys, it.terminusEnds.keys).map(Set<String>::toList) },
        )
        assertEquals(index.lineNames.keys.toList(), read.index.lineNames.keys.toList())
        assertEquals(index.lineNames, read.index.lineNames)
        assertEquals(index.revision, read.index.revision)
        assertEquals(index.skippedEntries, read.index.skippedEntries)
        val readHubs = read.hubs
        assertTrue(readHubs.sameAs(hubs))
        // Answers too, through the index lookups the read model draws again: King's Cross, a hub
        // (AGENTS.md *Privacy*), by a platform's id and by its station.
        for (id in listOf("9400ZZLUKSX3", "940GZZLUKSX", "HUBKGX")) {
            assertEquals(id, hubs.hubOf(id), readHubs.hubOf(id))
            assertEquals(id, hubs.stationOf(id), readHubs.stationOf(id))
            assertEquals(id, hubs.lookupIdsOf(id), readHubs.lookupIdsOf(id))
        }
        assertEquals(stops.nearby(51.5308, -0.1238, 500, types), read.stops.nearby(51.5308, -0.1238, 500, types))
    }

    @Test
    fun `an index and stops from different revisions are refused, written or read`() {
        val other = StationIndex(index.stations, revision = "other")
        // Never written: the build fails rather than ship a bundle the app refuses (Codex on #1002).
        assertThrows(IllegalArgumentException::class.java) { bytesOf(stops, index = other) }
        // Nor read, should one be made by hand.
        val handMade = ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).apply { writeInt(0x53444231); writeInt(StationBundle.FORMAT_VERSION); writeUTF("other") }
            out.write(bytesOf(stops).drop(4 + 4 + 2 + stops.revision.toByteArray().size).toByteArray())
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { StationBundle.read(ByteArrayInputStream(handMade)) }
    }

    @Test
    fun `positions come back exactly, and one finer than a millionth of a degree is refused`() {
        val placed = StationIndex(listOf(IndexedStation("X", "Example", latitude = 51.123456, longitude = -0.000001)), revision = stops.revision)
        val read = StationBundle.read(ByteArrayInputStream(bytesOf(stops, index = placed)))
        assertEquals(51.123456, read.index.stations.single().latitude!!, 0.0)
        assertEquals(-0.000001, read.index.stations.single().longitude!!, 0.0)
        val finer = StationIndex(listOf(IndexedStation("X", "Example", latitude = 51.1234567, longitude = 0.0)), revision = stops.revision)
        assertThrows(IllegalArgumentException::class.java) { bytesOf(stops, index = finer) }
    }

    @Test
    fun `a bundle with a malformed count is refused, not read as a negative size`() {
        val bytes = bytesOf(stops)
        // The string table's size, just after the header (magic, format, revision): made negative.
        val header = 4 + 4 + 2 + stops.revision.toByteArray().size
        bytes[header] = 0xff.toByte()
        assertThrows(IllegalArgumentException::class.java) { StationBundle.read(ByteArrayInputStream(bytes)) }
        // A count whose varint runs past an int's sign: refused too.
        val huge = ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).apply { writeInt(0x53444231); writeInt(StationBundle.FORMAT_VERSION); writeUTF(stops.revision); writeInt(0) }
            out.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f))
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { StationBundle.read(ByteArrayInputStream(huge)) }
    }

    @Test
    fun `a file that isn't a bundle is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            StationBundle.read(ByteArrayInputStream("# stopdash stops v2".toByteArray()))
        }
    }

    @Test
    fun `the build's step writes the bundle the app reads`() {
        val out = File.createTempFile("station_bundle", ".bin")
        try {
            assertNull(StationBundle.build(asset("stops.tsv"), asset("station_index.json"), out))
            val read = out.inputStream().use { StationBundle.read(it) }
            assertTrue(read.stops.sameAs(stops))
            assertTrue(read.hubs.sameAs(hubs))
        } finally {
            out.delete()
        }
    }

    @Test
    fun `the build fails on files that build no station model, writing nothing`() {
        val dir = createTempDirectory("station_bundle").toFile()
        try {
            val out = File(dir, "station_bundle.bin")
            // Stops with a record that can't be read: Hubs.of refuses to vouch for the rest.
            val damaged = File(dir, "stops.tsv")
            damaged.writeText(asset("stops.tsv").readText() + "S\tunreadable\n")
            val why = StationBundle.build(damaged, asset("station_index.json"), out)
            assertTrue(why.orEmpty(), why.orEmpty().startsWith("no station model"))
            assertFalse(out.exists())
            // An index from another refresh than the stops (Codex on #1002).
            val other = File(dir, "station_index.json")
            other.writeText(asset("station_index.json").readText().replaceFirst("\"revision\":\"", "\"revision\":\"other"))
            val apart = StationBundle.build(asset("stops.tsv"), other, out)
            assertTrue(apart.orEmpty(), apart.orEmpty().contains("different refreshes"))
            assertFalse(out.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private val types = listOf("NaptanMetroStation", "NaptanRailStation", "NaptanPublicBusCoachTram", "NaptanFerryPort")
}
