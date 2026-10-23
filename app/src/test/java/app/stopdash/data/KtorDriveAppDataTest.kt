package app.stopdash.data

import app.stopdash.domain.DriveClock
import app.stopdash.domain.DriveFailure
import app.stopdash.domain.DriveFile
import app.stopdash.domain.DriveListing
import app.stopdash.domain.DriveResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorDriveAppDataTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val warnings = mutableListOf<String>()

    // Drive's clock on its answers: 10:00:00 GMT on 22 October 2026.
    private val dateHeader = "Thu, 22 Oct 2026 10:00:00 GMT"
    private val dateMillis = 1_792_663_200_000L

    private fun client(
        token: String? = TOKEN,
        elapsed: Long = 5_000,
        // As the app's shared clients are (KtorTflClient's throws on a refusal): the answers are judged here.
        expectSuccess: Boolean = true,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorDriveAppData {
        val engine = MockEngine { request ->
            requests += request
            answer(request)
        }
        return KtorDriveAppData(
            http = HttpClient(engine) { this.expectSuccess = expectSuccess },
            token = { token },
            elapsed = { elapsed },
            warn = { warnings += it },
            baseUrl = "https://drive.example",
        )
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.Date to listOf(dateHeader)))

    @Test
    fun `a listing reads every page of the app data folder, with Drive's clock from its first answer`() = runBlocking {
        val drive = client(elapsed = 5_000) { request ->
            if (request.url.parameters["pageToken"] == null) {
                json("""{"nextPageToken":"p2","files":[{"id":"1","name":"phone-a.json","modifiedTime":"2026-10-22T09:00:00.000Z"}]}""")
            } else {
                json("""{"files":[{"id":"2","name":"phone-b.json","modifiedTime":"2026-10-22T09:30:00.000Z","size":"12"}]}""")
            }
        }
        val listing = drive.list()
        assertEquals(
            DriveResult.Ok(
                DriveListing(
                    listOf(
                        DriveFile("1", "phone-a.json", "2026-10-22T09:00:00.000Z"),
                        DriveFile("2", "phone-b.json", "2026-10-22T09:30:00.000Z"),
                    ),
                    DriveClock(dateMillis, 5_000),
                ),
            ),
            listing,
        )
        assertEquals(2, requests.size)
        // Only the app data folder, and the second page by Drive's token.
        assertTrue(requests.all { it.url.parameters["spaces"] == "appDataFolder" && it.url.encodedPath == "/drive/v3/files" })
        assertEquals("p2", requests[1].url.parameters["pageToken"])
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer $TOKEN" })
    }

    @Test
    fun `a listing without Drive's clock can't be read`() = runBlocking {
        val drive = client { respond("""{"files":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals(DriveResult.Failed(DriveFailure.Unreadable), drive.list())
        assertEquals(listOf("drive list: answer without Drive's clock"), warnings)
    }

    @Test
    fun `a file is downloaded as its text`() = runBlocking {
        val drive = client { json("""{"version":1,"kinds":{}}""") }
        assertEquals(DriveResult.Ok("""{"version":1,"kinds":{}}"""), drive.download("file 1"))
        assertEquals("/drive/v3/files/file%201", requests.single().url.encodedPath)
        assertEquals("media", requests.single().url.parameters["alt"])
    }

    @Test
    fun `a file is made in the app data folder, its name and text in one request`() = runBlocking {
        val drive = client { json("""{"id":"9","name":"phone-a.json","modifiedTime":"2026-10-22T10:00:00.000Z"}""") }
        assertEquals(DriveResult.Ok(DriveFile("9", "phone-a.json", "2026-10-22T10:00:00.000Z")), drive.create("phone-a.json", """{"version":1}"""))
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/upload/drive/v3/files", request.url.encodedPath)
        assertEquals("multipart", request.url.parameters["uploadType"])
        val body = (request.body as TextContent).text
        assertTrue(body.contains(""""name":"phone-a.json""""))
        assertTrue(body.contains(""""parents":["appDataFolder"]"""))
        assertTrue(body.contains("""{"version":1}"""))
        assertEquals("multipart/related", request.body.contentType?.withoutParameters()?.toString())
    }

    @Test
    fun `a file is replaced with its new text`() = runBlocking {
        val drive = client { json("""{"id":"9","name":"phone-a.json","modifiedTime":"2026-10-22T10:05:00.000Z"}""") }
        assertEquals(DriveResult.Ok(DriveFile("9", "phone-a.json", "2026-10-22T10:05:00.000Z")), drive.update("9", """{"version":1}"""))
        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/upload/drive/v3/files/9", request.url.encodedPath)
        assertEquals("media", request.url.parameters["uploadType"])
        assertEquals("""{"version":1}""", (request.body as TextContent).text)
    }

    @Test
    fun `a file deleted, or already gone, is done`() = runBlocking {
        val deleted = client { respond("", HttpStatusCode.NoContent) }
        assertEquals(DriveResult.Ok(Unit), deleted.delete("9"))
        assertEquals(HttpMethod.Delete, requests.single().method)
        val gone = client { respondError(HttpStatusCode.NotFound) }
        assertEquals(DriveResult.Ok(Unit), gone.delete("9"))
    }

    @Test
    fun `without a grant nothing is asked of Drive`() = runBlocking {
        val drive = client(token = null) { error("not asked") }
        assertEquals(DriveResult.Failed(DriveFailure.SignInNeeded), drive.list())
        assertEquals(DriveResult.Failed(DriveFailure.SignInNeeded), drive.update("9", "{}"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `each refusal says why`() = runBlocking {
        fun refusal(status: HttpStatusCode, reason: String? = null) = client {
            json(reason?.let { """{"error":{"code":${status.value},"errors":[{"domain":"usageLimits","reason":"$it"}]}}""" } ?: "{}", status)
        }
        assertEquals(DriveResult.Failed(DriveFailure.SignInNeeded), refusal(HttpStatusCode.Unauthorized).list())
        assertEquals(DriveResult.Failed(DriveFailure.Full), refusal(HttpStatusCode.Forbidden, "storageQuotaExceeded").update("9", "{}"))
        assertEquals(DriveResult.Failed(DriveFailure.RateLimited), refusal(HttpStatusCode.Forbidden, "userRateLimitExceeded").list())
        assertEquals(DriveResult.Failed(DriveFailure.RateLimited), refusal(HttpStatusCode.TooManyRequests).list())
        assertEquals(DriveResult.Failed(DriveFailure.NotFound), refusal(HttpStatusCode.NotFound).download("9"))
        assertEquals(DriveResult.Failed(DriveFailure.Refused(403)), refusal(HttpStatusCode.Forbidden, "appNotAuthorizedToFile").list())
        assertEquals(DriveResult.Failed(DriveFailure.Refused(503)), refusal(HttpStatusCode.ServiceUnavailable).list())
    }

    @Test
    fun `no answer is offline, said by its type and frames alone`() = runBlocking {
        val drive = client { throw IOException("https://drive.example/drive/v3/files?access_token=$TOKEN") }
        assertEquals(DriveResult.Failed(DriveFailure.Offline), drive.list())
        val warning = warnings.single()
        assertTrue(warning, warning.startsWith("drive list failed (IOException at "))
        assertTrue(warning, TOKEN !in warning && "drive.example" !in warning)
    }

    @Test
    fun `an answer that isn't a listing can't be read, and the log carries none of it`() = runBlocking {
        val drive = client { json("""{"files":"rides: home to work"}""") }
        assertEquals(DriveResult.Failed(DriveFailure.Unreadable), drive.list())
        assertTrue(warnings.single().startsWith("drive list: answer unreadable ("))
        assertTrue(warnings.none { "rides" in it || "home" in it })
    }

    @Test
    fun `refusals log their status and kind, never a file id or its text`() = runBlocking {
        val drive = client { json("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""", HttpStatusCode.Forbidden) }
        drive.update("file-id-123", """{"rides":"secret"}""")
        assertEquals(listOf("drive update: HTTP 403 (Full)"), warnings)
    }

    @Test
    fun `refusals are typed whether or not the client throws on them`() = runBlocking {
        for (throwing in listOf(true, false)) {
            val full = client(expectSuccess = throwing) {
                json("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""", HttpStatusCode.Forbidden)
            }
            assertEquals(DriveResult.Failed(DriveFailure.Full), full.update("9", "{}"))
            val gone = client(expectSuccess = throwing) { respondError(HttpStatusCode.NotFound) }
            assertEquals(DriveResult.Ok(Unit), gone.delete("9"))
        }
    }

    @Test
    fun `a listing is gathered off the caller's thread, from its first step`() {
        val askedOn = mutableListOf<Thread>()
        val decode = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val decodeThread = decode.submit<Thread> { Thread.currentThread() }.get()
            val engine = MockEngine { request ->
                if (request.url.parameters["pageToken"] == null) json("""{"nextPageToken":"p2","files":[{"id":"1","name":"a"}]}""")
                else json("""{"files":[{"id":"2","name":"b"}]}""")
            }
            val drive = KtorDriveAppData(
                http = HttpClient(engine),
                // The token is asked for before each page: on the decoding thread, so every step between
                // pages, the files gathered included, runs there too.
                token = { askedOn += Thread.currentThread(); TOKEN },
                elapsed = { 0L },
                warn = { warnings += it },
                baseUrl = "https://drive.example",
                decodeDispatcher = decode.asCoroutineDispatcher(),
            )
            val listing = runBlocking(caller.asCoroutineDispatcher()) { drive.list() }
            assertEquals(listOf("1", "2"), ((listing as DriveResult.Ok).value.files).map { it.id })
            assertEquals(listOf(decodeThread, decodeThread), askedOn)
        } finally {
            decode.shutdown()
            caller.shutdown()
        }
    }

    @Test
    fun `answers are read off the caller's thread`() {
        val readOn = mutableListOf<Thread>()
        val decode = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val decodeThread = decode.submit<Thread> { Thread.currentThread() }.get()
            val callerThread = caller.submit<Thread> { Thread.currentThread() }.get()
            val engine = MockEngine { json("""{"files":[]}""") }
            val drive = KtorDriveAppData(
                http = HttpClient(engine) { expectSuccess = false },
                token = { TOKEN },
                elapsed = { readOn += Thread.currentThread(); 0L },
                warn = { warnings += it },
                baseUrl = "https://drive.example",
                decodeDispatcher = decode.asCoroutineDispatcher(),
            )
            runBlocking(caller.asCoroutineDispatcher()) { drive.list() }
            // Drive's clock is read with the answer, on the decoding thread, never the caller's.
            assertEquals(listOf(decodeThread), readOn)
            assertNotEquals(callerThread, decodeThread)
        } finally {
            decode.shutdown()
            caller.shutdown()
        }
    }

    private companion object {
        const val TOKEN = "EXAMPLE-TOKEN"
    }
}
