package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceHits
import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.PlaceIndex
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.LineRef
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import app.stopdash.domain.YourStops
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // For a station's stops (StationStopsViewModel); the search asks TfL nothing.
    private class FakeFinder(
        val stops: suspend (String) -> List<StopLocation> = { emptyList() },
    ) : StationFinder {
        override suspend fun searchStations(query: String): List<StationMatch> = emptyList()
        override suspend fun stationStops(id: String): List<StopLocation> = stops(id)
    }

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
    private val oxfordStation = IndexedStation(oxford.id, oxford.name, oxford.modes)

    private fun searchVm(
        saved: SavedStateHandle = SavedStateHandle(),
        index: StationIndex = StationIndex.EMPTY,
        buses: BusStopIndex = BusStopIndex.EMPTY,
    ) = StationSearchViewModel(saved, loadIndex = { index }, loadBusStops = { buses }, io = dispatcher, compute = dispatcher)

    @Test
    fun `a To picker lists saved favorite places, kept across a clear`() = runTest {
        val places = listOf(FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12)))
        val vm = StationSearchViewModel(io = dispatcher, compute = dispatcher, loadPlaces = { places })
        advanceUntilIdle()
        assertEquals(places, vm.state.value.favoritePlaces)
        assertFalse(vm.state.value.favoritePlacesFailed)
        assertTrue(vm.state.value.yoursRead)
        // Closing the picker forgets the query but keeps the favorites, so reopening shows them at once.
        vm.onQueryChange("oxf")
        vm.clear()
        assertEquals(places, vm.state.value.favoritePlaces)
        assertEquals("", vm.state.value.query)
    }

    @Test
    fun `a favorite-places read failure is a retryable state, not silently empty`() = runTest {
        // Null from the loader = couldn't read (as opposed to an empty list = genuinely none).
        val vm = StationSearchViewModel(io = dispatcher, compute = dispatcher, loadPlaces = { null })
        advanceUntilIdle()
        assertTrue(vm.state.value.favoritePlaces.isEmpty())
        assertTrue(vm.state.value.favoritePlacesFailed)
        // Kept across a clear, so reopening still shows the honest notice until the next read.
        vm.clear()
        assertTrue(vm.state.value.favoritePlacesFailed)
    }

    // A bundled place index from synthetic lines (AGENTS *Privacy*).
    private fun placeIndex(vararg lines: String): PlaceIndex = PlaceIndex.read(
        java.io.BufferedReader(java.io.StringReader((listOf("# stopdash place index v1\ttest") + lines).joinToString("\n"))),
    )

    @Test
    fun `a To search lists the bundled places it finds`() = runTest {
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadPlaceIndex = { placeIndex("P\tZeta Gallery\tmuseum\t51.50000\t-0.12000\t9", "S\tZeta Road\t\t51.50000\t-0.12000\t4") },
        )
        runCurrent()
        vm.onQueryChange("zeta")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Zeta Gallery", "Zeta Road"), result.places.map { it.name })
        assertEquals(listOf(PlaceKind.PLACE, PlaceKind.STREET), result.places.map { it.kind })
    }

    @Test
    fun `a postcode-shaped query lists bundled postcodes, not places named like one`() = runTest {
        val places = Array(PlaceHits.DEFAULT_LIMIT + 2) { "P\tXX1$it Hall\tcafe\t51.50000\t-0.12000\t9" }
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadPlaceIndex = { placeIndex(*places, "C\tXX1 1AA\t\t51.50000\t-0.12000\t4") },
        )
        runCurrent()
        vm.onQueryChange("xx1")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("XX1 1AA"), result.places.map { it.name })
        assertEquals(PlaceKind.POSTCODE, result.places.single().kind)
    }

    @Test
    fun `each stop row carries the postcode district it's in`() = runTest {
        // Two same-named bus stops in different synthetic districts, told apart by the district.
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadBusStops = { busStops(busStop("49000001A", "Zeta Church", 51.5, -0.12), busStop("49000002B", "Zeta Church", 51.55, -0.2)) },
            loadPlaceIndex = { placeIndex("C\tX1 1AA\t\t51.50010\t-0.12000\t4", "C\tX2 2BB\t\t51.55010\t-0.20000\t4") },
        )
        runCurrent()
        vm.onQueryChange("zeta church")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(mapOf("49000001A" to "X1", "49000002B" to "X2"), result.areas)
    }

    @Test
    fun `a typed starred stop saved under its stop area keeps its district`() = runTest {
        val starred = StationMatch("490G00000001", "Zeta Church", listOf("bus"))
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadYours = { YourStops(favorites = listOf(starred)) },
            loadBusStops = { busStops(busStop("49000001A", "Zeta Church", 51.5, -0.12).copy(clusterId = "490G00000001")) },
            loadPlaceIndex = { placeIndex("C\tX1 1AA\t\t51.50010\t-0.12000\t4") },
        )
        advanceUntilIdle()
        vm.onQueryChange("zeta church")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals("X1", result.areas["490G00000001"])
    }

    @Test
    fun `a search that lists no places still gives stops their district`() = runTest {
        // The favorite-journey picker: the place index is read for districts only.
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher, searchPlaces = false,
            loadBusStops = { busStops(busStop("49000001A", "Zeta Church", 51.5, -0.12)) },
            loadPlaceIndex = { placeIndex("C\tX1 1AA\t\t51.50010\t-0.12000\t4", "P\tZeta Church Hall\tcafe\t51.50000\t-0.12000\t9") },
        )
        runCurrent()
        vm.onQueryChange("zeta church")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertTrue(result.places.isEmpty())
        assertEquals(mapOf("49000001A" to "X1"), result.areas)
    }

    @Test
    fun `starred and recent stops carry their postcode district too`() = runTest {
        // A starred bus stop is saved under its stop area, not its pole: the area's position is its
        // poles' middle, from the bundled bus stops.
        val starred = StationMatch("490G00000001", "Zeta Church", listOf("bus"))
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadYours = { YourStops(favorites = listOf(starred)) },
            loadBusStops = {
                busStops(
                    busStop("49000001A", "Zeta Church", 51.4999, -0.12).copy(clusterId = "490G00000001"),
                    busStop("49000001B", "Zeta Church", 51.5001, -0.12).copy(clusterId = "490G00000001"),
                )
            },
            loadPlaceIndex = { placeIndex("C\tX1 1AA\t\t51.50010\t-0.12000\t4") },
        )
        advanceUntilIdle()
        assertEquals(mapOf("490G00000001" to "X1"), vm.state.value.yourAreas)
    }

    // TfL's places outside London, synthetic (AGENTS *Privacy*).
    private val zetaTown = PlaceCandidate("Zeta Town", Coordinates(52.0, -0.5))

    @Test
    fun `a place outside London is offered, and asked of TfL only when tapped`() = runTest {
        val asked = mutableListOf<String>()
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            // One answer in London, which the bundle covers, is left out of "Outside London".
            findOutside = { asked += it; listOf(PlaceCandidate("Zeta Town Hall", Coordinates(51.5, -0.12)), zetaTown) },
        )
        runCurrent()
        vm.onQueryChange("zeta town")
        advanceUntilIdle()
        // Nothing bundled matches, and nothing is sent until the rider taps "Search outside London".
        assertEquals(StationSearchViewModel.Result.NoMatches, vm.state.value.result)
        assertEquals(OutsideLondon.Offered, vm.state.value.outside)
        assertTrue(asked.isEmpty())
        vm.searchOutside()
        advanceUntilIdle()
        assertEquals(listOf("zeta town"), asked)
        val found = vm.state.value.outside as OutsideLondon.Found
        assertEquals(listOf(PlaceHit("Zeta Town", Coordinates(52.0, -0.5), PlaceKind.PLACE)), found.places)
        // A new query starts over: hidden until its own search finishes.
        vm.onQueryChange("zeta")
        assertEquals(OutsideLondon.Hidden, vm.state.value.outside)
    }

    @Test
    fun `a failed search outside London says so and retries, leaving the bundled rows`() = runTest {
        var fail = true
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadPlaceIndex = { placeIndex("P\tZeta Gallery\tmuseum\t51.50000\t-0.12000\t9") },
            findOutside = { if (fail) throw TflException.Unreachable("test", null) else listOf(zetaTown) },
        )
        runCurrent()
        vm.onQueryChange("zeta")
        advanceUntilIdle()
        vm.searchOutside()
        advanceUntilIdle()
        assertEquals(OutsideLondon.Failed, vm.state.value.outside)
        assertEquals(listOf("Zeta Gallery"), (vm.state.value.result as StationSearchViewModel.Result.Matches).places.map { it.name })
        fail = false
        vm.searchOutside()
        advanceUntilIdle()
        assertEquals(listOf("Zeta Town"), (vm.state.value.outside as OutsideLondon.Found).places.map { it.name })
    }

    @Test
    fun `a complete postcode the bundle doesn't hold is asked of TfL at once, one it holds is not`() = runTest {
        val asked = mutableListOf<String>()
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadPlaceIndex = { placeIndex("C\tX1 9XX\t\t51.52000\t-0.11000\t4") },
            findOutside = { asked += it; listOf(PlaceCandidate("X2 9XX", Coordinates(52.0, -0.5))) },
        )
        runCurrent()
        vm.onQueryChange("X1 9XX")
        advanceUntilIdle()
        assertTrue(asked.isEmpty())
        assertEquals(OutsideLondon.Offered, vm.state.value.outside)
        vm.onQueryChange("X2 9XX")
        advanceUntilIdle()
        assertEquals(listOf("X2 9XX"), asked)
        assertEquals(PlaceKind.POSTCODE, (vm.state.value.outside as OutsideLondon.Found).places.single().kind)
    }

    @Test
    fun `a search with no outside lookup offers none`() = runTest {
        val vm = StationSearchViewModel(io = dispatcher, compute = dispatcher)
        runCurrent()
        vm.onQueryChange("zeta town")
        advanceUntilIdle()
        assertEquals(OutsideLondon.Hidden, vm.state.value.outside)
    }

    // Bundled bus stops, synthetic (AGENTS *Privacy*): each a bus stop at a synthetic position.
    private fun busStop(id: String, name: String, latitude: Double = 51.5, longitude: Double = -0.12) =
        StopLocation(id, name, latitude, longitude, lines = listOf(LineRef("1", "1", "bus")))

    private fun busStops(vararg stops: StopLocation): BusStopIndex = BusStopIndex.of(stops.toList())

    @Test
    fun `bundled bus stops are ranked with the stations by how well they match`() = runTest {
        // The bus stop's name is the query; the station's only starts with it.
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(listOf(IndexedStation("940GZZZETA", "Zeta Roadside Junction", listOf("tube")))) },
            loadBusStops = { busStops(busStop("4900A", "Zeta Road")) },
            io = dispatcher, compute = dispatcher,
        )
        runCurrent()
        vm.onQueryChange("zeta road")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("4900A", "940GZZZETA"), result.matches.map { it.id })
        assertEquals(StationMatch("4900A", "Zeta Road", listOf("bus"), 51.5, -0.12), result.matches.first())
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `bundled bus stops stay after a stop is opened`() = runTest {
        val station = IndexedStation("940GZZZOO", "Zoo Road", listOf("tube"))
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(listOf(station)) },
            loadBusStops = { busStops(busStop("4900A", "Zeta Road")) },
            io = dispatcher, compute = dispatcher,
        )
        runCurrent()
        // Both match "zrd" only by letters in order.
        vm.onQueryChange("zrd")
        advanceUntilIdle()
        val before = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(setOf("940GZZZOO", "4900A"), before.matches.map { it.id }.toSet())
        vm.onOpened(before.matches.first { it.id == "940GZZZOO" })
        advanceUntilIdle()
        val after = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(setOf("940GZZZOO", "4900A"), after.matches.map { it.id }.toSet())
    }

    @Test
    fun `bundled places read late are listed while the bus stops are still being read`() = runTest {
        val indexRead = CompletableDeferred<Unit>()
        val busesRead = CompletableDeferred<BusStopIndex>()
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadBusStops = { busesRead.await() },
            loadPlaceIndex = { indexRead.await(); placeIndex("P\tZeta Gallery\tmuseum\t51.50000\t-0.12000\t9") },
        )
        vm.onQueryChange("zeta")
        runCurrent()
        // The index is read only now, with the bus stops still unread.
        indexRead.complete(Unit)
        runCurrent()
        val early = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Zeta Gallery"), early.places.map { it.name })
        assertTrue(vm.state.value.searching)
        busesRead.complete(BusStopIndex.EMPTY)
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Zeta Gallery"), result.places.map { it.name })
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `bundled places are listed while the saved stops are still being read`() = runTest {
        // The place index is ready; the rider's saved stops aren't. The places don't wait for them.
        val yoursRead = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadYours = { yoursRead.await(); YourStops.EMPTY },
            loadPlaceIndex = { placeIndex("P\tZeta Gallery\tmuseum\t51.50000\t-0.12000\t9") },
        )
        runCurrent()
        vm.onQueryChange("zeta")
        runCurrent()
        val early = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Zeta Gallery"), early.places.map { it.name })
        yoursRead.complete(Unit)
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Zeta Gallery"), result.places.map { it.name })
    }

    @Test
    fun `an address's street is found in the bundle`() = runTest {
        // The house number isn't in the bundle, so the street is what's searched.
        val vm = StationSearchViewModel(
            io = dispatcher, compute = dispatcher,
            loadPlaceIndex = { placeIndex("S\tZeta Road\t\t51.50000\t-0.12000\t4") },
        )
        vm.onQueryChange("50 zeta road")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Zeta Road"), result.places.map { it.name })
    }

    @Test
    fun `a plain station search lists no places`() = runTest {
        // No place index (a From… or browse search): only stops.
        val vm = searchVm(index = StationIndex(listOf(oxfordStation)))
        vm.onQueryChange("oxford")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf(oxford), result.matches)
        assertTrue(result.places.isEmpty())
    }

    @Test
    fun `the index's best few show while the bus stops are read, and the bus stops only add below them`() = runTest {
        // Six bundled stations that contain "zeta" (Substring), and a bus stop and a place the query
        // starts (Prefix) that would rank above them. Synthetic names (AGENTS *Privacy*).
        val bundled = (1..6).map { IndexedStation("940GZZ$it", "Upper Bezeta $it", listOf("tube")) }
        val busesRead = CompletableDeferred<BusStopIndex>()
        val placesRead = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(bundled) },
            loadBusStops = { busesRead.await() },
            loadPlaceIndex = { placesRead.await(); placeIndex("P\tZeta Gallery\tmuseum\t51.50000\t-0.12000\t9") },
            io = dispatcher, compute = dispatcher,
        )
        fun rows() = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries.map {
            when (it) {
                is SearchEntry.Stop -> it.match.name
                is SearchEntry.Place -> it.hit.name
            }
        }
        vm.onQueryChange("zeta")
        runCurrent()
        // Before the bus stops are read: the index's best four, with more on its way.
        val preview = rows()
        assertEquals(StationSearchViewModel.LOCAL_PREVIEW, preview.size)
        assertTrue(vm.state.value.searching)
        busesRead.complete(busStops(busStop("490ZETA", "Zeta Lane")))
        runCurrent()
        // The bus stop, although it matches better, joins below the rows already up, with the rest of
        // the index's matches (maintainer, 2026-09-28: append, don't reorder).
        val withStops = rows()
        assertEquals(preview, withStops.take(preview.size))
        assertEquals("Zeta Lane", withStops[preview.size])
        assertEquals(7, withStops.size)
        placesRead.complete(Unit)
        advanceUntilIdle()
        val all = rows()
        assertEquals(withStops, all.take(withStops.size))
        assertEquals("Zeta Gallery", all.last())
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `a previewed row the bus stops' positions fold into its twin leaves rather than stay a duplicate`() = runTest {
        // Two of the rider's own stops of one name, matched as typed, told apart only once the bundled
        // bus stops place them side by side.
        val a = StationMatch("490ZETAA", "Zeta Road", listOf("bus"))
        val b = StationMatch("490ZETAB", "Zeta Road", listOf("bus"))
        val busesRead = CompletableDeferred<BusStopIndex>()
        val vm = StationSearchViewModel(
            loadYours = { YourStops(recent = listOf(a, b)) },
            loadBusStops = { busesRead.await() },
            io = dispatcher, compute = dispatcher,
        )
        fun keys() = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries.map { it.key }
        vm.onQueryChange("zeta")
        runCurrent()
        assertEquals(listOf("490ZETAA", "490ZETAB"), keys())
        busesRead.complete(busStops(busStop(a.id, a.name), busStop(b.id, b.name)))
        advanceUntilIdle()
        assertEquals(listOf("490ZETAA"), keys())
    }

    @Test
    fun `a previewed row pushed past the result cap by better bus stops stays where it is`() = runTest {
        // More bus stops match better than the cap holds; the index's rows are still up and stay.
        val bundled = (1..4).map { IndexedStation("940GZZ$it", "Upper Bezeta $it", listOf("tube")) }
        val busesRead = CompletableDeferred<BusStopIndex>()
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(bundled) },
            loadBusStops = { busesRead.await() },
            io = dispatcher, compute = dispatcher,
        )
        fun keys() = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries.map { it.key }
        vm.onQueryChange("zeta")
        runCurrent()
        val preview = keys()
        assertEquals(4, preview.size)
        busesRead.complete(busStops(*(1..StationIndex.DEFAULT_LIMIT).map { busStop("490ZETA$it", "Zeta Lane $it") }.toTypedArray()))
        advanceUntilIdle()
        assertEquals(preview, keys().take(preview.size))
    }

    @Test
    fun `a query restored after process death is kept and searched again`() = runTest {
        val index = StationIndex(listOf(oxfordStation))
        val first = SavedStateHandle()
        searchVm(first, index).onQueryChange("oxford")
        val restored = searchVm(SavedStateHandle(mapOf("query" to first.get<String>("query"))), index)
        assertEquals("oxford", restored.state.value.query)
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.Result.Matches(listOf(oxford)), restored.state.value.result)
    }

    @Test
    fun `closing the search forgets the query and its search`() = runTest {
        val saved = SavedStateHandle()
        val vm = searchVm(saved, StationIndex(listOf(oxfordStation)))
        vm.onQueryChange("oxford")
        vm.clear()
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.State(yoursRead = true), vm.state.value)
        assertEquals(null, saved.get<String>("query"))
    }

    private val kingsCross = IndexedStation("HUBKGX", "King's Cross St. Pancras", listOf("tube"))

    private val favoriteStop = StationMatch("490000000001A", "Example Road", listOf("bus"))

    @Test
    fun `the user's own stops are listed before anything is typed`() = runTest {
        val recent = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
        val vm = StationSearchViewModel(
            loadYours = { YourStops(favorites = listOf(favoriteStop), recent = listOf(recent)) },
            io = dispatcher, compute = dispatcher,
        )
        assertFalse("not read yet", vm.state.value.yoursRead)
        advanceUntilIdle()
        assertTrue(vm.state.value.yoursRead)
        assertEquals(listOf(favoriteStop), vm.state.value.favorites)
        assertEquals(listOf(SearchEntry.Stop(recent)), vm.state.value.recent)
    }

    @Test
    fun `the home's refresh while the first read is still going doesn't read again, a later one does`() = runTest {
        var reads = 0
        val vm = StationSearchViewModel(
            loadYours = { reads++; YourStops(favorites = listOf(favoriteStop), recent = emptyList()) },
            io = dispatcher, compute = dispatcher,
        )
        vm.refreshYoursUnlessReading()
        advanceUntilIdle()
        assertEquals(1, reads)
        vm.refreshYoursUnlessReading()
        advanceUntilIdle()
        assertEquals(2, reads)
        // Find a station's own refresh, still going when the home comes back, is reused too.
        vm.refreshYours()
        vm.refreshYoursUnlessReading()
        advanceUntilIdle()
        assertEquals(3, reads)
    }

    @Test
    fun `recent stations for the home without location leave out the starred ones`() = runTest {
        val recent = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
        val vm = StationSearchViewModel(
            loadYours = { YourStops(favorites = listOf(favoriteStop), recent = listOf(recent, favoriteStop)) },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(listOf(recent), vm.state.value.recentStations)
        // Kept when the search closes, as the rest of the rider's own stops are.
        vm.clear()
        assertEquals(listOf(recent), vm.state.value.recentStations)
    }

    @Test
    fun `the home's station lists are bounded however much the rider has saved`() = runTest {
        val starred = (1..15).map { StationMatch("HUB$it", "Station $it", listOf("tube")) }
        val recent = (1..15).map { StationMatch("940G$it", "Recent $it", listOf("tube")) }
        val vm = StationSearchViewModel(
            loadYours = { YourStops(favorites = starred, recent = recent) },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(starred.take(YourStops.HOME_ROWS), vm.state.value.homeStarred)
        assertEquals(recent.take(YourStops.HOME_ROWS), vm.state.value.recentStations)
        // The search's own lists stay whole.
        assertEquals(starred, vm.state.value.favorites)
    }

    @Test
    fun `a starred station the device couldn't name is named from the bundled index`() = runTest {
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(listOf(kingsCross)) },
            loadYours = { YourStops(unnamedStarred = listOf("HUBKGX")) },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(listOf("HUBKGX"), vm.state.value.favorites.map { it.id })
    }

    @Test
    fun `an older read of the user's stops that lands last doesn't overwrite a newer one`() = runTest {
        val first = CompletableDeferred<YourStops>()
        val reads = ArrayDeque(listOf<suspend () -> YourStops>({ first.await() }, { YourStops(favorites = listOf(favoriteStop)) }))
        val vm = StationSearchViewModel(loadYours = { reads.removeFirst()() }, io = dispatcher, compute = dispatcher)
        runCurrent()
        vm.refreshYours()
        advanceUntilIdle()
        assertEquals(listOf(favoriteStop), vm.state.value.favorites)
        first.complete(YourStops())
        advanceUntilIdle()
        assertEquals("the stale first read is ignored", listOf(favoriteStop), vm.state.value.favorites)
    }

    @Test
    fun `a starred bus stop matches as the user types`() = runTest {
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(listOf(kingsCross)) },
            loadYours = { YourStops(favorites = listOf(favoriteStop)) },
            io = dispatcher, compute = dispatcher,
        )
        vm.onQueryChange("example")
        advanceUntilIdle()
        assertEquals(listOf(favoriteStop), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches)
    }

    @Test
    fun `opening a match remembers it and rereads the recent list`() = runTest {
        val opened = mutableListOf<StationMatch>()
        val vm = StationSearchViewModel(
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened.add(0, it) },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        vm.onOpened(oxford)
        advanceUntilIdle()
        assertEquals(listOf(SearchEntry.Stop(oxford)), vm.state.value.recent)
    }

    @Test
    fun `picking a place remembers it, and lists it under Recent above the stop picked before it`() = runTest {
        val picks = mutableListOf<SearchEntry>()
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        val vm = StationSearchViewModel(
            loadYours = {
                YourStops(
                    recent = picks.filterIsInstance<SearchEntry.Stop>().map { it.match },
                    recentPicks = picks.toList(),
                )
            },
            recordOpen = { picks.add(0, SearchEntry.Stop(it)) },
            recordPlace = { picks.add(0, SearchEntry.Place(it)) },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        vm.onOpened(oxford)
        vm.onPlaceOpened(gallery)
        advanceUntilIdle()
        assertEquals(listOf(SearchEntry.Place(gallery), SearchEntry.Stop(oxford)), vm.state.value.recent)
        // And kept when the search closes, as the stops are.
        vm.clear()
        assertEquals(listOf(SearchEntry.Place(gallery), SearchEntry.Stop(oxford)), vm.state.value.recent)
    }

    @Test
    fun `removing a recent pick takes it off the list at once, then from the device`() = runTest {
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        val picks = mutableListOf<SearchEntry>(SearchEntry.Stop(oxford), SearchEntry.Place(gallery))
        val vm = StationSearchViewModel(
            loadYours = {
                YourStops(
                    recent = picks.filterIsInstance<SearchEntry.Stop>().map { it.match },
                    recentPicks = picks.toList(),
                )
            },
            recordRemove = { removed -> picks.removeAll { it.key == removed.key } },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(listOf(SearchEntry.Stop(oxford), SearchEntry.Place(gallery)), vm.state.value.recent)
        vm.onRemoveRecent(SearchEntry.Stop(oxford))
        runCurrent()
        // Gone from the list before the write runs.
        assertEquals(listOf<SearchEntry>(SearchEntry.Place(gallery)), vm.state.value.recent)
        advanceUntilIdle()
        assertEquals(listOf<SearchEntry>(SearchEntry.Place(gallery)), picks)
        assertEquals(listOf<SearchEntry>(SearchEntry.Place(gallery)), vm.state.value.recent)
    }

    @Test
    fun `a read under way when a row is removed doesn't bring it back`() = runTest {
        val picks = mutableListOf<SearchEntry>(SearchEntry.Stop(oxford))
        var gate: CompletableDeferred<Unit>? = null
        val writing = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            loadYours = {
                val read = YourStops(recent = picks.filterIsInstance<SearchEntry.Stop>().map { it.match }, recentPicks = picks.toList())
                gate?.await()
                read
            },
            recordRemove = { removed -> writing.await(); picks.removeAll { it.key == removed.key } },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        // The search reopens: a read starts, has the row, and is held.
        val held = CompletableDeferred<Unit>()
        gate = held
        vm.refreshYours()
        runCurrent()
        gate = null
        // The row is removed, its write still running, when the held read lands.
        vm.onRemoveRecent(SearchEntry.Stop(oxford))
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals(emptyList<SearchEntry>(), vm.state.value.recent)
        writing.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<SearchEntry>(), vm.state.value.recent)
    }

    @Test
    fun `a long press's question is held until confirmed or canceled`() = runTest {
        val vm = StationSearchViewModel(recordRemove = { true }, io = dispatcher, compute = dispatcher)
        advanceUntilIdle()
        vm.askRemoveRecent(SearchEntry.Stop(oxford))
        assertEquals(SearchEntry.Stop(oxford), vm.state.value.pendingRemoval)
        vm.cancelRemoveRecent()
        assertEquals(null, vm.state.value.pendingRemoval)
        vm.askRemoveRecent(SearchEntry.Stop(oxford))
        vm.onRemoveRecent(SearchEntry.Stop(oxford))
        assertEquals(null, vm.state.value.pendingRemoval)
    }

    @Test
    fun `a second removal stays gone while the first one's write lands and reads back`() = runTest {
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val file = mutableListOf<SearchEntry>(SearchEntry.Stop(oxford), SearchEntry.Stop(bank))
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            loadYours = { YourStops(recent = file.filterIsInstance<SearchEntry.Stop>().map { it.match }, recentPicks = file.toList()) },
            recordRemove = { removed ->
                (if (removed.key == oxford.key) first else second).await()
                file.removeAll { it.key == removed.key }
                true
            },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        vm.onRemoveRecent(SearchEntry.Stop(oxford))
        vm.onRemoveRecent(SearchEntry.Stop(bank))
        runCurrent()
        // Both gone at once, the second not waiting on the first one's write.
        assertEquals(emptyList<SearchEntry>(), vm.state.value.recent)
        // The first write lands and reads the file back, Bank still on it: Bank stays gone.
        first.complete(Unit)
        runCurrent()
        assertEquals(emptyList<SearchEntry>(), vm.state.value.recent)
        second.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList<SearchEntry>(), vm.state.value.recent)
        assertEquals(emptyList<SearchEntry>(), file)
    }

    @Test
    fun `one removal failing is still said after another succeeds`() = runTest {
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val file = mutableListOf<SearchEntry>(SearchEntry.Stop(oxford), SearchEntry.Stop(bank))
        val vm = StationSearchViewModel(
            loadYours = { YourStops(recent = file.filterIsInstance<SearchEntry.Stop>().map { it.match }, recentPicks = file.toList()) },
            recordRemove = { removed -> if (removed.key == oxford.key) false else { file.removeAll { it.key == removed.key }; true } },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        vm.onRemoveRecent(SearchEntry.Stop(oxford))
        vm.onRemoveRecent(SearchEntry.Stop(bank))
        advanceUntilIdle()
        assertTrue(vm.state.value.removeRecentFailed)
        assertEquals(listOf<SearchEntry>(SearchEntry.Stop(oxford)), vm.state.value.recent)
    }

    @Test
    fun `a removal asked about before process death is asked again once Recent is read`() = runTest {
        val picks = listOf<SearchEntry>(SearchEntry.Stop(oxford))
        val saved = SavedStateHandle()
        val before = StationSearchViewModel(saved, loadYours = { YourStops(recent = listOf(oxford), recentPicks = picks) }, io = dispatcher, compute = dispatcher)
        advanceUntilIdle()
        before.askRemoveRecent(SearchEntry.Stop(oxford))
        // A new model over the saved state, as after process death.
        val after = StationSearchViewModel(
            SavedStateHandle(mapOf("pendingRemoval" to saved.get<String>("pendingRemoval"))),
            loadYours = { YourStops(recent = listOf(oxford), recentPicks = picks) },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(SearchEntry.Stop(oxford), after.state.value.pendingRemoval)
        after.cancelRemoveRecent()
        // Canceled, nothing is asked again on the next read.
        after.refreshYours()
        advanceUntilIdle()
        assertEquals(null, after.state.value.pendingRemoval)
    }

    @Test
    fun `a removal confirmed while a read loads the places stays gone when that read lands`() = runTest {
        val picks = listOf<SearchEntry>(SearchEntry.Stop(oxford))
        var gate: CompletableDeferred<Unit>? = null
        val writing = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            loadYours = { YourStops(recent = listOf(oxford), recentPicks = picks) },
            loadPlaces = { gate?.await(); emptyList() },
            recordRemove = { writing.await(); true },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        // A read has the stops and waits on the places.
        val held = CompletableDeferred<Unit>()
        gate = held
        vm.refreshYours()
        runCurrent()
        vm.onRemoveRecent(SearchEntry.Stop(oxford))
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals(emptyList<SearchEntry>(), vm.state.value.recent)
        writing.complete(Unit)
    }

    @Test
    fun `a removal asked about while a read loads the places keeps its saved key`() = runTest {
        val picks = listOf<SearchEntry>(SearchEntry.Stop(oxford))
        val saved = SavedStateHandle()
        var gate: CompletableDeferred<Unit>? = null
        val vm = StationSearchViewModel(
            saved,
            loadYours = { YourStops(recent = listOf(oxford), recentPicks = picks) },
            loadPlaces = { gate?.await(); emptyList() },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        val held = CompletableDeferred<Unit>()
        gate = held
        vm.refreshYours()
        runCurrent()
        vm.askRemoveRecent(SearchEntry.Stop(oxford))
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(SearchEntry.Stop(oxford).key, saved.get<String>("pendingRemoval"))
        assertEquals(SearchEntry.Stop(oxford), vm.state.value.pendingRemoval)
    }

    @Test
    fun `a removal that couldn't be saved lists the row again and says so, until another is asked about`() = runTest {
        val picks = listOf<SearchEntry>(SearchEntry.Stop(oxford))
        val vm = StationSearchViewModel(
            loadYours = { YourStops(recent = listOf(oxford), recentPicks = picks) },
            recordRemove = { false },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        vm.onRemoveRecent(SearchEntry.Stop(oxford))
        advanceUntilIdle()
        assertEquals(picks, vm.state.value.recent)
        assertTrue(vm.state.value.removeRecentFailed)
        // Closed and shown again (the rider may have left before it failed): still said.
        vm.clear()
        vm.refreshYours()
        advanceUntilIdle()
        assertTrue(vm.state.value.removeRecentFailed)
        // The next long press: read, so no longer said.
        vm.askRemoveRecent(SearchEntry.Stop(oxford))
        assertFalse(vm.state.value.removeRecentFailed)
    }

    @Test
    fun `a removal that couldn't be saved is still said after process death, until another is asked about`() = runTest {
        val picks = listOf<SearchEntry>(SearchEntry.Stop(oxford))
        val saved = SavedStateHandle()
        val before = StationSearchViewModel(
            saved,
            loadYours = { YourStops(recent = listOf(oxford), recentPicks = picks) },
            recordRemove = { false },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        before.onRemoveRecent(SearchEntry.Stop(oxford))
        advanceUntilIdle()
        val after = StationSearchViewModel(saved, loadYours = { YourStops(recent = listOf(oxford), recentPicks = picks) }, io = dispatcher, compute = dispatcher)
        advanceUntilIdle()
        assertTrue(after.state.value.removeRecentFailed)
        after.askRemoveRecent(SearchEntry.Stop(oxford))
        assertFalse(after.state.value.removeRecentFailed)
        assertFalse(saved.contains("removeRecentFailed"))
    }

    @Test
    fun `picks are written in the order tapped, however long each write takes`() = runTest {
        // The first write is slow (the gate holds it); the second, tapped after it, must still land after it.
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<SearchEntry>()
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        val vm = StationSearchViewModel(
            recordOpen = { written.add(0, SearchEntry.Stop(it)) },
            recordPlace = { if (it == gallery) gate.await(); written.add(0, SearchEntry.Place(it)) },
            io = dispatcher, compute = dispatcher,
        )
        advanceUntilIdle()
        vm.onPlaceOpened(gallery)
        vm.onOpened(oxford)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        // The last tapped leads the recent list.
        assertEquals(listOf(SearchEntry.Stop(oxford), SearchEntry.Place(gallery)), written)
    }

    @Test
    fun `a match opened from a search leads it on the way back`() = runTest {
        val place = IndexedStation("940GZZLUAAA", "Example Place", listOf("tube"))
        val park = IndexedStation("940GZZLUBBB", "Example Park", listOf("tube"))
        val opened = mutableListOf<StationMatch>()
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(listOf(place, park)) },
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened.add(0, it) },
            io = dispatcher, compute = dispatcher,
        )
        vm.onQueryChange("example")
        advanceUntilIdle()
        val before = (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.id }
        assertEquals(listOf("940GZZLUBBB", "940GZZLUAAA"), before)
        // The lower match is picked; on Back the same query is still up, now led by it.
        vm.onOpened(StationMatch("940GZZLUAAA", "Example Place", listOf("tube")))
        advanceUntilIdle()
        val after = (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.id }
        assertEquals(listOf("940GZZLUAAA", "940GZZLUBBB"), after)
    }

    @Test
    fun `opening a bus stop re-ranks it with the stations`() = runTest {
        val place = IndexedStation("940GZZLUAAA", "Example Place", listOf("tube"))
        val opened = mutableListOf<StationMatch>()
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(listOf(place)) },
            loadBusStops = { busStops(busStop("490000000001A", "Example Ridge Road")) },
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened.add(0, it) },
            io = dispatcher, compute = dispatcher,
        )
        vm.onQueryChange("example")
        advanceUntilIdle()
        val before = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("940GZZLUAAA", "490000000001A"), before.matches.map { it.id })
        // On Back the bus stop just opened leads, still with its bundled position.
        vm.onOpened(before.matches.last())
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("490000000001A", "940GZZLUAAA"), result.matches.map { it.id })
        assertEquals(51.5, result.matches.first().latitude)
    }

    @Test
    fun `a match opened while its search is still running leads it once the search lands`() = runTest {
        val place = IndexedStation("940GZZLUAAA", "Example Place", listOf("tube"))
        val park = IndexedStation("940GZZLUBBB", "Example Park", listOf("tube"))
        val opened = mutableListOf<StationMatch>()
        val busesRead = CompletableDeferred<BusStopIndex>()
        val vm = StationSearchViewModel(
            loadIndex = { StationIndex(listOf(place, park)) },
            loadBusStops = { busesRead.await() },
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened.add(0, it) },
            io = dispatcher, compute = dispatcher,
        )
        vm.onQueryChange("example")
        runCurrent()
        // Picked from the index's matches before the bus stops are read.
        assertTrue(vm.state.value.searching)
        vm.onOpened(StationMatch("940GZZLUAAA", "Example Place", listOf("tube")))
        runCurrent()
        busesRead.complete(BusStopIndex.EMPTY)
        advanceUntilIdle()
        val after = (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.id }
        assertEquals(listOf("940GZZLUAAA", "940GZZLUBBB"), after)
    }

    @Test
    fun `a query under two characters doesn't search`() = runTest {
        val vm = searchVm(index = StationIndex(listOf(oxfordStation)))
        vm.onQueryChange(" o ")
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.Result.Idle, vm.state.value.result)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `no matches is its own answer, not an error`() = runTest {
        val vm = searchVm(index = StationIndex(listOf(oxfordStation)), buses = busStops(busStop("4900A", "Zeta Road")))
        vm.onQueryChange("qqq")
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.Result.NoMatches, vm.state.value.result)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `the previous matches stay up while the next search runs`() = runTest {
        val oxbow = IndexedStation("940GZZOXB", "Oxbow Park", listOf("tube"))
        val vm = searchVm(index = StationIndex(listOf(oxbow, oxfordStation)))
        vm.onQueryChange("oxb")
        advanceUntilIdle()
        vm.onQueryChange("oxfo")
        // Marked searching at once, the last answer still up until the new one lands.
        assertTrue(vm.state.value.searching)
        assertEquals(listOf("Oxbow Park"), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.name })
        advanceUntilIdle()
        assertFalse(vm.state.value.searching)
        assertEquals(listOf(oxford), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches)
    }

    @Test
    fun `a station resolves to its stops as departure seeds`() = runTest {
        val stop = StopLocation(
            id = "940GZZLUOXC",
            name = "Oxford Circus",
            latitude = 0.0,
            longitude = 0.0,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
            clusterId = "940GZZLUOXC",
        )
        val vm = StationStopsViewModel(FakeFinder(stops = { listOf(stop) }), "940GZZLUOXC", io = dispatcher, compute = dispatcher)
        assertEquals(StationStopsViewModel.State.Loading, vm.state.value)
        advanceUntilIdle()
        val ready = vm.state.value as StationStopsViewModel.State.Ready
        assertEquals(listOf("940GZZLUOXC"), ready.stops.map { it.id })
        assertEquals(listOf("victoria"), ready.stops.single().lines.map { it.id })
    }

    // Weybridge's two records: one place, one name and mode, no interchange (public TfL stations).
    private val weybridge = StationIndex(
        listOf(
            IndexedStation("910GWEYBDGB", "Weybridge Rail Station", listOf("national-rail"), "", 51.36176, -0.45772),
            IndexedStation("910GWEYBDGE", "Weybridge Rail Station", listOf("national-rail"), "", 51.36176, -0.45772),
        ),
    )

    @Test
    fun `a station under two ids opens both records' stops`() = runTest {
        // Weybridge's two records, no interchange: the page asks for each, once per stop.
        fun stop(id: String) = StopLocation(id, "Weybridge", 0.0, 0.0, listOf(LineRef("swr", "South Western Railway", "national-rail")), clusterId = id)
        val asked = mutableListOf<String>()
        val finder = FakeFinder(stops = { id ->
            asked += id
            listOf(stop(id), stop("910GWEYBDGB"))
        })
        val vm = StationStopsViewModel(
            finder, "910GWEYBDGB", io = dispatcher, compute = dispatcher,
            loadIndex = { weybridge },
        )
        advanceUntilIdle()
        assertEquals(listOf("910GWEYBDGB", "910GWEYBDGE"), asked)
        val ready = vm.state.value as StationStopsViewModel.State.Ready
        assertEquals(listOf("910GWEYBDGB", "910GWEYBDGE"), ready.stops.map { it.id })
    }

    @Test
    fun `one record failing fails the page, not a page missing its stops`() = runTest {
        val finder = FakeFinder(stops = { id ->
            if (id == "910GWEYBDGE") throw TflException.RateLimited(null)
            listOf(StopLocation(id, "Weybridge", 0.0, 0.0, emptyList(), clusterId = id))
        })
        val vm = StationStopsViewModel(
            finder, "910GWEYBDGB", io = dispatcher, compute = dispatcher,
            loadIndex = { weybridge },
        )
        advanceUntilIdle()
        assertEquals(StationStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.RATE_LIMITED), vm.state.value)
    }

    @Test
    fun `a station with no departure stops says so, and a failed lookup can retry`() = runTest {
        assertEquals(
            StationStopsViewModel.State.NoStops,
            StationStopsViewModel(FakeFinder(), "HUBEXA", io = dispatcher, compute = dispatcher).also { advanceUntilIdle() }.state.value,
        )
        var fail = true
        val finder = FakeFinder(stops = {
            if (fail) throw TflException.RateLimited(null) else emptyList()
        })
        val vm = StationStopsViewModel(finder, "HUBEXA", io = dispatcher, compute = dispatcher)
        advanceUntilIdle()
        assertEquals(
            StationStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.RATE_LIMITED),
            vm.state.value,
        )
        fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(StationStopsViewModel.State.NoStops, vm.state.value)
    }

    @Test
    fun `modes read as a rider says them`() {
        assertEquals("Tube · Elizabeth · National Rail", modesLabel(listOf("tube", "elizabeth-line", "national-rail")))
        assertEquals("Some mode", modesLabel(listOf("some-mode", "")))
    }
}
