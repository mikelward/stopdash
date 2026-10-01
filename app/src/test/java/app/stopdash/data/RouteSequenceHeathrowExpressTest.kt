package app.stopdash.data

import app.stopdash.domain.DirectTrips
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStops
import app.stopdash.domain.StopArrivals
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Heathrow Express train from Paddington, as National Rail's board lists it, checked against a
 * recorded `/Line/heathrow-express/Route/Sequence/outbound` (trimmed to the fields stopdash reads;
 * public network data only). The board names the airport stations "(Rail Station Only)", where
 * TfL's route has "… Rail Station": both must clean to one name, or no train can be checked.
 */
class RouteSequenceHeathrowExpressTest {
    private val fixture: String =
        checkNotNull(javaClass.getResource("/fixtures/route_sequence_heathrow_express_outbound.json")).readText()

    private val hex: LineSequence =
        Json { ignoreUnknownKeys = true }.decodeFromString<TflRouteSequenceDto>(fixture).toLineSequence()

    private val board = DarwinBoardDto(
        generatedAt = "2026-10-01T06:54:00+01:00",
        trainServices = listOf(
            DarwinServiceDto(
                std = "07:10",
                etd = "On time",
                platform = "6",
                operator = "Heathrow Express",
                operatorCode = "HX",
                destination = listOf(DarwinLocationDto("Heathrow Terminal 5 (Rail Station Only)", "HWV")),
            ),
        ),
    )

    private val now = Instant.parse("2026-10-01T05:54:00Z")

    @Test
    fun `the board's train is bound for the stop TfL's route names`() {
        val train = board.toDepartures().single()
        assertEquals("heathrow-express", train.lineId)
        assertEquals("Heathrow Terminal 5", train.destination)
        val stops = RouteStops.ahead(hex, PADDINGTON, train.destination, train.branch, train.lineId)
        assertEquals(listOf(PADDINGTON, TERMINALS_2_3, TERMINAL_5), stops?.map { it.id })
    }

    @Test
    fun `a trip from Paddington to the airport keeps the train, checked`() {
        val result = DirectTrips.filter(
            listOf(StopArrivals(PADDINGTON, "London Paddington", board.toDepartures(), now)),
            listOf(DirectTrips.End(TERMINALS_2_3, "Heathrow Terminals 2 & 3")),
            mapOf("heathrow-express" to hex),
        )
        assertEquals(listOf("Heathrow Terminal 5"), result.stops.flatMap { stop -> stop.departures.map { it.destination } })
        assertFalse(result.unresolved)
        assertTrue(result.misses.isEmpty())
    }

    private companion object {
        const val PADDINGTON = "910GPADTON"
        const val TERMINALS_2_3 = "910GHTRWAPT"
        const val TERMINAL_5 = "910GHTRWTM5"
    }
}
