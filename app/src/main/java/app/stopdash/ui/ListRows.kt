package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.ClosedNotice
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StationLead
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripVia
import app.stopdash.domain.hasTrains
import app.stopdash.domain.isStatusOnly
import app.stopdash.domain.stopPlaceKey
import java.time.Instant
import java.util.IdentityHashMap

/**
 * What a stage of the list is worked out from: [parts] for the answer, and [listKey], the list it
 * belongs to ([MainScreen]'s `listKey`). The last answer for the same list may stand in while a new
 * one is worked out ([rememberWorked]); one for another list may not.
 */
internal class ListInputs(val listKey: Any?, val parts: Inputs) {
    override fun equals(other: Any?): Boolean = other is ListInputs && other.listKey == listKey && other.parts == parts
    override fun hashCode(): Int = (listKey?.hashCode() ?: 0) * 31 + parts.hashCode()

    companion object {
        val sameList: (ListInputs, ListInputs) -> Boolean = { held, wanted -> held.listKey == wanted.listKey }
    }
}

/**
 * The list's rows before the timetable's "?" rows and the stars ([listRowsOf]).
 *
 * The board's rows are built once, here, at [now] ([board]: every loaded stop's, a Tube train just past its
 * time kept a moment, grayed, [Countdown.onBoard]); every board-side reader takes them from here, so none
 * judges a train by another set or another instant than the list beside it.
 *
 * [all]: [board], dismissed alerts out and a bus alert wholly behind its stop unflagged: what an opened
 * route page reads. [forCards]: [all] as the journey cards read it, judging a train gone at its time
 * ([DepartureRows.withoutPastDue]). [quietCandidates]: the near-me list's possible "?" rows from the same
 * build ([DepartureRows.quietCandidatesOf]), with their mark ids ([quietIds]); null where a list adds
 * none (no distances, or stops or closure checks still out). [nearby]: the near-me list's own
 * stops, a line once from its nearest stop and closest first, with dismissed alerts still in (a
 * dismissed closure keeps its place's heading). [shared]: the notices TfL filed against more than
 * one stop of a place. [marked]: [nearby] less dismissed alerts, unflagged as [all] is.
 * [destinationClosures]: the journeys' far ends' closure notices, dismissed ones out, and
 * [destinationsUnknown] the far ends whose check failed with nothing known, from the same check.
 * [placeModes]: each place's modes less those hidden, for a header's "Hide ‹mode›" items ([placeModes]).
 *
 * [source] and [now] are the snapshot and time these rows were built from. Anything judged against
 * the rows (the journey cards) reads them too, so rows held over from an earlier snapshot are never
 * judged against a newer one (Codex, #524). [byKey] finds one of [all] by its identity, for an open
 * route page.
 */
internal class ListRows(
    val source: DeparturesUiState.Loaded,
    val now: Instant,
    val all: List<DepartureRow>,
    val nearby: List<DepartureRow>,
    val shared: Set<Pair<String, String>>,
    val marked: RowsRevision,
    val destinationClosures: List<DepartureRow>,
    val destinationsUnknown: Set<String>,
    val placeModes: Map<String, Set<String>> = emptyMap(),
    val byKey: RowsByKey = RowsByKey.NONE,
    val board: List<DepartureRow> = all,
    val forCards: List<DepartureRow> = all,
    val quietCandidates: List<DepartureRow>? = null,
    val quietIds: Set<String> = emptySet(),
    // Moves on only when [quietIds] does, worked out here against the last rows: a key composition compares in
    // constant time, so the boards are watched again only when the candidates change.
    val quietRevision: Long = 0,
    // [forCards] found by identity, for a route page opened from a journey card ([byKey] is [all]'s).
    val cardsByKey: RowsByKey = RowsByKey.NONE,
    // Each stop's row for each of its lines with no times, as the list reads it ([byLineOf]), for a favorite ride's page.
    val byLine: Map<String, DepartureRow> = emptyMap(),
    // The journeys' far ends with a notice in force, dismissed or not: a dismissal hides a notice, it doesn't open the
    // stop, so a ride's page still holds its timetable back for one (Codex on #1036).
    val destinationsDisrupted: Set<String> = emptySet(),
    // The stops whose departures are still loading ([DeparturesUiState.Loaded.pendingStops], an opened farther card's
    // [DeparturesUiState.Loaded.openedLoadingStopIds]): an open route page at one isn't judged gone until it's in.
    val loadingStopIds: Set<String> = emptySet(),
)

/**
 * Rows found by their identity ([DepartureRow.detailKey]), the first of each as the list has them, so an open
 * route page finds its row without walking the rows in composition (AGENTS.md *Main thread*, Codex, #854).
 * Worked out with the rows, on the list's worker.
 */
internal class RowsByKey private constructor(private val byKey: Map<String, DepartureRow>) {
    operator fun get(key: String): DepartureRow? = byKey[key]

    companion object {
        val NONE = RowsByKey(emptyMap())

        @WorkerThread
        fun of(rows: List<DepartureRow>): RowsByKey {
            if (rows.isEmpty()) return NONE
            val byKey = HashMap<String, DepartureRow>(rows.size * 2)
            rows.forEach { byKey.putIfAbsent(it.detailKey(), it) }
            return RowsByKey(byKey)
        }
    }
}

/**
 * The list's [rows] as its stop cards draw them: grouped by place and direction ([groups],
 * [StopGrouping.groupByStop]), each group's card worked out with it ([stopCard]), found by the group
 * itself ([of], by identity, so a lookup compares no rows). Worked out with the rows on the list's
 * worker, never in composition (AGENTS.md *Main thread*).
 */
internal class ListCards private constructor(val groups: List<StopGroup>, private val byGroup: Map<StopGroup, StopCard>) {
    /** [group]'s card; [group] must be one of [groups]. */
    fun of(group: StopGroup): StopCard = checkNotNull(byGroup[group]) { "a group the list didn't work out" }

    companion object {
        val NONE = ListCards(emptyList(), emptyMap())

        /** [rows] grouped as the list draws them, warnings first unless [warningsLead] is false, under [topology]. */
        @WorkerThread
        fun of(rows: List<DepartureRow>, warningsLead: Boolean, topology: RouteTopology): ListCards {
            val groups = StopGrouping.groupByStop(rows, warningsLead = warningsLead)
            return ListCards(groups, groups.associateWithTo(IdentityHashMap()) { stopCard(it, topology) })
        }
    }
}

/** No rows, one object, so a key holding it stays the same while the list's rows are worked out. */
internal val noRows = RowsRevision(emptyList())

/**
 * The rows the list draws ([shownRowsOf]), and the closures dismissed from it that keep a heading.
 * [from] is the [ListRows] they were built from, whose snapshot and time the screen is drawn against;
 * [cards] the journey cards judged from it, drawn above the list, [cardRows] the rows they show, and
 * [journeys] the journeys they were judged for. [rowsByKey] and [cardRowsByKey] find one of [rows] and
 * [cardRows] by its identity, for an open route page.
 */
internal class ShownRows(
    val rows: List<DepartureRow>,
    // [rows] as the list's stop cards draw them.
    val listCards: ListCards,
    val dismissedClosures: List<DepartureRow>,
    val from: ListRows,
    val cards: List<JourneyCard>,
    val cardRows: List<DepartureRow>,
    val journeys: List<FavoriteJourney>,
    // How far each place [cards] board from is ([ridePlaceMeters]), for their by-stop headings.
    val ridePlaceMeters: Map<String, Double> = emptyMap(),
    // How far the stop each of [cards] boards from is, by journey key ([rideMeters]), for their headings.
    val rideMeters: Map<String, Double> = emptyMap(),
    val rowsByKey: RowsByKey = RowsByKey.NONE,
    val cardRowsByKey: RowsByKey = RowsByKey.NONE,
    // Each of [cards]' ride pages, by journey key ([rideViews]).
    val rides: Map<String, RideView> = emptyMap(),
)

/**
 * A journey card's ride page ([RidePage]), as its line's row with no train opens it ([JourneyCard.lineCard]):
 * the [card], that [row] as the page shows it, and the [ride] from its origin to its far end. [live] unless the
 * card's check is done and complete with no train to show: only then does the timetable speak for the ride.
 */
internal class RideView(
    val card: JourneyCard,
    val row: DepartureRow,
    val ride: TripLeg,
    val live: Boolean,
    // Its far end has a notice in force, dismissed or not ([ListRows.destinationsDisrupted]).
    val destinationDisrupted: Boolean = false,
    // Its origin's fetch names its line, so [row] is the list's own, its line checked there; false for a stand-in.
    val lineListed: Boolean = true,
    // The days its line's planned work starts, dismissed or not: dismissing hides work, it doesn't call it off.
    val plannedFrom: List<java.time.LocalDate> = emptyList(),
) {
    /**
     * Its card's closure notices, each by one of its rows and the title its card gives it ([JourneyCard.closures]):
     * its boarding stops' and its far end's, which its page shows as the card does (Codex on #1036). Paired here, as
     * the view is made, off the main thread.
     */
    val closures: List<Pair<DepartureRow, String>> = card.closures.map { it.first() }.zip(card.closureTitles)
}

/**
 * [cards]' ride pages by journey key ([RideView]), each row the list's own for the ride's line at its origin with
 * no times ([ListRows.byLine]): its age, its line-wide alert as of now less what the rider dismissed, and its
 * planned work are the list's, so the page reads the line exactly as the list does. With the origin's notice in
 * force at [from]'s time, as the line's rows don't carry it. A ride's planned-work days the same as in [last]'s are
 * [last]'s, the same list, so a page keyed on them compares them by identity on the main thread (Codex on #1048). On
 * the worker.
 */
@WorkerThread
internal fun rideViews(cards: List<JourneyCard>, from: ListRows, last: Map<String, RideView>? = null): Map<String, RideView> {
    val stops = from.source.stops.associateBy { it.stopId }
    val statuses = LineStatus.asOf(from.source.lineStatuses, from.now)
    return cards.associate { card ->
        val line = card.lineCard.group.rows.first()
        // A line the origin's fetch doesn't name: unchecked there, so its page never stands behind a time (Codex on #1036).
        val listed = from.byLine[lineKey(line.stopId, line.lineId)]
        val row = (listed ?: line.copy(fetchedAt = stops[line.stopId]?.fetchedAt ?: from.source.fetchedAt)).copy(
            // Its stop's notice in force, dismissed or not, so the page never calls a closed stop clean (Codex on #1036).
            stopDisruption = stops[line.stopId]?.let { DepartureRows.stopNotice(it, from.now) },
        )
        // The timetable speaks only once the card's check is done and complete with no train for the ride: while it
        // checks, or with a departure it couldn't check, a live train may yet serve it (Codex on #1036).
        val state = card.state
        // Every direct train it checked, those a nearer ride shows in its place included (Codex on #1036).
        val live = state !is JourneyCardState.Trains || state.incomplete || state.rows.any { it.hasTrains }
        val destinationDisrupted = card.destinationIds.any { it in from.destinationsDisrupted }
        val planned = statuses[line.lineId]?.planned.orEmpty().map { it.startsOn }
            .let { days -> last?.get(card.journey.key)?.plannedFrom?.takeIf { it == days } ?: days }
        card.journey.key to RideView(card, row, journeyRide(card.journey, line.stopId), live, destinationDisrupted, listed != null, planned)
    }
}

/** The key [ListRows.byLine] files a stop's row for a line under. */
internal fun lineKey(stopId: String, lineId: String): String = "$stopId|$lineId"

/**
 * Each stop's row for each of its lines with its times left out, as the list would read it, by [lineKey]: the row
 * the list gives a line with no times there ([DepartureRows.across]), a status row by its line-wide alert as of
 * [now] or a quiet row on good service, never one direction's verdict borrowed from a train the other way (Codex
 * on #1036); its alerts less those [dismissed], and one whose alert was dismissed outright, which the list leaves
 * out, marked so. For a page about a line at a stop with no train to show (a favorite ride's, [rideViews]). One
 * more pass over the stops, on the list's worker.
 */
@WorkerThread
private fun byLineOf(
    stops: List<StopArrivals>,
    now: Instant,
    lineStatuses: Map<String, LineStatus>,
    dismissed: Set<DismissedAlert>,
    alertSequences: Map<String, LineSequence?>,
): Map<String, DepartureRow> {
    val untimed = DepartureRows.across(stops.map { it.copy(departures = emptyList()) }, now, lineStatuses, quietRows = true)
        .filter { it.lineId.isNotBlank() }
    val byLine = HashMap<String, DepartureRow>()
    DepartureRows.withAlertsBehind(DepartureRows.withoutDismissed(untimed, dismissed), alertSequences)
        .forEach { byLine.putIfAbsent(lineKey(it.stopId, it.lineId), it) }
    untimed.forEach { row ->
        if (row.isStatusOnly) {
            byLine.putIfAbsent(lineKey(row.stopId, row.lineId), row.copy(status = null, statusDismissed = true, plannedAlerts = emptyList()))
        }
    }
    return byLine
}

/**
 * How far each place the journey [cards] board from is, as a near-me heading says it: the place's
 * nearest stop among its near-me groups ([nearMe]) and the cards' own, by place key. Empty off the
 * near-me list (no distances, D1). Walks the rows, so on the worker with them (AGENTS.md *Main
 * thread*); each by-stop heading then only looks its place up.
 */
@WorkerThread
internal fun ridePlaceMeters(cards: List<JourneyCard>, nearMe: List<StopGroup>, stopDistanceMeters: Map<String, Double>): Map<String, Double> {
    if (stopDistanceMeters.isEmpty()) return emptyMap()
    val rideGroups = cards.flatMap { it.state.drawn?.groups.orEmpty() }
    if (rideGroups.isEmpty()) return emptyMap()
    val places = rideGroups.mapTo(HashSet()) { it.placeKey }
    return (rideGroups + nearMe.filter { it.placeKey in places })
        .groupBy { it.placeKey }
        .mapNotNull { (place, groups) ->
            groups.asSequence().flatMap { it.rows.asSequence() }.mapNotNull { stopDistanceMeters[it.stopId] }.minOrNull()?.let { place to it }
        }
        .toMap()
}

/**
 * How far the stop each journey card boards from is, by journey key, for the ride's heading (SPEC
 * *Journeys*): its origin, as the near-me list measured it, once its route has placed it — the end a
 * far ride's heading measures to as well. Not the nearer of it and the poles beside it: those are found
 * after the heading is up, so a nearer one would change the number while the rider reads it (Codex on
 * #891). None before the origin is placed, or when it wasn't measured (off the near-me list, D1, or a
 * far ride, whose heading says how far its nearer end is instead). On the worker with the cards.
 */
@WorkerThread
internal fun rideMeters(cards: List<JourneyCard>, stopDistanceMeters: Map<String, Double>): Map<String, Double> {
    if (stopDistanceMeters.isEmpty()) return emptyMap()
    return cards.mapNotNull { card ->
        card.boardingIds.firstOrNull()?.let { stopDistanceMeters[it] }?.let { card.journey.key to it }
    }.toMap()
}

/** A platform or station view ([platformViewOf]): what the saved view keys on, and its inputs. */
internal class PlatformInputs(val stopIds: String, val splitKey: String, val station: Boolean, val parts: Inputs) {
    override fun equals(other: Any?): Boolean =
        other is PlatformInputs && other.stopIds == stopIds && other.splitKey == splitKey && other.station == station && other.parts == parts

    override fun hashCode(): Int = ((stopIds.hashCode() * 31 + splitKey.hashCode()) * 31 + station.hashCode()) * 31 + parts.hashCode()

    companion object {
        // The same view's last rows stand in while its new ones are worked out; another view's don't.
        val sameView: (PlatformInputs, PlatformInputs) -> Boolean = { held, wanted ->
            held.stopIds == wanted.stopIds && held.splitKey == wanted.splitKey && held.station == wanted.station
        }
    }
}

/**
 * A platform or station view's rows, and its title; a null title means its group is gone. [source]
 * and [now] are the snapshot and time the rows were built from, which the view is drawn against.
 */
internal class PlatformView(
    val rows: List<DepartureRow>,
    val title: String?,
    val source: DeparturesUiState.Loaded,
    val now: Instant,
    // [rows] as the view's stop cards draw them (one place, so warnings lead).
    val listCards: ListCards = ListCards.NONE,
    // One of [rows] by its identity, for an open route page.
    val byKey: RowsByKey = RowsByKey.NONE,
    // The whole station a platform view's "Whole station" button opens; null in a station view, or where
    // the station holds nothing this view doesn't.
    val station: PlatformStation? = null,
    // The stop its "Show on map" button shows, and the name to label it with: the nearest of the view's
    // stops the near-me list measured, as a heading's distance named it; null where none was (D1).
    val mapStop: Pair<String, String>? = null,
    // The stop its "Via" button plans a trip through ([viaOf]); null where the view has no one stop to.
    val via: TripVia? = null,
)

/**
 * The stop a trip via a platform or station view goes through, named [name] (its "Via" button, SPEC *Going via a
 * stop*), as the Via… search would give it: a station's interchange where it has one, so Finsbury Park's
 * Underground platform goes via all of Finsbury Park, changing there between any lines; else the one station or
 * stop the view shows. A [bus] view goes via its pole, as a rider picks a bus stop, never the interchange, which
 * is asked via its stations. Null where the view shows more than one stop with no interchange over them (a bus
 * place's poles): the Planner goes via one stop, and no one of them is the view's.
 */
internal fun viaOf(stops: List<StopArrivals>, bus: Boolean, name: String): TripVia? {
    if (!bus) stops.mapTo(HashSet()) { it.interchangeId }.singleOrNull()?.takeIf { it.isNotBlank() }?.let { return TripVia(it, name) }
    return stops.mapTo(HashSet()) { it.stopId }.singleOrNull()?.let { TripVia(it, name) }
}

/** The whole station a platform belongs to: its cluster keys, as a station view saves them, its bare
 *  name, and whether it is a bus place, whose button says "All stops". */
internal class PlatformStation(val ids: String, val title: String, val bus: Boolean)

/** [ListRows] for [stops], worked out on the list's worker, never in composition. */
internal fun listRowsOf(
    source: DeparturesUiState.Loaded,
    now: Instant,
    stopDistanceMeters: Map<String, Double>,
    dismissed: Set<DismissedAlert>,
    hiddenModes: Set<String>,
    alertSequences: Map<String, LineSequence?>,
    journeyDestinationStops: List<StopArrivals>,
    journeyDestinationsUnknown: Set<String> = emptySet(),
    // A station opened from one of its interchange's names ([StationLead]).
    stationLead: StationLead? = null,
    // The list's rows worked out last, whose [ListRows.quietRevision] this one keeps while its candidates are
    // the same.
    last: ListRows? = null,
): ListRows {
    val stops = source.stops
    val lineStatuses = source.lineStatuses
    // Every stop's rows, once, with the quiet ones a near-me list may add: a Tube train just past its time
    // kept a moment, grayed ([Countdown.onBoard]). Each stop's rows are its own, so a reader of some stops
    // takes theirs from these.
    val built = DepartureRows.across(stops, now, lineStatuses, quietRows = true, grace = true)
    // A train headed into a stretch TfL says has no service grayed, wherever its row is shown.
    val board = DepartureRows.withSuspendedTrains(built.filterNot { it.quiet }, alertSequences)
    val all = DepartureRows.withAlertsBehind(DepartureRows.withoutDismissed(board, dismissed), alertSequences)
    // A near-me list shows its nearby stops only: a journey's farther origin, fetched for its card,
    // isn't one of them (SPEC *Journeys*).
    val shownRows = if (stopDistanceMeters.isEmpty()) board else board.filter { it.stopId in stopDistanceMeters }
    val across = HiddenModes.rows(shownRows, hiddenModes)
    // Not while nearby stops are still loading: one of them may have trains for a line that would
    // otherwise read "?" at a stop already in. Nor while a stop's closure check is out: a closed
    // station's timetable trains aren't due, so "?" waits until it's known.
    val quietCandidates =
        if (stopDistanceMeters.isEmpty() || source.pendingStops.isNotEmpty() || source.closurePending.isNotEmpty()) {
            null
        } else {
            DepartureRows.quietCandidatesOf(
                built.filter { it.stopId in stopDistanceMeters }, source.determinedLineIds, stopDistanceMeters, hiddenModes,
                dismissed, source.stopsDisruptionUnknown, stationLead,
            )
        }
    // A "near me now" list (distances present) shows a line once, from its nearest stop, then orders
    // closest-stop-first (soonest breaks a same-stop tie). A location-free list keeps across's
    // soonest-first order (D1).
    val nearby =
        if (stopDistanceMeters.isEmpty()) {
            // No line dedupe without distances (a station's page shows every stop), but a notice TfL
            // reports against each member of a hub is still one card per place.
            // A station name opened with no stop TfL placed still leads with that name's own.
            DepartureRows.locationFree(across, stationLead)
        } else {
            DepartureRows.byStopDistance(DepartureRows.nearbyDeduped(across, stopDistanceMeters, dismissed, stationLead), stopDistanceMeters, stationLead)
        }
    // The notices TfL filed against more than one stop of a place, seen before the fold keeps one
    // copy: such a notice is about the place, so it heads the place's own group, even when the copy
    // kept is a lettered pole's (SPEC *Disruptions*).
    val shared = across.filter { it.stopDisruption != null }
        .groupBy { stopPlaceKey(it) to it.stopDisruption.orEmpty() }
        .filterValues { rows -> rows.mapTo(HashSet()) { it.stopId }.size > 1 }
        .keys
    // Hide the service alerts the user has dismissed (until their content changes), and unflag a bus
    // alert wholly behind its stop.
    val marked = RowsRevision(DepartureRows.withAlertsBehind(DepartureRows.withoutDismissed(nearby, dismissed), alertSequences), now)
    val destinationNotices = DepartureRows.across(journeyDestinationStops, now)
    val destinationClosures = DepartureRows.withoutDismissed(destinationNotices, dismissed)
        .filter { it.stopDisruption != null }
    val modes = placeModes(stops).mapValues { (_, modes) -> modes.filterNotTo(LinkedHashSet()) { HiddenModes.isHidden(it, hiddenModes) } }
    // A disrupted line whose only train is just past its time keeps the status row a card shows without it.
    val quietIds = quietCandidates.orEmpty().mapTo(HashSet(), ::quietId)
    val forCards = DepartureRows.withAlertsBehind(
        DepartureRows.withoutDismissed(DepartureRows.withoutPastDue(board, stops, now, lineStatuses), dismissed), alertSequences,
    )
    return ListRows(
        source, now, all, nearby, shared, marked, destinationClosures, journeyDestinationsUnknown, modes, RowsByKey.of(all),
        board = board,
        forCards = forCards,
        quietCandidates = quietCandidates,
        quietIds = quietIds,
        quietRevision = when {
            last == null -> 1L
            last.quietIds == quietIds -> last.quietRevision
            else -> last.quietRevision + 1
        },
        cardsByKey = RowsByKey.of(forCards),
        byLine = byLineOf(stops, now, lineStatuses, dismissed, alertSequences),
        destinationsDisrupted = destinationNotices.filter { it.stopDisruption != null }.mapTo(HashSet()) { it.stopId },
        loadingStopIds = source.pendingStops.mapTo(HashSet()) { it.id } + source.openedLoadingStopIds,
    )
}

/**
 * The rows the list draws: [nearbyRows] without those a journey card above already shows in full,
 * then with the user's starred services lifted to the top (SPEC D8). Warnings still lead on the
 * location-free watched list; on the near-me list (distances present) an alert is not hoisted, so a
 * nearer stop is never pushed below a farther one for carrying one. And the near-me closures the user
 * dismissed: a closed place with nothing else to show keeps its heading and "Closed" chip in place
 * (SPEC *Disruptions*).
 */
internal fun shownRowsOf(
    from: ListRows,
    nearbyRows: List<DepartureRow>,
    journeyRowsShown: List<DepartureRow>,
    starred: Set<StarredRow>,
    stopDistanceMeters: Map<String, Double>,
    cards: List<JourneyCard> = emptyList(),
    journeys: List<FavoriteJourney> = emptyList(),
    topology: RouteTopology = RouteTopology.EMPTY,
    // The last ones built, whose ride pages' planned-work days are kept where unchanged ([rideViews]).
    last: ShownRows? = null,
): ShownRows {
    val rows = DepartureRows.pinStarred(
        DepartureRows.withoutShownAbove(nearbyRows, journeyRowsShown, stopDistanceMeters, topology),
        starred,
        warningsLead = stopDistanceMeters.isEmpty(),
    )
    val dismissedClosures =
        if (stopDistanceMeters.isEmpty()) {
            emptyList()
        } else {
            val shown = nearbyRows.toHashSet()
            from.nearby.filter { it !in shown && it.stopDisruption?.let(ClosedNotice::saysClosed) == true }
        }
    // On the near-me list (distances present) a place is ordered by distance, not lifted for carrying a
    // line-status alert; the watched list keeps warnings leading (D1, SPEC *Disruptions*).
    val listCards = ListCards.of(rows, warningsLead = stopDistanceMeters.isEmpty(), topology)
    return ShownRows(
        rows, listCards, dismissedClosures, from, cards, journeyRowsShown, journeys,
        ridePlaceMeters(cards, listCards.groups, stopDistanceMeters),
        rideMeters(cards, stopDistanceMeters),
        RowsByKey.of(rows),
        // A card's row opens its route page with the card's own trains, the ones it counts on: no just-due
        // train, which the list's page keeps (maintainer, 2026-10-20).
        RowsByKey.of(journeyRowsShown),
        rideViews(cards, from, last?.rides),
    )
}

/** [stops]' board rows: from [board], built already, else built at [now] ([DepartureRows.across] with `grace`). */
private fun platformRowsOf(
    stops: List<StopArrivals>,
    board: List<DepartureRow>?,
    now: Instant,
    lineStatuses: Map<String, LineStatus>,
    alertSequences: Map<String, LineSequence?>,
): List<DepartureRow> {
    if (board == null) return DepartureRows.withSuspendedTrains(DepartureRows.across(stops, now, lineStatuses, grace = true), alertSequences)
    val ids = stops.mapTo(HashSet()) { it.stopId }
    return board.filter { it.stopId in ids }
}

/**
 * A platform or station view's rows ([MainScreen]'s drill-down, SPEC D8), built from its own stops
 * WITHOUT the near-me fold: the fold keeps a line only at its nearest stop, which would drop services
 * from a farther platform — the drill-down shows all of them. The stop ids alone aren't the platform:
 * a station's platforms all come from one TfL stop, so the rows are regrouped and only the tapped
 * group is kept, with any closure alert for its stops (Codex). It is matched on [StopGroup.splitKey]
 * — the platform, pole letter, bearing or compass — not [StopGroup.key], whose place part switches to
 * a per-stop key while the stop carries a line-status row (Codex). The saved stop ids already pin the
 * place. Dismissals and stars still apply, as on the full list. The title is resolved from the
 * matched group each time, since a letterless bus pole's qualifier (its shared terminus) can change
 * with the departures (Codex).
 */
internal fun platformViewOf(
    source: DeparturesUiState.Loaded,
    now: Instant,
    ids: Set<String>,
    splitKey: String,
    station: Boolean,
    stationTitle: String,
    starred: Set<StarredRow>,
    dismissed: Set<DismissedAlert>,
    rows: List<DepartureRow>,
    hiddenModes: Set<String>,
    alertSequences: Map<String, LineSequence?>,
    topology: RouteTopology = RouteTopology.EMPTY,
    // The near-me list's distances, for the stop "Show on map" shows; empty off it (D1).
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    // The drawn list's own rows of every stop ([ListRows.board]), built at [now] from [source]: the view's are
    // taken from these, so it shows the same trains as the list it opened from. Null builds them here.
    board: List<DepartureRow>? = null,
): PlatformView {
    val stops = source.stops
    val lineStatuses = source.lineStatuses
    // A station view saved its clusters, not stop ids, so each snapshot re-resolves its members — a
    // pole or platform that joins or leaves the cluster on a refresh is followed (Codex).
    val platformStops =
        if (station) stops.filter { stationClusterOf(it.clusterId, it.stopId) in ids }
        else stops.filter { it.stopId in ids }
    val stopRows = DepartureRows.pinStarred(
        DepartureRows.withAlertsBehind(
            DepartureRows.withoutDismissed(HiddenModes.rows(platformRowsOf(platformStops, board, now, lineStatuses, alertSequences), hiddenModes), dismissed),
            alertSequences,
        ),
        starred,
    )
    val groups = StopGrouping.groupByStop(stopRows)
    // A header with no platform/pole to split on (a bare stop, or a directionless line-status group
    // beside a station's platforms) opens the whole stop: matching only its blank split would show
    // the warning without the stop's live departures (Codex).
    val matched = if (splitKey.isEmpty()) groups else groups.filter { it.splitKey == splitKey }
    // Plus the stops' directionless line-status rows ([StopGrouping.unplacedStatusRows]): which
    // platform a suspended line would run from is unknown, so every platform view shows it.
    val groupRows = (matched.flatMap { it.rows } + StopGrouping.unplacedStatusRows(groups)).toHashSet()
    // A whole-station view, or a whole-stop view spanning several groups, is titled by the bare place,
    // never by whichever platform happens to come first (Codex); a single group opened from its own
    // header keeps its full header text.
    val title = matched.firstOrNull()?.let { g ->
        // A station view keeps the name that was tapped: the cluster's members can carry different
        // cleaned names, and the first matched group depends on row order (Codex).
        if (station) stationTitle
        else if (matched.size > 1) g.stopName
        else groupHeaderTitle(g.stopName, g.qualifier)
    }
    // The place's closure cards are the full list's own — already folded and dismissal-filtered — so
    // a card dismissed on either screen carries one identity and stays hidden on both (Codex).
    val places = platformStops.mapTo(HashSet()) { stopPlaceKey(it) }
    val closures = rows.filter { it.stopDisruption != null && stopPlaceKey(it) in places }
    val viewRows = closures + stopRows.filter { it.stopDisruption == null && it in groupRows }
    // The buttons a heading's name and distance stood for (SPEC D8). The whole station, from a platform
    // only, and only where it holds more: another platform of the same stop, or another stop of its
    // cluster. Its keys are the platform's clusters, as a tap on its name saved them.
    val stationOf = if (station) {
        null
    } else {
        matched.firstOrNull()?.let { first ->
            val clusters = matched.flatMap { it.rows }.mapTo(LinkedHashSet()) { stationClusterOf(it.clusterId, it.stopId) }
            val more = groups.any { it !in matched && it.rows.any { row -> !row.isStatusOnly } } ||
                stops.any { it.stopId !in ids && stationClusterOf(it.clusterId, it.stopId) in clusters }
            val modes = matched.flatMap { it.rows }.mapNotNullTo(HashSet()) { it.mode.ifBlank { null } }
            if (more) PlatformStation(clusters.joinToString(","), first.stopName, bus = modes == setOf("bus")) else null
        }
    }
    // The nearest measured stop of those it shows, as the heading's distance named its nearest.
    val mapStop = (if (station) platformStops.map { it.stopId } else matched.flatMap { it.rows }.map { it.stopId })
        .filter { it in stopDistanceMeters }
        .minByOrNull { stopDistanceMeters.getValue(it) }
        ?.let { it to (if (station) stationTitle else matched.first().stopName) }
    // Via: every stop the view stands for, not only those with departures now — an All stops view is several poles
    // even while one of them shows nothing (Codex) — named as its title names the place (a pole by its letter).
    val via = matched.firstOrNull()?.let { first ->
        val modes = matched.flatMap { it.rows }.mapNotNullTo(HashSet()) { it.mode.ifBlank { null } }
        val bus = modes == setOf("bus")
        val name = if (station) stationTitle else if (bus && matched.size == 1) groupHeaderTitle(first.stopName, first.qualifier) else first.stopName
        viaOf(platformStops, bus, name)
    }
    return PlatformView(
        viewRows, title, source, now, ListCards.of(viewRows, warningsLead = true, topology), RowsByKey.of(viewRows),
        station = stationOf, mapStop = mapStop, via = via,
    )
}
