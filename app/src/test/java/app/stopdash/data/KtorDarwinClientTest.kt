package app.stopdash.data

import app.stopdash.domain.CallingPortion
import app.stopdash.domain.TflException
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
        val warnedOn = mutableListOf<Thread>()
        val client = KtorDarwinClient(http, apiKey = { "EXAMPLE" }, baseUrl = "https://darwin.example", warn = { warnedOn += Thread.currentThread() })
        // The caller's own single thread, as a screen's main thread is.
        val caller = Executors.newSingleThreadExecutor()
        try {
            val callerThread = caller.submit<Thread> { Thread.currentThread() }.get()
            runBlocking(caller.asCoroutineDispatcher()) { client.board("WAT") }
            assertTrue(warnedOn.isNotEmpty())
            assertTrue(warnedOn.none { it == callerThread })
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
}
