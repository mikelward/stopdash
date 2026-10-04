package app.stopdash.wear

import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchStarKey
import app.stopdash.data.toPersisted
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import java.time.Instant
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

/** The complication picker's rows, and when a pick is dropped: synthetic stops only. */
class ComplicationChoicesTest {
    private val fetched: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private fun stop(id: String, line: String, destination: String, mode: String = "tube") = StopArrivals(
        stopId = id,
        stopName = "Stop $id",
        departures = listOf(
            Departure(line, line.replaceFirstChar { it.uppercase() }, "inbound", destination, null, fetched.plusSeconds(120), mode),
        ),
        fetchedAt = fetched,
        lines = listOf(LineRef(line, line.replaceFirstChar { it.uppercase() }, mode)),
    )

    private val victoria = stop("940GA", "victoria", "Brixton")
    private val central = stop("940GB", "central", "Ealing")

    @Test
    fun `the picker's rows are worked out on the worker, not the caller's thread`() {
        val env = WatchEnvelope(stops = listOf(victoria, central).map { it.toPersisted() })
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val base = pool.asCoroutineDispatcher()
            var ranOn: String? = null
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) =
                    base.dispatch(context) { ranOn = Thread.currentThread().name; block.run() }
            }
            val choices = runBlocking { ComplicationChoices.load(env, fetched, worker = worker) }
            assertEquals("test-worker", ranOn)
            assertEquals(ComplicationChoices.of(env, fetched), choices)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `the picker lists the widget's rows, starred first, labeled like the tile`() {
        val star = StarredRow("940GB", "central", "inbound")
        val env = WatchEnvelope(stops = listOf(victoria, central).map { it.toPersisted() }, starred = listOf(WatchStarKey.of(star)))
        val choices = ComplicationChoices.of(env, fetched)
        assertEquals(listOf("central", "victoria"), choices.map { it.row.lineId })
        assertEquals(ComplicationChoice(star, "CEN", "Ealing", "Stop 940GB"), choices.first())
    }

    @Test
    fun `a fresh stop's rows come before a stale stop's, as on the tile`() {
        val stale = stop("940GS", "central", "Ealing").copy(fetchedAt = fetched.minusSeconds(600))
            .let { it.copy(departures = it.departures.map { d -> d.copy(expectedArrival = fetched.plusSeconds(30)) }) }
        val env = WatchEnvelope(stops = listOf(stale, victoria).map { it.toPersisted() })
        assertEquals(listOf("victoria", "central"), ComplicationChoices.of(env, fetched).map { it.row.lineId })
    }

    @Test
    fun `a suspension's status row isn't a pick`() {
        val suspended = StopArrivals("940GW", "Stop 940GW", emptyList(), fetched, lines = listOf(LineRef("waterloo-city", "Waterloo-city", "tube")))
        val check = LineStatusCheck(LineStatus("waterloo-city", 5, "Suspended"), fetched)
        val env = WatchEnvelope(stops = listOf(suspended, victoria).map { it.toPersisted() }, lineStatuses = listOf(check.toPersisted()))
        assertEquals(listOf("victoria"), ComplicationChoices.of(env, fetched).map { it.row.lineId })
    }

    @Test
    fun `hidden modes aren't offered, and no envelope offers nothing`() {
        val bus = stop("940GC", "73", "Oxford Circus", mode = "bus")
        val env = WatchEnvelope(stops = listOf(victoria, bus).map { it.toPersisted() }, hiddenModes = listOf("bus"))
        assertEquals(listOf("victoria"), ComplicationChoices.of(env, fetched).map { it.row.lineId })
        assertTrue(ComplicationChoices.of(null, fetched).isEmpty())
    }

    @Test
    fun `the current pick stays offered with no departures right now, first`() {
        val quiet = StopArrivals(
            stopId = "940GQ",
            stopName = "Stop 940GQ",
            departures = emptyList(),
            fetchedAt = fetched,
            lines = listOf(LineRef("central", "Central", "tube")),
        )
        val env = WatchEnvelope(stops = listOf(victoria, quiet).map { it.toPersisted() })
        val pick = StarredRow("940GQ", "central", "inbound")
        val choices = ComplicationChoices.of(env, fetched, current = pick)
        assertEquals(pick, choices.first().row)
        assertEquals("CEN", choices.first().code)
        assertEquals("Stop 940GQ", choices.first().stopName)
        assertEquals(2, choices.size)
        // A pick the widget wouldn't show isn't offered.
        assertEquals(1, ComplicationChoices.of(env, fetched, current = StarredRow("940GQ", "bakerloo", "inbound")).size)
    }

    @Test
    fun `a pick is dropped only when its stop left a complete envelope`() {
        val pick = StarredRow("940GGONE", "central", "inbound")
        val env = WatchEnvelope(stops = listOf(victoria.toPersisted()))
        assertTrue("left the widget's scope", ComplicationSelections.isGone(pick, env))
        assertFalse("still sent", ComplicationSelections.isGone(StarredRow("940GA", "victoria", "inbound"), env))
        assertFalse("left out for size: it comes back", ComplicationSelections.isGone(pick, env.copy(omittedStops = 1)))
        assertFalse("failed to load", ComplicationSelections.isGone(pick, env.copy(missingStopIds = listOf("940GGONE"))))
        assertFalse("nothing received yet", ComplicationSelections.isGone(pick, null))
    }
}
