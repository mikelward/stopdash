package app.stopdash.data

import app.stopdash.domain.BundledStops
import app.stopdash.domain.Hubs
import app.stopdash.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A train's calls ahead of it, against a recorded (trimmed) `/Vehicle/{id}/Arrivals`: public network data only. */
class VehicleCallsTest {
    private val fixture = checkNotNull(javaClass.getResource("/fixtures/vehicle_arrivals_jubilee.json")).readText()

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
        hubs: Hubs? = null,
    ): KtorTflClient {
        val engine = MockEngine { request ->
            capture(request)
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, appKey = { "EXAMPLE" }, hubs = { hubs })
    }

    @Test
    fun `asks for one vehicle's arrivals`() = runTest {
        var captured: HttpRequestData? = null
        client(fixture, capture = { captured = it }).vehicleCalls("162", "jubilee")
        assertEquals("/Vehicle/162/Arrivals", checkNotNull(captured).url.encodedPath)
    }

    @Test
    fun `reads the stops a train will call at, soonest first`() = runTest {
        val calls = client(fixture).vehicleCalls("162", "jubilee")
        assertEquals(14, calls.size)
        assertEquals("940GZZLUCWR", calls.first().stopId)
        assertEquals("Canada Water", calls.first().stopName)
        assertEquals("Westbound - Platform 1", calls.first().platform)
        assertEquals(Instant.parse("2026-09-26T21:07:31Z"), calls.first().expected)
        // The prediction window ends at Kilburn though the train runs on to Stanmore: its last call
        // isn't the train's last stop.
        assertEquals("Kilburn", calls.last().stopName)
        assertTrue(calls.zipWithNext().all { (a, b) -> !b.expected.isBefore(a.expected) })
    }

    @Test
    fun `each call's stop is placed by the station model as the calls come in`() = runTest {
        val dir = listOf(File("src/main/assets/stations"), File("app/src/main/assets/stations")).first { it.exists() }
        val model = checkNotNull(
            Hubs.of(StationIndexStore.parse(File(dir, "station_index.json").readText()), File(dir, "stops.tsv").bufferedReader().use { BundledStops.read(it) }),
        )
        val calls = client(fixture, hubs = model).vehicleCalls("162", "jubilee")
        assertTrue(calls.all { it.place == model.resolve(it.stopId) })
        // Canada Water's tube station, in its hub.
        assertNotNull(calls.first().place?.station)
        assertNotNull(calls.first().place?.hub)
        // No model: unplaced.
        assertTrue(client(fixture).vehicleCalls("162", "jubilee").all { it.place == null })
    }

    @Test
    fun `keeps only the asked line's train, in time order whatever TfL's order`() = runTest {
        // Vehicle ids are only unique within a line: another line's train with the same id is not this one.
        val body = """
            [
              {"lineId": "jubilee", "naptanId": "B", "stationName": "Bermondsey Underground Station", "expectedArrival": "2026-09-26T21:09:00Z", "vehicleId": "162"},
              {"lineId": "northern", "naptanId": "X", "stationName": "Elsewhere", "expectedArrival": "2026-09-26T21:05:00Z", "vehicleId": "162"},
              {"lineId": "jubilee", "naptanId": "A", "stationName": "Canada Water Underground Station", "expectedArrival": "2026-09-26T21:07:00Z", "vehicleId": "162"}
            ]
        """.trimIndent()
        assertEquals(listOf("A", "B"), client(body).vehicleCalls("162", "jubilee").map { it.stopId })
    }

    @Test
    fun `a rate-limited lookup is the honest rate-limited state`() {
        assertThrows(TflException.RateLimited::class.java) {
            kotlinx.coroutines.runBlocking { client("[]", status = HttpStatusCode.TooManyRequests).vehicleCalls("162", "jubilee") }
        }
    }

    @Test
    fun `an arrival carries its train, and TfL's all-zero placeholder is no train`() {
        val base = TflArrivalDto(lineId = "jubilee", lineName = "Jubilee", expectedArrival = "2026-09-26T21:07:00Z")
        assertEquals("162", base.copy(vehicleId = "162").toDeparture().vehicleId)
        assertEquals("", base.copy(vehicleId = "000").toDeparture().vehicleId)
        assertEquals("", base.toDeparture().vehicleId)
        assertEquals("LTZ1001", base.copy(vehicleId = " LTZ1001 ").toDeparture().vehicleId)
    }
}
