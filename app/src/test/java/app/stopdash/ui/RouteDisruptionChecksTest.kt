package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a trip on the way's "route disruption" goes by, on synthetic stops A–C and an example line. */
class RouteDisruptionChecksTest {
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
    private var hubs: Map<String, String> = emptyMap()
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
    }

    private fun checks(cache: StopClosureCache, dispatcher: kotlinx.coroutines.CoroutineDispatcher) = RouteDisruptionChecks(
        client = client,
        closures = StopClosureChecks(client, cache, Duration.ofMinutes(5), dispatcher, { logged += it }, "on the way"),
        closureCache = cache,
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
        sequence = { _: String ->
            sequenceReads++
            null as LineSequence?
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
                }
            },
        )
        // Asked once for the line, and once for each stop still to reach.
        assertEquals(1, statusReads)
        assertEquals(listOf("A", "C"), closureReads.sorted())
        assertEquals(now.plus(Duration.ofMinutes(5)), found.until)
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
        assertEquals(RouteDisruption.Found.NONE, found)
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
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()))
        dismissedFails = true
        assertEquals(1, checks.check(trip, waiting, emptyMap()).signals.size)
        assertTrue(logged.any { it.startsWith("on the way: dismissals unreadable") })
    }

    @Test
    fun `a dismissed alert TfL no longer reports is forgotten, so its return is heard`() = runTest {
        val checks = checks(StopClosureCache(), StandardTestDispatcher(testScheduler))
        val severe = LineStatus("red", 6, "Severe Delays")
        dismissed = setOf(DismissedAlert.ofLineStatus(severe))
        statuses = mapOf("red" to severe)
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()))
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
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()))
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
        assertEquals(RouteDisruption.Found.NONE, checks.check(trip, waiting, emptyMap()))
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
        assertEquals(RouteDisruption.Found.NONE, found)
        assertNull(found.until)
        assertEquals(0, statusReads)
        assertEquals(emptyList<String>(), closureReads)
    }
}
