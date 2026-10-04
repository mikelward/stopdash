package app.stopdash.ui

import androidx.compose.ui.unit.Density
import app.stopdash.domain.LineRef
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic stops; bus lines only. */
class PillSlotWidthTest {
    private val t = Instant.parse("2026-10-04T08:00:00Z")

    private fun ride(line: String) = TripLeg(
        mode = "bus", lineId = line, lineName = line, fromId = "A", fromName = "A", toId = "B", toName = "B",
        departure = t, arrival = t,
    )

    private fun estimate(vararg rides: TripLeg) =
        TripTiming.Estimate(TripRoute(rides.toList()), TripTiming.Basis.LIVE, t, emptyList(), false, t)

    // A pill's width as its codes joined, so each pill measures apart: a stand-in for real text.
    private fun width(pill: List<LineRef>) = pill.joinToString("/") { it.id }.length

    @Test
    fun `the widest pill on any card sets the width, each cut pill measured once`() {
        // One card rides the 47 or the 188 (a cut pill), then the 43; another the 263 or the N263.
        val measured = mutableListOf<List<LineRef>>()
        val cards = listOf(
            listOf(estimate(ride("47"), ride("43")), estimate(ride("188"), ride("43"))),
            listOf(estimate(ride("263")), estimate(ride("N263"))),
            listOf(estimate(ride("47"), ride("43")), estimate(ride("188"), ride("43"))),
        )
        assertEquals("263/N263".length, pillSlotWidthPx(cards, emptyMap()) { measured += it; width(it) })
        // The 47/188 once, though two cards show it, and the lone 43 once: every lone pill is one width.
        assertEquals(3, measured.size)
    }

    @Test
    fun `no rides measure nothing`() {
        assertNull(pillSlotWidthPx(emptyList(), emptyMap()) { error("measured $it") })
    }

    @Test
    fun `a cut pill's width counts each code as at least two characters and its padding`() {
        val density = Density(1f)
        // Each character 10 px: "4" counts as "88"; 8 px at each end and 4 px either side of the cut.
        val cut = sharedPillWidthPx(listOf(LineRef("4", "4", "bus"), LineRef("N20", "N20", "bus")), density) { it.length * 10 }
        assertEquals(20 + 30 + 8 + 4 + 4 + 8, cut)
        // A lone pill is its fixed label width and padding, whatever its code.
        assertEquals(linePillWidthPx(density), sharedPillWidthPx(listOf(LineRef("N20", "N20", "bus")), density) { error("measured $it") })
        assertEquals(48 + 16, linePillWidthPx(density))
    }
}
