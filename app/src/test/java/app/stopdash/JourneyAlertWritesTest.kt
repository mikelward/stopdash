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

/** A journey alert schedule change that fails to save says so, wherever the rider is by then. */
class JourneyAlertWritesTest {
    private val scope = TestScope(StandardTestDispatcher())

    @Before
    fun reset() {
        JourneyAlertWrites.dismiss()
    }

    @Test
    fun `a failure ending after the screen has gone is still there for the next one`() {
        JourneyAlertWrites.attempt(scope) { throw IOException("disk") }
        // The overlay that asked has gone by now; the result lands in the process-wide state.
        scope.advanceUntilIdle()
        assertTrue(JourneyAlertWrites.failed.value)
        JourneyAlertWrites.dismiss()
        assertFalse(JourneyAlertWrites.failed.value)
    }

    @Test
    fun `a change that saves says nothing`() {
        JourneyAlertWrites.attempt(scope) {}
        scope.advanceUntilIdle()
        assertFalse(JourneyAlertWrites.failed.value)
    }

    @Test
    fun `a retry that saves takes the failure down`() {
        JourneyAlertWrites.attempt(scope) { throw IOException("disk") }
        scope.advanceUntilIdle()
        assertTrue(JourneyAlertWrites.failed.value)
        JourneyAlertWrites.attempt(scope) {}
        scope.advanceUntilIdle()
        assertFalse(JourneyAlertWrites.failed.value)
    }

    @Test
    fun `an earlier change failing after a later one saved says nothing`() {
        val gate = CompletableDeferred<Unit>()
        JourneyAlertWrites.attempt(scope) { gate.await(); throw IOException("disk") }
        JourneyAlertWrites.attempt(scope) {}
        scope.advanceUntilIdle()
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertFalse(JourneyAlertWrites.failed.value)
    }

    @Test
    fun `a failure is said away from the screens only for the latest change`() {
        var said = 0
        val gate = CompletableDeferred<Unit>()
        JourneyAlertWrites.attempt(scope, failedAway = { said++ }) { gate.await(); throw IOException("disk") }
        JourneyAlertWrites.attempt(scope, failedAway = { said++ }) { throw IOException("disk") }
        scope.advanceUntilIdle()
        assertEquals(1, said)
        // The older one failing after doesn't say it again.
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(1, said)
        JourneyAlertWrites.attempt(scope, failedAway = { said++ }) {}
        scope.advanceUntilIdle()
        assertEquals(1, said)
    }
}
