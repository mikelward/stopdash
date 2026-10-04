package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

    /** A read that throws a transient [IOException] once, then reads normally — to prove [places]
     *  retries rather than collapsing the flow permanently. */
    private class FlakyReadDataStore : DataStore<PersistedFavoritePlaces?> {
        private var thrown = false
        override val data: Flow<PersistedFavoritePlaces?> = flow {
            if (!thrown) {
                thrown = true
                throw IOException("transient read failure")
            }
            emit(null)
        }
        override suspend fun updateData(
            transform: suspend (t: PersistedFavoritePlaces?) -> PersistedFavoritePlaces?,
        ): PersistedFavoritePlaces? = throw UnsupportedOperationException()
    }

    @Test
    fun `a discard tombstone reads as Discarded, not empty`() = runTest {
        // The corruption handler replaced the unreadable file with a durable tombstone; the read must
        // say the data was lost (and, being on disk, it survives a restart) — not present it as empty.
        val store = DataStoreFavoritePlacesStore(FakeDataStore(PersistedFavoritePlaces(discarded = true)))
        assertEquals(FavoritePlacesSet.Discarded, store.places().first())
    }

    @Test
    fun `an empty store reads as an empty list, not Discarded`() = runTest {
        val store = DataStoreFavoritePlacesStore(FakeDataStore(null))
        assertEquals(FavoritePlacesSet.Loaded(emptyList()), store.places().first())
    }

    @Test
    fun `a newer-schema tombstone reads as Unavailable, not Discarded`() = runTest {
        // An older build reading a newer-schema file that also sets discarded must treat it as
        // Unavailable (preserved, not writable), not Discarded (writable) — else a save would no-op and
        // drop the new draft.
        val newer = PersistedFavoritePlaces(version = PersistedFavoritePlaces.CURRENT_VERSION + 1, discarded = true)
        val store = DataStoreFavoritePlacesStore(FakeDataStore(newer))
        assertEquals(FavoritePlacesSet.Unavailable, store.places().first())
    }

    @Test
    fun `saving over a discard tombstone clears it`() = runTest {
        val ds = FakeDataStore(PersistedFavoritePlaces(discarded = true))
        val store = DataStoreFavoritePlacesStore(ds)
        assertEquals(FavoritePlacesSet.Discarded, store.places().first())
        store.save(home)
        // The tombstone is gone; the saved place reads back normally.
        assertEquals(FavoritePlacesSet.Loaded(listOf(home)), store.places().first())
    }

    @Test
    fun `places surfaces a transient read failure as unavailable, then recovers`() = runTest {
        val store = DataStoreFavoritePlacesStore(FlakyReadDataStore())
        // The failed read surfaces Unavailable (so the screen isn't stuck on "Loading…"), then the retry
        // succeeds and it recovers to the real list (delay is virtual here).
        val emissions = store.places().take(2).toList()
        assertEquals(FavoritePlacesSet.Unavailable, emissions[0])
        assertEquals(FavoritePlacesSet.Loaded(emptyList()), emissions[1])
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

    @Test
    fun `the favorite places are mapped off the caller's thread`() {
        // Mapped on the store's worker, never the collector's (main) thread (AGENTS.md *Main thread: read
        // and dispatch only*): collected from a thread of its own, the stored list is read on the worker's.
        OffMainReads().use { reads ->
            val stored = PersistedFavoritePlaces(places = reads.recorded(PersistedFavoritePlace("p", "CUSTOM", "Place", 0.0, 0.0)))
            reads.fromCaller { DataStoreFavoritePlacesStore(reads.dataStore<PersistedFavoritePlaces?>(stored), compute = reads.worker).places().first() }
            assertTrue(reads.reads.isNotEmpty())
            assertEquals(setOf(OffMainReads.WORKER), reads.reads.toSet())
        }
    }
}
