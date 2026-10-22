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
    private var stationSearchOpen by pages.kept(Page.StationSearch, mutableStateOf(false)) { it }
    private var openStationId by pages.kept(Page.Station, mutableStateOf<String?>(null)) { it != null }
    private var linesOpen by pages.flag(Page.Lines)
    private var hereTripOpen by pages::tripOpen
    private var routeStopTrail by pages.kept(Page.RouteStop, mutableStateOf<String?>(null)) { it != null }
    private var departuresStationId by pages.kept(Page.StopDepartures, mutableStateOf<String?>(null)) { it != null }

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
        shown(Page.Lines, Page.Station)
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
    fun `a station picked in the search opens over it, and its Back returns to the search`() {
        stationSearchOpen = true
        shown(Page.StationSearch)
        openStationId = "940GZZLUKSX"
        shown(Page.StationSearch, Page.Station)
        // Settings over the station, then the station's Back: the search, as it was, under Settings.
        settingsOpen = true
        openStationId = null
        shown(Page.StationSearch, Page.Settings)
        settingsOpen = false
        stationSearchOpen = false
        shown()
        // A station opened on its own (a route page's stop), closed with nothing under it.
        openStationId = "940GZZLUKSX"
        shown(Page.Station)
        openStationId = null
        shown()
    }

    @Test
    fun `a change of start from a station leaves the search where it was, and Back to the station goes over it`() {
        // A station picked in the search, then its From row: the search under it again, the station closed.
        stationSearchOpen = true
        openStationId = "940GZZLUKSX"
        stationSearchOpen = true
        openStationId = null
        shown(Page.StationSearch)
        // Back to the station: over the search, which stays for its Back ([searchAfterChange]).
        openStationId = "940GZZLUKSX"
        stationSearchOpen = searchAfterChange(stationSearchOpen, openedForChange = false)
        shown(Page.StationSearch, Page.Station)
        openStationId = null
        stationSearchOpen = false
        // A station opened over Lines…, whose From row opens a search the rider never had: over the station, and
        // closed again as the change goes back to it.
        linesOpen = true
        openStationId = "940GZZLUKSX"
        stationSearchOpen = true
        openStationId = null
        shown(Page.Lines, Page.StationSearch)
        openStationId = "940GZZLUKSX"
        stationSearchOpen = searchAfterChange(stationSearchOpen, openedForChange = true)
        shown(Page.Lines, Page.Station)
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
    fun `a route stop's details show over the page while only windows are over them`() {
        routeStopTrail = "stop"
        assertTrue(pages.shows(Page.RouteStop))
        // Drawn over the near-me page, which stays the page showing, on top, under them.
        assertEquals(Page.NearMe(0), pages.topPage)
        assertEquals(listOf(Page.NearMe(0)), pages.pages)
        // Its menu's Licenses and Settings, and the screens that take over the app, cover them until their Back.
        settingsOpen = true
        assertFalse(pages.shows(Page.RouteStop))
        assertEquals(Page.Settings, pages.topPage)
        licensesOpen = true
        licensesOpen = false
        settingsOpen = false
        assertTrue(pages.shows(Page.RouteStop))
        onTheWayOpen = true
        assertFalse(pages.shows(Page.RouteStop))
        onTheWayOpen = false
        // Its Favorite's editor too, and a trip from here its place routes to stays over them until its Back.
        favoritePlacesOpen = true
        hereTripOpen = true
        favoritePlacesOpen = false
        assertFalse(pages.shows(Page.RouteStop))
        hereTripOpen = false
        assertTrue(pages.shows(Page.RouteStop))
        routeStopTrail = null
        shown()
    }

    @Test
    fun `a stop's Departures show over its details, or over Lines opened from them, which they cover`() {
        routeStopTrail = "stop"
        // Departures from the details: both shown, the Departures on top.
        departuresStationId = "940GZZLUKSX"
        assertTrue(pages.shows(Page.RouteStop))
        assertTrue(pages.shows(Page.StopDepartures))
        assertEquals(Page.StopDepartures, pages.top)
        departuresStationId = null
        assertEquals(Page.RouteStop, pages.top)
        // Lines… from the details' line pill covers them, and a stop's Departures picked there shows over it (Codex on
        // #1064): not under the details.
        linesOpen = true
        assertFalse(pages.shows(Page.RouteStop))
        departuresStationId = "940GZZLUKSX"
        assertTrue(pages.shows(Page.StopDepartures))
        assertFalse(pages.shows(Page.RouteStop))
        shown(Page.RouteStop, Page.Lines, Page.StopDepartures)
        // Back from each in turn: Lines…, then the details.
        departuresStationId = null
        linesOpen = false
        assertTrue(pages.shows(Page.RouteStop))
    }

    @Test
    fun `the stack holds each page once, however often it's opened`() {
        // Its walks in composition are bounded by the kinds of page ([Pages]), not by how the rider got there.
        repeat(3) {
            settingsOpen = true
            licensesOpen = true
            hereTripOpen = true
            linesOpen = true
            openStationId = "940GZZLUKSX"
            routeStopTrail = "stop"
            departuresStationId = "940GZZLUKSX"
            pages.show(Page.RouteStop)
            pages.generation++
        }
        val stack = pages.stack.toList()
        assertEquals(stack.size, stack.toSet().size)
        assertEquals(stack.size, stack.map { it::class }.toSet().size)
    }

    @Test
    fun `a window opened again shows over what was opened since`() {
        departuresStationId = "940GZZLUKSX"
        settingsOpen = true
        assertFalse(pages.shows(Page.StopDepartures))
        // Shown again over Settings, as opening it asks.
        pages.show(Page.StopDepartures)
        shown(Page.Settings, Page.StopDepartures)
        assertTrue(pages.shows(Page.StopDepartures))
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
