package app.stopdash.data

import app.stopdash.domain.DirectTrips
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStops
import app.stopdash.domain.RouteStops.Bound
import app.stopdash.domain.StopArrivals
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trains whose path the route can't pick from their destination alone, against recorded
 * `/Line/{id}/Route/Sequence/{inbound,outbound}` responses (trimmed to the fields stopdash reads;
 * public network data only) and recorded arrivals' platform, direction and destination fields.
 *
 * The Circle line is a loop ending at Edgware Road, so from King's Cross or Embankment a train "to
 * Edgware Road" may go either way round; the platform's compass ("Eastbound") picks, where TfL's
 * `direction` doesn't (a King's Cross train marked inbound stood on the eastbound platform, the
 * outbound route's way round). A District train TfL shows as "Check Front of Train" names no
 * place: it counts where every way it may run from its platform agrees.
 */
class RouteSequenceLoopTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sequence(line: String): LineSequence =
        json.decodeFromString<TflRouteSequenceDto>(
            checkNotNull(javaClass.getResource("/fixtures/route_sequence_$line.json")).readText(),
        ).toLineSequence()

    private val circle = sequence("circle")
    private val district = sequence("district")

    @Test
    fun `a Circle train to Edgware Road goes the way its platform faces`() {
        // Without the platform, both ways round reach Edgware Road: no single list.
        assertEquals(RouteStops.Resolution.Ambiguous(2), RouteStops.resolve(circle, KINGS_CROSS, "Edgware Road", null, "circle"))
        // Eastbound from King's Cross: the long way, by Farringdon.
        val east = RouteStops.ahead(circle, KINGS_CROSS, "Edgware Road", null, "circle", bound = Bound.EAST)!!
        assertEquals(FARRINGDON, east[1].id)
        assertEquals(EDGWARE_ROAD, east.last().id)
        // Westbound: the short way, by Euston Square.
        val west = RouteStops.ahead(circle, KINGS_CROSS, "Edgware Road", null, "circle", bound = Bound.WEST)!!
        assertEquals(EUSTON_SQUARE, west[1].id)
        assertEquals(5, west.size)
        // At Embankment, westbound runs on by Westminster.
        assertEquals(WESTMINSTER, RouteStops.ahead(circle, EMBANKMENT, "Edgware Road", null, "circle", bound = Bound.WEST)!![1].id)
    }

    @Test
    fun `the recorded platforms read as their compass`() {
        assertEquals(Bound.EAST, RouteStops.boundOf("Eastbound - Platform 2"))
        assertEquals(Bound.WEST, RouteStops.boundOf("Westbound - Platform 1"))
        assertNull(RouteStops.boundOf(null))
        assertNull(RouteStops.boundOf("Platform 3"))
    }

    @Test
    fun `a Check Front of Train shows as TfL words it but matches no place`() {
        val arrival = TflArrivalDto(
            lineId = "district",
            lineName = "District",
            platformName = "Westbound - Platform 1",
            towards = "Check Front of Train",
            expectedArrival = "2026-09-26T21:46:00Z",
            modeName = "tube",
        ).toDeparture()
        // The list shows what the platform board shows.
        assertEquals("Check Front of Train", arrival.destination)
        assertTrue(RouteStops.isUnknownDestination(arrival.destination))
        assertEquals(RouteStops.Resolution.NoDestination, RouteStops.resolve(district, EMBANKMENT, arrival.destination, null, "district"))
    }

    @Test
    fun `a train with no destination yet counts where every way it may run agrees`() {
        val west = Bound.WEST
        // Every westbound District train from Embankment passes Earl's Court, whichever branch.
        assertEquals(true, RouteStops.reaches(district, EMBANKMENT, CHECK, null, setOf(EARLS_COURT), bound = west))
        // Only some go to Wimbledon: unknown, so still flagged.
        assertNull(RouteStops.reaches(district, EMBANKMENT, CHECK, null, setOf(WIMBLEDON), bound = west))
        // None goes back east to Temple.
        assertEquals(false, RouteStops.reaches(district, EMBANKMENT, CHECK, null, setOf(TEMPLE), bound = west))
        // Without its platform, it may go either way: unknown.
        assertNull(RouteStops.reaches(district, EMBANKMENT, CHECK, null, setOf(EARLS_COURT)))
    }

    @Test
    fun `a To page keeps a Check Front of Train it can vouch for and says so of one it can't`() {
        val now = Instant.parse("2026-09-26T21:40:00Z")
        val train = TflArrivalDto(
            lineId = "district", lineName = "District", platformName = "Westbound - Platform 1",
            towards = "Check Front of Train", expectedArrival = "2026-09-26T21:46:00Z", modeName = "tube",
        ).toDeparture()
        val stop = StopArrivals(EMBANKMENT, "Embankment", listOf(train), now)
        val toEarlsCourt = DirectTrips.filter(listOf(stop), listOf(DirectTrips.End(EARLS_COURT, "Earl's Court")), mapOf("district" to district))
        assertEquals(1, toEarlsCourt.stops.single().departures.size)
        assertFalse(toEarlsCourt.unresolved)
        val toWimbledon = DirectTrips.filter(listOf(stop), listOf(DirectTrips.End(WIMBLEDON, "Wimbledon")), mapOf("district" to district))
        assertTrue(toWimbledon.unresolved)
        assertEquals(RouteStops.Resolution.NoDestination, toWimbledon.misses.single().reason)
    }

    private companion object {
        const val CHECK = "Check Front of Train"
        const val KINGS_CROSS = "940GZZLUKSX"
        const val EMBANKMENT = "940GZZLUEMB"
        const val EDGWARE_ROAD = "940GZZLUERC"
        const val FARRINGDON = "940GZZLUFCN"
        const val EUSTON_SQUARE = "940GZZLUESQ"
        const val WESTMINSTER = "940GZZLUWSM"
        const val EARLS_COURT = "940GZZLUECT"
        const val WIMBLEDON = "940GZZLUWIM"
        const val TEMPLE = "940GZZLUTMP"
    }
}
