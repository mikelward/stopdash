package app.stopdash

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What shows when several screens are open at once ([Pages]): the one opened last, over the ones it was opened
 * from, so Back returns to each in turn. Each case below was a fixed ranking of the screens before the back
 * stack, checked here against the order the rider opens them in.
 */
class PagesTest {
    private val pages = Pages(NavBackStack(Page.NearMe(0)))
    private var licensesOpen by pages.flag(Page.Licenses)
    private var settingsOpen by pages.flag(Page.Settings)
    private var onTheWayOpen by pages.flag(Page.OnTheWay)
    private var favoritePlacesOpen by pages.flag(Page.FavoritePlaces)
    private var favoriteJourneysOpen by pages.flag(Page.FavoriteJourneys)
    private val stationSearchState = mutableStateOf(false)
    private val openStationIdState = mutableStateOf<String?>(null)
    private var stationSearchOpen by pages.kept(Page.Stations, stationSearchState, others = { openStationIdState.value != null }) { it }
    private var openStationId by pages.kept(Page.Stations, openStationIdState, others = { stationSearchState.value }) { it != null }
    private var linesOpen by pages.flag(Page.Lines)
    private var hereTripOpen by pages::tripOpen

    private fun shown(vararg page: Page) = assertEquals(listOf(Page.NearMe(0), *page), pages.stack.toList())

    @Test
    fun `the licenses opened from On the way's About show over the trip, and close back to it`() {
        // Ranked under the trip, "Open source licenses" from On the way's About did nothing.
        onTheWayOpen = true
        licensesOpen = true
        assertEquals(Page.Licenses, pages.top)
        licensesOpen = false
        assertEquals(Page.OnTheWay, pages.top)
    }

    @Test
    fun `the saved places and favorite rides open over Settings, and close back to it`() {
        settingsOpen = true
        favoritePlacesOpen = true
        shown(Page.Settings, Page.FavoritePlaces)
        favoritePlacesOpen = false
        favoriteJourneysOpen = true
        shown(Page.Settings, Page.FavoriteJourneys)
        favoriteJourneysOpen = false
        assertEquals(Page.Settings, pages.top)
        settingsOpen = false
        assertEquals(Page.NearMe(0), pages.top)
    }

    @Test
    fun `a screen opened from Lines shows over it, and its Back returns to Lines`() {
        linesOpen = true
        // A line page's menu opens the disruptions settings over Lines….
        settingsOpen = true
        shown(Page.Lines, Page.Settings)
        settingsOpen = false
        // A stop's From opens its station page over Lines….
        openStationId = "940GZZLUKSX"
        shown(Page.Lines, Page.Stations)
        openStationId = null
        assertEquals(Page.Lines, pages.top)
    }

    @Test
    fun `a screen opened again shows, from under the pages opened since`() {
        // A trip End couldn't forget reopens the trip on the way's screen to say so, over whatever is up.
        onTheWayOpen = true
        settingsOpen = true
        licensesOpen = true
        onTheWayOpen = true
        shown(Page.Settings, Page.Licenses, Page.OnTheWay)
        // Already showing: left as it is.
        onTheWayOpen = true
        shown(Page.Settings, Page.Licenses, Page.OnTheWay)
    }

    @Test
    fun `the station flow is open while its search or a station is`() {
        stationSearchOpen = true
        shown(Page.Stations)
        // A station opened from the search, then its Back to the search: the flow stays where it is.
        openStationId = "940GZZLUKSX"
        settingsOpen = true
        openStationId = null
        shown(Page.Stations, Page.Settings)
        settingsOpen = false
        stationSearchOpen = false
        shown()
        // A station opened on its own (a route page's stop), closed with nothing under it.
        openStationId = "940GZZLUKSX"
        shown(Page.Stations)
        openStationId = null
        shown()
    }

    @Test
    fun `the trip from here opens over the page it was opened from, which its Back returns to`() {
        // To… from the near-me list.
        hereTripOpen = true
        shown(Page.HereTrip(0))
        hereTripOpen = false
        shown()
        // A stop's To on Lines…: the trip over it, Lines… under it, rather than stepping off the stack.
        linesOpen = true
        hereTripOpen = true
        shown(Page.Lines, Page.HereTrip(0))
        assertTrue(pages.isUnder(Page.Lines, pages.hereTrip))
        // A screen opened over the trip, then the trip's Back: Lines… on top, at the stop.
        settingsOpen = true
        settingsOpen = false
        hereTripOpen = false
        shown(Page.Lines)
        assertFalse(pages.isUnder(Page.Lines, pages.hereTrip))
        // Lines… opened over a trip isn't under it.
        linesOpen = false
        hereTripOpen = true
        linesOpen = true
        shown(Page.HereTrip(0), Page.Lines)
        assertFalse(pages.isUnder(Page.Lines, pages.hereTrip))
    }

    @Test
    fun `a new near-me generation leaves the pages over it open`() {
        // An arrived trip's Done, opened over Settings from its notification: the near-me view afresh,
        // Settings as it was.
        settingsOpen = true
        pages.generation++
        assertEquals(listOf(Page.NearMe(1), Page.Settings), pages.stack.toList())
        assertTrue(settingsOpen)
        assertFalse(licensesOpen)
    }

    @Test
    fun `a new near-me generation opens the trip from here afresh too, where it was`() {
        // A shared place while a trip is up under Settings: the trip opens afresh to it, not as it was left.
        hereTripOpen = true
        settingsOpen = true
        pages.generation++
        assertEquals(listOf(Page.NearMe(1), Page.HereTrip(1), Page.Settings), pages.stack.toList())
        assertTrue(hereTripOpen)
        hereTripOpen = false
        assertEquals(listOf(Page.NearMe(1), Page.Settings), pages.stack.toList())
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
