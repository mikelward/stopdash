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
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopGrouping
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
 * [all]: every loaded stop's rows, dismissed alerts out and a bus alert wholly behind its stop
 * unflagged: what the journey cards and an opened route page read. [nearby]: the near-me list's own
 * stops, a line once from its nearest stop and closest first, with dismissed alerts still in (a
 * dismissed closure keeps its place's heading). [shared]: the notices TfL filed against more than
 * one stop of a place. [marked]: [nearby] less dismissed alerts, unflagged as [all] is.
 * [destinationClosures]: the journeys' far ends' closure notices, dismissed ones out, and
 * [destinationsUnknown] the far ends whose check failed with nothing known, from the same check.
 *
 * [source] and [now] are the snapshot and time these rows were built from. Anything judged against
 * the rows (the journey cards) reads them too, so rows held over from an earlier snapshot are never
 * judged against a newer one (Codex, #524).
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
)

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
 * [journeys] the journeys they were judged for.
 */
internal class ShownRows(
    val rows: List<DepartureRow>,
    // [rows] as the list's stop cards draw them.
    val listCards: ListCards,
    val dismissedClosures: List<DepartureRow>,
    val from: ListRows,
    val cards: List<JourneyCard>,
    val cardRows: List<DepartureRow>,
    val journeys: List<StarredJourney>,
)

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
)

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
): ListRows {
    val stops = source.stops
    val lineStatuses = source.lineStatuses
    val all = DepartureRows.withAlertsBehind(DepartureRows.withoutDismissed(DepartureRows.across(stops, now, lineStatuses), dismissed), alertSequences)
    // A near-me list shows its nearby stops only: a journey's farther origin, fetched for its card,
    // isn't one of them (SPEC *Journeys*).
    val shownStops = if (stopDistanceMeters.isEmpty()) stops else stops.filter { it.stopId in stopDistanceMeters }
    val across = HiddenModes.rows(DepartureRows.across(shownStops, now, lineStatuses), hiddenModes)
    // A "near me now" list (distances present) shows a line once, from its nearest stop, then orders
    // closest-stop-first (soonest breaks a same-stop tie). A location-free list keeps across's
    // soonest-first order (D1).
    val nearby =
        if (stopDistanceMeters.isEmpty()) {
            // No line dedupe without distances (a station's page shows every stop), but a notice TfL
            // reports against each member of a hub is still one card per place.
            DepartureRows.stopStatusFolded(across)
        } else {
            DepartureRows.byStopDistance(DepartureRows.nearbyDeduped(across, stopDistanceMeters, dismissed), stopDistanceMeters)
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
    val destinationClosures = DepartureRows.withoutDismissed(DepartureRows.across(journeyDestinationStops, now), dismissed)
        .filter { it.stopDisruption != null }
    return ListRows(source, now, all, nearby, shared, marked, destinationClosures, journeyDestinationsUnknown)
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
    journeys: List<StarredJourney> = emptyList(),
    topology: RouteTopology = RouteTopology.EMPTY,
): ShownRows {
    val rows = DepartureRows.pinStarred(
        DepartureRows.withoutShownAbove(nearbyRows, journeyRowsShown),
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
    return ShownRows(rows, listCards, dismissedClosures, from, cards, journeyRowsShown, journeys)
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
            DepartureRows.withoutDismissed(HiddenModes.rows(DepartureRows.across(platformStops, now, lineStatuses), hiddenModes), dismissed),
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
    return PlatformView(viewRows, title, source, now, ListCards.of(viewRows, warningsLead = true, topology))
}
