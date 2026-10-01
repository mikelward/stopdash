package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.OnTheWay.Step
import app.stopdash.domain.RouteDisruption
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
    // How often a stop's board was asked for (each is a TfL request), and which stops' boards were.
    private var boardReads = 0
    private val boardStops = mutableListOf<String>()
    private var boardTakes: Duration = Duration.ZERO
    private var now = t0
    // A monotonic clock (ms), apart from [now]: the wall clock can be set back, this can't.
    private var ticks = 0L

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val route = TripRoute(listOf(ride))
    // The ride as the blue line runs it, as the trip's cards offer it ([RideLines]): A to C by B too.
    private val blueRide = ride.copy(lineId = "blue", lineName = "Blue")
    // The ride as the purple line runs it: A to C by its own stop X between.
    private val purpleRide = ride.copy(lineId = "purple", lineName = "Purple", path = listOf("X", "C"))
    // The lines the trip's cards offer for each ride, by the ride's line: the Planner's alone unless a test says.
    private val offered = mutableMapOf<String, List<TripLeg>>()

    private fun train(vehicle: String, minutes: Long) =
        Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)
    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))

    private val departures = mutableMapOf<String, List<Departure>>()
    private val trains = mutableMapOf<String, List<VehicleCall>>()
    private var failing = false
    private val unknownStops = mutableSetOf<String>()
    private val gone = mutableSetOf<String>()
    private val asked = mutableListOf<String>()
    // Each train's calls asked for, with the line TfL was asked on.
    private val askedOn = mutableListOf<String>()
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
    // What happened to the "time to board" notification, in order: each post (how, with the train's
    // time) or done.
    private val boardAlerts = mutableListOf<String>()
    private var boardPosts = true
    // Whether a posted one is still showing: false once the rider swipes it away.
    private var boardShowing = true
    // When the answer each post counts from was had.
    private val boardAnsweredAt = mutableListOf<Instant>()
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
    // A line's own route read time, where it's not [sequenceTakes].
    private val sequenceTakesFor = mutableMapOf<String, Long>()
    // How long a train's calls take to read, on the monotonic clock.
    private var vehicleTakes = 0L
    // Each stop area's poles, and how often they were asked for (a TfL request each).
    private val polesAt = mutableMapOf<String, List<app.stopdash.domain.StopLocation>>()
    private var poleReads = 0
    private var polesFail = false

    // What the disruption check knows about the trip's coming legs, and whether it fails.
    private var known: List<RouteDisruption.Signal> = emptyList()
    private var knownFails = false
    // The direction of each coming leg's trains, as each check was given it.
    private val directionsGiven = mutableListOf<Map<Int, String>>()
    // What happened to the "route disruption" notification, in order: each post (how, with what's
    // known, by key) or done.
    private val disruptionAlerts = mutableListOf<String>()
    private var disruptionPosts = true
    // Whether a posted one is still showing: false once the rider swipes it away.
    private var disruptionShowing = true
    // Until when each post stands.
    private val disruptionUntil = mutableListOf<Instant>()
    // What a "route disruption" left showing from before a restart was posted with.
    private var disruptionsShowing: Set<String> = emptySet()

    private fun line(leg: Int, severity: Int, description: String) = RouteDisruption.Signal.Line(
        leg, "red", "Red", app.stopdash.domain.LineStatus("red", severity, description), RouteDisruption.tierOf(severity)!!,
    )

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
            boardStops += stop
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
                askedOn += "$vehicleId/$lineId"
                // By its line and id where a test gives two lines' trains one id, as TfL's ids are each line's own.
                return (trains["$lineId/$vehicleId"] ?: trains[vehicleId]).orEmpty()
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
            ticks += sequenceTakesFor[lineId] ?: sequenceTakes
            sequences[lineId]
        },
        // As the cards find them: another line only where the departures given list a train of it.
        rideLines = { _, ride, given ->
            (offered[ride.lineId] ?: listOf(ride)).filter { it == ride || given.any { train -> train.lineId == it.lineId } }
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
        onBoardSoon = { _, waiting, how, answeredAt ->
            boardAlerts += "${how.name.lowercase()} ${waiting.due}"
            boardAnsweredAt += answeredAt
            if (how == ActiveTripTracker.BoardPost.KEEP) boardShowing && boardPosts else boardPosts
        },
        onBoardSoonDone = { boardAlerts += "done" },
        disruptions = { _, _, directions ->
            directionsGiven += directions
            if (knownFails) throw TflException.Offline(null)
            RouteDisruption.Found(known, now.plus(Duration.ofMinutes(5)).takeIf { known.isNotEmpty() })
        },
        onDisruption = { _, signals, how, until ->
            disruptionAlerts += "${how.name.lowercase()} ${signals.joinToString(",") { it.key }}"
            disruptionUntil += until
            if (how == ActiveTripTracker.DisruptionPost.KEEP) disruptionShowing && disruptionPosts else disruptionPosts
        },
        onDisruptionDone = { disruptionAlerts += "done" },
        disruptionsShown = { disruptionsShowing },
    ).also { current = it }

    @Test
    fun `a ride is followed on another of its lines when that train comes first and takes the rider`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        offered["red"] = listOf(ride, blueRide)
        // Another tube line's train first (as the Circle comes along the Hammersmith & City), then
        // the ride's own: the first that takes the rider where they get off is followed.
        val blue = Departure("blue", "Blue", "outbound", "C", null, at(6), "tube", vehicleId = "4")
        departures["A"] = listOf(blue, train("3", 8))
        trains["4"] = listOf(call("A", 6), call("B", 9), call("C", 12))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("4", tracker.trip.value?.vehicleId)
        assertEquals(blueRide, tracker.trip.value?.vehicleLeg)
        // The rider is told to board the line the train is on (Codex, PR #451).
        assertEquals("Blue", (tracker.progress.value as TripProgress.Waiting).lineName)
        // TfL is asked about it on its own line, then and on each refresh after.
        assertEquals(listOf("4/blue"), askedOn)
        tracker.refresh()
        assertEquals(listOf("4/blue", "4/blue"), askedOn)
        assertEquals(blueRide, kept?.vehicleLeg)
    }

    @Test
    fun `another line's train that turns off before where the rider gets off isn't followed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        offered["red"] = listOf(ride, blueRide)
        val blue = Departure("blue", "Blue", "outbound", "Z", null, at(6), "tube", vehicleId = "4")
        departures["A"] = listOf(blue, train("3", 8))
        // The blue train leaves the ride at B for its own branch.
        trains["4"] = listOf(call("A", 6), call("B", 9), call("Z", 12))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertNull(tracker.trip.value?.vehicleLeg)
        assertEquals(listOf("4/blue", "3/red"), askedOn)
    }

    @Test
    fun `another line the trip's cards don't offer isn't followed, however its train runs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Blue comes first and takes the rider, but the cards don't offer it (its status unchecked or
        // not running, a stop of its closed, or avoided): the trip follows red, as the cards would have
        // it (Codex, PR #451).
        val blue = Departure("blue", "Blue", "outbound", "C", null, at(6), "tube", vehicleId = "4")
        departures["A"] = listOf(blue, train("3", 8))
        trains["4"] = listOf(call("A", 6), call("B", 9), call("C", 12))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(listOf("3/red"), askedOn)
    }

    @Test
    fun `a ride is followed on another line by that line's own stops between`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Purple runs from A to C by X, not red's B (as the Metropolitan runs past the Jubilee's stops):
        // its train is checked against its own, not taken for one leaving the ride (Codex, PR #451).
        offered["red"] = listOf(ride, purpleRide)
        departures["A"] = listOf(Departure("purple", "Purple", "outbound", "C", null, at(6), "tube", vehicleId = "P"), train("3", 8))
        trains["P"] = listOf(call("A", 6), call("X", 9), call("C", 12))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("P", tracker.trip.value?.vehicleId)
        assertEquals(purpleRide, tracker.trip.value?.vehicleLeg)
        assertEquals("Purple", (tracker.progress.value as TripProgress.Waiting).lineName)
        // Gone from A with the rider: riding, its stops left counted along its own path.
        now = at(7)
        trains["P"] = listOf(call("X", 9), call("C", 12))
        tracker.refresh()
        val riding = tracker.progress.value as TripProgress.Riding
        assertEquals(2, riding.stopsLeft)
        assertEquals(ride, riding.leg)
    }

    @Test
    fun `two lines' trains sharing an id are each tried, as TfL's ids are each line's own`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        offered["red"] = listOf(ride, blueRide)
        // The blue line's train 4 turns off before C; the red line's train 4, a little later, takes the ride.
        val blue = Departure("blue", "Blue", "outbound", "Z", null, at(6), "tube", vehicleId = "4")
        departures["A"] = listOf(blue, train("4", 8))
        trains["blue/4"] = listOf(call("A", 6), call("B", 9), call("Z", 12))
        trains["red/4"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("4", tracker.trip.value?.vehicleId)
        assertNull(tracker.trip.value?.vehicleLeg)
        assertEquals(listOf("4/blue", "4/red"), askedOn)
    }

    @Test
    fun `route disruption is heard once, kept up to date, and goes once nothing is known`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        // A start with no trip kept clears any alert a trip ended before a restart left up.
        assertEquals(listOf("done"), disruptionAlerts)
        disruptionAlerts.clear()
        tracker.refresh()
        assertEquals(emptyList<String>(), disruptionAlerts)
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
        tracker.refresh()
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "keep ${severe.key}"), disruptionAlerts)
        // Kept on the trip as heard, so a restart doesn't sound it again.
        assertEquals(setOf(severe.key), kept?.disruptionsHeard)
        known = emptyList()
        tracker.refresh()
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "keep ${severe.key}", "done"), disruptionAlerts)
    }

    @Test
    fun `route disruption heard again for something new, silent for what's still known`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        disruptionAlerts.clear()
        val part = line(0, 3, "Part Suspended")
        val closed = RouteDisruption.Signal.Stop(0, "C", "C", closed = true)
        known = listOf(part)
        tracker.refresh()
        known = listOf(closed, part)
        tracker.refresh()
        known = listOf(closed)
        tracker.refresh()
        assertEquals(
            listOf("new ${part.key}", "new ${closed.key},${part.key}", "keep ${closed.key}"),
            disruptionAlerts,
        )
        // An escalation is something new.
        val suspended = line(0, 2, "Suspended")
        known = listOf(closed, suspended)
        tracker.refresh()
        assertEquals("new ${closed.key},${suspended.key}", disruptionAlerts.last())
    }

    @Test
    fun `route disruption comes down with a failed check, and isn't brought back`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        disruptionAlerts.clear()
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
        tracker.refresh()
        // Unknown is never a signal: what's up comes down rather than stand on no evidence.
        knownFails = true
        tracker.refresh()
        knownFails = false
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "done"), disruptionAlerts)
        assertTrue(logged.any { it.startsWith("on the way: disruption check failed") })
    }

    @Test
    fun `route disruption swiped away isn't brought back, and one that can't be kept up comes down`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        disruptionAlerts.clear()
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
        tracker.refresh()
        disruptionShowing = false
        tracker.refresh()
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "keep ${severe.key}", "done"), disruptionAlerts)
    }

    @Test
    fun `route disruption that couldn't be heard is tried again`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        disruptionAlerts.clear()
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
        disruptionPosts = false
        tracker.refresh()
        disruptionPosts = true
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "new ${severe.key}"), disruptionAlerts)
    }

    @Test
    fun `route disruption heard before a restart is kept up silently, and goes when the trip ends`() = runTest {
        val severe = line(0, 6, "Severe Delays")
        val keptTrip = ActiveTrip(route, "C", startedAt = t0, disruptionsHeard = setOf(severe.key))
        val tracker = tracker(StandardTestDispatcher(testScheduler)) { keptTrip }
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.restore()
        known = listOf(severe)
        tracker.refresh()
        tracker.end()
        assertEquals(listOf("keep ${severe.key}", "done"), disruptionAlerts)
    }

    @Test
    fun `route disruption still showing after the app died before keeping it as heard isn't sounded again`() = runTest {
        val severe = line(0, 6, "Severe Delays")
        // Posted, then the app died (or the save failed) before the trip kept it as heard.
        val keptTrip = ActiveTrip(route, "C", startedAt = t0)
        disruptionsShowing = setOf(severe.key)
        val tracker = tracker(StandardTestDispatcher(testScheduler)) { keptTrip }
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.restore()
        known = listOf(severe)
        tracker.refresh()
        assertEquals(listOf("keep ${severe.key}"), disruptionAlerts)
        // And the trip now keeps it as heard.
        assertEquals(setOf(severe.key), kept?.disruptionsHeard)
    }

    @Test
    fun `route disruption lasts no longer than the trip's answers stay live, renewed by each check`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        known = listOf(line(0, 6, "Severe Delays"))
        tracker.refresh()
        now = at(1)
        tracker.refresh()
        // Its evidence stands five minutes; it comes down by itself 75 s after the last check, so
        // once nothing follows the trip it doesn't linger.
        assertEquals(listOf(t0.plus(ActiveTripTracker.CURRENT_FOR), at(1).plus(ActiveTripTracker.CURRENT_FOR)), disruptionUntil)
    }

    @Test
    fun `route disruption about where the rider boards goes once they say they're on board`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        disruptionAlerts.clear()
        val closed = RouteDisruption.Signal.Stop(0, "A", "A", closed = true)
        known = listOf(closed)
        tracker.refresh()
        // Checked again with the step, not a refresh later.
        known = emptyList()
        tracker.goTo(Step(0), Step(0, onBoard = true))
        assertEquals(listOf("new ${closed.key}", "done"), disruptionAlerts)
    }

    @Test
    fun `route disruption is checked for the way the coming ride's trains go, on board too`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
        // On board by the rider's word, the board is gone, but the ride still goes the way its train
        // was seen going (Codex, PR #441).
        tracker.goTo(Step(0), Step(0, onBoard = true))
        tracker.refresh()
        assertNull(tracker.nextBoard.value)
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
    }

    @Test
    fun `route disruption keeps the followed train's direction once it's off the board, whatever else is listed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
        // The followed train is gone from the board, which lists only one going the other way: that
        // says nothing about the rider's train (Codex, PR #441).
        departures["A"] = listOf(train("4", 12).copy(direction = "inbound"))
        tracker.refresh()
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
    }

    @Test
    fun `route disruption keeps the ride's direction once its train is dropped, whatever the board lists`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
        // TfL no longer knows the train, so it's dropped, and the board lists only trains going the
        // other way: they aren't the ride's, which still goes the way its train did (Codex, PR #441).
        gone += "3"
        departures["A"] = listOf(train("4", 12).copy(direction = "inbound"), train("5", 16).copy(direction = "inbound"))
        tracker.refresh()
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
    }

    @Test
    fun `route disruption gives no direction for a ride followed on another line, the ride's own kept for it`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        offered["red"] = listOf(ride, blueRide)
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
        // Its train dropped, a blue train that takes the ride is followed: the red line's direction
        // says nothing of the blue line's `inbound`/`outbound`, so the leg's is unknown (Codex, PR #451).
        gone += "3"
        departures["A"] = listOf(Departure("blue", "Blue", "inbound", "C", null, at(10), "tube", vehicleId = "4"))
        trains["4"] = listOf(call("A", 10), call("B", 13), call("C", 16))
        tracker.refresh()
        assertEquals("blue", tracker.trip.value?.vehicleLeg?.lineId)
        assertEquals(emptyMap<Int, String>(), directionsGiven.last())
        // A red train followed again goes the way the red one was seen going.
        gone -= "3"
        gone += "4"
        departures["A"] = listOf(train("5", 12))
        trains["5"] = listOf(call("A", 12), call("B", 15), call("C", 18))
        tracker.refresh()
        assertNull(tracker.trip.value?.vehicleLeg)
        assertEquals(mapOf(0 to "outbound"), directionsGiven.last())
    }

    @Test
    fun `route disruption gives no direction until a train is taken for the ride, whatever the board lists`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Every train listed goes one way, but none calls where the rider gets off, so none is theirs:
        // the line's alerts count both ways (Codex, PR #441).
        departures["A"] = listOf(train("4", 12).copy(direction = "inbound"), train("5", 16).copy(direction = "inbound"))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(emptyMap<Int, String>(), directionsGiven.last())
    }

    @Test
    fun `route disruption says no train of the line is predicted at a change the rider nears`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Off at C, a two-minute walk to D, then the blue line on.
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(16), at(18))
        val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(20), at(28), path = listOf("E"))
        fun blue(vehicle: String, minutes: Long) = Departure("blue", "Blue", "outbound", "E", null, at(minutes), "tube", vehicleId = vehicle)
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 16))
        // D lists a green train, of a line the trip's cards don't offer, its route sending it to Y: none
        // of a line that takes the rider on to E (another of the ride's lines would count, Codex on #451).
        departures["D"] = listOf(Departure("green", "Green", "outbound", "Y", null, at(19), "tube", vehicleId = "8"))
        sequences["green"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("D-Y", listOf("D", "Y"))), mapOf("D" to "D", "Y" to "Y"),
        )
        tracker.start(TripRoute(listOf(ride, walkOn, second)), "E", readyAt = now)
        disruptionAlerts.clear()
        tracker.refresh()
        // On the train, due off at 16: D is boarded from 18, not yet a few minutes off, so it isn't read.
        now = at(7)
        trains["3"] = listOf(call("B", 9), call("C", 16))
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Riding)
        assertFalse("D" in boardStops)
        assertEquals(emptyList<String>(), disruptionAlerts)
        // From 13, five minutes before: read, and no blue train there is said, standing only as long
        // as the trip's answer does.
        now = at(13)
        trains["3"] = listOf(call("C", 16))
        tracker.refresh()
        val none = RouteDisruption.Signal.Unpredicted(2, "blue", "Blue", "D", "D")
        assertEquals(listOf("new ${none.key}"), disruptionAlerts)
        assertEquals(at(13).plus(ActiveTripTracker.CURRENT_FOR), disruptionUntil.last())
        // Not on the trip's screen: while riding it has no board.
        assertNull(tracker.nextBoard.value?.takeIf { it.ride == second })
        // A blue train comes up there: nothing's left known, so it goes.
        departures["D"] = listOf(blue("9", 21))
        tracker.refresh()
        assertEquals(listOf("new ${none.key}", "done"), disruptionAlerts)
        // Off the train and walking to D: its board, read for the trip's screen, is the one asked, once.
        departures["D"] = emptyList()
        now = at(17)
        trains["3"] = emptyList()
        boardStops.clear()
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Walking)
        assertEquals(1, boardStops.count { it == "D" })
        // Heard for the leg already, and gone since: not brought back.
        assertEquals(listOf("new ${none.key}", "done"), disruptionAlerts)
        assertEquals(setOf(none.key), kept?.disruptionsHeard)
    }

    @Test
    fun `route disruption claims no missing train at a change from a board that can't be read`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(16), at(18))
        val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(20), at(28), path = listOf("E"))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 16))
        tracker.start(TripRoute(listOf(ride, walkOn, second)), "E", readyAt = now)
        tracker.refresh()
        disruptionAlerts.clear()
        now = at(13)
        trains["3"] = listOf(call("C", 16))
        unknownStops += "D"
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Riding)
        assertTrue("D" in boardStops)
        assertEquals(emptyList<String>(), disruptionAlerts)
        assertTrue(logged.any { it.startsWith("on the way: change board lookup failed for line blue") })
        // Nor from one read for a refresh that failed: the trip's times aren't stood behind then.
        unknownStops.clear()
        boardStops.clear()
        failing = true
        tracker.refresh()
        assertFalse("D" in boardStops)
        assertEquals(emptyList<String>(), disruptionAlerts)
    }

    @Test
    fun `route disruption comes down with a failed trip refresh, however fresh its own checks`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        disruptionAlerts.clear()
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
        tracker.refresh()
        // The train can't be asked after: the trip's times are no longer stood behind, nor is this (Codex, PR #441).
        failing = true
        tracker.refresh()
        failing = false
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "done"), disruptionAlerts)
    }

    @Test
    fun `time to board as the train comes in, kept up to date by each answer, and done once it leaves`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(5)
        tracker.start(route, "C", readyAt = now)
        // A start with no trip kept clears any alert a trip ended before a restart left up.
        assertEquals(listOf("done"), boardAlerts)
        boardAlerts.clear()
        tracker.refresh()
        // Three minutes out: not yet.
        assertEquals(emptyList<String>(), boardAlerts)
        now = at(6)
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}"), boardAlerts)
        assertEquals("0/3", kept?.boardWarned)
        // Each fresh answer keeps it up, silently, never said anew; running late, at its new time.
        now = at(7)
        tracker.refresh()
        trains["3"] = listOf(call("A", 9), call("B", 12), call("C", 15))
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "keep ${at(8)}", "keep ${at(9)}"), boardAlerts)
        // It left the boarding stop: the rider is on it, and the alert is done with.
        now = at(10)
        trains["3"] = listOf(call("B", 12), call("C", 15))
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "keep ${at(8)}", "keep ${at(9)}", "done"), boardAlerts)
        tracker.refresh()
        assertEquals(4, boardAlerts.size)
    }

    @Test
    fun `time to board comes down with a failed refresh, for good`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        tracker.refresh()
        // TfL can't be reached: the time it counts down to is no longer stood behind.
        failing = true
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "done"), boardAlerts)
        // Not brought back by the next answer: it was heard, and may have been swiped away meanwhile,
        // which the failed refresh couldn't see (Codex, PR #440).
        failing = false
        tracker.refresh()
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "done"), boardAlerts)
    }

    @Test
    fun `time to board swiped away before a failed refresh stays away`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        tracker.refresh()
        boardShowing = false
        failing = true
        tracker.refresh()
        failing = false
        tracker.refresh()
        // Taken down (a no-op on a swiped one) and never posted again.
        assertEquals(listOf("new ${at(8)}", "done"), boardAlerts)
    }

    @Test
    fun `time to board swiped away isn't brought back`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        tracker.refresh()
        boardShowing = false
        tracker.refresh()
        tracker.refresh()
        // Found gone on the first answer after (and taken down, a no-op on a swiped one), then left
        // alone; nothing more to take down once it leaves.
        assertEquals(listOf("new ${at(8)}", "keep ${at(8)}", "done"), boardAlerts)
        now = at(10)
        trains["3"] = listOf(call("B", 12), call("C", 15))
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "keep ${at(8)}", "done"), boardAlerts)
    }

    @Test
    fun `time to board an answer can't keep up comes down at once, not at its timeout`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        tracker.refresh()
        // Still showing, but this answer can't keep it up (its train now past, say): it would
        // otherwise count down to a time no longer stood behind (Codex, PR #440).
        boardPosts = false
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "keep ${at(8)}", "done"), boardAlerts)
        // And it isn't brought back.
        boardPosts = true
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "keep ${at(8)}", "done"), boardAlerts)
    }

    @Test
    fun `time to board lasts from when its answer was had, however long the request took`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        // The board read takes a minute: the post comes after it, but the answer is as old as the step.
        boardTakes = Duration.ofMinutes(1)
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}"), boardAlerts)
        assertEquals(listOf(tracker.updatedAt.value), boardAnsweredAt)
        assertEquals(at(6), boardAnsweredAt.single())
    }

    @Test
    fun `time to board is judged after a slow lookup, not before it`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        // Two and a half minutes out when the step begins; the board read takes a minute.
        now = at(5).plusSeconds(30)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        boardTakes = Duration.ofMinutes(1)
        tracker.refresh()
        // A minute and a half out by the time it's known: said now, not a refresh later.
        assertEquals(listOf("new ${at(8)}"), boardAlerts)
        // Aged from the answer, though: when the step began.
        assertEquals(at(5).plusSeconds(30), boardAnsweredAt.single())
    }

    @Test
    fun `time to board goes on arriving, even when forgetting the trip fails`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}"), boardAlerts)
        // Seen at C, having taken 7: arrived, though the trip can't be forgotten on the phone yet.
        departures["A"] = listOf(train("9", 8))
        trains["7"] = emptyList()
        saves = false
        now = at(13)
        tracker.refresh(fixAt(51.52))
        assertEquals(TripProgress.Arrived, tracker.progress.value)
        assertEquals(listOf("new ${at(8)}", "done"), boardAlerts)
    }

    @Test
    fun `time to board that couldn't be said is tried again`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        boardPosts = false
        tracker.refresh()
        assertEquals("", kept?.boardWarned)
        boardPosts = true
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "new ${at(8)}"), boardAlerts)
        assertEquals("0/3", kept?.boardWarned)
    }

    @Test
    fun `time to board kept over a restart isn't said anew, and goes when the trip ends`() = runTest {
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        val saidBefore = ActiveTrip(route, "C", startedAt = t0, legStartedAt = at(5), vehicleId = "3", boardsAt = at(8), boardWarned = "0/3")
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saidBefore })
        now = at(7)
        tracker.restore()
        tracker.refresh()
        // Kept up to date if it's still up, never heard again.
        assertEquals(listOf("keep ${at(8)}"), boardAlerts)
        tracker.end()
        assertEquals(listOf("keep ${at(8)}", "done"), boardAlerts)
    }

    @Test
    fun `time to board for a train the rider was left behind by goes, and the next one's is said in its time`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        now = at(7)
        tracker.start(route, "C", readyAt = now)
        boardAlerts.clear()
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}"), boardAlerts)
        // TfL drops it before it leaves: another train is picked, the first one's alert taken down.
        gone += "3"
        departures["A"] = listOf(train("4", 12))
        trains["4"] = listOf(call("A", 12), call("B", 15), call("C", 18))
        tracker.refresh()
        assertEquals("4", tracker.trip.value?.vehicleId)
        assertEquals(listOf("new ${at(8)}", "done"), boardAlerts)
        now = at(10)
        tracker.refresh()
        assertEquals(listOf("new ${at(8)}", "done", "new ${at(12)}"), boardAlerts)
    }

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

    @Test
    fun `a pick asks only after trains the line's route takes the rider's way`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // The red line forks after A: to C, the rider's way, and to Z.
        sequences["red"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A-C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A-Z", listOf("A", "Y", "Z"))),
            stopNames = mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y", "Z" to "Z"),
        )
        // The first three due turn off to Z; the fourth runs to C.
        val toZ = listOf(train("1", 5), train("2", 6), train("3", 7)).map { it.copy(destination = "Z") }
        departures["A"] = toZ + train("4", 8)
        toZ.forEach { trains[it.vehicleId] = listOf(call("A", 5), call("Y", 8), call("Z", 12)) }
        trains["4"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Found at once, and only it asked after: the ones to Z cost no request.
        assertEquals("4", tracker.trip.value?.vehicleId)
        assertEquals(listOf("4"), asked)
    }

    @Test
    fun `without the line's route, a pick asks after the first few as before`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toZ = listOf(train("1", 5), train("2", 6), train("3", 7)).map { it.copy(destination = "Z") }
        departures["A"] = toZ + train("4", 8)
        toZ.forEach { trains[it.vehicleId] = listOf(call("A", 5), call("Y", 8), call("Z", 12)) }
        trains["4"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Their calls decide: the three asked after all turn off, so none is followed yet.
        assertEquals(listOf("1", "2", "3"), asked)
        assertEquals("", tracker.trip.value?.vehicleId)
    }

    // A line running north through synthetic stops A, B (1.1 km on) and C (2.2 km on).
    private val redLine = app.stopdash.domain.LineSequence(
        routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C"))),
        stopNames = mapOf("A" to "A", "B" to "B", "C" to "C"),
        stopPositions = mapOf("A" to (51.5 to -0.12), "B" to (51.51 to -0.12), "C" to (51.52 to -0.12)),
    )
    private fun fixAt(latitude: Double, longitude: Double = -0.12) =
        app.stopdash.domain.LocationFix(app.stopdash.domain.Coordinates(latitude, longitude), isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L)

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
    fun `seen along another of the ride's lines, its own stops place the rider on its train`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        // Purple runs from A to C by X, a street west of red's B: seen at X, the rider is on the purple
        // train that left A, placed by purple's own stops (Codex, PR #451).
        sequences["purple"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "X", "C"))),
            stopNames = mapOf("A" to "A", "X" to "X", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "X" to (51.51 to -0.135), "C" to (51.52 to -0.12)),
        )
        offered["red"] = listOf(ride, purpleRide)
        departures["A"] = listOf(Departure("purple", "Purple", "outbound", "C", null, at(5), "tube", vehicleId = "P"), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("9", tracker.trip.value?.vehicleId)
        // P has left A, 9 is still to come; the rider is seen at X.
        departures["A"] = listOf(train("9", 8))
        trains["P"] = listOf(call("X", 8), call("C", 12))
        now = at(7)
        tracker.refresh(fixAt(51.51, -0.135))
        assertEquals("P", tracker.trip.value?.vehicleId)
        assertEquals(purpleRide, tracker.trip.value?.vehicleLeg)
        assertEquals("X", (tracker.progress.value as TripProgress.Riding).nextStop)
    }

    @Test
    fun `seen along another of the ride's lines, it's placed on its train though the ride's own route can't be had`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Red's route can't be had; purple's can, and the rider is seen along it (Codex, PR #451).
        sequences["purple"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "X", "C"))),
            stopNames = mapOf("A" to "A", "X" to "X", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "X" to (51.51 to -0.135), "C" to (51.52 to -0.12)),
        )
        offered["red"] = listOf(ride, purpleRide)
        departures["A"] = listOf(Departure("purple", "Purple", "outbound", "C", null, at(5), "tube", vehicleId = "P"), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("9", tracker.trip.value?.vehicleId)
        departures["A"] = listOf(train("9", 8))
        trains["P"] = listOf(call("X", 8), call("C", 12))
        now = at(7)
        tracker.refresh(fixAt(51.51, -0.135))
        assertEquals("P", tracker.trip.value?.vehicleId)
        assertEquals(purpleRide, tracker.trip.value?.vehicleLeg)
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
    fun `a fix grown old over another line's slow route read doesn't end the ride`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Red's route can't be had; purple's read is slow. Seen where the ride gets off, but by a fix
        // too old by then to say so (Codex, PR #451).
        sequences["purple"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "X", "C"))),
            stopNames = mapOf("A" to "A", "X" to "X", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "X" to (51.51 to -0.135), "C" to (51.52 to -0.12)),
        )
        offered["red"] = listOf(ride, purpleRide)
        departures["A"] = listOf(Departure("purple", "Purple", "outbound", "C", null, at(5), "tube", vehicleId = "P"), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("9", 8))
        sequenceTakesFor["purple"] = 10_000L
        now = at(7)
        tracker.refresh(fixAt(51.52))
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
    fun `on board by the rider's word, a train followed still minutes away gives way to the one at the platform`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        now = at(4)
        // Only 3 is listed when the trip starts, due at 8, so it's the one followed.
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 16))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        // 2 turns up and stands at A; the rider gets on it with 3 still three minutes off.
        departures["A"] = listOf(train("2", 5), train("3", 8))
        trains["2"] = listOf(call("A", 5), call("B", 7), call("C", 11))
        now = at(5)
        tracker.goTo(Step(0), Step(0, onBoard = true))
        assertEquals("2", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals("B", (tracker.progress.value as TripProgress.Riding).nextStop)
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
    fun `a loop train held on its way round is still awaited while the rider is seen at the boarding stop`() = runTest {
        // Synthetic positions: the boarding stop, and the rider a few meters from it.
        val placed = TripRoute(listOf(ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Still on its previous lap, B and C ahead of A, due at A at 12.
        departures["A"] = listOf(train("8", 12))
        trains["8"] = listOf(call("B", 3), call("C", 6), call("A", 12), call("B", 15), call("C", 18))
        tracker.start(placed, "C", readyAt = now)
        tracker.refresh()
        assertEquals(TripProgress.Waiting(placed.legs.single(), at(12)), tracker.progress.value)
        // Held on its way round, its call at A jumps seven minutes: the calls of a train that just left
        // with its next lap predicted. The rider, seen still at A, hasn't left on it.
        trains["8"] = listOf(call("B", 4), call("C", 7), call("A", 19), call("B", 22), call("C", 25))
        now = at(2)
        tracker.refresh(fixAt(51.5003))
        assertFalse(checkNotNull(tracker.trip.value).boarded)
        assertEquals(TripProgress.Waiting(placed.legs.single(), at(19)), tracker.progress.value)
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

