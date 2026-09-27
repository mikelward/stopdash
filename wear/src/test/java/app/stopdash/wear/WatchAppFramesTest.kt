package app.stopdash.wear

import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchStarKey
import app.stopdash.data.toPersisted
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch app's list and its foreground ticker, over an injected clock: synthetic stops only. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchAppFramesTest {
    private val fetched: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private fun departure(seconds: Long, line: String = "victoria", destination: String = "Brixton") = Departure(
        lineId = line,
        lineName = line.replaceFirstChar { it.uppercase() },
        direction = "inbound",
        destination = destination,
        platform = null,
        expectedArrival = fetched.plusSeconds(seconds),
        mode = "tube",
    )

    private fun stop(id: String, departures: List<Departure>, line: String = "victoria") = StopArrivals(
        stopId = id,
        stopName = "Stop $id",
        departures = departures,
        fetchedAt = fetched,
        lines = listOf(LineRef(line, line.replaceFirstChar { it.uppercase() }, "tube")),
    )

    private fun received(vararg stops: StopArrivals, starred: Set<StarredRow> = emptySet()) = WatchReceived.Received(
        WatchEnvelope(stops = stops.map { it.toPersisted() }, starred = starred.map(WatchStarKey::of)),
        fetched,
    )

    private fun rows(frame: TileFrame?) = (frame as TileFrame.Rows).lines.filterIsInstance<TileLine.Departure>().map { it.row }

    @Test
    fun `before the stored envelope is read there's no frame, then the setup states`() {
        assertNull(WatchAppFrames.at(WatchReceived.Loading, fetched))
        assertEquals(TileFrame.NeverSynced, WatchAppFrames.at(WatchReceived.NeverSynced, fetched))
        assertEquals(TileFrame.NoStops, WatchAppFrames.at(WatchReceived.Received(WatchEnvelope(), fetched), fetched))
    }

    @Test
    fun `the app lists every row, where the tile stops at what fits`() {
        val stops = (1..8).map { stop("940GS$it", listOf(departure(60L * it + 30))) }.toTypedArray()
        val app = rows(WatchAppFrames.at(received(*stops), fetched))
        assertEquals(8, app.size)
        val tile = rows(TileTimeline.frame((received(*stops)).envelope, fetched))
        assertTrue(tile.size < app.size)
    }

    @Test
    fun `favorites come first, as on the tile`() {
        val star = StarredRow("940GB", "central", "inbound")
        val frame = WatchAppFrames.at(
            received(stop("940GA", listOf(departure(60))), stop("940GB", listOf(departure(600, "central", "Ealing")), "central"), starred = setOf(star)),
            fetched,
        )
        assertEquals(listOf("central", "victoria"), rows(frame).map { it.lineId })
        assertTrue(rows(frame).first().starred)
    }

    @Test
    fun `the ticker advances past a departure and turns stale at the boundary, with no polling`() = runTest {
        val frames = mutableListOf<TileFrame?>()
        val clock = { fetched.plusMillis(testScheduler.currentTime) }
        val job = launch { WatchAppFrames.tick(received(stop("940GA", listOf(departure(90), departure(200)))), RouteTopology.EMPTY, clock) { frames += it } }
        runCurrent()
        assertEquals("1 · 3 min", rows(frames.last()).single().countdown)

        // Past the first departure, mid-minute: it drops off at its exact time.
        advanceTimeBy(90_001)
        assertEquals("1 min", rows(frames.last()).single().countdown)

        // Past the stop's staleness boundary: every countdown withheld, and the ticker stops.
        advanceTimeBy(5 * 60_000L)
        val last = frames.last() as TileFrame.Rows
        assertTrue(last.stale)
        assertTrue(rows(last).all { it.countdown == "?" })
        assertTrue("nothing changes once stale, so the ticker ends", job.isCompleted)
        // It re-rendered only at instants where something could change: a bounded count, not a poll.
        assertTrue(frames.size < 20)
    }

    @Test
    fun `a check from the future stays unshown for the whole ticker run`() = runTest {
        val frames = mutableListOf<TileFrame?>()
        val clock = { fetched.plusMillis(testScheduler.currentTime) }
        val check = LineStatusCheck(LineStatus("victoria", 6, "Severe Delays"), fetched.plusSeconds(60))
        val stored = received(stop("940GA", listOf(departure(90), departure(200))))
            .let { it.copy(envelope = it.envelope.copy(lineStatuses = listOf(check.toPersisted()))) }
        val job = launch { WatchAppFrames.tick(stored, RouteTopology.EMPTY, clock) { frames += it } }
        runCurrent()
        // Past the check's instant: still not shown, as the tile and complication leave it.
        advanceTimeBy(120_000)
        assertTrue(frames.none { (it as? TileFrame.Rows)?.lines.orEmpty().any { l -> l is TileLine.Disruption } })
        job.cancel()
    }
}
