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
    // A station opened as the activity opens one, over whatever is up ([Pages.openStation]); null closes the one showing.
    private var openStationId: String?
        get() = pages.station?.id
        set(value) = if (value == null) pages.closeStation() else pages.openStation(value)
    private var linesOpen by pages.flag(Page.Lines)
    private var hereTripOpen by pages::tripOpen
    private var routeStopTrail by pages.kept(Page.RouteStop, mutableStateOf<String?>(null)) { it != null }
    private var departuresStationId by pages.kept(Page.StopDepartures, mutableStateOf<String?>(null)) { it != null }

    private fun shown(vararg page: Page) = assertEquals(listOf(Page.NearMe(0), *page), pages.stack.toList())

    @Test
    fun `a station opened beside a route page's stop goes over the details and the station they're over`() {
        openStationId = KINGS_CROSS
        routeStopTrail = "stop"
        // From on the details: the station beside the stop over them, the station under them kept for Back.
        openStationId = EUSTON
        shown(Page.Station(KINGS_CROSS), Page.RouteStop, Page.Station(EUSTON))
        assertEquals(Page.Station(EUSTON), pages.station)
        assertFalse(pages.shows(Page.RouteStop))
        // Its Back: the details again, over the station under them, which is the station showing once more.
        openStationId = null
        shown(Page.Station(KINGS_CROSS), Page.RouteStop)
        assertTrue(pages.shows(Page.RouteStop))
        assertEquals(Page.Station(KINGS_CROSS), pages.topPage)
        assertEquals(Page.Station(KINGS_CROSS), pages.station)
    }

    @Test
    fun `a stop's details a station was opened from or over are the station flow's, closed as it ends`() {
        routeStopTrail = "stop"
        assertFalse(pages.inStationFlow(Page.RouteStop))
        // From on the details over the near-me list: the station over them.
        openStationId = KINGS_CROSS
        assertTrue(pages.inStationFlow(Page.RouteStop))
        openStationId = null
        assertFalse(pages.inStationFlow(Page.RouteStop))
        // The details on a station's route page.
        routeStopTrail = null
        openStationId = KINGS_CROSS
        routeStopTrail = "stop"
        assertTrue(pages.inStationFlow(Page.RouteStop))
    }

    @Test
    fun `a change of start's search opens over its station, kept under it with what it was opened over`() {
        // King's Cross picked in the search, then Euston from a stop's details on its route page.
        stationSearchOpen = true
        openStationId = KINGS_CROSS
        routeStopTrail = "stop"
        openStationId = EUSTON
        // Euston's From row: the search to start elsewhere over it, everything under it as it was.
        pages.openStartSearch(EUSTON)
        shown(Page.StationSearch, Page.Station(KINGS_CROSS), Page.RouteStop, Page.Station(EUSTON), Page.StartSearch(EUSTON))
        assertEquals(Page.StartSearch(EUSTON), pages.topPage)
        // The station it changes is still the one showing, under its search.
        assertEquals(EUSTON, openStationId)
        // A station picked there opens over it; Back from it returns to the search, and Back from that to Euston.
        openStationId = VICTORIA
        shown(Page.StationSearch, Page.Station(KINGS_CROSS), Page.RouteStop, Page.Station(EUSTON), Page.StartSearch(EUSTON), Page.Station(VICTORIA))
        openStationId = null
        pages.close(Page.StartSearch(EUSTON))
        shown(Page.StationSearch, Page.Station(KINGS_CROSS), Page.RouteStop, Page.Station(EUSTON))
        // Euston's Back to the details, theirs to King's Cross, its to the search it was picked in.
        openStationId = null
        routeStopTrail = null
        openStationId = null
        shown(Page.StationSearch)
    }

    @Test
    fun `a change of start's search goes as its station moves, so none is left over another page`() {
        // Codex on #1152: picked again there, brought up over the search, its Back would close it and skip what it
        // was opened over. It comes back from under the search instead, the search closed.
        stationSearchOpen = true
        openStationId = KINGS_CROSS
        pages.openStartSearch(KINGS_CROSS)
        openStationId = KINGS_CROSS
        shown(Page.StationSearch, Page.Station(KINGS_CROSS))
        // Opened from somewhere over the search (a ride's Whole station): brought up, the search gone with it.
        pages.openStartSearch(KINGS_CROSS)
        settingsOpen = true
        openStationId = KINGS_CROSS
        shown(Page.StationSearch, Page.Settings, Page.Station(KINGS_CROSS))
        // Plan again's station in its place: the search changing its start goes, rather than covering the new one.
        pages.openStartSearch(KINGS_CROSS)
        pages.replaceStation(EUSTON)
        shown(Page.StationSearch, Page.Settings, Page.Station(EUSTON))
        // Its own Back closes one over it too.
        pages.openStartSearch(EUSTON)
        pages.closeStation()
        shown(Page.StationSearch, Page.Settings)
    }

    @Test
    fun `the end of the station flow closes a change of start's search with the stations`() {
        // A station opened over Lines…, with no search under it: its From row's search over it.
        linesOpen = true
        openStationId = KINGS_CROSS
        pages.openStartSearch(KINGS_CROSS)
        shown(Page.Lines, Page.Station(KINGS_CROSS), Page.StartSearch(KINGS_CROSS))
        // "Here" there, or a trip's Done: every station closes, and the search changing one's start with them.
        pages.closeStations()
        shown(Page.Lines)
    }

    @Test
    fun `a station open already is brought up, and the flow's end closes every station`() {
        openStationId = KINGS_CROSS
        settingsOpen = true
        openStationId = EUSTON
        openStationId = KINGS_CROSS
        shown(Page.Settings, Page.Station(EUSTON), Page.Station(KINGS_CROSS))
        pages.closeStations()
        shown(Page.Settings)
        assertEquals(null, pages.station)
    }

    @Test
    fun `Plan again's station takes the place of the station showing`() {
        openStationId = KINGS_CROSS
        onTheWayOpen = true
        pages.replaceStation(EUSTON)
        shown(Page.Station(EUSTON), Page.OnTheWay)
        // The same again: left as it is.
        pages.replaceStation(EUSTON)
        shown(Page.Station(EUSTON), Page.OnTheWay)
        // One open already, under the station showing: brought up in that one's place.
        pages.openStation(KINGS_CROSS)
        pages.replaceStation(EUSTON)
        shown(Page.OnTheWay, Page.Station(EUSTON))
        // None showing: opened.
        pages.closeStations()
        pages.replaceStation(KINGS_CROSS)
        shown(Page.OnTheWay, Page.Station(KINGS_CROSS))
    }

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
        openStationId = KINGS_CROSS
        shown(Page.Lines, Page.Station(KINGS_CROSS))
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
        openStationId = KINGS_CROSS
        shown(Page.StationSearch, Page.Station(KINGS_CROSS))
        // Settings over the station, then the station's Back: the search, as it was, under Settings.
        settingsOpen = true
        openStationId = null
        shown(Page.StationSearch, Page.Settings)
        settingsOpen = false
        stationSearchOpen = false
        shown()
        // A station opened on its own (a route page's stop), closed with nothing under it.
        openStationId = KINGS_CROSS
        shown(Page.Station(KINGS_CROSS))
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
        // Its walks in composition are bounded by the pages the rider opened ([Pages]), each kind once, a station (and
        // a search changing its trip's start) once for each station, not by how often they were opened.
        repeat(3) {
            settingsOpen = true
            licensesOpen = true
            hereTripOpen = true
            linesOpen = true
            openStationId = KINGS_CROSS
            pages.openStartSearch(KINGS_CROSS)
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

    private companion object {
        // Hubs, not anyone's place.
        const val KINGS_CROSS = "940GZZLUKSX"
        const val EUSTON = "940GZZLUEUS"
        const val VICTORIA = "940GZZLUVIC"
    }
}
