package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** One answer per line, shared across surfaces (SPEC *Freshness → Shared line status*). */
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
}
