package app.stopdash.watch

import app.stopdash.data.WatchDecode
import app.stopdash.data.WatchEnvelopes
import app.stopdash.data.WatchPayload
import app.stopdash.domain.AlertBehind
import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.Dismissals
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RoutePattern
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's watch publisher over a fake Data Layer: synthetic stops only, no user data. */
class WatchPublisherTest {
    private val now: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private class FakeChannel(var installed: Boolean = true, var failing: Boolean = false) : WatchChannel {
        val sent = mutableListOf<WatchPayload>()
        override suspend fun watchInstalled(): Boolean = installed
        override suspend fun put(payload: WatchPayload) {
            if (failing) throw IOException("data layer down")
            sent += payload
        }
    }

    private class FakeMarker : PublishMarker {
        var hash: String? = null
        override fun get(): String? = hash
        override fun set(hash: String) {
            this.hash = hash
        }
    }

    private val logged = mutableListOf<String>()

    private fun snapshot(minutes: Long = 3) = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                "940GEXAMPLE1",
                "Example",
                listOf(Departure("victoria", "Victoria", "inbound", "Brixton", null, now.plusSeconds(minutes * 60), "tube")),
                now,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `publishes the snapshot to a watch with the app, once per change`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet()))
        assertEquals(WatchPublisher.Outcome.Unchanged, publisher.publish(snapshot(), emptySet()))
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(minutes = 4), emptySet()))
        assertEquals(2, channel.sent.size)
        val envelope = (WatchEnvelopes.decode(channel.sent.last().bytes) as WatchDecode.Ok).envelope
        assertEquals(listOf("940GEXAMPLE1"), envelope.stops.map { it.stopId })
    }

    @Test
    fun `a star change is a change`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        val star = StarredRow("940GEXAMPLE1", "victoria", "inbound")
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), setOf(star)))
    }

    @Test
    fun `hiding a mode is a change`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet(), hiddenModes = setOf("bus")))
    }

    @Test
    fun `a change to the refreshed route lines is a change, and they reach the watch`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        val lines = mapOf("northern" to listOf(RoutePattern("Bank", listOf("940GA", "940GB"), "Edgware", "Morden")))
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet(), routeLines = lines))
        assertEquals(lines, (WatchEnvelopes.decode(channel.sent.last().bytes) as WatchDecode.Ok).envelope.routePatterns())
        assertEquals(WatchPublisher.Outcome.Unchanged, publisher.publish(snapshot(), emptySet(), routeLines = lines))
    }

    @Test
    fun `a forced republish goes out even when unchanged`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet(), force = true))
        assertEquals(2, channel.sent.size)
    }

    @Test
    fun `nothing leaves the phone when no watch has the app`() = runTest {
        val channel = FakeChannel(installed = false)
        val marker = FakeMarker()
        assertEquals(WatchPublisher.Outcome.NoWatch, WatchPublisher(channel, marker, logged::add) { now }.publish(snapshot(), emptySet()))
        assertEquals(0, channel.sent.size)
        assertNull("so a watch that appears later still gets it", marker.hash)
    }

    @Test
    fun `with no watch app, no envelope is built`() = runTest {
        var built = 0
        val publisher = WatchPublisher(FakeChannel(installed = false), FakeMarker(), logged::add) { built++; now }
        publisher.publish(snapshot(), emptySet())
        assertEquals(0, built)
    }

    @Test
    fun `nothing is sent before a snapshot is stored`() = runTest {
        val channel = FakeChannel()
        assertEquals(WatchPublisher.Outcome.NothingStored, WatchPublisher(channel, FakeMarker(), logged::add) { now }.publish(null, emptySet()))
        assertEquals(0, channel.sent.size)
    }

    @Test
    fun `asked to, an empty envelope clears a watch when nothing is stored`() = runTest {
        val channel = FakeChannel()
        val outcome = WatchPublisher(channel, FakeMarker(), logged::add) { now }.publish(null, emptySet(), force = true, emptyIfNone = true)
        assertEquals(WatchPublisher.Outcome.Published, outcome)
        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `each queue is logged, and a state that holds only when it changes`() = runTest {
        // A link that never forms left no trace: the watch kept asking for the phone and the
        // phone's log said nothing about the watch at all.
        val channel = FakeChannel(installed = false)
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        publisher.publish(snapshot(minutes = 4), emptySet())
        assertEquals(listOf("no paired watch has the app"), logged)
        channel.installed = true
        publisher.publish(snapshot(), emptySet())
        publisher.publish(snapshot(), emptySet())
        publisher.publish(snapshot(minutes = 5), emptySet())
        assertEquals(listOf("no paired watch has the app", "queued for the watch", "queued for the watch"), logged)
        publisher.publish(null, emptySet())
        assertEquals("nothing stored to send yet", logged.last())
    }

    @Test
    fun `a restarted process with nothing new to send still says a watch has the app`() = runTest {
        // The marker survives the restart, so the first publish is Unchanged: without its own line
        // the log would be as silent as before.
        val marker = FakeMarker()
        WatchPublisher(FakeChannel(), marker, {}) { now }.publish(snapshot(), emptySet())
        val restarted = WatchPublisher(FakeChannel(), marker, logged::add) { now }
        assertEquals(WatchPublisher.Outcome.Unchanged, restarted.publish(snapshot(), emptySet()))
        restarted.publish(snapshot(), emptySet())
        assertEquals(listOf("a paired watch has the app, nothing new to queue"), logged)
    }

    @Test
    fun `a queue that succeeds after a failure is logged, so the log doesn't end on the failure`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        channel.failing = true
        publisher.publish(snapshot(minutes = 4), emptySet())
        channel.failing = false
        publisher.publish(snapshot(minutes = 4), emptySet())
        assertEquals(listOf("queued for the watch", "watch publish failed: IOException", "queued for the watch"), logged)
    }

    @Test
    fun `a caller's own failure resets the state, so the recovery after it is logged`() = runTest {
        // The stored state couldn't be read before publishing: the retry finds nothing new, and
        // that is logged rather than hidden behind the queue before the failure.
        val publisher = WatchPublisher(FakeChannel(), FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        publisher.failed("stored state unreadable: IOException")
        publisher.publish(snapshot(), emptySet())
        assertEquals(
            listOf("queued for the watch", "stored state unreadable: IOException", "a paired watch has the app, nothing new to queue"),
            logged,
        )
    }

    @Test
    fun `a failed write is logged without user data and retried by the next attempt`() = runTest {
        val channel = FakeChannel(failing = true)
        val marker = FakeMarker()
        val publisher = WatchPublisher(channel, marker, logged::add) { now }
        assertEquals(WatchPublisher.Outcome.Failed, publisher.publish(snapshot(), emptySet()))
        assertNull(marker.hash)
        assertEquals(listOf("watch publish failed: IOException"), logged)
        channel.failing = false
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet()))
    }

    @Test
    fun `a publish lost to process death goes out on the next start`() = runTest {
        // The marker records the last *successful* write, so a restart compares against it.
        val marker = FakeMarker()
        WatchPublisher(FakeChannel(), marker, logged::add) { now }.publish(snapshot(), emptySet())
        val afterRestart = FakeChannel()
        assertEquals(WatchPublisher.Outcome.Published, WatchPublisher(afterRestart, marker, logged::add) { now }.publish(snapshot(minutes = 5), emptySet()))
        assertEquals(WatchPublisher.Outcome.Unchanged, WatchPublisher(afterRestart, marker, logged::add) { now }.publish(snapshot(minutes = 5), emptySet()))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a burst of writes becomes one request, the latest`() = runTest {
        val snapshots = MutableStateFlow<DeparturesSnapshot?>(snapshot(minutes = 1))
        val stars = MutableStateFlow<Set<StarredRow>>(emptySet())
        val requests = mutableListOf<Pair<DeparturesSnapshot?, Set<StarredRow>>>()
        val job = launch { WatchPublisher.requests(snapshots, stars, window = 2.seconds).collect { requests += it } }
        runCurrent()
        snapshots.value = snapshot(minutes = 2)
        advanceTimeBy(500)
        snapshots.value = snapshot(minutes = 3)
        advanceTimeBy(500)
        assertEquals(0, requests.size)
        advanceTimeBy(2_001)
        assertEquals(listOf(snapshot(minutes = 3) to emptySet<StarredRow>()), requests)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a change to the dismissed alerts is a request, since the envelope applies them`() = runTest {
        val snapshots = MutableStateFlow<DeparturesSnapshot?>(snapshot(minutes = 1))
        val stars = MutableStateFlow<Set<StarredRow>>(emptySet())
        val dismissed = MutableStateFlow(Dismissals.NONE)
        val requests = mutableListOf<Pair<DeparturesSnapshot?, Set<StarredRow>>>()
        val job = launch { WatchPublisher.requests(snapshots, stars, dismissed = dismissed, window = 2.seconds).collect { requests += it } }
        advanceTimeBy(2_001)
        assertEquals(1, requests.size)
        dismissed.value = Dismissals(setOf(DismissedAlert.ofLineStatus(LineStatus("victoria", 6, "Severe Delays"))))
        advanceTimeBy(2_001)
        assertEquals(2, requests.size)
        job.cancel()
    }

    @Test
    fun `a new verdict on an alert behind a stop is a request, since the envelope applies it`() = runTest {
        val snapshots = MutableStateFlow<DeparturesSnapshot?>(snapshot(minutes = 1))
        val stars = MutableStateFlow<Set<StarredRow>>(emptySet())
        val verdicts = MutableStateFlow<Set<AlertBehind>>(emptySet())
        val requests = mutableListOf<Pair<DeparturesSnapshot?, Set<StarredRow>>>()
        val job = launch { WatchPublisher.requests(snapshots, stars, alertsBehind = verdicts, window = 2.seconds).collect { requests += it } }
        advanceTimeBy(2_001)
        assertEquals(1, requests.size)
        verdicts.value = setOf(AlertBehind("99", "abc1234", "490GEXAMPLE1", "inbound"))
        advanceTimeBy(2_001)
        assertEquals(2, requests.size)
        job.cancel()
    }

    @Test
    fun `a refresh that changes the route lines is a request, with nothing else moving`() = runTest {
        val snapshots = MutableStateFlow<DeparturesSnapshot?>(snapshot(minutes = 1))
        val stars = MutableStateFlow<Set<StarredRow>>(emptySet())
        val routeLines = MutableStateFlow<Map<String, List<RoutePattern>>>(emptyMap())
        val requests = mutableListOf<Pair<DeparturesSnapshot?, Set<StarredRow>>>()
        val job = launch { WatchPublisher.requests(snapshots, stars, routeLines = routeLines, window = 2.seconds).collect { requests += it } }
        advanceTimeBy(2_001)
        assertEquals(1, requests.size)
        routeLines.value = mapOf("northern" to listOf(RoutePattern("Bank", listOf("940GA", "940GB"), "Edgware", "Morden")))
        advanceTimeBy(2_001)
        assertEquals(2, requests.size)
        // Cleared back to the asset: a cue too, so the watch drops them.
        routeLines.value = emptyMap()
        advanceTimeBy(2_001)
        assertEquals(3, requests.size)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an unreadable dismissed set counts as none for the cue, and is retried`() = runTest {
        val severe = Dismissals(setOf(DismissedAlert.ofLineStatus(LineStatus("victoria", 6, "Severe Delays"))))
        var reads = 0
        val source = kotlinx.coroutines.flow.flow {
            reads++
            if (reads <= 2) throw IOException("disk") else emit(severe)
        }
        val seen = mutableListOf<Dismissals>()
        val job = launch { source.asPublishCue(retryMs = 1_000) { logged += it.orEmpty() }.collect { seen += it } }
        runCurrent()
        // At once, so the snapshot and stars aren't held back; only the first failure gives one.
        assertEquals(listOf(Dismissals.NONE), seen)
        advanceTimeBy(1_001)
        assertEquals(2, reads)
        advanceTimeBy(2_001)
        assertEquals(listOf(Dismissals.NONE, severe), seen)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a failure after the dismissed set recovered gives an empty cue again`() = runTest {
        val severe = Dismissals(setOf(DismissedAlert.ofLineStatus(LineStatus("victoria", 6, "Severe Delays"))))
        var reads = 0
        val source = kotlinx.coroutines.flow.flow {
            reads++
            when (reads) {
                1 -> throw IOException("disk")
                2 -> { emit(severe); throw IOException("disk") }
                else -> kotlinx.coroutines.awaitCancellation()
            }
        }
        val seen = mutableListOf<Dismissals>()
        val job = launch { source.asPublishCue(retryMs = 1_000) { logged += it.orEmpty() }.collect { seen += it } }
        runCurrent()
        advanceTimeBy(1_001)
        // Failed, recovered, failed again: each run of failures starts with an empty cue, so the
        // watch doesn't keep hiding what it can no longer vouch for.
        assertEquals(listOf(Dismissals.NONE, severe, Dismissals.NONE), seen)
        // And the backoff restarts from the first step.
        advanceTimeBy(1_001)
        assertEquals(3, reads)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a failed collection restarts with backoff, then gives up`() = runTest {
        var runs = 0
        val job = launch {
            WatchPublisher.keepCollecting(listOf(30.seconds, 2.minutes), logged::add) {
                runs++
                throw IOException("store unreadable")
            }
        }
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(30_001)
        assertEquals(2, runs)
        advanceTimeBy(120_001)
        assertEquals(3, runs)
        advanceTimeBy(3_600_000)
        assertEquals("no restarts past the last wait", 3, runs)
        assertEquals("sync stopped: IOException, giving up", logged.last())
        job.join()
    }

    @Test
    fun `a collection that recovers keeps running`() = runTest {
        var runs = 0
        WatchPublisher.keepCollecting(listOf(1.seconds), logged::add) {
            runs++
            if (runs == 1) throw IOException("store unreadable")
        }
        assertEquals(2, runs)
    }
}
