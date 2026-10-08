package app.stopdash.ui

import app.stopdash.domain.RouteStop
import app.stopdash.domain.StepFreeLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The route page's map's stations by the id each is drawn as, mapped back to the stops the train calls at. */
class RouteMapInputsTest {
    // A made-up bus route's way back: its poles B2 and C2 drawn on the map as the outbound B1 and C1.
    private val inputs = RouteMapInputs.of(
        RouteStopsUi.Loaded(listOf(RouteStop("A2", "Alpha"), RouteStop("B2", "Beta"), RouteStop("C2", "Gamma"))),
        mapOf("C2" to StepFreeLevel.entries.first()),
    )

    @Test
    fun `a pole drawn as another is found by the id it's drawn as, and opens the one the train calls at`() {
        val drawn = inputs.drawn(mapOf("B2" to "B1", "C2" to "C1"))
        assertEquals(mapOf("B1" to "B2", "C1" to "C2"), drawn.after)
        assertEquals("B2", drawn.path["B1"])
        assertEquals("A2", drawn.path["A2"])
        assertEquals(setOf("C1"), drawn.stepFree.keys)
        assertEquals(setOf("C1"), inputs.stepFreeDrawn(mapOf("C2" to StepFreeLevel.entries.first(), "Z9" to StepFreeLevel.entries.first()), mapOf("C2" to "C1")).keys)
        // Where the train ends, bolded on the map, by the id it's drawn as.
        assertEquals("C1", drawn.terminus)
    }

    @Test
    fun `a map is drawn on only where it shows the train's whole path open`() {
        // A made-up line A1..D1: a route page's train from A1 to C1 is on it; one on stops it never draws isn't.
        val sequence = app.stopdash.domain.LineSequence(
            routes = listOf(app.stopdash.domain.LineRoute("A - D", listOf("A1", "B1", "C1", "D1"), "outbound")),
            stopNames = mapOf("A1" to "Alpha", "B1" to "Beta", "C1" to "Gamma", "D1" to "Delta"),
        )
        fun on(path: List<String>): Boolean {
            val inputs = RouteMapInputs.of(RouteStopsUi.Loaded(path.map { RouteStop(it, it) }), emptyMap())
            val map = app.stopdash.domain.LineMap.of(sequence, riding = inputs.riding, rides = inputs.rides, ridesOpen = true)!!
            return inputs.drawnOn(map)
        }
        assertEquals(true, on(listOf("A1", "B1", "C1")))
        assertEquals(false, on(listOf("X2", "Y2", "Z2")))
    }

    @Test
    fun `with nothing drawn as another, every stop is its own`() {
        assertEquals(inputs.asDrawn, inputs.drawn(emptyMap()))
        assertEquals("B2", inputs.asDrawn.path["B2"])
        assertEquals("C2", inputs.asDrawn.terminus)
    }
}
