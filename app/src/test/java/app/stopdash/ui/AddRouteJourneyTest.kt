package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.LineRef
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.PendingEnd
import app.stopdash.domain.PendingJourney
import app.stopdash.domain.RouteStop
import app.stopdash.domain.StopArrivals
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A route row's "Add favorite…" saves the ride picked on its To… as a long press on that stop on the route page saves
 * it ([routeJourneyTo]), through Settings' Add's own save ([saveFoundJourney]): added, already a favorite, or not
 * saved (SPEC *Journeys*). Public Victoria line stations only (SPEC *Privacy*).
 */
class AddRouteJourneyTest {
    private val now: Instant = Instant.parse("2026-10-23T08:00:00Z")

    private val row = DepartureRows.across(
        listOf(
            StopArrivals(
                "940GZZLUVIC",
                "Victoria",
                listOf(Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(120), "tube")),
                fetchedAt = now,
            ),
        ),
        now,
    ).single()

    private val sequence = LineSequence(
        routes = listOf(LineRoute("Brixton ↔ Walthamstow Central", listOf("940GZZLUVIC", "940GZZLUGPK", "940GZZLUWRR"))),
        stopNames = mapOf("940GZZLUVIC" to "Victoria", "940GZZLUGPK" to "Green Park", "940GZZLUWRR" to "Warren Street"),
        stopPositions = mapOf("940GZZLUWRR" to (51.5247 to -0.1384)),
        stopAreas = mapOf("940GZZLUWRR" to "HUBWRR"),
    )

    private val loaded = RouteStopsUi.Loaded(
        listOf(RouteStop("940GZZLUVIC", "Victoria"), RouteStop("940GZZLUGPK", "Green Park"), RouteStop("940GZZLUWRR", "Warren Street")),
        sequence.stopPositions,
        sequence,
    )

    private val warrenStreet = RouteStop("940GZZLUWRR", "Warren Street")

    private class Journeys(initial: List<FavoriteJourney>?, val failAdd: Boolean = false) : FavoriteJourneysStore {
        val state = MutableStateFlow(initial)
        val pending = MutableStateFlow<List<PendingJourney>?>(emptyList())
        override fun journeys(): Flow<List<FavoriteJourney>?> = state
        override fun pendingJourneys(): Flow<List<PendingJourney>?> = pending
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

    @Test
    fun `a picked stop saves the ride from the row's stop to it on the row's line`() = runTest {
        val journeys = Journeys(emptyList())
        assertNull(addRouteJourney(row, loaded, warrenStreet, journeys, worker = StandardTestDispatcher(testScheduler)))
        val saved = journeys.state.value!!.single()
        assertEquals("940GZZLUVIC", saved.from.stopId)
        assertEquals("940GZZLUWRR", saved.to.stopId)
        assertEquals("Warren Street", saved.to.name)
        // The end's position and stop area ride along, as a long press on the route page saves them.
        assertEquals(51.5247, saved.to.latitude!!, 0.0)
        assertEquals("HUBWRR", saved.to.areaId)
        assertEquals("victoria", saved.lineIds.single())
        assertEquals("tube", saved.mode)
    }

    @Test
    fun `a ride saved already, either way round, is said and not saved again`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        addRouteJourney(row, loaded, warrenStreet, journeys, worker = worker)
        assertEquals(
            JourneyAddNote.AlreadySaved("Victoria", "Warren Street"),
            addRouteJourney(row, loaded, warrenStreet, journeys, worker = worker),
        )
        assertEquals(1, journeys.state.value!!.size)
        // Saved the other way round (from Warren Street to here): the route page places it here, so it's this ride.
        val back = FavoriteJourney(JourneyEnd("940GZZLUWRR", "Warren Street"), JourneyEnd("940GZZLUVIC", "Victoria"), listOf(LineRef("victoria", "Victoria", "tube")))
        val reversed = Journeys(listOf(back))
        assertEquals(
            JourneyAddNote.AlreadySaved("Victoria", "Warren Street"),
            addRouteJourney(row, loaded, warrenStreet, reversed, worker = worker),
        )
        assertEquals(listOf(back), reversed.state.value)
    }

    @Test
    fun `a grayed copy of the same pair from Settings' Add goes as the ride is saved`() = runTest {
        val journeys = Journeys(emptyList())
        journeys.pending.value = listOf(PendingJourney(PendingEnd.Station("940GZZLUVIC", "Victoria"), PendingEnd.Station("HUBWRR", "Warren Street")))
        assertNull(addRouteJourney(row, loaded, warrenStreet, journeys, worker = StandardTestDispatcher(testScheduler)))
        assertEquals(emptyList<PendingJourney>(), journeys.pending.value)
    }

    @Test
    fun `a save that fails, or a file this build can't read, says it wasn't saved`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        assertEquals(
            JourneyAddNote.NotSaved("Victoria", "Warren Street"),
            addRouteJourney(row, loaded, warrenStreet, Journeys(emptyList(), failAdd = true), worker = worker),
        )
        val unreadable = Journeys(null)
        assertEquals(
            JourneyAddNote.NotSaved("Victoria", "Warren Street"),
            addRouteJourney(row, loaded, warrenStreet, unreadable, worker = worker),
        )
        assertNull(unreadable.state.value)
    }

    @Test
    fun `the save runs on the worker, not the caller`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "ride-worker") }
        try {
            var ranOn: String? = null
            val journeys = object : FavoriteJourneysStore by Journeys(emptyList()) {
                override suspend fun addReplacingPending(journey: FavoriteJourney, grayed: PendingJourney) {
                    ranOn = Thread.currentThread().name
                }
            }
            runBlocking { addRouteJourney(row, loaded, warrenStreet, journeys, worker = worker.asCoroutineDispatcher()) }
            assertTrue(ranOn.orEmpty().startsWith("ride-worker"))
        } finally {
            worker.shutdown()
        }
    }

    // A trip card's "Add favorite": its direct ride, saved through the same save.
    private val tripRide = FavoriteJourney(JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), listOf(LineRef("victoria", "Victoria", "tube")))

    @Test
    fun `a trip's ride saves once, then says it's a favorite already`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val journeys = Journeys(emptyList())
        assertNull(addLegJourney(tripRide, null, journeys, worker = worker))
        assertEquals(listOf(tripRide), journeys.state.value)
        assertEquals(JourneyAddNote.AlreadySaved("Victoria", "Warren Street"), addLegJourney(tripRide, null, journeys, worker = worker))
        assertEquals(JourneyAddNote.AlreadySaved("Victoria", "Warren Street"), addLegJourney(tripRide, null, Journeys(listOf(tripRide.reversed())), worker = worker))
        assertEquals(1, journeys.state.value!!.size)
    }

    @Test
    fun `a trip's ride that fails to save says it wasn't saved`() = runTest {
        assertEquals(
            JourneyAddNote.NotSaved("Victoria", "Warren Street"),
            addLegJourney(tripRide, null, Journeys(emptyList(), failAdd = true), worker = StandardTestDispatcher(testScheduler)),
        )
    }
}
