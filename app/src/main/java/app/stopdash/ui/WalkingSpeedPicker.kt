package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.WalkingSpeed

/** The label each [WalkingSpeed] is shown by: the Planner's "Average" reads as "Medium". */
@Composable
internal fun walkingSpeedLabel(speed: WalkingSpeed): String = stringResource(
    when (speed) {
        WalkingSpeed.SLOW -> R.string.walking_speed_slow
        WalkingSpeed.AVERAGE -> R.string.walking_speed_average
        WalkingSpeed.FAST -> R.string.walking_speed_fast
    },
)

/**
 * The walking speed atop a trip's routes (maintainer, 2026-09-28): "Walking speed" and the current
 * pace, which opens a menu of the three. The same setting as Settings' row, so a pick here holds
 * for later trips too; the trip plans again at the new pace ([TripViewModel.walkingSpeed]).
 */
@Composable
internal fun WalkingSpeedPicker(speed: WalkingSpeed, onChange: (WalkingSpeed) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(R.string.walking_speed_title)
    val current = walkingSpeedLabel(speed)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("walkingSpeed").semantics { contentDescription = "$title, $current" },
            ) {
                Text(current)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                WalkingSpeed.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(walkingSpeedLabel(option)) },
                        onClick = {
                            expanded = false
                            onChange(option)
                        },
                        modifier = Modifier.testTag("walkingSpeed-${option.name}"),
                    )
                }
            }
        }
    }
}
