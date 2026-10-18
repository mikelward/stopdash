package app.stopdash

import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideReturnTest {
    // Public station ids, as examples.
    private val victoria = JourneyEnd("940GZZLUVIC", "Victoria")
    private val warren = JourneyEnd("940GZZLUWRR", "Warren Street")
    private val fromVictoria = FavoriteJourney(victoria, warren, "victoria")
    private val fromWarren = FavoriteJourney(warren, victoria, "victoria")

    @Test
    fun `back from a ride's station reopens it from the stop it was shown boarding from`() {
        val reopen = RideReturn.onBack(RideReturn.record("HUBVIC", fromVictoria), "HUBVIC")
        assertEquals(fromVictoria.key, RideReturn.key(reopen))
        assertEquals("940GZZLUVIC", RideReturn.origin(reopen))
        // Shown swapped, from Warren Street: that is the stop it reopens from.
        assertEquals("940GZZLUWRR", RideReturn.origin(RideReturn.onBack(RideReturn.record("HUBWRR", fromWarren), "HUBWRR")))
    }

    @Test
    fun `back from another station reopens no ride`() {
        assertNull(RideReturn.onBack(RideReturn.record("HUBVIC", fromVictoria), "HUBKGX"))
        assertNull(RideReturn.onBack(null, "HUBVIC"))
        assertNull(RideReturn.key(null))
        assertNull(RideReturn.origin(null))
    }

    @Test
    fun `a ride now boarding from its other end is swapped back to its stop`() {
        // Shown from Victoria, swapped there; reopened unswapped, the fix turns it to board at Warren Street.
        assertEquals(listOf(fromVictoria.key), RideReturn.settledFlips(emptyList(), listOf(fromWarren), fromVictoria.key, "940GZZLUVIC"))
        // Already swapped, it's unswapped.
        assertEquals(emptyList<String>(), RideReturn.settledFlips(listOf(fromVictoria.key), listOf(fromWarren), fromVictoria.key, "940GZZLUVIC"))
    }

    @Test
    fun `a ride already boarding from its stop keeps its swaps`() {
        assertEquals(emptyList<String>(), RideReturn.settledFlips(emptyList(), listOf(fromVictoria), fromVictoria.key, "940GZZLUVIC"))
        // A ride no longer shown (unstarred meanwhile) changes nothing.
        assertEquals(emptyList<String>(), RideReturn.settledFlips(emptyList(), emptyList(), fromVictoria.key, "940GZZLUVIC"))
    }
}
