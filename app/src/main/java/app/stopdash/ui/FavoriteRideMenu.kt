package app.stopdash.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.stopdash.R

/**
 * A stop's long press on a route page's stop list or line map (maintainer, 2026-10-24: a long press opens a menu
 * everywhere, and none saves on its own): "Add favorite ride", or "Remove favorite ride" where one ends there
 * already ([starred]), saved or removed by [onToggle] once picked.
 */
@Composable
internal fun FavoriteRideMenu(expanded: Boolean, onDismiss: () -> Unit, starred: Boolean, onToggle: () -> Unit) {
    StopDashMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (starred) R.string.action_unstar_journey else R.string.action_star_journey)) },
            onClick = {
                onDismiss()
                onToggle()
            },
            modifier = Modifier.testTag("stopRideToggle"),
        )
    }
}
