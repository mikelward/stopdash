package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.AlertStart
import app.stopdash.domain.TripTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A later trip (SPEC *Trips with a change → A later trip*): the Planner's routes for [request]'s time, from its
 * timetable, each card its times leg by leg. Nothing on it is live, and it says so over the routes. A
 * card tapped opens that route in the trip for now ([onOpen]), whose live times then say they're for
 * now. Back, or "Leave now" in the overflow, goes back to the trip for now; "Change time…" picks again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LaterTripScreen(
    title: String,
    // What the screen asks for: the time and the rider's choices as they stand, held across recompositions
    // (remembered on its parts by identity) and handed to the model as it is, so [state] answers this very one.
    request: LaterTripViewModel.Request,
    state: LaterTripViewModel.State,
    // For the days' names ("Today" has no date): the minute the screen shows.
    now: Instant,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onChangeTime: () -> Unit,
    onOpen: (LaterTripViewModel.Card) -> Unit,
    // A one-off notice: a tapped route the trip for now doesn't offer. Said once, then acknowledged.
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            onNoticeShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    AppMenuOverflow { close ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trip_change_time)) },
                            onClick = {
                                close()
                                onChangeTime()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trip_leave_now)) },
                            onClick = {
                                close()
                                onBack()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("laterRoutes"),
        ) {
            item(key = "when") { LaterHeader(request.time, now) }
            // Only the plan for this request, its cards and what went wrong with it: one for another time, or for
            // other choices (a stair-free trip, a mode turned off), never shows here, which reads as planning
            // until this one's is in.
            val current = state.request === request
            val cards = state.cards.takeIf { current }
            val error = state.error.takeIf { current }
            error?.let {
                item(key = "error") { LaterNotice(stringResource(R.string.trip_plan_failed, stringResource(errorMessage(it))), state.planning, onRetry) }
            }
            if (error == null && current && state.incomplete) {
                item(key = "incomplete") { LaterNotice(stringResource(R.string.trip_plan_incomplete), state.planning, onRetry) }
            }
            when {
                cards == null && error == null -> item(key = "planning") {
                    Text(stringResource(R.string.trip_planning), style = MaterialTheme.typography.bodyLarge)
                }
                cards == null -> Unit
                cards.isEmpty() -> item(key = "none") {
                    Text(stringResource(R.string.trip_no_routes), style = MaterialTheme.typography.bodyLarge)
                }
                // Each card's keys and rows worked out with the plan ([LaterTripViewModel.cardOf]): drawn, never derived, here.
                else -> items(cards, key = { it.listKey }) { card -> LaterCard(card) { onOpen(card) } }
            }
        }
    }
}

/** When the trip is planned for, then that its times are the timetable's, not live ones. */
@Composable
private fun LaterHeader(time: TripTime, now: Instant) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        val label = tripTimeLabel(time, now)
        Text(
            stringResource(if (time.kind == TripTime.Kind.LEAVE) R.string.trip_later_leave else R.string.trip_later_arrive, label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("laterWhen"),
        )
        Text(
            stringResource(R.string.trip_later_timetable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LaterNotice(text: String, planning: Boolean, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, enabled = !planning) { Text(stringResource(R.string.try_again)) }
    }
}

/** One route as the timetable has it: when it leaves and gets there, then a row per leg. */
@Composable
private fun LaterCard(card: LaterTripViewModel.Card, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("laterCard")) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fare = card.route.fare
            val fareGap = with(LocalDensity.current) { 12.dp.roundToPx() }
            val lineGap = with(LocalDensity.current) { 4.dp.roundToPx() }
            // As the trip for now's cards: the fare beside the times where both fit whole, else on a
            // line of its own below, at the row's end.
            Layout(
                content = {
                    Text(
                        stringResource(R.string.trip_later_times, card.departs, card.arrives, card.minutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("laterTimes"),
                    )
                    if (fare != null) CardFare(fare)
                },
            ) { measurables, constraints -> cardHeaderLayout(measurables, constraints, fareGap, lineGap) }
            card.rows.forEach { row -> LaterRow(row) }
        }
    }
}

/** A ride: when it leaves, its line, and where it gets off. A walk: where to, and how long. */
@Composable
private fun LaterRow(row: LaterTripViewModel.Row) {
    if (row.walk) {
        Text(
            stringResource(R.string.trip_walk, row.toName, row.minutes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.time, style = MaterialTheme.typography.bodyMedium)
        LinePill(row.lineName, row.lineId, row.mode)
        Text(row.toName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * [time] as the rider reads it: its clock time alone today, else after its day's date ("Sat 18 10:00"),
 * which says which day without needing today's to know it.
 */
@Composable
internal fun tripTimeLabel(time: TripTime, now: Instant): String {
    val clock = time.at.toLocalTime().format(CLOCK)
    val today = now.atZone(AlertStart.ZONE).toLocalDate()
    if (time.at.toLocalDate() == today) return clock
    return stringResource(R.string.trip_time_on, time.at.toLocalDate().format(dayFormat()), clock)
}

@Composable
private fun dayFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", LocalConfiguration.current.locales[0])

/**
 * Picks a later trip's time (SPEC *Trips with a change → A later trip*): leaving at it or arriving by
 * it, on one of the next [TripTime.DAYS] days, at a time typed as hours and minutes. Done is offered
 * only for a time still to come, and one the clocks show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripTimeDialog(initial: TripTime, now: Instant, onDismiss: () -> Unit, onDone: (TripTime) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    val days = remember(now.atZone(AlertStart.ZONE).toLocalDate()) { TripTime.days(now) }
    var day by rememberSaveable { mutableStateOf(initial.at.toLocalDate().takeIf { it in days } ?: days.first()) }
    val clock = rememberTimePickerState(initial.at.hour, initial.at.minute, is24Hour = true)
    val typed = LocalTime.of(clock.hour, clock.minute)
    val picked = TripTime.of(kind, day, typed)
    val past = picked.isPast(now)
    // A time the clocks skip would be planned as the one it becomes, not the one still shown: refused.
    val skipped = !past && TripTime.skipped(day, typed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_time_title)) },
        confirmButton = {
            TextButton(onClick = { onDone(picked) }, enabled = !past && !skipped) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val kinds = listOf(TripTime.Kind.LEAVE to R.string.trip_time_leave, TripTime.Kind.ARRIVE to R.string.trip_time_arrive)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { index, (option, label) ->
                        SegmentedButton(
                            selected = kind == option,
                            onClick = { kind = option },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size),
                            modifier = Modifier.testTag("tripTime-${option.name}"),
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    days.forEachIndexed { index, option ->
                        FilterChip(
                            selected = day == option,
                            onClick = { day = option },
                            label = { Text(dayName(option, index), maxLines = 1) },
                            modifier = Modifier.testTag("tripDay-$index"),
                        )
                    }
                }
                TimeInput(state = clock)
                if (past || skipped) {
                    Text(
                        stringResource(if (past) R.string.trip_time_past else R.string.trip_time_skipped),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

// The picker's days: today and tomorrow by name, the rest by date ("Sat 18").
@Composable
private fun dayName(day: LocalDate, index: Int): String = when (index) {
    0 -> stringResource(R.string.trip_time_today)
    1 -> stringResource(R.string.trip_time_tomorrow)
    else -> day.format(dayFormat())
}
