package app.stopdash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * The home screen's disruptions row ([HomeLines]), one line high whatever it says (maintainer,
 * 2026-10-05): "Disruptions:", each disrupted line's pill, then the check's word as a trip's row says it
 * ("Checking…", "Unknown:" and what couldn't be checked, else "None"); what doesn't fit is counted as
 * "+N". A tap opens every line it covers with its status ([TripLinesPage]); no chevron (maintainer,
 * 2026-10-05).
 */
@Composable
internal fun HomeDisruptionsRow(row: TripRow, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    var open by rememberSaveable { mutableStateOf(false) }
    // The pills, worst first (the disrupted, then "Unknown:" and the unchecked), counted as "+N" where
    // they don't fit; only pills are counted (Codex, #577). A word alone ("Checking…", "None") always
    // shows, at the end.
    val disrupted = row.lines
    val unchecked = if (row.checking) emptyList() else row.unknownLines
    val word = when {
        row.checking -> R.string.trip_disruptions_checking
        row.lines.isEmpty() && !row.unknown -> R.string.trip_disruptions_none
        // Something couldn't be checked with no line to name for it (a stop's closure check, say): said
        // all the same, as the list's banner did (maintainer, 2026-10-05).
        row.unknown && unchecked.isEmpty() -> R.string.trip_disruptions_unknown
        else -> null
    }
    val wordColor = if (word == R.string.trip_disruptions_unknown) error else muted
    // Heard whole, every line named, never only the pills that fit nor one measured and left out.
    val spoken = listOfNotNull(
        stringResource(R.string.trip_disruptions_label),
        row.linesSpoken.ifBlank { null },
        word?.let { stringResource(it) },
        stringResource(R.string.trip_disruptions_unknown_label).takeIf { unchecked.isNotEmpty() },
        row.unknownSpoken.takeIf { unchecked.isNotEmpty() },
    ).joinToString(" ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag("homeDisruptions")
            .semantics { contentDescription = spoken }
            .clickable(onClickLabel = stringResource(R.string.trip_lines_open)) { open = true },
    ) {
        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f).padding(vertical = 4.dp).clearAndSetSemantics {}) {
            // A pill nobody sees sets the height, so a word turning into a pill never moves the list.
            LinePill("", "", "bus", Modifier.alpha(0f).clearAndSetSemantics {})
            Row(verticalAlignment = Alignment.CenterVertically) {
                OneLine(
                    pills = disrupted.size + unchecked.size,
                    pill = { i ->
                        // A refresh that drops pills recomposes the old ones' slots before the row is
                        // measured again and lets them go: a slot past the lists draws nothing.
                        val leg = disrupted.getOrNull(i) ?: unchecked.getOrNull(i - disrupted.size) ?: return@OneLine
                        LinePill(leg.lineName, leg.lineId, leg.mode)
                    },
                    // "Unknown:" before the first unchecked pill, as part of it, so it goes with it.
                    labelAt = disrupted.size.takeIf { unchecked.isNotEmpty() },
                    label = { Text(stringResource(R.string.trip_disruptions_unknown_label), style = style, color = error, maxLines = 1) },
                    word = word?.let { { Text(stringResource(it), style = style, color = wordColor, maxLines = 1) } },
                    modifier = Modifier.fillMaxWidth(),
                    lead = { Text(stringResource(R.string.trip_disruptions_label), style = style, color = muted, maxLines = 1) },
                ) { more ->
                    // The unchecked come last, so a count that hides any of them is red, as "Unknown:" is.
                    Text(stringResource(R.string.home_disruptions_more, more), style = style, color = if (unchecked.isNotEmpty()) error else muted, maxLines = 1)
                }
            }
        }
    }
    if (open) TripLinesPage(row, onClose = { open = false }, gone = R.string.home_lines_gone, dismissal = LocalDismissLineAlert.current)
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
