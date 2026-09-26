package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following a started trip, on synthetic stops A–E and example vehicle ids. */
class ActiveTripTrackerTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))
    private var now = t0

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val route = TripRoute(listOf(ride))

    private fun train(vehicle: String, minutes: Long) =
        Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)
    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))

    private val departures = mutableMapOf<String, List<Departure>>()
    private val trains = mutableMapOf<String, List<VehicleCall>>()
    private var failing = false
    private val gone = mutableSetOf<String>()
    private val asked = mutableListOf<String>()
    private var kept: ActiveTrip? = null
    private var saves = true
    // The next save is cut short, as by the activity being recreated mid-write.
    private var cancelNextSave = false
    private var current: ActiveTripTracker? = null
    // Whether the trip was still shown when it was forgotten on the device: a refresh cancelled
    // once it's gone from the screen must not leave it kept.
    private var shownWhenForgotten: Boolean? = null
    private val warned = mutableListOf<TripProgress.Riding>()
    private var alertPosts = true

    private fun tracker(dispatcher: kotlinx.coroutines.CoroutineDispatcher, load: () -> ActiveTrip? = { null }) = ActiveTripTracker(
        load = load,
        save = {
            if (cancelNextSave) {
                cancelNextSave = false
                throw kotlinx.coroutines.CancellationException("recreated")
            }
            if (it == null) shownWhenForgotten = current?.trip?.value != null
            kept = it
            saves
        },
        arrivals = { stop -> departures[stop].orEmpty() },
        vehicles = object : VehicleSource {
            override suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall> {
                if (failing) throw TflException.Offline(null)
                if (vehicleId in gone) throw TflException.NotFound(null)
                asked += vehicleId
                return trains[vehicleId].orEmpty()
            }
        },
        clock = { now },
        io = dispatcher,
        onGetOffSoon = { _, riding ->
            warned += riding
            alertPosts
        },
    ).also { current = it }

    @Test
    fun `Start follows the soonest train that runs where the rider is going`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // The 3-minute train is too soon to reach; the 4-minute one runs the other way.
        departures["A"] = listOf(train("1", 3), train("2", 4), train("3", 6))
        trains["2"] = listOf(call("C", 2), call("A", 4))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(4))
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(6)), tracker.progress.value)
        assertEquals(listOf("2", "3"), asked)
        assertEquals(tracker.trip.value, kept)
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
        tracker.start(TripRoute(listOf(bus)), "Example Street", readyAt = at(4))
        tracker.refresh()
        assertEquals("LX02", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `a trip that starts with a walk walks first, then picks its train`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        val toA = TripLeg(TripLeg.WALKING, "", "", "Z", "Z", "A", "A", at(0), at(4))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(TripRoute(listOf(toA, ride)), "C", readyAt = at(1))
        // The rider sets off at 1; the walk takes 4 minutes.
        assertEquals(TripProgress.Walking(toA, at(5)), tracker.progress.value)
        assertEquals(0, tracker.trip.value?.legIndex)
        now = at(5)
        tracker.refresh()
        assertEquals(1, tracker.trip.value?.legIndex)
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `on board, get off soon is said once`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        tracker.refresh()
        assertEquals(1, warned.size)
        assertTrue((tracker.progress.value as TripProgress.Riding).getOffSoon)
    }

    @Test
    fun `get off soon not said (notifications off) is tried again on the next refresh`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
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
        tracker.start(route, "C", readyAt = at(1))
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
    }

    @Test
    fun `a train TfL no longer knows is passed over for the next`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("2", 5), train("3", 6))
        gone += "2"
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(4))
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
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        gone += "3"
        departures["A"] = listOf(train("4", 9))
        tracker.refresh()
        tracker.refresh()
        assertEquals("4", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `an arrival that can't be forgotten on the device isn't let go, and says so`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
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
        saves = true
        tracker.refresh()
        assertNull(tracker.trip.value)
        assertFalse(tracker.endFailed.value)
    }

    @Test
    fun `a failed lookup says so and claims nothing new`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        failing = true
        tracker.refresh()
        assertTrue(tracker.failed.value)
        assertEquals("3", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `arriving forgets the trip, and says it arrived`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
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
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        now = at(7)
        trains["3"] = listOf(call("B", 9))
        tracker.refresh()
        // TfL's predictions go quiet before C was ever predicted: not arrived, still on train 3.
        now = at(10)
        trains["3"] = emptyList()
        tracker.refresh()
        assertEquals(TripProgress.Lost(ride), tracker.progress.value)
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertTrue(checkNotNull(tracker.trip.value).boarded)
    }

    @Test
    fun `a trip that couldn't be saved says so, and saying so clears once it is`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        saves = false
        tracker.start(route, "C", readyAt = at(1))
        assertTrue(tracker.notKept.value)
        saves = true
        tracker.refresh()
        assertFalse(tracker.notKept.value)
    }

    @Test
    fun `Start before the kept trip is read doesn't replace it`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.start(TripRoute(listOf(ride.copy(lineName = "Other"))), "Elsewhere", readyAt = at(1))
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
            tracker.start(route, "C", readyAt = at(1))
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
        tracker.start(route, "C", readyAt = at(1))
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
    fun `a kept trip comes back after the app was closed`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.restore()
        assertEquals(saved, tracker.trip.value)
        tracker.end()
        assertNull(tracker.trip.value)
        assertNull(kept)
    }
}
