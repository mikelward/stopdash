package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.IndexedStation
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

    private val index: List<IndexedStation> =
        StationIndexStore.parse(File("src/main/assets/stations/station_index.json").readText()).stations

    private val thameslink = fetched.withStations(index.filter { it.hubId.isNotBlank() }.groupBy { it.hubId })

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
    }
}
