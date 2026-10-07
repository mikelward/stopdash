package app.stopdash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class StopMapTapTest {
    @Test
    fun `a map tap builds the link off the tapping thread and opens it back on it`() = runBlocking {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            var encodedOn: String? = null
            val probe = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    worker.dispatch(context) { encodedOn = Thread.currentThread().name; block.run() }
                }
            }
            var opened: Pair<String, String>? = null
            CoroutineScope(caller).openStopMapOn(probe, 51.5, -0.12, "Euston") { uri ->
                opened = uri to Thread.currentThread().name
            }.join()
            assertTrue(encodedOn!!.startsWith("worker"))
            assertEquals("geo:0,0?q=51.5,-0.12(Euston)", opened!!.first)
            assertTrue(opened!!.second.startsWith("caller"))
        } finally {
            caller.close()
            worker.close()
        }
    }
}
