package app.stopdash.data

import app.stopdash.domain.CallingPortion
import app.stopdash.domain.Departure
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RailAwareTflClient
import app.stopdash.domain.RailFeed
import app.stopdash.domain.RailStationCodes
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.railRideArrivals
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorDarwinClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a board is read off the thread that asked for it, so a screen's own load never freezes it`() {
        // The example board with a train whose time can't be read: reading it says so, on the
        // thread it's read on.
        val example = json.decodeFromString<DarwinBoardDto>(
            checkNotNull(javaClass.getResource("/fixtures/darwin_board_example.json")).readText(),
        )
        val odd = example.trainServices!!.first().copy(etd = "soon-ish")
        val body = json.encodeToString(DarwinBoardDto.serializer(), example.copy(trainServices = example.trainServices + odd))
        val engine = MockEngine { respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val warnedOn = app.stopdash.ThreadRecorder()
        val client = KtorDarwinClient(http, apiKey = { "EXAMPLE" }, baseUrl = "https://darwin.example", warn = { warnedOn.note() })
        // The caller's own single thread, as a screen's main thread is.
        val caller = Executors.newSingleThreadExecutor()
        try {
            val callerThread = caller.submit<Thread> { Thread.currentThread() }.get()
            runBlocking(caller.asCoroutineDispatcher()) { client.board("WAT") }
            val warned = warnedOn.threads()
            assertTrue(warned.isNotEmpty())
            assertTrue(warned.none { it == callerThread.name.substringBefore(" @") })
        } finally {
            caller.shutdown()
        }
    }

    @Test
    fun `the board with details is asked for on its own, ten trains long`() {
        val example = json.decodeFromString<DarwinBoardDto>(
            checkNotNull(javaClass.getResource("/fixtures/darwin_board_example.json")).readText(),
        )
        val details = example.copy(
            trainServices = listOf(
                example.trainServices!![0].copy(
                    subsequentCallingPoints = listOf(DarwinCallingPointsDto(listOf(DarwinCallingPointDto("Guildford", "GLD")))),
                ),
            ),
        )
        var fail = false
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += request.url.encodedPath.split('/').dropLast(1).last() + " " + request.url.parameters["numRows"]
            if (fail) return@MockEngine respond("", HttpStatusCode.InternalServerError)
            respond(ByteReadChannel(json.encodeToString(DarwinBoardDto.serializer(), details)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val client = KtorDarwinClient(
            http, apiKey = { "EXAMPLE" }, baseUrl = "https://darwin.example",
            stopIdsFor = { if (it == "GLD") setOf("910GGUILDFD") else emptySet() },
        )
        val board = checkNotNull(runBlocking { client.boardWithDetails("WAT") })
        assertEquals(listOf("GetDepBoardWithDetails 10"), asked)
        assertEquals(listOf(CallingPortion(setOf("910GGUILDFD"), complete = true)), board.departures[0].callingAt)
        // A failure is reported as a board's is, for the caller to log and stand without.
        fail = true
        assertTrue(runCatching { runBlocking { client.boardWithDetails("WAT") } }.exceptionOrNull() is TflException)
        // No key, nothing asked.
        val keyless = KtorDarwinClient(http, apiKey = { null }, baseUrl = "https://darwin.example")
        assertEquals(null, runBlocking { keyless.boardWithDetails("WAT") })
    }

    @Test
    fun `without a key of the rider's own, boards come through the relay, carrying nothing of theirs`() {
        val board = checkNotNull(javaClass.getResource("/fixtures/darwin_board_example.json")).readText()
        val details = checkNotNull(javaClass.getResource("/fixtures/darwin_board_details_st_pancras.json")).readText()
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += "${request.url.host}${request.url.encodedPath}${request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()} ${request.headers["x-apikey"]}"
            val body = if (request.url.encodedPath.contains("details", ignoreCase = true)) details else board
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        var key: String? = null
        val client = KtorDarwinClient(http, apiKey = { key }, baseUrl = "https://darwin.example/api", relayUrl = { "https://relay.example/" })
        assertTrue(client.available)
        runBlocking {
            client.board("WAT")
            client.boardWithDetails("WAT")
            client.runs("STP", "BTN")
            client.runs("STP", "BTN", offsetMinutes = 45)
        }
        assertEquals(
            listOf(
                "relay.example/v1/board/WAT null",
                "relay.example/v1/board/WAT/details null",
                "relay.example/v1/board/STP/details?to=BTN null",
                "relay.example/v1/board/STP/details?to=BTN&offset=45 null",
            ),
            asked,
        )
        // A key of the rider's own goes straight to National Rail, as before the relay.
        key = "EXAMPLE"
        runBlocking { client.board("WAT") }
        assertEquals("darwin.example/api/GetDepartureBoard/WAT?numRows=20 EXAMPLE", asked.last())
        // Neither: nothing to ask.
        key = null
        assertTrue(!KtorDarwinClient(http, apiKey = { key }, relayUrl = { null }).available)
    }

    @Test
    fun `a board asked for before the saved key is read back waits for it, and goes the way the key says`() = kotlinx.coroutines.test.runTest {
        val board = checkNotNull(javaClass.getResource("/fixtures/darwin_board_example.json")).readText()
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += "${request.url.host} ${request.headers["x-apikey"]}"
            respond(ByteReadChannel(board), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val loaded = kotlinx.coroutines.flow.MutableStateFlow(false)
        var key: String? = null
        val client = KtorDarwinClient(
            http, apiKey = { key }, baseUrl = "https://darwin.example/api", relayUrl = { "https://relay.example" },
            keyLoaded = { loaded.first { it } },
        )
        // Not refused while the key is read back: a restored trip's first board isn't a failure (Codex, #944).
        assertTrue(client.available)
        val read = async { client.board("WAT") }
        runCurrent()
        assertEquals(emptyList<String>(), asked)
        // The saved key turns out to be the rider's own: straight to National Rail, never the relay.
        key = "EXAMPLE"
        loaded.value = true
        read.await()
        assertEquals(listOf("darwin.example EXAMPLE"), asked)
    }

    @Test
    fun `a key never read back gives no board, and the relay isn't asked in its place`() = kotlinx.coroutines.test.runTest {
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += request.url.host
            respond(ByteReadChannel("{}"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val client = KtorDarwinClient(http, apiKey = { null }, relayUrl = { "https://relay.example" }, keyLoaded = { awaitCancellation() })
        val failed = try {
            client.board("WAT")
            null
        } catch (e: TflException.Unreachable) {
            e.message
        }
        assertEquals("national rail key not read yet", failed)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a relay board carries the age the relay gave it`() {
        val board = checkNotNull(javaClass.getResource("/fixtures/darwin_board_example.json")).readText()
        var age: String? = "17"
        val engine = MockEngine {
            val headers = listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), age?.let { HttpHeaders.Age to listOf(it) })
            respond(ByteReadChannel(board), HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val client = KtorDarwinClient(http, apiKey = { null }, relayUrl = { "https://relay.example" })
        assertEquals(java.time.Duration.ofSeconds(17), runBlocking { client.board("WAT") }.age)
        // None, or one that can't be read, is no age at all.
        age = null
        assertEquals(java.time.Duration.ZERO, runBlocking { client.board("WAT") }.age)
        age = "soon"
        assertEquals(java.time.Duration.ZERO, runBlocking { client.board("WAT") }.age)
    }

    @Test
    fun `a followed train's relay board carries its age, as a plain board does`() {
        val body = checkNotNull(javaClass.getResource("/fixtures/darwin_board_details_st_pancras.json")).readText()
        val engine = MockEngine {
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.Age to listOf("23")))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val runs = runBlocking { KtorDarwinClient(http, apiKey = { null }, relayUrl = { "https://relay.example" }).runs("STP", "BTN", 0) }
        assertTrue(runs!!.runs.isNotEmpty())
        assertEquals(java.time.Duration.ofSeconds(23), runs.age)
    }

    @Test
    fun `a relay's failure names what went wrong upstream, and nothing else from its body`() {
        var body = """{"error":"national rail unreachable: TimeoutError"}"""
        val engine = MockEngine {
            respond(ByteReadChannel(body), HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val client = KtorDarwinClient(http, apiKey = { null }, relayUrl = { "https://relay.example" })
        fun failure() = try {
            runBlocking { client.board("WAT") }
            null
        } catch (e: TflException.Unreachable) {
            e.message
        }
        assertEquals("HTTP 502 (national rail unreachable: TimeoutError)", failure())
        body = """{"error":"national rail answered 500"}"""
        assertEquals("HTTP 502 (national rail answered 500)", failure())
        // Anything else the body says is never repeated.
        body = """{"error":"national rail unreachable: see https://example.com/?key=EXAMPLE"}"""
        assertEquals("HTTP 502", failure())
    }

    @Test
    fun `a relay's failure body is read off the thread that asked for it`() {
        val engine = MockEngine {
            respond(ByteReadChannel("""{"error":"national rail unreachable: TimeoutError"}"""), HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        // The decode dispatcher, noting which work it was handed.
        val decoder = Executors.newSingleThreadExecutor()
        val handed = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val decode = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                handed += context[kotlinx.coroutines.CoroutineName]?.name
                decoder.execute(block)
            }
        }
        val client = KtorDarwinClient(http, apiKey = { null }, relayUrl = { "https://relay.example" }, decodeDispatcher = decode)
        // The caller's own single thread, as a screen's main thread is.
        val caller = Executors.newSingleThreadExecutor()
        try {
            val failed = runBlocking(caller.asCoroutineDispatcher()) {
                try {
                    client.board("WAT")
                    null
                } catch (e: TflException.Unreachable) {
                    e.message
                }
            }
            assertEquals("HTTP 502 (national rail unreachable: TimeoutError)", failed)
            assertTrue(KtorDarwinClient.FAILURE_BODY.name in handed)
        } finally {
            caller.shutdown()
            decoder.shutdown()
        }
    }

    @Test
    fun `a followed train's board is asked toward its terminus, with each train's calls and times`() {
        val body = checkNotNull(javaClass.getResource("/fixtures/darwin_board_details_st_pancras.json")).readText()
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            val params = request.url.parameters
            asked += "${request.url.encodedPath.substringAfterLast("/api/20220120/")} ${params["numRows"]} ${params["filterCrs"]} ${params["filterType"]} ${params["timeOffset"]} ${request.headers["x-apikey"]}"
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val client = KtorDarwinClient(http, apiKey = { "EXAMPLE" }, baseUrl = "https://darwin.example/api/20220120")
        val runs = checkNotNull(runBlocking { client.runs("STP", "BTN") })
        assertEquals(listOf("GetDepBoardWithDetails/STP 10 BTN to null EXAMPLE"), asked)
        assertEquals(listOf("ZFD", "CTK", "BFR", "LBG", "GTW", "BTN"), runs.runs.first().next.map { it.crs })
        // From so many minutes on, for a train further out than the first ten (Codex, #883).
        runBlocking { client.runs("STP", "BTN", offsetMinutes = 45) }
        assertEquals("GetDepBoardWithDetails/STP 10 BTN to 45 EXAMPLE", asked.last())
        // No key, nothing asked.
        val keyless = KtorDarwinClient(http, apiKey = { " " }, baseUrl = "https://darwin.example/api/20220120")
        assertEquals(null, runBlocking { keyless.runs("STP", "BTN") })
        assertEquals(2, asked.size)
    }

    @Test
    fun `a board Darwin has suppressed fails, never reads as one with no trains (Codex, #883)`() {
        val engine = MockEngine {
            respond(
                ByteReadChannel("""{"generatedAt":"2026-10-15T17:00:00+01:00","crs":"STP","areServicesAvailable":false}"""),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val client = KtorDarwinClient(http, apiKey = { "EXAMPLE" }, baseUrl = "https://darwin.example/api/20220120")
        assertTrue(runCatching { runBlocking { client.runs("STP", "BTN") } }.exceptionOrNull() is TflException.Unreachable)
        assertTrue(runCatching { runBlocking { client.board("STP") } }.exceptionOrNull() is TflException.Unreachable)
    }

    @Test
    fun `a key cleared after it was checked fails the board, never reads as one with no trains (Codex, #883)`() {
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += request.url.encodedPath
            respond("", HttpStatusCode.InternalServerError)
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        // Set when it's checked for, cleared in Settings by the time the board is asked for.
        var reads = 0
        val client = KtorDarwinClient(http, apiKey = { if (reads++ == 0) "EXAMPLE" else null }, baseUrl = "https://darwin.example")
        assertTrue(client.available)
        assertTrue(runCatching { runBlocking { client.board("STP") } }.exceptionOrNull() is TflException.Unreachable)
        // So a trip's National Rail ride read in that gap fails its board, and the station says it has no key.
        reads = 0
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String) = emptyList<Departure>()
            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val trip = RailAwareTflClient(tfl, client, { RailStationCodes(mapOf("STPX" to "STP")) }, boardAtEveryStop = true)
        assertTrue(runCatching { runBlocking { trip.railRideArrivals("910GSTPX") } }.exceptionOrNull() is TflException.Unreachable)
        assertEquals(RailFeed.NO_KEY, trip.railFeed("910GSTPX"))
        assertEquals(emptyList<String>(), asked)
    }
}
