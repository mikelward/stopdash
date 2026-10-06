package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.RailBoard
import app.stopdash.domain.RailBoardSource
import app.stopdash.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * National Rail's live departure boards (Darwin) from the Rail Data Marketplace, with the user's
 * own key ([apiKey], read per request so a key pasted in Settings applies on the next refresh).
 * With no key it's unavailable and asks nothing: stopdash ships no key of its own.
 *
 * Only the station's CRS code and the key are sent: never a location (SPEC *Privacy*). Failures map
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
) : RailBoardSource {
    override val available: Boolean get() = !apiKey().isNullOrBlank()

    override suspend fun departures(crs: String): List<Departure> = board(crs).departures

    override suspend fun board(crs: String): RailBoard {
        val key = apiKey()?.trim()?.ifBlank { null } ?: return RailBoard(emptyList())
        return try {
            withContext(decodeDispatcher) {
                httpClient.get("$baseUrl/GetDepartureBoard/$crs") {
                    header("x-apikey", key)
                    parameter("numRows", ROWS)
                }.body<DarwinBoardDto>().toBoard(stopIdFor, warn)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientRequestException) {
            throw if (e.response.status == HttpStatusCode.TooManyRequests) {
                TflException.RateLimited(e)
            } else {
                TflException.Unreachable("HTTP ${e.response.status.value}", e)
            }
        } catch (e: ServerResponseException) {
            throw TflException.Unreachable("HTTP ${e.response.status.value}", e)
        } catch (e: UnknownHostException) {
            throw TflException.Offline(e)
        } catch (e: IOException) {
            throw TflException.Network("transport: ${e::class.simpleName}", e)
        } catch (e: Exception) {
            throw TflException.Unreachable("unexpected: ${e::class.simpleName}", e)
        }
    }

    companion object {
        /** The Live Departure Board product's endpoint on the Rail Data Marketplace. */
        const val DEFAULT_BASE_URL: String =
            "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120"

        /** How many departures to ask for: about the next hour at a busy London terminus. */
        const val ROWS = 20
    }
}
