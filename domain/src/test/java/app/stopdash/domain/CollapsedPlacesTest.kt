package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsedPlacesTest {
    @Test
    fun `a farther station becomes a collapsed place with its lines named as TfL spells them`() {
        val farther = FartherStations.Farther(
            StationMatch("940GZZLUEXA", "Example", listOf("tube")),
            1_600.0,
            listOf(FartherStations.Line("tube", "hammersmith-city"), FartherStations.Line("national-rail", "c2c")),
        )
        val place = CollapsedPlaces.of(farther, mapOf("hammersmith-city" to "Hammersmith & City"))
        assertEquals("station:940GZZLUEXA", place.key)
        assertEquals("940GZZLUEXA", place.stationId)
        assertEquals("Example", place.name)
        assertEquals(1_600.0, place.meters, 0.0)
        assertEquals(
            // A line the list has no name for keeps its id.
            listOf(LineRef("hammersmith-city", "Hammersmith & City", "tube"), LineRef("c2c", "c2c", "national-rail")),
            place.lines,
        )
    }

    @Test
    fun `a farther bus place becomes a collapsed place carrying its poles, named by the nearest`() {
        val poles = listOf(
            StopLocation("J1A", "Example Road", 0.0, 0.0, listOf(LineRef("73", "73", "bus")), clusterId = "J1"),
            StopLocation("J1B", "Example Road", 0.0, 0.0, listOf(LineRef("390", "390", "bus")), clusterId = "J1"),
        )
        val place = CollapsedPlaces.of(FartherBuses.Farther("J1", poles, 650.0, listOf(LineRef("73", "73", "bus"))))
        assertEquals("bus:J1", place.key)
        assertEquals("", place.stationId)
        assertEquals("Example Road", place.name)
        assertEquals(poles, place.stops)
    }

    @Test
    fun `bus places sit below the stations within a mile and above the farther ones`() {
        fun place(key: String, meters: Double) = CollapsedPlaces.Place(key, "", key, meters, emptyList())
        val ordered = CollapsedPlaces.ordered(
            stations = listOf(place("near", 900.0), place("edge", 1_609.0), place("far", 2_500.0)),
            buses = listOf(place("bus1", 400.0), place("bus2", 1_200.0)),
        )
        assertEquals(listOf("near", "edge", "bus1", "bus2", "far"), ordered.map { it.key })
    }

    private fun busPlace(key: String, vararg routes: String) =
        CollapsedPlaces.Place("bus:$key", "", key, 600.0, routes.map { LineRef(it, it, "bus") })

    private fun station(key: String) = CollapsedPlaces.Place("station:$key", key, key, 900.0, emptyList())

    @Test
    fun `a bus card earns its place with a route no nearer card claimed, and names every route it adds`() {
        val picked = CollapsedPlaces.withBusesPicked(
            listOf(station("S"), busPlace("J1", "73", "390"), busPlace("J2", "390"), busPlace("J3", "30", "390", "73")),
            shownLineIds = setOf("73"),
        )
        // J2 adds only 390, which J1 already offers: no card. J3 earns one with 30, and names 390 too,
        // since a tap on it would show it; 73 is on the list already, so neither names it.
        assertEquals(listOf("station:S", "bus:J1", "bus:J3"), picked.map { it.key })
        assertEquals(listOf("390"), picked[1].lines.map { it.id })
        assertEquals(listOf("30", "390"), picked[2].lines.map { it.id })
    }

    @Test
    fun `a place at a station on the list wins a tie with a nearer one, and keeps its place in the order`() {
        val nearer = busPlace("NEAR", "234", "N20")
        val atStation = busPlace("STATION", "234", "N20").copy(meters = 900.0, stationIds = setOf("940GEX"))
        val picked = CollapsedPlaces.withBusesPicked(listOf(nearer, atStation), emptySet(), shownStopIds = setOf("940GEX"))
        assertEquals(listOf("bus:STATION"), picked.map { it.key })

        // A station the screen draws no rows for (its fetch failed, or the dedupe folded its lines
        // into a nearer stop's) isn't on the list, so the nearer place wins the same tie.
        val notShown = CollapsedPlaces.withBusesPicked(listOf(nearer, atStation), emptySet(), shownStopIds = setOf("940GOTHER"))
        assertEquals(listOf("bus:NEAR"), notShown.map { it.key })

        // A nearer place that still adds a route of its own keeps its card, and stays first.
        val both = CollapsedPlaces.withBusesPicked(
            listOf(busPlace("NEAR", "234", "102"), atStation),
            emptySet(),
            shownStopIds = setOf("940GEX"),
        )
        assertEquals(listOf("bus:NEAR", "bus:STATION"), both.map { it.key })
        assertEquals(listOf("234", "102"), both[0].lines.map { it.id })
    }

    @Test
    fun `a place at a station claims its slot under the cap first`() {
        val places = (1..4).map { busPlace("J$it", "r$it") } + busPlace("S", "r9").copy(stationIds = setOf("940GEX"))
        val picked = CollapsedPlaces.withBusesPicked(places, emptySet(), shownStopIds = setOf("940GEX"))
        assertEquals(listOf("bus:J1", "bus:J2", "bus:J3", "bus:S"), picked.map { it.key })
    }

    @Test
    fun `at most four bus cards, stations uncounted`() {
        val places = listOf(station("S1")) + (1..6).map { busPlace("J$it", "r$it") } + station("S2")
        val picked = CollapsedPlaces.withBusesPicked(places, emptySet())
        assertEquals(
            listOf("station:S1", "bus:J1", "bus:J2", "bus:J3", "bus:J4", "station:S2"),
            picked.map { it.key },
        )
    }

    @Test
    fun `an opened bus card stays with its routes, counts toward the cap, and names its routes`() {
        val picked = CollapsedPlaces.withBusesPicked(
            listOf(busPlace("J1", "73"), busPlace("J2", "73", "30")),
            shownLineIds = setOf("73"),
            opened = setOf("bus:J1"),
            max = 2,
        )
        assertEquals(listOf("bus:J1", "bus:J2"), picked.map { it.key })
        assertEquals(listOf("73"), picked[0].lines.map { it.id })
        // J2's 73 is the opened card's to show, so J2 names only what it adds beyond it.
        assertEquals(listOf("30"), picked[1].lines.map { it.id })
    }

    @Test
    fun `an opened card behind four new ones still holds its slot, so the cap holds`() {
        val places = (1..4).map { busPlace("J$it", "r$it") } + busPlace("OPEN", "r9")
        val picked = CollapsedPlaces.withBusesPicked(places, emptySet(), opened = setOf("bus:OPEN"))
        assertEquals(FartherBuses.MAX_CARDS, picked.size)
        assertEquals(listOf("bus:J1", "bus:J2", "bus:J3", "bus:OPEN"), picked.map { it.key })
    }

    @Test
    fun `a nearer card doesn't repeat a route an opened farther card already shows`() {
        val picked = CollapsedPlaces.withBusesPicked(
            listOf(busPlace("J1", "73", "30"), busPlace("OPEN", "73")),
            emptySet(),
            opened = setOf("bus:OPEN"),
        )
        assertEquals(listOf("30"), picked[0].lines.map { it.id })
    }

    @Test
    fun `a tapped card still loading keeps naming only the routes it adds`() {
        val picked = CollapsedPlaces.withBusesPicked(
            listOf(busPlace("J1", "73", "390")),
            shownLineIds = setOf("73"),
            opened = setOf("bus:J1"),
        )
        assertEquals(listOf("390"), picked.single().lines.map { it.id })
    }

    @Test
    fun `a farther station's West Midlands Trains line is named as London Northwestern`() {
        // TfL's station list names the line after the parent company: the card's pill reads LNR.
        val farther = FartherStations.Farther(
            StationMatch("910GEXAMPLE", "Example", listOf("national-rail")),
            800.0,
            listOf(FartherStations.Line("national-rail", "west-midlands-trains")),
        )
        val place = CollapsedPlaces.of(farther, mapOf("west-midlands-trains" to "West Midlands Trains"))
        assertEquals(listOf(LineRef("west-midlands-trains", "London Northwestern Railway", "national-rail")), place.lines)
    }
}
