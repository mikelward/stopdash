package app.stopdash

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Settings' journey removals: only the latest attempt says whether a removal failed. */
class JourneyRemovalsTest {
    private val scope = TestScope(StandardTestDispatcher())

    @Before
    fun reset() {
        JourneyRemovals.failed.value = false
    }

    @Test
    fun `a failed removal says so, and a retry that lands clears it`() {
        JourneyRemovals.attempt(scope) { throw IOException("disk") }
        scope.advanceUntilIdle()
        assertTrue(JourneyRemovals.failed.value)
        JourneyRemovals.attempt(scope) {}
        scope.advanceUntilIdle()
        assertFalse(JourneyRemovals.failed.value)
    }

    @Test
    fun `an earlier attempt failing after a later one landed doesn't bring the error back`() {
        val first = CompletableDeferred<Unit>()
        JourneyRemovals.attempt(scope) {
            first.await()
            throw IOException("disk")
        }
        scope.advanceUntilIdle()
        JourneyRemovals.attempt(scope) {}
        scope.advanceUntilIdle()
        first.complete(Unit)
        scope.advanceUntilIdle()
        assertFalse(JourneyRemovals.failed.value)
    }
}
