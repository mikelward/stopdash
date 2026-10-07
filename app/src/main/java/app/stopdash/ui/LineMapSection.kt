package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.TflException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** What a line's map section shows: its route data loading, why it couldn't be had, no map, or the map. */
internal sealed interface LineMapUi {
    data object Loading : LineMapUi
    data class Failed(val kind: DeparturesUiState.Error.Kind) : LineMapUi

    /** TfL has no route for the line, or its routes can't be drawn this way (a loop with no end). */
    data object Unavailable : LineMapUi

    /**
     * The [map] folded as asked: [items] to draw, and whether there's anything to fold at all; laid out
     * for the status [statusKey] stands for.
     */
    class Ready(
        val map: LineMap,
        val items: List<LineMap.Item>,
        val foldable: Boolean,
        val statusKey: Any? = null,
        // Each stop's published position from the route data the map was laid from (the same map, not a
        // copy): a tapped station's, handed with it, so its details show their distance from the first frame.
        val positions: Map<String, Pair<Double, Double>> = emptyMap(),
    ) : LineMapUi
}

// The map laid out from [from] for the status [statusKey] stands for, or none where it can't be: kept
// apart from "not laid out yet" (null).
private class Laid(val from: LineSequence?, val statusKey: Any?, val map: LineMap?)

/**
 * [lineId]'s map for its page (SPEC *Line page → Map*): its route data from the route pages' own
 * day-long cache ([LocalRouteStops]), loaded when the page opens and never on a refresh path, laid out
 * with [status]'s alert placed on it, the rider's [starred], [nearby] and [riding] stops kept and an alert on the
 * [rides] they take shown in full, then folded as
 * [opened] and [all] say. Both run on [LocalWorker], never in composition; while a tap's new folding
 * is worked out the last one stands, so nothing moves under the finger. A map laid out for another
 * status never stands in, though: it would draw a closure that has ended, or none where one began, under
 * the status already saying otherwise, so it reads "Loading map…" for that moment (Codex, #606).
 * [statusKey] says when it's another: [LineMap.alertKey] where worked out with the line, compared by
 * value, so a status rebuilt with the same alert keeps the map up; else [status], by identity. A
 * [quieted] alert the page still names has its closures drawn too ([LineMap.forStatus]).
 */
@Composable
internal fun rememberLineMap(
    lineId: String,
    status: LineStatus?,
    starred: Set<String>,
    riding: Set<String>,
    opened: OpenedFolds?,
    all: Boolean,
    retry: Int,
    statusKey: Any? = status,
    quieted: LineStatus? = null,
    rides: List<List<String>> = emptyList(),
    nearby: Set<String> = emptySet(),
): LineMapUi? {
    val repository = LocalRouteStops.current ?: return null
    val worker = LocalWorker.current
    // The route data, from the cache in the first frame where it's there.
    val source by produceState<Any?>(repository.cached(lineId, ""), repository, lineId, retry) {
        if (value is LineSequence) return@produceState
        value = null
        value = try {
            withContext(worker) { repository.load(lineId, "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException.NotFound) {
            // TfL has no route for this line: no map, and no retry that could ever bring one.
            LineMapUi.Unavailable
        } catch (e: TflException) {
            // Already logged (sanitized) by the repository; surfaced here with its reason.
            LineMapUi.Failed(errorKindOf(e))
        }
    }
    val sequence = source as? LineSequence
    val laidSlot = remember { mutableStateOf<Worked<Inputs, Laid>?>(null) }
    val laid = rememberWorked(laidSlot, Inputs(sequence, status, statusKey, starred, riding, quieted, rides, nearby), keep = { _, _ -> true }) {
        Laid(sequence, statusKey, sequence?.let { LineMap.forStatus(it, status, starred, riding, quieted, rides, nearby) })
    }
    // A map laid out for this route data and this status, for starred or ridden stops since changed
    // standing in until the new one is in; never one laid out before the data came, which would read
    // "no map" for a moment, nor for another status.
    val current = laid?.takeIf { it.from === sequence && Inputs.same(it.statusKey, statusKey) }
    val viewSlot = remember { mutableStateOf<Worked<Inputs, LineMapUi.Ready?>?>(null) }
    val map = current?.map
    val positions = current?.from?.stopPositions.orEmpty()
    val ready = rememberWorked(viewSlot, Inputs(map, opened, all, statusKey), keep = { _, _ -> true }) {
        map?.let { LineMapUi.Ready(it, it.folded(opened?.keys().orEmpty(), all), it.foldable(), statusKey, positions) }
    }
    return when {
        source is LineMapUi -> source as LineMapUi
        sequence == null || current == null -> LineMapUi.Loading
        map == null -> LineMapUi.Unavailable
        else -> ready?.takeIf { Inputs.same(it.statusKey, statusKey) } ?: LineMapUi.Loading
    }
}

/**
 * The folds opened on a line's map, the last opened first: a tap adds one link, the same work however
 * many are open (Codex, #606), and the worker reads them into a set ([keys]).
 */
internal class OpenedFolds(val key: String, val rest: OpenedFolds?) {
    @WorkerThread
    fun keys(): Set<String> {
        val keys = HashSet<String>()
        var link: OpenedFolds? = this
        while (link != null) {
            keys += link.key
            link = link.rest
        }
        return keys
    }
}

// Kept as their keys when the page's state is saved, as the activity stops, never on a tap.
private val OpenedFoldsSaver = Saver<OpenedFolds?, ArrayList<String>>(
    save = { folds ->
        folds?.let {
            val keys = ArrayList<String>()
            var link: OpenedFolds? = it
            while (link != null) {
                keys += link.key
                link = link.rest
            }
            keys
        }
    },
    restore = { keys -> keys.asReversed().fold(null as OpenedFolds?) { rest, key -> OpenedFolds(key, rest) } },
)

/**
 * The line's map on its page as [rememberLineMapSection] has it: what [rememberLineMap] gives, whether
 * it's folded as it first opened ([atFirst]), and what the page does when a fold is opened ([open]),
 * every station shown or folded back ([toggleAll]), or a failed load tried again ([retry]).
 */
internal class LineMapSectionState(
    val ui: LineMapUi,
    val atFirst: Boolean,
    val open: (String) -> Unit,
    val toggleAll: () -> Unit,
    val retry: () -> Unit,
)

/**
 * The line's map on its page (SPEC *Line page → Map*), under its status and reason: every line has
 * one, disrupted or not. With an alert placed on it the page opens on where the alert is, the rest of
 * the line folded into the ends it leads to; with good service, on the line's ends and junctions. Null
 * where there's no route data to be had (none wired, as in a test of the page alone): no map at all.
 */
@Composable
internal fun rememberLineMapSection(line: TripLine, starred: Set<String>): LineMapSectionState? {
    val leg = line.leg
    var retry by remember(leg.lineId) { mutableIntStateOf(0) }
    var opened by rememberSaveable(leg.lineId, stateSaver = OpenedFoldsSaver) { mutableStateOf<OpenedFolds?>(null) }
    var all by rememberSaveable(leg.lineId) { mutableStateOf(false) }
    val ui = rememberLineMap(leg.lineId, line.status, starred, line.riding, opened, all, retry, line.mapKey ?: line.status, line.quieted, line.rides, line.nearby)
    val atFirst = opened == null && !all
    return if (ui == null) {
        null
    } else {
        LineMapSectionState(
            ui,
            atFirst,
            open = { key -> opened = OpenedFolds(key, opened) },
            toggleAll = {
                if (atFirst) {
                    all = true
                } else {
                    all = false
                    opened = null
                }
            },
            retry = { retry++ },
        )
    }
}

/**
 * [state]'s map as items of the page's list, one a station or fold, so only those on screen are drawn,
 * however long the line ("Show all stations" on a long National Rail line). A fold opens where it is
 * tapped, below what's above it, which doesn't move.
 */
internal fun LazyListScope.lineMapSection(state: LineMapSectionState, railColor: Color) {
    val ui = state.ui
    item(key = "lineMap") {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("lineMap")) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(
                    stringResource(R.string.line_map_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (ui is LineMapUi.Ready && ui.foldable) {
                    TextButton(onClick = state.toggleAll) {
                        Text(stringResource(if (state.atFirst) R.string.line_map_show_all else R.string.line_map_fold))
                    }
                }
            }
        }
    }
    when (ui) {
        LineMapUi.Loading -> item(key = "lineMapLoading") { LineMapNote(stringResource(R.string.line_map_loading)) }
        LineMapUi.Unavailable -> item(key = "lineMapUnavailable") { LineMapNote(stringResource(R.string.line_map_unavailable)) }
        is LineMapUi.Failed -> item(key = "lineMapFailed") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(lineMapFailureMessage(ui.kind)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = state.retry) { Text(stringResource(R.string.route_stops_retry)) }
            }
        }
        is LineMapUi.Ready -> {
            // The closure shown can't be put on the map: said, so the map isn't read as unaffected.
            if (!ui.map.closurePlaced) item(key = "lineMapNotPlaced") { LineMapNote(stringResource(R.string.line_map_closure_not_placed)) }
            items(ui.items, key = { "lineMap:${it.key}" }) { item ->
                when (item) {
                    is LineMap.Item.Station -> StationRow(item.row, ui.map.columns, railColor, ui.positions[item.row.stopId])
                    is LineMap.Item.Fold -> FoldRow(item, ui.map.columns, railColor) { state.open(item.key) }
                }
            }
        }
    }
}

@Composable
private fun LineMapNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The line's own color on the page, nudged to stand off it (a black Northern rail in dark theme), as the route page's rail. */
@Composable
internal fun lineRailColor(lineId: String, mode: String, lineName: String): Color {
    val surface = MaterialTheme.colorScheme.surface
    return lineAccentColor(lineId, mode, lineName)?.let { accentEdgeOn(it, surface) } ?: MaterialTheme.colorScheme.outline
}

// Each branch's column, a station dot's size and a rail's width, as the route page draws its rail.
private val COLUMN_PITCH = 24.dp
private val DOT_RADIUS = 6.dp
private val RAIL_WIDTH = 4.dp

// The first rail's middle: its edge 16dp in, as the last rail's edge is 16dp from the names.
private val COLUMN_INSET = 16.dp + RAIL_WIDTH / 2

private fun gutterWidth(columns: Int) = COLUMN_PITCH * columns + 12.dp

private fun DrawScope.columnX(column: Int): Float = (COLUMN_INSET + COLUMN_PITCH * column).toPx()

// A closed track's dashes, in the error color: the closure the alert names, drawn where it is.
private fun DrawScope.closedDashes() = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))

private fun DrawScope.drawRails(rails: List<LineMap.Rail>, top: Boolean, railColor: Color, closedColor: Color) {
    val middle = size.height / 2
    val y0 = if (top) 0f else middle
    val y1 = if (top) middle else size.height
    for (rail in rails) {
        val from = columnX(rail.from)
        val to = columnX(rail.to)
        val path = Path().apply {
            moveTo(from, y0)
            if (from == to) {
                lineTo(to, y1)
            } else {
                // Leaves and arrives upright, as a TfL diagram's branches do.
                val bend = (y1 - y0) / 2
                cubicTo(from, y0 + bend, to, y1 - bend, to, y1)
            }
        }
        drawPath(
            path,
            if (rail.closed) closedColor else railColor,
            style = Stroke(RAIL_WIDTH.toPx(), pathEffect = if (rail.closed) closedDashes() else null),
        )
    }
}

/**
 * Opens a station tapped on a line's map: its stop id and name. Provided where the page offers a stop's
 * details (a line page opened from *Lines…*, SPEC *Finding a line*); null leaves the map's stations inert.
 */
val LocalOpenLineMapStop = compositionLocalOf<((stopId: String, name: String, position: Pair<Double, Double>?) -> Unit)?> { null }

@Composable
private fun StationRow(row: LineMap.Row, columns: Int, railColor: Color, position: Pair<Double, Double>?) {
    val openStop = LocalOpenLineMapStop.current
    val openLabel = stringResource(R.string.line_stop_open)
    val surface = MaterialTheme.colorScheme.surface
    val closedColor = MaterialTheme.colorScheme.error
    val starColor = MaterialTheme.colorScheme.primary
    // What the drawing says, said to a screen reader too: the rider's stop, a star, the alert.
    val state = listOfNotNull(
        stringResource(R.string.route_stop_current).takeIf { row.riding },
        stringResource(R.string.line_map_starred).takeIf { row.starred },
        stringResource(R.string.line_map_nearest).takeIf { row.nearby && !row.riding },
        stringResource(R.string.route_stop_in_alert).takeIf { row.marked },
        // A closed track drawn to it, where its own line says nothing (Codex, #606).
        stringResource(R.string.line_map_beside_closure).takeIf { row.besideClosure },
    ).joinToString(", ")
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(
                if (openStop != null && row.stopId.isNotBlank()) {
                    Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { openStop(row.stopId, row.name, position) }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { if (state.isNotEmpty()) stateDescription = state },
    ) {
        Canvas(Modifier.width(gutterWidth(columns)).fillMaxHeight()) {
            drawRails(row.top, top = true, railColor, closedColor)
            drawRails(row.bottom, top = false, railColor, closedColor)
            val center = Offset(columnX(row.column), size.height / 2)
            val radius = DOT_RADIUS.toPx()
            if (row.riding) {
                // The rider's own stop: the route page's blue "you are here" dot, ringed off the rail.
                drawCircle(surface, radius + 2.dp.toPx(), center)
                drawCircle(CurrentStopBlue, radius, center)
            } else {
                drawCircle(surface, radius, center)
                drawCircle(if (row.unserved) closedColor else railColor, radius - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
        }
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 4.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append(row.name)
                    // The same glyphs and colors as the route page's stop list.
                    if (row.marked) withStyle(SpanStyle(color = closedColor)) { append(" ⚠") }
                    if (row.starred) withStyle(SpanStyle(color = starColor)) { append(" ★") }
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.end) FontWeight.SemiBold else FontWeight.Normal,
            )
            when {
                row.unserved -> Text(stringResource(R.string.line_map_no_service), style = MaterialTheme.typography.bodySmall, color = closedColor)
                row.servedOneWay -> Text(stringResource(R.string.line_map_no_service_one_way), style = MaterialTheme.typography.bodySmall, color = closedColor)
            }
            // Why it's on the page when the stations around it fold, under its service where that's shut.
            if (row.nearby && !row.riding) {
                Text(
                    stringResource(R.string.line_map_nearest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FoldRow(fold: LineMap.Item.Fold, columns: Int, railColor: Color, onOpen: () -> Unit) {
    val closedColor = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val stations = pluralStringResource(R.plurals.line_map_stations, fold.count, fold.count)
    // Where a stretch leads, by the line's ends folded into it; else its first and last stations, or the
    // one it holds. An alert's own stretch off the rider's, how many it holds: its every name is one the
    // alert affects (maintainer, 2026-10-06).
    val title = when {
        fold.unnamed -> stations
        fold.section && fold.ends.isNotEmpty() -> fold.endsText
        fold.count == 1 -> fold.first
        else -> stringResource(R.string.line_map_run, fold.first, fold.last)
    }
    val clickLabel = pluralStringResource(R.plurals.line_map_show, fold.count, fold.count)
    // How bad an alert folded into it is, without naming where (maintainer, 2026-10-06): no service, or a
    // station an alert names, as the route page marks one.
    val (glyph, state) = when (fold.level) {
        LineMap.Level.CLOSURE -> "\u26D4" to stringResource(R.string.line_map_fold_closure)
        LineMap.Level.WARNING -> "\u26A0" to stringResource(R.string.line_map_fold_alert)
        null -> null to null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onOpen)
            .semantics { if (state != null) stateDescription = state },
    ) {
        Canvas(Modifier.width(gutterWidth(columns)).fillMaxHeight()) {
            val inset = 12.dp.toPx()
            for (rail in fold.rails) {
                val x = columnX(rail.column)
                val y0 = if (rail.fromTop) 0f else inset
                val y1 = if (rail.toBottom) size.height else size.height - inset
                when {
                    // The stations folded in: a dotted rail, a dot a station's worth apart.
                    rail.folded -> drawLine(
                        if (rail.closed) closedColor else railColor, Offset(x, y0), Offset(x, y1), RAIL_WIDTH.toPx(),
                        cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 8.dp.toPx())),
                    )
                    else -> drawLine(
                        if (rail.closed) closedColor else railColor, Offset(x, y0), Offset(x, y1), RAIL_WIDTH.toPx(),
                        pathEffect = if (rail.closed) closedDashes() else null,
                    )
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).padding(vertical = 8.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append(title)
                    if (glyph != null) withStyle(SpanStyle(color = closedColor)) { append(" $glyph") }
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (!fold.unnamed) Text(stations, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        // Read out by the row's click label.
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = muted, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

private fun lineMapFailureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.line_map_failed_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.line_map_failed_rate_limited
    DeparturesUiState.Error.Kind.NETWORK, DeparturesUiState.Error.Kind.SERVER -> R.string.line_map_failed_unreachable
    DeparturesUiState.Error.Kind.KEY_REJECTED -> R.string.line_map_failed_key_rejected
}
