package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ride on a line whose two branches part after the boarding stop K and join again at N, where the
 * rider gets off: the plan's way by P1–P3, the other by Q1–Q3 (maintainer, 2026-10-09: a train up
 * the other branch is the rider's ride too). Synthetic stops and positions.
 */
class OnTheWayBranchTest {
    private val t0 = Instant.parse("2026-10-09T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private val ride = TripLeg("tube", "black", "Black", "K", "K", "N", "N", at(5), at(20), path = listOf("P1", "P2", "P3", "N"))
    private val trip = ActiveTrip(TripRoute(listOf(ride)), "N", startedAt = t0)

    private val positions = mapOf(
        "K" to (51.50 to -0.12),
        "P1" to (51.51 to -0.11), "P2" to (51.52 to -0.11), "P3" to (51.53 to -0.11),
        "Q1" to (51.51 to -0.13), "Q2" to (51.52 to -0.13), "Q3" to (51.53 to -0.13),
        "N" to (51.54 to -0.12), "Z" to (51.55 to -0.12),
    )
    private val line = LineSequence(
        routes = listOf(
            LineRoute("K-Z via P", listOf("K", "P1", "P2", "P3", "N", "Z")),
            LineRoute("K-Z via Q", listOf("K", "Q1", "Q2", "Q3", "N", "Z")),
        ),
        stopNames = positions.keys.associateWith { "Stop $it" },
        stopPositions = positions,
    )
    private val other = OnTheWay.branchWays(ride, line).single()

    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))
    private fun train(vehicle: String) = Departure("black", "Black", "inbound", "Z", null, at(5), "tube", vehicleId = vehicle)
    private fun fix(stop: String, accuracy: Float = 20f) =
        LocationFix(positions.getValue(stop).let { (lat, lon) -> Coordinates(lat, lon) }, isFallback = false, accuracyMeters = accuracy, ageMillis = 1_000L)

    @Test
    fun `the line's other branch to the same stop is a way to take the ride`() {
        assertEquals(listOf("Q1", "Q2", "Q3", "N"), other.path)
        assertEquals(listOf("Stop Q1", "Stop Q2", "Stop Q3", "Stop N"), other.pathNames)
        assertEquals(ride.copy(path = other.path, pathNames = other.pathNames), other)
        // A line that runs only the plan's way has none.
        assertEquals(emptyList<TripLeg>(), OnTheWay.branchWays(ride, line.copy(routes = line.routes.take(1))))
    }

    @Test
    fun `a loop's other way round is no way to take the ride`() {
        // The plan's route goes on past N round a loop by Y and X back toward K; a route the other way round
        // reaches N by X and Y, which the plan's passes only after N (Codex, #728).
        val loop = line.copy(
            routes = listOf(
                LineRoute("K-N-Y-X", listOf("K", "P1", "P2", "P3", "N", "Y", "X")),
                LineRoute("K-X-Y-N", listOf("K", "X", "Y", "N")),
            ),
        )
        assertEquals(emptyList<TripLeg>(), OnTheWay.branchWays(ride, loop))
        // Nor a route that calls at K twice before N: the long way from its first visit.
        val twice = line.copy(routes = listOf(LineRoute("K-A-K-N", listOf("K", "A", "B", "K", "P1", "P2", "P3", "N"))))
        assertEquals(emptyList<TripLeg>(), OnTheWay.branchWays(ride, twice))
        // A real fork still counts beside a loop.
        assertEquals(listOf(listOf("Q1", "Q2", "Q3", "N")), OnTheWay.branchWays(ride, loop.copy(routes = loop.routes + line.routes[1])).map { it.path })
    }

    @Test
    fun `a train up the other branch is followed on its own stops`() {
        val calls = listOf(call("Q2", 9), call("Q3", 12), call("N", 15))
        // On the plan's way alone it was lost: its calls leave the plan's stops.
        assertTrue(OnTheWay.advance(OnTheWay.follow(trip, train("8")), calls, at(8)).second is TripProgress.Lost)
        // As the other branch runs the ride, it's ridden, its stops counted on its own path.
        val progress = OnTheWay.advance(OnTheWay.follow(trip, train("8"), other), calls, at(8)).second as TripProgress.Riding
        assertEquals("Q2", progress.nextStop)
        assertEquals(3, progress.stopsLeft)
        assertEquals(at(15), progress.getOffAt)
    }

    @Test
    fun `a rider seen two stops ahead of the train followed isn't on it`() {
        val onBoard = OnTheWay.follow(trip, train("8")).copy(boarded = true, boardedAt = at(5))
        val ways = listOf(ride, other).map { it to OnTheWay.ridePositions(it, line) }
        // Followed up the plan's branch, still short of P1, while they're at Q3 on the other: let go, and
        // counted on by where they were seen, a stop from where they get off.
        val behind = listOf(call("P1", 9), call("P2", 11), call("P3", 13), call("N", 15))
        val (way, where) = OnTheWay.aheadOfTrain(onBoard, fix("Q3"), behind, ways)!!
        assertEquals(other, way)
        assertEquals(OnTheWay.Along(2, atStop = true), where)
        val counted = OnTheWay.onBoardAlong(onBoard, where, at(10), on = way)
        assertEquals("", counted.vehicleId)
        val progress = OnTheWay.advance(counted, null, at(10)).second as TripProgress.Riding
        assertEquals("Stop N", progress.nextStop)
        assertEquals(1, progress.stopsLeft)
        assertTrue(progress.byPosition)
        // On the plan's own branch too: seen at P3 while the train is still short of P2.
        assertEquals(ride, OnTheWay.aheadOfTrain(onBoard, fix("P3"), behind.drop(1), ways)?.first)
    }

    @Test
    fun `a train the rider may be on is kept`() {
        val onBoard = OnTheWay.follow(trip, train("8")).copy(boarded = true, boardedAt = at(5))
        val ways = listOf(ride, other).map { it to OnTheWay.ridePositions(it, line) }
        // One stop short of where they're seen: a fix near a stop can be that far out.
        assertNull(OnTheWay.aheadOfTrain(onBoard, fix("P3"), listOf(call("P3", 13), call("N", 15)), ways))
        assertNull(OnTheWay.aheadOfTrain(onBoard, fix("P2"), listOf(call("P2", 11), call("P3", 13), call("N", 15)), ways))
        // Its next call only where they get off: never behind them.
        assertNull(OnTheWay.aheadOfTrain(onBoard, fix("Q3"), listOf(call("N", 15)), ways))
        // A vague fix, or a last-known one, isn't acted on.
        assertNull(OnTheWay.aheadOfTrain(onBoard, fix("Q3", accuracy = 200f), listOf(call("P1", 9), call("N", 15)), ways))
        assertNull(OnTheWay.aheadOfTrain(onBoard, fix("Q3").copy(isFallback = true), listOf(call("P1", 9), call("N", 15)), ways))
        // Not yet on board: the left-behind check's, not this.
        assertNull(OnTheWay.aheadOfTrain(OnTheWay.follow(trip, train("8")), fix("Q3"), listOf(call("P1", 9), call("N", 15)), ways))
    }
}
