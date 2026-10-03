package app.stopdash.data

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trip's wire form: one this build reads, and the two it refuses. */
class WatchTripTest {
    private val trip = WatchTrip(
        title = "Walk to Victoria",
        steps = listOf(WatchTrip.Step("Walk to Victoria", walk = true)),
        current = 0,
        sentAt = 1L,
    )

    @Test
    fun `a trip round-trips`() {
        assertEquals(trip, runBlocking { WatchTrip.decode(WatchTrip.encode(trip)) { throw AssertionError(it) } })
    }

    @Test
    fun `a trip is parsed and serialized off the thread that hands it over`() = runBlocking {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            var parsedOn: String? = null
            val read = withContext(caller) {
                WatchTrip.decode("not a trip".encodeToByteArray(), worker) { parsedOn = Thread.currentThread().name }
            }
            assertNull(read)
            var encodedOn: String? = null
            val probe = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    worker.dispatch(context) { encodedOn = Thread.currentThread().name; block.run() }
                }
            }
            withContext(caller) { WatchTrip.encode(trip, probe) }
            assertTrue(encodedOn!!.startsWith("worker"))
            assertTrue(parsedOn!!.startsWith("worker"))
            assertEquals(trip, withContext(caller) { WatchTrip.decode(WatchTrip.encode(trip, worker), worker) { throw AssertionError(it) } })
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `another version is refused, and said`() {
        val logged = mutableListOf<String>()
        assertNull(runBlocking { WatchTrip.decode(WatchTrip.encode(trip.copy(version = 2)), log = logged::add) })
        assertEquals(listOf("trip version unsupported: 2"), logged)
    }

    @Test
    fun `bytes that aren't a trip are refused by type, never quoted`() {
        val logged = mutableListOf<String>()
        assertNull(runBlocking { WatchTrip.decode("{\"version\":1,\"title\":3}".encodeToByteArray(), log = logged::add) })
        assertEquals(1, logged.size)
        assertEquals(true, logged.single().startsWith("trip unreadable: "))
    }
}
