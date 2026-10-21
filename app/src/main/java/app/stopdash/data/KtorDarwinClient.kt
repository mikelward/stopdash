package app.stopdash.data

import androidx.annotation.WorkerThread
import app.stopdash.domain.Departure
import app.stopdash.domain.RailBoard
import app.stopdash.domain.RailBoardSource
import app.stopdash.domain.RailKeyRejected
import app.stopdash.domain.RailRuns
import app.stopdash.domain.SuppressedRailBoard
import app.stopdash.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.UnknownHostException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * National Rail's live departure boards (Darwin) from the Rail Data Marketplace: with the user's own
 * key ([apiKey], read per request so a key pasted in Settings applies on the next refresh) straight
 * from National Rail, else through stopdash's proxy ([proxyUrl]), which holds a key of its own and
 * answers the same boards. With neither it's unavailable and asks nothing. A board asked for with
 * neither (a key cleared since [available] was read) fails, never reading as one with no trains.
 *
 * Only the station's CRS code (and, following a train, its terminus's and an offset) is sent, with the
 * key where it's the rider's: never a location (SPEC *Privacy*). Failures map
 * to the same [TflException] kinds as TfL's client (offline, rate-limited, unreachable), sanitized to
 * a status or class name; the key is never logged.
 */
class KtorDarwinClient(
    private val httpClient: HttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    // Sink for a board's oddities (a train with an unreadable time), sanitized: no key, no payload.
    private val warn: (String) -> Unit = {},
    // Where a board's answer is read, as for TfL's ([KtorTflClient]): off the caller's thread, which
    // is a screen's main thread when it loads its own. A test swaps in its own.
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // TfL's stop id for a station code (RailStationCodes.stopIdFor), read where the board is: a
    // train's terminus by id, for its stop list. Null for none, as in a test.
    private val stopIdFor: (String) -> String? = { null },
    // Every TfL stop id of a station code (RailStationCodes.stopIdsFor): a train's calling points.
    private val stopIdsFor: (String) -> Set<String> = { emptySet() },
    // stopdash's proxy ([StopdashProxy]), for a rider with no key of their own. Null (tests, a build with
    // none) leaves a keyless rider without National Rail times, as before it.
    private val proxyUrl: () -> String? = { null },
    // Returns once the rider's saved key, if any, has been read back, so a board asked for meanwhile
    // goes the way it will once it's known: never through the proxy in place of their key, and never
    // refused for want of one (Codex, #944). Waited for up to [KEY_LOAD_WAIT].
    private val keyLoaded: suspend () -> Unit = {},
) : RailBoardSource {
    // A board can be had once there's a key or a proxy. With a proxy, that's so whatever the saved key
    // turns out to be, so a board isn't refused while it's still being read back.
    override val available: Boolean get() = apiKey()?.isNotBlank() == true || proxy() != null

    override suspend fun departures(crs: String): List<Departure> = board(crs).departures

    override suspend fun board(crs: String): RailBoard {
        // Cleared since the caller checked [available]: no answer, never a board with no trains (Codex, #883).
        val route = route() ?: throw TflException.Unreachable("no national rail key", null)
        return fetch(route, BOARD, crs, ROWS) { board, age -> board.toBoard(stopIdFor, stopIdsFor, warn).copy(age = age) }
    }

    /**
     * [crs]'s board asked for with its trains' calling points (`GetDepBoardWithDetails`), which lists
     * fewer ([DETAIL_ROWS]): each train's stops after this one ([Departure.callingAt]), paired with
     * the plain [board]'s trains by service id. Failures as [board]'s.
     */
    override suspend fun boardWithDetails(crs: String): RailBoard? {
        val route = route() ?: return null
        return fetch(route, DETAILS, crs, DETAIL_ROWS) { board, age -> board.toBoard(stopIdFor, stopIdsFor, warn).copy(age = age) }
    }

    /**
     * [crs]'s next trains that call at [towards] with their calling points and times
     * (`GetDepBoardWithDetails`, filtered to [towards]), from [offsetMinutes] on (`timeOffset`), for
     * following one: filtered, and asked from near its time, the train followed is among the [DETAIL_ROWS]
     * it lists. Failures as [board]'s.
     */
    override suspend fun runs(crs: String, towards: String, offsetMinutes: Int): RailRuns? {
        val route = route() ?: return null
        val from = if (offsetMinutes == 0) emptyMap() else mapOf("timeOffset" to offsetMinutes.toString())
        return fetch(route, DETAILS, crs, DETAIL_ROWS, mapOf("filterCrs" to towards, "filterType" to "to") + from, towards, offsetMinutes) { board, age -> RailRuns(board.toRuns(), age) }
    }

    // Where a board is asked: straight from National Rail with the rider's key, else through the proxy.
    private sealed interface Route {
        data class Direct(val key: String) : Route
        data class Proxy(val url: String) : Route
    }

    private fun proxy(): String? = proxyUrl()?.trim()?.trimEnd('/')?.ifEmpty { null }

    // The way to ask, once the saved key is known. If it isn't read back in time, no board: the proxy
    // isn't asked in the key's place.
    private suspend fun route(): Route? {
        withTimeoutOrNull(KEY_LOAD_WAIT.toMillis()) { keyLoaded() }
            ?: throw TflException.Unreachable("national rail key not read yet", null)
        return apiKey()?.trim()?.ifBlank { null }?.let(Route::Direct) ?: proxy()?.let(Route::Proxy)
    }

    // [crs]'s board from [operation], [rows] long and narrowed by [filters], read by [read] with how old it
    // already was (the proxy's `Age`, zero straight from National Rail); failures as [TflException]s.
    private suspend fun <T> fetch(
        route: Route,
        operation: String,
        crs: String,
        rows: Int,
        filters: Map<String, String> = emptyMap(),
        // The proxy's form of [filters] (a followed train's terminus and offset): it sets the rows itself.
        towards: String? = null,
        offsetMinutes: Int = 0,
        // Run on [decodeDispatcher], never the caller's thread: it reads the whole board.
        @WorkerThread read: (DarwinBoardDto, Duration) -> T,
    ): T {
        return try {
            withContext(decodeDispatcher) {
                val response =
                    when (route) {
                        is Route.Direct -> httpClient.get("$baseUrl/$operation/$crs") {
                            header("x-apikey", route.key)
                            parameter("numRows", rows)
                            filters.forEach { (name, value) -> parameter(name, value) }
                        }
                        // The proxy's own shape, with nothing of the rider's: no key, the board's codes alone.
                        is Route.Proxy -> httpClient.get("${route.url}/v1/board/$crs${if (operation == DETAILS) "/details" else ""}") {
                            towards?.let { parameter("to", it) }
                            if (towards != null && offsetMinutes != 0) parameter("offset", offsetMinutes)
                        }
                    }
                // Seconds the proxy counted itself, so the board is dated by its real age, never by clocks.
                val age = response.headers[HttpHeaders.Age]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofSeconds) ?: Duration.ZERO
                read(response.body<DarwinBoardDto>(), age)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientRequestException) {
            val status = e.response.status
            throw when {
                status == HttpStatusCode.TooManyRequests -> TflException.RateLimited(e)
                // The user's own key refused: its stations say so ([RailKeyRejected]). The proxy sends none
                // of the rider's, so a refusal there is the proxy's, and stays a plain failure.
                route is Route.Direct && (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) ->
                    TflException.Unreachable("HTTP ${status.value} (key rejected)", RailKeyRejected(e))
                else -> TflException.Unreachable("HTTP ${status.value}", e)
            }
        } catch (e: ServerResponseException) {
            val why = if (route is Route.Proxy) proxyReason(e) else null
            throw TflException.Unreachable("HTTP ${e.response.status.value}${why?.let { " ($it)" }.orEmpty()}", e)
        } catch (e: UnknownHostException) {
            throw TflException.Offline(e)
        } catch (e: IOException) {
            throw TflException.Network("transport: ${e::class.simpleName}", e)
        } catch (e: SuppressedRailBoard) {
            // Its notices ride along as the cause ([SuppressedRailBoard.messages]).
            throw TflException.Unreachable("board suppressed", e)
        } catch (e: Exception) {
            throw TflException.Unreachable("unexpected: ${e::class.simpleName}", e)
        }
    }

    // What the proxy's failed answer says went wrong upstream, where it's one of the two shapes it writes
    // (an error's type, or National Rail's status), so the debug log names it (Codex, #944). Anything else
    // in the body is never repeated: null. Read and matched on [decodeDispatcher], as a board is: the
    // caller can be a screen's main thread (Codex, #944).
    private suspend fun proxyReason(e: ServerResponseException): String? = try {
        withContext(decodeDispatcher + FAILURE_BODY) { PROXY_REASON.find(e.response.bodyAsText())?.groupValues?.get(1) }
    } catch (c: CancellationException) {
        throw c
    } catch (other: Exception) {
        // The status alone still says the proxy failed; the body is only its detail.
        warn("stopdash proxy: failure body unreadable: ${other::class.simpleName}")
        null
    }

    companion object {
        // Names the failure body's read on [decodeDispatcher], so a test can see it ran there.
        internal val FAILURE_BODY = CoroutineName("proxy failure body")

        private val PROXY_REASON = Regex(""""error"\s*:\s*"(national rail (?:unreachable: [A-Za-z]{1,40}|answered \d{3}))"""")

        /** The Live Departure Board product's endpoint on the Rail Data Marketplace. */
        const val DEFAULT_BASE_URL: String =
            "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120"

        /** How many departures to ask for: about the next hour at a busy London terminus. */
        const val ROWS = 20

        /** How many a board with calling points lists: Darwin's most for one with details. */
        const val DETAIL_ROWS = 10

        /** How long a board waits for the saved key to be read back: it's a small local read. */
        val KEY_LOAD_WAIT: Duration = Duration.ofSeconds(5)

        private const val BOARD = "GetDepartureBoard"
        private const val DETAILS = "GetDepBoardWithDetails"
    }
}
