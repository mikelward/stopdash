package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.OnTheWay.Step
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following a started trip, on synthetic stops A–E and example vehicle ids. */
class ActiveTripTrackerTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))
    // How often a stop's board was asked for (each is a TfL request).
    private var boardReads = 0
    private var boardTakes: Duration = Duration.ZERO
    private var now = t0
    // A monotonic clock (ms), apart from [now]: the wall clock can be set back, this can't.
    private var ticks = 0L

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val route = TripRoute(listOf(ride))

    private fun train(vehicle: String, minutes: Long) =
        Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)
    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))

    private val departures = mutableMapOf<String, List<Departure>>()
    private val trains = mutableMapOf<String, List<VehicleCall>>()
    private var failing = false
    private val unknownStops = mutableSetOf<String>()
    private val gone = mutableSetOf<String>()
    private val asked = mutableListOf<String>()
    private var kept: ActiveTrip? = null
    private var saves = true
    // The next save is cut short, as by the activity being recreated mid-write.
    private var cancelNextSave = false
    private var current: ActiveTripTracker? = null
    // Whether the trip was still shown when it was forgotten on the device: a refresh cancelled
    // once it's gone from the screen must not leave it kept.
    private var shownWhenForgotten: Boolean? = null
    private val warned = mutableListOf<TripProgress.Riding>()
    private var alertPosts = true
    // What the tracker logged: never a stop or place (docs/PRIVACY.md).
    private val logged = mutableListOf<String>()
    private var alertsDone = 0
    // What happened to the "get off soon" notification, in order: said or taken back.
    private val alerts = mutableListOf<String>()
    // Holds a train's calls back until completed, as a slow TfL answer does.
    private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    // Each station's point and entrances, by stop id, and how often they were asked for (a TfL request each).
    private val entrancesAt = mutableMapOf<String, app.stopdash.domain.StationPlaces>()
    private var entranceReads = 0
    private var entrancesFail = false
    // How long an entrances read takes, on the monotonic clock.
    private var entrancesTake = 0L
    // What the wall clock does while the entrances are read.
    private var entranceClock: () -> Unit = {}
    // Each line's route, placing a ride's stops ([ActiveTripTracker]'s lineSequence), and how long
    // reading one takes, on the monotonic clock.
    private val sequences = mutableMapOf<String, app.stopdash.domain.LineSequence>()
    private var sequenceTakes = 0L
    // How long a train's calls take to read, on the monotonic clock.
    private var vehicleTakes = 0L
    // Each stop area's poles, and how often they were asked for (a TfL request each).
    private val polesAt = mutableMapOf<String, List<app.stopdash.domain.StopLocation>>()
    private var poleReads = 0
    private var polesFail = false

    private fun tracker(dispatcher: kotlinx.coroutines.CoroutineDispatcher, load: () -> ActiveTrip? = { null }) = ActiveTripTracker(
        load = load,
        save = {
            if (cancelNextSave) {
                cancelNextSave = false
                throw kotlinx.coroutines.CancellationException("recreated")
            }
            if (it == null) shownWhenForgotten = current?.trip?.value != null
            kept = it
            saves
        },
        arrivals = { stop ->
            boardReads++
            // TfL (or the request pool) taking its time: the clock moves on during the read.
            now = now.plus(boardTakes)
            if (stop in unknownStops) throw TflException.NotFound(null) else departures[stop].orEmpty()
        },
        vehicles = object : VehicleSource {
            override suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall> {
                gate?.await()
                ticks += vehicleTakes
                if (failing) throw TflException.Offline(null)
                if (vehicleId in gone) throw TflException.NotFound(null)
                asked += vehicleId
                return trains[vehicleId].orEmpty()
            }
        },
        stationPlaces = { stop ->
            entranceReads++
            ticks += entrancesTake
            entranceClock()
            if (entrancesFail) throw TflException.Offline(null)
            entrancesAt[stop] ?: app.stopdash.domain.StationPlaces()
        },
        lineSequence = { lineId ->
            ticks += sequenceTakes
            sequences[lineId]
        },
        stopPoles = { area ->
            poleReads++
            if (polesFail) throw TflException.Offline(null)
            polesAt[area].orEmpty()
        },
        clock = { now },
        elapsed = { ticks },
        io = dispatcher,
        warn = { logged += it },
        onGetOffSoon = { _, riding ->
            warned += riding
            alerts += "said ${riding.leg.toId}"
            alertPosts
        },
        onGetOffSoonDone = {
            alertsDone++
            alerts += "done"
        },
    ).also { current = it }

    @Test
    fun `Start follows the soonest train that runs where the rider is going`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // The 3-minute train is too soon to reach; the 4-minute one runs the other way.
        departures["A"] = listOf(train("1", 3), train("2", 4), train("3", 6))
        trains["2"] = listOf(call("C", 2), call("A", 4))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(6)), tracker.progress.value)
        assertEquals(listOf("2", "3"), asked)
        assertEquals(tracker.trip.value, kept)
    }

    // A line running north through synthetic stops A, B (1.1 km on) and C (2.2 km on).
    private val redLine = app.stopdash.domain.LineSequence(
        routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C"))),
        stopNames = mapOf("A" to "A", "B" to "B", "C" to "C"),
        stopPositions = mapOf("A" to (51.5 to -0.12), "B" to (51.51 to -0.12), "C" to (51.52 to -0.12)),
    )
    private fun fixAt(latitude: Double) =
        app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(latitude, -0.12), isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L)

    @Test
    fun `a rider seen along the ride while its train is awaited is on the train that left`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        // The rider reached A sooner than planned and took 7; the trip follows 9, the next it
        // thought they could reach.
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("9", tracker.trip.value?.vehicleId)
        // 7 has left A, 9 is still to come.
        departures["A"] = listOf(train("9", 8))
        trains["7"] = listOf(call("B", 7), call("C", 12))
        now = at(7)
        // No fix, or one still at A: waiting for 9, as before.
        tracker.refresh()
        assertEquals(TripProgress.Waiting(ride, at(8)), tracker.progress.value)
        tracker.refresh(fixAt(51.5005))
        assertEquals(TripProgress.Waiting(ride, at(8)), tracker.progress.value)
        // Seen at B: on 7 (maintainer, 2026-09-29), and kept so.
        tracker.refresh(fixAt(51.51))
        assertEquals("7", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals("B", (tracker.progress.value as TripProgress.Riding).nextStop)
        assertEquals(tracker.trip.value, kept)
        assertTrue(logged.none { "51." in it })
    }

    @Test
    fun `seen between stops, a later train still short of the last stop they passed isn't theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("8", 7), train("9", 10))
        trains["8"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("8", tracker.trip.value?.vehicleId)
        // The rider took 7, now past B; 8 left A after it and, running late, is still short of B.
        departures["A"] = listOf(train("9", 10))
        trains["7"] = listOf(call("C", 11))
        trains["8"] = listOf(call("B", 10), call("C", 14))
        now = at(9)
        // Seen between B and C: 8, the newer, is behind them, so they're on 7 (Codex, PR #383).
        tracker.refresh(fixAt(51.515))
        assertEquals("7", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals("C", (tracker.progress.value as TripProgress.Riding).nextStop)
    }

    @Test
    fun `a rider seen where they get off while their train was awaited has done the ride`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // They took 7, which has called at C and gone on: no train left to say so, but they're at C.
        departures["A"] = listOf(train("9", 8))
        trains["7"] = emptyList()
        now = at(13)
        tracker.refresh(fixAt(51.52))
        assertEquals(TripProgress.Arrived, tracker.progress.value)
        assertNull(tracker.trip.value)
        assertNull(kept)
    }

    @Test
    fun `seen at a stop, a later train still on its way there isn't theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("8", 7), train("9", 10))
        trains["8"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // The rider took 7, at B now; 8 left A after it and is due at B in three minutes.
        departures["A"] = listOf(train("9", 10))
        trains["7"] = listOf(call("B", 9), call("C", 13))
        trains["8"] = listOf(call("B", 12), call("C", 16))
        now = at(9)
        tracker.refresh(fixAt(51.51))
        assertEquals("7", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
    }

    @Test
    fun `seen along the ride, another line's or another branch's train isn't taken for theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Red has a branch to D from B; C is on the other.
        sequences["red"] = redLine.copy(
            routes = redLine.routes + app.stopdash.domain.LineRoute("A ↔ D", listOf("A", "B", "D")),
            stopNames = redLine.stopNames + ("D" to "D"),
        )
        // Both left A just now: pink, which the trip doesn't follow, and red 6 for D, whose calls
        // don't yet show it turning off after B.
        departures["A"] = listOf(
            train("9", 8),
            Departure("pink", "Pink", "outbound", "C", null, at(5), "tube", vehicleId = "5"),
            Departure("red", "Red", "outbound", "D", null, at(5), "tube", vehicleId = "6"),
        )
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        trains["5"] = listOf(call("B", 7), call("C", 12))
        trains["6"] = listOf(call("B", 7))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("9", tracker.trip.value?.vehicleId)
        departures["A"] = listOf(train("9", 8))
        now = at(7)
        // Seen at B: neither is theirs, so the trip waits as it was, claiming nothing.
        tracker.refresh(fixAt(51.51))
        assertEquals("9", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(8)), tracker.progress.value)
        assertEquals(listOf("9", "9"), asked)
    }

    @Test
    fun `seen along the ride, a train TfL no longer knows is passed over, and said`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 4), train("8", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("9", tracker.trip.value?.vehicleId)
        // 7 and 8 have left A; 8, the newer, is gone from TfL's view.
        departures["A"] = listOf(train("9", 8))
        trains["7"] = listOf(call("B", 7), call("C", 12))
        gone += "8"
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals("7", tracker.trip.value?.vehicleId)
        // Said, coarsely: the line, never the train or where (Codex, PR #383).
        assertTrue(logged.any { it == "on the way: train not found on line red" })
        assertTrue(logged.none { "51." in it || "\"8\"" in it })
    }

    @Test
    fun `a fix grown old over a slow route read doesn't say the rider boarded`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("9", 8))
        trains["7"] = listOf(call("B", 7), call("C", 12))
        // A second old as it comes, then the route's read takes ten more: too old to place them.
        sequenceTakes = 10_000L
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals("9", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(8)), tracker.progress.value)
    }

    @Test
    fun `a fix grown old over a slow train lookup doesn't say the rider boarded it`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("9", 8))
        trains["7"] = listOf(call("B", 7), call("C", 12))
        // A second old as it comes, fresh after the route's read, then 7's calls take ten more:
        // too old to match against them.
        vehicleTakes = 10_000L
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals("9", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(8)), tracker.progress.value)
        assertTrue("7" in asked)
    }

    @Test
    fun `seen along the ride with no train that left to be found, the trip waits as it was`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals("9", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(8)), tracker.progress.value)
        // Said, coarsely: the line, never where.
        assertTrue(logged.any { it.startsWith("on the way: seen along the ride") && "red" in it })
        // With no route to place the ride's stops, a fix says nothing.
        sequences.clear()
        departures["A"] = listOf(train("9", 8))
        tracker.refresh(fixAt(51.51))
        assertEquals(TripProgress.Waiting(ride, at(8)), tracker.progress.value)
    }

    @Test
    fun `a bus whose stop isn't predicted yet is followed when it shows the leg's terminus`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val bus = TripLeg(
            "bus", "73", "73", "490000000001A", "Example Road", "490000000009Z", "Example Street", at(5), at(45),
            path = listOf("490G00000002", "490G00000009"), headings = listOf("Example Terminus"),
        )
        fun bus(vehicle: String, destination: String) =
            Departure("73", "73", "outbound", destination, null, at(6), "bus", vehicleId = vehicle)
        // A short working first, then one signed for the Planner's terminus; neither predicted to the stop yet.
        departures["490000000001A"] = listOf(bus("LX01", "Somewhere Short"), bus("LX02", "Example Terminus"))
        val short = listOf(VehicleCall("490000000001A", "Example Road", null, at(6)), VehicleCall("490000000002B", "Next Road", null, at(9)))
        trains["LX01"] = short
        trains["LX02"] = short
        now = at(4)
        tracker.start(TripRoute(listOf(bus)), "Example Street", readyAt = now)
        tracker.refresh()
        assertEquals("LX02", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a trip that starts with a walk walks first, then picks its train`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(4))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        // The rider is at Z, and sets off now; the walk takes 4 minutes.
        assertEquals(TripProgress.Walking(toA, at(4)), tracker.progress.value)
        assertEquals(0, tracker.trip.value?.legIndex)
        now = at(5)
        // The walk done, the ride picks its train in the same refresh, not 30 s later.
        tracker.refresh()
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals("3", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `Next puts the rider at the ride before the walk's time is up, and picks its train at once`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        departures["A"] = listOf(train("2", 3), train("3", 6))
        trains["2"] = listOf(call("A", 3), call("B", 7), call("C", 11))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(2)
        // Ten minutes by the clock, but the rider is at A after two: the next train they can catch is picked.
        tracker.goTo(Step(0), Step(1))
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals("2", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(3)), tracker.progress.value)
        assertEquals(tracker.trip.value, kept)
        // A tap on the leg they're on changes nothing, and none moves past the last: arriving would
        // forget the trip with no Back to undo it.
        tracker.goTo(Step(1), Step(1))
        assertEquals("2", tracker.trip.value?.vehicleId)
        tracker.goTo(Step(1), Step(2))
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals(tracker.trip.value, kept)
        // A tap made from a leg the trip has since moved off is stale: nothing moves.
        tracker.goTo(Step(0), Step(0))
        assertEquals(1, tracker.trip.value?.legIndex)
    }

    @Test
    fun `Next from boarding puts the rider on board the train followed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        now = at(5)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals(TripProgress.Waiting(ride, at(6)), tracker.progress.value)
        // On it as it stands at A (maintainer, 2026-09-29): riding, B next, two stops to go.
        tracker.goTo(Step(0), Step(0, onBoard = true))
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "B", 2, at(14), false), tracker.progress.value)
        assertEquals(tracker.trip.value, kept)
        // A refresh while TfL still has it at A keeps them on it.
        tracker.refresh()
        assertEquals(TripProgress.Riding(ride, "B", 2, at(14), false), tracker.progress.value)
        // Back: waiting for a train again.
        tracker.goTo(Step(0, onBoard = true), Step(0))
        assertFalse(tracker.trip.value?.boarded == true)
    }

    @Test
    fun `a step moved to isn't live until TfL answers for it`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        now = at(5)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals(at(5), tracker.updatedAt.value)
        // Next from boarding while TfL takes its time: on board, its next stop not yet known, and
        // the last answer not passed off as this step's (Codex, PR #384).
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        gate = slow
        now = at(5).plusSeconds(20)
        val moving = backgroundScope.launch { tracker.goTo(Step(0), Step(0, onBoard = true)) }
        runCurrent()
        assertTrue(tracker.progress.value is TripProgress.Riding)
        assertNull(tracker.updatedAt.value)
        gate = null
        slow.complete(Unit)
        moving.join()
        assertEquals(TripProgress.Riding(ride, "B", 2, at(14), false), tracker.progress.value)
        assertEquals(at(5).plusSeconds(20), tracker.updatedAt.value)
    }

    @Test
    fun `Back straight after Next from a ride they're on is back on its train`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        departures["A"] = listOf(train("3", 6), train("4", 12))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        trains["4"] = listOf(call("A", 12), call("B", 15), call("C", 20))
        now = at(5)
        tracker.start(TripRoute(listOf(ride, walkOn)), "D", readyAt = now)
        tracker.refresh()
        tracker.goTo(Step(0), Step(0, onBoard = true))
        assertEquals("3", tracker.trip.value?.vehicleId)
        // Next to the walk too soon, then Back: on 3 still, past A, not on 4 at A by then or lost
        // (Codex, PR #384).
        now = at(8)
        tracker.goTo(Step(0, onBoard = true), Step(1))
        now = at(12)
        trains["3"] = listOf(call("B", 13), call("C", 17))
        tracker.goTo(Step(1), Step(0, onBoard = true))
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "B", 2, at(17), false), tracker.progress.value)
    }

    @Test
    fun `Back straight after Next from a ride on no train named is on none still`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        // Nothing at A when they say they're on: 3 is eight minutes off.
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 16))
        tracker.start(TripRoute(listOf(toA, ride, walkOn)), "D", readyAt = now)
        tracker.goTo(Step(0), Step(1, onBoard = true))
        assertEquals(TripProgress.Lost(ride), tracker.progress.value)
        // Next to the walk too soon, then Back as 3 stands at A: still on no train they can be named,
        // not on 3, which they never boarded (Codex, PR #384).
        now = at(7)
        tracker.goTo(Step(1, onBoard = true), Step(2))
        now = at(8)
        tracker.goTo(Step(2), Step(1, onBoard = true))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals(TripProgress.Lost(ride), tracker.progress.value)
    }

    @Test
    fun `on board by the rider's word with no train followed, the one at the platform is theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        // 2 is at A now, due a moment ago; 3 comes later.
        departures["A"] = listOf(train("2", 3), train("3", 8))
        trains["2"] = listOf(call("A", 3), call("B", 7), call("C", 11))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 16))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(3).plusSeconds(30)
        // Still walking by the clock, but on the train: straight to getting off.
        tracker.goTo(Step(0), Step(1, onBoard = true))
        assertEquals("2", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals("B", (tracker.progress.value as TripProgress.Riding).nextStop)
    }

    @Test
    fun `on board by the rider's word just after it left, the train is still theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        // 2 left A a moment ago: the board still lists it, but TfL has dropped its call there.
        departures["A"] = listOf(train("2", 3), train("3", 8))
        trains["2"] = listOf(call("B", 7), call("C", 11))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 16))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(3).plusSeconds(30)
        tracker.goTo(Step(0), Step(1, onBoard = true))
        assertEquals("2", tracker.trip.value?.vehicleId)
        assertEquals("B", (tracker.progress.value as TripProgress.Riding).nextStop)
        // Nor does a train off the ride pass for it: 4, gone the other way, isn't.
        val other = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("4", 3))
        trains["4"] = listOf(call("X", 7))
        other.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        other.goTo(Step(0), Step(1, onBoard = true))
        assertEquals("", other.trip.value?.vehicleId)
    }

    @Test
    fun `on board by the rider's word, a train due minutes later isn't taken for theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        // Nothing at A now: 3 is eight minutes off.
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 16))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        tracker.goTo(Step(0), Step(1, onBoard = true))
        // On board, but on no train it can name: said so, rather than claiming the later one.
        assertEquals("", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals(TripProgress.Lost(ride), tracker.progress.value)
        // Nor once that train is due: it's measured from when they said so, not from each refresh.
        now = at(7).plusSeconds(30)
        tracker.refresh()
        assertEquals("", tracker.trip.value?.vehicleId)
        // Kept so, a restart says the same before any answer, not that they're riding.
        val restarted = tracker(StandardTestDispatcher(testScheduler), load = { kept })
        restarted.restore()
        assertEquals(TripProgress.Lost(ride), restarted.progress.value)
    }

    @Test
    fun `leaving a leg whose get off soon was said takes it back`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn)), "D", readyAt = now)
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        tracker.refresh()
        assertEquals(1, warned.size)
        // Off at C before TfL saw the train there: the stop the alert named is behind them.
        val done = alertsDone
        tracker.goTo(Step(0), Step(1))
        assertEquals(done + 1, alertsDone)
        assertEquals(TripProgress.Walking(walkOn, at(17)), tracker.progress.value)
    }

    @Test
    fun `a move that can't be saved isn't made, and says so`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn)), "D", readyAt = now)
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        tracker.refresh()
        assertEquals("said C", alerts.last())
        // The trip, and the alert, stay as a restart would bring them back.
        saves = false
        tracker.goTo(Step(0), Step(1))
        assertEquals(0, tracker.trip.value?.legIndex)
        assertTrue(tracker.notKept.value)
        assertEquals("said C", alerts.last())
        // Tried again once saves work, it's made.
        saves = true
        tracker.goTo(Step(0), Step(1))
        assertEquals(1, tracker.trip.value?.legIndex)
        assertFalse(tracker.notKept.value)
        assertEquals("done", alerts.last())
    }

    @Test
    fun `an alert left up by a moved trip saved just before the app died is taken back on restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        now = at(12)
        // Kept on the walk after C, moved there off the warned ride: its alert is still owed.
        val onDevice = ActiveTrip(TripRoute(listOf(ride, walkOn)), "D", t0, legIndex = 1, legStartedAt = at(12), alertLeft = true)
        val restarted = tracker(dispatcher, load = { onDevice })
        restarted.restore()
        assertEquals(listOf("done"), alerts)
        // Settled: the next save clears the mark, so a later restart doesn't take back a new alert.
        restarted.refresh()
        assertEquals(false, kept?.alertLeft)
    }

    @Test
    fun `an alert said just before the app died, before it was saved as said, stays up on restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("C", 14))
        now = at(12)
        // On the ride, kept as not yet warned: the alert may be up already, and is the right one.
        val onDevice = ActiveTrip(route, "C", t0, vehicleId = "3", boardsAt = at(6), boarded = true, boardedAt = at(6))
        val restarted = tracker(dispatcher, load = { onDevice })
        restarted.restore()
        assertEquals(emptyList<String>(), alerts)
    }

    @Test
    fun `the walk to a route that starts with a walk comes first too`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(4))
        // Two minutes' walk to Z, where the route's own walk to A begins.
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = at(2))
        val walking = tracker.progress.value as TripProgress.Walking
        assertEquals("Z", walking.leg.toId)
        assertEquals(at(2), walking.until)
    }

    @Test
    fun `the walk to the first stop comes first, as the route shows it`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        // Three minutes' walk to A before the route's first ride.
        tracker.start(route, "C", readyAt = at(3))
        val walking = tracker.progress.value as TripProgress.Walking
        assertEquals("A", walking.leg.toId)
        assertEquals(at(3), walking.until)
        now = at(3)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(6)), tracker.progress.value)
    }

    @Test
    fun `on board, get off soon is said once`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        tracker.refresh()
        assertEquals(1, warned.size)
        assertTrue((tracker.progress.value as TripProgress.Riding).getOffSoon)
    }

    @Test
    fun `get off soon is said again when the stop's time moves, so its deadline follows`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        trains["3"] = listOf(call("C", 22))
        tracker.refresh()
        tracker.refresh()
        assertEquals(listOf(at(14), at(22)), warned.map { it.getOffAt })
    }

    @Test
    fun `get off soon is taken back once the rider is past that leg`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        alertsDone = 0 // Start's first read found no kept trip, and cleared any alert left.
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        assertEquals(1, warned.size)
        assertEquals(0, alertsDone)
        // Past C: got off, and the trip has arrived.
        now = at(15)
        trains["3"] = emptyList()
        tracker.refresh()
        assertEquals(TripProgress.Arrived, tracker.progress.value)
        assertEquals(1, alertsDone)
    }

    @Test
    fun `get off soon is taken back when the train is lost, and said again once it's found`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        alertsDone = 0 // Start's first read found no kept trip, and cleared any alert left.
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        assertEquals(1, warned.size)
        // Its calls now leave the leg before C: the stop it named may no longer be the rider's.
        trains["3"] = listOf(call("X", 13))
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Lost)
        assertEquals(1, alertsDone)
        // Back on the leg: said again.
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        assertEquals(2, warned.size)
    }

    @Test
    fun `an alert left from a trip ended just before the app died is cleared once no trip is found`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        assertTrue(tracker.restore())
        assertEquals(1, alertsDone)
    }

    @Test
    fun `get off soon stands through a failed lookup`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        alertsDone = 0 // Start's first read found no kept trip, and cleared any alert left.
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        // No signal underground: the stop is still the rider's, so the alert stays.
        failing = true
        tracker.refresh()
        assertEquals(0, alertsDone)
    }

    @Test
    fun `get off soon not said (notifications off) is tried again on the next refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        alertPosts = false
        tracker.refresh()
        tracker.refresh()
        alertPosts = true
        tracker.refresh()
        tracker.refresh()
        assertEquals(3, warned.size)
    }

    @Test
    fun `a trip is current only while its refreshes reach TfL`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        // Started, not yet asked: nothing live to show.
        assertFalse(ActiveTripTracker.isCurrent(tracker.updatedAt.value, now))
        tracker.refresh()
        assertTrue(ActiveTripTracker.isCurrent(tracker.updatedAt.value, now))
        // Back after the app was away for a while: the last answer is too old to stand behind.
        now = at(5)
        assertFalse(ActiveTripTracker.isCurrent(tracker.updatedAt.value, now))
        failing = true
        tracker.refresh()
        assertFalse(ActiveTripTracker.isCurrent(tracker.updatedAt.value, now))
        // A failure soon after an answer: that answer isn't stood behind either, on any surface.
        failing = false
        tracker.refresh()
        assertTrue(ActiveTripTracker.isCurrent(tracker.updatedAt.value, now))
        failing = true
        now = at(5).plusSeconds(10)
        tracker.refresh()
        assertFalse(ActiveTripTracker.isCurrent(tracker.updatedAt.value, now))
    }

    @Test
    fun `a train whose calls say it leaves before the rider can be there is passed over in the same pick`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        now = at(4)
        // The board still shows 3 due at 6, but its own calls have it at A at 3: gone already.
        departures["A"] = listOf(train("3", 6), train("4", 9))
        trains["3"] = listOf(call("A", 3), call("B", 6), call("C", 11))
        trains["4"] = listOf(call("A", 9), call("B", 12), call("C", 17))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("4", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `Done on an arrival already forgotten asks nothing more of the device`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(10)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        now = at(15)
        trains["3"] = emptyList()
        tracker.refresh()
        assertNull(tracker.trip.value)
        // Storage failing now changes nothing: there's no trip left to forget.
        saves = false
        assertTrue(tracker.end())
        assertEquals(0, tracker.endFailures.value)
        assertFalse(tracker.endFailed.value)
    }

    @Test
    fun `a train TfL no longer knows is passed over for the next`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("2", 5), train("3", 6))
        gone += "2"
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertFalse(tracker.failed.value)
    }

    @Test
    fun `a followed train TfL no longer knows, not yet boarded, is dropped for another`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 9))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        trains["4"] = listOf(call("A", 9), call("B", 12), call("C", 17))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        gone += "3"
        departures["A"] = listOf(train("4", 9))
        // The next is picked in the same refresh, not 30 s later, when it may have gone.
        tracker.refresh()
        assertEquals("4", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `an arrival that can't be forgotten on the device isn't let go, and says so`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(10)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        now = at(15)
        trains["3"] = emptyList()
        saves = false
        tracker.refresh()
        assertEquals(TripProgress.Arrived, tracker.progress.value)
        assertTrue(tracker.trip.value != null)
        assertTrue(tracker.endFailed.value)
        assertEquals(1, tracker.endFailures.value)
        now = at(16)
        // The same train round again on its next lap: the arrival stands, and only the forgetting
        // is tried again, not the ride.
        trains["3"] = listOf(call("A", 30), call("B", 33), call("C", 38))
        asked.clear()
        tracker.refresh()
        assertEquals(TripProgress.Arrived, tracker.progress.value)
        assertTrue(asked.isEmpty())
        assertEquals(1, tracker.endFailures.value) // once per failure, not once per retry
        saves = true
        tracker.refresh()
        assertNull(tracker.trip.value)
        assertFalse(tracker.endFailed.value)
        assertEquals(0, tracker.endFailures.value)
    }

    @Test
    fun `a boarding stop TfL doesn't know is a failed update, not a train still being found`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        unknownStops += "A"
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertTrue(tracker.failed.value)
        // Logged without the stop.
        assertTrue(logged.isNotEmpty())
        assertTrue(logged.none { Regex("\\bA\\b").containsMatchIn(it) })
        assertFalse(ActiveTripTracker.isCurrent(tracker.updatedAt.value, now))
    }

    @Test
    fun `a failed lookup says so and claims nothing new`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        failing = true
        tracker.refresh()
        assertTrue(tracker.failed.value)
        assertEquals("3", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a ride straight after another picks its train in the refresh that ends the first`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val onward = TripLeg("tube", "red", "Red", "C", "C", "E", "E", at(16), at(25), path = listOf("D", "E"))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        departures["C"] = listOf(train("7", 16))
        trains["7"] = listOf(call("C", 16), call("D", 19), call("E", 23))
        tracker.start(TripRoute(listOf(ride, onward)), "E", readyAt = now)
        tracker.refresh()
        now = at(10)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        now = at(15)
        trains["3"] = emptyList()
        // Off the first train: the next is picked now, not 30 s later, when it may have gone.
        tracker.refresh()
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals("7", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a change's train is picked once the change is done, not while it runs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val first = ride.copy(changeAfter = Duration.ofMinutes(4))
        val onward = TripLeg("tube", "red", "Red", "C", "C", "E", "E", at(20), at(28), path = listOf("D", "E"))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        departures["C"] = listOf(train("7", 21))
        trains["7"] = listOf(call("C", 21), call("D", 24), call("E", 27))
        tracker.start(TripRoute(listOf(first, onward)), "E", readyAt = now)
        tracker.refresh()
        now = at(10)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        now = at(15)
        trains["3"] = emptyList()
        asked.clear()
        tracker.refresh()
        // Off at 14, changing until 18: no train is picked or asked about meanwhile.
        assertEquals(TripProgress.Changing(onward, at(18)), tracker.progress.value)
        assertEquals("", tracker.trip.value?.vehicleId)
        assertFalse("7" in asked)
        now = at(18)
        tracker.refresh()
        assertEquals("7", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `arriving forgets the trip, and says it arrived`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(10)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        now = at(15)
        trains["3"] = emptyList()
        tracker.refresh()
        assertEquals(TripProgress.Arrived, tracker.progress.value)
        assertNull(tracker.trip.value)
        assertNull(kept)
        assertEquals(true, shownWhenForgotten)
    }

    @Test
    fun `on board, an empty answer keeps following the train rather than pick another`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 8))
        trains["3"] = listOf(call("A", 6), call("B", 9))
        trains["4"] = listOf(call("A", 8), call("B", 11), call("C", 16))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        trains["3"] = listOf(call("B", 9))
        tracker.refresh()
        // TfL's predictions go quiet before C was ever predicted: not arrived, still on train 3.
        now = at(10)
        trains["3"] = emptyList()
        tracker.refresh()
        assertEquals(TripProgress.Lost(ride), tracker.progress.value)
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertTrue(checkNotNull(tracker.trip.value).boarded)
    }

    @Test
    fun `a trip that couldn't be saved says so, and saying so clears once it is`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        saves = false
        tracker.start(route, "C", readyAt = now)
        assertTrue(tracker.notKept.value)
        saves = true
        tracker.refresh()
        assertFalse(tracker.notKept.value)
    }

    @Test
    fun `Start before the kept trip is read doesn't replace it`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.start(TripRoute(listOf(ride.copy(lineName = "Other"))), "Elsewhere", readyAt = now)
        assertEquals(saved, tracker.trip.value)
        assertEquals(null, kept)
    }

    @Test
    fun `a trip that can't be forgotten on the device isn't ended, and says so`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.restore()
        saves = false
        assertFalse(tracker.end())
        assertEquals(saved, tracker.trip.value)
        assertTrue(tracker.endFailed.value)
        // Each failed attempt is told apart, so a second one opens the trip again too.
        assertFalse(tracker.end())
        assertEquals(2, tracker.endFailures.value)
        saves = true
        assertTrue(tracker.end())
        assertNull(tracker.trip.value)
        assertFalse(tracker.endFailed.value)
        // Ended at last: nothing left for a later screen to reopen on.
        assertEquals(0, tracker.endFailures.value)
    }

    @Test
    fun `a kept trip that couldn't be read is read again, and Start waits for it`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        var unreadable = true
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = {
            if (unreadable) throw java.io.IOException("storage busy")
            saved
        })
        assertFalse(tracker.restore())
        // Start can't tell whether a trip is already on the way, so it doesn't replace one, and says so.
        tracker.start(route, "C", readyAt = now)
        assertNull(tracker.trip.value)
        assertTrue(tracker.failed.value)
        unreadable = false
        assertTrue(tracker.restore())
        assertEquals(saved, tracker.trip.value)
    }

    @Test
    fun `a kept trip read back when a first read was cut short`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        var cutShort = true
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = {
            if (cutShort) {
                cutShort = false
                throw kotlinx.coroutines.CancellationException("recreated")
            }
            saved
        })
        try {
            tracker.restore()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // As the old composition's effect would be.
        }
        tracker.restore()
        assertEquals(saved, tracker.trip.value)
    }

    @Test
    fun `a trip whose save was cut short is saved on the next refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        cancelNextSave = true
        try {
            tracker.start(route, "C", readyAt = now)
        } catch (_: kotlinx.coroutines.CancellationException) {
            // As Start's coroutine would be.
        }
        assertNull(kept)
        // No train to pick: the trip is unchanged, but still not on the device.
        tracker.refresh()
        assertEquals(tracker.trip.value, kept)
    }

    @Test
    fun `a kept trip on board comes back on board, not lost, before its first answer`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true, dueOffAt = at(14))
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.restore()
        val progress = tracker.progress.value as TripProgress.Riding
        assertEquals(ride, progress.leg)
        assertEquals(at(14), progress.getOffAt)
    }

    @Test
    fun `a failed lookup on board keeps the step it had`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        val riding = tracker.progress.value
        failing = true
        tracker.refresh()
        assertEquals(riding, tracker.progress.value)
    }

    @Test
    fun `a start is counted from the tap until the trip is kept`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.launchStart(this, route, "C", readyAt = now)
        // Counted at once, before the trip is saved, so the service started from the tap waits for it.
        assertEquals(1, tracker.starting.value)
        advanceUntilIdle()
        assertEquals(0, tracker.starting.value)
        assertEquals(route, checkNotNull(tracker.trip.value).route)
    }

    @Test
    fun `a rider at a station's entrance ends the walk, however far the Planner placed its stop`() = runTest {
        // Synthetic positions: the Planner's point for A, an entrance 300 m north, and the rider by it.
        val stop = app.stopdash.domain.Coordinates(51.5, -0.12)
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = stop, entrances = listOf(app.stopdash.domain.Coordinates(51.5027, -0.12)))
        val rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5029, -0.12), isFallback = false, accuracyMeters = 20f)
        tracker.start(TripRoute(listOf(toA, ride.copy(fromAt = stop))), "C", readyAt = now)
        now = at(2)
        // TfL can't be reached for the entrances: the walk goes on, the failure is logged, and it's asked again.
        entrancesFail = true
        tracker.refresh(rider)
        assertEquals(0, tracker.trip.value?.legIndex)
        assertTrue(logged.any { it == "on the way: station entrances lookup failed: Offline" })
        entrancesFail = false
        tracker.refresh(rider)
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(2, entranceReads)
        // The log says what the fix saw them at, never where.
        assertTrue(logged.contains("on the way: seen at the stop by an entrance"))
    }

    @Test
    fun `a walk to a station the Planner left unplaced ends at its entrance too`() = runTest {
        // Synthetic positions: A's own point and an entrance, read from TfL; the rider by the entrance.
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = app.stopdash.domain.Coordinates(51.5, -0.12), entrances = listOf(app.stopdash.domain.Coordinates(51.5027, -0.12)))
        val rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5029, -0.12), isFallback = false, accuracyMeters = 20f)
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(2)
        tracker.refresh(rider)
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals(1, entranceReads)
    }

    @Test
    fun `a rider seen at the station they get off at is off, though the train followed is a stop away`() = runTest {
        // Synthetic positions: C's own point (unplaced by the Planner) and an entrance, the rider by it.
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        entrancesAt["C"] = app.stopdash.domain.StationPlaces(point = app.stopdash.domain.Coordinates(51.53, -0.12), entrances = listOf(app.stopdash.domain.Coordinates(51.5327, -0.12)))
        tracker.start(TripRoute(listOf(ride, walkOn)), "D", readyAt = now)
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        tracker.refresh()
        assertEquals("said C", alerts.last())
        // The train followed still due at C at 14, a later one than theirs: the rider is at C already.
        val rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5329, -0.12), isFallback = false, accuracyMeters = 20f)
        tracker.refresh(rider)
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals(TripProgress.Walking(walkOn, at(17)), tracker.progress.value)
        // Its "get off soon" has done its job, and C's entrances were read for it, once.
        assertEquals("done", alerts.last())
        assertEquals(1, entranceReads)
    }

    @Test
    fun `a get off soon a refresh is done with is taken back only once the trip is saved`() = runTest {
        // Synthetic positions: C's own point, the rider by it.
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        entrancesAt["C"] = app.stopdash.domain.StationPlaces(point = app.stopdash.domain.Coordinates(51.53, -0.12))
        tracker.start(TripRoute(listOf(ride, walkOn)), "D", readyAt = now)
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        tracker.refresh()
        assertEquals("said C", alerts.last())
        // Seen at C, but the trip can't be saved: the alert stays, since a restart would bring back
        // the ride it was said for, counted as said.
        saves = false
        val rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5301, -0.12), isFallback = false, accuracyMeters = 20f)
        tracker.refresh(rider)
        assertEquals(1, tracker.trip.value?.legIndex)
        assertTrue(logged.contains("on the way: seen at the stop by its placed point"))
        assertEquals("said C", alerts.last())
        // Saved on the next refresh: taken back then, and the mark cleared after.
        saves = true
        tracker.refresh()
        assertEquals("done", alerts.last())
        tracker.refresh()
        assertEquals(false, kept?.alertLeft)
    }

    @Test
    fun `a fix gone stale while the entrances were read isn't acted on`() = runTest {
        val stop = app.stopdash.domain.Coordinates(51.5, -0.12)
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = stop, entrances = listOf(app.stopdash.domain.Coordinates(51.5027, -0.12)))
        // Taken 5 s ago; the entrances take 8 s to come back, so it's 13 s old when weighed.
        val rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5029, -0.12), isFallback = false, accuracyMeters = 20f, ageMillis = 5_000L)
        entrancesTake = 8_000L
        tracker.start(TripRoute(listOf(toA, ride.copy(fromAt = stop))), "C", readyAt = now)
        now = at(2)
        tracker.refresh(rider)
        assertEquals(0, tracker.trip.value?.legIndex)
        // Read now: the next fresh fix there ends the walk at once.
        tracker.refresh(rider)
        assertEquals(1, tracker.trip.value?.legIndex)
    }

    @Test
    fun `a walk whose time runs out while the entrances are read ends on its time`() = runTest {
        val stop = app.stopdash.domain.Coordinates(51.5, -0.12)
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(2))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = stop)
        // Still well away; the read takes the clock past the walk's two minutes.
        val away = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.51, -0.12), isFallback = false, accuracyMeters = 20f)
        tracker.start(TripRoute(listOf(toA, ride.copy(fromAt = stop))), "C", readyAt = now)
        now = at(1)
        entranceClock = { now = at(3) }
        tracker.refresh(away)
        assertEquals(1, tracker.trip.value?.legIndex)
    }

    @Test
    fun `a bus stop walked to costs no entrance request`() = runTest {
        val pole = app.stopdash.domain.Coordinates(51.5, -0.12)
        val bus = TripLeg("bus", "73", "73", "A", "A", "C", "C", at(5), at(15), fromAt = pole)
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(toA, bus)), "C", readyAt = now)
        tracker.refresh(app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.51, -0.12), isFallback = false, accuracyMeters = 20f))
        assertEquals(0, entranceReads)
    }

    @Test
    fun `a station's entrances are asked for once, and not without a fix`() = runTest {
        val stop = app.stopdash.domain.Coordinates(51.5, -0.12)
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = stop)
        // Well away from A: still walking.
        val away = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.51, -0.12), isFallback = false, accuracyMeters = 20f)
        tracker.start(TripRoute(listOf(toA, ride.copy(fromAt = stop))), "C", readyAt = now)
        tracker.refresh()
        assertEquals(0, entranceReads)
        tracker.refresh(away)
        tracker.refresh(away)
        assertEquals(1, entranceReads)
        assertEquals(0, tracker.trip.value?.legIndex)
    }

    @Test
    fun `a rider seen still at the boarding stop after their train left is moved to the next train`() = runTest {
        // Synthetic positions: the boarding stop, and the rider a few meters from it.
        val placed = TripRoute(listOf(ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 9))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        trains["4"] = listOf(call("A", 9), call("B", 12), call("C", 17))
        tracker.start(placed, "C", readyAt = now)
        tracker.refresh()
        now = at(6)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        assertTrue(checkNotNull(tracker.trip.value).boarded)
        now = at(8)
        tracker.refresh(rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5003, -0.12), isFallback = false, accuracyMeters = 5f))
        assertEquals("4", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(placed.legs.single(), at(9)), tracker.progress.value)
    }

    @Test
    fun `a get-off alert said for a train the rider turned out not to be on is taken back`() = runTest {
        // A short leg: get off soon is said as the train leaves, before a fix shows the rider left behind.
        val placed = TripRoute(listOf(ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 9))
        trains["3"] = listOf(call("A", 6), call("C", 8))
        trains["4"] = listOf(call("A", 9), call("B", 12), call("C", 17))
        tracker.start(placed, "C", readyAt = now)
        alertsDone = 0 // Start's first read found no kept trip, and cleared any alert left.
        tracker.refresh()
        now = at(6)
        trains["3"] = listOf(call("C", 8))
        tracker.refresh()
        assertEquals(1, warned.size)
        now = at(7)
        tracker.refresh(rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5003, -0.12), isFallback = false, accuracyMeters = 5f))
        assertEquals("4", tracker.trip.value?.vehicleId)
        assertEquals(1, alertsDone)
    }

    @Test
    fun `a rider left behind isn't told to get off when the next train can't be found yet`() = runTest {
        val placed = TripRoute(listOf(ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 9))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(placed, "C", readyAt = now)
        tracker.refresh()
        now = at(6)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Riding)
        // Seen still on the platform, but the next train's lookup fails.
        now = at(8)
        failing = true
        tracker.refresh(rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5003, -0.12), isFallback = false, accuracyMeters = 5f))
        assertTrue(tracker.failed.value)
        assertEquals(TripProgress.Waiting(placed.legs.single(), null), tracker.progress.value)
    }

    @Test
    fun `a rider left behind with no next train yet asks the board once, not twice`() = runTest {
        val placed = TripRoute(listOf(ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(placed, "C", readyAt = now)
        tracker.refresh()
        now = at(6)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        // Seen still on the platform, and no other train on the board yet.
        now = at(8)
        departures["A"] = emptyList()
        boardReads = 0
        tracker.refresh(rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5003, -0.12), isFallback = false, accuracyMeters = 5f, ageMillis = 1_000L))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(1, boardReads)
    }

    @Test
    fun `a fix that went stale waiting behind another refresh doesn't drop the train`() = runTest {
        val placed = TripRoute(listOf(ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 9))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        trains["4"] = listOf(call("A", 9), call("B", 12), call("C", 17))
        tracker.start(placed, "C", readyAt = now)
        tracker.refresh()
        now = at(6)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        now = at(8)
        // A refresh held up on TfL, and a fix a second old waiting behind it for 20 s.
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        gate = slow
        backgroundScope.launch { tracker.refresh() }
        runCurrent()
        val fixed = backgroundScope.launch {
            tracker.refresh(rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5003, -0.12), isFallback = false, accuracyMeters = 5f, ageMillis = 1_000L))
        }
        runCurrent()
        now = now.plusSeconds(20)
        ticks += 20_000
        gate = null
        slow.complete(Unit)
        fixed.join()
        assertEquals("3", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a fix's wait is timed on a clock that can't be set back`() = runTest {
        val placed = TripRoute(listOf(ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 9))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        trains["4"] = listOf(call("A", 9), call("B", 12), call("C", 17))
        tracker.start(placed, "C", readyAt = now)
        tracker.refresh()
        now = at(6)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        now = at(8)
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        gate = slow
        backgroundScope.launch { tracker.refresh() }
        runCurrent()
        val fixed = backgroundScope.launch {
            tracker.refresh(rider = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5003, -0.12), isFallback = false, accuracyMeters = 5f, ageMillis = 1_000L))
        }
        runCurrent()
        // 20 s pass while the phone's clock is set back a few seconds.
        now = now.minusSeconds(5)
        ticks += 20_000
        gate = null
        slow.complete(Unit)
        fixed.join()
        assertEquals("3", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a kept trip comes back after the app was closed`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.restore()
        assertEquals(saved, tracker.trip.value)
        tracker.end()
        assertNull(tracker.trip.value)
        assertNull(kept)
    }

    // The first stop placed, and a rider seen on its forecourt: synthetic positions.
    private val stopAt = app.stopdash.domain.Coordinates(51.5, -0.12)
    private val atTheStop = app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(51.5003, -0.12), isFallback = false, accuracyMeters = 5f, ageMillis = 1_000L)

    @Test
    fun `a rider seen at the first stop is done walking there, and the train is picked at once`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        // A 3-minute walk estimated at Start, but the rider is already there.
        tracker.start(TripRoute(listOf(ride.copy(fromAt = stopAt))), "C", readyAt = at(3))
        assertTrue(tracker.progress.value is TripProgress.Walking)
        now = at(1)
        tracker.refresh(rider = atTheStop)
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride.copy(fromAt = stopAt), at(6)), tracker.progress.value)
    }

    @Test
    fun `without a fix the walk still runs its time`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        tracker.start(TripRoute(listOf(ride.copy(fromAt = stopAt))), "C", readyAt = at(3))
        now = at(1)
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Walking)
        assertEquals(0, tracker.trip.value?.legIndex)
    }

    @Test
    fun `a bus ride's board carries its pole's letter, read once, and none from a station`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // An example pole of an example stop area; the Planner names the area as the ride's end.
        val bus = TripLeg("bus", "73", "73", "490000000001D", "Example Road", "490000000002A", "Other Road", at(5), at(15), fromArea = "490G00000001")
        val pole = app.stopdash.domain.StopLocation("490000000001D", "Example Road", 0.0, 0.0, stopLetter = "D", towards = "Other Road")
        polesAt["490G00000001"] = listOf(app.stopdash.domain.StopLocation("490000000001C", "Example Road", 0.0, 0.0, stopLetter = "C"), pole)
        polesFail = true
        tracker.start(TripRoute(listOf(bus)), "Other Road", readyAt = now)
        tracker.refresh()
        // Couldn't be read: the board shows headed by name alone, the failure logged, and asked again.
        assertNull(tracker.nextBoard.value?.pole)
        assertTrue(logged.any { it.startsWith("on the way: boarding stop lookup failed") })
        polesFail = false
        tracker.refresh()
        assertEquals(pole, tracker.nextBoard.value?.pole)
        val reads = poleReads
        tracker.refresh()
        assertEquals(pole, tracker.nextBoard.value?.pole)
        assertEquals(reads, poleReads)
        // A station's board splits by platform: no pole is asked for.
        tracker.end()
        poleReads = 0
        departures["A"] = listOf(train("3", 6))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertNull(tracker.nextBoard.value?.pole)
        assertEquals(0, poleReads)
    }

    @Test
    fun `the next ride's board shows while walking and waiting, from one read a refresh, and goes once on board`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(3))
        now = at(1)
        tracker.refresh()
        // Walking to A: its board is in, for the ride about to be taken.
        assertEquals(ride, tracker.nextBoard.value?.ride)
        assertEquals(listOf(train("3", 6)), tracker.nextBoard.value?.departures)
        // The walk done and the train picked in one refresh: the board is read once, not twice.
        now = at(3)
        boardReads = 0
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(1, boardReads)
        assertEquals(at(3), tracker.nextBoard.value?.fetchedAt)
        // Its train gone, the rider only taken to be on it: the board stays, as they may still be on
        // the platform (maintainer, 2026-09-29).
        now = at(7)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        assertEquals(ride, tracker.nextBoard.value?.ride)
        // Said on board: nothing left to board, so no board.
        val at = app.stopdash.domain.OnTheWay.stepOf(checkNotNull(tracker.trip.value))
        tracker.goTo(at, at.copy(onBoard = true))
        tracker.refresh()
        assertNull(tracker.nextBoard.value)
    }

    @Test
    fun `a board that can't be read keeps the last, which ages on the screen`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        tracker.start(route, "C", readyAt = at(3))
        now = at(1)
        tracker.refresh()
        unknownStops += "A"
        now = at(2)
        tracker.refresh()
        assertEquals(at(1), tracker.nextBoard.value?.fetchedAt)
        // Kept, but said to have failed: not passed off as current.
        assertEquals(true, tracker.nextBoard.value?.failed)
        assertTrue(logged.none { it.contains("A ") || it.endsWith(" A") })
        // Read again: current, and no longer failed.
        unknownStops -= "A"
        now = at(3)
        tracker.refresh()
        assertEquals(false, tracker.nextBoard.value?.failed)
        assertEquals(at(3), tracker.nextBoard.value?.fetchedAt)
    }

    @Test
    fun `a board that couldn't be read at all says so, rather than showing nothing`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        unknownStops += "A"
        tracker.start(route, "C", readyAt = at(3))
        now = at(1)
        tracker.refresh()
        val board = tracker.nextBoard.value
        assertEquals(ride, board?.ride)
        assertEquals(true, board?.failed)
        assertNull(board?.fetchedAt)
        assertTrue(board?.departures.orEmpty().isEmpty())
    }

    @Test
    fun `a board that can't be read while waiting is asked for once a refresh, not again to pick a train`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        tracker.start(route, "C", readyAt = now)
        unknownStops += "A"
        boardReads = 0
        tracker.refresh()
        assertEquals(1, boardReads)
        // Said on the screen and in the trip's state, not passed off as current.
        assertTrue(tracker.failed.value)
        assertEquals(true, tracker.nextBoard.value?.failed)
        assertEquals("", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a slow board read still serves the train pick in the same refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(3))
        now = at(3)
        boardReads = 0
        // The walk ends and the ride picks its train in one refresh; the read takes 20 s.
        boardTakes = Duration.ofSeconds(20)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(1, boardReads)
    }

    @Test
    fun `a failed board read as the walk ends isn't asked for again to pick the train`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        tracker.start(route, "C", readyAt = at(3))
        now = at(3)
        unknownStops += "A"
        boardReads = 0
        // The walk runs out its time and the ride's train is wanted in the same refresh.
        tracker.refresh()
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals(1, boardReads)
        assertTrue(tracker.failed.value)
        assertEquals(true, tracker.nextBoard.value?.failed)
    }

    @Test
    fun `off a train and walking on, the next ride's board is read in the same refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
        val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(22), at(30), path = listOf("E"))
        departures["A"] = listOf(train("3", 6))
        departures["D"] = listOf(Departure("blue", "Blue", "outbound", "E", null, at(24), "tube", vehicleId = "7"))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn, second)), "E", readyAt = now)
        tracker.refresh()
        now = at(7)
        trains["3"] = listOf(call("B", 9), call("C", 14))
        tracker.refresh()
        // Its train gone, but not seen on it: the board stays up (maintainer, 2026-09-29).
        assertEquals(ride, tracker.nextBoard.value?.ride)
        // Seen due at C, and now past it: off the train, walking to D.
        now = at(15)
        trains["3"] = emptyList()
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Walking)
        assertEquals(second, tracker.nextBoard.value?.ride)
        assertEquals(departures["D"], tracker.nextBoard.value?.departures)
    }

    @Test
    fun `seen at the stop as its board can't be read, the trip says waiting, not still walking`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(ride.copy(fromAt = stopAt))), "C", readyAt = at(3))
        unknownStops += "A"
        now = at(1)
        tracker.refresh(rider = atTheStop)
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals(TripProgress.Waiting(ride.copy(fromAt = stopAt), null), tracker.progress.value)
        assertTrue(tracker.failed.value)
    }
}

