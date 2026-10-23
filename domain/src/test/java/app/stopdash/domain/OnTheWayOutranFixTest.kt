package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rider seen at a station a while ago, the train followed since gone further than they could have: not theirs
 * (maintainer, 2026-10-23: seen at Camden Town 36 s before a refresh, the train followed was past Tufnell Park, and
 * the trip said "Next stop" for where they got off three stops early). A line from K by S1 to S5 and N, its stations
 * about 1 km apart. Synthetic stops and positions.
 */
class OnTheWayOutranFixTest {
    private val t0 = Instant.parse("2026-10-23T14:00:00Z")
    private val km = 0.009
    private val ids = listOf("K", "S1", "S2", "S3", "S4", "S5", "N")
    private val positions = ids.withIndex().associate { (i, id) -> id to Coordinates(51.50 + i * km, -0.12) }
    private val ride = TripLeg("tube", "black", "Black", "K", "K", "N", "N", t0, t0.plusSeconds(900), path = ids.drop(1))

    // Placed by a fix at [stop] [ageSeconds] ago, sure to 22 m; the train followed next due at path index [trainAt].
    private fun outran(stop: String, ageSeconds: Long, trainAt: Int): Boolean {
        val fix = LocationFix(positions.getValue(stop), isFallback = false, accuracyMeters = 22f, ageMillis = ageSeconds * 1000)
        val along = OnTheWay.placeAlong(ride, fix, positions)!!
        return OnTheWay.outranFix(ride, along, trainAt, fix, positions)
    }

    @Test
    fun `a train past a stop the rider couldn't have reached since isn't theirs`() {
        // Seen at S1 36 s ago; the train next due at S4 has passed S3, 2 km on: 36 s takes them 1.4 km at most.
        assertTrue(outran("S1", 36, trainAt = 3))
    }

    @Test
    fun `a train they could have caught up with since stays theirs`() {
        // The same train, but the fix two minutes old: they could be past S3 by now.
        assertFalse(outran("S1", 120, trainAt = 3))
        // Only past the stop ahead of them: a train pulling away from where they were seen.
        assertFalse(outran("S1", 36, trainAt = 2))
        // Still to reach them.
        assertFalse(outran("S1", 36, trainAt = 1))
    }

    @Test
    fun `a stop on the way not placed can't tell`() {
        val fix = LocationFix(positions.getValue("S1"), isFallback = false, accuracyMeters = 22f, ageMillis = 36_000)
        val along = OnTheWay.placeAlong(ride, fix, positions)!!
        assertFalse(OnTheWay.outranFix(ride, along, 3, fix, positions - "S3"))
    }

    @Test
    fun `only a sure fix places a rider on board`() {
        val at = positions.getValue("S1")
        assertTrue(OnTheWay.placesOnRide(LocationFix(at, isFallback = false, accuracyMeters = 22f, ageMillis = 36_000)))
        // A network fix, a coarse one or a fallback says nothing about which station.
        assertFalse(OnTheWay.placesOnRide(LocationFix(at, isFallback = false, accuracyMeters = 900f)))
        assertFalse(OnTheWay.placesOnRide(LocationFix(at, isFallback = false, isCoarse = true, accuracyMeters = 22f)))
        assertFalse(OnTheWay.placesOnRide(LocationFix(at, isFallback = true, accuracyMeters = 22f)))
        assertFalse(OnTheWay.placesOnRide(LocationFix(at, isFallback = false)))
    }
}
