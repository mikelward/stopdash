package app.stopdash.data

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Following a Jubilee line train westbound, against its recorded (trimmed) `/Vehicle/{id}/Arrivals`. */
class OnTheWayFixtureTest {
    private val calls = Json { ignoreUnknownKeys = true }
        .decodeFromString<List<TflArrivalDto>>(checkNotNull(javaClass.getResource("/fixtures/vehicle_arrivals_jubilee.json")).readText())
        .map { it.toVehicleCall() }
        .sortedBy { it.expected }

    // Recorded at about 21:06 UTC, the train between Canary Wharf and Canada Water.
    private val now = Instant.parse("2026-09-26T21:06:30Z")

    private fun leg(fromId: String, fromName: String) = TripLeg(
        "tube", "jubilee", "Jubilee", fromId, fromName, "940GZZLUWLO", "Waterloo",
        now, now.plusSeconds(900), headings = listOf("Stanmore"),
    )

    @Test
    fun `a rider at Canada Water waits for the train still due there`() {
        val trip = ActiveTrip(TripRoute(listOf(leg("940GZZLUCWR", "Canada Water"))), "Waterloo", now, vehicleId = "162")
        val (next, progress) = OnTheWay.advance(trip, calls, now)
        assertEquals(TripProgress.Waiting(trip.leg!!, Instant.parse("2026-09-26T21:07:31Z")), progress)
        assertFalse(next.boarded)
    }

    @Test
    fun `a rider who boarded at Canary Wharf is on it, five stops from Waterloo`() {
        val trip = ActiveTrip(TripRoute(listOf(leg("940GZZLUCYF", "Canary Wharf"))), "Waterloo", now, vehicleId = "162")
        val progress = OnTheWay.advance(trip, calls, now).second as TripProgress.Riding
        assertEquals("Canada Water", progress.nextStop)
        assertEquals(5, progress.stopsLeft)
        assertEquals(Instant.parse(calls.first { it.stopId == "940GZZLUWLO" }.expected.toString()), progress.getOffAt)
        assertFalse(progress.getOffSoon)
    }
}
