package app.stopdash.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.stopdash.R
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.RouteStop
import app.stopdash.domain.RouteStops
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.TflException
import kotlinx.coroutines.CancellationException

/**
 * The route stop lists, provided once at the composition root ([app.stopdash.MainActivity]).
 * Null (the default) hides the stop list — a screenshot test or preview makes no network call.
 */
val LocalRouteStops = staticCompositionLocalOf<RouteStopsRepository?> { null }

/** What the route detail's stop list shows. */
sealed interface RouteStopsUi {
    /** No list: a status row (no train to follow), or no source wired. */
    data object Hidden : RouteStopsUi
    data object Loading : RouteStopsUi

    /**
     * The row's snapshot is stale: its soonest prediction may no longer be the next train, so its
     * train-specific stop list is withheld until fresh departures arrive (SPEC D4).
     */
    data object Stale : RouteStopsUi

    /** TfL answered, but no single path from here to this train's destination matched. */
    data class Unavailable(val reason: RouteStops.Resolution) : RouteStopsUi
    data class Failed(val kind: DeparturesUiState.Error.Kind) : RouteStopsUi
    // [positions]: each listed stop's published (latitude, longitude), for starring a journey;
    // [sequence]: the route they came from, which places a saved journey's stops on this page (a
    // bus's way back uses the poles across the road).
    data class Loaded(
        val stops: List<RouteStop>,
        val positions: Map<String, Pair<Double, Double>> = emptyMap(),
        val sequence: LineSequence? = null,
    ) : RouteStopsUi
}

/**
 * The stop list for the [next] departure on [row] (see [followedDeparture]): from the boarding stop
 * through that train's destination. Rendered at once from the in-memory cache when this line was
 * already fetched this process, else [RouteStopsUi.Loading] while it fetches off the render path
 * (SPEC D8, route detail). [retry] bumps to refetch after a failure.
 */
@Composable
internal fun rememberRouteStops(row: DepartureRow, next: Departure?, retry: Int): RouteStopsUi {
    val repository = LocalRouteStops.current
    if (repository == null || next == null || row.lineId.isBlank()) return RouteStopsUi.Hidden
    val destination = next.destination
    // The mode from any departure when TfL left it off the soonest one, so a bus blind that names no
    // stop still gets the bus rule (and a stop list to star from).
    val mode = row.mode.ifBlank { row.upcoming.firstOrNull { it.mode.isNotBlank() }?.mode.orEmpty() }
    val bus = mode.equals("bus", ignoreCase = true)
    // A station whose departures TfL lists under an id its routes don't call at boards at its sibling.
    fun resolve(fetched: LineSequence): RouteStopsUi {
        val sequence = fetched.callingAt(row.stopId)
        return when (val resolution = RouteStops.resolve(sequence, row.stopId, destination, next.branch, row.lineId, bus, RouteStops.boundOf(next.platform), next.direction)) {
            is RouteStops.Resolution.Found -> RouteStopsUi.Loaded(
                resolution.stops,
                resolution.stops.mapNotNull { stop -> sequence.stopPositions[stop.id]?.let { stop.id to it } }.toMap(),
                sequence,
            )
            else -> RouteStopsUi.Unavailable(resolution)
        }
    }
    // Keyed by the followed train (and the mode, which changes the matching rule), so a change of
    // soonest train (a refresh, or one departing) discards the old state outright: the first frame
    // for the new train is its cached list or Loading, never the previous train's stops.
    return key(repository, row.lineId, row.direction, row.stopId, destination, next.branch, next.platform, next.direction, bus) {
        val initial = remember { repository.cached(row.lineId, row.direction)?.let(::resolve) ?: RouteStopsUi.Loading }
        val state by produceState(initial, retry) {
            if (value !is RouteStopsUi.Loading && value !is RouteStopsUi.Failed) return@produceState
            value = RouteStopsUi.Loading
            value = try {
                resolve(repository.load(row.lineId, row.direction))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // TfL has no route for this line: unavailable, with no retry that could never work.
                RouteStopsUi.Unavailable(RouteStops.Resolution.UnknownLine)
            } catch (e: TflException) {
                // Already logged (sanitized) by the repository; surfaced here with its reason.
                RouteStopsUi.Failed(
                    errorKindOf(e),
                )
            }
        }
        // Logged once per followed train, off composition: the page itself only says "unavailable".
        LaunchedEffect(state) {
            (state as? RouteStopsUi.Unavailable)?.let { repository.reportUnresolved(row.lineId, row.stopId, it.reason) }
        }
        state
    }
}

/**
 * Every station on [lineId] in both directions, in route order, for a page with no stop list of its
 * own (a status row: no train to follow). Used only to name the stations a line's alert mentions
 * beside its chip (SPEC *Disruptions*), so it is empty until loaded, when not [wanted], and on a
 * failure: a missing name costs nothing the alert's own prose doesn't already say. Rendered from
 * the in-memory cache when this line was already fetched, else loaded off the render path.
 */
@Composable
internal fun rememberLineStops(lineId: String, wanted: Boolean): List<RouteStop> {
    val repository = LocalRouteStops.current
    if (repository == null || !wanted || lineId.isBlank()) return emptyList()
    fun stopsOf(sequence: LineSequence): List<RouteStop> =
        sequence.routes.flatMap { it.stopIds }.distinct()
            .map { id -> RouteStop(id, sequence.stopNames[id].orEmpty()) }
    return key(repository, lineId) {
        val initial = remember { repository.cached(lineId, "")?.let(::stopsOf) }
        val state by produceState(initial, lineId) {
            if (value != null) return@produceState
            value = try {
                stopsOf(repository.load(lineId, ""))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                // Already logged (sanitized) by the repository; the page just names no stations.
                emptyList()
            }
        }
        state.orEmpty()
    }
}

/**
 * The route detail's stop list: every station from the boarding stop to the train's
 * destination on a rail in the line's [railColor] — the boarding stop a blue "you are here" dot, every other stop
 * hollow, the boarding stop and terminus named in bold —
 * headed by the [direction] the train runs when known.
 */
@Composable
internal fun RouteStopsSection(
    state: RouteStopsUi,
    railColor: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    // The way the train heads ("Southbound"), shown atop the list; null shows no heading.
    direction: String? = null,
    // Stations (after the boarding stop) with a starred journey from here; each shows a star.
    starredStopIds: Set<String> = emptySet(),
    // Stations the line's service alert names ([app.stopdash.domain.AlertStops]); each shows a ⚠.
    alertStopIds: Set<String> = emptySet(),
    // Each station's step-free level for this line (SPEC *Step-free access*); a station marked
    // step-free shows TfL's symbol, and one missing or not step-free shows none.
    stepFree: Map<String, StepFreeLevel> = emptyMap(),
    // Stars or unstars the journey from the boarding stop to a tapped station (SPEC *Journeys*);
    // null leaves the stations inert.
    onToggleJourneyTo: ((RouteStop) -> Unit)? = null,
    // Dismisses the tip atop a starrable list that tapping a stop stars a journey; null shows none
    // (already dismissed, or not yet known to be undismissed).
    onDismissJourneyTip: (() -> Unit)? = null,
) {
    val note = when (state) {
        RouteStopsUi.Hidden -> return
        RouteStopsUi.Loading -> stringResource(R.string.route_stops_loading)
        is RouteStopsUi.Unavailable -> stringResource(R.string.route_stops_unavailable)
        RouteStopsUi.Stale -> stringResource(R.string.route_stops_stale)
        is RouteStopsUi.Failed -> stringResource(routeStopsFailureMessage(state.kind))
        is RouteStopsUi.Loaded -> null
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (state is RouteStopsUi.Loaded) {
            if (onToggleJourneyTo != null && onDismissJourneyTip != null) {
                JourneyTip(onDismiss = onDismissJourneyTip, modifier = Modifier.padding(bottom = 12.dp))
            }
            direction?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            state.stops.forEachIndexed { index, stop ->
                StopOnRail(
                    name = stop.name.ifBlank { stop.id },
                    connections = stop.connections,
                    railColor = railColor,
                    first = index == 0,
                    last = index == state.stops.lastIndex,
                    starred = index > 0 && stop.id in starredStopIds,
                    inAlert = stop.id in alertStopIds,
                    stepFree = stepFree[stop.id],
                    onClick = if (index > 0) onToggleJourneyTo?.let { toggle -> { toggle(stop) } } else null,
                )
            }
        } else if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state is RouteStopsUi.Failed) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                }
            }
        }
    }
}

/**
 * The one-time tip that a stop on the list can be tapped to star the journey there — the gesture has
 * no other visible cue — until the user dismisses it.
 */
@Composable
private fun JourneyTip(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.journey_tip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.journey_tip_dismiss)) }
        }
    }
}

/**
 * One station: a dot on the line-colored rail, its name, and a line pill per connection (the same
 * pill the departures list uses). The pills sit right-aligned on the name's line when the name and
 * all of them fit; otherwise the name takes its own line and the pills go together on the line(s)
 * below, right-aligned — never split around the name. The dot stays level with the name, and the
 * rail's ends stop at the dot.
 */
@Composable
private fun StopOnRail(
    name: String,
    connections: List<LineRef>,
    railColor: Color,
    first: Boolean,
    last: Boolean,
    // A starred journey ends here: the name carries a star, and the row says so to a screen reader.
    starred: Boolean = false,
    // The line's service alert names this station, or a stretch it's in: the name carries a ⚠, and
    // the row says so too.
    inAlert: Boolean = false,
    // How far the station is step-free for this line: TfL's symbol after the name ([StepFreeMark]),
    // read out as its words. None, or null, shows nothing.
    stepFree: StepFreeLevel? = null,
    // Stars or unstars the journey to this station; null leaves the row inert.
    onClick: (() -> Unit)? = null,
) {
    val surface = MaterialTheme.colorScheme.surface
    val starredLabel = stringResource(R.string.route_stop_journey_starred)
    val toggleLabel = stringResource(if (starred) R.string.action_unstar_journey else R.string.action_star_journey)
    val starColor = MaterialTheme.colorScheme.primary
    val alertColor = MaterialTheme.colorScheme.error
    val alertLabel = stringResource(R.string.route_stop_in_alert)
    val stepFreeLabel = when (stepFree) {
        StepFreeLevel.LEVEL -> stringResource(R.string.route_stop_step_free_train)
        StepFreeLevel.PLATFORM, StepFreeLevel.RAMP -> stringResource(R.string.route_stop_step_free_platform)
        StepFreeLevel.NONE, null -> null
    }
    val railStroke = Modifier.fillMaxSize()
    // The blue dot is drawn, so it says nothing to a screen reader: the boarding stop is read as one
    // node with "Your stop" as its state, so TalkBack users hear which stop is theirs too.
    val currentStop = stringResource(R.string.route_stop_current)
    Layout(
        contents = listOf(
            // The rail: the segment above the dot, the segment below it, and the dot itself —
            // separate so the layout can put the dot level with the name.
            {
                Box(railStroke.drawBehind { if (!first) drawRail(railColor) })
                Box(railStroke.drawBehind { if (!last) drawRail(railColor) })
                Box(
                    railStroke.drawBehind {
                        val center = Offset(size.width / 2, size.height / 2)
                        val radius = 6.dp.toPx()
                        // The boarding stop — where the rider is — is a blue "you are here" dot,
                        // ringed in the surface color so it stands off a blue rail (Victoria,
                        // Piccadilly). Every other stop, the terminus included, is hollow like a TfL
                        // line diagram's tick, so the one filled dot is the rider's; the terminus is
                        // marked by its bold name and the rail ending there.
                        if (first) {
                            drawCircle(surface, radius + 2.dp.toPx(), center)
                            drawCircle(CurrentStopBlue, radius, center)
                        } else {
                            drawCircle(surface, radius, center)
                            drawCircle(railColor, radius - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                        }
                    },
                )
            },
            {
                Text(
                    text = if (starred || inAlert || stepFreeLabel != null) {
                        buildAnnotatedString {
                            append(name)
                            if (stepFreeLabel != null) {
                                append(" ")
                                // The symbol's words are its text, so a screen reader reads the
                                // level where the eye sees the mark.
                                appendInlineContent(STEP_FREE_MARK, alternateText = stepFreeLabel)
                            }
                            // The same glyph and color as a disrupted departure row's warning.
                            if (inAlert) withStyle(SpanStyle(color = alertColor)) { append(" \u26A0") }
                            if (starred) withStyle(SpanStyle(color = starColor)) { append(" \u2605") }
                        }
                    } else {
                        AnnotatedString(name)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (first || last) FontWeight.SemiBold else FontWeight.Normal,
                    inlineContent = if (stepFree != null && stepFreeLabel != null) {
                        mapOf(
                            STEP_FREE_MARK to InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
                                StepFreeMark(stepFree, Modifier.fillMaxSize())
                            },
                        )
                    } else {
                        emptyMap()
                    },
                )
            },
            { connections.forEach { line -> LinePill(lineName = line.name, lineId = line.id, mode = line.mode) } },
        ),
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = toggleLabel, onClick = onClick) else Modifier)
            .then(
                // Every state that applies, so the boarding stop named in the alert says both.
                listOfNotNull(currentStop.takeIf { first }, starredLabel.takeIf { starred }, alertLabel.takeIf { inAlert })
                    .takeIf { it.isNotEmpty() }
                    ?.let { states -> Modifier.semantics(mergeDescendants = true) { stateDescription = states.joinToString(", ") } }
                    ?: Modifier,
            ),
    ) { (railParts, nameParts, pillParts), constraints ->
        val railWidth = 24.dp.roundToPx()
        val textStart = railWidth + 12.dp.roundToPx()
        // 8dp between the name and its pills; 4dp between pills, a tight group (five fit a phone row).
        val nameGap = 8.dp.roundToPx()
        val gap = 4.dp.roundToPx()
        val lineGap = 4.dp.roundToPx()
        val padV = 4.dp.roundToPx()
        val available = (constraints.maxWidth - textStart).coerceAtLeast(0)
        val pills = pillParts.map { it.measure(Constraints(maxWidth = available)) }
        val pillsWidth = pills.sumOf { it.width } + gap * (pills.size - 1).coerceAtLeast(0)
        val nameMeasurable = nameParts.single()
        val oneLine = pills.isEmpty() ||
            nameMeasurable.maxIntrinsicWidth(Constraints.Infinity) + nameGap + pillsWidth <= available
        val placements = ArrayList<Triple<Placeable, Int, Int>>()
        val height: Int
        val dotY: Int
        if (oneLine) {
            val nameWidth = if (pills.isEmpty()) available else available - pillsWidth - nameGap
            val namePlaceable = nameMeasurable.measure(Constraints(maxWidth = nameWidth.coerceAtLeast(0)))
            val lineHeight = maxOf(namePlaceable.height, pills.maxOfOrNull { it.height } ?: 0)
            height = maxOf(32.dp.roundToPx(), lineHeight + 2 * padV)
            dotY = height / 2
            placements += Triple(namePlaceable, textStart, (height - namePlaceable.height) / 2)
            var x = constraints.maxWidth - pillsWidth
            for (pill in pills) {
                placements += Triple(pill, x, (height - pill.height) / 2)
                x += pill.width + gap
            }
        } else {
            val namePlaceable = nameMeasurable.measure(Constraints(maxWidth = available))
            placements += Triple(namePlaceable, textStart, padV)
            dotY = padV + namePlaceable.height / 2
            // Greedy rows, each right-aligned, under the name.
            var y = padV + namePlaceable.height + lineGap
            var row = ArrayList<Placeable>()
            fun flush() {
                if (row.isEmpty()) return
                val rowWidth = row.sumOf { it.width } + gap * (row.size - 1)
                val rowHeight = row.maxOf { it.height }
                var x = constraints.maxWidth - rowWidth
                for (pill in row) {
                    placements += Triple(pill, x, y + (rowHeight - pill.height) / 2)
                    x += pill.width + gap
                }
                y += rowHeight + lineGap
                row = ArrayList()
            }
            for (pill in pills) {
                val rowWidth = row.sumOf { it.width } + gap * row.size
                if (row.isNotEmpty() && rowWidth + pill.width > available) flush()
                row += pill
            }
            flush()
            height = y - lineGap + padV
        }
        val (above, below, dot) = railParts
        val abovePlaceable = above.measure(Constraints.fixed(railWidth, dotY))
        val belowPlaceable = below.measure(Constraints.fixed(railWidth, height - dotY))
        val dotSize = 16.dp.roundToPx()
        val dotPlaceable = dot.measure(Constraints.fixed(railWidth, dotSize))
        layout(constraints.maxWidth, height) {
            abovePlaceable.place(0, 0)
            belowPlaceable.place(0, dotY)
            dotPlaceable.place(0, dotY - dotSize / 2)
            placements.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}

/** The inline content id of a station name's step-free mark. */
private const val STEP_FREE_MARK = "stepFree"

/** A rail segment down the middle of this box, its full height. */
private fun DrawScope.drawRail(color: Color) {
    val x = size.width / 2
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
}

private fun routeStopsFailureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.route_stops_failed_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.route_stops_failed_rate_limited
    // Wording not split yet: both still read "can't reach TfL" here.
    DeparturesUiState.Error.Kind.NETWORK, DeparturesUiState.Error.Kind.SERVER -> R.string.route_stops_failed_unreachable
    DeparturesUiState.Error.Kind.KEY_REJECTED -> R.string.route_stops_failed_key_rejected
}

/** The rail color for a row's line: the color its pill takes (TfL line, rail operator, Overground accent), kept visible on the surface, else a neutral tone. */
@Composable
internal fun railColorFor(row: DepartureRow): Color {
    val surface = MaterialTheme.colorScheme.surface
    // Nudged for contrast on the page surface, as the hollow pill's border is: a thin rail in a
    // dark line color (the Northern line's black, a dark Overground accent) would otherwise
    // vanish in dark theme.
    return lineAccentColor(row.lineId, row.mode, row.lineName)?.let { accentEdgeOn(it, surface) }
        ?: MaterialTheme.colorScheme.outline
}

/** The color a line is drawn in, in the pill's own lookup order, or null when it has none. */
internal fun lineAccentColor(lineId: String, mode: String, lineName: String): Color? =
    lineFillColor(lineId, mode)
        ?: railOperatorColor(mode, lineName, lineId)
        ?: overgroundAccentColor(lineId)

/** The boarding stop's "you are here" dot — the familiar map-location blue, the same in both themes. */
private val CurrentStopBlue = Color(0xFF1A73E8)
