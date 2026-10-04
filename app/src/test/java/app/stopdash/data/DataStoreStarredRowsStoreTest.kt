package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StarredRowSet
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The store wrapper's mapping, toggle, and version handling over a fake in-memory [DataStore]
 * so no Android file or Context is needed — the JSON serialization is covered by
 * [StarredRowsSerializerTest]. Mirrors [DataStoreWatchedStopsStoreTest].
 */
class DataStoreStarredRowsStoreTest {
    private val victoria = StarredRow("940GZZLUKSX", "victoria", "southbound")
    private val central = StarredRow("940GZZLUOXC", "central", "eastbound")

    private class FakeDataStore(initial: PersistedStarredRows?) : DataStore<PersistedStarredRows?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedStarredRows?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedStarredRows?) -> PersistedStarredRows?,
        ): PersistedStarredRows? = transform(state.value).also { state.value = it }
    }

    @Test
    fun `starred reads an empty set when nothing is stored`() = runTest {
        val store = DataStoreStarredRowsStore(FakeDataStore(null))
        assertEquals(StarredRowSet.Loaded(emptySet()), store.starred().first())
    }

    @Test
    fun `toggle adds a row that is not starred`() = runTest {
        val store = DataStoreStarredRowsStore(FakeDataStore(null))
        store.toggle(victoria)
        assertEquals(StarredRowSet.Loaded(setOf(victoria)), store.starred().first())
    }

    @Test
    fun `toggle removes a row that is already starred`() = runTest {
        val store = DataStoreStarredRowsStore(FakeDataStore(null))
        store.toggle(victoria)
        store.toggle(central)
        store.toggle(victoria)
        assertEquals(StarredRowSet.Loaded(setOf(central)), store.starred().first())
    }

    @Test
    fun `a newer-version set reads as Unavailable, not as an empty set`() = runTest {
        val future = setOf(victoria).toPersisted().copy(version = PersistedStarredRows.CURRENT_VERSION + 1)
        val store = DataStoreStarredRowsStore(FakeDataStore(future))
        assertEquals(StarredRowSet.Unavailable, store.starred().first())
    }

    @Test
    fun `a toggle against a newer-version set preserves it, never overwriting the user's stars`() = runTest {
        val future = setOf(victoria).toPersisted().copy(version = PersistedStarredRows.CURRENT_VERSION + 1)
        val backing = FakeDataStore(future)
        val store = DataStoreStarredRowsStore(backing)
        store.toggle(central)
        assertEquals(future, backing.data.first())
        assertEquals(StarredRowSet.Unavailable, store.starred().first())
    }

    @Test
    fun `the starred rows are read off the caller's thread`() {
        // Read and mapped on the store's worker, never the collector's (main) thread (AGENTS.md *Main
        // thread: read and dispatch only*): collected from a single thread of its own, the read runs on
        // the worker's.
        OffMainReads<PersistedStarredRows?>(null).use { reads ->
            val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
            caller.use { kotlinx.coroutines.runBlocking(it) { DataStoreStarredRowsStore(reads.dataStore, compute = reads.worker).starred().first() } }
            assertEquals(listOf(OffMainReads.WORKER), reads.reads)
        }
    }
}
