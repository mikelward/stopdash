package app.stopdash.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The startup warm-up of the bundled stops (AGENTS.md *Main thread*): it returns at once and reads elsewhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BusStopIndexStoreWarmTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the warm-up hands the read to its worker and leaves the stops ready`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "warm-worker") }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        try {
            var ranOn: String? = null
            val recording = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    worker.dispatch(context, Runnable { ranOn = Thread.currentThread().name; block.run() })
                }
            }
            val caller = Thread.currentThread().name
            val job = BusStopIndexStore.warm(app, scope, io = recording, compute = worker)
            job.join()
            assertTrue("ran on $ranOn", ranOn.orEmpty().startsWith("warm-worker"))
            assertTrue(ranOn != caller)
            // Ready now: the near-me list and the search find the stops already read.
            assertTrue(BundledStopsStore.load(app, io = recording).size > 15_000)
            assertTrue(BusStopIndexStore.load(app, io = recording, compute = worker).size > 15_000)
        } finally {
            scope.cancel()
            worker.close()
        }
    }
}
