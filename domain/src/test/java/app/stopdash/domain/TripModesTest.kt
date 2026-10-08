package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripModesTest {
    private fun group(key: String) = ModeGroups.ALL.single { it.key == key }

    private fun TripModes.planned() = plannerModes.split(",").toSet()

    @Test
    fun `rides every group by default, over the Planner's own modes`() {
        val modes = TripModes.DEFAULT
        assertTrue(ModeGroups.ALL.all(modes::rides))
        assertEquals(TripModes.PLANNER_MODES.toSet(), modes.planned())
        assertEquals("", modes.key)
    }

    @Test
    fun `a group turned off drops its modes and no other`() {
        val noBus = TripModes.DEFAULT.with(group("bus"), ride = false)
        assertFalse(noBus.rides(group("bus")))
        // A coach rides with the bus.
        assertEquals(TripModes.PLANNER_MODES.toSet() - "bus" - "coach", noBus.planned())
        // The trains are three modes to TfL, and the Tube group holds the DLR too.
        val noTrainOrTube = noBus.with(group("overground"), ride = false).with(group("rail"), ride = false).with(group("tube"), ride = false)
        val planned = noTrainOrTube.planned()
        assertTrue(planned.none { it in setOf("bus", "overground", "elizabeth-line", "national-rail", "tube", "dlr") })
        assertTrue(planned.containsAll(listOf("tram", "river-bus")))
        // Turned on again, it rides again.
        assertEquals(TripModes.PLANNER_MODES.toSet() - "bus" - "coach", noBus.with(group("bus"), ride = true).with(group("bus"), ride = false).planned())
        assertEquals(TripModes.DEFAULT, noBus.with(group("bus"), ride = true))
    }

    @Test
    fun `walking, the cable car and replacement buses are never dropped`() {
        val onlyTram = ModeGroups.ALL.filter { it.key != "tram" }.fold(TripModes.DEFAULT) { modes, g -> modes.with(g, ride = false) }
        assertEquals(setOf("tram", "walking", "cable-car", "replacement-bus"), onlyTram.planned())
    }

    @Test
    fun `the last group riding stays on`() {
        val onlyBus = ModeGroups.ALL.filter { it.key != "bus" }.fold(TripModes.DEFAULT) { modes, g -> modes.with(g, ride = false) }
        assertTrue(onlyBus.isLast(group("bus")))
        assertFalse(onlyBus.isLast(group("tram")))
        assertEquals(onlyBus, onlyBus.with(group("bus"), ride = false))
        assertTrue(onlyBus.rides(group("bus")))
        assertFalse(TripModes.DEFAULT.isLast(group("bus")))
    }

    @Test
    fun `each choice has its own key`() {
        val noBus = TripModes.DEFAULT.with(group("bus"), ride = false)
        val noTram = TripModes.DEFAULT.with(group("tram"), ride = false)
        assertNotEquals(noBus.key, noTram.key)
        assertNotEquals(TripModes.DEFAULT.key, noBus.key)
        // Order doesn't matter: the same groups off are the same choice.
        assertEquals(noBus.with(group("tram"), ride = false).key, noTram.with(group("bus"), ride = false).key)
    }

    @Test
    fun `reads back what was stored, dropping a group this build doesn't have`() {
        assertEquals(TripModes(setOf("bus")), TripModes.fromStored(setOf("bus")))
        assertEquals(TripModes(setOf("bus")), TripModes.fromStored(setOf("bus", "hovercraft")))
        // Train was one group until it split: off, both halves are.
        assertEquals(TripModes(setOf("overground", "rail", "bus")), TripModes.fromStored(setOf("train", "bus")))
        assertEquals(TripModes.DEFAULT, TripModes.fromStored(null))
        assertEquals(TripModes.DEFAULT, TripModes.fromStored(emptySet()))
    }

    @Test
    fun `a stored set turning every group off reads as riding them all`() {
        assertEquals(TripModes.DEFAULT, TripModes.fromStored(ModeGroups.ALL.map { it.key }.toSet()))
    }
}
