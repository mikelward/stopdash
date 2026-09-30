package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material **calendar_today** glyph (outlined) vendored as an [ImageVector], since
 * `material-icons-core` doesn't ship it — the same vendoring as [SwapIcon], from Google's own 24dp
 * path data. It marks planned work that starts on a later day: a date to come, where the circled
 * "i" it replaced read as a clock at row size (maintainer, 2026-09-30).
 */
val CalendarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "CalendarToday",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M20 3h-1V1h-2v2H7V1H5v2H4c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V5c0-1.1" +
                    "-.9-2-2-2zm0 18H4V10h16v11zm0-13H4V5h16v3z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
