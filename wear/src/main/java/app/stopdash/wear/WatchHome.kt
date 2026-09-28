package app.stopdash.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.stopdash.domain.NoTimes
import app.stopdash.ui.PillColors
import app.stopdash.ui.pillColors

private val Warning = Color(0xFFF2C14E)
private val Muted = Color(0xFFB0ABA3)
private val NeutralFill = Color(0xFF303030)

/**
 * The watch app: the tile's lines, all of them, in a dense list that scrolls with the crown or a
 * swipe (dev-docs/wear-os.md *Surfaces*). The age stamp and any out-of-date note lead; starred
 * rows come first, under the widget's stop headers; Refresh sits at the end. [frame] is null until
 * the stored envelope has been read: just the title, never a flash of a wrong state. Read-only:
 * starring, watching and settings stay on the phone.
 */
@Composable
fun WatchHomeScreen(frame: TileFrame?, notice: RefreshNotice.Kind? = null, onRefresh: (() -> Unit)? = null) {
    MaterialTheme {
        val background = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        when (frame) {
            is TileFrame.Rows -> ScalingLazyColumn(modifier = background, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Stamp(frame) }
                if (frame.stale || frame.partial) {
                    item { Note(stringResource(if (frame.stale) R.string.tile_out_of_date else R.string.watch_partly_out_of_date), Warning) }
                } else if (frame.statusUnknown) {
                    item { Note(stringResource(R.string.watch_disruptions_unknown), Warning) }
                }
                if (frame.omitted > 0) {
                    item { Note(pluralStringResource(R.plurals.watch_more_stops_on_phone, frame.omitted, frame.omitted)) }
                }
                items(frame.lines) { line -> Line(line) }
                if (onRefresh != null) item { RefreshButton(notice, onRefresh) }
            }
            null -> Box(modifier = background, contentAlignment = Alignment.Center) { Title() }
            else -> Box(modifier = background.padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (frame) {
                        // The phone may have stops this watch missed: a refresh resends them, and
                        // a failed one says why here rather than leaving only the setup line.
                        TileFrame.NeverSynced -> Message(stringResource(R.string.watch_open_phone))
                        // Stops added on the phone may not have arrived: the same refresh resends them.
                        TileFrame.NoStops -> Message(stringResource(R.string.watch_add_stops))
                        // Every stop failed to load: out of date, never "no stops".
                        else -> Message(stringResource(R.string.watch_partly_out_of_date), Warning)
                    }
                    if (onRefresh != null) RefreshButton(notice, onRefresh)
                }
            }
        }
    }
}

/** The data's age, highlighted once every stop is out of date. */
@Composable
private fun Stamp(frame: TileFrame.Rows) {
    val text = if (frame.ageMinutes < 1) {
        stringResource(R.string.tile_updated_now)
    } else {
        stringResource(R.string.tile_updated_ago, frame.ageMinutes)
    }
    Note(text, if (frame.stale) Warning else Muted)
}

@Composable
private fun Line(line: TileLine) {
    when (line) {
        is TileLine.Header -> Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = line.spoken },
        )
        is TileLine.Departure -> DepartureRow(line.row)
        is TileLine.Disruption -> DisruptionRow(line)
        TileLine.OnlyHidden -> Note(stringResource(R.string.tile_only_hidden))
        is TileLine.EmptyStop -> {
            val empty = stringResource(if (line.uncertain) R.string.tile_may_be_out_of_date else R.string.tile_no_departures)
            Note(stringResource(R.string.tile_stop_empty, line.stopName, empty))
        }
    }
}

/**
 * One compact departure line: the pill, where it's going, and its countdowns (or `?`). At a large
 * font scale the countdowns move under the destination, so neither squeezes the other off the
 * round screen.
 */
@Composable
private fun DepartureRow(row: TileRow) {
    val stacked = LocalDensity.current.fontScale > STACKED_FONT_SCALE
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Pill(row)
        if (stacked) {
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Destination(row, Modifier)
                Countdown(row)
            }
        } else {
            Destination(row, Modifier.weight(1f).padding(horizontal = 4.dp))
            Countdown(row)
        }
    }
}

/**
 * A disrupted line's "⚠ Severe Delays": beside its pill when the line has no countdown, else under
 * its departures, indented past the pill so it reads as part of that service (as on the tile). A
 * rail line's reason for no times follows it, "⚠ Part Suspended · No key", so the disruption always
 * leads; the list scrolls, so it may wrap rather than cut the reason.
 */
@Composable
private fun DisruptionRow(line: TileLine.Disruption) {
    val reason = when (line.noTimes) {
        NoTimes.NO_KEY -> stringResource(R.string.watch_no_rail_key)
        NoTimes.NO_DATA -> stringResource(R.string.watch_no_data)
        NoTimes.NO_TRAINS, null -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        if (line.alone) Pill(line.row) else Spacer(Modifier.width(36.dp))
        Text(
            text = if (reason == null) "⚠ ${line.description}" else "⚠ ${line.description} · $reason",
            style = MaterialTheme.typography.bodySmall,
            color = Warning,
            maxLines = if (reason == null) 1 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics {
                contentDescription = if (reason == null) "Disrupted: ${line.description}" else "Disrupted: ${line.description}. $reason"
            },
        )
    }
}

/** Above this font scale a row's countdowns sit under its destination. */
private const val STACKED_FONT_SCALE = 1.3f

@Composable
private fun Destination(row: TileRow, modifier: Modifier) {
    Text(
        text = row.label,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun Countdown(row: TileRow) {
    Text(
        text = row.countdown,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = if (row.stale) Warning else MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The line's pill in its official color, as on the tile; a screen reader says the line's name. */
@Composable
private fun Pill(row: TileRow) {
    val shape = RoundedCornerShape(6.dp)
    val (fill, label, border) = when (val colors = pillColors(row.lineName, row.lineId, row.mode, Color.Black)) {
        is PillColors.Solid -> Triple(colors.fill, colors.label, colors.border)
        is PillColors.Hollow -> Triple(Color.Black, colors.label, colors.border)
        PillColors.Neutral -> Triple(NeutralFill, Color.White, NeutralFill)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // At least the tile's 36dp, growing with the font scale so the code is never clipped.
            .widthIn(min = 36.dp)
            .background(fill, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics { contentDescription = row.lineName },
    ) {
        Text(text = row.code, color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Refresh, or while one is pending or after one failed, what happened. */
@Composable
private fun RefreshButton(notice: RefreshNotice.Kind?, onRefresh: () -> Unit) {
    Button(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(refreshLabel(notice)), maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Title() {
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Message(text: String, color: Color = Color.Unspecified) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center)
}

@Composable
private fun Note(text: String, color: Color = Color.Unspecified) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}
