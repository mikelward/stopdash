package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.FavoriteJourney
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Store mapping, toggle, versioning, and JSON round-trip. Example stations and synthetic positions. */
class DataStoreFavoriteJourneysStoreTest {
    private val journey = FavoriteJourney(
        from = JourneyEnd("940GZZLUHGT", "Highgate", 51.5, -0.12),
        to = JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras", 51.49, -0.12, areaId = "HUBKGX"),
        lineId = "northern",
        lineName = "Northern",
        mode = "tube",
    )

    private class FakeDataStore(initial: PersistedFavoriteJourneys?) : DataStore<PersistedFavoriteJourneys?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedFavoriteJourneys?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedFavoriteJourneys?) -> PersistedFavoriteJourneys?,
        ): PersistedFavoriteJourneys? = transform(state.value).also { state.value = it }
    }

    @Test
    fun `nothing stored reads as no journeys`() = runTest {
        assertEquals(emptyList<FavoriteJourney>(), DataStoreFavoriteJourneysStore(FakeDataStore(null)).journeys().first())
    }

    @Test
    fun `toggle adds, and toggling the reverse removes`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.toggle(journey)
        assertEquals(listOf(journey), store.journeys().first())
        store.toggle(journey.reversed())
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
    }

    @Test
    fun `add saves a journey once, either way round, and never removes one`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.add(journey)
        // A second Favorite, landing before the first is read back, leaves it saved.
        store.add(journey.reversed())
        assertEquals(listOf(journey), store.journeys().first())
    }

    @Test
    fun `remove unstars a journey, either way round, and never stars one`() = runTest {
        val store = DataStoreFavoriteJourneysStore(FakeDataStore(null))
        store.toggle(journey)
        store.remove(journey.reversed())
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
        // A second Remove, landing after the first's write, leaves it unstarred.
        store.remove(journey)
        assertEquals(emptyList<FavoriteJourney>(), store.journeys().first())
    }

    @Test
    fun `a newer-version file reads as unavailable and a toggle preserves it`() = runTest {
        val future = listOf(journey).toPersisted().copy(version = PersistedFavoriteJourneys.CURRENT_VERSION + 1)
        val data = FakeDataStore(future)
        val warnings = mutableListOf<String>()
        val store = DataStoreFavoriteJourneysStore(data, warn = { warnings += it })
        assertNull(store.journeys().first())
        store.toggle(journey)
        assertEquals(future, data.data.first())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a disk read failure reads as unavailable and is logged`() = runTest {
        val failing = object : DataStore<PersistedFavoriteJourneys?> {
            override val data: Flow<PersistedFavoriteJourneys?> = flow { throw IOException("disk") }
            override suspend fun updateData(
                transform: suspend (t: PersistedFavoriteJourneys?) -> PersistedFavoriteJourneys?,
            ): PersistedFavoriteJourneys? = throw IOException("disk")
        }
        val warnings = mutableListOf<String>()
        assertNull(DataStoreFavoriteJourneysStore(failing, warn = { warnings += it }).journeys().first())
        assertEquals(listOf("favorite journeys read failed: IOException"), warnings)
    }

    @Test
    fun `the same stations starred on two lines read as one journey`() = runTest {
        val twice = listOf(journey, journey.copy(lineId = "other")).toPersisted()
        assertEquals(listOf(journey), twice.toDomain())
    }

    @Test
    fun `journeys round-trip through JSON`() = runTest {
        val out = ByteArrayOutputStream()
        FavoriteJourneysSerializer.writeTo(listOf(journey).toPersisted(), out)
        val back = FavoriteJourneysSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf(journey), back!!.toDomain())
    }

    @Test
    fun `the favorite journeys are mapped off the caller's thread`() {
        // Mapped on the store's worker, never the collector's (main) thread (AGENTS.md *Main thread: read
        // and dispatch only*): collected from a thread of its own, the stored list is read on the worker's.
        OffMainReads().use { reads ->
            val stored = PersistedFavoriteJourneys(journeys = reads.recorded(PersistedFavoriteJourney(PersistedJourneyEnd("A", "A"), PersistedJourneyEnd("B", "B"), "victoria")))
            reads.fromCaller { DataStoreFavoriteJourneysStore(reads.dataStore<PersistedFavoriteJourneys?>(stored), compute = reads.worker).journeys().first() }
            assertTrue(reads.reads.isNotEmpty())
            assertEquals(setOf(OffMainReads.WORKER), reads.reads.toSet())
        }
    }

    @Test
    fun `a favorite journey is saved off the caller's thread`() {
        // An edit maps and searches the whole stored list, and DataStore runs it in the caller's context:
        // a tap's main thread hops to the worker first (AGENTS.md *Main thread*).
        OffMainReads().use { reads ->
            val stored = PersistedFavoriteJourneys(journeys = reads.recorded(PersistedFavoriteJourney(PersistedJourneyEnd("A", "A"), PersistedJourneyEnd("B", "B"), "victoria")))
            reads.fromCaller { DataStoreFavoriteJourneysStore(reads.dataStore<PersistedFavoriteJourneys?>(stored), compute = reads.worker).add(journey) }
            assertTrue(reads.reads.isNotEmpty())
            assertEquals(setOf(OffMainReads.WORKER), reads.reads.toSet())
        }
    }
}
