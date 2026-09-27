package app.stopdash.ui

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaces
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PostcodeResolution
import app.stopdash.domain.PostcodeResolver
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        override suspend fun searchStations(query: String): List<StationMatch> = search(query)
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

    private fun vm(store: FavoritePlacesStore, finder: StationFinder) =
        FavoritePlacesViewModel(store, finder, io = dispatcher, debounceMillis = 300, newId = { "id-1" })

    @Test
    fun `saved places are exposed once the store loads`() = runTest {
        val place = FavoritePlace("h", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
        val model = vm(FakeStore(listOf(place)), FakeFinder())
        advanceUntilIdle()
        assertTrue(model.state.value.loaded)
        assertEquals(FavoritePlacesSet.Loaded(listOf(place)), model.state.value.places)
    }

    @Test
    fun `adding searches, picks a positioned match, and saves it`() = runTest {
        val store = FakeStore()
        val model = vm(store, FakeFinder(search = { listOf(oxford, positionless) }))
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        assertEquals(listOf(oxford, positionless), model.state.value.editor?.results)
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
        val model = vm(FakeStore(), FakeFinder(search = { listOf(positionless) }, stops = { emptyList() }))
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
                search = { listOf(positionless) },
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
            FakeFinder(search = { listOf(positionless) }, stops = { throw TflException.NotFound(null) }),
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
            FakeFinder(search = { listOf(positionless) }, stops = { members }),
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

    @Test
    fun `a complete postcode with several places offers a chooser to pick from`() = runTest {
        val candidates = listOf(
            PlaceCandidate("X1 9XX", Coordinates(51.50, -0.10)),
            PlaceCandidate("X1 9XY", Coordinates(51.52, -0.12)),
        )
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(), postcodes = { PostcodeResolution.Options(candidates) },
            io = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        // Resolves on its own; TfL returns several places, so nothing is auto-adopted (SPEC D9).
        model.onQueryChange("X1 9XX")
        advanceUntilIdle()
        val offered = model.state.value.editor!!
        assertEquals(candidates, offered.postcodeCandidates)
        assertFalse(offered.postcodeResolving)
        assertNull(offered.coordinate)
        // The rider picks one and it's adopted.
        model.onPickCandidate(candidates[1])
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertEquals(Coordinates(51.52, -0.12), editor.coordinate)
        assertEquals("X1 9XY", editor.placeName)
        assertTrue(editor.postcodeCandidates.isEmpty())
        assertTrue(editor.canSave)
    }

    @Test
    fun `a complete postcode adopts its single place automatically`() = runTest {
        val candidate = PlaceCandidate("X1 9XX", Coordinates(51.50, -0.10))
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(), postcodes = { PostcodeResolution.Resolved(candidate) },
            io = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        // Typing a complete postcode resolves it — one unambiguous place, adopted without a tap.
        model.onQueryChange("X1 9XX")
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertEquals(Coordinates(51.50, -0.10), editor.coordinate)
        assertEquals("X1 9XX", editor.placeName)
        assertTrue(editor.postcodeCandidates.isEmpty())
        assertTrue(editor.canSave)
    }

    @Test
    fun `a postcode resolve failure is retryable`() = runTest {
        var attempt = 0
        val candidate = PlaceCandidate("X1 9XX", Coordinates(51.50, -0.10))
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            postcodes = {
                attempt++
                if (attempt == 1) throw TflException.Offline(null) else PostcodeResolution.Resolved(candidate)
            },
            io = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        // The automatic resolve on a complete postcode fails first.
        model.onQueryChange("X1 9XX")
        advanceUntilIdle()
        assertTrue(model.state.value.editor!!.postcodeFailed)
        // Retrying resolves, and the single place is adopted straight away.
        model.resolvePostcode()
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertFalse(editor.postcodeFailed)
        assertEquals(Coordinates(51.50, -0.10), editor.coordinate)
    }

    @Test
    fun `resolvePostcode is a no-op until the postcode is complete`() = runTest {
        var calls = 0
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(), postcodes = { calls++; PostcodeResolution.None },
            io = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("X1") // a partial postcode
        advanceUntilIdle()
        model.resolvePostcode()
        advanceUntilIdle()
        assertEquals(0, calls) // not resolvable yet, so no request
        assertTrue(model.state.value.editor!!.postcodeCandidates.isEmpty())
    }

    @Test
    fun `changing the query clears postcode candidates`() = runTest {
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(),
            // Two places, so a complete postcode offers a chooser rather than auto-adopting.
            postcodes = {
                PostcodeResolution.Options(
                    listOf(
                        PlaceCandidate("X1 9XX", Coordinates(51.5, -0.1)),
                        PlaceCandidate("X1 9XY", Coordinates(51.52, -0.12)),
                    ),
                )
            },
            io = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("X1 9XX")
        advanceUntilIdle()
        assertTrue(model.state.value.editor!!.postcodeCandidates.isNotEmpty())
        // A partial postcode isn't resolved, so the old candidates are dropped, not replaced.
        model.onQueryChange("X2")
        advanceUntilIdle()
        assertTrue(model.state.value.editor!!.postcodeCandidates.isEmpty())
    }

    @Test
    fun `a postcode-shaped query is not sent to the station search`() = runTest {
        var searchCalls = 0
        val model = FavoritePlacesViewModel(
            FakeStore(),
            FakeFinder(search = { searchCalls++; emptyList() }),
            postcodes = { PostcodeResolution.None },
            io = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("X1 9XX") // a postcode: resolved via the Journey Planner on tap, not searched
        advanceUntilIdle()
        assertEquals(0, searchCalls)
        // A plain two-letter prefix is how station names start ("Ox" → Oxford Circus), so it still searches.
        model.onQueryChange("Ox")
        advanceUntilIdle()
        assertTrue(searchCalls >= 1)
    }

    @Test
    fun `a postcode that resolves to nothing reports no places`() = runTest {
        val model = FavoritePlacesViewModel(
            FakeStore(), FakeFinder(), postcodes = { PostcodeResolution.None },
            io = dispatcher, debounceMillis = 300, newId = { "id-1" },
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("X1 9XX")
        advanceUntilIdle()
        model.resolvePostcode()
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertTrue(editor.postcodeNoResults)
        assertTrue(editor.postcodeCandidates.isEmpty())
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
        val model = vm(FakeStore(), FakeFinder(search = { listOf(oxford) }))
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
        val model = vm(store, FakeFinder(search = { q -> if (q.startsWith("oxf")) listOf(oxford) else listOf(victoria) }))
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
        val model = vm(FakeStore(listOf(place)), FakeFinder(search = { listOf(victoria) }))
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
        val model = vm(FakeStore(listOf(place)), FakeFinder(search = { listOf(victoria) }))
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
        val model = vm(FakeStore(), FakeFinder(search = { q -> if (q.startsWith("oxf")) listOf(oxford) else listOf(victoria) }))
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
        val model = vm(store, FakeFinder(search = { listOf(oxford) }))
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
            store, FakeFinder(search = { listOf(oxford) }),
            io = dispatcher, savedState = saved, debounceMillis = 300, newId = { "first-id" },
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
            store, FakeFinder(), io = dispatcher, savedState = saved, debounceMillis = 300, newId = { "second-id" },
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
            store, FakeFinder(search = { listOf(oxford) }),
            io = dispatcher, savedState = saved, debounceMillis = 300, newId = { "first-id" },
        )
        advanceUntilIdle()
        first.startAdd(FavoriteKind.HOME, "Home")
        first.onQueryChange("oxf")
        advanceUntilIdle()
        first.onPick(oxford)
        // Restored after process death, with a different id source, still saves under the assigned id.
        val restored = FavoritePlacesViewModel(
            store, FakeFinder(), io = dispatcher, savedState = saved, debounceMillis = 300, newId = { "second-id" },
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
        val model = vm(store, FakeFinder(search = { listOf(oxford) }))
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
        val model = vm(store, FakeFinder(search = { listOf(oxford) }))
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
        val model = vm(store, FakeFinder(search = { listOf(oxford) }))
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
        val model = vm(store, FakeFinder(search = { listOf(oxford) }))
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
        val first = FavoritePlacesViewModel(FakeStore(listOf(home)), FakeFinder(), io = dispatcher, savedState = saved, newId = { "id-1" })
        advanceUntilIdle()
        first.startEdit(home)
        first.onLabelChange("Flat")
        // A new instance from the same saved state (as after process death) restores the draft.
        val restored = FavoritePlacesViewModel(FakeStore(listOf(home)), FakeFinder(), io = dispatcher, savedState = saved, newId = { "id-1" })
        advanceUntilIdle()
        val editor = restored.state.value.editor
        assertEquals(FavoriteKind.HOME, editor?.kind)
        assertEquals("Flat", editor?.label)
        assertEquals(Coordinates(51.5, -0.12), editor?.coordinate)
    }

    @Test
    fun `a bundled station stays selectable when TfL is offline`() = runTest {
        val index = StationIndex(
            listOf(IndexedStation("940GZZLUOXC", "Oxford Circus", listOf("tube"), latitude = 51.5, longitude = -0.12)),
        )
        val model = FavoritePlacesViewModel(
            FakeStore(),
            FakeFinder(search = { throw TflException.Offline(null) }),
            io = dispatcher,
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
        // The bundled position anchors it, so it's pickable despite TfL being offline.
        assertNotNull(results.first().coordinate())
    }

    @Test
    fun `retry re-runs the search after a failure`() = runTest {
        var attempt = 0
        val model = vm(
            FakeStore(),
            FakeFinder(search = {
                attempt++
                if (attempt == 1) throw TflException.Offline(null) else listOf(oxford)
            }),
        )
        advanceUntilIdle()
        model.startAdd(FavoriteKind.CUSTOM, "")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        assertTrue(model.state.value.editor!!.searchFailed)
        model.retrySearch() // no retyping needed
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertFalse(editor.searchFailed)
        assertEquals(listOf(oxford), editor.results)
    }

    @Test
    fun `a failed search is reported without matches`() = runTest {
        val model = vm(FakeStore(), FakeFinder(search = { throw TflException.Offline(null) }))
        advanceUntilIdle()
        model.startAdd(FavoriteKind.HOME, "Home")
        model.onQueryChange("oxf")
        advanceUntilIdle()
        val editor = model.state.value.editor!!
        assertTrue(editor.searchFailed)
        assertTrue(editor.results.isEmpty())
        assertFalse(editor.searching)
    }
}
