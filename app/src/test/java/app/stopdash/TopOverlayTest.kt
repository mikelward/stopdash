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
        // Its own Departures, whose Back finds it again.
        assertEquals(true, routeStopCovered(licenses = false, settings = false, onTheWay = false, favoritePlaces = false, favoriteJourneys = false, departures = true))
        // Lines… opened from its own line pill, whose Back finds it again rather than the route page under it.
        assertEquals(true, routeStopCovered(licenses = false, settings = false, onTheWay = false, favoritePlaces = false, favoriteJourneys = false, lines = true))
    }

    @Test
    fun `a stop's Departures opened from Lines over the details shows over it`() {
        // Lines… covers the details alone: Departures picked within it is the window asked for (Codex on #1064).
        assertEquals(
            false,
            routeStopCovered(
                licenses = false, settings = false, onTheWay = false, favoritePlaces = false, favoriteJourneys = false,
                lines = true, window = RouteStopWindow.DEPARTURES,
            ),
        )
        // It still steps aside for what takes over the app.
        assertEquals(
            true,
            routeStopCovered(
                licenses = false, settings = true, onTheWay = false, favoritePlaces = false, favoriteJourneys = false,
                lines = true, window = RouteStopWindow.DEPARTURES,
            ),
        )
    }

    @Test
    fun `back to a station from a change of start closes a search its From row opened`() {
        // A station opened from a route page or Lines…: its From row opened the search, so going back to the
        // station closes it, and the station's Back doesn't land on a search the rider never opened.
        assertEquals(false, searchAfterChange(open = true, openedForChange = true))
        // A station opened from the search: the search stays under it, for its Back.
        assertEquals(true, searchAfterChange(open = true, openedForChange = false))
        assertEquals(false, searchAfterChange(open = false, openedForChange = false))
    }
}
