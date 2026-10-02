package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Example ids and synthetic positions only. */
class ReplanOriginTest {
    private val t0 = Instant.parse("2026-10-02T08:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

    // A walk to A, a ride A → B → C, a walk to D, a ride D → E → F.
    private val walkIn = TripLeg(TripLeg.WALKING, "", "", "", "", "A", "A", at(0), at(3))
    private val red = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val change = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(18))
    private val blue = TripLeg("tube", "blue", "Blue", "D", "D", "F", "F", at(20), at(30), path = listOf("E", "F"))
    private val route = TripRoute(listOf(walkIn, red, change, blue))
    private fun trip(legIndex: Int) = ActiveTrip(route, "F", startedAt = t0, legIndex = legIndex)

    // On a line due north, a kilometer between stops.
    private val positions = listOf("A", "B", "C", "D", "E", "F").withIndex()
        .associate { (i, id) -> id to Coordinates(51.5 + i * 0.009, -0.12) }
    private fun near(id: String) = LocationFix(
        positions.getValue(id).let { Coordinates(it.latitude + 0.001, it.longitude) },
        isFallback = false,
        accuracyMeters = 20f,
        ageMillis = 2_000,
    )

    @Test
    fun `every stop of a ride still to board is ahead, walks adding none`() {
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), ReplanOrigin.stopsAhead(trip(0), rideAhead = null))
        assertEquals(listOf("D", "E", "F"), ReplanOrigin.stopsAhead(trip(2), rideAhead = null))
    }

    @Test
    fun `on the train, only the stops it hasn't passed are ahead`() {
        assertEquals(listOf("C", "D", "E", "F"), ReplanOrigin.stopsAhead(trip(1), rideAhead = 1))
        assertEquals(listOf("B", "C", "D", "E", "F"), ReplanOrigin.stopsAhead(trip(1), rideAhead = 0))
    }

    @Test
    fun `a ride followed on another of its lines counts that train's own stops`() {
        // Its train is on the Pink, which goes A → X → C: B is no stop of it.
        val pink = red.copy(lineId = "pink", lineName = "Pink", path = listOf("X", "C"))
        val onPink = trip(1).copy(vehicleId = "7", vehicleLeg = pink)
        assertEquals(listOf("A", "X", "C", "D", "E", "F"), ReplanOrigin.stopsAhead(onPink, rideAhead = null))
        assertEquals(listOf("C", "D", "E", "F"), ReplanOrigin.stopsAhead(onPink, rideAhead = 1))
        // Near B, which the Pink skips: the nearer of its stops either side.
        assertEquals("C", ReplanOrigin.of(onPink, near("B"), positions, rideAhead = null))
    }

    @Test
    fun `on board with how far along unknown, only where they get off counts from the ride`() {
        val aboard = trip(1).copy(vehicleId = "7", boarded = true)
        assertEquals(listOf("C", "D", "E", "F"), ReplanOrigin.stopsAhead(aboard, rideAhead = null))
        assertEquals("C", ReplanOrigin.of(aboard, null, positions, rideAhead = null))
        assertEquals("C", ReplanOrigin.of(aboard, near("A"), positions, rideAhead = null))
        assertEquals(listOf("C", "D", "E", "F"), ReplanOrigin.stopsAhead(trip(1).copy(onBoardSeen = true), rideAhead = null))
    }

    @Test
    fun `the station ahead nearest the rider is planned from`() {
        assertEquals("E", ReplanOrigin.of(trip(0), near("E"), positions, rideAhead = null))
        assertEquals("B", ReplanOrigin.of(trip(1), near("B"), positions, rideAhead = null))
    }

    @Test
    fun `a stop the train has passed isn't planned from, however near`() {
        // Just past B on the train: B is nearest, but C is the next it calls at.
        assertEquals("C", ReplanOrigin.of(trip(1), near("B"), positions, rideAhead = 1))
    }

    @Test
    fun `with no usable fix, the next stop ahead`() {
        assertEquals("A", ReplanOrigin.of(trip(1), null, positions, rideAhead = null))
        assertEquals("C", ReplanOrigin.of(trip(1), null, positions, rideAhead = 1))
        assertEquals("A", ReplanOrigin.of(trip(1), near("E").copy(isFallback = true), positions, rideAhead = null))
        assertEquals("A", ReplanOrigin.of(trip(1), near("E").copy(isCoarse = true), positions, rideAhead = null))
        // Too old, too vague, or saying neither: it can't place a rider on a moving train.
        assertEquals("A", ReplanOrigin.of(trip(1), near("E").copy(ageMillis = 30_000), positions, rideAhead = null))
        assertEquals("A", ReplanOrigin.of(trip(1), near("E").copy(accuracyMeters = 1_500f), positions, rideAhead = null))
        assertEquals("A", ReplanOrigin.of(trip(1), near("E").copy(accuracyMeters = null), positions, rideAhead = null))
        assertEquals("A", ReplanOrigin.of(trip(1), near("E").copy(ageMillis = null), positions, rideAhead = null))
        // Nothing ahead placed: the next stop too.
        assertEquals("A", ReplanOrigin.of(trip(1), near("E"), emptyMap(), rideAhead = null))
    }

    @Test
    fun `a stop not placed is passed over for one that is`() {
        assertEquals("F", ReplanOrigin.of(trip(1), near("E"), positions - "E", rideAhead = null))
    }

    @Test
    fun `with no ride left, nothing is planned`() {
        val walkOut = TripLeg(TripLeg.WALKING, "", "", "F", "F", "", "Home", at(30), at(35))
        val trip = ActiveTrip(TripRoute(listOf(blue, walkOut)), "Home", startedAt = t0, legIndex = 1)
        assertNull(ReplanOrigin.of(trip, near("F"), positions, rideAhead = null))
        assertNull(ReplanOrigin.of(trip.copy(legIndex = 2), null, positions, rideAhead = null))
    }

    @Test
    fun `how far along the ride is counted from the stops left`() {
        val riding = trip(1).copy(boarded = true)
        // Two stops left, B and C: B is next.
        assertEquals(0, ReplanOrigin.rideAhead(riding, TripProgress.Riding(red, "B", 2, null, false)))
        assertEquals(1, ReplanOrigin.rideAhead(riding, TripProgress.Riding(red, "C", 1, null, true)))
        assertNull(ReplanOrigin.rideAhead(riding, TripProgress.Riding(red, null, null, null, false)))
        assertNull(ReplanOrigin.rideAhead(riding, TripProgress.Waiting(red, null)))
    }

    @Test
    fun `a stop is named as the route names it`() {
        val named = blue.copy(pathNames = listOf("Elm Park", "Fir Grove"), fromName = "Dock")
        val trip = ActiveTrip(TripRoute(listOf(red, named)), "F", startedAt = t0)
        assertEquals("Dock", ReplanOrigin.nameOf(trip, "D"))
        assertEquals("Elm Park", ReplanOrigin.nameOf(trip, "E"))
        assertEquals("C", ReplanOrigin.nameOf(trip, "C"))
        assertEquals("Z", ReplanOrigin.nameOf(trip, "Z"))
        assertEquals(ReplanOrigin.Stop("A", "A"), ReplanOrigin.stopOf(trip, null, positions, rideAhead = null))
        assertEquals(ReplanOrigin.Stop("E", "Elm Park"), ReplanOrigin.stopOf(trip.copy(legIndex = 1), near("E"), positions, rideAhead = null))
    }
}
