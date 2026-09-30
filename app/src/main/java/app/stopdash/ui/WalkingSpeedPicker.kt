package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
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
internal fun WalkingSpeedPicker(
    speed: WalkingSpeed,
    onChange: (WalkingSpeed) -> Unit,
    modifier: Modifier = Modifier,
    // False until the stored choice is read: shows no pace and opens nothing, as [MaxWalkPicker].
    enabled: Boolean = true,
) {
    PickerRow(
        title = stringResource(R.string.walking_speed_title),
        tag = "walkingSpeed",
        selected = speed,
        options = WalkingSpeed.entries,
        label = { walkingSpeedLabel(it) },
        onChange = onChange,
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * The longest walk a trip's routes may take, under the walking speed (maintainer, 2026-09-30): "Max
 * walk" and the current limit, which opens a menu of them. Atop a trip's routes, where a pick plans
 * again under the new limit ([TripViewModel.maxWalk]), and in Settings, tagged [tag] there; the same
 * setting either way. Until the stored choice is read ([enabled] false) it shows no limit and opens
 * nothing, so the default can't pass for the rider's choice.
 */
@Composable
internal fun MaxWalkPicker(
    maxWalk: MaxWalk,
    onChange: (MaxWalk) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "maxWalk",
    enabled: Boolean = true,
) {
    PickerRow(
        title = stringResource(R.string.max_walk_title),
        tag = tag,
        selected = maxWalk,
        options = MaxWalk.entries,
        label = { stringResource(R.string.max_walk_minutes, it.minutes) },
        onChange = onChange,
        modifier = modifier,
        enabled = enabled,
    )
}

/** The label each [StepFree] level is shown by: the maintainer's names (2026-09-30). */
@Composable
internal fun stepFreeLabel(stepFree: StepFree): String = stringResource(
    when (stepFree) {
        StepFree.ANY -> R.string.step_free_any
        StepFree.STATION -> R.string.step_free_station
        StepFree.FULLY -> R.string.step_free_fully
    },
)

/**
 * The line under each [StepFree] level in its menu, saying what it's for (Codex, #408): the bare
 * names don't tell a rider with a suitcase or a buggy that Station suits them. Any needs none.
 */
@Composable
internal fun stepFreeDetail(stepFree: StepFree): String? = when (stepFree) {
    StepFree.ANY -> null
    StepFree.STATION -> stringResource(R.string.step_free_station_detail)
    StepFree.FULLY -> stringResource(R.string.step_free_fully_detail)
}

/**
 * How step-free a trip's routes must be (maintainer, 2026-09-30): "Step-free" and Any, Station (to the
 * platform) or Fully (to the train as well). Atop a trip's routes, where a pick plans again
 * ([TripViewModel.stepFree]), and in Settings, tagged [tag] there; the same setting either way.
 * Until the stored choice is read ([enabled] false) it shows none and opens nothing.
 */
@Composable
internal fun StepFreePicker(
    stepFree: StepFree,
    onChange: (StepFree) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "stepFree",
    enabled: Boolean = true,
) {
    PickerRow(
        title = stringResource(R.string.step_free_title),
        tag = tag,
        selected = stepFree,
        options = StepFree.entries,
        label = { stepFreeLabel(it) },
        detail = { stepFreeDetail(it) },
        onChange = onChange,
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * A setting atop a trip's routes: its [title], and the [selected] option opening a menu of [options],
 * each shown by its [label] over its [detail], if it has one.
 */
@Composable
private fun <T : Enum<T>> PickerRow(
    title: String,
    tag: String,
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detail: @Composable (T) -> String? = { null },
) {
    var expanded by remember { mutableStateOf(false) }
    val current = if (enabled) label(selected) else "–"
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.testTag(tag).semantics { contentDescription = "$title, $current" },
            ) {
                Text(current)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(label(option))
                                detail(option)?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onChange(option)
                        },
                        modifier = Modifier.testTag("$tag-${option.name}"),
                    )
                }
            }
        }
    }
}
