package app.stopdash.domain

/**
 * One debug-log line for a slow HTTP request (to TfL or National Rail), so a refresh that took
 * seconds says which request held it up and where the time went: getting a connection (a stalled
 * connect retried on another address costs a whole connect timeout), waiting for the answer to
 * start, or reading it. Counts, milliseconds, the endpoint's shape and an exception class only:
 * never a stop, line, query or key (`docs/PRIVACY.md`).
 */
object SlowRequest {
    /** A request this long is logged; a normal one is well under a second. */
    const val SLOW_MILLIS = 2_000L

    /**
     * What one request's timing came to. [connectionMillis] runs from the start of the call to a
     * connection in hand: a reused one is near zero, a new one includes DNS, the connect and TLS.
     * [failedConnects] names each connect attempt that failed before one worked (its address family
     * and exception class). [answerMillis] is from sending the request to the answer's first byte;
     * [readMillis] from there to the end. Null where the call never got that far.
     */
    data class Timings(
        val host: String,
        val path: String,
        val totalMillis: Long,
        val connectionMillis: Long?,
        val newConnection: Boolean,
        val failedConnects: List<String> = emptyList(),
        val answerMillis: Long? = null,
        val readMillis: Long? = null,
        val failure: String? = null,
    )

    /** The log line for [timings], or null when the request was quick and every connect worked. */
    fun describe(timings: Timings): String? {
        if (timings.totalMillis < SLOW_MILLIS && timings.failedConnects.isEmpty()) return null
        val parts = buildList {
            timings.connectionMillis?.let { ms ->
                val failed = timings.failedConnects
                val how = if (timings.newConnection) "new" else "reused"
                add(
                    "connection $ms ms ($how" +
                        (if (failed.isEmpty()) "" else "; ${failed.size} failed: ${failed.joinToString()}") + ")",
                )
            } ?: timings.failedConnects.takeIf { it.isNotEmpty() }?.let { add("no connection (${it.size} failed: ${it.joinToString()})") }
            timings.answerMillis?.let { add("answer after $it ms") }
            timings.readMillis?.let { add("read $it ms") }
            timings.failure?.let { add("failed: $it") }
        }
        return "${source(timings.host)} ${endpoint(timings.path)} took ${timings.totalMillis} ms" +
            if (parts.isEmpty()) "" else ": ${parts.joinToString(", ")}"
    }

    /** Who a request went to, by name where it's one the app calls. */
    fun source(host: String): String = when {
        host.endsWith("tfl.gov.uk") -> "TfL"
        host.endsWith("raildata.org.uk") -> "National Rail"
        else -> host
    }

    /**
     * A request's endpoint with its identifiers left out, by the route it matches in [ROUTES]: each
     * route's fixed words stay and its variable segments (a stop or line id, a station code, a
     * postcode or anything typed) read "…", by position, so a typed value that happens to spell a
     * path word is still left out. A path matching no known route is "other". So
     * `StopPoint/490000000A,490000000B/Disruption` is `StopPoint/…/Disruption`.
     */
    fun endpoint(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val route = ROUTES.firstOrNull { route ->
            route.size == segments.size && route.indices.all { i -> route[i] == ANY || route[i] == segments[i] }
        } ?: return "other"
        return route.map { if (it == ANY) "…" else it }
            .fold(mutableListOf<String>()) { out, part -> out.apply { if (part != "…" || lastOrNull() != "…") add(part) } }
            .joinToString("/")
    }

    private const val ANY = "*"

    // The paths the app requests (`KtorTflClient`, `KtorDarwinClient`), `*` for a variable segment.
    // Fixed routes before the variable ones they'd otherwise match. A new endpoint reads "other"
    // until added here, which is the safe way round.
    private val ROUTES = listOf(
        "StopPoint",
        "StopPoint/Search",
        "StopPoint/*",
        "StopPoint/*/Arrivals",
        "StopPoint/*/Disruption",
        "Line/*/Status",
        "Line/*/Route/Sequence/*",
        "Line/*/Timetable/*",
        "Journey/JourneyResults/*/to/*",
        "Disruptions/Lifts/*",
        "Vehicle/*/Arrivals",
        // National Rail's Live Departure Board, under its product's base path.
        "*/*/*/*/GetDepartureBoard/*",
    ).map { it.split('/') }
}
