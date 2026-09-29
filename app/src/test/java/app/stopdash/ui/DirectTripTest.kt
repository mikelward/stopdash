package app.stopdash.ui

import app.stopdash.domain.LineRef
import org.junit.Assert.assertEquals
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
}
