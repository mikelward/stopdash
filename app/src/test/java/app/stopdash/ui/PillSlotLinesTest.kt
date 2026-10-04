package app.stopdash.ui

import app.stopdash.domain.LineRef
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic stops; bus and tube lines only. */
class PillSlotLinesTest {
    private val t = Instant.parse("2026-10-04T08:00:00Z")

    private fun ride(line: String, mode: String = "bus") = TripLeg(
        mode = mode, lineId = line, lineName = line, fromId = "A", fromName = "A", toId = "B", toName = "B",
        departure = t, arrival = t,
    )

    private fun estimate(vararg rides: TripLeg) =
        TripTiming.Estimate(TripRoute(rides.toList()), TripTiming.Basis.LIVE, t, emptyList(), false, t)

    private fun bus(id: String) = LineRef(id, id, "bus")

    @Test
    fun `keeps each cut pill once and the lone pills with the longest names`() {
        // One card rides the 47 or the 188 (a cut pill), then the 43; two more ride lone pills.
        val cut = listOf(estimate(ride("47"), ride("43")), estimate(ride("188"), ride("43")))
        val cards = listOf(cut, listOf(estimate(ride("N20"), ride("4"))), listOf(estimate(ride("47"), ride("188"))))
        assertEquals(
            // The 47/188 once; of the lone pills, the two with three-character names, first seen first.
            listOf(listOf(bus("47"), bus("188")), listOf(bus("N20")), listOf(bus("188"))),
            pillSlotLines(cards, emptyMap()),
        )
    }

    @Test
    fun `no rides draw no pills`() {
        assertEquals(emptyList<List<LineRef>>(), pillSlotLines(emptyList(), emptyMap()))
    }
}
