package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.WorkerThread
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyAlertSchedule
import app.stopdash.domain.JourneyAlerts
import app.stopdash.domain.TimeWindow
import app.stopdash.domain.Workers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A favorite journey's alerts as its screen shows them (SPEC *Journeys → Alerts*): the [journey] as
 * saved, each direction's schedule by [JourneyAlerts.directionKey] ([schedules], null while
 * unreadable), and whether Android will show the notifications at all ([notificationsOff]).
 */
data class JourneyAlertsUi(
    val journey: FavoriteJourney,
    val schedules: Map<String, JourneyAlertSchedule>? = emptyMap(),
    val notificationsOff: Boolean = false,
    // A change that couldn't be saved (a disk error), said until dismissed.
    val writeFailed: Boolean = false,
    // The schedules, or whether Android will show the alerts, not known yet: the controls wait, rather
    // than calling the schedules unreadable or having a warning pushed in above them (Codex on #700).
    val loading: Boolean = false,
    // Some journey's alerts are on but location isn't allowed all the time: a card at the foot asks for it,
    // so alerts fire only in London (maintainer, 2026-10-09).
    val askLocation: Boolean = false,
)

/**
 * A favorite journey's alerts, opened from its row in Settings' list: one section per direction, the
 * way it was saved first, each with its own switch, days and time windows. Turning a direction on
 * starts it at its default ([JourneyAlerts.defaultsFor]: the saved way mornings, the way back
 * evenings); off forgets it. UI-only: it reports each change through [onUpdate] as a change to the
 * latest stored schedule, so quick taps each build on the last; it stays Robolectric-renderable
 * without a store.
 */
@Composable
fun JourneyAlertsScreen(
    state: JourneyAlertsUi,
    onBack: () -> Unit,
    onUpdate: (directionKey: String, change: (JourneyAlertSchedule?) -> JourneyAlertSchedule?) -> Unit,
    onAllowNotifications: () -> Unit = {},
    onDismissWriteError: () -> Unit = {},
    onAllowLocation: () -> Unit = {},
    // Where the time dialog's starting window is worked out (AGENTS.md *Main thread*).
    compute: CoroutineDispatcher = Workers.compute,
) {
    BackHandler(onBack = onBack)
    val journey = state.journey
    // The window being edited: its direction key and the window as it was ("" for a new one), by
    // value rather than position, so an edit lands on the window tapped whatever changed meanwhile.
    var editing by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.journey_alerts_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                }
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = stringResource(R.string.journey_title_both_ways, journey.from.name, journey.to.name),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.journey_alerts_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.notificationsOff) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp).testTag("journeyAlertsNotificationsOff"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.journey_alerts_notifications_off),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onAllowNotifications) { Text(stringResource(R.string.journey_alerts_notifications_allow)) }
                        }
                    }
                    val schedules = state.schedules
                    if (state.loading) {
                        Unit
                    } else if (schedules == null) {
                        Text(
                            text = stringResource(R.string.favorite_journeys_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        val defaults = JourneyAlerts.defaultsFor(journey)
                        listOf(journey, journey.reversed()).forEach { way ->
                            val key = JourneyAlerts.directionKey(way, way.from.stopId)
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            DirectionSection(
                                way = way,
                                directionKey = key,
                                schedule = schedules[key],
                                onToggle = { on ->
                                    val start = defaults.getValue(key)
                                    onUpdate(key) { current -> if (on) current ?: start else null }
                                },
                                onUpdate = { change -> onUpdate(key) { current -> current?.let(change) } },
                                onEdit = { window -> editing = key to (window?.let(::windowKey) ?: "") },
                            )
                        }
                    }
                    // At the foot, below the controls, so it moves nothing the rider is reading or tapping.
                    if (state.askLocation && !state.loading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LondonOnlyCard(onAllowLocation, Modifier.padding(horizontal = 16.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            // Over the screen's foot rather than in its flow: a save that fails a moment after a tap mustn't
            // move the controls the rider is still tapping (Codex on #700), and wherever it's scrolled it shows.
            if (state.writeFailed) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).testTag("journeyAlertsWriteFailed"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.journey_alerts_write_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissWriteError) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }
            }
        }
    }
    val target = editing
    val schedule = target?.let { state.schedules?.get(it.first) }
    // Worked out off the main thread as the dialog opens; it appears once that's done, a moment later.
    val edit by produceState<WindowEdit?>(null, target, schedule) {
        value = if (target == null || schedule == null) null else windowEdit(target.second, schedule, compute)
    }
    val shown = edit
    if (target != null && schedule != null && shown != null) {
        val key = target.first
        val old = shown.old
        WindowDialog(
            initial = shown.initial,
            taken = shown.taken,
            onDismiss = { editing = null },
            onSave = { window ->
                onUpdate(key) { current ->
                    current?.copy(windows = (current.windows.filter { it != old } + window).distinct().sortedBy { it.start }.take(JourneyAlertSchedule.MAX_WINDOWS))
                }
                editing = null
            },
        )
    }
}

/** The time dialog's model: the window edited ([old], null for a new one), where it starts, and the others. */
internal data class WindowEdit(val old: TimeWindow?, val initial: TimeWindow, val taken: Set<TimeWindow>)

/** The dialog for the window keyed [was] ("" for a new one) in [schedule], worked out on [compute]. */
internal suspend fun windowEdit(was: String, schedule: JourneyAlertSchedule, compute: CoroutineDispatcher): WindowEdit = withContext(compute) {
    val old = schedule.windows.firstOrNull { windowKey(it) == was }
    WindowEdit(old, old ?: nextWindow(schedule), schedule.windows.filterTo(HashSet()) { it != old })
}

@Composable
private fun DirectionSection(
    way: FavoriteJourney,
    directionKey: String,
    schedule: JourneyAlertSchedule?,
    onToggle: (Boolean) -> Unit,
    onUpdate: ((JourneyAlertSchedule) -> JourneyAlertSchedule) -> Unit,
    // Opens the window editor on a window, or on a new one (null).
    onEdit: (TimeWindow?) -> Unit,
) {
    val on = schedule != null
    val switchDescription = stringResource(R.string.journey_alerts_direction_switch, way.from.name, way.to.name)
    val spokenTitle = stringResource(R.string.journey_title_spoken, way.from.name, way.to.name)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("alerts-$directionKey"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.journey_title, way.from.name, way.to.name),
                style = MaterialTheme.typography.titleMedium,
                // Spoken as the other journey headings are, not as the arrow symbol (Codex on #700).
                modifier = Modifier.weight(1f).semantics { contentDescription = spokenTitle },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = on,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .testTag("alertsSwitch-$directionKey")
                    .semantics { contentDescription = switchDescription },
            )
        }
        if (schedule != null) {
            DayToggleRow(
                selected = schedule.days,
                enabled = true,
                rowTag = "alertDays-$directionKey",
                dayTagPrefix = "alertDay-$directionKey-",
                // The last day can't be turned off: a direction with none would read as on yet never alert;
                // its switch is how it's turned off.
                onToggle = { day ->
                    onUpdate { it.copy(days = if (day !in it.days) it.days + day else if (it.days.size > 1) it.days - day else it.days) }
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Likewise the last window has no remove: the switch turns the direction off.
                val removable = schedule.windows.size > 1
                schedule.windows.forEach { window ->
                    WindowChip(
                        window = window,
                        onEdit = { onEdit(window) },
                        onRemove = if (removable) {
                            { onUpdate { it.copy(windows = (it.windows - window).ifEmpty { it.windows }) } }
                        } else {
                            null
                        },
                    )
                }
                // At most a few windows, so the screen stays bounded (Codex on #700).
                if (schedule.windows.size < JourneyAlertSchedule.MAX_WINDOWS) {
                    TextButton(onClick = { onEdit(null) }, modifier = Modifier.testTag("addTime-$directionKey")) {
                        Text(stringResource(R.string.journey_alerts_add_time))
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowChip(window: TimeWindow, onEdit: () -> Unit, onRemove: (() -> Unit)?) {
    val text = windowText(window)
    val editDescription = stringResource(R.string.journey_alerts_edit_time, text)
    val removeDescription = stringResource(R.string.journey_alerts_remove_time, text)
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .semantics { contentDescription = editDescription }
                // Symmetric 12dp without a remove button beside it.
                .padding(start = 12.dp, end = if (onRemove == null) 12.dp else 4.dp, top = 12.dp, bottom = 12.dp),
        )
        if (onRemove != null) {
            Text(
                text = "×",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = removeDescription }
                    .padding(12.dp),
            )
        }
    }
}

// A start and an end, each typed as hours and minutes; Save is offered only for a window that opens
// before it closes and isn't one of the direction's others ([taken]), which saving would drop.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowDialog(initial: TimeWindow, taken: Set<TimeWindow>, onDismiss: () -> Unit, onSave: (TimeWindow) -> Unit) {
    val start = rememberTimePickerState(initial.start.hour, initial.start.minute, is24Hour = true)
    val end = rememberTimePickerState(initial.end.hour, initial.end.minute, is24Hour = true)
    val window = TimeWindow(LocalTime.of(start.hour, start.minute), LocalTime.of(end.hour, end.minute))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(window) }, enabled = window.valid && window !in taken) { Text(stringResource(R.string.journey_alerts_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.journey_alerts_window_start), style = MaterialTheme.typography.labelLarge)
                TimeInput(state = start)
                Text(stringResource(R.string.journey_alerts_window_end), style = MaterialTheme.typography.labelLarge)
                TimeInput(state = end)
                val problem = when {
                    !window.valid -> R.string.journey_alerts_window_invalid
                    window in taken -> R.string.journey_alerts_window_taken
                    else -> null
                }
                if (problem != null) {
                    Text(
                        stringResource(problem),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

// Called off the main thread ([windowEdit]). A new window starts at a default one not already there, else the first free hour from the end of
// the last, wrapping round the day: never one already set, which saving would drop (Codex on #700).
@WorkerThread
internal fun nextWindow(schedule: JourneyAlertSchedule): TimeWindow {
    JourneyAlertSchedule.DEFAULT_WINDOWS.firstOrNull { it !in schedule.windows }?.let { return it }
    val from = schedule.windows.maxOfOrNull { it.end }?.hour?.coerceAtMost(22) ?: 0
    // The hours 00–01 through 22–23, starting from the end of the last window.
    return (0..22).map { (from + it) % 23 }
        .map { TimeWindow(LocalTime.of(it, 0), LocalTime.of(it + 1, 0)) }
        .firstOrNull { it !in schedule.windows }
        ?: JourneyAlertSchedule.DEFAULT_WINDOWS.first()
}

// A window as one saveable string, to find it again by value.
private fun windowKey(window: TimeWindow): String = "${window.start}-${window.end}"

internal val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun windowText(window: TimeWindow): String =
    stringResource(R.string.journey_alerts_window, window.start.format(TIME), window.end.format(TIME))
