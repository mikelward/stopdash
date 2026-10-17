package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material **help_outline** glyph, a "?" in a circle, vendored as an [ImageVector] since
 * `material-icons-core` doesn't ship it — the same vendoring as [CalendarIcon], from Google's own 24dp
 * path data. It marks what couldn't be checked on the home screen's disruptions card (maintainer,
 * 2026-10-17: outlined, where the ⚠ marks what is known disrupted).
 */
val HelpOutlineIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "HelpOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M11,18h2v-2h-2v2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                    "M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-2.21,0 -4,1.79" +
                    " -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5 0,-2.21" +
                    " -1.79,-4 -4,-4z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
