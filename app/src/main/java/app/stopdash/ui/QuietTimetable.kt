package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopTimetable
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
         * Null [choices] give no ways, only [dueSoon].
         */
        @WorkerThread
        fun of(timetable: StopTimetable, choices: RouteChoicesUi?, at: Instant, plannedFrom: List<LocalDate>, count: Int): QuietTimetable =
            QuietTimetable(
                choices?.let { ui ->
                    ui.choices.choices.mapIndexedNotNull { i, choice ->
                        // A train's own way only (a short working's live one has none to read), and a way from this stop.
                        if (choice.path.size < 2) return@mapIndexedNotNull null
                        val times = timetable.along(choice.path).nextDepartures(at, count).mapNotNull { departureBefore(it, plannedFrom) }
                        times.takeIf { it.isNotEmpty() }?.let { Way(ui.labels[i], choice.key, TimetabledTimes.of(it)) }
                    }
                }.orEmpty(),
                timetable.departsWithin(at, EmptyTimes.WINDOW),
            )
    }
}

/**
 * [row]'s ways' next trains by its stop's timetable ([QuietTimetable]), as of [now]'s minute, worked out on the
 * worker ([LocalWorker]) from the timetable already in ([LocalEmptyTimes]) and [choices]; none planned on or after a
 * day [lineStatus]'s planned work starts. No ways while [choices] is null (the page can't stand behind the times, or
 * the chips aren't in), only whether one is due. Null while [row] is null, its timetable isn't in (or couldn't be
 * had), or it's worked out.
 */
@Composable
internal fun rememberQuietTimetable(row: DepartureRow?, choices: RouteChoicesUi?, lineStatus: LineStatus?, now: Instant): QuietTimetable? {
    val state = LocalEmptyTimes.current
    val worker = LocalWorker.current
    val key = row?.let { EmptyTimes.Key(it.stopId, it.lineId) }
    // Only this stop and line's timetable, so another's landing doesn't recompose the page.
    val lookup by remember(state.lookups, key) {
        state.lookups.map { key?.let { k -> it[k] } }.distinctUntilChanged()
    }.collectAsState(null)
    val minute = now.epochSecond / 60
    val timetable = (lookup as? EmptyTimes.Lookup.Found)?.timetable
    val worked by produceState<QuietTimetable?>(null, timetable, choices, lineStatus, minute, worker) {
        if (timetable == null) {
            value = null
            return@produceState
        }
        // The last answer stands while the minute's is worked out, so the times never blink (SPEC *hold still*).
        value = withContext(worker) {
            QuietTimetable.of(timetable, choices, now, lineStatus?.planned.orEmpty().map { it.startsOn }, TIMETABLED_TRAINS)
        }
    }
    // The last answer stands only while what it was worked out for still does: never past its timetable going, nor
    // its ways past the page losing what lets it stand behind them ([choices] null), for a frame or a worker's turn
    // (Codex on #1044).
    val eligible = choices != null
    val shown = worked?.takeIf { row != null && timetable != null }
    return remember(shown, eligible) {
        shown?.let { if (eligible || it.ways.isEmpty()) it else QuietTimetable(emptyList(), it.dueSoon) }
    }
}
