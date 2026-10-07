package app.stopdash.ui

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
 * The longest walk a trip's routes may take, under the walking speed (maintainer, 2026-09-30): "Max
 * walk" and the current limit, which opens a menu of them. In Settings, tagged [tag]; atop a trip's
 * routes it's a chip of [TripPlanOptionChips], the same setting either way. Until the stored choice is
 * read ([enabled] false) it shows no limit and opens nothing, so the default can't pass for the
 * rider's choice.
 */
@Composable
internal fun MaxWalkPicker(
    maxWalk: MaxWalk,
    onChange: (MaxWalk) -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
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
 * platform) or Fully (to the train as well). In Settings, tagged [tag]; atop a trip's routes it's a
 * chip of [TripPlanOptionChips], the same setting either way. Until the stored choice is read
 * ([enabled] false) it shows none and opens nothing.
 */
@Composable
internal fun StepFreePicker(
    stepFree: StepFree,
    onChange: (StepFree) -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
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
 * A setting in Settings: its [title], and the [selected] option opening a menu of [options],
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
            PickerMenu(expanded && enabled, { expanded = false }, tag, options, label, detail, onChange)
        }
    }
}

/**
 * The walking speed, max walk and step-free atop a trip's routes, as one row of dropdown chips
 * (maintainer, 2026-10-07) rather than a row each, so the routes start two rows higher. Each chip
 * shows its pick by an icon or a short word, as tagged and announced as Settings' full rows ("Walking
 * speed, Medium"); a setting whose callback is null is left out. The row scrolls sideways when the
 * chips don't fit. Until the stored choices are read ([enabled] false) none shows a pick or opens.
 */
@Composable
internal fun TripPlanOptionChips(
    walkingSpeed: WalkingSpeed,
    onWalkingSpeedChange: ((WalkingSpeed) -> Unit)?,
    maxWalk: MaxWalk,
    onMaxWalkChange: ((MaxWalk) -> Unit)?,
    stepFree: StepFree,
    onStepFreeChange: ((StepFree) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (onWalkingSpeedChange == null && onMaxWalkChange == null && onStepFreeChange == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tripPlanOptions")
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onWalkingSpeedChange != null) {
            PickerChip(
                title = stringResource(R.string.walking_speed_title),
                tag = "walkingSpeed",
                selected = walkingSpeed,
                options = WalkingSpeed.entries,
                label = { walkingSpeedLabel(it) },
                onChange = onWalkingSpeedChange,
                enabled = enabled,
                leading = { ChipIcon(R.drawable.ic_walk) },
            )
        }
        if (onMaxWalkChange != null) {
            PickerChip(
                title = stringResource(R.string.max_walk_title),
                tag = "maxWalk",
                selected = maxWalk,
                options = MaxWalk.entries,
                label = { stringResource(R.string.max_walk_minutes, it.minutes) },
                onChange = onMaxWalkChange,
                enabled = enabled,
                // "Max 20 min": the word says it's a limit where a walking icon alone would read as the walk.
                prefix = stringResource(R.string.max_walk_chip_prefix),
            )
        }
        if (onStepFreeChange != null) {
            PickerChip(
                title = stringResource(R.string.step_free_title),
                tag = "stepFree",
                selected = stepFree,
                options = StepFree.entries,
                label = { stepFreeLabel(it) },
                detail = { stepFreeDetail(it) },
                onChange = onStepFreeChange,
                enabled = enabled,
                leading = { ChipIcon(R.drawable.ic_step_free) },
            )
        }
    }
}

@Composable
private fun ChipIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
}

/**
 * One of [TripPlanOptionChips]: the [selected] option, after its [leading] icon or [prefix] word,
 * opening the same menu as [PickerRow]; announced by its [title] and pick.
 */
@Composable
private fun <T : Enum<T>> PickerChip(
    title: String,
    tag: String,
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
    enabled: Boolean,
    detail: @Composable (T) -> String? = { null },
    leading: (@Composable () -> Unit)? = null,
    prefix: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = if (enabled) label(selected) else "–"
    val accent = MaterialTheme.colorScheme.primary
    Box {
        AssistChip(
            onClick = { expanded = true },
            enabled = enabled,
            label = {
                Text(
                    buildAnnotatedString {
                        prefix?.let {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(it) }
                            append(" ")
                        }
                        append(current)
                    },
                    maxLines = 1,
                )
            },
            leadingIcon = leading,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = accent,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconContentColor = accent,
            ),
            modifier = Modifier.testTag(tag).semantics { contentDescription = "$title, $current" },
        )
        PickerMenu(expanded && enabled, { expanded = false }, tag, options, label, detail, onChange)
    }
}

/** A picker's menu: each of [options] by its [label] over its [detail], tagged `"$tag-NAME"`. */
@Composable
private fun <T : Enum<T>> PickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tag: String,
    options: List<T>,
    label: @Composable (T) -> String,
    detail: @Composable (T) -> String?,
    onChange: (T) -> Unit,
) {
    // Styled as every StopDash menu is, the overflow menu's included (maintainer, 2026-10-03).
    StopDashMenu(expanded = expanded, onDismissRequest = onDismiss) {
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
                    onDismiss()
                    onChange(option)
                },
                modifier = Modifier.testTag("$tag-${option.name}"),
            )
        }
    }
}
