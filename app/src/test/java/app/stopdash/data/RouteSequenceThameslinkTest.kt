package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.IndexedStation
import app.stopdash.domain.BundledStops
import app.stopdash.domain.Hubs
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.JourneySegment
import app.stopdash.domain.Journeys
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStops
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StopArrivals
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route detail's stop list for Thameslink, against a recorded
 * `/Line/thameslink/Route/Sequence/inbound` (three of its routes, trimmed to the fields stopdash
 * reads; public network data only) and the bundled station index. TfL lists St Pancras's
 * Thameslink departures under the station's domestic-platforms id, which no route calls at: the
 * sequence calls at the low-level (`LL`) id instead, and King's Cross shares the interchange. The
 * index is what knows the domestic id's interchange, whatever row or journey names it.
 */
class RouteSequenceThameslinkTest {
    private val fixture: String =
        checkNotNull(javaClass.getResource("/fixtures/route_sequence_thameslink_inbound.json")).readText()

    private val fetched: LineSequence =
        Json { ignoreUnknownKeys = true }.decodeFromString<TflRouteSequenceDto>(fixture).toLineSequence()

    // Two of `/Line/thameslink/Route/Sequence/outbound`'s routes, trimmed the same way: southbound,
    // TfL routes the low-level platforms under St Pancras's main id.
    private val southbound: LineSequence =
        Json { ignoreUnknownKeys = true }.decodeFromString<TflRouteSequenceDto>(
            checkNotNull(javaClass.getResource("/fixtures/route_sequence_thameslink_outbound.json")).readText(),
        ).toLineSequence()

    private val index: List<IndexedStation> =
        StationIndexStore.parse(File("src/main/assets/stations/station_index.json").readText()).stations

    // The station model from the real bundle, which places a line's stations as the repository does.
    private val hubs: Hubs by lazy {
        val stops = File("src/main/assets/stations/stops.tsv").bufferedReader().use { BundledStops.read(it) }
        Hubs.of(StationIndexStore.parse(File("src/main/assets/stations/station_index.json").readText()), stops)
            ?: error("the bundled files build no station model")
    }

    private fun LineSequence.placed() = withModelStations { id -> (hubs.stationOf(id) as? Hubs.Membership.Of)?.id }

    private val thameslink by lazy { fetched.withStations(index.filter { it.interchangeId.isNotBlank() }.groupBy { it.interchangeId }).placed() }

    private val bothWays by lazy {
        (fetched + southbound).withStations(index.filter { it.interchangeId.isNotBlank() }.groupBy { it.interchangeId }).placed()
    }

    private val now = Instant.parse("2026-09-23T08:00:00Z")

    @Test
    fun `a train listed under the station's other id boards there, in place of its sibling`() {
        val stops = RouteStops.ahead(thameslink.callingAt(ST_PANCRAS_DOMESTIC), ST_PANCRAS_DOMESTIC, "Cambridge", null, "thameslink")!!
        assertEquals(ST_PANCRAS_DOMESTIC, stops.first().id)
        assertEquals("London St Pancras International", stops.first().name)
        assertEquals("Finsbury Park", stops[1].name)
        assertEquals("Cambridge", stops.last().name)
    }

    @Test
    fun `a train its board names by another spelling ends where its station code says`() {
        // National Rail's board calls the terminus "St Albans"; TfL's route calls it "St Albans City".
        val sequence = thameslink.callingAt(ST_PANCRAS_DOMESTIC)
        assertEquals(RouteStops.Resolution.NoMatch, RouteStops.resolve(sequence, ST_PANCRAS_DOMESTIC, "St Albans", null, "thameslink"))
        val stops = RouteStops.ahead(sequence, ST_PANCRAS_DOMESTIC, "St Albans", null, "thameslink", destinationId = ST_ALBANS_CITY)!!
        assertEquals(ST_PANCRAS_DOMESTIC, stops.first().id)
        assertEquals("Kentish Town", stops[1].name)
        assertEquals(ST_ALBANS_CITY, stops.last().id)
        assertEquals("St Albans City", stops.last().name)
    }

    @Test
    fun `another station in the interchange is not a sibling`() {
        // King's Cross is in the same hub, and its own route also reaches Cambridge: counting it
        // would leave two paths. On a route itself, it is left as it is.
        assertSame(thameslink, thameslink.callingAt(KINGS_CROSS))
        assertEquals(KINGS_CROSS, RouteStops.ahead(thameslink, KINGS_CROSS, "Cambridge", null)?.first()?.id)
    }

    @Test
    fun `without the index the stop stays off the route`() {
        assertSame(fetched, fetched.callingAt(ST_PANCRAS_DOMESTIC))
        assertEquals(
            RouteStops.Resolution.NotOnRoute,
            RouteStops.resolve(fetched.callingAt(ST_PANCRAS_DOMESTIC), ST_PANCRAS_DOMESTIC, "Cambridge", null),
        )
    }

    @Test
    fun `a journey starred from the station's other id finds its trains`() {
        val departure = Departure(
            lineId = "thameslink",
            lineName = "Thameslink",
            direction = "inbound",
            destination = "Cambridge",
            platform = null,
            expectedArrival = now.plusSeconds(300),
            mode = "national-rail",
        )
        // A row that knows nothing of its interchange (a watched stop, a journey card's origin).
        val rows = DepartureRows.across(
            listOf(StopArrivals(ST_PANCRAS_DOMESTIC, "London St Pancras International", listOf(departure), fetchedAt = now)),
            now,
        )
        val journey = FavoriteJourney(
            JourneyEnd(ST_PANCRAS_DOMESTIC, "London St Pancras International"),
            JourneyEnd(FINSBURY_PARK, "Finsbury Park"),
            "thameslink",
            "Thameslink",
            "national-rail",
        )
        val segment = Journeys.segment(journey, thameslink)
        assertEquals(JourneySegment(ST_PANCRAS_DOMESTIC, setOf(FINSBURY_PARK)), segment)
        val trains = Journeys.trains(segment!!, rows, mapOf("thameslink" to thameslink), journey)
        assertEquals(listOf("Cambridge"), trains.rows.flatMap { row -> row.upcoming.map { it.destination } })
        assertFalse(trains.unresolved)
    }

    @Test
    fun `the repository hands out sequences that know the index`() = runTest {
        val source = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = fetched
        }
        val repository = RouteStopsRepository(source, io = StandardTestDispatcher(testScheduler), compute = StandardTestDispatcher(testScheduler), stations = { index })
        // Nothing to hand out before the index is read, so a first frame never resolves without it.
        assertNull(repository.cached("thameslink", "inbound"))
        val loaded = repository.load("thameslink", "inbound")
        assertEquals(HUB, loaded.stopHubs[ST_PANCRAS_DOMESTIC])
        assertEquals(HUB, repository.cached("thameslink", "inbound")?.stopHubs?.get(ST_PANCRAS_DOMESTIC))
    }

    @Test
    fun `the repository names a station's interchange once the index is read`() = runTest {
        val source = object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = fetched
        }
        val repository = RouteStopsRepository(source, io = StandardTestDispatcher(testScheduler), compute = StandardTestDispatcher(testScheduler), stations = { index })
        assertNull(repository.hubOf(ST_PANCRAS_DOMESTIC))
        repository.warm()
        assertEquals(HUB, repository.hubOf(ST_PANCRAS_DOMESTIC))
        // A stop the index doesn't list (a bus stop) has none.
        assertNull(repository.hubOf("490G00000000"))
    }

    @Test
    fun `a train either way from either of the station's ids is matched to its route`() {
        // Southbound from the low level, as its board lists them (maintainer's report, 2026-10-15):
        // only the main id's routes run south.
        val brighton = RouteStops.ahead(bothWays, ST_PANCRAS_LL, "Brighton", null, "thameslink", destinationId = BRIGHTON)!!
        assertEquals(ST_PANCRAS_LL, brighton.first().id)
        assertEquals("Farringdon", brighton[1].name)
        assertEquals(BRIGHTON, brighton.last().id)
        val sutton = RouteStops.ahead(bothWays, ST_PANCRAS_LL, "Sutton (London)", null, "thameslink", destinationId = SUTTON)!!
        assertEquals(SUTTON, sutton.last().id)
        // Northbound from the main id: only the low level's routes run north to Cambridge.
        val cambridge = RouteStops.ahead(bothWays, ST_PANCRAS, "Cambridge", null, "thameslink")!!
        assertEquals(ST_PANCRAS, cambridge.first().id)
        assertEquals("Finsbury Park", cambridge[1].name)
        // Either way, from the domestic id that names neither, as before.
        assertEquals("Farringdon", RouteStops.ahead(bothWays.callingAt(ST_PANCRAS_DOMESTIC), ST_PANCRAS_DOMESTIC, "Brighton", null, destinationId = BRIGHTON)?.get(1)?.name)
    }

    @Test
    fun `a southbound train from the low level counts as going where its route does`() {
        val departure = Departure(
            lineId = "thameslink",
            lineName = "Thameslink",
            direction = "",
            destination = "Brighton",
            platform = null,
            expectedArrival = now.plusSeconds(300),
            mode = "national-rail",
            destinationId = BRIGHTON,
        )
        assertEquals(DirectTrips.Verdict.Reaches, DirectTrips.judge(departure, ST_PANCRAS_LL, bothWays, setOf(LONDON_BRIDGE)))
        assertEquals(DirectTrips.Verdict.Misses, DirectTrips.judge(departure, ST_PANCRAS_LL, bothWays, setOf(FINSBURY_PARK)))
    }

    @Test
    fun `a northbound train gets a trip to St Pancras's main id off there, by its low level`() {
        val bedford = Departure(
            lineId = "thameslink",
            lineName = "Thameslink",
            direction = "",
            destination = "Bedford",
            platform = null,
            expectedArrival = now.plusSeconds(300),
            mode = "national-rail",
        )
        val toStPancras = DirectTrips.routeAt(bothWays, FARRINGDON, "", "Farringdon", listOf(DirectTrips.End(ST_PANCRAS, "London St Pancras International", HUB)))
        assertEquals(DirectTrips.Verdict.Reaches, DirectTrips.judge(bedford, FARRINGDON, toStPancras, setOf(ST_PANCRAS)))
        // King's Cross is the interchange's other station, never St Pancras's.
        assertFalse(bothWays.sameStationId(KINGS_CROSS, ST_PANCRAS))
        assertTrue(bothWays.sameStationId(ST_PANCRAS_LL, ST_PANCRAS))
        val toKingsCross = DirectTrips.routeAt(bothWays, FARRINGDON, "", "Farringdon", listOf(DirectTrips.End(KINGS_CROSS, "London King's Cross", HUB)))
        assertEquals(DirectTrips.Verdict.Misses, DirectTrips.judge(bedford, FARRINGDON, toKingsCross, setOf(KINGS_CROSS)))
    }

    @Test
    fun `the sequence carries no station code of TfL's, the model placing its stations`() {
        // TfL's own `icsId` is left unread (maintainer, 2026-10-19: one source).
        assertTrue(fetched.stationCodes.isEmpty())
        assertTrue(southbound.stationCodes.isEmpty())
        assertTrue(bothWays.sameStationId(ST_PANCRAS, ST_PANCRAS_LL))
        assertFalse(bothWays.sameStationId(ST_PANCRAS, KINGS_CROSS))
    }

    @Test
    fun `the sequence carries each stop's interchange`() {
        assertEquals(HUB, fetched.stopHubs[ST_PANCRAS_LL])
        assertEquals(HUB, fetched.stopHubs[KINGS_CROSS])
    }

    private companion object {
        const val HUB = "HUBKGX"
        const val ST_PANCRAS_DOMESTIC = "910GSTPADOM"
        const val ST_PANCRAS_LL = "910GSTPXBOX"
        const val KINGS_CROSS = "910GKNGX"
        const val FINSBURY_PARK = "910GFNPK"
        const val ST_ALBANS_CITY = "910GSTALBCY"
        const val ST_PANCRAS = "910GSTPX"
        const val BRIGHTON = "910GBRGHTN"
        const val SUTTON = "910GSUTTON"
        const val LONDON_BRIDGE = "910GLNDNBDC"
        const val FARRINGDON = "910GFRNDNLT"
        const val STATION_CODE = "1001276"
    }
}
