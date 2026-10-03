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
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.TimetableRepository
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The marks of the empty boards on screen ([EmptyTimes]), as of [now], and how to ask for one.
 * [marks] is the repository's flow itself, not a copy of its map: each board collects only its own
 * entry ([emptyTimesMark]), so a mark landing doesn't hand composition the whole map to compare.
 * [watch] hands a board's recipe to the repository, which works the mark out off the main thread.
 */
@Immutable
class EmptyTimesState(
    val marks: StateFlow<Map<String, EmptyTimes.Marked>>,
    val now: Instant,
    val watch: (String, () -> EmptyTimes.Board) -> Unit,
)

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
    val state = remember(now, repository) { EmptyTimesState(repository.marks, now, repository::watch) }
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
