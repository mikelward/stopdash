package app.stopdash.domain

import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops only. */
class StopClosureCacheTest {
    private val now = Instant.parse("2026-09-26T08:00:00Z")
    private val closed = listOf(StopDisruption("Station closed"))

    // A kept lookup as when it was asked and what it found.
    private fun StopClosureCache.Lookup?.pair() = this?.let { it.at to it.notices }

    @Test
    fun `a stop's lookup is kept with when it was asked`() {
        val cache = StopClosureCache()
        cache.keep("A", cache.ask(now), closed)
        assertEquals(now to closed, cache["A"].pair())
        assertNull(cache["B"].pair())
    }

    @Test
    fun `a lookup asked earlier and landing late doesn't replace one asked later`() {
        val cache = StopClosureCache()
        val first = cache.ask(now)
        val second = cache.ask(now.plusSeconds(10))
        assertEquals(emptyList<StopDisruption>(), cache.keep("A", second, emptyList()).notices)
        // The first lands last: what the cache holds, the later lookup's, is what it gives back.
        assertEquals(emptyList<StopDisruption>(), cache.keep("A", first, closed).notices)
        assertEquals(now.plusSeconds(10) to emptyList<StopDisruption>(), cache["A"].pair())
        // One asked later still replaces it.
        assertEquals(closed, cache.keep("A", cache.ask(now.plusSeconds(20)), closed).notices)
    }

    @Test
    fun `lookups asked in the same instant keep their order`() {
        val cache = StopClosureCache()
        val first = cache.ask(now)
        val second = cache.ask(now)
        cache.keep("A", second, closed)
        assertEquals(closed, cache.keep("A", first, emptyList()).notices)
        assertEquals(now to closed, cache["A"].pair())
    }

    @Test
    fun `a lookup shown tells a newer one asked in the same instant by its place in line`() {
        val cache = StopClosureCache()
        val first = cache.ask(now)
        val second = cache.ask(now)
        // The first lands and is shown; the second lands after it.
        val shown = cache.keep("A", first, emptyList())
        assertNull(cache.since("A", shown.ask))
        cache.keep("A", second, closed)
        // Same instant, but asked later: newer than what's shown.
        assertEquals(closed, cache.since("A", shown.ask)?.notices)
        assertNull(cache.since("A", cache["A"]!!.ask))
    }

    @Test
    fun `a lookup asked later wins even when the clock stepped back`() {
        val cache = StopClosureCache()
        val first = cache.ask(now)
        val second = cache.ask(now.minusSeconds(60))
        cache.keep("A", second, closed)
        assertEquals(closed, cache.keep("A", first, emptyList()).notices)
    }

    @Test
    fun `a failed lookup is answered by one asked after it, never by one asked before`() {
        val cache = StopClosureCache()
        val failure = Result.failure<List<StopDisruption>>(IllegalStateException("offline"))
        val first = cache.ask(now)
        val second = cache.ask(now.plusSeconds(10))
        // Nothing kept: the failure stands.
        assertTrue(cache.settle("A", first, failure).isFailure)
        // One asked later landed first: it answers the late failure.
        cache.settle("A", second, Result.success(closed))
        assertEquals(Result.success(closed), cache.settle("A", first, failure).map { it.notices })
        // One asked earlier doesn't: its answer may be out of date, so the failure stands.
        assertTrue(cache.settle("A", cache.ask(now.plusSeconds(20)), failure).isFailure)
        // What was asked after a lookup is newer than it; what was asked before isn't.
        assertEquals(closed, cache.since("A", first)?.notices)
        assertNull(cache.since("A", second))
        // And a failure keeps no answer, but the one asked before it isn't reused over it (Codex,
        // PR #375).
        assertNull(cache["A"])
        // A success settles as it keeps.
        assertEquals(Result.success(emptyList<StopDisruption>()), cache.settle("A", cache.ask(now.plusSeconds(30)), Result.success(emptyList())).map { it.notices })
        assertEquals(now.plusSeconds(30) to emptyList<StopDisruption>(), cache["A"].pair())
    }

    @Test
    fun `it keeps the most recently looked up stops`() {
        val cache = StopClosureCache()
        repeat(StopClosureCache.MAX + 1) { cache.keep("S$it", cache.ask(now), emptyList()) }
        assertNull(cache["S0"].pair())
        assertEquals(now to emptyList<StopDisruption>(), cache["S1"].pair())
        assertEquals(now to emptyList<StopDisruption>(), cache["S${StopClosureCache.MAX}"].pair())
    }

    @Test
    fun `an answer asked before a lookup that failed since isn't reused, until one asked after it succeeds`() {
        val cache = StopClosureCache()
        val first = cache.ask(now)
        val second = cache.ask(now.plusSeconds(10))
        val third = cache.ask(now.plusSeconds(20))
        // The third fails at once; the second lands after it, and is kept as the stop's last answer.
        assertTrue(cache.settle("A", third, Result.failure(IllegalStateException())).isFailure)
        assertEquals(closed, cache.settle("A", second, Result.success(closed)).getOrNull()?.notices)
        // But it was asked before the lookup that failed, so it isn't given for reuse (Codex, PR #375).
        assertNull(cache["A"])
        // The first, failing last, is answered by the second, as before, and changes nothing.
        assertEquals(closed, cache.settle("A", first, Result.failure(IllegalStateException())).getOrNull()?.notices)
        assertNull(cache["A"])
        // One asked after the failure answers for it.
        cache.keep("A", cache.ask(now.plusSeconds(30)), emptyList())
        assertEquals(now.plusSeconds(30) to emptyList<StopDisruption>(), cache["A"].pair())
    }

    @Test
    fun `a failure dropped for room takes the answer asked before it too`() {
        val cache = StopClosureCache()
        cache.keep("A", cache.ask(now), closed)
        cache.settle("A", cache.ask(now.plusSeconds(10)), Result.failure(IllegalStateException()))
        assertNull(cache["A"])
        // Enough failures at other stops that A's is dropped: its older answer isn't offered again
        // (Codex, PR #375).
        repeat(StopClosureCache.MAX) { cache.settle("S$it", cache.ask(now.plusSeconds(20)), Result.failure(IllegalStateException())) }
        assertNull(cache["A"])
    }

    // Every lookup this cache's single-flight test client is sent, and a gate each waits on.
    private class Sender {
        val sent = mutableListOf<List<String>>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false

        suspend fun send(ids: List<String>): Map<String, Result<List<StopDisruption>>> {
            sent += ids
            gate?.await()
            return ids.associateWith { if (fail) Result.failure(IllegalStateException("offline")) else Result.success(emptyList()) }
        }
    }

    private suspend fun TestScope.look(cache: StopClosureCache, sender: Sender, vararg ids: String, group: (List<String>) -> List<List<String>> = { listOf(it) }) =
        cache.lookUp(this, Dispatchers.Unconfined, ids.toList(), reusable = { true }, now = { now }, dismissals = { 0L }, group = group, send = sender::send)

    @Test
    fun `two checks needing one stop at once send one request and share its answer`() = runTest {
        val cache = StopClosureCache()
        val sender = Sender().apply { gate = CompletableDeferred() }
        val first = look(cache, sender, "A")
        val second = look(cache, sender, "A")
        assertTrue(second.getValue("A").joined)
        val a = async { first.getValue("A").await() }
        val b = async { second.getValue("A").await() }
        advanceUntilIdle()
        sender.gate!!.complete(Unit)
        assertEquals(a.await().getOrThrow().ask, b.await().getOrThrow().ask)
        assertEquals(listOf(listOf("A")), sender.sent)
    }

    @Test
    fun `a stop answered recently is taken from the cache, not asked again`() = runTest {
        val cache = StopClosureCache()
        val sender = Sender()
        look(cache, sender, "A").getValue("A").await()
        val again = look(cache, sender, "A").getValue("A")
        assertTrue(again.cached)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `a failed request's failure is shared, not asked again in turn by each waiting`() = runTest {
        val cache = StopClosureCache()
        val sender = Sender().apply { gate = CompletableDeferred(); fail = true }
        val first = look(cache, sender, "A")
        val second = look(cache, sender, "A")
        val a = async { first.getValue("A").await() }
        val b = async { second.getValue("A").await() }
        advanceUntilIdle()
        sender.gate!!.complete(Unit)
        assertTrue(a.await().isFailure)
        assertTrue(b.await().isFailure)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `a request its owner cancels is asked again for a check that joined it`() = runTest {
        val cache = StopClosureCache()
        val sender = Sender().apply { gate = CompletableDeferred() }
        val owner = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job(coroutineContext[kotlinx.coroutines.Job]))
        cache.lookUp(owner, Dispatchers.Unconfined, listOf("A"), reusable = { true }, now = { now }, dismissals = { 0L }, send = sender::send)
        advanceUntilIdle()
        val joined = look(cache, sender, "A").getValue("A")
        assertTrue(joined.joined)
        val answer = async { joined.await() }
        advanceUntilIdle()
        // The owner leaves before the answer comes; the joiner asks again in its own scope.
        owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        sender.gate = null
        advanceUntilIdle()
        assertTrue(answer.await().isSuccess)
        assertEquals(2, sender.sent.size)
    }

    @Test
    fun `stops asked together go in one batch, each answered on its own`() = runTest {
        val cache = StopClosureCache()
        val sender = Sender().apply { gate = CompletableDeferred() }
        val batch = look(cache, sender, "P1", "P2", "P3")
        // Another check needing one of them, and one more: joins that one, asks only the other.
        val other = look(cache, sender, "P2", "P4")
        assertTrue(other.getValue("P2").joined)
        assertFalse(other.getValue("P4").joined)
        launch { batch.values.forEach { it.await() } }
        launch { other.values.forEach { it.await() } }
        advanceUntilIdle()
        sender.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(listOf("P1", "P2", "P3"), listOf("P4")), sender.sent)
        assertTrue(cache["P2"] != null)
    }

    @Test
    fun `which stops to ask, and each answer, are worked out on the worker, not the caller's thread`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.asCoroutineDispatcher()
            val cache = StopClosureCache()
            val caller = Thread.currentThread()
            // Where each step ran: the reuse check, the grouping, the place in line, and the answer's handling.
            val ranOn = mutableMapOf<String, MutableSet<Thread>>()
            fun ran(step: String) = synchronized(ranOn) { ranOn.getOrPut(step) { mutableSetOf() } += Thread.currentThread() }
            cache.keep("A", cache.ask(now, 0L), emptyList())
            val pending = cache.lookUp(
                this, worker, listOf("A", "B", "C"),
                reusable = { ran("reusable"); true },
                now = { ran("now"); now }, dismissals = { 0L },
                group = { ran("group"); listOf(it) },
            ) { ids -> ran("send"); ids.associateWith { Result.success(emptyList()) } }
            assertTrue(pending.getValue("A").cached)
            assertTrue(pending.getValue("C").await().isSuccess)
            assertEquals(setOf("reusable", "group", "now", "send"), ranOn.keys)
            assertTrue("$ranOn", ranOn.values.none { caller in it })
        } finally {
            executor.shutdownNow()
        }
    }
}
