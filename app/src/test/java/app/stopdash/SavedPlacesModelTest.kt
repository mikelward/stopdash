package app.stopdash

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaceIcon
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The saved places are held by a view model ([SavedPlacesModel]), so a recreated screen starts from the
 * very list it last had, which the chips worked out for it still match (Codex on #539).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedPlacesModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // Obviously synthetic: a place at the origin.
    private val place = FavoritePlace("p", FavoriteKind.CUSTOM, "Place", Coordinates(0.0, 0.0))

    private class Store(initial: FavoritePlacesSet) : FavoritePlacesStore {
        val sets = MutableStateFlow(initial)
        override fun places() = sets
        override suspend fun save(place: FavoritePlace) {}
        override suspend fun remove(id: String) {}
    }

    @Test
    fun the_places_read_are_held_as_the_same_list_until_they_change() = runTest(dispatcher) {
        val list = listOf(place)
        val store = Store(FavoritePlacesSet.Loaded(list))
        val model = SavedPlacesModel(store, worker = dispatcher)
        // Not read yet: none, and said to be unread, not unreadable, so no place's icon is known yet.
        assertEquals(SavedPlaces.UNREAD, model.state.value)
        assertEquals(false, model.state.value.icons.ready)
        advanceUntilIdle()
        // Read: the store's own list, held for any screen that collects it afresh.
        assertTrue(model.state.value.read)
        assertSame(list, model.state.value.places)
        // A read outage: read, but none can be shown, not "no places".
        store.sets.value = FavoritePlacesSet.Unavailable
        advanceUntilIdle()
        assertTrue(model.state.value.read)
        assertNull(model.state.value.places)
    }

    @Test
    fun an_unreadable_answer_keeps_the_icons_from_the_last_readable_one() = runTest(dispatcher) {
        // A read outage the store retries says nothing about which places are saved: an open trip's
        // saved place keeps its icon, never a pin it then swaps back (Codex on #824).
        val home = place.copy(icon = FavoritePlaceIcon.HOME)
        val store = Store(FavoritePlacesSet.Loaded(listOf(home)))
        val model = SavedPlacesModel(store, worker = dispatcher)
        advanceUntilIdle()
        val icons = model.state.value.icons
        assertEquals(FavoritePlaceIcon.HOME, icons.forPlace("p"))
        store.sets.value = FavoritePlacesSet.Unavailable
        advanceUntilIdle()
        assertNull(model.state.value.places)
        assertSame(icons, model.state.value.icons)
        // A discarded file is a real loss, though: its places are gone, and so are their icons.
        store.sets.value = FavoritePlacesSet.Discarded
        advanceUntilIdle()
        assertTrue(model.state.value.icons.ready)
        assertNull(model.state.value.icons.forPlace("p"))
    }

    @Test
    fun an_unreadable_first_answer_leaves_the_icons_unresolved() = runTest(dispatcher) {
        // Nothing readable yet: a place end can't be told apart from a saved place, so it still waits.
        val store = Store(FavoritePlacesSet.Unavailable)
        val model = SavedPlacesModel(store, worker = dispatcher)
        advanceUntilIdle()
        assertTrue(model.state.value.read)
        assertEquals(false, model.state.value.icons.ready)
        store.sets.value = FavoritePlacesSet.Loaded(listOf(place.copy(icon = FavoritePlaceIcon.HOME)))
        advanceUntilIdle()
        assertEquals(FavoritePlaceIcon.HOME, model.state.value.icons.forPlace("p"))
    }

    @Test
    fun the_places_icons_are_indexed_on_the_worker_never_the_main_thread() = runTest(dispatcher) {
        // Each read of a place notes its thread: the index is built from them on the worker alone
        // (AGENTS.md *Main thread*), and the screen only reads the index.
        val reads = ThreadRecorder()
        val home = place.copy(icon = FavoritePlaceIcon.HOME)
        val places = object : AbstractList<FavoritePlace>() {
            override val size: Int get() = 1
            override fun get(index: Int): FavoritePlace {
                reads.note()
                return home
            }
        }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "places-worker") }.asCoroutineDispatcher()
        worker.use {
            val model = SavedPlacesModel(Store(FavoritePlacesSet.Loaded(places)), worker = it)
            val read = model.state.first { state -> state.read }
            assertEquals(FavoritePlaceIcon.HOME, read.icons.forPlace("p"))
            assertTrue(reads.threads().isNotEmpty())
            assertEquals(listOf("places-worker"), reads.threads().distinct())
        }
    }

    @Test
    fun chips_are_pending_until_the_places_are_read_and_worked_out() {
        val places = listOf(place)
        // Not read yet: wait.
        assertTrue(app.stopdash.ui.chipsPending(read = false, places = null, chips = null))
        // Read and readable, no chips yet: wait.
        assertTrue(app.stopdash.ui.chipsPending(read = true, places = places, chips = null))
        // Worked out: show.
        assertEquals(false, app.stopdash.ui.chipsPending(read = true, places = places, chips = emptyList()))
        // Unreadable, or none saved: no row, nothing to wait on.
        assertEquals(false, app.stopdash.ui.chipsPending(read = true, places = null, chips = null))
        assertEquals(false, app.stopdash.ui.chipsPending(read = true, places = emptyList(), chips = null))
    }

    @Test
    fun saved_places_are_compared_by_identity_never_by_their_places() {
        // A list whose every read is counted: comparing two states must read none of it.
        var reads = 0
        fun counted() = object : AbstractList<FavoritePlace>() {
            override val size: Int get() = 1
            override fun get(index: Int): FavoritePlace {
                reads++
                return place
            }
        }
        // Two reads of the store, equal place for place.
        val first = SavedPlaces(read = true, places = counted())
        val second = SavedPlaces(read = true, places = counted())
        assertEquals(false, first == second)
        assertEquals(0, reads)
    }
}

