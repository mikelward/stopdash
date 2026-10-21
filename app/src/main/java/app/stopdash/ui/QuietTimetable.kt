package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopTimetable
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The timetable on the page of a TfL line's row with no live times, a "?" or a dash (maintainer, 2026-10-21): each
 * way its line runs on from the stop ([RouteChoicesUi]) with its next few trains by the stop's timetable
 * ([StopTimetable.along]), the one its mark was worked out from ([EmptyTimes]), so it costs no request of its own. A
 * way the timetable can't say for is left out. [dueSoon] is whether it has a train due within [EmptyTimes.WINDOW]
 * ([StopTimetable.departsWithin]): false is the dash's "none coming", said as such.
 */
internal class QuietTimetable(val ways: List<Way>, val dueSoon: Boolean? = null) {
    /** A way, named as its chip is ([label]), with its next trains by the timetable ([trains]). */
    class Way(val label: String, val key: String, val trains: TimetabledTimes)

    companion object {
        /**
         * Each of [choices]' ways with its next [count] trains at or after [at] by [timetable], none on or after a day
         * its line's planned work starts ([plannedFrom], [departureBefore]): the timetable knows nothing of that work.
         * Null [choices] give none.
         */
        @WorkerThread
        fun waysOf(timetable: StopTimetable, choices: RouteChoicesUi?, at: Instant, plannedFrom: List<LocalDate>, count: Int): List<Way> =
            choices?.let { ui ->
                ui.choices.choices.mapIndexedNotNull { i, choice ->
                    // A train's own way only (a short working's live one has none to read), and a way from this stop.
                    if (choice.path.size < 2) return@mapIndexedNotNull null
                    val times = timetable.along(choice.path).nextDepartures(at, count).mapNotNull { departureBefore(it, plannedFrom) }
                    times.takeIf { it.isNotEmpty() }?.let { Way(ui.labels[i], choice.key, TimetabledTimes.of(it)) }
                }
            }.orEmpty()
    }
}

/**
 * [row]'s ways' next trains by its stop's timetable ([QuietTimetable]), as of [now]'s minute, worked out on the
 * worker ([rememberWorked]) from the timetable already in ([LocalEmptyTimes]) and [choices]; none planned on or after a
 * day [lineStatus]'s planned work starts. No ways while [choices] is null (the page can't stand behind the times, or
 * the chips aren't in), only whether one is due. Null while [row] is null or its timetable isn't in (or couldn't be
 * had).
 *
 * Each part is paired with what it was worked out for, so the page never shows one past it: another stop, line or
 * timetable retires both in that frame, and the page losing what lets it stand behind the times retires the ways
 * (Codex on #1044). The last answer stands in only across what changes while the times hold: the minute's tick, the
 * chips or the line's status worked out again (SPEC *hold still*).
 */
@Composable
internal fun rememberQuietTimetable(row: DepartureRow?, choices: RouteChoicesUi?, lineStatus: LineStatus?, now: Instant): QuietTimetable? {
    val state = LocalEmptyTimes.current
    val key = row?.let { EmptyTimes.Key(it.stopId, it.lineId) }
    // Only this stop and line's timetable, so another's landing doesn't recompose the page.
    val lookup by remember(state.lookups, key) {
        state.lookups.map { key?.let { k -> it[k] } }.distinctUntilChanged()
    }.collectAsState(null)
    val minute = now.epochSecond / 60
    val timetable = (lookup as? EmptyTimes.Lookup.Found)?.timetable
    // Whether a train is due, which picks the why: the timetable's own answer, whatever the checks.
    val dueSlot = remember { mutableStateOf<Worked<Inputs, Boolean?>?>(null) }
    val dueSoon = rememberWorked(dueSlot, Inputs(key?.stopId, key?.lineId, timetable, minute), keep = ::sameTimetable) {
        timetable?.departsWithin(now, EmptyTimes.WINDOW)
    }
    val eligible = choices != null
    val waysSlot = remember { mutableStateOf<Worked<Inputs, List<QuietTimetable.Way>>?>(null) }
    val ways = rememberWorked(waysSlot, Inputs(key?.stopId, key?.lineId, timetable, eligible, minute, choices, lineStatus), keep = ::sameWays) {
        timetable?.let { QuietTimetable.waysOf(it, choices, now, lineStatus?.planned.orEmpty().map { work -> work.startsOn }, TIMETABLED_TRAINS) }.orEmpty()
    }
    val shown = row != null && timetable != null
    return remember(shown, ways, dueSoon) { if (shown) QuietTimetable(ways.orEmpty(), dueSoon) else null }
}

// The answer for the same stop, line and timetable stands in while another minute's is worked out.
private fun sameTimetable(held: Inputs, wanted: Inputs): Boolean = (0..2).all { Inputs.same(held.parts[it], wanted.parts[it]) }

// The ways for the same stop, line and timetable, the page standing behind them both times, stand in while another
// minute's, or the chips' or the status's worked out again, are.
private fun sameWays(held: Inputs, wanted: Inputs): Boolean = (0..3).all { Inputs.same(held.parts[it], wanted.parts[it]) }
