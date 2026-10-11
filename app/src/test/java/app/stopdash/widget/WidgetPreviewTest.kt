package app.stopdash.widget

import app.stopdash.ThreadRecorder
import app.stopdash.domain.RouteTopology
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The example the launcher's widget picker shows ([WidgetPreview]): sample departures, drawn as live. */
class WidgetPreviewTest {
    private fun models(worker: CoroutineDispatcher? = null) = runBlocking {
        if (worker == null) WidgetPreview.models(RouteTopology.EMPTY) else WidgetPreview.models(RouteTopology.EMPTY, worker)
    }

    @Test
    fun `every picker size shows fresh departures with nothing to warn about`() {
        val models = models()
        assertEquals(WidgetPreview.SIZES, models.bySize.keys)
        for ((size, model) in models.bySize) {
            assertTrue("$size has data", model.hasData)
            assertFalse("$size stale", model.stale)
            assertFalse("$size uncertain", model.uncertain)
            assertFalse("$size disruptions unknown", model.statusUnknown)
            assertFalse("$size too small", model.tooSmall)
            assertEquals(null, model.tap)
            assertTrue("$size shows a departure", model.rows.isNotEmpty())
        }
    }

    @Test
    fun `the full example names its station above its rows, and the compact one doesn't`() {
        for (size in WidgetPreview.SIZES.filter { it.height.value > 110f }) {
            val full = models().bySize.getValue(size)
            assertEquals("$size", listOf("King's Cross St. Pancras"), full.rows.mapNotNull { it.header?.text })
            assertEquals("$size: the header leads", "King's Cross St. Pancras", full.rows.first().header?.text)
            assertEquals("$size: one station's rows", setOf("940GZZLUKSX"), full.rows.mapTo(HashSet()) { it.row.stopId })
        }
    }

    @Test
    fun `the compact widget's example shows departures and no stop headers`() {
        val models = runBlocking { WidgetPreview.models(RouteTopology.EMPTY, bare = true) }
        for ((size, model) in models.bySize) {
            assertTrue("$size shows a departure", model.rows.isNotEmpty())
            assertTrue("$size has no headers", model.rows.all { it.header == null })
            assertEquals(null, model.tap)
            assertFalse("$size warns", model.stale || model.uncertain || model.statusUnknown)
        }
    }

    @Test
    fun `the example is worked out on the worker`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val ranOn = ThreadRecorder()
            val base = pool.asCoroutineDispatcher()
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) =
                    base.dispatch(context) { ranOn.note(); block.run() }
            }
            models(worker)
            assertEquals(setOf("test-worker"), ranOn.threads().toSet())
        } finally {
            pool.shutdown()
        }
    }
}
