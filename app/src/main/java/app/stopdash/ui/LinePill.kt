package app.stopdash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.stopdash.domain.LineRef
import app.stopdash.domain.lineCode
import app.stopdash.domain.riderLineName

/**
 * The line's short [lineCode] (VIC, BAK, …) in its line's color. Three shapes:
 * - a **tube/single-color-mode** line is a filled pill — the code in black/white ([textColorOn]),
 *   the fill the official color, a halo lifting the label on the tightest mid-luminance fills;
 * - a **named Overground** line is a **hollow** pill — the card surface shows through, with the
 *   line accent as the border and the label ([accentInkOn]/[accentEdgeOn], nudged to stay
 *   legible on the surface), so it reads as Overground even where its color is near a tube
 *   line's;
 * - a line/mode with **no defined color** is a neutral pill — theme colors, which already
 *   guarantee their own contrast, so no halo.
 *
 * The full [lineName] is the pill's accessible label, so a screen reader announces "Victoria"
 * rather than "VIC". The label is bold and ellipsizes; the caller caps the width so a long
 * name doesn't starve the countdown.
 */
@Composable
fun LinePill(lineName: String, lineId: String, mode: String, modifier: Modifier = Modifier) {
    val colors = pillColors(lineName, lineId, mode, MaterialTheme.colorScheme.surface)
    val background = when (colors) {
        is PillColors.Solid -> colors.fill
        is PillColors.Hollow -> Color.Transparent
        PillColors.Neutral -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (colors) {
        is PillColors.Solid -> colors.label
        is PillColors.Hollow -> colors.label
        PillColors.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = when (colors) {
        is PillColors.Solid -> colors.border
        is PillColors.Hollow -> colors.border
        PillColors.Neutral -> MaterialTheme.colorScheme.outlineVariant
    }
    // Zero-offset blurred shadow = a symmetric glow around the glyphs. Only for a solid fill;
    // the hollow and neutral pills read their color off the surface and need no halo.
    val haloBlurPx = with(LocalDensity.current) { 2.dp.toPx() }
    // A fixed label width so every pill is exactly the same size down the column — uniform
    // by construction, not just "no narrower than a floor": a two-digit bus number, a
    // three-letter tube code, and a four-character bus route all render in the same box.
    // The width holds the *widest* code this app shows (see [LINE_PILL_LABEL_WIDTH]), so
    // nothing truncates — a shorter code just gets more centering room. Scaled by the font
    // scale so it still holds those codes at a large accessibility text size rather than
    // clipping them.
    val labelWidth = LINE_PILL_LABEL_WIDTH * LocalDensity.current.fontScale
    val textStyle = MaterialTheme.typography.labelLarge.let { base ->
        if (colors is PillColors.Solid) base.copy(shadow = Shadow(colors.halo, Offset.Zero, haloBlurPx))
        else base
    }
    Surface(
        color = background,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.5.dp, borderColor),
        // The caller caps the width (to a fraction of the card) so a long name at a large
        // font ellipsizes rather than starving the countdown; the label already ellipsizes.
        modifier = modifier,
    ) {
        Text(
            text = lineCode(lineName, mode, lineId),
            style = textStyle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Fixed width + centered, so every pill is exactly the same width down the column
            // (see [labelWidth]) — a two-digit bus number and a three-letter code sit in the
            // same box instead of stepping ragged.
            textAlign = TextAlign.Center,
            // The visible label is the short code; the accessible label stays the full line
            // name so a screen reader announces "Victoria", not "VIC".
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .width(labelWidth)
                .semantics { contentDescription = riderLineName(lineName, mode) },
        )
    }
}

/**
 * The fixed label width every pill shares at the default font scale, before [LinePill] scales
 * it by the current font scale. Sized to the *widest* code [lineCode] produces — a
 * four-character bus route (`N550`, `SL10`), which is wider than any three-letter tube code
 * (VIC, HAM) or three-digit route — so every supported code renders complete; a shorter code
 * just centers with more room. `LinePillWidthTest` pins that a four-character code neither
 * clips nor widens the column past the others.
 */
private val LINE_PILL_LABEL_WIDTH = 48.dp

/**
 * Lines that serve one leg alike (the 43 or the 134 to the same stop) as **one pill cut
 * diagonally**, a segment per line in [lines]' order, each in its own [LinePill] colors and label
 * width, so "43/134" reads as either line. Three or more lines show the first, then "…" in the
 * second's colors ("43/…"), so the pill stays the size of two however many share the leg
 * (maintainer, 2026-09-28). [description] is the accessible label ("43, 134 or 263"), naming every line.
 */
@Composable
fun SharedLinePill(lines: List<LineRef>, description: String, modifier: Modifier = Modifier) {
    if (lines.size == 1) {
        val line = lines.single()
        LinePill(line.name, line.id, line.mode, modifier)
        return
    }
    val surface = MaterialTheme.colorScheme.surface
    val neutralFill = MaterialTheme.colorScheme.surfaceVariant
    val neutralLabel = MaterialTheme.colorScheme.onSurfaceVariant
    val neutralBorder = MaterialTheme.colorScheme.outlineVariant
    val codes = cutPillCodes(lines)
    val segments = lines.take(codes.size).mapIndexed { i, line ->
        val code = codes[i]
        when (val colors = pillColors(line.name, line.id, line.mode, surface)) {
            is PillColors.Solid -> Segment(line, code, colors.fill, colors.label, colors.border, colors.halo)
            is PillColors.Hollow -> Segment(line, code, Color.Transparent, colors.label, colors.border, null)
            PillColors.Neutral -> Segment(line, code, neutralFill, neutralLabel, neutralBorder, null)
        }
    }
    val density = LocalDensity.current
    val haloBlurPx = with(density) { 2.dp.toPx() }
    // Tighter than a lone pill: each segment as wide as its own code, not the fixed pill width, so
    // "43/134" reads as one label and a two-digit route takes less room than a three-digit one
    // (maintainer, 2026-09-28). Never narrower than a two-character code, though, so a one-character
    // route beside a longer one ("4/N20") still gets room of its own (maintainer, 2026-09-28).
    val measurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
    val naturalWidths = with(density) {
        val minWidth = measurer.measure(MIN_SEGMENT_CODE, baseStyle, maxLines = 1).size.width
        segments.map {
            maxOf(minWidth, measurer.measure(it.code, baseStyle, maxLines = 1).size.width).toDp()
        }
    }
    // A lone pill's side padding at the pill's two ends; half that either side of a cut, whose lean
    // already sets the codes apart.
    fun startPad(i: Int) = if (i == 0) SEGMENT_PADDING else CUT_PADDING
    fun endPad(i: Int) = if (i == segments.lastIndex) SEGMENT_PADDING else CUT_PADDING
    val shape = RoundedCornerShape(8.dp)
    // Never wider than the room it's given (many lines, a narrow screen, large text): each segment
    // shrinks alike, its label ellipsizing, so the segments stay equal and under their labels.
    // Left to right whatever the locale: the segments are painted left to right, and line codes
    // read that way anyway, so a code never sits over another line's color.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(modifier) {
        val padsPx = with(density) { segments.indices.sumOf { startPad(it).roundToPx() + endPad(it).roundToPx() } }
        val naturalPx = with(density) { naturalWidths.sumOf { it.roundToPx() } } + padsPx
        val labelWidths = if (constraints.hasBoundedWidth && naturalPx > constraints.maxWidth) {
            // Short of room: the longer codes shrink alike, and room a shorter code doesn't need goes to them.
            val fitted = fitSegmentWidths(with(density) { naturalWidths.map { it.roundToPx() } }, constraints.maxWidth - padsPx)
            with(density) { fitted.map { it.toDp() } }
        } else {
            naturalWidths
        }
        // Where each cut falls: the right edge of each segment but the last, in pixels.
        val cuts = with(density) {
            segments.indices.runningFold(0) { x, i -> x + startPad(i).roundToPx() + labelWidths[i].roundToPx() + endPad(i).roundToPx() }
                .drop(1).dropLast(1)
        }
        Row(
            modifier = Modifier
                .clip(shape)
                .drawWithContent {
                    val corner = CornerRadius(8.dp.toPx())
                    val stroke = 1.5.dp.toPx()
                    // The cut's lean: its top edge this far right of its bottom.
                    val lean = 8.dp.toPx()
                    fun part(i: Int) = Path().apply {
                        val left = if (i == 0) 0f else cuts[i - 1].toFloat()
                        val right = if (i == segments.lastIndex) size.width else cuts[i].toFloat()
                        moveTo(if (i == 0) 0f else left + lean / 2, 0f)
                        lineTo(if (i == segments.lastIndex) size.width else right + lean / 2, 0f)
                        lineTo(if (i == segments.lastIndex) size.width else right - lean / 2, size.height)
                        lineTo(if (i == 0) 0f else left - lean / 2, size.height)
                        close()
                    }
                    segments.forEachIndexed { i, segment ->
                        clipPath(part(i)) {
                            drawRoundRect(segment.fill, cornerRadius = corner)
                            drawRoundRect(
                                segment.border,
                                topLeft = Offset(stroke / 2, stroke / 2),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = corner,
                                style = Stroke(stroke),
                            )
                        }
                    }
                    // Each cut a gap of the surface, so two fills of one color still read as two lines.
                    for (i in 1 until segments.size) {
                        val x = cuts[i - 1].toFloat()
                        drawLine(
                            surface,
                            Offset(x + lean / 2, 0f),
                            Offset(x - lean / 2, size.height),
                            strokeWidth = stroke * 1.5f,
                        )
                    }
                    drawContent()
                }
                // Only the combined label ("43 or 134"), not each segment's code as well.
                .clearAndSetSemantics { contentDescription = description },
        ) {
            segments.forEachIndexed { i, segment ->
                val style = segment.halo?.let { baseStyle.copy(shadow = Shadow(it, Offset.Zero, haloBlurPx)) } ?: baseStyle
                Text(
                    text = segment.code,
                    style = style,
                    color = segment.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = startPad(i), end = endPad(i), top = 4.dp, bottom = 4.dp).width(labelWidths[i]),
                )
            }
        }
    }
    }
}

// A [SharedLinePill]'s padding at its two ends, as a lone pill's.
private val SEGMENT_PADDING = 8.dp

// A [SharedLinePill] segment's padding either side of a cut.
private val CUT_PADDING = 4.dp

/**
 * Fits segments whose natural widths ([natural], in pixels) add up to more than [room]: every
 * segment gets an equal share, capped at its own width, and whatever a narrower one leaves unused
 * is shared again among the rest, so a longer code is cut only when there is truly no room for it.
 * Whole pixels rounded down, so the result never adds up to more than [room].
 */
internal fun fitSegmentWidths(natural: List<Int>, room: Int): List<Int> {
    val widths = IntArray(natural.size)
    var left = room.coerceAtLeast(0)
    // Narrowest first: each takes its own width while that is within an equal share of what is
    // left; once one isn't, it and every wider one take that same share, so they stay alike.
    val order = natural.indices.sortedBy { natural[it] }
    for ((n, i) in order.withIndex()) {
        val share = left / (natural.size - n)
        if (natural[i] > share) {
            order.drop(n).forEach { widths[it] = share }
            break
        }
        widths[i] = natural[i]
        left -= widths[i]
    }
    return widths.toList()
}

/** The narrowest a cut pill's segment gets, as a code: two wide digits. */
private const val MIN_SEGMENT_CODE = "88"

// One line's part of a [SharedLinePill]: the code it shows, its fill, label and border colors, and a
// label halo on a solid fill.
private data class Segment(val line: LineRef, val code: String, val fill: Color, val label: Color, val border: Color, val halo: Color?)

/**
 * The codes a [SharedLinePill] shows for [lines], one per part: every line's code for two, and for
 * three or more the first line's then "…", the second part standing for the rest in the second
 * line's colors.
 */
internal fun cutPillCodes(lines: List<LineRef>): List<String> =
    if (lines.size > MAX_NAMED_LINES) {
        listOf(lineCode(lines[0].name, lines[0].mode, lines[0].id), MORE_LINES)
    } else {
        lines.map { lineCode(it.name, it.mode, it.id) }
    }

/** The most lines a [SharedLinePill] names; past that, the second part reads [MORE_LINES]. */
private const val MAX_NAMED_LINES = 2
private const val MORE_LINES = "…"
