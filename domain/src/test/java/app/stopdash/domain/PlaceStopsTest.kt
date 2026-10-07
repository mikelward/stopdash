package app.stopdash.domain

import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceStopsTest {
    // Synthetic coordinates only (SPEC *Privacy*): never a real position in a fixture.
    private val place = Coordinates(0.0, 0.0)

    // About 111 m per 0.001 degree of longitude at the equator.
    private fun stop(id: String, lonOffset: Double, hubId: String = "") =
        StopLocation(id, "Stop $id", 0.0, lonOffset, hubId = hubId)

    @Test
    fun `the stops within a short walk of the place, nearest first`() {
        val ends = PlaceStops.ends(
            place,
            listOf(stop("far", 0.006), stop("beyond", 0.009), stop("near", 0.001, hubId = "HUBA")),
        )
        assertEquals(listOf(DirectTrips.End("near", "Stop near", "HUBA"), DirectTrips.End("far", "Stop far")), ends)
    }

    @Test
    fun `a stop listed twice is one end`() {
        val ends = PlaceStops.ends(place, listOf(stop("a", 0.002), stop("a", 0.002)))
        assertEquals(listOf("a"), ends.map { it.id })
    }

    @Test
    fun `a cached answer centered nearby still covers the whole walk around the place`() = runBlocking {
        // A lookup about 145 m west of the place, cached; this place's own lookup reuses it.
        val cache = NearbyStopsCache()
        val west = -0.0013
        cache.put(0.0, west, PlaceStopsFinder.LOOKUP_METERS, StopFinder.DEFAULT_NEARBY_STOP_TYPES, listOf(stop("east-edge", 0.007)))
        val finder = CachingStopFinder(
            object : StopFinder {
                override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>) =
                    error("answered from the cache")
            },
            cache,
        )
        // 780 m east of the place, 920 m from the cached lookup's center: inside its wider ask, kept.
        assertEquals(listOf("east-edge"), PlaceStopsFinder(finder, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined).ends(place).map { it.id })
    }

    @Test
    fun `the walk from the place is as far as the rider's max walk reaches at their pace`() {
        // As far as a walk that long reads as that many minutes ([TripTiming.accessWalk]), never farther.
        for (speed in WalkingSpeed.entries) for (max in MaxWalk.entries) {
            val meters = PlaceStops.walkMeters(max, speed)
            assertTrue("$max $speed", TripTiming.accessWalk(meters.toDouble(), speed).toMinutes() <= max.minutes)
            assertTrue("$max $speed", TripTiming.accessWalk(meters + 30.0, speed).toMinutes() > max.minutes - 1)
        }
        // Farther at a faster pace, and farther for a longer walk.
        assertTrue(PlaceStops.walkMeters(MaxWalk.FIFTEEN, WalkingSpeed.FAST) > PlaceStops.walkMeters(MaxWalk.FIFTEEN, WalkingSpeed.AVERAGE))
        assertTrue(PlaceStops.walkMeters(MaxWalk.TWENTY, WalkingSpeed.AVERAGE) > PlaceStops.walkMeters(MaxWalk.FIFTEEN, WalkingSpeed.AVERAGE))
    }

    @Test
    fun `a longer walk keeps a stop beyond the default walk, and asks the finder that far`() = runBlocking {
        var asked = 0
        val finder = object : StopFinder {
            override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
                asked = radiusMeters
                return listOf(stop("near", 0.001), stop("beyond", 0.009))
            }
        }
        val ends = PlaceStopsFinder(finder, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined).ends(place, walkMeters = 1_100)
        assertEquals(listOf("near", "beyond"), ends.map { it.id })
        assertEquals(1_100 + 150, asked)
    }

    @Test
    fun `no stops in walking distance is no ends`() {
        assertTrue(PlaceStops.ends(place, listOf(stop("beyond", 0.01))).isEmpty())
    }

    @Test
    fun `the lookup asks the finder for the place's walk and works it out off the caller's thread`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "place-worker") }.asCoroutineDispatcher()
        try {
            var asked: Triple<Double, Double, Int>? = null
            var readOn: String? = null
            val stops = object : AbstractList<StopLocation>() {
                override val size = 1
                override fun get(index: Int): StopLocation {
                    readOn = Thread.currentThread().name
                    return stop("near", 0.001)
                }
            }
            val finder = object : StopFinder {
                override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
                    asked = Triple(latitude, longitude, radiusMeters)
                    return stops
                }
            }
            val ends = PlaceStopsFinder(finder, io = worker, compute = worker).ends(place)
            assertEquals(listOf("near"), ends.map { it.id })
            // The walk plus a cached answer's reuse distance, so a reused lookup still covers the whole walk.
            assertEquals(Triple(0.0, 0.0, PlaceStops.WALK_METERS + 150), asked)
            // Debug builds suffix the coroutine's name to the thread's.
            assertTrue(readOn.orEmpty().startsWith("place-worker"))
        } finally {
            worker.close()
        }
    }
}
