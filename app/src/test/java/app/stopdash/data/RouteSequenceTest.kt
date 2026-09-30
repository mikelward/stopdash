package app.stopdash.data

import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStops
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route detail's stop list against a recorded `/Line/northern/Route/Sequence/outbound`
 * (trimmed to the fields stopdash reads; public network data only) — the Northern line's two
 * central trunks, its Battersea extension, and its three northern termini make it the hard case.
 */
class RouteSequenceTest {
    private val fixture: String =
        checkNotNull(javaClass.getResource("/fixtures/route_sequence_northern_outbound.json")).readText()

    private val json = Json { ignoreUnknownKeys = true }
    private val northern: LineSequence = json.decodeFromString<TflRouteSequenceDto>(fixture).toLineSequence()

    private fun names(stopId: String, destination: String, branch: String?) =
        RouteStops.ahead(northern, stopId, destination, branch)?.map { it.name }

    @Test
    fun `each route keeps the direction its sequence was fetched for`() {
        val dto = json.decodeFromString<TflRouteSequenceDto>(fixture)
        // TfL echoes the direction asked for; the request's own wins, and blank or unknown falls
        // back to TfL's.
        assertEquals(setOf("outbound"), dto.toLineSequence().routes.mapTo(HashSet()) { it.direction })
        assertEquals(setOf("inbound"), dto.toLineSequence("inbound").routes.mapTo(HashSet()) { it.direction })
        assertEquals(setOf("outbound"), dto.toLineSequence("all").routes.mapTo(HashSet()) { it.direction })
    }

    @Test
    fun `a Charing Cross train from Kennington lists every stop to Edgware via Charing Cross`() {
        assertEquals(
            listOf(
                "Kennington", "Waterloo", "Embankment", "Charing Cross", "Leicester Square",
                "Tottenham Court Road", "Goodge Street", "Warren Street", "Euston", "Mornington Crescent",
                "Camden Town", "Chalk Farm", "Belsize Park", "Hampstead", "Golders Green", "Brent Cross",
                "Hendon Central", "Colindale", "Burnt Oak", "Edgware",
            ),
            names(KENNINGTON, "Edgware", "Charing X"),
        )
    }

    @Test
    fun `a Bank train from Kennington takes the Bank trunk`() {
        val stops = names(KENNINGTON, "High Barnet", "Bank")!!
        assertEquals("Kennington", stops.first())
        assertEquals("Elephant & Castle", stops[1])
        assertEquals("High Barnet", stops.last())
        assertTrue("Bank" in stops && "Waterloo" !in stops)
    }

    @Test
    fun `an unbranched train where the trunks differ ahead is not guessed`() {
        assertNull(names(KENNINGTON, "Edgware", null))
    }

    @Test
    fun `a short-working ends where the train terminates, not at the line's end`() {
        val stops = names(KENNINGTON, "Golders Green", "Charing X")!!
        assertEquals("Golders Green", stops.last())
        assertEquals(15, stops.size)
    }

    @Test
    fun `past the junction both trunks give one path, whatever the branch`() {
        val viaBank = names(CAMDEN_TOWN, "High Barnet", "Bank")
        assertEquals(viaBank, names(CAMDEN_TOWN, "High Barnet", "Charing X"))
        assertEquals("Camden Town", viaBank!!.first())
        assertEquals("Kentish Town", viaBank[1])
    }

    @Test
    fun `a branch no route carries doesn't narrow — Battersea's unlabeled routes still resolve`() {
        val stops = names(BATTERSEA, "Edgware", "Charing X")!!
        assertEquals(listOf("Battersea Power Station", "Nine Elms", "Kennington"), stops.take(3))
        assertEquals("Edgware", stops.last())
    }

    @Test
    fun `a stop off the line, or a destination behind the train, has no list`() {
        assertNull(names("940GZZLUVIC", "Edgware", null))
        assertNull(names(CAMDEN_TOWN, "Morden", "Bank"))
    }

    @Test
    fun `each stop carries its rail connections, from the station and its interchange, not buses`() {
        val stops = RouteStops.ahead(northern, KENNINGTON, "Edgware", "Charing X", lineId = "northern")!!
            .associate { it.name to it.connections.map { line -> line.id } }
        assertEquals(emptyList<String>(), stops["Kennington"])
        assertEquals(listOf("bakerloo", "jubilee", "waterloo-city"), stops["Waterloo"])
        // The Victoria line from Euston's own station, the Lioness line from its interchange; the
        // hub's buses and national-rail operators are left out.
        assertEquals(listOf("victoria", "lioness"), stops["Euston"])
        assertEquals(listOf("central", "elizabeth"), stops["Tottenham Court Road"])
        val bank = RouteStops.ahead(northern, KENNINGTON, "High Barnet", "Bank", lineId = "northern")!!
            .first { it.name == "Bank" }
        assertEquals(listOf("central", "waterloo-city", "dlr"), bank.connections.map { it.id })
        assertEquals(listOf("tube", "tube", "dlr"), bank.connections.map { it.mode })
    }

    @Test
    fun `the client reads the direction's route sequence`() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = ByteReadChannel(fixture),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val sequence = KtorTflClient(http, baseUrl = "https://tfl.example").routeSequence("northern", "outbound")
        assertEquals("/Line/northern/Route/Sequence/outbound", path)
        assertEquals(8, sequence.routes.size)
        assertEquals("Waterloo", sequence.stopNames["940GZZLUWLO"])
    }

    @Test
    fun `a route sequence waits longer than the default for TfL to answer`() = runTest {
        // An uncached National Rail line's sequence can take TfL 4–15 s to start answering, past
        // OkHttp's 10 s default, which failed the route page with "can't reach TfL".
        var timeout: Long? = null
        var requestTimeout: Long? = -1
        val engine = MockEngine { request ->
            val capability = request.getCapabilityOrNull(HttpTimeoutCapability)
            timeout = capability?.socketTimeoutMillis
            requestTimeout = capability?.requestTimeoutMillis
            respond(
                content = ByteReadChannel(fixture),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(HttpTimeout)
            install(ContentNegotiation) { json(json) }
        }
        KtorTflClient(http, baseUrl = "https://tfl.example").routeSequence("northern", "outbound")
        assertEquals(KtorTflClient.SLOW_SOCKET_TIMEOUT_MILLIS, timeout)
        // No overall cap: a bare install(HttpTimeout) has no default request timeout, so a slow start
        // followed by a slow 600 KB transfer runs until 30 s pass without data, not to a total limit.
        assertNull(requestTimeout)
    }

    @Test
    fun `a planned leg with no train to follow lists its own branch through to its terminus`() {
        // A recorded Planner answer (trimmed): Kennington to Archway, via Charing Cross, heading to
        // High Barnet. Both trunks board at Kennington and reach Archway and High Barnet, so only the
        // leg's whole path says which it rides.
        val planned = checkNotNull(javaClass.getResource("/fixtures/journey_results_kennington_to_archway.json")).readText()
        val leg = json.decodeFromString<TflJourneyResultsDto>(planned).toRoutes(Instant.parse("2026-09-26T19:00:00Z"))
            .first().rides.single()
        assertEquals(listOf("High Barnet"), leg.headings)
        val stops = (RouteStops.forLeg(northern.callingAt(KENNINGTON), leg) as RouteStops.Resolution.Found).stops.map { it.name }
        assertEquals("Kennington", stops.first())
        assertTrue("Charing Cross" in stops)
        assertTrue("Bank" !in stops)
        // On past where the rider gets off, to the train's terminus.
        assertTrue("Archway" in stops)
        assertEquals("High Barnet", stops.last())
    }

    private companion object {
        const val KENNINGTON = "940GZZLUKNG"
        const val CAMDEN_TOWN = "940GZZLUCTN"
        const val BATTERSEA = "940GZZBPSUST"
    }

    @Test
    fun `an interchange's West Midlands Trains line is named London Northwestern, and still isn't a connection`() {
        // The recorded Euston hub lists its lines under four modes, so each comes in with no mode of
        // its own: renamed all the same, and left out of the connections as a national-rail
        // operator at an interchange always is (never guessed at: Connections).
        val euston = northern.stopLines.getValue("940GZZLUEUS")
        assertEquals(
            LineRef("west-midlands-trains", "London Northwestern Railway", ""),
            euston.single { it.id == "west-midlands-trains" },
        )
        val stops = RouteStops.ahead(northern, KENNINGTON, "Edgware", "Charing X", lineId = "northern")!!
        assertEquals(listOf("victoria", "lioness"), stops.first { it.name == "Euston" }.connections.map { it.id })
    }

    @Test
    fun `a stop's West Midlands Trains line comes in named as London Northwestern`() {
        // A route page's connection pill for the line then reads LNR, as its departures do.
        val stop = TflMatchedStopDto(
            id = "910GEXAMPLE",
            modes = listOf("national-rail"),
            lines = listOf(TflLineIdentifierDto("west-midlands-trains", "West Midlands Trains")),
        )
        assertEquals("London Northwestern Railway", stop.lineRefs().single().name)
    }
}
