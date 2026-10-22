package app.stopdash

import android.app.Application
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.DistanceSystem
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.ui.ActiveTripTracker
import app.stopdash.ui.HeaderWords
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The trip's notification as a Live Update: its bar, its chip, and where to board. Well-known stations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class OnTheWayLiveUpdateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))

    private val victoria = TripLeg(
        "tube", "victoria", "Victoria", "940GZZLUKSX", "King's Cross St. Pancras", "940GZZLUVIC", "Victoria", at(2), at(12),
        path = listOf("940GZZLUEUS", "940GZZLUWRR", "940GZZLUOXC", "940GZZLUGPK", "940GZZLUVIC"), headings = listOf("Brixton Underground Station"),
    )
    private val walk = TripLeg(TripLeg.WALKING, "", "", "940GZZLUVIC", "Victoria", "EXAMPLE-POLE-A", "Victoria Station", at(12), at(16))
    private val bus = TripLeg("bus", "24", "24", "EXAMPLE-POLE-A", "Victoria Station", "EXAMPLE-POLE-B", "Euston", at(18), at(40), headings = listOf("Euston"))
    private val trip = ActiveTrip(TripRoute(listOf(victoria, walk, bus)), "Euston", startedAt = now, vehicleId = "EXAMPLE1")
    private val waiting = TripProgress.Waiting(victoria, at(4))

    private fun train(vehicle: String, destination: String, platform: String?, line: String = "victoria") =
        Departure(line, "Victoria", "outbound", destination, platform, at(4), "tube", vehicleId = vehicle)

    private fun board(vararg trains: Departure, ride: TripLeg = victoria) = ActiveTripTracker.NextBoard(ride, trains.toList(), fetchedAt = now)

    @Test
    fun `waiting, the train followed's destination and platform, from the board`() {
        val boarding = OnTheWayLiveUpdate.boarding(
            trip, waiting,
            board(train("OTHER", "Walthamstow Central Underground Station", "Northbound - Platform 7"), train("EXAMPLE1", "Brixton Underground Station", "Southbound - Platform 8")),
            now,
        )
        assertEquals(OnTheWayLiveUpdate.Boarding("Brixton", "Platform 8"), boarding)
        assertEquals("➔ Brixton · Platform 8 · Due in 4 min", OnTheWayLiveUpdate.withBoarding(app.resources, boarding, "Due in 4 min"))
    }

    @Test
    fun `waiting with the train not on the board, where the rider gets off, no platform`() {
        // Not the plan's heading ("Brixton"): that names the route's last stop, not always a blind.
        assertEquals(OnTheWayLiveUpdate.Boarding("Victoria"), OnTheWayLiveUpdate.boarding(trip, waiting, null, now))
        // Another line's train with the same id is not the one followed.
        val other = board(train("EXAMPLE1", "Edgware Underground Station", "Platform 1", line = "northern"))
        assertEquals(OnTheWayLiveUpdate.Boarding("Victoria"), OnTheWayLiveUpdate.boarding(trip, waiting, other, now))
    }

    @Test
    fun `a bus, its stop's letter, not a platform`() {
        val onBus = trip.copy(legIndex = 2, vehicleId = "EXAMPLE2")
        val pole = StopLocation("EXAMPLE-POLE-A", "Victoria Station", 0.0, 0.0, stopLetter = "g")
        val busBoard = ActiveTripTracker.NextBoard(bus, listOf(Departure("24", "24", "outbound", "Euston", "G", at(20), "bus", vehicleId = "EXAMPLE2")), now, pole = pole)
        val boarding = OnTheWayLiveUpdate.boarding(onBus, TripProgress.Waiting(bus, at(20)), busBoard, now)
        assertEquals(OnTheWayLiveUpdate.Boarding("Euston", "Stop G"), boarding)
        assertEquals("➔ Euston · Stop G · Due in 20 min", OnTheWayLiveUpdate.withBoarding(app.resources, boarding, "Due in 20 min"))
    }

    @Test
    fun `the platform or pole is named in the words the app gives it`() {
        val german = HeaderWords({ "Gleis $it" }, { "Haltestelle $it" }, { "Richtung $it" }, emptyMap())
        val platform = OnTheWayLiveUpdate.boarding(
            trip, waiting, board(train("EXAMPLE1", "Brixton Underground Station", "Southbound - Platform 8")), now, words = german,
        )
        assertEquals(OnTheWayLiveUpdate.Boarding("Brixton", "Gleis 8"), platform)
        val onBus = trip.copy(legIndex = 2, vehicleId = "EXAMPLE2")
        val pole = StopLocation("EXAMPLE-POLE-A", "Victoria Station", 0.0, 0.0, stopLetter = "g")
        val busBoard = ActiveTripTracker.NextBoard(bus, listOf(Departure("24", "24", "outbound", "Euston", "G", at(20), "bus", vehicleId = "EXAMPLE2")), now, pole = pole)
        assertEquals(
            OnTheWayLiveUpdate.Boarding("Euston", "Haltestelle G"),
            OnTheWayLiveUpdate.boarding(onBus, TripProgress.Waiting(bus, at(20)), busBoard, now, words = german),
        )
    }

    @Test
    fun `a letterless bus pole, its sign's towards, else its bearing, as the board heads it`() {
        val onBus = trip.copy(legIndex = 2, vehicleId = "EXAMPLE2")
        val departing = TripProgress.Waiting(bus, at(20))
        fun headed(pole: StopLocation) = OnTheWayLiveUpdate.boarding(onBus, departing, ActiveTripTracker.NextBoard(bus, emptyList(), now, pole = pole), now)
        assertEquals("➔ King's Cross", headed(StopLocation("EXAMPLE-POLE-A", "Victoria Station", 0.0, 0.0, towards = "King's Cross Or Camden Town", bearing = "N"))?.where)
        assertEquals("Northbound", headed(StopLocation("EXAMPLE-POLE-A", "Victoria Station", 0.0, 0.0, bearing = "N"))?.where)
        // Beside the train's own destination, a sign's "towards" is left to the chip, not said twice.
        val towards = OnTheWayLiveUpdate.Boarding("Euston", "➔ King's Cross")
        assertEquals("➔ Euston · Due in 20 min", OnTheWayLiveUpdate.withBoarding(app.resources, towards, "Due in 20 min"))
        // On the chip, shortened as every name is; with no form short enough, the shortest, for Android to cut.
        assertEquals("➔ King's X", OnTheWayLiveUpdate.chipText(app.resources, departing, now, towards))
        // The arrow isn't the name's first word: the place keeps its name, never "➔ A." at its floor.
        val archway = OnTheWayLiveUpdate.Boarding("Euston", "➔ Archway")
        assertEquals("➔ Archway", OnTheWayLiveUpdate.chipText(app.resources, departing, now, archway))
    }

    @Test
    fun `a tram boards at a pole too, headed by its sign, not a platform`() {
        val tram = bus.copy(mode = "tram", lineId = "tram", lineName = "Tram", headings = emptyList())
        val onTram = ActiveTrip(TripRoute(listOf(tram)), "Euston", startedAt = now)
        val pole = StopLocation("EXAMPLE-POLE-A", "Victoria Station", 0.0, 0.0, towards = "King's Cross")
        val boarding = OnTheWayLiveUpdate.boarding(onTram, TripProgress.Waiting(tram, null), ActiveTripTracker.NextBoard(tram, emptyList(), now, pole = pole), now)
        assertEquals("➔ King's Cross", boarding?.where)
    }

    @Test
    fun `changing, the platform of the next train the rider can catch to where the plan's goes`() {
        val changing = TripProgress.Changing(victoria, at(3))
        val trains = board(
            // Gone before the change is done; the other way; then the one to take.
            Departure("victoria", "Victoria", "outbound", "Brixton Underground Station", "Southbound - Platform 8", at(2), "tube"),
            Departure("victoria", "Victoria", "inbound", "Walthamstow Central Underground Station", "Northbound - Platform 7", at(3), "tube"),
            Departure("victoria", "Victoria", "outbound", "Brixton Underground Station", "Southbound - Platform 5", at(4), "tube"),
        )
        assertEquals(OnTheWayLiveUpdate.Boarding("Brixton", "Platform 5"), OnTheWayLiveUpdate.boarding(trip, changing, trains, now))
        // A tick past that train, before the next refresh: not its platform any more, nor its destination.
        assertEquals(OnTheWayLiveUpdate.Boarding("Victoria"), OnTheWayLiveUpdate.boarding(trip, changing, trains, at(5)))
        // Only the other way listed: no platform rather than the wrong one.
        val otherWay = board(Departure("victoria", "Victoria", "inbound", "Walthamstow Central Underground Station", "Northbound - Platform 7", at(4), "tube"))
        assertEquals(OnTheWayLiveUpdate.Boarding("Victoria"), OnTheWayLiveUpdate.boarding(trip, changing, otherWay, now))
    }

    @Test
    fun `a bus's heading is its blind, not the route's last stop the Planner names`() {
        // The Planner heads the ride by its route's last stop; the buses' blinds say somewhere else.
        val ride = TripLeg(
            "bus", "24", "24", "EXAMPLE-POLE-A", "Victoria Station", "EXAMPLE-POLE-B", "Example Stop B", at(18), at(40),
            headings = listOf("Example Terminus"),
        )
        val route = LineSequence(
            listOf(LineRoute("Victoria Station - Example Terminus", listOf("EXAMPLE-POLE-A", "EXAMPLE-POLE-M", "EXAMPLE-POLE-B", "EXAMPLE-POLE-T"))),
            mapOf(
                "EXAMPLE-POLE-A" to "Victoria Station", "EXAMPLE-POLE-M" to "Example Stop M",
                "EXAMPLE-POLE-B" to "Example Stop B", "EXAMPLE-POLE-T" to "Example Terminus",
            ),
        )
        val onBus = ActiveTrip(TripRoute(listOf(victoria, ride)), "Example Stop B", startedAt = now, legIndex = 1)
        val changing = TripProgress.Changing(ride, at(18))
        fun bus(destination: String, minutes: Long) = Departure("24", "24", "outbound", destination, null, at(minutes), "bus")
        // Gone before the change is done; a short working that ends before the rider's stop; then the one to take.
        val board = ActiveTripTracker.NextBoard(ride, listOf(bus("Euston", 17), bus("Example Stop M", 19), bus("Euston", 21)), now)
        val routes = mapOf("24" to route)
        assertEquals("Euston", OnTheWayLiveUpdate.boarding(onBus, changing, board, now) { routes[it] }?.heading)
        // Waiting with no bus followed yet, the same.
        assertEquals("Euston", OnTheWayLiveUpdate.boarding(onBus, TripProgress.Waiting(ride, at(21)), board, at(19)) { routes[it] }?.heading)
        // The route not held yet, so no bus is known to take the ride: where the rider gets off.
        assertEquals("Example Stop B", OnTheWayLiveUpdate.boarding(onBus, changing, board, now)?.heading)
        // Held, with none on the board that takes the ride: not a short working's blind either.
        val shortOnly = board.copy(departures = listOf(bus("Example Stop M", 19)))
        assertEquals("Example Stop B", OnTheWayLiveUpdate.boarding(onBus, changing, shortOnly, now) { routes[it] }?.heading)

        // Routed once for the board and read again each tick: once the bus to take has gone, no blind is left.
        val placements = OnTheWayLiveUpdate.Placements()
        assertEquals("Euston", OnTheWayLiveUpdate.boarding(onBus, changing, board, now, placements) { routes[it] }?.heading)
        assertEquals("Example Stop B", OnTheWayLiveUpdate.boarding(onBus, changing, board, at(22), placements) { routes[it] }?.heading)
        val placed = placements.of(board, ride, route, now, now)
        assertSame(placed, placements.of(board, ride, route, now, at(1)))
        // A new board, or the route loaded again, is routed afresh.
        assertNotSame(placed, placements.of(board.copy(), ride, route, now, at(1)))
        assertNotSame(placed, placements.of(board, ride, route.copy(), now, at(1)))
        // The clock set back before the last placing: placed again, so the trains it had dropped come back.
        val rewound = OnTheWayLiveUpdate.Placements()
        assertEquals("Example Stop B", OnTheWayLiveUpdate.boarding(onBus, changing, board, at(22), rewound) { routes[it] }?.heading)
        assertEquals("Euston", OnTheWayLiveUpdate.boarding(onBus, changing, board, now, rewound) { routes[it] }?.heading)
    }

    @Test
    fun `waiting with none followed, the next train's heading and platform both, not a doubtful one`() {
        val unfollowed = trip.copy(vehicleId = "")
        val trains = board(
            // TfL may be tracking this one twice: the tracker would pick the next, so the notification names that.
            Departure("victoria", "Victoria", "outbound", "Brixton Underground Station", "Southbound - Platform 8", at(4), "tube", doubtful = true),
            Departure("victoria", "Victoria", "outbound", "Brixton Underground Station", "Southbound - Platform 5", at(6), "tube"),
        )
        assertEquals(OnTheWayLiveUpdate.Boarding("Brixton", "Platform 5"), OnTheWayLiveUpdate.boarding(unfollowed, waiting, trains, now))
        // Only the doubtful one listed: that one, rather than none.
        val doubtfulOnly = board(Departure("victoria", "Victoria", "outbound", "Brixton Underground Station", "Southbound - Platform 8", at(4), "tube", doubtful = true))
        assertEquals(OnTheWayLiveUpdate.Boarding("Brixton", "Platform 8"), OnTheWayLiveUpdate.boarding(unfollowed, waiting, doubtfulOnly, now))
    }

    @Test
    fun `a platform with no number, the way it faces`() {
        val boarding = OnTheWayLiveUpdate.boarding(trip, waiting, board(train("EXAMPLE1", "Brixton Underground Station", "Southbound")), now)
        assertEquals(OnTheWayLiveUpdate.Boarding("Brixton", "Southbound"), boarding)
    }

    @Test
    fun `a change names the next ride's heading, riding and walking name none`() {
        assertEquals(OnTheWayLiveUpdate.Boarding("Euston"), OnTheWayLiveUpdate.boarding(trip, TripProgress.Changing(bus, at(18)), null, now))
        assertNull(OnTheWayLiveUpdate.boarding(trip, TripProgress.Walking(walk, at(16)), null, now))
        assertNull(OnTheWayLiveUpdate.boarding(trip, TripProgress.Riding(victoria, "Euston", 5, at(12), getOffSoon = false), null, now))
    }

    @Test
    fun `the chip, the platform or stop when boarding, stops riding, minutes walking`() {
        // Shortened as every name is, only where it runs past the chip: in full, "Platform 8" showed as
        // "Platform", the number cut off.
        assertEquals("Plat 8", OnTheWayLiveUpdate.chipText(app.resources, waiting, now, OnTheWayLiveUpdate.Boarding("Brixton", "Platform 8")))
        assertEquals("Plat 12", OnTheWayLiveUpdate.chipText(app.resources, waiting, now, OnTheWayLiveUpdate.Boarding("Brixton", "Platform 12")))
        // Closed up where even that runs over, so a cut can't leave another platform's number ("Plat 10").
        assertEquals("Plat10A", OnTheWayLiveUpdate.chipText(app.resources, waiting, now, OnTheWayLiveUpdate.Boarding("Stratford", "Platform 10A")))
        assertEquals("Stop G", OnTheWayLiveUpdate.chipText(app.resources, waiting, now, OnTheWayLiveUpdate.Boarding("Euston", "Stop G")))
        // Neither known: where the train goes, in full where it fits, else shortened alike.
        assertEquals("Brixton", OnTheWayLiveUpdate.chipText(app.resources, waiting, now, OnTheWayLiveUpdate.Boarding("Brixton")))
        assertEquals("Kew Br", OnTheWayLiveUpdate.chipText(app.resources, waiting, now, OnTheWayLiveUpdate.Boarding("Kew Bridge")))
        assertEquals("4 min", OnTheWayLiveUpdate.chipText(app.resources, waiting, now))
        assertEquals("5 stops", OnTheWayLiveUpdate.chipText(app.resources, TripProgress.Riding(victoria, "Euston", 5, at(12), getOffSoon = false), now))
        assertEquals("Next stop", OnTheWayLiveUpdate.chipText(app.resources, TripProgress.Riding(victoria, "Victoria", 1, at(12), getOffSoon = true), now))
        assertEquals("3 min", OnTheWayLiveUpdate.chipText(app.resources, TripProgress.Walking(walk, at(16)), at(13)))
    }

    @Test
    fun `the chip on a walk, the distance left in the rider's units, minutes until a fix places them`() {
        val placed = TripProgress.Walking(walk, at(16), metersLeft = 450.0)
        assertEquals("450 m", OnTheWayLiveUpdate.chipText(app.resources, placed, at(13), system = DistanceSystem.METERS))
        assertEquals("~450 m", OnTheWayLiveUpdate.chipText(app.resources, placed.copy(estimated = true), at(13), system = DistanceSystem.METERS))
        // The units not read yet, or no fix: minutes, never a distance in the wrong units.
        assertEquals("3 min", OnTheWayLiveUpdate.chipText(app.resources, placed, at(13), system = null))
        assertEquals("3 min", OnTheWayLiveUpdate.chipText(app.resources, TripProgress.Walking(walk, at(16)), at(13), system = DistanceSystem.METERS))
    }

    @Test
    fun `the chip on a walk names the way its end lies, beside the app's route arrow`() {
        val placed = TripProgress.Walking(walk, at(16), metersLeft = 450.0, bearing = 62.0)
        // Within the chip's seven characters (maintainer, 2026-10-11): the distance closed up, then the
        // space after the point dropped where that still runs over.
        assertEquals("NE 450m", OnTheWayLiveUpdate.chipText(app.resources, placed, at(13), system = DistanceSystem.METERS))
        assertEquals("NE~450m", OnTheWayLiveUpdate.chipText(app.resources, placed.copy(estimated = true), at(13), system = DistanceSystem.METERS))
        val far = OnTheWayLiveUpdate.chipText(app.resources, placed.copy(metersLeft = 1_200.0), at(13), system = DistanceSystem.METERS)!!
        assertTrue(far, far.startsWith("NE") && far.length <= OnTheWayLiveUpdate.CHIP_CHARS)
        val miles = OnTheWayLiveUpdate.chipText(app.resources, placed.copy(metersLeft = 1_200.0), at(13), system = DistanceSystem.YARDS)!!
        assertTrue(miles, miles.startsWith("NE") && miles.length <= OnTheWayLiveUpdate.CHIP_CHARS)
        // The app's route arrow beside it, not a compass (maintainer, 2026-10-13).
        val shown = OnTheWayNotification.build(app, trip.copy(legIndex = 1), placed, failed = false, updatedAt = at(13), now = at(13))
        assertEquals(R.drawable.ic_stat_route_arrow, shown.smallIcon.resId)
        val failed = OnTheWayNotification.build(app, trip.copy(legIndex = 1), placed, failed = true, updatedAt = at(13), now = at(13))
        assertEquals(R.drawable.ic_stat_route_arrow, failed.smallIcon.resId)
    }

    @Test
    fun `the bar, a segment per leg in its line's color, the rider along it`() {
        val style = OnTheWayLiveUpdate.style(trip, TripProgress.Riding(victoria, "Oxford Circus", 3, at(12), getOffSoon = false), at(6))!!
        assertEquals(listOf(600, 240, 1320), style.progressSegments.map { it.length })
        // The Victoria line's light blue, the walk neutral, the bus red.
        assertEquals(0xFF0098D4.toInt(), style.progressSegments[0].color)
        assertNotEquals(style.progressSegments[0].color, style.progressSegments[1].color)
        assertNotEquals(style.progressSegments[1].color, style.progressSegments[2].color)
        // Two of five stops ridden.
        assertEquals(240, style.progress)
    }

    @Test
    fun `the notification asks to be a Live Update, with its chip and bar`() {
        val notification = OnTheWayNotification.build(app, trip, waiting, failed = false, updatedAt = now, now = now, boarding = OnTheWayLiveUpdate.Boarding("Brixton", "Platform 8"))
        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
        // What Android 16 asks of a Live Update, item by item: Robolectric's Android 16 image has a
        // pre-release hasPromotableCharacteristics() that asks the opposite of the released one on colorizing.
        assertEquals("android.app.Notification\$ProgressStyle", notification.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.extras.getBoolean(android.app.Notification.EXTRA_COLORIZED))
        assertNull(notification.contentView)
        assertEquals("Plat 8", NotificationCompat.getShortCriticalText(notification))
        // The text has the room the chip hasn't: the platform in full.
        assertEquals("➔ Brixton · Platform 8 · Due in 4 min", notification.extras.getString(android.app.Notification.EXTRA_TEXT))
    }

    @Test
    fun `the first notification, posted before the service is in the foreground, is plain`() {
        val notification = OnTheWayNotification.build(app, trip, waiting, failed = false, updatedAt = now, now = now, live = false)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
        assertNull(NotificationCompat.getShortCriticalText(notification))
        assertNull(notification.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun `a train that can't be placed has no bar`() {
        assertNull(OnTheWayLiveUpdate.style(trip, TripProgress.Lost(victoria), at(6)))
        val notification = OnTheWayNotification.build(app, trip, TripProgress.Lost(victoria), failed = false, updatedAt = now, now = now)
        assertNull(notification.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun `swiped away, it isn't posted again for that trip, but is for the next`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        OnTheWayNotification.ensureChannel(app)
        val manager = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        try {
            val notification = OnTheWayNotification.build(app, trip, waiting, failed = false, updatedAt = now, now = now)
            // The delete intent says which trip it was.
            val delete = shadowOf(notification.deleteIntent).savedIntent
            OnTheWayDismissReceiver().onReceive(app, delete)
            OnTheWayNotification.show(app, trip, waiting, failed = false, updatedAt = now)
            assertNull(manager.getNotification(OnTheWayNotification.ID))
            // Another trip started: shown again.
            OnTheWayNotification.show(app, trip.copy(startedAt = at(30)), waiting, failed = false, updatedAt = now)
            assertNotNull(manager.getNotification(OnTheWayNotification.ID))
        } finally {
            OnTheWayNotification.dismissedTrip = null
        }
    }

    @Test
    fun `no current answer, no chip and no bar, the text says it's updating`() {
        val notification = OnTheWayNotification.build(app, trip, waiting, failed = false, updatedAt = null, now = now, boarding = OnTheWayLiveUpdate.Boarding("Brixton", "Platform 8"))
        assertNull(NotificationCompat.getShortCriticalText(notification))
        assertEquals("Updating…", notification.extras.getString(android.app.Notification.EXTRA_TEXT))
        val failed = OnTheWayNotification.build(app, trip, waiting, failed = true, updatedAt = now, now = now, boarding = OnTheWayLiveUpdate.Boarding("Brixton"))
        assertNull(NotificationCompat.getShortCriticalText(failed))
        assertFalse(failed.extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty().contains("Brixton"))
    }
}
