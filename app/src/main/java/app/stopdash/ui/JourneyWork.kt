package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Connections
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.FartherBuses
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.JourneySegment
import app.stopdash.domain.Journeys
import app.stopdash.domain.LineSequence
import app.stopdash.domain.SiblingPoles
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopLocation
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.WidgetJourneys
import app.stopdash.domain.WidgetJourneysReport

// The near-me screen's journey work, done on the list's worker ([rememberWorked]), never in
// composition (AGENTS.md *Main thread: read and dispatch only*).

/**
 * The stop each of [journeys] is fetched from: its resolved origin ([segments]), or, before its route
 * is in, a station's own id (the same both ways); a bus waits, since its way-back pole isn't known yet.
 * With the lines of its mode the route lists there and, once looked up ([poles]), its pole's letter and
 * area, one [StopRef] per stop ([mergeJourneyOrigins]).
 */
@WorkerThread
internal fun journeyOriginsOf(
    journeys: List<FavoriteJourney>,
    segments: Map<String, JourneySegment?>,
    starSequences: Map<String, LineSequence?>,
    poles: Map<String, List<StopLocation>?>,
    farEnds: Map<String, Journeys.FarEnd> = emptyMap(),
): List<StopRef> =
    journeys.mapNotNull { j ->
        val id = segments[j.key]?.originId ?: j.from.stopId.takeUnless { j.bus } ?: return@mapNotNull null
        // The starred line, plus every line of its mode the route data lists at the origin (the
        // 134 beside the 43), so one with no predictions still has its route loaded and its
        // status checked — a suspended route shows its warning, not "no buses".
        // Only lines of a known, matching mode: an interchange's lines come in with a blank mode
        // when it mixes modes (King's Cross's buses beside its tube), and loading every one
        // would spend the request budget on routes that don't serve this stop.
        // A journey saved with no mode (TfL left it off) takes its line's known one.
        val mode = j.mode.ifBlank { Connections.knownMode(j.lineId).orEmpty() }
        val served = starSequences[j.lineId]?.stopLines?.get(id).orEmpty()
            .filter { it.mode.isNotBlank() && it.mode.equals(mode, ignoreCase = true) }
        // Its pole letter and area, once looked up, so the card heads it like its neighbors.
        val pole = poles[j.key]?.firstOrNull { it.id == id }
        // And any line the origin's own lookup lists that serves a stop at the far end, of whatever mode,
        // so its route and status are checked too (Codex, #691).
        val toward = farEnds[j.key]?.let { far -> pole?.lines.orEmpty().filter { it.id in far.lineIds } }.orEmpty()
        StopRef(
            id, j.from.name, lines = (listOf(j.line) + served + toward).distinctBy { it.id },
            clusterId = pole?.clusterId.orEmpty(), stopLetter = pole?.stopLetter.orEmpty(),
            bearing = pole?.bearing.orEmpty(), towards = pole?.towards.orEmpty(),
            // Its interchange, so a closure there folds and titles by the interchange (SPEC
            // *Disruptions*) when no nearby stop brought it in.
            hubId = Journeys.originHub(id, starSequences[j.lineId], pole),
        )
    }.let(::mergeJourneyOrigins)

/**
 * The lines whose routes the journey cards need: each journey's starred line ([starLines]), every line
 * the list has at its origin ([origins], from [stops]), and the lines boarding beside the origin that
 * may reach the far end ([Journeys.boardsTowards], by [farEnds]), so their routes can
 * say whether they do ([Journeys.siblingPoles]).
 */
@WorkerThread
internal fun journeyLineIdsOf(
    starLines: List<String>,
    origins: List<StopRef>,
    stops: List<StopArrivals>,
    journeys: List<FavoriteJourney>,
    segments: Map<String, JourneySegment?>,
    poles: Map<String, List<StopLocation>?>,
    farEnds: Map<String, Journeys.FarEnd> = emptyMap(),
): List<String> {
    val originIds = origins.mapTo(HashSet()) { it.id }
    val originLines = stops.filter { it.stopId in originIds }
        .flatMap { stop -> stop.departures.map { it.lineId } + stop.lines.map { it.id } } +
        origins.flatMap { origin -> origin.lines.map { it.id } }
    val siblingLines = journeys.flatMap { j ->
        val originId = segments[j.key]?.originId ?: return@flatMap emptyList()
        // Every line beside the origin that may reach the far end, the origin's own too: whether a stop
        // beside it boards one is judged by both stops' routes ([Journeys.siblingPoles]).
        poles[j.key].orEmpty().filter { it.id != originId }.flatMap { pole ->
            pole.lines.filter { Journeys.boardsTowards(it, j, farEnds[j.key]) }.map { it.id }
        }
    }
    return (starLines + originLines + siblingLines).filter { it.isNotBlank() }.distinct()
}

/**
 * The stops the journeys are fetched from, as the screen reports them: their [origins], then the stops
 * beside the origin that board a line reaching its far end ([siblings]), each with the lines that may
 * ([Journeys.boardsTowards], by [farEnds]; a stop two journeys board from, with both's lines); and each journey's boarding stops by key, with the journey view open ([viewKey]).
 * Each the same [Reported] as [last]'s where it holds the same.
 */
@WorkerThread
internal fun journeyStopsOf(
    origins: List<StopRef>,
    journeys: List<FavoriteJourney>,
    segments: Map<String, JourneySegment?>,
    siblings: Map<String, SiblingPoles>,
    viewKey: String?,
    last: JourneyStops? = null,
    farEnds: Map<String, Journeys.FarEnd> = emptyMap(),
): JourneyStops {
    val originIds = origins.mapTo(HashSet()) { it.id }
    val siblingOrigins = journeys.flatMap { j ->
        siblings[j.key]?.poles.orEmpty().map { pole ->
            pole.toStopRef().copy(lines = pole.lines.filter { Journeys.boardsTowards(it, j, farEnds[j.key]) })
        }
    }.filter { it.id !in originIds }.let(::mergeJourneyOrigins)
    val stopIds = journeys.associate { j ->
        val originId = segments[j.key]?.originId ?: j.from.stopId
        j.key to (setOf(originId) + siblings[j.key]?.poles.orEmpty().map { it.id })
    }
    return JourneyStops(Reported.of(origins + siblingOrigins, last?.fetched), Reported.of(stopIds to viewKey, last?.stopIds))
}

/**
 * What the judged journey [cards] report, or null with no cards judged yet ([shown] null).
 *
 * The widget follows the list's rule: only near journeys (not in [farJourneyMeters]) are pinned there,
 * so a far one opened in the app doesn't join it. One check per boarding stop: the origin under the
 * journey's key, a neighboring pole under its [WidgetJourneys.poleKey], each pinned from its own stop.
 * And the boarding keys of each journey whose neighboring poles are settled (none to look up, or
 * looked up and judged: [poleKeys], [poles], [siblings], [siblingsPending]), so a pole that no longer
 * qualifies loses its pin. Each the same [Reported] as [last]'s where it holds the same.
 */
@WorkerThread
internal fun cardReportsOf(
    shown: ShownRows?,
    journeys: List<FavoriteJourney>,
    farJourneyMeters: Map<String, Double>,
    poleKeys: Set<String>,
    poles: Map<String, List<StopLocation>?>,
    siblings: Map<String, SiblingPoles>,
    siblingsPending: Boolean,
    last: CardReports? = null,
): CardReports? {
    val cards = shown?.cards ?: return null
    val keys = journeys.filter { it.key !in farJourneyMeters }.mapTo(HashSet()) { it.key }
    // The direction each journey is shown in, so a flip reaches the widget even before its route
    // can place the new origin.
    val shownFrom = journeys.filter { it.key in keys }.associate { it.key to it.from.stopId }
    val checks = cards.filter { it.journey.key in keys }.flatMap { card ->
        val key = card.journey.key
        val rows = (card.state as? JourneyCardState.Trains)?.rows.orEmpty()
        card.boardingIds.mapIndexed { i, id ->
            WidgetJourneyCheck(
                if (i == 0) key else WidgetJourneys.poleKey(key, id),
                id,
                rows.filter { it.stopId == id }.flatMapTo(HashSet()) { row -> row.upcoming.map(JourneyCall::of) },
                card.checked[id].orEmpty(),
                card.journey.from.stopId,
            )
        }
    }
    val boarding = cards.filter { card ->
        val key = card.journey.key
        card.boardingIds.isNotEmpty() && (
            key !in poleKeys ||
                poles[key] != null && !siblingsPending && siblings[key]?.settled == true
            )
    }.associate { card ->
        val key = card.journey.key
        key to card.boardingIds.mapIndexedTo(HashSet()) { i, id -> if (i == 0) key else WidgetJourneys.poleKey(key, id) }
    }
    // The far ends to check for a closure.
    val destinations = cards.flatMap { card -> card.destinationIds.map { id -> StopRef(id, card.journey.to.name) } }.distinctBy { it.id }
    return CardReports(
        Reported.of(WidgetJourneysReport(keys, checks, shownFrom, boarding), last?.widget),
        Reported.of(destinations, last?.destinations),
        Reported.of(cards.flatMapTo(LinkedHashSet()) { it.misses }, last?.misses),
    )
}

/**
 * The [farther] bus cards to show, decided against the routes the screen actually shows (SPEC
 * *Finding stops → Farther stations*): every row drawn, the list's ([rows]) and the journey cards'
 * ([cardRows]), and an opened card's (an opened card itself always stays). Deciding it from the rows
 * drawn means every filter the list applies (dismissed alerts, hidden modes, the nearest-stop dedupe,
 * a journey's origin) is honored without being copied, and a route an opened card turned out to run
 * is never offered again by another. A place by a station the screen shows claims its routes first:
 * one it draws rows for, or one still drawn as a cold load's "Loading" card ([pendingStops]), so the
 * cards don't reshuffle when its rows land (Codex).
 */
@WorkerThread
internal fun fartherShownOf(
    farther: List<FartherCard>,
    rows: List<DepartureRow>,
    cardRows: List<DepartureRow>,
    pendingStops: List<StopRef>,
): List<FartherCard> {
    if (farther.isEmpty()) return farther
    val opened = farther.filter { it.load != null }.mapTo(HashSet()) { it.place.key }
    val shownBus = (rows + cardRows)
        .filter { it.mode.equals(FartherBuses.MODE, ignoreCase = true) && it.lineId.isNotBlank() }
        .mapTo(HashSet()) { it.lineId }
    val shownStops = (rows + cardRows).mapTo(HashSet()) { it.stopId }
    pendingStops.mapTo(shownStops) { it.id }
    val kept = CollapsedPlaces.withBusesPicked(farther.map { it.place }, shownBus, opened, shownStopIds = shownStops)
        .associateBy { it.key }
    return farther.mapNotNull { card -> kept[card.place.key]?.let { card.copy(place = it) } }
}

/**
 * What the screen knows of the stops around each journey's ends ([journeyAroundOf]): the stops beside
 * the origin it may board from, the far end's stops within the rider's walk, and whether a lookup is
 * still out.
 */
internal class JourneyAround(
    // The stops within [Journeys.BOARDING_RADIUS_METERS] of the origin, nearest first; null when the
    // lookup failed.
    val poles: List<StopLocation>?,
    // The far end's stops within the walk; null while unknown (no position, failed, or still out),
    // when the card matches the far end by its own stop alone.
    val farEnd: Journeys.FarEnd?,
    val loading: Boolean,
    // The far end's lookup failed: the card can't say every line reaching it was checked (Codex, #691).
    val farFailed: Boolean = false,
)

/**
 * The stops around each of [journeys]' ends, by journey key, from [endStops] (each end's lookup by stop
 * id: absent while out, null when it failed) within [walkMeters] of the far end. A journey whose origin
 * has no position has none: it boards from its origin (and a bus's stop area) alone, as before.
 */
@WorkerThread
internal fun journeyAroundOf(
    journeys: List<FavoriteJourney>,
    endStops: Map<String, List<StopLocation>?>,
    walkMeters: Int,
): Map<String, JourneyAround> =
    journeys.mapNotNull { j ->
        if (j.from.latitude == null || j.from.longitude == null) return@mapNotNull null
        val farKnown = j.to.latitude != null && j.to.longitude != null
        val loading = j.from.stopId !in endStops || farKnown && j.to.stopId !in endStops
        val poles = endStops[j.from.stopId]?.let { Journeys.stopsAround(j.from, it, Journeys.BOARDING_RADIUS_METERS) }
        val farEnd = endStops[j.to.stopId]?.let { Journeys.farEnd(j.to, it, walkMeters) }
        val farFailed = farKnown && j.to.stopId in endStops && endStops[j.to.stopId] == null
        j.key to JourneyAround(poles, farEnd, loading, farFailed)
    }.toMap()

/**
 * Each journey's boarding stops to weigh beside its origin, by key: those around it ([around]), then its
 * bus stop area's ([areaPoles], by journey key) not among them. Absent while any lookup it needs is out (the far end's
 * too, which says which lines to weigh, so the stops fetched don't change twice); null when
 * one failed, so the card says it couldn't check every stop. Keyed by [areas] for a stop area.
 */
@WorkerThread
internal fun journeyPolesOf(
    journeys: List<FavoriteJourney>,
    around: Map<String, JourneyAround>,
    areas: Map<String, String>,
    areaPoles: Map<String, List<StopLocation>?>,
): Map<String, List<StopLocation>?> =
    journeys.mapNotNull { j ->
        val near = around[j.key]
        val areaId = areas[j.key]
        if (near == null && areaId == null) return@mapNotNull null
        if (near?.loading == true || areaId != null && j.key !in areaPoles) return@mapNotNull null
        // Either end's lookup failed: null, so the card says it couldn't check every line, with a retry.
        if (near?.farFailed == true) return@mapNotNull j.key to null
        val nearPoles = near?.let { it.poles ?: return@mapNotNull j.key to null }.orEmpty()
        val inArea = if (areaId == null) emptyList() else areaPoles[j.key] ?: return@mapNotNull j.key to null
        j.key to (nearPoles + inArea).distinctBy { it.id }
    }.toMap()

/** The journeys' boarding stops beside their origins and their far ends' stops ([journeyBoardingOf]). */
internal class JourneyBoarding(
    val poles: Map<String, List<StopLocation>?>,
    val farEnds: Map<String, Journeys.FarEnd>,
    val poleKeys: Set<String>,
)

/** [journeyAroundOf] and [journeyPolesOf] together, with the keys of the journeys that weigh stops beside their origin. */
@WorkerThread
internal fun journeyBoardingOf(
    // The journeys to look around: none with no lookup wired.
    lookedAround: List<FavoriteJourney>,
    endStops: Map<String, List<StopLocation>?>,
    walkMeters: Int,
    areas: Map<String, String>,
    areaPoles: Map<String, List<StopLocation>?>,
    journeys: List<FavoriteJourney> = lookedAround,
    // The radius each end's answer was asked with, and the one the walk now needs: an answer short of it
    // is still out, never a complete check (a failure stands as one).
    radius: Map<String, Int> = emptyMap(),
    required: Int = 0,
): JourneyBoarding {
    val covered = endStops.filter { (id, stops) -> stops == null || (radius[id] ?: 0) >= required }
    val around = journeyAroundOf(lookedAround, covered, walkMeters)
    return JourneyBoarding(
        journeyPolesOf(journeys, around, areas, areaPoles),
        around.mapNotNull { (key, a) -> a.farEnd?.let { key to it } }.toMap(),
        journeys.filter { it.key in around || it.key in areas }.mapTo(HashSet()) { it.key },
    )
}
