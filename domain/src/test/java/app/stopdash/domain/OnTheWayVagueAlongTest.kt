package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A vague fix (a network one, as underground) sees a rider still to board gone from where the ride boards and on along
 * it (maintainer, 2026-10-26: no sure fix came between two stations, so the trip kept them where they boarded). A line
 * from K by S1 to S5 and N, its stations about 1 km apart. Synthetic stops and positions.
 */
class OnTheWayVagueAlongTest {
    private val t0 = Instant.parse("2026-10-26T09:50:00Z")
    private val km = 0.009
    private val ids = listOf("K", "S1", "S2", "S3", "S4", "S5", "N")
    private val positions = ids.withIndex().associate { (i, id) -> id to Coordinates(51.50 + i * km, -0.12) }
    private val ride = TripLeg("tube", "black", "Black", "K", "K", "N", "N", t0, t0.plusSeconds(900), path = ids.drop(1))
    private val waiting = ActiveTrip(TripRoute(listOf(ride)), "N", startedAt = t0)

    private fun fix(at: String, accuracy: Float = 300f, ageSeconds: Long = 0) =
        LocationFix(positions.getValue(at), isFallback = false, accuracyMeters = accuracy, ageMillis = ageSeconds * 1000)

    @Test
    fun `a vague fix clear of the track from where it boards places them short of the nearest stop in reach`() {
        // Sure to 300 m by S3: twice that and the slack reach the track from S2 to S3, not S1 to S2. Short of S3 at least.
        assertEquals(OnTheWay.Along(2, atStop = false), OnTheWay.vagueAlong(ride, fix("S3"), positions))
    }

    @Test
    fun `a vague fix reaching the track from where it boards leaves them there`() {
        assertNull(OnTheWay.vagueAlong(ride, fix("S1"), positions))
        // Sure to 900 m by S2: it reaches back to K's track.
        assertNull(OnTheWay.vagueAlong(ride, fix("S2", accuracy = 900f), positions))
        // Nowhere near the ride: not on it.
        val away = LocationFix(Coordinates(51.50, -0.20), isFallback = false, accuracyMeters = 300f, ageMillis = 0)
        assertNull(OnTheWay.vagueAlong(ride, away, positions))
        // A stop not placed: a stretch in reach could go unseen.
        assertNull(OnTheWay.vagueAlong(ride, fix("S3"), positions - "S4"))
    }

    @Test
    fun `by where they get off, a vague fix places them short of it, never off`() {
        assertEquals(OnTheWay.Along(5, atStop = false), OnTheWay.vagueAlong(ride, fix("N"), positions))
    }

    @Test
    fun `a vague fix reaches the refresh only while a train is awaited, fresh`() {
        val now = t0.plusSeconds(60)
        val vague = fix("S3", ageSeconds = 2)
        assertSame(vague, OnTheWay.leavingFix(vague, waiting, now))
        assertSame(vague, OnTheWay.fixOfUse(vague, waiting, now))
        assertEquals(true, OnTheWay.takesApproximate(waiting, now))
        // A sure one is the trip's as ever, not this.
        assertNull(OnTheWay.leavingFix(fix("S3", accuracy = 20f), waiting, now))
        // Too old, coarse, or of no stated accuracy, it says nothing of where they are.
        assertNull(OnTheWay.leavingFix(fix("S3", ageSeconds = 11), waiting, now))
        assertNull(OnTheWay.leavingFix(vague.copy(isCoarse = true), waiting, now))
        assertNull(OnTheWay.leavingFix(vague.copy(accuracyMeters = null), waiting, now))
        // Seen on board already, or past the wait's ten minutes: not for this.
        assertNull(OnTheWay.leavingFix(vague, waiting.copy(onBoardSeen = true), now))
        assertNull(OnTheWay.leavingFix(vague, waiting, t0.plus(OnTheWay.WAITING_WINDOW)))
        assertEquals(false, OnTheWay.takesApproximate(waiting, t0.plus(OnTheWay.WAITING_WINDOW)))
    }
}
