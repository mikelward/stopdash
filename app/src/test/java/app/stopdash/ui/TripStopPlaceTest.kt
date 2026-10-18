package app.stopdash.ui

import app.stopdash.domain.BusStopIndex
import app.stopdash.domain.Coordinates
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.LineRef
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StopLocation
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a trip's stop or station is, as its Direct section's place (SPEC *Direct to a place*). */
class TripStopPlaceTest {
    // Public interchanges with their published positions, and a synthetic bus stop by Euston (SPEC *Privacy*).
    private val stations = StationIndex(
        listOf(
            IndexedStation("940GZZLUEUS", "Euston", listOf("tube"), latitude = 51.5282, longitude = -0.1337),
            IndexedStation("940GZZLUKSX", "King's Cross St. Pancras", listOf("tube"), interchangeId = "HUBKGX", latitude = 51.5308, longitude = -0.1238),
            IndexedStation("910GKGX", "King's Cross", listOf("national-rail"), interchangeId = "HUBKGX", latitude = 51.5320, longitude = -0.1233),
        ),
    )
    private val busStops = BusStopIndex.of(
        listOf(StopLocation("490EXAMPLEA", "Stop A", 51.5287, -0.1337, listOf(LineRef("X1", "X1", "bus")), clusterId = "490G0EXAMPLE")),
    )

    @Test
    fun `a station, an interchange and a bus stop are each placed from the bundled lists`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        suspend fun place(id: String) = tripStopPlace(id, { stations }, { busStops }, io = worker, compute = worker)
        assertEquals(Coordinates(51.5282, -0.1337), place("940GZZLUEUS"))
        val hub = place("HUBKGX")!!
        assertEquals(51.5314, hub.latitude, 1e-9)
        assertEquals(-0.12355, hub.longitude, 1e-9)
        assertEquals(Coordinates(51.5287, -0.1337), place("490EXAMPLEA"))
        // A bus stop area, at the middle of its poles.
        assertEquals(Coordinates(51.5287, -0.1337), place("490G0EXAMPLE"))
    }

    @Test
    fun `a stop neither list places is none, and said without the stop`() = runTest {
        val warned = mutableListOf<String>()
        val worker = StandardTestDispatcher(testScheduler)
        assertNull(tripStopPlace("910GNOWHERE", { stations }, { busStops }, warn = { warned += it }, io = worker, compute = worker))
        assertEquals(1, warned.size)
        assertTrue(warned.none { "910GNOWHERE" in it })
        // An unreadable list is an empty one: no place, never a guess.
        assertNull(tripStopPlace("940GZZLUEUS", { StationIndex.EMPTY }, { BusStopIndex.EMPTY }, io = worker, compute = worker))
    }

    @Test
    fun `the lists are read and searched on the workers, not the caller`() {
        val io = Executors.newSingleThreadExecutor { Thread(it, "place-io") }
        val compute = Executors.newSingleThreadExecutor { Thread(it, "place-compute") }
        try {
            var readOn: String? = null
            var busesOn: String? = null
            runBlocking {
                tripStopPlace(
                    "490EXAMPLEA",
                    stations = { readOn = Thread.currentThread().name; stations },
                    busStops = { busesOn = Thread.currentThread().name; busStops },
                    io = io.asCoroutineDispatcher(),
                    compute = compute.asCoroutineDispatcher(),
                )
            }
            assertTrue(readOn.orEmpty().startsWith("place-io"))
            assertTrue(busesOn.orEmpty().startsWith("place-compute"))
        } finally {
            io.shutdown()
            compute.shutdown()
        }
    }
}
