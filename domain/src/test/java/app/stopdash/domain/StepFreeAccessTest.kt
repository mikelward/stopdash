package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `a National Rail train takes the station's National Rail platforms, any other line only its own`() {
        val rail = StepFreeAccess(
            mapOf(
                "910GEXAMPLE" to mapOf(
                    StepFreeAccess.NATIONAL_RAIL to listOf(StepFreePlatform(StepFreeLevel.RAMP, platform = "1")),
                    "elizabeth" to listOf(StepFreePlatform(StepFreeLevel.LEVEL, platform = "A")),
                ),
            ),
        )
        // An operator's line id TfL's station data doesn't use, on a National Rail train.
        assertEquals(StepFreeLevel.RAMP, rail.levelFor("910GEXAMPLE", "thameslink", "national-rail"))
        // A line TfL describes keeps its own level.
        assertEquals(StepFreeLevel.LEVEL, rail.levelFor("910GEXAMPLE", "elizabeth", "elizabeth-line"))
        // Another mode's unknown line doesn't borrow National Rail's.
        assertNull(rail.levelFor("910GEXAMPLE", "weaver", "overground"))
        assertNull(rail.levelFor("940GZZEXMPB", "thameslink", "national-rail"))
    }

    // An example station's lift map: the street (0) walks to a ticket hall (1); lifts A and B each
    // take the hall to the eastbound platform (2), and lift C alone to the westbound one (3).
    private val lifted = StepFreeAccess(
        mapOf(
            "940GZZEXMPC" to mapOf(
                "district" to listOf(
                    StepFreePlatform(StepFreeLevel.LEVEL, "1", "Eastbound", byLift = LiftStop("HUBEXM", 2)),
                    StepFreePlatform(StepFreeLevel.RAMP, "2", "Westbound", byLift = LiftStop("HUBEXM", 3)),
                    StepFreePlatform(StepFreeLevel.LEVEL, "3", "Westbound"),
                ),
            ),
            "940GZZEXMPD" to mapOf("district" to listOf(StepFreePlatform(StepFreeLevel.LEVEL, "1"))),
        ),
        mapOf(
            "HUBEXM" to LiftMap(
                walks = mapOf(0 to setOf(1), 1 to setOf(0)),
                lifts = mapOf("HUBEXM-Lift-A" to setOf(1, 2), "HUBEXM-Lift-B" to setOf(1, 2), "HUBEXM-Lift-C" to setOf(1, 3)),
            ),
        ),
    )

    @Test
    fun `a platform only lifts reach loses its level while every lift that gets there is out`() {
        val cOut = lifted.withLiftsOut(setOf("HUBEXM-Lift-C"))
        assertEquals(StepFreeLevel.NONE, cOut.level("940GZZEXMPC", "district", "2"))
        assertEquals(StepFreeLevel.LEVEL, cOut.level("940GZZEXMPC", "district", "1"))
        // Another lift still reaches the eastbound platform; both out, it's cut off too.
        assertEquals(StepFreeLevel.LEVEL, lifted.withLiftsOut(setOf("HUBEXM-Lift-A")).level("940GZZEXMPC", "district", "1"))
        assertEquals(
            StepFreeLevel.NONE,
            lifted.withLiftsOut(setOf("HUBEXM-Lift-A", "HUBEXM-Lift-B")).level("940GZZEXMPC", "district", "1"),
        )
        // A platform reached without a lift keeps its level whatever's out.
        assertEquals(StepFreeLevel.LEVEL, cOut.level("940GZZEXMPC", "district", "3"))
    }

    @Test
    fun `lifts the table doesn't know, or none, change nothing`() {
        assertSame(lifted, lifted.withLiftsOut(emptySet()))
        assertSame(lifted, lifted.withLiftsOut(setOf("HUBOTHER-Lift-1")))
    }

    @Test
    fun `a stop is by lift when any of its platforms is reached only by one`() {
        assertTrue(lifted.byLift("940GZZEXMPC"))
        assertFalse(lifted.byLift("940GZZEXMPD"))
        assertFalse(lifted.byLift("940GZZEXMPE"))
    }

    @Test
    fun `a lift map walks one way where TfL's paths go one way`() {
        val map = LiftMap(walks = mapOf(0 to setOf(1), 2 to setOf(0)), lifts = mapOf("L" to setOf(1, 3)))
        assertEquals(setOf(0, 1, 3), map.reached(emptySet()))
        assertEquals(setOf(0, 1), map.reached(setOf("L")))
    }

    @Test
    fun `levels run worst to best`() {
        assertEquals(
            listOf(StepFreeLevel.NONE, StepFreeLevel.PLATFORM, StepFreeLevel.RAMP, StepFreeLevel.LEVEL),
            StepFreeLevel.entries,
        )
    }
}
