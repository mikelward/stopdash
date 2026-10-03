package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * What a line with no live times shows where its times would be (maintainer, 2026-10-03): a dash
 * only when StopDash is sure nothing is coming, and "?" when something might be but TfL hasn't said
 * when. TfL's live list reaches about half an hour ahead, so "sure" means the line isn't running
 * here, or its timetable has nothing leaving within [WINDOW]. Anything StopDash can't settle — no
 * timetable yet, a failed one, a day it doesn't cover — is "?", never a dash.
 *
 * The timetable only settles which; its times are never shown, since it knows nothing of the
 * disruption that empties a board.
 */
object EmptyTimes {
    /** TfL's live arrivals reach about this far ahead; a timetable is read no further. */
    val WINDOW: Duration = Duration.ofMinutes(30)

    /**
     * How long a mark is shown for once worked out: until the next minute's is in. So a mark looks
     * this much further ahead than [WINDOW], and a dash worked out at 12:00 still holds at 12:00:59,
     * when a departure at 12:31 has come within the window.
     */
    val MARK_LIFETIME: Duration = Duration.ofMinutes(1)

    /** A line at a stop, as its timetable is asked for. */
    data class Key(val stopId: String, val lineId: String)

    /** A [Key]'s timetable as far as it was looked up: found, or failed. Absent while pending. */
    sealed interface Lookup {
        data class Found(val timetable: StopTimetable) : Lookup

        data object Failed : Lookup
    }

    /**
     * A board with no live times, as its mark is worked out: its lines' [keys], whether its status
     * already says nothing runs there ([notRunning]: a dash, with no timetable needed), and the mark
     * for a line whose timetable can't settle it ([unsure]: a failed one, a day it doesn't cover) and
     * for one whose timetable isn't in yet ([pending]).
     */
    data class Board(
        val keys: List<Key>,
        val notRunning: Boolean = false,
        val unsure: Mark = Mark.UNKNOWN,
        val pending: Mark = Mark.UNKNOWN,
    )

    /**
     * The board of a [DepartureRow.quiet] row, a line on good service with no times: shown ("?",
     * [Mark.UNKNOWN]) when its timetable has a train due, hidden ([Mark.NONE]) when it has none, so
     * an infrequent service or a night bus by day adds no row. When the timetable can't say, a line
     * of a [FREQUENT_MODES] mode is "?", since half an hour without a train is a fault there; any
     * other is hidden, as it was before quiet rows. Until the timetable is in, hidden: a "?" shown
     * while it loads would flash off when it says nothing's due.
     */
    fun quietBoard(stopId: String, lineId: String, mode: String): Board = Board(
        listOf(Key(stopId, lineId)),
        unsure = if (mode.lowercase() in FREQUENT_MODES) Mark.UNKNOWN else Mark.NONE,
        pending = Mark.NONE,
    )

    /** The modes whose trains come every few minutes all day. */
    val FREQUENT_MODES: Set<String> = setOf("tube", "elizabeth-line", "overground", "dlr", "tram")

    /** A dash ([NONE]) or "?" ([UNKNOWN]). */
    enum class Mark { NONE, UNKNOWN }

    /**
     * A board's [mark] and the time it was worked out [at], so a board coming back on screen can
     * tell a mark worked out for it now from one left over from when it was last shown.
     */
    data class Marked(val mark: Mark, val at: Instant)

    /**
     * [keys]' mark at [now]: [Mark.NONE] when every one is [notRunning] or has a timetable with
     * nothing leaving within [WINDOW] (and [MARK_LIFETIME], as long as the mark is shown for);
     * [Mark.UNKNOWN] when one has a train due; otherwise [pending] while some key's timetable isn't
     * in yet, and [unsure] when one can't say. No keys at all is unknown.
     */
    fun mark(
        keys: Collection<Key>,
        lookups: Map<Key, Lookup>,
        now: Instant,
        unsure: Mark = Mark.UNKNOWN,
        pending: Mark = Mark.UNKNOWN,
        notRunning: (Key) -> Boolean = { false },
    ): Mark {
        if (keys.isEmpty()) return Mark.UNKNOWN
        var waiting = false
        var unsettled = false
        for (key in keys) {
            if (notRunning(key)) continue
            when (val lookup = lookups[key]) {
                null -> waiting = true
                is Lookup.Failed -> unsettled = true
                is Lookup.Found -> when (lookup.timetable.departsWithin(now, WINDOW.plus(MARK_LIFETIME))) {
                    true -> return Mark.UNKNOWN
                    false -> Unit
                    null -> unsettled = true
                }
            }
        }
        return when {
            waiting -> pending
            unsettled -> unsure
            else -> Mark.NONE
        }
    }

    /**
     * Whether [status] says its line isn't running at [stopId]: closed, suspended or not running
     * line-wide, or a part closure with the stop inside a closed section. A stop at a section's
     * edge still has trains up to it, so it isn't taken as closed. Where TfL scopes the line's
     * alerts by direction ([LineStatus.byDirection]), only when every direction is shut there: a
     * status row has no direction, and trains may still run the other way. Not while those directions
     * are still being looked up ([LineStatus.awaitingDirections]): a suspension may yet prove one-way.
     */
    fun notRunningAt(status: LineStatus?, stopId: String): Boolean {
        if (status == null || status.awaitingDirections) return false
        if (status.byDirection.isNotEmpty()) return status.byDirection.values.all { notRunningAt(it.copy(byDirection = emptyMap()), stopId) }
        if (status.severity in NOT_RUNNING) return true
        return status.closures.any { closure -> closure.severity in NOT_RUNNING_HERE && stopId in closure.interior }
    }

    // TfL's status severities for a line not running at all: Closed, Suspended, Planned Closure,
    // Not Running, Service Closed.
    private val NOT_RUNNING = setOf(1, 2, 4, 16, 20)

    // And for a stretch of one: Part Suspended, Part Closure, Part Closed, as well as the above.
    private val NOT_RUNNING_HERE = NOT_RUNNING + setOf(3, 5, 11)
}
