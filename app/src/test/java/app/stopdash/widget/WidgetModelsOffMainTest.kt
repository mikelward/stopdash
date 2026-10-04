package app.stopdash.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopArrivals
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget's models are worked out on a worker before it composes (AGENTS.md *Main thread*), one
 * per size bucket, and composition only looks one up. Synthetic stops only.
 */
class WidgetModelsOffMainTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val snapshot = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                "940GA",
                "Example Stop",
                listOf(Departure("victoria", "Victoria", "inbound", "Brixton", null, now.plusSeconds(120), "tube")),
                now,
                disruptions = emptyList(),
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `every bucket's model is worked out on the worker, as composing one would`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val base = pool.asCoroutineDispatcher()
            val ranOn = mutableSetOf<String>()
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) =
                    base.dispatch(context) { ranOn += Thread.currentThread().name; block.run() }
            }
            val models = runBlocking {
                widgetModels(snapshot, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet(), worker = worker)
            }
            assertEquals(setOf("test-worker"), ranOn)
            assertEquals(WIDGET_BUCKETS, models.bySize.keys)
            for ((size, model) in models.bySize) {
                val geometry = WidgetGeometry(size.width, size.height, 1f)
                assertEquals(widgetModel(snapshot, now, geometry = geometry), model)
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a bucket reads its own model, and a size the host wasn't given the smallest bucket's`() {
        val models = runBlocking { widgetModels(snapshot, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet()) }
        val smallest = models.bySize.minBy { (size, _) -> size.width.value * size.height.value }
        assertEquals(smallest.value, models.fallback)
        for ((size, model) in models.bySize) assertEquals(model, models[size])
        assertEquals(smallest.value, models[DpSize(1.dp, 1.dp)])
    }
}
