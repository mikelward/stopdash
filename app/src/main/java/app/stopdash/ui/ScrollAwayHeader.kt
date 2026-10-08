package app.stopdash.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * How far a [ScrollAwayHeader]'s header has scrolled off the top: 0 shown in full, down to minus its
 * height. Saved with the list under it, which restores its own scroll: a header back in full after a
 * rotation would stand over the middle of the list (Codex, #694).
 */
@Stable
internal class ScrollAwayState(initialOffset: Float = 0f, initialHeight: Int = 0) {
    // Read only when a scroll moves the header, so not state: measuring writes it without a relayout.
    var headerHeight = initialHeight
        private set
    var offset by mutableFloatStateOf(initialOffset)
        private set

    /** Moves the header by [delta] px (negative scrolls it away) within its range; returns what was used. */
    fun consume(delta: Float): Float {
        val next = (offset + delta).coerceIn(-headerHeight.toFloat(), 0f)
        val used = next - offset
        offset = next
        return used
    }

    /**
     * The header measured at [height]. One scrolled partly or wholly away keeps showing as much as it
     * did, so what it gains or loses (a banner, a Direct section landing) happens out of sight rather
     * than push the routes down under the rider (Codex, #694); one shown in full grows in place.
     */
    internal fun measured(height: Int) {
        if (height == headerHeight) return
        if (offset < 0f) offset = (offset - (height - headerHeight)).coerceIn(-height.toFloat(), 0f)
        headerHeight = height
    }

    // The body's scroll: scrolling it up takes the header away first; scrolling down brings it back
    // only once the body is at its top, so the header comes back with the content it heads, never over
    // the middle of a list (SPEC *Hold still*).
    val bodyConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0) Offset(0f, consume(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0) Offset(0f, consume(available.y)) else Offset.Zero
    }

    // A list inside the header (a place's Direct trains, expanded) scrolls itself first; only what it
    // leaves over moves the header, so its own rows can be scrolled into view and read (Codex, #694).
    val headerConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, consume(available.y))
    }

    companion object {
        // The header's height goes with its offset, so a header measured again at another width keeps
        // showing as much as it did ([measured]).
        val Saver: Saver<ScrollAwayState, List<Float>> = Saver(
            save = { listOf(it.offset, it.headerHeight.toFloat()) },
            restore = { ScrollAwayState(it[0], it[1].toInt()) },
        )
    }
}

@Composable
internal fun rememberScrollAwayState(): ScrollAwayState = rememberSaveable(saver = ScrollAwayState.Saver) { ScrollAwayState() }

/**
 * A page's [header] over its [body], where the header scrolls away as the body scrolls, so a header
 * taller than a short window (a phone on its side, a large font) never leaves the body no room
 * (maintainer, 2026-10-08). The body takes whatever height the header leaves; a drag on the header
 * itself scrolls it too, so a body that doesn't scroll (a placeholder) still lets the header move.
 * Put a pull-to-refresh around this, not inside the body, so a pull down at the top brings the header
 * back before it refreshes.
 */
@Composable
internal fun ScrollAwayHeader(
    modifier: Modifier = Modifier,
    state: ScrollAwayState = rememberScrollAwayState(),
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable () -> Unit,
) {
    val dragState = rememberScrollableState { delta -> state.consume(delta) }
    Layout(
        contents = listOf(
            { Column(Modifier.nestedScroll(state.headerConnection), content = header) },
            { Box(Modifier.nestedScroll(state.bodyConnection)) { body() } },
        ),
        modifier = modifier
            .clipToBounds()
            .scrollable(dragState, Orientation.Vertical),
    ) { (headerMeasurables, bodyMeasurables), constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val headerPlaceables = headerMeasurables.map { it.measure(loose) }
        val headerHeight = headerPlaceables.maxOfOrNull { it.height } ?: 0
        state.measured(headerHeight)
        val offset = state.offset.roundToInt()
        val shown = headerHeight + offset
        val bodyHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - shown).coerceAtLeast(0) else Constraints.Infinity
        val bodyConstraints = constraints.copy(minHeight = if (constraints.hasBoundedHeight) bodyHeight else 0, maxHeight = bodyHeight)
        val bodyPlaceables = bodyMeasurables.map { it.measure(bodyConstraints) }
        val width = constraints.maxWidth.takeIf { constraints.hasBoundedWidth }
            ?: (headerPlaceables + bodyPlaceables).maxOfOrNull { it.width } ?: 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else shown + (bodyPlaceables.maxOfOrNull { it.height } ?: 0)
        layout(width, height) {
            headerPlaceables.forEach { it.place(0, offset) }
            bodyPlaceables.forEach { it.place(0, shown) }
        }
    }
}
