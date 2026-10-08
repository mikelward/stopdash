package app.stopdash.ui

import androidx.compose.foundation.layout.fillMaxSize
import app.stopdash.domain.PlannedAlert
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.PathMeasure
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
import app.stopdash.domain.Coordinates
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.NearestStops
import app.stopdash.domain.StopDistance
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
        // How far the rider is from each station marked nearest, where their position is known
        // ([LocalRiderPosition]): worked out with the folding, so a row only reads it.
        val nearestMeters: Map<String, Double> = emptyMap(),
    ) : LineMapUi
}

// The map laid out from [from] for the status [statusKey] stands for, or none where it can't be: kept
// apart from "not laid out yet" (null).
internal class Laid(val from: LineSequence?, val statusKey: Any?, val map: LineMap?)

/**
 * The rider's last near-me fix, provided app-wide: a line's map says how far its nearest stop is, and where
 * no near-me list chose that stop (*Lines…*, a trip, a departure) marks the line's own stop nearest the fix,
 * however far ([LineMap.of]). Null marks none.
 */
internal val LocalRiderPosition = compositionLocalOf<Coordinates?> { null }

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
    // The line's work still to come: each closure's stations marked from the day it starts ([LineMap.forStatus]).
    upcoming: List<PlannedAlert> = emptyList(),
    // Marked instead of [upcoming] where given: the work to come as a page last showed it, read on the worker.
    upcomingHeld: SavedPlanned? = null,
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
    // Kept above the page where it's given one ([LocalLineMapWork]), so a page come back draws its map at once.
    val held = LocalLineMapWork.current
    val here = LocalRiderPosition.current
    val ownLaid = remember { mutableStateOf<Worked<Inputs, Laid>?>(null) }
    val laidSlot = held?.laid ?: ownLaid
    val laid = rememberWorked(laidSlot, Inputs(sequence, status, statusKey, starred, riding, quieted, rides, nearby, here, upcoming, upcomingHeld), keep = { _, _ -> true }) {
        Laid(
            sequence,
            statusKey,
            // No near-me list's pick: the stop the map draws nearest the rider's fix, however far.
            sequence?.let { LineMap.forStatus(it, status, starred, riding, quieted, rides, nearby, here, upcomingHeld?.alerts() ?: upcoming) },
        )
    }
    // A map laid out for this route data and this status, for starred or ridden stops since changed
    // standing in until the new one is in; never one laid out before the data came, which would read
    // "no map" for a moment, nor for another status.
    val current = laid?.takeIf { it.from === sequence && Inputs.same(it.statusKey, statusKey) }
    val ownView = remember { mutableStateOf<Worked<Inputs, LineMapUi.Ready?>?>(null) }
    val viewSlot = held?.view ?: ownView
    val map = current?.map
    val positions = current?.from?.stopPositions.orEmpty()
    val ready = rememberWorked(viewSlot, Inputs(map, opened, all, statusKey, here), keep = { _, _ -> true }) {
        map?.let {
            val nearestMeters = if (here == null) {
                emptyMap()
            } else {
                it.rows.filter { row -> row.nearby }.mapNotNull { row ->
                    positions[row.stopId]?.let { (lat, lon) -> row.stopId to NearestStops.distanceMeters(here.latitude, here.longitude, lat, lon) }
                }.toMap()
            }
            LineMapUi.Ready(it, it.folded(opened?.keys().orEmpty(), all), it.foldable(), statusKey, positions, nearestMeters)
        }
    }
    return when {
        source is LineMapUi -> source as LineMapUi
        sequence == null || current == null -> LineMapUi.Loading
        map == null -> LineMapUi.Unavailable
        else -> ready?.takeIf { Inputs.same(it.statusKey, statusKey) } ?: LineMapUi.Loading
    }
}

/**
 * A line map's work ([rememberLineMap]: the map laid out, then folded for the page) kept by whoever shows
 * the page, where the page itself can leave composition and come back: Lines… under From's station page.
 * Its first frame back is the map as it was, so its saved scroll lands where it was left (Codex on #659).
 */
internal class LineMapWork(
    opened: OpenedFolds? = null,
    all: Boolean = false,
    list: LazyListState = LazyListState(),
    // Where a restored page was scrolled to (item, offset), applied once its map is laid out again: the list
    // starts short (the line, then "Loading"), and a position past its end would be lost (Codex on #679).
    internal var pendingScroll: Pair<Int, Int>? = null,
) {
    internal val laid = mutableStateOf<Worked<Inputs, Laid>?>(null)
    internal val view = mutableStateOf<Worked<Inputs, LineMapUi.Ready?>?>(null)

    // The folds opened and "Show all stations", and where the page was scrolled to: the page is a dialog
    // window of its own, whose saved state doesn't outlive it, so these are kept here too, and saved with
    // whoever keeps this ([LinePageWorkHolder]) for a rotation or the process coming back.
    internal val opened = mutableStateOf(opened)
    internal val all = mutableStateOf(all)
    internal val list = list

    /** Scrolls to where a restored page was, once its map is in ([pendingScroll]); a read, then a request. */
    internal fun restoreScroll() {
        val (index, offset) = pendingScroll ?: return
        pendingScroll = null
        list.requestScrollToItem(index, offset)
    }
}

/** The [LineMapWork] the line map below keeps its work in; null keeps its own. */
internal val LocalLineMapWork = compositionLocalOf<LineMapWork?> { null }

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
internal val OpenedFoldsSaver = Saver<OpenedFolds?, ArrayList<String>>(
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
internal fun rememberLineMapSection(line: TripLine, starred: Set<String>, upcomingHeld: SavedPlanned? = null): LineMapSectionState? {
    val leg = line.leg
    var retry by remember(leg.lineId) { mutableIntStateOf(0) }
    // Kept above the page where it's given somewhere to keep them ([LocalLineMapWork]), else saved here.
    val held = LocalLineMapWork.current
    val ownOpened = rememberSaveable(leg.lineId, stateSaver = OpenedFoldsSaver) { mutableStateOf<OpenedFolds?>(null) }
    val ownAll = rememberSaveable(leg.lineId) { mutableStateOf(false) }
    var opened by (held?.opened ?: ownOpened)
    var all by (held?.all ?: ownAll)
    // The work to come marks the map but isn't what it's keyed by: as it comes in or changes, the map laid out
    // before stands in until the marks are worked in, never a loading note in its place (Codex, #707). While the
    // page works its week ahead in again, the work to come as last shown ([upcomingHeld]).
    val ui = rememberLineMap(leg.lineId, line.status, starred, line.riding, opened, all, retry, line.mapKey ?: line.status, line.quieted, line.rides, line.nearby, line.planned, upcomingHeld)
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
                    is LineMap.Item.Station -> StationRow(item.row, ui.map.columns, railColor, ui.positions[item.row.stopId], ui.nearestMeters[item.row.stopId])
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

// A one-way track's arrow: a head twice the rail's width across and a tail down the rail's middle, in the
// inverse surface color so it stands out from a rail in the line's own color (maintainer, 2026-10-08).
private val ARROW_LENGTH = 8.dp
private val ARROW_WIDTH = 12.dp
private val ARROW_TAIL_LENGTH = 12.dp
private val ARROW_TAIL_WIDTH = 2.dp

// The least space between two arrows on one fold rail, one each way.
private val ARROW_GAP = 8.dp

// The no-entry sign across, its 2dp surface ring included.
internal val NO_ENTRY_SIZE = 20.dp

// The shortest rail a no-entry sign and its one-way arrows fit on apart ([foldArrowsWithSign]); a fold
// row with the sign is held at least this tall plus its rail's insets.
internal val SIGN_RAIL_MIN = 40.dp

// How far a fold rail that ends in the row stops short of the row's edge.
private val FOLD_RAIL_INSET = 12.dp

/** Where a fold rail [length] long draws its arrows, as fractions along it, and whether they keep their tails. */
internal data class FoldArrows(val downAt: Float, val upAt: Float, val tails: Boolean)

/**
 * A fold rail's arrows ([LineMap.FoldRail]'s one-way flags), a [whole] arrow long each: one alone at a third
 * (down) or two thirds (up), whole. [both] ways on one rail spread to a quarter and three quarters where the
 * two fit whole with a [gap] between, else are drawn as heads alone at a third and two thirds, so the two
 * never overlap.
 */
internal fun foldArrows(length: Float, both: Boolean, whole: Float, gap: Float): FoldArrows {
    val roomy = length / 2 >= whole + gap
    return if (both && roomy) FoldArrows(1f / 4, 3f / 4, tails = true) else FoldArrows(1f / 3, 2f / 3, tails = !both || roomy)
}

/**
 * A fold rail's arrows and its no-entry sign together (Codex, #706), the sign's place as a fraction along
 * the rail. An arrow alone moves out to a quarter from its end and the sign takes the third it leaves
 * free; arrows both ways go out to an eighth from each end with the sign between. Arrows keep their tails
 * only where the rail is [roomy] enough for a whole arrow beside the sign, else are drawn as heads alone.
 */
internal fun foldArrowsWithSign(length: Float, down: Boolean, up: Boolean, whole: Float, gap: Float): Pair<FoldArrows, Float> {
    val roomy = length >= 2 * whole + gap * 2
    return when {
        down && up -> FoldArrows(1f / 8, 7f / 8, tails = false) to 1f / 2
        down -> FoldArrows(1f / 4, 3f / 4, tails = roomy) to 2f / 3
        up -> FoldArrows(1f / 4, 3f / 4, tails = roomy) to 1f / 3
        else -> foldArrows(length, both = false, whole, gap) to 1f / 2
    }
}

// The first rail's middle: its edge 16dp in, as the last rail's edge is 16dp from the names.
private val COLUMN_INSET = 16.dp + RAIL_WIDTH / 2

private fun gutterWidth(columns: Int) = COLUMN_PITCH * columns + 12.dp

private fun DrawScope.columnX(column: Int): Float = (COLUMN_INSET + COLUMN_PITCH * column).toPx()

// A closed track's dashes, in the error color: the closure the alert names, drawn where it is.
private fun DrawScope.closedDashes() = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))

private fun DrawScope.drawRails(rails: List<LineMap.Rail>, top: Boolean, railColor: Color, closedColor: Color, arrowColor: Color) {
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
        val color = if (rail.closed) closedColor else railColor
        drawPath(path, color, style = Stroke(RAIL_WIDTH.toPx(), pathEffect = if (rail.closed) closedDashes() else null))
        // A track the line runs one way only: an arrow the way it runs, once, centered where it comes into the
        // row it ends at, so head and tail sit in the gap between the two stations, clear of both their dots.
        // Drawn on the rail carried on straight up into the row above (a rail always arrives upright), whose
        // track it is too: this row is drawn after that one, so it lands on top.
        if (top && rail.arrives && rail.oneWay) {
            val reach = ARROW_TAIL_LENGTH.toPx()
            val carried = Path().apply {
                moveTo(from, y0 - reach)
                lineTo(from, y0)
                if (from == to) {
                    lineTo(to, y1)
                } else {
                    val bend = (y1 - y0) / 2
                    cubicTo(from, y0 + bend, to, y1 - bend, to, y1)
                }
            }
            drawOneWayArrow(carried, down = rail.runsDown, arrowColor, center = reach)
        }
    }
}

// An arrow centered [center] along [path] (else [along] the way), pointing the way it's drawn ([down]) or
// back up it: a head wider than the rail so it reads over it, and a tail following the rail back from it,
// curve and all, the two together the same length either way; without [tail], the head alone, centered there.
private fun DrawScope.drawOneWayArrow(
    path: Path,
    down: Boolean,
    color: Color,
    along: Float = 1f / 2,
    center: Float? = null,
    tail: Boolean = true,
) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = ARROW_LENGTH.toPx()
    val tailLength = if (tail) ARROW_TAIL_LENGTH.toPx() else 0f
    val middle = center ?: (measure.length * along)
    // The head's own middle: the arrow's front end the way it points.
    val distance = if (down) middle + tailLength / 2 else middle - tailLength / 2
    val at = measure.getPosition(distance)
    val tangent = measure.getTangent(distance)
    val (dx, dy) = if (down) tangent.x to tangent.y else -tangent.x to -tangent.y
    val halfWidth = ARROW_WIDTH.toPx() / 2
    val arrow = Path().apply {
        moveTo(at.x + dx * length / 2, at.y + dy * length / 2)
        lineTo(at.x - dx * length / 2 - dy * halfWidth, at.y - dy * length / 2 + dx * halfWidth)
        lineTo(at.x - dx * length / 2 + dy * halfWidth, at.y - dy * length / 2 - dx * halfWidth)
        close()
    }
    // The tail, from the head's base back along the rail, cut short only where a rail is too short for it.
    val base = if (down) distance - length / 2 else distance + length / 2
    val (from, to) = if (down) (base - tailLength).coerceAtLeast(0f) to base else base to (base + tailLength).coerceAtMost(measure.length)
    if (to > from) {
        val tailPath = Path()
        measure.getSegment(from, to, tailPath, true)
        drawPath(tailPath, color, style = Stroke(ARROW_TAIL_WIDTH.toPx(), cap = StrokeCap.Butt))
    }
    drawPath(arrow, color)
}

/**
 * Opens a station tapped on a line's map: its stop id and name. Provided where the page offers a stop's
 * details (a line page opened from *Lines…*, SPEC *Finding a line*); null leaves the map's stations inert.
 */
val LocalOpenLineMapStop = compositionLocalOf<((stopId: String, name: String, position: Pair<Double, Double>?) -> Unit)?> { null }

/**
 * Opens a station tapped on any line's map, by the line it was tapped on: the home screen's and a trip's line
 * pages, which have no stop details of their own, open *Lines…*'s over them (SPEC *Finding a line*). Null where
 * that can't show over the page (another screen is open above it): their stations stay inert there.
 */
val LocalOpenLineStop = compositionLocalOf<((line: LineRef, stopId: String, name: String, position: Pair<Double, Double>?) -> Unit)?> { null }

@Composable
private fun StationRow(row: LineMap.Row, columns: Int, railColor: Color, position: Pair<Double, Double>?, nearestMeters: Double?) {
    val openStop = LocalOpenLineMapStop.current
    val openLabel = stringResource(R.string.line_stop_open)
    val surface = MaterialTheme.colorScheme.surface
    val closedColor = MaterialTheme.colorScheme.error
    val arrowColor = MaterialTheme.colorScheme.inverseSurface
    val starColor = MaterialTheme.colorScheme.primary
    val system = LocalDistanceSystem.current
    // "Nearest", with how far where the rider's fix gives it, so they judge how near that is.
    val nearest = if (nearestMeters != null && system != null) {
        stringResource(R.string.line_map_nearest_at, StopDistance.label(nearestMeters, system))
    } else {
        stringResource(R.string.line_map_nearest)
    }
    // What the drawing says, said to a screen reader too: the rider's stop, a star, the alert.
    val state = listOfNotNull(
        stringResource(R.string.route_stop_current).takeIf { row.riding },
        stringResource(R.string.line_map_starred).takeIf { row.starred },
        nearest.takeIf { row.nearby },
        stringResource(R.string.route_stop_in_alert).takeIf { row.marked },
        // A closed track drawn to it, where its own line says nothing (Codex, #606).
        stringResource(R.string.line_map_beside_closure).takeIf { row.besideClosure },
        // The arrows on one-way tracks into it, said as well as drawn (Codex, #665).
        stringResource(R.string.line_map_one_way_down).takeIf { row.oneWayDown },
        stringResource(R.string.line_map_one_way_up).takeIf { row.oneWayUp },
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
            drawRails(row.top, top = true, railColor, closedColor, arrowColor)
            drawRails(row.bottom, top = false, railColor, closedColor, arrowColor)
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
            val upcoming = row.upcomingFrom
            when {
                row.unserved -> Text(stringResource(R.string.line_map_no_service), style = MaterialTheme.typography.bodySmall, color = closedColor)
                row.servedOneWay -> Text(stringResource(R.string.line_map_no_service_one_way), style = MaterialTheme.typography.bodySmall, color = closedColor)
                // A closure still to come: a calendar, as a route row marks planned work, in the muted color since
                // nothing is shut yet (maintainer, 2026-10-08).
                upcoming != null -> UpcomingClosureNote(upcoming)
            }
            // Why it's on the page when the stations around it fold, under its service where that's shut.
            if (row.nearby) {
                Text(
                    nearest,
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
    val arrowColor = MaterialTheme.colorScheme.inverseSurface
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
    // A closure is drawn on the rail as a no-entry sign and said in words under the title (maintainer,
    // 2026-10-08); an alert keeps its glyph after the title, as a station row marks one.
    val closure = fold.level == LineMap.Level.CLOSURE
    val closureText = stringResource(R.string.line_map_no_service)
    val warning = fold.level == LineMap.Level.WARNING
    // A closure still to come at a station still served: a calendar after the title, beside an alert's glyph where
    // there's one too, as they're separate facts (Codex, #707).
    val upcoming = fold.upcoming
    val surface = MaterialTheme.colorScheme.surface
    val signBar = MaterialTheme.colorScheme.onError
    // The arrows on one-way tracks folded in, said as well as drawn (Codex, #665). The closure is read
    // out as the row's own text.
    val state = listOfNotNull(
        stringResource(R.string.line_map_fold_alert).takeIf { warning },
        stringResource(R.string.line_map_fold_upcoming).takeIf { upcoming },
        stringResource(R.string.line_map_one_way_down).takeIf { fold.oneWayDown },
        stringResource(R.string.line_map_one_way_up).takeIf { fold.oneWayUp },
    ).joinToString(", ").ifEmpty { null }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onOpen)
            .semantics { if (state != null) stateDescription = state },
    ) {
        // Room on the sign's rail for it and its arrows beside it, whatever the font scale (Codex, #706).
        val signRail = fold.signRail
        val minHeight = if (signRail == null) 0.dp else
            SIGN_RAIL_MIN + FOLD_RAIL_INSET * ((if (signRail.fromTop) 0 else 1) + (if (signRail.toBottom) 0 else 1))
        Canvas(Modifier.width(gutterWidth(columns)).fillMaxHeight().heightIn(min = minHeight)) {
            val inset = FOLD_RAIL_INSET.toPx()
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
                // One-way tracks folded in: their arrows on the fold's rail, as the rows they end at would draw
                // them, one each way where they disagree.
                val whole = (ARROW_LENGTH + ARROW_TAIL_LENGTH).toPx()
                val gap = ARROW_GAP.toPx()
                val (placed, signAt) = if (rail === signRail) {
                    foldArrowsWithSign(y1 - y0, rail.oneWayDown, rail.oneWayUp, whole, gap)
                } else {
                    foldArrows(y1 - y0, both = rail.oneWayDown && rail.oneWayUp, whole, gap) to null
                }
                if (rail.oneWayDown || rail.oneWayUp) {
                    val path = Path().apply { moveTo(x, y0); lineTo(x, y1) }
                    if (rail.oneWayDown) drawOneWayArrow(path, down = true, arrowColor, along = placed.downAt, tail = placed.tails)
                    if (rail.oneWayUp) drawOneWayArrow(path, down = false, arrowColor, along = placed.upAt, tail = placed.tails)
                }
                if (signAt != null) drawNoEntry(Offset(x, y0 + (y1 - y0) * signAt), closedColor, signBar, surface)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).padding(vertical = 8.dp),
        ) {
            // A closure still to come in it: a calendar, in the muted color.
            Text(
                buildAnnotatedString {
                    append(title)
                    if (warning) withStyle(SpanStyle(color = closedColor)) { append(" \u26A0") }
                    if (upcoming) {
                        append(" ")
                        appendInlineContent(CALENDAR_INLINE, "\uD83D\uDCC5")
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                inlineContent = if (upcoming) calendarInline(muted) else emptyMap(),
            )
            if (!fold.unnamed || closure) {
                Text(
                    buildAnnotatedString {
                        if (!fold.unnamed) append(stations)
                        if (!fold.unnamed && closure) append(" · ")
                        if (closure) withStyle(SpanStyle(color = closedColor)) { append(closureText) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
        }
        // Read out by the row's click label.
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = muted, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

// A no-entry sign: a filled disc with a bar across, ringed in [surface] so the rail stops short of it.
private fun DrawScope.drawNoEntry(center: Offset, color: Color, bar: Color, surface: Color) {
    val radius = (NO_ENTRY_SIZE / 2 - 2.dp).toPx()
    drawCircle(surface, (NO_ENTRY_SIZE / 2).toPx(), center)
    drawCircle(color, radius, center)
    val half = radius * 0.6f
    drawLine(bar, Offset(center.x - half, center.y), Offset(center.x + half, center.y), 2.5.dp.toPx(), cap = StrokeCap.Round)
}

private fun lineMapFailureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.line_map_failed_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.line_map_failed_rate_limited
    DeparturesUiState.Error.Kind.NETWORK, DeparturesUiState.Error.Kind.SERVER -> R.string.line_map_failed_unreachable
    DeparturesUiState.Error.Kind.KEY_REJECTED -> R.string.line_map_failed_key_rejected
}

// The key of the calendar drawn in a line of text ([calendarInline]).
private const val CALENDAR_INLINE = "calendar"

/** A calendar the height of the text it sits in, for [CALENDAR_INLINE], in [tint]; said by the row, not itself. */
private fun calendarInline(tint: Color) = mapOf(
    CALENDAR_INLINE to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
        Icon(CalendarIcon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
    },
)

/**
 * Under a station a closure still to come will shut: a calendar and "No service from 10 Oct", muted, as nothing
 * is shut yet and the station's rails are drawn as they run today.
 */
@Composable
private fun UpcomingClosureNote(from: java.time.LocalDate) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            appendInlineContent(CALENDAR_INLINE, "\uD83D\uDCC5")
            append(" ")
            append(stringResource(R.string.line_map_no_service_from, plannedDay(from)))
        },
        style = MaterialTheme.typography.bodySmall,
        color = muted,
        inlineContent = calendarInline(muted),
    )
}
