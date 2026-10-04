package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One end-to-end route of a line in one direction, from TfL's `/Line/{id}/Route/Sequence`
 * `orderedLineRoutes`: its [name] as TfL spells it ("Morden ↔ Edgware via Bank"), its
 * [stopIds] in travel order, and the TfL [direction] (`inbound`/`outbound`) it was fetched for,
 * blank where not known (a sequence cached before this was kept).
 */
data class LineRoute(val name: String, val stopIds: List<String>, val direction: String = "")

/**
 * A line's routes in one or both directions, plus a display name for each stop id ([stopNames],
 * already [cleanStopName]d) and the lines that serve each stop or its interchange ([stopLines],
 * a line's [LineRef.mode] blank where TfL's data doesn't say). Static network data — no user
 * data, no clock.
 */
data class LineSequence(
    val routes: List<LineRoute>,
    val stopNames: Map<String, String>,
    val stopLines: Map<String, List<LineRef>> = emptyMap(),
    // Each stop's published (latitude, longitude), where TfL gave one — a starred journey's ends.
    val stopPositions: Map<String, Pair<Double, Double>> = emptyMap(),
    // Each stop's stop area (TfL `stationId`), where given: opposite bus stops often share one.
    val stopAreas: Map<String, String> = emptyMap(),
    // Each stop's interchange (TfL `topMostParentId`, e.g. `HUBKGX`), where it has one other than
    // itself: how a departure listed under one of a station's stop ids finds the sibling id the
    // route calls at (see [callingAt]).
    val stopHubs: Map<String, String> = emptyMap(),
) {
    operator fun plus(other: LineSequence) =
        LineSequence(
            routes + other.routes,
            stopNames + other.stopNames,
            stopLines + other.stopLines,
            stopPositions + other.stopPositions,
            stopAreas + other.stopAreas,
            stopHubs + other.stopHubs,
        )

    /**
     * This sequence as seen from [stopId], a stop departures or a journey end are listed under. TfL
     * can list a station's departures under one stop id and route the line through a sibling: St
     * Pancras's Thameslink trains depart under its domestic-platforms id, while the sequence calls at
     * its main and low-level ids. When no route calls at [stopId], every stop on a route in the same
     * interchange ([stopHubs]) that is the same station by name ([stopNames], or it plus a platform
     * qualifier like "LL") becomes [stopId], so the route page, a journey starred from it, and that
     * journey's trains all work from the id the departures carry. The name keeps another station in
     * the hub (King's Cross beside St Pancras) out, which would otherwise make the path ambiguous or
     * wrong. [stopId]'s own hub and name come from the station index ([withStations]). Unchanged
     * when a route calls at [stopId], its hub or name is unknown, or no sibling matches.
     */
    @WorkerThread
    fun callingAt(stopId: String): LineSequence {
        val onRoute = routes.flatMapTo(HashSet()) { it.stopIds }
        if (stopId in onRoute) return this
        val hubId = stopHubs[stopId].orEmpty()
        val name = stopNames[stopId].orEmpty()
        if (hubId.isBlank() || name.isBlank()) return this
        val siblings = onRoute.filterTo(LinkedHashSet()) { id ->
            stopHubs[id] == hubId && sameStation(name, stopNames[id].orEmpty())
        }
        if (siblings.isEmpty()) return this
        fun <T> firstOf(map: Map<String, T>): T? = siblings.firstNotNullOfOrNull { map[it] }
        return copy(
            routes = routes.map { route -> route.copy(stopIds = route.stopIds.map { if (it in siblings) stopId else it }) },
            stopLines = stopLines + (stopId to siblings.flatMap { stopLines[it].orEmpty() }.distinct()),
            stopPositions = firstOf(stopPositions)?.let { stopPositions + (stopId to it) } ?: stopPositions,
            stopAreas = firstOf(stopAreas)?.let { stopAreas + (stopId to it) } ?: stopAreas,
        )
    }

    /**
     * This sequence knowing the [stations] (the bundled station index) that share an interchange
     * with one of its stops: each one's hub and name, for [callingAt]. A stop TfL routes the line
     * past is added this way, whatever row, saved snapshot or journey names it. What TfL's response
     * already says about a stop is kept.
     */
    @WorkerThread
    fun withStations(stations: Map<String, List<IndexedStation>>): LineSequence {
        val hubs = stopHubs.values.toSet()
        val known = stations.filterKeys { it in hubs }.values.flatten().filter { it.id !in stopHubs }
        if (known.isEmpty()) return this
        return copy(
            stopNames = known.associate { it.id to it.name } + stopNames,
            stopHubs = known.associate { it.id to it.hubId } + stopHubs,
        )
    }
}

// "St Pancras International" and "St Pancras International LL" are one station; "King's Cross" is
// not. Either name may carry the qualifier, as TfL's ids don't say which one is the main.
private fun sameStation(rawA: String, rawB: String): Boolean {
    // By the matching form, so a line qualifier on one spelling doesn't part them ([matchStopName]);
    // two that name different lines do: Hammersmith's two stations share an interchange
    // ([conflictingQualifiers]).
    if (conflictingQualifiers(rawA, rawB)) return false
    val a = matchStopName(rawA)
    val b = matchStopName(rawB)
    if (a.isBlank() || b.isBlank()) return false
    val (short, long) = if (a.length <= b.length) a to b else b to a
    return long.equals(short, ignoreCase = true) || long.startsWith("$short ", ignoreCase = true)
}

/**
 * One station on the route detail's stop list, with the [connections] a rider can change to
 * there — other rail-type lines at the station or its interchange (see [Connections]).
 */
data class RouteStop(val id: String, val name: String, val connections: List<LineRef> = emptyList())

/**
 * Reads a line's route sequence from TfL — the stop list behind the route detail page (SPEC
 * *Route detail*). Separate from [TflClient] so the background refresh path can't reach it: it is
 * fetched only when the user opens a route. Throws a [TflException] on failure, like [TflClient].
 */
interface RouteSequenceSource {
    /** [direction] is TfL's `inbound` or `outbound`. */
    suspend fun routeSequence(lineId: String, direction: String): LineSequence
}

/**
 * Reads a stop area's poles from TfL (`/StopPoint/{areaId}`): each pole's id, letter and lines —
 * how a starred bus journey finds the poles beside its origin that other lines board from (SPEC
 * *Journeys*). On demand like [RouteSequenceSource], never on the refresh path. Throws a
 * [TflException] on failure.
 */
interface StopAreaSource {
    suspend fun stopAreaPoles(areaId: String): List<StopLocation>
}

/**
 * A departure a filter left out because it couldn't be checked against its line's route: the line,
 * the stop it boards at, why ([RouteStops.Resolution], never [RouteStops.Resolution.Found]), and
 * the [destination] TfL gave it (its terminus label, blank where none) — public TfL data, so it can
 * go in the debug log as it stands. A route that failed to load isn't one: its fetch failure is
 * logged where it happened.
 */
data class RouteMiss(
    val lineId: String,
    val stopId: String,
    val reason: RouteStops.Resolution,
    val destination: String = "",
)

object RouteStops {
    private val DIRECTIONS = listOf("inbound", "outbound")

    /**
     * The whole of the route in [sequence] that [stops] (a stop list from where the rider boards on, as
     * [resolve] gives it) is part of, from its first stop: where a line's alert can name the stretch it
     * touches before the boarding stop, which the list leaves off (maintainer, 2026-10-02). The longest
     * where several routes run it; empty where none does.
     */
    @WorkerThread
    fun wholeRouteOf(sequence: LineSequence, stops: List<RouteStop>): List<RouteStop> {
        if (stops.isEmpty()) return emptyList()
        val ids = stops.map(RouteStop::id)
        val route = sequence.routes.filter { java.util.Collections.indexOfSubList(it.stopIds, ids) >= 0 }
            .maxByOrNull { it.stopIds.size } ?: return emptyList()
        return route.stopIds.map { RouteStop(it, sequence.stopNames[it].orEmpty()) }
    }

    /** The TfL directions to fetch for a row: its own when TfL gave one, else both. */
    fun directionsFor(direction: String): List<String> =
        if (direction in DIRECTIONS) listOf(direction) else DIRECTIONS

    /**
     * Why [resolve] could or couldn't produce a stop list — the reason is what the debug log
     * records when the route page says the list is unavailable (SPEC principle 2: never fail
     * silently). Carries only network data (TfL stop and line ids), no user data.
     */
    sealed interface Resolution {
        data class Found(val stops: List<RouteStop>) : Resolution
        /** No destination to follow. */
        data object NoDestination : Resolution
        /** No route in the sequence calls at the boarding stop. */
        data object NotOnRoute : Resolution
        /** The stop is on a route, but nothing ahead of it matches the destination. */
        data object NoMatch : Resolution
        /**
         * The train or bus ends at the stop it's listed under ([endsAt]): it takes no one anywhere
         * from there, so it reaches nothing, as surely as one whose path is known.
         */
        data object EndsHere : Resolution
        /** The destination matches, but no path calls where the board says it runs by ([Departure.via]). */
        data object ViaMatchesNoRoute : Resolution
        /** More than one distinct path matches; [paths] of them. */
        data class Ambiguous(val paths: Int) : Resolution
        /** TfL has no route for the line at all (a National Rail service it doesn't know). */
        data object UnknownLine : Resolution
        /** The departure carries no line id, so there is no route to follow it on. */
        data object NoLine : Resolution
    }

    /** The stop list, or null when [resolve] can't say which path the train takes. */
    @WorkerThread
    fun ahead(
        sequence: LineSequence,
        stopId: String,
        destination: String,
        branch: String?,
        lineId: String = "",
        bus: Boolean = false,
        bound: Bound? = null,
        direction: String = "",
        destinationId: String = "",
        via: String = "",
    ): List<RouteStop>? = (resolve(sequence, stopId, destination, branch, lineId, bus, bound, direction, destinationId, via) as? Resolution.Found)?.stops

    /**
     * Which way a train leaves its platform, from the platform's name ("Eastbound - Platform 2",
     * "Inner Rail - Platform 1"): what tells the two ways round a loop apart (the Circle line), where
     * a train's destination is the same either way. TfL's `direction` can't: it names the train's
     * trip, and on a loop both trips pass the same platform.
     */
    enum class Bound { NORTH, SOUTH, EAST, WEST, INNER_RAIL, OUTER_RAIL }

    /** The [Bound] a platform's name starts with, else null (a bus, a platform with no compass). */
    fun boundOf(platform: String?): Bound? {
        val head = platform?.substringBefore(" - ")?.trim()?.lowercase() ?: return null
        return when (head) {
            "northbound" -> Bound.NORTH
            "southbound" -> Bound.SOUTH
            "eastbound" -> Bound.EAST
            "westbound" -> Bound.WEST
            "inner rail" -> Bound.INNER_RAIL
            "outer rail" -> Bound.OUTER_RAIL
            else -> null
        }
    }

    /**
     * TfL's stand-in for a train whose destination isn't set yet, as the platform board shows it
     * ("Check Front of Train"). Shown as TfL words it, but it names no place, so route matching
     * treats it as unknown: the train runs to one of its line's ends ([candidatePaths]).
     */
    fun isUnknownDestination(destination: String): Boolean =
        destination.isBlank() || destination.equals(CHECK_FRONT_OF_TRAIN, ignoreCase = true)

    private const val CHECK_FRONT_OF_TRAIN = "Check Front of Train"

    /**
     * The stations a train at [stopId] bound for [destination] (cleaned, as on a [Departure]) via
     * [branch] calls at, from the boarding stop through its terminus — or a non-[Resolution.Found]
     * reason when [sequence] can't say which path it takes (SPEC principle 1: no guessed stop list).
     *
     * A route matches when it calls at [stopId] and, later, at a stop named [destination] — so a
     * short-working (a Northern train terminating at Kennington) ends where the train does, not at
     * the line's end. A route with no such stop still matches if its *name* ends at [destination]
     * (a bus destination TfL spells differently from its last stop), running to its end. Where TfL
     * names the branch ("via Bank"), only matching routes count; the answer must then be one
     * unambiguous path. Each stop carries its connections other than [lineId], the line ridden.
     *
     * A [bus] gets one more fallback when neither test matches any route: its route end. A bus
     * arrival's destination is the blind's place label (an area or landmark, not the last stop's
     * name), which usually names no stop and not the route either, so without
     * this nearly every bus had no list. It still has to be a single path from here to the end, and
     * a short-working whose label *does* name a stop still ends there (the tests above run first).
     * Rail keeps the strict rule: its destinations name real stations, so a miss there means a
     * working the sequence doesn't model, and running it to the line's end would be a guess.
     *
     * A train or bus that ends here ([endsAt]: a bus arriving at its stand, a train turned short at
     * this station during engineering works) is [Resolution.EndsHere], not a miss: TfL lists it among
     * the stop's arrivals, but it goes nowhere from it. [destinationId] is TfL's id for its terminus.
     * [via] is what a National Rail board says it runs by ([Departure.via]).
     */
    @WorkerThread
    fun resolve(
        sequence: LineSequence,
        stopId: String,
        destination: String,
        branch: String?,
        lineId: String = "",
        bus: Boolean = false,
        // Which way the train leaves its platform ([boundOf]): picks between ways round a loop.
        bound: Bound? = null,
        direction: String = "",
        destinationId: String = "",
        via: String = "",
    ): Resolution {
        // TfL's id for where it ends is authoritative: ending here outranks any match by name (a loop
        // calling at a stop of the same name again), and holds with no destination named.
        if (destinationId.isNotBlank() && endsAt(sequence, stopId, destination, destinationId)) return Resolution.EndsHere
        if (isUnknownDestination(destination)) return Resolution.NoDestination
        if (sequence.routes.none { visits(it, stopId).isNotEmpty() }) return Resolution.NotOnRoute
        val paths = candidatePaths(sequence, stopId, destination, branch, bus, bound, direction, destinationId, via)
        if (paths.isEmpty()) {
            return when {
                endsAt(sequence, stopId, destination, destinationId) -> Resolution.EndsHere
                // Its destination matched, but no way runs by where its board says it does.
                via.isNotBlank() && candidatePaths(sequence, stopId, destination, branch, bus, bound, direction, destinationId).isNotEmpty() -> Resolution.ViaMatchesNoRoute
                else -> Resolution.NoMatch
            }
        }
        val path = paths.singleOrNull() ?: return Resolution.Ambiguous(paths.size)
        return Resolution.Found(
            path.map { id ->
                RouteStop(id, sequence.stopNames[id].orEmpty(), Connections.of(sequence.stopLines[id].orEmpty(), lineId))
            },
        )
    }

    /**
     * Whether a train at [stopId] calls at one of [destinationIds] after boarding, where its
     * [candidatePaths] all agree — true when every way it may take does, false when none does,
     * null when they differ or none is known. So a train whose exact path can't be told (TfL's
     * "Check Front of Train", or two ways matching its destination) still counts when it can only
     * reach the stop, or can't: it runs at least as far as where its possible ways part.
     */
    @WorkerThread
    fun reaches(
        sequence: LineSequence,
        stopId: String,
        destination: String,
        branch: String?,
        destinationIds: Set<String>,
        bus: Boolean = false,
        bound: Bound? = null,
        direction: String = "",
        destinationId: String = "",
        via: String = "",
    ): Boolean? {
        val paths = candidatePaths(sequence, stopId, destination, branch, bus, bound, direction, destinationId, via)
        if (paths.isEmpty()) return null
        val answers = paths.mapTo(HashSet()) { path -> path.drop(1).any { it in destinationIds } }
        return answers.singleOrNull()
    }

    /**
     * Every distinct way a train at [stopId] bound for [destination] via [branch] may take, from the
     * boarding stop through where it ends (see [resolve] for the matching rules). With no known
     * destination ([isUnknownDestination]), every way from here to its route's end. Where there's
     * more than one, a [bound] keeps those leaving the platform that way — unless it would keep
     * none, or the stops' positions can't say. With no [bound], the train's TfL [direction] keeps
     * the ways on routes fetched for it (a route whose direction isn't known stays), again unless
     * it would keep none. A service that ends here ([endsAt]) by TfL's id has no way at all, and a bus
     * that does by name isn't run on to its route's end. What a National Rail board says it runs [via]
     * keeps the ways calling there after boarding (the two ways round a loop to one terminus): those
     * whose stops its words, split at its "&"s and "and"s where a way's stops say so, can be read as
     * ([viaSpans]). A via no way fits leaves none: a working the routes don't model.
     */
    @WorkerThread
    fun candidatePaths(
        sequence: LineSequence,
        stopId: String,
        destination: String,
        branch: String?,
        bus: Boolean = false,
        bound: Bound? = null,
        direction: String = "",
        destinationId: String = "",
        via: String = "",
    ): List<List<String>> {
        if (sequence.routes.none { visits(it, stopId).isNotEmpty() }) return emptyList()
        // TfL's id saying it ends here outranks any way its name would match ([resolve]).
        if (destinationId.isNotBlank() && endsAt(sequence, stopId, destination, destinationId)) return emptyList()
        val unknown = isUnknownDestination(destination)
        // Every visit to [stopId] is a candidate origin and every later stop named [destination] a
        // candidate end: a loop can call here twice, and two stops can share a cleaned name (a
        // loop, a bus route passing a place twice, TfL's line qualifiers that [matchStopName]
        // drops). Nothing on the arrival says which, so each pairing is its own path, and more
        // than one leaves the answer ambiguous below rather than picking the first.
        // Per route: its stop-name matches, else (none on that route) its route-name terminus — so
        // one variant matching by stop name can't hide another that only matches by its name.
        // A destination with a line qualifier ("Paddington (H&C)") takes the stops of that exact name
        // where any match, and only failing those every stop of its name without one ([isLineQualified]).
        fun matchedBy(same: (String?, String) -> Boolean) = sequence.routes.flatMap { route ->
            val byStopName = visits(route, stopId).flatMap { i ->
                (i + 1 until route.stopIds.size).filter { k ->
                    same(sequence.stopNames[route.stopIds[k]], destination)
                }.map { j -> route to route.stopIds.subList(i, j + 1) }
            }
            byStopName.ifEmpty {
                if (!same(terminusOf(route.name), destination)) return@ifEmpty emptyList()
                toEnd(route, stopId)
            }
        }
        val matched = when {
            unknown -> sequence.routes.flatMap { toEnd(it, stopId) }
            isLineQualified(destination) -> matchedBy(::exactStopName).ifEmpty { matchedBy(::sameStopName) }
            else -> matchedBy(::sameStopName)
        }
        // A bus whose label matched nothing: every route calling here, run to its end — unless it ends
        // here, as a bus curtailed at this stop does.
        val candidates = if (matched.isEmpty() && bus && !endsAt(sequence, stopId, destination, destinationId)) {
            sequence.routes.flatMap { toEnd(it, stopId) }
        } else {
            matched
        }
        if (candidates.isEmpty()) return emptyList()
        // A branch TfL named narrows to the routes carrying it; if none carry it (an unlabeled
        // Battersea route for a "via CX" train), the branch can't narrow and all candidates stand.
        val onBranch = if (branch == null) {
            candidates
        } else {
            candidates.filter { (route, _) -> branchOf(route.name) == branch }.ifEmpty { candidates }
        }.let { all ->
            // A board's "via Wimbledon" names a station the train passes, not a route: the ways whose
            // stops after boarding its words can be read as, every part in a station ([viaSpans]).
            // Each way judged on its own, so two that each fit a different reading stay ambiguous.
            // A via no way fits is a working the routes don't model: no way, never one guessed.
            if (via.isBlank()) {
                all
            } else {
                val spans = viaSpans(via)
                all.filter { (_, path) -> readsAs(sequence, path.drop(1), spans) }
            }
        }
        val routeOf = onBranch.associate { (route, path) -> path to route }
        val paths = onBranch.map { it.second }.distinct()
        if (paths.size < 2) return paths
        if (bound != null) return paths.filter { leaves(sequence, routeOf.getValue(it), it, bound) != false }.ifEmpty { paths }
        // A platform with no compass ("Platform 1"): the train's own [direction] keeps the ways its
        // routes were fetched for — only then, as on a loop TfL's direction can name the other way
        // round from the platform the train is at, and the platform is what wins there.
        if (direction !in DIRECTIONS) return paths
        return onBranch.filter { (route, _) -> route.direction == direction || route.direction.isBlank() }
            .map { it.second }.distinct().ifEmpty { paths }
    }

    /**
     * Whether a via's parts ([viaSpans]) can be read, in order of its words, as stations among
     * [stops] in the order the route calls at them: each run of parts naming one of them
     * ([isViaStation]) later than the last, together covering every part. Quadratic in the parts
     * (times the stops), so a long via costs no more than its own length squared.
     */
    private fun readsAs(sequence: LineSequence, stops: List<String>, spans: List<List<String>>): Boolean {
        val names = stops.map { sequence.stopNames[it] }
        // after[i]: the earliest stop past which the next station may come, once the first i parts
        // read as stations here; -1 for none yet. The earliest is enough: any later one leaves less.
        val after = IntArray(spans.size + 1) { Int.MAX_VALUE }.also { it[0] = -1 }
        for (i in spans.indices) {
            if (after[i] == Int.MAX_VALUE) continue
            spans[i].forEachIndexed { k, text ->
                val at = (after[i] + 1 until names.size).firstOrNull { isViaStation(names[it], text) } ?: return@forEachIndexed
                after[i + k + 1] = minOf(after[i + k + 1], at)
            }
        }
        return after[spans.size] != Int.MAX_VALUE
    }

    /**
     * Whether a route's stop [name] is the station a board's via [station] names: by name, or with
     * the place TfL brackets after it dropped, as the board leaves it off ("Richmond (London)" for
     * "Richmond").
     */
    private fun isViaStation(name: String?, station: String): Boolean =
        name != null && (sameStopName(name, station) || matchStopName(name).substringBefore(" (").equals(matchStopName(station), ignoreCase = true))

    /**
     * Whether [path] (on [route]) leaves its first stop the way [bound] says: its first hop's
     * compass for north/south/east/west, or the turn about the route's middle for a loop's inner
     * and outer rail (the outer rail runs clockwise, as London drives on the left). Null where the
     * stops' positions aren't known.
     */
    private fun leaves(sequence: LineSequence, route: LineRoute, path: List<String>, bound: Bound): Boolean? {
        val (lat0, lon0) = sequence.stopPositions[path.getOrNull(0)] ?: return null
        val (lat1, lon1) = sequence.stopPositions[path.getOrNull(1)] ?: return null
        val north = lat1 - lat0
        val east = (lon1 - lon0) * Math.cos(Math.toRadians(lat0))
        return when (bound) {
            Bound.NORTH -> north > 0
            Bound.SOUTH -> north < 0
            Bound.EAST -> east > 0
            Bound.WEST -> east < 0
            Bound.INNER_RAIL, Bound.OUTER_RAIL -> {
                val known = route.stopIds.mapNotNull { sequence.stopPositions[it] }
                if (known.isEmpty()) return null
                val midLat = known.map { it.first }.average()
                val midLon = known.map { it.second }.average()
                // Cross product of (stop - middle) and the hop: negative turns clockwise.
                val cross = (lon0 - midLon) * north - (lat0 - midLat) * (lon1 - lon0)
                if (bound == Bound.OUTER_RAIL) cross < 0 else cross > 0
            }
        }
    }

    /**
     * The stops a planned [leg] rides, for a page with no train to follow: the route from where it
     * boards, by way of the most of the Planner's path and where it gets off, on to the Planner's
     * terminus, as a train's list runs — so a line that forks toward one terminus (the Northern
     * line's Bank and Charing Cross branches) follows the leg's own branch. The terminus is a later
     * stop so named, else the route's end if the route is named for it (or on a bus, whose blind
     * names a place, not a stop); a way reaching no such terminus is dropped rather than guessed.
     * Failing a single way, [resolve] toward the Planner's terminus, if that passes where the leg
     * gets off. [boarding] is already [LineSequence.callingAt] the boarding stop.
     */
    @WorkerThread
    fun forLeg(boarding: LineSequence, leg: TripLeg): Resolution {
        // Where it gets off by the id the Planner names, as where it boards: a station's other id
        // in the same interchange (a sibling platform) is the same place.
        val sequence = boarding.callingAt(leg.toId)
        fun alights(id: String) = id == leg.toId || (leg.toArea.isNotEmpty() && sequence.stopAreas[id] == leg.toArea)
        // How much of the planned path a way calls at, in order: branches that share their first
        // stops only part where the path does.
        fun followed(stops: List<String>): Int {
            var at = 0
            return leg.path.count { planned ->
                val found = (at until stops.size).firstOrNull { stops[it] == planned || sequence.stopAreas[stops[it]] == planned }
                    ?: return@count false
                at = found + 1
                true
            }
        }
        val bus = leg.mode.equals("bus", ignoreCase = true)
        val termini = leg.headings.ifEmpty { listOf(leg.toName) }
        // Exactly where a qualified terminus names a stop or route end of this line, else loosely
        // ([isLineQualified]).
        val exactly = termini.any(::isLineQualified) && termini.any { t ->
            sequence.stopNames.values.any { exactStopName(it, t) } || sequence.routes.any { exactStopName(terminusOf(it.name), t) }
        }
        fun terminus(name: String?) = termini.any { if (exactly) exactStopName(it, name) else sameStopName(it, name) }
        val candidates = sequence.routes.flatMap { route ->
            visits(route, leg.fromId).mapNotNull { i ->
                val off = (i + 1 until route.stopIds.size).firstOrNull { alights(route.stopIds[it]) } ?: return@mapNotNull null
                val end = (off until route.stopIds.size).firstOrNull { terminus(sequence.stopNames[route.stopIds[it]]) }
                    ?: route.stopIds.lastIndex.takeIf { bus || terminus(terminusOf(route.name)) }
                    ?: return@mapNotNull null
                route.stopIds.subList(i, off + 1) to route.stopIds.subList(i, end + 1)
            }
        }.distinct().groupBy({ (ridden, _) -> followed(ridden.drop(1)) }, { (_, full) -> full })
        val best = candidates.keys.maxOrNull()
        val paths = if (best == null || (leg.path.isNotEmpty() && best == 0)) emptyList() else candidates.getValue(best).distinct()
        paths.singleOrNull()?.let { path ->
            return Resolution.Found(
                path.map { id -> RouteStop(id, sequence.stopNames[id].orEmpty(), Connections.of(sequence.stopLines[id].orEmpty(), leg.lineId)) },
            )
        }
        // The terminus alone must still pass where the leg gets off, or the list isn't the leg's.
        val byTerminus = resolve(sequence, leg.fromId, leg.headings.firstOrNull() ?: leg.toName, null, leg.lineId, bus)
        if (byTerminus is Resolution.Found && byTerminus.stops.drop(1).none { alights(it.id) }) return Resolution.NoMatch
        return byTerminus
    }

    /**
     * Whether a train or bus at [stopId] bound for [destination] ends there: TfL's [destinationId]
     * for its terminus is [stopId] or another stop in its stop area (a bus stand's other pole), or,
     * only where TfL gave no id, [destination] is [stopId]'s name. The id is authoritative when given,
     * as two places can share a name ([Terminating] reads it the same way).
     */
    fun endsAt(sequence: LineSequence, stopId: String, destination: String, destinationId: String): Boolean {
        if (destinationId.isNotBlank()) {
            if (destinationId == stopId) return true
            val area = sequence.stopAreas[stopId].orEmpty()
            return area.isNotBlank() && (destinationId == area || sequence.stopAreas[destinationId] == area)
        }
        val name = sequence.stopNames[stopId].orEmpty()
        return name.isNotBlank() && sameStopName(destination, name)
    }

    /** From each visit to [stopId] on [route] (bar its last stop) through the route's end. */
    private fun toEnd(route: LineRoute, stopId: String): List<Pair<LineRoute, List<String>>> =
        visits(route, stopId).filter { it < route.stopIds.lastIndex }
            .map { i -> route to route.stopIds.subList(i, route.stopIds.size) }

    private fun visits(route: LineRoute, stopId: String): List<Int> =
        route.stopIds.indices.filter { route.stopIds[it] == stopId }

    /** The far end of a route name ("Morden ↔ Edgware via Bank" → "Edgware"), cleaned. */
    internal fun terminusOf(routeName: String): String {
        val end = routeName.replace("&harr;", "↔").substringAfterLast("↔")
        return cleanStopName(end.substringBefore(" via ").trim())
    }
}

/**
 * Where [RouteStopsRepository] keeps what it fetched between processes: each line+direction's
 * sequence (keyed `"$lineId/$direction"`) and each stop area's poles, with when each was fetched.
 * Blocking; the repository calls it off the main thread. [load] never throws — an unreadable store
 * loads as empty.
 */
interface RouteStopsStore {
    fun load(): Contents
    fun save(contents: Contents)

    data class Timed<T>(val at: Instant, val value: T)

    data class Contents(
        val sequences: Map<String, Timed<LineSequence>> = emptyMap(),
        val poles: Map<String, Timed<List<StopLocation>>> = emptyMap(),
    )

    companion object {
        /** Keeps nothing: the repository holds its entries in memory only, as in a test. */
        val NONE: RouteStopsStore = object : RouteStopsStore {
            override fun load() = Contents()
            override fun save(contents: Contents) = Unit
        }
    }
}

/**
 * The route detail's stop lists and stop areas' poles, fetched on demand and kept for up to
 * [maxAge] (a day: a line's route and a stop area's poles barely change), in memory and through
 * [store] so a reopen after the process was killed still has them. [cached] is a constant-time peek
 * at a line's routes already merged; [load] reads [store] once (see [warm]) and fetches on a miss or
 * an expired entry. One or two requests per line+direction per day.
 *
 * Main-safe: every suspend function hops to [compute] first, as merging a line's routes and placing
 * the station index walk them whole (a National Rail line's run to thousands of stops); [cached]
 * only looks up what that work left.
 */
class RouteStopsRepository(
    private val source: RouteSequenceSource,
    private val warn: (String) -> Unit = {},
    // A stop area's poles, for a bus journey's origin (null: none looked up, as in a test).
    private val areas: StopAreaSource? = source as? StopAreaSource,
    private val store: RouteStopsStore = RouteStopsStore.NONE,
    private val clock: () -> Instant = Instant::now,
    private val maxAge: Duration = MAX_AGE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // The bundled station index (blocking, read once in [warm]): lets a sequence place a station
    // TfL lists departures under an id its routes don't call at ([LineSequence.withStations]).
    // Null: none, as in a test.
    private val stations: (() -> List<IndexedStation>)? = null,
    // Where the merging and placing above run ([Workers]).
    private val compute: CoroutineDispatcher = Workers.compute,
) {
    private val cache = ConcurrentHashMap<String, RouteStopsStore.Timed<LineSequence>>()
    // Each line+direction's routes merged and placed ([merge]), with the cache entries they came
    // from: what [cached] hands out, so it never merges on its caller's thread.
    private val merged = ConcurrentHashMap<String, Merged>()

    /** How many merged lines are held: a test's view of what [saveLocked] lets go of. */
    internal val mergedCount: Int get() = merged.size

    private class Merged(val keys: List<String>, val parts: List<RouteStopsStore.Timed<LineSequence>>, val sequence: LineSequence) {
        // Whether every entry this was merged from is still the one [cache] holds.
        fun heldIn(cache: Map<String, RouteStopsStore.Timed<LineSequence>>) = keys.indices.all { cache[keys[it]] === parts[it] }
    }
    // The index's stations by interchange, once read; empty when no index is wired.
    @Volatile private var stationsByHub: Map<String, List<IndexedStation>>? = if (stations == null) emptyMap() else null
    // And each such station's interchange, by the station's id.
    @Volatile private var hubByStation: Map<String, String> = emptyMap()
    private val areaCache = ConcurrentHashMap<String, RouteStopsStore.Timed<List<StopLocation>>>()
    private val storeLock = Mutex()
    // Route sequences share TfL's in-flight request pool with the live refresh, and a National Rail
    // one can take seconds: at most this many at once, so live times always find a free slot.
    private val fetchSlots = Semaphore(MAX_CONCURRENT_FETCHES)
    // Each line+direction's fetch under way, for a second caller to join ([shared]).
    private val inFlight = ConcurrentHashMap<String, Deferred<Result<LineSequence>>>()
    // Nothing to read from a store that keeps nothing, so no IO hop (a test's store is NONE).
    @Volatile private var storeRead = store === RouteStopsStore.NONE

    /**
     * Reads [store] into memory if not yet read, dropping (and deleting from it) anything older
     * than [maxAge]. [load] and [loadPoles] do this themselves; calling it early, off the render
     * path, lets [cached] answer a first frame from what an earlier process fetched.
     */
    suspend fun warm(): Unit = withContext(compute) { warmHere() }

    private suspend fun warmHere() {
        if (stationsByHub == null) {
            val index = withContext(io) { stations?.invoke().orEmpty() }
            val inHubs = index.filter { it.hubId.isNotBlank() }
            hubByStation = inHubs.associate { it.id to it.hubId }
            stationsByHub = inHubs.groupBy { it.hubId }
        }
        if (storeRead) return
        storeLock.withLock {
            if (storeRead) return
            val contents = withContext(io) { store.load() }
            val now = clock()
            contents.sequences.forEach { (key, entry) -> if (fresh(entry, now)) cache.putIfAbsent(key, entry) }
            contents.poles.forEach { (key, entry) -> if (fresh(entry, now)) areaCache.putIfAbsent(key, entry) }
            storeRead = true
            // Every line held, merged now, so [cached] answers a first frame for it.
            cache.keys.map { it.substringBefore('/') }.distinct().forEach { lineId ->
                (RouteStops.directionsFor("") + "").forEach { direction -> merge(lineId, direction) }
            }
            val expired = contents.sequences.size + contents.poles.size -
                contents.sequences.values.count { fresh(it, now) } - contents.poles.values.count { fresh(it, now) }
            if (expired > 0) saveLocked()
        }
    }

    // A negative age (the clock moved back) is never fresh: fetch again rather than trust it.
    private fun fresh(entry: RouteStopsStore.Timed<*>, now: Instant): Boolean {
        val age = Duration.between(entry.at, now)
        return !age.isNegative && age < maxAge
    }

    private fun <T> Map<String, RouteStopsStore.Timed<T>>.freshValue(key: String): T? =
        this[key]?.takeIf { fresh(it, clock()) }?.value

    /** Writes the fresh entries to [store], so an expired one leaves it too. */
    private suspend fun save() {
        if (store === RouteStopsStore.NONE) return
        storeLock.withLock { saveLocked() }
    }

    private suspend fun saveLocked() {
        val now = clock()
        cache.entries.removeIf { !fresh(it.value, now) }
        // A merge outlives no entry it came from, so an expired line's routes leave memory too.
        merged.values.removeIf { !it.heldIn(cache) }
        areaCache.entries.removeIf { !fresh(it.value, now) }
        val contents = RouteStopsStore.Contents(cache.toMap(), areaCache.toMap())
        withContext(io) { store.save(contents) }
    }

    /** The poles of stop area [areaId] if already fetched (and not expired), else null. No IO. */
    fun cachedPoles(areaId: String): List<StopLocation>? = areaCache.freshValue(areaId)

    /**
     * The interchange ("HUB…") the bundled index puts [stopId] in, or null when it's in none, or
     * the index isn't read yet ([warm]): where a stop no line's route data places (one a trip only
     * walks to or from) is keyed as the list keys it ([stopPlaceKey]).
     */
    fun hubOf(stopId: String): String? = hubByStation[stopId]

    /**
     * The poles of stop area [areaId], fetched once a day and cached (a stop area's poles barely
     * change). Empty when no area source is wired. Throws a [TflException] on failure after logging
     * it (sanitized: the area id and error class).
     */
    suspend fun loadPoles(areaId: String): List<StopLocation> = withContext(compute) {
        warmHere()
        loadPolesHere(areaId)
    }

    private suspend fun loadPolesHere(areaId: String): List<StopLocation> {
        areaCache.freshValue(areaId)?.let { return it }
        val areas = areas ?: return emptyList()
        val poles = try {
            areas.stopAreaPoles(areaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("stop area fetch failed for $areaId: ${e::class.simpleName}")
            throw e
        }
        areaCache[areaId] = RouteStopsStore.Timed(clock(), poles)
        save()
        return poles
    }

    /**
     * Logs why a fetched sequence gave no stop list for a train on [lineId] at [stopId] — the page
     * shows only "unavailable", so this line is what explains it in a bug report (SPEC principle 2).
     * With the [destination] TfL gave the train, where known: a miss is a working the line's routes
     * don't model, and which one can't be told from the ids (maintainer, 2026-10-04, reversing the
     * ids-only rule). It's the train's terminus, never where the rider gets off. Formatted and
     * logged on [compute]: its callers ask from the main thread (AGENTS.md *Main thread*).
     */
    suspend fun reportUnresolved(lineId: String, stopId: String, resolution: RouteStops.Resolution, destination: String = "") =
        withContext(compute) { logUnresolved(lineId, stopId, resolution, destination) }

    @WorkerThread
    private fun logUnresolved(lineId: String, stopId: String, resolution: RouteStops.Resolution, destination: String) {
        val reason = when (resolution) {
            is RouteStops.Resolution.Found -> return
            RouteStops.Resolution.NoDestination -> "no destination"
            RouteStops.Resolution.NotOnRoute -> "stop not on any route"
            RouteStops.Resolution.NoMatch -> "destination matches no route"
            RouteStops.Resolution.ViaMatchesNoRoute -> "via matches no route"
            RouteStops.Resolution.EndsHere -> "ends at this stop"
            is RouteStops.Resolution.Ambiguous -> "${resolution.paths} possible paths"
            RouteStops.Resolution.UnknownLine -> "line not known to TfL"
            RouteStops.Resolution.NoLine -> "no line id"
        }
        val bound = destination.trim().takeIf { it.isNotEmpty() }?.let { " (bound for $it)" }.orEmpty()
        warn("route stops unavailable for line ${lineId.ifBlank { "(none)" }} at stop $stopId: $reason$bound")
    }

    /**
     * Logs each departure a trip, To… page or journey card left out as unchecked ([misses]), as
     * [reportUnresolved] does for a followed train: those screens show only "Some routes couldn't be
     * checked", so this is what says which line, at which stop, and why (SPEC principle 2). On
     * [compute], as [reportUnresolved].
     */
    suspend fun reportMisses(misses: Collection<RouteMiss>) {
        if (misses.isEmpty()) return
        withContext(compute) { misses.forEach { logUnresolved(it.lineId, it.stopId, it.reason, it.destination) } }
    }

    /**
     * Logs that a starred journey on [lineId] can't be placed on its line's route this way round —
     * no route calls at both ends in order, or the origin comes out as more than one stop — so a
     * card reading "Couldn't check" says why (SPEC principle 2). The line only: a journey's two ends
     * together are a route the rider travels, which the log's floor keeps out (docs/PRIVACY.md).
     */
    fun reportUnplaced(lineId: String) {
        warn("journey not placed on line $lineId: no single boarding stop before the far end")
    }

    /**
     * The merged sequence if already fetched (and not expired) and merged by [load] or [warm], else
     * null — also null until [warm] has read the station index, so a first frame never resolves
     * without it. A lookup only: no IO, no merging, nothing that grows with the line.
     */
    fun cached(lineId: String, direction: String): LineSequence? {
        if (stationsByHub == null) return null
        val held = merged[mergedKey(lineId, direction)] ?: return null
        val now = clock()
        return held.sequence.takeIf { held.heldIn(cache) && held.parts.all { fresh(it, now) } }
    }

    /**
     * The sequence for [lineId] in [direction] (both directions when blank), fetched and cached.
     * Throws a [TflException] on failure after logging it (sanitized: line id and error class).
     */
    suspend fun load(lineId: String, direction: String): LineSequence = withContext(compute) {
        warmHere()
        cached(lineId, direction)?.let { return@withContext it }
        // Both directions at once: a National Rail line's sequence can take TfL several seconds to
        // start answering, so fetching them in turn doubled the wait.
        val parts = coroutineScope {
            RouteStops.directionsFor(direction).map { dir -> async { shared(lineId, dir) } }.awaitAll()
        }
        // A direction fetched while the other failed is still kept.
        if (parts.any { it.getOrNull()?.second == true }) save()
        parts.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        merge(lineId, direction) ?: parts.map { it.getOrThrow().first }.reduce(LineSequence::plus)
            .withStations(stationsByHub.orEmpty())
    }

    private fun mergedKey(lineId: String, direction: String) = "$lineId|${RouteStops.directionsFor(direction).joinToString(",")}"

    // [lineId]'s routes in [direction] merged and placed from what's held, kept for [cached]; null
    // when a direction isn't held. Runs on [compute]: it walks every route and the station index.
    private fun merge(lineId: String, direction: String): LineSequence? {
        val byHub = stationsByHub ?: return null
        val keys = RouteStops.directionsFor(direction).map { "$lineId/$it" }
        val parts = keys.map { cache[it] ?: return null }
        val sequence = parts.map { it.value }.reduce(LineSequence::plus).withStations(byHub)
        merged[mergedKey(lineId, direction)] = Merged(keys, parts, sequence)
        return sequence
    }

    /**
     * [lineId]'s route one way, held or fetched, and whether this call fetched it. A fetch already
     * under way for it (a trip's, when its page opens meanwhile) is joined rather than asked again,
     * sparing TfL's request quota; one canceled with the caller that started it is asked again.
     */
    private suspend fun CoroutineScope.shared(lineId: String, dir: String): Result<Pair<LineSequence, Boolean>> {
        val key = "$lineId/$dir"
        while (true) {
            cache.freshValue(key)?.let { return Result.success(it to false) }
            val mine = async(start = CoroutineStart.LAZY) { fetch(lineId, dir) }
            val joined = inFlight.putIfAbsent(key, mine)
            if (joined != null) {
                mine.cancel()
                val result = try {
                    joined.await()
                } catch (e: CancellationException) {
                    // Ours: stop. Its starter's: forget it and ask again.
                    currentCoroutineContext().ensureActive()
                    inFlight.remove(key, joined)
                    continue
                }
                return result.map { it to false }
            }
            try {
                return mine.await().map { it to true }
            } finally {
                inFlight.remove(key, mine)
            }
        }
    }

    // One fetch of a line's route one way, into the cache; a TfL failure is returned, not thrown, so
    // a caller joining it gets the same answer without failing the fetch's own scope.
    private suspend fun fetch(lineId: String, dir: String): Result<LineSequence> {
        val started = clock()
        return try {
            val fetched = fetchSlots.withPermit { source.routeSequence(lineId, dir) }
            cache["$lineId/$dir"] = RouteStopsStore.Timed(clock(), fetched)
            // Line, direction and time only: why a trip or card waited on its route.
            warn("route sequence fetched for line $lineId $dir in ${Duration.between(started, clock()).toMillis()} ms")
            Result.success(fetched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("route sequence fetch failed for line $lineId $dir after ${Duration.between(started, clock()).toMillis()} ms: ${e::class.simpleName}")
            Result.failure(e)
        }
    }

    companion object {
        /**
         * Route sequences fetched at once, below the app's in-flight request pool (10), so the live
         * refresh it shares that pool with is never queued behind slow route fetches.
         */
        const val MAX_CONCURRENT_FETCHES = 4

        /** How long a fetched route or stop area's poles is reused (maintainer, 2026-09-24). */
        val MAX_AGE: Duration = Duration.ofHours(24)
    }
}
