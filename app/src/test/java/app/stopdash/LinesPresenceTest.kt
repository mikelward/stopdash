package app.stopdash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinesPresenceTest {
    @Test
    fun `Back from a stop's trip returns to the stop`() {
        // Lines… opened, a stop's details up, its To planning the near-me trip.
        val underTrip = LinesPresence.CLOSED.opened().stepAsideForTrip()
        // Hidden under the trip, but still open: its line, stop and scroll are kept.
        assertFalse(underTrip.shown)
        assertTrue(underTrip.isOpen)
        assertEquals(
            TopOverlay.STATIONS,
            topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = false, lines = underTrip.shown),
        )
        // The trip's Back: Lines… shows again, over the near-me list, at the stop.
        val back = underTrip.tripClosed()
        assertTrue(back.shown)
        assertEquals(
            TopOverlay.LINES,
            topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = false, lines = back.shown),
        )
    }

    @Test
    fun `a trip opened without Lines… under it leaves Lines… as it was`() {
        assertEquals(LinesPresence.CLOSED, LinesPresence.CLOSED.tripClosed())
        assertEquals(LinesPresence.CLOSED, LinesPresence.CLOSED.stepAsideForTrip())
        assertEquals(LinesPresence.SHOWN, LinesPresence.SHOWN.tripClosed())
    }

    @Test
    fun `opened from a menu while under a trip, Lines… shows where it was left`() {
        assertEquals(LinesPresence.SHOWN, LinesPresence.UNDER_TRIP.opened())
    }

    @Test
    fun `closing Lines… under a trip leaves nothing to come back when the trip closes`() {
        // A change of start that replaces the trip with a station's closes Lines… with it.
        assertEquals(LinesPresence.CLOSED, LinesPresence.UNDER_TRIP.closed().tripClosed())
    }
}
