package app.stopdash.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The in-flight cap on TfL requests: never more than [TflRequestPool.maxConcurrent] at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class TflRequestPoolTest {
    @Test
    fun `runs up to the cap at once and queues the rest`() = runTest {
        val pool = TflRequestPool(2)
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var started = 0
        var peak = 0
        repeat(5) {
            launch {
                pool.run {
                    started++
                    inFlight++
                    peak = maxOf(peak, inFlight)
                    gate.await()
                    inFlight--
                }
            }
        }
        runCurrent()
        // Both directions: the cap admits exactly two, not one (so it does parallelize) and not more.
        assertEquals(2, started)
        gate.complete(Unit)
        runCurrent()
        assertEquals(5, started)
        assertEquals(2, peak)
    }

    @Test
    fun `a slot is released when its block throws`() = runTest {
        val pool = TflRequestPool(1)
        runCatching { pool.run { throw IllegalStateException("boom") } }
        assertEquals("ok", pool.run { "ok" })
    }

    @Test
    fun `a request kept waiting for a slot says how long, one let straight in says nothing`() = runTest {
        val waits = mutableListOf<Long>()
        val pool = TflRequestPool(1, onSlowWait = { waits += it }, elapsedMillis = { testScheduler.currentTime })
        val first = launch { pool.run { kotlinx.coroutines.delay(10_000) } }
        runCurrent()
        // Waits behind the first for its whole 10 s.
        pool.run { }
        first.join()
        // A free slot: no wait to tell.
        pool.run { }
        assertEquals(listOf(10_000L), waits)
    }

    @Test
    fun `a short wait for a slot is not told`() = runTest {
        val waits = mutableListOf<Long>()
        val pool = TflRequestPool(1, onSlowWait = { waits += it }, elapsedMillis = { testScheduler.currentTime })
        launch { pool.run { kotlinx.coroutines.delay(TflRequestPool.SLOW_WAIT_MILLIS - 1) } }
        runCurrent()
        pool.run { }
        assertEquals(emptyList<Long>(), waits)
    }
}
