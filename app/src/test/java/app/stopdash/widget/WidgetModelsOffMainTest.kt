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
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The widget's models are worked out on a worker before it composes (AGENTS.md *Main thread*), one
 * per size the launcher reports, and composition only looks one up. Synthetic stops only.
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

    private val portrait = DpSize(380.dp, 400.dp)
    private val landscape = DpSize(700.dp, 250.dp)

    private fun recordingWorker(base: CoroutineDispatcher, ranOn: MutableSet<String>) = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            base.dispatch(context) { ranOn += Thread.currentThread().name; block.run() }
    }

    @Test
    fun `every reported size's model is worked out on the worker, as composing one would`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val base = pool.asCoroutineDispatcher()
            val ranOn = mutableSetOf<String>()
            val worker = recordingWorker(base, ranOn)
            val models = runBlocking {
                widgetModels(snapshot, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet(), listOf(portrait, landscape), worker = worker)
            }
            assertEquals(setOf("test-worker"), ranOn)
            assertEquals(setOf(portrait, landscape), models.bySize.keys)
            val min = WidgetGeometry(WIDGET_MIN_SIZE.width, WIDGET_MIN_SIZE.height, 1f)
            assertEquals(widgetModel(snapshot, now, geometry = min), models.fallback)
            for ((size, model) in models.bySize) {
                val geometry = WidgetGeometry(size.width, size.height, 1f)
                assertEquals(widgetModel(snapshot, now, geometry = geometry), model)
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a size reported later is worked out on the worker, and a known one isn't again`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val ranOn = mutableSetOf<String>()
            val worker = recordingWorker(pool.asCoroutineDispatcher(), ranOn)
            val models = runBlocking {
                widgetModels(snapshot, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet(), listOf(portrait), worker = worker)
            }
            ranOn.clear()
            assertSame(models, runBlocking { models.including(portrait) })
            assertEquals(emptySet<String>(), ranOn)
            val resized = runBlocking { models.including(landscape) }
            assertEquals(setOf("test-worker"), ranOn)
            val geometry = WidgetGeometry(landscape.width, landscape.height, 1f)
            assertEquals(widgetModel(snapshot, now, geometry = geometry), resized[landscape])
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a reported size reads its own model, and an unknown one the minimum size's`() {
        val models = runBlocking {
            widgetModels(snapshot, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet(), listOf(portrait, landscape))
        }
        for ((size, model) in models.bySize) assertEquals(model, models[size])
        assertEquals(models.fallback, models[DpSize(400.dp, 500.dp)])
    }

    // Fresh rows lead and fill a small widget, so a stale row's guess shows only on a taller one: the
    // redraw that drops it is due by then even when no reported size draws it yet (a later resize).
    @Test
    fun `a stale guess only a taller size draws still sets the redraw`() {
        val old = now.minusSeconds(3600)
        fun dep(line: String, destination: String, offsetSeconds: Long) =
            Departure(line, line, "inbound", destination, null, now.plusSeconds(offsetSeconds), "tube")
        val mixed = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    "940GA",
                    "Example Stop",
                    listOf(dep("victoria", "Brixton", 300), dep("district", "Richmond", 360), dep("northern", "Morden", 420)),
                    now,
                    disruptions = emptyList(),
                ),
                StopArrivals("940GB", "Other Stop", listOf(dep("central", "Epping", 600)), old, disruptions = emptyList()),
            ),
            fetchedAt = now,
        )
        val models = runBlocking {
            widgetModels(mixed, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet(), emptyList())
        }
        assertEquals(null, models.fallback.guessExpiresAt)
        assertEquals(now.plusSeconds(600), models.guessExpiresAt)
        assertEquals(models.guessExpiresAt, runBlocking { models.including(DpSize(380.dp, 900.dp)) }.guessExpiresAt)
    }

    // Glance only recomposes a session it kept open, so a redraw in that window drew the old models
    // and a tap to refresh did nothing visible (maintainer bug report, 2026-10-05).
    @Test
    fun `a redraw an open session sees draws again on the worker, and only for a new generation`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val ranOn = mutableSetOf<String>()
            val worker = recordingWorker(pool.asCoroutineDispatcher(), ranOn)
            val first = DeparturesDrawing(
                runBlocking { widgetModels(snapshot, now, emptySet(), 1f, RouteTopology.EMPTY, emptySet(), listOf(portrait)) },
                now,
                1f,
                generation = 3,
            )
            val drawnFor = mutableListOf<Long>()
            val later = now.plusSeconds(60)
            val draw: suspend (Long) -> DeparturesDrawing = { generation ->
                drawnFor += generation
                first.copy(now = later, generation = generation)
            }
            // The caller's single thread never does the work.
            val same = runBlocking { redrawn(first, 3, portrait, worker, draw) }
            assertSame(first, same)
            assertEquals(emptyList<Long>(), drawnFor)
            ranOn.clear()
            val again = runBlocking { redrawn(first, 4, portrait, worker, draw) }
            assertEquals(listOf(4L), drawnFor)
            assertEquals(4L, again.generation)
            assertEquals(later, (again as DeparturesDrawing).now)
            assertEquals(setOf("test-worker"), ranOn)
        } finally {
            pool.shutdown()
        }
    }
}
