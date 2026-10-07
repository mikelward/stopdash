package app.stopdash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R

/** How many rows [PlaceDirectSection] shows before "+N more". */
internal const val DIRECT_ROWS = 3

// The most the expanded rows take before they scroll: five rows, so the routes below stay in view.
private val DIRECT_EXPANDED_MAX = 200.dp

/**
 * The *Direct* section atop a trip to a place (SPEC *Direct to a place*; maintainer, 2026-10-07): a bold
 * "Direct" header like the routes' "Fastest", then a row per line going straight there — its pill, the
 * stop it leaves from, its next trains ("3 · 8 · 13 min") — up to [DIRECT_ROWS], then "+N more". With
 * none, one line says so: "Checking…", "None", or "Couldn't check" with a Retry, never a blank, and the
 * header shows from the first frame so the routes under it don't jump when the rows land.
 */
@Composable
internal fun PlaceDirectSection(
    state: PlaceDirectViewModel.State,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    // A row tapped: its ride opened as a route (maintainer, 2026-10-07), as a card below opens. Null, not tappable.
    onOpen: ((PlaceDirectViewModel.ShownRow) -> Unit)? = null,
    // The row being opened, said in place of its times while its route is found, or that none was.
    opening: TripViewModel.DirectOpening? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("placeDirect").padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.trip_direct_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            PlaceDirectViewModel.State.Checking -> DirectNote(stringResource(R.string.trip_direct_checking))
            PlaceDirectViewModel.State.Failed -> DirectFailed(onRetry)
            is PlaceDirectViewModel.State.Ready -> when {
                state.rows.isNotEmpty() -> {
                    // Expanded, the rows scroll in a bounded lazy list: the section sits over the routes'
                    // list, outside its scrolling, so it must never push the routes off the screen, and only
                    // the rows in view are composed, however many lines go there.
                    if (expanded) {
                        LazyColumn(Modifier.heightIn(max = DIRECT_EXPANDED_MAX).testTag("placeDirectRows")) {
                            items(state.rows, key = { it.row.lineId }) { DirectRow(it, onOpen, opening) }
                        }
                    } else {
                        Column(Modifier.testTag("placeDirectRows")) {
                            // At most [DIRECT_ROWS], by index: nothing that grows with the answer.
                            for (i in 0 until minOf(DIRECT_ROWS, state.rows.size)) DirectRow(state.rows[i], onOpen, opening)
                        }
                    }
                    val more = if (expanded) 0 else state.rows.size - minOf(DIRECT_ROWS, state.rows.size)
                    if (more > 0) {
                        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("placeDirectMore")) {
                            Text(stringResource(R.string.trip_direct_more, more))
                        }
                    }
                    // Some stop or line went unchecked: the rows may not be every way there.
                    if (state.uncertain) DirectNote(stringResource(R.string.trip_direct_partial), MaterialTheme.colorScheme.error)
                }
                state.checking -> DirectNote(stringResource(R.string.trip_direct_checking))
                state.uncertain -> DirectFailed(onRetry)
                else -> DirectNote(stringResource(R.string.trip_direct_none))
            }
        }
    }
}

/**
 * Whether [opening] is [shown]'s: its line and stop, and, once failed, the stops near the place it reached when tapped.
 * A row that reaches others since (a branch's trains now listed) may have a route: it doesn't say it failed.
 */
internal fun directOpeningOf(opening: TripViewModel.DirectOpening, shown: PlaceDirectViewModel.ShownRow): Boolean =
    opening.lineId == shown.row.lineId && opening.fromId == shown.row.fromId && (!opening.failed || opening.endKey == shown.endKey)

@Composable
private fun DirectRow(shown: PlaceDirectViewModel.ShownRow, onOpen: ((PlaceDirectViewModel.ShownRow) -> Unit)?, opening: TripViewModel.DirectOpening?) {
    val row = shown.row
    val tap = onOpen?.let { open -> Modifier.clickable(onClickLabel = stringResource(R.string.trip_direct_open)) { open(shown) } } ?: Modifier
    // This row's route being found, or not found: said where its times go, so nothing else moves.
    val mine = opening?.takeIf { directOpeningOf(it, shown) }
    Row(Modifier.fillMaxWidth().then(tap).testTag("placeDirect-${row.lineId}"), verticalAlignment = Alignment.Top) {
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
            LinePill(row.lineName, row.lineId, row.mode, Modifier.widthIn(min = 56.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                Text(row.fromName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // A line not running a good service says so under its stop, in TfL's words ("Severe
            // Delays"), so its trains never read as running normally.
            shown.disruption?.let { disruption ->
                Text(
                    stringResource(R.string.trip_line_status, row.lineName, disruption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp).testTag("placeDirectStatus-${row.lineId}"),
                )
            }
            // A stop it boards at, or every stop near the place it gets off at, has a closure notice.
            shown.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp).testTag("placeDirectNotice-${row.lineId}"),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                mine == null -> Text(shown.times, style = MaterialTheme.typography.bodyLarge)
                mine.failed -> Text(
                    stringResource(R.string.trip_direct_not_planned),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("placeDirectNotPlanned-${row.lineId}"),
                )
                else -> Text(
                    stringResource(R.string.trip_planning),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("placeDirectPlanning-${row.lineId}"),
                )
            }
        }
    }
}

@Composable
private fun DirectNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.heightIn(min = 40.dp).padding(vertical = 8.dp),
    )
}

@Composable
private fun DirectFailed(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DirectNote(stringResource(R.string.trip_direct_failed), MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.testTag("placeDirectRetry")) {
            Text(stringResource(R.string.route_stops_retry))
        }
    }
}
