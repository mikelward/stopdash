package app.stopdash.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.stopdash.data.WatchTrip
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.lineCode
import java.time.Instant

/**
 * The trip on the way, at the top of the watch app (dev-docs/wear-os.md *Trip on the way*): the step
 * the rider is at, what to do there now, and the next ride's trains when there are some. Previous and
 * Next page through the trip's steps to look ahead or back ([page], kept by the caller); they move
 * nothing, the trip stays where the phone says the rider is, and a new step from the phone brings the
 * page back to it. Each part is its own list item, so the round screen scales them as it scrolls.
 */
internal fun ScalingLazyListScope.tripItems(shown: ShownTrip, now: Instant, page: Int, onPage: (Int) -> Unit) {
    val trip = shown.trip
    val step = trip.steps.getOrNull(page) ?: return
    item { TripHeading(shown.stale) }
    item { TripStep(trip, step, page) }
    // The next ride's trains: at its boarding step, and on the way to it.
    if (trip.departuresAt >= 0 && (page == trip.departuresAt || (page == trip.current && page < trip.departuresAt))) {
        // What the phone says of them: a failed update (over the last good rows), updating, or none.
        if (trip.departuresNote.isNotEmpty()) item { Note(trip.departuresNote, Warning) }
        // Each pole's trains under its own "Stop N", where the pair's other poles have some too.
        trainRows(trip, now, shown.stale).groupBy { it.stop }.forEach { (stop, rows) ->
            if (stop.isNotEmpty()) item { Note(stop) }
            items(rows) { DepartureRow(it.row) }
        }
    }
    if (trip.steps.size > 1) item { Pager(page, trip.steps.size, onPage) }
}

/** "On the way", and that it may be out of date when the phone hasn't updated it lately. */
@Composable
private fun TripHeading(stale: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Note(stringResource(R.string.watch_trip), if (stale) Warning else Muted)
        if (stale) Note(stringResource(R.string.tile_may_be_out_of_date), Warning)
    }
}

/** The step on the page: what to do now at the rider's own, else the step's words. */
@Composable
private fun TripStep(trip: WatchTrip, step: WatchTrip.Step, page: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (!step.walk && step.lineName.isNotBlank()) {
            Pill(TileRow(step.lineName, step.lineId, step.mode, lineCode(step.lineName, step.mode, step.lineId), "", "", starred = false, stale = false))
        }
        Column(Modifier.padding(start = 4.dp)) {
            if (page == trip.current) {
                Text(trip.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                if (trip.detail.isNotBlank()) Text(trip.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                // A step behind the rider reads muted; one ahead, as plain text.
                Text(step.text, style = MaterialTheme.typography.bodyMedium, color = if (page < trip.current) Muted else MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

/** Previous and Next, either side of where the page is ("2/5"). */
@Composable
private fun Pager(page: Int, count: Int, onPage: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val previous = stringResource(R.string.watch_trip_previous)
        val next = stringResource(R.string.watch_trip_next)
        FilledTonalIconButton(onClick = { onPage(page - 1) }, enabled = page > 0, modifier = Modifier.semantics { contentDescription = previous }) {
            Text("‹")
        }
        Text("${page + 1}/$count", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onPage(page + 1) }, enabled = page < count - 1, modifier = Modifier.semantics { contentDescription = next }) {
            Text("›")
        }
    }
}

/** One of a trip's train rows, with the pole it boards at ([WatchTrip.Train.stop]). */
internal data class TripTrainRow(val stop: String, val row: TileRow)

/**
 * [trip]'s trains as the tile's rows draw them: a row per pole, line and destination with its next
 * times, or "?" when the trip is out of date, as a stop's rows read once stale (SPEC D4). Trains the
 * rider can't catch get their own muted row, so a grayed time never shares a row with a catchable
 * one. Rows keep the order of each one's soonest train, a pole's rows together.
 */
internal fun trainRows(trip: WatchTrip, now: Instant, stale: Boolean): List<TripTrainRow> {
    // Paired, not keyed: two poles can list the same train, and each keeps its own pole.
    val due = trip.departures
        .map { Departure(it.lineId, it.lineName, "", it.destination, null, Instant.ofEpochMilli(it.dueAt), it.mode) to it }
        .filter { Countdown.upcoming(listOf(it.first), now).isNotEmpty() }
        .sortedBy { it.first.expectedArrival }
    return due.groupBy { it.second.stop }.flatMap { (stop, trains) ->
        trains.groupBy { (d, t) -> listOf(t.missed, d.lineId, d.lineName, d.destination) }.map { (_, group) ->
            val first = group.first().first
            val missed = group.first().second.missed
            val countdown = if (stale) "?" else Countdown.mergedLabel(group.map { it.first }, now)
            TripTrainRow(stop, TileRow(first.lineName, first.lineId, first.mode, lineCode(first.lineName, first.mode, first.lineId), first.destination, countdown, starred = false, stale = stale, muted = missed))
        }
    }
}
