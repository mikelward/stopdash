package app.stopdash.data

import app.stopdash.domain.BundledStops
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FinalStop
import app.stopdash.domain.Hubs
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripFare
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.TripTime
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.journeys
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import app.stopdash.domain.TripRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against a recorded Journey Planner answer between two well-known stations. */
class JourneyPlannerTest {
    private val fixture = checkNotNull(javaClass.getResource("/fixtures/journey_results_highbury_to_canary_wharf.json")).readText()

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
        warn: (String) -> Unit = {},
    ): KtorTflClient {
        val engine = MockEngine { request ->
            capture(request)
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, appKey = { "EXAMPLE" }, warn = warn)
    }

    @Test
    fun `asks the Planner between two stop ids`() = runTest {
        var captured: HttpRequestData? = null
        client(fixture, capture = { captured = it }).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
        val url = checkNotNull(captured).url
        assertEquals("/Journey/JourneyResults/910GHGHI/to/940GZZLUCYF", url.encodedPath)
        assertEquals("EXAMPLE", url.parameters["app_key"])
        assertEquals("20", url.parameters["maxWalkingMinutes"])
        // The Planner's own average unless the rider chose otherwise.
        assertEquals("Average", url.parameters["walkingSpeed"])
    }

    @Test
    fun `asks the Planner to time walks at the rider's speed`() = runTest {
        var captured: HttpRequestData? = null
        client(fixture, capture = { captured = it })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), WalkingSpeed.FAST)
        assertEquals("Fast", checkNotNull(captured).url.parameters["walkingSpeed"])
    }

    @Test
    fun `names the modes, walking among them, so the Planner applies the walking speed`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), WalkingSpeed.SLOW)
        // Every request names walking, the one for buses alone included.
        assertTrue(requests.all { "walking" in checkNotNull(it.url.parameters["mode"]).split(",") })
        val modes = checkNotNull(requests.first { it.quickest() }.url.parameters["mode"]).split(",")
        // Left to its default modes, the Planner times every walk at its average whatever the speed.
        assertTrue("walking" in modes)
        // The Planner's own default set, so naming them adds and drops no route: every mode a trip rides...
        assertTrue(modes.containsAll(listOf("tube", "bus", "dlr", "overground", "elizabeth-line", "national-rail", "tram", "river-bus", "cable-car", "coach", "replacement-bus")))
        // ...and none it leaves out by default.
        assertTrue(modes.none { it in setOf("river-tour", "international-rail", "cycle", "cycle-hire", "taxi") })
    }

    @Test
    fun `asks the Planner to a place by its coordinate`() = runTest {
        var captured: HttpRequestData? = null
        // A favorite (or resolved postcode) at a coordinate — synthetic, no real place (SPEC *Privacy*).
        client(fixture, capture = { captured = it })
            .journeys("910GHGHI", TripDestination.Place(Coordinates(51.5, -0.12), "X1 9XX"))
        val url = checkNotNull(captured).url
        assertEquals("/Journey/JourneyResults/910GHGHI/to/51.5,-0.12", url.encodedPath)
    }

    @Test
    fun `asks the Planner from the rider's position`() = runTest {
        var captured: HttpRequestData? = null
        // Synthetic position, no real place (SPEC *Privacy*).
        client(fixture, capture = { captured = it })
            .journeys(TripOrigin.Here(Coordinates(51.5, -0.12)), TripDestination.Stop("940GZZLUCYF"))
        assertEquals("/Journey/JourneyResults/51.5,-0.12/to/940GZZLUCYF", checkNotNull(captured).url.encodedPath)
    }

    @Test
    fun `a walk from the rider's position leaves its start unnamed, a walk from a stop keeps it`() = runTest {
        // Constructed: TfL echoes the coordinate as the start's name. Synthetic values — no real place.
        val body =
            """
            { "journeys": [ { "legs": [ {
              "departureTime": "2026-09-27T09:00:00", "arrivalTime": "2026-09-27T09:06:00",
              "mode": { "id": "walking", "name": "walking" },
              "departurePoint": { "commonName": "51.5, -0.12", "lat": 51.5, "lon": -0.12 },
              "arrivalPoint": { "naptanId": "940GZZLUKSX", "commonName": "King's Cross" }
            } ] } ] }
            """.trimIndent()
        val here = client(body).journeys(TripOrigin.Here(Coordinates(51.5, -0.12)), TripDestination.Stop("940GZZLUKSX")).single().legs.first()
        assertEquals("", here.fromName)
        assertNull(here.fromAt)
        assertEquals("King's Cross", here.toName)
        val stopBody = body.replace("\"commonName\": \"51.5, -0.12\"", "\"naptanId\": \"940GZZLUACY\", \"commonName\": \"Archway\"")
        val fromStop = client(stopBody).journeys(TripOrigin.Here(Coordinates(51.5, -0.12)), TripDestination.Stop("940GZZLUKSX")).single().legs.first()
        assertEquals("Archway", fromStop.fromName)
    }

    @Test
    fun `reads a walk to a place at a coordinate as the trip's end`() = runTest {
        // Constructed: the final leg walks from a stop to a coordinate (no naptanId), as TfL routes to a
        // place. Synthetic coordinate/name — no real place.
        val body =
            """
            { "journeys": [ { "legs": [ {
              "departureTime": "2026-09-27T09:00:00", "arrivalTime": "2026-09-27T09:06:00",
              "mode": { "id": "walking", "name": "walking" },
              "departurePoint": { "naptanId": "940GZZLUKSX", "commonName": "King's Cross" },
              "arrivalPoint": { "commonName": "X1 9XX", "lat": 51.5, "lon": -0.12 }
            } ] } ] }
            """.trimIndent()
        val walk = client(body).journeys("940GZZLUKSX", TripDestination.Place(Coordinates(51.5, -0.12), "X1 9XX"))
            .single().legs.single()
        assertTrue(walk.isWalk)
        assertEquals("X1 9XX", walk.toName)
        assertEquals("", walk.toId) // a coordinate has no stop id to fetch arrivals at
    }

    @Test
    fun `names the final walk leg for the place, not TfL's label for the coordinate`() = runTest {
        // TfL labels a coordinate arrival with whatever sits at the point (here just the coordinate
        // echoed back); the leg should read with the name the rider picked. Synthetic values — no real place.
        val body =
            """
            { "journeys": [ { "legs": [ {
              "departureTime": "2026-09-27T09:00:00", "arrivalTime": "2026-09-27T09:06:00",
              "mode": { "id": "walking", "name": "walking" },
              "departurePoint": { "naptanId": "940GZZLUKSX", "commonName": "King's Cross" },
              "arrivalPoint": { "commonName": "51.5,-0.12", "lat": 51.5, "lon": -0.12 }
            } ] } ] }
            """.trimIndent()
        val walk = client(body).journeys("940GZZLUKSX", TripDestination.Place(Coordinates(51.5, -0.12), "Home"))
            .single().legs.single()
        assertEquals("Home", walk.toName)
        assertEquals("", walk.toId)
    }

    @Test
    fun `reads each route's legs, lines, ends, times and change`() = runTest {
        val routes = client(fixture).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
        assertEquals(3, routes.size)
        val first = routes[0]
        assertEquals(listOf("mildmay", "jubilee"), first.rides.map { it.lineId })
        val mildmay = first.legs[0]
        assertEquals("overground", mildmay.mode)
        assertEquals("Mildmay", mildmay.lineName)
        assertEquals("910GHGHI", mildmay.fromId)
        assertEquals("910GSTFD", mildmay.toId)
        // London wall-clock, British Summer Time in September.
        assertEquals(Instant.parse("2026-09-26T06:37:00Z"), mildmay.departure)
        assertEquals(Duration.ofMinutes(16), mildmay.run)
        assertEquals(6, mildmay.stops)
        assertEquals("910GSTFD", mildmay.path.last())
        assertEquals(Duration.ofMinutes(6), mildmay.changeAfter)
        // The terminus the service runs to, cleaned as a stop name.
        assertEquals(listOf("Stanmore"), first.legs[1].headings)
    }

    @Test
    fun `reads a walk at the end of a route`() = runTest {
        val route = client(fixture).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))[2]
        assertEquals(listOf("windrush", "elizabeth"), route.rides.map { it.lineId })
        val walk = route.legs.last()
        assertTrue(walk.isWalk)
        assertEquals("", walk.lineId)
        assertEquals(Duration.ofMinutes(5), walk.run)
        // The change time TfL puts on a walk to the destination is not a change.
        assertEquals(Duration.ZERO, walk.changeAfter)
    }

    @Test
    fun `a walk to a station's entrance goes by the station, not its street`() = runTest {
        val fixture = checkNotNull(javaClass.getResource("/fixtures/journey_results_archway_to_cannon_street.json")).readText()
        // The Planner names it "Cannon Street, Cannon Street Rail Station".
        val walk = client(fixture).journeys("940GZZLUACY", TripDestination.Stop("910GCANONST"))[1].legs.last()
        assertTrue(walk.isWalk)
        assertEquals("Cannon Street", walk.toName)
    }

    @Test
    fun `a leg keeps its stops' names alongside their ids, cleaned`() = runTest {
        val fixture = checkNotNull(javaClass.getResource("/fixtures/journey_results_archway_to_cannon_street.json")).readText()
        val ride = client(fixture).journeys("940GZZLUACY", TripDestination.Stop("910GCANONST")).first().legs.first()
        assertEquals(ride.path.size, ride.pathNames.size)
        assertEquals("940GZZLUTFP", ride.path.first())
        assertEquals("Tufnell Park", ride.pathNames.first())
    }

    @Test
    fun `a leg keeps where the Planner places its boarding stop, and none when it gives none`() = runTest {
        val fixture = checkNotNull(javaClass.getResource("/fixtures/journey_results_archway_to_cannon_street.json")).readText()
        val journeys = client(fixture).journeys("940GZZLUACY", TripDestination.Stop("910GCANONST"))
        assertEquals(app.stopdash.domain.Coordinates(51.564624624535, -0.134969428047), journeys[0].legs.first().fromAt)
        assertEquals(null, journeys[1].legs.first().fromAt)
    }

    @Test
    fun `a leg keeps where the Planner places the stop it gets off at, and none when it gives none`() {
        // Synthetic positions: the Planner places both ends of this ride.
        fun ride(end: TflJourneyPointDto) = TflJourneyLegDto(
            departureTime = "2026-09-26T08:05:00", arrivalTime = "2026-09-26T08:15:00",
            departurePoint = TflJourneyPointDto(naptanId = "940GZZLUAAA", commonName = "A", lat = 51.5, lon = -0.12),
            arrivalPoint = end,
            routeOptions = listOf(TflJourneyRouteOptionDto(TflJourneyIdentifierDto("red", "Red"))),
            mode = TflJourneyIdentifierDto("tube"),
        ).toLegOrNull(Instant.parse("2026-09-26T07:00:00Z"))
        val placed = ride(TflJourneyPointDto(naptanId = "940GZZLUCCC", commonName = "C", lat = 51.53, lon = -0.12))
        assertEquals(Coordinates(51.53, -0.12), placed?.toAt)
        val unplaced = ride(TflJourneyPointDto(naptanId = "940GZZLUCCC", commonName = "C"))
        assertEquals("940GZZLUCCC", unplaced?.toId)
        assertNull(unplaced?.toAt)
        // A walk's end can be the rider's own place: not kept, placed or not.
        val home = TflJourneyLegDto(
            departureTime = "2026-09-26T08:15:00", arrivalTime = "2026-09-26T08:20:00",
            departurePoint = TflJourneyPointDto(naptanId = "940GZZLUCCC", commonName = "C"),
            arrivalPoint = TflJourneyPointDto(commonName = "Home", lat = 51.54, lon = -0.12),
            mode = TflJourneyIdentifierDto("walking"),
        ).toLegOrNull(Instant.parse("2026-09-26T07:00:00Z"))
        assertEquals("walking", home?.mode)
        assertNull(home?.toAt)
    }

    @Test
    fun `a train's heading drops the branch the Planner names after it`() = runTest {
        val fixture = checkNotNull(javaClass.getResource("/fixtures/journey_results_kennington_to_archway.json")).readText()
        // The Planner names it "High Barnet Station via Charing Cross"; the train's front reads "High Barnet".
        val ride = client(fixture).journeys("940GZZLUKNG", TripDestination.Stop("940GZZLUACY")).first().rides.single()
        assertEquals(listOf("High Barnet"), ride.headings)
    }

    @Test
    fun `drops a route with a leg it can't read, and says how many`() = runTest {
        val broken = fixture.replaceFirst("\"departureTime\": \"2026-09-26T07:37:00\"", "\"departureTime\": \"soon\"")
        val warnings = mutableListOf<String>()
        val routes = client(broken, warn = { warnings += it }).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
        // Every request got the same answer: the same two routes, and each says what it dropped.
        assertEquals(2, routes.size)
        // Each request's timing line aside ([`each Planner request says how long it took`]).
        val dropped = warnings.untimed()
        assertEquals(
            setOf(
                "journey planner: 1 of 3 routes unreadable",
                "journey planner (fewest changes): 1 of 3 routes unreadable",
                "journey planner (least walking): 1 of 3 routes unreadable",
                "journey planner (bus only): 1 of 3 routes unreadable",
            ),
            dropped.toSet(),
        )
        assertEquals(4, dropped.size)
    }

    @Test
    fun `each Planner request says how long it took`() = runTest {
        // A slow trip page says whether its plan held it up: one line per request, with its routes
        // and milliseconds, and nothing that names either end.
        val warnings = mutableListOf<String>()
        client(fixture, warn = { warnings += it }).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
        val timed = warnings.map { it.replace(Regex("in \\d+ ms \\(waited \\d+ ms to send\\)"), "in N ms (waited W ms to send)") }
        assertEquals(
            setOf(
                // And what each offered, by its lines alone: what the Planner gave, told from what StopDash shows.
                // This answer was recorded without fares, so each route says it came unpriced.
                "journey planner: 3 routes in N ms (waited W ms to send): mildmay+jubilee (unpriced), windrush+jubilee (unpriced), windrush+elizabeth (unpriced)",
                "journey planner (fewest changes): 3 routes in N ms (waited W ms to send): mildmay+jubilee (unpriced), windrush+jubilee (unpriced), windrush+elizabeth (unpriced)",
                "journey planner (least walking): 3 routes in N ms (waited W ms to send): mildmay+jubilee (unpriced), windrush+jubilee (unpriced), windrush+elizabeth (unpriced)",
                "journey planner (bus only): 3 routes in N ms (waited W ms to send): mildmay+jubilee (unpriced), windrush+jubilee (unpriced), windrush+elizabeth (unpriced)",
            ),
            timed.toSet(),
        )
        assertTrue(warnings.none { "910GHGHI" in it || "940GZZLUCYF" in it })
    }

    @Test
    fun `a route the Planner priced is logged as it was, and only one it didn't says unpriced`() = runTest {
        // A card with no price is then told apart: the Planner gave none, or StopDash lost it on the way.
        val priced = fixture.replaceFirst("\"startDateTime\": \"2026-09-26T07:41:00\",", "\"startDateTime\": \"2026-09-26T07:41:00\", \"fare\": {\"totalCost\": 300},")
        assertTrue("the fare went into the second journey", priced != fixture)
        val warnings = mutableListOf<String>()
        val routes = client(priced, warn = { warnings += it }).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
        assertEquals(listOf(null, 300, null), routes.map { it.fare?.pence })
        val offered = warnings.filter { it.startsWith("journey planner: ") }.single().substringAfter("to send): ")
        assertEquals("mildmay+jubilee (unpriced), windrush+jubilee, windrush+elizabeth (unpriced)", offered)
    }

    @Test
    fun `a later trip asks every request for its date and time, leaving or arriving`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val leave = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 17, 9, 5))
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), at = leave)
        assertEquals(4, requests.size)
        requests.forEach { request ->
            assertEquals("20261017", request.url.parameters["date"])
            assertEquals("0905", request.url.parameters["time"])
            assertEquals("Departing", request.url.parameters["timeIs"])
            // One way only: TfL otherwise also offers routes leaving before the time.
            assertEquals("true", request.url.parameters["calcOneDirection"])
        }
        var arriving: HttpRequestData? = null
        client(fixture, capture = { arriving = it })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), at = TripTime(TripTime.Kind.ARRIVE, LocalDateTime.of(2026, 10, 17, 18, 30)))
        assertEquals("1830", checkNotNull(arriving).url.parameters["time"])
        assertEquals("Arriving", checkNotNull(arriving).url.parameters["timeIs"])
    }

    @Test
    fun `a later trip across the clocks going back reads its times about its own time, not now`() = runTest {
        // The recorded journeys moved to 01:37-02:12 on 25 October 2026, where 01:xx happens twice: arriving by
        // 02:15, each leaves in the second 01:xx, so each takes as long as it did.
        val moved = fixture.replace("2026-09-26T07:", "2026-10-25T01:").replace("2026-09-26T08:", "2026-10-25T02:")
        fun minutes(routes: List<TripRoute>) = routes.map { Duration.between(it.legs.first().departure, it.legs.last().arrival).toMinutes() }
        val asRecorded = client(fixture).journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"))
        val arriveBy = TripTime(TripTime.Kind.ARRIVE, LocalDateTime.of(2026, 10, 25, 2, 15))
        val later = client(moved).journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), at = arriveBy)
        assertTrue("the moved fixture still plans", later.isNotEmpty())
        assertEquals(minutes(asRecorded), minutes(later))
        assertTrue(later.all { !it.legs.first().departure.isBefore(Instant.parse("2026-10-25T01:00:00Z")) })
    }

    @Test
    fun `a trip for now sends no time, so the Planner plans from now`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"))
        assertEquals(4, requests.size)
        assertTrue(requests.none { request -> listOf("date", "time", "timeIs", "calcOneDirection").any { it in request.url.parameters.names() } })
    }

    @Test
    fun `a later trip's request via a stop asks for the same time`() = runTest {
        var captured: HttpRequestData? = null
        val at = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 17, 9, 5))
        client(fixture, capture = { captured = it })
            .fewestChangesVia(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), "940GZZLUCWR", at = at)
        assertEquals("20261017", checkNotNull(captured).url.parameters["date"])
        assertEquals("0905", checkNotNull(captured).url.parameters["time"])
    }

    @Test
    fun `a later trip's request via a bus stop towards the destination asks for the same time`() = runTest {
        var captured: HttpRequestData? = null
        val at = TripTime(TripTime.Kind.ARRIVE, LocalDateTime.of(2026, 10, 17, 18, 30))
        clientBy { request -> captured = request; busAnswer("10") to HttpStatusCode.OK }
            .quickestVia(TripOrigin.Here(Coordinates(51.5, -0.12)), TripDestination.Stop("940GZZLUEUS"), "490G000002", at = at)
        val request = checkNotNull(captured)
        assertEquals("490G000002", request.url.parameters["via"])
        assertEquals("20261017", request.url.parameters["date"])
        assertEquals("1830", request.url.parameters["time"])
        assertEquals("Arriving", request.url.parameters["timeIs"])
        assertEquals("true", request.url.parameters["calcOneDirection"])
    }

    @Test
    fun `a later trip's requests say they're later in the log, never when`() = runTest {
        val warnings = mutableListOf<String>()
        client(fixture, warn = { warnings += it })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), at = TripTime(TripTime.Kind.LEAVE, LocalDateTime.of(2026, 10, 17, 9, 5)))
        assertEquals(4, warnings.size)
        assertTrue(warnings.all { it.startsWith("journey planner") && it.substringBefore(":").endsWith(", later") })
        assertTrue(warnings.none { "0905" in it || "09:05" in it || "20261017" in it || "2026-10-17" in it })
    }

    @Test
    fun `drops a route riding from or to a stop the Planner didn't name`() = runTest {
        val broken = fixture.replaceFirst("\"naptanId\": \"910GSTFD\"", "\"naptanId\": null")
        val routes = client(broken).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
        assertEquals(2, routes.size)
        assertTrue(routes.none { route -> route.rides.any { it.toId.isBlank() || it.fromId.isBlank() } })
    }

    @Test
    fun `a time in the autumn rollback's repeated hour runs forward from the one before`() {
        // 25 October 2026: 01:30 London is 00:30Z (summer time) or 01:30Z (after the rollback).
        val now = Instant.parse("2026-10-25T00:50:00Z")
        // Planned at 00:50Z (01:50 summer time): 01:55 is still to come in summer time, but 01:30
        // has passed, so it's the 01:30 after the rollback.
        assertEquals(Instant.parse("2026-10-25T00:55:00Z"), londonTime("2026-10-25T01:55:00", now))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), londonTime("2026-10-25T01:30:00", now))
        // A leg leaving 01:50 (summer time) and arriving 01:10: the arrival is after the rollback.
        val departure = checkNotNull(londonTime("2026-10-25T01:50:00", now))
        assertEquals(Instant.parse("2026-10-25T01:10:00Z"), londonTime("2026-10-25T01:10:00", now, after = departure))
        // A ride leaving 01:30 summer time and arriving 01:30 after the rollback: an hour, not none.
        val leaves = checkNotNull(londonTime("2026-10-25T01:30:00", Instant.parse("2026-10-25T00:20:00Z")))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), londonTime("2026-10-25T01:30:00", now, after = leaves, strictlyAfter = true))
        assertEquals(leaves, londonTime("2026-10-25T01:30:00", now, after = leaves))
        assertEquals(Instant.parse("2026-09-26T06:37:00Z"), londonTime("2026-09-26T07:37:00", now))
    }

    @Test
    fun `a leg after a change in the repeated hour is read after the change`() {
        // Arrive 01:20 summer time with 20 min to change: the next leg's 01:30 is the one after the rollback.
        val leg = TflJourneyLegDto(
            departureTime = "2026-10-25T01:00:00",
            arrivalTime = "2026-10-25T01:20:00",
            departurePoint = TflJourneyPointDto("A", "A"),
            arrivalPoint = TflJourneyPointDto("B", "B"),
            routeOptions = listOf(TflJourneyRouteOptionDto(TflJourneyIdentifierDto("red", "Red"))),
            mode = TflJourneyIdentifierDto("tube", "Tube"),
            interChangeDuration = "20",
            interChangePosition = "AFTER",
        )
        val next = leg.copy(
            departureTime = "2026-10-25T01:30:00",
            arrivalTime = "2026-10-25T01:40:00",
            departurePoint = TflJourneyPointDto("B", "B"),
            arrivalPoint = TflJourneyPointDto("C", "C"),
            interChangeDuration = null,
            interChangePosition = null,
        )
        val route = checkNotNull(TflJourneyDto(listOf(leg, next)).toRouteOrNull(Instant.parse("2026-10-25T00:00:00Z")))
        assertEquals(Instant.parse("2026-10-25T00:20:00Z"), route.legs[0].arrival)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), route.legs[1].departure)
    }

    @Test
    fun `journeys offered but none readable is a failure, not no routes`() {
        val broken = fixture.replace(Regex("\"departureTime\": \"[^\"]+\""), "\"departureTime\": \"soon\"")
        assertThrows(TflException.Unreachable::class.java) {
            kotlinx.coroutines.runBlocking { client(broken).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF")) }
        }
    }

    // A failed request's timing line says why, so a bug report tells a status TfL answered with from an
    // answer StopDash couldn't read, where "Unreachable" alone said neither.
    private fun failedLine(body: String, status: HttpStatusCode = HttpStatusCode.OK): String {
        val warnings = mutableListOf<String>()
        assertThrows(TflException.Unreachable::class.java) {
            kotlinx.coroutines.runBlocking { client(body, status = status, warn = { synchronized(warnings) { warnings += it } }).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF")) }
        }
        val line = warnings.single { it.startsWith("journey planner: ") }
        assertTrue(line, Regex(" in \\d+ ms \\(waited \\d+ ms to send\\)$").containsMatchIn(line))
        return line.substringBefore(" in ")
    }

    @Test
    fun `a request TfL failed says its status`() {
        assertEquals("journey planner: Unreachable (HTTP 500)", failedLine("{}", HttpStatusCode.InternalServerError))
    }

    @Test
    fun `a request whose routes none read says so`() {
        val broken = fixture.replace(Regex("\"departureTime\": \"[^\"]+\""), "\"departureTime\": \"soon\"")
        assertEquals("journey planner: Unreachable (3 routes, none readable)", failedLine(broken))
    }

    @Test
    fun `an answer that won't decode says where, never what it held`() {
        // A time where an object stands, holding a place the rider might have chosen: the path is
        // logged, the value never.
        val broken = fixture.replaceFirst(Regex("\"departureTime\": \"[^\"]+\""), "\"departureTime\": { \"at\": \"X1 9XX\" }")
        assertTrue("the fixture has a time to break", broken != fixture)
        val line = failedLine(broken)
        assertTrue(line, Regex("""^journey planner: Unreachable \(unexpected: \w+ at \$\.journeys\[0]\.legs\[0]\.departureTime\)$""").matches(line))
        assertTrue(line, "X1 9XX" !in line)
    }

    @Test
    fun `an end the Planner can't place gives no routes, not a failure`() = runTest {
        val warnings = mutableListOf<String>()
        val routes = client("{}", status = HttpStatusCode.MultipleChoices, warn = { warnings += it })
            .journeys("910GHGHI", TripDestination.Stop("HUBEXAMPLE"))
        assertEquals(emptyList<Any>(), routes)
        assertEquals(
            setOf(
                "journey planner: HTTP 300",
                "journey planner (fewest changes): HTTP 300",
                "journey planner (least walking): HTTP 300",
                "journey planner (bus only): HTTP 300",
            ),
            warnings.untimed().toSet(),
        )
        assertEquals(4, warnings.untimed().size)
        // Each still timed, by what came of it.
        assertEquals(4, warnings.count { Regex(": HTTP 300 in \\d+ ms \\(waited \\d+ ms to send\\)$").containsMatchIn(it) })
    }

    @Test
    fun `a rate-limited Planner is the honest rate-limited state`() {
        assertThrows(TflException.RateLimited::class.java) {
            kotlinx.coroutines.runBlocking {
                client("{}", status = HttpStatusCode.TooManyRequests).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
            }
        }
    }

    @Test
    fun `a bus leg boards and alights at the poles, not the stop pairs the Planner names`() = runTest {
        // A recorded Planner answer, trimmed: its bus legs' ends are stop pairs ("490G…", which TfL
        // gives no arrivals for) naming their poles, and the last ends at a pole with no pair at all.
        val body = checkNotNull(javaClass.getResource("/fixtures/journey_results_trafalgar_square_to_archway_bus.json")).readText()
        val legs = client(body).journeys("490G000832", TripDestination.Stop("940GZZLUACY")).single().legs
        assertEquals(listOf("walking", "bus", "bus"), legs.map { it.mode })
        assertEquals("490013767A", legs[1].fromId)
        assertEquals("490000252S", legs[1].toId)
        assertEquals("490000252S", legs[2].fromId)
        assertEquals("490000008C", legs[2].toId)
        // Each bus stop pair kept, for the pole its bus uses once its route is known.
        assertEquals("490G000804", legs[1].fromArea)
        assertEquals("490G000850", legs[1].toArea)
        assertEquals("", legs[2].toArea)
    }

    @Test
    fun `a bus leg ending at a stop pair arrives there, whatever ids its path ends with`() = runTest {
        // The same recorded answer: the first bus's path ends with the pair's other pole and then the
        // pair itself, before the pole the Planner names as its end. Planned to that pair, the route
        // arrives there; it doesn't pass through it.
        val body = checkNotNull(javaClass.getResource("/fixtures/journey_results_trafalgar_square_to_archway_bus.json")).readText()
        val route = client(body).journeys("490G000832", TripDestination.Stop("940GZZLUACY")).single()
        val first = TripRoute(route.legs.take(2))
        val pair = setOf(route.legs[1].toId, route.legs[1].toArea) + route.legs[1].path.takeLast(2)
        assertTrue(!first.passesThrough(pair))
    }

    @Test
    fun `a bus to a bus station is headed by its place, as its blind reads`() = runTest {
        // Recorded, trimmed: the Planner's 43 runs to "London Bridge Bus Station"; its buses read "London Bridge".
        val body = checkNotNull(javaClass.getResource("/fixtures/journey_results_archway_to_london_bridge_bus.json")).readText()
        val bus = client(body).journeys("940GZZLUACY", TripDestination.Stop("940GZZLULNB")).single().rides.single()
        assertEquals(listOf("London Bridge"), bus.headings)
    }

    // A client answering each request by [answer]: its body and status.
    private fun clientBy(
        warn: (String) -> Unit = {},
        answer: (HttpRequestData) -> Pair<String, HttpStatusCode>,
    ): KtorTflClient {
        val engine = MockEngine { request ->
            val (body, status) = answer(request)
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, appKey = { "EXAMPLE" }, warn = warn)
    }

    // Constructed: an answer of one route per entry of [minutes], each a walk between two stations
    // taking that long. Hub stations, no real trip.
    private fun walkOnly(vararg minutes: Int) = minutes.joinToString(", ", prefix = """{ "journeys": [ """, postfix = " ] }") { walk ->
        """
        { "legs": [ {
          "departureTime": "2026-09-27T09:00:00", "arrivalTime": "2026-09-27T09:${"%02d".format(walk)}:00",
          "mode": { "id": "walking", "name": "walking" },
          "departurePoint": { "naptanId": "940GZZLUKSX", "commonName": "King's Cross" },
          "arrivalPoint": { "naptanId": "940GZZLUEUS", "commonName": "Euston" }
        } ] }
        """.trimIndent()
    }

    // Constructed: an answer of one route by bus between two hubs, on [lines] in turn, priced at [pence]
    // or left unpriced. Synthetic London stop ids, no real trip.
    private fun busAnswer(vararg lines: String, pence: Int? = null): String {
        val stops = listOf("490000001A", "490000002B", "490000003C")
        val legs = lines.mapIndexed { index, line ->
            """
            { "departureTime": "2026-09-27T09:${"%02d".format(index * 20)}:00", "arrivalTime": "2026-09-27T09:${"%02d".format(index * 20 + 15)}:00",
              "mode": { "id": "bus", "name": "bus" },
              "routeOptions": [ { "lineIdentifier": { "id": "$line", "name": "$line" } } ],
              "departurePoint": { "naptanId": "${stops[index]}", "commonName": "King's Cross" },
              "arrivalPoint": { "naptanId": "${stops[index + 1]}", "commonName": "Euston" } }
            """.trimIndent()
        }
        val fare = pence?.let { """, "fare": { "totalCost": $it }""" }.orEmpty()
        return """{ "journeys": [ { "legs": [ ${legs.joinToString(", ")} ]$fare } ] }"""
    }

    @Test
    fun `a bus route one request left unpriced takes the bus fare another request priced on its line`() = runTest {
        val routes = clientBy { request ->
            (if (request.fewestChanges()) busAnswer("10") else busAnswer("10", "30", pence = 175)) to HttpStatusCode.OK
        }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
        assertEquals("both routes were read", 2, routes.size)
        assertEquals(listOf(listOf("10", "30"), listOf("10")), routes.map { route -> route.rides.map { it.lineId } })
        assertEquals(listOf(TripFare(175), TripFare(175)), routes.map { it.fare })
    }

    @Test
    fun `a route the quickest answer left unpriced keeps the price another answer gave the same route`() = runTest {
        // The kept copy is the quickest's: the price comes over from the other's, whatever the route rides.
        val routes = clientBy { request ->
            (if (request.fewestChanges()) busAnswer("10", "30", pence = 175) else busAnswer("10", "30")) to HttpStatusCode.OK
        }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
        assertEquals(listOf(TripFare(175)), routes.map { it.fare })
    }

    @Test
    fun `a plan's routes are priced off the caller's thread`() {
        // Every route and leg goes past the fare step: on the decode dispatcher, never a screen's main thread.
        val pricedOn = app.stopdash.ThreadRecorder()
        val engine = MockEngine { respond(ByteReadChannel(busAnswer("10", pence = 175)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", fillFares = { pricedOn.note(); it })
        // The caller's own single thread, as a screen's main thread is.
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val callerThread = caller.submit<Thread> { Thread.currentThread() }.get()
            kotlinx.coroutines.runBlocking(caller.asCoroutineDispatcher()) {
                client.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
                client.fewestChangesVia(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"), "490000002B")
                client.quickestWithout(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"), setOf("tube"))
                client.quickestVia(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"), "490G000002")
                client.fewestChangesBetween("940GZZLUKSX", "940GZZLUEUS")
            }
            val priced = pricedOn.threads()
            assertEquals("each request's routes went past the fare step", 5, priced.size)
            assertTrue(priced.none { it == callerThread.name.substringBefore(" @") })
        } finally {
            caller.shutdown()
        }
    }

    private fun HttpRequestData.fewestChanges() = url.parameters["journeyPreference"] == "leastinterchange"

    private fun HttpRequestData.leastWalking() = url.parameters["journeyPreference"] == "leastwalking"

    private fun HttpRequestData.busOnly() = url.parameters["mode"] == "bus,walking"

    // The quickest is the Planner's default, so that request names no preference; nor does the one for
    // buses alone, which is told by its modes.
    private fun HttpRequestData.quickest() = url.parameters["journeyPreference"] == null && !busOnly()

    @Test
    fun `asks the Planner for the quickest routes, the fewest changes, the least walking and buses alone, at the rider's walk`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), WalkingSpeed.FAST, MaxWalk.SIXTY)
        assertEquals(4, requests.size)
        assertEquals(1, requests.count { it.quickest() })
        assertEquals(1, requests.count { it.fewestChanges() })
        assertEquals(1, requests.count { it.leastWalking() })
        // Buses alone at the Planner's quickest: no preference, just the bus and walking.
        assertEquals(1, requests.count { it.busOnly() && "journeyPreference" !in it.url.parameters.names() })
        // All at the rider's pace and walk limit, between the same ends.
        requests.forEach { request ->
            assertEquals("/Journey/JourneyResults/910GHGHI/to/940GZZLUCYF", request.url.encodedPath)
            assertEquals("60", request.url.parameters["maxWalkingMinutes"])
            assertEquals("Fast", request.url.parameters["walkingSpeed"])
        }
    }

    @Test
    fun `plans once more via the recorded fastest route's last bus stop, as the Planner answers`() = runTest {
        // Recorded, trimmed: Euston to St Paul's, hub stations and a landmark (no one's place; SPEC
        // *Privacy*), from the station's position to the cathedral's. The quickest rides the
        // Hammersmith & City line and then the 4 to a stop pair by the cathedral; asked for the
        // fewest changes via that pair, the Planner offers the 46 the whole way from King's Cross.
        fun recorded(which: String) =
            checkNotNull(javaClass.getResource("/fixtures/journey_results_euston_to_st_pauls_$which.json")).readText()
        val vias = mutableListOf<String>()
        val client = clientBy { request ->
            val via = request.url.parameters["via"]
            val body = when {
                via != null -> recorded("via").also { synchronized(vias) { vias += via } }
                request.fewestChanges() -> recorded("fewest")
                else -> recorded("quickest")
            }
            body to HttpStatusCode.OK
        }
        val from = TripOrigin.Here(Coordinates(51.5282, -0.1337))
        val to = TripDestination.Place(Coordinates(51.5138, -0.0984), "St Paul's")
        // The Planner names the 4's end by its stop pair: the stop to route via.
        val stop = checkNotNull(FinalStop.of(client.journeys(from, to)))
        assertEquals("490G000672", stop.stopId)
        val fewer = stop.fewerRides(client.fewestChangesVia(from, to, stop.stopId))
        assertEquals(listOf("490G000672"), vias)
        val route = fewer.single()
        val ride = route.rides.single()
        assertEquals("46", ride.lineId)
        // Boarding at King's Cross by its pole, as the live feed knows it.
        assertEquals("490000129D", ride.fromId)
        // Read as the plan's own routes are: the start unnamed, the walk on named for the place.
        assertEquals("", route.legs.first().fromName)
        assertEquals("St Paul's", route.legs.last().toName)
    }

    @Test
    fun `a plan is read off the thread that asked for it, so a screen's own load never freezes it`() {
        // The answer that has the planner look a platform's station up in the bundled index.
        val via = checkNotNull(javaClass.getResource("/fixtures/journey_results_euston_to_st_pauls_via.json")).readText()
        val engine = MockEngine { respond(ByteReadChannel(via), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val lookedUpOn = app.stopdash.ThreadRecorder()
        val client = KtorTflClient(
            httpClient = http,
            baseUrl = "https://tfl.example",
            stationOf = { lookedUpOn.note(); null },
        )
        // The caller's own single thread, as a screen's main thread is.
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val callerThread = caller.submit<Thread> { Thread.currentThread() }.get()
            kotlinx.coroutines.runBlocking(caller.asCoroutineDispatcher()) {
                client.fewestChangesVia(
                    TripOrigin.Here(Coordinates(51.5282, -0.1337)),
                    TripDestination.Place(Coordinates(51.5138, -0.0984), "St Paul's"),
                    "490G000672",
                )
            }
            val lookedUp = lookedUpOn.threads()
            assertTrue(lookedUp.isNotEmpty())
            assertTrue(lookedUp.none { it == callerThread.name.substringBefore(" @") })
        } finally {
            caller.shutdown()
        }
    }

    @Test
    fun `a train the Planner names by its platform alone leaves from that platform's station`() = runTest {
        // The same recorded answer: besides the 46, Thameslink from St Pancras's low-level
        // platforms, which the Planner names by an access area alone ("9100STPXBOX", no station).
        val via = checkNotNull(javaClass.getResource("/fixtures/journey_results_euston_to_st_pauls_via.json")).readText()
        val index = StationIndexStore.parse(File("src/main/assets/stations/station_index.json").readText())
        val warnings = mutableListOf<String>()
        fun planner(stationOf: (String) -> String?): KtorTflClient {
            val engine = MockEngine { respond(ByteReadChannel(via), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val http = HttpClient(engine) {
                expectSuccess = true
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, warn = { warnings += it }, stationOf = stationOf)
        }
        val from = TripOrigin.Here(Coordinates(51.5282, -0.1337))
        val to = TripDestination.Place(Coordinates(51.5138, -0.0984), "St Paul's")
        val routes = planner(index::stationOf).fewestChangesVia(from, to, "490G000672")
        assertEquals(3, routes.size)
        assertEquals(emptyList<String>(), warnings.untimed())
        val thameslink = routes.filter { route -> route.rides.single().lineId == "thameslink" }
        assertEquals(2, thameslink.size)
        thameslink.forEach { route ->
            val (walk, ride) = route.legs
            // Boarding at the station whose trains the live feed and National Rail's boards list,
            // and walked to there.
            assertEquals("910GSTPXBOX", ride.fromId)
            assertEquals("910GSTPXBOX", walk.toId)
            assertEquals("910GCTMSLNK", ride.toId)
        }
        // With no index to place the platform, those routes can't be read, as before.
        warnings.clear()
        assertEquals(listOf("46"), planner { null }.fewestChangesVia(from, to, "490G000672").map { it.rides.single().lineId })
        assertEquals(listOf("journey planner (fewest changes via a stop): 2 of 3 routes unreadable"), warnings.untimed())
    }

    @Test
    fun `a planned leg's stops are placed by the station model as the answer comes in`() = runTest {
        // The same recorded answer: Thameslink from St Pancras's low-level platforms.
        val via = checkNotNull(javaClass.getResource("/fixtures/journey_results_euston_to_st_pauls_via.json")).readText()
        val dir = listOf(File("src/main/assets/stations"), File("app/src/main/assets/stations")).first { it.exists() }
        val index = StationIndexStore.parse(File(dir, "station_index.json").readText())
        val model = checkNotNull(Hubs.of(index, File(dir, "stops.tsv").bufferedReader().use { BundledStops.read(it) }))
        fun planner(hubs: Hubs?): KtorTflClient {
            val engine = MockEngine { respond(ByteReadChannel(via), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val http = HttpClient(engine) {
                expectSuccess = true
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, stationOf = index::stationOf, hubs = { hubs })
        }
        val from = TripOrigin.Here(Coordinates(51.5282, -0.1337))
        val to = TripDestination.Place(Coordinates(51.5138, -0.0984), "St Paul's")
        val ride = planner(model).fewestChangesVia(from, to, "490G000672").first { it.rides.single().lineId == "thameslink" }.rides.single()
        // Boarding at St Pancras: one of its records, in the King's Cross hub.
        val boarding = ride.placeOf(ride.fromId)
        assertEquals(model.resolve("910GSTPX").station, boarding.station)
        assertEquals("HUBKGX", boarding.hub)
        assertEquals("910GSTPXBOX", boarding.tflId)
        // Its other end too, and every call on its way.
        assertTrue(ride.toId in ride.placed)
        assertTrue(ride.path.all { it in ride.placed })
        // No model in time: unplaced, each id only itself.
        val unplaced = planner(null).fewestChangesVia(from, to, "490G000672").first { it.rides.single().lineId == "thameslink" }.rides.single()
        assertTrue(unplaced.placed.isEmpty())
    }

    @Test
    fun `the plan's own requests pass no stop`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"))
        assertEquals(4, requests.size)
        assertTrue(requests.none { "via" in it.url.parameters.names() })
    }

    @Test
    fun `a trip via a stop the rider picked asks all four requests via it, naming it nowhere in the log`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val warnings = mutableListOf<String>()
        client(fixture, capture = { synchronized(requests) { requests += it } }, warn = { synchronized(warnings) { warnings += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), via = "940GZZLUFPK")
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.url.parameters["via"] == "940GZZLUFPK" })
        // Still the quickest, the fewest changes, the least walking and buses alone, between the same ends.
        assertEquals(1, requests.count { it.quickest() })
        assertEquals(1, requests.count { it.fewestChanges() })
        assertEquals(1, requests.count { it.leastWalking() })
        assertEquals(1, requests.count { it.busOnly() })
        requests.forEach { assertEquals("/Journey/JourneyResults/910GHGHI/to/940GZZLUCYF", it.url.encodedPath) }
        // Each said as asked via a stop, which stop never named.
        listOf("quickest via a stop", "fewest changes via a stop", "least walking via a stop", "bus only via a stop").forEach { request ->
            assertTrue(warnings.toString(), warnings.any { it.startsWith("journey planner ($request): ") })
        }
        assertTrue(warnings.none { "940GZZLUFPK" in it })
    }

    @Test
    fun `a trip via a stop asks around delays via it too`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val warnings = mutableListOf<String>()
        client(fixture, capture = { synchronized(requests) { requests += it } }, warn = { synchronized(warnings) { warnings += it } })
            .quickestWithout(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), setOf("tube"), via = "940GZZLUFPK")
        val request = requests.single()
        assertEquals("940GZZLUFPK", request.url.parameters["via"])
        assertTrue(request.url.parameters["mode"].orEmpty().split(',').none { it == "tube" })
        assertTrue(warnings.toString(), warnings.single().startsWith("journey planner (without tube, via a stop): "))
    }

    @Test
    fun `asks once for the quickest routes via a bus stop, at the rider's walk`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val warnings = mutableListOf<String>()
        val client = clientBy(warn = { synchronized(warnings) { warnings += it } }) { request ->
            synchronized(requests) { requests += request }
            busAnswer("10") to HttpStatusCode.OK
        }
        val routes = client.quickestVia(
            TripOrigin.Here(Coordinates(51.5, -0.12)), TripDestination.Stop("940GZZLUEUS"), "490G000002", WalkingSpeed.FAST, MaxWalk.SIXTY,
        )
        assertEquals(listOf("10"), routes.map { it.rides.single().lineId })
        val request = requests.single()
        assertEquals("490G000002", request.url.parameters["via"])
        // The quickest: the Planner's default, so no preference is named.
        assertEquals(null, request.url.parameters["journeyPreference"])
        assertEquals("60", request.url.parameters["maxWalkingMinutes"])
        assertEquals("Fast", request.url.parameters["walkingSpeed"])
        // Said apart from the via the fastest route's last stop asks, by which request it was.
        assertTrue(warnings.toString(), warnings.any { it.startsWith("journey planner (quickest via a stop): 1 routes in ") })
    }

    @Test
    fun `no route through the stop asked via is the Planner's 404`() {
        assertThrows(TflException.NotFound::class.java) {
            kotlinx.coroutines.runBlocking {
                clientBy { """{ "message": "No journey found for your inputs." }""" to HttpStatusCode.NotFound }
                    .quickestVia(TripOrigin.Here(Coordinates(51.5, -0.12)), TripDestination.Stop("940GZZLUEUS"), "490G000002")
            }
        }
    }

    @Test
    fun `asks for a Direct ride from one stop to the other, once, for the fewest changes and at the rider's walk`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val warnings = mutableListOf<String>()
        client(fixture, capture = { synchronized(requests) { requests += it } }, warn = { warnings += it })
            .fewestChangesBetween("910GHGHI", "940GZZLUCYF", WalkingSpeed.FAST, MaxWalk.SIXTY, StepFree.STATION)
        val request = requests.single()
        // Both ends are the stops themselves: from the first, the Planner offers the lines leaving it.
        assertEquals("/Journey/JourneyResults/910GHGHI/to/940GZZLUCYF", request.url.encodedPath)
        assertTrue(request.fewestChanges())
        assertTrue("via" !in request.url.parameters.names())
        assertEquals("Fast", request.url.parameters["walkingSpeed"])
        assertEquals("60", request.url.parameters["maxWalkingMinutes"])
        assertEquals("StepFreeToPlatform", request.url.parameters["accessibilityPreference"])
        // Said apart in the log, naming neither stop.
        assertTrue(warnings.single().startsWith("journey planner (fewest changes between stops): 3 routes in "))
        assertTrue(warnings.none { "910GHGHI" in it || "940GZZLUCYF" in it })
    }

    @Test
    fun `asking for a Direct ride between stops fails as the one request does`() {
        assertThrows(TflException.RateLimited::class.java) {
            kotlinx.coroutines.runBlocking {
                clientBy { "{}" to HttpStatusCode.TooManyRequests }.fewestChangesBetween("940GZZLUKSX", "940GZZLUEUS")
            }
        }
    }

    @Test
    fun `asking for the fewest changes via a stop fails as the one request does`() {
        assertThrows(TflException.RateLimited::class.java) {
            kotlinx.coroutines.runBlocking {
                clientBy { "{}" to HttpStatusCode.TooManyRequests }
                    .fewestChangesVia(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"), "940GZZLUEUS")
            }
        }
    }

    @Test
    fun `asks every request for the rider's step-free level, and none for any`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(fixture, capture = { synchronized(requests) { requests += it } })
        client.journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), stepFree = StepFree.STATION)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.url.parameters["accessibilityPreference"] == "StepFreeToPlatform" })
        requests.clear()
        client.journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), stepFree = StepFree.FULLY)
        assertTrue(requests.all { it.url.parameters["accessibilityPreference"] == "StepFreeToVehicle" })
        requests.clear()
        // No requirement sends nothing, leaving the Planner its own default.
        client.journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"))
        assertEquals(4, requests.size)
        assertTrue(requests.none { "accessibilityPreference" in it.url.parameters.names() })
    }

    @Test
    fun `asks every request for only the modes the rider rides`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val noBusOrTrain = listOf("bus", "overground", "rail").fold(TripModes.DEFAULT) { modes, key ->
            modes.with(ModeGroups.ALL.single { it.key == key }, ride = false)
        }
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), modes = noBusOrTrain)
        // With the bus off, there's no asking for buses alone.
        assertEquals(3, requests.size)
        requests.forEach { request ->
            val modes = checkNotNull(request.url.parameters["mode"]).split(",")
            assertTrue(modes.none { it in setOf("bus", "overground", "national-rail") })
            // Still walking, so the rider's pace applies, and the Underground (the Elizabeth line too), tram and the rest.
            assertTrue("coach" !in modes)
            assertTrue(modes.containsAll(listOf("walking", "tube", "dlr", "elizabeth-line", "tram", "river-bus", "replacement-bus")))
        }
    }

    @Test
    fun `around delays the quickest routes are asked for once without the delayed mode`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val warnings = mutableListOf<String>()
        val noBus = TripModes.DEFAULT.with(ModeGroups.ALL.single { it.key == "bus" }, ride = false)
        client(fixture, capture = { synchronized(requests) { requests += it } }, warn = { warnings += it })
            .quickestWithout(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), setOf("tube"), modes = noBus)
        val request = requests.single()
        val modes = checkNotNull(request.url.parameters["mode"]).split(",")
        // The tube left out, and what the rider turned off stays off; the rest of the Underground still rides.
        assertTrue("tube" !in modes && "bus" !in modes)
        assertTrue(modes.containsAll(listOf("walking", "dlr", "elizabeth-line", "national-rail")))
        // The quickest: no preference sent.
        assertTrue("journeyPreference" !in request.url.parameters.names())
        // Its timing line names the mode left out, never a stop.
        assertTrue(warnings.any { it.startsWith("journey planner (without tube): ") })
    }

    @Test
    fun `the fewest-changes routes follow the quickest, a route both offer once`() = runTest {
        val routes = clientBy { request ->
            // The fewest-changes answer adds a longer route and repeats the quickest's.
            (if (request.fewestChanges()) walkOnly(20, 12) else walkOnly(12)) to HttpStatusCode.OK
        }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
        assertEquals(listOf(12L, 20L), routes.map { it.legs.single().run.toMinutes() })
    }

    @Test
    fun `the least-walking routes follow the others, a route already offered once`() = runTest {
        val routes = clientBy { request ->
            when {
                request.fewestChanges() -> walkOnly(20, 12)
                request.leastWalking() -> walkOnly(25, 12)
                else -> walkOnly(12)
            } to HttpStatusCode.OK
        }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
        assertEquals(listOf(12L, 20L, 25L), routes.map { it.legs.single().run.toMinutes() })
    }

    @Test
    fun `the bus-only routes follow the rest, a route already offered once`() = runTest {
        val routes = clientBy { request ->
            when {
                request.fewestChanges() -> walkOnly(20, 12)
                request.leastWalking() -> walkOnly(25, 12)
                request.busOnly() -> busAnswer("10", pence = 175)
                else -> walkOnly(12)
            } to HttpStatusCode.OK
        }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
        // Merged in that order, each kept where it came; the list sorts them as always.
        assertEquals(listOf(emptyList<String>(), emptyList(), emptyList(), listOf("10")), routes.map { route -> route.rides.map { it.lineId } })
        assertEquals(TripFare(175), routes.last().fare)
    }

    @Test
    fun `buses alone are asked for with the bus the only group riding too`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val onlyBus = ModeGroups.ALL.filter { it.key != "bus" }.fold(TripModes.DEFAULT) { modes, group -> modes.with(group, ride = false) }
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), modes = onlyBus)
        // The quickest still rides the coach, the cable car and replacement buses, which can crowd out the bus.
        assertEquals(4, requests.size)
        assertEquals(1, requests.count { it.busOnly() })
        assertTrue("coach" in checkNotNull(requests.first { it.quickest() }.url.parameters["mode"]).split(","))
    }

    @Test
    fun `any request failing still plans the trip from the others, and says which failed`() = runTest {
        val sources = mapOf<(HttpRequestData) -> Boolean, String>(
            { r: HttpRequestData -> r.quickest() } to "journey planner (quickest)",
            { r: HttpRequestData -> r.fewestChanges() } to "journey planner (fewest changes)",
            { r: HttpRequestData -> r.leastWalking() } to "journey planner (least walking)",
            { r: HttpRequestData -> r.busOnly() } to "journey planner (bus only)",
        )
        for ((failing, which) in sources) {
            val warnings = mutableListOf<String>()
            val routes = clientBy(warn = { warnings += it }) { request ->
                if (failing(request)) "{}" to HttpStatusCode.ServiceUnavailable else walkOnly(12) to HttpStatusCode.OK
            }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
            assertEquals(1, routes.size)
            assertEquals(1, warnings.untimed().size)
            assertTrue(warnings.untimed().single(), warnings.untimed().single().startsWith("$which: "))
            // The failed request is timed too: it can be the slow one holding the plan up (Codex, #563).
            val timedFailure = warnings.filter { TIMED.containsMatchIn(it) && !Regex(": \\d+ routes in ").containsMatchIn(it) }
            assertEquals(warnings.toString(), 1, timedFailure.size)
        }
    }

    @Test
    fun `every request failing fails the plan with the quickest's failure, and logs the others'`() {
        val warnings = mutableListOf<String>()
        // Different failures: the quickest rate-limited, the others unreachable.
        assertThrows(TflException.RateLimited::class.java) {
            kotlinx.coroutines.runBlocking {
                clientBy(warn = { warnings += it }) { request ->
                    "{}" to if (request.quickest()) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable
                }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
            }
        }
        assertEquals(3, warnings.untimed().size)
        // And every one timed, by what came of it.
        assertEquals(warnings.toString(), 4, warnings.size - warnings.untimed().size)
        for (which in listOf("journey planner (fewest changes): ", "journey planner (least walking): ", "journey planner (bus only): ")) {
            val warning = warnings.untimed().single { it.startsWith(which) }
            assertTrue(warning, warning.removePrefix(which) !in setOf("", "null", "RateLimited"))
        }
    }

    @Test
    fun `a West Midlands Trains leg comes in named as London Northwestern, so directions say LNR`() {
        val leg = TflJourneyLegDto(
            departureTime = "2026-09-30T10:00:00",
            arrivalTime = "2026-09-30T10:40:00",
            departurePoint = TflJourneyPointDto("A", "A"),
            arrivalPoint = TflJourneyPointDto("B", "B"),
            routeOptions = listOf(TflJourneyRouteOptionDto(TflJourneyIdentifierDto("west-midlands-trains", "West Midlands Trains"))),
            mode = TflJourneyIdentifierDto("national-rail", "National Rail"),
        ).toLegOrNull(Instant.parse("2026-09-30T08:00:00Z"))
        assertEquals("London Northwestern Railway", leg?.lineName)
    }

    // The log less each request's timing line ([`each Planner request says how long it took`]).
    private fun List<String>.untimed() = filterNot { TIMED.containsMatchIn(it) }

    private companion object {
        // A request's timing line, whatever its outcome.
        val TIMED = Regex(": [^:]+ in \\d+ ms \\((waited \\d+ ms to send|not sent)\\)(: .+)?$")
    }
}
