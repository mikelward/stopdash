package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The hours of the day a line alert's work runs, where its text gives them as daily hours over its
 * dates ("Oxford Street will be closed … 22:00-06:00 3-5 November", "nightly 20:00-05:00 12-17
 * October", "09:30-15:00 12 October"): outside them, the line runs as normal. Any hours, by day or
 * past midnight, so a ride is judged by whether its own times meet them (maintainer, 2026-11-04: a
 * rider shown a closure at 11:10 that runs 22:00-06:00).
 *
 * Read only in that shape, a pair of times then the days, or "nightly" before them, and only where it's
 * the text's only time: one with other times ("from 22:00 5 November until 06:00 6 November") may run
 * through the day, and wording not seen from TfL ("between 22:00 and 06:00") is left as all day. Null
 * is all day, the safe side (SPEC principle 1): a disruption shown out of its hours costs a glance,
 * one hidden in them leaves the rider waiting.
 */
data class AlertHours(val from: LocalTime, val to: LocalTime) {
    /**
     * Whether the work is in force at any time from [start] to [end] in London: on any day's hours that
     * overlap them, a window past midnight ([to] not after [from]) running into the next day.
     */
    fun overlaps(start: Instant, end: Instant): Boolean {
        val first = start.atZone(AlertStart.ZONE).toLocalDate().minusDays(1)
        val last = end.atZone(AlertStart.ZONE).toLocalDate()
        var day = first
        while (!day.isAfter(last)) {
            // The widest the hours can be where the clocks change: opening at the earlier of a repeated
            // hour, closing at the later; opening in an hour the clocks skip at the moment they change, not
            // an hour on, which could pass the close (Codex on #1256). A close in the gap moves past it, later.
            val opens = opening(day.atTime(from))
            val closes = (if (to.isAfter(from)) day else day.plusDays(1)).atTime(to).atZone(AlertStart.ZONE).withLaterOffsetAtOverlap().toInstant()
            if (opens.isBefore(end) && closes.isAfter(start)) return true
            day = day.plusDays(1)
        }
        return false
    }

    // [local] in London, opening as early as it can: the earlier of a repeated hour, the start of a skipped one.
    private fun opening(local: LocalDateTime): Instant =
        AlertStart.ZONE.rules.getTransition(local)?.takeIf { it.isGap }?.instant
            ?: local.atZone(AlertStart.ZONE).withEarlierOffsetAtOverlap().toInstant()

    companion object {
        /** [text]'s daily hours, read as above; null where it gives none, so the work runs all day. */
        @WorkerThread
        fun of(text: String?): AlertHours? {
            if (text.isNullOrBlank()) return null
            val match = DAILY.findAll(text).singleOrNull() ?: return null
            val (nightly, h1, m1, h2, m2, days) = match.destructured
            if (nightly.isBlank() && days.isBlank()) return null
            // The text's only times: the pair's two, none elsewhere in any form TfL writes one ("from 1000
            // on 6 November"; Codex on #1256).
            if (AlertStart.timeCount(text) != 2) return null
            val from = time(h1, m1)
            val to = time(h2, m2)
            return AlertHours(from, to).takeIf { from != to }
        }

        private fun time(hour: String, minute: String): LocalTime {
            // [DAILY] reads only real times, so this never fails.
            return LocalTime.of(hour.toInt(), minute.toInt())
        }

        private const val MONTHS =
            """(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\b"""

        // A pair of times ("22:00-06:00", a dash of any kind, spaced or not), "nightly" before them or the
        // days they run on after ("3-5 November", "12 October"): [of] takes it only with one or the other.
        private val DAILY = Regex(
            """(\bnightly\s+)?\b([01]?\d|2[0-3]):([0-5]\d)\s*[-–—]\s*([01]?\d|2[0-3]):([0-5]\d)\b""" +
                """(\s+\d{1,2}(?:\s*[-–—]\s*\d{1,2})?\s+$MONTHS)?""",
            RegexOption.IGNORE_CASE,
        )

    }
}
