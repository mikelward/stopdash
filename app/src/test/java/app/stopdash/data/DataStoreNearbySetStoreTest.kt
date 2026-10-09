package app.stopdash.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DataStoreNearbySetStore] over an in-memory [DataStore]; synthetic stop ids only. */
class DataStoreNearbySetStoreTest {
    private class FakeDataStore(initial: PersistedNearbySet?) : DataStore<PersistedNearbySet?> {
        val state = MutableStateFlow(initial)
        var writes = 0
        var failing = false
        override val data: Flow<PersistedNearbySet?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedNearbySet?) -> PersistedNearbySet?,
        ): PersistedNearbySet? = transform(state.value).also {
            if (failing) throw java.io.IOException("disk full")
            if (it != state.value) writes++
            state.value = it
        }
    }

    @Test
    fun `nothing stored reads as no set, not an empty one`() = runTest {
        assertNull(DataStoreNearbySetStore(FakeDataStore(null)).nearby().first())
    }

    @Test
    fun `a saved set reads back, and an empty one stays empty`() = runTest {
        val store = DataStoreNearbySetStore(FakeDataStore(null))
        assertTrue(store.save(setOf("490000002B", "490000001A")))
        assertEquals(setOf("490000001A", "490000002B"), store.nearby().first())
        assertTrue(store.save(emptySet()))
        assertEquals(emptySet<String>(), store.nearby().first())
    }

    @Test
    fun `the same set again isn't rewritten`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreNearbySetStore(backing)
        store.save(setOf("490000001A", "490000002B"))
        assertFalse(store.save(setOf("490000002B", "490000001A")))
        assertEquals(1, backing.writes)
    }

    @Test
    fun `a set that couldn't be written is still the one shown, until one is`() = runTest {
        val backing = FakeDataStore(PersistedNearbySet(listOf("490000001A")))
        val store = DataStoreNearbySetStore(backing)
        backing.failing = true
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { store.save(setOf("490000002B")) } }
        // Not the place the rider left, which is what the file still says.
        assertEquals(setOf("490000002B"), store.nearby().first())
        backing.failing = false
        assertTrue(store.save(setOf("490000003C")))
        assertEquals(setOf("490000003C"), store.nearby().first())
        assertEquals(listOf("490000003C"), backing.state.value!!.stopIds)
    }

    @Test
    fun `a set shown from memory and then written counts as a change`() = runTest {
        val backing = FakeDataStore(PersistedNearbySet(listOf("490000001A")))
        val store = DataStoreNearbySetStore(backing)
        backing.failing = true
        runCatching { store.save(setOf("490000002B")) }
        backing.failing = false
        // Back to the place the file still names: the widget was showing the other set, so redraw.
        assertTrue(store.save(setOf("490000001A")))
        assertEquals(setOf("490000001A"), store.nearby().first())
    }

    @Test
    fun `a set that can't be read is no stops, never no set`() = runTest {
        val unreadable = object : DataStore<PersistedNearbySet?> {
            override val data: Flow<PersistedNearbySet?> = kotlinx.coroutines.flow.flow { throw java.io.IOException("unreadable") }
            override suspend fun updateData(
                transform: suspend (t: PersistedNearbySet?) -> PersistedNearbySet?,
            ): PersistedNearbySet? = throw java.io.IOException("unreadable")
        }
        // No set would show the whole snapshot, which may be the place the rider left.
        assertEquals(emptySet<String>(), DataStoreNearbySetStore(unreadable).nearby().first())
    }

    @Test
    fun `a failed write is tried again until it's stored, redrawing once for it`() = runTest {
        val backing = FakeDataStore(PersistedNearbySet(listOf("490000001A")))
        val store = DataStoreNearbySetStore(backing)
        backing.failing = true
        var redraws = 0
        val job = launch { store.saveUntilStored(setOf("490000002B")) { redraws++ } }
        advanceTimeBy(DataStoreNearbySetStore.FIRST_RETRY_MILLIS * 3)
        assertEquals("shown from memory at once", 1, redraws)
        assertEquals(listOf("490000001A"), backing.state.value!!.stopIds)
        backing.failing = false
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(listOf("490000002B"), backing.state.value!!.stopIds)
        assertEquals("no second redraw for the set already shown", 1, redraws)
    }

    @Test
    fun `a set stored at the first try redraws once`() = runTest {
        val store = DataStoreNearbySetStore(FakeDataStore(null))
        var redraws = 0
        store.saveUntilStored(setOf("490000001A")) { redraws++ }
        store.saveUntilStored(setOf("490000001A")) { redraws++ }
        assertEquals(1, redraws)
    }

    @Test
    fun `after a read error it shows no stops, then watches the file again`() = runTest {
        val backing = MutableStateFlow<PersistedNearbySet?>(PersistedNearbySet(listOf("490000001A")))
        var reads = 0
        val flaky = object : DataStore<PersistedNearbySet?> {
            override val data: Flow<PersistedNearbySet?> = kotlinx.coroutines.flow.flow {
                if (reads++ == 0) throw java.io.IOException("unreadable")
                emitAll(backing)
            }
            override suspend fun updateData(
                transform: suspend (t: PersistedNearbySet?) -> PersistedNearbySet?,
            ): PersistedNearbySet? = transform(backing.value).also { backing.value = it }
        }
        val seen = mutableListOf<Set<String>?>()
        val job = launch { DataStoreNearbySetStore(flaky).nearby().collect { seen += it } }
        advanceTimeBy(DataStoreNearbySetStore.FIRST_RETRY_MILLIS + 1)
        backing.value = PersistedNearbySet(listOf("490000002B"))
        advanceTimeBy(1)
        job.cancel()
        assertEquals(listOf(emptySet(), setOf("490000001A"), setOf("490000002B")), seen)
    }

    @Test
    fun `a render's read says when it failed, with no stops`() = runTest {
        val unreadable = object : DataStore<PersistedNearbySet?> {
            override val data: Flow<PersistedNearbySet?> = kotlinx.coroutines.flow.flow { throw java.io.IOException("unreadable") }
            override suspend fun updateData(
                transform: suspend (t: PersistedNearbySet?) -> PersistedNearbySet?,
            ): PersistedNearbySet? = throw java.io.IOException("unreadable")
        }
        assertEquals(DataStoreNearbySetStore.Read(emptySet(), failed = true), DataStoreNearbySetStore(unreadable).read())
        assertEquals(DataStoreNearbySetStore.Read(null, failed = false), DataStoreNearbySetStore(FakeDataStore(null)).read())
    }

    @Test
    fun `keep writes from its own scope, and a newer set replaces one still retrying`() = runTest {
        val backing = FakeDataStore(PersistedNearbySet(listOf("490000001A")))
        val store = DataStoreNearbySetStore(backing, writer = backgroundScope)
        backing.failing = true
        store.keep(setOf("490000002B")) {}
        advanceTimeBy(1)
        store.keep(setOf("490000003C")) {}
        backing.failing = false
        advanceTimeBy(DataStoreNearbySetStore.MAX_RETRY_MILLIS)
        // The older set's retry never lands over the newer one.
        assertEquals(listOf("490000003C"), backing.state.value!!.stopIds)
        assertEquals(setOf("490000003C"), store.nearby().first())
    }

    @Test
    fun `a redraw that couldn't be asked for is asked for again`() = runTest {
        val store = DataStoreNearbySetStore(FakeDataStore(null))
        var attempts = 0
        val job = launch {
            store.saveUntilStored(setOf("490000001A")) {
                attempts++
                if (attempts == 1) throw IllegalStateException("not queued")
            }
        }
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(2, attempts)
    }

    @Test
    fun `replaceIf replaces only the set it followed from`() = runTest {
        val store = DataStoreNearbySetStore(FakeDataStore(PersistedNearbySet(listOf("940GZZLUKSX"))))
        assertTrue(store.replaceIf(setOf("940GZZLUKSX"), setOf("940GZZLUWLO")))
        assertEquals(setOf("940GZZLUWLO"), store.nearby().first())
        // The app stored another set meanwhile: that one stands.
        assertFalse(store.replaceIf(setOf("940GZZLUKSX"), setOf("940GZZLUEUS")))
        assertEquals(setOf("940GZZLUWLO"), store.nearby().first())
    }

    @Test
    fun `replaceIf leaves a set still waiting to be written alone`() = runTest {
        val backing = FakeDataStore(PersistedNearbySet(listOf("940GZZLUKSX")))
        val store = DataStoreNearbySetStore(backing)
        backing.failing = true
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { store.save(setOf("940GZZLUEUS")) } }
        backing.failing = false
        // The app's newer set is shown from memory; the widget's follow mustn't replace it.
        assertFalse(store.replaceIf(setOf("940GZZLUKSX"), setOf("940GZZLUWLO")))
        assertEquals(setOf("940GZZLUEUS"), store.nearby().first())
    }
}
