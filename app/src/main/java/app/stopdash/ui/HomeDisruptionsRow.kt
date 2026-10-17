package app.stopdash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.stopdash.R

/** What leads a disruptions card ([DisruptionsCard]): one mark for what it says as a whole. */
internal enum class DisruptionsMark { CHECKING, DISRUPTED, UNKNOWN, NOT_COUNTED, CLEAR }

internal fun disruptionsMark(row: TripRow): DisruptionsMark = when {
    row.checking -> DisruptionsMark.CHECKING
    // A trip's closed stops are disruptions too; the home screen's row names none.
    row.lines.isNotEmpty() || row.stops.isNotEmpty() -> DisruptionsMark.DISRUPTED
    // A check that failed is never shown as all clear.
    row.unknown || row.unknownLines.isNotEmpty() -> DisruptionsMark.UNKNOWN
    // A disruption it doesn't count (dismissed, say): a step above all clear, and a hint that a tap says
    // more (maintainer, 2026-10-17).
    row.linesDisrupted -> DisruptionsMark.NOT_COUNTED
    else -> DisruptionsMark.CLEAR
}

/**
 * Where the ? goes among the pills: before the first of [unchecked] lines, only behind [disrupted] ones
 * it has to be told apart from. With none disrupted, the card's own mark is that ?, and one is enough
 * (maintainer, 2026-10-17); null leaves it out.
 */
internal fun unknownMarkAt(disrupted: Int, unchecked: Int): Int? = disrupted.takeIf { it > 0 && unchecked > 0 }

/**
 * Whether the ? goes before the "+N" count instead: every one of [unchecked] lines is among the [hidden]
 * pills it counts (they come last), so the ? [unknownMarkAt] places has no pill left to stand before,
 * and the unchecked must still be marked (Codex, #919). Behind no [disrupted] lines the card's own mark
 * is the ?, so it is never needed there.
 */
internal fun unknownMarkOnCount(disrupted: Int, unchecked: Int, hidden: Int): Boolean =
    disrupted > 0 && unchecked > 0 && hidden >= unchecked

/** A ? in a circle: something couldn't be checked. Heard through the card's own description. */
@Composable
private fun UnknownMark() {
    Icon(HelpOutlineIcon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
}

/**
 * The home screen's disruptions card ([HomeLines], [DisruptionsCard]): its lines alone, a stop's closure
 * having its own card, so all clear is "No line disruptions" beside a ✓, or "No watched disruptions" beside
 * an ⓘ where a line it covers has one it doesn't count (maintainer, 2026-10-17). Its lines page has the
 * home screen's sections, and offers watching a line.
 */
@Composable
internal fun HomeDisruptionsRow(row: TripRow, modifier: Modifier = Modifier) {
    DisruptionsCard(
        row,
        tag = "homeDisruptions",
        clear = R.string.home_disruptions_no_line,
        notCounted = R.string.home_disruptions_none,
        stops = false,
        modifier = modifier,
    ) { close ->
        // *Other*'s lines are asked about while the page is open, and no longer once it closes.
        val onPage = LocalLinesPage.current
        DisposableEffect(onPage) {
            onPage?.invoke(true)
            onDispose { onPage?.invoke(false) }
        }
        TripLinesPage(row, onClose = close, gone = R.string.home_lines_gone, goneWatched = R.string.home_lines_unwatched, dismissal = LocalDismissLineAlert.current, watch = LocalLineWatch.current)
    }
}

/**
 * A disruptions card, the home screen's ([HomeDisruptionsRow]) and a trip's alike (maintainer, 2026-10-17:
 * a trip's matches the home screen's), one line high whatever it says (maintainer, 2026-10-05): each
 * disrupted line's pill behind a ⚠, then a ? in a circle and what couldn't be checked, on a tinted card,
 * the ? shown once ([DisruptionsMark], [unknownMarkAt]); with none of those, a plain card saying so beside
 * its mark ([clear] beside a ✓, [notCounted] beside an ⓘ where a line it covers has a disruption it doesn't
 * count, "Checking for disruptions" beside a spinner; maintainer, 2026-10-16, 2026-10-17). With [stops], the
 * closed stops follow the disrupted lines, and the stops that couldn't be checked the unchecked lines, as
 * text: a trip names them, the home screen gives a closure a card of its own. What doesn't fit is counted
 * as "+N". A tap opens every line it covers with its status ([page], told how to close), which its chevron
 * says (maintainer, 2026-10-16).
 */
@Composable
internal fun DisruptionsCard(
    row: TripRow,
    tag: String,
    @StringRes clear: Int,
    @StringRes notCounted: Int,
    stops: Boolean,
    modifier: Modifier = Modifier,
    page: @Composable (close: () -> Unit) -> Unit,
) {
    val style = MaterialTheme.typography.bodyMedium
    val colors = MaterialTheme.colorScheme
    val error = colors.error
    var open by rememberSaveable { mutableStateOf(false) }
    // The pills, worst first (the disrupted, then the ? and the unchecked), counted as "+N" where
    // they don't fit; only pills are counted (Codex, #577). A word alone always shows, at the end.
    val disrupted = row.lines
    val closed = if (stops) row.stops else ""
    val unchecked = if (row.checking) emptyList() else row.unknownLines
    val uncheckedStops = if (stops && !row.checking) row.unknownStops else ""
    // The disrupted lines, then the closed stops as one; the unchecked lines, then the unchecked stops.
    val disruptedCount = disrupted.size + (if (closed.isNotEmpty()) 1 else 0)
    val uncheckedCount = unchecked.size + (if (uncheckedStops.isNotEmpty()) 1 else 0)
    val pills = disruptedCount + uncheckedCount
    // Anything disrupted, or anything that couldn't be checked, takes the warning and the tint: a check
    // that failed is never shown as all clear. Lines already known disrupted keep it through a recheck.
    val warn = disruptedCount > 0 || (!row.checking && (uncheckedCount > 0 || row.unknown))
    val word = when {
        // Said whole on its own; beside pills, the short form leaves them the room.
        row.checking -> if (pills == 0) R.string.disruptions_checking else R.string.trip_disruptions_checking
        warn && pills == 0 -> R.string.disruptions_unknown
        // On the home screen, said of its lines alone: a stop's closure has its own card, so all clear there
        // is never more than the lines' (maintainer, 2026-10-17; Codex, #927).
        disruptedCount == 0 && !row.unknown -> if (row.linesDisrupted) notCounted else clear
        // Something couldn't be checked with no line to name for it (a stop's closure check, say),
        // beside the lines that are disrupted: said all the same (maintainer, 2026-10-05).
        row.unknown && uncheckedCount == 0 -> R.string.trip_disruptions_unknown
        else -> null
    }
    val muted = if (warn) colors.onErrorContainer else colors.onSurfaceVariant
    val wordColor = if (word == R.string.disruptions_unknown) error else muted
    // Heard whole, every line and stop named, never only the pills that fit nor one measured and left
    // out. A word alone already says what it's about.
    val spoken = if (pills == 0 && word != null) {
        stringResource(word)
    } else {
        listOfNotNull(
            stringResource(R.string.trip_disruptions_label),
            row.linesSpoken.ifBlank { null },
            closed.ifBlank { null },
            word?.let { stringResource(it) },
            stringResource(R.string.trip_disruptions_unknown_label).takeIf { uncheckedCount > 0 },
            row.unknownSpoken.takeIf { unchecked.isNotEmpty() },
            uncheckedStops.ifBlank { null },
        ).joinToString(" ")
    }
    // The card the departures sit in, its border theirs whatever it says, its fill tinted as the notices
    // are where something's wrong (maintainer, 2026-10-16); the fill and the mark change, never the size,
    // so nothing under it moves.
    Surface(
        shape = CardDefaults.outlinedShape,
        color = if (warn) colors.errorContainer else colors.surface,
        border = CardDefaults.outlinedCardBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .testTag(tag)
                .semantics { contentDescription = spoken }
                .clickable(onClickLabel = stringResource(R.string.trip_lines_open)) { open = true }
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).clearAndSetSemantics {}) {
                when (disruptionsMark(row)) {
                    DisruptionsMark.CHECKING -> CircularProgressIndicator(strokeWidth = 2.dp, color = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    DisruptionsMark.DISRUPTED -> Icon(Icons.Filled.Warning, contentDescription = null, tint = error)
                    DisruptionsMark.UNKNOWN -> UnknownMark()
                    DisruptionsMark.NOT_COUNTED -> Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.onSurfaceVariant)
                    DisruptionsMark.CLEAR -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.onSurfaceVariant)
                }
            }
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f).padding(start = 12.dp).clearAndSetSemantics {},
            ) {
                // A pill nobody sees sets the height, so a word turning into a pill never moves the list.
                LinePill("", "", "bus", Modifier.alpha(0f).clearAndSetSemantics {})
                OneLine(
                    pills = pills,
                    pill = { i ->
                        // A refresh that drops pills recomposes the old ones' slots before the row is
                        // measured again and lets them go: a slot past the lists draws nothing.
                        val uncheckedAt = i - disruptedCount
                        when {
                            i < disrupted.size -> disrupted[i].let { LinePill(it.lineName, it.lineId, it.mode) }
                            i < disruptedCount -> Text(closed, style = style, maxLines = 1)
                            uncheckedAt < unchecked.size -> unchecked[uncheckedAt].let { LinePill(it.lineName, it.lineId, it.mode) }
                            uncheckedAt < uncheckedCount -> Text(uncheckedStops, style = style, color = error, maxLines = 1)
                        }
                    },
                    // The ? before the first unchecked pill, as part of it, so it goes with it.
                    labelAt = unknownMarkAt(disruptedCount, uncheckedCount),
                    label = { UnknownMark() },
                    word = word?.let {
                        {
                            // Beside the disrupted lines, what couldn't be checked with no line to name is
                            // the same ?, never the word.
                            if (it == R.string.trip_disruptions_unknown) UnknownMark()
                            else Text(stringResource(it), style = style, color = wordColor, maxLines = 1)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { more ->
                    // The unchecked come last, so a count that hides any of them is red, as the ? is.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (unknownMarkOnCount(disruptedCount, uncheckedCount, more)) UnknownMark()
                        Text(stringResource(R.string.home_disruptions_more, more), style = style, color = if (uncheckedCount > 0) error else muted, maxLines = 1)
                    }
                }
            }
            // The card always opens the lines page, so it says it goes somewhere (maintainer, 2026-10-16).
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    if (open) page { open = false }
}

/**
 * [pills] pills on one line, 8dp apart, as many as fit, then [word] if there's one, always shown; the
 * pills that don't fit are counted by [more] ("+N"), which takes the place of the last one or more that
 * would have shown, and is left out only where not even it fits beside the word. [lead] goes first
 * where it fits beside the word. [label] goes before pill [labelAt], shown only with it. Pills are composed and measured only until the line is full, never every
 * one a long list has (Codex, #577): each [pill] is drawn by its index.
 */
@Composable
internal fun OneLine(
    pills: Int,
    pill: @Composable (Int) -> Unit,
    labelAt: Int?,
    label: @Composable () -> Unit,
    word: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    // Before everything ("Disruptions:"), where it fits beside the word; left out where it doesn't.
    lead: (@Composable () -> Unit)? = null,
    more: @Composable (Int) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val gap = 8.dp.roundToPx()
        val max = constraints.maxWidth
        val loose = Constraints(maxWidth = max, maxHeight = constraints.maxHeight)
        fun measure(slot: Any, content: @Composable () -> Unit) = subcompose(slot) { content() }.map { it.measure(loose) }
        // A pill (or a stop list) measured at its own width, never squeezed to the line's: one wider than
        // the line is counted as "+N", not drawn clipped as if it fit (Codex, #620).
        val unbounded = Constraints(maxHeight = constraints.maxHeight)
        fun widthOf(parts: List<Placeable>) = parts.maxOfOrNull { it.width } ?: 0
        val end = word?.let { measure("word", it) }.orEmpty()
        val labelParts = labelAt?.let { measure("label", label) }.orEmpty()
        // The word's room comes first; the lead takes what's left of the line only where it fits beside
        // it, so a large font never squeezes the word out (Codex, #620).
        val afterWord = if (end.isEmpty()) max else max - widthOf(end) - gap
        // Kept only where it leaves room for at least the count of every pill, so the label never stands
        // alone over what it introduces (Codex, #620).
        val leastPills = if (pills > 0) widthOf(measure("leastPills") { more(pills) }) + gap else 0
        val leadParts = lead?.let { measure("lead", it) }.orEmpty().takeIf { it.isNotEmpty() && widthOf(it) + leastPills <= afterWord }.orEmpty()
        // The room the pills (and the label) have, the word and the lead taken off.
        val room = if (leadParts.isEmpty()) afterWord else afterWord - widthOf(leadParts) - gap
        // Pills as they fit, one at a time, and one past the room at most: the rest are never measured.
        val shown = ArrayList<List<Placeable>>()
        var used = 0
        fun withLabel(i: Int) = if (labelAt == i) widthOf(labelParts) + gap else 0
        while (shown.size < pills) {
            val i = shown.size
            val parts = subcompose(i) { pill(i) }.map { it.measure(unbounded) }
            val next = used + (if (i > 0) gap else 0) + withLabel(i) + widthOf(parts)
            shown += parts
            used = next
            if (used > room) break
        }
        // How many pills show: all if they fit, else as many as leave room for "+N".
        var count = shown.size
        var counted: List<Placeable> = emptyList()
        fun span(n: Int) = (0 until n).sumOf { widthOf(shown[it]) + withLabel(it) } + gap * (n - 1).coerceAtLeast(0)
        // Nothing to count with no pills, however little room the word leaves (Codex, #620).
        if (pills > 0 && (shown.size < pills || span(count) > room)) {
            count = shown.size - 1
            while (true) {
                val label2 = measure("more${pills - count}") { more(pills - count) }
                val after = span(count) + (if (count > 0) gap else 0) + widthOf(label2)
                if (after <= room) {
                    counted = label2
                    break
                }
                // Not even the count fits beside the word: the word stands, the count goes, rather than
                // push the word past the row's end (Codex, #620). The lines page lists what it hid.
                if (count <= 0) {
                    count = 0
                    break
                }
                count--
            }
        }
        val labelShown = labelAt != null && labelAt < count
        val all = leadParts + shown.take(count).flatten() + counted + end + (if (labelShown) labelParts else emptyList())
        val height = all.maxOfOrNull { it.height } ?: 0
        layout(max, height) {
            var x = 0
            fun place(parts: List<Placeable>) {
                parts.forEach { it.placeRelative(x, (height - it.height) / 2) }
                x += widthOf(parts) + gap
            }
            if (leadParts.isNotEmpty()) place(leadParts)
            for (i in 0 until count) {
                if (labelAt == i) place(labelParts)
                place(shown[i])
            }
            if (counted.isNotEmpty()) place(counted)
            if (end.isNotEmpty()) place(end)
        }
    }
}
