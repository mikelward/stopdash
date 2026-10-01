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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R

/**
 * The "Help make StopDash better" question, put atop the near-me list to an install that never
 * answered it (SPEC *Privacy*), as the sibling apps do: stopdash has no onboarding to ask it in, and
 * the Settings switch alone is one a rider may never find. Off stays the default.
 *
 * Both buttons give an answer ([onAnswer]), stored like the switch's, because a question that can
 * only be walked away from isn't one that was asked: a "no" is recorded, so the card doesn't return.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TelemetryInviteCard(onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag("telemetryInvite")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.telemetry_invite_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.telemetry_invite_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Wraps the two answers onto a line each where both don't fit (a large text size, a narrow
            // window), rather than squeeze one (Codex, PR #447).
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { onAnswer(false) }) {
                    Text(stringResource(R.string.telemetry_invite_decline))
                }
                Button(onClick = { onAnswer(true) }) {
                    Text(stringResource(R.string.telemetry_invite_accept))
                }
            }
        }
    }
}
