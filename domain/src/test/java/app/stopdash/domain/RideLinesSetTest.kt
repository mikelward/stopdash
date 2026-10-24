package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A favorite ride is its two stops, never a line (maintainer, 2026-10-24: "Rides are between stops. Period."):
 * it caches the lines found serving both, all alike, any of which may be hidden and any of whose routes may
 * place its stops. Synthetic stops and routes.
 */
class RideLinesSetTest {
    private val park = JourneyEnd("490000001S", "Park")
    private val hill = JourneyEnd("490000002S", "Hill")
    private val rOne = LineRef("b1", "b1", "bus")
    private val rTwo = LineRef("b2", "b2", "bus")

    @Test
    fun `lines found serving a ride join it after those it has, and nothing new leaves it as it is`() {
        val ride = FavoriteJourney(park, hill, listOf(rTwo))
        val widened = Journeys.withLines(ride, listOf(rTwo, rOne))
        assertEquals(listOf(rTwo, rOne), widened.lines)
        assertSame(widened, Journeys.withLines(widened, listOf(rOne)))
        // A line known by id alone gets its name and mode once found.
        val bare = FavoriteJourney(park, hill, listOf(LineRef("b1", "", "")))
        assertEquals(listOf(rOne), Journeys.withLines(bare, listOf(rOne)).lines)
    }

    @Test
    fun `any of a ride's lines can be hidden, the first included`() {
        val ride = FavoriteJourney(park, hill, listOf(rTwo, rOne))
        val hidden = Journeys.withLineHidden(listOf(ride), ride, HiddenModes.lineKey("b2", "b2"), hidden = true).single()
        assertTrue(hidden.hides("b2"))
        assertFalse(hidden.hides("b1"))
    }

    @Test
    fun `a ride is a bus ride when its lines are buses, or before any are known when both its ends are bus stops`() {
        assertTrue(FavoriteJourney(park, hill, listOf(rOne, rTwo)).bus)
        assertEquals("bus", FavoriteJourney(park, hill, listOf(rOne)).mode)
        assertFalse(FavoriteJourney(park, hill, listOf(rOne, LineRef("northern", "Northern", "tube"))).bus)
        assertTrue(FavoriteJourney(park, hill).bus)
        assertFalse(FavoriteJourney(JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"), hill).bus)
    }

    @Test
    fun `a bus ride's way-back pole is placed by distance on any of its lines' routes, never on another line's`() {
        // The way back boards across the road ("490000002N"), near the saved pole; only the first bus's route is here.
        val route = LineSequence(
            routes = listOf(LineRoute("North", listOf("490000002N", "490000009N", "490000001N"))),
            stopNames = mapOf("490000002N" to "Hill North", "490000009N" to "Mid", "490000001N" to "Park North"),
            stopPositions = mapOf("490000002N" to (51.5 to -0.12), "490000001N" to (51.51 to -0.12)),
        )
        val ride = FavoriteJourney(
            JourneyEnd("490000002S", "Hill", 51.5001, -0.12),
            JourneyEnd("490000001S", "Park", 51.5101, -0.12),
            listOf(rTwo, rOne),
        )
        assertNotNull(Journeys.segment(ride, route, "b1"))
        assertEquals(null, Journeys.segment(ride, route, "263"))
        // A rail line learned at an end's hub leaves its buses placed as before.
        val learned = ride.copy(lines = ride.lines + LineRef("northern", "Northern", "tube"))
        assertFalse(learned.bus)
        assertNotNull(Journeys.segment(learned, route, "b1"))
    }

    @Test
    fun `a pair added between two stops is saved with every line running between them`() {
        val from = StopLocation("490000001S", "Park", 51.5, -0.12, listOf(rTwo, rOne))
        val to = StopLocation("490000002S", "Hill", 51.51, -0.12, listOf(rOne, rTwo, LineRef("263", "263", "bus")))
        val found = JourneyPair.resolve("Park", listOf(from), "Hill", listOf(to)) as JourneyPair.Result.Found
        assertEquals(setOf("b2", "b1"), found.journey.lineIds.toSet())
    }
}
