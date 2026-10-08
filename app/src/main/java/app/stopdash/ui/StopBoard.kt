package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.stopdash.R
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.TflException
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.RelativeTime
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredRow
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * A stop's live departures as its details page draws them (SPEC *Finding a line*): its [state], the
 * time it counts down from ([now]), and a way to ask again ([onRefresh]). The caller keeps the model
 * behind them alive while the page is up, refreshing as a station's page does.
 */
@Immutable
internal class StopDepartures(
    val state: DeparturesUiState,
    val now: Instant,
    val onRefresh: () -> Unit,
    // The alerts dismissed anywhere, as the near-me list has them.
    val dismissed: Set<DismissedAlert> = emptySet(),
    // A route page's star and dismiss, as on a station's page: the routes pinned to the top, whether pinning
    // can be saved, and the stop's own model's writes; null leaves them off. A write that failed says so
    // ([starWriteFailed], [dismissWriteFailed]) until its [onStarWriteFailureShown] or
    // [onDismissWriteFailureShown].
    val starred: Set<StarredRow> = emptySet(),
    val starringAvailable: Boolean = false,
    val onToggleStar: ((DepartureRow) -> Unit)? = null,
    val onDismissAlert: ((DepartureRow) -> Unit)? = null,
    val starWriteFailed: Boolean = false,
    val onStarWriteFailureShown: () -> Unit = {},
    val dismissWriteFailed: Boolean = false,
    val onDismissWriteFailureShown: () -> Unit = {},
)

/**
 * The stop's departures split for its page: a closure or moved-stop notice ([closures]) ahead of
 * everything, then the line it was opened from, then the rest.
 */
internal class StopBoardView(
    val line: List<StopCard>,
    val others: List<StopCard>,
    val closures: List<DepartureRow> = emptyList(),
    // An empty line part can't be stood behind as "none due": a failed or partial refresh, or a stale stop.
    val emptyUncertain: Boolean = false,
    // The board's services by their route's key ([detailKey]), so a row's route page finds it again on each
    // tick with a lookup, never a walk on the main thread; and the state and time they were drawn from.
    val rowsByKey: Map<String, DepartureRow> = emptyMap(),
    val source: DeparturesUiState.Loaded? = null,
    val now: Instant? = null,
)

/**
 * The stops a stop's board asks for: [id], declaring [served], and the other ids TfL lists the same station
 * under ([otherIds]). Those share one cluster, so their departures group as one place on the board, never
 * as two places of one name (Codex on #664); a stop under one id keeps none, as a searched station's does.
 */
internal fun lineStopRefs(id: String, name: String, served: List<LineRef>, otherIds: List<String>): List<StopRef> {
    val cluster = if (otherIds.isEmpty()) "" else id
    return listOf(StopRef(id, name, lines = served, clusterId = cluster)) + otherIds.map { StopRef(it, name, clusterId = cluster) }
}

/**
 * [state]'s rows for [lineId] grouped by platform or pole, and every other line's after them
 * ([StopGrouping.groupByStop], [stopCard]); with no [lineId], every line's in [StopBoardView.others].
 * Walks every departure, so on the worker, never in composition (AGENTS.md *Main thread*).
 */
@WorkerThread
internal fun stopBoardView(
    state: DeparturesUiState.Loaded,
    lineId: String?,
    now: Instant,
    topology: RouteTopology = RouteTopology.EMPTY,
    // Alerts dismissed anywhere stay dismissed here: they're place-wide and line-wide (Codex on #661).
    dismissed: Set<DismissedAlert> = emptySet(),
    // The routes of its bus lines with an alert to place: one wholly behind the stop is muted, as on the
    // near-me list ([DepartureRows.withAlertsBehind]); a line with no route in keeps its alert.
    alertSequences: Map<String, LineSequence?> = emptyMap(),
): StopBoardView {
    // A stop's own notice (closed, moved) comes out first: grouping leaves it to a caller to draw, so it's
    // never dropped from the board (Codex on #661).
    val rows = DepartureRows.withAlertsBehind(
        DepartureRows.withoutDismissed(DepartureRows.across(state.stops, now, state.lineStatuses), dismissed),
        alertSequences,
    )
    val (closures, services) = rows.partition { it.stopDisruption != null }
    val (line, others) = services.partition { lineId != null && it.lineId == lineId }
    fun cards(rows: List<DepartureRow>) = StopGrouping.groupByStop(rows).map { stopCard(it, topology) }
    return StopBoardView(
        cards(line),
        cards(others),
        closures,
        emptyStateUncertain(state, stopStamps(state.stops), now),
        rowsByKey = services.associateBy { it.detailKey() },
        source = state,
        now = now,
    )
}

/** [departures] as [stopBoardView] splits them, worked out on [LocalWorker]; null until the first is in. */
@Composable
internal fun rememberStopBoard(departures: StopDepartures?, lineId: String?): StopBoardView? {
    val loaded = departures?.state as? DeparturesUiState.Loaded ?: return null
    val topology = LocalRouteTopology.current
    val routes = LocalRouteStops.current
    val worker = LocalWorker.current
    // The routes of its bus lines with an alert to place, asked of the route pages' day-long cache on each
    // load, so its expiry applies here as everywhere: one request per such line a day, as the near-me list
    // makes. Until one is in the alert stays on; a reload that fails keeps the route held, a day-old route
    // beating none, as on the near-me list.
    var alertSequences by remember { mutableStateOf<Map<String, LineSequence>>(emptyMap()) }
    // A dismissed alert asks for nothing: its route couldn't change the board.
    val dismissed = departures.dismissed
    LaunchedEffect(loaded, routes, dismissed) {
        val repository = routes ?: return@LaunchedEffect
        val held = alertSequences
        val found = withContext(worker) {
            val wanted = DepartureRows.linesWithAlertsToPlace(loaded.stops, loaded.lineStatuses, departures.now, dismissed)
            val routesNow = coroutineScope {
                wanted.map { lineId ->
                    async {
                        try {
                            lineId to (repository.cached(lineId, "") ?: repository.load(lineId, ""))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: TflException) {
                            // Logged (sanitized) by the repository; the next load asks again.
                            held[lineId]?.let { lineId to it }
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            // Compared by identity here, so the board is worked out again only when a route changed.
            routesNow.takeIf { now -> now.size != held.size || now.any { (id, route) -> held[id] !== route } }
        }
        if (found != null) alertSequences = found
    }
    val slot: MutableState<Worked<Inputs, StopBoardView>?> = remember { mutableStateOf(null) }
    // Keyed by identity ([Inputs]): a new state or dismissal set is a new key at once, never compared field
    // by field on the main thread (Codex on #661). The board up for an earlier tick or fetch of the same
    // line stands in while the next is worked out.
    val key = Inputs(loaded, lineId, departures.now, topology, departures.dismissed, alertSequences)
    return rememberWorked(slot, key, keep = { held, wanted -> held.parts[1] == wanted.parts[1] }) {
        stopBoardView(loaded, lineId, departures.now, topology, departures.dismissed, alertSequences)
    }
}

/**
 * The stop's board, after its title and buttons: [lineName]'s platform cards, then the stop's other
 * services under "Also here" (SPEC *Finding a line*); with no [lineName], every service, none leading. Loading and failure say so in place; a refresh
 * that failed keeps the board up with a line saying so, and a stale card withholds its times
 * ([StopGroupCard]), so nothing old reads as live (SPEC D4).
 */
internal fun LazyListScope.stopBoard(
    departures: StopDepartures?,
    view: StopBoardView?,
    lineName: String?,
    // A row's route page, as on a station's page; null leaves the rows inert.
    onOpenRoute: ((DepartureRow, RouteFocus?) -> Unit)? = null,
) {
    val state = departures?.state
    when {
        state is DeparturesUiState.Error -> item(key = "boardError") {
            Column(horizontalAlignment = Alignment.Start) {
                Text(stringResource(errorMessage(state.kind)), modifier = Modifier.testTag("stopBoardError"))
                TextButton(onClick = departures.onRefresh) { Text(stringResource(R.string.try_again)) }
            }
        }
        state !is DeparturesUiState.Loaded || view == null -> item(key = "boardLoading") {
            BoardNote(stringResource(R.string.stop_board_loading))
        }
        else -> {
            // One line, always there while the board is: how old it is, or what it couldn't stand behind
            // yet. Its disruption checks still out or failed say so here, so unchecked times never read as
            // clean (Codex on #661), and nothing below moves as they come back.
            item(key = "boardStamp") { BoardStamp(state, departures.now, departures.onRefresh) }
            state.refreshFailure?.let { kind ->
                item(key = "boardRefreshFailed") { BoardNote(stringResource(refreshFailureMessage(kind))) }
            }
            // Arrivals that couldn't be refreshed while another check could: kept, and said so, as the
            // near-me list does (Codex on #661).
            if (state.partialRefresh && state.refreshFailure == null) {
                item(key = "boardPartialRefresh") {
                    BoardNote(partialRefreshMessage(state.partialStops.values.map { it.name }.distinct(), state.partialReason))
                }
            }
            items(view.closures, key = { row -> "closure|${row.stopId}|${row.stopDisruption}" }) { row ->
                StopClosureCard(row, onDismiss = null)
            }
            if (lineName != null && view.line.isEmpty()) {
                // "None due" only where the board can stand behind it: not stale, not after a failed or
                // partial refresh (Codex on #661); else that it may be out of date, a tap asking again.
                item(key = "boardLineEmpty") {
                    if (view.emptyUncertain) {
                        Text(
                            stringResource(R.string.departures_stale_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = departures.onRefresh).padding(vertical = 8.dp),
                        )
                    } else {
                        BoardNote(stringResource(R.string.stop_board_line_empty, lineName))
                    }
                }
            }
            cards(view.line, "line", departures.now, onOpenRoute)
            if (lineName == null && view.others.isEmpty()) {
                // No line leading and nothing due: said as the near-me list says it, unless uncertain.
                item(key = "boardEmpty") {
                    if (view.emptyUncertain) {
                        Text(
                            stringResource(R.string.departures_stale_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = departures.onRefresh).padding(vertical = 8.dp),
                        )
                    } else {
                        BoardNote(stringResource(R.string.stop_board_empty))
                    }
                }
            }
            if (view.others.isNotEmpty()) {
                if (lineName != null) {
                    item(key = "boardAlsoHere") {
                        Text(
                            stringResource(R.string.stop_board_also_here),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp).testTag("stopBoardAlsoHere"),
                        )
                    }
                }
                cards(view.others, "others", departures.now, onOpenRoute)
            }
        }
    }
}

private fun LazyListScope.cards(cards: List<StopCard>, section: String, now: Instant, onOpenRoute: ((DepartureRow, RouteFocus?) -> Unit)?) {
    items(cards, key = { card -> "$section|${card.group.key}" }) { card ->
        Column {
            // The list spaces the cards evenly, so no header takes the extra break above it.
            StopGroupHeader(card.group.stopName, card.group.qualifier, distanceLabel = null, firstOnScreen = true)
            StopGroupCard(
                card,
                now,
                starred = emptySet(),
                onToggleStar = {},
                starringAvailable = false,
                onOpenDetail = onOpenRoute,
            )
        }
    }
}

/**
 * The board's stamp: stale outranks all (its times are withheld, and a tap refreshes); then a check
 * still out, then one that couldn't be made; else the board's age.
 */
@Composable
private fun BoardStamp(state: DeparturesUiState.Loaded, now: Instant, onRefresh: () -> Unit) {
    val age = Staleness.age(state.fetchedAt, now)
    val text = when {
        Staleness.isStale(age) -> stringResource(R.string.stale_stamp)
        state.checkingDisruptions -> stringResource(R.string.checking_stamp)
        state.disruptionUnknown || state.stopsDisruptionUnknown.isNotEmpty() -> stringResource(R.string.disruptions_unknown)
        else -> RelativeTime.formatAge(age).replaceFirstChar { it.uppercaseChar() }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRefresh).padding(vertical = 4.dp).testTag("stopBoardStamp"),
    )
}

@Composable
private fun BoardNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/**
 * The route page of a row tapped on a stop's board ([stopBoard]), over the stop's details as it opens over
 * a station's page: the row found again on each tick by its [key] in the board [view], with the state and
 * time it was drawn from. Nothing while the board is worked out again (a rotation); the caller closes it
 * once the row has left the board. Stars and dismissals stay with the near-me list and station pages.
 */
@Composable
internal fun StopRoutePage(
    view: StopBoardView?,
    key: String,
    focus: RouteFocus?,
    // Its line's page ("View line") open over it: held by the caller, whose usage stats count it.
    lineOpen: MutableState<Boolean>,
    onBack: () -> Unit,
    // The board's model's star and dismiss ([StopDepartures]); null offers neither.
    actions: StopDepartures? = null,
) {
    val row = view?.rowsByKey?.get(key) ?: return
    val loaded = view.source ?: return
    val now = view.now ?: return
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // A dialog's window has none of the app's text size nor its pinch (SPEC *Display size*).
        FontSizePinchWindow {
            RouteDetailScreen(
                row = row,
                // Pinned and dismissed as on a station's page: the stop's own model holds them.
                isStarred = actions?.onToggleStar != null && StarredRow.of(row) in actions.starred,
                // Same rule as the list card: only a timed row with starring available is pinnable.
                starrable = actions?.onToggleStar != null && actions.starringAvailable &&
                    row.stopDisruption == null && row.upcoming.isNotEmpty(),
                // Per row, as the near-me list's page judges it: its line unchecked, or its stop's closure check.
                disruptionUnknown = loaded.lineUncheckedFor(row) || row.stopId in loaded.stopsDisruptionUnknown,
                disruptionChecking = loaded.checkingDisruptionsFor(row),
                lineUnknown = loaded.lineDoubtedFor(row, now),
                lineChecking = loaded.checkingLineFor(row),
                // A stale row's status isn't presented as current (SPEC D4).
                stale = Staleness.isStale(row.fetchedAt, now),
                now = now,
                onToggleStar = { actions?.onToggleStar?.invoke(row) },
                onBack = onBack,
                focus = focus,
                lineOpen = lineOpen,
                onDismissAlert = actions?.onDismissAlert?.takeIf { row.status != null }?.let { dismiss -> { dismiss(row) } },
                // One planned alert dismissed on its own: passed as a row standing for just that alert.
                onDismissPlanned = actions?.onDismissAlert?.let { dismiss ->
                    { planned -> dismiss(row.copy(status = null, stopDisruption = null, plannedAlerts = listOf(planned))) }
                },
                // A station on its stop list opens that station's page, as from any route page.
                onOpenStop = LocalOpenRouteStop.current,
            )
        }
    }
}
