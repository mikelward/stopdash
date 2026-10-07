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
    // Every TfL stop id of a station code (RailStationCodes.stopIdsFor): a train's calling points.
    private val stopIdsFor: (String) -> Set<String> = { emptySet() },
) : RailBoardSource {
    override val available: Boolean get() = !apiKey().isNullOrBlank()

    override suspend fun departures(crs: String): List<Departure> = board(crs).departures

    override suspend fun board(crs: String): RailBoard {
        val key = key() ?: return RailBoard(emptyList())
        return fetch(key, BOARD, crs, ROWS) { it.toBoard(stopIdFor, stopIdsFor, warn) }
    }

    /**
     * [crs]'s board asked for with its trains' calling points (`GetDepBoardWithDetails`), which lists
     * fewer ([DETAIL_ROWS]): each train's stops after this one ([Departure.callingAt]), paired with
     * the plain [board]'s trains by service id. Failures as [board]'s.
     */
    override suspend fun boardWithDetails(crs: String): RailBoard? {
        val key = key() ?: return null
        return fetch(key, DETAILS, crs, DETAIL_ROWS) { it.toBoard(stopIdFor, stopIdsFor, warn) }
    }

    private fun key(): String? = apiKey()?.trim()?.ifBlank { null }

    // [crs]'s board from [operation], [rows] long, read by [read]; failures as [TflException]s.
    private suspend fun <T> fetch(key: String, operation: String, crs: String, rows: Int, read: (DarwinBoardDto) -> T): T {
        return try {
            withContext(decodeDispatcher) {
                read(
                    httpClient.get("$baseUrl/$operation/$crs") {
                        header("x-apikey", key)
                        parameter("numRows", rows)
                    }.body<DarwinBoardDto>(),
                )
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

        /** How many a board with calling points lists: Darwin's most for one with details. */
        const val DETAIL_ROWS = 10

        private const val BOARD = "GetDepartureBoard"
        private const val DETAILS = "GetDepBoardWithDetails"
    }
}
