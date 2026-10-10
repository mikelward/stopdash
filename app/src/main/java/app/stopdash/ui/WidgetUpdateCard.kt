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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R

/**
 * What the near-me "Automatically update widget?" card does when answered; null shows no card.
 * [onShown] is told once the card is actually on screen, so the caller can keep it in place from then.
 */
@Immutable
class WidgetUpdateActions(val onSettings: () -> Unit, val onDismiss: () -> Unit, val onShown: () -> Unit = {})

/**
 * Points a rider with a StopDash widget placed, and automatic updates off, at the widget settings
 * (SPEC *Widget*), atop the near-me list: the widget otherwise updates only when the app runs.
 * Settings opens Settings at the widget rows; Dismiss takes the card away for good. Laid out as the
 * watch card ([WatchInstallCard]) is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WidgetUpdateCard(actions: WidgetUpdateActions, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { actions.onShown() }
    Card(modifier = modifier.fillMaxWidth().testTag("widgetUpdate")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.widget_update_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.widget_update_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = actions.onDismiss) {
                    Text(stringResource(R.string.widget_update_dismiss))
                }
                Button(onClick = actions.onSettings) {
                    Text(stringResource(R.string.widget_update_settings))
                }
            }
        }
    }
}
