package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A station's step-free access for its details (SPEC *Finding a line*): for the line it was opened
 * from, else the level all its lines meet, and said as a lift outage where one is what takes it away.
 * A made-up station's table, after the shape of TfL's.
 */
class StopAccessTest {
    // Two platforms of the Somewhere line, the eastbound reached only by a lift; one of the Elsewhere
    // line, reached on foot.
    private val table = StepFreeAccess(
        mapOf(
            "940GX" to mapOf(
                "somewhere" to listOf(
                    StepFreePlatform(StepFreeLevel.LEVEL, "1", "Eastbound", byLift = LiftStop("HUBX", 2)),
                    StepFreePlatform(StepFreeLevel.LEVEL, "2", "Westbound"),
                ),
                "elsewhere" to listOf(StepFreePlatform(StepFreeLevel.PLATFORM, "3")),
            ),
        ),
        mapOf("HUBX" to LiftMap(walks = mapOf(0 to setOf(1), 1 to setOf(0)), lifts = mapOf("HUBX-Lift-A" to setOf(1, 2)))),
    )

    private val bothLines = listOf(LineRef("somewhere", "Somewhere", "tube"), LineRef("elsewhere", "Elsewhere", "tube"))

    @Test
    fun `a station's access is the opened line's, else what all its lines meet`() {
        assertEquals(StopAccess(StepFreeLevel.LEVEL, liftOut = false, byLift = true), stopAccess(table, emptySet(), listOf("940GX"), emptyList(), "somewhere", "tube"))
        // Opened from another's details, off the line: the level every line through it meets.
        assertEquals(StepFreeLevel.PLATFORM, stopAccess(table, emptySet(), listOf("940GX"), bothLines, null, "tube").level)
        // A line through it the table doesn't describe leaves it unknown, never a guess (Codex on #678).
        val withThird = bothLines + LineRef("third", "Third", "tube")
        assertEquals(StopAccess(null, liftOut = false, byLift = false), stopAccess(table, emptySet(), listOf("940GX"), withThird, null, "tube"))
        // A station TfL's table doesn't describe has none, never a guess.
        assertEquals(StopAccess(null, liftOut = false, byLift = false), stopAccess(table, emptySet(), listOf("940GY"), emptyList(), "somewhere", "tube"))
    }

    @Test
    fun `a lift out that takes the step-free route away is said to be why`() {
        val out = stopAccess(table, setOf("HUBX-Lift-A"), listOf("940GX"), emptyList(), "somewhere", "tube")
        assertEquals(StopAccess(StepFreeLevel.NONE, liftOut = true, byLift = true), out)
        // A lift the station doesn't use changes nothing.
        assertEquals(StepFreeLevel.LEVEL, stopAccess(table, setOf("HUBZ-Lift-B"), listOf("940GX"), emptyList(), "somewhere", "tube").level)
    }

    @Test
    fun `only a lift the shown lines need is worth watching`() {
        // Opened from the Elsewhere line, reached on foot: no lift to watch, though the Somewhere line needs one
        // (Codex on #678).
        assertEquals(false, stopAccess(table, emptySet(), listOf("940GX"), emptyList(), "elsewhere", "tube").byLift)
        assertEquals(true, stopAccess(table, emptySet(), listOf("940GX"), emptyList(), "somewhere", "tube").byLift)
        // Off the line, every line through it is shown, so the Somewhere line's lift is watched.
        assertEquals(true, stopAccess(table, emptySet(), listOf("940GX"), bothLines, null, "tube").byLift)
        // Already not step-free, or unknown: an outage can't change that, so nothing is watched (Codex on #678).
        val stepped = StepFreeAccess(
            mapOf(
                "940GX" to mapOf(
                    "somewhere" to listOf(
                        StepFreePlatform(StepFreeLevel.LEVEL, "1", byLift = LiftStop("HUBX", 2)),
                        StepFreePlatform(StepFreeLevel.NONE, "2"),
                    ),
                ),
            ),
        )
        assertEquals(false, stopAccess(stepped, emptySet(), listOf("940GX"), emptyList(), "somewhere", "tube").byLift)
        val withThird = bothLines + LineRef("third", "Third", "tube")
        assertEquals(false, stopAccess(table, emptySet(), listOf("940GX"), withThird, null, "tube").byLift)
    }

    @Test
    fun `a station under several ids takes them all`() {
        // The table describes this station under its second id only, as St Pancras's under its high-speed
        // one (Codex on #678): opened under the first, it still finds it for a line only the second serves.
        val split = StepFreeAccess(
            mapOf(
                "910GA" to emptyMap(),
                "910GB" to mapOf("national-rail" to listOf(StepFreePlatform(StepFreeLevel.PLATFORM, "1"))),
            ),
        )
        val rail = listOf(LineRef("southeastern", "Southeastern", "national-rail"))
        val onlyB = mapOf("910GA" to setOf("thameslink"), "910GB" to setOf("southeastern"))
        assertEquals(StepFreeLevel.PLATFORM, stopAccess(split, emptySet(), listOf("910GA", "910GB"), rail, null, "national-rail", onlyB).level)
        // A line both ids serve, one undescribed, is unknown: its platforms there may be the ones without
        // access (Codex on #678).
        val bothServe = mapOf("910GA" to setOf("southeastern"), "910GB" to setOf("southeastern"))
        assertEquals(null, stopAccess(split, emptySet(), listOf("910GA", "910GB"), rail, null, "national-rail", bothServe).level)
        assertEquals(null, stopAccess(split, emptySet(), listOf("910GA", "910GB"), emptyList(), "southeastern", "national-rail", bothServe).level)
        // Where each id covers some platforms, the level is the one all of them meet.
        val both = StepFreeAccess(
            mapOf(
                "910GA" to mapOf("national-rail" to listOf(StepFreePlatform(StepFreeLevel.NONE, "2"))),
                "910GB" to mapOf("national-rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL, "1"))),
            ),
        )
        assertEquals(StepFreeLevel.NONE, stopAccess(both, emptySet(), listOf("910GA", "910GB"), emptyList(), "national-rail", "national-rail").level)
    }

    @Test
    fun `an id's access answers only for the lines it serves`() {
        // The table describes only the second id's platforms, as St Pancras's Southeastern ones (Codex on #678).
        val split = StepFreeAccess(
            mapOf("910GB" to mapOf("national-rail" to listOf(StepFreePlatform(StepFreeLevel.LEVEL, "1")))),
        )
        val served = mapOf("910GA" to setOf("north-rail"), "910GB" to setOf("south-rail"))
        val ids = listOf("910GA", "910GB")
        // A train only the first id serves isn't given the second's platforms: unknown.
        assertEquals(null, stopAccess(split, emptySet(), ids, emptyList(), "north-rail", "national-rail", served).level)
        // The second id's own train is.
        assertEquals(StepFreeLevel.LEVEL, stopAccess(split, emptySet(), ids, emptyList(), "south-rail", "national-rail", served).level)
        // Every line through it: the first's undescribed leaves it unknown.
        val rail = listOf(LineRef("north-rail", "North", "national-rail"), LineRef("south-rail", "South", "national-rail"))
        assertEquals(null, stopAccess(split, emptySet(), ids, rail, null, "national-rail", served).level)
        // A line the index lists under neither is read under the stop itself.
        assertEquals(null, stopAccess(split, emptySet(), ids, emptyList(), "other-rail", "national-rail", served).level)
        assertEquals(StepFreeLevel.LEVEL, stopAccess(split, emptySet(), ids.reversed(), emptyList(), "other-rail", "national-rail", served).level)
    }
}
