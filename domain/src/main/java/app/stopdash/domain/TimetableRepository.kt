package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Lines' timetables at stops, fetched when a board comes back empty ([EmptyTimes]) and kept, in
 * memory, for the rest of the London service day: a timetable changes from one day to the next, not
 * within one. Past that day it's dropped, as are the marks of boards no longer shown. A failed fetch is asked again after [retryAfter]. Never on the refresh path: a screen
 * [watch]es each board it shows with no live times and reads its mark from [marks], so until a mark
 * is worked out the board shows "?", which is what an unanswered timetable means anyway.
 *
 * Everything past the call runs on [io] — the board's keys, which timetables are due, the mark — so
 * the screen calling it on the main thread only reads a map ([marks]) and hands over a recipe.
 *
 * One request per line and stop a day (three for a line TfL times only by direction), and only for
 * a line with no live times: free, and nothing at all on a board with times on it. TfL having no
 * timetable at all (a 404) isn't asked again that day.
 */
class TimetableRepository(
    private val source: TimetableSource,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = {},
    private val retryAfter: Duration = RETRY_AFTER,
) {
    private data class Entry(val lookup: EmptyTimes.Lookup, val day: LocalDate, val at: Instant)

    private val entries = ConcurrentHashMap<EmptyTimes.Key, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<EmptyTimes.Key>()
    private val boards = ConcurrentHashMap<String, EmptyTimes.Board>()
    private val _lookups = MutableStateFlow<Map<EmptyTimes.Key, EmptyTimes.Lookup>>(emptyMap())
    private val _marks = MutableStateFlow<Map<String, EmptyTimes.Marked>>(emptyMap())

    // A timetable fetch shares TfL's request budget with the live refresh: at most this many at once.
    private val fetchSlots = Semaphore(MAX_CONCURRENT_FETCHES)

    /** Each key's timetable as far as it was looked up; a key not yet answered is absent. */
    val lookups: StateFlow<Map<EmptyTimes.Key, EmptyTimes.Lookup>> = _lookups.asStateFlow()

    /**
     * Each watched board's mark, by the id it was [watch]ed under, stamped with when it was worked
     * out; absent until then. Restamped on every [watch], so a shown board's stamp is never more
     * than a tick old.
     */
    val marks: StateFlow<Map<String, EmptyTimes.Marked>> = _marks.asStateFlow()

    /**
     * Works out board [id]'s mark as of now, calling [board] for what it is, and fetches each of its
     * timetables not yet in for today's service day (or failed over [retryAfter] ago), working the
     * mark out again as each lands. Returns at once: all of it runs on [io]. Asked again (each minute
     * a board stays shown), it moves the mark on with the clock and retries what's due; a key already
     * being fetched isn't asked for twice.
     */
    fun watch(id: String, board: () -> EmptyTimes.Board) {
        scope.launch(io) {
            val now = clock()
            val today = serviceDay(now)
            forget(now, today)
            val shown = board()
            boards[id] = shown
            publish(id, shown)
            if (shown.notRunning) return@launch
            shown.keys.filter { key -> needed(entries[key], today, now) && inFlight.add(key) }.forEach { key ->
                launch {
                    try {
                        val lookup = fetch(key)
                        // Filed under the service day it was asked in: one asked just before 04:00 and
                        // answered after is the old day's, and the next watch replaces it.
                        entries[key] = Entry(lookup, today, clock())
                        _lookups.update { it + (key to lookup) }
                        boards.forEach { (other, watched) -> if (key in watched.keys) publish(other, watched) }
                    } finally {
                        inFlight.remove(key)
                    }
                }
            }
        }
    }

    /**
     * Drops what nothing on screen still needs, so memory doesn't grow with every stop a rider has
     * passed: timetables from before [today]'s service day (one still shown is fetched again), and
     * the boards and marks not watched for [FORGET_AFTER] (a shown board is watched each minute).
     */
    private fun forget(now: Instant, today: LocalDate) {
        val past = entries.filterValues { it.day != today }.keys
        if (past.isNotEmpty()) {
            past.forEach { entries.remove(it) }
            _lookups.update { it - past }
        }
        val gone = _marks.value.filterValues { Duration.between(it.at, now) > FORGET_AFTER }.keys
        if (gone.isNotEmpty()) {
            gone.forEach { boards.remove(it) }
            _marks.update { it - gone }
        }
    }

    private fun publish(id: String, board: EmptyTimes.Board) {
        val now = clock()
        val mark = if (board.notRunning) EmptyTimes.Mark.NONE else EmptyTimes.mark(board.keys, _lookups.value, now)
        _marks.update { it + (id to EmptyTimes.Marked(mark, now)) }
    }

    private fun needed(entry: Entry?, today: LocalDate, now: Instant): Boolean = when {
        entry == null || entry.day != today -> true
        entry.lookup is EmptyTimes.Lookup.Failed -> Duration.between(entry.at, now) >= retryAfter
        else -> false
    }

    private suspend fun fetch(key: EmptyTimes.Key): EmptyTimes.Lookup =
        fetchSlots.withPermit {
            try {
                EmptyTimes.Lookup.Found(source.timetable(key.lineId, key.stopId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                // TfL has no timetable for this line here, and asking again won't give one: an empty
                // timetable, which can't settle the mark ("?"), kept for the day like any other.
                warn("no timetable for line ${key.lineId} at stop ${key.stopId}")
                EmptyTimes.Lookup.Found(StopTimetable(emptyList()))
            } catch (e: TflException) {
                // The board stays "?" until a later request gets it: it's what an unanswered one means.
                warn("timetable failed for line ${key.lineId} at stop ${key.stopId}: ${e.message}")
                EmptyTimes.Lookup.Failed
            }
        }

    companion object {
        val RETRY_AFTER: Duration = Duration.ofMinutes(5)

        /** A board not watched for this long is off screen, and its mark is dropped. */
        val FORGET_AFTER: Duration = Duration.ofHours(1)
        private const val MAX_CONCURRENT_FETCHES = 2
        private val LONDON: ZoneId = ZoneId.of("Europe/London")

        // A service day ends a few hours after midnight, when the last night buses have run, so a
        // timetable fetched at 00:30 is still that evening's day's ([StopTimetable] times past 24:00).
        // Read on London's wall clock, so it ends at 04:00 on a clock-change night too.
        private val SERVICE_DAY_ENDS: LocalTime = LocalTime.of(4, 0)

        internal fun serviceDay(at: Instant): LocalDate {
            val local = at.atZone(LONDON).toLocalDateTime()
            return if (local.toLocalTime().isBefore(SERVICE_DAY_ENDS)) local.toLocalDate().minusDays(1) else local.toLocalDate()
        }
    }
}
