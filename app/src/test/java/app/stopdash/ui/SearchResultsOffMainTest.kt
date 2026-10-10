package app.stopdash.ui

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.RecentStations
import app.stopdash.domain.SearchEntry
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import app.stopdash.domain.YourStops
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The search and lookup view-models work over what they get back on their compute dispatcher, never
 * the main thread (AGENTS.md *Main thread: read and dispatch only*). Compute runs on a scheduler of its
 * own that each test holds back: with the main thread (and io) run to idle, a moved pass can't have
 * published, and does once compute runs. Each case reaches one moved pass. Hub stations, synthetic
 * names and positions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultsOffMainTest {
    private val mainScheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(mainScheduler)
    private val computeScheduler = TestCoroutineScheduler()
    private val compute = StandardTestDispatcher(computeScheduler)

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Runs the main thread (and io, on it) to idle, compute held. */
    private fun mainIdle() = mainScheduler.advanceUntilIdle()

    /** Lets compute run what it holds, then the main thread what follows. */
    private fun releaseCompute() {
        computeScheduler.advanceUntilIdle()
        mainIdle()
    }

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"), 51.5, -0.12)
    private val bundled = (1..2).map { IndexedStation("940GZZ$it", "Upper Bezeta $it", listOf("tube")) }
    private val never = CompletableDeferred<Nothing>()

    private class Finder(
        val search: suspend (String) -> List<StationMatch> = { emptyList() },
        val stops: suspend (String) -> List<StopLocation> = { emptyList() },
    ) : StationFinder {
        override suspend fun searchStations(query: String): List<StationMatch> = search(query)
        override suspend fun stationStops(id: String): List<StopLocation> = stops(id)
    }

    private fun searchVm(
        finder: StationFinder,
        index: StationIndex = StationIndex.EMPTY,
        searchPlaces: suspend (String) -> List<PlaceCandidate> = { emptyList() },
        loadYours: suspend () -> YourStops = { YourStops.EMPTY },
        recordOpen: suspend (StationMatch) -> Unit = {},
    ) = StationSearchViewModel(
        finder, loadIndex = { index }, loadYours = loadYours, searchPlaces = searchPlaces, recordOpen = recordOpen,
        io = main, compute = compute, debounceMillis = 0,
    )

    private fun StationSearchViewModel.matches() = state.value.result as? StationSearchViewModel.Result.Matches

    @Test
    fun theIndexPreviewIsBuiltOnCompute() {
        val vm = searchVm(Finder(search = { never.await() }), index = StationIndex(bundled))
        vm.onQueryChange("zeta")
        mainIdle()
        assertNull("preview published with compute held", vm.matches())
        releaseCompute()
        assertEquals(2, vm.matches()!!.entries.size)
    }

    @Test
    fun tflsAnswerIsRankedAndAppendedOnCompute() {
        // No index matches and the places never answer: ranking TfL's answer is the only pass to make.
        val vm = searchVm(Finder(search = { listOf(oxford) }), searchPlaces = { never.await() })
        vm.onQueryChange("oxford")
        mainIdle()
        assertNull("TfL's answer published with compute held", vm.matches())
        releaseCompute()
        assertEquals(listOf(oxford.id), vm.matches()!!.matches.map { it.id })
    }

    @Test
    fun aFailedSearchKeepsTheIndexMatchesOnCompute() {
        val vm = searchVm(Finder(search = { throw TflException.Unreachable("down", null) }), index = StationIndex(bundled))
        vm.onQueryChange("zeta")
        mainIdle()
        releaseCompute() // the preview
        assertNull("failure published with compute held", vm.matches()!!.remoteFailure)
        // The rows the failure keeps, then the (no) places folded in, each a pass of its own.
        releaseCompute()
        releaseCompute()
        assertNotNull(vm.matches()!!.remoteFailure)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun thePlacesAreFoldedInOnCompute() {
        // TfL's stop search fails with nothing local to keep, so folding in the places is the only pass.
        val vm = searchVm(
            Finder(search = { throw TflException.Unreachable("down", null) }),
            searchPlaces = { listOf(PlaceCandidate("Example Gallery", Coordinates(51.5, -0.12))) },
        )
        vm.onQueryChange("gallery")
        mainIdle()
        assertTrue("finished with compute held", vm.state.value.searching)
        releaseCompute()
        assertFalse(vm.state.value.searching)
        assertTrue(vm.matches()!!.entries.single() is SearchEntry.Place)
    }

    @Test
    fun removingARecentPickFiltersOnCompute() {
        val pick = SearchEntry.Stop(oxford)
        val file = mutableListOf(oxford)
        val vm = StationSearchViewModel(
            Finder(),
            loadYours = { YourStops(recent = file.toList()) },
            // The write held until compute has run, so the filter alone can take the row off.
            recordRemove = { removed -> file.removeAll { it.key == removed.key }; true },
            io = compute, compute = compute, debounceMillis = 0,
        )
        mainIdle()
        releaseCompute()
        assertEquals(listOf<SearchEntry>(pick), vm.state.value.recent)
        vm.onRemoveRecent(pick)
        mainIdle()
        assertEquals("removed with compute held", listOf<SearchEntry>(pick), vm.state.value.recent)
        computeScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test
    fun aPickOpenedAfterARemovalIsWrittenAfterItWhileComputeIsBusy() {
        // Eight picks, the most a list keeps: remove the first, then open a new station before compute
        // runs. The removal's write keeps its place, so the open drops nothing (Codex, #745).
        val stops = (1..RecentStations.MAX).map { StationMatch("94000000000$it", "Example $it", listOf("tube")) }
        val file = stops.map<StationMatch, SearchEntry>(SearchEntry::Stop).toMutableList()
        fun write(picks: List<SearchEntry>) { file.clear(); file.addAll(picks) }
        val vm = StationSearchViewModel(
            Finder(),
            recordOpen = { write(RecentStations.add(file.toList(), SearchEntry.Stop(it))) },
            recordRemove = { write(RecentStations.remove(file.toList(), it)); true },
            io = main, compute = compute, debounceMillis = 0,
        )
        mainIdle()
        releaseCompute()
        vm.onRemoveRecent(SearchEntry.Stop(stops[0]))
        vm.onOpened(oxford)
        mainIdle()
        releaseCompute()
        releaseCompute()
        assertEquals(listOf<SearchEntry>(SearchEntry.Stop(oxford)) + stops.drop(1).map(SearchEntry::Stop), file)
    }

    @Test
    fun aRemovalSavedBeforeComputeRunsIsStillTakenOffTheScreen() {
        // The write (on io, here main) lands while compute is held, and the read after it is slow: the
        // row must still come off the screen once compute runs (Codex, #745).
        val pick = SearchEntry.Stop(oxford)
        val file = mutableListOf(oxford)
        var slowRead: CompletableDeferred<Unit>? = null
        val vm = StationSearchViewModel(
            Finder(),
            loadYours = { slowRead?.await(); YourStops(recent = file.toList()) },
            recordRemove = { removed -> file.removeAll { it.key == removed.key }; true },
            io = main, compute = compute, debounceMillis = 0,
        )
        mainIdle()
        releaseCompute()
        assertEquals(listOf<SearchEntry>(pick), vm.state.value.recent)
        slowRead = CompletableDeferred()
        vm.onRemoveRecent(pick)
        mainIdle()
        assertTrue("written with compute held", file.isEmpty())
        releaseCompute()
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test
    fun anOpenRanksTheMatchesAgainOnCompute() {
        // A station the index doesn't hold, opened: read back as recent, it joins the matches on a re-rank.
        val park = StationMatch("940GZZPARK", "Zeta Park", listOf("tube"))
        val opened = mutableListOf<StationMatch>()
        val vm = searchVm(
            Finder(search = { emptyList() }),
            index = StationIndex(bundled),
            loadYours = { YourStops(recent = opened.toList()) },
            recordOpen = { opened += it },
        )
        vm.onQueryChange("zeta")
        mainIdle()
        // The preview, TfL's (empty) answer and the (no) places, a pass each.
        repeat(3) { releaseCompute() }
        assertFalse(vm.state.value.searching)
        vm.onOpened(park)
        mainIdle()
        assertFalse("re-ranked with compute held", vm.matches()!!.matches.any { it.id == park.id })
        releaseCompute()
        assertTrue(vm.matches()!!.matches.any { it.id == park.id })
    }

    @Test
    fun aStationsStopsAreCenteredOnCompute() {
        val stops = listOf(StopLocation("9400ZZLUOXC1", "Oxford Circus", 51.5, -0.12))
        val vm = StationStopsViewModel(Finder(stops = { stops }), oxford.id, io = main, compute = compute)
        mainIdle()
        assertEquals(StationStopsViewModel.State.Loading, vm.state.value)
        releaseCompute()
        // Its stop ids too, made with the state, so the page never walks the stops (Codex on #736).
        assertEquals(setOf("9400ZZLUOXC1"), (vm.state.value as StationStopsViewModel.State.Ready).stopIds)
    }

    @Test
    fun aStationsOtherRecordsAreFoundOnIo() {
        // One station under two ids, no interchange: reading and walking the index waits on io, held here,
        // so the main thread run to idle hasn't asked TfL for anything yet.
        val twin = (1..2).map { IndexedStation("910GTWIN$it", "Upper Bezeta Rail Station", listOf("national-rail"), "", 51.5, -0.12) }
        val ioScheduler = TestCoroutineScheduler()
        val io = StandardTestDispatcher(ioScheduler)
        val asked = mutableListOf<String>()
        var indexRead = false
        val vm = StationStopsViewModel(
            Finder(stops = { id -> asked += id; listOf(StopLocation(id, "Upper Bezeta", 51.5, -0.12)) }),
            "910GTWIN1", io = io, compute = compute, loadIndex = { indexRead = true; StationIndex(twin) },
        )
        mainIdle()
        computeScheduler.advanceUntilIdle()
        assertFalse("index read with io held", indexRead)
        assertEquals(emptyList<String>(), asked)
        assertEquals(StationStopsViewModel.State.Loading, vm.state.value)
        repeat(3) {
            ioScheduler.advanceUntilIdle()
            releaseCompute()
        }
        assertEquals(listOf("910GTWIN1", "910GTWIN2"), asked)
        assertTrue(vm.state.value is StationStopsViewModel.State.Ready)
    }

    private val store = object : FavoritePlacesStore {
        override fun places(): Flow<FavoritePlacesSet> = flowOf(FavoritePlacesSet.Loaded(emptyList<FavoritePlace>()))
        override suspend fun save(place: FavoritePlace) {}
        override suspend fun remove(id: String) {}
    }

    private fun placesVm(finder: StationFinder, index: StationIndex = StationIndex.EMPTY) =
        FavoritePlacesViewModel(store, finder, io = main, compute = compute, loadIndex = { index }, debounceMillis = 0, newId = { "id-1" })

    @Test
    fun aPickedFavoriteIsCenteredOnCompute() {
        val stops = listOf(StopLocation("9400ZZLUOXC1", "Oxford Circus", 51.5, -0.12))
        val vm = placesVm(Finder(stops = { stops }))
        vm.startAdd(FavoriteKind.CUSTOM, "")
        vm.onPick(oxford.copy(latitude = null, longitude = null))
        mainIdle()
        assertNull("centered with compute held", vm.state.value.editor!!.coordinate)
        releaseCompute()
        assertNotNull(vm.state.value.editor!!.coordinate)
    }

    @Test
    fun aFavoriteSearchPlacesTheIndexMatchesOnCompute() {
        val vm = placesVm(Finder(search = { never.await() }), index = StationIndex(bundled))
        vm.startAdd(FavoriteKind.CUSTOM, "")
        vm.onQueryChange("zeta")
        mainIdle()
        assertTrue("placed with compute held", vm.state.value.editor!!.results.isEmpty())
        releaseCompute()
        assertEquals(2, vm.state.value.editor!!.results.size)
    }

    @Test
    fun aFavoriteSearchRanksTflsAnswerOnCompute() {
        val vm = placesVm(Finder(search = { listOf(oxford) }))
        vm.startAdd(FavoriteKind.CUSTOM, "")
        vm.onQueryChange("oxford")
        mainIdle()
        // The (empty) index matches placed, then TfL's answer ranked: two passes.
        releaseCompute()
        assertTrue("ranked with compute held", vm.state.value.editor!!.searching)
        releaseCompute()
        assertEquals(listOf(oxford.id), vm.state.value.editor!!.results.map { it.id })
    }
}
