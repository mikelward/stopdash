package app.stopdash.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
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
 * The lines a trip avoids, atop its routes (SPEC *Trips with a change → Avoiding a line*; maintainer,
 * 2026-10-01): "Avoiding", then a chip per line by the name its long press offered it under, which a
 * tap stops avoiding ([onStopAvoiding], the line's [AvoidedLines.key]). Sticky across trips, so the
 * row says on every trip what's left out. Nothing shows while no line is avoided; the row scrolls
 * sideways when the chips don't fit.
 */
@Composable
internal fun AvoidedLineChips(
    avoided: Set<String>,
    onStopAvoiding: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = remember(avoided) { AvoidedLines.labeled(avoided) }
    if (lines.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("avoidedLines")
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.trip_avoiding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.forEach { (entry, label) ->
            val description = stringResource(R.string.trip_stop_avoiding, label)
            InputChip(
                selected = false,
                onClick = { onStopAvoiding(entry) },
                label = { Text(label, maxLines = 1) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(InputChipDefaults.IconSize)) },
                modifier = Modifier.semantics { contentDescription = description }.testTag("avoided-$entry"),
            )
        }
    }
}

/**
 * Which kinds of transport a trip may ride, atop its routes: one chip per mode group, by the names
 * the list's hide-mode menu uses ("Tube & DLR", "Train", "Bus"…), selected while the trip rides it.
 * A tap turns a group off or on and plans again ([TripViewModel.tripModes]); the last group riding
 * stays on, since a trip riding nothing has no route. The row scrolls sideways when the chips don't
 * fit. Until the stored choice is read ([enabled] false) none shows selected and none responds.
 */
@Composable
internal fun TripModeChips(
    modes: TripModes,
    onChange: (TripModes) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // A plain scrolling row, not a lazy one: six chips, all composed, so each can be found and read.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tripModes")
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeGroups.ALL.forEach { group ->
            val rides = modes.rides(group)
            FilterChip(
                selected = enabled && rides,
                onClick = { if (!modes.isLast(group)) onChange(modes.with(group, !rides)) },
                label = { Text(groupName(group), maxLines = 1) },
                enabled = enabled,
                modifier = Modifier.testTag("tripMode-${group.key}"),
            )
        }
    }
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
            // Styled as every StopDash menu is, the overflow menu's included (maintainer, 2026-10-03).
            StopDashMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
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
