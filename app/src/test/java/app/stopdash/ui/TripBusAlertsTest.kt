package app.stopdash.ui

import app.stopdash.domain.LegEnd
import app.stopdash.domain.LineAlert
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** A trip card's bus alerts placed on its rides ([onBusRides]). Made-up stops; TfL's wording. */
class TripBusAlertsTest {
    private val at = Instant.parse("2026-10-17T15:00:00Z")
    private val route = LineSequence(
        listOf(LineRoute("North End - Victoria", listOf("n1", "n2", "n3", "w1", "w2", "w3"))),
        mapOf(
            "n1" to "North End", "n2" to "Alpha Road", "n3" to "Beta Road",
            "w1" to "Tottenham Court Road Station", "w2" to "Gamma Street", "w3" to "Marble Arch",
        ),
    )
    private val diversion = LineStatus(
        "99", 6, "Diversion",
        "Buses towards Victoria are diverted from Tottenham Court Road Station to Marble Arch via Delta Road and Epsilon Street.",
        soleAlert = true,
        planned = listOf(PlannedAlert("Diversion", "Buses divert from 30 October.", LocalDate.of(2026, 10, 30))),
    )

    private fun leg(from: String, to: String, mode: String = "bus") = TripLeg(mode, "99", "99", from, from, to, to, at, at.plusSeconds(600))

    // [leg] getting off where the Planner does ([LegEnd.planned]), as every caller's ride does today.
    private fun ride(from: String, to: String, mode: String = "bus") = leg(from, to, mode).let { it to LegEnd.planned(it, StepFree.DEFAULT) }

    @Test
    fun `a bus diversion at the far end of the route doesn't flag a ride at the near end`() {
        val shown = onBusRides(mapOf("99" to diversion), listOf(ride("n1", "n3")), mapOf("99" to route)).getValue("99")
        assertFalse(shown.disrupted)
        // Its work to come still shows.
        assertEquals(diversion.planned, shown.planned)
    }

    @Test
    fun `a ride into the diversion, an unknown route or another mode keeps the alert`() {
        assertSame(diversion, onBusRides(mapOf("99" to diversion), listOf(ride("n1", "w2")), mapOf("99" to route)).getValue("99"))
        assertSame(diversion, onBusRides(mapOf("99" to diversion), listOf(ride("n1", "n3")), mapOf("99" to null)).getValue("99"))
        assertSame(diversion, onBusRides(mapOf("99" to diversion), listOf(ride("n1", "n3"), ride("n1", "n3", "tube")), mapOf("99" to route)).getValue("99"))
        // Two rides of the line, one the route doesn't run: unknown, so on.
        assertSame(diversion, onBusRides(mapOf("99" to diversion), listOf(ride("n1", "n3"), ride("x1", "x2")), mapOf("99" to route)).getValue("99"))
    }

    @Test
    fun `an open route's row and the line page it opens take the leg's placed status`() {
        val near = leg("n1", "n3")
        val state = TripViewModel.State(statuses = mapOf("99" to diversion))
        assertSame(diversion, legStatusRow(state, near, at).status)
        val placed = state.copy(legStatuses = legStatuses(state.statuses, listOf(near to LegEnd.planned(near, StepFree.DEFAULT), ride("n1", "w2")), mapOf("99" to route)))
        // Only the ride the diversion is off has a status of its own.
        assertEquals(setOf(tripLegKey(near)), placed.legStatuses.keys)
        assertEquals(null, legStatusRow(placed, near, at).status)
        assertEquals(diversion.planned, legStatusRow(placed, near, at).plannedAlerts)
        assertSame(diversion, legStatusRow(placed, leg("n1", "w2"), at).status)
    }

    @Test
    fun `with several alerts, one reaching the ride keeps the line's status whole`() {
        val near = "Buses are diverted from Alpha Road to Beta Road via Zeta Lane."
        val several = LineStatus(
            "99", 6, "Diversion", diversion.fullText, soleAlert = false,
            underWay = listOf(LineAlert(6, "Diversion", diversion.fullText), LineAlert(6, "Diversion", near)),
            planned = diversion.planned,
        )
        assertSame(several, onBusRides(mapOf("99" to several), listOf(ride("n1", "n3")), mapOf("99" to route)).getValue("99"))
    }
}
