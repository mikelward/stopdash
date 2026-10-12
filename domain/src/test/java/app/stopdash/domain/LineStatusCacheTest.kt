package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One answer and one request at a time per line, across surfaces (SPEC *Freshness → Shared line status*). */
class LineStatusCacheTest {
    private val severe = LineStatus("victoria", 6, "Severe Delays")
    private val good = LineStatus("victoria", LineStatus.GOOD_SERVICE, "Good Service")

    // Codex on #765: two surfaces asking at once, the earlier request's answer landing last must not
    // replace the later one's.
    @Test
    fun `an earlier request's answer landing last doesn't replace a later one's`() {
        val cache = LineStatusCache()
        val earlier = cache.ticket()
        val later = cache.ticket()
        cache.set("victoria", LineStatusCache.Held.Verdict(Instant.EPOCH.plusSeconds(10), good), later)
        cache.set("victoria", LineStatusCache.Held.Verdict(Instant.EPOCH.plusSeconds(5), severe), earlier)
        assertEquals(LineStatusCache.Held.Verdict(Instant.EPOCH.plusSeconds(10), good), cache["victoria"])
    }

    // Codex on #783: a pull is ordered among requests by ticket, so one sent just before it on the same
    // clock tick is still before it.
    @Test
    fun `an answer counts as after a point only if its request was sent after it`() {
        val cache = LineStatusCache()
        val before = cache.ticket()
        val point = cache.ticket()
        val answer = LineStatusCache.Held.Verdict(Instant.EPOCH, severe)
        cache.set("victoria", answer, before)
        assertEquals(null, cache.get("victoria", askedAfter = point))
        assertEquals(answer, cache.get("victoria", askedAfter = 0L))
        cache.set("victoria", answer, cache.ticket())
        assertEquals(answer, cache.get("victoria", askedAfter = point))
        assertEquals(null, cache.get("jubilee", askedAfter = 0L))
    }

    // Codex on #783: a surface's pull is held here, beside the answers, rather than by a screen's model.
    @Test
    fun `a pull is held per surface, ordered among the requests`() {
        val cache = LineStatusCache()
        assertEquals(0L, cache.pulledAfter("nearby"))
        val before = cache.ticket()
        cache.pull("nearby")
        val after = cache.ticket()
        assertTrue(cache.pulledAfter("nearby") in (before + 1) until after)
        assertEquals(0L, cache.pulledAfter("trip"))
    }

    // Ordered by request, not by stamp: after the clock moves back, a later request's earlier-stamped
    // answer still replaces the one before it.
    @Test
    fun `a later request's answer replaces an earlier one whatever its stamp`() {
        val cache = LineStatusCache()
        cache.set("victoria", LineStatusCache.Held.Verdict(Instant.EPOCH.plusSeconds(100), severe), cache.ticket())
        cache.set("victoria", LineStatusCache.Held.Omitted(Instant.EPOCH), cache.ticket())
        assertEquals(LineStatusCache.Held.Omitted(Instant.EPOCH), cache["victoria"])
    }

    // Codex on #765: a later request that answers for other lines and leaves this one out says nothing
    // new about it, so a line TfL said it doesn't know stays marked so; a verdict clears the mark.
    @Test
    fun `an unrecognized line stays so through a later omission until a verdict`() {
        val cache = LineStatusCache()
        cache.set("eurostar", LineStatusCache.Held.Omitted(Instant.EPOCH, unrecognized = true), cache.ticket())
        cache.set("eurostar", LineStatusCache.Held.Omitted(Instant.EPOCH.plusSeconds(5)), cache.ticket())
        assertEquals(LineStatusCache.Held.Omitted(Instant.EPOCH.plusSeconds(5), unrecognized = true), cache["eurostar"])
        val verdict = LineStatusCache.Held.Verdict(Instant.EPOCH.plusSeconds(10), LineStatus("eurostar", LineStatus.GOOD_SERVICE, "Good Service"))
        cache.set("eurostar", verdict, cache.ticket())
        assertEquals(verdict, cache["eurostar"])
        cache.set("eurostar", LineStatusCache.Held.Omitted(Instant.EPOCH.plusSeconds(15)), cache.ticket())
        assertEquals(LineStatusCache.Held.Omitted(Instant.EPOCH.plusSeconds(15)), cache["eurostar"])
    }

    // Codex on #766: two surfaces past the wait at once must not both ask. The claim is atomic, so the
    // second runs nothing, waits for the first, and is told to read again.
    @Test
    fun `a claim on a line already being asked about runs nothing and waits for it`() = runTest {
        val cache = LineStatusCache()
        val gate = CompletableDeferred<Unit>()
        val first = launch { cache.asking(setOf("victoria", "jubilee"), cache.mark()) { gate.await() } }
        testScheduler.runCurrent()
        var asked = false
        val second = async { cache.asking(setOf("jubilee", "central"), cache.mark()) { asked = true } }
        testScheduler.runCurrent()
        assertTrue("waiting on the first", second.isActive)
        gate.complete(Unit)
        first.join()
        assertEquals(LineStatusCache.Asked.Waited, second.await())
        assertFalse("nothing asked, not even the line nobody else was asking about", asked)
        // Once the first is done, a claim goes through.
        assertEquals(LineStatusCache.Asked.Ran(1), cache.asking(setOf("jubilee"), cache.mark()) { 1 })
    }

    @Test
    fun `a canceled request gives up its claim`() = runTest {
        val cache = LineStatusCache()
        val first = launch { cache.asking(setOf("victoria"), cache.mark()) { CompletableDeferred<Unit>().await() } }
        testScheduler.runCurrent()
        first.cancel()
        first.join()
        assertFalse(cache.awaitAsking(setOf("victoria")))
        assertEquals(LineStatusCache.Asked.Ran(2), cache.asking(setOf("victoria"), cache.mark()) { 2 })
    }

    // Codex on #766: a caller that read the cache while another request was out, then reached the
    // claim only after that request answered and let go, must not ask on its stale reading.
    @Test
    fun `a claim made on a reading another answer has since overtaken asks nothing`() = runTest {
        val cache = LineStatusCache()
        val since = cache.mark()
        cache["victoria"] = LineStatusCache.Held.Verdict(Instant.EPOCH, good)
        var asked = false
        assertEquals(LineStatusCache.Asked.Waited, cache.asking(setOf("victoria"), since) { asked = true })
        assertFalse(asked)
        // Read again from a fresh mark, a line nobody answered since is asked.
        assertEquals(LineStatusCache.Asked.Ran(3), cache.asking(setOf("victoria"), cache.mark()) { 3 })
    }

    // [LineStatusCache.check], the one way every screen asks about a line (SPEC *Freshness → Shared line status*).

    @Test
    fun `a check asks what has no standing answer, holds it, and the next within the window asks nothing`() = runTest {
        val cache = LineStatusCache()
        var now = Instant.EPOCH.plusSeconds(1_000)
        val asked = mutableListOf<List<String>>()
        val first = cache.check(listOf("victoria", "jubilee"), { now }, Duration.ofSeconds(90)) { ids ->
            asked += ids
            listOf(severe)
        }
        assertEquals(listOf(setOf("victoria", "jubilee")), asked.map { it.toSet() })
        assertEquals(mapOf("victoria" to severe), first.statuses)
        assertEquals(setOf("victoria", "jubilee"), first.fresh)
        // Jubilee was asked and left out: an answer, held as such.
        assertEquals(LineStatusCache.Held.Omitted(now), first.answers["jubilee"])
        now = now.plusSeconds(30)
        val second = cache.check(listOf("victoria", "jubilee"), { now }, Duration.ofSeconds(90)) { ids ->
            asked += ids
            emptyList()
        }
        assertEquals(1, asked.size)
        assertEquals(mapOf("victoria" to severe), second.statuses)
        assertEquals(emptySet<String>(), second.fresh)
    }

    @Test
    fun `a pull asks every line afresh`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        cache["victoria"] = LineStatusCache.Held.Verdict(now, severe)
        var asked: List<String>? = null
        val checked = cache.check(listOf("victoria"), { now }, Duration.ofSeconds(90), refresh = true) { ids ->
            asked = ids
            listOf(good)
        }
        assertEquals(listOf("victoria"), asked)
        assertEquals(mapOf("victoria" to good), checked.statuses)
    }

    // Codex on #776: a screen the rider pulled asks again for what it had before the pull.
    @Test
    fun `an answer had before the rider's pull is asked again, one had after it isn't`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        cache["victoria"] = LineStatusCache.Held.Verdict(now.minusSeconds(20), severe)
        val pulled = cache.ticket()
        cache["jubilee"] = LineStatusCache.Held.Verdict(now.minusSeconds(5), good.copy(lineId = "jubilee"))
        var asked: List<String>? = null
        val checked = cache.check(listOf("victoria", "jubilee"), { now }, Duration.ofSeconds(90), askedAfter = pulled) { ids ->
            asked = ids
            listOf(good)
        }
        assertEquals(listOf("victoria"), asked)
        assertEquals(good, checked.statuses["victoria"])
    }

    // Codex on #783: a request another surface had in flight when the rider pulled is from before the
    // pull, though its answer lands after it and during this check: the pull still asks again.
    @Test
    fun `a request already in flight when the rider pulled doesn't stand for the pull`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        val gate = CompletableDeferred<Unit>()
        val inFlight = async { cache.check(listOf("victoria"), { now }, Duration.ofSeconds(90)) { gate.await(); listOf(severe) } }
        testScheduler.runCurrent()
        val pulled = cache.ticket()
        var asked: List<String>? = null
        val pull = async {
            cache.check(listOf("victoria"), { now }, Duration.ofSeconds(90), refresh = true, askedAfter = pulled) { ids ->
                asked = ids
                listOf(good)
            }
        }
        testScheduler.runCurrent()
        gate.complete(Unit)
        inFlight.await()
        assertEquals(good, pull.await().statuses["victoria"])
        assertEquals(listOf("victoria"), asked)
    }

    // Codex on #776: a pull that fails shows the line unchecked, not the answer it was meant to replace.
    @Test
    fun `a pull that fails leaves its lines unchecked, however recent their last answer`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        cache["victoria"] = LineStatusCache.Held.Verdict(now.minusSeconds(5), severe)
        val checked = cache.check(listOf("victoria"), { now }, Duration.ofSeconds(90), refresh = true) {
            throw TflException.Offline(null)
        }
        assertEquals(emptyMap<String, LineStatusCache.Held>(), checked.answers)
        assertEquals(setOf("victoria"), checked.failed)
    }

    @Test
    fun `a check with no window still takes an answer that landed while it waited`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        val gate = CompletableDeferred<Unit>()
        val first = launch {
            cache.asking(setOf("victoria"), cache.mark()) {
                gate.await()
                cache["victoria"] = LineStatusCache.Held.Verdict(now, severe)
            }
        }
        testScheduler.runCurrent()
        var asked = false
        val second = async { cache.check(listOf("victoria"), { now }, java.time.Duration.ZERO) { asked = true; emptyList() } }
        testScheduler.runCurrent()
        gate.complete(Unit)
        first.join()
        assertEquals(mapOf("victoria" to severe), second.await().statuses)
        assertFalse(asked)
    }

    // Codex on #776: a request another surface has out is waited on even with an answer standing, so the
    // answer it's replacing isn't shown meanwhile.
    @Test
    fun `a check waits for another surface's request even when an answer stands`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        cache["victoria"] = LineStatusCache.Held.Verdict(now.minusSeconds(10), good)
        val gate = CompletableDeferred<Unit>()
        val first = launch {
            cache.asking(setOf("victoria"), cache.mark()) {
                gate.await()
                cache["victoria"] = LineStatusCache.Held.Verdict(now, severe)
            }
        }
        testScheduler.runCurrent()
        var asked = false
        val second = async { cache.check(listOf("victoria"), { now }, Duration.ofSeconds(90)) { asked = true; emptyList() } }
        testScheduler.runCurrent()
        assertFalse(second.isCompleted)
        gate.complete(Unit)
        first.join()
        assertEquals(mapOf("victoria" to severe), second.await().statuses)
        assertFalse(asked)
    }

    @Test
    fun `a line whose request failed is left unchecked once its last answer is out of the window`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        cache["victoria"] = LineStatusCache.Held.Verdict(now.minusSeconds(300), severe)
        val checked = cache.check(listOf("victoria"), { now }, Duration.ofSeconds(90)) { throw TflException.Offline(null) }
        assertEquals(emptyMap<String, LineStatusCache.Held>(), checked.answers)
        assertEquals(setOf("victoria"), checked.failed)
        assertEquals(emptySet<String>(), checked.fresh)
    }

    // Codex on #776: a line held as unknown for good mustn't make every status shown with it read as old.
    @Test
    fun `the statuses' age is their own, not an omission's beside them`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(10_000)
        cache["eurostar"] = LineStatusCache.Held.Omitted(now.minusSeconds(3_600), unrecognized = true)
        cache["victoria"] = LineStatusCache.Held.Verdict(now.minusSeconds(10), severe)
        val checked = cache.check(listOf("eurostar", "victoria"), { now }, Duration.ofSeconds(90)) { emptyList() }
        assertEquals(now.minusSeconds(10), checked.oldest)
        val none = cache.check(listOf("eurostar"), { now }, Duration.ofSeconds(90)) { emptyList() }
        assertEquals(now.minusSeconds(3_600), none.oldest)
    }

    @Test
    fun `a line TfL doesn't know isn't asked about again`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        cache.check(listOf("eurostar"), { now }, Duration.ofSeconds(90)) { throw TflException.NotFound(null) }
        var asked = false
        val later = cache.check(listOf("eurostar"), { now.plusSeconds(3_600) }, Duration.ofSeconds(90)) { asked = true; emptyList() }
        assertFalse(asked)
        assertEquals(LineStatusCache.Held.Omitted(now, unrecognized = true), later.answers["eurostar"])
    }

    @Test
    fun `a line TfL doesn't know isn't asked about again on a pull either`() = runTest {
        val cache = LineStatusCache()
        val now = Instant.EPOCH.plusSeconds(1_000)
        cache.check(listOf("eurostar"), { now }, Duration.ofSeconds(90)) { throw TflException.NotFound(null) }
        val asked = mutableListOf<List<String>>()
        val later = now.plusSeconds(600)
        cache.check(listOf("eurostar", "victoria"), { later }, Duration.ofSeconds(90), refresh = true) { asked += it; listOf(severe) }
        cache.check(listOf("eurostar"), { later }, Duration.ofSeconds(90), askedAfter = cache.ticket()) { asked += it; emptyList() }
        assertEquals(listOf(listOf("victoria")), asked)
    }

    // Stamped when asked, before the request goes out: an answer landing late never passes for newer than it is.
    @Test
    fun `an answer is stamped when it was asked for, not when it landed`() = runTest {
        val cache = LineStatusCache()
        var now = Instant.EPOCH.plusSeconds(1_000)
        val asked = now
        cache.check(listOf("victoria"), { now }, Duration.ofSeconds(90)) { ids ->
            now = now.plusSeconds(5)
            ids.map { severe }
        }
        assertEquals(asked, cache["victoria"]!!.at)
    }
}
