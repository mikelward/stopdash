package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.LineStatus
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.Staleness
import app.stopdash.domain.TimetableRepository
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The marks of the empty boards on screen ([EmptyTimes]), as of [now], and how to ask for one.
 * [marks] is the repository's flow itself, not a copy of its map: each board collects only its own
 * entry ([emptyTimesMark]), so a mark landing doesn't hand composition the whole map to compare.
 * [watch] hands a board's recipe to the repository, which works the mark out off the main thread;
 * [watchEach] hands it a recipe for many ([TimetableRepository.watchEach]), by default each [watch]ed
 * in turn. A list's own scans run on [worker].
 */
@Immutable
class EmptyTimesState(
    val marks: StateFlow<Map<String, EmptyTimes.Marked>>,
    val now: Instant,
    val worker: CoroutineContext = EmptyCoroutineContext,
    private val watchMany: ((() -> Map<String, EmptyTimes.Board>) -> Unit)? = null,
    val watch: (String, () -> EmptyTimes.Board) -> Unit,
) {
    fun watchEach(boards: () -> Map<String, EmptyTimes.Board>) {
        watchMany?.invoke(boards) ?: boards().forEach { (id, board) -> watch(id) { board } }
    }
}

/**
 * A list of rows, built as of the screen's clock [now], with identity equality, so a Compose key on
 * it costs nothing whatever its length.
 */
internal class RowsRevision(val rows: List<DepartureRow>, val now: Instant = Instant.EPOCH)

/** With nothing worked out, every empty board is "?": StopDash can't say nothing's coming. */
val LocalEmptyTimes = compositionLocalOf { EmptyTimesState(MutableStateFlow(emptyMap()), Instant.EPOCH) { _, _ -> } }

/**
 * Provides [LocalEmptyTimes] from [repository], its clock moving once a minute while the screen is
 * resumed: a 30-minute window ([EmptyTimes.WINDOW]) needs no finer, and a backgrounded screen isn't
 * woken for it.
 */
@Composable
internal fun ProvideEmptyTimes(repository: TimetableRepository, content: @Composable () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(initialValue = Instant.now(), lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = Instant.now()
                delay(untilNextMinute(value))
            }
        }
    }
    val state = remember(now, repository) {
        EmptyTimesState(repository.marks, now, Dispatchers.Default, repository::watchEach, repository::watch)
    }
    CompositionLocalProvider(LocalEmptyTimes provides state, content = content)
}

/**
 * The mark of the board [id] with no live times: a dash when [board] says it isn't running, or
 * every line's timetable has nothing due within [EmptyTimes.WINDOW]; "?" otherwise, and until
 * worked out. [board] is the recipe, never called here: the repository calls it off the main thread
 * ([TimetableRepository.watch]), again each minute while the board is shown, so a failed lookup is
 * retried and the mark moves with the clock. [id] names the board, its stop and line, so a board
 * that changes is a new id.
 *
 * Only a fresh mark counts ([freshMark]); otherwise, until the repository answers again, it's "?",
 * never a dash StopDash no longer stands behind.
 */
@Composable
internal fun emptyTimesMark(id: String, board: () -> EmptyTimes.Board): EmptyTimes.Mark {
    val state = LocalEmptyTimes.current
    val watch by rememberUpdatedState(state.watch)
    val recipe by rememberUpdatedState(board)
    val shownSince = remember(id) { state.now }
    LaunchedEffect(id, state.now) { watch(id) { recipe() } }
    // This board's own entry, so another board's mark landing doesn't recompose this one. Nothing
    // until the first collection: a mark from before the board came on screen doesn't count anyway.
    val marked by remember(id, state.marks) { state.marks.map { it[id] }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    return freshMark(marked, shownSince, state.now)
}

/** The mark id of a [DepartureRow.quiet] row. */
internal fun quietId(row: DepartureRow): String = "quiet:${row.stopId}|${row.lineId}"

/** [rows]' [DepartureRow.quiet] rows' boards, by [quietId]. */
internal fun quietBoards(rows: List<DepartureRow>): Map<String, EmptyTimes.Board> =
    rows.filter { it.quiet }.associate { quietId(it) to EmptyTimes.quietBoard(it.stopId, it.lineId, it.mode) }

/**
 * What a near-me list's [DepartureRow.quiet] rows are worked out from, off the main thread
 * ([DepartureRows.quietCandidates]): the fetched [stops], those near ([stopDistanceMeters]) being
 * the list's, as of [now].
 */
internal class QuietInputs(
    val stops: List<StopArrivals>,
    val lineStatuses: Map<String, LineStatus>,
    val determinedLineIds: Set<String>,
    val now: Instant,
    val stopDistanceMeters: Map<String, Double>,
    val hiddenModes: Set<String>,
    val dismissed: Set<DismissedAlert> = emptySet(),
    val disruptionUnknown: Set<String> = emptySet(),
) {
    /** The quiet rows a near-me list could add. Work that grows with the stops: off the main thread only. */
    fun candidates(): List<DepartureRow> = DepartureRows.quietCandidates(
        stops.filter { it.stopId in stopDistanceMeters }, now, lineStatuses, determinedLineIds, stopDistanceMeters, hiddenModes, dismissed, disruptionUnknown,
    )
}

/**
 * [rows] with only the quiet rows of [candidates] whose fresh mark in [marks] is "?" as of [now]
 * ([EmptyTimes.quietBoard]) added, in their stops' places: for each line, the first such of
 * [candidates], which come nearest first ([DepartureRows.quietCandidates]). None from a stop whose
 * arrivals are stale by the screen's clock [screenNow] ([Staleness]): the candidates are worked out
 * each minute and the list every few seconds, and a stop gone stale in between says nothing about its
 * lines any more. A stale mark adds nothing (unlike
 * [freshMark], which reads a stale mark as "?"), nor does one stamped more than a tick after [now],
 * from before the clock was set back.
 */
internal fun resolveQuietRows(
    rows: List<DepartureRow>,
    candidates: List<DepartureRow>,
    marks: Map<String, EmptyTimes.Marked>,
    now: Instant,
    stopDistanceMeters: Map<String, Double>,
    screenNow: Instant = now,
): List<DepartureRow> {
    val since = now.minus(EmptyTimes.MARK_LIFETIME)
    val until = now.plus(EmptyTimes.MARK_LIFETIME)
    val shown = candidates.filter { row ->
        if (Staleness.isStale(row.fetchedAt, screenNow)) return@filter false
        val marked = marks[quietId(row)]
        marked != null && marked.mark == EmptyTimes.Mark.UNKNOWN && !marked.at.isBefore(since) && !marked.at.isAfter(until)
    }.distinctBy { it.lineId }
    return DepartureRows.withQuietRows(rows, shown, stopDistanceMeters)
}

/**
 * The quiet-row candidates worked out from [inputs], with identity equality, for a Compose key that
 * costs nothing.
 */
internal class QuietCandidates(val inputs: QuietInputs, val rows: List<DepartureRow>)

/** [rows], [revision]'s rows with the confirmed ones of [found] added. */
private class QuietResolved(val revision: RowsRevision, val found: QuietCandidates, val rows: List<DepartureRow>)

/**
 * [revision]'s rows, a near-me list, with a "?" row for each line it serves on good service whose
 * times are missing and whose timetable says a train is due ([EmptyTimes.quietBoard]). With no
 * [inputs] (a location-free list), [revision]'s rows as they are.
 *
 * Composition reads and dispatches only. The candidate lines are worked out on
 * [EmptyTimesState.worker] once per [inputs] (the minute tick, or new data), and the repository
 * watches each ([EmptyTimesState.watchEach]), so its mark follows the clock. Each list the screen
 * builds then has the confirmed ones added on the worker too. Until that's done for this [revision]
 * and these [inputs]' candidates, [revision]'s own rows, with no "?" rows yet: never an earlier list, whose
 * departures a refresh may have dropped. A line its timetable rules out is never added, so it never
 * flashes up.
 */
@Composable
internal fun withQuietRows(revision: RowsRevision, inputs: QuietInputs?): List<DepartureRow> {
    if (inputs == null) return revision.rows
    val state = LocalEmptyTimes.current
    val current by rememberUpdatedState(state)
    val candidates by remember(inputs, state.worker) {
        flow { emit(QuietCandidates(inputs, inputs.candidates())) }.flowOn(state.worker)
    }.collectAsStateWithLifecycle(initialValue = null)
    // A state kept across a new key holds the old key's answer until the new one's is in: only these
    // inputs' candidates count.
    val found = candidates?.takeIf { it.inputs === inputs } ?: return revision.rows
    LaunchedEffect(found, state.now) { current.watchEach { quietBoards(found.rows) } }
    if (found.rows.isEmpty()) return revision.rows
    val now = state.now
    val resolved by remember(revision, found, state.marks, now, state.worker) {
        state.marks.map { marks -> QuietResolved(revision, found, resolveQuietRows(revision.rows, found.rows, marks, now, inputs.stopDistanceMeters, revision.now)) }
            .flowOn(state.worker)
    }.collectAsStateWithLifecycle(initialValue = null)
    // Both keys, since either can change without the other: a new list from the same data, or a
    // location update moving a line's nearest candidate under an unchanged list.
    return resolved?.takeIf { it.revision === revision && it.found === found }?.rows ?: revision.rows
}

/**
 * [marked]'s mark if it's fresh, else "?". Fresh means worked out since the board came on screen
 * ([shownSince]: one left over from when it was last shown may be hours old) and no more than
 * [EmptyTimes.MARK_LIFETIME] before the current tick ([now]), which a mark looks that far ahead to
 * cover: the last tick's mark holds until this one's lands, with no "?" between, and a row that
 * stayed in place while the screen was paused doesn't keep a mark from before the pause.
 * A mark stamped more than a tick after [now] is from before the clock was set back, and isn't fresh.
 */
internal fun freshMark(marked: EmptyTimes.Marked?, shownSince: Instant, now: Instant): EmptyTimes.Mark {
    // A board shown "since" a time the clock has since gone back past counts from now instead, or no
    // mark worked out after the change would ever count.
    val since = maxOf(minOf(shownSince, now), now.minus(EmptyTimes.MARK_LIFETIME))
    // Nor one stamped later than the tick it could belong to: the clock was set back since.
    val until = now.plus(EmptyTimes.MARK_LIFETIME)
    return if (marked != null && !marked.at.isBefore(since) && !marked.at.isAfter(until)) marked.mark else EmptyTimes.Mark.UNKNOWN
}

/**
 * How long from [now] to the start of the next minute, a whole minute when [now] is on one. A
 * timetable's departures fall on whole minutes, so a clock that ticks on them works each mark out
 * for exactly the minute it's shown in; one ticking at whatever second the screen resumed could hold
 * a dash for most of a minute after a departure came into the window.
 */
internal fun untilNextMinute(now: Instant): Long = 60_000 - Math.floorMod(now.toEpochMilli(), 60_000L)
