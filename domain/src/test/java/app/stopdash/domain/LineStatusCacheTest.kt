package app.stopdash.domain

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
}
