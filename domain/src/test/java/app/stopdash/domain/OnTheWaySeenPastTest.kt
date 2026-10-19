package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A rider on board a bus counted by its calls, seen past the stop those calls still say is next: TfL's
 * predictions run a stop or so behind a bus (maintainer, 2026-10-19). A straight ride from K by A and B to
 * C, its stops about 220 m apart. Synthetic stops and positions.
 */
class OnTheWaySeenPastTest {
    private val t0 = Instant.parse("2026-10-19T09:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private val ride = TripLeg("bus", "black", "Black", "K", "Stop K", "C", "Stop C", at(5), at(12), path = listOf("A", "B", "C"), pathNames = listOf("Stop A", "Stop B", "Stop C"))
    private val trip = ActiveTrip(TripRoute(listOf(ride)), "C", startedAt = t0)

    private val positions = mapOf(
        "K" to Coordinates(51.500, -0.12), "A" to Coordinates(51.502, -0.12),
        "B" to Coordinates(51.504, -0.12), "C" to Coordinates(51.506, -0.12),
    )

    private fun call(id: String, minutes: Long) = VehicleCall(id, "Stop $id", null, at(minutes))
    private fun train() = Departure("black", "Black", "inbound", "Stop C", null, at(5), "bus", vehicleId = "8")
    private fun fix(stop: String, accuracy: Float = 20f) = LocationFix(positions.getValue(stop), isFallback = false, accuracyMeters = accuracy, ageMillis = 1_000L)

    private val onBoard = OnTheWay.follow(trip, train()).copy(boarded = true, boardedAt = at(5), onBoardSeen = true)

    // The trip's step by the train's [calls], as a refresh works it out.
    private fun riding(trip: ActiveTrip, calls: List<VehicleCall>, now: Instant = at(7)) = OnTheWay.advance(trip, calls, now).second as TripProgress.Riding

    @Test
    fun `seen at a stop the calls still say is next, the count moves on from there`() {
        val lagging = listOf(call("A", 7), call("B", 8), call("C", 9))
        val byCalls = riding(onBoard, lagging)
        assertEquals("Stop A", byCalls.nextStop)
        assertEquals(3, byCalls.stopsLeft)
        val seen = OnTheWay.seenPastCalls(onBoard, byCalls, fix("A"), positions, at(7))!!
        assertEquals(1, seen.seenAlongStop)
        val counted = OnTheWay.countedOn(seen, byCalls) as TripProgress.Riding
        assertEquals("Stop B", counted.nextStop)
        assertEquals(2, counted.stopsLeft)
        // The time where they get off is still the calls'.
        assertEquals(at(9), counted.getOffAt)
        // Still counted by the train, not by position: its calls stand behind it while they're current.
        assertEquals(false, counted.byPosition)
        // Show stops marks A, the stop they're at, not the one they boarded at.
        assertEquals(1, rideStopList(ride, counted, boarded = true)?.here)
        // A refresh with no fix doesn't take it back while the calls lag.
        assertEquals(counted, OnTheWay.countedOn(seen, riding(seen, lagging)))
    }

    @Test
    fun `a next stop the Planner names with a qualifier the calls leave off is still the one counted to`() {
        // As "Hammersmith (H&C)" is "Hammersmith" (Codex, #993).
        val qualified = ride.copy(pathNames = listOf("Stop A (H&C)", "Stop B", "Stop C"))
        val onIt = OnTheWay.follow(ActiveTrip(TripRoute(listOf(qualified)), "C", startedAt = t0), train())
            .copy(boarded = true, boardedAt = at(5), onBoardSeen = true)
        val byCalls = riding(onIt, listOf(call("A", 7), call("B", 8), call("C", 9)))
        assertEquals("Stop A", byCalls.nextStop)
        val seen = OnTheWay.seenPastCalls(onIt, byCalls, fix("A"), positions, at(7))!!
        assertEquals("Stop B", (OnTheWay.countedOn(seen, byCalls) as TripProgress.Riding).nextStop)
    }

    @Test
    fun `a next stop the Planner leaves unnamed isn't counted on from`() {
        // Nothing then shows the calls' count isn't off by a stop the train skips (Codex, #993).
        val unnamed = ride.copy(pathNames = listOf("", "Stop B", "Stop C"))
        val onIt = OnTheWay.follow(ActiveTrip(TripRoute(listOf(unnamed)), "C", startedAt = t0), train())
            .copy(boarded = true, boardedAt = at(5), onBoardSeen = true)
        val byCalls = riding(onIt, listOf(call("A", 7), call("B", 8), call("C", 9)))
        assertNull(OnTheWay.seenPastCalls(onIt, byCalls, fix("A"), positions, at(7)))
    }

    @Test
    fun `seen past the stop before where they get off, it's said one stop out`() {
        val lagging = listOf(call("B", 8), call("C", 9))
        val byCalls = riding(onBoard, lagging, at(5))
        assertEquals(false, byCalls.getOffSoon)
        val seen = OnTheWay.seenPastCalls(onBoard, byCalls, fix("B"), positions, at(5))!!
        val counted = OnTheWay.countedOn(seen, byCalls) as TripProgress.Riding
        assertEquals("Stop C", counted.nextStop)
        assertEquals(1, counted.stopsLeft)
        assertEquals(true, counted.getOffSoon)
    }

    @Test
    fun `calls that catch up, or run ahead, count again`() {
        val seen = OnTheWay.seenPastCalls(onBoard, riding(onBoard, listOf(call("A", 7), call("B", 8), call("C", 9))), fix("A"), positions, at(7))!!
        // The calls now say B is next: as the rider was seen, so nothing changes.
        val caughtUp = riding(seen, listOf(call("B", 8), call("C", 9)))
        assertSame(caughtUp, OnTheWay.countedOn(seen, caughtUp))
        // Further on than where they were seen: the calls' count.
        val ahead = riding(seen, listOf(call("C", 9)))
        assertSame(ahead, OnTheWay.countedOn(seen, ahead))
        assertEquals(1, (OnTheWay.countedOn(seen, ahead) as TripProgress.Riding).stopsLeft)
    }

    @Test
    fun `at the stop before theirs, one a fix there also reaches, the count moves on to theirs`() {
        // C moved to 70 m past B: a fix at B is within reach of where they get off too, and counted from B (Codex, #993).
        val close = positions + ("C" to Coordinates(51.504 + 70.0 / 111_195.0, -0.12))
        val byCalls = riding(onBoard, listOf(call("B", 8), call("C", 9)), at(5))
        val seen = OnTheWay.seenPastCalls(onBoard, byCalls, fix("B"), close, at(5))!!
        val counted = OnTheWay.countedOn(seen, byCalls) as TripProgress.Riding
        assertEquals("Stop C", counted.nextStop)
        assertEquals(1, counted.stopsLeft)
        // Closest to where they get off: nothing moves here, as the ride being done is told elsewhere.
        assertNull(OnTheWay.seenPastCalls(onBoard, byCalls, LocationFix(close.getValue("C"), isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L), close, at(5)))
    }

    @Test
    fun `at two stops a fix reaches both of, the count moves on from the closer`() {
        // B moved to 70 m past A: a fix at A is within reach of both, and counted from A, not B (Codex, #993).
        val close = positions + ("B" to Coordinates(51.502 + 70.0 / 111_195.0, -0.12))
        val byCalls = riding(onBoard, listOf(call("A", 7), call("B", 8), call("C", 9)))
        val seen = OnTheWay.seenPastCalls(onBoard, byCalls, LocationFix(positions.getValue("A"), isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L), close, at(7))!!
        assertEquals(1, seen.seenAlongStop)
        assertEquals("Stop B", (OnTheWay.countedOn(seen, byCalls) as TripProgress.Riding).nextStop)
        // A fix at B, within reach of A too, is counted from B, the one it's at.
        val atB = LocationFix(close.getValue("B"), isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L)
        val calledB = riding(onBoard, listOf(call("B", 8), call("C", 9)))
        val past = OnTheWay.seenPastCalls(onBoard, calledB, atB, close, at(8))!!
        assertEquals(2, past.seenAlongStop)
        assertEquals("Stop C", (OnTheWay.countedOn(past, calledB) as TripProgress.Riding).nextStop)
    }

    @Test
    fun `a fix that doesn't place them past the next call moves nothing`() {
        val calls = listOf(call("A", 7), call("B", 8), call("C", 9))
        val byCalls = riding(onBoard, calls)
        // Too vague, a last-known fallback, or still at the boarding stop.
        assertNull(OnTheWay.seenPastCalls(onBoard, byCalls, fix("A", accuracy = 80f), positions, at(7)))
        assertNull(OnTheWay.seenPastCalls(onBoard, byCalls, fix("A").copy(isFallback = true), positions, at(7)))
        assertNull(OnTheWay.seenPastCalls(onBoard, byCalls, fix("K"), positions, at(7)))
        // At the stop the calls say the train has left, short of the next: no further than they count.
        val pastA = riding(onBoard, listOf(call("B", 8), call("C", 9)))
        assertNull(OnTheWay.seenPastCalls(onBoard, pastA, fix("A"), positions, at(7)))
        // Between stops, however far along the ride's way: only a stop of the ride counts (maintainer, 2026-10-19).
        val between = LocationFix(Coordinates(51.5030, -0.12), isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L)
        assertNull(OnTheWay.seenPastCalls(onBoard, byCalls, between, positions, at(7)))
        // Where they get off: the ride is done, which the trip's own check sees ([OnTheWay.rideDone]).
        assertNull(OnTheWay.seenPastCalls(onBoard, byCalls, fix("C"), positions, at(7)))
        // Already counted by position, or on another of the ride's lines: not counted by these calls.
        assertNull(OnTheWay.seenPastCalls(onBoard, byCalls.copy(byPosition = true), fix("A"), positions, at(7)))
        assertNull(OnTheWay.seenPastCalls(onBoard.copy(vehicleLeg = ride.copy(lineId = "white")), byCalls, fix("A"), positions, at(7)))
        // A train not calling at A, its calls left not counting the path's stops: nothing to compare against (Codex, #993).
        val skipping = riding(onBoard, listOf(call("B", 8), call("C", 9))).copy(stopsLeft = 3)
        assertNull(OnTheWay.seenPastCalls(onBoard, skipping, fix("A"), positions, at(7)))
        assertSame(skipping, OnTheWay.countedOn(onBoard.copy(seenAlongStop = 2), skipping))
        // Seen that far already: kept, not said again.
        val seen = OnTheWay.seenPastCalls(onBoard, byCalls, fix("A"), positions, at(7))!!
        assertNull(OnTheWay.seenPastCalls(seen, byCalls, fix("A"), positions, at(8)))
    }
}
