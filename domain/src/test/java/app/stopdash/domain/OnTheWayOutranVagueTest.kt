package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A vague fix (a network one, sure to 900 m) can't place a rider on board, but it can bound how far on they could be:
 * a train followed past the stop after that isn't theirs (maintainer, 2026-10-23: such a fix put the rider by Camden
 * Town while the train followed neared Highgate). A line from K by S1 to S5 and N, its stations about 1 km apart.
 * Synthetic stops and positions.
 */
class OnTheWayOutranVagueTest {
    private val t0 = Instant.parse("2026-10-23T14:00:00Z")
    private val km = 0.009
    private val ids = listOf("K", "S1", "S2", "S3", "S4", "S5", "N")
    private val positions = ids.withIndex().associate { (i, id) -> id to Coordinates(51.50 + i * km, -0.12) }
    private val ride = TripLeg("tube", "black", "Black", "K", "K", "N", "N", t0, t0.plusSeconds(900), path = ids.drop(1))
    private val sequence = LineSequence(
        routes = listOf(LineRoute("K-N", ids)),
        stopNames = ids.associateWith { it },
        stopPositions = positions.mapValues { (_, c) -> c.latitude to c.longitude },
    )

    // A call still to come, as any train past the ride's end and due off would have none.
    private val call = VehicleCall("X", "X", null, t0)

    private fun fix(at: String, accuracy: Float = 900f, ageSeconds: Long = 7) =
        LocationFix(positions.getValue(at), isFallback = false, accuracyMeters = accuracy, ageMillis = ageSeconds * 1000)

    @Test
    fun `a train past the stop after the furthest the rider could be isn't theirs`() {
        // Sure to 900 m by S1, 7 s ago: twice that, the slack and 7 s at the fastest reach S3 (2 km on), not S4, so they're
        // short of S4 at most. The train next due at S5 has passed S4: they're placed short of S4, as far on as they could be.
        assertEquals(OnTheWay.Along(3, atStop = false), OnTheWay.outranVague(ride, 4, fix("S1"), positions, sequence, listOf(call)))
    }

    @Test
    fun `a train they could be on stays theirs`() {
        // Next due at S4, it has passed only S3, which they could be at.
        assertNull(OnTheWay.outranVague(ride, 3, fix("S1"), positions, sequence, listOf(call)))
        // Two minutes old, the fix reaches past S4.
        assertNull(OnTheWay.outranVague(ride, 4, fix("S1", ageSeconds = 120), positions, sequence, listOf(call)))
    }

    @Test
    fun `a fix reaching only the boarding stop, or a stop up to the train not placed, can't tell`() {
        assertNull(OnTheWay.outranVague(ride, 4, fix("K", accuracy = 150f, ageSeconds = 0), positions, sequence, listOf(call)))
        assertNull(OnTheWay.outranVague(ride, 4, fix("S1"), positions - "S2", sequence, listOf(call)))
    }

    @Test
    fun `a vague fix bounds a ride but is acted on for nothing else`() {
        val vague = fix("S1")
        assertEquals(true, OnTheWay.boundsRide(vague))
        assertEquals(false, OnTheWay.placesOnRide(vague))
        assertEquals(true, OnTheWay.tooVagueToAct(vague))
        assertEquals(false, OnTheWay.tooVagueToAct(fix("S1", accuracy = 20f)))
        assertEquals(false, OnTheWay.boundsRide(vague.copy(isCoarse = true)))
        assertEquals(false, OnTheWay.boundsRide(vague.copy(isFallback = true)))
        assertEquals(false, OnTheWay.boundsRide(vague.copy(accuracyMeters = null)))
    }

    @Test
    fun `each fix on board is logged against the train by stop counts only`() {
        assertEquals(
            "on the way: fix 900 m, 7 s old, short of stop 4 at most of 6 on line black; train followed next due at stop 5",
            OnTheWay.fixAgainstTrainNote(ride, fix("S1"), positions, 4),
        )
        assertEquals(
            "on the way: fix 20 m, 7 s old, at stop 1 of 6 on line black; train followed past every stop",
            OnTheWay.fixAgainstTrainNote(ride, fix("S1", accuracy = 20f), positions, 6),
        )
    }

    // On board the train followed, seen on it, its calls counting from S1 next: six stops to go.
    private val named = ride.copy(pathNames = ids.drop(1))
    private val onBoard = OnTheWay.follow(
        ActiveTrip(TripRoute(listOf(named)), "N", startedAt = t0),
        Departure("black", "Black", "inbound", "N", null, t0, "tube", vehicleId = "8"),
    ).copy(boarded = true, boardedAt = t0, onBoardSeen = true)
    private val counted = TripProgress.Riding(named, "S1", 6, null, getOffSoon = false)

    @Test
    fun `stops a vague fix can't reach are behind the rider`() {
        // Sure to 900 m by S4: twice that and the slack reach S3, not S2. However far off it is, they're past S2.
        val moved = OnTheWay.vaguePastCalls(onBoard, counted, fix("S4", ageSeconds = 0), positions, t0)
        assertEquals(2, moved?.seenAlongStop)
        assertEquals(t0, moved?.seenAlongAt)
        assertEquals(4, (OnTheWay.countedOn(moved!!, counted) as TripProgress.Riding).stopsLeft)
    }

    @Test
    fun `a vague fix never counts back, nor to where they get off`() {
        // Two minutes old, it reaches back to where they boarded: nothing is behind them for sure.
        assertNull(OnTheWay.vaguePastCalls(onBoard, counted, fix("S4", ageSeconds = 120), positions, t0))
        // Counted that far already.
        assertNull(OnTheWay.vaguePastCalls(onBoard.copy(seenAlongStop = 2), counted, fix("S4", ageSeconds = 0), positions, t0))
        // Reaching only where they get off: the stop before it is as far as a vague fix ever counts them, and that's a
        // stop that would end nothing, so it moves nothing here.
        assertNull(OnTheWay.vaguePastCalls(onBoard, counted, fix("N", accuracy = 150f, ageSeconds = 0), positions, t0))
        // A sure fix is counted by its own rule, not this one.
        assertNull(OnTheWay.vaguePastCalls(onBoard, counted, fix("S4", accuracy = 20f, ageSeconds = 0), positions, t0))
        // Nor before they're seen on board.
        assertNull(OnTheWay.vaguePastCalls(onBoard, counted.copy(seen = false), fix("S4", ageSeconds = 0), positions, t0))
    }

    @Test
    fun `a ride curving back within reach of an earlier stop keeps the train`() {
        // K, A, B, C, D, E: B and C 2 km out, D back beside A. A fix by A reaches D too: a train next due at D may be
        // theirs, though it has passed B and C (Codex, #1204).
        val loop = mapOf(
            "K" to Coordinates(51.500, -0.12), "A" to Coordinates(51.509, -0.12), "B" to Coordinates(51.536, -0.12),
            "C" to Coordinates(51.536, -0.08), "D" to Coordinates(51.509, -0.115), "E" to Coordinates(51.509, -0.10),
        )
        val bend = TripLeg("tube", "black", "Black", "K", "K", "E", "E", t0, t0.plusSeconds(900), path = listOf("A", "B", "C", "D", "E"))
        val route = LineSequence(
            routes = listOf(LineRoute("K-E", listOf("K", "A", "B", "C", "D", "E"))),
            stopNames = loop.mapValues { it.key },
            stopPositions = loop.mapValues { (_, c) -> c.latitude to c.longitude },
        )
        val byA = LocationFix(loop.getValue("A"), isFallback = false, accuracyMeters = 300f, ageMillis = 0)
        assertNull(OnTheWay.outranVague(bend, 3, byA, loop, route, listOf(call)))
    }

    @Test
    fun `a train past where they get off, its calls gone, isn't theirs while the fix can't reach there`() {
        // Sure to 900 m by S4: reaching S5, not N, so they're short of N at most. A train with no calls left, past every
        // stop, has passed N too.
        assertEquals(OnTheWay.Along(5, atStop = false), OnTheWay.outranVague(ride, 6, fix("S4", ageSeconds = 0), positions, sequence, emptyList()))
        // With calls left, at the path's end only means calls off it: nothing says it passed S5 (Codex, #1204).
        assertNull(OnTheWay.outranVague(ride, 6, fix("S4", ageSeconds = 0), positions, sequence, listOf(call)))
    }

    @Test
    fun `track between two stops out of reach can still be in reach`() {
        // K, A, B, C, D, E, the ride crossing itself: the stretch from A to B runs through D. A fix sure to 900 m at D
        // reaches none of A, B, C or E, but someone halfway from A to B is there too, B their next stop (Codex, #1204).
        val cross = mapOf(
            "K" to Coordinates(51.40, -0.12), "A" to Coordinates(51.45, -0.12), "B" to Coordinates(51.55, -0.12),
            "C" to Coordinates(51.55, -0.20), "D" to Coordinates(51.50, -0.12), "E" to Coordinates(51.50, -0.30),
        )
        val ids = listOf("A", "B", "C", "D", "E")
        val crossing = TripLeg("tube", "black", "Black", "K", "K", "E", "E", t0, t0.plusSeconds(900), path = ids, pathNames = ids)
        val crossed = OnTheWay.follow(
            ActiveTrip(TripRoute(listOf(crossing)), "E", startedAt = t0),
            Departure("black", "Black", "inbound", "E", null, t0, "tube", vehicleId = "8"),
        ).copy(boarded = true, boardedAt = t0, onBoardSeen = true)
        val atD = LocationFix(cross.getValue("D"), isFallback = false, accuracyMeters = 900f, ageMillis = 0)
        // Counted from A next: past A for sure, not past B, so counted from B, never from D.
        val moved = OnTheWay.vaguePastCalls(crossed, TripProgress.Riding(crossing, "A", 5, null, getOffSoon = false), atD, cross, t0)
        assertEquals(1, moved?.seenAlongStop)
    }
}
