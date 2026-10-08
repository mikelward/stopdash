package app.stopdash.ui

import app.stopdash.ThreadRecorder
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.LineStatus
import app.stopdash.domain.OnTheWay.Step
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import app.stopdash.domain.SteadyClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    // Told on each read of the tracker's clock: for where its work runs.
    private var clockRead: () -> Unit = {}
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
    // The lines of the ride that went unchecked, as a failed check leaves them ([RideLinesNow.uncheckedLines]).
    private var ridesUnchecked: Set<String> = emptySet()
    // The boards each ride lines check was given, by stop.
    private val ridesGiven = mutableListOf<Map<String, List<Departure>>>()
    // The lines the rider hides.
    private var hiddenLines: Set<String> = emptySet()

    private fun train(vehicle: String, minutes: Long) =
        Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)
    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))

    private val departures = mutableMapOf<String, List<Departure>>()
    private val trains = mutableMapOf<String, List<VehicleCall>>()
    private var afterRead: (() -> Unit)? = null
    private var failing = false
    // Trains whose own lookups fail.
    private val failingFor = mutableSetOf<String>()
    // The boarding stop's board alone failing to load.
    private var boardFails = false
    // The line's route alone failing to load.
    private var routeFails = false
    private val unknownStops = mutableSetOf<String>()
    private val gone = mutableSetOf<String>()
    private val asked = mutableListOf<String>()
    // Each train's calls asked for, with the line TfL was asked on.
    private val askedOn = mutableListOf<String>()
    private var kept: ActiveTrip? = null

    // Where the station index places stops, by id, and the threads it was read on.
    private val stopPositions = mutableMapOf<String, app.stopdash.domain.Coordinates>()
    private val indexThreads = ThreadRecorder()
    // The thread each save ran on.
    private val saveThreads = ThreadRecorder()
    // The thread each "route disruption" post ran on.
    private val postThreads = ThreadRecorder()
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
    // Run as a line's route is read: a test's moment mid-refresh.
    private var sequenceClock: (String) -> Unit = {}
    // A station's read held open until completed: a refresh caught mid-read.
    private var entrancesGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    // Each line's route, placing a ride's stops ([ActiveTripTracker]'s lineSequence), and how long
    // reading one takes, on the monotonic clock.
    private val sequences = mutableMapOf<String, app.stopdash.domain.LineSequence>()
    private var sequenceTakes = 0L
    // A line's own route read time, where it's not [sequenceTakes].
    private val sequenceTakesFor = mutableMapOf<String, Long>()
    // Each line whose route was read, in order.
    private val sequencesRead = mutableListOf<String>()
    // The routes read before each "route disruption" post.
    private val routesReadAtPost = mutableListOf<List<String>>()
    // How long a train's calls take to read, on the monotonic clock.
    private var vehicleTakes = 0L
    // Each stop area's poles, and how often they were asked for (a TfL request each).
    private val polesAt = mutableMapOf<String, List<app.stopdash.domain.StopLocation>>()
    private var poleReads = 0
    private var polesFail = false

    // What the disruption check knows about the trip's coming legs, and whether it fails.
    private var known: List<RouteDisruption.Signal> = emptyList()
    // How long each known signal's own evidence stands, by its key, where the check says.
    private var knownStands: Map<String, Instant> = emptyMap()
    private var knownFails = false
    // The coming stations' notes the check finds.
    private var knownNotes: List<RouteDisruption.StationNote> = emptyList()
    // The direction of each coming leg's trains, as each check was given it.
    private val directionsGiven = mutableListOf<Map<Int, String>>()
    // The next board's lines each check was asked about besides the trip's, and the lines' statuses it says it found.
    private val alsoLinesGiven = mutableListOf<Collection<String>>()
    // Called while a check is out, before it answers.
    private var duringCheck: () -> Unit = {}
    private var knownLines: RouteDisruption.LinesChecked? = null
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

    private fun tracker(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        compute: kotlinx.coroutines.CoroutineDispatcher = dispatcher,
        remembered: () -> app.stopdash.domain.LocationFix? = { null },
        forgetting: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(dispatcher),
        preciseAllowed: () -> Boolean = { true },
        load: () -> ActiveTrip? = { null },
    ) = ActiveTripTracker(
        remembered = remembered,
        forgetting = forgetting,
        preciseAllowed = preciseAllowed,
        load = load,
        save = {
            saveThreads.note()
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
            if (boardFails) throw TflException.Offline(null)
            if (stop in unknownStops) throw TflException.NotFound(null) else departures[stop].orEmpty()
        },
        vehicles = object : VehicleSource {
            override suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall> {
                gate?.await()
                ticks += vehicleTakes
                if (failing || vehicleId in failingFor) throw TflException.Offline(null)
                if (vehicleId in gone) throw TflException.NotFound(null)
                asked += vehicleId
                askedOn += "$vehicleId/$lineId"
                // By its line and id where a test gives two lines' trains one id, as TfL's ids are each line's own.
                val calls = (trains["$lineId/$vehicleId"] ?: trains[vehicleId]).orEmpty()
                // What changes once this read is answered, for a test's next read to see.
                afterRead?.let { afterRead = null; it() }
                return calls
            }
        },
        stationPlaces = { stop ->
            entranceReads++
            entrancesGate?.await()
            ticks += entrancesTake
            entranceClock()
            if (entrancesFail) throw TflException.Offline(null)
            entrancesAt[stop] ?: app.stopdash.domain.StationPlaces()
        },
        lineSequence = { lineId ->
            sequencesRead += lineId
            sequenceClock(lineId)
            ticks += sequenceTakesFor[lineId] ?: sequenceTakes
            if (routeFails) throw TflException.Offline(null)
            sequences[lineId]
        },
        // As the cards find them: another line only where the departures given list a train of it.
        rideLines = { _, ride, given ->
            ridesGiven += given
            RideLinesNow(
                (offered[ride.lineId] ?: listOf(ride)).filter { it == ride || given.values.flatten().any { train -> train.lineId == it.lineId } },
                uncheckedLines = ridesUnchecked,
            )
        },
        hidden = { hiddenLines },
        stations = {
            indexThreads.note()
            app.stopdash.domain.StationIndex(
                stopPositions.map { (id, at) -> app.stopdash.domain.IndexedStation(id, id, latitude = at.latitude, longitude = at.longitude) },
            )
        },
        stopPoles = { area ->
            poleReads++
            if (polesFail) throw TflException.Offline(null)
            polesAt[area].orEmpty()
        },
        clock = {
            clockRead()
            now
        },
        elapsed = { ticks },
        io = dispatcher,
        compute = compute,
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
        disruptions = { _, _, directions, also ->
            directionsGiven += directions
            alsoLinesGiven += also
            duringCheck()
            if (knownFails) throw TflException.Offline(null)
            RouteDisruption.Found(
                known,
                (knownStands.values + now.plus(Duration.ofMinutes(5))).min().takeIf { !((known as? Watched<*>)?.quietlyEmpty ?: known.isEmpty()) },
                knownStands,
                notes = knownNotes,
                notesUntil = now.plus(Duration.ofMinutes(5)).takeIf { !((knownNotes as? Watched<*>)?.quietlyEmpty ?: knownNotes.isEmpty()) },
                lines = knownLines,
            )
        },
        onDisruption = { _, signals, how, until ->
            postThreads.note()
            disruptionAlerts += "${how.name.lowercase()} ${signals.joinToString(",") { it.key }}"
            routesReadAtPost += sequencesRead.toList()
            disruptionUntil += until
            if (how == ActiveTripTracker.DisruptionPost.KEEP) disruptionShowing && disruptionPosts else disruptionPosts
        },
        onDisruptionDone = { disruptionAlerts += "done" },
        disruptionsShown = { disruptionsShowing },
    ).also { current = it }

    // A ride, a walk between two named stations, and a ride on: the walk's ends placed only by the index.
    private val changing = TripRoute(
        listOf(
            ride.copy(toId = "940GZZ1", toName = "Hammersmith (H&C Line)"),
            TripLeg(TripLeg.WALKING, "", "", "940GZZ1", "Hammersmith (H&C Line)", "940GZZ2", "Hammersmith (Dist&Picc Line)", at(15), at(18)),
            ride.copy(fromId = "940GZZ2", fromName = "Hammersmith (Dist&Picc Line)", departure = at(20), arrival = at(30)),
        ),
    )

    @Test
    fun `a started trip keeps the changes on foot decided from the station index`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // About 156 m apart by the index: a change on foot, no step of its own, kept with the trip.
        stopPositions["940GZZ1"] = app.stopdash.domain.Coordinates(51.5, -0.12)
        stopPositions["940GZZ2"] = app.stopdash.domain.Coordinates(51.5 + 156.0 / 111_195.0, -0.12)
        tracker.start(changing, "C", readyAt = now)
        assertEquals(setOf(1), kept?.onFootChanges)
        assertFalse(Step(1) in app.stopdash.domain.OnTheWay.steps(checkNotNull(kept)))
        // Both directions: about 400 m apart, a walk the rider is followed on.
        tracker.end()
        stopPositions["940GZZ2"] = app.stopdash.domain.Coordinates(51.5 + 400.0 / 111_195.0, -0.12)
        tracker.start(changing, "C", readyAt = now)
        assertEquals(emptySet<Int>(), kept?.onFootChanges)
        assertTrue(Step(1) in app.stopdash.domain.OnTheWay.steps(checkNotNull(kept)))
    }

    @Test
    fun `the changes on foot are decided off the caller's thread`() {
        // AGENTS.md *Main-safe by default*: the station index is read on the worker, not the caller,
        // which is the main thread when the rider taps Start.
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            val tracker = tracker(worker)
            kotlinx.coroutines.runBlocking(caller) { tracker.start(changing, "C", readyAt = now) }
            assertEquals(listOf("worker"), indexThreads.threads())
            assertTrue(kept?.onFootChanges != null)
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a started trip keeps its destination as chosen`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val destinations = listOf(TripDestination.Stop("C"), TripDestination.Stop("C2"))
        val ids = mapOf("C" to "C", "C2" to "C2", "C2a" to "C2")
        tracker.start(route, "C", readyAt = now, destinations = destinations, destinationIds = ids)
        assertEquals(destinations, kept?.destinations)
        assertEquals(ids, kept?.destinationIds)
        // So does one started with a walk to its first stop first.
        tracker.end()
        tracker.start(route, "C", readyAt = now.plusSeconds(120), destinations = destinations, destinationIds = ids)
        assertEquals(destinations, kept?.destinations)
        assertEquals(ids, kept?.destinationIds)
    }

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
    fun `while something is known wrong ahead, the trip says where it would be planned again from`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertNull(tracker.replanFrom.value)
        known = listOf(line(0, 6, "Severe Delays"))
        tracker.refresh()
        // Waiting at A with no fix: the next stop ahead, as the route names it, with where the trip goes worked
        // out with it, so planning again only reads it.
        assertEquals(app.stopdash.domain.ReplanOrigin.Stop("A", "A", to = app.stopdash.domain.ToChoice(stopId = "C", name = "C")), tracker.replanFrom.value)
        known = emptyList()
        tracker.refresh()
        assertNull(tracker.replanFrom.value)
    }

    @Test
    fun `with no fix to place the rider, where the trip is planned again from asks for no route`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(18))
        val blue = TripLeg("tube", "blue", "Blue", "D", "D", "F", "F", at(20), at(30), path = listOf("E", "F"))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn, blue)), "F", readyAt = now)
        known = listOf(line(2, 6, "Severe Delays"))
        tracker.refresh()
        assertEquals("A", tracker.replanFrom.value?.id)
        // Only a fix would have the later ride's line placed (Codex on #479).
        assertFalse("blue" in sequencesRead)
    }

    @Test
    fun `where a trip is planned again from isn't picked by a fix once precise location is taken away`() = runTest {
        // As below, but with precise location turned off before the refresh: the fix near E isn't used
        // to pick where to plan again from (Codex, #542).
        val tracker = tracker(StandardTestDispatcher(testScheduler), preciseAllowed = { false })
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(18))
        val blue = TripLeg("tube", "blue", "Blue", "D", "D", "F", "F", at(20), at(30), path = listOf("E", "F"))
        sequences["red"] = redLine
        sequences["blue"] = blueLine
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn, blue)), "F", readyAt = now)
        known = listOf(line(2, 6, "Severe Delays"))
        tracker.refresh(fixAt(51.61))
        assertEquals("A", tracker.replanFrom.value?.id)
    }

    @Test
    fun `precise location taken away while the routes are read stops a fix picking where to plan again from`() = runTest {
        // Turned off as the later ride's route is read for the disruption: the fix near E no longer picks
        // where the trip is planned again from (Codex, #542).
        var precise = true
        // Only replanning reads the later ride's line.
        sequenceClock = { if (it == "blue") precise = false }
        val tracker = tracker(StandardTestDispatcher(testScheduler), preciseAllowed = { precise })
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(18))
        val blue = TripLeg("tube", "blue", "Blue", "D", "D", "F", "F", at(20), at(30), path = listOf("E", "F"))
        sequences["red"] = redLine
        sequences["blue"] = blueLine
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn, blue)), "F", readyAt = now)
        known = listOf(line(2, 6, "Severe Delays"))
        tracker.refresh(fixAt(51.61))
        assertNotEquals("E", tracker.replanFrom.value?.id)
    }

    @Test
    fun `a disruption is posted before the routes placing where it's planned again from are read`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(18))
        val blue = TripLeg("tube", "blue", "Blue", "D", "D", "F", "F", at(20), at(30), path = listOf("E", "F"))
        sequences["red"] = redLine
        sequences["blue"] = blueLine
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn, blue)), "F", readyAt = now)
        known = listOf(line(2, 6, "Severe Delays"))
        tracker.refresh(fixAt(51.61))
        assertEquals("E", tracker.replanFrom.value?.id)
        // Out before the later ride's route was read for it (Codex on #479).
        assertFalse(routesReadAtPost.single().contains("blue"))
    }

    @Test
    fun `a fix that grew too old while the routes were read places no one`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(18))
        val blue = TripLeg("tube", "blue", "Blue", "D", "D", "F", "F", at(20), at(30), path = listOf("E", "F"))
        sequences["red"] = redLine
        sequences["blue"] = blueLine
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn, blue)), "F", readyAt = now)
        known = listOf(line(2, 6, "Severe Delays"))
        // 1 s old when given, then 10 s reading the blue line's route: too old to say where the rider is
        // (Codex on #479), so the next stop ahead.
        sequenceTakesFor["blue"] = 10_000
        tracker.refresh(fixAt(51.61))
        assertEquals("A", tracker.replanFrom.value?.id)
    }

    @Test
    fun `a fix that waited for another refresh is aged once for where the trip is planned again from`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(18))
        val blue = TripLeg("tube", "blue", "Blue", "D", "D", "F", "F", at(20), at(30), path = listOf("E", "F"))
        sequences["red"] = redLine
        sequences["blue"] = blueLine
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(TripRoute(listOf(ride, walkOn, blue)), "F", readyAt = now)
        known = listOf(line(0, 6, "Severe Delays"))
        tracker.refresh()
        assertEquals("A", tracker.replanFrom.value?.id)
        // One refresh holds the lock for 5 s; the next brings a fix 1 s old near E and takes 1 s itself:
        // 7 s old when used, not counted twice (Codex on #479).
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        gate = slow
        vehicleTakes = 1_000
        val first = backgroundScope.launch { tracker.refresh() }
        runCurrent()
        val second = backgroundScope.launch { tracker.refresh(fixAt(51.61)) }
        runCurrent()
        ticks += 4_000
        gate = null
        slow.complete(Unit)
        first.join()
        second.join()
        assertEquals(app.stopdash.domain.ReplanOrigin.Stop("E", "E"), tracker.replanFrom.value?.copy(to = app.stopdash.domain.ToChoice.NONE))
    }

    @Test
    fun `a route planned again takes the place of the trip on the way`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        val other = TripRoute(listOf(ride.copy(fromId = "B", fromName = "B", path = listOf("C"))))
        tracker.launchStart(this, other, "C", readyAt = now, destinationStopId = "C", replacing = true).join()
        assertEquals(other, tracker.trip.value?.route)
        assertEquals("C", kept?.destinationStopId)
        // Without replacing, one trip at a time: the one on the way stays.
        tracker.launchStart(this, route, "C", readyAt = now).join()
        assertEquals(other, tracker.trip.value?.route)
    }

    @Test
    fun `a route planned again replaces a kept trip not read yet`() = runTest {
        // A restart left the old trip on the device only (Codex on #479).
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        var ended = 0
        val other = TripRoute(listOf(ride.copy(fromId = "B", fromName = "B", path = listOf("C"))))
        tracker.launchStart(this, other, "C", readyAt = now, replacing = true, onEnded = { ended++ }).join()
        assertEquals(other, tracker.trip.value?.route)
        assertEquals(other, kept?.route)
        assertEquals(1, ended)
    }

    @Test
    fun `a trip that can't be ended isn't replaced, and its alert stays`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.restore()
        saves = false
        var ended = 0
        val other = TripRoute(listOf(ride.copy(fromId = "B", fromName = "B", path = listOf("C"))))
        tracker.launchStart(this, other, "C", readyAt = now, replacing = true, onEnded = { ended++ }).join()
        assertEquals(saved, tracker.trip.value)
        assertTrue(tracker.endFailed.value)
        assertEquals(0, ended)
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
        // The trip's screen shows what the alert says (maintainer, 2026-10-01), no longer than its evidence stands.
        val shown = tracker.routeDisruptions.value!!
        assertEquals(listOf(severe), shown.at(shown.until.minusSeconds(1)))
        assertEquals(emptyList<RouteDisruption.Signal>(), shown.at(shown.until))
        // Kept on the trip as heard, so a restart doesn't sound it again.
        assertEquals(setOf(severe.key), kept?.disruptionsHeard)
        known = emptyList()
        tracker.refresh()
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "keep ${severe.key}", "done"), disruptionAlerts)
        assertNull(tracker.routeDisruptions.value)
    }

    @Test
    fun `a disruption card's expiry holds when the wall clock is set back`() {
        // Kept in the steady frame: an hour set back doesn't keep it up an hour longer (Codex, PR #453).
        var offset = Duration.ZERO
        SteadyClock.source = object : SteadyClock.Source {
            override val frame: SteadyClock.Frame? = null
            override fun offset(): Duration = offset
        }
        try {
            val signal = line(0, 6, "Severe Delays")
            val wall = Instant.parse("2026-09-26T08:00:00Z")
            val known = ActiveTripTracker.KnownDisruptions(listOf(signal), SteadyClock.stamp(wall.plusSeconds(75)))
            assertEquals(listOf(signal), known.at(wall.plusSeconds(60)))
            // Two minutes on, the clock set back an hour: past its evidence, whatever the wall says.
            offset = Duration.ofHours(1)
            assertEquals(emptyList<RouteDisruption.Signal>(), known.at(wall.plusSeconds(120).minus(Duration.ofHours(1))))
        } finally {
            SteadyClock.source = null
        }
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
        assertNull(tracker.routeDisruptions.value)
        knownFails = false
        tracker.refresh()
        assertEquals(listOf("new ${severe.key}", "done"), disruptionAlerts)
        // Known again, it's back on the trip's screen, though not sounded again.
        assertEquals(listOf(severe), tracker.routeDisruptions.value?.signals)
        assertTrue(logged.any { it.startsWith("on the way: disruption check failed") })
    }

    @Test
    fun `the lines' statuses a check found are kept, with the next board's lines asked too`() = runTest {
        // A train tapped on the board opens its line's page with a status (maintainer, 2026-10-06): the
        // board's lines go into the same check, and what it found is kept, whole.
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // A bus at the stop too: the board shows the ride's mode alone, so it isn't asked about (Codex, #627).
        departures["A"] = listOf(
            train("3", 8), train("4", 9).copy(lineId = "blue", lineName = "Blue"),
            train("5", 10).copy(lineId = "bus1", lineName = "1", mode = "bus"),
        )
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        assertNull("none before a check", tracker.lineChecks.value)
        val good = LineStatus("blue", 10, "Good Service")
        knownLines = RouteDisruption.LinesChecked(setOf("red", "blue"), mapOf("blue" to good), now)
        // While the check is out, it says which lines it's asking about (Codex, #627).
        var askingSeen: Set<String>? = null
        duringCheck = { askingSeen = tracker.lineChecks.value?.asking }
        tracker.refresh()
        assertEquals(setOf("red", "blue"), askingSeen)
        assertEquals(setOf("red", "blue"), alsoLinesGiven.last().toSet())
        // In, it asks nothing more.
        assertEquals(knownLines, tracker.lineChecks.value)
        assertNull(tracker.lineChecks.value?.asking)
        // The next one out keeps what the last found beside what it asks.
        var heldSeen: RouteDisruption.LinesChecked? = null
        duringCheck = { heldSeen = tracker.lineChecks.value }
        tracker.refresh()
        assertEquals(knownLines?.copy(asking = setOf("red", "blue")), heldSeen)
        duringCheck = {}
        // A check that failed has every line it would have asked about, with no status.
        knownFails = true
        tracker.refresh()
        val failed = tracker.lineChecks.value
        assertEquals(setOf("red", "blue"), failed?.asked)
        assertTrue(failed?.statuses.orEmpty().isEmpty())
        assertNull(failed?.at)
        assertNull(failed?.asking)
        // The trip over, nothing is kept.
        knownFails = false
        tracker.end()
        assertNull(tracker.lineChecks.value)
    }

    @Test
    fun `a board line the board no longer lists isn't followed from the moment the board is read`() = runTest {
        // The board read again without the Blue line: its page no longer takes Blue's last status as
        // current, even while this refresh is still on its way to the check that leaves it out (Codex, #627).
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8), train("4", 9).copy(lineId = "blue", lineName = "Blue"))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        knownLines = RouteDisruption.LinesChecked(setOf("red", "blue"), mapOf("blue" to LineStatus("blue", 10, "Good Service")), now)
        tracker.refresh()
        val before = boardLineStatus(tracker.lineChecks.value, "blue", now)
        assertFalse("current while the board lists it", before.unknown || before.checking)
        departures["A"] = listOf(train("3", 8))
        // Read once the new board is in, as the train's calls are, before the check goes out.
        var between: RouteDisruption.LinesChecked? = null
        afterRead = { between = tracker.lineChecks.value }
        tracker.refresh()
        assertNull("no check out yet", between?.asking)
        assertEquals(setOf("red"), between?.following)
        val blue = boardLineStatus(between, "blue", now)
        assertTrue("no longer current", blue.unknown)
        assertFalse(blue.checking)
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
        // The alert went, but what's known still stands on the trip's screen.
        assertEquals(listOf(severe), tracker.routeDisruptions.value?.signals)
        tracker.end()
        assertNull(tracker.routeDisruptions.value)
    }

    @Test
    fun `a dismissed route disruption leaves the screen and the alert, and only something new comes back`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        disruptionAlerts.clear()
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
        tracker.refresh()
        tracker.dismissDisruptions(listOf(severe))
        assertNull(tracker.routeDisruptions.value)
        assertNull(tracker.replanFrom.value)
        assertEquals(setOf(severe.dismissKey), kept?.disruptionsDismissed)
        tracker.refresh()
        assertNull(tracker.routeDisruptions.value)
        // Worse than what was dismissed: shown and heard.
        val suspended = line(0, 3, "Part Suspended")
        known = listOf(severe, suspended)
        tracker.refresh()
        assertEquals(listOf(suspended), tracker.routeDisruptions.value?.signals)
        assertEquals(listOf("new ${severe.key}", "done", "new ${suspended.key}"), disruptionAlerts)
    }

    @Test
    fun `something found after the screen showed what was dismissed keeps the alert up, as that alone`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        disruptionAlerts.clear()
        val severe = line(0, 6, "Severe Delays")
        val suspended = line(0, 3, "Part Suspended")
        // The screen showed only the severe delays; a check found the suspension before the tap landed (Codex on #519).
        known = listOf(severe, suspended)
        tracker.refresh()
        disruptionAlerts.clear()
        tracker.dismissDisruptions(listOf(severe))
        assertEquals(listOf("keep ${suspended.key}"), disruptionAlerts)
        assertEquals(listOf(suspended), tracker.routeDisruptions.value?.signals)
        // Its card worked out with it, for the screen only to draw (Codex on #519).
        assertEquals(listOf(suspended), tracker.routeDisruptions.value?.cards)
    }

    @Test
    fun `what's left after Keep going stands as long as its own evidence`() = runTest {
        // A stop notice ending in seconds bounded the alert; let go of, it no longer cuts the line's
        // alert short, which would time out before the next check and never be heard again (Codex on #519).
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        val severe = line(0, 6, "Severe Delays")
        val closed = RouteDisruption.Signal.Stop(0, "C", "C", closed = true)
        known = listOf(severe, closed)
        knownStands = mapOf(closed.key to now.plusSeconds(10), severe.key to now.plus(Duration.ofMinutes(5)))
        tracker.refresh()
        assertEquals(now.plusSeconds(10), disruptionUntil.last())
        tracker.dismissDisruptions(listOf(closed))
        // The line's alert, kept up on its own: as long as the trip's answer stands, not ten seconds.
        assertEquals(now.plus(ActiveTripTracker.CURRENT_FOR), disruptionUntil.last())
        // And a check after stands by it too, the notice it let go of not cutting it short.
        tracker.refresh()
        assertEquals(now.plus(ActiveTripTracker.CURRENT_FOR), disruptionUntil.last())
    }

    @Test
    fun `a dismissal that can't be saved isn't made, and says so`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
        tracker.refresh()
        disruptionAlerts.clear()
        saves = false
        tracker.dismissDisruptions(listOf(severe))
        // Still shown and alerted: a restart would bring back the trip without it (Codex on #519).
        assertEquals(listOf(severe), tracker.routeDisruptions.value?.signals)
        assertEquals(emptyList<String>(), disruptionAlerts)
        assertTrue(tracker.notKept.value)
        assertEquals(emptySet<String>(), tracker.trip.value?.disruptionsDismissed)
    }

    @Test
    fun `Keep going after arriving saves nothing back`() = runTest {
        // An arrival not yet forgotten on the device: a tap queued behind it would write the trip back,
        // to come back after a restart (Codex on #519).
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        val severe = line(0, 6, "Severe Delays")
        known = listOf(severe)
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
        saves = true
        saveThreads.clear()
        tracker.dismissDisruptions(listOf(severe))
        assertTrue(saveThreads.threads().isEmpty())
        assertEquals(emptySet<String>(), tracker.trip.value?.disruptionsDismissed)
    }

    @Test
    fun `a dismissed alert placed on the leg later stays dismissed`() = runTest {
        // Keep going before the alert's detail landed: placing it later is no new alert (Codex on #519).
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        val closed = line(0, 5, "Part Closure")
        known = listOf(closed)
        tracker.refresh()
        tracker.dismissDisruptions(listOf(closed))
        disruptionAlerts.clear()
        known = listOf(closed.copy(placed = true, tier = RouteDisruption.Tier.HIGH))
        tracker.refresh()
        assertNull(tracker.routeDisruptions.value)
        assertEquals(emptyList<String>(), disruptionAlerts)
    }

    @Test
    fun `a dismissed station note leaves the screen for the trip, and comes back once it changes`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        val lift = RouteDisruption.StationNote(0, "C", "C", "Lift out of service.")
        val exit = RouteDisruption.StationNote(0, "B", "B", "Exit closed.")
        knownNotes = listOf(lift, exit)
        tracker.refresh()
        assertEquals(listOf(lift, exit), tracker.stationNotes.value?.notes)
        disruptionAlerts.clear()
        tracker.dismissNote(lift)
        assertEquals(listOf(exit), tracker.stationNotes.value?.notes)
        assertEquals(lift.dismissKeys, kept?.disruptionsDismissed)
        // Never alerted, so dismissing one posts nothing.
        assertEquals(emptyList<String>(), disruptionAlerts)
        tracker.refresh()
        assertEquals(listOf(exit), tracker.stationNotes.value?.notes)
        // Only one left, dismissed: none.
        tracker.dismissNote(exit)
        assertNull(tracker.stationNotes.value)
        // TfL's words change: a new notice, shown.
        val changed = lift.copy(text = "Lift out of service until Friday.", listed = mapOf("Lift out of service until Friday." to setOf("C")))
        knownNotes = listOf(changed, exit)
        tracker.refresh()
        assertEquals(listOf(changed), tracker.stationNotes.value?.notes)
    }

    @Test
    fun `the notes left after a dismissal stand as long as their own evidence`() = runTest {
        // The note ending first let go of, the rest no longer go with it (Codex, #609).
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        val lift = RouteDisruption.StationNote(0, "C", "C", "Lift out of service.", until = now.plusSeconds(20))
        val exit = RouteDisruption.StationNote(0, "B", "B", "Exit closed.", until = now.plusSeconds(50))
        knownNotes = listOf(lift, exit)
        tracker.refresh()
        assertEquals(emptyList<RouteDisruption.StationNote>(), tracker.stationNotes.value?.at(now.plusSeconds(30)))
        tracker.dismissNote(lift)
        assertEquals(listOf(exit), tracker.stationNotes.value?.at(now.plusSeconds(30)))
        // And a check after, with the dismissed note still found, times the rest by their own.
        tracker.refresh()
        assertEquals(listOf(exit), tracker.stationNotes.value?.at(now.plusSeconds(30)))
    }

    @Test
    fun `a station note dismissal that can't be saved isn't made, and says so`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        val lift = RouteDisruption.StationNote(0, "C", "C", "Lift out of service.")
        knownNotes = listOf(lift)
        tracker.refresh()
        saves = false
        tracker.dismissNote(lift)
        assertTrue(tracker.notKept.value)
        assertEquals(listOf(lift), tracker.stationNotes.value?.notes)
        assertEquals(emptySet<String>(), tracker.trip.value?.disruptionsDismissed)
    }

    @Test
    fun `a station note is dismissed off the caller's thread`() {
        // AGENTS.md *Main thread*: the rider taps the × on the main thread; the key is worked out, the
        // trip saved and the notes left out on the worker.
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            val tracker = tracker(worker)
            departures["A"] = listOf(train("3", 8))
            trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
            val lift = RouteDisruption.StationNote(0, "C", "C", "Lift out of service.")
            val read = ThreadRecorder()
            knownNotes = Watched(listOf(lift), read)
            kotlinx.coroutines.runBlocking(caller) {
                tracker.start(route, "C", readyAt = now)
                tracker.refresh()
                saveThreads.clear()
                tracker.dismissNote(lift)
                tracker.refresh()
            }
            assertEquals(lift.dismissKeys, kept?.disruptionsDismissed)
            assertNull(tracker.stationNotes.value)
            assertTrue(saveThreads.threads().isNotEmpty() && saveThreads.threads().all { it == "worker" })
            assertTrue(read.threads().isNotEmpty())
            assertEquals(setOf("worker"), read.threads().toSet())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a dismissed route disruption stays dismissed across a restart`() = runTest {
        val severe = line(0, 6, "Severe Delays")
        val keptTrip = ActiveTrip(route, "C", startedAt = t0, disruptionsHeard = setOf(severe.key), disruptionsDismissed = setOf(severe.dismissKey))
        val tracker = tracker(StandardTestDispatcher(testScheduler)) { keptTrip }
        departures["A"] = listOf(train("3", 8))
        trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
        tracker.restore()
        known = listOf(severe)
        tracker.refresh()
        assertNull(tracker.routeDisruptions.value)
    }

    @Test
    fun `a route disruption is dismissed off the caller's thread`() {
        // AGENTS.md *Main thread*: the rider taps Keep going on the main thread; the keys are worked out
        // and the trip saved on the worker.
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            val tracker = tracker(worker)
            departures["A"] = listOf(train("3", 8))
            trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
            val severe = line(0, 6, "Severe Delays")
            // Lists that note each thread that reads them: what's known, and what the screen showed, are
            // only ever gone through on the worker (Codex on #519).
            val read = ThreadRecorder()
            known = Watched(listOf(severe), read)
            kotlinx.coroutines.runBlocking(caller) {
                tracker.start(route, "C", readyAt = now)
                tracker.refresh()
                saveThreads.clear()
                tracker.dismissDisruptions(Watched(listOf(severe), read))
                // And a refresh from the caller leaves it out, sorted out on the worker (Codex on #519).
                tracker.refresh()
            }
            assertEquals(setOf(severe.dismissKey), kept?.disruptionsDismissed)
            assertNull(tracker.routeDisruptions.value)
            assertTrue(saveThreads.threads().isNotEmpty() && saveThreads.threads().all { it == "worker" })
            assertTrue(read.threads().isNotEmpty())
            assertEquals(setOf("worker"), read.threads().toSet())
            // The alert, put together from every signal, is posted from the worker too (Codex on #519).
            assertTrue(postThreads.threads().isNotEmpty())
            assertEquals(setOf("worker"), postThreads.threads().toSet())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a route disruption dismissed with another left up is sorted out off the caller's thread`() {
        // AGENTS.md *Main thread*: Keep going on one of two; what's left, and whether a stop gone past is among
        // it, is worked out on the worker, not the caller (Codex, #635).
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            val tracker = tracker(worker)
            departures["A"] = listOf(train("3", 8))
            trains["3"] = listOf(call("A", 8), call("B", 11), call("C", 14))
            val severe = line(0, 6, "Severe Delays")
            val suspended = line(0, 3, "Part Suspended")
            val read = ThreadRecorder()
            known = Watched(listOf(severe, suspended), read)
            kotlinx.coroutines.runBlocking(caller) {
                tracker.start(route, "C", readyAt = now)
                tracker.refresh()
                read.clear()
                tracker.dismissDisruptions(Watched(listOf(severe), read))
            }
            assertEquals(listOf(suspended), tracker.routeDisruptions.value?.signals)
            assertTrue(read.threads().isNotEmpty())
            assertEquals(setOf("worker"), read.threads().toSet())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a change with no train predicted is joined with what else is known off the caller's thread`() {
        // AGENTS.md *Main thread*: the refresh runs on Main; joining the change's signal with the rest,
        // in a list that notes each thread reading it, is done on the worker (Codex on #519).
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            val tracker = tracker(worker)
            val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(16), at(18))
            val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(20), at(28), path = listOf("E"))
            departures["A"] = listOf(train("3", 6))
            trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 16))
            departures["D"] = emptyList()
            val read = ThreadRecorder()
            val severe = line(0, 6, "Severe Delays")
            known = Watched(listOf(severe), read)
            kotlinx.coroutines.runBlocking(caller) {
                tracker.start(TripRoute(listOf(ride, walkOn, second)), "E", readyAt = now)
                tracker.refresh()
                // On the train, then five minutes before boarding at D.
                now = at(7)
                trains["3"] = listOf(call("B", 9), call("C", 16))
                tracker.refresh()
                now = at(13)
                trains["3"] = listOf(call("C", 16))
                tracker.refresh()
            }
            val none = RouteDisruption.Signal.Unpredicted(2, "blue", "Blue", "D", "D")
            assertEquals(setOf(severe.key, none.key), tracker.routeDisruptions.value?.signals?.mapTo(HashSet()) { it.key })
            assertTrue(read.threads().isNotEmpty())
            assertEquals(setOf("worker"), read.threads().toSet())
        } finally {
            caller.close()
            worker.close()
        }
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
    fun `route disruption claims no missing train at a change while a line of the ride went unchecked`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(16), at(18))
        val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(20), at(28), path = listOf("E"))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 16))
        // D lists a green train, its route sending it to Y.
        departures["D"] = listOf(Departure("green", "Green", "outbound", "Y", null, at(19), "tube", vehicleId = "8"))
        sequences["green"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("D-Y", listOf("D", "Y"))), mapOf("D" to "D", "Y" to "Y"),
        )
        tracker.start(TripRoute(listOf(ride, walkOn, second)), "E", readyAt = now)
        tracker.refresh()
        disruptionAlerts.clear()
        // D lists no blue train, but green, listed there, couldn't be checked as one of the ride's lines: a
        // train of it may take the rider on, so nothing is known, and no missing train is claimed (Codex,
        // PR #459).
        now = at(13)
        trains["3"] = listOf(call("C", 16))
        ridesUnchecked = setOf("green")
        tracker.refresh()
        assertTrue("D" in boardStops)
        assertEquals(emptyList<String>(), disruptionAlerts)
        // A line unchecked with no train listed at D couldn't add one: it's said (Codex, PR #460).
        ridesUnchecked = setOf("purple")
        tracker.refresh()
        assertEquals(listOf("new ${RouteDisruption.Signal.Unpredicted(2, "blue", "Blue", "D", "D").key}"), disruptionAlerts)
    }

    @Test
    fun `waiting, no train found while a line of the ride went unchecked fails the refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // No red train on the board, but a blue one, and blue couldn't be checked as one of the ride's lines:
        // it may be theirs, so the refresh failed rather than finding no train (Codex, PR #459).
        ridesUnchecked = setOf("blue")
        departures["A"] = listOf(Departure("blue", "Blue", "outbound", "C", null, at(6), "tube", vehicleId = "4"))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertTrue(tracker.failed.value)
        // A line unchecked with no train on the board couldn't add one: no train found is just that (Codex,
        // PR #460).
        ridesUnchecked = setOf("purple")
        tracker.refresh()
        assertFalse(tracker.failed.value)
        // A red train found all the same is followed: what was checked stands.
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        tracker.refresh()
        assertFalse(tracker.failed.value)
        assertEquals("9", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `on board by position, a fix off their line while a line of the ride went unchecked fails the refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // 9 still to come, they're seen at B: on board by where they were seen.
        now = at(7)
        tracker.refresh(fixAt(51.51))
        val onBoard = TripProgress.Riding(ride, "C", 1, null, true, byPosition = true)
        assertEquals(onBoard, tracker.progress.value)
        // Seen a street off red's way, with another line of the ride unchecked: they may be on it, so the
        // refresh failed, their position kept (Codex, PR #459).
        // Any line unchecked counts here, listed or not: a line of the plan can be ridden with no train on the board.
        ridesUnchecked = setOf("purple")
        now = at(8)
        tracker.refresh(fixAt(51.51, -0.135))
        assertTrue(tracker.failed.value)
        assertEquals(onBoard, tracker.progress.value)
        // With every line checked, a fix that places them nowhere is no failure: their position stands.
        ridesUnchecked = emptySet()
        tracker.refresh(fixAt(51.51, -0.135))
        assertFalse(tracker.failed.value)
        assertEquals(onBoard, tracker.progress.value)
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
    @Test
    fun `a rider seen past where they got off is told, with where to plan again from, until they're back`() = runTest {
        // Off at B, walking east to W; seen a minute later at C, on north: on the train still (maintainer, 2026-10-06).
        sequences["red"] = redLine.copy(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ D", listOf("A", "B", "C", "D"))),
            stopNames = redLine.stopNames + ("D" to "D"),
            stopPositions = redLine.stopPositions + ("D" to (51.53 to -0.12)),
        )
        val toB = ride.copy(toId = "B", toName = "B", path = listOf("B"), arrival = at(10))
        val toW = TripLeg(TripLeg.WALKING, "", "", "B", "B", "W", "W", at(10), at(20), toAt = app.stopdash.domain.Coordinates(51.51, -0.105))
        val off = ActiveTrip(TripRoute(listOf(toB, toW)), "W", startedAt = t0, legIndex = 1, legStartedAt = at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { off })
        now = at(11)
        tracker.restore()
        tracker.refresh(fixAt(51.52))
        val past = checkNotNull(tracker.trip.value)
        assertEquals(Triple(0, "C", "C"), Triple(past.pastLeg, past.pastAtId, past.pastAtName))
        assertEquals(past, kept)
        assertTrue(disruptionAlerts.any { it == "new missed/0/B" })
        assertEquals(app.stopdash.domain.ReplanOrigin.Stop("C", "C"), tracker.replanFrom.value?.copy(to = app.stopdash.domain.ToChoice.NONE))
        // The log names the ride and a stop, never where the rider is.
        assertTrue(logged.contains("on the way: seen past the stop of ride 0, by stop C"))
        // Further on, at D: planned again from there, not from C behind them, and not sounded again (Codex, #635).
        now = at(12)
        tracker.refresh(fixAt(51.53))
        assertEquals("D", tracker.trip.value?.pastAtId)
        assertEquals(app.stopdash.domain.ReplanOrigin.Stop("D", "D"), tracker.replanFrom.value?.copy(to = app.stopdash.domain.ToChoice.NONE))
        assertEquals(1, disruptionAlerts.count { it.startsWith("new") })
        // Back at B: no longer.
        now = at(16)
        tracker.refresh(fixAt(51.51))
        assertEquals(-1, tracker.trip.value?.pastLeg)
        assertTrue(tracker.routeDisruptions.value?.signals.orEmpty().none { it is RouteDisruption.Signal.Missed })
    }

    @Test
    fun `back where the trip keeps the stop placed is seen before a slow route read ages the fix out`() = runTest {
        // Marked past B; the line's route takes 20 s to read, longer than a fix stays fresh. Back at B, where the
        // trip keeps it placed: let go of all the same (Codex, #635).
        sequences["red"] = redLine
        sequenceTakesFor["red"] = 20_000
        val toB = ride.copy(toId = "B", toName = "B", path = listOf("B"), arrival = at(10), toAt = app.stopdash.domain.Coordinates(51.51, -0.12))
        val toW = TripLeg(TripLeg.WALKING, "", "", "B", "B", "W", "W", at(10), at(20), toAt = app.stopdash.domain.Coordinates(51.51, -0.105))
        val off = ActiveTrip(
            TripRoute(listOf(toB, toW)), "W", startedAt = t0, legIndex = 1, legStartedAt = at(10),
            pastLeg = 0, pastAtId = "C", pastAtName = "C",
        )
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { off })
        now = at(12)
        tracker.restore()
        tracker.refresh(fixAt(51.51))
        assertEquals(-1, tracker.trip.value?.pastLeg)
    }

    @Test
    fun `at the next ride's platform is seen before a slow read of the gone-past line's route`() = runTest {
        // Marked past B; the red route takes 20 s to read. At N2, the platform the next (blue) line calls N by,
        // which the Planner didn't place: let go of all the same (Codex, #635).
        sequences["red"] = redLine
        sequenceTakesFor["red"] = 20_000
        sequences["blue"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("N ↔ X", listOf("N2", "X"))),
            stopNames = mapOf("N" to "N", "N2" to "N", "X" to "X"),
            stopPositions = mapOf("N2" to (51.515 to -0.11), "X" to (51.53 to -0.11)),
            stopHubs = mapOf("N" to "HUBN", "N2" to "HUBN"),
        )
        val toB = ride.copy(toId = "B", toName = "B", path = listOf("B"), arrival = at(10))
        val toN = TripLeg(TripLeg.WALKING, "", "", "B", "B", "N", "N", at(10), at(20))
        val onBlue = ride.copy(lineId = "blue", lineName = "Blue", fromId = "N", fromName = "N", toId = "X", toName = "X", path = listOf("X"), departure = at(22), arrival = at(30))
        val off = ActiveTrip(
            TripRoute(listOf(toB, toN, onBlue)), "X", startedAt = t0, legIndex = 1, legStartedAt = at(10),
            pastLeg = 0, pastAtId = "C", pastAtName = "C",
        )
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { off })
        now = at(15)
        tracker.restore()
        tracker.refresh(fixAt(51.515, -0.11))
        assertEquals(-1, tracker.trip.value?.pastLeg)
    }

    @Test
    fun `precise location taken away while the line's route is read keeps a fix from marking a stop gone past`() = runTest {
        // Off at B, a fix at C a minute later would mark it; precise location is turned off as the red route is
        // read, so it doesn't (Codex, #635).
        var precise = true
        sequenceClock = { if (it == "red") precise = false }
        sequences["red"] = redLine
        val toB = ride.copy(toId = "B", toName = "B", path = listOf("B"), arrival = at(10))
        val toW = TripLeg(TripLeg.WALKING, "", "", "B", "B", "W", "W", at(10), at(20), toAt = app.stopdash.domain.Coordinates(51.51, -0.105))
        val off = ActiveTrip(TripRoute(listOf(toB, toW)), "W", startedAt = t0, legIndex = 1, legStartedAt = at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler), preciseAllowed = { precise }, load = { off })
        now = at(11)
        tracker.restore()
        tracker.refresh(fixAt(51.52))
        assertEquals(-1, tracker.trip.value?.pastLeg)
    }

    @Test
    fun `a stop gone past and kept going from isn't where another alert plans again from`() = runTest {
        // Seen past B at C, then Keep going: with another alert up, plan again isn't from C (Codex, #635).
        sequences["red"] = redLine
        val toB = ride.copy(toId = "B", toName = "B", path = listOf("B"), arrival = at(10))
        val toW = TripLeg(TripLeg.WALKING, "", "", "B", "B", "W", "W", at(10), at(20), toAt = app.stopdash.domain.Coordinates(51.51, -0.105))
        val off = ActiveTrip(TripRoute(listOf(toB, toW)), "W", startedAt = t0, legIndex = 1, legStartedAt = at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { off })
        now = at(11)
        tracker.restore()
        tracker.refresh(fixAt(51.52))
        assertEquals(app.stopdash.domain.ReplanOrigin.Stop("C", "C"), tracker.replanFrom.value?.copy(to = app.stopdash.domain.ToChoice.NONE))
        // Kept going while another alert is up: plan again isn't from C at once, not only after a refresh.
        known = listOf(line(0, 6, "Severe Delays"))
        tracker.refresh(fixAt(51.52))
        tracker.dismissDisruptions(tracker.routeDisruptions.value?.signals.orEmpty().filterIsInstance<RouteDisruption.Signal.Missed>())
        assertTrue(tracker.routeDisruptions.value?.signals.orEmpty().isNotEmpty())
        assertNotEquals(app.stopdash.domain.ReplanOrigin.Stop("C", "C"), tracker.replanFrom.value?.copy(to = app.stopdash.domain.ToChoice.NONE))
        tracker.refresh(fixAt(51.52))
        assertTrue(tracker.routeDisruptions.value?.signals.orEmpty().isNotEmpty())
        assertNotEquals(app.stopdash.domain.ReplanOrigin.Stop("C", "C"), tracker.replanFrom.value?.copy(to = app.stopdash.domain.ToChoice.NONE))
        // Let go of, and not marked again on that ride by a fix still past it: no fixes asked for to follow it (Codex, #635).
        assertEquals("", tracker.trip.value?.pastAtId)
        assertFalse(app.stopdash.domain.OnTheWay.wantsFix(checkNotNull(tracker.trip.value), at(40)))
    }

    @Test
    fun `a stop gone past is let go of once the rider boards on, so going back can't bring it back`() = runTest {
        // Seen past B, then seen or said on the next ride: the mark goes with the next refresh, fix or none (Codex, #635).
        val toB = ride.copy(toId = "B", toName = "B", path = listOf("B"), arrival = at(10))
        val toW = TripLeg(TripLeg.WALKING, "", "", "B", "B", "W", "W", at(10), at(12))
        val onward = ride.copy(fromId = "W", fromName = "W", departure = at(13), arrival = at(20))
        val aboard = ActiveTrip(
            TripRoute(listOf(toB, toW, onward)), "C", startedAt = t0, legIndex = 2, legStartedAt = at(13), boarded = true, boardedAt = at(13),
            onBoardSeen = true, pastLeg = 0, pastAtId = "C", pastAtName = "C",
        )
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { aboard })
        now = at(14)
        tracker.restore()
        tracker.refresh()
        assertEquals(Triple(-1, "", ""), tracker.trip.value?.let { Triple(it.pastLeg, it.pastAtId, it.pastAtName) })
        assertEquals(-1, kept?.pastLeg)
    }

    @Test
    fun `a stop gone past stands while boarding on is only taken from the next train's time`() = runTest {
        // Seen past B; the next train's time went by, so the trip took them as on it, though nothing saw or
        // said so: they may still be on the train they went past B on, so it stands (Codex, #635).
        val toB = ride.copy(toId = "B", toName = "B", path = listOf("B"), arrival = at(10))
        val toW = TripLeg(TripLeg.WALKING, "", "", "B", "B", "W", "W", at(10), at(12))
        val onward = ride.copy(fromId = "W", fromName = "W", departure = at(13), arrival = at(20))
        val taken = ActiveTrip(
            TripRoute(listOf(toB, toW, onward)), "C", startedAt = t0, legIndex = 2, legStartedAt = at(13), boarded = true, boardedAt = at(13),
            pastLeg = 0, pastAtId = "C", pastAtName = "C",
        )
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { taken })
        now = at(14)
        tracker.restore()
        tracker.refresh()
        assertEquals(0, tracker.trip.value?.pastLeg)
    }

    // Synthetic stops, well clear of the red line.
    private val blueLine = app.stopdash.domain.LineSequence(
        routes = listOf(app.stopdash.domain.LineRoute("D ↔ F", listOf("D", "E", "F"))),
        stopNames = mapOf("D" to "D", "E" to "E", "F" to "F"),
        stopPositions = mapOf("D" to (51.6 to -0.12), "E" to (51.61 to -0.12), "F" to (51.62 to -0.12)),
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
        // 7's calls, read to place them, are the ride's own answer: its time stays should the next refresh fail (Codex, #611).
        assertEquals(now, tracker.answeredAt.value)
        assertEquals(tracker.trip.value, kept)
        assertTrue(logged.none { "51." in it })
    }

    @Test
    fun `a rider seen down the line while still on the walk to the train is on it, not still walking`() = runTest {
        // Walking to A, placed, for two minutes; no fix ever catches them there (underground), and a
        // minute in they're seen at B, 1.1 km on: on a train, not on foot.
        val placedRide = ride.copy(fromAt = app.stopdash.domain.Coordinates(51.5, -0.12))
        val toA = TripLeg(TripLeg.WALKING, "", "", "", "", "A", "A", at(3), at(5))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        now = at(3)
        tracker.start(TripRoute(listOf(toA, placedRide)), "C", readyAt = now)
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Walking)
        now = at(4)
        // Within walking reach of A: still walking.
        tracker.refresh(fixAt(51.502))
        assertTrue(tracker.progress.value is TripProgress.Walking)
        tracker.refresh(fixAt(51.51))
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals(true, tracker.trip.value?.boarded)
        assertTrue(tracker.progress.value is TripProgress.Riding)
        assertEquals(tracker.trip.value, kept)
        assertTrue(logged.none { "51." in it })
    }

    @Test
    fun `a train that left with no calls to place it may be theirs, so none on the board ahead is named`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // 7 has left A, its calls empty in a race with TfL's predictions; B's board, in the same race,
        // lists only 8, a later train that came and went between refreshes. 7 may be theirs, so 8, a
        // lone match, isn't taken for it (Codex, PR #462).
        departures["A"] = listOf(train("9", 8))
        trains["7"] = emptyList()
        departures["B"] = listOf(train("8", 9))
        trains["8"] = listOf(call("B", 9), call("C", 13))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        assertFalse(tracker.failed.value)
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
    fun `seen between stops with two trains that left calling next at the same stop, neither is named`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("8", 7), train("9", 10))
        trains["8"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // 7 and 8 have both left A, and 8 has caught 7 up: both are between B and C, due at C a minute
        // apart. The rider, seen between them too, may be on either: 8, the newer, isn't taken for theirs.
        departures["A"] = listOf(train("9", 10))
        trains["7"] = listOf(call("C", 11))
        trains["8"] = listOf(call("C", 12))
        now = at(9)
        tracker.refresh(fixAt(51.515))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        assertEquals(listOf("8", "7"), asked.takeLast(2))
        assertTrue(logged.any { it.startsWith("on the way: seen along the ride between stops with two trains that left on line red") })
        assertFalse(tracker.failed.value)
    }

    @Test
    fun `seen between stops, an older train of the line at the same spot is found past another line's`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        sequences["blue"] = redLine
        offered["red"] = listOf(ride, blueRide)
        val blue = Departure("blue", "Blue", "outbound", "C", null, at(6), "tube", vehicleId = "P")
        departures["A"] = listOf(train("7", 5), blue, train("8", 7), train("9", 10))
        trains["8"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Red 7, blue P and red 8 have left A, in that order. Red 8 has caught red 7 up between B and C;
        // blue P is between them in time, calling next at C too (Codex, PR #465).
        departures["A"] = listOf(train("9", 10))
        trains["7"] = listOf(call("C", 11))
        trains["blue/P"] = listOf(call("C", 12))
        trains["8"] = listOf(call("C", 12))
        now = at(9)
        asked.clear()
        tracker.refresh(fixAt(51.515))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        // Red 7 was asked after, past blue P, which needn't be.
        assertTrue("7" in asked)
        assertFalse("P" in asked)
    }

    @Test
    fun `seen between stops with two trains of another line at the same spot, they're on board on that line`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        sequences["blue"] = redLine
        offered["red"] = listOf(ride, blueRide)
        fun blue(vehicle: String, minutes: Long) = Departure("blue", "Blue", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)
        departures["A"] = listOf(blue("7", 5), blue("8", 7), train("9", 10))
        trains["9"] = listOf(call("A", 10), call("B", 12), call("C", 16))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Blue 7 and 8 have left A and are both between B and C; red shares their way. Neither is named,
        // but either is blue, so the rider is counted on blue's stops, not red's (Codex, PR #465).
        departures["A"] = listOf(train("9", 10))
        trains["blue/7"] = listOf(call("C", 11))
        trains["blue/8"] = listOf(call("C", 12))
        now = at(9)
        tracker.refresh(fixAt(51.515))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertEquals(blueRide, tracker.trip.value?.vehicleLeg)
    }

    @Test
    fun `seen between stops, an older fast train calling next further on may still be short of them`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Red also runs fast, A to C without calling at B.
        sequences["red"] = redLine.copy(routes = redLine.routes + app.stopdash.domain.LineRoute("A ↔ C fast", listOf("A", "C")))
        departures["A"] = listOf(train("7", 5), train("8", 7), train("9", 10))
        trains["8"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen between A and B: 8 calls next at B; 7, fast, calls next at C, and may be between A and B too, so
        // neither is named (Codex, PR #465).
        departures["A"] = listOf(train("9", 10))
        trains["7"] = listOf(call("C", 10))
        trains["8"] = listOf(call("B", 9), call("C", 13))
        now = at(8)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
    }

    @Test
    fun `seen between stops, an older train that can't be looked up leaves the newer unnamed, and it's said`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("8", 7), train("9", 10))
        trains["8"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("9", 10))
        trains["8"] = listOf(call("C", 12))
        // 7 may be at the same spot as 8, and can't be asked after: neither is named, and the refresh failed
        // (Codex, PR #465).
        failingFor += "7"
        now = at(9)
        tracker.refresh(fixAt(51.515))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertTrue(tracker.failed.value)
        // So too with no calls for 7, in a race with TfL's predictions, though nothing failed.
        failingFor.clear()
        trains["7"] = emptyList()
        tracker.refresh(fixAt(51.516))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertFalse(tracker.failed.value)
        // Told apart, 7 further on, past C: 8 is theirs.
        trains["7"] = listOf(call("D", 12))
        tracker.refresh(fixAt(51.517))
        assertEquals("8", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `seen between stops, the newest train past them is theirs when an older one is further on`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("8", 7), train("9", 10))
        trains["8"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen between A and B: 8 calls next at B, 7 is past it and calls next at C, ahead of them.
        departures["A"] = listOf(train("9", 10))
        trains["7"] = listOf(call("C", 10))
        trains["8"] = listOf(call("B", 9), call("C", 13))
        now = at(8)
        tracker.refresh(fixAt(51.505))
        assertEquals("8", tracker.trip.value?.vehicleId)
        assertTrue(tracker.trip.value?.boarded == true)
        assertEquals("B", (tracker.progress.value as TripProgress.Riding).nextStop)
    }

    @Test
    fun `seen between stops, an older train at the same spot is found past one of its line that's ahead`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("6", 5), train("7", 6), train("8", 7), train("9", 10))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // 6, 7 and 8 left A in that order. 7 overtook 6 and is past C; 8 has caught 6 up between B and C. The
        // rider, seen there, may be on 6 or 8, so neither is named (Codex, PR #465).
        departures["A"] = listOf(train("9", 10))
        trains["6"] = listOf(call("C", 12))
        trains["7"] = listOf(call("D", 11))
        trains["8"] = listOf(call("C", 12))
        now = at(9)
        asked.clear()
        tracker.refresh(fixAt(51.515))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertTrue("6" in asked)
    }

    @Test
    fun `seen between stops, with more older trains than are looked up, none is named`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("2", 2), train("3", 3), train("4", 4), train("5", 5), train("8", 7), train("9", 10))
        now = at(1)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Four trains left before 8, any of which may be at its spot: more than are asked after, so none is.
        departures["A"] = listOf(train("9", 10))
        listOf("2", "3", "4", "5").forEach { trains[it] = listOf(call("D", 9)) }
        trains["8"] = listOf(call("C", 12))
        now = at(9)
        asked.clear()
        tracker.refresh(fixAt(51.515))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertEquals(listOf("8"), asked)
    }

    @Test
    fun `seen between stops, an older train that left before they could be at the stop isn't at the same spot`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("4", 4), train("7", 7), train("9", 10))
        trains["7"] = listOf(call("A", 7), call("B", 9), call("C", 13))
        now = at(4)
        // Walking to A until 6, the ride's board read meanwhile: 4, there now, left before they could be.
        tracker.start(route, "C", readyAt = at(6))
        tracker.refresh()
        now = at(6)
        tracker.refresh()
        assertEquals("7", tracker.trip.value?.vehicleId)
        // 7 has caught 4 up between B and C, both calling next at C: only 7 can be theirs (Codex, PR #465).
        departures["A"] = listOf(train("9", 10))
        trains["4"] = listOf(call("C", 11))
        trains["7"] = listOf(call("C", 12))
        now = at(9)
        asked.clear()
        tracker.refresh(fixAt(51.515))
        assertEquals("7", tracker.trip.value?.vehicleId)
        assertEquals("C", (tracker.progress.value as TripProgress.Riding).nextStop)
        assertFalse("4" in asked)
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
        // Seen at B: neither is theirs, so they're on board by where they were seen, on no train named
        // (maintainer, 2026-10-01).
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        assertEquals(listOf("9"), asked)
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
    fun `seen along another of the ride's lines with none of its trains found, they're on board by where they were seen on its stops`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        sequences["purple"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "X", "C"))),
            stopNames = mapOf("A" to "A", "X" to "X", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "X" to (51.51 to -0.135), "C" to (51.52 to -0.12)),
        )
        offered["red"] = listOf(ride, purpleRide)
        // Purple's next train P is still listed, due later: none of purple's has left that could be theirs.
        departures["A"] = listOf(Departure("purple", "Purple", "outbound", "C", null, at(9), "tube", vehicleId = "P"), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("9", tracker.trip.value?.vehicleId)
        // Seen at X, purple's stop a street west of red's way: on board by where they were seen, on
        // purple, counted on its stops (Codex, PR #449), rather than left waiting for 9.
        now = at(7)
        tracker.refresh(fixAt(51.51, -0.135))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(purpleRide, tracker.trip.value?.vehicleLeg)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        assertTrue(logged.any { it.startsWith("on the way: seen along the ride") && "purple" in it })
        // Its disruptions are checked as purple's: red's line, and the way red's 9 was going, aren't
        // the ride they're on (Codex, PR #459).
        assertEquals(listOf("purple"), RouteDisruption.comingLines(tracker.trip.value!!))
        assertEquals(emptyMap<Int, String>(), directionsGiven.last())
        // A refresh with no fix keeps them there, on purple, never on 9.
        tracker.refresh()
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(purpleRide, tracker.trip.value?.vehicleLeg)
        // Seen where they get off: the ride is done.
        now = at(10)
        tracker.refresh(fixAt(51.52))
        assertEquals(TripProgress.Arrived, tracker.progress.value)
    }

    @Test
    fun `on board by position across a restart, seen along a third of the ride's lines, they're on that one`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        sequences["purple"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "X", "C"))),
            stopNames = mapOf("A" to "A", "X" to "X", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "X" to (51.51 to -0.135), "C" to (51.52 to -0.12)),
        )
        // Orange runs the ride by Z, a street east of red's way as purple's X is west.
        val orangeRide = ride.copy(lineId = "orange", lineName = "Orange", path = listOf("Z", "C"))
        sequences["orange"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "Z", "C"))),
            stopNames = mapOf("A" to "A", "Z" to "Z", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "Z" to (51.51 to -0.105), "C" to (51.52 to -0.12)),
        )
        offered["red"] = listOf(ride, purpleRide, orangeRide)
        departures["A"] = listOf(
            Departure("purple", "Purple", "outbound", "C", null, at(9), "tube", vehicleId = "P"),
            Departure("orange", "Orange", "outbound", "C", null, at(9), "tube", vehicleId = "O"),
            train("9", 8),
        )
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        tracker.refresh(fixAt(51.51, -0.135))
        assertEquals(purpleRide, tracker.trip.value?.vehicleLeg)
        // The process dies: what it saw of the board goes with it, and the board isn't read on board.
        // Seen at Z, along orange: the stop's board is read for the lines it lists, so they're on
        // orange, not left counted on purple (Codex, PR #459).
        val restarted = tracker(StandardTestDispatcher(testScheduler), load = { kept })
        restarted.restore()
        // TfL down for that read: the other lines go unchecked, so the refresh says it failed, and
        // they stay counted on purple rather than be moved on a guess (Codex, PR #459).
        boardFails = true
        now = at(8)
        restarted.refresh(fixAt(51.51, -0.105))
        assertTrue(restarted.failed.value)
        assertEquals(purpleRide, restarted.trip.value?.vehicleLeg)
        assertTrue(logged.any { it == "on the way: ride lines board lookup failed for line red: Offline" })
        boardFails = false
        // The board read lists no orange train just then: orange isn't offered, so they stay on purple,
        // the refresh fine. Not kept for the rest of the ride: the next fix reads it again, and orange
        // listed by then is offered (Codex, PR #459).
        departures["A"] = listOf(Departure("purple", "Purple", "outbound", "C", null, at(12), "tube", vehicleId = "Q"))
        restarted.refresh(fixAt(51.51, -0.105))
        assertFalse(restarted.failed.value)
        assertEquals(purpleRide, restarted.trip.value?.vehicleLeg)
        departures["A"] = listOf(Departure("orange", "Orange", "outbound", "C", null, at(12), "tube", vehicleId = "R"))
        restarted.refresh(fixAt(51.51, -0.105))
        assertFalse(restarted.failed.value)
        assertEquals(orangeRide, restarted.trip.value?.vehicleLeg)
        assertEquals("", restarted.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), restarted.progress.value)
        // Seen along orange, the line they're now counted on, the board isn't read.
        val reads = boardReads
        restarted.refresh(fixAt(51.51, -0.105))
        assertEquals(reads, boardReads)
        assertEquals(orangeRide, restarted.trip.value?.vehicleLeg)
    }

    @Test
    fun `on board by position, a line the board lists only once they're on is offered`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        sequences["purple"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "X", "C"))),
            stopNames = mapOf("A" to "A", "X" to "X", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "X" to (51.51 to -0.135), "C" to (51.52 to -0.12)),
        )
        val orangeRide = ride.copy(lineId = "orange", lineName = "Orange", path = listOf("Z", "C"))
        sequences["orange"] = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "Z", "C"))),
            stopNames = mapOf("A" to "A", "Z" to "Z", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "Z" to (51.51 to -0.105), "C" to (51.52 to -0.12)),
        )
        offered["red"] = listOf(ride, purpleRide, orangeRide)
        // No orange train on the board while they wait.
        departures["A"] = listOf(Departure("purple", "Purple", "outbound", "C", null, at(9), "tube", vehicleId = "P"), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        tracker.refresh(fixAt(51.51, -0.135))
        assertEquals(purpleRide, tracker.trip.value?.vehicleLeg)
        // An orange train is listed now. Seen at Z, along orange: the board is read as it is, not as it
        // was while they waited, so orange is offered and they're on it (Codex, PR #459).
        departures["A"] = listOf(Departure("orange", "Orange", "outbound", "C", null, at(12), "tube", vehicleId = "R"))
        now = at(8)
        tracker.refresh(fixAt(51.51, -0.105))
        assertFalse(tracker.failed.value)
        assertEquals(orangeRide, tracker.trip.value?.vehicleLeg)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
    }

    @Test
    fun `a train the boarding stop's board never listed is named from the board at the stop ahead`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("9", tracker.trip.value?.vehicleId)
        // 5 came and went between refreshes, never on A's board. The rider is seen short of B: B, the stop
        // ahead, lists 5 arriving, and 9 behind it, still to call at A.
        departures["B"] = listOf(train("5", 8), train("9", 10))
        trains["5"] = listOf(call("B", 8), call("C", 12))
        now = at(7)
        boardStops.clear()
        tracker.refresh(fixAt(51.505))
        // Read once, and 5 is theirs: 9, behind them, isn't (TODO, *A train the board never listed*).
        assertEquals(1, boardStops.count { it == "B" })
        assertEquals("5", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        assertFalse(tracker.failed.value)
        assertTrue(tracker.progress.value is TripProgress.Riding)
    }

    @Test
    fun `a train named from the board ahead doesn't take that stop's time for when it was at theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Named from B's board, due there at 8: not when it was at A, which isn't known.
        departures["B"] = listOf(train("5", 8), train("9", 10))
        trains["5"] = listOf(call("B", 8), call("C", 11), call("A", 12), call("B", 14))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("5", tracker.trip.value?.vehicleId)
        assertEquals(null, tracker.trip.value?.boardsAt)
        // A loop: due at C at 11, then round to A at 12. Past C, the call at A is its next lap, not the
        // one they boarded at, so they've got off (Codex, PR #462).
        trains["5"] = listOf(call("C", 11), call("A", 12), call("B", 14), call("C", 17))
        now = at(10)
        tracker.refresh()
        trains["5"] = listOf(call("A", 12), call("B", 14), call("C", 17))
        now = at(12)
        tracker.refresh()
        assertEquals(TripProgress.Arrived, tracker.progress.value)
    }

    @Test
    fun `on the board ahead, a train that doesn't take the ride isn't theirs, and theirs is found past a full board`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B, whose board lists a train ending there first, then theirs (Codex, PR #462).
        departures["B"] = listOf(train("4", 8).copy(destination = "B"), train("5", 9))
        trains["4"] = listOf(call("B", 8))
        trains["5"] = listOf(call("B", 9), call("C", 13))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("5", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `on the board ahead, theirs is the soonest however many come behind it`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // B lists theirs, then more trains behind it than are tried, each still to call at A (Codex, PR
        // #462).
        departures["B"] = listOf(train("5", 8), train("9", 10), train("10", 12), train("11", 14))
        trains["5"] = listOf(call("B", 8), call("C", 12))
        trains["10"] = listOf(call("A", 10), call("B", 12), call("C", 16))
        trains["11"] = listOf(call("A", 12), call("B", 14), call("C", 18))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("5", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `on the board ahead, seen at a stop theirs is the latest past them, and between stops only a lone one`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B, whose board lists a train between them and B, then theirs: both left A and
        // call next at B, so neither is told for theirs, and they're on board by where they were seen.
        departures["B"] = listOf(train("4", 8), train("5", 9))
        trains["4"] = listOf(call("B", 8), call("C", 12))
        trains["5"] = listOf(call("B", 9), call("C", 13))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        assertFalse(tracker.failed.value)
        // Seen at B, C's board lists the train ahead first, theirs at B now, and one behind still due at B:
        // theirs is the latest of those past them.
        departures["C"] = listOf(train("4", 9), train("5", 11), train("6", 14))
        trains["4"] = listOf(call("C", 9))
        trains["5"] = listOf(call("B", 8), call("C", 11))
        trains["6"] = listOf(call("B", 11), call("C", 14))
        now = at(8)
        tracker.refresh(fixAt(51.51))
        assertEquals("5", tracker.trip.value?.vehicleId)
        assertEquals(at(7), tracker.trip.value?.boardedAt)
    }

    @Test
    fun `on the board ahead, a train TfL no longer knows leaves the trains either side of it unnamed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen at B, C's board lists a train ahead of them, then theirs, gone from TfL's view by the time
        // it's looked up: the one ahead isn't taken for theirs (Codex, PR #462).
        departures["C"] = listOf(train("4", 9), train("5", 11))
        trains["4"] = listOf(call("C", 9))
        gone += "5"
        now = at(8)
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        assertFalse(tracker.failed.value)
    }

    @Test
    fun `on the board ahead, a lone train after one TfL no longer knows isn't taken for theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B, whose board lists theirs, gone from TfL's view by the time it's looked up, then
        // one behind them that has left A: it calls next at B as theirs would, so it isn't taken for theirs.
        departures["B"] = listOf(train("5", 8), train("6", 10))
        gone += "5"
        trains["6"] = listOf(call("B", 10), call("C", 14))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
    }

    @Test
    fun `on the board ahead, a train its calls don't place leaves the trains either side of it unnamed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen at B, C's board lists a train ahead of them, then theirs, its calls empty in a race with
        // TfL's predictions: no proof it's behind them, so the one ahead isn't named (Codex, PR #462).
        departures["C"] = listOf(train("4", 9), train("5", 11))
        trains["4"] = listOf(call("C", 9))
        trains["5"] = emptyList()
        now = at(8)
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        assertFalse(tracker.failed.value)
    }

    @Test
    fun `on the board ahead, a lone train after one its calls don't place isn't taken for theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B, whose board lists theirs with no calls, then one behind them that has left A.
        departures["B"] = listOf(train("5", 8), train("6", 10))
        trains["5"] = emptyList()
        trains["6"] = listOf(call("B", 10), call("C", 14))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
    }

    @Test
    fun `where trains join the line at the stop ahead, its board isn't read for theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Red trains also join at B from a branch by X.
        sequences["red"] = redLine.copy(routes = redLine.routes + app.stopdash.domain.LineRoute("X ↔ C", listOf("X", "B", "C")))
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B, whose board lists a lone train that may have come from X: not taken for theirs,
        // and the board isn't asked (Codex, PR #462).
        departures["B"] = listOf(train("6", 8))
        trains["6"] = listOf(call("B", 8), call("C", 12))
        boardStops.clear()
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        assertFalse("B" in boardStops)
    }

    @Test
    fun `on the board ahead, a train its route can't place leaves the trains before it unnamed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Red parts after B, for C or for E.
        sequences["red"] = redLine.copy(routes = redLine.routes + app.stopdash.domain.LineRoute("A ↔ E", listOf("A", "B", "E")))
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B, whose board lists one for C, then one with no destination, which may go either
        // way after B: it may be theirs, so the one for C isn't taken for a lone match (Codex, PR #462).
        departures["B"] = listOf(train("4", 8), train("5", 9).copy(destination = ""))
        trains["4"] = listOf(call("B", 8), call("C", 12))
        trains["5"] = listOf(call("B", 9), call("C", 13))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        // Behind the lone train for C, it's never reached: that one is named.
        departures["B"] = listOf(train("4", 8), train("9", 10), train("5", 12).copy(destination = ""))
        trains["5"] = listOf(call("B", 12), call("C", 16))
        now = at(8)
        tracker.refresh(fixAt(51.506))
        assertEquals("4", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a train that left that its route can't place may be theirs, so none on the board ahead is named`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // Red parts after B, for C or for E.
        sequences["red"] = redLine.copy(routes = redLine.routes + app.stopdash.domain.LineRoute("A ↔ E", listOf("A", "B", "E")))
        departures["A"] = listOf(train("7", 5).copy(destination = ""), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // 7, with no destination, has left A; B's board lists only 8, a later train for C. 7 may be
        // theirs, so 8, a lone match, isn't taken for it (Codex, PR #462).
        departures["A"] = listOf(train("9", 8))
        trains["7"] = listOf(call("B", 7), call("C", 11))
        departures["B"] = listOf(train("8", 9))
        trains["8"] = listOf(call("B", 9), call("C", 13))
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
    }

    @Test
    fun `on the board ahead, a train with no id to look up leaves the trains before it unnamed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen at B, C's board lists a train ahead of them, then one TfL gives no id: it may be theirs, so
        // the one ahead isn't named (Codex, PR #462).
        departures["C"] = listOf(train("4", 9), train("", 11))
        trains["4"] = listOf(call("C", 9))
        now = at(8)
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
    }

    @Test
    fun `on the board ahead, trains ahead of them filling the lookups name none`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen at B, C's board lists three trains ahead of them before theirs: no train behind them is
        // reached to show where theirs ends, so none is named, not the third ahead (Codex, PR #462).
        departures["C"] = listOf(train("2", 8), train("3", 9), train("4", 10), train("5", 11))
        listOf("2" to 8L, "3" to 9L, "4" to 10L).forEach { (id, at) -> trains[id] = listOf(call("C", at)) }
        trains["5"] = listOf(call("B", 8), call("C", 11))
        now = at(8)
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.boarded)
        assertFalse(tracker.failed.value)
        // Only as many lookups as ever: theirs, past them, isn't asked after.
        assertTrue(asked.containsAll(listOf("2", "3", "4")))
        assertFalse("5" in asked)
    }

    @Test
    fun `on board by position, a train found on the board ahead keeps when they boarded, and a failed read is said`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B, with nothing on B's board yet: on board by where they were seen.
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(at(7), tracker.trip.value?.boardedAt)
        // A fix further on, B's board can't be read: their place stands, and the refresh says it failed.
        unknownStops += "B"
        now = at(8)
        tracker.refresh(fixAt(51.508))
        assertTrue(tracker.failed.value)
        assertEquals("", tracker.trip.value?.vehicleId)
        assertTrue(logged.any { it == "on the way: board ahead lookup failed for line red: NotFound" })
        // Read, it lists theirs: named, their ride's time still from when they were first seen on board.
        unknownStops -= "B"
        departures["B"] = listOf(train("5", 9))
        trains["5"] = listOf(call("B", 9), call("C", 13))
        tracker.refresh(fixAt(51.509))
        assertFalse(tracker.failed.value)
        assertEquals("5", tracker.trip.value?.vehicleId)
        assertEquals(at(7), tracker.trip.value?.boardedAt)
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
    fun `seen along the ride with no train that left to be found, the rider is on board by where they were seen`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        // Seen at the next stop (maintainer, 2026-10-01): on board, though 9, still to come, isn't theirs.
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        // Said, coarsely: the line, never where.
        assertTrue(logged.any { it.startsWith("on the way: seen along the ride") && "red" in it })
        // A refresh with no fix keeps them on board, counted from where they were seen, never on 9.
        departures["A"] = listOf(train("9", 8))
        tracker.refresh()
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        // No train is matched against where they were last seen, only a fresh fix (maintainer, 2026-10-01):
        // with none, nothing is asked after.
        val before = asked.size
        now = at(9)
        tracker.refresh()
        assertEquals(before, asked.size)
        // TfL down while the train that took them is looked for with a fresh fix further on: said, not
        // passed off as current (Codex, PR #449); the ride stands as it was. 9 is due by now, so it's asked after.
        failing = true
        tracker.refresh(fixAt(51.515))
        assertTrue(tracker.failed.value)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        failing = false
        tracker.refresh(fixAt(51.515))
        assertFalse(tracker.failed.value)
        // Kept so, a restart says the same before any answer, not that their train is still to find
        // (Codex, PR #449).
        val restarted = tracker(StandardTestDispatcher(testScheduler), load = { kept })
        restarted.restore()
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), restarted.progress.value)
    }

    @Test
    fun `seen along the ride while TfL is down, they're on board by where they were seen and it says so`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("9", 8))
        now = at(7)
        // 7, which left, can't be asked after: on board all the same, the refresh failed (Codex, PR #449).
        failing = true
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertTrue(tracker.failed.value)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
    }

    @Test
    fun `seen along the ride as the boarding stop's board fails, on board by where they were seen and it says so`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        // No train found theirs, and the board that might have listed it failed: said (Codex, PR #449).
        boardFails = true
        tracker.refresh(fixAt(51.51))
        assertEquals(true, tracker.trip.value?.onBoardSeen)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        assertTrue(tracker.failed.value)
    }

    @Test
    fun `on board by where they were seen, seen at the same stop again restarts the Planner's clock`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals(at(7), tracker.trip.value?.seenAlongAt)
        // Still at B two minutes on (a train held there): counted again from now (Codex, #572).
        now = at(9)
        tracker.refresh(fixAt(51.51))
        assertEquals(at(9), tracker.trip.value?.seenAlongAt)
        // Seen short of B after that is a fix's error: the clock stands.
        now = at(10)
        tracker.refresh(fixAt(51.505))
        assertEquals(at(9), tracker.trip.value?.seenAlongAt)
    }

    @Test
    fun `get off soon said by the Planner's time is taken back when the train is seen held, and said again in time`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // Seen short of B at 7: the ride's 10 minutes over its two stops is 5 a stop, so one stop out by 12.
        now = at(7)
        tracker.refresh(fixAt(51.505))
        assertFalse((tracker.progress.value as TripProgress.Riding).getOffSoon)
        now = at(12)
        tracker.refresh()
        assertEquals("said C", alerts.last())
        val said = alerts.size
        // Seen short of B again at 13, after that time: the train is held there, so the alert is taken
        // back and the leg can warn again (Codex, #572).
        now = at(13)
        tracker.refresh(fixAt(51.505))
        assertFalse((tracker.progress.value as TripProgress.Riding).getOffSoon)
        assertEquals(listOf("done"), alerts.drop(said))
        assertEquals(-1, tracker.trip.value?.warnedLeg)
        // Due one stop out from there by 18: said again.
        now = at(18)
        tracker.refresh()
        assertEquals(listOf("done", "said C"), alerts.drop(said))
    }

    @Test
    fun `on board by where they were seen, a fix further on moves them on though TfL is down`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["7"] = listOf(call("A", 5), call("B", 8), call("C", 12))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("9", 8))
        now = at(6)
        // Short of B: on board by where they were seen, two stops left (this plan names no stops on the way).
        tracker.refresh(fixAt(51.505))
        assertEquals(2, (tracker.progress.value as TripProgress.Riding).stopsLeft)
        assertFalse(tracker.failed.value)
        // At B with TfL down: moved on from location all the same, "get off soon" said, the failure too
        // (Codex, PR #449).
        failing = true
        now = at(8)
        tracker.refresh(fixAt(51.51))
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        assertTrue(tracker.failed.value)
        assertTrue(warned.isNotEmpty())
    }

    @Test
    fun `on board by where they were seen, a fix where they get off takes back get off soon`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(30))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(TripRoute(listOf(ride, walkOn)), "D", readyAt = now)
        tracker.refresh()
        now = at(7)
        // At B, a stop from C: "get off soon" said.
        tracker.refresh(fixAt(51.51))
        assertEquals("said C", alerts.last())
        val said = alerts.size
        // Seen at C: off, and the alert taken back (Codex, PR #449).
        now = at(9)
        tracker.refresh(fixAt(51.52))
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals(listOf("done"), alerts.drop(said))
    }

    @Test
    fun `on board by where they were seen, later trains not yet due don't crowd theirs out`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("7", 5), train("8", 9), train("9", 10), train("10", 11))
        trains["8"] = listOf(call("A", 9), call("B", 11), call("C", 15))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("8", 9), train("9", 10), train("10", 11))
        // 7's calls not yet past A: not told as theirs, so they're on board by where they were seen.
        trains["7"] = listOf(call("A", 5), call("B", 7), call("C", 11))
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        // Its calls moved on, and a fresh fix further on: told as theirs, though three later trains were
        // seen at A too (Codex, PR #449).
        trains["7"] = listOf(call("C", 11))
        now = at(8)
        logged.clear()
        tracker.refresh(fixAt(51.515))
        assertEquals("7", tracker.trip.value?.vehicleId)
        // The fix that found their train is logged with where it placed them, as any on such a ride (Codex, #566).
        assertTrue(logged.any { it.startsWith("on the way: seen ") && it.endsWith("fix accuracy 20 m") })
    }

    @Test
    fun `on board by where they were seen, a route that can't be read with a fresh fix is said`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        assertFalse(tracker.failed.value)
        // The route that places them can't be read: their position stands, and the refresh says it
        // couldn't update (Codex, PR #449).
        routeFails = true
        logged.clear()
        tracker.refresh(fixAt(51.515))
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        assertTrue(tracker.failed.value)
        // The fix is still logged, placed nowhere, after the failure that kept it from being placed.
        val failedAt = logged.indexOfFirst { it.startsWith("on the way: route lookup failed for line red") }
        assertEquals("on the way: not placed along the ride, fix accuracy 20 m", logged.getOrNull(failedAt + 1))
    }

    @Test
    fun `on board by where they were seen, a train behind them isn't taken for theirs as it moves on`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        departures["A"] = listOf(train("9", 6))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = emptyList()
        // Seen at B with 9 still minutes from it: behind them, so on board by where they were seen.
        trains["9"] = listOf(call("B", 10), call("C", 14))
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        // 9 moves on past B: with no fresh fix it isn't asked after, nor taken for theirs (maintainer,
        // 2026-10-01): where they were last seen says where, not when (Codex, PR #449).
        trains["9"] = listOf(call("C", 14))
        now = at(11)
        tracker.refresh()
        assertEquals("", tracker.trip.value?.vehicleId)
        // A fix again at B, no further on than they were, matches nothing either.
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
    }

    @Test
    fun `on board by where they were seen, a fix further back doesn't take a train behind them for theirs`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        sequences["red"] = redLine
        // 7 left A and is pulling in at B; 9 is still to come.
        departures["A"] = listOf(train("7", 5), train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        trains["7"] = listOf(call("B", 7), call("C", 11))
        now = at(4)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        departures["A"] = listOf(train("9", 8))
        now = at(7)
        // Seen past B, toward C: 7, still due at B, is behind them, so they're on by where they were seen.
        tracker.refresh(fixAt(51.515))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
        // A vaguer fix back at B doesn't make 7, due there now, theirs (Codex, PR #449).
        tracker.refresh(fixAt(51.51))
        assertEquals("", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Riding(ride, "C", 1, null, true, byPosition = true), tracker.progress.value)
    }

    @Test
    fun `with no route to place the ride's stops, a fix says nothing of a rider waiting`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("9", 8))
        trains["9"] = listOf(call("A", 8), call("B", 10), call("C", 14))
        now = at(6)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(7)
        tracker.refresh(fixAt(51.51))
        assertEquals("9", tracker.trip.value?.vehicleId)
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
    fun `a start, a refresh, Next and End work the trip out off the caller's thread`() = runTest {
        // Each walks the trip's route, which grows with it: on the tracker's worker, never the caller's
        // (main) thread (AGENTS.md *Main thread: read and dispatch only*).
        val test = StandardTestDispatcher(testScheduler)
        val onWorker = ThreadLocal<Boolean>()
        val worker = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                test.dispatch(context) {
                    onWorker.set(true)
                    try {
                        block.run()
                    } finally {
                        onWorker.set(false)
                    }
                }
            }
        }
        val reads = mutableListOf<Boolean>()
        clockRead = { reads += onWorker.get() == true }
        val tracker = tracker(test, compute = worker)
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        departures["A"] = listOf(train("2", 3))
        trains["2"] = listOf(call("A", 3), call("B", 7), call("C", 11))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(1)
        tracker.refresh()
        now = at(2)
        tracker.goTo(Step(0), Step(1))
        assertEquals("2", tracker.trip.value?.vehicleId)
        assertTrue(tracker.end())
        assertTrue(reads.isNotEmpty())
        assertEquals(listOf(true), reads.distinct())
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
    fun `a failed refresh keeps when the step was last answered, for its time left to stay`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = now)
        // Started, not yet asked: no answer to keep a time from.
        assertNull(tracker.answeredAt.value)
        tracker.refresh()
        val answered = now
        assertEquals(answered, tracker.answeredAt.value)
        // TfL out of reach: no longer current, but the last answer's time is kept.
        failing = true
        now = at(5)
        tracker.refresh()
        assertNull(tracker.updatedAt.value)
        assertEquals(answered, tracker.answeredAt.value)
        // A step moved on by hand has no answer of its own: nothing kept until TfL answers for it.
        tracker.goTo(Step(0), Step(0, onBoard = true))
        assertNull(tracker.answeredAt.value)
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
        assertEquals(ride, (tracker.progress.value as TripProgress.Lost).leg)
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
    fun `the walk to the destination waits for the rider to be seen there, saying how far is left`() = runTest {
        // A synthetic destination point, and the rider about 450 m short of it, then there.
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        // Unplaced, as TfL's answer gives a walk's end: the destination chosen places it.
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        now = at(12)
        // Past its time, seen short of it: still walking, with how far is left (maintainer, 2026-10-03).
        tracker.refresh(fixAt(51.504))
        val walking = tracker.progress.value as TripProgress.Walking
        assertEquals(445.0, checkNotNull(walking.metersLeft), 5.0)
        // A refresh with no fix keeps the last distance rather than blanking it.
        tracker.refresh()
        assertEquals(walking.metersLeft, (tracker.progress.value as TripProgress.Walking).metersLeft)
        // Seen there: arrived.
        tracker.refresh(fixAt(51.5001))
        assertEquals(TripProgress.Arrived, tracker.progress.value)
    }

    @Test
    fun `a fix between refreshes moves the walk's distance at once`() = runTest {
        // A synthetic destination point; the rider seen about 445 m short of it, then about 222 m.
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        now = at(2)
        tracker.refresh(fixAt(51.504))
        val reads = boardReads
        // A fix with no refresh behind it (maintainer, 2026-10-04): the distance follows it, nothing is asked of TfL.
        tracker.onFix(fixAt(51.502))
        val walking = tracker.progress.value as TripProgress.Walking
        assertEquals(222.0, checkNotNull(walking.metersLeft), 5.0)
        assertFalse(walking.estimated)
        assertEquals(reads, boardReads)
        // A vague fix can't place the rider: the distance stands.
        tracker.onFix(fixAt(51.503).copy(isCoarse = true))
        assertEquals(222.0, checkNotNull((tracker.progress.value as TripProgress.Walking).metersLeft), 5.0)
    }

    @Test
    fun `a walk begins with a distance estimated from a fix taken lately`() = runTest {
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        // The fix the app took a minute ago, the one the trip was planned from, say: about 445 m short.
        val remembered = fixAt(51.504).copy(ageMillis = 60_000L)
        val tracker = tracker(StandardTestDispatcher(testScheduler), remembered = { remembered })
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        // No fix on the walk yet: estimated from it, and said to be.
        tracker.refresh()
        val estimated = tracker.progress.value as TripProgress.Walking
        assertEquals(445.0, checkNotNull(estimated.metersLeft), 5.0)
        assertTrue(estimated.estimated)
        // A fix on the walk replaces it, no longer an estimate.
        now = at(1)
        tracker.onFix(fixAt(51.502))
        val seen = tracker.progress.value as TripProgress.Walking
        assertEquals(222.0, checkNotNull(seen.metersLeft), 5.0)
        assertFalse(seen.estimated)
    }

    @Test
    fun `a walk's distance isn't estimated from a fix too old`() = runTest {
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        val stale = fixAt(51.504).copy(ageMillis = ActiveTripTracker.ESTIMATE_WITHIN.toMillis() + 1)
        val tracker = tracker(StandardTestDispatcher(testScheduler), remembered = { stale })
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        tracker.refresh()
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
    }

    @Test
    fun `a fix kept for a later walk is aged by the reads after it was seen`() = runTest {
        // Synthetic places: A walked to, then a walk on from C to the destination.
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "", "Destination", at(15), at(20))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = app.stopdash.domain.Coordinates(51.6, -0.12))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(
            TripRoute(listOf(toA, ride, walkOn)), "Destination", readyAt = now,
            destinations = listOf(app.stopdash.domain.TripDestination.Place(app.stopdash.domain.Coordinates(51.5, -0.12), "Destination")),
        )
        now = at(1)
        // At A, waiting for 3: seen a second old, then 3's calls take past the estimate's window to come
        // back. The fix is that old by then, however recently it was kept (Codex, #542).
        tracker.goTo(Step(0), Step(1))
        assertEquals("3", tracker.trip.value?.vehicleId)
        vehicleTakes = ActiveTripTracker.ESTIMATE_WITHIN.toMillis() + 10_000
        tracker.refresh(fixAt(51.504))
        vehicleTakes = 0
        tracker.goTo(Step(1), Step(2))
        tracker.refresh()
        val walking = tracker.progress.value as TripProgress.Walking
        assertEquals(walkOn, walking.leg)
        assertNull(walking.metersLeft)
    }

    @Test
    fun `a kept fix is let go past its window though TfL can't be reached`() = runTest {
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(1)
        // Waiting for 3 at A, seen there.
        tracker.goTo(Step(0), Step(1))
        tracker.onFix(fixAt(51.504))
        assertNotNull(tracker.heldFix())
        // 3 can't be looked up for longer than the window: no refresh gets through, but the fix still
        // goes (docs/PRIVACY.md; Codex, #542).
        failing = true
        ticks += ActiveTripTracker.ESTIMATE_WITHIN.toMillis() + 1
        tracker.refresh()
        assertNull(tracker.heldFix())
    }

    @Test
    fun `a refresh with no fix keeps the walk's station for the fixes after it`() = runTest {
        // A walk to A, whose place the Planner left out: only A's read places it.
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = app.stopdash.domain.Coordinates(51.5, -0.12))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(1)
        tracker.refresh(fixAt(51.504))
        // The timer's refresh, with no fix: A isn't read again, but its read stands (Codex, #542).
        tracker.refresh()
        tracker.onFix(fixAt(51.502))
        assertEquals(222.0, checkNotNull((tracker.progress.value as TripProgress.Walking).metersLeft), 5.0)
    }

    @Test
    fun `a kept fix is let go past its window though nothing follows the trip`() = runTest {
        // The service stopped just after a fix, the app in the background: no refresh comes, but the
        // fix still goes on time (docs/PRIVACY.md; Codex, #542).
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        val tracker = tracker(StandardTestDispatcher(testScheduler), forgetting = backgroundScope)
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        tracker.onFix(fixAt(51.504))
        assertNotNull(tracker.heldFix())
        // The elapsed clock and the timer's both move on past the window.
        ticks += ActiveTripTracker.ESTIMATE_WITHIN.toMillis() + 1
        advanceTimeBy(ActiveTripTracker.ESTIMATE_WITHIN.toMillis() - 1_000)
        assertNotNull(tracker.heldFix())
        advanceTimeBy(2_000)
        assertNull(tracker.heldFix())
    }

    @Test
    fun `a walk isn't estimated from a vague fix`() = runTest {
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        // Recent, but of no stated accuracy, then hundreds of meters wide: neither places the rider (Codex, #542).
        var remembered = fixAt(51.504).copy(accuracyMeters = null, ageMillis = 60_000L)
        val tracker = tracker(StandardTestDispatcher(testScheduler), remembered = { remembered })
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        tracker.refresh()
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
        remembered = remembered.copy(accuracyMeters = 400f)
        tracker.refresh()
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
    }

    @Test
    fun `a fix from before the leg it waited into doesn't count as seen on its walk`() = runTest {
        // A fix on the ride to C, delivered once Next has put the rider on the walk on: it was taken
        // on the ride, not the walk (Codex, #542).
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "", "Destination", at(15), at(20))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(
            TripRoute(listOf(ride, walkOn)), "Destination", readyAt = now,
            destinations = listOf(app.stopdash.domain.TripDestination.Place(app.stopdash.domain.Coordinates(51.5, -0.12), "Destination")),
        )
        now = at(14)
        tracker.goTo(Step(0), Step(1))
        val walking = tracker.progress.value as TripProgress.Walking
        assertEquals(walkOn, walking.leg)
        // Come a minute ago, before the walk began, and held up since.
        tracker.onFix(fixAt(51.502), arrivedAgoMillis = 60_000L)
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
        // One come on the walk but taken a little before it began: where they likely are, so an estimate.
        tracker.onFix(fixAt(51.502).copy(ageMillis = 5_000L))
        val estimated = tracker.progress.value as TripProgress.Walking
        assertEquals(222.0, checkNotNull(estimated.metersLeft), 5.0)
        assertTrue(estimated.estimated)
        // One taken on the walk places them.
        now = at(15)
        tracker.onFix(fixAt(51.503))
        val seen = tracker.progress.value as TripProgress.Walking
        assertEquals(333.0, checkNotNull(seen.metersLeft), 5.0)
        assertFalse(seen.estimated)
        // Then one taken before the walk began can't take that back.
        tracker.onFix(fixAt(51.502).copy(ageMillis = 120_000L))
        assertEquals(333.0, checkNotNull((tracker.progress.value as TripProgress.Walking).metersLeft), 5.0)
    }

    @Test
    fun `a fix that came on the last trip isn't kept by the next`() = runTest {
        // A fix from trip A, held up while B replaced it: B neither keeps it nor estimates from it
        // (docs/PRIVACY.md; Codex, #542).
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        now = at(2)
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, replacing = true, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        tracker.onFix(fixAt(51.504), arrivedAgoMillis = 60_000L)
        assertNull(tracker.heldFix())
        tracker.refresh()
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
    }

    @Test
    fun `no kept fix estimates a walk once precise location is taken away`() = runTest {
        // A fix kept on the trip's ride, then precise location turned off before the walk: the walk
        // isn't estimated from it, and it's deleted (Codex, #542).
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "", "Destination", at(15), at(20))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        var precise = true
        val tracker = tracker(StandardTestDispatcher(testScheduler), preciseAllowed = { precise })
        tracker.start(
            TripRoute(listOf(ride, walkOn)), "Destination", readyAt = now,
            destinations = listOf(app.stopdash.domain.TripDestination.Place(app.stopdash.domain.Coordinates(51.5, -0.12), "Destination")),
        )
        now = at(1)
        tracker.onFix(fixAt(51.504))
        assertNotNull(tracker.heldFix())
        precise = false
        tracker.goTo(Step(0), Step(1))
        tracker.refresh()
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
        assertNull(tracker.heldFix())
    }

    @Test
    fun `a fix that waited past precise location being taken away isn't used`() = runTest {
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        var precise = true
        val tracker = tracker(StandardTestDispatcher(testScheduler), preciseAllowed = { precise })
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        now = at(1)
        // Queued, then precise location turned off before it was taken up (Codex, #542).
        precise = false
        tracker.onFix(fixAt(51.502))
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
        assertNull(tracker.heldFix())
    }

    @Test
    fun `a refresh's fix from before precise location was taken away isn't used`() = runTest {
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        var precise = true
        val tracker = tracker(StandardTestDispatcher(testScheduler), preciseAllowed = { precise })
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        now = at(1)
        tracker.onFix(fixAt(51.504))
        // A fix held for the next refresh, then precise location turned off (Codex, #542).
        precise = false
        tracker.refresh(fixAt(51.502))
        assertNotEquals(222.0, (tracker.progress.value as TripProgress.Walking).metersLeft ?: 0.0, 5.0)
        assertNull(tracker.heldFix())
    }

    @Test
    fun `precise location taken away during a refresh's reads stops its fix being used`() = runTest {
        // Turned off while A's entrances are read: the fix that refresh brought is neither kept nor
        // measured from (Codex, #542).
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = app.stopdash.domain.Coordinates(51.5, -0.12))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        var precise = true
        entranceClock = { precise = false }
        val tracker = tracker(StandardTestDispatcher(testScheduler), preciseAllowed = { precise })
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(1)
        tracker.refresh(fixAt(51.504))
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
        assertNull(tracker.heldFix())
    }

    @Test
    fun `the fix a refresh moves the trip onto a walk with gives an estimate`() = runTest {
        // A walk on from where the first ends, by its time: the fix this refresh took was taken before
        // the second walk began, so it's where the rider likely is, not seen on it (Codex, #542).
        val first = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "Y", "Y", at(0), at(2))
        val second = TripLeg(TripLeg.WALKING, "", "", "Y", "Y", "", "Destination", at(2), at(8))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(
            TripRoute(listOf(first, second)), "Destination", readyAt = now,
            destinations = listOf(app.stopdash.domain.TripDestination.Place(app.stopdash.domain.Coordinates(51.5, -0.12), "Destination")),
        )
        // 3 s into the second walk, with a fix 5 s old: taken on the first.
        now = at(2).plusSeconds(3)
        tracker.refresh(fixAt(51.502).copy(ageMillis = 5_000L))
        val walking = tracker.progress.value as TripProgress.Walking
        assertEquals(second, walking.leg)
        assertEquals(222.0, checkNotNull(walking.metersLeft), 5.0)
        assertTrue(walking.estimated)
    }

    @Test
    fun `a fix during a refresh isn't overwritten by the refresh's older one`() = runTest {
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = app.stopdash.domain.Coordinates(51.5, -0.12))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(1)
        // A refresh from a fix about 445 m short, held in A's read; a newer fix, 222 m short, comes
        // meanwhile. It waits for the refresh, then stands (Codex, #542).
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        entrancesGate = slow
        val refreshing = backgroundScope.launch { tracker.refresh(fixAt(51.504)) }
        runCurrent()
        val fixed = backgroundScope.launch { tracker.onFix(fixAt(51.502)) }
        runCurrent()
        entrancesGate = null
        slow.complete(Unit)
        refreshing.join()
        fixed.join()
        assertEquals(222.0, checkNotNull((tracker.progress.value as TripProgress.Walking).metersLeft), 5.0)
    }

    @Test
    fun `an ended trip's fix and station aren't the next trip's`() = runTest {
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(10))
        entrancesAt["A"] = app.stopdash.domain.StationPlaces(point = end)
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // A walk to A, seen on it: A's place is read, the fix kept.
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        now = at(1)
        tracker.refresh(fixAt(51.504))
        assertTrue(tracker.end())
        // The next trip, a walk to a place, begins with no fix of its own: none of the last trip's
        // estimates it (docs/PRIVACY.md), and a fix before its first refresh isn't measured to A.
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(app.stopdash.domain.Coordinates(51.6, -0.12), "Destination")))
        tracker.refresh()
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
        assertTrue(tracker.end())
        // A's place read again on a walk to it; the next trip's walk to E, fixed before its first
        // refresh, isn't measured to A's (Codex, #542).
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        tracker.refresh(fixAt(51.504))
        assertEquals(445.0, checkNotNull((tracker.progress.value as TripProgress.Walking).metersLeft), 5.0)
        assertTrue(tracker.end())
        tracker.start(TripRoute(listOf(toA.copy(toId = "E", toName = "E"), ride)), "C", readyAt = now)
        tracker.onFix(fixAt(51.504))
        assertNull((tracker.progress.value as TripProgress.Walking).metersLeft)
    }

    @Test
    fun `taking a branch off the plan reroutes the trip to where it turns off, off the caller's thread`() = runTest {
        // A forked line: from A through B, then on to C (the rider's way) or to Y.
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        val toY = Departure("red", "Red", "outbound", "Y", null, at(3), "tube", vehicleId = "2")
        departures["A"] = listOf(toY, train("1", 11))
        trains["1"] = listOf(call("A", 11), call("B", 13), call("C", 15))
        trains["2"] = listOf(call("A", 3), call("B", 5), call("Y", 7))
        val test = StandardTestDispatcher(testScheduler)
        var hops = 0
        val worker = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                hops++
                test.dispatch(context, block)
            }
        }
        val tracker = tracker(test, compute = worker)
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // The plan's own train: the one to Y turns off at B.
        assertEquals("1", tracker.trip.value?.vehicleId)
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        val before = hops
        tracker.take(ride, branch)
        assertTrue("$hops", hops > before)
        val taken = checkNotNull(tracker.trip.value)
        assertEquals(listOf("B", "C"), taken.route.legs.map { it.toId })
        assertEquals(taken, kept)
        // A reroute, nothing followed specially: the ride to B picks the next train that reaches it.
        assertEquals("2", taken.vehicleId)
        // On it, the rider is told to get off at B, where they change back onto the ride.
        now = at(4)
        trains["2"] = listOf(call("B", 5), call("Y", 7))
        tracker.refresh()
        assertEquals("B", (tracker.progress.value as TripProgress.Riding).leg.toId)
        assertTrue(alerts.contains("said B"))
        // A ride the trip has moved past isn't rerouted again.
        tracker.take(ride, branch)
        assertEquals(2, tracker.trip.value?.route?.legs?.size)
    }

    @Test
    fun `with none of the plan's trains listed, only a branch's, the trip takes the branch by itself`() = runTest {
        // A forked line: from A through B, then on to C (the rider's way) or to Y. The board lists only a
        // train to Y (the Planner's timetable promised one to C): the rider will take that one, change at B
        // (maintainer, 2026-10-06).
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(Departure("red", "Red", "outbound", "Y", null, at(6), "tube", vehicleId = "2"))
        trains["2"] = listOf(call("A", 6), call("B", 8), call("Y", 10))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        boardReads = 0
        tracker.refresh()
        val taken = checkNotNull(tracker.trip.value)
        assertEquals(listOf("B", "C"), taken.route.legs.map { it.toId })
        // The board read once: the shortened ride's is the same stop's (Codex, #630).
        assertEquals(1, boardReads)
        assertEquals(taken.route.legs[0], tracker.nextBoard.value?.ride)
        assertEquals(taken, kept)
        // The ride to B follows the train to Y, which takes the rider there.
        assertEquals("2", taken.vehicleId)
        assertTrue(logged.any { "took the branch" in it })
        // Said on the trip's screen until the rider is past the ride to B, kept with the trip for a restart (Codex, #630).
        assertEquals(Triple(0, "C", "B"), Triple(taken.branchTakenLeg, taken.branchTakenTo, taken.branchTakenFork))
        // And sounded, as the unexpected is (maintainer, 2026-10-06): once, through "route disruption".
        assertEquals(listOf("new nonedirect/0/red/C/B"), disruptionAlerts.filter { it != "done" })
        tracker.refresh()
        assertEquals("keep nonedirect/0/red/C/B", disruptionAlerts.last())
        assertEquals(1, disruptionAlerts.count { it.startsWith("new") })
        // Not a card, so not Keep going's to let go of (Codex, #633).
        tracker.dismissDisruptions(checkNotNull(tracker.routeDisruptions.value).signals)
        assertEquals(emptySet<String>(), tracker.trip.value?.disruptionsDismissed)
        assertEquals(listOf("nonedirect/0/red/C/B"), tracker.routeDisruptions.value?.signals?.map { it.key })
        // A reroute that can't be kept moves nothing, the board shown with it (Codex, #630).
        assertTrue(tracker.end())
        tracker.start(route, "C", readyAt = now)
        saves = false
        tracker.refresh()
        assertEquals(route, tracker.trip.value?.route)
        assertEquals(ride, tracker.nextBoard.value?.ride)
        saves = true
        // A route TfL can't give is asked for once a refresh, however many look for it (Codex, #630).
        assertTrue(tracker.end())
        tracker.start(route, "C", readyAt = now)
        routeFails = true
        sequencesRead.clear()
        tracker.refresh()
        assertEquals(1, sequencesRead.count { it == "red" })
        routeFails = false
        // With a train of the plan's listed, nothing is taken.
        assertTrue(tracker.end())
        departures["A"] = listOf(Departure("red", "Red", "outbound", "Y", null, at(6), "tube", vehicleId = "2"), train("1", 11))
        trains["1"] = listOf(call("A", 11), call("B", 13), call("C", 15))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals(1, tracker.trip.value?.route?.legs?.size)
    }

    @Test
    fun `a branch taken once the walk to the ride is over picks its train at once`() = runTest {
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(2))
        // A train of the plan's listed too: the branch is the rider's to take, not taken by itself.
        departures["A"] = listOf(Departure("red", "Red", "outbound", "Y", null, at(4), "tube", vehicleId = "2"), train("1", 11))
        trains["2"] = listOf(call("A", 4), call("B", 6), call("Y", 8))
        trains["1"] = listOf(call("A", 11), call("B", 13), call("C", 15))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = now)
        tracker.refresh()
        assertEquals(0, tracker.trip.value?.legIndex)
        // The walk's time is up, and no refresh has moved the trip on yet.
        now = at(3)
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals("2", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `another line's branch taken while waiting picks a train of that line to the fork`() = runTest {
        // Green shares the platform at A and runs with the ride to B before turning off for Z
        // (maintainer, 2026-10-05: the Circle beside the District). Its train comes first.
        sequences["red"] = app.stopdash.domain.LineSequence(listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C"))), mapOf("A" to "A", "B" to "B", "C" to "C"))
        sequences["green"] = app.stopdash.domain.LineSequence(listOf(app.stopdash.domain.LineRoute("A ↔ Z", listOf("A", "B", "Z"))), mapOf("A" to "A", "B" to "B", "Z" to "Z"))
        val green = Departure("green", "Green", "outbound", "Z", null, at(2), "tube", vehicleId = "9")
        departures["A"] = listOf(train("1", 8), green)
        trains["1"] = listOf(call("A", 8), call("B", 10), call("C", 12))
        trains["9"] = listOf(call("A", 2), call("B", 4), call("Z", 6))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        assertEquals("1", tracker.trip.value?.vehicleId)
        val branch = app.stopdash.domain.OffPlan.branches(ride, departures["A"].orEmpty(), sequences, now).single()
        assertEquals("green", branch.lineId)
        tracker.take(ride, branch)
        val taken = checkNotNull(tracker.trip.value)
        assertEquals(listOf("green" to "B", "red" to "C"), taken.route.legs.map { it.lineId to it.toId })
        // The green train, the next to reach B.
        assertEquals("9", taken.vehicleId)
    }

    @Test
    fun `on board, a branch taken keeps the rider on their train and gets them off at the fork`() = runTest {
        // Their train changed its branch on the way (maintainer, 2026-10-05): it now turns off at B.
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        // It leaves with the rider on it, as planned.
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        assertTrue(checkNotNull(tracker.trip.value).boarded)
        // Then its calls switch to the other branch: the trip can't place it on the ride any more.
        trains["1"] = listOf(call("B", 4), call("Y", 7))
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Lost)
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        val taken = checkNotNull(tracker.trip.value)
        assertEquals("1", taken.vehicleId)
        assertTrue(taken.boarded)
        assertEquals(listOf("B", "C"), taken.route.legs.map { it.toId })
        // A stop from B: get off soon, there.
        now = at(3)
        tracker.refresh()
        assertEquals("B", (tracker.progress.value as TripProgress.Riding).leg.toId)
        assertEquals("said B", alerts.last())
    }

    @Test
    fun `a fork the rider's train passed before it was lost isn't taken`() = runTest {
        // The ride A to D through B and C; the line forks at B for Y (Codex, #586).
        val long = ride.copy(toId = "D", toName = "D", path = listOf("B", "C", "D"))
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ D", listOf("A", "B", "C", "D")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "D" to "D", "Y" to "Y"),
        )
        departures["A"] = listOf(Departure("red", "Red", "outbound", "D", null, at(1), "tube", vehicleId = "1"))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 6), call("D", 8))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(long)), "D", readyAt = now)
        tracker.refresh()
        // Past B, next at C.
        now = at(5)
        trains["1"] = listOf(call("C", 6), call("D", 8))
        tracker.refresh()
        assertEquals(2, (tracker.progress.value as TripProgress.Riding).stopsLeft)
        // Then lost: the last known stop ahead is kept.
        trains["1"] = listOf(call("C", 6), call("Z", 8))
        tracker.refresh()
        assertEquals(1, (tracker.progress.value as TripProgress.Lost).ahead)
        val branch = app.stopdash.domain.OffPlan.branches(long, emptyList(), sequences, now).single()
        assertEquals(0, branch.forkIndex)
        tracker.take(long, branch)
        assertEquals(listOf("D"), tracker.trip.value?.route?.legs?.map { it.toId })
    }

    @Test
    fun `a fork passed before a restart isn't taken once the train is lost`() = runTest {
        // Kept by the trip before the app died: on board, the next stop known ahead C (Codex, #586).
        val long = ride.copy(toId = "D", toName = "D", path = listOf("B", "C", "D"))
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ D", listOf("A", "B", "C", "D")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "D" to "D", "Y" to "Y"),
        )
        val stored = ActiveTrip(
            TripRoute(listOf(long)), "D", startedAt = t0, vehicleId = "1", boardsAt = at(1), boarded = true, boardedAt = at(1),
            aheadLeg = 0, aheadStop = 1,
        )
        now = at(5)
        trains["1"] = listOf(call("C", 6), call("Z", 8))
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { stored })
        tracker.restore()
        tracker.refresh()
        assertEquals(1, (tracker.progress.value as TripProgress.Lost).ahead)
        val branch = app.stopdash.domain.OffPlan.branches(long, emptyList(), sequences, now).single()
        tracker.take(long, branch)
        assertEquals(listOf("D"), tracker.trip.value?.route?.legs?.map { it.toId })
    }

    @Test
    fun `the stop known ahead goes once the rider is waiting again`() = runTest {
        // Kept from a train taken to be theirs that left without them: waiting again, it's forgotten, so a later
        // train's forks aren't judged by it (Codex, #586).
        val stored = ActiveTrip(TripRoute(listOf(ride)), "C", startedAt = t0, aheadLeg = 0, aheadStop = 1)
        departures["A"] = listOf(train("2", 6))
        trains["2"] = listOf(call("A", 6), call("B", 8), call("C", 10))
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { stored })
        tracker.restore()
        tracker.refresh()
        assertEquals(-1, kept?.aheadLeg)
        assertEquals(-1, kept?.aheadStop)
    }

    @Test
    fun `the stop known ahead on board is kept with the trip`() = runTest {
        val long = ride.copy(toId = "D", toName = "D", path = listOf("B", "C", "D"))
        departures["A"] = listOf(Departure("red", "Red", "outbound", "D", null, at(1), "tube", vehicleId = "1"))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 6), call("D", 8))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(TripRoute(listOf(long)), "D", readyAt = now)
        tracker.refresh()
        now = at(5)
        trains["1"] = listOf(call("C", 6), call("D", 8))
        tracker.refresh()
        assertEquals(0, kept?.aheadLeg)
        assertEquals(1, kept?.aheadStop)
    }

    @Test
    fun `on board, a branch isn't taken from an answer too old to say where the train is`() = runTest {
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        assertTrue(checkNotNull(tracker.trip.value).boarded)
        // No answer since: the train may have passed B by now (Codex, #586).
        now = at(2).plus(ActiveTripTracker.CURRENT_FOR).plusSeconds(1)
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        assertEquals(listOf("C"), tracker.trip.value?.route?.legs?.map { it.toId })
    }

    @Test
    fun `a train whose calls have already turned off at the fork has it behind it`() = runTest {
        // A to C, forking at B for Y: the train's calls name only Y, so it's past B (Codex, #586).
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        now = at(5)
        trains["1"] = listOf(call("Y", 7))
        tracker.refresh()
        assertEquals(2, (tracker.progress.value as TripProgress.Lost).ahead)
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        assertEquals(listOf("C"), tracker.trip.value?.route?.legs?.map { it.toId })
    }

    @Test
    fun `a lost train with no calls to place it offers no branch`() = runTest {
        // TfL answers with no calls for the rider's train: where it is now isn't known (Codex, #586).
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        trains["1"] = emptyList()
        tracker.refresh()
        val lost = tracker.progress.value as TripProgress.Lost
        assertFalse(lost.placed)
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        assertEquals(listOf("C"), tracker.trip.value?.route?.legs?.map { it.toId })
    }

    @Test
    fun `a branch taken just before the fork, the train past it by the lookup, isn't taken`() = runTest {
        // Tapped with B still ahead, but by the reroute's own lookup the train has gone on to Y: the rider,
        // on it, is past B too, so a change there would be behind them (Codex, #586).
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Riding)
        // Within the answer's freshness: the tap is taken.
        now = at(2).plusSeconds(30)
        trains["1"] = listOf(call("Y", 7))
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        assertEquals(listOf("C"), tracker.trip.value?.route?.legs?.map { it.toId })
        assertEquals(listOf("C"), kept?.route?.legs?.map { it.toId })
        // Worked out afresh: lost on the way to Y, with B behind it.
        assertEquals(2, (tracker.progress.value as TripProgress.Lost).ahead)
    }

    @Test
    fun `a branch taken just before the fork keeps when it's due there, for a train past it by the next read`() = runTest {
        // Short of B by the reroute's own lookup, past it by the step's: when it was due at B, from the
        // first, still moves the rider on to the change there (Codex, #586).
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        // Within the answer's freshness, B half a minute off.
        now = at(2).plusSeconds(30)
        trains["1"] = listOf(call("B", 3), call("Y", 7))
        afterRead = { trains["1"] = listOf(call("Y", 7)) }
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        // Off at B: on to the rest of the ride from there.
        assertEquals(1, tracker.trip.value?.legIndex)
        assertEquals("B", tracker.trip.value?.leg?.fromId)
    }

    @Test
    fun `a branch taken on board while its train can't be read isn't taken`() = runTest {
        // The reroute's own lookup fails: the train may be past the fork, so nothing is rerouted (Codex, #586).
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Riding)
        now = at(2).plusSeconds(30)
        failingFor += "1"
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        assertEquals(listOf("C"), tracker.trip.value?.route?.legs?.map { it.toId })
        assertEquals(listOf("C"), kept?.route?.legs?.map { it.toId })
        // Said, and the old answer no longer stood behind.
        assertTrue(tracker.failed.value)
        assertNull(tracker.updatedAt.value)
    }

    @Test
    fun `a branch taken on board isn't taken when its train's calls come back empty`() = runTest {
        // Nothing to place the train by: it may be past the fork (Codex, #586).
        takenWithLookup(testScheduler, emptyList())
    }

    @Test
    fun `a branch taken on board isn't taken once its train is past the fork on the plan's way`() = runTest {
        // The ride to the fork would have nothing to finish by: the train has gone on the rider's own way.
        val tracker = takenWithLookup(testScheduler, listOf(call("C", 7)))
        // Worked out afresh: riding on, the fork behind.
        assertTrue(tracker.progress.value is TripProgress.Riding)
    }

    @Test
    fun `a branch taken on board isn't taken once its train is past the fork on another branch`() = runTest {
        // Forking at B for Y and for Z: the rider takes Y, but their train has gone on to Z (Codex, #586).
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(
                app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")),
                app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y")),
                app.stopdash.domain.LineRoute("A ↔ Z", listOf("A", "B", "Z")),
            ),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y", "Z" to "Z"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        now = at(2).plusSeconds(30)
        trains["1"] = listOf(call("Z", 7))
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single { it.label == "Y" }
        tracker.take(ride, branch)
        assertEquals(listOf("C"), tracker.trip.value?.route?.legs?.map { it.toId })
        assertEquals(listOf("C"), kept?.route?.legs?.map { it.toId })
    }

    // A rider on board taps a branch forking at B with the trip's answer current, and the reroute's own
    // lookup of their train returns [lookup]: not one that places it short of the fork, so nothing changes.
    private suspend fun takenWithLookup(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, lookup: List<VehicleCall>): ActiveTripTracker {
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 4), call("C", 7))
        val tracker = tracker(StandardTestDispatcher(scheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(2)
        trains["1"] = listOf(call("B", 4), call("C", 7))
        tracker.refresh()
        assertTrue(tracker.progress.value is TripProgress.Riding)
        now = at(2).plusSeconds(30)
        trains["1"] = lookup
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        assertEquals(listOf("C"), tracker.trip.value?.route?.legs?.map { it.toId })
        assertEquals(listOf("C"), kept?.route?.legs?.map { it.toId })
        return tracker
    }

    @Test
    fun `a get off soon said for the train before a reroute is taken back`() = runTest {
        sequences["red"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("A ↔ C", listOf("A", "B", "C")), app.stopdash.domain.LineRoute("A ↔ Y", listOf("A", "B", "Y"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y"),
        )
        // The plan's train has left with the rider only taken to be on it, two minutes from C: get off soon
        // is said, with the fork at B still ahead.
        departures["A"] = listOf(train("1", 1))
        trains["1"] = listOf(call("A", 1), call("B", 5), call("C", 6))
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        tracker.start(route, "C", readyAt = now)
        tracker.refresh()
        now = at(4)
        trains["1"] = listOf(call("B", 5), call("C", 6))
        tracker.refresh()
        assertEquals("said C", alerts.last())
        val branch = app.stopdash.domain.OffPlan.branches(ride, emptyList(), sequences, now).single()
        tracker.take(ride, branch)
        // Its stop isn't theirs any more: taken back, said again for the fork, and the mark cleared.
        assertEquals(listOf("done", "said B"), alerts.takeLast(2))
        assertFalse(checkNotNull(tracker.trip.value).alertLeft)
        assertEquals(listOf("B", "C"), tracker.trip.value?.route?.legs?.map { it.toId })
    }

    @Test
    fun `a fix between refreshes is measured off the caller's thread`() = runTest {
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        val test = StandardTestDispatcher(testScheduler)
        var hops = 0
        val worker = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                hops++
                test.dispatch(context, block)
            }
        }
        val tracker = tracker(test, compute = worker)
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        now = at(2)
        tracker.refresh()
        val before = hops
        tracker.onFix(fixAt(51.504))
        assertTrue("$hops", hops > before)
        assertEquals(445.0, checkNotNull((tracker.progress.value as TripProgress.Walking).metersLeft), 5.0)
    }

    @Test
    fun `a walk's distance is measured off the caller's thread`() = runTest {
        // A station's entrances can be many: measured on the tracker's worker, never the caller's
        // (main) thread (AGENTS.md *Main thread: read and dispatch only*; Codex, PR #521).
        val end = app.stopdash.domain.Coordinates(51.5, -0.12)
        val home = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "", "Destination", at(0), at(5))
        val test = StandardTestDispatcher(testScheduler)
        var hops = 0
        val worker = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                hops++
                test.dispatch(context, block)
            }
        }
        val tracker = tracker(test, compute = worker)
        tracker.start(TripRoute(listOf(home)), "Destination", readyAt = now, destinations = listOf(app.stopdash.domain.TripDestination.Place(end, "Destination")))
        now = at(2)
        tracker.refresh(fixAt(51.504))
        assertTrue("$hops", hops > 0)
        assertEquals(445.0, checkNotNull((tracker.progress.value as TripProgress.Walking).metersLeft), 5.0)
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

    // A bus ride from pole Bs of the example pair BG to pair CG, the Planner's bus 1; and as bus 2 runs it
    // from Bn, the other side of the road ([RideLines]).
    private val busRide = TripLeg("bus", "1", "1", "Bs", "Pair B", "Cs", "Pair C", at(5), at(15), path = listOf("Cs"), fromArea = "BG", toArea = "CG")
    private val busTwoRide = busRide.copy(lineId = "2", lineName = "2", fromId = "Bn", toId = "Cn", path = listOf("Cn"), fromArea = "", toArea = "")
    private fun bus(line: String, vehicle: String, minutes: Long) = Departure(line, line, "outbound", "Pair C", null, at(minutes), "bus", vehicleId = vehicle)
    private fun pole(id: String, letter: String, vararg lines: String) =
        app.stopdash.domain.StopLocation(id, "Pair B", 0.0, 0.0, lines = lines.map { app.stopdash.domain.LineRef(it, it, "bus") }, stopLetter = letter)

    // The pair BG: Bs serves bus 1, Bn bus 2, which runs to pair CG; bus 3 at Bw goes the other way.
    private fun busPair() {
        polesAt["BG"] = listOf(pole("Bs", "S", "1"), pole("Bn", "N", "2"), pole("Bw", "W", "3"))
        sequences["2"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("north", listOf("Bn", "Cn"))), mapOf("Bn" to "Pair B", "Cn" to "Pair C"),
            stopAreas = mapOf("Bn" to "BG", "Cn" to "CG"),
        )
        sequences["3"] = app.stopdash.domain.LineSequence(
            listOf(app.stopdash.domain.LineRoute("south", listOf("Cn", "Bw"))), mapOf("Bw" to "Pair B", "Cn" to "Pair C"),
            stopAreas = mapOf("Bw" to "BG", "Cn" to "CG"),
        )
        offered["1"] = listOf(busRide, busTwoRide)
    }

    @Test
    fun `a bus of the ride that stops only at the other pole of the boarding pair is followed`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        busPair()
        // Bs lists bus 2 going the other way (its way back) before Bn's bus 2 that takes the rider.
        departures["Bs"] = listOf(bus("2", "x", 5))
        departures["Bn"] = listOf(bus("2", "b2", 6))
        trains["2/x"] = listOf(call("Bs", 5), call("Ws", 9))
        trains["2/b2"] = listOf(call("Bn", 6), call("Cn", 12))
        tracker.start(TripRoute(listOf(busRide)), "Pair C", readyAt = now)
        tracker.refresh()
        assertEquals("b2", tracker.trip.value?.vehicleId)
        assertEquals(busTwoRide, tracker.trip.value?.vehicleLeg)
        // Only Bn's board is read besides the ride's own: Bw's bus 3 doesn't run the ride.
        assertEquals(listOf("Bs", "Bn"), boardStops.distinct())
        assertEquals(listOf("Bn"), tracker.nextBoard.value?.others?.map { it.pole.id })
        assertEquals(mapOf("Bs" to departures.getValue("Bs"), "Bn" to departures.getValue("Bn")), ridesGiven.last())
        // Bs's bus 2, the other way, is no bus of the ride's: never asked after.
        assertEquals(listOf("b2/2"), askedOn.distinct())
        assertFalse(tracker.failed.value)
    }

    @Test
    fun `a pair's other pole is read only for a line that may take the ride, and never a hidden one's`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        busPair()
        // Bus 2 hidden by the rider: only Bw's bus 3, which goes the other way, is left, so nothing more is read.
        hiddenLines = setOf(app.stopdash.domain.HiddenModes.lineKey("2", "2"))
        departures["Bs"] = listOf(bus("1", "b1", 7))
        trains["1/b1"] = listOf(call("Bs", 7), call("Cs", 13))
        tracker.start(TripRoute(listOf(busRide)), "Pair C", readyAt = now)
        tracker.refresh()
        assertEquals("b1", tracker.trip.value?.vehicleId)
        assertEquals(listOf("Bs"), boardStops.distinct())
        assertEquals(emptyList<ActiveTripTracker.PoleBoard>(), tracker.nextBoard.value?.others)
        assertFalse(tracker.nextBoard.value?.partial ?: true)
    }

    @Test
    fun `a pole of the pair that can't be read is said, and no bus found fails the refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        busPair()
        unknownStops += "Bn"
        departures["Bn"] = listOf(bus("2", "b2", 6))
        trains["2/b2"] = listOf(call("Bn", 6), call("Cn", 12))
        tracker.start(TripRoute(listOf(busRide)), "Pair C", readyAt = now)
        tracker.refresh()
        // Bn's bus 2 may be theirs: none found isn't passed off as none to take.
        assertEquals("", tracker.trip.value?.vehicleId)
        assertTrue(tracker.failed.value)
        assertEquals(true, tracker.nextBoard.value?.partial)
        assertTrue(logged.any { it.startsWith("on the way: pair board lookup failed for line 1") })
        // So too with the pair's poles unknown (another pair's, not yet read): which may list one can't be told.
        unknownStops.clear()
        tracker.end()
        val other = busRide.copy(fromArea = "BG2")
        offered["1"] = listOf(other, busTwoRide)
        sequences["2"] = sequences.getValue("2").let { it.copy(stopAreas = it.stopAreas + ("Bn" to "BG2")) }
        polesAt["BG2"] = polesAt.getValue("BG")
        polesFail = true
        tracker.start(TripRoute(listOf(other)), "Pair C", readyAt = now)
        tracker.refresh()
        assertTrue(tracker.failed.value)
        assertEquals(true, tracker.nextBoard.value?.partial)
        // Read again: Bn's bus is followed.
        polesFail = false
        tracker.refresh()
        assertEquals("b2", tracker.trip.value?.vehicleId)
        assertFalse(tracker.failed.value)
        assertEquals(false, tracker.nextBoard.value?.partial)
    }

    @Test
    fun `route disruption counts a bus at the other pole of a change's pair as predicted`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        busPair()
        // Off the train at C, a walk to pair B, then bus 1 on.
        val walkOn = TripLeg(TripLeg.WALKING, "", "", "C", "C", "Bs", "Pair B", at(16), at(18))
        val busOn = busRide.copy(departure = at(20), arrival = at(28))
        offered["1"] = listOf(busOn, busTwoRide.copy(departure = at(20), arrival = at(28)))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 16))
        tracker.start(TripRoute(listOf(ride, walkOn, busOn)), "Pair C", readyAt = now)
        tracker.refresh()
        disruptionAlerts.clear()
        // Nearing the change, no bus 1 at Bs, but bus 2 at Bn takes the rider on: nothing missing is said.
        departures["Bn"] = listOf(bus("2", "b2", 21))
        now = at(13)
        trains["3"] = listOf(call("C", 16))
        tracker.refresh()
        assertTrue("Bn" in boardStops)
        assertEquals(emptyList<String>(), disruptionAlerts)
        // Bn's board unread: unknown, never a signal.
        unknownStops += "Bn"
        tracker.refresh()
        assertEquals(emptyList<String>(), disruptionAlerts)
        // Bn read and empty: no bus of the ride's lines is predicted there.
        unknownStops.clear()
        departures["Bn"] = emptyList()
        tracker.refresh()
        assertEquals(listOf("new ${RouteDisruption.Signal.Unpredicted(2, "1", "1", "Bs", "Pair B").key}"), disruptionAlerts)
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
    fun `a ride entered as its lookup fails has no answer of its own to keep a time from`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        tracker.start(route, "C", readyAt = at(3))
        now = at(3)
        unknownStops += "A"
        // The walk runs out its time and the ride's lookup fails in the same refresh: "Updating…", not
        // a time kept from an answer the ride never had (Codex, #611).
        tracker.refresh()
        assertEquals(1, tracker.trip.value?.legIndex)
        assertTrue(tracker.failed.value)
        assertNull(tracker.answeredAt.value)
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
        // The ride's answer moved the trip on: the leg it moved to has had none of its own (Codex, #611).
        assertNull(tracker.answeredAt.value)
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

// [items], noting the thread of each read in [read]: work done over it is seen where it ran.
internal class Watched<T>(private val items: List<T>, private val read: ThreadRecorder) : AbstractList<T>() {
    private fun seen() { read.note() }

    // Whether there's anything, unnoted: for a fake standing in for TfL, whose own reads aren't the app's.
    val quietlyEmpty: Boolean get() = items.isEmpty()

    override val size: Int get() = items.size.also { seen() }

    override fun get(index: Int): T = items[index].also { seen() }
}
