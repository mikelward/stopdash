package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store wrapper's mapping, save/remove, and version handling over a fake in-memory [DataStore]
 * so no Android file or Context is needed — the JSON serialization is covered by
 * [FavoritePlacesSerializerTest], the persisted mapping by `PersistedFavoritePlacesTest`, and the
 * pure list ops by `FavoritePlacesTest`. Mirrors
 * [DataStoreStarredRowsStoreTest]. Coordinates here are synthetic (SPEC *Privacy*).
 */
class DataStoreFavoritePlacesStoreTest {
    private val home = FavoritePlace("home-1", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))
    private val work = FavoritePlace("work-1", FavoriteKind.WORK, "Work", Coordinates(51.6, -0.10))

    private class FakeDataStore(
        initial: PersistedFavoritePlaces?,
    ) : DataStore<PersistedFavoritePlaces?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedFavoritePlaces?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedFavoritePlaces?) -> PersistedFavoritePlaces?,
        ): PersistedFavoritePlaces? = transform(state.value).also { state.value = it }
    }

    @Test
    fun `places reads an empty list when nothing is stored`() = runTest {
        val store = DataStoreFavoritePlacesStore(FakeDataStore(null))
        assertEquals(FavoritePlacesSet.Loaded(emptyList()), store.places().first())
    }

    @Test
    fun `save adds a place that is not stored`() = runTest {
        val store = DataStoreFavoritePlacesStore(FakeDataStore(null))
        store.save(home)
        assertEquals(FavoritePlacesSet.Loaded(listOf(home)), store.places().first())
    }

    @Test
    fun `save replaces a singleton kind in place`() = runTest {
        val store = DataStoreFavoritePlacesStore(FakeDataStore(null))
        store.save(home)
        store.save(work)
        val movedHome = home.copy(label = "Flat", coordinate = Coordinates(51.55, -0.11))
        store.save(movedHome)
        assertEquals(FavoritePlacesSet.Loaded(listOf(movedHome, work)), store.places().first())
    }

    @Test
    fun `remove deletes a place by id`() = runTest {
        val store = DataStoreFavoritePlacesStore(FakeDataStore(null))
        store.save(home)
        store.save(work)
        store.remove(home.id)
        assertEquals(FavoritePlacesSet.Loaded(listOf(work)), store.places().first())
    }

    @Test
    fun `a newer-version file reads as Unavailable, not as an empty list`() = runTest {
        val future = listOf(home).toPersisted().copy(version = PersistedFavoritePlaces.CURRENT_VERSION + 1)
        val store = DataStoreFavoritePlacesStore(FakeDataStore(future))
        assertEquals(FavoritePlacesSet.Unavailable, store.places().first())
    }

    @Test
    fun `a save against a newer-version file preserves it and warns, never overwriting`() = runTest {
        val future = listOf(home).toPersisted().copy(version = PersistedFavoritePlaces.CURRENT_VERSION + 1)
        val backing = FakeDataStore(future)
        val warnings = mutableListOf<String>()
        val store = DataStoreFavoritePlacesStore(backing, warn = { warnings += it })
        store.save(work)
        assertEquals(future, backing.data.first())
        assertEquals(FavoritePlacesSet.Unavailable, store.places().first())
        assertTrue(warnings.isNotEmpty())
    }

    @Test
    fun `a remove against a newer-version file preserves it and warns, never overwriting`() = runTest {
        val future = listOf(home).toPersisted().copy(version = PersistedFavoritePlaces.CURRENT_VERSION + 1)
        val backing = FakeDataStore(future)
        val warnings = mutableListOf<String>()
        val store = DataStoreFavoritePlacesStore(backing, warn = { warnings += it })
        store.remove(home.id)
        assertEquals(future, backing.data.first())
        assertTrue(warnings.isNotEmpty())
    }
}
