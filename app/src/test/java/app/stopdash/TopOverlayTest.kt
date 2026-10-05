package app.stopdash

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which activity-level overlay shows when several are open at once ([topOverlay]). */
class TopOverlayTest {
    @Test
    fun `the licenses opened from On the way's About show over the trip`() {
        // Ranked under the trip, "Open source licenses" from On the way's About did nothing.
        assertEquals(TopOverlay.LICENSES, topOverlay(licenses = true, onTheWay = true, favoritePlaces = false, settings = false))
    }

    @Test
    fun `closing the licenses returns to the trip on the way`() {
        assertEquals(TopOverlay.ON_THE_WAY, topOverlay(licenses = false, onTheWay = true, favoritePlaces = true, settings = true))
    }

    @Test
    fun `the saved places sit above Settings, which is last`() {
        assertEquals(TopOverlay.FAVORITE_PLACES, topOverlay(licenses = false, onTheWay = false, favoritePlaces = true, settings = true))
        assertEquals(TopOverlay.SETTINGS, topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = true))
    }

    @Test
    fun `the starred journeys sit above Settings, under the saved places`() {
        assertEquals(
            TopOverlay.STARRED_JOURNEYS,
            topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = true, starredJourneys = true),
        )
        assertEquals(
            TopOverlay.FAVORITE_PLACES,
            topOverlay(licenses = false, onTheWay = false, favoritePlaces = true, settings = true, starredJourneys = true),
        )
    }

    @Test
    fun `with none of those open, the station pages and search show`() {
        assertEquals(TopOverlay.STATIONS, topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = false))
    }
}
