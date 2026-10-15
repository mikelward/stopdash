package app.stopdash

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A ride's line hides and Shows: they land in tap order, and a failure stands until a later write for
 * the same ride and line saves.
 */
class RideLineWritesTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val victoriaLine = "ride-a|line:victoria"
    private val northernLine = "ride-a|line:northern"

    @Before
    fun reset() {
        RideLineWrites.shown()
    }

    @Test
    fun `a failed hide says so, logged without the ride or the line, and a Show that lands clears it`() {
        val warnings = mutableListOf<String>()
        RideLineWrites.attempt(scope, victoriaLine, hidden = true, warn = { warnings += it }) { throw IOException("disk") }
        scope.advanceUntilIdle()
        assertTrue(RideLineWrites.failed.value)
        assertEquals(listOf("ride line hide not saved: IOException"), warnings)
        RideLineWrites.attempt(scope, victoriaLine, hidden = false) {}
        scope.advanceUntilIdle()
        assertFalse(RideLineWrites.failed.value)
    }

    @Test
    fun `an earlier write failing after a later one for the same line landed doesn't bring the error back`() {
        val first = CompletableDeferred<Unit>()
        RideLineWrites.attempt(scope, victoriaLine, hidden = true) {
            first.await()
            throw IOException("disk")
        }
        RideLineWrites.attempt(scope, victoriaLine, hidden = false) {}
        first.complete(Unit)
        scope.advanceUntilIdle()
        assertFalse(RideLineWrites.failed.value)
    }

    @Test
    fun `a hide on another line saving doesn't hide one that failed`() {
        RideLineWrites.attempt(scope, victoriaLine, hidden = true) { throw IOException("disk") }
        RideLineWrites.attempt(scope, northernLine, hidden = true) {}
        scope.advanceUntilIdle()
        assertTrue(RideLineWrites.failed.value)
        RideLineWrites.attempt(scope, "ride-b|line:victoria", hidden = true) {}
        scope.advanceUntilIdle()
        assertTrue(RideLineWrites.failed.value)
    }

    @Test
    fun `once the screen has said so, the failure is cleared`() {
        RideLineWrites.attempt(scope, victoriaLine, hidden = true) { throw IOException("disk") }
        scope.advanceUntilIdle()
        RideLineWrites.shown()
        assertFalse(RideLineWrites.failed.value)
        RideLineWrites.attempt(scope, northernLine, hidden = true) {}
        scope.advanceUntilIdle()
        assertFalse(RideLineWrites.failed.value)
    }

    @Test
    fun `an Undo tapped while its hide is still saving waits for it, so the writes land in tap order`() {
        val hideSaving = CompletableDeferred<Unit>()
        val landed = mutableListOf<String>()
        RideLineWrites.attempt(scope, victoriaLine, hidden = true) {
            hideSaving.await()
            landed += "hide"
        }
        RideLineWrites.attempt(scope, victoriaLine, hidden = false) { landed += "show" }
        scope.advanceUntilIdle()
        assertEquals(emptyList<String>(), landed)
        hideSaving.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(listOf("hide", "show"), landed)
    }

    @Test
    fun `a write after one that failed still runs`() {
        var shown = false
        RideLineWrites.attempt(scope, victoriaLine, hidden = true) { throw IOException("disk") }
        RideLineWrites.attempt(scope, victoriaLine, hidden = false) { shown = true }
        scope.advanceUntilIdle()
        assertTrue(shown)
        assertFalse(RideLineWrites.failed.value)
    }

    @Test
    fun `a write that lands says nothing`() {
        var wrote = false
        RideLineWrites.attempt(scope, victoriaLine, hidden = true) { wrote = true }
        scope.advanceUntilIdle()
        assertTrue(wrote)
        assertFalse(RideLineWrites.failed.value)
    }
}
