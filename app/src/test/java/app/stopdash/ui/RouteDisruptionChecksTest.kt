package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.HubInfo
import app.stopdash.domain.HubInfoCache
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteDisruption
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a trip on the way's "route disruption" goes by, on synthetic stops A–C and an example line. */
class RouteDisruptionChecksTest {
    // What [found] says is wrong, without the lines' statuses it came by ([RouteDisruption.Found.lines]),
    // which a check reports whatever it finds.
    private fun RouteDisruption.Found.signalsOnly() = copy(lines = null)

    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))
    private var now = t0

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val trip = ActiveTrip(TripRoute(listOf(ride)), "C", startedAt = t0)
    private val waiting = TripProgress.Waiting(ride, at(5))

    private var statuses: Map<String, LineStatus> = emptyMap()
    private var statusesFail = false
    private var statusReads = 0
    private val notices = mutableMapOf<String, List<StopDisruption>>()
    private val closuresFail = mutableSetOf<String>()
    private val closureReads = mutableListOf<String>()
    private var dismissed: Set<DismissedAlert> = emptySet()
    private var dismissedFails = false
    private var sequenceReads = 0
    private var routeDelayMillis = 0L
    private var routesInFlight = 0
    private var mostRoutesInFlight = 0
    private var routes: Map<String, LineSequence> = emptyMap()
    private var hubs: Map<String, String> = emptyMap()
    private var hubInfos: Map<String, HubInfo> = emptyMap()
    private val hubReads = mutableListOf<String>()
    private val hubNames = HubInfoCache()
    private val logged = mutableListOf<String>()

    private val client = object : TflClient {
        override suspend fun arrivals(stopId: String): List<Departure> = emptyList()

        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            statusReads++
            if (statusesFail) throw TflException.Offline(null)
            return lineIds.mapNotNull { statuses[it] }
        }

        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
            closureReads += stopId
            if (stopId in closuresFail) throw TflException.Offline(null)
            return notices[stopId].orEmpty()
        }

        override suspend fun hubInfo(hubId: String): HubInfo {
            hubReads += hubId
            return hubInfos[hubId] ?: throw TflException.Offline(null)
        }
    }

    private fun checks(cache: StopClosureCache, dispatcher: kotlinx.coroutines.CoroutineDispatcher) = RouteDisruptionChecks(
        client = client,
        closures = StopClosureChecks(client, cache, Duration.ofMinutes(5), dispatcher, dispatcher, { logged += it }, "on the way"),
        closureCache = cache,
        hubNames = hubNames,
        background = CoroutineScope(dispatcher),
        dismissedStore = object : DismissedAlertsStore {
            override fun dismissed(): kotlinx.coroutines.flow.Flow<Set<DismissedAlert>> = kotlinx.coroutines.flow.flow {
                if (dismissedFails) throw java.io.IOException("unreadable")
                emit(dismissed)
            }

            override suspend fun dismiss(alert: DismissedAlert) {
                dismissed = dismissed + alert
            }

            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
                dismissed = app.stopdash.domain.Dismissed.reconcile(dismissed, live, checkedPlaces)
            }
        },
        sequence = { line: String ->
            sequenceReads++
            routesInFlight++
            mostRoutesInFlight = maxOf(mostRoutesInFlight, routesInFlight)
            try {
                if (routeDelayMillis > 0) kotlinx.coroutines.delay(routeDelayMillis)
                routes[line]
            } finally {
                routesInFlight--
            }
        },
        hubOf = { hubs[it] },
        clock = { now },
        io = dispatcher,
        warn = { logged += it },
    )

    @Test
    fun `a coming line's alert and a closed stop ahead, standing while their checks are current`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        statuses = mapOf("red" to LineStatus("red", 6, "Severe Delays"))
        notices["C"] = listOf(StopDisruption("Station closed"))
        val found = checks.check(trip, waiting, emptyMap())
        assertEquals(
            listOf("C closed", "red Severe Delays"),
            found.signals.map {
                when (it) {
                    is RouteDisruption.Signal.Stop -> "${it.stopId} ${if (it.closed) "closed" else "moved"}"
                    is RouteDisruption.Signal.Line -> "${it.lineId} ${it.status.description}"
                    is RouteDisruption.Signal.Unpredicted -> "${it.lineId} none at ${it.stopId}"
                    is RouteDisruption.Signal.NoneDirect -> "${it.lineId} none direct to ${it.toName}"
                }
            },
        )
        // Asked once for the line, and once for each stop still to reach.
        assertEquals(1, statusReads)
        assertEquals(listOf("A", "C"), closureReads.sorted())
        assertEquals(now.plus(Duration.ofMinutes(5)), found.until)
        // Each signal with its own time, so letting go of one leaves the rest by theirs (Codex on #519).
        assertEquals(found.signals.map { it.key }.toSet(), found.stands.keys)
    }

    @Test
    fun `the next board's lines are asked with the trip's, kept as found, and never alerted`() = runTest {
        // A train tapped on the board opens its line's page with a status (maintainer, 2026-10-06).
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val blueDelays = LineStatus("blue", 6, "Severe Delays")
        statuses = mapOf("red" to LineStatus("red", 10, "Good Service"), "blue" to blueDelays)
        val found = checks.check(trip, waiting, emptyMap(), alsoLines = listOf("blue", "red", "", "green"))
        // One request for them all.
        assertEquals(1, statusReads)
        val lines = found.lines!!
        assertEquals(setOf("red", "blue", "green"), lines.asked)
        assertEquals(blueDelays, lines.statuses["blue"])
        // TfL left one out: no status, so it couldn't be checked.
        assertNull(lines.statuses["green"])
        assertTrue(lines.at != null)
        // Not the trip's line, so not its alert.
        assertTrue(found.signals.isEmpty())
        // A request that failed has them all asked, with no status and no time.
        statusesFail = true
        val failed = checks.check(trip, waiting, emptyMap(), alsoLines = listOf("blue")).lines!!
        assertEquals(setOf("red", "blue"), failed.asked)
        assertTrue(failed.statuses.isEmpty())
        assertNull(failed.at)
    }

    @Test
    fun `a coming station's other notice is a note that stands while its check is current, with nothing to alert`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        statuses = mapOf("red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"))
        notices["C"] = listOf(StopDisruption("Lift out of order"))
        val found = checks.check(trip, waiting, emptyMap())
        // Nothing to alert, but the lift is said on the trip's screen (maintainer, 2026-10-04).
        assertEquals(emptyList<RouteDisruption.Signal>(), found.signals)
        assertEquals(listOf("C" to "Lift out of order"), found.notes.map { it.stopId to it.text })
        assertTrue(checkNotNull(found.notesUntil).isAfter(t0))
        // No longer than the stop's own listing of it, its check current or not (Codex, #567).
        notices["C"] = listOf(StopDisruption("Lift out of order", t0, at(10)))
        now = at(6)
        assertEquals(at(10), checks.check(trip, waiting, emptyMap()).notesUntil)
    }

    @Test
    fun `a station's note at an interchange is titled by it, leading with none of its names, each asked for once`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        hubs = mapOf("C" to "HUBKGX")
        hubInfos = mapOf("HUBKGX" to HubInfo("King's Cross & St Pancras International", listOf("King's Cross St. Pancras", "St Pancras International")))
        notices["C"] = listOf(StopDisruption("St Pancras International: Lift out of order"))
        // Not yet named, the note keeps the stop's own name, and the check doesn't wait on the lookup
        // (Codex, #567): it goes on in the background, for the next check.
        assertEquals(listOf("C"), checks.check(trip, waiting, emptyMap()).notes.map { it.stopName })
        advanceUntilIdle()
        now = at(6)
        val found = checks.check(trip, waiting, emptyMap())
        // Led by another member's spelling, still stripped (Codex, #567).
        assertEquals(
            listOf("King's Cross & St Pancras International" to "Lift out of order"),
            found.notes.map { it.stopName to it.text },
        )
        advanceUntilIdle()
        assertEquals(listOf("HUBKGX"), hubReads)
        // Named already by the list's lookup, it costs nothing more.
        hubReads.clear()
        hubNames["HUBX"] = HubInfo("Example Hub")
        hubs = mapOf("C" to "HUBX")
        notices["C"] = listOf(StopDisruption("Lift out of order"))
        now = at(12)
        assertEquals(listOf("Example Hub"), checks.check(trip, waiting, emptyMap()).notes.map { it.stopName })
        assertEquals(emptyList<String>(), hubReads)
    }

    @Test
    fun `a station's note keeps its own name when its interchange can't be named, and asks again`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        hubs = mapOf("C" to "HUBKGX")
        notices["C"] = listOf(StopDisruption("Lift out of order"))
        val found = checks.check(trip, waiting, emptyMap())
        assertEquals(listOf("C" to "Lift out of order"), found.notes.map { it.stopName to it.text })
        advanceUntilIdle()
        assertTrue(logged.any { it.startsWith("on the way: hub lookup failed") })
        now = at(6)
        assertEquals(listOf("C"), checks.check(trip, waiting, emptyMap()).notes.map { it.stopName })
        advanceUntilIdle()
        assertEquals(listOf("HUBKGX", "HUBKGX"), hubReads)
    }

    @Test
    fun `a check weighs its alerts from a single-thread caller, on the worker`() {
        // AGENTS.md *Main thread*: the service refreshes from Main; the signals are worked out on [io]
        // (Codex on #519).
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        try {
            val checks = checks(StopClosureCache(), worker)
            // A bus line's several alerts, in a list that notes each thread reading it: weighing them for
            // the dismissals, the routes and the signals is only ever done on the worker (Codex on #519).
            val bus = TripLeg("bus", "99", "99", "b3", "b3", "b4", "b4", at(5), at(15))
            val busTrip = ActiveTrip(TripRoute(listOf(bus)), "b4", startedAt = t0)
            routes = mapOf(
                "99" to LineSequence(
                    listOf(app.stopdash.domain.LineRoute("North", listOf("b1", "b2", "b3", "b4"))),
                    mapOf("b1" to "Alpha Road", "b2" to "Example Street", "b3" to "Gamma Road", "b4" to "Beta Road"),
                ),
            )
            val read = mutableListOf<String>()
            val alerts = listOf(
                app.stopdash.domain.LineAlert(6, "Diversion", "Bus stop 'Alpha Road' will not be served."),
                app.stopdash.domain.LineAlert(6, "Diversion", "Bus stop 'Beta Road' will not be served."),
            )
            statuses = mapOf("99" to LineStatus("99", 6, "Diversion", alerts.first().fullText, underWay = Watched(alerts, read)))
            // The rider's dismissals too, settled against what's live there (Codex on #519).
            dismissed = WatchedSet(setOf(DismissedAlert("line:elsewhere", "Minor Delays")), read)
            val found = kotlinx.coroutines.runBlocking(caller) { checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap()) }
            assertEquals(listOf("Bus stop 'Beta Road' will not be served."), found.signals.filterIsInstance<RouteDisruption.Signal.Line>().map { it.status.fullText })
            assertTrue(read.isNotEmpty())
            assertEquals(setOf("worker"), read.toSet())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a bus alert naming only stops off the ride is left out, and logged`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val bus = TripLeg("bus", "99", "99", "b3", "b3", "b4", "b4", at(5), at(15))
        val busTrip = ActiveTrip(TripRoute(listOf(bus)), "b4", startedAt = t0)
        routes = mapOf(
            "99" to LineSequence(
                listOf(app.stopdash.domain.LineRoute("North", listOf("b1", "b2", "b3", "b4"))),
                mapOf("b1" to "Bank Station", "b2" to "Moorgate Station", "b3" to "Alpha Road", "b4" to "Beta Road"),
            ),
        )
        statuses = mapOf("99" to LineStatus("99", 6, "Diversion", "Not serving stops between 'Bank Station' and 'Moorgate Station'.", soleAlert = true))
        assertEquals(RouteDisruption.Found.NONE, checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap()).signalsOnly())
        assertEquals(1, sequenceReads)
        assertTrue(logged.any { it == "on the way: 1 line alert(s) left out, naming only stops off the ride" })
        // Planned work counts from its day, as the signals read it: started today, its route is read;
        // later, or an alert that can't sound (minor delays), costs no route (Codex, PR #455).
        val today = java.time.LocalDate.ofInstant(now, java.time.ZoneId.of("Europe/London"))
        fun planned(day: java.time.LocalDate) = mapOf("99" to LineStatus("99", LineStatus.GOOD_SERVICE, "Good Service", planned = listOf(
            app.stopdash.domain.PlannedAlert("Diversion", "Diverted.", day, 6, false),
        )))
        sequenceReads = 0
        statuses = planned(today)
        checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap())
        assertEquals(1, sequenceReads)
        sequenceReads = 0
        statuses = planned(java.time.LocalDate.of(2099, 1, 1))
        checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap())
        statuses = mapOf("99" to LineStatus("99", 9, "Minor Delays", "Minor delays.", soleAlert = true))
        checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap())
        // Nor does one that sounds but can only stay on: severe delays are the whole route's.
        statuses = mapOf("99" to LineStatus("99", 6, "Severe Delays", "Severe delays.", soleAlert = true))
        checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap())
        // Nor one the rider dismissed: it won't sound, wherever it is.
        val diverted = LineStatus("99", 6, "Diversion", "Not serving stops between 'Bank Station' and 'Moorgate Station'.", soleAlert = true)
        statuses = mapOf("99" to diverted)
        dismissed = setOf(DismissedAlert.ofLineStatus(diverted))
        checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap())
        dismissed = emptySet()
        assertEquals(0, sequenceReads)
        // Named on the ride, it's heard.
        statuses = mapOf("99" to LineStatus("99", 6, "Diversion", "Not serving stops between 'Moorgate Station' and 'Beta Road'.", soleAlert = true))
        assertEquals(1, checks.check(busTrip, TripProgress.Waiting(bus, at(5)), emptyMap()).signals.size)
    }

    @Test
    fun `the alerting bus lines' routes are read at once`() = runTest {
        // The check waits on all of them, so not one after another (Codex, PR #455).
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val first = TripLeg("bus", "98", "98", "b1", "b1", "b2", "b2", at(5), at(10))
        val second = TripLeg("bus", "99", "99", "b2", "b2", "b3", "b3", at(12), at(20))
        val busTrip = ActiveTrip(TripRoute(listOf(first, second)), "b3", startedAt = t0)
        statuses = listOf("98", "99").associateWith { LineStatus(it, 6, "Diversion", "Not serving stops between 'Bank Station' and 'Moorgate Station'.", soleAlert = true) }
        routeDelayMillis = 1_000
        checks.check(busTrip, TripProgress.Waiting(first, at(5)), emptyMap())
        assertEquals(2, sequenceReads)
        assertEquals(2, mostRoutesInFlight)
    }

    @Test
    fun `a closure stands no longer than its notice's window`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        // TfL says the closure ends in two minutes, before the lookup goes stale: the alert goes with
        // it rather than standing as current until a check notices (Codex, PR #441).
        notices["C"] = listOf(StopDisruption("Station closed", validTo = at(2)))
        assertEquals(at(2), checks.check(trip, waiting, emptyMap()).until)
        // One with no end stands until its lookup goes stale.
        notices["A"] = listOf(StopDisruption("Station closed"))
        notices["C"] = emptyList()
        now = at(6)
        assertEquals(at(11), checks.check(trip, waiting, emptyMap()).until)
    }

    @Test
    fun `a failed check is unknown, never a signal`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        statuses = mapOf("red" to LineStatus("red", 2, "Suspended"))
        statusesFail = true
        notices["C"] = listOf(StopDisruption("Station closed"))
        closuresFail += "C"
        val found = checks.check(trip, waiting, emptyMap())
        assertEquals(RouteDisruption.Found.NONE, found.signalsOnly())
        assertTrue(logged.any { it.startsWith("on the way: line status failed") })
        assertTrue(logged.any { it.startsWith("on the way closure check failed") })
    }

    @Test
    fun `a stop's lookup is reused while current, and stands only until it isn't`() = runTest {
        val cache = StopClosureCache()
        val checks = checks(cache, StandardTestDispatcher(testScheduler))
        notices["C"] = listOf(StopDisruption("Station closed"))
        checks.check(trip, waiting, emptyMap())
        // Four minutes on, the list's lookup is reused, and the alert stands for the minute it has left.
        now = at(4)
        val reused = checks.check(trip, waiting, emptyMap())
        assertEquals(listOf("A", "C"), closureReads.sorted())
        assertEquals(at(5), reused.until)
        // Past five, it's asked again.
        now = at(6)
        checks.check(trip, waiting, emptyMap())
        assertEquals(4, closureReads.size)
    }

    @Test
    fun `what the rider dismissed stays dismissed, and dismissals that can't be read hide nothing`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val severe = LineStatus("red", 6, "Severe Delays")
        statuses = mapOf("red" to severe)
        dismissed = setOf(DismissedAlert.ofLineStatus(severe))
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()).signalsOnly())
        dismissedFails = true
        assertEquals(1, checks.check(trip, waiting, emptyMap()).signals.size)
        assertTrue(logged.any { it.startsWith("on the way: dismissals unreadable") })
    }

    @Test
    fun `an alert dismissed while the check waits its turn to settle isn't said`() = runTest {
        val cache = StopClosureCache()
        val checks = checks(cache, StandardTestDispatcher(testScheduler))
        val severe = LineStatus("red", 6, "Severe Delays")
        statuses = mapOf("red" to severe)
        // Another screen is settling; this check waits its turn.
        val gate = CompletableDeferred<Unit>()
        val other = launch { cache.settling(kotlinx.coroutines.Dispatchers.Unconfined, sequenceOf("Z")) { gate.await() } }
        advanceUntilIdle()
        val found = async { checks.check(trip, waiting, emptyMap()) }
        advanceUntilIdle()
        // The rider dismisses the line's alert meanwhile.
        dismissed = setOf(DismissedAlert.ofLineStatus(severe))
        gate.complete(Unit)
        other.join()
        assertEquals(RouteDisruption.Found.NONE, found.await().signalsOnly())
    }

    @Test
    fun `a dismissed alert TfL no longer reports is forgotten, so its return is heard`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val severe = LineStatus("red", 6, "Severe Delays")
        dismissed = setOf(DismissedAlert.ofLineStatus(severe))
        statuses = mapOf("red" to severe)
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()).signalsOnly())
        // It ends: with nothing else checking the line, the trip on the way settles the dismissal (Codex, PR #441).
        statuses = mapOf("red" to LineStatus("red", LineStatus.GOOD_SERVICE, "Good Service"))
        checks.check(trip, waiting, emptyMap())
        assertEquals(emptySet<DismissedAlert>(), dismissed)
        // And the same alert coming back later is a signal again.
        statuses = mapOf("red" to severe)
        assertEquals(1, checks.check(trip, waiting, emptyMap()).signals.size)
    }

    @Test
    fun `a dismissed alert is forgotten when TfL answers its line with no status at all`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val severe = LineStatus("red", 6, "Severe Delays")
        dismissed = setOf(DismissedAlert.ofLineStatus(severe))
        statuses = mapOf("red" to severe)
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()).signalsOnly())
        // An answer naming nothing for the line is still a verdict on it, as the list counts it (Codex, PR #441).
        statuses = emptyMap()
        checks.check(trip, waiting, emptyMap())
        assertEquals(emptySet<DismissedAlert>(), dismissed)
        statuses = mapOf("red" to severe)
        assertEquals(1, checks.check(trip, waiting, emptyMap()).signals.size)
        // A failed check is no verdict: a dismissal made meanwhile stays.
        dismissed = setOf(DismissedAlert.ofLineStatus(severe))
        statuses = emptyMap()
        statusesFail = true
        checks.check(trip, waiting, emptyMap())
        assertEquals(1, dismissed.size)
    }

    @Test
    fun `a dismissed stop notice that's gone is forgotten, so its return is heard, unless the stop couldn't be checked`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val closed = listOf(StopDisruption("Station closed"))
        val card = RouteDisruption.closureCards(trip, waiting, mapOf("C" to closed), emptyMap(), now).single()
        dismissed = setOf(DismissedAlert.ofStopClosure(card))
        notices["C"] = closed
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()).signalsOnly())
        // A failed lookup of the stop isn't evidence it reopened: the dismissal stays.
        now = at(6)
        closuresFail += "C"
        checks.check(trip, waiting, emptyMap())
        assertEquals(1, dismissed.size)
        // It reopens: settled as the list settles it (Codex, PR #441).
        now = at(12)
        closuresFail.clear()
        notices["C"] = emptyList()
        checks.check(trip, waiting, emptyMap())
        assertEquals(emptySet<DismissedAlert>(), dismissed)
        // And the same notice coming back later is a signal again.
        now = at(18)
        notices["C"] = closed
        assertEquals(1, checks.check(trip, waiting, emptyMap()).signals.size)
    }

    @Test
    fun `a stop at an interchange settles only its own dismissals, never the interchange's`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        hubs = mapOf("C" to "HUBX")
        val closed = listOf(StopDisruption("Station closed"))
        val placed = mapOf("C" to RouteDisruption.StopPlace(hub = "HUBX"))
        // Dismissed at the interchange (on the list, say, where another of its stops still has the
        // notice), and on the stop's own card (a journey's destination).
        val atHub = DismissedAlert.ofStopClosure(RouteDisruption.closureCards(trip, waiting, mapOf("C" to closed), placed, now).single())
        val atStop = DismissedAlert.ofStopClosure(RouteDisruption.closureCards(trip, waiting, mapOf("C" to closed), emptyMap(), now).single())
        assertEquals("HUBX", atHub.alertKey)
        assertEquals("C", atStop.alertKey)
        dismissed = setOf(atHub, atStop)
        // The stop is checked clear: its own dismissal goes, the interchange's stays, since this trip
        // didn't look at the interchange's other stops (Codex, PR #441).
        checks.check(trip, waiting, emptyMap())
        assertEquals(setOf(atHub), dismissed)
        // And while the stop still has the notice, its own dismissal stays too.
        dismissed = setOf(atHub, atStop)
        notices["C"] = closed
        now = at(6)
        checks.check(trip, waiting, emptyMap())
        assertEquals(setOf(atHub, atStop), dismissed)
        // A closure alone is no note, so its interchange's names are never asked for (Codex, #567).
        assertEquals(emptyList<String>(), hubReads)
    }

    @Test
    fun `a line's route is asked for once a check, even when it can't be had`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        notices["A"] = listOf(StopDisruption("Station closed"))
        notices["C"] = listOf(StopDisruption("Station closed"))
        checks.check(trip, waiting, emptyMap())
        // Both ends of the ride are placed from its line's route: one request, not one an end (Codex, PR #441).
        assertEquals(1, sequenceReads)
    }

    @Test
    fun `no route is asked for when no stop ahead has a notice`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        statuses = mapOf("red" to LineStatus("red", 6, "Severe Delays"))
        // A route only places a stop's notice; with none ahead, a check asks no route (Codex, PR #441).
        assertEquals(1, checks.check(trip, waiting, emptyMap()).signals.size)
        assertEquals(0, sequenceReads)
    }

    @Test
    fun `nothing is asked once the trip has arrived`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val found = checks.check(trip, TripProgress.Arrived, emptyMap())
        assertEquals(RouteDisruption.Found.NONE, found.signalsOnly())
        assertNull(found.until)
        assertEquals(0, statusReads)
        assertEquals(emptyList<String>(), closureReads)
    }
}

// [items], noting the thread of each read in [read], as [Watched] does for a list.
private class WatchedSet<T>(private val items: Set<T>, private val read: MutableList<String>) : AbstractSet<T>() {
    private fun seen() { synchronized(read) { read += Thread.currentThread().name.substringBefore(" @") } }

    override val size: Int get() = items.size.also { seen() }

    override fun iterator(): Iterator<T> = items.iterator().also { seen() }
}
