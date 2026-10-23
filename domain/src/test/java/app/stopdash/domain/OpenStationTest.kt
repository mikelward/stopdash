package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenStationTest {
    // Hubs, not anyone's place.
    private val toCanaryWharf = ToChoice(stopId = "940GZZLUCYF", name = "Canary Wharf")
    private val toBank = ToChoice(stopId = "940GZZLUBNK", name = "Bank")
    private val kingsCross = OpenStation("King's Cross St. Pancras", to = toCanaryWharf)

    @Test
    fun `a station not open yet opens as picked`() {
        val picked = OpenStation("Euston", to = toBank)
        assertEquals(picked, OpenStation.opened(null, picked, carryTo = false))
        assertEquals(picked, OpenStation.opened(null, picked, carryTo = true))
    }

    @Test
    fun `a station open already is brought up as it was, but as a trip's new start at that trip's To`() {
        val picked = OpenStation("King's Cross St. Pancras", to = toBank)
        assertEquals(kingsCross, OpenStation.opened(kingsCross, picked, carryTo = false))
        // Codex on #1147: picked in the From… search for a change of start, it kept its own trip's destination.
        assertEquals(kingsCross.copy(to = toBank), OpenStation.opened(kingsCross, picked, carryTo = true))
    }
}
