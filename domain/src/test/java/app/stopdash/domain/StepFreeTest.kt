package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StepFreeTest {
    @Test
    fun `reads back what was stored, and no requirement otherwise`() {
        StepFree.entries.forEach { assertEquals(it, StepFree.fromStored(it.name)) }
        assertEquals(StepFree.ANY, StepFree.fromStored(null))
        assertEquals(StepFree.ANY, StepFree.fromStored("LEVEL"))
    }

    @Test
    fun `sends the Planner's own names, and none for any`() {
        assertEquals(listOf(null, "StepFreeToPlatform", "StepFreeToVehicle"), StepFree.entries.map { it.plannerValue })
    }
}
