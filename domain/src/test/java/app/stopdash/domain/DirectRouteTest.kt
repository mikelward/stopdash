package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DirectRouteTest {
    private val departs = Instant.parse("2026-10-15T08:20:00Z")
    private val arrives = departs.plus(Duration.ofMinutes(9))
    private val victoria = Coordinates(51.4965, -0.1447)
    private val oxfordCircus = Coordinates(51.5152, -0.1418)

    // A rider a short way from the stop, and the place a short way on from where it gets off: synthetic points.
    private val here = Coordinates(51.4990, -0.1447)
    private val place = TripDestination.Place(Coordinates(51.5180, -0.1418), "Work")

    private val ride = TripLeg(
        mode = "tube",
        lineId = "victoria",
        lineName = "Victoria",
        fromId = "940GZZLUVIC",
        fromName = "Victoria",
        toId = "940GZZLUOXC",
        toName = "Oxford Circus",
        departure = departs,
        arrival = arrives,
        path = listOf("940GZZLUGPK", "940GZZLUOXC"),
        fromAt = victoria,
        toAt = oxfordCircus,
    )

    private fun meters(a: Coordinates, b: Coordinates) = NearestStops.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    @Test
    fun `the ride is walked to from the rider and on to the place, timed as the trip's walks are`() {
        val route = DirectRoute.around(TripRoute(listOf(ride), TripFare(290)), here, place, WalkingSpeed.AVERAGE)!!
        assertEquals(3, route.legs.size)
        val (to, rode, on) = route.legs
        assertEquals(ride, rode)
        // The walk there ends as the ride leaves, as long as the trip's own walk to that stop takes.
        val walk = TripTiming.accessWalk(meters(here, victoria))
        assertEquals(TripLeg.WALKING, to.mode)
        assertEquals("940GZZLUVIC", to.toId)
        assertEquals("Victoria", to.toName)
        assertEquals(departs.minus(walk), to.departure)
        assertEquals(departs, to.arrival)
        // Named only by where it goes: the rider's own position is neither named nor kept.
        assertEquals("", to.fromId)
        assertEquals("", to.fromName)
        assertNull(to.fromAt)
        // The walk on leaves as the ride arrives, and ends at the place, by its name.
        val walkOn = TripTiming.accessWalk(meters(oxfordCircus, place.coordinate))
        assertEquals(TripLeg.WALKING, on.mode)
        assertEquals("940GZZLUOXC", on.fromId)
        assertEquals("Oxford Circus", on.fromName)
        assertEquals("", on.toId)
        assertEquals("Work", on.toName)
        assertEquals(arrives, on.departure)
        assertEquals(arrives.plus(walkOn), on.arrival)
        // Only rides are paid for: the Planner's price for the ride is the route's.
        assertEquals(TripFare(290), route.fare)
    }

    @Test
    fun `walks are timed at the rider's own pace`() {
        val slow = DirectRoute.around(TripRoute(listOf(ride)), here, place, WalkingSpeed.SLOW)!!
        assertEquals(TripTiming.accessWalk(meters(here, victoria), WalkingSpeed.SLOW), slow.legs.first().run)
        assertEquals(TripTiming.accessWalk(meters(oxfordCircus, place.coordinate), WalkingSpeed.SLOW), slow.legs.last().run)
    }

    @Test
    fun `a trip from the stop itself has no walk to it`() {
        val route = DirectRoute.around(TripRoute(listOf(ride)), null, place, WalkingSpeed.AVERAGE)!!
        assertEquals(listOf("tube", TripLeg.WALKING), route.legs.map { it.mode })
        assertEquals(departs, route.legs.first().departure)
    }

    @Test
    fun `a bus walks from and to its stop's pair, as the Planner's walks name it`() {
        val bus = ride.copy(mode = "bus", lineId = "73", lineName = "73", fromArea = "490G00000001", toArea = "490G00000002")
        val route = DirectRoute.around(TripRoute(listOf(bus)), here, place, WalkingSpeed.AVERAGE)!!
        assertEquals("490G00000001", route.legs.first().toArea)
        assertEquals("490G00000002", route.legs.last().fromArea)
    }

    @Test
    fun `the Planner's own walks around the ride are put back by the phone's`() {
        // A walk the Planner added between two poles of a stop isn't the rider's: theirs is from where they are.
        val poles = ride.copy(mode = TripLeg.WALKING, lineId = "", lineName = "", toId = "940GZZLUVIC", toName = "Victoria", path = emptyList())
        val route = DirectRoute.around(TripRoute(listOf(poles, ride)), here, place, WalkingSpeed.AVERAGE)!!
        assertEquals(listOf(TripLeg.WALKING, "tube", TripLeg.WALKING), route.legs.map { it.mode })
        assertEquals(ride, route.legs[1])
    }

    @Test
    fun `a walk either side longer than the rider's max walk is past their limit`() {
        // About 1.6 km to the stop: 42 minutes at a slow pace, past 20 and within 45.
        val far = Coordinates(51.4820, -0.1447)
        val route = DirectRoute.around(TripRoute(listOf(ride)), far, place, WalkingSpeed.SLOW)!!
        assertEquals(false, DirectRoute.walksWithin(route, MaxWalk.TWENTY))
        assertEquals(true, DirectRoute.walksWithin(route, MaxWalk.FORTY_FIVE))
        // The walk on to the place counts as much as the walk there.
        val farPlace = place.copy(coordinate = Coordinates(51.5300, -0.1418))
        val walkOn = DirectRoute.around(TripRoute(listOf(ride)), null, farPlace, WalkingSpeed.SLOW)!!
        assertEquals(false, DirectRoute.walksWithin(walkOn, MaxWalk.TWENTY))
        // A ride itself is never a walk: a long one is within any limit.
        assertEquals(true, DirectRoute.walksWithin(TripRoute(listOf(ride.copy(arrival = departs.plus(Duration.ofHours(2))))), MaxWalk.TEN))
        assertEquals(true, DirectRoute.walksWithin(DirectRoute.around(TripRoute(listOf(ride)), here, place, WalkingSpeed.AVERAGE)!!, MaxWalk.TEN))
    }

    @Test
    fun `none where a walk can't be timed, or the route rides other than once`() {
        // A stop the Planner didn't place: the walk to it, or on from it, would be a guess.
        assertNull(DirectRoute.around(TripRoute(listOf(ride.copy(fromAt = null))), here, place, WalkingSpeed.AVERAGE))
        assertNull(DirectRoute.around(TripRoute(listOf(ride.copy(toAt = null))), here, place, WalkingSpeed.AVERAGE))
        // From the stop itself there's no walk to it to time, so an unplaced boarding stop is no matter.
        assertEquals(2, DirectRoute.around(TripRoute(listOf(ride.copy(fromAt = null))), null, place, WalkingSpeed.AVERAGE)?.legs?.size)
        assertNull(DirectRoute.around(TripRoute(listOf(ride, ride)), here, place, WalkingSpeed.AVERAGE))
        assertNull(DirectRoute.around(TripRoute(emptyList()), here, place, WalkingSpeed.AVERAGE))
    }
}
