package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rider seen at a station while the train followed is already past the next one: not theirs, though only one stop
 * ahead (maintainer, 2026-10-19: followed a train a stop ahead to where they get off while seen at Camden Town). A
 * line from K by S1, S2 and S3 to N, its stations about 1 km apart, beside a bus's stops 200 m apart. Synthetic
 * stops and positions.
 */
class OnTheWayStationClearTest {
    private val t0 = Instant.parse("2026-10-19T09:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private fun line(step: Double): Pair<TripLeg, LineSequence> {
        val ids = listOf("K", "S1", "S2", "S3", "N")
        val positions = ids.withIndex().associate { (i, id) -> id to (51.50 + i * step to -0.12) }
        val ride = TripLeg("tube", "black", "Black", "K", "K", "N", "N", at(5), at(20), path = listOf("S1", "S2", "S3", "N"))
        return ride to LineSequence(routes = listOf(LineRoute("K-N", ids)), stopNames = ids.associateWith { it }, stopPositions = positions)
    }

    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))
    private fun followed(ride: TripLeg) = OnTheWay.follow(
        ActiveTrip(TripRoute(listOf(ride)), "N", startedAt = t0),
        Departure("black", "Black", "inbound", "N", null, at(5), "tube", vehicleId = "8"),
    ).copy(boarded = true, boardedAt = at(5), onBoardSeen = true)

    // Whether the train followed, with [calls], is clear of a rider placed by a fix at [stop] (or [between] it and the next).
    private fun clear(step: Double, stop: String, calls: List<VehicleCall>, between: Boolean = false, now: Instant = at(10), dueOff: Instant? = null): Boolean {
        val (ride, sequence) = line(step)
        val positions = OnTheWay.ridePositions(ride, sequence)
        val here = positions.getValue(stop).let { if (between) Coordinates(it.latitude + step / 2, it.longitude) else it }
        val fix = LocationFix(here, isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L)
        val along = OnTheWay.placeAlong(ride, fix, positions) ?: return false
        return OnTheWay.followedAhead(followed(ride).copy(dueOffAt = dueOff), ride, along, calls, now, sequence, positions = positions)
    }

    private val km = 0.009
    private val busStops = 200.0 / 111_195.0

    @Test
    fun `seen at a station with the train past the next, it isn't theirs`() {
        assertTrue(clear(km, "S2", listOf(call("N", 14))))
    }

    @Test
    fun `seen at the station before theirs with the train gone past it, it isn't theirs`() {
        // No calls left once it was due where they get off: past every stop of the ride (Codex, #997).
        assertTrue(clear(km, "S3", emptyList(), now = at(15), dueOff = at(14)))
        // Before then, no calls may be TfL not predicting it for a while.
        assertFalse(clear(km, "S3", emptyList(), dueOff = at(14)))
        // Calls all off the ride's path (a diversion) aren't past it, due off or not: nothing says it called there.
        assertFalse(clear(km, "S3", listOf(call("X", 16)), now = at(15), dueOff = at(14)))
        assertFalse(clear(km, "S3", listOf(call("X", 16))))
    }

    @Test
    fun `a train that may still be theirs is kept`() {
        // Due next at the station after theirs: at theirs or pulling away from it.
        assertFalse(clear(km, "S2", listOf(call("S3", 12), call("N", 14))))
        // Seen between stations: only two stops out lets it go, as before.
        assertFalse(clear(km, "S2", listOf(call("N", 14)), between = true))
        // Stops close enough that a fix at one could be one at the next, or a train still pulling in.
        assertFalse(clear(busStops, "S2", listOf(call("N", 14))))
        // Without its stops' positions, as before this rule.
        val (ride, sequence) = line(km)
        val positions = OnTheWay.ridePositions(ride, sequence)
        val fix = LocationFix(positions.getValue("S2"), isFallback = false, accuracyMeters = 20f, ageMillis = 1_000L)
        assertFalse(OnTheWay.followedAhead(followed(ride), ride, OnTheWay.placeAlong(ride, fix, positions)!!, listOf(call("N", 14)), at(10), sequence))
    }
}
