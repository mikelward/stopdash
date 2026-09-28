package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkingSpeedTest {
    @Test
    fun `reads back what was stored, and the Planner's default otherwise`() {
        WalkingSpeed.entries.forEach { assertEquals(it, WalkingSpeed.fromStored(it.name)) }
        assertEquals(WalkingSpeed.AVERAGE, WalkingSpeed.fromStored(null))
        assertEquals(WalkingSpeed.AVERAGE, WalkingSpeed.fromStored("BRISK"))
    }

    @Test
    fun `sends the Planner's own names`() {
        assertEquals(listOf("Slow", "Average", "Fast"), WalkingSpeed.entries.map { it.plannerValue })
    }
}
