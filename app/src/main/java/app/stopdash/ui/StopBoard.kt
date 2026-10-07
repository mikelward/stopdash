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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineRef
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.RelativeTime
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.Staleness
import java.time.Instant

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
): StopBoardView {
    // A stop's own notice (closed, moved) comes out first: grouping leaves it to a caller to draw, so it's
    // never dropped from the board (Codex on #661).
    val rows = DepartureRows.withoutDismissed(DepartureRows.across(state.stops, now, state.lineStatuses), dismissed)
    val (closures, services) = rows.partition { it.stopDisruption != null }
    val (line, others) = services.partition { lineId != null && it.lineId == lineId }
    fun cards(rows: List<DepartureRow>) = StopGrouping.groupByStop(rows).map { stopCard(it, topology) }
    return StopBoardView(cards(line), cards(others), closures, emptyStateUncertain(state, stopStamps(state.stops), now))
}

/** [departures] as [stopBoardView] splits them, worked out on [LocalWorker]; null until the first is in. */
@Composable
internal fun rememberStopBoard(departures: StopDepartures?, lineId: String?): StopBoardView? {
    val loaded = departures?.state as? DeparturesUiState.Loaded ?: return null
    val topology = LocalRouteTopology.current
    val slot: MutableState<Worked<Inputs, StopBoardView>?> = remember { mutableStateOf(null) }
    // Keyed by identity ([Inputs]): a new state or dismissal set is a new key at once, never compared field
    // by field on the main thread (Codex on #661). The board up for an earlier tick or fetch of the same
    // line stands in while the next is worked out.
    val key = Inputs(loaded, lineId, departures.now, topology, departures.dismissed)
    return rememberWorked(slot, key, keep = { held, wanted -> held.parts[1] == wanted.parts[1] }) {
        stopBoardView(loaded, lineId, departures.now, topology, departures.dismissed)
    }
}

/**
 * The stop's board, after its title and buttons: [lineName]'s platform cards, then the stop's other
 * services under "Also here" (SPEC *Finding a line*); with no [lineName], every service, none leading. Loading and failure say so in place; a refresh
 * that failed keeps the board up with a line saying so, and a stale card withholds its times
 * ([StopGroupCard]), so nothing old reads as live (SPEC D4).
 */
internal fun LazyListScope.stopBoard(departures: StopDepartures?, view: StopBoardView?, lineName: String?) {
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
            cards(view.line, "line", departures.now)
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
                cards(view.others, "others", departures.now)
            }
        }
    }
}

private fun LazyListScope.cards(cards: List<StopCard>, section: String, now: Instant) {
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
                onOpenDetail = null,
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
