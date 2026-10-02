package app.stopdash.domain

import java.time.Duration
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AskedAgainTest {
    private val every = Duration.ofSeconds(12)

    @Test
    fun `a source that ends at once is asked again after the wait, and what it then gives comes through`() = runTest {
        var asks = 0
        // No provider at first (ends at once), then one comes back.
        val source = { if (asks++ == 0) emptyFlow() else flowOf("fix") }
        val seen = mutableListOf<String>()
        val job = launch { askedAgainWhenEnded(every, source).collect { seen += it } }

        runCurrent()
        assertEquals(1, asks)
        assertEquals(emptyList<String>(), seen)

        advanceTimeBy(every.toMillis() - 1)
        runCurrent()
        assertEquals(1, asks)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, asks)
        assertEquals(listOf("fix"), seen)
        job.cancel()
    }

    @Test
    fun `nothing is asked for once the collector stops`() = runTest {
        var asks = 0
        val job = launch { askedAgainWhenEnded(every) { asks++; emptyFlow<String>() }.collect {} }
        runCurrent()
        job.cancel()
        advanceTimeBy(10 * every.toMillis())
        assertEquals(1, asks)
    }
}
