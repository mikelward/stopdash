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
     * A board with no live times, as its mark is worked out: its lines' [keys], and whether its
     * status already says nothing runs there ([notRunning]: a dash, with no timetable needed).
     */
    data class Board(val keys: List<Key>, val notRunning: Boolean = false)

    /** A dash ([NONE]) or "?" ([UNKNOWN]). */
    enum class Mark { NONE, UNKNOWN }

    /**
     * A board's [mark] and the time it was worked out [at], so a board coming back on screen can
     * tell a mark worked out for it now from one left over from when it was last shown.
     */
    data class Marked(val mark: Mark, val at: Instant)

    /**
     * [keys]' mark at [now]: [Mark.NONE] when every one is [notRunning] or has a timetable with
     * nothing leaving within [WINDOW] (and [MARK_LIFETIME], as long as the mark is shown for), else
     * [Mark.UNKNOWN]. No keys at all is unknown too.
     */
    fun mark(
        keys: Collection<Key>,
        lookups: Map<Key, Lookup>,
        now: Instant,
        notRunning: (Key) -> Boolean = { false },
    ): Mark {
        if (keys.isEmpty()) return Mark.UNKNOWN
        val sure = keys.all { key ->
            notRunning(key) || (lookups[key] as? Lookup.Found)?.timetable?.departsWithin(now, WINDOW.plus(MARK_LIFETIME)) == false
        }
        return if (sure) Mark.NONE else Mark.UNKNOWN
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
