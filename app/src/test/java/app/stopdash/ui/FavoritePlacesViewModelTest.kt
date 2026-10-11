package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import app.stopdash.domain.ChipLabel
import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.Coordinates
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaceIcon
import app.stopdash.domain.FavoritePlaces
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.LineRef
import app.stopdash.domain.PlaceIndex
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import java.io.IOException
import java.time.DayOfWeek
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
 * The favorite-places editor's state machine over a fake store and finder (SPEC D9). Coordinates are
 * synthetic (SPEC *Privacy*).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritePlacesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeStore(initial: List<FavoritePlace> = emptyList()) : FavoritePlacesStore {
        private val flow = MutableStateFlow<FavoritePlacesSet>(FavoritePlacesSet.Loaded(initial))
        val saved = mutableListOf<FavoritePlace>()
        val removed = mutableListOf<String>()
        override fun places(): Flow<FavoritePlacesSet> = flow
        override suspend fun save(place: FavoritePlace) {
            saved += place
            val current = (flow.value as? FavoritePlacesSet.Loaded)?.places ?: emptyList()
            flow.value = FavoritePlacesSet.Loaded(FavoritePlaces.upsert(current, place))
        }
        override suspend fun remove(id: String) {
            removed += id
            val current = (flow.value as? FavoritePlacesSet.Loaded)?.places ?: emptyList()
            flow.value = FavoritePlacesSet.Loaded(FavoritePlaces.remove(current, id))
        }
    }

    private class FakeFinder(
        val search: suspend (String) -> List<StationMatch> = { emptyList() },
        val stops: suspend (String) -> List<StopLocation> = { emptyList() },
    ) : StationFinder {
        // Every stop search sent to TfL: none, now the search is on the device.
        val queries = mutableListOf<String>()
        override suspend fun searchStations(query: String): List<StationMatch> {
            queries += query
            return search(query)
        }
        override suspend fun stationStops(id: String): List<StopLocation> = stops(id)
    }

    /** A store whose read fails before the first emission (e.g. a DataStore IOException). */
    private class FailingStore : FavoritePlacesStore {
        override fun places(): Flow<FavoritePlacesSet> = flow { throw IOException("read failed") }
        override suspend fun save(place: FavoritePlace) {}
        override suspend fun remove(id: String) {}
    }

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"), 51.5, -0.12)
    private val positionless = StationMatch("490000000A", "Somewhere Road", listOf("bus"))

    // The bundled stations the editor's searches find on the device: two public TfL stations, and a
    // positionless record whose position only its stops can give. Positions are synthetic.
    private val stations = StationIndex(
        listOf(
            IndexedStation("940GZZLUOXC", "Oxford Circus", listOf("tube"), latitude = 51.5, longitude = -0.12),
            IndexedStation("940GZZLUVIC", "Victoria", listOf("tube"), latitude = 51.49, longitude = -0.14),
            IndexedStation(positionless.id, positionless.name, positionless.modes),
        ),
    )

    private fun vm(store: FavoritePlacesStore, finder: StationFinder) =
        FavoritePlacesViewModel(store, finder, io = dispatcher, compute = dispatcher, loadIndex = { stations }, debounceMillis = 300, newId = { "id-1" })

    @Test
    fun `a stop search finds stations and bus stops with nothing sent to TfL`() = runTest {
        // Synthetic stop (AGENTS.md *Privacy*). The finder stays for picks; a search must not touch it.
        val finder = FakeFinder()
        val buses = BusStopIndex.of(listOf(StopLocation("4900A", "Oxford Lane", 51.5, -0.12, lines = listOf(LineRef("1", "1", "bus")))))
        val model = FavoritePlacesViewModel(
            FakeStore(), finder,
            io = dispatcher, compute = dispatcher, loadIndex = { stations }, loadBusStops = { buses }, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertEquals(listOf(oxford, StationMatch("4900A", "Oxford Lane", listOf("bus"), 51.5, -0.12)), editor.results)
        assertFalse(editor.searching)
        assertEquals(emptyList<String>(), finder.queries)
    }

    @Test
    fun `saved places are exposed once the store loads`() = runTest {
        val place = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
        val model = vm(FakeStore(listOf(place)), FakeFinder())
        advanceUntilIdle()
        assertTrue(model.state.value.loaded)
        assertEquals(FavoritePlacesSet.Loaded(listOf(place)), model.state.value.places)
    }

    @Test
    fun `a stop's Favorite starts a custom place named for it at its position`() = runTest {
        val store = FakeStore()
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAddAt(oxford)
        val editor = model.state.value.editor!!
        assertEquals(FavoriteKind.CUSTOM, editor.kind)
        assertEquals(Coordinates(51.5, -0.12), editor.coordinate)
        assertEquals("Oxford Circus", editor.label)
        assertTrue(editor.canSave)
        model.commit()
        advanceUntilIdle()
        assertEquals(listOf("Oxford Circus"), store.saved.map { it.label })
    }

    @Test
    fun `a stop's Favorite with no position resolves it from the stop's members`() = runTest {
        val model = vm(FakeStore(), FakeFinder(stops = { listOf(StopLocation("490X", "Somewhere Road", 51.52, -0.13)) }))
        advanceUntilIdle()
        model.startAddAt(positionless)
        advanceUntilIdle()
        assertEquals(Coordinates(51.52, -0.13), model.state.value.editor?.coordinate)
    }

    @Test
    fun `a stop's Favorite whose lookup fails keeps its row with a retry`() = runTest {
        var fail = true
        val model = vm(FakeStore(), FakeFinder(stops = {
            if (fail) throw IOException("offline") else listOf(StopLocation("490X", "Somewhere Road", 51.52, -0.13))
        }))
        advanceUntilIdle()
        model.startAddAt(positionless)
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        // The stop stays listed, its failure on its row, not "No matches" (Codex on #670).
        assertEquals(listOf(positionless), editor.results)
        assertTrue(positionless.id in editor.resolveFailedIds)
        assertNull(editor.coordinate)
        // A tap on it again is the retry.
        fail = false
        model.onPick(positionless)
        advanceUntilIdle()
        assertEquals(Coordinates(51.52, -0.13), model.state.value.editor?.coordinate)
    }

    @Test
    fun `a stop's Favorite cut short by a restore resumes its lookup and keeps the rider's edits`() = runTest {
        val saved = SavedStateHandle()
        var fail = true
        val finder = FakeFinder(stops = {
            if (fail) throw IOException("offline") else listOf(StopLocation("490X", "Somewhere Road", 51.52, -0.13))
        })
        val first = FavoritePlacesViewModel(FakeStore(), finder, io = dispatcher, compute = dispatcher, savedState = saved, newId = { "id-1" })
        advanceUntilIdle()
        first.startAddAt(positionless)
        advanceUntilIdle()
        first.onLabelChange("Gran's")
        // The process dies; a new model restores the draft from saved state alone.
        fail = false
        val second = FavoritePlacesViewModel(FakeStore(), finder, io = dispatcher, compute = dispatcher, savedState = saved, newId = { "id-2" })
        advanceUntilIdle()
        val editor = second.state.value.editor!!
        assertEquals(Coordinates(51.52, -0.13), editor.coordinate)
        // The rider's label stands; the draft's id too (Codex on #670).
        assertEquals("Gran's", editor.label)
        assertEquals("id-1", editor.draftId)
        assertNull(editor.pendingStop)
    }

    @Test
    fun `searching for another place drops the stop's pending lookup`() = runTest {
        val model = vm(FakeStore(), FakeFinder(stops = { throw IOException("offline") }))
        advanceUntilIdle()
        model.startAddAt(positionless)
        advanceUntilIdle()
        model.onQueryChange("Oxf")
        assertNull(model.state.value.editor?.pendingStop)
    }

    @Test
    fun `adding searches, picks a positioned match, and saves it`() = runTest {
        val store = FakeStore()
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        assertEquals(listOf(oxford), model.state.value.editor?.results)
        model.onPick(oxford)
        val editor = model.state.value.editor!!
        assertEquals(Coordinates(51.5, -0.12), editor.coordinate)
        assertEquals("Oxford Circus", editor.label) // blank label takes the match name
        assertTrue(editor.canSave)
        model.commit()
        advanceUntilIdle()
        assertNull(model.state.value.editor)
        assertEquals(
            listOf(FavoritePlace("id-1", FavoriteKind.CUSTOM, "Oxford Circus", Coordinates(51.5, -0.12), "Oxford Circus")),
            store.saved,
        )
    }

    @Test
    fun `a positionless match that can't be resolved is left unselectable`() = runTest {
        // stationStops returns nothing, so there's no member center to anchor it.
        val model = vm(FakeStore(), FakeFinder(stops = { emptyList() }))
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("some")
        advanceUntilIdle()
        model.onPick(positionless)
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertNull(editor.coordinate)
        assertFalse(editor.canSave)
        assertTrue(positionless.id in editor.unresolvableIds) // shown, but honestly unselectable
    }

    @Test
    fun `a transient resolve failure is retryable, not marked no-location`() = runTest {
        var attempt = 0
        val members = listOf(StopLocation("490000000A1", "Somewhere Road", 51.5, -0.12))
        val model = vm(
            FakeStore(),
            FakeFinder(
                stops = {
                    attempt++
                    if (attempt == 1) throw TflException.Offline(null) else members
                },
            ),
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("some")
        advanceUntilIdle()
        model.onPick(positionless)
        advanceUntilIdle()
        val failed = model.state.value.editor!!
        assertTrue(positionless.id in failed.resolveFailedIds) // transient: retryable
        assertFalse(positionless.id in failed.unresolvableIds) // NOT presented as "no location"
        assertNull(failed.coordinate)
        // Tapping again retries, and this time it resolves.
        model.onPick(positionless)
        advanceUntilIdle()
        val resolved = model.state.value.editor!!
        assertFalse(positionless.id in resolved.resolveFailedIds)
        assertEquals(Coordinates(51.5, -0.12), resolved.coordinate)
        assertTrue(resolved.canSave)
    }

    @Test
    fun `a 404 resolve failure is unresolvable, not retryable`() = runTest {
        // TfL 404s the stop: its contract says retrying won't help, so the row is marked unselectable,
        // never offered as a retry (distinct from a transient failure above).
        val model = vm(
            FakeStore(),
            FakeFinder(stops = { throw TflException.NotFound(null) }),
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("some")
        advanceUntilIdle()
        model.onPick(positionless)
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertTrue(positionless.id in editor.unresolvableIds) // permanent: shown but unselectable
        assertFalse(positionless.id in editor.resolveFailedIds) // NOT offered as a retry
        assertNull(editor.coordinate)
        assertFalse(editor.canSave)
    }

    @Test
    fun `a positionless pick resolves its coordinate from the stop's members`() = runTest {
        // The match has no inline position, but its stops do: their center anchors the place.
        val members = listOf(
            StopLocation("490000000A1", "Somewhere Road", 51.50, -0.10),
            StopLocation("490000000A2", "Somewhere Road", 51.52, -0.14),
        )
        val model = vm(
            FakeStore(),
            FakeFinder(stops = { members }),
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("some")
        advanceUntilIdle()
        model.onPick(positionless)
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        val c = editor.coordinate!!
        assertEquals(51.51, c.latitude, 1e-9) // mean of the members' latitudes
        assertEquals(-0.12, c.longitude, 1e-9)
        assertEquals("Somewhere Road", editor.placeName)
        assertTrue(editor.canSave)
    }

    // A bundled place index from synthetic lines (AGENTS *Privacy*).
    private fun placeIndex(vararg lines: String): PlaceIndex = PlaceIndex.read(
        java.io.BufferedReader(java.io.StringReader((listOf("# stopdash place index v1\ttest") + lines).joinToString("\n"))),
    )

    @Test
    fun `a bundled place is offered beside the stops and picking it adopts its position`() = runTest {
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadPlaceIndex = { placeIndex("P\tZeta Gallery\tmuseum\t51.51000\t-0.13000\t7") },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("zeta gal")
        advanceUntilIdle()
        val found = model.state.value.editor!!.places.single()
        assertEquals("Zeta Gallery", found.name)
        model.onPickPlace(found)
        val editor = model.state.value.editor!!
        assertEquals(Coordinates(51.51, -0.13), editor.coordinate)
        assertEquals("Zeta Gallery", editor.label)
        assertTrue(editor.places.isEmpty())
        assertTrue(editor.canSave)
    }

    @Test
    fun `each stop result carries the district of its nearest bundled postcode`() = runTest {
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadIndex = { stations },
            // A synthetic postcode beside Oxford Circus's synthetic position, none near Victoria's.
            loadPlaceIndex = { placeIndex("C\tX1 1AA\t\t51.50010\t-0.12000\t4") },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxford")
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertEquals(listOf("940GZZLUOXC"), editor.results.map { it.id })
        assertEquals(mapOf(editor.results.single().key to "X1"), editor.areas)
    }

    @Test
    fun `a complete postcode in the bundle is adopted`() = runTest {
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadPlaceIndex = { placeIndex("C\tX1 9XX\t\t51.52000\t-0.11000\t4") },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("x1 9xx")
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertEquals(Coordinates(51.52, -0.11), editor.coordinate)
        assertEquals("X1 9XX", editor.placeName)
        assertTrue(editor.canSave)
    }

    @Test
    fun `a partial postcode lists the bundled postcodes it starts`() = runTest {
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadPlaceIndex = { placeIndex("C\tX1 9XX\t\t51.52000\t-0.11000\t4", "C\tX1 9XY\t\t51.52100\t-0.11000\t4") },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("X1 9")
        advanceUntilIdle()
        assertEquals(listOf("X1 9XX", "X1 9XY"), model.state.value.editor!!.places.map { it.name })
    }

    @Test
    fun `a partial postcode lists postcodes, not places named like one`() = runTest {
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadPlaceIndex = {
                placeIndex(
                    *Array(10) { "P\tX1$it Hall\tmuseum\t51.51000\t-0.13000\t7" },
                    "C\tX1 9XX\t\t51.52000\t-0.11000\t4",
                )
            },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("X1")
        advanceUntilIdle()
        assertEquals(listOf("X1 9XX"), model.state.value.editor!!.places.map { it.name })
    }

    @Test
    fun `bus stops read late go below the bundled places, not above them`() = runTest {
        val road = StationMatch("490000000Z", "Zeta Road", listOf("bus"), 51.52, -0.14)
        val busesRead = CompletableDeferred<BusStopIndex>()
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadPlaceIndex = { placeIndex("P\tZeta Gallery\tmuseum\t51.51000\t-0.13000\t7") },
            loadBusStops = { busesRead.await() },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("zeta")
        runCurrent()
        // The place is listed before the bus stops are read, with no stop above it.
        val early = model.state.value.editor!!
        assertEquals(listOf("Zeta Gallery"), early.places.map { it.name })
        assertEquals(0, early.placesAfter)
        assertTrue(early.searching)
        busesRead.complete(BusStopIndex.of(listOf(StopLocation(road.id, road.name, 51.52, -0.14, lines = listOf(LineRef("1", "1", "bus"))))))
        advanceUntilIdle()
        // The bus stop lands below it: still nothing above the place.
        val late = model.state.value.editor!!
        assertEquals(listOf(road), late.results)
        assertEquals(0, late.placesAfter.coerceAtMost(late.results.size))
    }

    @Test
    fun `picking a bundled place before the bus stops are read drops the late stops`() = runTest {
        val busesRead = CompletableDeferred<BusStopIndex>()
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadPlaceIndex = { placeIndex("P\tZeta Gallery\tmuseum\t51.51000\t-0.13000\t7") },
            loadBusStops = { busesRead.await() },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("Zeta Gallery")
        runCurrent()
        // Picked with the bus stops still unread, under the same name it sets as the query.
        model.onPickPlace(model.state.value.editor!!.places.single())
        busesRead.complete(BusStopIndex.of(listOf(StopLocation("490000000Z", "Zeta Gallery", 51.52, -0.14, lines = listOf(LineRef("1", "1", "bus"))))))
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertEquals(Coordinates(51.51, -0.13), editor.coordinate)
        assertTrue(editor.results.isEmpty())
    }

    @Test
    fun `holdStill keeps the shown stops first and in order, with TfL's added after`() {
        val a = StationMatch("a", "Alpha", listOf("tube"))
        val b = StationMatch("b", "Beta", listOf("tube"))
        val bFromTfl = StationMatch("b", "Beta", listOf("tube", "bus"), 51.5, -0.12)
        val c = StationMatch("c", "Gamma", listOf("bus"))
        assertEquals(listOf(a, bFromTfl, c), FavoritePlacesViewModel.holdStill(listOf(a, b), listOf(c, bFromTfl, a)))
    }

    @Test
    fun `holdStill adds TfL's stops only up to the usual limit`() {
        val shown = List(3) { StationMatch("s$it", "Shown $it", listOf("tube")) }
        val added = List(5) { StationMatch("t$it", "Added $it", listOf("bus")) }
        val held = FavoritePlacesViewModel.holdStill(shown, added + shown, limit = 4)
        assertEquals(shown + added.take(1), held)
    }

    @Test
    fun `holdStill keeps an interchange's names apart, though they share an id`() {
        val one = StationMatch("hub", "Alpha", listOf("tube"), lead = listOf("tube"))
        val other = StationMatch("hub", "Beta", listOf("tube"), lead = listOf("tube"))
        assertEquals(listOf(one, other), FavoritePlacesViewModel.holdStill(listOf(one, other), listOf(other, one)))
    }

    @Test
    fun `a postcode-shaped query is not searched as a stop`() = runTest {
        // A synthetic station named like the postcode, which a stop search would find.
        val index = StationIndex(listOf(IndexedStation("940GZZX1", "X1 Junction", listOf("tube"), latitude = 51.5, longitude = -0.12)) + stations.stations)
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadIndex = { index },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("X1") // a postcode: its bundled postcodes are listed, not stops
        advanceUntilIdle()
        assertTrue(model.state.value.editor!!.results.isEmpty())
        // A plain two-letter prefix is how station names start ("Ox" → Oxford Circus), so it still searches.
        model.onQueryChange("Ox")
        advanceUntilIdle()
        assertEquals(listOf(oxford), model.state.value.editor!!.results)
    }

    @Test
    fun `a complete postcode the bundle doesn't hold is looked up with TfL, and a new query clears it`() = runTest {
        val asked = mutableListOf<String>()
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            loadPlaceIndex = { placeIndex("C\tX1 9XX\t\t51.52000\t-0.11000\t4") },
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
            findOutside = { asked += it; listOf(PlaceCandidate("X2 9XX", Coordinates(52.0, -0.5))) },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        // Outside the bundle (outside London): asked of TfL, and offered to pick rather than adopted.
        model.onQueryChange("X2 9XX")
        advanceUntilIdle()
        assertEquals(listOf("X2 9XX"), asked)
        val editor = model.state.value.editor!!
        val found = editor.outside as OutsideLondon.Found
        assertNull(editor.coordinate)
        model.onPickPlace(found.places.single())
        assertEquals(Coordinates(52.0, -0.5), model.state.value.editor!!.coordinate)
        assertEquals(OutsideLondon.Hidden, model.state.value.editor!!.outside)
        model.onQueryChange("X2")
        advanceUntilIdle()
        assertEquals(OutsideLondon.Hidden, model.state.value.editor!!.outside)
    }

    @Test
    fun `a place the bundle lacks is offered outside London, asked only when tapped`() = runTest {
        val asked = mutableListOf<String>()
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            io = dispatcher, compute = dispatcher, debounceMillis = 300, newId = { "id-1" },
            findOutside = { asked += it; throw TflException.Unreachable("test", null) },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("Zeta Town")
        advanceUntilIdle()
        assertEquals(OutsideLondon.Offered, model.state.value.editor!!.outside)
        assertTrue(asked.isEmpty())
        model.searchOutside()
        advanceUntilIdle()
        assertEquals(listOf("Zeta Town"), asked)
        assertEquals(OutsideLondon.Failed, model.state.value.editor!!.outside)
    }

    @Test
    fun `editing keeps the saved coordinate until a new pick`() = runTest {
        val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12), "Old place")
        val store = FakeStore(listOf(home))
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startEdit(home)
        assertEquals(Coordinates(51.5, -0.12), model.state.value.editor?.coordinate)
        model.onLabelChange("Flat")
        model.commit()
        advanceUntilIdle()
        // Same id and coordinate, new label — no re-pick needed.
        assertEquals(
            FavoritePlace("h", FavoriteKind.HOME, "Flat", Coordinates(51.5, -0.12), "Old place"),
            store.saved.single(),
        )
    }

    @Test
    fun `a new place shows every day, and a turned-off day is saved`() = runTest {
        val store = FakeStore()
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.WORK, "Work")
        assertEquals(FavoritePlace.EVERY_DAY, model.state.value.editor?.showOnDays)
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.onToggleDay(DayOfWeek.SATURDAY)
        model.onToggleDay(DayOfWeek.SUNDAY)
        model.commit()
        advanceUntilIdle()
        assertEquals(FavoritePlace.EVERY_DAY - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY, store.saved.single().showOnDays)
    }

    @Test
    fun `editing keeps a place's days, and a toggle turns one back on`() = runTest {
        val work = FavoritePlace(
            "w", FavoriteKind.WORK, "Work", Coordinates(51.5, -0.12),
            showOnDays = setOf(DayOfWeek.MONDAY),
        )
        val store = FakeStore(listOf(work))
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startEdit(work)
        assertEquals(setOf(DayOfWeek.MONDAY), model.state.value.editor?.showOnDays)
        model.onToggleDay(DayOfWeek.TUESDAY)
        model.commit()
        advanceUntilIdle()
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), store.saved.single().showOnDays)
    }

    @Test
    fun `a new place starts with its kind's icon, and the chosen icon and chip label save`() = runTest {
        val store = FakeStore()
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        assertNull(model.state.value.editor?.icon)
        model.startAdd(FavoriteKind.WORK, "Work")
        assertEquals(FavoritePlaceIcon.WORK, model.state.value.editor?.icon)
        model.onIconChange(FavoritePlaceIcon.OFFICE)
        model.onChipShowsChange(ChipLabel.BOTH)
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.commit()
        advanceUntilIdle()
        val saved = store.saved.single()
        assertEquals(FavoritePlaceIcon.OFFICE, saved.icon)
        assertEquals(ChipLabel.BOTH, saved.chipShows)
    }

    @Test
    fun `choosing the chosen icon clears it, and editing keeps a place's icon and chip label`() = runTest {
        val home = FavoritePlace(
            "h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12),
            icon = FavoritePlaceIcon.HOME, chipShows = ChipLabel.NAME,
        )
        val store = FakeStore(listOf(home))
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startEdit(home)
        assertEquals(FavoritePlaceIcon.HOME, model.state.value.editor?.icon)
        assertEquals(ChipLabel.NAME, model.state.value.editor?.chipShows)
        model.onIconChange(FavoritePlaceIcon.HOME)
        assertNull(model.state.value.editor?.icon)
        model.commit()
        advanceUntilIdle()
        assertNull(store.saved.single().icon)
    }

    @Test
    fun `a restored draft keeps its icon and chip label`() = runTest {
        val saved = SavedStateHandle()
        val work = FavoritePlace("w", FavoriteKind.WORK, "Work", Coordinates(51.5, -0.12))
        val first = FavoritePlacesViewModel(FakeStore(listOf(work)), FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved)
        advanceUntilIdle()
        first.startEdit(work)
        first.onIconChange(FavoritePlaceIcon.OFFICE)
        first.onChipShowsChange(ChipLabel.BOTH)
        val restored = FavoritePlacesViewModel(FakeStore(listOf(work)), FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved)
        assertEquals(FavoritePlaceIcon.OFFICE, restored.state.value.editor?.icon)
        assertEquals(ChipLabel.BOTH, restored.state.value.editor?.chipShows)
    }

    @Test
    fun `a restored draft keeps its days`() = runTest {
        val saved = SavedStateHandle()
        val work = FavoritePlace("w", FavoriteKind.WORK, "Work", Coordinates(51.5, -0.12))
        val first = FavoritePlacesViewModel(FakeStore(listOf(work)), FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved)
        advanceUntilIdle()
        first.startEdit(work)
        first.onToggleDay(DayOfWeek.SUNDAY)
        val restored = FavoritePlacesViewModel(FakeStore(listOf(work)), FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved)
        assertEquals(FavoritePlace.EVERY_DAY - DayOfWeek.SUNDAY, restored.state.value.editor?.showOnDays)
    }

    @Test
    fun `delete removes by id`() = runTest {
        val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
        val store = FakeStore(listOf(home))
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.delete("h")
        advanceUntilIdle()
        assertEquals(listOf("h"), store.removed)
        assertEquals(FavoritePlacesSet.Loaded(emptyList()), model.state.value.places)
    }

    @Test
    fun `editing the query after a pick invalidates the selection`() = runTest {
        val model = vm(FakeStore(), FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        assertTrue(model.state.value.editor!!.canSave)
        model.onQueryChange("vic")
        val editor = model.state.value.editor!!
        assertNull(editor.coordinate) // the old pick no longer counts
        assertFalse(editor.canSave)
    }

    @Test
    fun `repicking after changing the query refreshes an auto-filled label`() = runTest {
        val victoria = StationMatch("940GZZLUVIC", "Victoria", listOf("tube"), 51.49, -0.14)
        val store = FakeStore()
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford) // blank label takes "Oxford Circus"
        assertEquals("Oxford Circus", model.state.value.editor?.label)
        model.onQueryChange("vic")
        advanceUntilIdle()
        model.onPick(victoria)
        val editor = model.state.value.editor!!
        // The auto-filled label follows the new pick, so Save can't store Victoria under "Oxford Circus".
        assertEquals("Victoria", editor.label)
        assertEquals(Coordinates(51.49, -0.14), editor.coordinate)
    }

    @Test
    fun `reopening an auto-labeled place refreshes the label on repick`() = runTest {
        val victoria = StationMatch("940GZZLUVIC", "Victoria", listOf("tube"), 51.49, -0.14)
        // Originally auto-labeled: label equals the resolved place name.
        val place = FavoritePlace("c", FavoriteKind.CUSTOM, "Oxford Circus", Coordinates(51.5, -0.12), "Oxford Circus")
        val model = vm(FakeStore(listOf(place)), FakeFinder())
        advanceUntilIdle()
        model.startEdit(place)
        model.onQueryChange("vic")
        advanceUntilIdle()
        model.onPick(victoria)
        assertEquals("Victoria", model.state.value.editor?.label) // refreshed with the new pick
    }

    @Test
    fun `reopening a user-labeled place keeps the label on repick`() = runTest {
        val victoria = StationMatch("940GZZLUVIC", "Victoria", listOf("tube"), 51.49, -0.14)
        // User-chosen label differs from the resolved place name.
        val place = FavoritePlace("c", FavoriteKind.CUSTOM, "Gym", Coordinates(51.5, -0.12), "Oxford Circus")
        val model = vm(FakeStore(listOf(place)), FakeFinder())
        advanceUntilIdle()
        model.startEdit(place)
        model.onQueryChange("vic")
        advanceUntilIdle()
        model.onPick(victoria)
        assertEquals("Gym", model.state.value.editor?.label) // the user's label stands
    }

    @Test
    fun `a user-typed label survives a repick`() = runTest {
        val victoria = StationMatch("940GZZLUVIC", "Victoria", listOf("tube"), 51.49, -0.14)
        val model = vm(FakeStore(), FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.onLabelChange("Gym") // the user names it
        model.onQueryChange("vic")
        advanceUntilIdle()
        model.onPick(victoria)
        // The user's label stands; only the location changed.
        assertEquals("Gym", model.state.value.editor?.label)
        assertEquals(Coordinates(51.49, -0.14), model.state.value.editor?.coordinate)
    }

    @Test
    fun `a second Save while one is in flight does not add a duplicate`() = runTest {
        val store = FakeStore()
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.commit()
        model.commit() // ignored: a save is already in flight
        advanceUntilIdle()
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `a restored custom draft re-saves under its assigned id, not a duplicate`() = runTest {
        val saved = SavedStateHandle()
        val store = FakeStore()
        // A first session assigns the CUSTOM place its id, picks a location, and persists the draft (as
        // Android saves instance state while the save is in flight).
        val first = FavoritePlacesViewModel(
            store, FakeFinder(), loadIndex = { stations },
            io = dispatcher, compute = dispatcher, savedState = saved, debounceMillis = 300, newId = { "first-id" },
        )
        advanceUntilIdle()
        first.startAdd(FavoriteKind.CUSTOM, "")
        first.onQueryChange("oxf")
        advanceUntilIdle()
        first.onPick(oxford)
        // That write lands under the assigned id, but the process dies before the editor-clear persists.
        store.save(FavoritePlace("first-id", FavoriteKind.CUSTOM, "Oxford Circus", Coordinates(51.5, -0.12), "Oxford Circus"))
        // A new instance restores the still-open draft; a fresh id would be minted if it weren't saved.
        val restored = FavoritePlacesViewModel(
            store, FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved, debounceMillis = 300, newId = { "second-id" },
        )
        advanceUntilIdle()
        restored.commit()
        advanceUntilIdle()
        val places = (restored.state.value.places as FavoritePlacesSet.Loaded).places
        assertEquals(1, places.size) // upserted under the same id, not appended as a duplicate
        assertEquals("first-id", places.single().id) // reused the assigned id, not "second-id"
    }

    @Test
    fun `a restored reserved-kind draft keeps its assigned id`() = runTest {
        val saved = SavedStateHandle()
        val store = FakeStore()
        val first = FavoritePlacesViewModel(
            store, FakeFinder(), loadIndex = { stations },
            io = dispatcher, compute = dispatcher, savedState = saved, debounceMillis = 300, newId = { "first-id" },
        )
        advanceUntilIdle()
        first.startAdd(FavoriteKind.HOME, "Home")
        first.onQueryChange("oxf")
        advanceUntilIdle()
        first.onPick(oxford)
        // Restored after process death, with a different id source, still saves under the assigned id.
        val restored = FavoritePlacesViewModel(
            store, FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved, debounceMillis = 300, newId = { "second-id" },
        )
        advanceUntilIdle()
        restored.commit()
        advanceUntilIdle()
        assertEquals("first-id", store.saved.single().id) // not "second-id"
        assertEquals("Home", store.saved.single().label) // the reserved default stands
    }

    @Test
    fun `a save failure surfaces even after the editor is replaced`() = runTest {
        val store = object : FavoritePlacesStore {
            private val flow = MutableStateFlow<FavoritePlacesSet>(FavoritePlacesSet.Loaded(emptyList()))
            override fun places(): Flow<FavoritePlacesSet> = flow
            override suspend fun save(place: FavoritePlace) = throw IOException("disk full")
            override suspend fun remove(id: String) {}
        }
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.commit() // write launched, not yet run
        // The user cancels and opens another editor before the write fails (epoch changes).
        model.cancelEditor()
        model.startAdd(FavoriteKind.CUSTOM, "")
        advanceUntilIdle() // the failed write now completes
        assertTrue(model.state.value.writeFailed) // surfaced despite the epoch change
    }

    @Test
    fun `a save before the store's first emission is held, not written`() = runTest {
        val store = object : FavoritePlacesStore {
            val saved = mutableListOf<FavoritePlace>()
            override fun places(): Flow<FavoritePlacesSet> = flow { awaitCancellation() } // never emits
            override suspend fun save(place: FavoritePlace) { saved += place }
            override suspend fun remove(id: String) {}
        }
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        assertFalse(model.state.value.loaded)
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.commit()
        advanceUntilIdle()
        assertTrue(store.saved.isEmpty()) // not written before the store's schema is known
        assertNotNull(model.state.value.editor) // the draft is kept
    }

    @Test
    fun `saving against an unavailable store surfaces failure and keeps the draft`() = runTest {
        val store = object : FavoritePlacesStore {
            private val flow = MutableStateFlow<FavoritePlacesSet>(FavoritePlacesSet.Unavailable)
            val saved = mutableListOf<FavoritePlace>()
            override fun places(): Flow<FavoritePlacesSet> = flow
            override suspend fun save(place: FavoritePlace) { saved += place }
            override suspend fun remove(id: String) {}
        }
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.commit()
        advanceUntilIdle()
        assertTrue(store.saved.isEmpty()) // save isn't attempted against a newer-schema file
        assertNotNull(model.state.value.editor) // the restored/entered draft stays open
        assertTrue(model.state.value.writeFailed) // and the failure is surfaced, not silent
    }

    @Test
    fun `edits made while a save is in flight are ignored`() = runTest {
        val store = FakeStore()
        val model = vm(store, FakeFinder())
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        model.onPick(oxford)
        model.commit() // saving = true; write queued, not yet run
        assertTrue(model.state.value.editor!!.saving)
        model.onLabelChange("changed while saving") // frozen: ignored
        model.onQueryChange("vic")
        assertEquals("Oxford Circus", model.state.value.editor?.label)
        advanceUntilIdle()
        // The completed write used the pre-save values, not the ignored edits.
        assertEquals("Oxford Circus", store.saved.single().label)
    }

    @Test
    fun `a read failure reads as Unavailable, not empty`() = runTest {
        val model = vm(FailingStore(), FakeFinder())
        advanceUntilIdle()
        assertTrue(model.state.value.loaded)
        assertEquals(FavoritePlacesSet.Unavailable, model.state.value.places)
    }

    @Test
    fun `an in-progress edit is restored from saved state`() = runTest {
        val home = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12), "Old place")
        val saved = SavedStateHandle()
        val first = FavoritePlacesViewModel(FakeStore(listOf(home)), FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved, newId = { "id-1" })
        advanceUntilIdle()
        first.startEdit(home)
        first.onLabelChange("Flat")
        // A new instance from the same saved state (as after process death) restores the draft.
        val restored = FavoritePlacesViewModel(FakeStore(listOf(home)), FakeFinder(), io = dispatcher, compute = dispatcher, savedState = saved, newId = { "id-1" })
        advanceUntilIdle()
        val editor = restored.state.value.editor
        assertEquals(FavoriteKind.HOME, editor?.kind)
        assertEquals("Flat", editor?.label)
        assertEquals(Coordinates(51.5, -0.12), editor?.coordinate)
    }

    @Test
    fun `a bundled station is selectable at once by its bundled position`() = runTest {
        val index = StationIndex(
            listOf(IndexedStation("940GZZLUOXC", "Oxford Circus", listOf("tube"), latitude = 51.5, longitude = -0.12)),
        )
        val model = FavoritePlacesViewModel(
            FakeStore(),
            FakeFinder(),
            io = dispatcher, compute = dispatcher,
            loadIndex = { index },
            debounceMillis = 300,
            newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxford")
        advanceUntilIdle()
        val results = model.state.value.editor!!.results
        assertTrue(results.isNotEmpty())
        // The bundled position anchors it, so it's pickable with no lookup.
        assertNotNull(results.first().coordinate())
    }
}
