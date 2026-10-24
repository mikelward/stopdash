package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Material **remove** glyph (a minus sign) vendored as an [ImageVector], since
 * `material-icons-core` doesn't ship it, from Google's own 24dp path data (`M19 13H5v-2h14v2z`). It
 * makes text one percent smaller beside the text size slider. The path is built from nodes rather
 * than parsed from a string, so composition reading it does no parsing on the main thread.
 */
val RemoveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Remove",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 13f)
            horizontalLineTo(5f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(2f)
            close()
        }
    }.build()
}
