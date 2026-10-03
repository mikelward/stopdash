package app.stopdash.domain

/**
 * One end of a [StarredJourney]: a station the rider boards or alights at, with its published
 * position (TfL's, from the route sequence — never the rider's own fix) so the nearer end can be
 * picked. Position is null when TfL gave none; such a journey keeps its saved direction.
 */
data class JourneyEnd(
    val stopId: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    // The stop area the end's pole belongs to (TfL `stationId` from the route sequence), where
    // known: "Find a station" lists and opens the end as that area, whose page holds all its poles.
    // Blank for an end starred before it was recorded, or where TfL gave none.
    val areaId: String = "",
)

/**
 * A starred journey (SPEC *Journeys*): a segment the rider travels, both ways — "Highgate ↔ King's
 * Cross St. Pancras", or two bus stops shared by the 43 and the 134. Direct only: the card shows
 * every train or bus from one end whose route calls at the other, on any line; a journey needing a
 * change is routing, a non-goal for now. [from] → [to] is the saved orientation, starred from
 * [lineId]'s route page; the near-me list shows whichever end is nearer as the origin
 * ([Journeys.oriented]). Stored on the device like the starred rows and, like them, never logged (a
 * pair of stops can reveal home and work).
 */
data class StarredJourney(
    val from: JourneyEnd,
    val to: JourneyEnd,
    // The line it was starred from. Its route places the segment's stops in each direction (a bus
    // stop's way-back pole is across the road), and it is declared on the origin's fetch so its
    // status is checked even when no trains are predicted (a suspension).
    val lineId: String,
    val lineName: String = "",
    val mode: String = "",
) {
    /**
     * Direction- and line-free identity: the same segment starred from either end, or from another
     * line's page, is one journey.
     */
    val key: String get() = listOf(from.stopId, to.stopId).sorted().joinToString("|")

    fun reversed(): StarredJourney = copy(from = to, to = from)

    /** The line as a stop declares it, for fetching the origin. */
    val line: LineRef get() = LineRef(lineId, lineName, mode)

    val bus: Boolean get() = mode.equals("bus", ignoreCase = true)
}

/**
 * Where a journey (already [Journeys.oriented]) boards and alights in the direction it's shown: the
 * [originId] stop to fetch, and the [destinationIds] a train or bus must call at afterwards. For a
 * bus the way back uses other poles, so these can differ from the saved ends' ids.
 */
data class JourneySegment(val originId: String, val destinationIds: Set<String>)

/**
 * The poles beside a journey's origin that board a line reaching its far end ([poles]), the lines
 * whose route must load before the rest can be judged ([pendingLines]), and those whose route
 * failed to load ([failedLines]) — a pole of theirs is undecided, not ruled out.
 */
data class SiblingPoles(
    val poles: List<StopLocation>,
    val pendingLines: Set<String>,
    val failedLines: Set<String> = emptySet(),
) {
    /** Whether every neighboring pole has been judged, so one not in [poles] truly doesn't qualify. */
    val settled: Boolean get() = pendingLines.isEmpty() && failedLines.isEmpty()
}

/**
 * The trains or buses a journey card can show ([rows]), plus why an empty list may not mean "none":
 * a line's route still loading ([pending]), or a departure whose path couldn't be resolved or whose
 * route failed to load ([unresolved]) — either could call at the far end (SPEC principle 1).
 */
data class JourneyTrains(
    val rows: List<DepartureRow>,
    val pending: Boolean,
    val unresolved: Boolean,
    // Some line's route failed to load (a cause of [unresolved] a retry can fix).
    val routeFailed: Boolean = false,
    // The far-end stops the kept departures actually call at (a line may reach another pole there),
    // whose closure the card checks.
    val reachedIds: Set<String> = emptySet(),
    // Trains that don't reach the far end but share its route as far as a stop where one that does
    // can be caught ([JourneyChange]); [Journeys.changesWithoutDirect] says when to show them.
    val changes: List<JourneyChange> = emptyList(),
    // The departures left out as unchecked (a cause of [unresolved]), by line, stop and reason, for
    // the debug log.
    val misses: Set<RouteMiss> = emptySet(),
)

/**
 * Departures ([row]) that don't call at a journey's far end but run with its route as far as [stopId]
 * ([stopName]), the last stop they share, where the rider changes for a train that does — a Northern
 * line train to Edgware for High Barnet, changing at Camden Town, while the High Barnet branch isn't
 * served from here. Only where to change: the connecting train's time isn't known.
 */
data class JourneyChange(val stopId: String, val stopName: String, val row: DepartureRow)

object Journeys {
    /** How far a way-back stop may be from the end it stands in for, when nothing else matches. */
    const val WAY_BACK_RADIUS_METERS = 400.0

    /**
     * How far apart two stops whose names start the same may be and still count as one place — a
     * station's bus stops that TfL files under different stop areas and names ("Hill Station",
     * "Hill Station / High Road"), about 100 m apart.
     */
    const val SAME_PLACE_RADIUS_METERS = 150.0

    /** Flip [journey] in or out of [starred], matched by [StarredJourney.key]. */
    fun toggle(starred: List<StarredJourney>, journey: StarredJourney): List<StarredJourney> =
        if (starred.any { it.key == journey.key }) starred.filterNot { it.key == journey.key } else starred + journey

    /**
     * [journey] with the end nearer ([latitude], [longitude]) as its origin, so the list shows the
     * trains the rider can catch from where they are. Without a position (a location-free list) or
     * an end's coordinates, the saved orientation stands.
     */
    fun oriented(journey: StarredJourney, latitude: Double?, longitude: Double?): StarredJourney {
        if (latitude == null || longitude == null) return journey
        val fromMeters = distanceTo(journey.from, latitude, longitude) ?: return journey
        val toMeters = distanceTo(journey.to, latitude, longitude) ?: return journey
        return if (toMeters < fromMeters) journey.reversed() else journey
    }

    /** Within this of either end, a starred journey is near enough to show in full: about a mile. */
    const val NEAR_METERS: Double = 1609.0

    /**
     * How far [journey] is from ([latitude], [longitude]) — the distance to its nearer end — when
     * that is more than [nearMeters], else null (near). A far journey waits behind the Faraway
     * favorites button and isn't fetched until that's tapped (maintainer, 2026-09-24). Without a position or either end's
     * coordinates it can't be judged, so it counts as near and shows in full rather than hide live
     * trains on a guess (SPEC principle 1).
     */
    fun farMeters(
        journey: StarredJourney,
        latitude: Double?,
        longitude: Double?,
        nearMeters: Double = NEAR_METERS,
    ): Double? {
        if (latitude == null || longitude == null) return null
        val fromMeters = distanceTo(journey.from, latitude, longitude) ?: return null
        val toMeters = distanceTo(journey.to, latitude, longitude) ?: return null
        return minOf(fromMeters, toMeters).takeIf { it > nearMeters }
    }

    /**
     * The far journeys among [journeys], keyed to their [farMeters] distance. Only a confirmed fix
     * holds a journey back: an approximate (last-known) or unrefreshed one may be where the rider
     * was, not is, so on [fixConfirmed] false none is far and every journey shows in full.
     */
    fun farJourneys(
        journeys: List<StarredJourney>,
        latitude: Double?,
        longitude: Double?,
        fixConfirmed: Boolean,
    ): Map<String, Double> {
        if (!fixConfirmed) return emptyMap()
        return journeys.mapNotNull { j -> farMeters(j, latitude, longitude)?.let { j.key to it } }.toMap()
    }

    /**
     * The stops to stop fetching for the [held] journeys, given each journey's fetched stops
     * ([stopIdsByJourney], key → its origin and neighboring poles): theirs, less any another
     * journey still needs.
     */
    fun heldBackStopIds(stopIdsByJourney: Map<String, Set<String>>, held: Set<String>): Set<String> {
        if (held.isEmpty()) return emptySet()
        val kept = stopIdsByJourney.filterKeys { it !in held }.values.flatten().toSet()
        return stopIdsByJourney.filterKeys { it in held }.values.flatten().toSet() - kept
    }

    /**
     * The journey stops a same-set relocate to ([latitude], [longitude]) should stop fetching at
     * once: those of the journeys that fix puts over a mile away ([farJourneys]), unless the rider
     * has revealed them ([revealed]). Nothing on a fix that isn't [fixConfirmed] — a retained or
     * last-known one may be where the rider was, and the screen, which also holds nothing back on
     * one, wouldn't re-report a stop dropped on it. Nor the [openJourneyKey] journey's, which the
     * screen keeps however far while its own view is open.
     */
    fun stopIdsToHoldBack(
        journeys: List<StarredJourney>,
        latitude: Double?,
        longitude: Double?,
        fixConfirmed: Boolean,
        revealed: Boolean,
        stopIdsByJourney: Map<String, Set<String>>,
        openJourneyKey: String? = null,
    ): Set<String> {
        if (revealed) return emptySet()
        val held = farJourneys(journeys, latitude, longitude, fixConfirmed).keys - setOfNotNull(openJourneyKey)
        return heldBackStopIds(stopIdsByJourney, held)
    }

    /**
     * Whether a same-set relocate to ([latitude], [longitude]) releases any of the [heldNow] journeys
     * (held back and not on screen), so the screen will add its stops: one now within a mile, or
     * any on a fix that isn't [fixConfirmed], which holds nothing back.
     */
    fun releasesHeldJourney(
        journeys: List<StarredJourney>,
        latitude: Double?,
        longitude: Double?,
        fixConfirmed: Boolean,
        heldNow: Set<String>,
    ): Boolean {
        if (heldNow.isEmpty()) return false
        val farAfter = farJourneys(journeys, latitude, longitude, fixConfirmed).keys
        return journeys.any { it.key in heldNow && it.key !in farAfter }
    }

    /**
     * Whether a same-set relocate to ([latitude], [longitude]) turns round any of the [shown] journeys
     * (those whose stops the screen reported, facing the way ([shownLatitude], [shownLongitude]) turned
     * them): its other end is now the nearer, so its origin, and the stops to fetch, change. The new
     * origin isn't known until the screen builds the turned card, so the refresh waits for that report.
     */
    fun turnsShownJourney(
        journeys: List<StarredJourney>,
        shownLatitude: Double?,
        shownLongitude: Double?,
        latitude: Double?,
        longitude: Double?,
        shown: Set<String>,
    ): Boolean = journeys.any { j ->
        j.key in shown && oriented(j, shownLatitude, shownLongitude).from != oriented(j, latitude, longitude).from
    }

    private fun distanceTo(end: JourneyEnd, latitude: Double, longitude: Double): Double? {
        val lat = end.latitude ?: return null
        val lon = end.longitude ?: return null
        return NearestStops.distanceMeters(latitude, longitude, lat, lon)
    }

    /**
     * Where [journey] boards and alights on [sequence] (its starred line's route, both directions),
     * or null when the route can't place it unambiguously — the card then says it couldn't check
     * rather than guess a stop (SPEC principle 1).
     *
     * Each end is matched on a route by, in turn: its own stop id (a station, or a bus pole in the
     * saved direction); a stop in the same stop area; a stop of the same name; and, for a stop served
     * one way only, the route's nearest stop within [WAY_BACK_RADIUS_METERS]. The first way that
     * finds the origin before the destination on some route wins; the origin must come out as one
     * stop. The distance fallback applies only to a bus journey on its starred line's own route:
     * another line that merely passes near an end hasn't been shown to serve it.
     */
    fun segment(journey: StarredJourney, lineSequence: LineSequence, lineId: String = journey.lineId): JourneySegment? {
        val sequence = lineSequence.callingAtEnds(journey)
        for (tier in 0..maxTier(journey, lineId)) {
            val pairs = sequence.routes.flatMap { route ->
                val origins = matches(route, journey.from, sequence, tier)
                val destinations = matches(route, journey.to, sequence, tier)
                origins.flatMap { i -> destinations.filter { it > i }.map { j -> route.stopIds[i] to route.stopIds[j] } }
            }
            if (pairs.isEmpty()) continue
            val origin = pairs.map { it.first }.distinct().singleOrNull() ?: return null
            return JourneySegment(origin, pairs.mapTo(HashSet()) { it.second })
        }
        return null
    }

    /**
     * The indices on [route] that stand for [end] at this matching [tier] or looser (see [segment]):
     * each end finds its best match on its own, so a bus origin found by stop area can pair with a
     * one-way destination found by distance.
     */
    private fun matches(route: LineRoute, end: JourneyEnd, sequence: LineSequence, tier: Int): List<Int> {
        val ids = route.stopIds
        val byId = ids.indices.filter { ids[it] == end.stopId }
        if (byId.isNotEmpty() || tier == 0) return byId
        val area = sequence.stopAreas[end.stopId]?.takeIf { it.isNotBlank() }
        val byArea = if (area == null) emptyList() else ids.indices.filter { sequence.stopAreas[ids[it]] == area }
        if (byArea.isNotEmpty() || tier == 1) return byArea
        // A qualified name ("Paddington (H&C)") takes its exact stop where the line has one, else any
        // stop of its name ([isLineQualified]).
        val exactly = isLineQualified(end.name) && sequence.stopNames.values.any { exactStopName(it, end.name) }
        val byName = ids.indices.filter {
            if (exactly) exactStopName(sequence.stopNames[ids[it]], end.name) else sameStopName(sequence.stopNames[ids[it]], end.name)
        }
        if (byName.isNotEmpty() || tier == 2) return byName
        val (lat, lon) = (end.latitude?.let { la -> end.longitude?.let { la to it } })
            ?: sequence.stopPositions[end.stopId] ?: return emptyList()
        return ids.indices
            .mapNotNull { i ->
                sequence.stopPositions[ids[i]]?.let { (la, lo) -> i to NearestStops.distanceMeters(lat, lon, la, lo) }
            }
            .filter { it.second <= WAY_BACK_RADIUS_METERS }
            .minByOrNull { it.second }
            ?.let { listOf(it.first) }
            .orEmpty()
    }

    /**
     * The departures at [segment]'s origin, on any line, that call at one of its destinations —
     * "Bank-branch trains" from Highgate toward King's Cross, or both the 43 and the 134 between two
     * shared stops. Each departure's path comes from its own line's route in [sequences] (null when
     * that route failed to load; absent while loading); one it can't resolve is left out rather than
     * guessed, and flagged. A line's status-only row (a suspension, no predictions) is kept when its
     * route serves the segment, so the card shows the warning rather than a bare "no trains".
     */
    fun trains(
        segment: JourneySegment,
        rows: List<DepartureRow>,
        sequences: Map<String, LineSequence?>,
        // The journey (this way round), so each line can place the far end on its own route — another
        // route may stop at a different pole of the destination's stop area.
        journey: StarredJourney? = null,
    ): JourneyTrains {
        var pending = false
        var unresolved = false
        var routeFailed = false
        val reached = HashSet<String>()
        val changes = ArrayList<JourneyChange>()
        val atOrigin = rows.filter { it.stopId == segment.originId && it.stopDisruption == null }
        // A departure TfL gave no line id can't be checked against any route: it may well call there.
        val misses = LinkedHashSet<RouteMiss>()
        atOrigin.filter { it.lineId.isBlank() && it.upcoming.isNotEmpty() }.forEach { row ->
            unresolved = true
            misses += RouteMiss(row.lineId, row.stopId, RouteStops.Resolution.NoLine)
        }
        val kept = atOrigin
            .filter { it.lineId.isNotBlank() }
            .mapNotNull { row ->
                if (row.lineId !in sequences) {
                    pending = true
                    return@mapNotNull null
                }
                // The row's own stop id (and the journey's ends) stand in for a sibling its routes
                // call at (see callingAt), as [segment] placed them.
                val sequence = sequences[row.lineId]?.callingAt(row.stopId)
                    ?.let { if (journey == null) it else it.callingAtEnds(journey) }
                if (sequence == null) {
                    // A failed route can't say whether its departures — or its warning, on a
                    // status-only row — belong to this segment: never a silent drop.
                    if (row.upcoming.isNotEmpty() || row.status != null) {
                        unresolved = true
                        routeFailed = true
                    }
                    return@mapNotNull null
                }
                val destinations = segment.destinationIds +
                    (journey?.let { destinationsOn(it.to, segment.originId, sequence, maxTier(it, row.lineId)) }.orEmpty())
                if (row.upcoming.isEmpty()) {
                    val served = servedDestinations(sequence, segment.originId, destinations)
                    if (row.status == null || served.isEmpty()) return@mapNotNull null
                    reached += served
                    // The line's warning alone: trains with no time ([DepartureRow.untimed]) aren't
                    // checked against the journey's far end, so a card never shows them as its own.
                    return@mapNotNull row.copy(untimed = emptyList())
                }
                // The mode from any departure when TfL left it off the soonest one.
                val mode = row.mode.ifBlank { row.upcoming.firstOrNull { it.mode.isNotBlank() }?.mode.orEmpty() }
                val bus = mode.equals("bus", ignoreCase = true)
                // Rail only: a bus's path comes from its route's end as often as not, too loose to
                // send a rider to change on.
                val changing = LinkedHashMap<String, MutableList<Departure>>()
                val calling = row.upcoming.filter { departure ->
                    val bound = RouteStops.boundOf(departure.platform)
                    val resolution = RouteStops.resolve(
                        sequence, segment.originId, departure.destination, departure.branch, row.lineId, bus, bound, departure.direction,
                        departure.destinationId,
                    )
                    // Ending here, it goes nowhere: a sure "no", not a gap in the check.
                    if (resolution == RouteStops.Resolution.EndsHere) return@filter false
                    val path = (resolution as? RouteStops.Resolution.Found)?.stops
                    if (path == null) {
                        // One path can't be told (no destination yet, or two ways that match it):
                        // still an answer when every way it may take agrees on reaching the far end.
                        val ways = RouteStops.candidatePaths(
                            sequence, segment.originId, departure.destination, departure.branch, bus, bound, departure.direction,
                            departure.destinationId,
                        )
                            .map { way -> way.drop(1).filter { it in destinations } }
                        when {
                            ways.isNotEmpty() && ways.all { it.isNotEmpty() } -> {
                                ways.flatten().toCollection(reached)
                                return@filter true
                            }
                            ways.isNotEmpty() && ways.all { it.isEmpty() } -> return@filter false
                            else -> {
                                unresolved = true
                                misses += RouteMiss(row.lineId, segment.originId, resolution)
                            }
                        }
                    }
                    val hits = path?.filter { it.id in destinations }.orEmpty()
                    hits.mapTo(reached) { it.id }
                    if (hits.isEmpty() && path != null && !bus && journey?.bus != true) {
                        changeStop(sequence, segment.originId, path.map { it.id }, destinations)
                            ?.let { changing.getOrPut(it) { ArrayList() } += departure }
                    }
                    hits.isNotEmpty()
                }
                changing.forEach { (stopId, departures) ->
                    changes += JourneyChange(
                        stopId,
                        sequence.stopNames[stopId].orEmpty(),
                        row.copy(upcoming = departures, destination = departures.first().destination, untimed = row.untimedTo(departures)),
                    )
                }
                if (calling.isEmpty()) null else row.copy(upcoming = calling, destination = calling.first().destination, untimed = row.untimedTo(calling))
            }
        return JourneyTrains(kept, pending, unresolved, routeFailed, reached, changes, misses)
    }

    /**
     * Where a train following [path] (from [originId], as [RouteStops.ahead] gives it) and missing
     * [destinations] should be left for one that reaches them: the last stop it shares with a route
     * from [originId] to a destination, counting from the origin — Camden Town, where an Edgware train
     * leaves the High Barnet branch. The route that shares the most wins; null when none shares a stop
     * past the origin.
     */
    internal fun changeStop(sequence: LineSequence, originId: String, path: List<String>, destinations: Set<String>): String? {
        var shared = 0
        for (route in sequence.routes) {
            val i = route.stopIds.indexOf(originId)
            if (i < 0) continue
            val j = (i + 1 until route.stopIds.size).firstOrNull { route.stopIds[it] in destinations } ?: continue
            val leg = route.stopIds.subList(i, j + 1)
            var k = 0
            while (k < path.size && k < leg.size && path[k] == leg[k]) k++
            // Short of the far end itself, which would make it a direct train.
            if (k in 2 until leg.size && k > shared) shared = k
        }
        return if (shared >= 2) path[shared - 1] else null
    }

    /**
     * The [changes] to offer: all of them when no direct train is due in [rows] (a suspended line's
     * status row carries none), else none (maintainer, 2026-09-24). While a direct train runs, changing
     * mostly lands the rider on that same train at the fork, and the connection's time isn't known to
     * show otherwise.
     */
    fun changesWithoutDirect(rows: List<DepartureRow>, changes: List<JourneyChange>): List<JourneyChange> =
        if (directDue(rows)) emptyList() else changes

    /**
     * The interchange of [originId], the stop a journey card fetches: its looked-up [pole]'s, else
     * what its line's [sequence] knows (from TfL's route data or the bundled station index), blank
     * when neither says.
     */
    fun originHub(originId: String, sequence: LineSequence?, pole: StopLocation?): String =
        pole?.hubId?.takeIf { it.isNotBlank() } ?: sequence?.stopHubs?.get(originId).orEmpty()

    /** [this] with [journey]'s ends in place of the sibling stop ids its routes call at. */
    private fun LineSequence.callingAtEnds(journey: StarredJourney): LineSequence =
        callingAt(journey.from.stopId).callingAt(journey.to.stopId)

    /**
     * [this] row's trains with no time ([DepartureRow.untimed]) to a destination one of [kept] runs to:
     * drawn among those on a journey card, as on the stop's own. One to anywhere else isn't, since its
     * path to the journey's far end goes unchecked; it would be a line of its own there.
     */
    private fun DepartureRow.untimedTo(kept: List<Departure>): List<UntimedTrain> =
        untimed.filter { train -> kept.any { it.destination == train.train.destination } }

    /** Whether any of a journey card's direct [rows] has a train due: a status-only row has none. */
    fun directDue(rows: List<DepartureRow>): Boolean = rows.any { it.upcoming.isNotEmpty() }

    /**
     * The other poles of [originId]'s stop area ([poles], its lookup) that board a line reaching
     * [journey]'s far end — a bus leaving from stop K beside the journey's stop L — and, among each
     * such pole's lines, those whose route isn't in [sequences] yet ([SiblingPoles.pendingLines]; a
     * line at the origin itself is left to the origin's own check). A pole qualifies once one of its
     * lines' routes calls there and then at the far end, matched as [trains] matches it. Only a pole
     * of the journey's mode: another mode's stop in the area is a different journey.
     */
    fun siblingPoles(
        journey: StarredJourney,
        originId: String,
        poles: List<StopLocation>,
        sequences: Map<String, LineSequence?>,
    ): SiblingPoles {
        val mode = journey.mode
        val originLines = poles.firstOrNull { it.id == originId }?.lines.orEmpty().mapTo(HashSet()) { it.id }
        val pending = HashSet<String>()
        val failed = HashSet<String>()
        val serving = poles.filter { pole ->
            if (pole.id == originId) return@filter false
            val lines = pole.lines.filter { ofMode(it, mode) }
            if (lines.isEmpty()) return@filter false
            lines.any { line ->
                if (line.id in originLines) {
                    // Served at the origin too (the way-back pole's lines, usually): the origin's
                    // check covers it, and its route isn't loaded for nothing.
                    false
                } else if (line.id !in sequences) {
                    pending += line.id
                    false
                } else {
                    // A route that failed to load can't say either way: undecided, never a "no".
                    val sequence = sequences[line.id] ?: run {
                        failed += line.id
                        return@any false
                    }
                    destinationsOn(journey.to, pole.id, sequence, maxTier(journey, line.id)).isNotEmpty()
                }
            }
        }
        return SiblingPoles(serving, pending, failed)
    }

    /**
     * Whether a neighboring pole's [line] may serve a journey of [mode]: the same mode, or either
     * unknown (TfL leaves a line's mode off at times) — judged by its route rather than excluded.
     */
    fun ofMode(line: LineRef, mode: String): Boolean =
        mode.isBlank() || line.mode.isBlank() || line.mode.equals(mode, ignoreCase = true)

    /**
     * The loosest match [segment] may use on [lineId]'s route: by distance only for a bus journey on
     * its starred line (a pole served one way only). A station missing from a rail route is a
     * journey that can't be placed, not one a nearby station stands in for.
     */
    private fun maxTier(journey: StarredJourney, lineId: String): Int =
        if (journey.bus && lineId == journey.lineId) 3 else 2

    /** Whether some route of [sequence] calls at [originId] and then one of [destinations]. */
    /** The stops of [destinations] some route of [sequence] calls at after [originId]. */
    private fun servedDestinations(sequence: LineSequence, originId: String, destinations: Set<String>): Set<String> =
        sequence.routes.flatMapTo(HashSet()) { route ->
            val i = route.stopIds.indexOf(originId)
            if (i < 0) emptyList() else route.stopIds.drop(i + 1).filter { it in destinations }
        }

    /**
     * The stops on [sequence]'s routes after [originId] that stand for [end], matched the way
     * [segment] matches (id, stop area, name, nearest within [WAY_BACK_RADIUS_METERS] up to
     * [maxTier]), the first way that finds any — plus, on every route, any stop at the same place
     * ([samePlace]): a route variant can reach the destination's other stop even where another variant
     * reaches its own. The far end only, so another line reaching the destination's other stops
     * counts, while the origin stays the exact stop the departures are fetched for.
     */
    private fun destinationsOn(end: JourneyEnd, originId: String, sequence: LineSequence, maxTier: Int): Set<String> {
        val matched = (0..maxTier).asSequence()
            .map { tier -> after(originId, sequence) { route, i -> matches(route, end, sequence, tier).filter { it > i } } }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val position = end.latitude?.let { la -> end.longitude?.let { la to it } } ?: sequence.stopPositions[end.stopId]
        return matched + after(originId, sequence) { route, i ->
            (i + 1 until route.stopIds.size).filter { j ->
                val id = route.stopIds[j]
                samePlace(end.name, position, sequence.stopNames[id], sequence.stopPositions[id])
            }
        }
    }

    /** The stops [pick] chooses on each route of [sequence], by index, after [originId]'s index. */
    private fun after(originId: String, sequence: LineSequence, pick: (LineRoute, Int) -> List<Int>): Set<String> =
        sequence.routes.flatMapTo(HashSet()) { route ->
            val i = route.stopIds.indexOf(originId)
            if (i < 0) emptyList() else pick(route, i).map { route.stopIds[it] }
        }

    /**
     * Whether two stops are one place though TfL groups them apart: their names start the same (the
     * part before any " / ", cleaned — "Hill Station" and "Hill Station / High Road") and
     * they stand within [SAME_PLACE_RADIUS_METERS]. Both are needed: a name alone joins same-named
     * stops across town, a distance alone a road that merely passes by.
     */
    fun samePlace(
        nameA: String?,
        positionA: Pair<Double, Double>?,
        nameB: String?,
        positionB: Pair<Double, Double>?,
    ): Boolean {
        val rootA = nameA?.let(::placeRoot)?.takeIf { it.isNotEmpty() } ?: return false
        if (rootA != nameB?.let(::placeRoot)) return false
        // One name, but two stations its line qualifiers tell apart ([conflictingQualifiers]).
        if (conflictingQualifiers(nameA.substringBefore("/"), nameB.substringBefore("/"))) return false
        val (latA, lonA) = positionA ?: return false
        val (latB, lonB) = positionB ?: return false
        return NearestStops.distanceMeters(latA, lonA, latB, lonB) <= SAME_PLACE_RADIUS_METERS
    }

    private val WHITESPACE = Regex("\\s+")

    private fun placeRoot(name: String): String =
        matchStopName(name.substringBefore("/")).lowercase().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * Reads and writes the starred journeys (SPEC *Journeys*). A seam so a ViewModel depends on the
 * capability, not DataStore; mirrors [StarredRowsStore]. [journeys] emits the saved list at once
 * and on every change — null when a stored list exists that this build can't read (a newer schema),
 * which the store then preserves rather than overwrite. [toggle] runs off the main thread.
 */
interface StarredJourneysStore {
    fun journeys(): kotlinx.coroutines.flow.Flow<List<StarredJourney>?>

    suspend fun toggle(journey: StarredJourney)

    companion object {
        /** Persists nothing and reads an empty list: tests and an unwired build. */
        val NONE: StarredJourneysStore = object : StarredJourneysStore {
            override fun journeys() = kotlinx.coroutines.flow.flowOf<List<StarredJourney>?>(emptyList())
            override suspend fun toggle(journey: StarredJourney) {}
        }
    }
}
