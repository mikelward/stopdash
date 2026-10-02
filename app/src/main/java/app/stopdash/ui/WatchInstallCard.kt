package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R

/** What the near-me "StopDash for your watch" card does when answered; null shows no card. */
@Immutable
class WatchInstallActions(val onInstall: () -> Unit, val onDismiss: () -> Unit)

/**
 * Offers StopDash to a connected watch that doesn't have it (SPEC *Wear OS*), atop the near-me
 * list: a watch app is otherwise found only by searching Play on the watch. Either answer takes it
 * away for good, as Settings keeps its own way to install, so the card asks once and stays gone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchInstallCard(actions: WatchInstallActions, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag("watchInstall")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.watch_install_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.watch_install_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Wraps like the telemetry question's answers where both don't fit on one line.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = actions.onDismiss) {
                    Text(stringResource(R.string.watch_install_dismiss))
                }
                Button(onClick = actions.onInstall) {
                    Text(stringResource(R.string.watch_install_accept))
                }
            }
        }
    }
}
