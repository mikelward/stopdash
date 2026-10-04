package app.stopdash

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val model = SavedPlacesModel(store)
        // Not read yet: none, and said to be unread, not unreadable.
        assertEquals(SavedPlaces.UNREAD, model.state.value)
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

