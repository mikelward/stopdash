package app.stopdash.data

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
}
