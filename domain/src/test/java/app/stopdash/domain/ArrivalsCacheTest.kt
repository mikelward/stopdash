package app.stopdash.domain

import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ArrivalsCacheTest {
    private val now = Instant.parse("2026-09-26T08:00:00Z")

    private fun departure(minutes: Long) = Departure(
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        destination = "Brixton",
        platform = null,
        expectedArrival = now.plusSeconds(minutes * 60),
        mode = "tube",
    )

    @Test
    fun `a stop's arrivals are handed out with when they were fetched until they're stale`() {
        val cache = ArrivalsCache()
        val departures = listOf(departure(3))
        cache.put("940GZZLUOXC", departures, now)
        assertEquals(ArrivalsCache.Entry(departures, now), cache.get("940GZZLUOXC", now.plusSeconds(90)))
        assertNull(cache.get("940GZZLUOXC", now.plus(java.time.Duration.ofMinutes(5))))
        assertNull(cache.get("940GZZLUVIC", now))
    }

    @Test
    fun `an older fetch landing late doesn't replace a newer one`() {
        val cache = ArrivalsCache()
        val newer = listOf(departure(2))
        cache.put("940GZZLUOXC", newer, now)
        cache.put("940GZZLUOXC", listOf(departure(4)), now.minusSeconds(10))
        assertEquals(newer, cache.get("940GZZLUOXC", now)?.departures)
    }

    @Test
    fun `the least recently fetched stop goes first once full`() {
        val cache = ArrivalsCache()
        repeat(ArrivalsCache.MAX + 1) { i -> cache.put("stop$i", emptyList(), now) }
        assertNull(cache.get("stop0", now))
        assertEquals(emptyList<Departure>(), cache.get("stop${ArrivalsCache.MAX}", now)?.departures)
    }

    @Test
    fun `every arrivals fetch through the client lands in the cache, a failed one doesn't`() = runBlocking {
        val cache = ArrivalsCache()
        val departures = listOf(departure(3))
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> =
                if (stopId == "bad") throw TflException.Offline(null) else departures
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        val client = CachingTflClient(tfl, cache, clock = { now })
        assertEquals(departures, client.arrivals("940GZZLUOXC"))
        assertEquals(ArrivalsCache.Entry(departures, now), cache.get("940GZZLUOXC", now))
        runCatching { client.arrivals("bad") }
        assertNull(cache.get("bad", now))
    }

    @Test
    fun `an entry dated after the clock, set back since, is never handed out`() {
        val cache = ArrivalsCache()
        cache.put("940GZZLUOXC", listOf(departure(3)), now)
        assertNull(cache.get("940GZZLUOXC", now.minusSeconds(30)))
        assertNull(cache.recent("940GZZLUOXC", now.minusSeconds(30)))
    }

    @Test
    fun `a stop another client could answer differently isn't kept`() = runBlocking {
        val cache = ArrivalsCache()
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = listOf(departure(3))
            override fun shareable(stopId: String) = stopId != "910GEXAMPLE"
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        val client = CachingTflClient(tfl, cache, clock = { now })
        client.arrivals("910GEXAMPLE")
        client.arrivals("940GZZLUOXC")
        assertNull(cache.get("910GEXAMPLE", now))
        assertEquals(1, cache.get("940GZZLUOXC", now)?.departures?.size)
    }

    @Test
    fun `a fetch is kept at when it was asked for, so a slow older one can't pass for newer`() = runBlocking {
        val cache = ArrivalsCache()
        var clock = now
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                // The answer takes 20 s to come back.
                clock = clock.plusSeconds(20)
                return listOf(departure(3))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        CachingTflClient(tfl, cache, clock = { clock }).arrivals("940GZZLUOXC")
        assertEquals(now, cache.get("940GZZLUOXC", clock)?.fetchedAt)
    }

    @Test
    fun `a fetch asked for before the cache was cleared isn't kept`() {
        val cache = ArrivalsCache()
        val asked = cache.generation
        cache.clear()
        cache.put("940GZZLUOXC", listOf(departure(3)), now, generation = asked)
        assertNull(cache.get("940GZZLUOXC", now))
    }

    @Test
    fun `a fetch whose source changed while it was out isn't kept`() = runBlocking {
        val cache = ArrivalsCache()
        var railKey = false
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                // The National Rail key is added while the request is out.
                railKey = true
                return listOf(departure(3))
            }
            override fun shareable(stopId: String) = !railKey
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        CachingTflClient(tfl, cache, clock = { now }).arrivals("910GEXAMPLE")
        assertNull(cache.get("910GEXAMPLE", now))
    }

    @Test
    fun `arrivals from another source aren't handed out, however the key changed`() {
        val cache = ArrivalsCache()
        // Kept without National Rail times (a widget refresh, say); a reader with a key set doesn't take them.
        cache.put("910GEXAMPLE", listOf(departure(3)), now, source = false)
        assertNull(cache.get("910GEXAMPLE", now, source = true))
        assertEquals(1, cache.get("910GEXAMPLE", now, source = false)?.departures?.size)
    }

    private val source = Any()

    @Test
    fun `askers at once share one fetch, kept for the next`() = runTest {
        val cache = ArrivalsCache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val fetch: suspend () -> List<Departure>? = { fetches++; gate.await(); listOf(departure(3)) }
        val a = async { cache.fetchOnce("board", now, source, fetch) }
        val b = async { cache.fetchOnce("board", now, source, fetch) }
        runCurrent()
        gate.complete(Unit)
        assertEquals(listOf(departure(3)), a.await()?.departures)
        assertEquals(listOf(departure(3)), b.await()?.departures)
        assertEquals(1, fetches)
        val kept = cache.fetchOnce("board", now.plusSeconds(20), source) { error("kept, so not fetched") }
        assertEquals(listOf(departure(3)), kept?.departures)
        // With its own age, not the later ask's.
        assertEquals(now, kept?.fetchedAt)
    }

    @Test
    fun `a failed fetch is shared by its askers and never kept`() = runTest {
        val cache = ArrivalsCache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val fetch: suspend () -> List<Departure>? = { fetches++; gate.await(); null }
        val a = async { cache.fetchOnce("board", now, source, fetch) }
        val b = async { cache.fetchOnce("board", now, source, fetch) }
        runCurrent()
        gate.complete(Unit)
        assertNull(a.await())
        assertNull(b.await())
        assertEquals(1, fetches)
        cache.fetchOnce("board", now, source, fetch)
        assertEquals("asked again", 2, fetches)
    }

    @Test
    fun `an ask after a clear doesn't join a fetch from before it, whose answer isn't kept`() = runTest {
        val cache = ArrivalsCache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val fetch: suspend () -> List<Departure>? = { val n = ++fetches; gate.await(); listOf(departure(n.toLong())) }
        val before = async { cache.fetchOnce("board", now, source, fetch) }
        runCurrent()
        // A pull to refresh.
        cache.clear()
        val after = async { cache.fetchOnce("board", now, source, fetch) }
        runCurrent()
        gate.complete(Unit)
        assertEquals(listOf(departure(1)), before.await()?.departures)
        assertEquals(listOf(departure(2)), after.await()?.departures)
        assertEquals(listOf(departure(2)), cache.recent("board", now, source)?.departures)
    }

    @Test
    fun `when the asker whose fetch it is gives up, the others ask afresh`() = runTest {
        val cache = ArrivalsCache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val fetch: suspend () -> List<Departure>? = { fetches++; gate.await(); listOf(departure(3)) }
        val first = async { cache.fetchOnce("board", now, source, fetch) }
        runCurrent()
        val second = async { cache.fetchOnce("board", now, source, fetch) }
        runCurrent()
        first.cancel()
        runCurrent()
        gate.complete(Unit)
        assertEquals(listOf(departure(3)), second.await()?.departures)
        assertEquals(2, fetches)
    }
}
