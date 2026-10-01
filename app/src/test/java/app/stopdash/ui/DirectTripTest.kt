package app.stopdash.ui

import app.stopdash.domain.Coordinates
import app.stopdash.domain.LineRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Where a To… from the near-me list starts (SPEC *Finding stops → From… To…*), on synthetic stops. */
class DirectTripTest {
    private fun stop(id: String, mode: String) = StopRef(id, id, lines = listOf(LineRef("l-$id", "L", mode)))

    @Test
    fun `a trip from here starts at the list's stops and any within 0_2 mi`() {
        val tube = stop("TUBE", "tube")
        val pole = stop("POLE", "bus")
        val farBus = stop("FARBUS", "bus")
        val distances = mapOf("TUBE" to 1200.0, "POLE" to 200.0, "FARBUS" to 900.0)
        assertEquals(
            listOf("POLE", "TUBE"),
            hereOriginIds(eager = listOf(tube), nearby = listOf(tube, pole, farBus), distanceMeters = distances, hidden = emptySet()),
        )
    }

    @Test
    fun `a trip from here leaves out hidden modes, and starts nowhere when all are hidden`() {
        val tube = stop("TUBE", "tube")
        val pole = stop("POLE", "bus")
        val distances = mapOf("TUBE" to 1200.0, "POLE" to 200.0)
        assertEquals(listOf("TUBE"), hereOriginIds(listOf(tube, pole), listOf(tube, pole), distances, setOf("bus")))
        assertEquals(emptyList<String>(), hereOriginIds(listOf(tube, pole), listOf(tube, pole), distances, setOf("bus", "tube")))
    }

    @Test
    fun `a stop with no routes is never an origin`() {
        val tube = stop("TUBE", "tube")
        val bare = StopRef("BARE", "BARE")
        val distances = mapOf("TUBE" to 1200.0, "BARE" to 100.0)
        assertEquals(listOf("TUBE"), hereOriginIds(listOf(tube), listOf(tube, bare), distances, emptySet()))
        assertEquals(emptyList<String>(), hereOriginIds(listOf(tube), listOf(tube, bare), distances, setOf("tube")))
    }

    @Test
    fun `a trip from here is keyed by the stop nearest the rider, an own stop from a station`() {
        val near = stop("near", "bus")
        val far = stop("far", "tube")
        val distances = mapOf("near" to 40.0, "far" to 300.0)
        val rider = Coordinates(51.5, -0.12)
        assertEquals("near", tripStartId(listOf(far, near), emptyList(), emptySet(), distances, noneNearby = false, hereAnchor = rider))
        // A From… station starts at one of its own stops, however near a neighbor is.
        assertEquals("far", tripStartId(listOf(far, near), emptyList(), setOf("far"), distances, noneNearby = false, hereAnchor = null))
    }

    @Test
    fun `with no stop in range a trip from here still starts, keyed by where it plans from`() {
        val rider = Coordinates(51.5, -0.12)
        assertEquals(hereStartId(rider), tripStartId(emptyList(), emptyList(), emptySet(), emptyMap(), noneNearby = true, hereAnchor = rider))
        // Every stop in range hidden ends the trip, as before; so does no position to plan from.
        assertEquals(null, tripStartId(emptyList(), emptyList(), emptySet(), emptyMap(), noneNearby = false, hereAnchor = rider))
        assertEquals(null, tripStartId(emptyList(), emptyList(), emptySet(), emptyMap(), noneNearby = true, hereAnchor = null))
    }

    @Test
    fun `with no stop in range a move far enough to plan again re-keys the trip, a smaller one doesn't`() {
        val rider = Coordinates(51.5, -0.12)
        // About 130 m north: a refined fix, the same trip (refreshed by its re-pick).
        val refined = Coordinates(51.50117, -0.12)
        // About 170 m north of where it was keyed: planned afresh, its old routes not shown.
        val moved = Coordinates(51.50153, -0.12)
        assertEquals(rider, hereAnchor(null, rider))
        assertEquals(rider, hereAnchor(rider, refined))
        assertEquals(moved, hereAnchor(rider, moved))
        // Measured from where it was keyed, not the last fix: small steps that add up re-key it too.
        assertEquals(moved, hereAnchor(hereAnchor(rider, refined), moved))
        // No position for now keeps the key it had.
        assertEquals(rider, hereAnchor(rider, null))
        assertEquals(null, hereAnchor(null, null))
    }

    @Test
    fun `with no stop in range each new position of the rider stands in for a re-pick`() {
        val rider = Coordinates(51.5, -0.12)
        val refined = Coordinates(51.5012, -0.12)
        val first = tripRepickId(noneNearby = true, here = rider, repickId = null)
        // A refined fix refreshes the trip; the same position again doesn't.
        assertNotEquals(first, tripRepickId(noneNearby = true, here = refined, repickId = 7))
        assertEquals(first, tripRepickId(noneNearby = true, here = Coordinates(51.5, -0.12), repickId = 8))
        // A trip from the list follows the nearby set's own re-picks.
        assertEquals(7L, tripRepickId(noneNearby = false, here = rider, repickId = 7))
    }
}
