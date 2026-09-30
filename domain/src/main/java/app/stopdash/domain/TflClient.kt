package app.stopdash.domain

import java.time.Instant

/**
 * Reads live departures from TfL, behind a domain interface so the decision
 * logic (and later the ViewModel) depends on this, not on Ktor or the network —
 * and is tested against recorded fixtures via a fake or a Ktor `MockEngine`
 * (SPEC *Architecture* / *Testing*).
 *
 * Returns departures **unsorted and unfiltered**: ordering, the "0 min"/"N min"
 * label, and dropping a service that has gone are the caller's job via
 * [Countdown], recomputed from the current clock (SPEC D4). Implementations
 * throw on a transport or decode failure; the caller decides what the user sees
 * (offline / can't-reach-TfL / rate-limited), per SPEC *When something is wrong*.
 */
interface TflClient {
    suspend fun arrivals(stopId: String): List<Departure>

    /**
     * Where [stopId]'s National Rail times stood in its last [arrivals] (SPEC *National Rail*): the
     * board came back, no key is set, or the board failed; null when none apply. Read after
     * [arrivals] returns; a client with no National Rail feed always answers null.
     */
    fun railFeed(stopId: String): RailFeed? = null

    /**
     * When the oldest part of [stopId]'s last [arrivals] was fetched, where that's before they were
     * asked for: a station's National Rail board another screen fetched moments ago, kept for every
     * screen ([ArrivalsCache.fetchOnce]). Null when all of it was asked for then. Read after
     * [arrivals] returns; a screen dates the arrivals by it ([stampOf]), so they show at their real
     * age (SPEC D4).
     */
    fun fetchedAt(stopId: String): Instant? = null

    /**
     * Whether [stopId]'s [arrivals] are the same whichever client asked, so another screen may show
     * them ([ArrivalsCache]). Not so where a client decides between stops: two stops sharing one
     * National Rail station show its board under only the one that client picked ([RailAwareTflClient]).
     */
    fun shareable(stopId: String): Boolean = true

    /**
     * Where this client's [arrivals] come from right now, as far as it changes what they hold: whether
     * National Rail times are on ([RailAwareTflClient]). An arrival kept ([ArrivalsCache]) from another
     * source isn't this client's to reuse. Null for a client with only one source.
     */
    fun arrivalsSource(): Any? = null

    /**
     * The current status of each line in [lineIds], from `/Line/{ids}/Status` — one
     * [LineStatus] per line TfL knows, carrying the worst of that line's statuses. An
     * empty [lineIds] makes no request and returns empty. Like [arrivals] it throws on a
     * transport or decode failure; the caller decides what a failed disruption lookup
     * means (SPEC *Disruptions*: mark the affected departures "status unknown", never
     * present them as verified-clean).
     */
    suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus>

    /**
     * Disruptions reported for the stop [stopId], from `/StopPoint/{id}/Disruption` — a
     * closure or stop-level notice, empty when the stop is clear. Per stop (the endpoint
     * scopes to it), so a closed stop is flagged even when its lines run normally (SPEC
     * *Disruptions*). Throws on a transport/decode failure, like [arrivals].
     */
    suspend fun stopDisruptions(stopId: String): List<StopDisruption>

    /**
     * Disruptions for several **bus poles** ([StopDisruptionBatch.isPole]) in one request, keyed by
     * stop id; a pole with none maps to empty. One request for a whole junction instead of one per
     * pole — a busy corner's biggest cost against the keyless rate budget. Each pole gets only its
     * own notices: a pole has no children to walk, and TfL's family view of a pole is its whole
     * junction, which would pin a sibling's closure on an open pole. Throws like [arrivals]. The
     * default asks per stop, so a fake that only implements [stopDisruptions] still answers.
     */
    suspend fun poleDisruptions(stopIds: List<String>): Map<String, List<StopDisruption>> =
        stopIds.associateWith { stopDisruptions(it) }

    /**
     * The interchange [hubId] (TfL `hubNaptanCode`) resolved from `/StopPoint/{hubId}`: its display
     * [HubInfo.name] — e.g. "King's Cross & St Pancras International" for `HUBKGX`, cleaned of its
     * type suffix like a stop name — plus [HubInfo.aliases], every member station's cleaned name.
     * The name titles an interchange's folded disruption by the interchange rather than one member
     * stop; the aliases let the display strip drop a redundant leading name even when the notice
     * uses a different member's spelling than the watched stop (SPEC *Disruptions*). The real client
     * throws on a transport/decode failure, like [arrivals]; the caller falls back to the stop's own
     * name so a failed lookup never blanks the alert. Defaults to empty so a client that doesn't
     * enrich hubs, and a test fake, take the same safe fallback without implementing it.
     */
    suspend fun hubInfo(hubId: String): HubInfo = HubInfo()
}

/** An interchange's resolved display [name] and every member-station [aliases] spelling. */
data class HubInfo(val name: String = "", val aliases: List<String> = emptyList())

/**
 * When [stopId]'s last [TflClient.arrivals], asked for at [askedAt], were fetched: then, or as long
 * ago as the oldest part of them was ([TflClient.fetchedAt]).
 */
fun TflClient.stampOf(stopId: String, askedAt: Instant): Instant =
    fetchedAt(stopId)?.takeIf { it.isBefore(askedAt) } ?: askedAt
