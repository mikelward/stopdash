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
    fun `the favorite journeys sit above Settings, under the saved places`() {
        assertEquals(
            TopOverlay.FAVORITE_JOURNEYS,
            topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = true, favoriteJourneys = true),
        )
        assertEquals(
            TopOverlay.FAVORITE_PLACES,
            topOverlay(licenses = false, onTheWay = false, favoritePlaces = true, settings = true, favoriteJourneys = true),
        )
    }

    @Test
    fun `with none of those open, the station pages and search show`() {
        assertEquals(TopOverlay.STATIONS, topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = false))
    }

    @Test
    fun `Lines shows over the station pages, under the trip on the way and the licenses`() {
        assertEquals(TopOverlay.LINES, topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = false, lines = true))
        // A line page's menu opens the disruptions settings over Lines…; their Back returns to it.
        assertEquals(TopOverlay.SETTINGS, topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = true, lines = true))
        // A stop's From on Lines… opens its station page over it; its Back returns to Lines….
        assertEquals(TopOverlay.STATIONS, topOverlay(licenses = false, onTheWay = false, favoritePlaces = false, settings = false, lines = true, station = true))
        assertEquals(TopOverlay.ON_THE_WAY, topOverlay(licenses = false, onTheWay = true, favoritePlaces = false, settings = false, lines = true))
        assertEquals(TopOverlay.LICENSES, topOverlay(licenses = true, onTheWay = false, favoritePlaces = false, settings = false, lines = true))
    }

    @Test
    fun `a route stop's details step aside for a screen opened over them, and only then`() {
        assertEquals(false, routeStopCovered(licenses = false, settings = false, onTheWay = false, favoritePlaces = false, favoriteJourneys = false))
        // Its menu's Licenses and Settings, and the screens that take over the app.
        assertEquals(true, routeStopCovered(licenses = true, settings = false, onTheWay = false, favoritePlaces = false, favoriteJourneys = false))
        assertEquals(true, routeStopCovered(licenses = false, settings = true, onTheWay = false, favoritePlaces = false, favoriteJourneys = false))
        assertEquals(true, routeStopCovered(licenses = false, settings = false, onTheWay = true, favoritePlaces = false, favoriteJourneys = false))
        assertEquals(true, routeStopCovered(licenses = false, settings = false, onTheWay = false, favoritePlaces = true, favoriteJourneys = false))
        assertEquals(true, routeStopCovered(licenses = false, settings = false, onTheWay = false, favoritePlaces = false, favoriteJourneys = true))
    }
}
