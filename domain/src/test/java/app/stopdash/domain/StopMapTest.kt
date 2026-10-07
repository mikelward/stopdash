package app.stopdash.domain

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.Executors
import org.junit.Test

class StopMapTest {
    @Test
    fun `a stop opens as a labeled pin at its own coordinate`() {
        assertEquals(
            "geo:0,0?q=51.5,-0.12(Manor%20House)",
            StopMap.geoUri(51.5, -0.12, "Manor House"),
        )
    }

    @Test
    fun `punctuation in the name is encoded so it cannot end the query`() {
        assertEquals(
            "geo:0,0?q=51.5,-0.12(King%27s%20Cross%20%28D%29%20%26%20more)",
            StopMap.geoUri(51.5, -0.12, "King's Cross (D) & more"),
        )
    }

    @Test
    fun `a blank name drops the label rather than an empty one`() {
        assertEquals("geo:0,0?q=51.5,-0.12", StopMap.geoUri(51.5, -0.12, "  "))
    }

    @Test
    fun `the link is built off the thread that taps`() = runBlocking {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            var ranOn: String? = null
            val probe = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    worker.dispatch(context) { ranOn = Thread.currentThread().name; block.run() }
                }
            }
            val uri = withContext(caller) { StopMap.geoUriOn(probe, 51.5, -0.12, "Euston") }
            assertEquals("geo:0,0?q=51.5,-0.12(Euston)", uri)
            assertTrue(ranOn!!.startsWith("worker"))
        } finally {
            caller.close()
            worker.close()
        }
    }
}
