package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material **schedule** glyph (a clock) vendored as an [ImageVector], since `material-icons-core`
 * doesn't ship it — the same vendoring as [CalendarIcon], from Google's own 24dp path data. It opens a
 * route page's timetable (maintainer, 2026-10-22).
 */
val ScheduleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Schedule",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8" +
                    "-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zM12.5 7H11v6l5.25 3.15.75-1.23-4.5-2.67z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
