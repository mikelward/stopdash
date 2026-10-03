package app.stopdash.data

import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStops
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The route detail's stop list for a bus, against a recorded `/Line/24/Route/Sequence/inbound`
 * (trimmed to the fields stopdash reads; public network data only). Its arrivals' recorded
 * `destinationName` is "Pimlico", which is neither a stop on the route (the nearest reads "St
 * George's Square / Pimlico" once cleaned) nor the route's named end ("Grosvenor Road") — the
 * mismatch that left most buses without a list.
 */
class RouteSequenceBusTest {
    private val fixture: String =
        checkNotNull(javaClass.getResource("/fixtures/route_sequence_24_inbound.json")).readText()

    private val route24: LineSequence =
        Json { ignoreUnknownKeys = true }.decodeFromString<TflRouteSequenceDto>(fixture).toLineSequence()

    // Victoria Station's inbound stop, from the recorded sequence.
    private val victoria = "490000248S"

    @Test
    fun `a bus signed to a place runs from the boarding stop to its route's end`() {
        assertEquals(
            listOf(
                "Victoria", "Warwick Way", "Belgrave Road", "St George's Square/Pimlico",
                "Pimlico Academy & Library", "Alderney Street", "Winchester Street",
                "Westmoreland Terrace", "Grosvenor Road",
            ),
            RouteStops.ahead(route24, victoria, "Pimlico", null, bus = true)?.map { it.name },
        )
    }

    @Test
    fun `the same label on a non-bus row stays unmatched`() {
        assertNull(RouteStops.ahead(route24, victoria, "Pimlico", null, bus = false))
    }

    @Test
    fun `a bus short-working signed to a stop on the route still ends there`() {
        assertEquals(
            "Belgrave Road",
            RouteStops.ahead(route24, victoria, "Belgrave Road", null, bus = true)?.last()?.name,
        )
    }

    @Test
    fun `a stop's stop area is kept for placing a bus journey's way back`() {
        // Synthetic ids: two poles of one stop area, as TfL lists them under stopPointSequences.
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<TflRouteSequenceDto>(
            """{"orderedLineRoutes":[{"name":"A ↔ B","naptanIds":["490000001N","490000002N"]}],
               "stopPointSequences":[{"stopPoint":[
                 {"id":"490000001N","name":"Park","stationId":"490G1"},
                 {"id":"490000002N","name":"Hill","stationId":""}]}]}""",
        )
        assertEquals(mapOf("490000001N" to "490G1"), dto.toLineSequence().stopAreas)
    }
}
