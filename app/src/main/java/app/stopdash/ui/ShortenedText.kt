package app.stopdash.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.EvenShortening
import kotlin.math.roundToInt

/**
 * A place name shortened to fit (SPEC destination-label): the first of [forms], longest first, that fits
 * the width given, else the last, eliding with a single "…" if even that overflows. For a name, [forms]
 * are [DestinationAbbreviations.forms], worked out with the row it's drawn from, so only measuring
 * happens here: in full when it fits, else with common whole words shortened ("East Finchley" →
 * "E. Finchley", "Wood Lane" → "Wood Ln"), else its floor ("Battersea Power" → "Battersea P."), never
 * cut mid-glyph. Each part of a slash-separated name shortens alike, and below the floor each elides on
 * its own, so no place is lost to one trailing "…". Copy around the name ("From ‹stop›") is drawn beside
 * it, not in it, so only the name yields. The first form stays the screen-reader label when another shows.
 */
@Composable
internal fun ShortenedText(forms: List<String>, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val distinct = forms.distinct()
    val full = distinct.first()
    if (distinct.size == 1 && '/' !in full) {
        // Nothing to shorten: show it, and elide with a single "…" only if the row leaves too little room.
        // A slash-separated name is measured even so, so each place can elide on its own ([SplitName]).
        Text(text = full, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        // Key each measurement on the font scale, not the text alone: a display-size / accessibility
        // resize grows the text while the row's px width is unchanged, so a width cached on the
        // string would stay stale and the shrink never fire.
        val fontScale = LocalDensity.current.fontScale
        val widths = remember(distinct, style, fontScale) {
            distinct.map { measurer.measure(it, style, maxLines = 1).size.width }
        }
        val fits = distinct.indices.firstOrNull { widths[it] <= constraints.maxWidth }
        val display = fits?.let { distinct[it] } ?: distinct.last()
        if (fits == null && '/' in display) {
            // Even the floor overflows: a single trailing "…" would drop every place after the first,
            // so each slash-separated place takes a share of the width and elides on its own.
            SplitName(display, full, style, color)
            return@BoxWithConstraints
        }
        Text(
            text = display,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // As wide as the form shown, not the room it was chosen for, as the one-form name is: what follows it (a
            // ride's star) sits after it whichever form shows, where a filled width pushed it to the far edge.
            modifier = if (display != full) Modifier.semantics { contentDescription = full } else Modifier,
        )
    }
}

/**
 * A line naming a place amid other copy that ends it ("Alerts to ‹place›: Mon–Fri 08:00–10:00"): the
 * first of [forms] (distinct, the whole line with the place in full, then shortened, longest first) that
 * fits the width given; else [before] and [after] around as much of [name] as fits, cut with a "…" (each
 * slash-separated place on its own, as [SplitName] does); and if even the copy around it won't fit, the
 * line trimmed from its start. So the end of the line, what it
 * goes on to say after the place, is the last thing cut. The first form stays the screen-reader label
 * when another shows.
 */
@Composable
internal fun ShortenedLine(
    forms: List<String>,
    before: String,
    name: String,
    after: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val full = forms.first()
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        // Keyed on the font scale too, as in [ShortenedText].
        val fontScale = LocalDensity.current.fontScale
        val width = constraints.maxWidth
        val display = remember(forms, before, name, after, style, fontScale, width) {
            forms.firstOrNull { measurer.measure(it, style, maxLines = 1).size.width <= width }
                ?: run {
                    // The copy either side whole, and the name in what's left.
                    val room = width - measurer.measure(before + after, style, maxLines = 1).size.width
                    if (room <= 0) forms.last() else before + fitPlaces(name, room, measurer, style) + after
                }
        }
        Text(
            text = display,
            style = style,
            color = color,
            maxLines = 1,
            // Anything still too wide loses its start, never the end the line is for.
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (display != full) Modifier.semantics { contentDescription = full } else Modifier),
        )
    }
}

/**
 * [name] cut to fit [room] px, each slash-separated place on its own as [SplitName] draws them, so none
 * is lost behind the first: shortest first, a place that fits its share keeps it all, the rest sharing
 * what's left, each cut where its "…" fits.
 */
private fun fitPlaces(name: String, room: Int, measurer: TextMeasurer, style: TextStyle): String {
    fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1).size.width
    fun cut(text: String, max: Int): String {
        val layout = measurer.measure(
            text,
            style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = Constraints(maxWidth = max.coerceAtLeast(0)),
        )
        return if (!layout.isLineEllipsized(0)) text else text.take(layout.getLineEnd(0, visibleEnd = true)).trimEnd() + "…"
    }
    val places = name.split("/").map { it.trim() }
    if (places.size == 1) return cut(name, room)
    val widths = places.map(::widthOf)
    val shown = places.toMutableList()
    var left = room - widthOf("/".repeat(places.size - 1))
    places.indices.sortedBy { widths[it] }.forEachIndexed { done, i ->
        val share = left / (places.size - done)
        if (widths[i] <= share) {
            left -= widths[i]
        } else {
            shown[i] = cut(places[i], share)
            left -= share
        }
    }
    return shown.joinToString("/")
}

/**
 * [text]'s slash-separated places side by side, each eliding with its own "…" in an equal share of
 * the width, so "Shepherd's Bush Mkt/Wood Ln" reads "Shepherd's…/Wood Ln" rather than losing
 * the second place; [full] is the screen-reader label.
 */
@Composable
private fun SplitName(text: String, full: String, style: TextStyle, color: Color) {
    val parts = text.split("/")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = full },
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text(text = "/", style = style, color = color, maxLines = 1, softWrap = false)
            Text(
                text = part.trim(),
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * As [ShortenedText], over up to [maxLines] lines, shortening before it wraps (maintainer,
 * 2026-10-03): each of [forms] but the last (the floor, which drops words to initials) on one line,
 * longest first; then each of [forms] over [maxLines]; else the last, eliding with a single "…". So a
 * step's title takes its common abbreviations to stay on one line, wraps only if even those don't
 * fit, and is cut only as a last resort. The first form stays the screen-reader label when another
 * shows.
 */
@Composable
internal fun ShortenedLines(forms: List<String>, style: TextStyle, maxLines: Int, modifier: Modifier = Modifier) {
    val distinct = forms.distinct()
    val full = distinct.first()
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        val fontScale = LocalDensity.current.fontScale
        val width = constraints.maxWidth
        // Each try: a form, and the lines it may take.
        // The forms before the floor, as given: the floor can be the very form that shortens a word, so
        // it's dropped before de-duplicating, never the abbreviation itself.
        val tries = remember(forms, maxLines) { forms.dropLast(1).distinct().map { it to 1 } + distinct.map { it to maxLines } }
        val chosen = remember(tries, style, fontScale, width) {
            tries.firstOrNull { (form, lines) ->
                !measurer.measure(form, style, maxLines = lines, constraints = Constraints(maxWidth = width)).hasVisualOverflow
            }?.first
        }
        val display = chosen ?: distinct.last()
        Text(
            text = display,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = if (display != full) Modifier.semantics { contentDescription = full } else Modifier,
        )
    }
}

/**
 * [EvenShortening.choose] for a line [maxWidth] px wide with [gap] px between the parts, measured in
 * [style] as drawn; worked out again when the forms, the style, the font scale or the width change.
 */
@Composable
private fun rememberEvenPair(first: List<String>, second: List<String>, style: TextStyle, maxWidth: Int, gap: Int): EvenShortening.Choice =
    rememberEvenPair(first, second, style, maxWidth, listOf(gap))

/**
 * As above, with what sits between the parts taking [gaps] px at each level, and an optional [extra]
 * px beside them shown only while both parts fit in full (null for none).
 */
@Composable
private fun rememberEvenPair(
    first: List<String>,
    second: List<String>,
    style: TextStyle,
    maxWidth: Int,
    gaps: List<Int>,
    extra: Int? = null,
): EvenShortening.Choice {
    val measurer = rememberTextMeasurer()
    val fontScale = LocalDensity.current.fontScale
    return remember(first, second, style, fontScale, maxWidth, gaps, extra) {
        val widthOf = { text: String -> measurer.measure(text, style, maxLines = 1).size.width }
        if (extra == null) {
            EvenShortening.choose(first, second, maxWidth, gaps, widthOf)
        } else {
            EvenShortening.choose(first, second, maxWidth, gaps, extra, widthOf)
        }
    }
}

/**
 * A favorite ride's heading, "[from] ➔ [to]", its two ends shortened evenly ([EvenShortening]) so a
 * long name at one end never pushes the other off the line. Each end's forms come longest first
 * ([DestinationAbbreviations.forms]), worked out with the card, so only measuring happens here. While some level
 * fits it is one text, as the ride's title reads; below that, each end elides in its own share either
 * side of the arrow.
 */
@Composable
internal fun RideTitle(from: List<String>, to: List<String>, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        // The arrow's inline box ([arrowInlineContent]), which takes the spaces either side of it.
        val arrow = if (style.fontSize.isSp) with(density) { (style.fontSize.toPx() * 1.15f).roundToInt() } else 0
        val pair = rememberEvenPair(from, to, style, constraints.maxWidth, arrow)
        val inline = arrowInlineContent(color)
        val firstMax = pair.firstMax
        val secondMax = pair.secondMax
        if (firstMax == null || secondMax == null) {
            Text(
                text = withArrowIcons(stringResource(R.string.journey_title, pair.first, pair.second)),
                inlineContent = inline,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RideEnd(pair.first, firstMax, style, color)
                Text(text = withArrowIcons(" $ARROW "), inlineContent = inline, style = style, maxLines = 1, softWrap = false)
                RideEnd(pair.second, secondMax, style, color)
            }
        }
    }
}

/** One end of a [RideTitle] too long for the line, eliding within the [max] px its share allows. */
@Composable
private fun RideEnd(text: String, max: Int, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = with(LocalDensity.current) { max.toDp() }),
    )
}

/**
 * A stop a favorite ride boards from, grouped by stop, read as a bus place's heading reads: the stop's
 * name ([stop], its forms longest first) with its platform or pole ([qualifier], " – Platform 7" then
 * " – Plat 7"), then "➔" and where the ride goes ([destination], forms likewise), the two names
 * shortened evenly ([EvenShortening]). [marker] (a favorite ride's star, [markerWidth] wide) follows
 * the destination as it follows a ride's own title. How far the stop is ([distance], "120 m", dimmed in
 * [distanceColor] as on a near-me heading) comes last while both names fit whole beside it, and is the
 * first thing to go. The forms are worked out with the card, so only measuring happens here.
 */
@Composable
internal fun RideStopTitle(
    stop: List<String>,
    qualifier: List<String>,
    destination: List<String>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    distance: String? = null,
    distanceColor: Color = color,
    marker: (@Composable () -> Unit)? = null,
    markerWidth: Dp = 0.dp,
    // The distance's own weight: regular beside a bold heading, as a station's heading has it.
    distanceStyle: TextStyle = style,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val fontScale = density.fontScale
        // The marker is always drawn, so the names share what's left beside it.
        val width = (constraints.maxWidth - with(density) { markerWidth.roundToPx() }).coerceAtLeast(0)
        // The arrow's inline box ([arrowInlineContent]), which takes the spaces either side of it.
        val arrow = if (style.fontSize.isSp) with(density) { (style.fontSize.toPx() * 1.15f).roundToInt() } else 0
        val gaps = remember(qualifier, style, fontScale, arrow) {
            qualifier.map { measurer.measure(it, style, maxLines = 1).size.width + arrow }.ifEmpty { listOf(arrow) }
        }
        val distanceText = distance?.let { " ($it)" }
        val distanceWidth = remember(distanceText, distanceStyle, fontScale) {
            distanceText?.let { measurer.measure(it, distanceStyle, maxLines = 1).size.width }
        }
        val pair = rememberEvenPair(stop, destination, style, width, gaps, distanceWidth)
        val shownQualifier = qualifier.getOrNull(minOf(pair.level, qualifier.lastIndex))
        val inline = arrowInlineContent(color)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pair.first,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = pair.firstMax?.let { Modifier.widthIn(max = with(density) { it.toDp() }) } ?: Modifier,
            )
            if (shownQualifier != null) {
                Text(
                    // A letterless pole's "➔ Archway" carries the arrow, drawn as the app draws it.
                    text = withArrowIcons(shownQualifier),
                    inlineContent = inline,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(text = withArrowIcons(" $ARROW "), inlineContent = inline, style = style, maxLines = 1, softWrap = false)
            Text(
                text = pair.second,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = pair.secondMax?.let { Modifier.widthIn(max = with(density) { it.toDp() }) } ?: Modifier,
            )
            marker?.invoke()
            if (distanceText != null && pair.extra) {
                Text(text = distanceText, style = distanceStyle, color = distanceColor, maxLines = 1, softWrap = false)
            }
        }
    }
}
