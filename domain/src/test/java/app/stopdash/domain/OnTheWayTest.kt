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

    // Synthetic positions: the boarding stop, a rider still on its platform, and one 1 km down the line.
    private val platform = Coordinates(51.5, -0.12)
    private fun fix(at: Coordinates, accuracyMeters: Float = 5f) = LocationFix(at, isFallback = false, accuracyMeters = accuracyMeters, ageMillis = 1_000L)
    private val stillThere = fix(Coordinates(51.5005, -0.12))
    private val downTheLine = fix(Coordinates(51.509, -0.12))
    private val placed = trip.copy(route = TripRoute(listOf(ride.copy(fromAt = platform), walk, second)))

    @Test
    fun `the train leaving the boarding stop notes when the rider boarded`() {
        val following = OnTheWay.follow(placed, train("8", 5))
        val (onBoard, _) = OnTheWay.advance(following, listOf(call("B", 9), call("C", 14)), at(6))
        // When it was due to leave, not when a refresh first saw it gone.
        assertEquals(at(5), onBoard.boardedAt)
        // Kept from the first sighting, not moved on by each refresh.
        assertEquals(at(5), OnTheWay.advance(onBoard, listOf(call("C", 14)), at(10)).first.boardedAt)
    }

    @Test
    fun `a departure first seen late is dated to the train, so the left-behind window isn't stretched`() {
        // TfL unreachable (or the app not running) as the train left at 5: first seen gone at 12.
        val following = OnTheWay.follow(placed, train("8", 5))
        val (onBoard, _) = OnTheWay.advance(following, listOf(call("C", 14)), at(12))
        assertEquals(at(5), onBoard.boardedAt)
        // A fix is asked for now only to see them nearly at C; one at the boarding stop says nothing.
        assertEquals(onBoard, OnTheWay.seen(onBoard, stillThere, at(12)))
        assertFalse(OnTheWay.wantsFix(onBoard.copy(dueOffAt = at(20)), at(12)))
    }

    @Test
    fun `a rider still at the boarding stop after their train left missed it, and the next is picked`() {
        val onBoard = OnTheWay.follow(placed, train("8", 5)).copy(boarded = true, boardedAt = at(6), dueOffAt = at(14))
        // Too soon to tell: the train may still be pulling out.
        assertEquals(onBoard, OnTheWay.seen(onBoard, stillThere, at(6)))
        val missed = OnTheWay.seen(onBoard, stillThere, at(7))
        assertEquals("", missed.vehicleId)
        assertFalse(missed.boarded)
        assertNull(missed.dueOffAt)
        assertEquals(at(7), missed.legStartedAt)
        assertEquals(TripProgress.Waiting(ride.copy(fromAt = platform), null), OnTheWay.advance(missed, null, at(7)).second)
    }

    @Test
    fun `a rider moving off with the train, or with no fix, stays on it`() {
        val onBoard = OnTheWay.follow(placed, train("8", 5)).copy(boarded = true, boardedAt = at(6))
        assertEquals(onBoard, OnTheWay.seen(onBoard, downTheLine, at(7)))
        assertEquals(onBoard, OnTheWay.seen(onBoard, null, at(7)))
    }

    @Test
    fun `a fix is wanted only just after boarding, where the stop is placed`() {
        val onBoard = OnTheWay.follow(placed, train("8", 5)).copy(boarded = true, boardedAt = at(6))
        assertTrue(OnTheWay.wantsFix(onBoard, at(7)))
        assertFalse(OnTheWay.wantsFix(onBoard, at(11)))
        assertFalse(OnTheWay.wantsFix(onBoard.copy(route = trip.route), at(7)))
        // Still waiting for it, a fix is wanted too: they may be on another train already ([seenAlong]).
        assertTrue(OnTheWay.wantsFix(OnTheWay.follow(placed, train("8", 5)), at(4)))
        // Past the window the rider is taken to be on board, wherever the fix says.
        assertEquals(onBoard, OnTheWay.seen(onBoard, stillThere, at(12)))
    }

    // Synthetic stops on a line running north: A (the platform above), B 1.1 km on, C 2.2 km on.
    private val atB = Coordinates(51.51, -0.12)
    private val atC = Coordinates(51.52, -0.12)
    private val redLine = LineSequence(
        routes = listOf(LineRoute("A ↔ C", listOf("A", "B", "C"))),
        stopNames = mapOf("A" to "A", "B" to "B", "C" to "C"),
        stopPositions = mapOf("A" to (51.5 to -0.12), "B" to (51.51 to -0.12), "C" to (51.52 to -0.12)),
    )
    private val along = OnTheWay.ridePositions(ride, redLine)

    @Test
    fun `a ride's stops are placed from its line's route, a stop area at its poles' middle`() {
        assertEquals(mapOf("A" to platform, "B" to atB, "C" to atC), along)
        // A bus ride the Planner names by stop area: placed between its two poles.
        val bus = ride.copy(mode = "bus", path = listOf("490GB", "C"))
        val poles = redLine.copy(
            stopPositions = redLine.stopPositions + ("Bn" to (51.5102 to -0.12)) + ("Bs" to (51.5098 to -0.12)),
            stopAreas = mapOf("Bn" to "490GB", "Bs" to "490GB"),
        )
        val placedB = OnTheWay.ridePositions(bus, poles).getValue("490GB")
        assertEquals(51.51, placedB.latitude, 1e-9)
        // The Planner's own point where the route places no boarding stop.
        assertEquals(platform, OnTheWay.ridePositions(ride.copy(fromId = "Z", fromAt = platform), redLine)["Z"])
    }

    @Test
    fun `a rider seen along the ride while waiting for its train has boarded`() {
        val waiting = OnTheWay.follow(trip, train("9", 8))
        // At a later stop: the index of the stop they're at along the ride's path.
        assertEquals(OnTheWay.Along(0, atStop = true), OnTheWay.seenAlong(waiting, fix(atB, 20f), along, at(6)))
        // At C, where they get off: the ride is done, whichever train took them (Codex, PR #383).
        assertEquals(OnTheWay.Along(2, atStop = true, atEnd = true), OnTheWay.seenAlong(waiting, fix(atC, 20f), along, at(6)))
        val done = OnTheWay.rideDone(waiting, at(9))
        assertEquals(1, done.legIndex)
        assertEquals(at(9), done.legStartedAt)
        assertEquals("", done.vehicleId)
        assertEquals(TripProgress.Walking(walk, at(9).plus(walk.run)), OnTheWay.advance(done, null, at(9)).second)
        // Between stops, most of a kilometer on toward where they get off (maintainer, 2026-09-29):
        // short of B, or past B and short of C, so a train still to reach B is behind them.
        assertEquals(OnTheWay.Along(0, atStop = false), OnTheWay.seenAlong(waiting, fix(Coordinates(51.505, -0.12), 20f), along, at(6)))
        assertEquals(OnTheWay.Along(1, atStop = false), OnTheWay.seenAlong(waiting, fix(Coordinates(51.515, -0.12), 20f), along, at(6)))
        // Beside the ride's way, 200 m east of it, still on it; a kilometer east, off it, however much
        // nearer C (Codex, PR #383).
        assertEquals(OnTheWay.Along(1, atStop = false), OnTheWay.seenAlong(waiting, fix(Coordinates(51.515, -0.1171), 20f), along, at(6)))
        assertNull(OnTheWay.seenAlong(waiting, fix(Coordinates(51.51, -0.1056), 20f), along, at(6)))
        // Still on the platform, 300 m up the road, or 600 m the other way: waiting, as before.
        assertNull(OnTheWay.seenAlong(waiting, stillThere, along, at(6)))
        assertNull(OnTheWay.seenAlong(waiting, fix(Coordinates(51.5027, -0.12), 20f), along, at(6)))
        assertNull(OnTheWay.seenAlong(waiting, fix(Coordinates(51.4946, -0.12), 20f), along, at(6)))
        // Far enough on, but the fix too vague to be sure of it.
        assertNull(OnTheWay.seenAlong(waiting, fix(Coordinates(51.505, -0.12), 200f), along, at(6)))
        // Only while the ride is awaited: not once on board, nor while a change onto it runs.
        assertNull(OnTheWay.seenAlong(waiting.copy(boarded = true), fix(atB, 20f), along, at(6)))
        val onward = TripLeg("tube", "blue", "Blue", "C", "C", "E", "E", at(16), at(25), path = listOf("E"))
        val changing = trip.copy(route = TripRoute(listOf(ride, onward)), legIndex = 1, legStartedAt = at(17))
        assertNull(OnTheWay.seenAlong(changing, fix(atB, 20f), along, at(16)))
        // With the boarding stop not placed, nothing to measure from.
        assertNull(OnTheWay.seenAlong(waiting, fix(atB, 20f), along - "A", at(6)))
    }

    @Test
    fun `waiting for a ride's train, a fix is wanted and one sure to 100 m will do`() {
        val waiting = OnTheWay.follow(trip, train("9", 8))
        assertTrue(OnTheWay.wantsFix(waiting, at(6)))
        assertTrue(OnTheWay.sureEnoughFor(waiting, at(6))(fix(atB, 100f)))
        assertFalse(OnTheWay.sureEnoughFor(waiting, at(6))(fix(atB, 120f)))
        // Only for the first ten minutes of the wait: a long one for a delayed train keeps GPS off.
        assertFalse(OnTheWay.wantsFix(waiting, at(10)))
        // A clock set back to before the wait began: its ten minutes can't be counted, so none
        // (Codex, PR #383).
        val set = waiting.copy(waitFrom = at(5))
        assertTrue(OnTheWay.watchesWait(set, at(6)))
        assertFalse(OnTheWay.watchesWait(set, at(3)))
        // Left behind by a train seven minutes in, the wait goes on, but its ten minutes don't start
        // again: they'd be twenty fixes more for each train missed.
        val left = OnTheWay.seen(OnTheWay.follow(placed, train("8", 5)).copy(boarded = true, boardedAt = at(6)), fix(Coordinates(51.50126, -0.12), 5f), at(7))
        assertEquals("", left.vehicleId)
        assertTrue(OnTheWay.wantsFix(left, at(9)))
        assertFalse(OnTheWay.wantsFix(left, at(10)))
        // A leg of its own starts a wait of its own.
        assertTrue(OnTheWay.wantsFix(OnTheWay.atLeg(left, 2, at(11)), at(12)))
        // On board, past the left-behind window, none is: the train says where they are.
        assertFalse(OnTheWay.wantsFix(waiting.copy(boarded = true, boardedAt = at(1)), at(9)))
    }

    @Test
    fun `seen along, the rider is on the train that has left the boarding stop on the ride`() {
        val waiting = OnTheWay.follow(trip, train("9", 8))
        // An earlier train, gone from A, next at B: theirs.
        val (onBoard, progress) = checkNotNull(OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("B", 7), call("C", 12)), 0, at(6)))
        assertEquals("7", onBoard.vehicleId)
        assertTrue(onBoard.boarded)
        assertEquals(at(5), onBoard.boardedAt)
        progress as TripProgress.Riding
        assertEquals("B", progress.nextStop)
        assertEquals(2, progress.stopsLeft)
        // Still to call at A: it hasn't left, so it isn't theirs.
        assertNull(OnTheWay.boardedOn(waiting, train("9", 8), listOf(call("A", 8), call("B", 10), call("C", 14)), 0, at(6)))
        // Seen at C, a train still to reach B is behind them.
        assertNull(OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("B", 7), call("C", 12)), 1, at(6)))
        assertEquals("7", OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("C", 12)), 1, at(6))?.first?.vehicleId)
        // Off the ride altogether.
        assertNull(OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("X", 7), call("Y", 12)), 0, at(6)))
        // Seen at B: a train due at B now is at the platform or pulling in; one due there in three
        // minutes is on its way to it, behind them.
        assertEquals("7", OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("B", 6), call("C", 12)), 0, at(6), atStop = true)?.first?.vehicleId)
        assertNull(OnTheWay.boardedOn(waiting, train("8", 5), listOf(call("B", 9), call("C", 14)), 0, at(6), atStop = true))
        // Short of B, it's where the train they're on calls next, whenever it's due there.
        assertEquals("8", OnTheWay.boardedOn(waiting, train("8", 5), listOf(call("B", 9), call("C", 14)), 0, at(6))?.first?.vehicleId)
    }

    @Test
    fun `seen along a bus ride, a bus is taken only once its calls reach where the rider gets off`() {
        val bus = ride.copy(mode = "bus", path = listOf("490GB", "C"))
        val waiting = OnTheWay.follow(trip.copy(route = TripRoute(listOf(bus, walk, second))), train("9", 8))
        assertEquals("7", OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("Bn", 7), call("C", 12)), 0, at(6))?.first?.vehicleId)
        // Its stops can't be matched by pole, so any bus of the mode would pass the rest.
        assertNull(OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("Bn", 7), call("Q", 12)), 0, at(6)))
        // The Planner named one pole of each stop pair; a bus using the other is still the ride.
        val paired = waiting.copy(route = TripRoute(listOf(bus.copy(fromArea = "490GA", toArea = "490GC"), walk, second)))
        val otherPole = listOf(call("Bn", 7), call("Cs", 12))
        assertNull(OnTheWay.boardedOn(paired, train("7", 5), otherPole, 0, at(6)))
        val onOther = checkNotNull(OnTheWay.boardedOn(paired, train("7", 5), otherPole, 0, at(6), alightingPoles = setOf("C", "Cs"))).first
        assertEquals("7", onOther.vehicleId)
        // Its stop there is known as the rider's from then on: due off at 12, and got off once past it.
        assertEquals("Cs", onOther.vehicleOffId)
        val (due, riding) = OnTheWay.advance(onOther, otherPole, at(8))
        assertEquals(at(12), (riding as TripProgress.Riding).getOffAt)
        assertEquals(1, OnTheWay.advance(due, emptyList(), at(13)).first.legIndex)
        // Still to call at the other boarding pole: it hasn't left for the rider.
        assertNull(OnTheWay.boardedOn(paired, train("7", 5), listOf(call("As", 6)) + otherPole, 0, at(6), setOf("A", "As"), setOf("C", "Cs")))
    }

    @Test
    fun `seen further along a bus ride, a bus still behind the rider isn't theirs`() {
        val bus = ride.copy(mode = "bus", path = listOf("490GB", "490GD", "C"))
        val waiting = OnTheWay.follow(trip.copy(route = TripRoute(listOf(bus, walk, second))), train("9", 8))
        val areas = mapOf("Bn" to "490GB", "Dn" to "490GD")
        // Seen at D (the path's second stop): the bus next calling at B is a later one, behind them.
        assertNull(OnTheWay.boardedOn(waiting, train("8", 7), listOf(call("Bn", 9), call("Dn", 11), call("C", 14)), 1, at(8), areas = areas))
        // The one next calling at D, or past it, can be theirs.
        assertEquals("7", OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("Dn", 8), call("C", 11)), 1, at(8), areas = areas)?.first?.vehicleId)
        assertEquals("7", OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("C", 9)), 1, at(8), areas = areas)?.first?.vehicleId)
        // With its next stop unplaced, it can't be told from a later bus.
        assertNull(OnTheWay.boardedOn(waiting, train("7", 5), listOf(call("Dn", 8), call("C", 11)), 1, at(8)))
    }

    @Test
    fun `a stop pair's poles are those its line's route puts in it`() {
        val poles = redLine.copy(stopAreas = mapOf("Cn" to "490GC", "Cs" to "490GC", "B" to "490GB"))
        assertEquals(setOf("Cn", "Cs"), OnTheWay.pairPoles("490GC", poles))
        assertEquals(emptySet<String>(), OnTheWay.pairPoles("", poles))
    }

    @Test
    fun `a fix is wanted only on a train, not a bus held up near its stop`() {
        val bus = ride.copy(mode = "bus", fromAt = platform)
        val onBus = OnTheWay.follow(placed.copy(route = TripRoute(listOf(bus, walk, second))), train("8", 5)).copy(boarded = true, boardedAt = at(6))
        assertFalse(OnTheWay.wantsFix(onBus, at(7)))
        // Still near the pole in traffic: kept on the bus it's on.
        assertEquals(onBus, OnTheWay.seen(onBus, stillThere, at(8)))
    }

    @Test
    fun `only a fix sure to within the left-behind distance is used`() {
        val sure = stillThere.copy(accuracyMeters = 10f)
        assertEquals(sure, OnTheWay.usableFix(sure))
        // Indoors or at a tunnel mouth: a GPS fix that can't say whether the rider is 150 m away.
        assertNull(OnTheWay.usableFix(sure.copy(accuracyMeters = 120f)))
        assertNull(OnTheWay.usableFix(sure.copy(accuracyMeters = null)))
        assertNull(OnTheWay.usableFix(sure.copy(isCoarse = true)))
        assertNull(OnTheWay.usableFix(sure.copy(isFallback = true)))
    }

    @Test
    fun `a rider is left behind only if the fix is sure they're within the distance`() {
        val onBoard = OnTheWay.follow(placed, train("8", 5)).copy(boarded = true, boardedAt = at(6))
        // About 140 m from the stop: sure to 5 m, that's still on the platform's doorstep...
        val near = Coordinates(51.50126, -0.12)
        assertEquals("", OnTheWay.seen(onBoard, fix(near, 5f), at(7)).vehicleId)
        // ...but to 50 m the rider could be 190 m off, already moving on the train: kept on it.
        assertEquals(onBoard, OnTheWay.seen(onBoard, fix(near, 50f), at(7)))
    }

    @Test
    fun `a fix taken a while ago isn't used, however sure it was then`() {
        val fresh = stillThere.copy(ageMillis = 2_000L)
        assertEquals(fresh, OnTheWay.usableFix(fresh))
        // Sure to 5 m, but half a minute old: the rider may be well down the line by now.
        assertNull(OnTheWay.usableFix(fresh.copy(ageMillis = 30_000L)))
        assertNull(OnTheWay.usableFix(fresh.copy(ageMillis = null)))
    }

    // A trip that starts with a walk to the placed boarding stop.
    private val toStop = TripLeg(TripLeg.WALKING, "", "", "", "", "A", "A", t0, at(2))
    private val walkingTrip = ActiveTrip(TripRoute(listOf(toStop, ride.copy(fromAt = platform), walk, second)), "E", startedAt = t0)

    @Test
    fun `a rider seen at the stop they're walking to is there, and its train is picked from now`() {
        assertEquals(platform, OnTheWay.walkingTo(walkingTrip, t0))
        assertTrue(OnTheWay.wantsFix(walkingTrip, t0))
        val there = OnTheWay.seen(walkingTrip, stillThere, at(1))
        assertEquals(1, there.legIndex)
        assertEquals(at(1), there.legStartedAt)
        assertEquals(TripProgress.Waiting(ride.copy(fromAt = platform), null), OnTheWay.advance(there, null, at(1)).second)
    }

    // Riding to C, placed at a synthetic point, on a train due there at 14 min.
    private val getOff = Coordinates(51.53, -0.12)
    private val nearlyThere = OnTheWay.follow(trip.copy(route = TripRoute(listOf(ride.copy(toAt = getOff), walk, second))), train("8", 5))
        .copy(boarded = true, boardedAt = at(5), dueOffAt = at(14))

    @Test
    fun `a train nearly where the rider gets off asks for a fix, a bus or one far off doesn't`() {
        // Due at 14: from 10 on, about two stops out, and a few minutes past its time.
        assertNull(OnTheWay.stationRiddenTo(nearlyThere, at(9)))
        assertEquals(nearlyThere.leg, OnTheWay.stationRiddenTo(nearlyThere, at(10)))
        assertEquals(nearlyThere.leg, OnTheWay.stationRiddenTo(nearlyThere, at(18)))
        // A time left stale (TfL gone quiet) doesn't keep asking for fixes.
        assertNull(OnTheWay.stationRiddenTo(nearlyThere, at(19)))
        assertFalse(OnTheWay.wantsFix(nearlyThere, at(60)))
        assertTrue(OnTheWay.wantsFix(nearlyThere, at(10)))
        // Not yet on the train, not predicted that far, or a bus in the street: none.
        assertNull(OnTheWay.stationRiddenTo(nearlyThere.copy(boarded = false), at(12)))
        assertNull(OnTheWay.stationRiddenTo(nearlyThere.copy(dueOffAt = null), at(12)))
        val bus = nearlyThere.copy(route = TripRoute(listOf(ride.copy(mode = "bus", toAt = getOff), walk, second)))
        assertNull(OnTheWay.stationRiddenTo(bus, at(12)))
        // A fix sure to 100 m can settle it, as on a walk to a stop.
        assertTrue(OnTheWay.sureEnoughFor(nearlyThere, at(12))(fix(getOff, accuracyMeters = 100f)))
    }

    @Test
    fun `a rider seen at the station they get off at is off, whatever the train followed says`() {
        // The train followed still a stop or two out, but the rider is at C's entrance: on to the walk.
        val atEntrance = fix(Coordinates(51.5327, -0.12), accuracyMeters = 20f)
        assertEquals(nearlyThere, OnTheWay.seen(nearlyThere, atEntrance, at(11)))
        val off = OnTheWay.seen(nearlyThere, atEntrance, at(11), StationPlaces(entrances = listOf(Coordinates(51.5329, -0.12))))
        assertEquals(1, off.legIndex)
        assertEquals(at(11), off.legStartedAt)
        assertEquals("", off.vehicleId)
        // At its placed point, likewise; a fix still down the line says nothing.
        assertEquals(1, OnTheWay.seen(nearlyThere, fix(getOff), at(11)).legIndex)
        assertEquals(nearlyThere, OnTheWay.seen(nearlyThere, fix(Coordinates(51.52, -0.12)), at(11)))
        // Nor before the train is nearly there.
        assertEquals(nearlyThere, OnTheWay.seen(nearlyThere, fix(getOff), at(9)))
        // Seen after the train followed was due: the walk on starts then, not at its due time.
        val late = OnTheWay.seen(nearlyThere, fix(getOff), at(17))
        assertEquals(1, late.legIndex)
        assertEquals(at(17), late.legStartedAt)
        // The Planner left the stop unplaced: the station's own position, read with its entrances, does.
        val unplaced = nearlyThere.copy(route = TripRoute(listOf(ride, walk, second)))
        assertEquals(unplaced, OnTheWay.seen(unplaced, fix(getOff), at(11)))
        assertEquals(1, OnTheWay.seen(unplaced, fix(getOff), at(11), StationPlaces(point = getOff)).legIndex)
    }

    @Test
    fun `a rider who says they're at a leg starts it now, as if they'd just got there`() {
        // Still walking by the clock, but at the stop: Next puts them waiting for the ride's train from now.
        val next = OnTheWay.atLeg(walkingTrip, 1, at(1))
        assertEquals(1, next.legIndex)
        assertEquals(at(1), next.legStartedAt)
        assertEquals(TripProgress.Waiting(ride.copy(fromAt = platform), null), OnTheWay.advance(next, null, at(1)).second)
        // On board, with get off soon said: a later walk starts now, its time run from now, and the
        // train is let go.
        val riding = OnTheWay.warned(OnTheWay.follow(walkingTrip.copy(legIndex = 1), train("8", 5)).copy(boarded = true, boardedAt = at(5)))
        val walking = OnTheWay.atLeg(riding, 2, at(9))
        assertEquals("", walking.vehicleId)
        assertFalse(walking.boarded)
        assertEquals(-1, walking.warnedLeg)
        assertEquals(TripProgress.Walking(walk, at(14)), OnTheWay.advance(walking, null, at(9)).second)
        // Back to an earlier leg, to undo a tap made by mistake, and past the last one is arrived.
        assertEquals(0, OnTheWay.atLeg(walking, 0, at(9)).legIndex)
        assertEquals(TripProgress.Arrived, OnTheWay.advance(OnTheWay.atLeg(walking, 4, at(9)), null, at(9)).second)
        assertEquals(4, OnTheWay.atLeg(walking, 9, at(9)).legIndex)
    }

    @Test
    fun `the rider can go to any step but the one they're at, and never straight to arriving`() {
        assertTrue(OnTheWay.canGoTo(walkingTrip, OnTheWay.Step(1), at(1)))
        assertTrue(OnTheWay.canGoTo(walkingTrip, OnTheWay.Step(1, onBoard = true), at(1)))
        assertTrue(OnTheWay.canGoTo(walkingTrip.copy(legIndex = 2), OnTheWay.Step(0), at(1)))
        assertFalse(OnTheWay.canGoTo(walkingTrip, OnTheWay.Step(0), at(1)))
        assertFalse(OnTheWay.canGoTo(walkingTrip, OnTheWay.Step(-1), at(1)))
        assertFalse(OnTheWay.canGoTo(walkingTrip, OnTheWay.Step(4), at(1)))
        // A walk has no second step.
        assertFalse(OnTheWay.canGoTo(walkingTrip.copy(legIndex = 1), OnTheWay.Step(2, onBoard = true), at(1)))
        // A closing walk of no length arrives the moment it starts: not one to move onto.
        val closing = TripLeg(TripLeg.WALKING, "", "", "E", "E", "E", "E", at(30), at(30))
        val endsAtOnce = ActiveTrip(TripRoute(listOf(ride, closing)), "E", startedAt = t0)
        assertFalse(OnTheWay.canGoTo(endsAtOnce, OnTheWay.Step(1), at(1)))
    }

    @Test
    fun `a ride is two steps, boarding it and getting off it`() {
        assertEquals(
            listOf(OnTheWay.Step(0), OnTheWay.Step(0, onBoard = true), OnTheWay.Step(1), OnTheWay.Step(2), OnTheWay.Step(2, onBoard = true)),
            OnTheWay.steps(trip.route),
        )
        assertEquals(OnTheWay.Step(0), OnTheWay.stepOf(trip))
        assertEquals(OnTheWay.Step(0, onBoard = true), OnTheWay.stepOf(trip.copy(boarded = true)))
        // Back and Next step through them in order, with nothing before the first or after the last.
        assertNull(OnTheWay.stepBefore(trip))
        assertEquals(OnTheWay.Step(0, onBoard = true), OnTheWay.stepAfter(trip))
        val walking = trip.copy(legIndex = 1)
        assertEquals(OnTheWay.Step(0, onBoard = true), OnTheWay.stepBefore(walking))
        assertEquals(OnTheWay.Step(2), OnTheWay.stepAfter(walking))
        assertNull(OnTheWay.stepAfter(trip.copy(legIndex = 2, boarded = true)))
    }

    @Test
    fun `an arrival kept because forgetting it failed has no step before or after it`() {
        // Past the last leg, it isn't at any step: Next would start the trip over from its first, and
        // the arrival is being forgotten, which Back can't undo; End trip is the way out.
        val arrived = trip.copy(legIndex = trip.route.legs.size)
        assertNull(OnTheWay.stepAfter(arrived))
        assertNull(OnTheWay.stepBefore(arrived))
        // Nor can any step be tapped: the tracker moves that arrival nowhere (Codex, PR #384).
        assertTrue(OnTheWay.steps(trip.route).none { OnTheWay.canGoTo(arrived, it, at(20)) })
        // Every step is behind the rider there, so the route reads as done (Codex, PR #384).
        assertEquals(OnTheWay.steps(trip.route).size, OnTheWay.stepsDone(arrived))
        // On the way, only those before their step: none at the first, the ride's two on the walk.
        assertEquals(0, OnTheWay.stepsDone(trip))
        assertEquals(2, OnTheWay.stepsDone(trip.copy(legIndex = 1)))
    }

    @Test
    fun `a rider who says they're on board is on the train followed, and stays so`() {
        // At the platform with 8 due in a minute: Next says they're on it.
        val waiting = OnTheWay.follow(placed, train("8", 5))
        val onBoard = OnTheWay.atStep(waiting, OnTheWay.Step(0, onBoard = true), at(4))
        assertTrue(onBoard.boarded)
        assertEquals("8", onBoard.vehicleId)
        // TfL still has it at A: that call is behind them, so they're riding, B next, two stops left.
        val (riding, progress) = OnTheWay.advance(onBoard, listOf(call("A", 5), call("B", 9), call("C", 14)), at(4))
        progress as TripProgress.Riding
        assertEquals("B", progress.nextStop)
        assertEquals(2, progress.stopsLeft)
        assertTrue(riding.boarded)
        // TfL late to drop the stop before A too: that's behind them as well.
        val lagging = OnTheWay.advance(onBoard, listOf(call("Z", 3), call("A", 5), call("B", 9), call("C", 14)), at(4)).second
        assertEquals(TripProgress.Riding(onBoard.leg!!, "B", 2, at(14), false), lagging)
        // A call at A after C is a loop's next lap, not the stop they boarded at: nothing is dropped.
        val lap = OnTheWay.advance(onBoard, listOf(call("B", 9), call("C", 14), call("A", 30)), at(4)).second as TripProgress.Riding
        assertEquals("B", lap.nextStop)
        assertEquals(2, lap.stopsLeft)
        // TfL still showing C from the lap before, ahead of A: that's behind them too, with this
        // lap's C still to come.
        val previousLap = OnTheWay.advance(onBoard, listOf(call("C", 2), call("A", 5), call("B", 9), call("C", 14)), at(4)).second
        assertEquals(TripProgress.Riding(onBoard.leg!!, "B", 2, at(14), false), previousLap)
        // Said just after it called at A, with the next lap's A listed too: the call behind them is
        // the one about when it was due, not the next lap's.
        val justLeft = OnTheWay.advance(onBoard, listOf(call("A", 3), call("B", 9), call("C", 14), call("A", 30)), at(4)).second
        assertEquals(TripProgress.Riding(onBoard.leg!!, "B", 2, at(14), false), justLeft)
        // Run seven minutes late since it was last seen due at 5, but at the platform now: the call
        // there is still the one behind them.
        val late = OnTheWay.advance(onBoard, listOf(call("A", 12), call("B", 16), call("C", 21)), at(12)).second
        assertEquals(TripProgress.Riding(onBoard.leg!!, "B", 2, at(21), false), late)
        // Seven minutes late, with TfL still listing the stop before A: both behind them (Codex, PR #384).
        val lateLagging = OnTheWay.advance(onBoard, listOf(call("Z", 11), call("A", 12), call("B", 16), call("C", 21)), at(12)).second
        assertEquals(TripProgress.Riding(onBoard.leg!!, "B", 2, at(21), false), lateLagging)
        // Held at the platform after they said they're on, its time there slipping a few minutes each
        // refresh to well past the ride's planned time: still the train they boarded (Codex, PR #384).
        var held = onBoard
        for (minute in listOf(5L, 9L, 13L, 16L)) {
            val (kept, standing) = OnTheWay.advance(held, listOf(call("A", minute), call("B", minute + 4), call("C", minute + 9)), at(minute))
            assertEquals(TripProgress.Riding(onBoard.leg!!, "B", 2, at(minute + 9), false), standing)
            held = kept
        }
        // Back a lap later, their stop never predicted while the app was away: the next lap's A isn't
        // the one they boarded at, so nothing's dropped, and the train isn't said to be on their way
        // to C again (Codex, PR #384).
        val lapLater = OnTheWay.advance(onBoard, listOf(call("A", 40), call("B", 44), call("C", 49)), at(40)).second
        assertEquals(TripProgress.Lost(onBoard.leg!!), lapLater)
        // Nearly round, the next lap's A due soon after C: not the stop they boarded at, so C is
        // still where they get off, at 14 (Codex, PR #384).
        val nearlyRound = OnTheWay.advance(onBoard, listOf(call("C", 14), call("A", 16), call("B", 21), call("C", 26)), at(12)).second
        nearlyRound as TripProgress.Riding
        assertEquals("C", nearlyRound.nextStop)
        assertEquals(at(14), nearlyRound.getOffAt)
        // Still beside the stop as the train stands there: their word stands, it isn't taken back.
        assertEquals(riding, OnTheWay.seen(riding, stillThere, at(6)))
        assertFalse(OnTheWay.wantsFix(riding.copy(dueOffAt = null), at(6)))
        // With no train followed yet, the one picked next is theirs, still on board.
        val none = OnTheWay.atStep(placed, OnTheWay.Step(0, onBoard = true), at(4))
        assertEquals("", none.vehicleId)
        assertTrue(OnTheWay.follow(none, train("7", 4)).boarded)
        // Back to boarding it: waiting again, a train picked afresh.
        val back = OnTheWay.atStep(riding, OnTheWay.Step(0), at(6))
        assertFalse(back.boarded)
        assertEquals("", back.vehicleId)
    }

    @Test
    fun `a train still at the boarding stop on a short ride isn't taken for its next lap`() {
        // A one-stop ride, B due a minute after A, so the rider is seen due off while TfL still has
        // the train standing at A.
        val hop = ride.copy(toId = "B", toName = "B", arrival = at(7), path = listOf("B"))
        val onBoard = OnTheWay.atStep(OnTheWay.follow(placed.copy(route = TripRoute(listOf(hop, walk))), train("8", 5)), OnTheWay.Step(0, onBoard = true), at(4))
        val (seen, _) = OnTheWay.advance(onBoard, listOf(call("A", 5), call("B", 6)), at(4))
        // Held there a moment: that call is still the one behind them, not a lap said to be over
        // (Codex, PR #384).
        val (held, standing) = OnTheWay.advance(seen, listOf(call("A", 5), call("B", 6)), at(5))
        assertEquals(0, held.legIndex)
        assertEquals(TripProgress.Riding(hop, "B", 1, at(6), true), standing)
        // Nor with nothing after it predicted: still there, so not got off, but not placed either.
        val (alone, lost) = OnTheWay.advance(seen, listOf(call("A", 5)), at(5))
        assertEquals(0, alone.legIndex)
        assertEquals(TripProgress.Lost(hop), lost)
        // Held longer, its times slipping past when B was last due: still the train they're on.
        val (later, stillHeld) = OnTheWay.advance(held, listOf(call("A", 9), call("B", 10)), at(5))
        assertEquals(0, later.legIndex)
        assertEquals(TripProgress.Riding(hop, "B", 1, at(10), true), stillHeld)
        // Once B has had it and the next lap's A is listed, they got off: the walk is next.
        assertEquals(1, OnTheWay.advance(held, listOf(call("A", 15), call("B", 16)), at(7)).first.legIndex)
        assertEquals(1, OnTheWay.advance(held, emptyList(), at(7)).first.legIndex)
    }

    @Test
    fun `on a tight loop the next lap's boarding stop isn't taken for the one they boarded at`() {
        // Said on at 6 for a ride planned at ten minutes, run in eight; A comes round again two
        // minutes after C, inside the ride's planned time after they boarded.
        val onBoard = OnTheWay.atStep(OnTheWay.follow(placed, train("8", 6)), OnTheWay.Step(0, onBoard = true), at(6))
        val loop = listOf(call("C", 14), call("A", 16), call("B", 21), call("C", 26))
        // At C now, its time come, with the next lap listed after: still getting off at C, not
        // carried round to the next lap's C (Codex, PR #384).
        val (atC, standing) = OnTheWay.advance(onBoard, loop, at(14))
        assertEquals(0, atC.legIndex)
        assertEquals("C", (standing as TripProgress.Riding).nextStop)
        assertEquals(at(14), standing.getOffAt)
        // TfL drops C: seen due off there, the next lap's A says they got off; the walk is next.
        assertEquals(1, OnTheWay.advance(atC, loop.drop(1), at(15)).first.legIndex)
    }

    @Test
    fun `Back straight after Next from a ride they're on is back on its train`() {
        val onBoard = OnTheWay.atStep(OnTheWay.follow(placed, train("8", 5)), OnTheWay.Step(0, onBoard = true), at(4))
        // Next to the walk after it, too soon; then Back: the same train, as it was (Codex, PR #384).
        val walking = OnTheWay.atStep(onBoard, OnTheWay.Step(1), at(8))
        assertEquals(1, walking.legIndex)
        val back = OnTheWay.atStep(walking, OnTheWay.Step(0, onBoard = true), at(9))
        assertEquals("8", back.vehicleId)
        assertTrue(back.boarded)
        assertEquals(onBoard.legStartedAt, back.legStartedAt)
        assertEquals(onBoard.boardsAt, back.boardsAt)
        assertNull(back.leftRide)
        // Only straight back: a step further on, the ride's getting off is picked afresh.
        val further = OnTheWay.atStep(walking, OnTheWay.Step(2), at(10))
        assertEquals("", OnTheWay.atStep(further, OnTheWay.Step(0, onBoard = true), at(11)).vehicleId)
        // On board with no train named: Back is on board as they said then, not from the Back tap,
        // which would look for one at the platform by then (Codex, PR #384).
        val unnamed = OnTheWay.atStep(placed, OnTheWay.Step(0, onBoard = true), at(4))
        val unnamedBack = OnTheWay.atStep(OnTheWay.atStep(unnamed, OnTheWay.Step(1), at(8)), OnTheWay.Step(0, onBoard = true), at(9))
        assertEquals("", unnamedBack.vehicleId)
        assertTrue(unnamedBack.boarded)
        assertEquals(unnamed.legStartedAt, unnamedBack.legStartedAt)
        assertEquals(unnamed.boardedAt, unnamedBack.boardedAt)
    }

    @Test
    fun `a rider at one of the station's entrances is there, however far its placed point`() {
        // 300 m north of where the Planner placed the stop, but 20 m from an entrance.
        val atEntrance = fix(Coordinates(51.5027, -0.12), accuracyMeters = 20f)
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, atEntrance, at(1)))
        val there = OnTheWay.seen(walkingTrip, atEntrance, at(1), StationPlaces(entrances = listOf(Coordinates(51.5029, -0.12))))
        assertEquals(1, there.legIndex)
        // An entrance well away from the rider says nothing.
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, atEntrance, at(1), StationPlaces(entrances = listOf(Coordinates(51.506, -0.12)))))
    }

    @Test
    fun `an entrance is reached at its door, not from the street around it`() {
        // Synthetic positions: an entrance 300 m north of where the Planner placed the stop.
        val entrance = Coordinates(51.5027, -0.12)
        val station = StationPlaces(entrances = listOf(entrance))
        // 55 m past it, sure to 16 m: still on the way, where 150 m round every entrance of a big
        // interchange said they'd arrived from the streets around it (maintainer, 2026-09-29).
        val acrossTheStreet = fix(north(entrance, 55.0), accuracyMeters = 16f)
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, acrossTheStreet, at(1), station))
        assertNull(OnTheWay.seenAtStop(walkingTrip, acrossTheStreet, at(1), station))
        // At the door, as sure: there.
        val atDoor = fix(north(entrance, 20.0), accuracyMeters = 16f)
        assertEquals(1, OnTheWay.seen(walkingTrip, atDoor, at(1), station).legIndex)
        assertEquals(SeenAt.ENTRANCE, OnTheWay.seenAtStop(walkingTrip, atDoor, at(1), station))
        // By the door but only sure to 60 m: they could be anywhere round it.
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, fix(entrance, accuracyMeters = 60f), at(1), station))
        // A station's own point, which can sit well inside it, is still reached from as far.
        val point = StationPlaces(point = entrance)
        assertEquals(1, OnTheWay.seen(walkingTrip, acrossTheStreet, at(1), point).legIndex)
        assertEquals(SeenAt.POINT, OnTheWay.seenAtStop(walkingTrip, acrossTheStreet, at(1), point))
        // But not from 95 m, sure to 16 m: 150 m round a point said they'd arrived from outside too
        // (maintainer, 2026-09-29).
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, fix(north(entrance, 95.0), accuracyMeters = 16f), at(1), point))
        // As is the Planner's.
        assertEquals(SeenAt.POINT, OnTheWay.seenAtStop(walkingTrip, fix(north(platform, 55.0), accuracyMeters = 16f), at(1)))
    }

    @Test
    fun `a rider riding past the station they get off at isn't off until at its door`() {
        // Synthetic positions: an entrance of C, the station they get off at, placed 300 m on.
        val entrance = Coordinates(51.5327, -0.12)
        val station = StationPlaces(entrances = listOf(entrance))
        assertEquals(nearlyThere, OnTheWay.seen(nearlyThere, fix(north(entrance, 55.0), accuracyMeters = 16f), at(11), station))
        assertEquals(1, OnTheWay.seen(nearlyThere, fix(north(entrance, 20.0), accuracyMeters = 16f), at(11), station).legIndex)
    }

    // [at] moved [meters] due north.
    private fun north(at: Coordinates, meters: Double) = Coordinates(at.latitude + meters / 111_195.0, at.longitude)

    @Test
    fun `a walk to a station is one to read entrances for, a walk to a bus stop isn't`() {
        assertEquals(1, walkingTrip.route.legs.indexOf(OnTheWay.stationWalkedTo(walkingTrip, t0)))
        val bus = TripLeg("bus", "73", "73", "A", "A", "C", "C", at(5), at(15), fromAt = platform)
        assertNull(OnTheWay.stationWalkedTo(ActiveTrip(TripRoute(listOf(toStop, bus)), "C", startedAt = t0), t0))
        // Nor once the walk's time is up.
        assertNull(OnTheWay.stationWalkedTo(walkingTrip, at(3)))
    }

    @Test
    fun `a walk to a station the Planner left unplaced is seen at by its own point and entrances`() {
        val unplaced = ActiveTrip(TripRoute(listOf(toStop, ride, walk, second)), "E", startedAt = t0)
        // Worth a fix, and the station read: its own point and entrances can place it.
        assertTrue(OnTheWay.wantsFix(unplaced, at(1)))
        assertEquals(ride, OnTheWay.stationWalkedTo(unplaced, at(1)))
        val atEntrance = fix(Coordinates(51.5027, -0.12), accuracyMeters = 20f)
        assertEquals(1, OnTheWay.seen(unplaced, atEntrance, at(1), StationPlaces(entrances = listOf(Coordinates(51.5029, -0.12)))).legIndex)
        // Nothing read, nothing to see it by.
        assertEquals(unplaced, OnTheWay.seen(unplaced, atEntrance, at(1)))
        // A bus stop left unplaced has no entrances to read: no fix asked for.
        val bus = TripLeg("bus", "73", "73", "A", "A", "C", "C", at(5), at(15))
        assertFalse(OnTheWay.wantsFix(ActiveTrip(TripRoute(listOf(toStop, bus)), "C", startedAt = t0), at(1)))
    }

    @Test
    fun `a rider still some way off, a vague fix, or none, walks on out the walk's time`() {
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, downTheLine, at(1)))
        // 55 m out but only sure to 100 m: they could be anywhere up to 155 m away.
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, fix(Coordinates(51.5005, -0.12), accuracyMeters = 100f), at(1)))
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, null, at(1)))
        assertEquals(TripProgress.Walking(toStop, at(2)), OnTheWay.advance(walkingTrip, null, at(1)).second)
    }

    @Test
    fun `no fix is asked for on a walk to the destination, or to a stop with no position`() {
        val lastWalk = TripLeg(TripLeg.WALKING, "", "", "E", "E", "F", "F", at(30), at(35))
        val finishing = ActiveTrip(TripRoute(listOf(second, lastWalk)), "F", startedAt = t0, legIndex = 1)
        assertNull(OnTheWay.walkingTo(finishing, at(31)))
        assertFalse(OnTheWay.wantsFix(finishing, at(31)))
        // The walk between rides goes to D, whose position isn't known.
        assertNull(OnTheWay.walkingTo(trip.copy(legIndex = 1), at(16)))
    }

    @Test
    fun `a fix sure only to 80 m still settles arriving on foot, but not a rider left behind`() {
        // At the stop, sure to 80 m: wherever they are, they're within 100 m of it.
        val vague = LocationFix(platform, isFallback = false, accuracyMeters = 80f, ageMillis = 1_000L)
        assertEquals(vague, OnTheWay.usableFix(vague, walkingTrip, at(1)))
        assertEquals(1, OnTheWay.seen(walkingTrip, OnTheWay.usableFix(vague, walkingTrip, at(1)), at(1)).legIndex)
        // Just after boarding, the same fix can't tell a rider on the train from one on the platform.
        val onBoard = OnTheWay.follow(placed, train("8", 5)).copy(boarded = true, boardedAt = at(6))
        assertNull(OnTheWay.usableFix(vague, onBoard, at(7)))
        // Nor is one sure only to 120 m taken for arriving: it can't place them within 100 m.
        assertNull(OnTheWay.usableFix(vague.copy(accuracyMeters = 120f), walkingTrip, at(1)))
    }

    @Test
    fun `on the way into a station, a fix sure enough for its entrances is the one waited for`() {
        // Sure to 100 m: taken if nothing better comes, but not what the wait stops for, since it
        // can't tell an entrance (Codex, PR #389). Sure to 30 m, it can.
        val vague = fix(platform, accuracyMeters = 100f)
        val sharp = fix(platform, accuracyMeters = 30f)
        assertTrue(OnTheWay.sureEnoughFor(walkingTrip, at(1))(vague))
        assertFalse(OnTheWay.preferredFor(walkingTrip, at(1))(vague))
        assertTrue(OnTheWay.preferredFor(walkingTrip, at(1))(sharp))
        // Nearly at the station they get off at, the same.
        assertFalse(OnTheWay.preferredFor(nearlyThere, at(12))(vague))
        assertTrue(OnTheWay.preferredFor(nearlyThere, at(12))(sharp))
        // A walk to a bus stop has no entrances to tell: the vague fix ends the wait, as before.
        val bus = TripLeg("bus", "73", "73", "A", "A", "C", "C", at(5), at(15), fromAt = platform)
        val toBus = ActiveTrip(TripRoute(listOf(toStop, bus)), "C", startedAt = t0)
        assertTrue(OnTheWay.preferredFor(toBus, at(1))(vague))
        // Never looser than what's usable: a stale sharp fix isn't waited for either.
        assertFalse(OnTheWay.preferredFor(walkingTrip, at(1))(sharp.copy(ageMillis = 30_000L)))
    }

    @Test
    fun `once the walk's time is up no fix is asked for, and it ends on its time`() {
        // The walk to A runs 0–2 min.
        assertTrue(OnTheWay.wantsFix(walkingTrip, at(1)))
        assertNull(OnTheWay.walkingTo(walkingTrip, at(2)))
        assertFalse(OnTheWay.wantsFix(walkingTrip, at(2)))
        // A fix at the stop by then changes nothing: the walk's own end moves the trip on.
        assertEquals(walkingTrip, OnTheWay.seen(walkingTrip, stillThere, at(2)))
    }

    @Test
    fun `the board's routes are asked for its mode's named lines only`() {
        val board = listOf(
            Departure("red", "Red", "outbound", "C", null, at(3), "tube"),
            Departure("", "", "outbound", "C", null, at(4), "tube"),
            Departure("green", "Green", "outbound", "C", null, at(5), "tube"),
            Departure("red", "Red", "outbound", "C", null, at(6), "tube"),
            Departure("99", "99", "outbound", "C", null, at(2), "bus"),
        )
        assertEquals(listOf("green", "red"), OnTheWay.boardLineIds(ride, board))
    }

    @Test
    fun `a bus reaching the alighting stop pair by its other pole is on the board too`() {
        // The ride gets off at pole P1 of stop pair G; bus 2 calls at the pair's other pole, P2.
        val bus = TripLeg("bus", "1", "1", "Q", "Q", "P1", "Stop", at(5), at(15), toArea = "490GEXAMPLE")
        val one = LineSequence(listOf(LineRoute("Q-P1", listOf("Q", "P1"))), mapOf("Q" to "Q", "P1" to "Stop"), stopAreas = mapOf("P1" to "490GEXAMPLE"))
        val two = LineSequence(listOf(LineRoute("Q-P2", listOf("Q", "P2"))), mapOf("Q" to "Q", "P2" to "Stop"), stopAreas = mapOf("P2" to "490GEXAMPLE"))
        val other = LineSequence(listOf(LineRoute("Q-X", listOf("Q", "X"))), mapOf("Q" to "Q", "X" to "Elsewhere"))
        val board = listOf(
            Departure("1", "1", "outbound", "P1", null, at(3), "bus"),
            Departure("2", "2", "outbound", "P2", null, at(4), "bus"),
            Departure("3", "3", "outbound", "X", null, at(5), "bus"),
        )
        val found = OnTheWay.boardTrains(bus, board, t0, mapOf("1" to one, "2" to two, "3" to other), t0)
        assertEquals(listOf("1", "2"), found.trains.map { it.lineId })
    }

    @Test
    fun `the rider is ready to board when the walk or change ends, or from when the wait began`() {
        val walking = trip.copy(legIndex = 1)
        assertEquals(at(20), OnTheWay.readyAt(walking, TripProgress.Walking(walk, at(20))))
        assertEquals(at(3), OnTheWay.readyAt(trip, TripProgress.Changing(ride, at(3))))
        assertEquals(t0, OnTheWay.readyAt(trip, TripProgress.Waiting(ride, null)))
        assertNull(OnTheWay.readyAt(trip, TripProgress.Riding(ride, "B", 2, null, false)))
        assertNull(OnTheWay.readyAt(trip, TripProgress.Arrived))
    }

    @Test
    fun `the upcoming ride is the one after the walk, the one waited for, and none once on board`() {
        assertEquals(ride.copy(fromAt = platform), OnTheWay.upcomingRide(walkingTrip))
        assertEquals(ride, OnTheWay.upcomingRide(trip))
        assertNull(OnTheWay.upcomingRide(trip.copy(boarded = true)))
        // The walk to D goes on to the second ride; none after the last leg.
        assertEquals(second, OnTheWay.upcomingRide(trip.copy(legIndex = 1)))
        assertNull(OnTheWay.upcomingRide(trip.copy(legIndex = 3)))
    }

    @Test
    fun `a ride's trains, gone or still to come, are those whose route takes them to the stop`() {
        val red = LineSequence(
            listOf(LineRoute("A-C", listOf("A", "B", "C")), LineRoute("A-Z", listOf("A", "B", "Z"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Z" to "Z"),
        )
        // Both left A a while ago: the one for C takes the ride, the one for the Z branch doesn't,
        // though both run through B first.
        val gone = listOf(
            Departure("red", "Red", "outbound", "C", null, t0, "tube", vehicleId = "1"),
            Departure("red", "Red", "outbound", "Z", null, t0, "tube", vehicleId = "2"),
        )
        assertEquals(listOf("1"), OnTheWay.takesRide(ride, gone, mapOf("red" to red)).map { it.vehicleId })
        // With no route for the line, none is vouched for.
        assertEquals(emptyList<Departure>(), OnTheWay.takesRide(ride, gone, emptyMap()))
    }

    @Test
    fun `the next ride's board keeps every line that reaches the stop, and no other branch`() {
        // Two lines from A: red to C, and green, which also reaches C; red's "Z" branch doesn't.
        val red = LineSequence(
            listOf(LineRoute("A-C", listOf("A", "B", "C")), LineRoute("A-Z", listOf("A", "Y", "Z"))),
            mapOf("A" to "A", "B" to "B", "C" to "C", "Y" to "Y", "Z" to "Z"),
        )
        val green = LineSequence(listOf(LineRoute("A-C", listOf("A", "C"))), mapOf("A" to "A", "C" to "C"))
        val board = listOf(
            Departure("red", "Red", "outbound", "C", null, at(3), "tube"),
            Departure("red", "Red", "outbound", "Z", null, at(4), "tube"),
            Departure("green", "Green", "outbound", "C", null, at(6), "tube"),
            // Another mode at the same stop: not a way the ride is taken.
            Departure("99", "99", "outbound", "C", null, at(2), "bus"),
        )
        val found = OnTheWay.boardTrains(ride, board, t0, mapOf("red" to red, "green" to green), t0)
        assertEquals(listOf("red" to at(3), "green" to at(6)), found.trains.map { it.lineId to it.expectedArrival })
        assertFalse(found.pending)
        // A line whose route is still loading isn't guessed at, and says so.
        val loading = OnTheWay.boardTrains(ride, board, t0, mapOf("red" to red), t0)
        assertEquals(listOf("red"), loading.trains.map { it.lineId })
        assertTrue(loading.pending)
        // A line whose route failed can't vouch for its trains: said, not taken for none.
        val failed = OnTheWay.boardTrains(ride, board, t0, mapOf("red" to red, "green" to null), t0)
        assertEquals(listOf("red"), failed.trains.map { it.lineId })
        assertTrue(failed.unresolved)
        // A failed route is logged by its own fetch, so it names no miss here.
        assertTrue(failed.misses.isEmpty())
        assertFalse(found.unresolved)
        assertTrue(found.misses.isEmpty())
        // A loaded route that can't place a train's destination names it, for the debug log.
        val lost = OnTheWay.boardTrains(ride, listOf(Departure("red", "Red", "outbound", "Nowhere", null, at(3), "tube")), t0, mapOf("red" to red), t0)
        assertTrue(lost.unresolved)
        assertEquals(setOf("red"), lost.misses.map { it.lineId }.toSet())
    }
}

