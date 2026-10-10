package app.stopdash.ui

import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRef
import app.stopdash.domain.PendingEnd
import app.stopdash.domain.PendingJourney
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' Add: two picked stations saved as a journey on a line both serve, or why not (SPEC *Journeys*). */
class AddFavoriteJourneyPairTest {
    // Public TfL interchanges only (SPEC *Privacy*).
    private val kingsCross = StationMatch("HUBKGX", "King's Cross St. Pancras")
    private val waterloo = StationMatch("940GZZLUWLO", "Waterloo")
    private val canadaWater = StationMatch("940GZZLUCWR", "Canada Water")

    private fun tube(id: String, vararg lines: String) = StopLocation(id, "Station $id", 51.5, -0.12, lines.map { LineRef(it, it, "tube") }, clusterId = id)

    private class Finder(val stops: Map<String, List<StopLocation>>, val fail: TflException? = null) : StationFinder {
        override suspend fun searchStations(query: String): List<StationMatch> = emptyList()
        override suspend fun stationStops(id: String): List<StopLocation> {
            fail?.let { throw it }
            return stops[id].orEmpty()
        }
    }

    private val finder = Finder(
        mapOf(
            "HUBKGX" to listOf(tube("940GZZLUKSX", "northern", "victoria")),
            "940GZZLUWLO" to listOf(tube("940GZZLUWLO", "northern", "jubilee")),
            "940GZZLUCWR" to listOf(tube("940GZZLUCWR", "jubilee")),
        ),
    )

    private class Journeys(
        initial: List<FavoriteJourney>?,
        val failAdd: Boolean = false,
        pendingInitial: List<PendingJourney>? = initial?.let { emptyList() },
    ) : FavoriteJourneysStore {
        val state = MutableStateFlow(initial)
        val pending = MutableStateFlow(pendingInitial)
        override fun journeys(): Flow<List<FavoriteJourney>?> = state
        override fun pendingJourneys(): Flow<List<PendingJourney>?> = pending
        override suspend fun addPending(journey: PendingJourney) {
            if (failAdd) throw IOException("disk")
            pending.value = pending.value.orEmpty() + journey
        }
        override suspend fun removePending(journey: PendingJourney) {
            pending.value = pending.value?.filterNot { it.key == journey.key }
        }
        override suspend fun toggle(journey: FavoriteJourney) {}
        override suspend fun remove(journey: FavoriteJourney) {}
        override suspend fun add(journey: FavoriteJourney) {
            if (failAdd) throw IOException("disk")
            state.value = state.value.orEmpty() + journey
        }
    }

    @After
    fun clearNote() = JourneyAdds.dismiss()

    @Test
    fun `two stations on one line are saved as a journey on it`() = runTest {
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(kingsCross, waterloo, finder, journeys, worker = StandardTestDispatcher(testScheduler)))
        val saved = journeys.state.value!!.single()
        assertEquals("northern", saved.lineId)
        assertEquals(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras", 51.5, -0.12, "940GZZLUKSX"), saved.from)
        assertEquals("Waterloo", saved.to.name)
    }

    @Test
    fun `two stations no one line serves are saved grayed, once`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        assertNull(addFavoriteJourneyPair(kingsCross, canadaWater, finder, journeys, worker = worker))
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(
            listOf(PendingJourney(PendingEnd.Station("HUBKGX", "King's Cross St. Pancras"), PendingEnd.Station("940GZZLUCWR", "Canada Water"))),
            journeys.pending.value,
        )
        // Either way round, it's the same one.
        assertEquals(
            JourneyAddNote.AlreadySaved("Canada Water", "King's Cross St. Pancras"),
            addFavoriteJourneyPair(canadaWater, kingsCross, finder, journeys, worker = worker),
        )
        assertEquals(1, journeys.pending.value!!.size)
        assertEquals(
            JourneyAddNote.NotSaved("King's Cross St. Pancras", "Canada Water"),
            addFavoriteJourneyPair(kingsCross, canadaWater, finder, Journeys(emptyList(), failAdd = true), worker = worker),
        )
    }

    @Test
    fun `a grayed pair that resolves to a line later is followed, and its gray copy goes`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        addFavoriteJourneyPair(kingsCross, canadaWater, finder, journeys, worker = worker)
        assertEquals(1, journeys.pending.value!!.size)
        // TfL now reports the Jubilee at King's Cross too.
        val later = Finder(
            mapOf(
                "HUBKGX" to listOf(tube("940GZZLUKSX", "northern", "victoria", "jubilee")),
                "940GZZLUCWR" to listOf(tube("940GZZLUCWR", "jubilee")),
            ),
        )
        assertNull(addFavoriteJourneyPair(canadaWater, kingsCross, later, journeys, worker = worker))
        assertEquals("jubilee", journeys.state.value!!.single().lineId)
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    @Test
    fun `a followed pair that no longer resolves to a line isn't saved again grayed`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        addFavoriteJourneyPair(kingsCross, waterloo, finder, journeys, worker = worker)
        // TfL no longer reports a line both serve.
        val later = Finder(
            mapOf(
                "HUBKGX" to listOf(tube("940GZZLUKSX", "victoria")),
                "940GZZLUWLO" to listOf(tube("940GZZLUWLO", "jubilee")),
            ),
        )
        assertEquals(
            JourneyAddNote.AlreadySaved("Waterloo", "King's Cross St. Pancras"),
            addFavoriteJourneyPair(waterloo, kingsCross, later, journeys, worker = worker),
        )
        assertEquals(1, journeys.state.value!!.size)
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    @Test
    fun `a favorite place at an end is saved grayed`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        val home = PendingEnd.Place("place-1", "Home")
        val station = PendingEnd.Station("940GZZLUWLO", "Waterloo")
        assertNull(addPendingJourneyPair(home, station, journeys, worker = worker))
        assertEquals(listOf(PendingJourney(home, station)), journeys.pending.value)
        assertTrue(journeys.state.value!!.isEmpty())
        assertEquals(JourneyAddNote.AlreadySaved("Waterloo", "Home"), addPendingJourneyPair(station, home, journeys, worker = worker))
        assertEquals(JourneyAddNote.SameStation("Home", "Home"), addPendingJourneyPair(home, home, journeys, worker = worker))
        assertEquals(1, journeys.pending.value!!.size)
        // Unreadable: kept as it is, so said rather than passed off as added.
        val unreadable = Journeys(null)
        assertEquals(JourneyAddNote.NotSaved("Home", "Waterloo"), addPendingJourneyPair(home, station, unreadable, worker = worker))
        assertNull(unreadable.pending.value)
    }

    @Test
    fun `a place pair is saved on the worker, not the caller`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "add-worker") }
        try {
            var ranOn: String? = null
            val journeys = object : FavoriteJourneysStore by Journeys(emptyList()) {
                override suspend fun addPending(journey: PendingJourney) {
                    ranOn = Thread.currentThread().name
                }
            }
            runBlocking {
                addPendingJourneyPair(PendingEnd.Place("place-1", "Home"), PendingEnd.Station("940GZZLUWLO", "Waterloo"), journeys, worker = worker.asCoroutineDispatcher())
            }
            assertTrue(ranOn.orEmpty().startsWith("add-worker"))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun `why a pair isn't added is said`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        assertEquals(JourneyAddNote.SameStation("Waterloo", "Waterloo"), addFavoriteJourneyPair(waterloo, waterloo, finder, journeys, worker = worker))
        assertEquals(
            JourneyAddNote.LookupFailed("King's Cross St. Pancras", "Waterloo", DeparturesUiState.Error.Kind.OFFLINE),
            addFavoriteJourneyPair(kingsCross, waterloo, Finder(emptyMap(), TflException.Offline(null)), journeys, worker = worker),
        )
        assertEquals(
            JourneyAddNote.NotSaved("King's Cross St. Pancras", "Waterloo"),
            addFavoriteJourneyPair(kingsCross, waterloo, finder, Journeys(emptyList(), failAdd = true), worker = worker),
        )
        assertTrue(journeys.state.value!!.isEmpty())
        // A file this build can't read is kept as it is, so nothing would be saved: said, not passed off as added.
        val unreadable = Journeys(null)
        assertEquals(JourneyAddNote.NotSaved("King's Cross St. Pancras", "Waterloo"), addFavoriteJourneyPair(kingsCross, waterloo, finder, unreadable, worker = worker))
        assertEquals(null, unreadable.state.value)
    }

    @Test
    fun `a pair already saved is said, either way round`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        addFavoriteJourneyPair(kingsCross, waterloo, finder, journeys, worker = worker)
        assertEquals(
            JourneyAddNote.AlreadySaved("Waterloo", "King's Cross St. Pancras"),
            addFavoriteJourneyPair(waterloo, kingsCross, finder, journeys, worker = worker),
        )
        assertEquals(1, journeys.state.value!!.size)
    }

    @Test
    fun `the lookup and save run on the worker, not the caller`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "add-worker") }
        try {
            var ranOn: String? = null
            // A new journey is saved through addReplacingPending, which drops any grayed copy in the same write.
            val journeys = object : FavoriteJourneysStore by Journeys(emptyList()) {
                override suspend fun addReplacingPending(journey: FavoriteJourney, grayed: PendingJourney) {
                    ranOn = Thread.currentThread().name
                }
            }
            runBlocking { addFavoriteJourneyPair(kingsCross, waterloo, finder, journeys, worker = worker.asCoroutineDispatcher()) }
            assertTrue(ranOn.orEmpty().startsWith("add-worker"))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun `only the latest pair's outcome is said`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val first = CompletableDeferred<JourneyAddNote?>()
        JourneyAdds.attempt(scope, kingsCross, waterloo) { first.await() }
        assertEquals(JourneyAddNote.Adding("King's Cross St. Pancras", "Waterloo"), JourneyAdds.note.value)
        JourneyAdds.attempt(scope, kingsCross, canadaWater) { JourneyAddNote.AlreadySaved("King's Cross St. Pancras", "Canada Water") }
        scope.testScheduler.advanceUntilIdle()
        first.complete(JourneyAddNote.NotSaved("King's Cross St. Pancras", "Waterloo"))
        scope.testScheduler.advanceUntilIdle()
        assertEquals(JourneyAddNote.AlreadySaved("King's Cross St. Pancras", "Canada Water"), JourneyAdds.note.value)
    }
}
