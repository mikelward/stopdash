package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A favorite journey from two picked stations: a line both serve, or why there's none. */
class JourneyPairTest {
    // Big interchanges, with TfL's ids; positions are TfL's published ones, never a rider's.
    private fun tube(id: String, name: String, vararg lines: String, area: String = id) =
        StopLocation(id, "$name Underground Station", 51.53, -0.12, lines.map { LineRef(it, it.replaceFirstChar(Char::uppercase), "tube") }, clusterId = area)

    private val kingsCross = listOf(tube("940GZZLUKSX", "King's Cross St. Pancras", "northern", "victoria", "piccadilly", "circle"))
    private val euston = listOf(tube("940GZZLUEUS", "Euston", "northern", "victoria"))
    private val waterloo = listOf(tube("940GZZLUWLO", "Waterloo", "jubilee", "bakerloo", "northern", "waterloo-city"))
    private val canadaWater = listOf(tube("940GZZLUCWR", "Canada Water", "jubilee"))

    @Test
    fun `two stations on one line make a journey on it, each end named as picked`() {
        val result = JourneyPair.resolve("King's Cross St. Pancras", kingsCross, "Waterloo", waterloo) as JourneyPair.Result.Found
        val journey = result.journey
        assertEquals("northern", journey.lineId)
        assertEquals("Northern", journey.lineName)
        assertEquals("tube", journey.mode)
        assertEquals(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras", 51.53, -0.12, "940GZZLUKSX"), journey.from)
        assertEquals("940GZZLUWLO", journey.to.stopId)
        assertEquals("Waterloo", journey.to.name)
    }

    @Test
    fun `no line serving both is said, never guessed`() {
        assertEquals(JourneyPair.Result.NoDirectLine, JourneyPair.resolve("King's Cross St. Pancras", kingsCross, "Canada Water", canadaWater))
        assertEquals(JourneyPair.Result.NoDirectLine, JourneyPair.resolve("Euston", euston, "Nowhere", emptyList()))
    }

    @Test
    fun `a position TfL left out is none, never 0,0`() {
        val unplaced = listOf(tube("940GZZLUEUS", "Euston", "northern").copy(latitude = 0.0, longitude = 0.0))
        val journey = (JourneyPair.resolve("Euston", unplaced, "Waterloo", waterloo) as JourneyPair.Result.Found).journey
        assertEquals(null, journey.from.latitude)
        assertEquals(null, journey.from.longitude)
        val halfPlaced = listOf(tube("940GZZLUEUS", "Euston", "northern").copy(longitude = 0.0))
        assertEquals(null, (JourneyPair.resolve("Euston", halfPlaced, "Waterloo", waterloo) as JourneyPair.Result.Found).journey.from.latitude)
        assertEquals(51.53, journey.to.latitude)
    }

    @Test
    fun `the same station both ends is said`() {
        assertEquals(JourneyPair.Result.SameStation, JourneyPair.resolve("King's Cross St. Pancras", kingsCross, "King's Cross", kingsCross))
    }

    @Test
    fun `rail before a bus both serve, and a bus pole's stop area kept`() {
        val busAndTube = listOf(
            StopLocation("490000000A", "Euston Bus Station", 51.53, -0.13, listOf(LineRef("30", "30", "bus")), clusterId = "490G00000000"),
        ) + euston
        val kxBus = listOf(StopLocation("490000000B", "King's Cross Station", 51.53, -0.12, listOf(LineRef("30", "30", "bus")), clusterId = "490G00000001")) + kingsCross
        val rail = JourneyPair.resolve("Euston", busAndTube, "King's Cross St. Pancras", kxBus) as JourneyPair.Result.Found
        assertTrue(rail.journey.lineId in setOf("northern", "victoria"))
        val bus = JourneyPair.resolve("Euston", busAndTube.take(1), "King's Cross St. Pancras", kxBus.take(1)) as JourneyPair.Result.Found
        assertEquals("30", bus.journey.lineId)
        assertEquals("490G00000000", bus.journey.from.areaId)
        // A cluster id that's only the stop's name is no stop area.
        val named = listOf(StopLocation("490000000C", "Somewhere", 51.5, -0.1, listOf(LineRef("30", "30", "bus")), clusterId = "Somewhere"))
        assertEquals("", (JourneyPair.resolve("Somewhere", named, "King's Cross", kxBus) as JourneyPair.Result.Found).journey.from.areaId)
    }

    // A station's own bus stops, which TfL files apart from it: synthetic poles around Euston's
    // published position (SPEC *Privacy*), about 55 m and 330 m north of it.
    private val eustonPlaced = StopLocation("940GZZLUEUS", "Euston Underground Station", 51.5282, -0.1337, listOf(LineRef("northern", "Northern", "tube")), clusterId = "940GZZLUEUS")
    private fun pole(id: String, latitude: Double, vararg routes: String) =
        StopLocation(id, "Stop $id", latitude, -0.1337, routes.map { LineRef(it, it, "bus") }, clusterId = "490G0$id")
    private val outside = pole("490EXAMPLEA", 51.5287, "X1", "X2")
    private val acrossTheRoad = pole("490EXAMPLEB", 51.5286, "X1")
    private val farther = pole("490EXAMPLEC", 51.5312, "X3")

    @Test
    fun `a station's own bus stops are added after its stops, nearest first, and none farther`() {
        val withBuses = JourneyPair.withStationBuses(listOf(eustonPlaced), listOf(farther, outside, acrossTheRoad, outside))
        assertEquals(listOf("940GZZLUEUS", "490EXAMPLEB", "490EXAMPLEA"), withBuses.map { it.id })
    }

    @Test
    fun `a bus stop end looks for no more stops, and neither does an unplaced station`() {
        assertEquals(emptyList<StopLocation>(), JourneyPair.stationPositions(listOf(outside, acrossTheRoad)))
        assertEquals(listOf(outside), JourneyPair.withStationBuses(listOf(outside), listOf(acrossTheRoad)))
        val unplaced = eustonPlaced.copy(latitude = 0.0, longitude = 0.0)
        assertEquals(emptyList<StopLocation>(), JourneyPair.stationPositions(listOf(unplaced)))
        assertEquals(listOf(unplaced), JourneyPair.withStationBuses(listOf(unplaced), listOf(outside)))
    }

    @Test
    fun `a station's stops sharing a position are looked around once`() {
        val platform = eustonPlaced.copy(id = "9400ZZLUEUS1")
        assertEquals(listOf(eustonPlaced), JourneyPair.stationPositions(listOf(eustonPlaced, platform, outside)))
    }

    @Test
    fun `a bus from a stop to one outside a station makes a journey to the station`() {
        val from = listOf(pole("490EXAMPLED", 51.54, "X1"))
        assertEquals(JourneyPair.Result.NoDirectLine, JourneyPair.resolve("Example Road", from, "Euston", listOf(eustonPlaced)))
        val toAll = JourneyPair.withStationBuses(listOf(eustonPlaced), listOf(outside, farther))
        val journey = (JourneyPair.resolve("Example Road", from, "Euston", toAll) as JourneyPair.Result.Found).journey
        assertEquals("X1", journey.lineId)
        assertEquals("bus", journey.mode)
        assertEquals("490EXAMPLEA", journey.to.stopId)
        assertEquals("Euston", journey.to.name)
    }
}
