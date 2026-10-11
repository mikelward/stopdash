package app.stopdash.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopdash.ThreadRecorder
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picker's example as providePreview builds it: the bundled topology read and the models worked
 * out off the caller's thread, each on its own dispatcher (AGENTS.md *Main thread*; Codex on #758).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB")
class WidgetPreviewReadTest {
    private fun recording(base: CoroutineDispatcher, ranOn: ThreadRecorder) = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            base.dispatch(context) { ranOn.note(); block.run() }
    }

    @Test
    fun `the example reads its topology on io and works out its models on the worker`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }
        val io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val ranOn = ThreadRecorder()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val models = runBlocking(caller.asCoroutineDispatcher()) {
                WidgetPreview.models(
                    context,
                    io = recording(io.asCoroutineDispatcher(), ranOn),
                    worker = recording(worker.asCoroutineDispatcher(), ranOn),
                )
            }
            assertEquals(setOf("test-io", "test-worker"), ranOn.threads().toSet())
            assertTrue(models.bySize.values.all { it.rows.isNotEmpty() })
        } finally {
            caller.shutdown()
            io.shutdown()
            worker.shutdown()
        }
    }
}
