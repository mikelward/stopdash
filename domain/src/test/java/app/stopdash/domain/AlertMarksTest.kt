package app.stopdash.domain

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a line's page marks for its alert, read off the caller's thread. Stock station names. */
class AlertMarksTest {
    private fun stops(vararg names: String) = names.mapIndexed { i, name -> RouteStop("S$i", name) }

    private val train = stops("Old Street", "Moorgate", "Bank", "Monument", "Tower Hill")
    private val alert = "Diverted between Moorgate and Monument due to roadworks."

    @Test
    fun `the train's list gives the named stations, the stretch between them, and its run`() = runTest {
        val marks = AlertMarks.of(alert, alert, hasStatus = true, train, emptyList(), train, RouteStop::name)
        assertEquals(listOf("Moorgate", "Monument"), marks.named.map { it.name })
        assertEquals(setOf("S1", "S2", "S3"), marks.stretch)
        assertEquals(listOf("Moorgate to Monument"), marks.places)
    }

    @Test
    fun `without the train's list, the line's stations it names are marked, and no stretch`() = runTest {
        val marks = AlertMarks.of(alert, alert, hasStatus = true, null, train, emptyList(), RouteStop::name)
        assertEquals(setOf("S1", "S3"), marks.stretch)
        assertEquals(listOf("Moorgate", "Monument"), marks.places)
    }

    @Test
    fun `an alert behind the stop flags no stop, and nothing marks nothing`() = runTest {
        val behind = AlertMarks.of(alert, null, hasStatus = false, train, emptyList(), train, RouteStop::name)
        assertEquals(emptySet<String>(), behind.stretch)
        assertEquals(listOf("Moorgate", "Monument"), behind.named.map { it.name })
        assertEquals(AlertMarks.NONE, AlertMarks.of(null, null, hasStatus = false, train, emptyList(), train, RouteStop::name))
    }

    @Test
    fun `the matching runs on the worker, not a single-thread caller`() {
        // AGENTS.md *Main-safe by default*: the page asks from the main thread, and matching builds a
        // pattern per station and per pair of them, which froze the page on a long bus route.
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        try {
            val namedOn = mutableListOf<String>()
            runBlocking(caller) {
                AlertMarks.of(alert, alert, hasStatus = true, train, emptyList(), train, { stop ->
                    namedOn += Thread.currentThread().name
                    stop.name
                }, worker = worker)
            }
            assertTrue(namedOn.isNotEmpty())
            // Debug coroutines append " @coroutine#n" to the name; the thread is what matters.
            assertEquals(setOf("test-worker"), namedOn.mapTo(HashSet()) { it.substringBefore(" @") })
        } finally {
            caller.close()
            worker.close()
        }
    }
}
