package app.stopdash.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and places only. */
class RefusedStopsTest {
    private val work = TripDestination.Place(Coordinates(51.455, -0.12), "Work")
    private val station = TripDestination.Stop("940GZZLUEXA")
    private val asked = RefusedStops.Asked(WalkingSpeed.AVERAGE, MaxWalk.DEFAULT, StepFree.ANY, TripModes.DEFAULT)

    @Test
    fun `remembers a stop refused for its destination, and only that one`() {
        val refused = RefusedStops()
        assertFalse(refused.has("490G0A", work, asked))
        refused.add("490G0A", work, asked)
        assertTrue(refused.has("490G0A", work, asked))
        // Another destination may route through it: a refusal can be the trip's, not the stop's.
        assertFalse(refused.has("490G0A", station, asked))
        assertFalse(refused.has("490G0B", work, asked))
        // A place is its position, whatever it's called.
        assertTrue(refused.has("490G0A", work.copy(name = "Office"), asked))
    }

    @Test
    fun `a refusal under one walk, step-free level or set of modes isn't one under another`() {
        val refused = RefusedStops()
        refused.add("490G0A", work, asked.copy(stepFree = StepFree.FULLY))
        assertTrue(refused.has("490G0A", work, asked.copy(stepFree = StepFree.FULLY)))
        // Any step-free level may route through it.
        assertFalse(refused.has("490G0A", work, asked))
        refused.add("490G0B", work, asked.copy(maxWalk = MaxWalk.entries.first()))
        assertFalse(refused.has("490G0B", work, asked.copy(maxWalk = MaxWalk.entries.last())))
        refused.add("490G0C", work, asked.copy(speed = WalkingSpeed.SLOW))
        assertFalse(refused.has("490G0C", work, asked.copy(speed = WalkingSpeed.FAST)))
        val noTram = asked.copy(modes = TripModes.DEFAULT.with(ModeGroups.ALL.first { it.key != "bus" }, ride = false))
        refused.add("490G0D", work, noTram)
        assertTrue(refused.has("490G0D", work, noTram))
        assertFalse(refused.has("490G0D", work, asked))
    }

    @Test
    fun `lets the oldest go past its bound`() {
        val refused = RefusedStops()
        (0..RefusedStops.MAX).forEach { refused.add("490G$it", station, asked) }
        assertFalse(refused.has("490G0", station, asked))
        assertTrue(refused.has("490G1", station, asked))
        assertTrue(refused.has("490G${RefusedStops.MAX}", station, asked))
        // One added again is the newest, so it outlasts those since.
        refused.add("490G1", station, asked)
        refused.add("490Gnext", station, asked)
        assertTrue(refused.has("490G1", station, asked))
        assertFalse(refused.has("490G2", station, asked))
    }
}
