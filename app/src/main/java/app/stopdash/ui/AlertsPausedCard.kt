package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R

/**
 * Says favorite journey alerts are paused (Pause on an alert, SPEC *Journeys → Alerts*), atop the
 * near-me list, with Unpause: the one way back, so there's no Dismiss and it stays until tapped. Laid
 * out as the other cards atop the list ([WidgetUpdateCard]).
 */
@Composable
internal fun AlertsPausedCard(onUnpause: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag("alertsPaused")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.journey_alerts_paused),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.journey_alerts_paused_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onUnpause) {
                    Text(stringResource(R.string.journey_alerts_unpause))
                }
            }
        }
    }
}
