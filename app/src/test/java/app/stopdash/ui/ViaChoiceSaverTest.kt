package app.stopdash.ui

import androidx.compose.runtime.saveable.SaverScope
import app.stopdash.domain.DestinationViaChoice
import app.stopdash.domain.Pick
import app.stopdash.domain.TripVia
import app.stopdash.domain.ViaChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ViaChoiceSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    // Hubs' ids and names, not anyone's stop.
    private val bank = TripVia("940GZZLUBNK", "Bank", Pick.Level.STATION)
    private val kingsCross = TripVia("HUBKGX", "King's Cross & St Pancras International", Pick.Level.HUB)

    private fun roundTrip(held: DestinationViaChoice): DestinationViaChoice? =
        ViaChoiceSaver.restore(with(ViaChoiceSaver) { scope.save(held) }!!)

    @Test
    fun `a via and a pending one round-trip with the levels they were picked at`() {
        val held = DestinationViaChoice("940GZZLUCYF", ViaChoice(via = bank, pending = kingsCross))
        assertEquals(held, roundTrip(held))
        assertEquals(DestinationViaChoice(null), roundTrip(DestinationViaChoice(null)))
        // A row that didn't say its level, or a stop a trip's row handed in, keeps none.
        val handed = DestinationViaChoice("940GZZLUCYF", ViaChoice(via = TripVia("940GZZLUBNK", "Bank")))
        assertEquals(handed, roundTrip(handed))
    }

    @Test
    fun `a via saved before its level was kept restores with none`() {
        val before = listOf("940GZZLUCYF", "940GZZLUBNK", "Bank", "HUBKGX", "King's Cross & St Pancras International")
        assertEquals(
            DestinationViaChoice("940GZZLUCYF", ViaChoice(via = bank.copy(level = null), pending = kingsCross.copy(level = null))),
            ViaChoiceSaver.restore(before),
        )
    }
}
