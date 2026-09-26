package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following a started trip, on synthetic stops A–E and example vehicle ids. */
class OnTheWayTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
    private val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(22), at(30), path = listOf("E"))
    private val trip = ActiveTrip(TripRoute(listOf(ride, walk, second)), "E", startedAt = t0)

    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))
    private fun train(vehicle: String, minutes: Long) =
        Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)

    @Test
    fun `Start follows the next train the rider can catch`() {
        val trains = listOf(train("7", 2), train("", 4), train("9", 6), train("8", 5))
        // Ready at 4 min: the 2-minute train is gone, the 4-minute one has no id to follow.
        assertEquals("8", OnTheWay.pickTrain(trains, at(4))?.vehicleId)
        assertNull(OnTheWay.pickTrain(trains, at(7)))
    }

    @Test
    fun `waits while the train is still due at the boarding stop`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        val (next, progress) = OnTheWay.advance(following, listOf(call("A", 5), call("B", 9), call("C", 14)), at(3))
        assertEquals(TripProgress.Waiting(ride, at(5)), progress)
        assertFalse(next.boarded)
    }

    @Test
    fun `once the train has left the boarding stop the rider is on it`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        val (next, progress) = OnTheWay.advance(following, listOf(call("B", 9), call("C", 14)), at(6))
        assertTrue(next.boarded)
        progress as TripProgress.Riding
        assertEquals("B", progress.nextStop)
        assertEquals(2, progress.stopsLeft)
        assertEquals(at(14), progress.getOffAt)
        assertFalse(progress.getOffSoon)
    }

    @Test
    fun `get off soon from one stop out, or two minutes, and said once`() {
        val following = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        val (oneStop, lastStop) = OnTheWay.advance(following, listOf(call("C", 14)), at(10))
        assertTrue((lastStop as TripProgress.Riding).getOffSoon)
        val (_, twoMinutes) = OnTheWay.advance(following, listOf(call("B", 12), call("C", 13)), at(11))
        assertTrue((twoMinutes as TripProgress.Riding).getOffSoon)
        assertTrue(OnTheWay.shouldWarn(oneStop, lastStop))
        assertFalse(OnTheWay.shouldWarn(OnTheWay.warned(oneStop), lastStop))
    }

    @Test
    fun `off the train, the trip walks on, then waits for the next leg's train`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true, dueOffAt = at(15))
        // Seen due at C at 15, and its calls no longer include C: the rider got off there.
        val (walking, progress) = OnTheWay.advance(onBoard, listOf(call("X", 16)), at(15))
        assertEquals(1, walking.legIndex)
        assertEquals(TripProgress.Walking(walk, at(20)), progress)
        assertEquals("", walking.vehicleId)
        // The walk has its time: the next leg, waiting for a train to be picked.
        val (changed, waiting) = OnTheWay.advance(walking, null, at(20))
        assertEquals(2, changed.legIndex)
        assertEquals(TripProgress.Waiting(second, null), waiting)
    }

    @Test
    fun `a followed train that never calls where the rider gets off is lost, not ridden`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        // Past the boarding stop and not calling at C: the wrong train (or TfL lost it).
        val (next, progress) = OnTheWay.advance(following, listOf(call("Y", 9)), at(6))
        assertEquals(TripProgress.Lost(ride), progress)
        assertEquals(0, next.legIndex)
    }

    @Test
    fun `calls that couldn't be fetched claim nothing`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        assertEquals(TripProgress.Lost(ride), OnTheWay.advance(onBoard, null, at(8)).second)
        assertEquals(TripProgress.Waiting(ride, null), OnTheWay.advance(trip, null, at(1)).second)
    }

    @Test
    fun `past the last leg the trip has arrived`() {
        val last = trip.copy(legIndex = 2, vehicleId = "5", boarded = true, dueOffAt = at(30))
        val (done, progress) = OnTheWay.advance(last, emptyList(), at(30))
        assertEquals(TripProgress.Arrived, progress)
        assertNull(done.leg)
    }

    @Test
    fun `a stop named by another of its ids is the same place`() {
        val station = ride.copy(toId = "940GZZLUXXX", toName = "Stratford")
        val following = trip.copy(route = TripRoute(listOf(station)), vehicleId = "8")
        val calls = listOf(VehicleCall("B", "B", null, at(9)), VehicleCall("910GSTFD", "Stratford", null, at(14)))
        assertEquals(2, (OnTheWay.advance(following, calls, at(6)).second as TripProgress.Riding).stopsLeft)
    }

    @Test
    fun `a leg's candidate trains are its line's, named, reachable and once each`() {
        val other = Departure("blue", "Blue", "outbound", "C", null, at(5), "tube", vehicleId = "4")
        val trains = listOf(train("9", 8), train("8", 5), other, train("", 6), train("8", 5), train("7", 1))
        assertEquals(listOf("8", "9"), OnTheWay.candidates(trains, ride, at(3)).map { it.vehicleId })
    }

    @Test
    fun `only a train calling at the boarding stop and then where the rider gets off takes the leg`() {
        assertTrue(OnTheWay.runsAlong(ride, listOf(call("A", 5), call("B", 9), call("C", 14))))
        // The other way: C before A.
        assertFalse(OnTheWay.runsAlong(ride, listOf(call("C", 5), call("B", 9), call("A", 14))))
        // Another branch: never reaches C.
        assertFalse(OnTheWay.runsAlong(ride, listOf(call("A", 5), call("Z", 9))))
    }

    @Test
    fun `a ride followed by another ride picks its train after the change time`() {
        val first = ride.copy(changeAfter = Duration.ofMinutes(4))
        val direct = ActiveTrip(TripRoute(listOf(first, second)), "E", startedAt = t0, vehicleId = "8", boarded = true, dueOffAt = at(15))
        val (next, _) = OnTheWay.advance(direct, listOf(call("X", 16)), at(15))
        assertEquals(1, next.legIndex)
        assertEquals(at(19), next.legStartedAt)
    }

    @Test
    fun `a change between two rides shows the change time before the next ride`() {
        val first = ride.copy(changeAfter = Duration.ofMinutes(4))
        val direct = ActiveTrip(TripRoute(listOf(first, second)), "E", startedAt = t0, vehicleId = "8", boarded = true, dueOffAt = at(15))
        val (changing, progress) = OnTheWay.advance(direct, listOf(call("X", 16)), at(15))
        assertEquals(TripProgress.Changing(second, at(19)), progress)
        assertEquals(TripProgress.Changing(second, at(19)), OnTheWay.advance(changing, null, at(17)).second)
        assertEquals(TripProgress.Waiting(second, null), OnTheWay.advance(changing, null, at(19)).second)
    }

    @Test
    fun `a walk already done while away moves on to the ride after it`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true, dueOffAt = at(14))
        // Off at 14, the 5-minute walk done by 19: back at 25, the next ride is waited for.
        val (next, progress) = OnTheWay.advance(onBoard, emptyList(), at(25))
        assertEquals(2, next.legIndex)
        assertEquals(TripProgress.Waiting(second, null), progress)
    }

    @Test
    fun `a change noticed late runs from when the rider got off, not from when it was noticed`() {
        val first = ride.copy(changeAfter = Duration.ofMinutes(4))
        val direct = ActiveTrip(TripRoute(listOf(first, second)), "E", startedAt = t0, vehicleId = "8", boarded = true, dueOffAt = at(15))
        // Back at 18 after a while away: off at 15, so the change ends at 19, not 22.
        val (changing, progress) = OnTheWay.advance(direct, listOf(call("X", 16)), at(18))
        assertEquals(TripProgress.Changing(second, at(19)), progress)
        assertEquals(at(19), changing.legStartedAt)
        // Back only after the change was up: the next ride is waited for at once.
        assertEquals(TripProgress.Waiting(second, null), OnTheWay.advance(direct, listOf(call("X", 16)), at(25)).second)
    }

    // A long ride: TfL predicts only so far ahead, so the calls can end before F.
    private val long = TripLeg("tube", "red", "Red", "A", "A", "F", "F", at(5), at(45), path = listOf("B", "C", "D", "E", "F"))

    @Test
    fun `a train whose predictions end short of the stop runs along the leg while it keeps to its path`() {
        assertTrue(OnTheWay.runsAlong(long, listOf(call("A", 5), call("B", 9), call("C", 12))))
        // Leaves the path (another branch) before the predictions end.
        assertFalse(OnTheWay.runsAlong(long, listOf(call("A", 5), call("B", 9), call("Z", 12))))
        // Nothing predicted past the boarding stop: can't tell yet.
        assertFalse(OnTheWay.runsAlong(long, listOf(call("A", 5))))
    }

    @Test
    fun `riding beyond the predictions counts the stops left, claiming no time`() {
        val following = ActiveTrip(TripRoute(listOf(long)), "F", startedAt = t0, vehicleId = "8")
        val (next, progress) = OnTheWay.advance(following, listOf(call("B", 9), call("C", 12)), at(6))
        assertTrue(next.boarded)
        assertEquals(TripProgress.Riding(long, "B", 5, null, false), progress)
        assertEquals(0, next.legIndex)
    }

    @Test
    fun `an empty prediction list ends a ride only once the rider was due off`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        // Never seen due off: an empty list may be TfL's gap, not the end of the ride.
        assertEquals(TripProgress.Lost(ride), OnTheWay.advance(onBoard, emptyList(), at(8)).second)
        // Seen due at C at 14: not yet at 12, then off at 15.
        val (seen, _) = OnTheWay.advance(onBoard, listOf(call("B", 9), call("C", 14)), at(6))
        assertEquals(at(14), seen.dueOffAt)
        assertEquals(TripProgress.Lost(ride), OnTheWay.advance(seen, emptyList(), at(12)).second)
        val (walking, progress) = OnTheWay.advance(seen, emptyList(), at(15))
        assertEquals(1, walking.legIndex)
        assertNull(walking.dueOffAt)
        // The walk runs from when the rider was due off (14), not from when that was noticed.
        assertEquals(TripProgress.Walking(walk, at(19)), progress)
    }

    @Test
    fun `a boarded train that turns off the leg before the stop is lost, not got off`() {
        // Never seen due at C: its calls leave the leg (a diversion), so nothing says the rider got off.
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        val (next, progress) = OnTheWay.advance(onBoard, listOf(call("Z", 12)), at(10))
        assertEquals(TripProgress.Lost(ride), progress)
        assertEquals(0, next.legIndex)
        // Seen due at C at 14 and now past it: the calls moved on because the train called there.
        val (off, _) = OnTheWay.advance(onBoard.copy(dueOffAt = at(14)), listOf(call("Z", 16)), at(15))
        assertEquals(1, off.legIndex)
    }

    @Test
    fun `a train reaching the stop the wrong way round the line doesn't take the leg`() {
        val loop = TripLeg("tube", "red", "Red", "A", "A", "D", "D", at(5), at(15), path = listOf("B", "C", "D"))
        assertTrue(OnTheWay.runsAlong(loop, listOf(call("A", 5), call("B", 7), call("C", 9), call("D", 11))))
        // A loop train the other way: from A it runs C, B … and only reaches D the long way round.
        assertFalse(OnTheWay.runsAlong(loop, listOf(call("A", 5), call("C", 7), call("B", 9), call("D", 20))))
    }

    @Test
    fun `switching trains forgets when the old one was due off`() {
        val onOld = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true, dueOffAt = at(14))
        assertNull(OnTheWay.follow(onOld, train("9", 8)).dueOffAt)
    }

    @Test
    fun `a train calling off the leg's path before the stop doesn't take it`() {
        // Boards at A and reaches C, but via X rather than B: a diversion or another branch.
        assertFalse(OnTheWay.runsAlong(ride, listOf(call("A", 5), call("X", 9), call("C", 14))))
    }

    @Test
    fun `a boarded train calling off the leg before the stop is lost, not ridden`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        val (next, progress) = OnTheWay.advance(onBoard, listOf(call("Z", 9), call("C", 14)), at(6))
        assertEquals(TripProgress.Lost(ride), progress)
        assertEquals(0, next.legIndex)
    }

    @Test
    fun `a train leaving the leg within its predictions is lost, even with the stop beyond them`() {
        val following = ActiveTrip(TripRoute(listOf(long)), "F", startedAt = t0, vehicleId = "8", boarded = true)
        // Next at B, then Z: off the leg before F, which isn't predicted yet.
        val (next, progress) = OnTheWay.advance(following, listOf(call("B", 9), call("Z", 12)), at(6))
        assertEquals(TripProgress.Lost(long), progress)
        assertEquals(0, next.legIndex)
    }

    // A bus leg as the Planner gives it: its path as stop areas ("490G…"), which the live calls
    // (by pole) never name, so its stops in between can't be checked.
    private val bus = TripLeg(
        "bus", "73", "73", "490000000001A", "Example Road", "490000000009Z", "Example Street", at(5), at(45),
        path = listOf("490G00000002", "490G00000003", "490G00000009"),
    )
    private fun pole(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))

    @Test
    fun `a bus is taken on its ends, since its path's stop areas never match its poles`() {
        val calls = listOf(pole("490000000001A", 5), pole("490000000002B", 9), pole("490000000009Z", 20))
        assertTrue(OnTheWay.runsAlong(bus, calls))
        val following = ActiveTrip(TripRoute(listOf(bus)), "Example Street", startedAt = t0, vehicleId = "LX01", boarded = true)
        val riding = OnTheWay.advance(following, calls.drop(1), at(6)).second as TripProgress.Riding
        assertEquals(2, riding.stopsLeft)
    }

    @Test
    fun `a bus with its stop beyond the predictions is ridden, its stops left unknown`() {
        val following = ActiveTrip(TripRoute(listOf(bus)), "Example Street", startedAt = t0, vehicleId = "LX01", boarded = true)
        val progress = OnTheWay.advance(following, listOf(pole("490000000002B", 9), pole("490000000003C", 12)), at(6)).second
        assertEquals(TripProgress.Riding(bus, "490000000002B", null, null, false), progress)
    }

    @Test
    fun `a bus whose stop isn't predicted yet is taken only when it's signed for the leg's terminus`() {
        val short = listOf(pole("490000000001A", 5), pole("490000000002B", 9))
        // A short working or another branch looks the same until its calls reach the stop.
        assertFalse(OnTheWay.runsAlong(bus, short))
        assertTrue(OnTheWay.runsAlong(bus.copy(headings = listOf("Example Terminus")), short, heading = "Example Terminus"))
        assertFalse(OnTheWay.runsAlong(bus.copy(headings = listOf("Example Terminus")), short, heading = "Somewhere Short"))
        // A bus station, as the live feed cleans it ("Example Bus Station" → "Example Bus"), against
        // the Planner's heading for the same blind ("Example").
        assertTrue(OnTheWay.runsAlong(bus.copy(headings = listOf("Example")), short, heading = "Example Bus"))
        assertTrue(OnTheWay.runsAlong(bus.copy(headings = listOf("Example")), short, heading = "Example Bus Station"))
    }

    @Test
    fun `a bus seen due at the stop, now past it, has been got off`() {
        val onBoard = ActiveTrip(TripRoute(listOf(bus)), "Example Street", startedAt = t0, vehicleId = "LX01", boarded = true, dueOffAt = at(20))
        // Its calls have moved on past the stop, to poles its stop areas can't place.
        val (_, progress) = OnTheWay.advance(onBoard, listOf(pole("490000000010A", 24)), at(21))
        assertEquals(TripProgress.Arrived, progress)
    }

    @Test
    fun `a loop coming round to the boarding stop again doesn't put a boarded rider back to waiting`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        // Past A, due at C at 14, then round the loop to A again at 20.
        val progress = OnTheWay.advance(onBoard, listOf(call("B", 9), call("C", 14), call("A", 20)), at(6)).second
        assertEquals(TripProgress.Riding(ride, "B", 2, at(14), false), progress)
    }

    @Test
    fun `a loop's later call at the boarding stop doesn't hold a departed train as still to come`() {
        // First refresh after it left A: not boarded yet, its calls B, C then round to A again.
        val following = OnTheWay.follow(trip, train("8", 5))
        val (next, progress) = OnTheWay.advance(following, listOf(call("B", 9), call("C", 14), call("A", 20)), at(6))
        assertTrue(next.boarded)
        assertEquals(TripProgress.Riding(ride, "B", 2, at(14), false), progress)
    }

    @Test
    fun `two bus stops of the same name are told apart by id`() {
        val highStreet = bus.copy(toName = "High Street")
        val onBoard = ActiveTrip(TripRoute(listOf(highStreet)), "High Street", startedAt = t0, vehicleId = "LX01", boarded = true)
        val calls = listOf(
            VehicleCall("490000000005X", "High Street", null, at(10)),
            VehicleCall("490000000009Z", "High Street", null, at(20)),
        )
        val riding = OnTheWay.advance(onBoard, calls, at(6)).second as TripProgress.Riding
        assertEquals(at(20), riding.getOffAt)
        assertEquals(2, riding.stopsLeft)
    }

    @Test
    fun `a loop train still on its previous lap is waited for, not boarded`() {
        // It passes B and C on its way round to A, then runs A, B, C: the rider's ride is after A.
        val following = OnTheWay.follow(trip, train("8", 12))
        val calls = listOf(call("B", 3), call("C", 6), call("A", 12), call("B", 15), call("C", 18))
        val (next, progress) = OnTheWay.advance(following, calls, at(1))
        assertFalse(next.boarded)
        assertEquals(TripProgress.Waiting(ride, at(12)), progress)
    }

    @Test
    fun `a loop train that just left is ridden, though its next lap is predicted too`() {
        // Picked as due at A at 5; at 6 it has left, and its calls run on round to A at 20 and on again.
        val following = OnTheWay.follow(trip, train("8", 5))
        val calls = listOf(call("B", 9), call("C", 14), call("A", 20), call("B", 23), call("C", 28))
        val (next, progress) = OnTheWay.advance(following, calls, at(6))
        assertTrue(next.boarded)
        assertEquals(TripProgress.Riding(ride, "B", 2, at(14), false), progress)
    }

    @Test
    fun `a train running late at the boarding stop is still the one waited for`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        val (late, progress) = OnTheWay.advance(following, listOf(call("A", 8), call("B", 11), call("C", 16)), at(4))
        assertEquals(TripProgress.Waiting(ride, at(8)), progress)
        assertEquals(at(8), late.boardsAt)
    }

    @Test
    fun `a train delayed well past when it was due is still waited for`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        // Ten minutes late at A, its calls on the leg still after it: the same train, not a later lap.
        val (late, progress) = OnTheWay.advance(following, listOf(call("A", 15), call("B", 18), call("C", 22)), at(4))
        assertEquals(TripProgress.Waiting(ride, at(15)), progress)
        assertEquals(at(15), late.boardsAt)
    }

    @Test
    fun `a loop train past the rider's stop, due there again next lap, has been got off`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true, dueOffAt = at(14))
        // Seen due at C at 14; at 15 it's round towards A, and at C again at 24.
        val (off, progress) = OnTheWay.advance(onBoard, listOf(call("A", 18), call("B", 21), call("C", 24)), at(15))
        assertEquals(1, off.legIndex)
        assertEquals(TripProgress.Walking(walk, at(19)), progress)
    }

    @Test
    fun `a train delayed well past when it was due at the rider's stop is still ridden`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true, dueOffAt = at(14))
        // Due at C at 14, now at 15 held and due there at 20: the same call, not a later lap.
        val (next, progress) = OnTheWay.advance(onBoard, listOf(call("B", 18), call("C", 20)), at(15))
        assertEquals(0, next.legIndex)
        assertEquals(TripProgress.Riding(ride, "B", 2, at(20), false), progress)
    }

    @Test
    fun `a station on the leg that TfL names by another id is still on it`() {
        // The plan names B by one id, the live calls by another; its name says it's the same station.
        val named = ride.copy(path = listOf("940GZZLUBEE", "C"), pathNames = listOf("Bee", "C"))
        val calls = listOf(call("A", 5), VehicleCall("910GBEE", "Bee", null, at(9)), call("C", 14))
        assertTrue(OnTheWay.runsAlong(named, calls))
    }

    @Test
    fun `a loop train picked for a later lap isn't boarded on the lap the rider can't catch`() {
        // The rider is at A from 5, and picked for A at 20: it calls at A at 3 first, too soon for them,
        // then goes round.
        val following = OnTheWay.follow(trip.copy(legStartedAt = at(5)), train("8", 20))
        val calls = listOf(call("A", 3), call("B", 6), call("C", 9), call("A", 20), call("B", 23), call("C", 28))
        val (waiting, progress) = OnTheWay.advance(following, calls, at(1))
        assertEquals(TripProgress.Waiting(ride, at(20)), progress)
        assertEquals(at(20), waiting.boardsAt)
        // Once the earlier lap has left A, still waiting for 20, not riding the lap before.
        val (later, stillWaiting) = OnTheWay.advance(waiting, calls.drop(1), at(4))
        assertFalse(later.boarded)
        assertEquals(TripProgress.Waiting(ride, at(20)), stillWaiting)
    }

    @Test
    fun `a boarded ride with no plan of its stops is still ridden when its stop drops out of the predictions`() {
        val pathless = bus.copy(path = emptyList())
        val onBoard = ActiveTrip(TripRoute(listOf(pathless)), "Example Street", startedAt = t0, vehicleId = "LX01", boarded = true)
        val progress = OnTheWay.advance(onBoard, listOf(pole("490000000002B", 9)), at(6)).second
        assertEquals(TripProgress.Riding(pathless, "490000000002B", null, null, false), progress)
    }

    @Test
    fun `a train predicted much earlier than when it was picked is still waited for`() {
        // Picked for A at 20; now predicted there at 14, with no later lap: the same train, earlier.
        val following = OnTheWay.follow(trip, train("8", 20))
        val (next, progress) = OnTheWay.advance(following, listOf(call("A", 14), call("B", 17), call("C", 20)), at(12))
        assertEquals(TripProgress.Waiting(ride, at(14)), progress)
        assertEquals(at(14), next.boardsAt)
    }

    @Test
    fun `a train revised to leave before the rider can reach the stop is given up`() {
        // The rider reaches A at 20; the train picked for 21 is now due there at 15.
        val following = OnTheWay.follow(trip.copy(legStartedAt = at(20)), train("8", 21))
        val (_, stillDue) = OnTheWay.advance(following, listOf(call("A", 15), call("B", 18), call("C", 22)), at(10))
        assertEquals(TripProgress.Lost(ride), stillDue)
        // Nor, once it has left without them, is the rider taken to be on it.
        val (next, gone) = OnTheWay.advance(following.copy(boardsAt = at(15)), listOf(call("B", 18), call("C", 22)), at(16))
        assertFalse(next.boarded)
        assertEquals(TripProgress.Lost(ride), gone)
    }

    @Test
    fun `a waited-for train now calling off the leg is given up, not waited for`() {
        // Still due at A, but now via Z rather than B: a diversion or short working.
        val following = OnTheWay.follow(trip, train("8", 5))
        val (next, progress) = OnTheWay.advance(following, listOf(call("A", 5), call("Z", 9), call("C", 14)), at(3))
        assertEquals(TripProgress.Lost(ride), progress)
        assertFalse(next.boarded)
    }

    @Test
    fun `a bus with no plan of its stops is taken by its terminus, and ridden once it leaves`() {
        val pathless = bus.copy(path = emptyList(), headings = listOf("Example Terminus"))
        val short = listOf(pole("490000000001A", 5), pole("490000000002B", 9))
        assertTrue(OnTheWay.runsAlong(pathless, short, heading = "Example Terminus"))
        assertFalse(OnTheWay.runsAlong(pathless, short, heading = "Somewhere Short"))
        // Once it has left the boarding stop, with its stop still beyond the predictions.
        val following = OnTheWay.follow(ActiveTrip(TripRoute(listOf(pathless)), "Example Street", startedAt = t0), Departure("73", "73", "outbound", "Example Terminus", null, at(5), "bus", vehicleId = "LX01"))
        val (next, progress) = OnTheWay.advance(following, listOf(pole("490000000002B", 9)), at(6))
        assertTrue(next.boarded)
        assertEquals(TripProgress.Riding(pathless, "490000000002B", null, null, false), progress)
    }

    @Test
    fun `a bus stop the Planner gives only as a stop area isn't matched to a pole by name`() {
        // The Planner named no pole for the stop: any "Example Street" pole on the route could be another.
        val areaEnd = bus.copy(toId = "490G00000009")
        val calls = listOf(pole("490000000001A", 5), VehicleCall("490000000007Y", "Example Street", null, at(12)))
        assertFalse(OnTheWay.runsAlong(areaEnd, calls))
    }

    @Test
    fun `a loop train predicted much earlier, still catchable, is waited for though its next lap shows too`() {
        // The rider is at A from 12; picked for A at 20, now due there at 14, then round to A at 30.
        val following = OnTheWay.follow(trip.copy(legStartedAt = at(12)), train("8", 20))
        val calls = listOf(call("A", 14), call("B", 17), call("C", 20), call("A", 30))
        val (next, progress) = OnTheWay.advance(following, calls, at(10))
        assertEquals(TripProgress.Waiting(ride, at(14)), progress)
        assertEquals(at(14), next.boardsAt)
    }

    @Test
    fun `a route with a National Rail train can't be followed, having no train to name`() {
        assertTrue(OnTheWay.canFollow(trip.route))
        val byRail = TripRoute(listOf(ride.copy(mode = NATIONAL_RAIL_MODE), walk, second))
        assertFalse(OnTheWay.canFollow(byRail))
    }
}
