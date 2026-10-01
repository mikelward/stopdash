package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of a ride's lines a trip on the way may follow, on synthetic stops A–C and X, and example lines. */
class RideLineChecksTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))
    private var now = t0

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val route = TripRoute(listOf(ride))

    private var statuses: Map<String, LineStatus> = emptyMap()
    private var statusesFail = false
    private var statusReads = 0
    private val notices = mutableMapOf<String, List<StopDisruption>>()
    private val closureReads = mutableListOf<String>()
    private var hidden: Set<String> = emptySet()
    private val logged = mutableListOf<String>()

    // Red runs A, B, C; blue the same; purple from A to C by X.
    private val sequences = mapOf(
        "red" to LineSequence(listOf(LineRoute("A-C", listOf("A", "B", "C"))), mapOf("A" to "A", "B" to "B", "C" to "C")),
        "blue" to LineSequence(listOf(LineRoute("A-C", listOf("A", "B", "C"))), mapOf("A" to "A", "B" to "B", "C" to "C")),
        "purple" to LineSequence(listOf(LineRoute("A-C", listOf("A", "X", "C"))), mapOf("A" to "A", "X" to "X", "C" to "C")),
    )

    private val client = object : TflClient {
        override suspend fun arrivals(stopId: String): List<Departure> = emptyList()

        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            statusReads++
            if (statusesFail) throw TflException.Offline(null)
            return lineIds.mapNotNull { statuses[it] }
        }

        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
            closureReads += stopId
            return notices[stopId].orEmpty()
        }
    }

    private fun checks(dispatcher: kotlinx.coroutines.CoroutineDispatcher, cache: StopClosureCache = StopClosureCache()) = RideLineChecks(
        client = client,
        closures = StopClosureChecks(client, cache, Duration.ofMinutes(5), dispatcher, { logged += it }, "on the way"),
        closureCache = cache,
        sequence = { sequences[it] },
        hidden = { hidden },
        clock = { now },
        io = dispatcher,
        warn = { logged += it },
    )

    private fun due(line: String) = Departure(line, line.replaceFirstChar { it.uppercase() }, "outbound", "C", null, at(6), "tube", vehicleId = "v-$line")
    private fun good(line: String) = LineStatus(line, LineStatus.GOOD_SERVICE, "Good Service")

    @Test
    fun `a ride with no other line on its board is the Planner's alone, nothing asked`() = runTest {
        val checks = checks(StandardTestDispatcher(testScheduler))
        assertEquals(listOf(ride), checks.running(route, ride, listOf(due("red"))))
        assertEquals(0, statusReads)
        assertEquals(emptyList<String>(), closureReads)
    }

    @Test
    fun `another line running between the same stops, from stops open, is offered as it runs the ride`() = runTest {
        val checks = checks(StandardTestDispatcher(testScheduler))
        statuses = mapOf("red" to good("red"), "blue" to good("blue"), "purple" to good("purple"))
        val lines = checks.running(route, ride, listOf(due("red"), due("blue"), due("purple")))
        assertEquals(listOf("red", "blue", "purple"), lines.map { it.lineId })
        // Each as its own line runs the ride: purple by its own stop X between.
        assertEquals(listOf("X", "C"), lines.single { it.lineId == "purple" }.path)
        assertEquals(ride, lines.first())
        // Its own boarding and alighting stops are the ones checked open; the Planner's line's aren't asked here.
        assertEquals(setOf("A", "C"), closureReads.toSet())
    }

    @Test
    fun `a line not checked as running, from a closed stop, or avoided, isn't offered`() = runTest {
        val checks = checks(StandardTestDispatcher(testScheduler))
        val board = listOf(due("red"), due("blue"))
        // Blue's status unknown: not offered.
        statuses = mapOf("red" to good("red"))
        assertEquals(listOf(ride), checks.running(route, ride, board))
        // Suspended, once its status is asked again.
        now = at(2)
        statuses = mapOf("red" to good("red"), "blue" to LineStatus("blue", 2, "Suspended"))
        assertEquals(listOf(ride), checks.running(route, ride, board))
        // Running, but where the rider gets off is closed (a fresh check: one kept five minutes is reused).
        now = at(4)
        statuses = mapOf("red" to good("red"), "blue" to good("blue"))
        notices["C"] = listOf(StopDisruption("Station closed"))
        assertEquals(listOf(ride), checks(StandardTestDispatcher(testScheduler)).running(route, ride, board))
        // Open, and running, but the rider avoids it.
        notices.clear()
        val open = checks(StandardTestDispatcher(testScheduler))
        assertEquals(listOf("red", "blue"), open.running(route, ride, board).map { it.lineId })
        hidden = setOf(HiddenModes.lineKey("blue", "Blue"))
        assertEquals(listOf(ride), open.running(route, ride, board))
    }

    @Test
    fun `a status that can't be had offers no other line, and one answered serves a minute`() = runTest {
        val checks = checks(StandardTestDispatcher(testScheduler))
        val board = listOf(due("red"), due("blue"))
        statusesFail = true
        assertEquals(listOf(ride), checks.running(route, ride, board))
        assertEquals(true, logged.any { it.startsWith("on the way: ride line status failed") })
        statusesFail = false
        statuses = mapOf("red" to good("red"), "blue" to good("blue"))
        val reads = statusReads
        assertEquals(listOf("red", "blue"), checks.running(route, ride, board).map { it.lineId })
        assertEquals(reads + 1, statusReads)
        // Within the minute, the answer serves again; after it, it's asked again.
        now = at(0).plusSeconds(30)
        checks.running(route, ride, board)
        assertEquals(reads + 1, statusReads)
        now = at(2)
        checks.running(route, ride, board)
        assertEquals(reads + 2, statusReads)
        // Past the minute with TfL down: blue's last answer isn't offered on, as running or not (Codex,
        // PR #451).
        statusesFail = true
        now = at(4)
        assertEquals(listOf(ride), checks.running(route, ride, board))
    }
}
