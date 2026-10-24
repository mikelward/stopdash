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
        assertEquals("northern", journey.lineIds.single())
        assertEquals("Northern", journey.lines.single().name)
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
        // Every rail line between the two stations picked, and no bus.
        assertEquals(setOf("northern", "victoria"), rail.journey.lineIds.toSet())
        val bus = JourneyPair.resolve("Euston", busAndTube.take(1), "King's Cross St. Pancras", kxBus.take(1)) as JourneyPair.Result.Found
        assertEquals("30", bus.journey.lineIds.single())
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
        val withBuses = JourneyPair.withPlace(listOf(eustonPlaced), listOf(farther, outside, acrossTheRoad, outside))
        assertEquals(listOf("940GZZLUEUS", "490EXAMPLEB", "490EXAMPLEA"), withBuses.map { it.id })
    }

    @Test
    fun `a station's bus stop named for it counts a little farther than one of another name`() {
        // About 220 m north: past the 150 m any stop counts within, inside the 250 m a namesake does.
        val named = pole("490EXAMPLEE", 51.5302, "X4").copy(name = "Euston Station / Example Road")
        val unnamed = named.copy(id = "490EXAMPLEF", name = "Example Road")
        assertEquals(listOf("940GZZLUEUS", "490EXAMPLEE"), JourneyPair.withPlace(listOf(eustonPlaced), listOf(unnamed, named)).map { it.id })
    }

    @Test
    fun `a bus stop end looks for no more bus stops, and an unplaced station none around it`() {
        assertEquals(emptyList<StopLocation>(), JourneyPair.stationPositions(listOf(outside, acrossTheRoad)))
        assertEquals(listOf(outside), JourneyPair.withPlace(listOf(outside), listOf(acrossTheRoad)))
        val unplaced = eustonPlaced.copy(latitude = 0.0, longitude = 0.0)
        assertEquals(emptyList<StopLocation>(), JourneyPair.stationPositions(listOf(unplaced)))
        assertEquals(listOf(unplaced), JourneyPair.withPlace(listOf(unplaced), listOf(outside)))
    }

    @Test
    fun `a station's stops sharing a position are looked around once`() {
        val platform = eustonPlaced.copy(id = "9400ZZLUEUS1")
        assertEquals(listOf(eustonPlaced), JourneyPair.stationPositions(listOf(eustonPlaced, platform, outside)))
    }

    // The pole outside Euston as TfL names a stop for the station: "Euston Station", which cleans to the
    // station's own name (synthetic id and position, about 55 m north of it). A station 330 m south is past
    // the name's reach; one 120 m north, beside the pole only, is named otherwise.
    private val eustonPole = outside.copy(name = "Euston Station")
    private val fartherEuston = eustonPlaced.copy(id = "940GEXAMPLE2", latitude = 51.5257)
    private val besidePole = StopLocation("940GEXAMPLE1", "Station One Underground Station", 51.5298, -0.1337, listOf(LineRef("jubilee", "Jubilee", "tube")), clusterId = "940GEXAMPLE1")

    @Test
    fun `the station a bus stop is named for is added, and no other`() {
        val withStation = JourneyPair.withPlace(listOf(eustonPole), listOf(fartherEuston, besidePole, acrossTheRoad, eustonPlaced, eustonPlaced))
        // The bus stop across the road is no station, the farther Euston is past the name's reach, and the
        // station beside the pole is another place.
        assertEquals(listOf("490EXAMPLEA", "940GZZLUEUS"), withStation.map { it.id })
        // Named for it with its cross street too.
        val crossStreet = eustonPole.copy(name = "Euston Station / Example Road")
        assertEquals(listOf("490EXAMPLEA", "940GZZLUEUS"), JourneyPair.withPlace(listOf(crossStreet), listOf(eustonPlaced)).map { it.id })
    }

    @Test
    fun `a bus stop picked for itself brings in no station that merely stands near it`() {
        assertEquals(listOf(outside), JourneyPair.withPlace(listOf(outside), listOf(eustonPlaced, besidePole)))
        val journeys = JourneyPair.resolve("Example Road", JourneyPair.withPlace(listOf(outside), listOf(eustonPlaced)), "King's Cross St. Pancras", kingsCross)
        assertEquals(JourneyPair.Result.NoDirectLine, journeys)
        // A line qualifier tells two stations of one name apart.
        val qualified = eustonPole.copy(name = "Paddington (H&C Line)")
        val otherLine = eustonPlaced.copy(name = "Paddington (Circle Line) Underground Station")
        assertEquals(listOf(qualified), JourneyPair.withPlace(listOf(qualified), listOf(otherLine)))
    }

    // King's Cross St Pancras's interchange as TfL lists it: a bus stop by a name no name match joins, the
    // Underground and the rail station (TfL's ids; synthetic positions, the pole about 330 m off).
    private val kxTube = kingsCross.single().copy(interchangeId = "HUBKGX")
    private val kxRail = StopLocation("910GKNGX", "London King's Cross Rail Station", 51.531, -0.123, listOf(LineRef("great-northern", "Great Northern", "national-rail")), interchangeId = "HUBKGX")
    private val kxPole = StopLocation("490012553B", "St Pancras Intern'l & King's X Stns", 51.533, -0.12, listOf(LineRef("214", "214", "bus")), interchangeId = "HUBKGX")
    private val kxOtherPole = kxPole.copy(id = "490001172X", name = "King's Cross / Caledonian Road", lines = listOf(LineRef("30", "30", "bus")))

    @Test
    fun `an interchange's bus stop belongs to its stations, whatever it's called`() {
        assertEquals(listOf("HUBKGX"), JourneyPair.interchanges(listOf(kxPole)))
        val placed = JourneyPair.withPlace(listOf(kxPole), listOf(kxOtherPole, kxTube, kxRail))
        // Its stations, never the interchange's other bus stop, named apart.
        assertEquals(setOf("490012553B", "940GZZLUKSX", "910GKNGX"), placed.map { it.id }.toSet())
        val journey = (JourneyPair.resolve("St Pancras International", placed, "Waterloo", waterloo) as JourneyPair.Result.Found).journey
        assertEquals("northern", journey.lineIds.single())
    }

    @Test
    fun `two picks of one place are the same station`() {
        // An interchange's bus stop and its Underground, its two stations, a station and the bus stop named for it.
        assertEquals(JourneyPair.Result.SameStation, JourneyPair.resolve("St Pancras", listOf(kxPole), "King's Cross St. Pancras", listOf(kxTube)))
        assertEquals(JourneyPair.Result.SameStation, JourneyPair.resolve("King's Cross", listOf(kxRail), "King's Cross St. Pancras", listOf(kxTube)))
        assertEquals(JourneyPair.Result.SameStation, JourneyPair.resolve("Euston", listOf(eustonPole), "Euston", listOf(eustonPlaced)))
        // Two of an interchange's bus stops named apart, or a bus stop merely near a station, are still two.
        assertEquals(JourneyPair.Result.NoDirectLine, JourneyPair.resolve("St Pancras", listOf(kxPole), "Caledonian Road", listOf(kxOtherPole)))
        assertEquals(JourneyPair.Result.NoDirectLine, JourneyPair.resolve("Example Road", listOf(outside), "Euston", listOf(eustonPlaced)))
    }

    @Test
    fun `an interchange's station is one place with its others, and its bus stops are its own`() {
        assertEquals(listOf("HUBKGX"), JourneyPair.interchanges(listOf(kxRail)))
        val placed = JourneyPair.withPlace(listOf(kxRail), listOf(kxTube, kxPole))
        assertEquals(listOf("910GKNGX", "940GZZLUKSX", "490012553B"), placed.map { it.id })
        assertEquals("northern", (JourneyPair.resolve("King's Cross", placed, "Waterloo", waterloo) as JourneyPair.Result.Found).journey.lineIds.single())
        // A stop in no interchange, or another one, has none to look in.
        assertEquals(emptyList<String>(), JourneyPair.interchanges(listOf(eustonPlaced)))
        assertEquals(listOf(eustonPlaced), JourneyPair.withPlace(listOf(eustonPlaced), listOf(kxPole.copy(interchangeId = "HUBEXA"))))
        // A station end's interchanges are its stations', not its bus poles'.
        assertEquals(listOf("HUBKGX"), JourneyPair.interchanges(listOf(kxRail, kxPole.copy(interchangeId = "HUBEXA"))))
    }

    @Test
    fun `a bus stop beside an interchange's other station is the end's own, picked at either`() {
        // A synthetic pole about 65 m from the rail station and 300 m from the Underground, named for neither.
        val railPole = StopLocation("490EXAMPLED", "Example Road", 51.5315, -0.1235, listOf(LineRef("X5", "X5", "bus")))
        assertEquals(listOf("940GZZLUKSX", "910GKNGX"), JourneyPair.placeStations(listOf(kxTube), listOf(kxPole, kxRail, railPole)).map { it.id })
        // Nearest first, each measured from the nearest of the place's stations.
        assertEquals(listOf("940GZZLUKSX", "490EXAMPLED", "910GKNGX", "490012553B"), JourneyPair.withPlace(listOf(kxTube), listOf(kxPole, kxRail, railPole)).map { it.id })
        // Not a bus-stop end's: its place is the stations it belongs to, never their bus stops.
        assertEquals(emptyList<StopLocation>(), JourneyPair.placeStations(listOf(kxPole), listOf(kxTube, kxRail)))
        assertEquals(setOf("490012553B", "940GZZLUKSX", "910GKNGX"), JourneyPair.withPlace(listOf(kxPole), listOf(kxTube, kxRail, railPole)).map { it.id }.toSet())
        // A station of another place adds nothing to measure from.
        assertEquals(listOf(eustonPlaced), JourneyPair.placeStations(listOf(eustonPlaced), listOf(kxRail, kxPole)))
    }

    @Test
    fun `a station end looks for no station beside it, and neither does an unplaced or mixed stop`() {
        assertEquals(emptyList<StopLocation>(), JourneyPair.busStopPositions(listOf(eustonPlaced)))
        assertEquals(listOf(eustonPlaced), JourneyPair.withPlace(listOf(eustonPlaced), listOf(fartherEuston)))
        val unplaced = eustonPole.copy(latitude = 0.0, longitude = 0.0)
        assertEquals(emptyList<StopLocation>(), JourneyPair.busStopPositions(listOf(unplaced)))
        // A stop a tram serves as well as a bus has its rail already.
        val tramToo = eustonPole.copy(lines = eustonPole.lines + LineRef("tram", "Tram", "tram"))
        assertEquals(emptyList<StopLocation>(), JourneyPair.busStopPositions(listOf(tramToo)))
        assertEquals(listOf(eustonPole), JourneyPair.busStopPositions(listOf(eustonPole, eustonPole.copy(id = "490EXAMPLEZ"))))
    }

    @Test
    fun `an interchange's own bus poles bring in no other station`() {
        // Euston with a pole outside named for another station: that station, about 145 m beyond the pole, is
        // the pole's place but not the end's, which has its own station already.
        val squarePole = eustonPole.copy(name = "Euston Square Station / Example Road")
        val square = StopLocation("940GEXAMPLE3", "Euston Square Underground Station", 51.5300, -0.1337, listOf(LineRef("circle", "Circle", "tube")), clusterId = "940GEXAMPLE3")
        val interchange = listOf(eustonPlaced, squarePole)
        assertEquals(emptyList<StopLocation>(), JourneyPair.busStopPositions(interchange))
        assertEquals(interchange, JourneyPair.withPlace(interchange, listOf(square)))
        // An unplaced station still makes the end a station's.
        assertEquals(emptyList<StopLocation>(), JourneyPair.busStopPositions(listOf(eustonPlaced.copy(latitude = 0.0, longitude = 0.0), eustonPole)))
        // The pole alone does look around, and finds it.
        assertEquals(listOf("490EXAMPLEA", "940GEXAMPLE3"), JourneyPair.withPlace(listOf(squarePole), listOf(square)).map { it.id })
    }

    @Test
    fun `a ride from the bus stop outside a station rides the station's trains`() {
        // The station picked as the bus stop outside it: its bus doesn't reach King's Cross.
        val picked = listOf(eustonPole)
        assertEquals(JourneyPair.Result.NoDirectLine, JourneyPair.resolve("Euston", picked, "King's Cross St. Pancras", kingsCross))
        val placed = JourneyPair.withPlace(picked, listOf(eustonPlaced))
        val journey = (JourneyPair.resolve("Euston", placed, "King's Cross St. Pancras", kingsCross) as JourneyPair.Result.Found).journey
        assertEquals("northern", journey.lineIds.single())
        assertEquals("940GZZLUEUS", journey.from.stopId)
        assertEquals("Euston", journey.from.name)
        // Rail before a bus that reaches it too.
        val busToo = listOf(kingsCross.single().copy(lines = kingsCross.single().lines + LineRef("X1", "X1", "bus")))
        assertEquals("northern", (JourneyPair.resolve("Euston", placed, "King's Cross St. Pancras", busToo) as JourneyPair.Result.Found).journey.lineIds.single())
    }

    @Test
    fun `a bus from a stop to one outside a station makes a journey to the station`() {
        val from = listOf(pole("490EXAMPLED", 51.54, "X1"))
        assertEquals(JourneyPair.Result.NoDirectLine, JourneyPair.resolve("Example Road", from, "Euston", listOf(eustonPlaced)))
        val toAll = JourneyPair.withPlace(listOf(eustonPlaced), listOf(outside, farther))
        val journey = (JourneyPair.resolve("Example Road", from, "Euston", toAll) as JourneyPair.Result.Found).journey
        assertEquals("X1", journey.lineIds.single())
        assertEquals("bus", journey.mode)
        assertEquals("490EXAMPLEA", journey.to.stopId)
        assertEquals("Euston", journey.to.name)
    }
}
