package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rider seen past where they got off a ride ([OnTheWay.pastStop]), on synthetic stops near a stand-in position. */
class PastStopTest {
    private val t0 = Instant.parse("2026-10-06T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))
    private fun atSeconds(seconds: Long) = t0.plusSeconds(seconds)

    // A line running north from A, its stops about 1.1 km apart; W is off to the east, where the walk goes.
    private fun north(steps: Double) = Coordinates(51.5 + 0.01 * steps, -0.12)
    private val w = Coordinates(51.51, -0.105)
    private val line = LineSequence(
        listOf(LineRoute("A ↔ E", listOf("A", "B", "C", "D", "E"))),
        mapOf("A" to "A", "B" to "B", "C" to "C", "D" to "D", "E" to "E"),
        stopPositions = listOf("A", "B", "C", "D", "E").withIndex().associate { (i, id) -> id to (51.5 + 0.01 * i to -0.12) },
    )

    // Off at B at 08:10, then a walk to W and a ride on from there.
    private val ride = TripLeg("tube", "red", "Red", "A", "A", "B", "B", at(5), at(10), path = listOf("B"))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "B", "B", "W", "W", at(10), at(15))
    private val onward = TripLeg("bus", "9", "9", "W", "W", "Z", "Z", at(16), at(30), fromAt = w)
    private val walking = ActiveTrip(TripRoute(listOf(ride, walk, onward)), "Z", startedAt = t0, legIndex = 1, legStartedAt = at(10))

    private fun fix(at: Coordinates, accuracy: Float = 20f) = LocationFix(at, isFallback = false, accuracyMeters = accuracy)

    @Test
    fun `the stops of the line on past where the rider gets off, the way it runs`() {
        assertEquals(listOf(listOf("C", "D", "E")), OnTheWay.onward(ride, line))
        // Ridden the other way, on a route listed only one way: run backwards.
        val south = ride.copy(fromId = "D", toId = "C", path = listOf("C"))
        assertEquals(listOf(listOf("B", "A")), OnTheWay.onward(south, line))
        // A loop calling at B twice: from the visit the ride's path reaches, not the first (Codex, #635).
        val loop = line.copy(routes = listOf(LineRoute("A loop", listOf("A", "B", "C", "D", "B", "E"))))
        assertEquals(listOf(listOf("C", "D", "B")), OnTheWay.onward(ride, loop))
        assertEquals(listOf(listOf("E")), OnTheWay.onward(ride.copy(path = listOf("B", "C", "D", "B")), loop))
        // Two branches from A that rejoin at B with as many stops between, then part again: only the one the
        // ride's path runs by is its way on (Codex, #635).
        val rejoin = line.copy(routes = listOf(LineRoute("via X", listOf("A", "X", "B", "C")), LineRoute("via Y", listOf("A", "Y", "B", "D"))))
        assertEquals(listOf(listOf("C")), OnTheWay.onward(ride.copy(path = listOf("X", "B")), rejoin))
        // A path that names neither: by as many stops, as before.
        assertEquals(listOf(listOf("C"), listOf("D")), OnTheWay.onward(ride.copy(path = listOf("Q", "B")), rejoin))
        // A to B on each of two laps: which one they boarded can't be told, so on from either (Codex, #635).
        val twice = line.copy(routes = listOf(LineRoute("A loop", listOf("A", "B", "C", "A", "B", "D"))))
        assertEquals(listOf(listOf("C", "A", "B"), listOf("D")), OnTheWay.onward(ride, twice))
        // A branch listing B by a sibling id of the same station: seen from B all the same (Codex, #635).
        val sibling = line.copy(
            routes = line.routes + LineRoute("A ↔ Y", listOf("A", "B2", "X", "Y")),
            stopNames = line.stopNames + mapOf("B2" to "B", "X" to "X", "Y" to "Y"),
            stopHubs = mapOf("B" to "HUBB", "B2" to "HUBB"),
        )
        assertEquals(listOf(listOf("C", "D", "E"), listOf("X", "Y")), OnTheWay.onward(ride, sibling))
        // A bus, its path naming stop pairs where its routes list poles: matched pole by pair, so only the
        // branch it runs by is its way on (Codex, #635).
        val bus = line.copy(
            routes = listOf(LineRoute("via P", listOf("A", "P1", "B", "C")), LineRoute("via Q", listOf("A", "Q1", "B", "D"))),
            stopAreas = mapOf("P1" to "GP", "Q1" to "GQ"),
        )
        assertEquals(listOf(listOf("C")), OnTheWay.onward(ride.copy(path = listOf("GP", "B")), bus))
        // A bus left on the Planner's own poles, A1 and B1, where the route lists the pairs' other poles: matched by
        // pair at either end (Codex, #635).
        val otherPoles = bus.copy(
            routes = listOf(LineRoute("via P", listOf("A2", "P1", "B2", "C"))),
            stopAreas = mapOf("P1" to "GP", "A2" to "GA", "B2" to "GB"),
        )
        val onPlannerPoles = ride.copy(fromId = "A1", fromArea = "GA", toId = "B1", toArea = "GB", path = listOf("GP", "B1"))
        assertEquals(listOf(listOf("C")), OnTheWay.onward(onPlannerPoles, otherPoles))
        // A route that doesn't run the ride: none.
        assertEquals(emptyList<List<String>>(), OnTheWay.onward(ride.copy(toId = "Q"), line))
    }

    @Test
    fun `seen at the next stop sooner than they could have walked there, they went past`() {
        // At C two minutes after getting off at B: over a kilometer, a train's pace.
        assertEquals(OnTheWay.Past(0, "C", "C"), OnTheWay.pastStop(walking, fix(north(2.0)), line, at(12)))
        // Between C and D, near the line: heading for D.
        assertEquals(OnTheWay.Past(0, "D", "D"), OnTheWay.pastStop(walking, fix(north(2.5)), line, at(13)))
        // Just past B, near the line: heading for C.
        assertEquals(OnTheWay.Past(0, "C", "C"), OnTheWay.pastStop(walking, fix(north(1.6)), line, atSeconds(10 * 60 + 90)))
    }

    @Test
    fun `not where a walk could have taken them, near where they got off, off the line or long after`() {
        // Ten minutes for 1.1 km: a walk's pace.
        assertNull(OnTheWay.pastStop(walking, fix(north(2.0)), line, at(20)))
        // Still near B.
        assertNull(OnTheWay.pastStop(walking, fix(north(1.3)), line, at(11)))
        // As far, but off to the side of the line.
        assertNull(OnTheWay.pastStop(walking, fix(Coordinates(51.515, -0.1)), line, at(11)))
        // Long gone: past the window a train's rider is looked for.
        assertNull(OnTheWay.pastStop(walking, fix(north(4.0)), line, at(30)))
        // A fix too uncertain to say they're clear of B.
        assertNull(OnTheWay.pastStop(walking, fix(north(1.5), accuracy = 600f), line, at(11)))
    }

    @Test
    fun `past a fork, each branch is its own way on`() {
        // From B the line runs on north to C, or east to X then Y. Seen between X and Y: heading for Y,
        // never on a way between C and X that no train runs (Codex, #635).
        val forked = line.copy(
            routes = line.routes + LineRoute("A ↔ Y", listOf("A", "B", "X", "Y")),
            stopNames = line.stopNames + mapOf("X" to "X", "Y" to "Y"),
            stopPositions = line.stopPositions + mapOf("X" to (51.51 to -0.104), "Y" to (51.51 to -0.088)),
        )
        assertEquals(listOf(listOf("C", "D", "E"), listOf("X", "Y")), OnTheWay.onward(ride, forked))
        assertEquals(OnTheWay.Past(0, "Y", "Y"), OnTheWay.pastStop(walking, fix(Coordinates(51.51, -0.096)), forked, at(12)))
        // Between C and X, off both ways: not past.
        assertNull(OnTheWay.pastStop(walking, fix(Coordinates(51.515, -0.11)), forked, at(12)))
        // Seen past it at C: followed on that branch only, never to Y, which their train doesn't reach (Codex, #635).
        val atC = walking.copy(pastLeg = 0, pastAtId = "C", pastAtName = "C")
        assertNull(OnTheWay.pastStop(atC, fix(Coordinates(51.51, -0.088)), forked, at(14), Int.MAX_VALUE, following = true))
        assertEquals(OnTheWay.Past(0, "D", "D"), OnTheWay.pastStop(atC, fix(north(3.0)), forked, at(14), Int.MAX_VALUE, following = true))
    }

    @Test
    fun `a stop the trip goes on to still shapes the line`() {
        // B to D bends east by C, where the walk goes: the way is B, C, D, never straight from B to D (Codex, #635).
        val c = Coordinates(51.52, -0.105)
        val bent = line.copy(stopPositions = line.stopPositions + ("C" to (c.latitude to c.longitude)))
        val toC = walking.copy(route = TripRoute(listOf(ride, walk.copy(toId = "C", toName = "C"), onward.copy(fromId = "C", fromAt = c))))
        // On the bend between B and C, at a train's pace: past, and planned again from D, as the trip goes to C anyway.
        assertEquals(OnTheWay.Past(0, "D", "D"), OnTheWay.pastStop(toC, fix(Coordinates(51.515, -0.1125)), bent, at(12)))
        // On the straight B to D, off both real stretches: not.
        assertNull(OnTheWay.pastStop(toC, fix(north(2.0)), bent, at(12)))
    }

    @Test
    fun `on a bend, the stop ahead is by where they are along the line, not how far from where they got off`() {
        // B to C runs far east, then C to D comes back toward B. Seen on the way to D, nearer B than C is:
        // C is behind them, D ahead (Codex, #635).
        val hook = line.copy(
            routes = listOf(LineRoute("A ↔ D", listOf("A", "B", "C", "D"))),
            stopPositions = line.stopPositions + mapOf("C" to (51.52 to -0.09), "D" to (51.515 to -0.115)),
        )
        assertEquals(OnTheWay.Past(0, "D", "D"), OnTheWay.pastStop(walking, fix(Coordinates(51.516, -0.11)), hook, at(12)))
    }

    @Test
    fun `a stop the trip goes on to by another id of the same station isn't past it`() {
        // The walk goes to C, which the next ride names C2, the same station (Codex, #635).
        val siblings = line.copy(stopNames = line.stopNames + ("C2" to "C"), stopHubs = mapOf("C" to "HUBC", "C2" to "HUBC"))
        val toC2 = walking.copy(route = TripRoute(listOf(ride, walk.copy(toId = "C2", toName = "C"), onward.copy(fromId = "C2", fromAt = null))))
        assertNull(OnTheWay.pastStop(toC2, fix(north(2.0)), siblings, at(12)))
        // The walk goes to C as one route lists it; another branch lists it as C2, its own platforms: there too,
        // it's where the trip goes (Codex, #635).
        val branch = line.copy(
            routes = line.routes + LineRoute("A ↔ Y", listOf("A", "B", "C2", "Y")),
            stopNames = line.stopNames + mapOf("C2" to "C", "Y" to "Y"),
            stopPositions = line.stopPositions + mapOf("C2" to (51.52 to -0.116), "Y" to (51.53 to -0.112)),
            stopHubs = mapOf("C" to "HUBC", "C2" to "HUBC"),
        )
        val toC = walking.copy(route = TripRoute(listOf(ride, walk.copy(toId = "C", toName = "C"), onward.copy(fromId = "C", fromAt = north(2.0)))))
        assertNull(OnTheWay.pastStop(toC, fix(Coordinates(51.52, -0.116)), branch, at(12)))
    }

    @Test
    fun `first seen within a few stops, then followed as far on as their train goes`() {
        val long = line.copy(
            routes = listOf(LineRoute("A ↔ G", listOf("A", "B", "C", "D", "E", "F", "G"))),
            stopNames = line.stopNames + mapOf("F" to "F", "G" to "G"),
            stopPositions = line.stopPositions + mapOf("F" to (51.55 to -0.12), "G" to (51.56 to -0.12)),
        )
        // At F, four stops on: not where they're first looked for.
        assertNull(OnTheWay.pastStop(walking, fix(north(5.0)), long, at(14)))
        // Already seen past B: still followed, and planned again from F (Codex, #635).
        assertEquals(OnTheWay.Past(0, "F", "F"), OnTheWay.pastStop(walking, fix(north(5.0)), long, at(14), stops = Int.MAX_VALUE))
        // However long they stay on: at G, forty minutes on (Codex, #635).
        assertNull(OnTheWay.pastStop(walking, fix(north(6.0)), long, at(50), stops = Int.MAX_VALUE))
        assertEquals(OnTheWay.Past(0, "G", "G"), OnTheWay.pastStop(walking, fix(north(6.0)), long, at(50), stops = Int.MAX_VALUE, following = true))
    }

    @Test
    fun `a stop with no place ends the way there, never bridged by a straight line`() {
        // B to D bends east by C, which TfL gives no place for: the straight B to D isn't the line (Codex, #635).
        val unplaced = line.copy(stopPositions = line.stopPositions - "C")
        assertNull(OnTheWay.pastStop(walking, fix(north(1.6)), unplaced, at(12)))
        // At D itself, beyond it, all the same: a stop with no place leaves only its own stretch out (Codex, #635).
        assertEquals(OnTheWay.Past(0, "D", "D"), OnTheWay.pastStop(walking, fix(north(3.0)), unplaced, at(12)))
        // Between D and E, both placed: that stretch still counts, heading for E (Codex, #635).
        assertEquals(OnTheWay.Past(0, "E", "E"), OnTheWay.pastStop(walking, fix(north(3.5)), unplaced, at(12)))
    }

    @Test
    fun `how long since they got off is from when they did, whatever moved the leg after`() {
        // Off at 08:10, the walk then said done by a tap at 08:25, its leg begun then (Codex, #635).
        val tapped = walking.copy(legIndex = 2, legStartedAt = at(25), offAt = at(10))
        // At C at 08:26: sixteen minutes since getting off, past the window, though one since the tap.
        assertNull(OnTheWay.pastStop(tapped, fix(north(2.0)), line, at(26)))
        // Kept as they leave the ride: when seen off at its stop, or by their word, now.
        val riding = walking.copy(legIndex = 0, boarded = true)
        assertEquals(at(12), OnTheWay.rideDone(riding, at(12)).offAt)
        assertEquals(at(14), OnTheWay.atLeg(riding, 1, at(14)).offAt)
        assertEquals(at(14), OnTheWay.atLeg(OnTheWay.atLeg(riding, 1, at(14)), 2, at(16)).offAt)
    }

    @Test
    fun `a path naming the stop by a sibling id doesn't undo the ride's own`() {
        // The route calls B by B2; the ride ends at B, its path at B2 (Codex, #635).
        val siblings = line.copy(
            routes = listOf(LineRoute("A ↔ E", listOf("A", "B2", "C", "D", "E"))),
            stopNames = line.stopNames + ("B2" to "B"),
            stopHubs = mapOf("B" to "HUBB", "B2" to "HUBB"),
        )
        assertEquals(listOf(listOf("C", "D", "E")), OnTheWay.onward(ride.copy(path = listOf("B2")), siblings))
        // And matched stop by stop as so: only the branch by X, not the as-long one by Y (Codex, #635).
        val rejoin = siblings.copy(routes = listOf(LineRoute("via X", listOf("A", "X", "B2", "C")), LineRoute("via Y", listOf("A", "Y", "B", "D"))))
        assertEquals(listOf(listOf("C")), OnTheWay.onward(ride.copy(path = listOf("X", "B2")), rejoin))
        // A path that leaves out where it gets off: matched all the same (Codex, #635).
        assertEquals(listOf(listOf("C")), OnTheWay.onward(ride.copy(path = listOf("X")), rejoin))
    }

    @Test
    fun `a branch listing the stop by a sibling id is measured from that sibling's own place`() {
        // A branch leaving B by B2, a platform 700 m east, then north to X: the rider on that stretch is past
        // it, though nowhere near a line drawn from B's own place to X (Codex, #635).
        val branch = line.copy(
            routes = line.routes + LineRoute("A ↔ X", listOf("A", "B2", "X")),
            stopNames = line.stopNames + mapOf("B2" to "B", "X" to "X"),
            stopHubs = mapOf("B" to "HUBB", "B2" to "HUBB"),
            stopPositions = line.stopPositions + mapOf("B2" to (51.51 to -0.11), "X" to (51.53 to -0.11)),
        )
        assertEquals(OnTheWay.Past(0, "X", "X"), OnTheWay.pastStop(walking, fix(Coordinates(51.515, -0.11)), branch, at(12)))
        // Under 300 m on from B2, though further from B's own place: not yet beyond where they got off (Codex, #635).
        assertNull(OnTheWay.pastStop(walking, fix(Coordinates(51.5125, -0.11)), branch, at(12)))
        // B2 with no place of its own: that way is left out, not measured from B's place (Codex, #635).
        val unplaced = branch.copy(stopPositions = branch.stopPositions - "B2")
        assertNull(OnTheWay.pastStop(walking, fix(Coordinates(51.52, -0.115)), unplaced, at(12)))
    }

    @Test
    fun `with no path, two routes getting off at sibling platforms are each measured from their own`() {
        // A to B with no stops between named; one route calls B, the other its sibling B2, 700 m east, then
        // north to C. Beside B2 to C, far from B to C: past it (Codex, #635).
        val twin = LineSequence(
            listOf(LineRoute("A ↔ C", listOf("A", "B", "C")), LineRoute("A ↔ C via B2", listOf("A", "B2", "C"))),
            mapOf("A" to "A", "B" to "B", "B2" to "B", "C" to "C"),
            stopPositions = mapOf("A" to (51.5 to -0.12), "B" to (51.51 to -0.12), "B2" to (51.51 to -0.11), "C" to (51.53 to -0.11)),
            stopHubs = mapOf("B" to "HUBB", "B2" to "HUBB"),
        )
        val pathless = walking.copy(route = TripRoute(listOf(ride.copy(path = emptyList()), walk, onward)))
        assertEquals(OnTheWay.Past(0, "C", "C"), OnTheWay.pastStop(pathless, fix(Coordinates(51.52, -0.11)), twin, at(12)))
        // B itself with no place, nor the ride's own: B2's way is measured from B2 all the same (Codex, #635).
        val bUnplaced = twin.copy(stopPositions = twin.stopPositions - "B")
        assertEquals(OnTheWay.Past(0, "C", "C"), OnTheWay.pastStop(pathless, fix(Coordinates(51.52, -0.11)), bUnplaced, at(12)))
    }

    @Test
    fun `a later bus stop named by its pair is one the trip goes on to, by either pole`() {
        // The trip boards a bus at D's pair GD next; the route lists D's pole D1. Between C and D1: heading
        // for E, past D1, which the trip goes to anyway (Codex, #635).
        val poles = line.copy(
            routes = listOf(LineRoute("A ↔ E", listOf("A", "B", "C", "D1", "E"))),
            stopNames = line.stopNames + ("D1" to "D"),
            stopPositions = line.stopPositions - "D" + ("D1" to (51.53 to -0.12)),
            stopAreas = mapOf("D1" to "GD"),
        )
        val toGD = TripLeg(TripLeg.WALKING, "", "", "B", "B", "GD", "D", at(10), at(15))
        val bus = TripLeg("bus", "9", "9", "GD", "D", "Z", "Z", at(16), at(30), fromAt = w)
        val trip = ActiveTrip(TripRoute(listOf(ride, toGD, bus)), "Z", startedAt = t0, legIndex = 1, legStartedAt = at(10))
        assertEquals(OnTheWay.Past(0, "E", "E"), OnTheWay.pastStop(trip, fix(north(2.5)), poles, at(12)))
        // Named by a pole the route doesn't list, D9, with its pair: D1 is still where the trip goes (Codex, #635).
        val byPole = trip.copy(route = TripRoute(listOf(ride, toGD.copy(toId = "D9"), bus.copy(fromId = "D9", fromArea = "GD"))))
        assertEquals(OnTheWay.Past(0, "E", "E"), OnTheWay.pastStop(byPole, fix(north(2.5)), poles, at(12)))
        // Boarding there: each of the pair's poles is a place it boards (Codex, #635).
        assertTrue(Coordinates(51.53, -0.12) in OnTheWay.boardingPlaces(bus.copy(fromId = "D9", fromArea = "GD"), poles))
        // Got off a bus at pole B9 of pair GB, the route calling it at B1 only: back at B1 counts (Codex, #635).
        val pairs = line.copy(routes = listOf(LineRoute("A ↔ C", listOf("A", "B1", "C"))), stopAreas = mapOf("B1" to "GB"),
            stopPositions = line.stopPositions + ("B1" to (51.51 to -0.12)) - "B")
        val offBus = walking.copy(route = TripRoute(listOf(ride.copy(toId = "B9", toArea = "GB", path = listOf("B9")), walk, onward)), pastLeg = 0, pastAtId = "C")
        assertTrue(OnTheWay.backFromPast(offBus, fix(north(1.0)), pairs))
    }

    @Test
    fun `at two stops close together, the nearer`() {
        // C and D only 150 m apart: a fix 60 m short of D is at both, and at D (Codex, #635).
        val close = line.copy(stopPositions = line.stopPositions + ("D" to (51.52135 to -0.12)))
        assertEquals(OnTheWay.Past(0, "D", "D"), OnTheWay.pastStop(walking, fix(Coordinates(51.5208, -0.12), accuracy = 5f), close, at(12)))
    }

    @Test
    fun `followed on from where they were last seen, never back along a line that doubles back`() {
        // Past D the line turns back south to E, beside C to D: marked at D, a rider between D and E, nearer C
        // to D's stretch, heads for E, not back for D (Codex, #635).
        val back = line.copy(stopPositions = line.stopPositions + ("E" to (51.52 to -0.116)))
        val atD = walking.copy(pastLeg = 0, pastAtId = "D", pastAtName = "D")
        assertEquals(OnTheWay.Past(0, "E", "E"), OnTheWay.pastStop(atD, fix(Coordinates(51.525, -0.1195)), back, at(14), Int.MAX_VALUE, following = true))
    }

    @Test
    fun `a walk's change time is taken back too, to when they got off`() {
        // A walk with two minutes to change after it, then the wait: off at 08:10, not 08:12 (Codex, #635).
        val waiting = walking.copy(
            route = TripRoute(listOf(ride, walk.copy(changeAfter = Duration.ofMinutes(2)), onward)), legIndex = 2, legStartedAt = at(17),
        )
        // 1.1 km in eight minutes since getting off: a walk's pace, though a train's in the six left without it.
        assertNull(OnTheWay.pastStop(waiting, fix(north(2.0)), line, at(18)))
    }

    @Test
    fun `looked for on the line ridden, where another of the ride's lines took it`() {
        // Ridden as the blue line runs it, which goes on from B to Q, not C (Codex, #635).
        val blue = ride.copy(lineId = "blue", lineName = "Blue")
        val off = walking.copy(offLeg = blue)
        assertEquals(blue, OnTheWay.offRide(off, 0))
        assertEquals(ride, OnTheWay.offRide(walking, 0))
        val blueLine = line.copy(routes = listOf(LineRoute("A ↔ Q", listOf("A", "B", "Q"))), stopPositions = line.stopPositions + ("Q" to (51.52 to -0.12)))
        assertEquals(OnTheWay.Past(0, "Q", ""), OnTheWay.pastStop(off, fix(north(2.0)), blueLine, at(12)))
        // Kept from the ride as it's left, through the walk after it.
        val riding = ActiveTrip(TripRoute(listOf(ride, walk, onward)), "Z", startedAt = t0, boarded = true, vehicleLeg = blue)
        val left = OnTheWay.rideDone(riding, at(11))
        assertEquals(1, left.legIndex)
        assertEquals(blue, left.offLeg)
    }

    @Test
    fun `moved on by the rider's word, a stop gone past goes, and the line ridden is kept`() {
        // Waiting for the next ride, seen past B: Next onto it, then Back, never brings it back (Codex, #635).
        val past = walking.copy(legIndex = 2, legStartedAt = at(15), pastLeg = 0, pastAtId = "C", pastAtName = "C")
        val aboard = OnTheWay.atStep(past, OnTheWay.Step(2, onBoard = true), at(16))
        assertEquals(-1, aboard.pastLeg)
        assertEquals("", OnTheWay.atStep(aboard, OnTheWay.Step(2), at(17)).pastAtId)
        // Still off it (Back to the walk): kept.
        assertEquals("C", OnTheWay.atLeg(past, 1, at(16)).pastAtId)
        // Next off a ride another of its lines took: that line kept, as when its train moves them on.
        val blue = ride.copy(lineId = "blue")
        val riding = walking.copy(legIndex = 0, boarded = true, vehicleLeg = blue)
        assertEquals(blue, OnTheWay.atLeg(riding, 1, at(10)).offLeg)
        assertEquals(blue, OnTheWay.atLeg(OnTheWay.atLeg(riding, 1, at(10)), 2, at(15)).offLeg)
        // Past a whole ride by a tap: that ride is the one they're off, as planned, not the blue one before (Codex, #635).
        val twoRides = walking.copy(route = TripRoute(listOf(ride, walk, onward, walk.copy(fromId = "Z", toId = "V"))), offLeg = blue)
        assertEquals(null, OnTheWay.atLeg(twoRides, 3, at(20)).offLeg)
        // Back to the walk after the blue ride: still off it, as it was.
        assertEquals(blue, OnTheWay.atLeg(twoRides.copy(legIndex = 2), 1, at(20)).offLeg)
    }

    @Test
    fun `a stop the trip goes on to anyway isn't past it`() {
        // The walk from B goes to C: there by the plan, however fast.
        val toC = walking.copy(route = TripRoute(listOf(ride, walk.copy(toId = "C", toName = "C"), onward.copy(fromId = "C", fromAt = north(2.0)))))
        assertNull(OnTheWay.pastStop(toC, fix(north(2.0)), line, at(12)))
    }

    @Test
    fun `a fix is asked for as long as a stop gone past stands`() {
        // An hour after getting off, waiting on the next ride: none asked for unmarked, asked for while marked (Codex, #635).
        val waiting = walking.copy(legIndex = 2, legStartedAt = at(15))
        assertFalse(OnTheWay.wantsFix(waiting, at(75)))
        assertTrue(OnTheWay.wantsFix(waiting.copy(pastLeg = 0, pastAtId = "C", pastAtName = "C"), at(75)))
        // On board onward, seen: let go of, and no longer.
        val aboard = OnTheWay.settledPast(waiting.copy(pastLeg = 0, pastAtId = "C", boarded = true, onBoardSeen = true, boardedAt = at(70)))
        assertFalse(OnTheWay.wantsFix(aboard, at(75)))
    }

    @Test
    fun `only while off a ride and not yet on the next`() {
        assertEquals(0, OnTheWay.lastRideOff(walking))
        // Waiting for the next ride after the walk: still off the first.
        assertEquals(0, OnTheWay.lastRideOff(walking.copy(legIndex = 2)))
        // On it: no longer.
        assertNull(OnTheWay.lastRideOff(walking.copy(legIndex = 2, boarded = true)))
        // Seen past the first, and on the next only as its train's time went by: still off the first; seen or
        // said on it, no longer (Codex, #635).
        val marked = walking.copy(legIndex = 2, boarded = true, pastLeg = 0, pastAtId = "C", pastAtName = "C")
        assertEquals(0, OnTheWay.lastRideOff(marked))
        assertNull(OnTheWay.lastRideOff(marked.copy(onBoardSeen = true)))
        // On the first ride: none got off yet.
        assertNull(OnTheWay.lastRideOff(walking.copy(legIndex = 0)))
        // Waiting after the walk, the walk's time is taken back to when they got off.
        val waiting = walking.copy(legIndex = 2, legStartedAt = at(15))
        assertEquals(OnTheWay.Past(0, "C", "C"), OnTheWay.pastStop(waiting, fix(north(2.0)), line, at(12)))
    }

    @Test
    fun `back where they got off, or at the next ride's stop, the stop gone past no longer stands`() {
        val past = walking.copy(pastLeg = 0, pastAtId = "C", pastAtName = "C")
        assertTrue(OnTheWay.backFromPast(past, fix(north(1.0)), line))
        // 400 m on from B, under the 500 m that marked it, but not back: a fix wavering about it doesn't let it go (Codex, #635).
        assertFalse(OnTheWay.backFromPast(past, fix(north(1.36)), line))
        assertTrue(OnTheWay.backFromPast(past, fix(w), line, nextAt = listOf(w)))
        // With no place for the next ride's stop, only back toward where they got off counts.
        assertFalse(OnTheWay.backFromPast(past, fix(w), line))
        assertFalse(OnTheWay.backFromPast(past, fix(north(2.0)), line))
        // Only at the next ride's stop itself, not passing a few hundred metres off it (Codex, #635).
        assertFalse(OnTheWay.backFromPast(past, fix(Coordinates(w.latitude + 0.003, w.longitude)), line, nextAt = listOf(w)))
        assertEquals(onward, OnTheWay.rideAfterPast(past))
        // Followed on another of its lines, waiting for it: that line's ride, its own platform (Codex, #635).
        val other = onward.copy(lineId = "10", lineName = "10", fromId = "W2", fromAt = Coordinates(51.512, -0.1))
        assertEquals(other, OnTheWay.rideAfterPast(past.copy(legIndex = 2, vehicleLeg = other)))
        assertEquals(onward, OnTheWay.rideAfterPast(past.copy(vehicleLeg = other)))
        // Got off where a branch calls B by B2, 700 m east of B's own place: back there counts too (Codex, #635).
        val branch = line.copy(
            routes = line.routes + LineRoute("A ↔ X", listOf("A", "B2", "X")),
            stopNames = line.stopNames + mapOf("B2" to "B", "X" to "X"),
            stopHubs = mapOf("B" to "HUBB", "B2" to "HUBB"),
            stopPositions = line.stopPositions + mapOf("B2" to (51.51 to -0.11), "X" to (51.53 to -0.11)),
        )
        assertTrue(OnTheWay.backFromPast(past, fix(Coordinates(51.51, -0.11)), branch))
        // Without the line's route: by the places kept with the trip alone (Codex, #635).
        val placed = past.copy(route = TripRoute(listOf(ride.copy(toAt = north(1.0)), walk, onward)))
        assertTrue(OnTheWay.backFromPast(placed, fix(north(1.0)), null))
        assertTrue(OnTheWay.backFromPast(past, fix(w), null, nextAt = listOf(w)))
        assertFalse(OnTheWay.backFromPast(placed, fix(north(2.0)), null))
        // The next ride's stop, by every id its line's routes call it, each where it's placed (Codex, #635).
        assertEquals(setOf(Coordinates(51.51, -0.12), Coordinates(51.51, -0.11)), OnTheWay.stationPlaces(branch, "B").toSet())
        // A bus's stop pair: each of its poles, not only their middle (Codex, #635).
        val pair = line.copy(stopAreas = mapOf("P1" to "GP", "P2" to "GP"), stopPositions = mapOf("P1" to (51.5 to -0.12), "P2" to (51.5 to -0.11)))
        assertTrue(OnTheWay.stationPlaces(pair, "GP").containsAll(listOf(Coordinates(51.5, -0.12), Coordinates(51.5, -0.11))))
    }
}
