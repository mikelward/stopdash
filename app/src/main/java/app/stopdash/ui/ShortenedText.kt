package app.stopdash.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import app.stopdash.domain.DestinationAbbreviations

/**
 * A place name shortened to fit (SPEC destination-label): in full when it fits, else with common
 * whole words shortened ("East Finchley" → "E. Finchley", "Wood Lane" → "Wood Ln"), else its floor
 * ("Battersea Power" → "Battersea P."), eliding with a single "…" only below that, never mid-glyph.
 * Each part of a slash-separated name shortens alike ([DestinationAbbreviations]), and below the
 * floor each elides on its own, so no place is lost to one trailing "…". Copy around the name
 * ("From ‹stop›") is drawn beside it, not in it, so only the name yields. The full text stays the
 * screen-reader label when a shorter form shows.
 */
@Composable
internal fun ShortenedName(
    name: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val abbreviated = remember(name) { DestinationAbbreviations.abbreviate(name) }
    val floor = remember(name) { DestinationAbbreviations.floor(name) }
    ShortenedText(listOf(name, abbreviated, floor), style, modifier, color)
}

/**
 * The first of [forms], longest first, that fits the width given, else the last, eliding with a
 * single "…" if even that overflows. The first form stays the screen-reader label when another shows.
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
            modifier = Modifier
                .fillMaxWidth()
                .then(if (display != full) Modifier.semantics { contentDescription = full } else Modifier),
        )
    }
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
