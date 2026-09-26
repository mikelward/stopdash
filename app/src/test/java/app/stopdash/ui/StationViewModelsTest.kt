package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import app.stopdash.domain.Coordinates
import app.stopdash.domain.IndexedStation
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
        StationSearchViewModel(finder, saved, io = dispatcher, debounceMillis = 300)

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
        val vm = StationSearchViewModel(finder, loadIndex = { StationIndex(listOf(kingsCross)) }, io = dispatcher, debounceMillis = 300)
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
        val vm = StationSearchViewModel(finder, loadIndex = { StationIndex(listOf(kingsCross)) }, io = dispatcher, debounceMillis = 300)
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
            io = dispatcher,
        )
        assertFalse("not read yet", vm.state.value.yoursRead)
        advanceUntilIdle()
        assertTrue(vm.state.value.yoursRead)
        assertEquals(listOf(favoriteStop), vm.state.value.favorites)
        assertEquals(listOf(recent), vm.state.value.recent)
    }

    @Test
    fun `a starred station the device couldn't name is named from the bundled index`() = runTest {
        val vm = StationSearchViewModel(
            FakeFinder(),
            loadIndex = { StationIndex(listOf(kingsCross)) },
            loadYours = { YourStops(unnamedStarred = listOf("HUBKGX")) },
            io = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(listOf("HUBKGX"), vm.state.value.favorites.map { it.id })
    }

    @Test
    fun `an older read of the user's stops that lands last doesn't overwrite a newer one`() = runTest {
        val first = CompletableDeferred<YourStops>()
        val reads = ArrayDeque(listOf<suspend () -> YourStops>({ first.await() }, { YourStops(favorites = listOf(favoriteStop)) }))
        val vm = StationSearchViewModel(FakeFinder(), loadYours = { reads.removeFirst()() }, io = dispatcher)
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
            io = dispatcher,
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
            io = dispatcher,
        )
        advanceUntilIdle()
        vm.onOpened(oxford)
        advanceUntilIdle()
        assertEquals(listOf(oxford), vm.state.value.recent)
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
            io = dispatcher,
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
            io = dispatcher,
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
            io = dispatcher,
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
            io = dispatcher,
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
        val vm = StationStopsViewModel(FakeFinder(stops = { listOf(stop) }), "940GZZLUOXC", io = dispatcher)
        assertEquals(StationStopsViewModel.State.Loading, vm.state.value)
        advanceUntilIdle()
        val ready = vm.state.value as StationStopsViewModel.State.Ready
        assertEquals(listOf("940GZZLUOXC"), ready.stops.map { it.id })
        assertEquals(listOf("victoria"), ready.stops.single().lines.map { it.id })
    }

    @Test
    fun `a To destination takes in the stops around the station`() = runTest {
        val platform = StopLocation("940GZZLUEXA", "Example", 51.5, -0.12, clusterId = "940GZZLUEXA")
        val bus = StopLocation("490000000001A", "Example", 51.5005, -0.12)
        var askedAt: Coordinates? = null
        val vm = StationStopsViewModel(
            FakeFinder(stops = { listOf(platform) }),
            "940GZZLUEXA",
            io = dispatcher,
            around = { center ->
                askedAt = center
                listOf(bus)
            },
        )
        advanceUntilIdle()
        val ready = vm.state.value as StationStopsViewModel.State.Ready
        assertEquals(listOf("940GZZLUEXA", "490000000001A"), ready.stops.map { it.id })
        assertEquals(Coordinates(51.5, -0.12), askedAt)
    }

    @Test
    fun `a failed look around a To destination fails the lookup, with retry`() = runTest {
        val platform = StopLocation("940GZZLUEXA", "Example", 51.5, -0.12)
        var fail = true
        val vm = StationStopsViewModel(
            FakeFinder(stops = { listOf(platform) }),
            "940GZZLUEXA",
            io = dispatcher,
            around = { if (fail) throw TflException.RateLimited(null) else emptyList() },
        )
        advanceUntilIdle()
        assertEquals(StationStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.RATE_LIMITED), vm.state.value)
        fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("940GZZLUEXA"), (vm.state.value as StationStopsViewModel.State.Ready).stops.map { it.id })
    }

    @Test
    fun `a station with no departure stops says so, and a failed lookup can retry`() = runTest {
        assertEquals(
            StationStopsViewModel.State.NoStops,
            StationStopsViewModel(FakeFinder(), "HUBEXA", io = dispatcher).also { advanceUntilIdle() }.state.value,
        )
        var fail = true
        val finder = FakeFinder(stops = {
            if (fail) throw TflException.RateLimited(null) else emptyList()
        })
        val vm = StationStopsViewModel(finder, "HUBEXA", io = dispatcher)
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
        assertEquals("Tube · Elizabeth line · National Rail", modesLabel(listOf("tube", "elizabeth-line", "national-rail")))
        assertEquals("Some mode", modesLabel(listOf("some-mode", "")))
    }
}
