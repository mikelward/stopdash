package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.HubInfo
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusBatch
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.PlaceSearch
import app.stopdash.domain.PostcodeResolution
import app.stopdash.domain.PostcodeResolver
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.StationFinder
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopAreaSource
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.StopFinder
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflRateLimiter
import app.stopdash.domain.TflRequestPool
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.TflException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher

/**
 * The production [TflClient]: Ktor over the OkHttp engine, decoding TfL's JSON
 * through content negotiation (SPEC *Architecture*; the HTTP-engine choice
 * mirrors clothescast). Off the render path — a surface renders the snapshot and
 * a refresh calls this in the background (SPEC *Snapshot-render*).
 *
 * [appKey] is read per request, not captured at construction, so a key the user pastes
 * in Settings takes effect on the next refresh without rebuilding the long-lived clients
 * (SPEC D7). A null/blank result means keyless access: stopdash ships no baked-in key, and
 * a user may supply their own for the higher rate limit. `expectSuccess` makes a non-2xx
 * (e.g. 429 rate-limited) throw, which the caller turns into the honest user-facing state
 * rather than a silent empty list.
 */
class KtorTflClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appKey: () -> String? = { null },
    // The shared TfL limiter (item 6), selected from the request's key snapshot: every request
    // acquires a token first, so a dense corner or a new caller throttles toward the budget instead
    // of firing a 429 storm. Taking the key means one read of the active key drives BOTH which
    // budget the request is charged to and the `app_key` it carries — a paste/clear can't leave a
    // request charged to one bucket but sent with the other key state (Codex). Defaults to the no-op
    // limiter so a test (or an unwired client) runs unthrottled; production passes the shared buckets
    // ([SharedTflRateLimiter.rateLimiterFor]).
    private val rateLimiterFor: (String?) -> TflRateLimiter = { TflRateLimiter.UNLIMITED },
    // The shared cap on in-flight requests, so a caller can fan a refresh out in parallel without
    // opening one connection per stop. Unbounded by default (tests, an unwired client); production
    // passes [SharedTflRequestPool.pool] so the app and widget share one cap.
    private val requestPool: TflRequestPool = TflRequestPool.UNBOUNDED,
    // Sink for recoverable response oddities (an unparseable disruption date), coarse facts only —
    // a stop id, never a coordinate or key (SPEC *Privacy*). No-op by default (tests, widget).
    private val warn: (String) -> Unit = {},
    // Where each line alert's direction of travel is remembered, and the scope its one-off lookup
    // runs in, off the refresh ([LineAlertDirections]). Both null by default (tests, the widget,
    // whose HTTP client closes when its work ends): alerts then count for both directions.
    private val alertDirections: LineAlertDirections? = null,
    private val alertDirectionScope: CoroutineScope? = null,
    // The lookup decodes a ~150 KB response a line, so it runs off the main thread; a test swaps in
    // its own dispatcher to await it.
    private val alertDirectionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // The time a line alert's start is judged against ([AlertStart]): work starting on a later
    // day is planned, not a disruption. Injected so a test can pin it.
    private val clock: () -> Instant = Instant::now,
) : TflClient, StopFinder, StationFinder, RouteSequenceSource, StopAreaSource, JourneyPlanner, PostcodeResolver, PlaceSearch, VehicleSource {
    override suspend fun journeys(from: TripOrigin, to: TripDestination, speed: WalkingSpeed): List<TripRoute> =
        tflRequest { key ->
            // From here, the rider's own coordinate ("lat,lon"): TfL walks from it to the stop that
            // serves the trip best, the same position the nearby lookup already sends (SPEC *Trips
            // with a change*). Never logged, as neither end is.
            val fromParam = when (from) {
                is TripOrigin.Stop -> from.id
                is TripOrigin.Here -> "${from.coordinate.latitude},${from.coordinate.longitude}"
            }
            // A stop goes by id; a place goes by its coordinate ("lat,lon"), which TfL routes to with a
            // final walk leg (SPEC D9). The coordinate is the rider's chosen destination, so — like the
            // trip's ends — it isn't logged (SPEC *Privacy*).
            val toParam = when (to) {
                is TripDestination.Stop -> to.id
                is TripDestination.Place -> "${to.coordinate.latitude},${to.coordinate.longitude}"
            }
            val dto = try {
                httpClient.get("$baseUrl/Journey/JourneyResults/$fromParam/to/$toParam") {
                    // No leg asks the rider to walk longer than this (the Planner's default allows
                    // far more, offering an all-walk route beside the rides).
                    parameter("maxWalkingMinutes", MAX_WALKING_MINUTES)
                    // Every walk timed at the rider's own pace (their setting; the Planner's average by default).
                    parameter("walkingSpeed", speed.plannerValue)
                    // The Planner applies that pace to a route's walks only when the request names its
                    // modes, walking among them; left to its default modes, every walk comes back at the
                    // average whatever the speed ([PLANNER_MODES]).
                    parameter("mode", PLANNER_MODES)
                    applyAppKey(key)
                    // The Planner can take several seconds to answer a trip it hasn't cached.
                    allowSlowAnswer()
                }.body<TflJourneyResultsDto>()
            } catch (e: RedirectResponseException) {
                // 300: the Planner couldn't place an end and offers look-alike places instead. The
                // trip has no route StopDash can stand behind, so none is shown. The two ends together
                // are a trip the rider chose, so neither is logged (SPEC *Privacy*).
                warn("journey planner: HTTP ${e.response.status.value}")
                return@tflRequest emptyList()
            }
            val routes = dto.toRoutes()
            // Journeys offered but none readable: a decode failure, not "no routes" (which would be
            // reused as a real answer for the plan's lifetime).
            if (routes.isEmpty() && dto.journeys.isNotEmpty()) {
                throw TflException.Unreachable("journey planner: ${dto.journeys.size} routes, none readable", null)
            }
            if (routes.size < dto.journeys.size) {
                warn("journey planner: ${dto.journeys.size - routes.size} of ${dto.journeys.size} routes unreadable")
            }
            // TfL names a coordinate arrival by whatever (if anything) sits there, not the favorite the
            // rider picked, so the final walk leg reads with the name they know it by (SPEC D9). The
            // last leg of every route to a place is that walk.
            val named = if (to is TripDestination.Place) routes.map { it.namedTo(to.name) } else routes
            if (from is TripOrigin.Here) named.map { it.fromHere() } else named
        }

    // The route with its first leg's start unnamed when it starts at the rider's coordinate (no stop
    // id): TfL names that point by whatever sits there, often the coordinate itself, which is neither
    // useful on screen nor anything to keep in the trip on the way. A walk from where the rider set
    // off names only where it goes, as the walk to the first stop always has.
    private fun TripRoute.fromHere(): TripRoute {
        val first = legs.firstOrNull() ?: return this
        if (first.fromId.isNotBlank() || first.fromArea.isNotBlank()) return this
        return TripRoute(listOf(first.copy(fromName = "", fromAt = null)) + legs.drop(1))
    }

    // The route with its final leg named [name] when that leg ends at a bare coordinate (no stop id):
    // the place the rider chose, in place of whatever TfL happened to call the point. A leg that ends
    // at a real stop keeps its name, and an empty route is left as is.
    private fun TripRoute.namedTo(name: String): TripRoute {
        val last = legs.lastOrNull() ?: return this
        if (last.toId.isNotBlank()) return this
        return copy(legs = legs.dropLast(1) + last.copy(toName = name))
    }

    override suspend fun resolvePostcode(postcode: String): PostcodeResolution =
        tflRequest { key ->
            try {
                // Plan from the postcode to a fixed, always-resolvable interchange (King's Cross St
                // Pancras): a valid postcode resolves and the journey's origin is its point; an
                // ambiguous one 300s with look-alike places. Either way we read the place(s), never a
                // route. The postcode is the rider's own input, so it isn't logged (SPEC *Privacy*).
                val dto = httpClient.get(
                    "$baseUrl/Journey/JourneyResults/${postcode.encodeURLPathPart()}/to/$POSTCODE_ANCHOR_ID",
                ) {
                    applyAppKey(key)
                    allowSlowAnswer()
                }.body<TflJourneyResultsDto>()
                val candidate = dto.resolvedOriginCandidate()
                // A journey was offered but its origin couldn't be read (a short or changed response):
                // that's a decode failure, not "placed nowhere" — surface it as the retryable error the
                // caller expects, never a false no-result (SPEC principle 2), as journeys() does.
                if (candidate == null && dto.journeys.isNotEmpty()) {
                    throw TflException.Unreachable("journey planner: postcode origin unreadable", null)
                }
                // 200: TfL placed the postcode at one point — an unambiguous resolution the caller may
                // adopt straight away; nothing at all means it's placed nowhere.
                if (candidate != null) PostcodeResolution.Resolved(candidate) else PostcodeResolution.None
            } catch (e: RedirectResponseException) {
                // 300: the Planner offers look-alike places for the postcode instead of one point — a
                // disambiguation the rider must choose from, never a single resolution even if only one
                // option carries a position.
                val result = e.response.body<TflDisambiguationResultDto>()
                val from = result.fromLocationDisambiguation
                val candidates = result.toCandidates()
                // A disambiguation that offers options — or says it has a list — but yields none decodable
                // is schema drift, not "placed nowhere": surface it as retryable, symmetric with the 200
                // path. (A renamed/absent options array would otherwise default to empty and pass.)
                val offeredButUnreadable = candidates.isEmpty() &&
                    (from.disambiguationOptions.isNotEmpty() || from.matchStatus.equals("list", ignoreCase = true))
                if (offeredButUnreadable) {
                    throw TflException.Unreachable("journey planner: disambiguation options unreadable", null)
                }
                if (candidates.isEmpty()) PostcodeResolution.None else PostcodeResolution.Options(candidates)
            }
        }

    override suspend fun searchPlaces(query: String): List<PlaceCandidate> =
        // The same geocoder as the postcode resolver — a place name or landmark resolves the way a
        // postcode does (plan from the text to a fixed anchor, read the origin place(s)). Reusing it
        // keeps the offered-but-unreadable checks (schema drift throws TflException.Unreachable rather
        // than reading as "no place"), so the To… search logs the failure instead of silently blanking
        // (SPEC principle 2). The 200 resolution and the 300 disambiguation options flatten to one list;
        // the caller treats it as additive to the stop search. The query is the rider's own input, never
        // logged (SPEC *Privacy*).
        when (val resolution = resolvePostcode(query)) {
            is PostcodeResolution.Resolved -> listOf(resolution.place)
            is PostcodeResolution.Options -> resolution.places
            PostcodeResolution.None -> emptyList()
        }

    override suspend fun arrivals(stopId: String): List<Departure> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint/$stopId/Arrivals") {
                applyAppKey(key)
            }.body<List<TflArrivalDto>>().map { it.toDeparture() }.let(DepartureRows::inferDirections)
        }

    override suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall> =
        tflRequest { key ->
            httpClient.get("$baseUrl/Vehicle/${vehicleId.encodeURLPathPart()}/Arrivals") {
                applyAppKey(key)
            }.body<List<TflArrivalDto>>()
                .filter { it.lineId == lineId && !it.naptanId.isNullOrBlank() }
                .map { it.toVehicleCall() }
                .sortedBy { it.expected }
        }

    override suspend fun nearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        stopTypes: List<String>,
    ): List<StopLocation> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint") {
                parameter("lat", latitude)
                parameter("lon", longitude)
                parameter("stopTypes", stopTypes.joinToString(","))
                parameter("radius", radiusMeters)
                // The geo search omits each stop's served lines unless asked; without this the
                // stops come back line-less in production (the fixture has them), so a suspended
                // no-prediction line couldn't surface as a status row without a second lookup.
                parameter("returnLines", true)
                // Only the Direction properties (CompassPoint, Towards) are read; the rest is station
                // facilities, about two thirds of a dense area's response (2 MB → 0.7 MB for a
                // 1-mile search in the City).
                parameter("categories", "Direction")
                applyAppKey(key)
                // A dense area's search can take TfL over OkHttp's 10 s default to start answering
                // when it isn't cached, which failed the whole nearby list; give it longer.
                allowSlowAnswer()
            }.body<TflStopPointsResponseDto>().stopPoints.mapNotNull { it.toStopLocationOrNull() }
        }

    override suspend fun searchStations(query: String): List<StationMatch> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint/Search") {
                parameter("query", query)
                parameter("maxResults", SEARCH_MAX_RESULTS)
                // Interchanges ("HUBKGX") are left out unless asked for; a hub is the match whose
                // stops span every station there, which is what a rider searching a name wants.
                parameter("includeHubs", true)
                applyAppKey(key)
            }.body<TflSearchResponseDto>().matches
                .mapNotNull { it.toStationMatchOrNull() }
                .distinctBy { it.id }
        }

    override suspend fun stationStops(id: String): List<StopLocation> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint/$id") {
                applyAppKey(key)
            }.body<TflStopPointDto>()
                .departureStops(StopFinder.DEFAULT_NEARBY_STOP_TYPES)
                .mapNotNull { it.toStopLocationOrNull() }
                .distinctBy { it.id }
        }

    override suspend fun hubInfo(hubId: String): HubInfo =
        tflRequest { key ->
            val dto = httpClient.get("$baseUrl/StopPoint/$hubId") {
                applyAppKey(key)
            }.body<TflStopPointDto>()
            // The hub's own cleaned name titles the alert; the member-station spellings across the
            // tree are the alias set the disruption strip matches against (SPEC *Disruptions*).
            HubInfo(name = cleanStopName(dto.commonName), aliases = dto.hubStationNames())
        }

    /**
     * Where the station [id] can be walked into ([entrancesOf]): for a trip's walk to it, which ends
     * when the rider is seen at any of them. One request, naming only the station.
     */
    suspend fun stationEntrances(id: String): List<Coordinates> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint/$id") {
                applyAppKey(key)
            }.body<TflStopPointDto>().entrancesOf(id)
        }

    override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
        tflRequest { key ->
            httpClient.get("$baseUrl/Line/$lineId/Route/Sequence/$direction") {
                applyAppKey(key)
                // A National Rail line's sequence (~600 KB, every station and pattern it runs) can take
                // TfL 4–15 s to start answering when it isn't cached, which failed the route page.
                allowSlowAnswer()
            }.body<TflRouteSequenceDto>().toLineSequence(direction)
        }

    override suspend fun stopAreaPoles(areaId: String): List<StopLocation> =
        tflRequest { key ->
            val dto = httpClient.get("$baseUrl/StopPoint/$areaId") {
                applyAppKey(key)
            }.body<TflStopPointDto>()
            // The area's leaf stop points (its poles), each with its letter and lines.
            dto.leaves().mapNotNull { it.toStopLocationOrNull() }
        }

    override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
        // No lines → no request: a refresh with no predicted lines has nothing to check,
        // and an empty `/Line//Status` path would 404.
        if (lineIds.isEmpty()) return emptyList()
        // Split so no request's `{ids}` segment is longer than TfL accepts: a busy hub's lines in
        // one segment were refused outright with a 400, failing every line's check (LineStatusBatch).
        // Answered whole or not at all here; a caller that keeps each group's outcome apart calls
        // LineStatusBatch.request itself, so each call here is one group.
        val results = LineStatusBatch.request(lineIds) { chunk ->
            tflRequest { key ->
                httpClient.get("$baseUrl/Line/${chunk.joinToString(",")}/Status") {
                    applyAppKey(key)
                }.body<List<TflLineDto>>()
            }
        }
        results.failure?.let { throw it }
        // TfL knows none of the lines at all. A group it knows none of beside one it answered is
        // simply absent, as an unknown line is from a single mixed answer.
        if (results.answers.isEmpty()) throw TflException.NotFound(null)
        val lines = results.answers.flatMap { it.value }
        lookUpAlertDirections(lines)
        val directions = alertDirections?.takeIf { alertDirectionScope != null }
        return lines.mapNotNull { line ->
            line.toLineStatus(clock(), onBadDate = { warn("line ${line.id}: unreadable alert posting date") }) { reason ->
                directions?.directionsOf(line.id, reason)
            }
                // Marked while a lookup for it runs, so the caller asks again next refresh instead
                // of reusing this unsplit answer for its whole reuse window (Codex, PR #334).
                ?.let { if (directions?.anyUnknown(line) == true) it.copy(awaitingDirections = true) else it }
        }
    }

    /**
     * Starts the one-off direction lookup ([LineAlertDirections]) for the alerts in [lines] not seen
     * before, without waiting for it: this refresh shows them for both directions, the next one by
     * direction. The detailed response is ~150 KB a line, so it is asked for only here, per new alert.
     */
    private fun lookUpAlertDirections(lines: List<TflLineDto>) {
        val cache = alertDirections ?: return
        val scope = alertDirectionScope ?: return
        val claimed = cache.claimUnknown(lines)
        if (claimed.isEmpty()) return
        scope.launch(alertDirectionDispatcher) {
            try {
                // One request per group TfL accepts, each recorded on its own (LineStatusBatch), so a
                // later group failing doesn't discard the directions an earlier one returned.
                val byId = claimed.associateBy { it.id }
                val results = LineStatusBatch.request(claimed.map { it.id }) { chunk ->
                    tflRequest { key ->
                        httpClient.get("$baseUrl/Line/${chunk.joinToString(",")}/Status") {
                            parameter("detail", "true")
                            applyAppKey(key)
                            allowSlowAnswer()
                        }.body<List<TflLineDto>>()
                    }
                }
                results.answers.forEach { answer -> cache.record(answer.lineIds.mapNotNull(byId::get), answer.value) }
                // The rest keep showing for both directions, as before; the next refresh asks again.
                val unanswered = results.failed + results.unknown
                if (unanswered.isNotEmpty()) {
                    cache.release(unanswered.mapNotNull(byId::get))
                    warn("alert directions: ${unanswered.size} lines, ${results.failure?.let { it::class.simpleName } ?: "NotFound"}")
                }
            } catch (e: CancellationException) {
                cache.release(claimed)
                throw e
            }
        }
    }

    override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
        tflRequest { key ->
            httpClient.get("$baseUrl/StopPoint/$stopId/Disruption") {
                // Both default false, which would miss the very closures this exists to
                // surface (SPEC principle 1): getFamily includes disruptions recorded
                // against a station's child platforms/entrances (a hub id carries few of
                // its own), and includeRouteBlockedStops includes a stop blocked by a
                // route-level disruption.
                parameter("getFamily", true)
                parameter("includeRouteBlockedStops", true)
                applyAppKey(key)
            }.body<TflDisruptedPointFamilyDto>().allDisruptions { raw ->
                // Length only: the raw value is TfL's, but a bare fact keeps the log coarse.
                warn("stop disruption for stop $stopId: unparseable date (${raw.length} chars), window left open")
            }
        }

    override suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> {
        if (stopIds.isEmpty()) return emptyMap()
        val ids = stopIds.joinToString(",")
        val entries = tflRequest { key ->
            // No getFamily: TfL rejects it for more than one stop, and a pole's family is its whole
            // junction, which would pin a sibling pole's closure on this one. A multi-stop request
            // returns a flat array, each entry naming its stop.
            httpClient.get("$baseUrl/StopPoint/$ids/Disruption") {
                parameter("includeRouteBlockedStops", true)
                applyAppKey(key)
            }.body<List<TflStopDisruptionDto>>()
        }
        val byStop = entries.groupBy { it.atcoCode }
        return stopIds.associateWith { stopId ->
            byStop[stopId].orEmpty().mapNotNull { dto ->
                dto.toStopDisruptionOrNull { raw ->
                    warn("stop disruption for stop $stopId: unparseable date (${raw.length} chars), window left open")
                }
            }.distinct()
        }
    }

    /**
     * Adds the request's `app_key` [key] when one is set (SPEC D7); a null/blank value adds nothing
     * (keyless). [key] is the single per-request snapshot [tflRequest] took, the same value the
     * limiter's budget was selected from — so the two never disagree.
     */
    /**
     * Lets a request TfL can be slow to start answering wait [SLOW_SOCKET_TIMEOUT_MILLIS] without
     * data instead of OkHttp's 10 s. Needs the [HttpTimeout] plugin, which the production client
     * installs; a client without it keeps its engine's timeouts.
     */
    private fun HttpRequestBuilder.allowSlowAnswer() {
        if (httpClient.pluginOrNull(HttpTimeout) != null) {
            timeout { socketTimeoutMillis = SLOW_SOCKET_TIMEOUT_MILLIS }
        }
    }

    private fun HttpRequestBuilder.applyAppKey(key: String?) {
        if (!key.isNullOrBlank()) parameter("app_key", key)
    }

    /**
     * Runs a TfL request and maps every transport/decode failure to the domain
     * [TflException] the caller reasons about (offline / rate-limited / unreachable),
     * so both endpoints share one error contract rather than repeating the mapping.
     * Sanitized throughout — a status code or class name, never a payload (SPEC *Privacy*).
     * Runs inside a [requestPool] slot, taken before the rate token so a queued request holds no
     * token while it waits for a slot.
     */
    private suspend fun <T> tflRequest(block: suspend (key: String?) -> T): T =
        requestPool.run { tflRequestInSlot(block) }

    private suspend inline fun <T> tflRequestInSlot(block: suspend (key: String?) -> T): T =
        try {
            // One read of the active key per request, used for both the budget and the app_key so
            // they can't disagree (Codex). Throttle toward the budget before issuing the request
            // (item 6): acquire() may suspend (deferring this background refresh) or throw
            // RateLimited when the budget is spent; both are handled below — RateLimited propagates
            // as the honest state, and a canceled wait rethrows CancellationException.
            val key = appKey()
            rateLimiterFor(key).acquire()
            block(key)
        } catch (e: CancellationException) {
            // Never swallow cancellation — rethrow first so structured concurrency
            // isn't broken (a canceled refresh must actually cancel).
            throw e
        } catch (e: ClientRequestException) {
            // 4xx. 429 is the one the UI treats specially (a user app_key lifts the
            // limit); any other client error is "reached TfL, request rejected".
            throw when (e.response.status) {
                HttpStatusCode.TooManyRequests -> TflException.RateLimited(e)
                HttpStatusCode.NotFound -> TflException.NotFound(e)
                else -> TflException.Unreachable("HTTP ${e.response.status.value}", e)
            }
        } catch (e: ServerResponseException) {
            throw TflException.Unreachable("HTTP ${e.response.status.value}", e)
        } catch (e: UnknownHostException) {
            // No DNS resolution — the device has no network path at all, the
            // conventional "you're offline" signal. Checked before the broader
            // IOException below so a reachable-but-failing TfL isn't mislabeled.
            throw TflException.Offline(e)
        } catch (e: IOException) {
            // Online but the request didn't complete — a connect/read timeout,
            // connection refused, or a TLS failure. The device has a network but the
            // request didn't get through, so this is Network rather than Offline
            // (which would wrongly tell the user they're offline).
            throw TflException.Network("transport: ${e::class.simpleName}", e)
        } catch (e: TflException) {
            throw e
        } catch (e: Exception) {
            // A decode failure or anything else unexpected: reached the client but
            // couldn't produce a result. Sanitized — the class name, no payload.
            throw TflException.Unreachable("unexpected: ${e::class.simpleName}", e)
        }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.tfl.gov.uk"

        /**
         * How long a request TfL is slow to start answering may go without data: the nearby-stops
         * search (an uncached 1-mile search in central London measured 7–11 s before TfL's first
         * byte) and a line's route sequence (an uncached National Rail line measured 4–15 s), both
         * past OkHttp's 10 s default.
         */
        const val SLOW_SOCKET_TIMEOUT_MILLIS: Long = 30_000

        /** The longest walk a planned trip may ask of the rider, in minutes (fixed for now). */
        const val MAX_WALKING_MINUTES: Int = 15

        /**
         * The modes a trip is planned over: the Planner's own default set, named so that it times each
         * walk at the rider's `walkingSpeed`. Without `mode=` it ignores the speed for every walk in a
         * route that rides — the first, the changes and the last alike (measured 2026-09-28: a 649 m
         * first walk read 11 min at Slow, Average and Fast; named, 15, 11 and 8, and a slower pace
         * made it offer other connections). Named, it offered the same routes as its default on every
         * trip compared, boats and the cable car included; tour boats (`river-tour`) and
         * `international-rail` stay out, as the default leaves them.
         */
        const val PLANNER_MODES: String =
            "bus,cable-car,coach,dlr,elizabeth-line,national-rail,overground,replacement-bus,river-bus,tram,tube,walking"

        /**
         * The fixed, always-resolvable destination the postcode resolver plans to — King's Cross St
         * Pancras Underground, a major interchange (public TfL data, SPEC *Privacy*). Only the trip's
         * resolved origin (or the `from` disambiguation) is read, never the route to it.
         */
        const val POSTCODE_ANCHOR_ID: String = "940GZZLUKSX"

        /** How many name-search matches to ask for — a screenful; a longer query narrows it. */
        const val SEARCH_MAX_RESULTS: Int = 20

        /** The production HTTP client: OkHttp engine + lenient JSON, failing on non-2xx. */
        fun defaultHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                engine {
                    config {
                        // OkHttp's own per-host cap defaults to 5, below the request pool's; raise it
                        // to match so the pool is the one limit that decides concurrency, not a
                        // hidden second queue inside the engine.
                        dispatcher(
                            Dispatcher().apply {
                                maxRequestsPerHost = SharedTflRequestPool.MAX_CONCURRENT
                            },
                        )
                    }
                }
                expectSuccess = true
                // No client-wide values, so every request keeps OkHttp's defaults; installed so a
                // slow endpoint can ask for longer per request (nearbyStops).
                install(HttpTimeout)
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
    }
}
