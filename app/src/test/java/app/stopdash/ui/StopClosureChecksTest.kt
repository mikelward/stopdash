package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A closure check's answers and how old a verdict it can vouch for. Made-up stop ids. */
class StopClosureChecksTest {
    private val now = Instant.parse("2026-10-04T08:00:00Z")

    private val client = object : TflClient {
        override suspend fun arrivals(stopId: String) = emptyList<Departure>()
        override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
        override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
    }

    @Test
    fun `each stop is as old as its own answer, a reused one included`() = runBlocking {
        val cache = StopClosureCache()
        val checks = StopClosureChecks(client, cache, Duration.ofMinutes(5), Dispatchers.Unconfined, Dispatchers.Unconfined, {}, "test")
        // Another screen's answer, asked when 3 dismissals had been counted, is reused; the other stop
        // is asked now, at 7.
        cache.keep("STOP_A", cache.ask(now, dismissals = 3), emptyList())
        val result = checks.check(listOf("STOP_A", "STOP_B"), cache.ask(now, dismissals = 7), now)
        assertEquals(setOf("STOP_A", "STOP_B"), result.found.keys)
        // The reused stop as old as its answer; the one asked now, as old as this check, not held back by it.
        assertEquals(mapOf("STOP_A" to 3L, "STOP_B" to 7L), result.dismissals)
    }

    @Test
    fun `a trip's check of a stop the list already has a request out for joins it`() = kotlinx.coroutines.test.runTest {
        val cache = StopClosureCache()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var asked = 0
        val counting = object : TflClient by client {
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                asked++
                gate.await()
                return listOf(StopDisruption("Station closed"))
            }
        }
        val checks = StopClosureChecks(counting, cache, Duration.ofMinutes(5), Dispatchers.Unconfined, Dispatchers.Unconfined, {}, "test")
        // The list's request for the stop goes out first.
        val list = cache.lookUp(this, Dispatchers.Unconfined, listOf("STOP_A"), reusable = { true }, now = { now }, dismissals = { 0L }) {
            mapOf("STOP_A" to runCatching { counting.stopDisruptions("STOP_A") })
        }
        testScheduler.runCurrent()
        val trip = async { checks.check(listOf("STOP_A"), cache.ask(now), now) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        assertEquals(listOf(StopDisruption("Station closed")), trip.await().found["STOP_A"])
        assertEquals(listOf(StopDisruption("Station closed")), list.getValue("STOP_A").await().getOrThrow().notices)
        assertEquals(1, asked)
    }
}
