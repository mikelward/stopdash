package app.stopdash.ui

import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.PendingEnd
import app.stopdash.domain.PendingJourney
import app.stopdash.domain.StationFinder
import app.stopdash.domain.CachingStopFinder
import app.stopdash.domain.NearbyStopsCache
import app.stopdash.domain.NearestStops
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopFinder
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' Add: two picked stations saved as a journey on a line both serve, or why not (SPEC *Journeys*). */
class AddFavoriteJourneyPairTest {
    // Public TfL interchanges only (SPEC *Privacy*).
    private val kingsCross = StationMatch("HUBKGX", "King's Cross St. Pancras")
    private val waterloo = StationMatch("940GZZLUWLO", "Waterloo")
    private val canadaWater = StationMatch("940GZZLUCWR", "Canada Water")

    private fun tube(id: String, vararg lines: String) = StopLocation(id, "Station $id", 51.5, -0.12, lines.map { LineRef(it, it, "tube") }, clusterId = id)

    private class Finder(
        val stops: Map<String, List<StopLocation>>,
        val fail: TflException? = null,
        val failOn: Set<String> = emptySet(),
    ) : StationFinder {
        val asked = mutableListOf<String>()
        override suspend fun searchStations(query: String): List<StationMatch> = emptyList()
        override suspend fun stationStops(id: String): List<StopLocation> {
            asked += id
            fail?.let { throw it }
            if (id in failOn) throw TflException.Offline(null)
            return stops[id].orEmpty()
        }
    }

    // The stops around an end, by its position: [stops] when asked for bus stops, [stations] when asked
    // for stations, none unless a test places some.
    private class Buses(val stops: List<StopLocation>, val fail: TflException? = null, val stations: List<StopLocation> = emptyList()) : StopFinder {
        val asked = mutableListOf<Pair<Double, Double>>()
        val askedStations = mutableListOf<Pair<Double, Double>>()
        val stationTypes = mutableListOf<List<String>>()
        override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
            val forStations = "NaptanMetroStation" in stopTypes
            if (forStations) {
                askedStations += latitude to longitude
                stationTypes += stopTypes
            } else {
                asked += latitude to longitude
            }
            fail?.let { throw it }
            return if (forStations) stations else stops
        }
    }

    private val noBuses = Buses(emptyList())

    private val finder = Finder(
        mapOf(
            "HUBKGX" to listOf(tube("940GZZLUKSX", "northern", "victoria")),
            "940GZZLUWLO" to listOf(tube("940GZZLUWLO", "northern", "jubilee")),
            "940GZZLUCWR" to listOf(tube("940GZZLUCWR", "jubilee")),
        ),
    )

    private class Journeys(
        initial: List<FavoriteJourney>?,
        val failAdd: Boolean = false,
        pendingInitial: List<PendingJourney>? = initial?.let { emptyList() },
    ) : FavoriteJourneysStore {
        val state = MutableStateFlow(initial)
        val pending = MutableStateFlow(pendingInitial)
        override fun journeys(): Flow<List<FavoriteJourney>?> = state
        override fun pendingJourneys(): Flow<List<PendingJourney>?> = pending
        override suspend fun addPending(journey: PendingJourney) {
            if (failAdd) throw IOException("disk")
            pending.value = pending.value.orEmpty() + journey
        }
        override suspend fun removePending(journey: PendingJourney) {
            pending.value = pending.value?.filterNot { it.key == journey.key }
        }
        override suspend fun toggle(journey: FavoriteJourney) {}
        override suspend fun remove(journey: FavoriteJourney) {}
        override suspend fun add(journey: FavoriteJourney) {
            if (failAdd) throw IOException("disk")
            state.value = state.value.orEmpty() + journey
        }
    }

    @After
    fun clearNote() = JourneyAdds.dismiss()

    @Test
    fun `two stations on one line are saved as a journey on it`() = runTest {
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(kingsCross, waterloo, finder, noBuses, journeys, worker = StandardTestDispatcher(testScheduler)))
        val saved = journeys.state.value!!.single()
        assertEquals("northern", saved.lineId)
        assertEquals(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras", 51.5, -0.12, "940GZZLUKSX"), saved.from)
        assertEquals("Waterloo", saved.to.name)
    }

    @Test
    fun `two stations no one line serves are saved grayed, once`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(kingsCross, canadaWater, finder, noBuses, journeys, worker = worker))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(
            listOf(PendingJourney(PendingEnd.Station("HUBKGX", "King's Cross St. Pancras"), PendingEnd.Station("940GZZLUCWR", "Canada Water"))),
            journeys.pending.value,
        )
        // Either way round, it's the same one.
        assertEquals(
            JourneyAddNote.AlreadySaved("Canada Water", "King's Cross St. Pancras"),
            addFavoriteJourneyPair(canadaWater, kingsCross, finder, noBuses, journeys, worker = worker),
        )
        assertEquals(1, journeys.pending.value!!.size)
        assertEquals(
            JourneyAddNote.NotSaved("King's Cross St. Pancras", "Canada Water"),
            addFavoriteJourneyPair(kingsCross, canadaWater, finder, noBuses, Journeys(emptyList(), failAdd = true), worker = worker),
        )
    }

    @Test
    fun `a grayed pair that resolves to a line later is followed, and its gray copy goes`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        addFavoriteJourneyPair(kingsCross, canadaWater, finder, noBuses, journeys, worker = worker)
        assertEquals(1, journeys.pending.value!!.size)
        // TfL now reports the Jubilee at King's Cross too.
        val later = Finder(
            mapOf(
                "HUBKGX" to listOf(tube("940GZZLUKSX", "northern", "victoria", "jubilee")),
                "940GZZLUCWR" to listOf(tube("940GZZLUCWR", "jubilee")),
            ),
        )
        assertNull(addFavoriteJourneyPair(canadaWater, kingsCross, later, noBuses, journeys, worker = worker))
        assertEquals("jubilee", journeys.state.value!!.single().lineId)
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    @Test
    fun `a followed pair that no longer resolves to a line isn't saved again grayed`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        addFavoriteJourneyPair(kingsCross, waterloo, finder, noBuses, journeys, worker = worker)
        // TfL no longer reports a line both serve.
        val later = Finder(
            mapOf(
                "HUBKGX" to listOf(tube("940GZZLUKSX", "victoria")),
                "940GZZLUWLO" to listOf(tube("940GZZLUWLO", "jubilee")),
            ),
        )
        assertEquals(
            JourneyAddNote.AlreadySaved("Waterloo", "King's Cross St. Pancras"),
            addFavoriteJourneyPair(waterloo, kingsCross, later, noBuses, journeys, worker = worker),
        )
        assertEquals(1, journeys.state.value!!.size)
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    // A bus stop's pole, and Euston, whose own bus stop TfL files apart from it: synthetic ids, the
    // pole outside about 55 m north of Euston's published position (SPEC *Privacy*).
    private val busStop = StationMatch("490G0EXAMPLE", "Example Road")
    private val euston = StationMatch("940GZZLUEUS", "Euston")
    private fun pole(id: String, latitude: Double, vararg routes: String) =
        StopLocation(id, "Stop $id", latitude, -0.1337, routes.map { LineRef(it, it, "bus") }, clusterId = "490G0EXAMPLE")
    private val busFinder = Finder(
        mapOf(
            "490G0EXAMPLE" to listOf(pole("490EXAMPLED", 51.54, "X1")),
            "940GZZLUEUS" to listOf(StopLocation("940GZZLUEUS", "Euston Underground Station", 51.5282, -0.1337, listOf(LineRef("northern", "Northern", "tube")), clusterId = "940GZZLUEUS")),
        ),
    )

    @Test
    fun `a bus to a station's own bus stop is saved as a journey to the station`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        val buses = Buses(listOf(pole("490EXAMPLEA", 51.5287, "X1")))
        assertNull(addFavoriteJourneyPair(busStop, euston, busFinder, buses, journeys, worker = worker))
        val saved = journeys.state.value!!.single()
        assertEquals("X1", saved.lineId)
        assertEquals("490EXAMPLEA", saved.to.stopId)
        assertEquals("Euston", saved.to.name)
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
        // Bus stops looked for around the station only: a bus stop end has its poles already. The bus stop
        // end is looked around for a station beside it instead, and there's none.
        assertEquals(listOf(51.5282 to -0.1337), buses.asked)
        assertEquals(listOf(51.54 to -0.1337), buses.askedStations)
        // Either way round, it's the same one.
        assertEquals(JourneyAddNote.AlreadySaved("Euston", "Example Road"), addFavoriteJourneyPair(euston, busStop, busFinder, buses, journeys, worker = worker))
    }

    @Test
    fun `a grayed pair a station's bus stop now joins is followed, and its gray copy goes`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(busStop, euston, busFinder, noBuses, journeys, worker = worker))
        assertEquals(1, journeys.pending.value!!.size)
        assertNull(addFavoriteJourneyPair(busStop, euston, busFinder, Buses(listOf(pole("490EXAMPLEA", 51.5287, "X1"))), journeys, worker = worker))
        assertEquals("X1", journeys.state.value!!.single().lineId)
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    @Test
    fun `an interchange's second station finds its own bus stop through the shared cache`() = runTest {
        // Two stations of one interchange about 100 m apart, a bus stop by the first on another route, and
        // one about 120 m past the second: within reach of it, about 220 m from the first (synthetic
        // positions by Euston). An empty answer isn't cached, so the first's own stop makes it reusable.
        val first = StopLocation("940GZZLUEUS", "Euston Underground Station", 51.5282, -0.1337, listOf(LineRef("northern", "Northern", "tube")))
        val second = StopLocation("910GEUSTON", "Euston", 51.5291, -0.1337, listOf(LineRef("london-overground", "Overground", "overground")))
        val byFirst = pole("490EXAMPLEG", 51.5280, "X2")
        val outsideSecond = pole("490EXAMPLEF", 51.53018, "X1")
        val interchange = StationMatch("HUBEUS", "Euston")
        val finder = Finder(mapOf("490G0EXAMPLE" to listOf(pole("490EXAMPLED", 51.54, "X1")), "HUBEUS" to listOf(first, second)))
        // TfL's answer by distance, behind the station-area cache the app gives Add, which reuses a lookup
        // centered within its reuse distance of the one asked for.
        val byDistance = object : StopFinder {
            override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>) =
                listOf(byFirst, outsideSecond).filter { NearestStops.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= radiusMeters }
        }
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(busStop, interchange, finder, CachingStopFinder(byDistance, NearbyStopsCache()), journeys, worker = StandardTestDispatcher(testScheduler)))
        assertEquals("490EXAMPLEF", journeys.state.value!!.single().to.stopId)
    }

    @Test
    fun `a bus stop farther from the station than its own doesn't count`() = runTest {
        val journeys = Journeys(emptyList())
        // About 330 m away: the next road's, not the station's.
        val buses = Buses(listOf(pole("490EXAMPLEC", 51.5312, "X1")))
        assertNull(addFavoriteJourneyPair(busStop, euston, busFinder, buses, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(1, journeys.pending.value!!.size)
    }

    @Test
    fun `a pair a line already serves looks for no bus stops`() = runTest {
        val buses = Buses(emptyList())
        assertNull(addFavoriteJourneyPair(kingsCross, waterloo, finder, buses, Journeys(emptyList()), worker = StandardTestDispatcher(testScheduler)))
        assertEquals(emptyList<Pair<Double, Double>>(), buses.asked)
        assertEquals(emptyList<Pair<Double, Double>>(), buses.askedStations)
    }

    // The search's one row for a station whose bus stop outside is among the rider's own carries that bus
    // stop's id (StationIndex.foldNeighbors): Euston's row here stands on a synthetic pole about 55 m north
    // of Euston's published position, served by a bus that doesn't reach King's Cross (SPEC *Privacy*).
    private val eustonRow = StationMatch("490EXAMPLEA", "Euston")
    private val eustonStation = StopLocation("940GZZLUEUS", "Euston Underground Station", 51.5282, -0.1337, listOf(LineRef("northern", "Northern", "tube")), clusterId = "940GZZLUEUS")
    private val rowFinder = Finder(
        mapOf(
            // TfL names the pole for the station it stands outside, as the search folds them.
            "490EXAMPLEA" to listOf(pole("490EXAMPLEA", 51.5287, "X2").copy(name = "Euston Station")),
            "HUBKGX" to listOf(tube("940GZZLUKSX", "northern", "victoria")),
            "940GZZLUCWR" to listOf(tube("940GZZLUCWR", "jubilee")),
        ),
    )

    @Test
    fun `a station picked as the bus stop outside it is saved on the station's line`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        val beside = Buses(emptyList(), stations = listOf(eustonStation))
        assertNull(addFavoriteJourneyPair(eustonRow, kingsCross, rowFinder, beside, journeys, worker = worker))
        val saved = journeys.state.value!!.single()
        assertEquals("northern", saved.lineId)
        assertEquals(JourneyEnd("940GZZLUEUS", "Euston", 51.5282, -0.1337, "940GZZLUEUS"), saved.from)
        assertEquals("940GZZLUKSX", saved.to.stopId)
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
        // Either way round, and from the far end too: the same one.
        assertEquals(JourneyAddNote.AlreadySaved("King's Cross St. Pancras", "Euston"), addFavoriteJourneyPair(kingsCross, eustonRow, rowFinder, beside, journeys, worker = worker))
    }

    @Test
    fun `a station beside the bus stop picked still has to reach the far end`() = runTest {
        val journeys = Journeys(emptyList())
        val beside = Buses(emptyList(), stations = listOf(eustonStation))
        assertNull(addFavoriteJourneyPair(eustonRow, canadaWater, rowFinder, beside, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(listOf(PendingJourney(PendingEnd.Station("490EXAMPLEA", "Euston"), PendingEnd.Station("940GZZLUCWR", "Canada Water"))), journeys.pending.value)
    }

    @Test
    fun `a tram stop folded into a bus stop's row is weighed too`() = runTest {
        // A tram stop and the bus stop beside it share a name, so the search shows one row on the bus stop
        // (synthetic ids and positions by Euston): the tram is the direct ride, and the lookup asks for its kind.
        val tramStop = StopLocation("940GEXAMPLET", "Euston", 51.5283, -0.1337, listOf(LineRef("tram", "Tram", "tram")))
        val far = StationMatch("940GEXAMPLEU", "Tram Terminus")
        val finder = Finder(
            mapOf(
                "490EXAMPLEA" to listOf(pole("490EXAMPLEA", 51.5287, "X2").copy(name = "Euston Station")),
                "940GEXAMPLEU" to listOf(StopLocation("940GEXAMPLEU", "Tram Terminus", 51.5, -0.12, listOf(LineRef("tram", "Tram", "tram")))),
            ),
        )
        val beside = Buses(emptyList(), stations = listOf(tramStop))
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(eustonRow, far, finder, beside, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertEquals("tram", journeys.state.value!!.single().lineId)
        assertEquals("940GEXAMPLET", journeys.state.value!!.single().from.stopId)
        assertTrue(beside.stationTypes.single().containsAll(listOf("NaptanPublicBusCoachTram", "NaptanFerryPort")))
    }

    @Test
    fun `a bus stop picked for itself brings in no station that merely stands near it`() = runTest {
        // A stop of another name about 55 m from Euston: the search never folded them, so it was picked as the stop.
        val ownStop = StationMatch("490EXAMPLEB", "Example Road")
        val finder = Finder(mapOf("490EXAMPLEB" to listOf(pole("490EXAMPLEB", 51.5287, "X2")), "HUBKGX" to listOf(tube("940GZZLUKSX", "northern"))))
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(ownStop, kingsCross, finder, Buses(emptyList(), stations = listOf(eustonStation)), journeys, worker = StandardTestDispatcher(testScheduler)))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(1, journeys.pending.value!!.size)
    }

    @Test
    fun `a station farther from the bus stop than its own doesn't count`() = runTest {
        val journeys = Journeys(emptyList())
        // About 330 m from the pole: past the search's fold, so not the station it stands outside.
        val beside = Buses(emptyList(), stations = listOf(eustonStation.copy(latitude = 51.5257)))
        assertNull(addFavoriteJourneyPair(eustonRow, kingsCross, rowFinder, beside, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(1, journeys.pending.value!!.size)
    }

    @Test
    fun `an interchange's own bus pole looks for no other station`() = runTest {
        // An interchange's stops come back with its own pole among them; another station beside that pole
        // would reach Canada Water, but the rider picked the interchange (synthetic ids and positions).
        val interchange = StationMatch("HUBEXAMPLE", "Euston")
        val finder = Finder(
            mapOf(
                "HUBEXAMPLE" to listOf(eustonStation, pole("490EXAMPLEA", 51.5287, "X2")),
                "940GZZLUCWR" to listOf(tube("940GZZLUCWR", "jubilee")),
            ),
        )
        val other = StopLocation("940GEXAMPLE1", "Station One", 51.5290, -0.1337, listOf(LineRef("jubilee", "Jubilee", "tube")))
        val beside = Buses(emptyList(), stations = listOf(other))
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(interchange, canadaWater, finder, beside, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(1, journeys.pending.value!!.size)
        assertEquals(emptyList<Pair<Double, Double>>(), beside.askedStations)
    }

    @Test
    fun `a failed look for the station beside a bus stop is said, not saved grayed`() = runTest {
        val journeys = Journeys(emptyList())
        assertEquals(
            JourneyAddNote.LookupFailed("Euston", "King's Cross St. Pancras", DeparturesUiState.Error.Kind.OFFLINE),
            addFavoriteJourneyPair(eustonRow, kingsCross, rowFinder, Buses(emptyList(), TflException.Offline(null)), journeys, worker = StandardTestDispatcher(testScheduler)),
        )
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    @Test
    fun `a failed look for a station's bus stops is said, not saved grayed`() = runTest {
        val journeys = Journeys(emptyList())
        assertEquals(
            JourneyAddNote.LookupFailed("Example Road", "Euston", DeparturesUiState.Error.Kind.OFFLINE),
            addFavoriteJourneyPair(busStop, euston, busFinder, Buses(emptyList(), TflException.Offline(null)), journeys, worker = StandardTestDispatcher(testScheduler)),
        )
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    // King's Cross St Pancras's interchange as TfL lists it (its public ids): a bus stop by a name no name
    // match joins to the station, the Underground, and the rail station.
    private val kxTube = tube("940GZZLUKSX", "northern", "victoria").copy(hubId = "HUBKGX")
    private val kxRail = StopLocation("910GKNGX", "London King's Cross Rail Station", 51.531, -0.123, listOf(LineRef("great-northern", "Great Northern", "national-rail")), hubId = "HUBKGX")
    private val kxPole = StopLocation("490012553B", "St Pancras Intern'l & King's X Stns", 51.533, -0.12, listOf(LineRef("214", "214", "bus")), hubId = "HUBKGX")
    private val kxFinder = Finder(
        mapOf(
            "490012553B" to listOf(kxPole),
            "910GKNGX" to listOf(kxRail),
            "HUBKGX" to listOf(kxTube, kxRail, kxPole),
            "940GZZLUWLO" to listOf(tube("940GZZLUWLO", "northern", "jubilee")),
            "940GZZLUCWR" to listOf(tube("940GZZLUCWR", "jubilee")),
        ),
    )

    @Test
    fun `an interchange's bus stop is saved on its station's line, whatever it's called`() = runTest {
        val journeys = Journeys(emptyList())
        val picked = StationMatch("490012553B", "St Pancras Intern'l & King's X Stns")
        assertNull(addFavoriteJourneyPair(picked, waterloo, kxFinder, noBuses, journeys, worker = StandardTestDispatcher(testScheduler)))
        val saved = journeys.state.value!!.single()
        assertEquals("northern", saved.lineId)
        assertEquals("940GZZLUKSX", saved.from.stopId)
        assertEquals(listOf("490012553B", "940GZZLUWLO", "HUBKGX"), kxFinder.asked)
    }

    @Test
    fun `an interchange's bus stop and its station are the same station`() = runTest {
        val journeys = Journeys(emptyList())
        val finder = Finder(kxFinder.stops + ("940GZZLUKSX" to listOf(kxTube)))
        assertEquals(
            JourneyAddNote.SameStation("St Pancras", "King's Cross St. Pancras"),
            addFavoriteJourneyPair(StationMatch("490012553B", "St Pancras"), StationMatch("940GZZLUKSX", "King's Cross St. Pancras"), finder, noBuses, journeys, worker = StandardTestDispatcher(testScheduler)),
        )
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
        // Said before looking any wider.
        assertEquals(listOf("490012553B", "940GZZLUKSX"), finder.asked)
    }

    @Test
    fun `a station picked by its own id counts its interchange's others`() = runTest {
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(StationMatch("910GKNGX", "King's Cross"), waterloo, kxFinder, noBuses, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertEquals("northern", journeys.state.value!!.single().lineId)
    }

    @Test
    fun `a bus stop beside an interchange's other station counts for a ride picked at one`() = runTest {
        // A synthetic pole about 65 m from the rail station, named for neither station, on a bus to a stop
        // nowhere near (Codex on #867).
        val railPole = StopLocation("490EXAMPLED", "Example Road", 51.5315, -0.1235, listOf(LineRef("X5", "X5", "bus")))
        val farStop = StopLocation("490EXAMPLEF", "Example Street", 51.52, -0.1, listOf(LineRef("X5", "X5", "bus")))
        val finder = Finder(kxFinder.stops + ("940GZZLUKSX" to listOf(kxTube)) + ("490EXAMPLEF" to listOf(farStop)))
        val buses = Buses(listOf(railPole))
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(StationMatch("940GZZLUKSX", "King's Cross"), StationMatch("490EXAMPLEF", "Example Street"), finder, buses, journeys, worker = StandardTestDispatcher(testScheduler)))
        val saved = journeys.state.value!!.single()
        assertEquals("X5", saved.lineId)
        assertEquals("490EXAMPLED", saved.from.stopId)
        // Looked for around the rail station too, not only the Underground picked.
        assertEquals(listOf(51.5 to -0.12, 51.531 to -0.123), buses.asked)
    }

    @Test
    fun `a pick that is the interchange doesn't ask for it again`() = runTest {
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(kingsCross, canadaWater, kxFinder, noBuses, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertEquals(1, journeys.pending.value!!.size)
        assertEquals(listOf("HUBKGX", "940GZZLUCWR"), kxFinder.asked)
    }

    @Test
    fun `a failed look for an end's interchange is said, not saved grayed`() = runTest {
        val journeys = Journeys(emptyList())
        val failing = Finder(kxFinder.stops, failOn = setOf("HUBKGX"))
        assertEquals(
            JourneyAddNote.LookupFailed("St Pancras", "Waterloo", DeparturesUiState.Error.Kind.OFFLINE),
            addFavoriteJourneyPair(StationMatch("490012553B", "St Pancras"), waterloo, failing, noBuses, journeys, worker = StandardTestDispatcher(testScheduler)),
        )
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    @Test
    fun `two stations sharing a bus stop outside are still two`() = runTest {
        val journeys = Journeys(emptyList())
        // Both stations' fixtures stand at one position, so one bus stop is within reach of each.
        val shared = Buses(listOf(StopLocation("490EXAMPLEE", "Stop E", 51.5, -0.12, listOf(LineRef("X9", "X9", "bus")))))
        assertNull(addFavoriteJourneyPair(kingsCross, canadaWater, finder, shared, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(1, journeys.pending.value!!.size)
    }

    @Test
    fun `a bus stop two stations share leaves the rest of their stops to find a line`() = runTest {
        // Two stations about 200 m apart, a stop between them on the X9 that both count as their own, and
        // the X9 again at a stop by each alone (synthetic ids and positions).
        val one = StopLocation("940GEXAMPLE1", "Station One", 51.5000, -0.12, listOf(LineRef("northern", "Northern", "tube")))
        val two = StopLocation("940GEXAMPLE2", "Station Two", 51.5018, -0.12, listOf(LineRef("jubilee", "Jubilee", "tube")))
        val finder = Finder(mapOf("940GEXAMPLE1" to listOf(one), "940GEXAMPLE2" to listOf(two)))
        val between = StopLocation("490EXAMPLEH", "Stop H", 51.5009, -0.12, listOf(LineRef("X9", "X9", "bus")))
        val byOne = StopLocation("490EXAMPLEJ", "Stop J", 51.4990, -0.12, listOf(LineRef("X9", "X9", "bus")))
        val byTwo = StopLocation("490EXAMPLEK", "Stop K", 51.5028, -0.12, listOf(LineRef("X9", "X9", "bus")))
        val journeys = Journeys(emptyList())
        assertNull(
            addFavoriteJourneyPair(
                StationMatch("940GEXAMPLE1", "Station One"),
                StationMatch("940GEXAMPLE2", "Station Two"),
                finder,
                Buses(listOf(between, byOne, byTwo)),
                journeys,
                worker = StandardTestDispatcher(testScheduler),
            ),
        )
        val saved = journeys.state.value!!.single()
        assertEquals("X9", saved.lineId)
        assertEquals(setOf("490EXAMPLEJ", "490EXAMPLEK"), setOf(saved.from.stopId, saved.to.stopId))
    }

    @Test
    fun `a favorite place at an end is saved grayed`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        val home = PendingEnd.Place("place-1", "Home")
        val station = PendingEnd.Station("940GZZLUWLO", "Waterloo")
        assertNull(addPendingJourneyPair(home, station, journeys, worker = worker))
        assertEquals(listOf(PendingJourney(home, station)), journeys.pending.value)
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(JourneyAddNote.AlreadySaved("Waterloo", "Home"), addPendingJourneyPair(station, home, journeys, worker = worker))
        assertEquals(JourneyAddNote.SameStation("Home", "Home"), addPendingJourneyPair(home, home, journeys, worker = worker))
        assertEquals(1, journeys.pending.value!!.size)
        // Unreadable: kept as it is, so said rather than passed off as added.
        val unreadable = Journeys(null)
        assertEquals(JourneyAddNote.NotSaved("Home", "Waterloo"), addPendingJourneyPair(home, station, unreadable, worker = worker))
        assertNull(unreadable.pending.value)
    }

    @Test
    fun `a place pair is saved on the worker, not the caller`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "add-worker") }
        try {
            var ranOn: String? = null
            val journeys = object : FavoriteJourneysStore by Journeys(emptyList()) {
                override suspend fun addPending(journey: PendingJourney) {
                    ranOn = Thread.currentThread().name
                }
            }
            runBlocking {
                addPendingJourneyPair(PendingEnd.Place("place-1", "Home"), PendingEnd.Station("940GZZLUWLO", "Waterloo"), journeys, worker = worker.asCoroutineDispatcher())
            }
            assertTrue(ranOn.orEmpty().startsWith("add-worker"))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun `why a pair isn't added is said`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        assertEquals(JourneyAddNote.SameStation("Waterloo", "Waterloo"), addFavoriteJourneyPair(waterloo, waterloo, finder, noBuses, journeys, worker = worker))
        assertEquals(
            JourneyAddNote.LookupFailed("King's Cross St. Pancras", "Waterloo", DeparturesUiState.Error.Kind.OFFLINE),
            addFavoriteJourneyPair(kingsCross, waterloo, Finder(emptyMap(), TflException.Offline(null)), noBuses, journeys, worker = worker),
        )
        assertEquals(
            JourneyAddNote.NotSaved("King's Cross St. Pancras", "Waterloo"),
            addFavoriteJourneyPair(kingsCross, waterloo, finder, noBuses, Journeys(emptyList(), failAdd = true), worker = worker),
        )
        assertTrue(journeys.state.value!!.isEmpty())
        // A file this build can't read is kept as it is, so nothing would be saved: said, not passed off as added.
        val unreadable = Journeys(null)
        assertEquals(JourneyAddNote.NotSaved("King's Cross St. Pancras", "Waterloo"), addFavoriteJourneyPair(kingsCross, waterloo, finder, noBuses, unreadable, worker = worker))
        assertEquals(null, unreadable.state.value)
    }

    @Test
    fun `a pair already saved is said, either way round`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        addFavoriteJourneyPair(kingsCross, waterloo, finder, noBuses, journeys, worker = worker)
        assertEquals(
            JourneyAddNote.AlreadySaved("Waterloo", "King's Cross St. Pancras"),
            addFavoriteJourneyPair(waterloo, kingsCross, finder, noBuses, journeys, worker = worker),
        )
        assertEquals(1, journeys.state.value!!.size)
    }

    @Test
    fun `the lookup and save run on the worker, not the caller`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "add-worker") }
        try {
            var ranOn: String? = null
            // A new journey is saved through addReplacingPending, which drops any grayed copy in the same write.
            val journeys = object : FavoriteJourneysStore by Journeys(emptyList()) {
                override suspend fun addReplacingPending(journey: FavoriteJourney, grayed: PendingJourney) {
                    ranOn = Thread.currentThread().name
                }
            }
            runBlocking { addFavoriteJourneyPair(kingsCross, waterloo, finder, noBuses, journeys, worker = worker.asCoroutineDispatcher()) }
            assertTrue(ranOn.orEmpty().startsWith("add-worker"))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun `only the latest pair's outcome is said`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val first = CompletableDeferred<JourneyAddNote?>()
        JourneyAdds.attempt(scope, kingsCross, waterloo) { first.await() }
        assertEquals(JourneyAddNote.Adding("King's Cross St. Pancras", "Waterloo"), JourneyAdds.note.value)
        JourneyAdds.attempt(scope, kingsCross, canadaWater) { JourneyAddNote.AlreadySaved("King's Cross St. Pancras", "Canada Water") }
        scope.testScheduler.advanceUntilIdle()
        first.complete(JourneyAddNote.NotSaved("King's Cross St. Pancras", "Waterloo"))
        scope.testScheduler.advanceUntilIdle()
        assertEquals(JourneyAddNote.AlreadySaved("King's Cross St. Pancras", "Canada Water"), JourneyAdds.note.value)
    }
}
