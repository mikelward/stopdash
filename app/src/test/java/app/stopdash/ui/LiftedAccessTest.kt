package app.stopdash.ui

import app.stopdash.domain.LiftMap
import app.stopdash.domain.LiftStop
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.StepFreePlatform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** TfL's step-free table, read once, with the lifts out rebuilt only as they change. A synthetic station. */
class LiftedAccessTest {
    private val table = StepFreeAccess(
        mapOf("940GZZLUPRA" to mapOf("red" to listOf(StepFreePlatform(StepFreeLevel.RAMP, byLift = LiftStop("PRA", 1))))),
        mapOf("PRA" to LiftMap(walks = emptyMap(), lifts = mapOf("LIFT1" to setOf(0, 1)))),
    )

    @Test
    fun `the table with every lift working is had without losing the one rebuilt for the lifts out`() = runTest {
        var reads = 0
        val lifted = LiftedAccess({ reads++; table })
        val out = lifted.with(setOf("LIFT1"))!!
        assertEquals(StepFreeLevel.NONE, out.level("940GZZLUPRA", "red"))
        assertSame(table, lifted.base())
        // The rebuilt one is still had as it was, not rebuilt again; the table read once.
        assertSame(out, lifted.with(setOf("LIFT1")))
        assertEquals(1, reads)
        // With nothing out, the table itself.
        assertSame(table, lifted.with(emptySet()))
    }

    @Test
    fun `a table that can't be read is none, said`() = runTest {
        val said = mutableListOf<String>()
        val lifted = LiftedAccess({ throw IllegalStateException("unreadable") }, { said += it })
        assertNull(lifted.base())
        assertNull(lifted.with(setOf("LIFT1")))
        assertEquals(listOf("step-free table unreadable (IllegalStateException)"), said)
    }
}
