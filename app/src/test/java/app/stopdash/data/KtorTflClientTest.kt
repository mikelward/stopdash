package app.stopdash.data

import app.stopdash.domain.PostcodeResolution
import app.stopdash.domain.TflException
import app.stopdash.domain.TflRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorTflClientTest {
    // A recorded /Arrivals fixture: public infrastructure/line names only, no user
    // data (SPEC *Privacy*). Prediction 1 carries extra TfL fields to prove
    // ignoreUnknownKeys; prediction 2 has a blank platform and blank
    // destinationName (falls back to `towards`).
    private val arrivalsJson =
        """
        [
          {
            "${'$'}type": "Tfl.Api.Presentation.Entities.Prediction",
            "id": "111",
            "naptanId": "940GZZLUVIC",
            "stationName": "Victoria Underground Station",
            "lineId": "victoria",
            "lineName": "Victoria",
            "platformName": "Northbound - Platform 1",
            "direction": "inbound",
            "destinationName": "Walthamstow Central",
            "towards": "Walthamstow Central",
            "expectedArrival": "2026-09-18T08:03:00Z",
            "timeToStation": 180,
            "modeName": "tube"
          },
          {
            "id": "222",
            "lineId": "victoria",
            "lineName": "Victoria",
            "platformName": "",
            "direction": "outbound",
            "destinationName": "",
            "towards": "Brixton",
            "expectedArrival": "2026-09-18T08:05:30Z",
            "timeToStation": 330
          },
          {
            "id": "333",
            "lineId": "24",
            "lineName": "24",
            "platformName": "",
            "destinationName": "Pimlico",
            "towards": "Pimlico, Grosvenor Road",
            "expectedArrival": "2026-09-18T08:01:00Z",
            "timeToStation": 60,
            "modeName": "bus"
          }
        ]
        """.trimIndent()

    // A recorded /Line/{ids}/Status fixture: victoria good, northern severely delayed,
    // central carrying both a good-service and a minor-delays entry (the disruption must
    // win). Public line names only (SPEC *Privacy*). Extra fields prove ignoreUnknownKeys.
    private val statusJson =
        """
        [
          {
            "${'$'}type": "Tfl.Api.Presentation.Entities.Line",
            "id": "victoria",
            "name": "Victoria",
            "modeName": "tube",
            "lineStatuses": [
              { "statusSeverity": 10, "statusSeverityDescription": "Good Service" }
            ]
          },
          {
            "id": "northern",
            "name": "Northern",
            "lineStatuses": [
              {
                "statusSeverity": 6,
                "statusSeverityDescription": "Severe Delays",
                "reason": "Northern line: severe delays while we fix a faulty train."
              }
            ]
          },
          {
            "id": "central",
            "name": "Central",
            "lineStatuses": [
              { "statusSeverity": 10, "statusSeverityDescription": "Good Service" },
              { "statusSeverity": 9, "statusSeverityDescription": "Minor Delays" }
            ]
          },
          {
            "id": "43",
            "name": "43",
            "modeName": "bus",
            "lineStatuses": [
              {
                "statusSeverity": 0,
                "statusSeverityDescription": "Special Service",
                "reason": "Road closed for works. Buses will be diverted and will miss stops."
              }
            ]
          },
          {
            "id": "district",
            "name": "District",
            "lineStatuses": [
              {
                "statusSeverity": 5,
                "statusSeverityDescription": "Part Closure",
                "reason": "No service between Earls Court and Ealing Broadway this weekend."
              }
            ]
          },
          {
            "id": "circle",
            "name": "Circle",
            "lineStatuses": []
          }
        ]
        """.trimIndent()

    // A recorded /StopPoint/{id}/Disruption fixture in the real `getFamily=true` shape:
    // a DisruptedPointFamily tree, not a flat array (TfL switches to this object whenever
    // getFamily is set — the shape the client actually receives). The notice lives on a
    // child platform, not the hub node, so the walk must descend; a blank-description entry
    // must be dropped; and the same closure repeated on a second child must dedupe to one.
    // Public station text only.
    private val disruptionJson =
        """
        {
          "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPointFamily",
          "naptanId": "HUBKGX",
          "disruptions": [],
          "children": [
            {
              "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPointFamily",
              "naptanId": "940GZZLUKSX",
              "disruptions": [
                {
                  "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPoint",
                  "atcoCode": "940GZZLUKSX",
                  "description": "Station closed until further notice.",
                  "closureText": "stationClosed"
                },
                { "description": "" }
              ],
              "children": [
                {
                  "naptanId": "9400ZZLUKSX1",
                  "disruptions": [
                    { "description": "Station closed until further notice." }
                  ],
                  "children": []
                }
              ]
            }
          ]
        }
        """.trimIndent()

    // A recorded bus-stop closure (/StopPoint/{id}/Disruption, getFamily=true), trimmed to two of
    // the StopArea's poles: TfL's real `fromDate`/`toDate` form and placement, on the DisruptedPoint
    // alongside the description. A public, unrelated stop's notice (SPEC *Privacy*).
    private val busClosureJson =
        """
        {
          "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPointFamily, Tfl.Api.Presentation.Entities",
          "naptanId": "490G00003087",
          "disruptions": [],
          "children": [
            {
              "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPointFamily, Tfl.Api.Presentation.Entities",
              "naptanId": "490003087W1",
              "disruptions": [
                {
                  "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPoint, Tfl.Api.Presentation.Entities",
                  "atcoCode": "490003087W1",
                  "fromDate": "2026-09-14T08:00:00Z",
                  "toDate": "2026-09-27T17:00:00Z",
                  "description": "Bus Stop Closed\\n   see tfl.gov.uk/bus/status\\n     for more information\\n",
                  "commonName": "Acton Street",
                  "type": "Closure",
                  "mode": "bus",
                  "stationAtcoCode": "490G00003087",
                  "appearance": "Information"
                }
              ],
              "children": []
            },
            {
              "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPointFamily, Tfl.Api.Presentation.Entities",
              "naptanId": "490012149N",
              "disruptions": [
                {
                  "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPoint, Tfl.Api.Presentation.Entities",
                  "atcoCode": "490012149N",
                  "fromDate": "2026-09-14T08:00:00Z",
                  "toDate": "2026-09-27T17:00:00Z",
                  "description": "Bus Stop Closed\\n   see tfl.gov.uk/bus/status\\n     for more information\\n",
                  "commonName": "Acton Street",
                  "type": "Closure",
                  "mode": "bus",
                  "stationAtcoCode": "490G00003087",
                  "appearance": "Information"
                }
              ],
              "children": []
            }
          ]
        }
        """.trimIndent()

    // A nearby-search fixture in the shape of a real /StopPoint response, trimmed to the
    // mapped fields. Public station names/ids only, and every coordinate is an
    // obviously-synthetic stand-in — never a real position (SPEC *Privacy*).
    // Stop 1 has a blank `id` (falls back to `naptanId`) and a type suffix to strip; stop
    // 2 is a mixed-mode hub proving per-line mode comes from `lineModeGroups`, with a
    // blank-id line that must be dropped; stop 3 has no usable id and must be dropped.
    // Extra top-level and per-stop fields prove ignoreUnknownKeys.
    private val nearbyJson =
        """
        {
          "${'$'}type": "Tfl.Api.Presentation.Entities.StopPointsResponse",
          "pageSize": 25,
          "total": 3,
          "page": 1,
          "centrePoint": [51.5, -0.12],
          "stopPoints": [
            {
              "${'$'}type": "Tfl.Api.Presentation.Entities.StopPoint",
              "id": "",
              "naptanId": "940GZZLUCHX",
              "commonName": "Charing Cross Underground Station",
              "distance": 12.3,
              "hubNaptanCode": "HUBCHX",
              "lat": 51.5,
              "lon": -0.12,
              "modes": ["tube"],
              "lines": [
                { "id": "bakerloo", "name": "Bakerloo" },
                { "id": "northern", "name": "Northern" }
              ],
              "lineModeGroups": [
                { "modeName": "tube", "lineIdentifier": ["bakerloo", "northern"] }
              ]
            },
            {
              "id": "HUBSRA",
              "naptanId": "HUBSRA",
              "commonName": "Stratford Station",
              "lat": 51.55,
              "lon": -0.1,
              "modes": ["tube", "dlr", "elizabeth-line"],
              "lines": [
                { "id": "central", "name": "Central" },
                { "id": "dlr", "name": "DLR" },
                { "id": "", "name": "" },
                { "id": "elizabeth", "name": "Elizabeth line" }
              ],
              "lineModeGroups": [
                { "modeName": "tube", "lineIdentifier": ["central"] },
                { "modeName": "dlr", "lineIdentifier": ["dlr"] },
                { "modeName": "elizabeth-line", "lineIdentifier": ["elizabeth"] }
              ]
            },
            {
              "id": "",
              "naptanId": "",
              "commonName": "Nowhere",
              "lat": 0.0,
              "lon": 0.0
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `the rate limiter gates each request, and its RateLimited surfaces without a network call`() = runTest {
        var requests = 0
        var acquires = 0
        val engine = MockEngine { _ ->
            requests++
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // A limiter that grants the first token, then reports the budget spent — so the second
        // request is turned away before any network call is made.
        val limiter = object : TflRateLimiter {
            override suspend fun acquire() {
                acquires++
                if (acquires > 1) throw TflException.RateLimited(null)
            }
        }
        val client = KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, rateLimiterFor = { limiter })

        client.arrivals("940GZZLUVIC")
        assertEquals("the granted request hit the network", 1, requests)

        var rateLimited = false
        try {
            client.arrivals("940GZZLUVIC")
        } catch (e: TflException.RateLimited) {
            rateLimited = true
        }
        assertTrue("a turned-away request surfaces RateLimited", rateLimited)
        assertEquals("the limiter was consulted for both requests", 2, acquires)
        assertEquals("the turned-away request made no network call", 1, requests)
    }

    @Test
    fun `one key snapshot drives both the limiter budget and the app_key`() = runTest {
        var limiterKey: String? = "unset"
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = KtorTflClient(
            httpClient = http,
            baseUrl = "https://tfl.example", decodeDispatcher = serialDecode,
            appKey = { "EXAMPLE" },
            rateLimiterFor = { key ->
                limiterKey = key
                TflRateLimiter.UNLIMITED
            },
        )

        client.arrivals("940GZZLUVIC")

        // The limiter was selected from the same key the request carries — one read, no disagreement.
        assertEquals("EXAMPLE", limiterKey)
        assertEquals("EXAMPLE", checkNotNull(captured).url.parameters["app_key"])
    }

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        appKey: String? = null,
        capture: (HttpRequestData) -> Unit = {},
        warn: (String) -> Unit = {},
        httpTimeout: Boolean = false,
        keyAnswered: (String, Boolean) -> Unit = { _, _ -> },
    ): KtorTflClient {
        val engine = MockEngine { request ->
            capture(request)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            if (httpTimeout) install(HttpTimeout)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, appKey = { appKey }, warn = warn, keyAnswered = keyAnswered)
    }

    @Test
    fun `a new alert's direction is looked up once in the background, then splits the status`() = runTest {
        val north = "STATION A: routes are on diversion northbound via Street A."
        val south = "Road closed: buses towards Town B will miss stops."
        fun statusJson(detail: Boolean) = """
            [{"id": "bus1", "name": "1", "lineStatuses": [
              {"statusSeverity": 0, "statusSeverityDescription": "Special Service", "reason": "$north",
               "disruption": {"affectedRoutes": [${if (detail) """{"direction": "inbound", "routeSectionNaptanEntrySequence": []}""" else ""}]}},
              {"statusSeverity": 0, "statusSeverityDescription": "Special Service", "reason": "$south",
               "disruption": {"affectedRoutes": [${if (detail) """{"direction": "outbound"}""" else ""}]}}
            ]}]
        """.trimIndent()
        val requests = mutableListOf<String?>()
        // The lookup's parent, joined to wait for it: the mock engine answers off the test scheduler.
        val lookups = Job()
        val engine = MockEngine { request ->
            val detail = request.url.parameters["detail"]
            requests += detail
            respond(
                content = ByteReadChannel(statusJson(detail == "true")),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = KtorTflClient(
            httpClient = http,
            baseUrl = "https://tfl.example", decodeDispatcher = serialDecode,
            alertDirections = LineAlertDirections(),
            alertDirectionScope = CoroutineScope(lookups),
            alertDirectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        // The first refresh answers at once, line-wide, and starts the lookup behind it.
        val first = client.lineStatuses(listOf("bus1")).single()
        assertTrue(first.byDirection.isEmpty())
        // Marked as waiting, so a caller doesn't reuse it past the answer.
        assertTrue(first.awaitingDirections)
        advanceUntilIdle()
        lookups.children.toList().joinAll()
        assertEquals(listOf(null, "true"), requests)

        // The next splits by direction, without asking for the detail again.
        val second = client.lineStatuses(listOf("bus1")).single()
        assertEquals(north, second.forDirection("inbound").fullText)
        assertEquals(south, second.forDirection("outbound").fullText)
        assertFalse(second.awaitingDirections)
        assertTrue(lookups.children.none())
        assertEquals(listOf(null, "true", null), requests)
    }

    @Test
    fun `a direction lookup split across requests keeps what one answered when another fails`() = runTest {
        val north = "STATION A: routes are on diversion northbound via Street A."
        val south = "Road closed: buses towards Town B will miss stops."
        // Line ids long enough that TfL needs a request for each (LineStatusBatch).
        val first = "bus-" + "x".repeat(150)
        val second = "bus-" + "y".repeat(150)
        fun line(id: String, detail: Boolean) = """
            {"id": "$id", "name": "$id", "lineStatuses": [
              {"statusSeverity": 0, "statusSeverityDescription": "Special Service", "reason": "$north",
               "disruption": {"affectedRoutes": [${if (detail) """{"direction": "inbound"}""" else ""}]}},
              {"statusSeverity": 0, "statusSeverityDescription": "Special Service", "reason": "$south",
               "disruption": {"affectedRoutes": [${if (detail) """{"direction": "outbound"}""" else ""}]}}
            ]}
        """.trimIndent()
        // Each detail request's lines, in order; the first detail request about [second] fails.
        var now = Instant.parse("2026-09-29T08:00:00Z")
        val detailAsks = mutableListOf<List<String>>()
        var secondFailed = false
        val lookups = Job()
        val engine = MockEngine { request ->
            val ids = request.url.segments[1].split(",")
            val detail = request.url.parameters["detail"] == "true"
            if (detail) detailAsks += ids
            if (detail && second in ids && !secondFailed) {
                secondFailed = true
                respond(ByteReadChannel("{}"), HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(
                    ByteReadChannel(ids.joinToString(",", "[", "]") { line(it, detail) }),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = KtorTflClient(
            httpClient = http,
            baseUrl = "https://tfl.example", decodeDispatcher = serialDecode,
            alertDirections = LineAlertDirections(),
            alertDirectionScope = CoroutineScope(lookups),
            alertDirectionDispatcher = StandardTestDispatcher(testScheduler),
            clock = { now },
        )

        client.lineStatuses(listOf(first, second))
        advanceUntilIdle()
        lookups.children.toList().joinAll()
        assertEquals(listOf(listOf(first), listOf(second)), detailAsks)

        // The first line's directions were recorded; the failed one isn't asked about again at once.
        val statuses = client.lineStatuses(listOf(first, second)).associateBy { it.lineId }
        assertEquals(north, statuses.getValue(first).forDirection("inbound").fullText)
        assertFalse(statuses.getValue(first).awaitingDirections)
        assertTrue(statuses.getValue(second).awaitingDirections)
        advanceUntilIdle()
        lookups.children.toList().joinAll()
        assertEquals(2, detailAsks.size)
        // A minute on, it is, alone.
        now = now.plus(LineAlertDirections.RETRY_AFTER)
        client.lineStatuses(listOf(first, second))
        advanceUntilIdle()
        lookups.children.toList().joinAll()
        assertEquals(listOf(second), detailAsks.last())
        assertEquals(3, detailAsks.size)
    }

    @Test
    fun `a direction lookup's unsent requests aren't held back like the one that failed`() = runTest {
        val reason = "STATION A: routes are on diversion northbound via Street A."
        // Line ids long enough that TfL needs a request for each (LineStatusBatch).
        val first = "bus-" + "x".repeat(150)
        val second = "bus-" + "y".repeat(150)
        fun line(id: String, detail: Boolean) = """
            {"id": "$id", "name": "$id", "lineStatuses": [
              {"statusSeverity": 0, "statusSeverityDescription": "Special Service", "reason": "$reason",
               "disruption": {"affectedRoutes": [${if (detail) """{"direction": "inbound"}""" else ""}]}}
            ]}
        """.trimIndent()
        // The first detail request (about [first]) fails, so the one about [second] is never sent.
        val detailAsks = mutableListOf<List<String>>()
        var firstFailed = false
        val lookups = Job()
        val engine = MockEngine { request ->
            val ids = request.url.segments[1].split(",")
            val detail = request.url.parameters["detail"] == "true"
            if (detail) detailAsks += ids
            if (detail && first in ids && !firstFailed) {
                firstFailed = true
                respond(ByteReadChannel("{}"), HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(
                    ByteReadChannel(ids.joinToString(",", "[", "]") { line(it, detail) }),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = KtorTflClient(
            httpClient = http,
            baseUrl = "https://tfl.example", decodeDispatcher = serialDecode,
            alertDirections = LineAlertDirections(),
            alertDirectionScope = CoroutineScope(lookups),
            alertDirectionDispatcher = StandardTestDispatcher(testScheduler),
            clock = { Instant.parse("2026-09-29T08:00:00Z") },
        )

        client.lineStatuses(listOf(first, second))
        advanceUntilIdle()
        lookups.children.toList().joinAll()
        assertEquals(listOf(listOf(first)), detailAsks)

        // The next refresh, at once: the unsent line is asked about, the failed one waits.
        client.lineStatuses(listOf(first, second))
        advanceUntilIdle()
        lookups.children.toList().joinAll()
        assertEquals(listOf(listOf(first), listOf(second)), detailAsks)
    }

    @Test
    fun `parses and maps predictions, tolerating unknown fields`() = runTest {
        val departures = client(arrivalsJson).arrivals("940GZZLUVIC")

        assertEquals(3, departures.size)
        // Order is preserved from the response; sorting is the caller's job (Countdown).
        assertEquals("Victoria", departures[0].lineName)
        assertEquals("Walthamstow Central", departures[0].destination)
        assertEquals("Northbound - Platform 1", departures[0].platform)
        assertEquals(Instant.parse("2026-09-18T08:03:00Z"), departures[0].expectedArrival)
        // modeName is carried so the UI can color by mode; absent → "".
        assertEquals("tube", departures[0].mode)
        assertEquals("", departures[1].mode)
        assertEquals("bus", departures[2].mode)
    }

    @Test
    fun `retains TfL direction, defaulting to empty when the field is absent`() = runTest {
        val departures = client(arrivalsJson).arrivals("940GZZLUVIC")

        // TfL gives inbound/outbound on the tube predictions; the bus one omits it.
        assertEquals("inbound", departures[0].direction)
        assertEquals("outbound", departures[1].direction)
        assertEquals("", departures[2].direction)
    }

    @Test
    fun `blank platform is null and blank destination falls back to towards`() = runTest {
        val departures = client(arrivalsJson).arrivals("940GZZLUVIC")

        assertNull(departures[1].platform)
        assertEquals("Brixton", departures[1].destination)
        assertNull(departures[2].platform)
        assertEquals("Pimlico", departures[2].destination)
    }

    @Test
    fun `fills a blank direction from its platform's tagged trains as it fetches`() = runTest {
        // Kentish Town West's shape from the live feed: one Platform 1 service tagged `outbound`,
        // another prediction for it with no direction. The fetched snapshot carries the inferred
        // direction, so every surface reading it (widget, watch) groups the two as one row.
        val body =
            """
            [
              {"lineId": "mildmay", "lineName": "Mildmay", "platformName": "Platform 1", "direction": "outbound",
               "destinationName": "Clapham Junction Rail Station", "expectedArrival": "2026-09-18T08:06:00Z", "modeName": "overground"},
              {"lineId": "mildmay", "lineName": "Mildmay", "platformName": "Platform 1",
               "destinationName": "Clapham Junction Rail Station", "expectedArrival": "2026-09-18T08:30:00Z", "modeName": "overground"}
            ]
            """.trimIndent()

        val departures = client(body).arrivals("910GKNTSHTW")

        assertEquals(listOf("outbound", "outbound"), departures.map { it.direction })
    }

    @Test
    fun `requests the Arrivals endpoint and adds app_key only when set`() = runTest {
        var captured: HttpRequestData? = null
        client(arrivalsJson, capture = { captured = it }).arrivals("940GZZLUVIC")
        val keyless = checkNotNull(captured)
        assertEquals("/StopPoint/940GZZLUVIC/Arrivals", keyless.url.encodedPath)
        assertNull(keyless.url.parameters["app_key"])

        var capturedKeyed: HttpRequestData? = null
        client(arrivalsJson, appKey = "EXAMPLE", capture = { capturedKeyed = it }).arrivals("940GZZLUVIC")
        assertEquals("EXAMPLE", checkNotNull(capturedKeyed).url.parameters["app_key"])
    }

    @Test
    fun `parses line statuses, marking disruptions and leaving good service clean`() = runTest {
        val statuses = client(statusJson)
            .lineStatuses(listOf("victoria", "northern", "central", "43", "district", "circle"))
            .associateBy { it.lineId }

        // circle has no status entries → dropped as unknown, not fabricated into good
        // service (SPEC principle 1: don't manufacture a clean status from absent data).
        assertEquals(5, statuses.size)
        assertFalse(statuses.containsKey("circle"))
        assertFalse(statuses.getValue("victoria").disrupted)
        assertEquals("Good Service", statuses.getValue("victoria").description)
        assertTrue(statuses.getValue("northern").disrupted)
        assertEquals("Severe Delays", statuses.getValue("northern").description)
        // A good-service entry alongside a disruption must not mask it: filter to the
        // non-good statuses, then take the worst (lowest severity).
        assertTrue(statuses.getValue("central").disrupted)
        assertEquals("Minor Delays", statuses.getValue("central").description)
        // A bus's vague "Special Service" is replaced by the disruption its reason names,
        // and the line stays flagged (never turned into a good service).
        assertTrue(statuses.getValue("43").disrupted)
        assertEquals("Diversion", statuses.getValue("43").description)
        // An informative status (a weekend part closure) is still surfaced as TfL words it.
        assertTrue(statuses.getValue("district").disrupted)
        assertEquals("Part Closure", statuses.getValue("district").description)
    }

    @Test
    fun `batches the requested lines into one Line Status request`() = runTest {
        var captured: HttpRequestData? = null
        client(statusJson, capture = { captured = it }).lineStatuses(listOf("victoria", "northern"))
        // One request, both lines in the path segment (segments decode any encoding).
        assertEquals(
            listOf("Line", "victoria,northern", "Status"),
            checkNotNull(captured).url.segments,
        )
    }

    /** A client whose mock answers each `/Line/{ids}/Status` request by its ids via [answer]. */
    private fun lineClient(answer: (List<String>) -> Pair<HttpStatusCode, String>): KtorTflClient {
        val engine = MockEngine { request ->
            val (status, body) = answer(request.url.segments[1].split(","))
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode, appKey = { null })
    }

    private fun goodService(ids: List<String>) = ids.joinToString(",", "[", "]") { id ->
        """{"id": "$id", "name": "$id", "lineStatuses": [{"statusSeverity": 10, "statusSeverityDescription": "Good Service"}]}"""
    }

    // A busy hub's lines: joined in one segment they're 286 characters, which TfL refused with a
    // bare HTTP 400 ("Invalid URL"), so every line there read "couldn't check for disruptions".
    private val hubLines = listOf(
        "214", "46", "63", "205", "30", "73", "390", "91", "circle", "hammersmith-city", "metropolitan",
        "northern", "piccadilly", "victoria", "london-north-eastern-railway", "great-northern",
        "hull-trains", "grand-central", "thameslink", "southeastern", "eurostar", "east-midlands-railway",
        "avanti-west-coast", "west-midlands-trains", "n63", "n205", "n73", "n91", "lumo", "lioness",
    )

    @Test
    fun `a hub's many lines are split across requests TfL accepts`() = runTest {
        val segments = mutableListOf<List<String>>()
        val statuses = lineClient { ids ->
            segments += ids
            // Stands in for TfL's server: a longer segment is refused before the API sees it.
            if (ids.joinToString(",").length > 260) HttpStatusCode.BadRequest to "" else HttpStatusCode.OK to goodService(ids)
        }.lineStatuses(hubLines)

        assertEquals(2, segments.size)
        assertEquals(hubLines, segments.flatten())
        assertEquals(hubLines.toSet(), statuses.map { it.lineId }.toSet())
    }

    @Test
    fun `a split request with one unknown part keeps the lines TfL knows`() = runTest {
        // TfL 404s a request whose every id it doesn't know; the other part still answers.
        val statuses = lineClient { ids ->
            if ("lioness" in ids) HttpStatusCode.OK to goodService(ids) else HttpStatusCode.NotFound to "{}"
        }.lineStatuses(hubLines)

        assertTrue(statuses.any { it.lineId == "lioness" })
        assertFalse(statuses.any { it.lineId == "214" })
    }

    @Test
    fun `a split request TfL knows none of is still NotFound`() {
        assertThrows(TflException.NotFound::class.java) {
            runTest { lineClient { HttpStatusCode.NotFound to "{}" }.lineStatuses(hubLines) }
        }
    }

    @Test
    fun `no line ids makes no request and returns empty`() = runTest {
        var calls = 0
        val statuses = client(statusJson, capture = { calls++ }).lineStatuses(emptyList())
        assertEquals(0, calls)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `walks the disruption family tree, dropping blanks and deduping`() = runTest {
        // Regression: getFamily=true returns a DisruptedPointFamily object, so parsing it as
        // a flat array threw JsonConvertException for every stop and the "couldn't check for
        // disruptions" notice fired constantly. The walk must descend into children, drop the
        // blank entry, and collapse the notice repeated on a grandchild to one.
        val disruptions = client(disruptionJson).stopDisruptions("940GZZLUKSX")

        assertEquals(1, disruptions.size)
        assertEquals("Station closed until further notice.", disruptions[0].description)
    }

    @Test
    fun `maps a recorded bus closure's window`() = runTest {
        val warnings = mutableListOf<String>()
        val disruptions = client(busClosureJson, warn = { warnings += it }).stopDisruptions("490012149N")

        // One closure reported on both poles folds to one, with TfL's own bounds parsed.
        assertEquals(1, disruptions.size)
        assertEquals(Instant.parse("2026-09-14T08:00:00Z"), disruptions[0].validFrom)
        assertEquals(Instant.parse("2026-09-27T17:00:00Z"), disruptions[0].validTo)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `maps a stop disruption's window, leaving a missing or unreadable bound open`() = runTest {
        val json = """
            {
              "disruptions": [
                { "description": "Bus Stop Closed", "fromDate": "2026-01-01T09:00:00Z", "toDate": "2026-01-01T14:00:00Z" },
                { "description": "Stop moved", "fromDate": "not a date" }
              ],
              "children": []
            }
        """.trimIndent()

        val warnings = mutableListOf<String>()
        val disruptions = client(json, warn = { warnings += it }).stopDisruptions("490000001A")

        assertEquals(Instant.parse("2026-01-01T09:00:00Z"), disruptions[0].validFrom)
        assertEquals(Instant.parse("2026-01-01T14:00:00Z"), disruptions[0].validTo)
        assertNull(disruptions[1].validFrom)
        assertNull(disruptions[1].validTo)
        // The fallback is logged, not silent — and the raw value is not echoed.
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("490000001A"))
        assertFalse(warnings[0].contains("not a date"))
    }

    @Test
    fun `requests the stop Disruption endpoint, including family and route-blocked stops`() = runTest {
        var captured: HttpRequestData? = null
        client(disruptionJson, capture = { captured = it }).stopDisruptions("940GZZLUKSX")
        val req = checkNotNull(captured)
        assertEquals("/StopPoint/940GZZLUKSX/Disruption", req.url.encodedPath)
        // Without these a closure recorded against a child platform/entrance, or a
        // route-blocked stop, would be missed (SPEC principle 1).
        assertEquals("true", req.url.parameters["getFamily"])
        assertEquals("true", req.url.parameters["includeRouteBlockedStops"])
    }

    @Test
    fun `lifts out of service are read from TfL's lift disruptions, every station's in one request`() = runTest {
        // The /Disruptions/Lifts/v2 shape, trimmed; big interchanges, with a lift id that has a space.
        val json = """
            [{"stationUniqueId": "HUBBAN", "disruptedLiftUniqueIds": ["HUBBAN-Lift-7"], "message": "Bank: ..."},
             {"stationUniqueId": "940GZZLUECT", "disruptedLiftUniqueIds": ["940GZZLUECT-Lift-5", " HUBBAN-Lift 10 "]},
             {"stationUniqueId": "HUBKGX", "disruptedLiftUniqueIds": []}]
        """.trimIndent()
        var captured: HttpRequestData? = null
        val out = client(json, capture = { captured = it }).liftsOut()
        assertEquals("/Disruptions/Lifts/v2", checkNotNull(captured).url.encodedPath)
        assertEquals(setOf("HUBBAN-Lift-7", "940GZZLUECT-Lift-5", "HUBBAN-Lift 10"), out)
    }

    @Test
    fun `a lift disruption without its lifts fails to read rather than reading as none out`() = runTest {
        // A renamed field: the entry no longer says which lifts are out.
        val json = """[{"stationUniqueId": "HUBBAN", "liftIds": ["HUBBAN-Lift-7"]}]"""
        val error = runCatching { client(json).liftsOut() }.exceptionOrNull()
        assertTrue(error.toString(), error is TflException.Unreachable)
        // No disruptions anywhere is still an answer.
        assertEquals(emptySet<String>(), client("[]").liftsOut())
    }

    @Test
    fun `a failed lift disruptions request throws TfL's error rather than reading as no lifts out`() = runTest {
        val error = runCatching { client("{}", status = HttpStatusCode.ServiceUnavailable).liftsOut() }.exceptionOrNull()
        assertTrue(error.toString(), error is TflException.Unreachable)
    }

    // The multi-stop /StopPoint/{ids}/Disruption shape (no getFamily): a flat array, each entry
    // naming its stop. Trimmed from a recorded response; the ids are example poles.
    private val poleClosuresJson = """
        [
          {
            "${'$'}type": "Tfl.Api.Presentation.Entities.DisruptedPoint, Tfl.Api.Presentation.Entities",
            "atcoCode": "490000001A",
            "fromDate": "2026-09-11T07:00:00Z",
            "toDate": "2026-10-01T16:00:00Z",
            "description": "Bus Stop Closed",
            "commonName": "Example Road",
            "type": "Closure",
            "mode": "bus"
          },
          {
            "atcoCode": "490000001A",
            "description": "Bus Stop Closed",
            "fromDate": "2026-09-11T07:00:00Z",
            "toDate": "2026-10-01T16:00:00Z"
          },
          { "atcoCode": "490000009Z", "description": "Not asked for" }
        ]
    """.trimIndent()

    @Test
    fun `batches poles into one closure request, each pole getting only its own notices`() = runTest {
        var captured: HttpRequestData? = null
        val byStop = client(poleClosuresJson, capture = { captured = it })
            .poleDisruptions(listOf("490000001A", "490000001B"))

        val req = checkNotNull(captured)
        assertEquals("/StopPoint/490000001A,490000001B/Disruption", req.url.encodedPath)
        // TfL rejects getFamily for several stops, and a pole's family is its whole junction.
        assertNull(req.url.parameters["getFamily"])
        assertEquals("true", req.url.parameters["includeRouteBlockedStops"])
        // The repeated notice folds to one; the open pole maps to empty; an unrequested stop is dropped.
        assertEquals(setOf("490000001A", "490000001B"), byStop.keys)
        assertEquals(1, byStop.getValue("490000001A").size)
        assertEquals(Instant.parse("2026-10-01T16:00:00Z"), byStop.getValue("490000001A")[0].validTo)
        assertTrue(byStop.getValue("490000001B").isEmpty())
    }

    @Test
    fun `no poles makes no closure request`() = runTest {
        var calls = 0
        assertTrue(client(poleClosuresJson, capture = { calls++ }).poleDisruptions(emptyList()).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `parses nearby stops, cleaning names and dropping unidentifiable ones`() = runTest {
        val stops = client(nearbyJson).nearbyStops(latitude = 51.5, longitude = -0.12, radiusMeters = 350)

        // The third stopPoint has no usable id (blank id and naptanId) → dropped.
        assertEquals(2, stops.size)
        // Names cleaned of the TfL type suffix.
        assertEquals("Charing Cross", stops[0].name)
        assertEquals("Stratford", stops[1].name)
        // A blank `id` falls back to `naptanId`.
        assertEquals("940GZZLUCHX", stops[0].id)
        assertEquals("HUBSRA", stops[1].id)
        assertEquals(51.5, stops[0].latitude, 1e-6)
        assertEquals(-0.12, stops[0].longitude, 1e-6)
        // The interchange code is carried when TfL gives one, blank otherwise — a folded
        // near-me disruption alert titles by the hub (SPEC *Disruptions*).
        assertEquals("HUBCHX", stops[0].hubId)
        assertEquals("", stops[1].hubId)
    }

    @Test
    fun `recovers each line's mode from lineModeGroups and drops a blank-id line`() = runTest {
        val stratford = client(nearbyJson)
            .nearbyStops(latitude = 51.5, longitude = -0.12, radiusMeters = 350)
            .single { it.id == "HUBSRA" }

        // The blank-id line is dropped; the rest carry the mode from their lineModeGroup,
        // not the stop's first mode — so a mixed hub colors each line correctly.
        val modeByLine = stratford.lines.associate { it.id to it.mode }
        assertEquals(mapOf("central" to "tube", "dlr" to "dlr", "elizabeth" to "elizabeth-line"), modeByLine)
    }

    // A /StopPoint/{hubId} fixture for an interchange: its commonName carries a type suffix to prove
    // the name is cleaned like a stop's, and its `children` carry the member stations whose many
    // spellings become the disruption strip's alias set. Public place names only (SPEC *Privacy*).
    private val hubJson =
        """
        {
          "${'$'}type": "Tfl.Api.Presentation.Entities.StopPoint",
          "id": "HUBKGX",
          "commonName": "King's Cross St. Pancras Underground Station",
          "stopType": "TransportInterchange",
          "children": [
            { "commonName": "King's Cross Rail Station", "children": [
              { "commonName": "King's Cross Rail Station" }
            ] },
            { "commonName": "London St Pancras International LL Rail Station" },
            { "commonName": "St Pancras International Station" }
          ]
        }
        """.trimIndent()

    @Test
    fun `reads a stop area's poles with their letters and lines`() = runTest {
        // Synthetic ids: a stop area with two poles.
        val area = """
            {
              "naptanId": "490G00000001", "commonName": "Hill", "lat": 51.5, "lon": -0.12,
              "stopType": "NaptanOnstreetBusCoachStopPair", "modes": ["bus"],
              "children": [
                { "naptanId": "490000001K", "commonName": "Hill", "stopLetter": "K", "lat": 51.5, "lon": -0.12,
                  "modes": ["bus"], "stationNaptan": "490G00000001", "lines": [{ "id": "b1", "name": "B1" }] },
                { "naptanId": "490000001L", "commonName": "Hill", "stopLetter": "L", "lat": 51.5001, "lon": -0.12,
                  "modes": ["bus"], "stationNaptan": "490G00000001", "lines": [{ "id": "b2", "name": "B2" }] }
              ]
            }
        """.trimIndent()
        var captured: HttpRequestData? = null
        val poles = client(area, capture = { captured = it }).stopAreaPoles("490G00000001")
        assertEquals("/StopPoint/490G00000001", checkNotNull(captured).url.encodedPath)
        assertEquals(listOf("490000001K", "490000001L"), poles.map { it.id })
        assertEquals(listOf("K", "L"), poles.map { it.stopLetter })
        assertEquals(listOf("b2"), poles[1].lines.map { it.id })
        assertEquals("bus", poles[1].lines.single().mode)
        assertEquals("490G00000001", poles[1].clusterId)
    }

    @Test
    fun `resolves a hub display name, cleaned of its type suffix`() = runTest {
        var captured: HttpRequestData? = null
        val info = client(hubJson, capture = { captured = it }).hubInfo("HUBKGX")

        assertEquals("/StopPoint/HUBKGX", checkNotNull(captured).url.encodedPath)
        // The type suffix is stripped, like any stop name (SPEC *Concise copy*).
        assertEquals("King's Cross St. Pancras", info.name)
    }

    @Test
    fun `collects every member-station spelling as the alias set, cleaned and deduped`() = runTest {
        val info = client(hubJson).hubInfo("HUBKGX")
        // The hub's own name plus each member's, cleaned of type suffixes and deduplicated (the
        // repeated King's Cross platform collapses to one). These are the spellings the strip matches.
        assertEquals(
            listOf(
                "King's Cross St. Pancras",
                "King's Cross",
                "London St Pancras International LL",
                "St Pancras International",
            ),
            info.aliases,
        )
    }

    @Test
    fun `hub request adds app_key only when set`() = runTest {
        var keyless: HttpRequestData? = null
        client(hubJson, capture = { keyless = it }).hubInfo("HUBKGX")
        assertNull(checkNotNull(keyless).url.parameters["app_key"])

        var keyed: HttpRequestData? = null
        client(hubJson, appKey = "EXAMPLE", capture = { keyed = it }).hubInfo("HUBKGX")
        assertEquals("EXAMPLE", checkNotNull(keyed).url.parameters["app_key"])
    }

    @Test
    fun `a failed hub lookup throws, so the caller can fall back`() {
        // Like arrivals: a transport/decode failure throws (the ViewModel falls back to the
        // stop's own name), never a silent blank that would be mistaken for "no hub name".
        assertThrows(TflException.Unreachable::class.java) {
            runTest { client("{}", status = HttpStatusCode.InternalServerError).hubInfo("HUBKGX") }
        }
    }

    @Test
    fun `requests the StopPoint search with coordinates, radius, and stop types`() = runTest {
        var captured: HttpRequestData? = null
        client(nearbyJson, capture = { captured = it })
            .nearbyStops(latitude = 51.5, longitude = -0.12, radiusMeters = 350)
        val req = checkNotNull(captured)
        assertEquals("/StopPoint", req.url.encodedPath)
        assertEquals("51.5", req.url.parameters["lat"])
        assertEquals("-0.12", req.url.parameters["lon"])
        assertEquals("350", req.url.parameters["radius"])
        assertEquals(
            "NaptanMetroStation,NaptanRailStation,NaptanPublicBusCoachTram,NaptanFerryPort",
            req.url.parameters["stopTypes"],
        )
        // Without this the search returns line-less stops, so a suspended no-prediction line
        // couldn't surface as a status row (SPEC Disruptions).
        assertEquals("true", req.url.parameters["returnLines"])
        // Only the Direction properties are read; the facilities are most of a dense area's payload.
        assertEquals("Direction", req.url.parameters["categories"])
        assertNull(req.url.parameters["app_key"])
    }

    @Test
    fun `nearby search waits longer than the default for TfL to answer`() = runTest {
        // An uncached search in a dense area can take TfL past OkHttp's 10 s default to start
        // answering, which failed the whole nearby list with a timeout.
        var captured: HttpRequestData? = null
        client(nearbyJson, httpTimeout = true, capture = { captured = it })
            .nearbyStops(latitude = 51.5, longitude = -0.12, radiusMeters = 350)
        assertEquals(
            KtorTflClient.SLOW_SOCKET_TIMEOUT_MILLIS,
            checkNotNull(captured).getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis,
        )
    }

    @Test
    fun `other requests keep the default timeout`() = runTest {
        var captured: HttpRequestData? = null
        client(arrivalsJson, httpTimeout = true, capture = { captured = it }).arrivals("940GZZLUVIC")
        assertNull(checkNotNull(captured).getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis)
    }

    @Test
    fun `nearby search adds app_key only when set`() = runTest {
        var captured: HttpRequestData? = null
        client(nearbyJson, appKey = "EXAMPLE", capture = { captured = it })
            .nearbyStops(latitude = 51.5, longitude = -0.12, radiusMeters = 350)
        assertEquals("EXAMPLE", checkNotNull(captured).url.parameters["app_key"])
    }

    @Test
    fun `a 429 maps to RateLimited, not an empty list`() {
        assertThrows(TflException.RateLimited::class.java) {
            runTest { client("{}", status = HttpStatusCode.TooManyRequests).arrivals("940GZZLUVIC") }
        }
    }

    @Test
    fun `a 401 or 403 answering the user's key maps to KeyRejected`() {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            assertThrows("$status", TflException.KeyRejected::class.java) {
                runTest { client("{}", status = status, appKey = "EXAMPLE").arrivals("940GZZLUVIC") }
            }
        }
    }

    @Test
    fun `a keyless 401 or 403 says nothing about a key, so it stays Unreachable`() {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            assertThrows("$status", TflException.Unreachable::class.java) {
                runTest { client("{}", status = status).arrivals("940GZZLUVIC") }
            }
        }
    }

    @Test
    fun `how TfL answered the user's key is reported, for the app-wide bar`() = runTest {
        val answers = mutableListOf<Pair<String, Boolean>>()
        client("[]", appKey = "EXAMPLE", keyAnswered = { key, rejected -> answers += key to rejected }).arrivals("940GZZLUVIC")
        assertEquals(listOf("EXAMPLE" to false), answers)
        runCatching { client("{}", status = HttpStatusCode.Forbidden, appKey = "EXAMPLE", keyAnswered = { key, rejected -> answers += key to rejected }).arrivals("940GZZLUVIC") }
        assertEquals(listOf("EXAMPLE" to false, "EXAMPLE" to true), answers)
        // Keyless, or a failure that says nothing about the key: nothing to report.
        client("[]", keyAnswered = { key, rejected -> answers += key to rejected }).arrivals("940GZZLUVIC")
        runCatching { client("{}", status = HttpStatusCode.InternalServerError, appKey = "EXAMPLE", keyAnswered = { key, rejected -> answers += key to rejected }).arrivals("940GZZLUVIC") }
        assertEquals(2, answers.size)
    }

    @Test
    fun `a 404 maps to NotFound, so a line TfL doesn't know isn't retried`() {
        assertThrows(TflException.NotFound::class.java) {
            runTest { client("{}", status = HttpStatusCode.NotFound).lineStatuses(listOf("caledonian-sleeper")) }
        }
    }

    @Test
    fun `another non-2xx maps to Unreachable`() {
        assertThrows(TflException.Unreachable::class.java) {
            runTest { client("{}", status = HttpStatusCode.InternalServerError).arrivals("940GZZLUVIC") }
        }
    }

    @Test
    fun `an unresolved host maps to Offline`() {
        // No DNS resolution is the device-is-offline signal.
        val client = throwingClient(UnknownHostException("api.tfl.example"))
        assertThrows(TflException.Offline::class.java) {
            runTest { client.arrivals("940GZZLUVIC") }
        }
    }

    @Test
    fun `a transport failure while online maps to Network, not Offline or Unreachable`() {
        // A read timeout (device online, TfL slow/down) is a subclass of IOException
        // but not UnknownHostException, so it's Network — the UI must not tell an online
        // user they're offline, nor blame TfL's server for a request that never completed.
        val client = throwingClient(SocketTimeoutException("read timed out"))
        assertThrows(TflException.Network::class.java) {
            runTest { client.arrivals("940GZZLUVIC") }
        }
    }

    private fun throwingClient(error: IOException): KtorTflClient {
        val engine = MockEngine { throw error }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorTflClient(httpClient = http, baseUrl = "https://tfl.example", decodeDispatcher = serialDecode)
    }

    // A trimmed /StopPoint/Search response: public station names only. The second match repeats the
    // first's id (TfL can list one hub under two spellings) and the third has no id.
    private val searchJson =
        """
        {
          "query": "oxford",
          "total": 3,
          "matches": [
            { "id": "940GZZLUOXC", "name": "Oxford Circus Underground Station", "modes": ["tube", "bus"], "zone": "1" },
            { "id": "940GZZLUOXC", "name": "Oxford Circus", "modes": ["tube"] },
            { "id": "", "name": "Nowhere" }
          ]
        }
        """.trimIndent()

    @Test
    fun `station search sends the typed query and maps cleaned, deduped matches`() = runTest {
        var captured: HttpRequestData? = null
        val matches = client(searchJson, capture = { captured = it }).searchStations("oxford ci")
        val url = captured!!.url
        assertEquals("/StopPoint/Search", url.encodedPath)
        assertEquals("oxford ci", url.parameters["query"])
        assertEquals("interchanges are asked for", "true", url.parameters["includeHubs"])
        assertEquals(listOf("Oxford Circus"), matches.map { it.name })
        assertEquals(listOf("tube", "bus"), matches.single().modes)
    }

    // A trimmed /StopPoint/{hubId} tree: an interchange holding a tube station (whose platform child
    // must not become a stop of its own), a bus stop area with one line-serving pole and one
    // line-less pole, and an entrance. Public names and ids only.
    private val stationTreeJson =
        """
        {
          "id": "HUBEXA",
          "commonName": "Example Interchange",
          "stopType": "TransportInterchange",
          "children": [
            {
              "id": "940GZZLUEXA",
              "commonName": "Example Underground Station",
              "stopType": "NaptanMetroStation",
              "stationNaptan": "940GZZLUEXA",
              "hubNaptanCode": "HUBEXA",
              "modes": ["tube"],
              "lines": [{ "id": "victoria", "name": "Victoria" }],
              "lineModeGroups": [{ "modeName": "tube", "lineIdentifier": ["victoria"] }],
              "children": [
                { "id": "9400ZZLUEXA1", "commonName": "Example Underground Station", "stopType": "NaptanMetroPlatform",
                  "lines": [{ "id": "victoria", "name": "Victoria" }] }
              ]
            },
            {
              "id": "490G00000001",
              "commonName": "Example Station",
              "stopType": "NaptanOnstreetBusCoachStopCluster",
              "children": [
                { "id": "490000000001A", "commonName": "Example Station", "stopType": "NaptanPublicBusCoachTram",
                  "stationNaptan": "490G00000001", "stopLetter": "A", "modes": ["bus"],
                  "lines": [{ "id": "73", "name": "73" }] },
                { "id": "490000000001B", "commonName": "Example Station", "stopType": "NaptanPublicBusCoachTram",
                  "stationNaptan": "490G00000001", "modes": ["bus"], "lines": [] }
              ]
            },
            { "id": "940GZZLUEXA-ENT", "commonName": "Example Entrance", "stopType": "NaptanMetroEntrance" }
          ]
        }
        """.trimIndent()

    @Test
    fun `a station lookup returns the tree's departure-bearing stops, not platforms or entrances`() = runTest {
        var captured: HttpRequestData? = null
        val stops = client(stationTreeJson, capture = { captured = it }).stationStops("HUBEXA")
        assertEquals("/StopPoint/HUBEXA", captured!!.url.encodedPath)
        assertEquals(listOf("940GZZLUEXA", "490000000001A"), stops.map { it.id })
        assertEquals(listOf("tube"), stops.first().lines.map { it.mode })
        assertEquals("A", stops.last().stopLetter)
    }

    @Test
    fun `a searched stop that is itself a pole returns itself`() = runTest {
        val pole = """
            { "id": "490000000001A", "commonName": "Example Station", "stopType": "NaptanPublicBusCoachTram",
              "modes": ["bus"], "lines": [{ "id": "73", "name": "73" }] }
        """.trimIndent()
        assertEquals(listOf("490000000001A"), client(pole).stationStops("490000000001A").map { it.id })
    }

    @Test
    fun `a failed station lookup throws a domain error`() {
        assertThrows(TflException::class.java) {
            runTest { client("{}", status = HttpStatusCode.InternalServerError).stationStops("HUBEXA") }
        }
    }

    // Constructed Journey Planner responses for the postcode resolver (live TfL is unreachable here).
    // Coordinates and names are synthetic; no postcode names a real place (SPEC *Privacy*).

    // A postcode TfL geocodes outright (HTTP 200): the journey's first leg walks from the resolved
    // point, which carries the coordinate.
    private val postcodeResolvedJson =
        """
        { "journeys": [ { "legs": [ {
          "mode": { "id": "walking", "name": "walking" },
          "departurePoint": { "commonName": "X1 9XX", "lat": 51.53, "lon": -0.11 },
          "arrivalPoint": { "commonName": "Anchor Station", "naptanId": "940GZZLUKSX" }
        } ] } ] }
        """.trimIndent()

    // A postcode TfL can't pin to one point (HTTP 300): it offers look-alike places.
    private val postcodeDisambiguationJson =
        """
        {
          "fromLocationDisambiguation": {
            "matchStatus": "list",
            "disambiguationOptions": [
              { "place": { "commonName": "X1 9XX", "lat": 51.53, "lon": -0.10 }, "matchQuality": 900 },
              { "place": { "commonName": "X1 9XY", "lat": 51.54, "lon": -0.11 }, "matchQuality": 800 }
            ]
          },
          "toLocationDisambiguation": { "matchStatus": "identified" }
        }
        """.trimIndent()

    @Test
    fun `resolves a postcode TfL geocodes directly to its origin point`() = runTest {
        val resolution = client(postcodeResolvedJson).resolvePostcode("X1 9XX")
        // A 200 direct resolution: unambiguous, so the caller may adopt it (SPEC D9).
        assertTrue(resolution is PostcodeResolution.Resolved)
        val place = (resolution as PostcodeResolution.Resolved).place
        assertEquals("X1 9XX", place.name)
        assertEquals(51.53, place.coordinate.latitude, 1e-9)
        assertEquals(-0.11, place.coordinate.longitude, 1e-9)
    }

    @Test
    fun `offers every look-alike place when TfL disambiguates a postcode`() = runTest {
        val resolution = client(postcodeDisambiguationJson, status = HttpStatusCode.MultipleChoices)
            .resolvePostcode("X1 9XX")
        assertTrue(resolution is PostcodeResolution.Options)
        val places = (resolution as PostcodeResolution.Options).places
        assertEquals(listOf("X1 9XX", "X1 9XY"), places.map { it.name })
        assertEquals(51.53, places[0].coordinate.latitude, 1e-9)
        assertEquals(-0.11, places[1].coordinate.longitude, 1e-9)
    }

    @Test
    fun `a disambiguation surviving to one place is still Options, never a resolution`() = runTest {
        val json =
            """
            { "fromLocationDisambiguation": { "disambiguationOptions": [
              { "place": { "commonName": "X1 9XX", "lat": 51.50, "lon": 0.0 } },
              { "place": { "commonName": "X1 9XY", "lat": 0.0, "lon": 0.0 } },
              { "place": { "commonName": "X1 9XZ" } }
            ] } }
            """.trimIndent()
        val resolution = client(json, status = HttpStatusCode.MultipleChoices).resolvePostcode("X1 9XX")
        // A 300 stays a disambiguation even when only one option carries a position — the caller must not
        // auto-adopt it as if TfL had resolved the postcode (SPEC D9). Only the Greenwich-meridian point
        // survives: London sits on lon 0.0, so it's a real place; the (0,0) unset sentinel and the
        // coordinate-less place both drop.
        assertTrue(resolution is PostcodeResolution.Options)
        val places = (resolution as PostcodeResolution.Options).places
        assertEquals(listOf("X1 9XX"), places.map { it.name })
        assertEquals(51.50, places[0].coordinate.latitude, 1e-9)
        assertEquals(0.0, places[0].coordinate.longitude, 1e-9)
    }

    @Test
    fun `a disambiguation whose options are all unreadable is an error, not no-result`() {
        // 300 with options offered but none decodable (no coordinate): schema drift, surfaced as a
        // retryable error rather than a false no-result — symmetric with the 200 path.
        val json =
            """
            { "fromLocationDisambiguation": { "disambiguationOptions": [
              { "place": { "commonName": "X1 9XX" } },
              { "place": { "commonName": "X1 9XY" } }
            ] } }
            """.trimIndent()
        assertThrows(TflException::class.java) {
            runTest { client(json, status = HttpStatusCode.MultipleChoices).resolvePostcode("X1 9XX") }
        }
    }

    @Test
    fun `a list-status disambiguation with no usable options is an error, not no-result`() {
        // matchStatus says there's a list to choose from, but the options array is absent (a renamed or
        // dropped field). Serialization defaults it to empty, so this must be caught as schema drift.
        val json =
            """
            { "fromLocationDisambiguation": { "matchStatus": "list" } }
            """.trimIndent()
        assertThrows(TflException::class.java) {
            runTest { client(json, status = HttpStatusCode.MultipleChoices).resolvePostcode("X1 9XX") }
        }
    }

    @Test
    fun `a resolved journey with an unreadable origin is an error, not no-result`() {
        // 200 with a journey whose origin carries no coordinate: a decode/shape failure, surfaced as a
        // retryable error rather than a false "the postcode places nowhere".
        val json =
            """
            { "journeys": [ { "legs": [ { "departurePoint": { "commonName": "X1 9XX" } } ] } ] }
            """.trimIndent()
        assertThrows(TflException::class.java) {
            runTest { client(json).resolvePostcode("X1 9XX") }
        }
    }

    @Test
    fun `requests the Journey Planner from the postcode to the anchor, url-encoded`() = runTest {
        var captured: HttpRequestData? = null
        client(postcodeResolvedJson, appKey = "EXAMPLE", capture = { captured = it }).resolvePostcode("X1 9XX")
        val request = checkNotNull(captured)
        assertEquals("/Journey/JourneyResults/X1%209XX/to/940GZZLUKSX", request.url.encodedPath)
        assertEquals("EXAMPLE", request.url.parameters["app_key"])
    }

    // A place name TfL geocodes to one point (HTTP 200). Synthetic name and coordinates (AGENTS *Privacy*).
    private val placeResolvedJson =
        """
        { "journeys": [ { "legs": [ {
          "departurePoint": { "commonName": "Zeta Hall", "lat": 51.50, "lon": -0.10 },
          "arrivalPoint": { "commonName": "Anchor Station", "naptanId": "940GZZLUKSX" }
        } ] } ] }
        """.trimIndent()

    // A place name TfL offers look-alikes for (HTTP 300). Synthetic names and coordinates.
    private val placeDisambiguationJson =
        """
        { "fromLocationDisambiguation": { "matchStatus": "list", "disambiguationOptions": [
          { "place": { "commonName": "Zeta Hall", "lat": 51.50, "lon": -0.10 } },
          { "place": { "commonName": "Zeta Gardens", "lat": 51.49, "lon": -0.13 } }
        ] } }
        """.trimIndent()

    @Test
    fun `searchPlaces returns the single origin place TfL geocodes a name to`() = runTest {
        val places = client(placeResolvedJson).searchPlaces("zeta hall")
        assertEquals(listOf("Zeta Hall"), places.map { it.name })
        assertEquals(51.50, places[0].coordinate.latitude, 1e-9)
        assertEquals(-0.10, places[0].coordinate.longitude, 1e-9)
    }

    @Test
    fun `searchPlaces returns every look-alike place when TfL disambiguates a name`() = runTest {
        val places = client(placeDisambiguationJson, status = HttpStatusCode.MultipleChoices).searchPlaces("zeta")
        assertEquals(listOf("Zeta Hall", "Zeta Gardens"), places.map { it.name })
    }

    @Test
    fun `searchPlaces requests the Journey Planner from the typed query to the anchor, url-encoded`() = runTest {
        var captured: HttpRequestData? = null
        client(placeResolvedJson, appKey = "EXAMPLE", capture = { captured = it }).searchPlaces("zeta hall")
        val request = checkNotNull(captured)
        assertEquals("/Journey/JourneyResults/zeta%20hall/to/940GZZLUKSX", request.url.encodedPath)
    }
}
