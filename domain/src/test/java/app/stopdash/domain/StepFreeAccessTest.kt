package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading step-free access by stop, line and platform, on example stop ids. */
class StepFreeAccessTest {
    private val access = StepFreeAccess(
        mapOf(
            "940GZZEXMPA" to mapOf(
                "victoria" to listOf(
                    StepFreePlatform(StepFreeLevel.LEVEL, platform = "3", direction = "Northbound"),
                    StepFreePlatform(StepFreeLevel.RAMP, platform = "4", direction = "Southbound"),
                ),
                "central" to listOf(
                    StepFreePlatform(StepFreeLevel.NONE, platform = "1", direction = "Westbound"),
                    StepFreePlatform(StepFreeLevel.PLATFORM, platform = "3A", direction = "Westbound"),
                ),
            ),
        ),
    )

    @Test
    fun `a line's level is the one every platform of it there meets`() {
        assertEquals(StepFreeLevel.RAMP, access.level("940GZZEXMPA", "victoria"))
        assertEquals(StepFreeLevel.NONE, access.level("940GZZEXMPA", "central"))
    }

    @Test
    fun `a platform's level is its own, by TfL's number`() {
        assertEquals(StepFreeLevel.LEVEL, access.level("940GZZEXMPA", "victoria", "3"))
        assertEquals(StepFreeLevel.RAMP, access.level("940GZZEXMPA", "victoria", " 4 "))
        assertEquals(StepFreeLevel.PLATFORM, access.level("940GZZEXMPA", "central", "3a"))
    }

    @Test
    fun `a stop, line or platform TfL doesn't describe is unknown`() {
        assertNull(access.level("940GZZEXMPB", "victoria"))
        assertNull(access.level("940GZZEXMPA", "jubilee"))
        assertNull(access.level("940GZZEXMPA", "victoria", "9"))
        assertTrue(access.platforms("940GZZEXMPA", "jubilee").isEmpty())
        assertEquals(setOf("victoria", "central"), access.lines("940GZZEXMPA"))
        assertTrue(StepFreeAccess.EMPTY.isEmpty)
    }

    @Test
    fun `levels run worst to best`() {
        assertEquals(
            listOf(StepFreeLevel.NONE, StepFreeLevel.PLATFORM, StepFreeLevel.RAMP, StepFreeLevel.LEVEL),
            StepFreeLevel.entries,
        )
    }
}
