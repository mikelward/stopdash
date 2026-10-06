package app.stopdash.watch

import app.stopdash.data.WatchTrip
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import app.stopdash.ui.BusPoleCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trip on the way as the watch is sent it. Stock station names, a made-up trip. */
class WatchTripsTest {
    private val t0 = Instant.parse("2026-10-03T08:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

    private val walk = TripLeg(TripLeg.WALKING, "", "", "", "", "9400ZZLUKSX", "King's Cross St. Pancras", at(0), at(5))
    private val ride = TripLeg("tube", "victoria", "Victoria", "9400ZZLUKSX", "King's Cross St. Pancras", "9400ZZLUVIC", "Victoria", at(6), at(15))
    private val trip = ActiveTrip(TripRoute(listOf(walk, ride)), "Victoria", startedAt = t0)

    private fun train(minutes: Long, destination: String = "Brixton Underground Station") =
        Departure("victoria", "Victoria", "outbound", destination, null, at(minutes), "tube")

    private fun build(trip: ActiveTrip = this.trip, trains: List<Departure> = emptyList(), now: Instant = t0) = runBlocking {
        WatchTrips.build(trip, "Walk to King's Cross St. Pancras", "4 min", trains, now) { leg, onBoard ->
            if (onBoard) "ride ${leg.toName}" else "step ${leg.toName}"
        }
    }

    @Test
    fun `a walk is one step and a ride two, boarding then getting off`() {
        val sent = build()
        assertEquals(listOf("step King's Cross St. Pancras", "step Victoria", "ride Victoria"), sent.steps.map { it.text })
        assertEquals(listOf(true, false, false), sent.steps.map { it.walk })
        assertEquals("Victoria", sent.steps[1].lineName)
        assertEquals("", sent.steps[0].lineName)
        assertEquals(0, sent.current)
        assertEquals("Walk to King's Cross St. Pancras", sent.title)
    }

    @Test
    fun `on the walk, the next ride's trains go with its boarding step, shortened and capped`() {
        val sent = build(trains = listOf(train(9), train(7), train(12), train(15), train(-1)))
        assertEquals(1, sent.departuresAt)
        // The one already gone is left out; three at most, soonest first.
        assertEquals(listOf(at(7), at(9), at(12)).map { it.toEpochMilli() }, sent.departures.map { it.dueAt })
        assertEquals("Brixton", sent.departures.first().destination)
    }

    @Test
    fun `no trains, no step for them`() {
        assertEquals(-1, build().departuresAt)
        assertTrue(build().departures.isEmpty())
    }

    @Test
    fun `on board, the current step is getting off and no trains go`() {
        val riding = trip.copy(legIndex = 1, boarded = true, onBoardSeen = true)
        val sent = build(riding, trains = listOf(train(9)))
        assertEquals(2, sent.current)
        assertEquals(-1, sent.departuresAt)
    }

    @Test
    fun `an arrival kept past the last step shows at the last`() {
        assertEquals(2, build(trip.copy(legIndex = 2)).current)
    }

    @Test
    fun `a resend of the same trip is the same, whenever it was sent`() {
        val first = build()
        assertTrue(WatchTrips.sameAs(first, build(now = t0).copy(sentAt = 99)))
        assertFalse(WatchTrips.sameAs(first, build(trains = listOf(train(9)))))
        assertFalse(WatchTrips.sameAs(null, first))
    }

    @Test
    fun `an unchanged trip is sent again once a heartbeat is due`() {
        val first = build()
        val later = first.copy(sentAt = first.sentAt + 30_000)
        assertFalse(WatchTrips.needsSend(first, later, Duration.ofSeconds(30)))
        assertTrue(WatchTrips.needsSend(first, later, Duration.ofSeconds(60)))
        // Timed apart from the trips' stamps: a wall clock set back doesn't hold the heartbeat.
        assertTrue(WatchTrips.needsSend(first, first.copy(sentAt = first.sentAt - 600_000), Duration.ofSeconds(60)))
        assertTrue(WatchTrips.needsSend(first, build(trains = listOf(train(9))), Duration.ZERO))
        assertTrue(WatchTrips.needsSend(null, first, Duration.ZERO))
    }

    @Test
    fun `whether to send is worked out off the thread that asks`() = runBlocking {
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            var ranOn: String? = null
            val probe = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    worker.dispatch(context) { ranOn = Thread.currentThread().name; block.run() }
                }
            }
            val first = build()
            assertTrue(withContext(caller) { WatchTrips.needsSendOn(null, first, Duration.ZERO, probe) })
            assertTrue(ranOn!!.startsWith("worker"))
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a trip is built off the thread that asks`() = runBlocking {
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            var builtOn: String? = null
            withContext(caller) {
                WatchTrips.build(trip, "Walk", "4 min", emptyList(), t0, dispatcher = worker) { leg, _ -> builtOn = Thread.currentThread().name; leg.toName }
            }
            assertTrue(builtOn!!.startsWith("worker"))
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a removal queued as a trip ends runs before the next trip's send`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val writes = WatchTrips.OrderedWrites(scope)
            val ran = java.util.Collections.synchronizedList(mutableListOf<String>())
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            // Started undispatched, so each send is queued before the line after it runs.
            val first = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { writes.run { gate.await(); ran += "send A" } }
            writes.enqueue { ran += "clear A" }
            val second = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { writes.run { ran += "send B" } }
            first.cancel()
            gate.complete(Unit)
            second.join()
            assertEquals(listOf("send A", "clear A", "send B"), ran.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a train leaving before the rider can board is marked missed, and never pushes a catchable one off`() {
        val trains = listOf(train(2), train(3), train(7), train(8), train(9))
        val sent = runBlocking {
            WatchTrips.build(trip, "Walk", "4 min", trains, t0, readyAt = at(5), poleOf = { WatchTrips.Pole("A", "King's Cross", BusPoleCues("A", "", "")) }) { leg, _ -> leg.toName }
        }
        assertEquals(listOf(at(7), at(8), at(9)).map { it.toEpochMilli() }, sent.departures.map { it.dueAt })
        assertTrue(sent.departures.none { it.missed })
        assertTrue(sent.departures.all { it.stop == "Stop A" })
        val few = runBlocking { WatchTrips.build(trip, "Walk", "4 min", listOf(train(2), train(3), train(7)), t0, readyAt = at(5)) { leg, _ -> leg.toName } }
        // Missed ones fill the room the catchable leave, marked.
        assertEquals(listOf(true, true, false), few.departures.map { it.missed })
        assertEquals("", few.departures.first().stop)
    }

    @Test
    fun `poles are told apart by bearing only among those whose trains are sent`() {
        // Two letterless poles whose signs read the same, facing apart: by their bearings while both have
        // trains sent; one with none sent can't relabel the other, which keeps its towards (Codex P2, #492).
        val west = WatchTrips.Pole("W", "King's Cross", BusPoleCues("", "Euston", "W"))
        val east = WatchTrips.Pole("E", "King's Cross", BusPoleCues("", "Euston", "E"))
        val a = train(7)
        val b = train(8)
        val both = runBlocking {
            WatchTrips.build(trip, "Walk", "4 min", listOf(a, b), t0, poleOf = { if (it === a) west else east }) { leg, _ -> leg.toName }
        }
        assertEquals(listOf("Westbound", "Eastbound"), both.departures.map { it.stop })
        val alone = runBlocking {
            WatchTrips.build(trip, "Walk", "4 min", listOf(a), t0, poleOf = { if (it === a) west else east }) { leg, _ -> leg.toName }
        }
        assertEquals(listOf("➔ Euston"), alone.departures.map { it.stop })
        // A pole whose cues name nothing goes by its name.
        val bare = runBlocking {
            WatchTrips.build(trip, "Walk", "4 min", listOf(a), t0, poleOf = { WatchTrips.Pole("X", "King's Cross", BusPoleCues("", "", "")) }) { leg, _ -> leg.toName }
        }
        assertEquals(listOf("King's Cross"), bare.departures.map { it.stop })
    }

    @Test
    fun `a write that fails or is cancelled on its own doesn't stop the queue`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val logged = java.util.Collections.synchronizedList(mutableListOf<String>())
            val writes = WatchTrips.OrderedWrites(scope) { logged += it }
            writes.enqueue { throw kotlinx.coroutines.CancellationException("task canceled") }
            writes.enqueue { throw IllegalStateException("no node") }
            var ran = false
            kotlinx.coroutines.withTimeout(5_000) { writes.run { ran = true } }
            assertTrue(ran)
            assertEquals(2, logged.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `what the screen says of the trains goes with them, even with none`() {
        val sent = runBlocking { WatchTrips.build(trip, "Walk to King's Cross St. Pancras", "4 min", emptyList(), t0, "Couldn't update just now") { leg, _ -> leg.toName } }
        assertEquals("Couldn't update just now", sent.departuresNote)
        assertTrue(sent.departuresAt >= 0)
        assertEquals(-1, build().departuresAt)
        assertEquals("", build().departuresNote)
    }

    @Test
    fun `the trip says when it was started, so the same route again is another trip`() {
        assertEquals(t0.toEpochMilli(), build().startedAt)
        assertFalse(WatchTrips.sameAs(build(), build(trip.copy(startedAt = t0.plusSeconds(600)))))
    }

    @Test
    fun `what's sent decodes as it was`() {
        val sent = build(trains = listOf(train(9)))
        assertEquals(sent, runBlocking { WatchTrip.decode(WatchTrip.encode(sent)) { throw AssertionError(it) } })
    }

    // Boarding at Euston, where the Northern line's two branches part: each train's branch is a choice
    // there, so it's named, as the tile and the trip's screen name it (maintainer, 2026-10-06).
    @Test
    fun `a train's branch is named where it's a choice from the stop`() {
        val topology = app.stopdash.domain.RouteTopology(
            mapOf(
                "northern" to listOf(
                    app.stopdash.domain.RoutePattern("Bank", listOf("HGT", "CTN", "EUS", "BNK", "KNG", "MDN"), "High Barnet", "Morden"),
                    app.stopdash.domain.RoutePattern("Charing Cross", listOf("HGT", "CTN", "EUS", "CHX", "KNG", "MDN"), "High Barnet", "Morden"),
                ),
            ),
        )
        val walkThere = TripLeg(TripLeg.WALKING, "", "", "", "", "EUS", "Euston", at(0), at(5))
        val northern = TripLeg("tube", "northern", "Northern", "EUS", "Euston", "KNG", "Kennington", at(6), at(15))
        val toKennington = ActiveTrip(TripRoute(listOf(walkThere, northern)), "Kennington", startedAt = t0)
        fun morden(minutes: Long, branch: String) =
            Departure("northern", "Northern", "outbound", "Morden Underground Station", null, at(minutes), "tube", branch = branch)
        val sent = runBlocking {
            WatchTrips.build(toKennington, "Walk to Euston", "4 min", listOf(morden(6, "Bank"), morden(8, "Charing Cross")), t0, topology = topology) { leg, _ -> leg.toName }
        }
        // Shortened as the widget shortens a branch (Codex on #612).
        assertEquals(listOf("Morden/Bank", "Morden/Charing X"), sent.departures.map { it.destination })
    }
}
