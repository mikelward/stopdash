package app.stopdash.ui

import app.stopdash.ThreadRecorder
import app.stopdash.domain.LiftMap
import app.stopdash.domain.LineRef
import app.stopdash.domain.LiftStop
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.StepFreePlatform
import app.stopdash.domain.StopAccess
import app.stopdash.domain.StopLinks
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A station's step-free access on its details (SPEC *Finding a line*): for the line it was opened
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
    fun `a station's access is worked out on the worker`() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        try {
            val base = pool.asCoroutineDispatcher()
            val ranOn = ThreadRecorder()
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) =
                    base.dispatch(context) { ranOn.note(); block.run() }
            }
            val access = runBlocking { stopAccessOn(worker, table, setOf("HUBX-Lift-A"), "940GX", StopLinks(bothLines, emptyList(), emptyList()), "somewhere", "tube") }
            // Opened under an id the index files under the station, it reads the station's own ids.
            val links = StopLinks(bothLines, emptyList(), emptyList(), linesById = mapOf("940GX" to setOf("somewhere", "elsewhere")))
            assertEquals(StepFreeLevel.LEVEL, runBlocking { stopAccessOn(worker, table, emptySet(), "9400ZZX1", links, "somewhere", "tube") }.level)
            assertEquals(setOf("test-worker"), ranOn.threads().toSet())
            assertEquals(StepFreeLevel.NONE, access.level)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a station only a lift makes step-free shows nothing while a new outage is worked out`() {
        val byLift = StopAccess(StepFreeLevel.LEVEL, liftOut = false, byLift = true)
        // Lifts newly out could take the access away: nothing is shown until it's worked out again.
        assertEquals(null, heldWhileReworked(byLift, onlyLiftsBack = false))
        // Lifts coming back can only add access: the last answer, the more cautious, stands meanwhile.
        assertEquals(byLift, heldWhileReworked(byLift, onlyLiftsBack = true))
        // No lift can change a station reached on foot.
        val onFoot = StopAccess(StepFreeLevel.PLATFORM, liftOut = false, byLift = false)
        assertEquals(onFoot, heldWhileReworked(onFoot, onlyLiftsBack = false))
    }
}
