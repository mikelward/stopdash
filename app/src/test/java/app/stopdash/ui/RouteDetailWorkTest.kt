package app.stopdash.ui

import app.stopdash.ThreadRecorder
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopQualifier
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
 * The route page's trains and termini are worked out on a worker before it reads them (AGENTS.md
 * *Main thread*), never in composition. Synthetic stops only.
 */
class RouteDetailWorkTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(destination: String, offsetSeconds: Long) = Departure(
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        destination = destination,
        platform = null,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = "tube",
    )

    private val row = DepartureRow(
        stopId = "940GA",
        stopName = "Example Stop",
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        directionKey = "outbound",
        destination = "Brixton",
        mode = "tube",
        upcoming = listOf(departure("Brixton", 120), departure("Stockwell", 240), departure("Brixton", 360)),
        fetchedAt = now,
    )

    @Test
    fun `the route's trains and termini are worked out on the worker`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val base = pool.asCoroutineDispatcher()
            val ranOn = ThreadRecorder()
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) =
                    base.dispatch(context) { ranOn.note(); block.run() }
            }
            val work = runBlocking { routeDetailWork(row, focus = null, RouteTopology.EMPTY, worker) }
            assertEquals(setOf("test-worker"), ranOn.threads().toSet())
            // With no route tapped, the page names every terminus the row runs to.
            assertEquals(listOf("Brixton", "Stockwell"), work.destinations)
            // A tapped route lists only its own trains, and names only its terminus.
            val tapped = runBlocking { routeDetailWork(row, RouteFocus("Brixton", null), RouteTopology.EMPTY, worker) }
            assertEquals(listOf(120L, 360L), tapped.departures.map { it.expectedArrival.epochSecond - now.epochSecond })
            assertEquals(listOf("Brixton"), tapped.destinations)
            // Whether any train is grayed is found there too, so the page only walks them to gray one.
            assertEquals(false, work.grayed)
            val withDoubtful = row.copy(upcoming = row.upcoming.mapIndexed { i, d -> if (i == 2) d.copy(doubtful = true) else d })
            assertEquals(true, runBlocking { routeDetailWork(withDoubtful, focus = null, RouteTopology.EMPTY, worker) }.grayed)
            // Where it's boarded is found there too: the followed train's platform, or a bus's pole.
            assertEquals(null, work.boarding)
            val platformed = row.copy(upcoming = row.upcoming.map { it.copy(platform = "Southbound - Platform 2") })
            assertEquals(
                StopQualifier.Platform("2", null),
                runBlocking { routeDetailWork(platformed, focus = null, RouteTopology.EMPTY, worker) }.boarding,
            )
            val bus = platformed.copy(mode = "bus", stopLetter = "K", upcoming = platformed.upcoming.map { it.copy(mode = "bus") })
            assertEquals(StopQualifier.BusStop("K", null), runBlocking { routeDetailWork(bus, focus = null, RouteTopology.EMPTY, worker) }.boarding)
            // Either of a close pair is grayed too.
            val withPaired = row.copy(upcoming = row.upcoming.mapIndexed { i, d -> if (i == 2) d.copy(paired = true) else d })
            assertEquals(true, runBlocking { routeDetailWork(withPaired, focus = null, RouteTopology.EMPTY, worker) }.grayed)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a status row has no termini`() {
        val status = row.copy(upcoming = emptyList())
        val work = runBlocking { routeDetailWork(status, focus = null, RouteTopology.EMPTY, kotlinx.coroutines.Dispatchers.Unconfined) }
        assertEquals(RouteDetailWork.NONE, work)
    }
}
