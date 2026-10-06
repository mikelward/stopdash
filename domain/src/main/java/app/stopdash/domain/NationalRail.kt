package app.stopdash.domain

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * National Rail's live departure boards (Darwin), by a station's three-letter CRS code (SPEC
 * *National Rail*). [available] is false with no key, and then nothing is asked. Throws a
 * [TflException] on failure, the same contract as [TflClient], so a caller handles both alike.
 */
interface RailBoardSource {
    val available: Boolean
    suspend fun departures(crs: String): List<Departure>

    /** [crs]'s whole board: its trains with a time, and those it lists with none ([RailBoard.untimed]). */
    suspend fun board(crs: String): RailBoard = RailBoard(departures(crs))
}

/**
 * A National Rail station's board: the trains with a time to count down ([departures]), and those it
 * lists with none ([untimed]), kept apart so only a surface that says what they are shows them.
 */
data class RailBoard(val departures: List<Departure>, val untimed: List<UntimedTrain> = emptyList())

/**
 * A National Rail train its board lists with no time to count down: [canceled], or "Delayed" with no
 * estimate (SPEC *National Rail*). [train] is it as a departure would be, its
 * [Departure.expectedArrival] the time it was scheduled to leave: that places it among the trains with
 * a time, and is never counted down. Its own type, never in a list of [Departure]s, so nothing that
 * times a journey or a countdown can take it for a train that's coming.
 */
data class UntimedTrain(val train: Departure, val canceled: Boolean)

/**
 * Where a National Rail station's National Rail times stand after its last fetch: the board came
 * back ([LIVE], so a line with no trains truly has none), the user has set no key so none was asked
 * for ([NO_KEY]), or the board was asked for and failed ([UNAVAILABLE]).
 */
enum class RailFeed { LIVE, NO_KEY, UNAVAILABLE }

/** What a line with no departures shows where its times would be (SPEC *Departures*). */
enum class NoTimes {
    /** Its source answered with no trains: a dash. */
    NO_TRAINS,

    /** A National Rail line with no key set: "No key", a tap away from Settings. */
    NO_KEY,

    /** No source answered for it (no board, or a failed one): "No data". */
    NO_DATA,
    ;

    companion object {
        /**
         * TfL answered for every line but National Rail, whose times come from its own board: a
         * National Rail line has trains to count only when that board came back.
         */
        fun of(row: DepartureRow): NoTimes = when {
            !row.mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true) -> NO_TRAINS
            row.railFeed == RailFeed.LIVE -> NO_TRAINS
            row.railFeed == RailFeed.NO_KEY -> NO_KEY
            else -> NO_DATA
        }
    }
}

/**
 * Each National Rail station's CRS, keyed by its TIPLOC, the code TfL's `910G` stop ids end in
 * (`910GWATRLMN` → `WATRLMN` → `WAT`). From NaPTAN, so the two join exactly; a stop with no entry
 * gets no National Rail times.
 */
class RailStationCodes(private val codes: Map<String, String>) {
    fun crsFor(stopId: String): String? =
        if (stopId.startsWith(TFL_RAIL_PREFIX)) codes[stopId.removePrefix(TFL_RAIL_PREFIX)] else null

    // Each code's one TIPLOC; a code several share is left out. Built with the table, which is read
    // off the main thread ([RailStationCodesStore]), so a lookup is constant time wherever it runs.
    private val tiplocs: Map<String, String> =
        codes.entries.groupBy({ it.value }, { it.key }).mapNotNull { (crs, all) -> all.singleOrNull()?.let { crs to it } }.toMap()

    /**
     * The TfL stop id of the station with [crs], as a National Rail board names a train's terminus: how
     * its stop list finds the stop whatever the board calls it ("St Albans" for TfL's "St Albans
     * City"). Null when no station has it, or several do (St Pancras's three), as one would be a guess.
     */
    fun stopIdFor(crs: String): String? = tiplocs[crs.uppercase()]?.let { TFL_RAIL_PREFIX + it }

    companion object {
        private const val TFL_RAIL_PREFIX = "910G"
        val EMPTY = RailStationCodes(emptyMap())
    }
}

/**
 * A [TflClient] whose [arrivals] at a National Rail station also carry that station's National
 * Rail departures from [rail]: TfL's arrivals feed has none for Great Northern, Thameslink and the
 * rest (SPEC *National Rail*). The TfL-run services Darwin also lists (London Overground, the
 * Elizabeth line) come from TfL only, so none shows twice.
 *
 * TfL's answer decides the stop's outcome as before: its failure fails the stop. A failed rail board
 * is logged ([warn], sanitized: the stop id and reason) and the stop keeps its TfL departures, its
 * National Rail lines' status rows saying "No data" ([RailFeed.UNAVAILABLE]). Everything else is
 * [tfl]'s.
 *
 * A station's board is kept in [boards], the app's one cache ([ArrivalsCache]), under its code: a
 * board fetched for one stop or screen is what every other reads until it's [ArrivalsCache.TTL]
 * old, as a stop's arrivals are. A list shows the board under one of the station's stops only
 * (below); with [boardAtEveryStop] every stop gets it, as a trip needs for a train boarding at any
 * of them.
 */
class RailAwareTflClient(
    private val tfl: TflClient,
    private val rail: RailBoardSource,
    // Read on the request path (off the main thread), so a bundled table can load lazily.
    private val codes: () -> RailStationCodes,
    private val warn: (String) -> Unit = {},
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    // A trip times each leg from the station it boards at, whichever of the station's stops the
    // Planner names; a list would show the trains twice.
    private val boardAtEveryStop: Boolean = false,
    // Null keeps nothing between requests (tests, an unwired client).
    private val boards: ArrivalsCache? = null,
    private val clock: () -> Instant = Instant::now,
) : TflClient by tfl {
    // Each station code's owner: the one stop its board is fetched for and shown under, and when it
    // last asked. Two TfL stops can share a code (St Pancras's two National Rail ids, side by side in
    // every lookup); in a list the board is joined to only one of them, or its trains would show
    // twice ([boardAtEveryStop] turns this off for a trip). The first stop whose own TfL fetch works owns it,
    // and keeps it while it keeps asking, so the trains don't hop between the two; after
    // [OWNER_IDLE_MILLIS] without a request, or once its own TfL fetch fails, another stop may take
    // over.
    private val owners = HashMap<String, Pair<String, Long>>()
    // The standing owner's TfL fetch in progress, per code: completes when it ends, either way.
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()
    // A board a failed owner fetched, handed to the twin that takes over in the same refresh so the
    // station still costs one request; with when it was fetched, and used once.
    private val handoffs = HashMap<String, Pair<ArrivalsCache.Entry, Long>>()
    private val ownersLock = Mutex()

    // Each stop's [RailFeed] from its last [arrivals]; absent when none applies (not a National Rail
    // station, or its twin shows the board).
    private val feeds = ConcurrentHashMap<String, RailFeed>()

    override fun railFeed(stopId: String): RailFeed? = feeds[stopId]

    // When each stop's last board was fetched, which a kept one ([boards]) makes older than the stop's
    // own request; absent when its last arrivals carried no board.
    private val boardTimes = ConcurrentHashMap<String, Instant>()

    override fun fetchedAt(stopId: String): Instant? = boardTimes[stopId]

    // Each stop's trains with no time from its last board ([UntimedTrain]); absent when its last
    // arrivals carried no board.
    private val untimedByStop = ConcurrentHashMap<String, List<UntimedTrain>>()

    override fun untimed(stopId: String): List<UntimedTrain> = untimedByStop[stopId].orEmpty()

    // With National Rail times on, a station's board goes under whichever of its stops this client
    // picked, so its arrivals aren't another client's to reuse; with none, it's TfL's alone.
    override fun shareable(stopId: String): Boolean = !rail.available || codes().crsFor(stopId) == null

    // With a key its stations' boards join TfL's arrivals; without one they don't ("No key").
    override fun arrivalsSource(): Any? = rail.available

    override fun hasRailBoard(stopId: String): Boolean = rail.available && codes().crsFor(stopId) != null

    override suspend fun arrivals(stopId: String): List<Departure> {
        boardTimes.remove(stopId)
        untimedByStop.remove(stopId)
        val crs = codes().crsFor(stopId)
        if (crs == null || !rail.available) {
            // A station a key would give National Rail times says so; any other stop has none to give.
            if (crs != null) feeds[stopId] = RailFeed.NO_KEY else feeds.remove(stopId)
            return tfl.arrivals(stopId)
        }
        feeds.remove(stopId)
        if (boardAtEveryStop) return withBoard(stopId, crs)
        return coroutineScope {
            // The standing owner asks for its board alongside TfL, and marks its own fetch in flight;
            // any other stop claims the board only once its own TfL fetch worked. So of two twins
            // refreshed together, the board goes to one whose fetch succeeds, whichever finishes first.
            // Asking renews the owner's hold before its board request goes out, so a twin can't take
            // an idle owner's board over mid-refresh and spend a second request on it.
            val outcome = ownersLock.withLock {
                if (owners[crs]?.first != stopId) return@withLock null
                owners[crs] = stopId to elapsedMillis()
                CompletableDeferred<Unit>().also { inFlight[crs] = it }
            }
            val early = outcome?.let { async { fetchBoard(crs, stopId) } }
            val fromTfl = try {
                tfl.arrivals(stopId)
            } catch (e: CancellationException) {
                outcome?.let { settle(crs, it) }
                throw e
            } catch (e: Exception) {
                // This stop shows nothing now, so let a twin show the board, handing it the board
                // already asked for rather than have it ask again.
                val fetched = early?.await()
                ownersLock.withLock {
                    if (owners[crs]?.first == stopId) owners.remove(crs)
                    if (fetched != null) handoffs[crs] = fetched to elapsedMillis()
                }
                outcome?.let { settle(crs, it) }
                throw e
            }
            // A twin that finishes while the owner is still fetching waits for the owner's fetch to
            // end, then asks again: a failed owner has released the board by then, so the claim
            // succeeds whether or not the twin caught the owner in flight.
            val shows = claim(crs, stopId) || run {
                awaitOwner(crs)
                claim(crs, stopId)
            }
            outcome?.let { settle(crs, it) }
            when {
                !shows -> fromTfl.also { early?.cancel() }
                else -> {
                    val board = if (early != null) early.await() else takeHandoff(crs) ?: fetchBoard(crs, stopId)
                    showed(stopId, board)
                    fromTfl + board?.departures.orEmpty()
                }
            }
        }
    }

    // Nothing shown at the stop runs on National Rail (the mode is hidden), so its station's board
    // isn't asked for. A twin that still shows it (a starred journey's origin) takes it over at once,
    // rather than wait out this stop's hold. With no board to leave out (no key, no station) it's the
    // usual fetch, which asks for none and keeps a station's "No key" for when its rows show again.
    override suspend fun arrivals(stopId: String, railBoard: Boolean): List<Departure> {
        if (railBoard || !hasRailBoard(stopId)) return arrivals(stopId)
        boardTimes.remove(stopId)
        untimedByStop.remove(stopId)
        feeds.remove(stopId)
        codes().crsFor(stopId)?.let { crs -> ownersLock.withLock { if (owners[crs]?.first == stopId) owners.remove(crs) } }
        return tfl.arrivals(stopId)
    }

    /** [stopId]'s TfL arrivals with its station's board, asked for together. */
    private suspend fun withBoard(stopId: String, crs: String): List<Departure> = coroutineScope {
        val board = async { fetchBoard(crs, stopId) }
        // A failed TfL fetch fails the stop, as ever, and cancels the board's.
        val fromTfl = tfl.arrivals(stopId)
        val fetched = board.await()
        showed(stopId, fetched)
        fromTfl + fetched?.departures.orEmpty()
    }

    // [stopId]'s feed, board time and untimed trains once its arrivals carry [board], or would have
    // (null: it failed).
    private fun showed(stopId: String, board: ArrivalsCache.Entry?) {
        feeds[stopId] = if (board == null) RailFeed.UNAVAILABLE else RailFeed.LIVE
        board?.let {
            boardTimes[stopId] = it.fetchedAt
            untimedByStop[stopId] = it.untimed
        }
    }

    /** [crs]'s board, kept or asked for, with when it was fetched; null when it failed (logged). */
    private suspend fun fetchBoard(crs: String, stopId: String): ArrivalsCache.Entry? {
        suspend fun request(): RailBoard? =
            try {
                rail.board(crs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("national rail board failed for stop $stopId: ${e.message}")
                null
            }
        val cache = boards ?: return request()?.let { ArrivalsCache.Entry(it.departures, SteadyClock.stamp(clock()), untimed = it.untimed) }
        // Kept, and asked for once however many screens and stops ask at the same time.
        return cache.fetchBoardOnce(BOARD_KEY + crs, clock(), BOARD_SOURCE) { request() }
    }

    private suspend fun takeHandoff(crs: String): ArrivalsCache.Entry? = ownersLock.withLock {
        val (board, at) = handoffs.remove(crs) ?: return@withLock null
        board.takeIf { elapsedMillis() - at < HANDOFF_MILLIS }
    }

    /** Waits for the owner fetching for [crs] right now, if any, to finish. */
    private suspend fun awaitOwner(crs: String) {
        ownersLock.withLock { inFlight[crs] }?.await()
    }

    private suspend fun settle(crs: String, outcome: CompletableDeferred<Unit>) {
        ownersLock.withLock { if (inFlight[crs] === outcome) inFlight.remove(crs) }
        outcome.complete(Unit)
    }

    /** Whether [stopId] shows [crs]'s board: it owns it, or takes it over from an idle or no owner. */
    private suspend fun claim(crs: String, stopId: String): Boolean = ownersLock.withLock {
        val now = elapsedMillis()
        val owner = owners[crs]
        val mine = owner == null || owner.first == stopId || now - owner.second >= OWNER_IDLE_MILLIS
        if (mine) owners[crs] = stopId to now
        mine
    }

    companion object {
        /** How long a station code's owning stop keeps it without asking again. */
        const val OWNER_IDLE_MILLIS = 5 * 60_000L

        /** How long a failed owner's board waits for a twin to take it over: one refresh's span. */
        const val HANDOFF_MILLIS = 30_000L

        // A board's key in [boards], beside the stops' ids: no stop id has a colon.
        private const val BOARD_KEY = "national-rail:"

        // What a kept board was fetched from: a board, whatever key asked for it.
        private val BOARD_SOURCE = Any()
    }
}

/** TfL's mode id for National Rail lines, which a Darwin departure carries too. */
const val NATIONAL_RAIL_MODE = "national-rail"

/**
 * The operators whose trains TfL's own feed carries, left out of a National Rail board: London
 * Overground (LO), the Elizabeth line (XR), and London Underground (LT, its London Transport code),
 * which a board lists where tube trains share National Rail platforms (the District at Richmond,
 * the Bakerloo north of Queen's Park). TfL's rows for them carry the real line, its status and its
 * route; a board's copy would add a "London Underground" line (pill "LU") with neither.
 */
val TFL_RUN_OPERATORS = setOf("LO", "XR", "LT")

/**
 * TfL's National Rail line id for each operator's code (Darwin's `operatorCode`), from TfL's
 * `/Line/Mode/national-rail`. The operator's *name* doesn't always slug to it: West Midlands Trains
 * (`LM`) runs as "London Northwestern Railway" / "West Midlands Railway", whose slug TfL doesn't
 * know (every status and route request 404s), and Northern (`NT`) would slug to `northern`, the
 * tube line's id, taking its status. Checked 2026-09-26.
 */
private val TFL_RAIL_LINE_IDS = mapOf(
    "AW" to "transport-for-wales",
    "CC" to "c2c",
    "CH" to "chiltern-railways",
    "EM" to "east-midlands-railway",
    "GC" to "grand-central",
    "GN" to "great-northern",
    "GR" to "london-north-eastern-railway",
    "GW" to "great-western-railway",
    "GX" to "gatwick-express",
    "HT" to "hull-trains",
    "HX" to "heathrow-express",
    "IL" to "island-line",
    "LD" to "lumo",
    "LE" to "greater-anglia",
    "LM" to "west-midlands-trains",
    "ME" to "merseyrail",
    "NT" to "northern-rail",
    "SE" to "southeastern",
    "SN" to "southern",
    "SR" to "scotrail",
    "SW" to "south-western-railway",
    "TL" to "thameslink",
    "TP" to "transpennine-express",
    "VT" to "avanti-west-coast",
    "XC" to "crosscountry",
)

/**
 * TfL's line ids for the National Rail operators ([TFL_RAIL_LINE_IDS]'s values), so an interchange,
 * which names its lines without saying which run trains, can tell an operator from a bus route by id
 * ([Connections]). An operator TfL lists that isn't here is left out there, never guessed at.
 */
val NATIONAL_RAIL_LINE_IDS: Set<String> = TFL_RAIL_LINE_IDS.values.toSet()

/**
 * A National Rail operator's TfL line id, so its departures and TfL's status and route for the
 * line share one row: TfL's own id for a known [operatorCode], else the operator's name as a slug
 * ("Great Northern" → `great-northern`) — which, for an operator TfL has no line for (the
 * Caledonian Sleeper), TfL answers 404 and the app treats as a line it doesn't know.
 */
fun railLineId(operator: String, operatorCode: String? = null): String =
    operatorCode?.uppercase()?.let { TFL_RAIL_LINE_IDS[it] }
        ?: operator.lowercase().replace(NOT_SLUG, "-").trim('-')

/** What a line-id slug leaves out ([railLineId]): compiled once, as it's asked per departure. */
private val NOT_SLUG = Regex("[^a-z0-9]+")

/**
 * What a National Rail board's "via" text names ([Departure.via]), cleaned as a destination is
 * ([cleanStopName]): "via Wimbledon" gives "Wimbledon". Kept whole, as a station's own name can hold
 * an "&" or "and" (Elephant & Castle); [viaSpans] reads it as more than one. Blank for no text.
 */
fun railVia(text: String?): String {
    val names = text?.trim()?.replace(LEADING_VIA, "")?.trim().orEmpty()
    return if (names.isEmpty()) "" else cleanStopName(names)
}

/**
 * The runs of words [via] ([railVia]) may name a station by: between its "&"s and "and"s it has
 * parts, and `spans[i][k]` is parts i through i + k as written, joins included. So "Elephant &
 * Castle and Denmark Hill" has `spans[0]` = "Elephant", "Elephant & Castle", "Elephant & Castle
 * and Denmark Hill". A route's stops say how the parts group into stations, if any way does
 * ([RouteStops.candidatePaths]); a station's own "&" (Elephant & Castle) stays in one span. Empty for
 * a blank via.
 */
fun viaSpans(via: String): List<List<String>> {
    if (via.isBlank()) return emptyList()
    val separators = VIA_SEPARATOR.findAll(via).toList()
    // Where each part starts and ends, so a span keeps the joins between its parts as written.
    val starts = listOf(0) + separators.map { it.range.last + 1 }
    val ends = separators.map { it.range.first } + via.length
    if (starts.indices.any { via.substring(starts[it], ends[it]).isBlank() }) return listOf(listOf(via.trim()))
    return starts.indices.map { i -> (i until starts.size).map { j -> via.substring(starts[i], ends[j]).trim() } }
}

private val LEADING_VIA = Regex("^via\\s+", RegexOption.IGNORE_CASE)
private val VIA_SEPARATOR = Regex("\\s*&\\s*|\\s+and\\s+", RegexOption.IGNORE_CASE)
