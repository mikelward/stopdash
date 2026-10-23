package app.stopdash.data

import app.stopdash.domain.DriveAppData
import app.stopdash.domain.DriveClock
import app.stopdash.domain.DriveFailure
import app.stopdash.domain.DriveFile
import app.stopdash.domain.DriveListing
import app.stopdash.domain.DriveResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The rider's Drive app data folder over Drive's REST API (dev-docs/drive-sync.md), through [http]: the
 * `drive.appdata` scope reaches that folder and nothing else of their Drive. Unused until sync's later
 * steps (TODO.md), and never in a release build until the Play Data Safety form says so.
 *
 * [token] is the access token Play services' `AuthorizationClient` hands over for the scope, asked for
 * per request so a fresh one is used once the last expires; null when there's no grant, which needs the
 * screen. [elapsed] is the phone's monotonic clock (`SystemClock.elapsedRealtime`), read as a listing's
 * answer arrives, for Drive's clock ([DriveClock]). Each failure is logged by its kind and HTTP status
 * alone ([warn], required so no caller drops it): no token, file id or content, which holds the rider's
 * stops. Answers are read on [decodeDispatcher], never the caller's thread, which can be the main one.
 */
internal class KtorDriveAppData(
    private val http: HttpClient,
    private val token: suspend () -> String?,
    private val elapsed: () -> Long,
    private val warn: (String) -> Unit,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DriveAppData {

    // On [decodeDispatcher] from the start: every page's files are gathered here, work that grows with the
    // folder, never on the caller's thread (AGENTS.md *Main thread*; Codex on #1149).
    override suspend fun list(): DriveResult<DriveListing> = withContext(decodeDispatcher) { listPages() }

    private suspend fun listPages(): DriveResult<DriveListing> {
        val files = ArrayList<DriveFile>()
        var clock: DriveClock? = null
        var pageToken: String? = null
        // Pages are followed to the end, a bounded number of them: a folder holds a file per phone.
        repeat(MAX_PAGES) {
            val page = call("list", {
                method = HttpMethod.Get
                url("$baseUrl/drive/v3/files")
                parameter("spaces", "appDataFolder")
                parameter("fields", "nextPageToken,files(id,name,modifiedTime)")
                parameter("pageSize", PAGE_SIZE)
                pageToken?.let { parameter("pageToken", it) }
            }) { response ->
                // Drive's clock as of the first answer, the moment this sync stamps from.
                if (clock == null) clock = clockOf(response) ?: return@call null
                json.decodeFromString(FileListDto.serializer(), response.bodyAsText())
            }
            when (page) {
                is DriveResult.Failed -> return page
                is DriveResult.Ok -> {
                    page.value.files.mapTo(files) { DriveFile(it.id, it.name, it.modifiedTime) }
                    pageToken = page.value.nextPageToken?.takeIf { it.isNotEmpty() }
                    if (pageToken == null) return DriveResult.Ok(DriveListing(files, checkNotNull(clock)))
                }
            }
        }
        warn("drive list: more than $MAX_PAGES pages")
        return DriveResult.Failed(DriveFailure.Unreadable)
    }

    override suspend fun download(id: String): DriveResult<String> =
        call("download", {
            method = HttpMethod.Get
            url("$baseUrl/drive/v3/files/${id.encodeURLPathPart()}")
            parameter("alt", "media")
        }) { it.bodyAsText() }

    override suspend fun create(name: String, text: String): DriveResult<DriveFile> {
        // One request: the file's name and folder, then its text (Drive's multipart upload). Put together
        // off the caller's thread, as it copies the whole file.
        val body = withContext(decodeDispatcher) {
            val metadata = json.encodeToString(CreateDto.serializer(), CreateDto(name, listOf("appDataFolder")))
            "--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$text\r\n--$BOUNDARY--"
        }
        return call("create", {
            method = HttpMethod.Post
            url("$baseUrl/upload/drive/v3/files")
            parameter("uploadType", "multipart")
            parameter("fields", FILE_FIELDS)
            setBody(TextContent(body, ContentType.parse("multipart/related; boundary=$BOUNDARY")))
        }) { fileOf(it) }
    }

    override suspend fun update(id: String, text: String): DriveResult<DriveFile> =
        call("update", {
            method = HttpMethod.Patch
            url("$baseUrl/upload/drive/v3/files/${id.encodeURLPathPart()}")
            parameter("uploadType", "media")
            parameter("fields", FILE_FIELDS)
            setBody(TextContent(text, ContentType.Application.Json))
        }) { fileOf(it) }

    override suspend fun delete(id: String): DriveResult<Unit> {
        val result = call("delete", {
            method = HttpMethod.Delete
            url("$baseUrl/drive/v3/files/${id.encodeURLPathPart()}")
        }) { }
        // Already gone is what was asked for.
        return if (result == DriveResult.Failed(DriveFailure.NotFound)) DriveResult.Ok(Unit) else result
    }

    /**
     * One request: authorized with [token], sent, and its answer [read] on [decodeDispatcher] once it
     * succeeded. A null from [read] is an answer that couldn't be read.
     */
    private suspend fun <T> call(
        what: String,
        setup: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> T?,
    ): DriveResult<T> {
        val bearer = token() ?: return DriveResult.Failed(DriveFailure.SignInNeeded)
        val response = try {
            http.request {
                setup(this)
                header(HttpHeaders.Authorization, "Bearer $bearer")
                // Every answer is judged below, whatever the client's own setting: a shared client that
                // throws on a refusal would skip the typed failures (Codex on #1149).
                expectSuccess = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Offline, unreachable or timed out (Ktor's timeout is an IOException). Its type and where
            // it was thrown, never its message, which can quote the request (AGENTS.md *Error handling*).
            warn("drive $what failed (${e.javaClass.simpleName}${framesOf(e)})")
            return DriveResult.Failed(DriveFailure.Offline)
        }
        if (!response.status.isSuccess()) {
            val failure = withContext(decodeDispatcher) { failureOf(response) }
            warn("drive $what: HTTP ${response.status.value} ($failure)")
            return DriveResult.Failed(failure)
        }
        return try {
            withContext(decodeDispatcher) { read(response) }?.let { DriveResult.Ok(it) }
                ?: run {
                    warn("drive $what: answer without Drive's clock")
                    DriveResult.Failed(DriveFailure.Unreadable)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The answer cut off as it was read.
            warn("drive $what: answer cut off (${e.javaClass.simpleName}${framesOf(e)})")
            DriveResult.Failed(DriveFailure.Offline)
        } catch (e: SerializationException) {
            warn("drive $what: answer unreadable (${e.javaClass.simpleName}${framesOf(e)})")
            DriveResult.Failed(DriveFailure.Unreadable)
        } catch (e: IllegalArgumentException) {
            warn("drive $what: answer unreadable (${e.javaClass.simpleName}${framesOf(e)})")
            DriveResult.Failed(DriveFailure.Unreadable)
        }
    }

    // Drive's clock as the answer's `Date` gives it, against the phone's monotonic clock as it arrived.
    private fun clockOf(response: HttpResponse): DriveClock? {
        val date = response.headers[HttpHeaders.Date] ?: return null
        val millis = try {
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            warn("drive: Date unreadable (${e.javaClass.simpleName}${framesOf(e)})")
            return null
        }
        return DriveClock(millis, elapsed())
    }

    private suspend fun fileOf(response: HttpResponse): DriveFile {
        val file = json.decodeFromString(FileDto.serializer(), response.bodyAsText())
        return DriveFile(file.id, file.name, file.modifiedTime)
    }

    // A refusal by its status, and for a 403 by Drive's reason: a full Drive and a rate limit both say 403.
    private suspend fun failureOf(response: HttpResponse): DriveFailure = when (response.status) {
        HttpStatusCode.Unauthorized -> DriveFailure.SignInNeeded
        HttpStatusCode.NotFound -> DriveFailure.NotFound
        HttpStatusCode.TooManyRequests -> DriveFailure.RateLimited
        HttpStatusCode.Forbidden -> when (reasonOf(response)) {
            "storageQuotaExceeded", "quotaExceeded" -> DriveFailure.Full
            "rateLimitExceeded", "userRateLimitExceeded" -> DriveFailure.RateLimited
            else -> DriveFailure.Refused(response.status.value)
        }
        else -> DriveFailure.Refused(response.status.value)
    }

    private suspend fun reasonOf(response: HttpResponse): String? = try {
        json.decodeFromString(ErrorDto.serializer(), response.bodyAsText()).error?.errors?.firstOrNull()?.reason
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        warn("drive: refusal unreadable (${e.javaClass.simpleName}${framesOf(e)})")
        null
    } catch (e: SerializationException) {
        warn("drive: refusal unreadable (${e.javaClass.simpleName}${framesOf(e)})")
        null
    } catch (e: IllegalArgumentException) {
        warn("drive: refusal unreadable (${e.javaClass.simpleName}${framesOf(e)})")
        null
    }

    private fun framesOf(e: Exception): String = e.stackTrace.take(STACK_FRAMES).joinToString("") { " at $it" }

    @Serializable
    private data class FileListDto(val files: List<FileDto> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    private data class FileDto(val id: String, val name: String = "", val modifiedTime: String = "")

    @Serializable
    private data class CreateDto(val name: String, val parents: List<String>)

    @Serializable
    private data class ErrorDto(val error: ErrorBodyDto? = null)

    @Serializable
    private data class ErrorBodyDto(val errors: List<ErrorItemDto>? = null)

    @Serializable
    private data class ErrorItemDto(val reason: String? = null)

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com"
        private const val FILE_FIELDS = "id,name,modifiedTime"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 20
        private const val STACK_FRAMES = 3
        private const val BOUNDARY = "stopdash-sync-boundary"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
