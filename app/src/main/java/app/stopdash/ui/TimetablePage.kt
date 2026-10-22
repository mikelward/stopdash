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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.stopdash.domain.RailTimetable
import app.stopdash.domain.RailTimetables
import app.stopdash.domain.ScheduledDay
import app.stopdash.domain.StopTimetable
import app.stopdash.domain.TflException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Where a route page's timetable comes from (maintainer, 2026-10-22): the line's at the page's stop, one way's trains
 * picked out by the stops it calls at ([Stop.path], [StopTimetable.along]; every route's where null), or a ride's, the
 * trains from where it boards that call where it gets off ([Ride]); for a National Rail line, which TfL doesn't time,
 * its station's board over the next few hours ([Rail]).
 */
internal sealed interface TimetableSource {
    /** [lineId]'s timetable at [stopId] ([app.stopdash.domain.TimetableRepository]), the one the board's empty rows read. */
    data class Stop(val stopId: String, val lineId: String, val path: List<String>?) : TimetableSource

    /** A ride's timetable ([LegTimetables]), the one its next trains are read from. */
    data class Ride(val key: LegTimetables.Key) : TimetableSource

    /**
     * A National Rail line's trains from [stopId] over the next [RailTimetable.HOURS] hours, from National Rail's board
     * there ([RailTimetables], maintainer, 2026-10-22): those to [to] (a stop id) where it's given, ending there where
     * [ends] (a way's chip, one per station the line's trains end at), else calling there (a ride's far end).
     */
    data class Rail(val stopId: String, val lineId: String, val to: String?, val ends: Boolean) : TimetableSource
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

    /** A National Rail timetable ([TimetableSource.Rail]), as National Rail gave it at [asOf]. */
    data class FoundRail(val timetable: RailTimetable, val asOf: Instant) : TimetableLookup

    /** No timetable to ask for: the station, or where the way goes, has no National Rail code. */
    data object NoRoute : TimetableLookup
}

/** National Rail's timetables for a route page ([TimetableSource.Rail]); null where National Rail isn't wired. */
val LocalRailTimetables = staticCompositionLocalOf<RailTimetables?> { null }

/** The mark id a route page's timetable is watched under ([EmptyTimesState.watch]), apart from any board's. */
internal fun timetableWatchId(key: EmptyTimes.Key): String = "timetable:${key.stopId}|${key.lineId}"

/**
 * [source]'s timetable as far as it's in, asked for again each minute of [now] while it isn't (a failure is asked
 * again once its retry is due): a stop's from the board's timetables ([LocalEmptyTimes]), a ride's from the rides'
 * ([LocalLegTimetables]). One request per stop and line, or per ride, a service day, shared with what the board and
 * the ride's next trains already fetched. A ride's answer shows only for the ride it was fetched for ([Worked]).
 * National Rail's ([LocalRailTimetables]) is asked again every [RailTimetable.REFRESH] while it's shown, for its
 * estimates; one that fails then keeps the last it had, which says how old it is ([TimetableLookup.FoundRail.asOf]).
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
        is TimetableSource.Rail -> {
            val timetables = LocalRailTimetables.current ?: return TimetableLookup.Failed
            val worker = LocalWorker.current
            val round = minute / RailTimetable.REFRESH.toMinutes()
            val asked by rememberUpdatedState(now)
            val answer by produceState<Worked<TimetableSource.Rail, TimetableLookup>?>(null, timetables, worker, source, round) {
                val held = value?.takeIf { it.key == source }?.value as? TimetableLookup.FoundRail
                val at = asked
                value = Worked(
                    source,
                    try {
                        withContext(worker) { timetables.fetch(source.stopId, source.lineId, source.to, source.ends) }
                            ?.let { TimetableLookup.FoundRail(it, at.minus(it.age)) } ?: TimetableLookup.NoRoute
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TflException) {
                        // Logged (sanitized) by [RailTimetables]: the last answer stands, saying how old it is, else the
                        // failure is said, asked again next round.
                        held ?: TimetableLookup.Failed
                    },
                )
            }
            answer?.takeIf { it.key == source }?.value ?: TimetableLookup.Loading
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

        /**
         * [timetable]'s trains (National Rail's, [TimetableSource.Rail]) as service [day]'s, listed by time, an hour to a
         * line: never summed up as a frequency, since each says what National Rail says of it now. Each is placed by its
         * estimate where it has one, so a late train isn't grayed while it's still to come.
         */
        @WorkerThread
        fun ofRail(timetable: RailTimetable, day: LocalDate): TimetableDay {
            val times = timetable.trains.map { train ->
                val scheduled = ScheduledDay.minuteOf(train.scheduled, day).toInt()
                val expected = train.expected?.let { ScheduledDay.minuteOf(it, day).toInt() }
                TimetableRow.Time(clock(scheduled), expected ?: scheduled, expected?.let(::clock), train.canceled, train.delayed, scheduled)
            }
            // By the hour of the clock, the day's: a train held over from the day before (scheduleNear keeps one up to
            // 18 hours late) comes first, in an hour of its own (Codex on #1115).
            val rows = times.sortedBy { it.scheduled }.groupBy { Math.floorDiv(it.scheduled, 60) }.values.map { hour -> TimetableRow.Hour(hour) }
            return TimetableDay(day, rows, holiday = false, partial = !timetable.complete, unscheduled = false)
        }

        // A time as the clock reads it, past midnight from 00:00 again, and before the service day's start from 23:59 back
        // (a National Rail train held over from the day before, Codex on #1115).
        private fun clock(minute: Int): String = Math.floorMod(minute, 24 * 60).let { "%02d:%02d".format(it / 60, it % 60) }
    }
}

/** A line of a [TimetableDay]: a frequency over a stretch, or an hour's trains listed. [last] is its last train's minute. */
internal sealed interface TimetableRow {
    val last: Int

    class Every(val from: String, val to: String, val shortest: Int, val longest: Int, override val last: Int) : TimetableRow

    class Hour(val times: List<Time>) : TimetableRow {
        override val last: Int get() = times.last().minute
    }

    /**
     * A train at [text] (its schedule, [scheduled] its minute), [minute] when it's due. For National Rail's: [expected],
     * its estimate where it's another time; [canceled]; or [delayed], "Delayed" with none.
     */
    class Time(
        val text: String,
        val minute: Int,
        val expected: String? = null,
        val canceled: Boolean = false,
        val delayed: Boolean = false,
        val scheduled: Int = minute,
    ) {
        /** When it's due as shown: by its estimate while that's [live], else by its schedule alone (Codex on #1115). */
        fun due(live: Boolean): Int = if (live) minute else scheduled
    }
}

/**
 * [lookup]'s service [day] along [path] ([TimetableDay]), worked out on the worker; null while it is, or while the
 * timetable isn't in. Only ever for the [source], timetable, way and day asked: another stop's or day's times never
 * stand under one just moved to.
 */
@Composable
internal fun rememberTimetableDay(source: TimetableSource?, lookup: TimetableLookup, path: List<String>?, day: LocalDate): TimetableDay? {
    val timetable = (lookup as? TimetableLookup.Found)?.timetable
    val rail = (lookup as? TimetableLookup.FoundRail)?.timetable
    // The source by its ids, which compare by value ([Inputs]).
    val from = when (source) {
        is TimetableSource.Stop -> "${source.stopId}|${source.lineId}"
        is TimetableSource.Ride -> "${source.key.lineId}|${source.key.fromId}|${source.key.toId}"
        is TimetableSource.Rail -> "rail|${source.stopId}|${source.lineId}|${source.to.orEmpty()}|${source.ends}"
        null -> null
    }
    val slot = remember { mutableStateOf<Worked<Inputs, TimetableDay?>?>(null) }
    return rememberWorked(slot, Inputs(from, timetable ?: rail, path, day)) {
        when {
            timetable != null -> TimetableDay.of(timetable, path, day)
            rail != null -> TimetableDay.ofRail(rail, day)
            else -> null
        }
    }
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
    // A window of its own: drawn over a glimpse of the page it's over, it would cover the page being swiped.
    if (!LocalPageOnTop.current) return
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
 * National Rail's ([TimetableSource.Rail]) are the next [RailTimetable.HOURS] hours, said so in place of the day's
 * chips, each time with what National Rail says of it ("10:15 (10:22)", "(canceled)", "(delayed)") while that's no
 * older than [RailTimetable.LIVE_FOR]; past it, the schedule alone, said as such (SPEC D4).
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
    // Today's service day, or the next; National Rail's reach no further than the next few hours.
    var later by rememberSaveable { mutableIntStateOf(0) }
    val source = (ask as? TimetableAsk.Of)?.source
    val rail = source is TimetableSource.Rail
    val today = ScheduledDay.serviceDay(now)
    val day = today.plusDays(if (rail) 0L else later.toLong())
    val lookup = rememberTimetableLookup(source, now)
    val path = (source as? TimetableSource.Stop)?.path
    val shown = rememberTimetableDay(source, lookup, path, day)
    // Trains gone by are today's alone: tomorrow's are all to come.
    val nowMinute = if (rail || later == 0) ScheduledDay.minuteOf(now, today) else Long.MIN_VALUE
    // National Rail's estimates, while they're recent enough to stand as live.
    val live = (lookup as? TimetableLookup.FoundRail)?.let { Duration.between(it.asOf, now) <= RailTimetable.LIVE_FOR } == true
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
            if (!rail) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = later == 0, onClick = { later = 0 }, label = { Text(stringResource(R.string.trip_time_today)) })
                    FilterChip(selected = later == 1, onClick = { later = 1 }, label = { Text(stringResource(R.string.trip_time_tomorrow)) })
                }
            }
            Text(
                text = stringResource(R.string.timetable_scheduled),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { heading() },
            )
            if (rail) TimetableNote(pluralStringResource(R.plurals.timetable_next_hours, RailTimetable.HOURS, RailTimetable.HOURS))
            when {
                ask == TimetableAsk.Choose -> TimetableNote(R.string.timetable_choose_route)
                ask == TimetableAsk.Unknown || lookup == TimetableLookup.NoRoute -> TimetableNote(R.string.timetable_route_unknown)
                lookup == TimetableLookup.Failed -> TimetableNote(R.string.timetable_failed)
                shown == null -> TimetableNote(R.string.timetable_loading)
                shown.unscheduled -> TimetableNote(R.string.timetable_unavailable)
                else -> {
                    if (shown.holiday) TimetableNote(R.string.timetable_holiday)
                    if (shown.partial) TimetableNote(R.string.timetable_partial)
                    if (rail && !live) TimetableNote(R.string.timetable_live_stale)
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
                                    TimetableTime(time, live, if (time.due(live) < nowMinute) gone else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One train's time, with what National Rail says of it where that's [live] ("10:15 (10:22)", "(canceled)",
 * "(delayed)"), and spoken so; else its schedule alone.
 */
@Composable
private fun TimetableTime(time: TimetableRow.Time, live: Boolean, color: Color) {
    val (text, spoken) = when {
        !live -> time.text to null
        time.canceled -> stringResource(R.string.timetable_canceled, time.text) to stringResource(R.string.timetable_canceled_spoken, time.text)
        time.delayed -> stringResource(R.string.timetable_delayed, time.text) to stringResource(R.string.timetable_delayed_spoken, time.text)
        time.expected != null ->
            stringResource(R.string.timetable_expected, time.text, time.expected) to stringResource(R.string.timetable_expected_spoken, time.text, time.expected)
        else -> time.text to null
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
    )
}

/** A line under a timetable's heading saying what it shows, or why it shows nothing. */
@Composable
private fun TimetableNote(@StringRes text: Int) {
    TimetableNote(stringResource(text))
}

@Composable
private fun TimetableNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}
