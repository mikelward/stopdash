package app.stopdash.ui

import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.stopdash.R
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.LegTimetables
import app.stopdash.domain.ScheduledDay
import app.stopdash.domain.StopTimetable
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Where a route page's timetable comes from (maintainer, 2026-10-22): the line's at the page's stop, one way's trains
 * picked out by the stops it calls at ([Stop.path], [StopTimetable.along]; every route's where null), or a ride's, the
 * trains from where it boards that call where it gets off ([Ride]).
 */
internal sealed interface TimetableSource {
    /** [lineId]'s timetable at [stopId] ([app.stopdash.domain.TimetableRepository]), the one the board's empty rows read. */
    data class Stop(val stopId: String, val lineId: String, val path: List<String>?) : TimetableSource

    /** A ride's timetable ([LegTimetables]), the one its next trains are read from. */
    data class Ride(val key: LegTimetables.Key) : TimetableSource
}

/** What a route page's timetable shows for the way it's on. */
internal sealed interface TimetableAsk {
    /** [source]'s times, the way named by [label] where no chips name it. */
    data class Of(val source: TimetableSource, val label: String?) : TimetableAsk

    /** Several ways, none chosen: the chips above choose one. */
    data object Choose : TimetableAsk

    /** A way only a live train names, whose stops the timetable can't tell apart from another's. */
    data object Unknown : TimetableAsk

    /** The ways are still being worked out. */
    data object Waiting : TimetableAsk
}

/** A timetable as far as it's in: being fetched, failed (asked again shortly), or found. */
internal sealed interface TimetableLookup {
    data object Loading : TimetableLookup

    data object Failed : TimetableLookup

    data class Found(val timetable: StopTimetable) : TimetableLookup
}

/** The mark id a route page's timetable is watched under ([EmptyTimesState.watch]), apart from any board's. */
internal fun timetableWatchId(key: EmptyTimes.Key): String = "timetable:${key.stopId}|${key.lineId}"

/**
 * [source]'s timetable as far as it's in, asked for again each minute of [now] while it isn't (a failure is asked
 * again once its retry is due): a stop's from the board's timetables ([LocalEmptyTimes]), a ride's from the rides'
 * ([LocalLegTimetables]). One request per stop and line, or per ride, a service day, shared with what the board and
 * the ride's next trains already fetched. A ride's answer shows only for the ride it was fetched for ([Worked]).
 */
@Composable
internal fun rememberTimetableLookup(source: TimetableSource?, now: Instant): TimetableLookup {
    val minute = now.epochSecond / 60
    return when (source) {
        null -> TimetableLookup.Loading
        is TimetableSource.Stop -> {
            val state = LocalEmptyTimes.current
            val key = EmptyTimes.Key(source.stopId, source.lineId)
            val watch by rememberUpdatedState(state.watch)
            LaunchedEffect(key, minute) { watch(timetableWatchId(key)) { EmptyTimes.Board(listOf(key)) } }
            // Only this stop and line's timetable, so another's landing doesn't recompose the page. Kept with the key it
            // was read for: a page moved on to another stop holds the last one's state until its own is collected, and
            // that's never this stop's answer (Codex on #1081).
            val keyed by remember(state.lookups, key) { state.lookups.map { key to it[key] }.distinctUntilChanged() }.collectAsState(null)
            when (val found = keyed?.takeIf { it.first == key }?.second) {
                null -> TimetableLookup.Loading
                EmptyTimes.Lookup.Failed -> TimetableLookup.Failed
                is EmptyTimes.Lookup.Found -> TimetableLookup.Found(found.timetable)
            }
        }
        is TimetableSource.Ride -> {
            val timetables = LocalLegTimetables.current ?: return TimetableLookup.Failed
            val worker = LocalWorker.current
            val key = source.key
            val answer by produceState<Worked<LegTimetables.Key, TimetableLookup>?>(null, timetables, worker, key, minute) {
                val known = withContext(worker) {
                    timetables.fetch(listOf(key))
                    timetables.known()
                }
                value = Worked(
                    key,
                    // TfL having none for the ride is an empty timetable, said as such; absent is a failure.
                    if (key in known.timetables) TimetableLookup.Found(known.timetables[key] ?: StopTimetable(emptyList())) else TimetableLookup.Failed,
                )
            }
            answer?.takeIf { it.key == key }?.value ?: TimetableLookup.Loading
        }
    }
}

/** One service day of a timetable as the page lays it out ([ScheduledDay]), its times written out on the worker. */
internal class TimetableDay(
    val day: LocalDate,
    val rows: List<TimetableRow>,
    val holiday: Boolean,
    val partial: Boolean,
    val unscheduled: Boolean,
) {
    companion object {
        /** [timetable]'s service [day], along [path] where there is one ([StopTimetable.along]). */
        @WorkerThread
        fun of(timetable: StopTimetable, path: List<String>?, day: LocalDate): TimetableDay {
            val scheduled = ScheduledDay.of(if (path != null) timetable.along(path) else timetable, day)
            val rows = scheduled.stretches.map { stretch ->
                when (stretch) {
                    is ScheduledDay.Stretch.Frequent -> TimetableRow.Every(clock(stretch.first), clock(stretch.last), stretch.shortest, stretch.longest, stretch.last)
                    is ScheduledDay.Stretch.Times -> TimetableRow.Hour(stretch.minutes.map { TimetableRow.Time(clock(it), it) })
                }
            }
            return TimetableDay(day, rows, scheduled.holiday, scheduled.partial, scheduled.unscheduled)
        }

        // A time as the clock reads it, past midnight from 00:00 again.
        private fun clock(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)
    }
}

/** A line of a [TimetableDay]: a frequency over a stretch, or an hour's trains listed. [last] is its last train's minute. */
internal sealed interface TimetableRow {
    val last: Int

    class Every(val from: String, val to: String, val shortest: Int, val longest: Int, override val last: Int) : TimetableRow

    class Hour(val times: List<Time>) : TimetableRow {
        override val last: Int get() = times.last().minute
    }

    class Time(val text: String, val minute: Int)
}

/**
 * [lookup]'s service [day] along [path] ([TimetableDay]), worked out on the worker; null while it is, or while the
 * timetable isn't in. Only ever for the [source], timetable, way and day asked: another stop's or day's times never
 * stand under one just moved to.
 */
@Composable
internal fun rememberTimetableDay(source: TimetableSource?, lookup: TimetableLookup, path: List<String>?, day: LocalDate): TimetableDay? {
    val timetable = (lookup as? TimetableLookup.Found)?.timetable
    // The source by its ids, which compare by value ([Inputs]).
    val from = when (source) {
        is TimetableSource.Stop -> "${source.stopId}|${source.lineId}"
        is TimetableSource.Ride -> "${source.key.lineId}|${source.key.fromId}|${source.key.toId}"
        null -> null
    }
    val slot = remember { mutableStateOf<Worked<Inputs, TimetableDay?>?>(null) }
    return rememberWorked(slot, Inputs(from, timetable, path, day)) { timetable?.let { TimetableDay.of(it, path, day) } }
}

/**
 * A route page's timetable (maintainer, 2026-10-22), over the page in a window of its own, so the page keeps its
 * place for Back. Shown whatever the line's status or the stop's notices: the page's own [notices] head it, so its
 * times are never read as a promise the line can't keep. See [TimetableScreen].
 */
@Composable
internal fun TimetablePage(
    lineId: String,
    lineName: String,
    mode: String,
    place: String,
    ask: TimetableAsk,
    now: Instant,
    onClose: () -> Unit,
    chips: (@Composable () -> Unit)? = null,
    notices: @Composable () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog's own Back, as a gesture the page follows: the route page under it shows as it goes.
        val follow = rememberBackFollow()
        PredictiveBack(follow, onBack = onClose)
        TimetableScreen(lineId, lineName, mode, place, ask, now, onClose, chips, notices, Modifier.followsBack(follow))
    }
}

/**
 * A route page's timetable: the line's pill and "Timetable" in its bar; the page's [notices] first (its line's
 * status, the stop's closures, whether they could be checked), then where it's from ([place]), the way ([chips], or
 * its label), today's or tomorrow's service day, and under "Scheduled" the day's trains ([TimetableDay]): each stretch
 * where they come often as "06:00–10:00 every 4 min", the rest by time, an hour to a line. Today's trains gone by
 * [now] are grayed. What the times may lack is said under the heading: a holiday, a train that couldn't be read.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TimetableScreen(
    lineId: String,
    lineName: String,
    mode: String,
    place: String,
    ask: TimetableAsk,
    now: Instant,
    onClose: () -> Unit,
    chips: (@Composable () -> Unit)? = null,
    notices: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Today's service day, or the next.
    var later by rememberSaveable { mutableIntStateOf(0) }
    val today = ScheduledDay.serviceDay(now)
    val day = today.plusDays(later.toLong())
    val source = (ask as? TimetableAsk.Of)?.source
    val lookup = rememberTimetableLookup(source, now)
    val path = (source as? TimetableSource.Stop)?.path
    val shown = rememberTimetableDay(source, lookup, path, day)
    // Trains gone by are today's alone: tomorrow's are all to come.
    val nowMinute = if (later == 0) ScheduledDay.minuteOf(now, today) else Long.MIN_VALUE
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("timetablePage"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinePill(lineName = lineName, lineId = lineId, mode = mode)
                        Text(
                            text = stringResource(R.string.route_detail_timetable_heading),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // Each notice brings its own space above it, as on the route page.
            notices()
            if (place.isNotBlank()) {
                Text(
                    text = stringResource(R.string.route_detail_from, place),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            val label = (ask as? TimetableAsk.Of)?.label
            if (chips != null) {
                Row(Modifier.padding(top = 8.dp)) { chips() }
            } else if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = later == 0, onClick = { later = 0 }, label = { Text(stringResource(R.string.trip_time_today)) })
                FilterChip(selected = later == 1, onClick = { later = 1 }, label = { Text(stringResource(R.string.trip_time_tomorrow)) })
            }
            Text(
                text = stringResource(R.string.timetable_scheduled),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { heading() },
            )
            when {
                ask == TimetableAsk.Choose -> TimetableNote(R.string.timetable_choose_route)
                ask == TimetableAsk.Unknown -> TimetableNote(R.string.timetable_route_unknown)
                lookup == TimetableLookup.Failed -> TimetableNote(R.string.timetable_failed)
                shown == null -> TimetableNote(R.string.timetable_loading)
                shown.unscheduled -> TimetableNote(R.string.timetable_unavailable)
                else -> {
                    if (shown.holiday) TimetableNote(R.string.timetable_holiday)
                    if (shown.partial) TimetableNote(R.string.timetable_partial)
                    if (shown.rows.isEmpty()) TimetableNote(R.string.timetable_none)
                    val gone = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    shown.rows.forEach { row ->
                        when (row) {
                            is TimetableRow.Every -> {
                                val every = if (row.shortest == row.longest) {
                                    stringResource(R.string.timetable_every, row.shortest)
                                } else {
                                    stringResource(R.string.timetable_every_range, row.shortest, row.longest)
                                }
                                val spoken = if (row.shortest == row.longest) {
                                    pluralStringResource(R.plurals.timetable_every_spoken, row.shortest, row.from, row.to, row.shortest)
                                } else {
                                    stringResource(R.string.timetable_every_range_spoken, row.from, row.to, row.shortest, row.longest)
                                }
                                val color = if (row.last < nowMinute) gone else MaterialTheme.colorScheme.onSurface
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.timetable_stretch, row.from, row.to),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = color,
                                    )
                                    // Beside its stretch, as it's read ("06:00–10:00 every 4 min"), the gap a list's times keep.
                                    Text(text = every, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                            is TimetableRow.Hour -> FlowRow(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                row.times.forEach { time ->
                                    Text(
                                        text = time.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (time.minute < nowMinute) gone else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A line under a timetable's heading saying what it shows, or why it shows nothing. */
@Composable
private fun TimetableNote(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}
