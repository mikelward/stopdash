package app.stopdash.domain

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops around the obviously-fake (51.5, -0.12); never a real fix. */
class BundledStopsTest {
    private val types = StopFinder.DEFAULT_NEARBY_STOP_TYPES

    // ~0.001° of latitude is ~111 m.
    private val file = """
        # stopdash stops v1	bounds=51.25,-0.65,51.75,0.4
        L	1	bus	1
        L	n1	bus	N1
        L	victoria	tube	Victoria
        S	490000001A	B	Example Road  / High Street	51.501000	-0.120000	490G000001	HUBEXA	A	N	Example Town	1,n1
        S	490000001B	B	Example Road	51.502000	-0.120000				SW	Elsewhere	1
        S	490000002Z	B	Far Lane	51.530000	-0.120000	490G000002		->E			1
        S	940GZZLUEXA	M	Example Underground Station	51.500500	-0.120000	940GZZLUEXA	HUBEXA				victoria
        S	930GEXA	F	Example Pier	51.500200	-0.120000	930GEXA
    """.trimIndent()

    private fun read(text: String = file) = BundledStops.read(BufferedReader(StringReader(text)))

    @Test
    fun `stops in range come back nearest first, read as TfL's own answer is`() {
        val stops = read().nearby(51.5, -0.12, 500, types)
        assertEquals(listOf("930GEXA", "940GZZLUEXA", "490000001A", "490000001B"), stops.map { it.id })
        val pole = stops.first { it.id == "490000001A" }
        assertEquals(cleanStopName("Example Road  / High Street"), pole.name)
        assertEquals(listOf(LineRef("1", "1", "bus"), LineRef("n1", "N1", "bus")), pole.lines)
        assertEquals("490G000001", pole.clusterId)
        assertEquals("HUBEXA", pole.hubId)
        assertEquals("A", pole.stopLetter)
        assertEquals("N", pole.bearing)
        assertEquals("Example Town", pole.towards)
        val tube = stops.first { it.id == "940GZZLUEXA" }
        assertEquals("Example", tube.name)
        assertEquals(listOf("tube"), tube.lines.map { it.mode })
    }

    @Test
    fun `places a stop by its id and a stop pair by the middle of its stops`() {
        // 490000001B has no area of its own: its cluster is its name, which places nothing.
        val pair = read()
        assertEquals(Coordinates(51.501, -0.12), pair.positionOf("490000001A"))
        assertEquals(Coordinates(51.53, -0.12), pair.positionOf("490G000002"))
        assertEquals(Coordinates(51.5005, -0.12), pair.positionOf("940GZZLUEXA"))
        assertEquals(null, pair.positionOf("Example Road"))
        assertEquals(null, pair.positionOf("490000009X"))
    }

    @Test
    fun `a stop pair is placed between its poles`() {
        val poles = file + "\nS\t490000002Y\tB\tFar Lane\t51.532000\t-0.122000\t490G000002\t\t\t\t\t1"
        val at = read(poles).positionOf("490G000002")!!
        assertEquals(51.531, at.latitude, 1e-9)
        assertEquals(-0.121, at.longitude, 1e-9)
        // Each pole still by its own position.
        assertEquals(Coordinates(51.532, -0.122), read(poles).positionOf("490000002Y"))
    }

    @Test
    fun `the bus stops are the served ones placed inside the bounds`() {
        // A zeroed position is the file's own mistake: never listed, so it can't be saved as a favorite.
        val zeroed = "S\t490000003Z\tB\tZeta Road\t0.000000\t0.000000\t\t\t\t\t\t1"
        val bus = read(file + "\n" + zeroed).busStops()
        // The tube station and pier aren't bus stops.
        assertEquals(listOf("490000001A", "490000001B", "490000002Z"), bus.map { it.id })
    }

    @Test
    fun `a stop with no station clusters by its name, and an arrow in the letter is a bearing`() {
        val stops = read().nearby(51.5, -0.12, 5_000, types).associateBy { it.id }
        assertEquals("Example Road", stops.getValue("490000001B").clusterId)
        assertEquals("", stops.getValue("490000002Z").stopLetter)
        assertEquals("E", stops.getValue("490000002Z").bearing)
        assertTrue(stops.getValue("930GEXA").lines.isEmpty())
    }

    @Test
    fun `only the stop types asked for come back`() {
        val stops = read().nearby(51.5, -0.12, 500, listOf("NaptanMetroStation"))
        assertEquals(listOf("940GZZLUEXA"), stops.map { it.id })
    }

    @Test
    fun `a stop past the radius is left out`() {
        val ids = read().nearby(51.5, -0.12, 150, types).map { it.id }
        assertTrue("490000001A" in ids)
        assertFalse("490000001B" in ids)
    }

    @Test
    fun `a search reaching past the bundle's area isn't covered by it`() {
        val stops = read()
        assertTrue(stops.covers(51.5, -0.12, 1609))
        // Near the north edge: the circle crosses 51.75.
        assertFalse(stops.covers(51.745, -0.12, 1609))
        assertFalse(stops.covers(53.48, -2.24, 1609))
        assertTrue(stops.holds(types))
        assertFalse(stops.holds(types + "NaptanCoachBay"))
        assertFalse(BundledStops.EMPTY.covers(51.5, -0.12, 1609))
    }

    @Test
    fun `an unreadable record is left out and counted, the rest kept`() {
        val stops = read(
            file + "\n" +
                "S\tbroken\tB\tNo Position\t\t\t\t\t\t\t\t\n" +
                "S\t490000009X\tB\tUnknown Line\t51.5\t-0.12\t\t\t\t\t\tnot-a-line\n" +
                "X\twhat\n",
        )
        assertEquals(5, stops.size)
        assertEquals(3, stops.skippedLines)
    }

    @Test
    fun `another format version, or no bounds, isn't read`() {
        assertThrows(IllegalArgumentException::class.java) { read("# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\n") }
        assertThrows(IllegalArgumentException::class.java) { read("# stopdash stops v1\n") }
        assertEquals(0, read("").size)
    }

    @Test
    fun `the finder answers from the bundle, and asks TfL outside it`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "bundle-worker") }.asCoroutineDispatcher()
        try {
            val asked = mutableListOf<Pair<Double, Double>>()
            val tfl = object : StopFinder {
                override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
                    asked += latitude to longitude
                    return listOf(StopLocation("tfl", "From TfL", latitude, longitude))
                }
            }
            val bundle = read()
            val finder = BundledStopFinder(tfl, bundled = { bundle }, compute = worker)
            assertEquals(4, finder.nearbyStops(51.5, -0.12, 500).size)
            assertTrue(asked.isEmpty())
            assertEquals(listOf("tfl"), finder.nearbyStops(53.48, -2.24, 500).map { it.id })
            assertEquals(listOf(53.48 to -2.24), asked)
            // A stop type the bundle doesn't hold is TfL's too.
            finder.nearbyStops(51.5, -0.12, 500, listOf("NaptanCoachBay"))
            assertEquals(2, asked.size)
        } finally {
            worker.close()
        }
    }

    @Test
    fun `the search runs on the compute dispatcher, not the caller's thread`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "bundle-worker") }.asCoroutineDispatcher()
        try {
            var ranOn: String? = null
            val finder = BundledStopFinder(
                delegate = object : StopFinder {
                    override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>) =
                        emptyList<StopLocation>()
                },
                bundled = { read() },
                compute = object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                        worker.dispatch(context, Runnable { ranOn = Thread.currentThread().name; block.run() })
                    }
                },
            )
            assertEquals(4, finder.nearbyStops(51.5, -0.12, 500).size)
            assertTrue(ranOn.orEmpty().startsWith("bundle-worker"))
        } finally {
            worker.close()
        }
    }

    @Test
    fun `an empty or unreadable bundle leaves the search to TfL`() = runBlocking {
        var asked = 0
        val tfl = object : StopFinder {
            override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
                asked++
                return emptyList()
            }
        }
        val warnings = mutableListOf<String>()
        BundledStopFinder(tfl, bundled = { BundledStops.EMPTY }).nearbyStops(51.5, -0.12, 500)
        BundledStopFinder(tfl, bundled = { error("unreadable") }, warn = { warnings += it }).nearbyStops(51.5, -0.12, 500)
        assertEquals(2, asked)
        assertEquals(listOf("bundled stops unavailable: IllegalStateException; asking TfL"), warnings)
    }

    @Test
    fun `the on-device finder never asks TfL, and caches only what the bundle covers`() = runBlocking {
        val cache = NearbyStopsCache()
        val warnings = mutableListOf<String>()
        val bundle = read()
        val finder = OnDeviceStopFinder(bundled = { bundle }, cache = cache, warn = { warnings += it })
        // Inside the area: the bundle's answer, cached for the app's near-me list to reuse.
        assertEquals(4, finder.nearbyStops(51.5, -0.12, 500).size)
        assertEquals(4, cache.lookup(51.5, -0.12, 500, types)?.size)
        // Past the area: what the bundle holds there (nothing), not cached, so the app may still ask TfL.
        assertEquals(emptyList<StopLocation>(), finder.nearbyStops(53.48, -2.24, 500))
        assertEquals(null, cache.lookup(53.48, -2.24, 500, types))
        assertEquals(listOf("nearby: past the bundled stops; answered on the device"), warnings)
    }

    @Test
    fun `the on-device finder reuses a lookup the app made, even past the bundle`() = runBlocking {
        val cache = NearbyStopsCache()
        val fromTfl = listOf(StopLocation("tfl", "From TfL", 53.48, -2.24))
        cache.put(53.48, -2.24, 500, types, fromTfl)
        val finder = OnDeviceStopFinder(bundled = { read() }, cache = cache)
        assertEquals(fromTfl, finder.nearbyStops(53.48, -2.24, 500))
    }

    @Test
    fun `the on-device finder says it has no answer rather than none nearby`() = runBlocking {
        assertThrows(BundledStopsUnavailableException::class.java) {
            runBlocking { OnDeviceStopFinder(bundled = { BundledStops.EMPTY }).nearbyStops(51.5, -0.12, 500) }
        }
        assertThrows(BundledStopsUnavailableException::class.java) {
            runBlocking { OnDeviceStopFinder(bundled = { error("unreadable") }).nearbyStops(51.5, -0.12, 500) }
        }
        assertThrows(BundledStopsUnavailableException::class.java) {
            runBlocking { OnDeviceStopFinder(bundled = { read() }).nearbyStops(51.5, -0.12, 500, listOf("NaptanCoachBay")) }
        }
        Unit
    }

    @Test
    fun `the on-device search runs on the compute dispatcher, not the caller's thread`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "bundle-worker") }.asCoroutineDispatcher()
        try {
            var ranOn: String? = null
            val finder = OnDeviceStopFinder(
                bundled = { read() },
                compute = object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                        worker.dispatch(context, Runnable { ranOn = Thread.currentThread().name; block.run() })
                    }
                },
            )
            assertEquals(4, finder.nearbyStops(51.5, -0.12, 500).size)
            assertTrue(ranOn.orEmpty().startsWith("bundle-worker"))
        } finally {
            worker.close()
        }
    }

    @Test
    fun `the bundled asset reads whole and covers central London`() {
        val asset = listOf(File("../app/src/main/assets/stations/stops.tsv"), File("app/src/main/assets/stations/stops.tsv"))
            .first { it.exists() }
        val stops = asset.bufferedReader().use { BundledStops.read(it) }
        assertEquals(0, stops.skippedLines)
        assertTrue("${stops.size} stops", stops.size > 15_000)
        // King's Cross: a hub, public data (AGENTS.md *Privacy*).
        assertTrue(stops.covers(51.5308, -0.1238, 1609))
        val near = stops.nearby(51.5308, -0.1238, 500, types)
        assertTrue(near.any { it.id == "940GZZLUKSX" })
        assertTrue(near.count { it.lines.any { line -> line.mode == "bus" } } > 10)
    }
}
