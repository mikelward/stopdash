package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material **swap_horiz** glyph (two opposed arrows) vendored as an [ImageVector], since
 * `material-icons-core` doesn't ship it — the same vendoring as [StarBorderIcon], from Google's own
 * 24dp path data. It swaps a favorite journey's direction.
 */
val SwapIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SwapHoriz",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
