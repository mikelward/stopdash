package app.stopdash.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.stopdash.R

/**
 * The "Update available" call to action shown on the loading screens — the departures spinner
 * ([DeparturesUiState.Loading]) and the location gate's Locating spinner — when Google Play reports
 * a newer version (SPEC *Update indicator*). On both, the overflow (with its dot) is already present,
 * so this is a more direct prompt than a dot the user may not notice while waiting. Opens the Play
 * listing via [onClick]. Outlined, not filled, so it stays a secondary offer — the "action" of a
 * loading screen is still to wait for the content.
 *
 * It sits at the bottom of the screen, and its room is kept whether or not an update is pending:
 * with [shown] false it's laid out the same but invisible, disabled and silent, so the screen's own
 * content stays where it is either way. A loading screen puts one not shown at the top too, so
 * that content stays centered between the two.
 */
@Composable
fun UpdateAvailableButton(onClick: () -> Unit, modifier: Modifier = Modifier, shown: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = shown,
        modifier = if (shown) modifier else modifier.alpha(0f).clearAndSetSemantics {},
    ) {
        Text(stringResource(R.string.update_available))
    }
}
