package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.AlertsBehind
import app.stopdash.domain.ClosedNotice
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.FartherBuses
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineSequence
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.stopPlaceKey
import java.time.Instant

/**
 * The near-me list as the screen draws it, worked out together by [nearbyListOf] on a worker
 * ([rememberComputed]): grouping every stop's departures, folding a line to its nearest stop, placing
 * each bus alert against its route and pinning the starred grows with the list, and the screen asks
 * again on every tick.
 */
internal data class NearbyListShown(
    // Each place's modes not yet hidden (by cluster), for a header's "Hide ‹mode›" items.
    val placeModes: Map<String, Set<String>> = emptyMap(),
    // The notices TfL filed against more than one stop of a place, by place and text.
    val sharedNotices: Set<Pair<String, String>> = emptySet(),
    // The list's rows before the journey cards and stars: what the cards may cover.
    val nearbyRows: List<DepartureRow> = emptyList(),
    // The near-me closures the user dismissed, whose place keeps its heading and "Closed" chip.
    val dismissedClosures: List<DepartureRow> = emptyList(),
    // The rows drawn: less what the journey cards show, starred lifted to the top.
    val rows: List<DepartureRow> = emptyList(),
    // The farther bus cards to show, decided against the routes drawn.
    val farther: List<FartherCard> = emptyList(),
    // Every loaded stop's rows by route identity, and the drawn rows', for a route page to follow.
    val rowsByDetailKey: Map<String, DepartureRow> = emptyMap(),
    val loadedByDetailKey: Map<String, DepartureRow> = emptyMap(),
)

/**
 * The near-me list from [loaded] at [now] ([NearbyListShown]). A near-me list (distances in
 * [stopDistanceMeters]) shows each line once, from its nearest stop, nearest first; a location-free
 * one keeps every stop, soonest first (D1). [journeyRowsShown] are the rows the journey cards draw,
 * [farther] the farther cards before picking, and [pendingStopIds] the stops still loading.
 */
@WorkerThread
internal fun nearbyListOf(
    loaded: DeparturesUiState.Loaded?,
    now: Instant,
    stopDistanceMeters: Map<String, Double>,
    dismissed: Set<DismissedAlert>,
    hiddenModes: Set<String>,
    alertSequences: Map<String, LineSequence?>,
    journeyRowsShown: List<DepartureRow>,
    starred: Set<StarredRow>,
    farther: List<FartherCard>,
    pendingStopIds: List<String>,
): NearbyListShown {
    val placeModesShown = placeModes(loaded?.stops.orEmpty()).mapValues { (_, modes) ->
        modes.filterNotTo(LinkedHashSet()) { HiddenModes.isHidden(it, hiddenModes) }
    }
    val (nearbyOrdered, sharedNotices) = if (loaded == null) {
        emptyList<DepartureRow>() to emptySet<Pair<String, String>>()
    } else {
        // A near-me list shows its nearby stops only: a journey's farther origin, fetched for its
        // card above, isn't one of them (SPEC *Journeys*).
        val shownStops = if (stopDistanceMeters.isEmpty()) loaded.stops else loaded.stops.filter { it.stopId in stopDistanceMeters }
        val across = HiddenModes.rows(DepartureRows.across(shownStops, now, loaded.lineStatuses), hiddenModes)
        // A "near me now" list (distances present) shows a line once, from its nearest stop, then
        // orders closest-stop-first (soonest breaks a same-stop tie). A location-free list keeps
        // across's soonest-first order (D1).
        val ordered =
            if (stopDistanceMeters.isEmpty()) {
                // No line dedupe without distances (a station's page shows every stop), but a
                // notice TfL reports against each member of a hub is still one card per place.
                DepartureRows.stopStatusFolded(across)
            } else {
                val deduped = DepartureRows.nearbyDeduped(across, stopDistanceMeters, dismissed)
                DepartureRows.byStopDistance(deduped, stopDistanceMeters)
            }
        // The notices TfL filed against more than one stop of a place, seen before the fold keeps one
        // copy: such a notice is about the place, so it heads the place's own group, even when the
        // copy kept is a lettered pole's (SPEC *Disruptions*).
        val shared = across.filter { it.stopDisruption != null }
            .groupBy { stopPlaceKey(it) to it.stopDisruption.orEmpty() }
            .filterValues { rows -> rows.mapTo(HashSet()) { it.stopId }.size > 1 }
            .keys
        ordered to shared
    }
    // Hide the service alerts the user has dismissed (until their content changes), and unflag a bus
    // alert wholly behind its stop.
    val nearbyRows = DepartureRows.withAlertsBehind(DepartureRows.withoutDismissed(nearbyOrdered, dismissed), alertSequences)
    // The near-me closures the user dismissed: a closed place with nothing else to show keeps its
    // heading and "Closed" chip in place (SPEC *Disruptions*).
    val dismissedClosures = if (stopDistanceMeters.isEmpty()) {
        emptyList()
    } else {
        val shown = nearbyRows.toHashSet()
        nearbyOrdered.filter { it !in shown && it.stopDisruption?.let(ClosedNotice::saysClosed) == true }
    }
    // Without the rows a journey card above already shows in full, then with the user's starred
    // services lifted to the top (SPEC D8). Warnings still lead on the location-free watched list; on
    // the near-me list (distances present) an alert is not hoisted, so a nearer stop is never pushed
    // below a farther one for carrying one.
    val rows = DepartureRows.pinStarred(
        DepartureRows.withoutShownAbove(nearbyRows, journeyRowsShown),
        starred,
        warningsLead = stopDistanceMeters.isEmpty(),
    )
    // The farther bus cards to show, decided against the routes the screen actually shows (SPEC
    // *Finding stops → Farther stations*): every row drawn, the list's, the journey cards' and an
    // opened card's (an opened card itself always stays). Deciding it here, from the rows drawn,
    // means every filter the list applies (dismissed alerts, hidden modes, the nearest-stop dedupe,
    // a journey's origin) is honored without being copied, and a route an opened card turned out to
    // run is never offered again by another. A place by a station the screen shows claims its
    // routes first: one it draws rows for (the list's or a journey card's), or one still drawn as a
    // cold load's "Loading" card, so the cards don't reshuffle when its rows land (Codex).
    val opened = farther.filter { it.load != null }.mapTo(HashSet()) { it.place.key }
    val shownBus = (rows + journeyRowsShown)
        .filter { it.mode.equals(FartherBuses.MODE, ignoreCase = true) && it.lineId.isNotBlank() }
        .mapTo(HashSet()) { it.lineId }
    val shownStops = (rows + journeyRowsShown).mapTo(HashSet()) { it.stopId }
    shownStops += pendingStopIds
    val kept = CollapsedPlaces.withBusesPicked(farther.map { it.place }, shownBus, opened, shownStopIds = shownStops)
        .associateBy { it.key }
    val fartherShown = farther.mapNotNull { card -> kept[card.place.key]?.let { card.copy(place = it) } }
    // Every loaded stop's rows too: a page opened from a journey card stays open when that journey is
    // unstarred from the page itself, while its origin's departures are still loaded.
    val loadedRows = loaded?.let { ld ->
        DepartureRows.withAlertsBehind(DepartureRows.withoutDismissed(DepartureRows.across(ld.stops, now, ld.lineStatuses), dismissed), alertSequences)
    }.orEmpty()
    return NearbyListShown(
        placeModes = placeModesShown,
        sharedNotices = sharedNotices,
        nearbyRows = nearbyRows,
        dismissedClosures = dismissedClosures,
        rows = rows,
        farther = fartherShown,
        rowsByDetailKey = byDetailKey(rows),
        loadedByDetailKey = byDetailKey(loadedRows),
    )
}

/**
 * The platform, pole or station the user drilled into (SPEC D8): its stop ids, or for a whole-station
 * view its cluster keys ([stationClusterOf]), comma-joined as saved; its [StopGroup.splitKey]; whether it's a whole station;
 * and the name tapped, which a station view keeps as its title.
 */
internal data class PlatformSelection(val ids: String, val key: String, val station: Boolean, val title: String)

/**
 * A drilled-into view's rows and title ([platformViewOf]); a null title means the snapshot no longer
 * holds the tapped group, and the view closes. [byDetailKey] is its rows by route identity.
 */
internal data class PlatformShown(val rows: List<DepartureRow>, val title: String?, val byDetailKey: Map<String, DepartureRow>)

/**
 * The rows of the view [selection] picks from [loaded] at [now], built from the platform's own stops
 * WITHOUT the near-me fold: the fold keeps a line only at its nearest stop, which would drop services
 * from a farther platform — the drill-down shows all of them. The stop ids alone aren't the platform:
 * a station's platforms all come from one TfL stop, so the rows are regrouped and only the tapped
 * group is kept, with any closure alert for its stops (Codex). It is matched on [StopGroup.splitKey]
 * — the platform, pole letter, bearing or compass — not [StopGroup.key], whose place part switches to
 * a per-stop key while the stop carries a line-status row (Codex). The saved stop ids already pin the
 * place. Dismissals and stars still apply, as on the full list ([listRows] are its rows). The title is
 * resolved from the matched group each time, since a letterless bus pole's qualifier (its shared
 * terminus) can change with the departures (Codex).
 */
@WorkerThread
internal fun platformViewOf(
    selection: PlatformSelection,
    loaded: DeparturesUiState.Loaded,
    now: Instant,
    starred: Set<StarredRow>,
    dismissed: Set<DismissedAlert>,
    listRows: List<DepartureRow>,
    hiddenModes: Set<String>,
    alertSequences: Map<String, LineSequence?>,
): PlatformShown {
    val ids = selection.ids.split(',').toSet()
    // A station view saved its clusters, not stop ids, so each snapshot re-resolves its members —
    // a pole or platform that joins or leaves the cluster on a refresh is followed (Codex).
    val platformStops =
        if (selection.station) loaded.stops.filter { stationClusterOf(it.clusterId, it.stopId) in ids }
        else loaded.stops.filter { it.stopId in ids }
    val stopRows = DepartureRows.pinStarred(
        DepartureRows.withAlertsBehind(
            DepartureRows.withoutDismissed(
                HiddenModes.rows(DepartureRows.across(platformStops, now, loaded.lineStatuses), hiddenModes),
                dismissed,
            ),
            alertSequences,
        ),
        starred,
    )
    val groups = StopGrouping.groupByStop(stopRows)
    // A header with no platform/pole to split on (a bare stop, or a directionless line-status
    // group beside a station's platforms) opens the whole stop: matching only its blank split
    // would show the warning without the stop's live departures (Codex).
    val matched = if (selection.key.isEmpty()) groups else groups.filter { it.splitKey == selection.key }
    // Plus the stops' directionless line-status rows ([StopGrouping.unplacedStatusRows]): which
    // platform a suspended line would run from is unknown, so every platform view shows it.
    val groupRows = (matched.flatMap { it.rows } + StopGrouping.unplacedStatusRows(groups)).toHashSet()
    // A whole-station view, or a whole-stop view spanning several groups, is titled by the bare
    // place, never by whichever platform happens to come first (Codex); a single group opened
    // from its own header keeps its full header text.
    val title = matched.firstOrNull()?.let { g ->
        // A station view keeps the name that was tapped: the cluster's members can carry different
        // cleaned names, and the first matched group depends on row order (Codex).
        if (selection.station) selection.title
        else if (matched.size > 1) g.stopName
        else groupHeaderTitle(g.stopName, g.qualifier)
    }
    // The place's closure cards are the full list's own — already folded and dismissal-filtered —
    // so a card dismissed on either screen carries one identity and stays hidden on both (Codex).
    val places = platformStops.mapTo(HashSet()) { stopPlaceKey(it) }
    val closures = listRows.filter { it.stopDisruption != null && stopPlaceKey(it) in places }
    val rows = closures + stopRows.filter { it.stopDisruption == null && it in groupRows }
    return PlatformShown(rows, title, byDetailKey(rows))
}

/**
 * The bus lines whose alert may lie wholly behind a stop shown, whose routes the screen loads to
 * place it ([DepartureRows.linesWithAlertsToPlace]), and the verdicts those routes give at every stop
 * shown, kept for the widget and the watch ([AlertsBehind.placement]); no verdicts without a list.
 */
@WorkerThread
internal fun alertLinesOf(loaded: DeparturesUiState.Loaded?, now: Instant, dismissed: Set<DismissedAlert>): Set<String> =
    loaded?.let { DepartureRows.linesWithAlertsToPlace(it.stops, it.lineStatuses, now, dismissed) }.orEmpty()

@WorkerThread
internal fun alertVerdictsOf(loaded: DeparturesUiState.Loaded?, now: Instant, alertSequences: Map<String, LineSequence?>) =
    loaded?.let { ld -> AlertsBehind.placement(ld.stops, ld.lineStatuses, alertSequences, now) }

/** Rows by their route identity ([detailKey]), the first of each, as the route page finds its row. */
@WorkerThread
internal fun byDetailKey(rows: List<DepartureRow>): Map<String, DepartureRow> {
    val byKey = LinkedHashMap<String, DepartureRow>()
    for (row in rows) byKey.putIfAbsent(row.detailKey(), row)
    return byKey
}
