package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A step-free ride's ways off against today's lifts, on synthetic stations and an example line. */
class StepFreeExitsTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")

    // Praed's red platform is reached by ramp through one lift, LIFT1; Hamlet's by ramp with no lift.
    private val access = StepFreeAccess(
        mapOf(
            "940GZZLUPRA" to mapOf("red" to listOf(StepFreePlatform(StepFreeLevel.RAMP, byLift = LiftStop("PRA", 1)))),
            "940GZZLUHAM" to mapOf("red" to listOf(StepFreePlatform(StepFreeLevel.RAMP))),
        ),
        mapOf("PRA" to LiftMap(walks = emptyMap(), lifts = mapOf("LIFT1" to setOf(0, 1)))),
    )
    private val liftOut = access.withLiftsOut(setOf("LIFT1"))

    private val toPraed = TripLeg("tube", "red", "Red", "A", "A", "940GZZLUPRA", "Praed", t0, t0.plusSeconds(600), path = listOf("B", "940GZZLUPRA"))
    private fun at(stepFree: StepFree, vararg ids: String) = LegEnd(stepFree, LegEnd.Level.STOP, setOf("STATION"), ids = ids.toSet())

    @Test
    fun `a lift out leaving no call step-free has lost the ride's way off`() {
        assertTrue(StepFreeExits.lost(toPraed, at(StepFree.FULLY, "940GZZLUPRA"), access, liftOut))
        // With no ids worked out, by the ride's own call alone.
        assertTrue(StepFreeExits.lost(toPraed, at(StepFree.FULLY), access, liftOut))
        // Every lift working: nothing lost.
        assertFalse(StepFreeExits.lost(toPraed, at(StepFree.FULLY, "940GZZLUPRA"), access, access))
    }

    @Test
    fun `a call named by its platform is judged as its station's record`() {
        // The Planner names Praed's platform, which TfL's table holds under the station's record (Codex, #1077).
        val toPlatform = toPraed.copy(toId = "9400ZZLUPRA", path = listOf("B", "9400ZZLUPRA"))
        assertTrue(StepFreeExits.lost(toPlatform, at(StepFree.FULLY, "940GZZLUPRA"), access, liftOut))
        assertTrue(StepFreeExits.lost(toPlatform, at(StepFree.FULLY), access, liftOut))
        // Hamlet's platform on the way, still step-free: the rider gets off there.
        val viaHamlet = toPlatform.copy(path = listOf("B", "9400ZZLUHAM", "9400ZZLUPRA"))
        assertFalse(StepFreeExits.lost(viaHamlet, at(StepFree.FULLY, "940GZZLUPRA", "940GZZLUHAM"), access, liftOut))
    }

    @Test
    fun `another call where the ride gets off that's still step-free keeps its way off`() {
        // Calling at Hamlet first, reached by ramp with no lift: the rider gets off there.
        val viaHamlet = toPraed.copy(path = listOf("B", "940GZZLUHAM", "940GZZLUPRA"))
        assertFalse(StepFreeExits.lost(viaHamlet, at(StepFree.FULLY, "940GZZLUPRA", "940GZZLUHAM"), access, liftOut))
        // Not where it gets off: a call the end doesn't name counts for nothing.
        assertTrue(StepFreeExits.lost(viaHamlet, at(StepFree.FULLY, "940GZZLUPRA"), access, liftOut))
    }

    @Test
    fun `calls the table fails without a lift out say nothing`() {
        // TfL's table has nothing for the Planner's call: the Planner, who checked it, may know better.
        val toElsewhere = toPraed.copy(toId = "940GZZLUXXX", toName = "Elsewhere", path = listOf("B", "940GZZLUXXX"))
        assertFalse(StepFreeExits.lost(toElsewhere, at(StepFree.FULLY, "940GZZLUXXX"), access, liftOut))
    }

    @Test
    fun `nothing is lost at no level or from the street`() {
        assertFalse(StepFreeExits.lost(toPraed, at(StepFree.ANY, "940GZZLUPRA"), access, liftOut))
        val bus = toPraed.copy(mode = "bus", lineId = "25")
        assertFalse(StepFreeExits.lost(bus, at(StepFree.FULLY, "940GZZLUPRA"), access, liftOut))
    }
}
