package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripOrigin
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
import java.time.Duration
import java.time.Instant
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
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", appKey = { "EXAMPLE" }, warn = warn)
    }

    @Test
    fun `asks the Planner between two stop ids`() = runTest {
        var captured: HttpRequestData? = null
        client(fixture, capture = { captured = it }).journeys("910GHGHI", TripDestination.Stop("940GZZLUCYF"))
        val url = checkNotNull(captured).url
        assertEquals("/Journey/JourneyResults/910GHGHI/to/940GZZLUCYF", url.encodedPath)
        assertEquals("EXAMPLE", url.parameters["app_key"])
        assertEquals("30", url.parameters["maxWalkingMinutes"])
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
        var captured: HttpRequestData? = null
        client(fixture, capture = { captured = it })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), WalkingSpeed.SLOW)
        val modes = checkNotNull(checkNotNull(captured).url.parameters["mode"]).split(",")
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
        // Both requests got the same answer: the same two routes, and each says what it dropped.
        assertEquals(2, routes.size)
        assertEquals(
            setOf("journey planner: 1 of 3 routes unreadable", "journey planner (fewest changes): 1 of 3 routes unreadable"),
            warnings.toSet(),
        )
        assertEquals(2, warnings.size)
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

    @Test
    fun `an end the Planner can't place gives no routes, not a failure`() = runTest {
        val warnings = mutableListOf<String>()
        val routes = client("{}", status = HttpStatusCode.MultipleChoices, warn = { warnings += it })
            .journeys("910GHGHI", TripDestination.Stop("HUBEXAMPLE"))
        assertEquals(emptyList<Any>(), routes)
        assertEquals(setOf("journey planner: HTTP 300", "journey planner (fewest changes): HTTP 300"), warnings.toSet())
        assertEquals(2, warnings.size)
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
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", appKey = { "EXAMPLE" }, warn = warn)
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

    private fun HttpRequestData.fewestChanges() = url.parameters["journeyPreference"] == "leastinterchange"

    @Test
    fun `asks the Planner twice, for the quickest routes and for the fewest changes, at the rider's walk`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        client(fixture, capture = { synchronized(requests) { requests += it } })
            .journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), WalkingSpeed.FAST, MaxWalk.SIXTY)
        assertEquals(2, requests.size)
        // The quickest is the Planner's default, so that request names no preference.
        assertEquals(1, requests.count { it.url.parameters["journeyPreference"] == null })
        assertEquals(1, requests.count { it.fewestChanges() })
        // Both at the rider's pace and walk limit, between the same ends.
        requests.forEach { request ->
            assertEquals("/Journey/JourneyResults/910GHGHI/to/940GZZLUCYF", request.url.encodedPath)
            assertEquals("60", request.url.parameters["maxWalkingMinutes"])
            assertEquals("Fast", request.url.parameters["walkingSpeed"])
        }
    }

    @Test
    fun `asks both requests for the rider's step-free level, and none for any`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(fixture, capture = { synchronized(requests) { requests += it } })
        client.journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), stepFree = StepFree.STATION)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.parameters["accessibilityPreference"] == "StepFreeToPlatform" })
        requests.clear()
        client.journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"), stepFree = StepFree.FULLY)
        assertTrue(requests.all { it.url.parameters["accessibilityPreference"] == "StepFreeToVehicle" })
        requests.clear()
        // No requirement sends nothing, leaving the Planner its own default.
        client.journeys(TripOrigin.Stop("910GHGHI"), TripDestination.Stop("940GZZLUCYF"))
        assertEquals(2, requests.size)
        assertTrue(requests.none { "accessibilityPreference" in it.url.parameters.names() })
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
    fun `either request failing still plans the trip from the other, and says which failed`() = runTest {
        for (failing in listOf(true, false)) {
            val warnings = mutableListOf<String>()
            val routes = clientBy(warn = { warnings += it }) { request ->
                if (request.fewestChanges() == failing) "{}" to HttpStatusCode.ServiceUnavailable else walkOnly(12) to HttpStatusCode.OK
            }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
            assertEquals(1, routes.size)
            val which = if (failing) "journey planner (fewest changes)" else "journey planner (quickest)"
            assertEquals(1, warnings.size)
            assertTrue(warnings.single(), warnings.single().startsWith("$which: "))
        }
    }

    @Test
    fun `both requests failing fails the plan with the quickest's failure, and logs the other's`() {
        val warnings = mutableListOf<String>()
        // Different failures: the quickest rate-limited, the fewest changes unreachable.
        assertThrows(TflException.RateLimited::class.java) {
            kotlinx.coroutines.runBlocking {
                clientBy(warn = { warnings += it }) { request ->
                    "{}" to if (request.fewestChanges()) HttpStatusCode.ServiceUnavailable else HttpStatusCode.TooManyRequests
                }.journeys(TripOrigin.Stop("940GZZLUKSX"), TripDestination.Stop("940GZZLUEUS"))
            }
        }
        val fewest = warnings.single { it.startsWith("journey planner (fewest changes): ") }
        assertTrue(fewest, fewest.removePrefix("journey planner (fewest changes): ") !in setOf("", "null", "RateLimited"))
    }
}
