package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gathering the user's own stops for "Find a station", on example stop ids and public names. */
class YourStopsTest {
    private val journey = FavoriteJourney(
        from = JourneyEnd("490000000001A", "Example Road"),
        to = JourneyEnd("940GZZLUOXC", "Oxford Circus Underground Station"),
        lineId = "example",
        mode = "bus",
    )

    @Test
    fun `favorites are the journey ends, then starred places by name, cleaned and once each`() {
        val yours = YourStops.of(
            journeys = listOf(journey),
            starred = listOf(
                StationMatch("940GZZLUVIC", "Victoria Underground Station", listOf("tube")),
                StationMatch("940GZZLUBND", "Bond Street Underground Station", listOf("tube")),
                StationMatch("940GZZLUOXC", "Oxford Circus Underground Station", listOf("tube")),
            ),
            recent = emptyList(),
            known = emptyList(),
        )
        assertEquals(
            listOf("Example Road", "Oxford Circus", "Bond Street", "Victoria"),
            yours.favorites.map { it.name },
        )
    }

    @Test
    fun `a favorite opened lately is listed once, among the recent, most recent first`() {
        val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val yours = YourStops.of(listOf(journey), emptyList(), listOf(oxford, bank).map(SearchEntry::Stop), emptyList())
        assertEquals(listOf(oxford, bank), yours.recent)
        assertEquals(listOf("Example Road"), yours.favorites.map { it.name })
        // The user's own, by last use: the recent first, then the favorites not used lately.
        assertEquals(listOf("940GZZLUOXC", "940GZZLUBNK", "490000000001A"), yours.own)
    }

    @Test
    fun `a stop shown lately with no lines isn't offered, one with lines is`() {
        val stand = StationMatch("490000000002Z", "Example Stand", emptyList())
        val pole = StationMatch("490000000002S", "Example Stand", listOf("bus"))
        val yours = YourStops.of(emptyList(), emptyList(), emptyList(), known = listOf(stand, pole))
        assertEquals(listOf("490000000002S"), yours.known.map { it.id })
    }

    @Test
    fun `a stop picked with no lines isn't listed under Recent, one with lines is`() {
        val stand = StationMatch("490000000002Z", "Example Stand", emptyList())
        val pole = StationMatch("490000000002S", "Example Stand", listOf("bus"))
        val yours = YourStops.of(emptyList(), emptyList(), listOf(stand, pole).map(SearchEntry::Stop), emptyList())
        assertEquals(listOf("490000000002S"), yours.recent.map { it.id })
        assertEquals(listOf("490000000002S"), yours.recentPicks.map { (it as SearchEntry.Stop).match.id })
        assertEquals(listOf("490000000002S"), yours.homeRecent().map { it.id })
    }

    @Test
    fun `removing a pick takes it off by the key it was listed under, leaving the rest`() {
        val oxford = StationMatch("940GZZLUOXC", "Oxford Circus Underground Station", listOf("tube"))
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val hub = StationMatch("HUBKGX", "King's Cross & St Pancras International", listOf("tube", "national-rail"))
        val stPancras = StationMatch("HUBKGX", "St Pancras International", listOf("national-rail"), lead = listOf("national-rail"))
        val gallery = SearchEntry.Place(PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE))
        val stored = listOf(oxford, bank, hub, stPancras).map(SearchEntry::Stop) + gallery
        // Listed with its name cleaned, as YourStops.of shows it.
        val listedOxford = SearchEntry.Stop(oxford.copy(name = "Oxford Circus"))
        assertEquals(stored.drop(1), RecentStations.remove(stored, listedOxford))
        // An interchange's station name comes off alone, the interchange's own row staying.
        assertEquals(stored - SearchEntry.Stop(stPancras), RecentStations.remove(stored, SearchEntry.Stop(stPancras)))
        assertEquals(stored.dropLast(1), RecentStations.remove(stored, gallery))
    }

    @Test
    fun `picks being removed are left out of the recent lists alone`() {
        val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val gallery = SearchEntry.Place(PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE))
        val yours = YourStops(
            favorites = listOf(oxford),
            recent = listOf(oxford, bank),
            recentPicks = listOf(SearchEntry.Stop(oxford), gallery, SearchEntry.Stop(bank)),
        )
        val without = yours.withoutRecent(setOf(oxford.key, gallery.key))
        assertEquals(listOf(bank), without.recent)
        assertEquals(listOf<SearchEntry>(SearchEntry.Stop(bank)), without.recentPicks)
        // A starred stop isn't a recent pick: it stays.
        assertEquals(listOf(oxford), without.favorites)
        assertEquals(yours, yours.withoutRecent(emptySet()))
    }

    @Test
    fun `an open moves to the front, and the list stays capped`() {
        val stops = (1..RecentStations.MAX).map { StationMatch("49000000000$it", "Stop $it") }
        val reopened = RecentStations.add(stops, stops[3])
        assertEquals(stops[3], reopened.first())
        assertEquals(RecentStations.MAX, reopened.size)
        val added = RecentStations.add(stops, StationMatch("490000000099", "New"))
        assertEquals("490000000099", added.first().id)
        assertEquals(RecentStations.MAX, added.size)
        assertEquals(stops.dropLast(1), added.drop(1))
    }

    @Test
    fun `a place picked from To… lists under Recent in the order picked, and matches nothing typed`() {
        val oxford = StationMatch("940GZZLUOXC", "Oxford Circus Underground Station", listOf("tube"))
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        val yours = YourStops.of(
            listOf(journey), emptyList(),
            recent = listOf(SearchEntry.Stop(bank), SearchEntry.Place(gallery), SearchEntry.Stop(oxford)),
            known = emptyList(),
        )
        // Listed in the order picked, the stops' names cleaned as before.
        assertEquals(
            listOf(SearchEntry.Stop(bank), SearchEntry.Place(gallery), SearchEntry.Stop(oxford.copy(name = "Oxford Circus"))),
            yours.recentPicks,
        )
        // Only the stops match as the user types, or lead a search.
        assertEquals(listOf("940GZZLUBNK", "940GZZLUOXC"), yours.recent.map { it.id })
        assertEquals(listOf("940GZZLUBNK", "940GZZLUOXC", "490000000001A"), yours.own)
        // A stop listed as before, with no places, lists as it always did.
        assertEquals(listOf(SearchEntry.Stop(bank)), YourStops(recent = listOf(bank)).recentPicks)
    }

    @Test
    fun `a place picked again moves to the front by its name and coordinate, in the same capped list`() {
        val gallery = SearchEntry.Place(PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE))
        val stops = (1..RecentStations.MAX).map { SearchEntry.Stop(StationMatch("49000000000$it", "Stop $it")) }
        val withPlace = RecentStations.add(stops, gallery)
        assertEquals(gallery, withPlace.first())
        assertEquals(RecentStations.MAX, withPlace.size)
        val again = RecentStations.add(RecentStations.add(withPlace, stops[0]), gallery)
        assertEquals(listOf(gallery, stops[0]), again.take(2))
        assertEquals(1, again.count { it == gallery })
        // Another place of the same name elsewhere is another pick.
        val elsewhere = SearchEntry.Place(PlaceHit("Example Gallery", Coordinates(51.4, -0.1), PlaceKind.PLACE))
        assertEquals(2, RecentStations.add(again, elsewhere).count { it is SearchEntry.Place })
    }

    @Test
    fun `a journey end lists as its stop area when it has one`() {
        val areaJourney = journey.copy(from = journey.from.copy(areaId = "490G00000001"))
        val yours = YourStops.of(listOf(areaJourney), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("490G00000001", "940GZZLUOXC"), yours.favorites.map { it.id })
    }

    @Test
    fun `a stop's place is its stop area, unless the cluster is only its name`() {
        assertEquals(
            StationMatch("490G00000001", "Example Road", listOf("bus")),
            stopPlace("490000000001A", "Example Road", "490G00000001", listOf("bus", "bus", "")),
        )
        assertEquals("490000000001A", stopPlace("490000000001A", "Example Road", "Example Road", emptyList()).id)
        assertEquals("490000000001A", stopPlace("490000000001A", "Example Road", "", emptyList()).id)
    }

    @Test
    fun `an unnamed starred station is named from the bundled list, a bus stop isn't`() {
        val index = StationIndex(listOf(IndexedStation("940GZZLUBNK", "Bank", listOf("tube"))))
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val yours = YourStops(recent = listOf(bank), unnamedStarred = listOf("940GZZLUBNK", "490000000001A"))
            .namedFrom(index)
        // Bank was opened lately, so it stays among the recent rather than moving to the favorites.
        assertEquals(emptyList<StationMatch>(), yours.favorites)
        assertEquals(listOf(bank), yours.recent)
        assertEquals(emptyList<String>(), yours.unnamedStarred)
    }

    @Test
    fun `the home's Recent is the stops picked lately, less the starred and places, capped`() {
        val victoria = StationMatch("940GZZLUVIC", "Victoria", listOf("tube"))
        val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))
        val gallery = PlaceHit("Example Gallery", Coordinates(51.5, -0.12), PlaceKind.PLACE)
        val many = (1..12).map { StationMatch("HUB$it", "Station $it", listOf("tube")) }
        val yours = YourStops(
            favorites = listOf(victoria),
            recentPicks = listOf(SearchEntry.Stop(bank), SearchEntry.Place(gallery), SearchEntry.Stop(victoria)) +
                many.map(SearchEntry::Stop),
        )
        assertEquals(listOf(bank) + many.take(YourStops.HOME_ROWS - 1), yours.homeRecent())
    }

    @Test
    fun `the home's Starred is the first favorites, capped`() {
        val many = (1..12).map { StationMatch("HUB$it", "Station $it", listOf("tube")) }
        assertEquals(many.take(YourStops.HOME_ROWS), YourStops(favorites = many).homeStarred)
        assertEquals(many.take(3), YourStops(favorites = many.take(3)).homeStarred)
    }
}
