package app.stopdash.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapsLinkMemoTest {
    private val full = "https://maps.google.com/?q=51.5,-0.12"

    @Test
    fun `a reading started while the first is in flight shares its one request`() = runTest {
        var asked = 0
        val reply = CompletableDeferred<String?>()
        val memo = MapsLinkMemo({ asked++; reply.await() }, CoroutineScope(StandardTestDispatcher(testScheduler)), now = { 0L })
        // The first reader goes away mid-request, as a screen recreated mid-share does.
        val first = async { memo.follow("https://maps.app.goo.gl/X") }
        advanceUntilIdle()
        first.cancelAndJoin()
        val second = async { memo.follow("https://maps.app.goo.gl/X") }
        advanceUntilIdle()
        reply.complete(full)
        assertEquals(full, second.await())
        assertEquals(1, asked)
        // Read again later, it's the answer already had.
        assertEquals(full, memo.follow("https://maps.app.goo.gl/X"))
        assertEquals(1, asked)
    }

    @Test
    fun `a failure is read again by a recreated screen, and asked again a minute later`() = runTest {
        var asked = 0
        var clock = 0L
        val memo = MapsLinkMemo({ asked++; null }, CoroutineScope(StandardTestDispatcher(testScheduler)), now = { clock })
        assertNull(memo.follow("https://maps.app.goo.gl/X"))
        // Moments later, as a screen recreated just as the answer came back would read it.
        clock = 1_000L
        assertNull(memo.follow("https://maps.app.goo.gl/X"))
        assertEquals(1, asked)
        // A share of the same link once the wait is over tries again.
        clock = 1_000L + MapsLinkMemo.RETRY_AFTER_MILLIS
        assertNull(memo.follow("https://maps.app.goo.gl/X"))
        assertEquals(2, asked)
    }

    @Test
    fun `a failure no one waited for still counts from when it failed`() = runTest {
        var asked = 0
        var clock = 0L
        val reply = CompletableDeferred<String?>()
        val memo = MapsLinkMemo({ asked++; reply.await() }, CoroutineScope(StandardTestDispatcher(testScheduler)), now = { clock })
        // The only reader goes away; the request fails later with no one waiting.
        val reader = async { memo.follow("https://maps.app.goo.gl/X") }
        advanceUntilIdle()
        reader.cancelAndJoin()
        reply.complete(null)
        advanceUntilIdle()
        // Shared again long after, it is asked again rather than reusing the old failure.
        clock = MapsLinkMemo.RETRY_AFTER_MILLIS
        assertNull(memo.follow("https://maps.app.goo.gl/X"))
        assertEquals(2, asked)
    }

    @Test
    fun `a follow that throws is read as a link that couldn't be followed`() = runTest {
        val memo = MapsLinkMemo({ error("boom") }, CoroutineScope(StandardTestDispatcher(testScheduler)), now = { 0L })
        assertNull(memo.follow("https://maps.app.goo.gl/X"))
    }

    @Test
    fun `a request still in flight isn't dropped by later shares`() = runTest {
        val asked = mutableListOf<String>()
        val first = CompletableDeferred<String?>()
        val memo = MapsLinkMemo(
            { link -> asked += link; if (link.endsWith("/0")) first.await() else "https://maps.google.com/?q=51.5,-0.12" },
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            now = { 0L },
        )
        val waiting = async { memo.follow("https://maps.app.goo.gl/0") }
        advanceUntilIdle()
        // Nine more links, past what is kept, while the first is still being asked.
        for (n in 1..9) memo.follow("https://maps.app.goo.gl/$n")
        val again = async { memo.follow("https://maps.app.goo.gl/0") }
        advanceUntilIdle()
        first.complete(full)
        assertEquals(full, waiting.await())
        assertEquals(full, again.await())
        assertEquals(1, asked.count { it.endsWith("/0") })
    }

    @Test
    fun `a burst of shares in flight is trimmed back once they answer`() = runTest {
        val replies = (0..9).map { CompletableDeferred<String?>() }
        val memo = MapsLinkMemo(
            { link -> replies[link.substringAfterLast('/').toInt()].await() },
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            now = { 0L },
        )
        val readers = (0..9).map { n -> async { memo.follow("https://maps.app.goo.gl/$n") } }
        advanceUntilIdle()
        // All ten are still being asked, so none can go.
        assertEquals(10, memo.size)
        replies.forEach { it.complete(full) }
        readers.forEach { it.await() }
        advanceUntilIdle()
        assertEquals(8, memo.size)
    }
}
