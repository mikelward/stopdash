package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
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
import kotlinx.coroutines.test.advanceTimeBy
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

    private class FakeFinder(
        val search: suspend (String) -> List<StationMatch> = { emptyList() },
        val stops: suspend (String) -> List<StopLocation> = { emptyList() },
    ) : StationFinder {
        val queries = mutableListOf<String>()
        override suspend fun searchStations(query: String): List<StationMatch> {
            queries += query
            return search(query)
        }
        override suspend fun stationStops(id: String): List<StopLocation> = stops(id)
    }

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))

    private fun searchVm(finder: StationFinder, saved: SavedStateHandle = SavedStateHandle()) =
        StationSearchViewModel(finder, saved, io = dispatcher, compute = dispatcher, debounceMillis = 300)

    @Test
    fun `a To picker lists saved favorite places, kept across a clear`() = runTest {
        val places = listOf(FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12)))
        val vm = StationSearchViewModel(FakeFinder(), io = dispatcher, compute = dispatcher, debounceMillis = 300, loadPlaces = { places })
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
        val vm = StationSearchViewModel(FakeFinder(), io = dispatcher, compute = dispatcher, debounceMillis = 300, loadPlaces = { null })
        advanceUntilIdle()
        assertTrue(vm.state.value.favoritePlaces.isEmpty())
        assertTrue(vm.state.value.favoritePlacesFailed)
        // Kept across a clear, so reopening still shows the honest notice until the next read.
        vm.clear()
        assertTrue(vm.state.value.favoritePlacesFailed)
    }

    @Test
    fun `a To search surfaces geocoded places, re-ranked, alongside the stops`() = runTest {
        val vm = StationSearchViewModel(
            FakeFinder(search = { emptyList() }),
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
            // TfL's geocoder order is noisy; our matcher floats the prefix match above the weak partial.
            // Synthetic names and coordinates (AGENTS *Privacy*).
            searchPlaces = {
                listOf(
                    PlaceCandidate("Alpha Zeta", Coordinates(51.5, -0.10)),
                    PlaceCandidate("Zeta Hall", Coordinates(51.50, -0.10)),
                )
            },
        )
        vm.onQueryChange("zeta")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Zeta Hall", "Alpha Zeta"), result.places.map { it.name })
        assertEquals(PlaceKind.PLACE, result.places.first().kind)
    }

    @Test
    fun `a plain station search geocodes no places`() = runTest {
        // No searchPlaces seam (a From… or browse search): only stops, never a geocode call.
        val vm = StationSearchViewModel(FakeFinder(search = { listOf(oxford) }), io = dispatcher, compute = dispatcher, debounceMillis = 300)
        vm.onQueryChange("oxford")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertTrue(result.places.isEmpty())
    }

    @Test
    fun `a geocode failure leaves the stops standing`() = runTest {
        val vm = StationSearchViewModel(
            FakeFinder(search = { listOf(oxford) }),
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
            searchPlaces = { throw RuntimeException("geocode down") },
        )
        vm.onQueryChange("oxford")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Oxford Circus"), result.matches.map { it.name })
        assertTrue(result.places.isEmpty())
    }

    @Test
    fun `stop matches show before a slow geocode finishes, then places merge in`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            FakeFinder(search = { listOf(oxford) }),
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
            searchPlaces = {
                gate.await() // the geocode stalls until released
                listOf(PlaceCandidate("Oxford Point", Coordinates(51.5, -0.1)))
            },
        )
        vm.onQueryChange("oxford")
        advanceUntilIdle()
        // The stop matches are on screen although the geocode hasn't answered.
        val partial = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Oxford Circus"), partial.matches.map { it.name })
        assertTrue(partial.places.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        val full = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Oxford Circus"), full.matches.map { it.name })
        assertEquals(listOf("Oxford Point"), full.places.map { it.name })
    }

    @Test
    fun `the index's best few show at once, and TfL's answers only add below them`() = runTest {
        // Six bundled stations that contain "zeta" (Substring), and TfL answers a stop and a place the
        // query starts (Prefix) that would rank above them. Synthetic names (AGENTS *Privacy*).
        val bundled = (1..6).map { IndexedStation("940GZZ$it", "Upper Bezeta $it", listOf("tube")) }
        val stopGate = CompletableDeferred<Unit>()
        val placeGate = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            FakeFinder(search = {
                stopGate.await()
                listOf(StationMatch("490ZETA", "Zeta Lane", listOf("bus")))
            }),
            loadIndex = { StationIndex(bundled) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
            searchPlaces = {
                placeGate.await()
                listOf(PlaceCandidate("Alpha District, Zeta Gallery", Coordinates(51.5, -0.1)))
            },
        )
        fun rows() = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries.map {
            when (it) {
                is SearchEntry.Stop -> it.match.name
                is SearchEntry.Place -> it.hit.name
            }
        }
        vm.onQueryChange("zeta")
        runCurrent()
        // Before TfL answers: the index's best four, with more on its way.
        val preview = rows()
        assertEquals(StationSearchViewModel.LOCAL_PREVIEW, preview.size)
        assertTrue(vm.state.value.searching)
        stopGate.complete(Unit)
        advanceTimeBy(301)
        runCurrent()
        // TfL's stop, although it matches better, joins below the rows already up, with the rest of
        // the index's matches (maintainer, 2026-09-28: append, don't reorder).
        val withStops = rows()
        assertEquals(preview, withStops.take(preview.size))
        assertEquals("Zeta Lane", withStops[preview.size])
        assertEquals(7, withStops.size)
        placeGate.complete(Unit)
        advanceUntilIdle()
        val all = rows()
        assertEquals(withStops, all.take(withStops.size))
        assertEquals("Alpha District, Zeta Gallery", all.last())
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `a row already shown keeps its place but takes TfL's fuller copy of itself`() = runTest {
        // The index knows the station without a position; TfL's answer for the same id lends it one.
        val bundled = listOf(IndexedStation("940GZZZETA", "Zeta Cross", listOf("tube")))
        val gate = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            FakeFinder(search = {
                gate.await()
                listOf(StationMatch("940GZZZETA", "Zeta Cross", listOf("tube"), latitude = 51.5, longitude = -0.1))
            }),
            loadIndex = { StationIndex(bundled) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
        )
        vm.onQueryChange("zeta")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val rows = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries
        assertEquals(1, rows.size)
        val stop = (rows.single() as SearchEntry.Stop).match
        assertEquals(51.5, stop.latitude)
        assertEquals(-0.1, stop.longitude)
    }

    @Test
    fun `a previewed row TfL's ranking folds into its twin leaves rather than stay a duplicate`() = runTest {
        // Two bundled records of one station, told apart only once TfL's positions show them side by side.
        val bundled = listOf(
            IndexedStation("910GZETAA", "Zeta Rail", listOf("national-rail")),
            IndexedStation("910GZETAB", "Zeta Rail", listOf("national-rail")),
        )
        val gate = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            FakeFinder(search = {
                gate.await()
                listOf(
                    StationMatch("910GZETAA", "Zeta Rail", listOf("national-rail"), latitude = 51.5, longitude = -0.1),
                    StationMatch("910GZETAB", "Zeta Rail", listOf("national-rail"), latitude = 51.5, longitude = -0.1),
                )
            }),
            loadIndex = { StationIndex(bundled) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
        )
        fun keys() = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries.map { it.key }
        vm.onQueryChange("zeta")
        runCurrent()
        assertEquals(listOf("910GZETAA", "910GZETAB"), keys())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("910GZETAA"), keys())
    }

    @Test
    fun `a previewed row pushed past the result cap by better TfL matches stays where it is`() = runTest {
        // TfL answers more better matches than the cap holds; the index's rows are still up and stay.
        val bundled = (1..4).map { IndexedStation("940GZZ$it", "Upper Bezeta $it", listOf("tube")) }
        val gate = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            FakeFinder(search = {
                gate.await()
                (1..StationIndex.DEFAULT_LIMIT).map { StationMatch("490ZETA$it", "Zeta Lane $it", listOf("bus")) }
            }),
            loadIndex = { StationIndex(bundled) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
        )
        fun keys() = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries.map { it.key }
        vm.onQueryChange("zeta")
        runCurrent()
        val preview = keys()
        assertEquals(4, preview.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(preview, keys().take(preview.size))
    }

    @Test
    fun `a Retry of the same query keeps the rows already up`() = runTest {
        // TfL's stop search fails once; the index's matches and a place are up. Retry must add to
        // them, not drop back to the four-row preview while TfL answers.
        val bundled = (1..6).map { IndexedStation("940GZZ$it", "Upper Bezeta $it", listOf("tube")) }
        var fail = true
        val gate = CompletableDeferred<Unit>()
        val vm = StationSearchViewModel(
            FakeFinder(search = {
                if (fail) throw TflException.Offline(null)
                gate.await()
                listOf(StationMatch("490ZETA", "Zeta Lane", listOf("bus")))
            }),
            loadIndex = { StationIndex(bundled) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
            searchPlaces = { listOf(PlaceCandidate("Alpha District, Zeta Gallery", Coordinates(51.5, -0.1))) },
        )
        fun keys() = (vm.state.value.result as StationSearchViewModel.Result.Matches).entries.map { it.key }
        vm.onQueryChange("zeta")
        advanceUntilIdle()
        val before = keys()
        assertEquals(7, before.size)
        fail = false
        vm.retry()
        runCurrent()
        assertEquals(before, keys())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(before, keys().take(before.size))
        assertEquals("490ZETA", keys().last())
    }

    @Test
    fun `a Retry that fails again keeps the places already up, with the failure noted`() = runTest {
        // First try: TfL's stop search fails, the geocoder finds a place. Retry: both come back empty-handed.
        var geocoded = listOf(PlaceCandidate("Alpha District, Zeta Gallery", Coordinates(51.5, -0.1)))
        val vm = StationSearchViewModel(
            FakeFinder(search = { throw TflException.Offline(null) }),
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
            searchPlaces = { geocoded },
        )
        vm.onQueryChange("zeta")
        advanceUntilIdle()
        val before = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("Alpha District, Zeta Gallery"), before.places.map { it.name })
        geocoded = emptyList()
        vm.retry()
        advanceUntilIdle()
        val after = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(before.entries, after.entries)
        assertEquals(before.places, after.places)
        assertTrue(after.remoteFailure != null)
    }

    @Test
    fun `a query restored after process death is kept and searched again`() = runTest {
        val first = SavedStateHandle()
        searchVm(FakeFinder(), first).onQueryChange("oxford")
        val finder = FakeFinder(search = { listOf(oxford) })
        val restored = searchVm(finder, SavedStateHandle(mapOf("query" to first.get<String>("query"))))
        assertEquals("oxford", restored.state.value.query)
        advanceUntilIdle()
        assertEquals(listOf("oxford"), finder.queries)
        assertEquals(StationSearchViewModel.Result.Matches(listOf(oxford)), restored.state.value.result)
    }

    @Test
    fun `typing searches once, after the pause, for the trimmed query`() = runTest {
        val finder = FakeFinder(search = { listOf(oxford) })
        val vm = searchVm(finder)
        vm.onQueryChange("ox")
        advanceTimeBy(100)
        vm.onQueryChange("oxf ")
        advanceTimeBy(299)
        runCurrent()
        assertTrue("no request before the pause", finder.queries.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("oxf"), finder.queries)
        assertEquals(StationSearchViewModel.Result.Matches(listOf(oxford)), vm.state.value.result)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `closing the search forgets the query and cancels a pending request`() = runTest {
        val saved = SavedStateHandle()
        val finder = FakeFinder(search = { listOf(oxford) })
        val vm = searchVm(finder, saved)
        vm.onQueryChange("oxford")
        advanceTimeBy(100)
        vm.clear()
        advanceUntilIdle()
        assertTrue("the pending search never ran", finder.queries.isEmpty())
        assertEquals(StationSearchViewModel.State(yoursRead = true), vm.state.value)
        assertEquals(null, saved.get<String>("query"))
    }

    private val kingsCross = IndexedStation("HUBKGX", "King's Cross St. Pancras", listOf("tube"))

    @Test
    fun `the bundled index answers at once, before TfL`() = runTest {
        val finder = FakeFinder(search = { listOf(StationMatch("490000000001A", "Kings Road", listOf("bus"))) })
        val vm = StationSearchViewModel(finder, loadIndex = { StationIndex(listOf(kingsCross)) }, io = dispatcher, compute = dispatcher, debounceMillis = 300)
        vm.onQueryChange("kx")
        runCurrent()
        assertEquals(listOf("HUBKGX"), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.id })
        assertTrue("TfL's search is still to come", vm.state.value.searching)
        advanceUntilIdle()
        assertEquals(listOf("kx"), finder.queries)
    }

    @Test
    fun `a failed TfL search keeps the index's matches and says what's missing`() = runTest {
        val finder = FakeFinder(search = { throw TflException.Offline(null) })
        val vm = StationSearchViewModel(finder, loadIndex = { StationIndex(listOf(kingsCross)) }, io = dispatcher, compute = dispatcher, debounceMillis = 300)
        vm.onQueryChange("kings")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("HUBKGX"), result.matches.map { it.id })
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, result.remoteFailure)
    }

    private val favoriteStop = StationMatch("490000000001A", "Example Road", listOf("bus"))

    @Test
    fun `the user's own stops are listed before anything is typed`() = runTest {
        val recent = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
        val vm = StationSearchViewModel(
            FakeFinder(),
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
            FakeFinder(),
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
            FakeFinder(),
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
            FakeFinder(),
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
            FakeFinder(),
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
        val vm = StationSearchViewModel(FakeFinder(), loadYours = { reads.removeFirst()() }, io = dispatcher, compute = dispatcher)
        runCurrent()
        vm.refreshYours()
        advanceUntilIdle()
        assertEquals(listOf(favoriteStop), vm.state.value.favorites)
        first.complete(YourStops())
        advanceUntilIdle()
        assertEquals("the stale first read is ignored", listOf(favoriteStop), vm.state.value.favorites)
    }

    @Test
    fun `a starred bus stop matches as the user types, before TfL`() = runTest {
        val finder = FakeFinder(search = { emptyList() })
        val vm = StationSearchViewModel(
            finder,
            loadIndex = { StationIndex(listOf(kingsCross)) },
            loadYours = { YourStops(favorites = listOf(favoriteStop)) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
        )
        vm.onQueryChange("example")
        runCurrent()
        assertEquals(listOf(favoriteStop), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches)
        assertTrue("TfL's search is still to come", finder.queries.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(favoriteStop), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches)
    }

    @Test
    fun `opening a match remembers it and rereads the recent list`() = runTest {
        val opened = mutableListOf<StationMatch>()
        val vm = StationSearchViewModel(
            FakeFinder(),
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
            FakeFinder(),
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
    fun `picks are written in the order tapped, however long each write takes`() = runTest {
        // The first write is slow (the gate holds it); the second, tapped after it, must still land after it.
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<SearchEntry>()
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        val vm = StationSearchViewModel(
            FakeFinder(),
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
            FakeFinder(),
            loadIndex = { StationIndex(listOf(place, park)) },
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened.add(0, it) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
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
    fun `opening a match re-ranks TfL's matches without asking TfL again`() = runTest {
        val place = IndexedStation("940GZZLUAAA", "Example Place", listOf("tube"))
        val busStop = StationMatch("490000000001A", "Example Road", listOf("bus"))
        var up = true
        val finder = FakeFinder(search = { if (up) listOf(busStop) else throw TflException.Offline(null) })
        val opened = mutableListOf<StationMatch>()
        val vm = StationSearchViewModel(
            finder,
            loadIndex = { StationIndex(listOf(place)) },
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened.add(0, it) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
        )
        vm.onQueryChange("example")
        advanceUntilIdle()
        assertEquals(1, finder.queries.size)
        // TfL goes down while the bus stop's page is open; Back still has both matches, the bus stop first.
        up = false
        vm.onOpened(busStop)
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("490000000001A", "940GZZLUAAA"), result.matches.map { it.id })
        assertEquals(null, result.remoteFailure)
        assertEquals(1, finder.queries.size)
    }

    @Test
    fun `a match opened while its search is still running leads it once the search lands`() = runTest {
        val place = IndexedStation("940GZZLUAAA", "Example Place", listOf("tube"))
        val park = IndexedStation("940GZZLUBBB", "Example Park", listOf("tube"))
        val opened = mutableListOf<StationMatch>()
        val vm = StationSearchViewModel(
            FakeFinder(),
            loadIndex = { StationIndex(listOf(place, park)) },
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened.add(0, it) },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
        )
        vm.onQueryChange("example")
        runCurrent()
        // Picked from the index's matches, inside the typing pause, before TfL has answered.
        assertTrue(vm.state.value.searching)
        vm.onOpened(StationMatch("940GZZLUAAA", "Example Place", listOf("tube")))
        advanceUntilIdle()
        val after = (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.id }
        assertEquals(listOf("940GZZLUAAA", "940GZZLUBBB"), after)
    }

    @Test
    fun `a re-rank never brings back TfL matches from an earlier answer`() = runTest {
        val place = IndexedStation("940GZZLUAAA", "Example Place", listOf("tube"))
        val busStop = StationMatch("490000000001A", "Example Road", listOf("bus"))
        var up = true
        val finder = FakeFinder(search = { if (up) listOf(busStop) else throw TflException.Offline(null) })
        val vm = StationSearchViewModel(
            finder,
            loadIndex = { StationIndex(listOf(place)) },
            loadYours = { YourStops() },
            io = dispatcher, compute = dispatcher,
            debounceMillis = 300,
        )
        vm.onQueryChange("example")
        advanceUntilIdle()
        up = false
        vm.onQueryChange("exampl")
        advanceUntilIdle()
        vm.onQueryChange("example")
        advanceUntilIdle()
        vm.onOpened(StationMatch("940GZZLUAAA", "Example Place", listOf("tube")))
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("940GZZLUAAA"), result.matches.map { it.id })
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, result.remoteFailure)
    }

    @Test
    fun `a query under two characters doesn't search`() = runTest {
        val finder = FakeFinder(search = { listOf(oxford) })
        val vm = searchVm(finder)
        vm.onQueryChange(" o ")
        advanceUntilIdle()
        assertTrue(finder.queries.isEmpty())
        assertEquals(StationSearchViewModel.Result.Idle, vm.state.value.result)
    }

    @Test
    fun `no matches is its own answer, not an error`() = runTest {
        val vm = searchVm(FakeFinder(search = { emptyList() }))
        vm.onQueryChange("zzz")
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.Result.NoMatches, vm.state.value.result)
    }

    @Test
    fun `a failed search says why, and retry searches again`() = runTest {
        var fail = true
        val finder = FakeFinder(search = { if (fail) throw TflException.Offline(null) else listOf(oxford) })
        val vm = searchVm(finder)
        vm.onQueryChange("oxford")
        advanceUntilIdle()
        assertEquals(
            StationSearchViewModel.Result.Failed(DeparturesUiState.Error.Kind.OFFLINE),
            vm.state.value.result,
        )
        fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.Result.Matches(listOf(oxford)), vm.state.value.result)
    }

    @Test
    fun `the previous matches stay up while the next search runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val finder = FakeFinder(search = { q -> if (q == "oxfo") gate.await(); listOf(oxford.copy(name = q)) })
        val vm = searchVm(finder)
        vm.onQueryChange("oxf")
        advanceUntilIdle()
        vm.onQueryChange("oxfo")
        runCurrent()
        assertTrue("marked searching during the typing pause, not only after it", vm.state.value.searching)
        advanceTimeBy(301)
        runCurrent()
        assertTrue(vm.state.value.searching)
        assertEquals(listOf("oxf"), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.name })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("oxfo"), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.name })
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
