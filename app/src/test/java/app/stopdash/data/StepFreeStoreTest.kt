package app.stopdash.data

import app.stopdash.domain.LiftStop
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.StepFreePlatform
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled step-free table (built from TfL's station data by the `station-index` workflow) is
 * present, parses, and grades public stations as TfL's step-free guide does. A missing asset fails
 * rather than skips: the app would mark nothing step-free, silently.
 */
class StepFreeStoreTest {
    private val asset = File("src/main/assets/stations/step_free.json")

    @Test
    fun `the bundled table grades stations by platform and line`() {
        assertTrue("bundled step-free table is missing; run the station-index workflow", asset.exists())
        val access = StepFreeStore.parse(asset.readText())
        // Green Park: level onto the train on all three lines.
        for (line in listOf("jubilee", "piccadilly", "victoria")) {
            assertEquals(line, StepFreeLevel.LEVEL, access.level("940GZZLUGPK", line))
        }
        // Oxford Circus: no step-free route from the street.
        assertEquals(StepFreeLevel.NONE, access.level("940GZZLUOXC", "central"))
        // Westminster: level onto the Jubilee line, a ramp onto the District.
        assertEquals(StepFreeLevel.LEVEL, access.level("940GZZLUWSM", "jubilee"))
        assertEquals(StepFreeLevel.RAMP, access.level("940GZZLUWSM", "district"))
        // Bank: the Northern line step-free, the Central line not.
        assertEquals(StepFreeLevel.LEVEL, access.level("940GZZLUBNK", "northern"))
        assertEquals(StepFreeLevel.NONE, access.level("940GZZLUBNK", "central"))
        // King's Cross: every National Rail platform by ramp, under the one line id TfL gives them.
        assertEquals(StepFreeLevel.RAMP, access.level("910GKNGX", StepFreeAccess.NATIONAL_RAIL))
        assertTrue(access.platforms("910GKNGX", StepFreeAccess.NATIONAL_RAIL).size > 5)
    }

    @Test
    fun `the bundled table takes a platform off while the lifts its route needs are out`() {
        val access = StepFreeStore.parse(asset.readText())
        // Earls Court: one lift takes the street to the eastbound District platforms.
        assertTrue(access.byLift("940GZZLUECT"))
        val earlsCourt = access.withLiftsOut(setOf("940GZZLUECT-Lift-5"))
        assertEquals(StepFreeLevel.NONE, earlsCourt.level("940GZZLUECT", "district", "1"))
        assertEquals(StepFreeLevel.LEVEL, earlsCourt.level("940GZZLUECT", "district", "3"))
        // Bank: with the King William Street entrance's lift out, the Cannon Street one still gets a
        // rider to the Northern line.
        assertEquals(StepFreeLevel.LEVEL, access.withLiftsOut(setOf("HUBBAN-Lift-7")).level("940GZZLUBNK", "northern"))
        // Green Park's Victoria line: either of two lifts reaches it, so it takes both out.
        assertEquals(StepFreeLevel.LEVEL, access.withLiftsOut(setOf("940GZZLUGPK-Lift-5")).level("940GZZLUGPK", "victoria"))
        assertEquals(
            StepFreeLevel.NONE,
            access.withLiftsOut(setOf("940GZZLUGPK-Lift-5", "940GZZLUGPK-Lift-6")).level("940GZZLUGPK", "victoria"),
        )
    }

    @Test
    fun `a platform reached only by lift keeps its place in the station's lift map`() {
        val warnings = mutableListOf<String>()
        val access = StepFreeStore.parse(
            """{"version":1,"stops":{"940GZZEXMPA":{"victoria":[
              {"platform":"3","level":"level","station":"HUBEXM","node":1},
              {"platform":"4","level":"level","station":"HUBNONE","node":1}]}},
              "stations":{"HUBEXM":{"walks":[],"lifts":{"HUBEXM-Lift-1":[0,1]}}}}""",
        ) { warnings += it }
        assertEquals(LiftStop("HUBEXM", 1), access.platforms("940GZZEXMPA", "victoria")[0].byLift)
        val out = access.withLiftsOut(setOf("HUBEXM-Lift-1"))
        assertEquals(StepFreeLevel.NONE, out.level("940GZZEXMPA", "victoria", "3"))
        // A platform naming a map the table doesn't hold can't be walked again: it keeps its level, and says so.
        assertEquals(StepFreeLevel.LEVEL, out.level("940GZZEXMPA", "victoria", "4"))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a platform keeps its number, direction and notes`() {
        val access = StepFreeStore.parse(
            """{"version":1,"stops":{"940GZZEXMPA":{"victoria":[
              {"platform":"3","direction":"Northbound","level":"level","where":"2 centre doors on car 5"},
              {"platform":"4","direction":"Southbound","level":"ramp","limitedLift":true,"entrance":"Example Road"}]}}}""",
        )
        assertEquals(
            listOf(
                StepFreePlatform(StepFreeLevel.LEVEL, "3", "Northbound", where = "2 centre doors on car 5"),
                StepFreePlatform(StepFreeLevel.RAMP, "4", "Southbound", limitedLift = true, entrance = "Example Road"),
            ),
            access.platforms("940GZZEXMPA", "victoria"),
        )
    }

    @Test
    fun `a level this build doesn't know is left out, not guessed`() {
        val warnings = mutableListOf<String>()
        val access = StepFreeStore.parse(
            """{"version":1,"stops":{"940GZZEXMPA":{"victoria":[
              {"platform":"3","level":"hover"},{"platform":"4","level":"none"}],
              "jubilee":[{"platform":"5","level":"hover"}]}}}""",
        ) { warnings += it }
        assertEquals(StepFreeLevel.NONE, access.level("940GZZEXMPA", "victoria"))
        assertNull(access.level("940GZZEXMPA", "victoria", "3"))
        assertEquals(setOf("victoria"), access.lines("940GZZEXMPA"))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a table of another version is ignored`() {
        val warnings = mutableListOf<String>()
        val access = StepFreeStore.parse("""{"version":2,"stops":{"940GZZEXMPA":{"victoria":[{"level":"level"}]}}}""") { warnings += it }
        assertTrue(access.isEmpty)
        assertEquals(1, warnings.size)
    }
}
