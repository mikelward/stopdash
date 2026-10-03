package app.stopdash.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * One line's timetable at one stop, as TfL publishes it (`/Line/{id}/Timetable/{stop}`): for each
 * day type ("Monday - Thursday", "Saturday (also Good Friday)"), every departure from the stop, in
 * minutes after that service day's midnight in London. A service day runs past midnight, so a
 * night's last trains read 24:00 and later (TfL's "25:34" is 01:34 the next morning).
 *
 * Used only to tell an empty board's "none coming" from "none known" ([EmptyTimes]), never to show
 * a time: a timetable knows nothing of the disruption that empties a board (maintainer, 2026-10-03).
 *
 * [readable] is false when some schedule's days couldn't be read ("School days"): it's left out of
 * [schedules], but it might run on any day, so the timetable can then never say nothing is due.
 */
data class StopTimetable(val schedules: List<DaySchedule>, val readable: Boolean = true) {
    /**
     * The departures of the service days in [days], in minutes after their midnight. [complete] is
     * false when some departure couldn't be read: its days then can't be said to have nothing due.
     */
    data class DaySchedule(val days: Set<DayOfWeek>, val minutes: List<Int>, val complete: Boolean = true)

    /**
     * Whether anything leaves within [window] of [now]: today's service day, yesterday's still
     * running past midnight, and tomorrow's if the window reaches it. True as soon as one does.
     * False only when every day that could put a departure in the window is covered by a schedule
     * and none does; otherwise null, since a day no schedule covers can't be said to have nothing.
     * A holiday ([publicHoliday]) gives no answer either: TfL runs another day type's timetable on
     * some ("Saturday (also Good Friday)"), and which one isn't read from the names, so its weekday's
     * could say "none" while trains run.
     */
    fun departsWithin(now: Instant, window: Duration): Boolean? {
        val today = now.atZone(LONDON).toLocalDate()
        val end = now.plus(window)
        // Yesterday's service day may still be running past midnight, and the window may reach
        // tomorrow's. Each departure is placed by its wall-clock time on its own day, so a window
        // across a clock change still covers 30 real minutes. In autumn's repeated hour a time has
        // two instants and the timetable doesn't say which: either one in the window counts, erring
        // toward "something's due".
        val days = listOf(today.minusDays(1), today, today.plusDays(1))
        val due = days.any { day ->
            val midnight = day.atStartOfDay()
            schedules.any { schedule ->
                day.dayOfWeek in schedule.days && schedule.minutes.any { minute ->
                    val zoned = midnight.plusMinutes(minute.toLong()).atZone(LONDON)
                    listOf(zoned.withEarlierOffsetAtOverlap(), zoned.withLaterOffsetAtOverlap()).any { candidate ->
                        val at = candidate.toInstant()
                        !at.isBefore(now) && !at.isAfter(end)
                    }
                }
            }
        }
        if (due) return true
        // Nothing found: that's an answer only if every day that could have put a departure in the
        // window has a schedule, and no schedule was left out unread. Today always could; tomorrow
        // if the window reaches it; yesterday until its own latest time past midnight has gone by,
        // or, when no schedule covers it or one of its journeys couldn't be read, until the service
        // day ends at 04:00, since what it runs isn't known.
        if (!readable) return null
        val yesterday = today.minusDays(1)
        val yesterdays = schedules.filter { yesterday.dayOfWeek in it.days }
        // A schedule with a journey that couldn't be read could run it at any hour, and a holiday may
        // have run another day's timetable, so yesterday then counts to the cutoff too, as an
        // uncovered one does.
        val yesterdayRunsTo =
            if (yesterdays.isEmpty() || yesterdays.any { !it.complete } || publicHoliday(yesterday)) SERVICE_DAY_MINUTES
            else yesterdays.maxOf { it.minutes.maxOrNull() ?: 0 }
        val yesterdayEnds = yesterday.atStartOfDay().plusMinutes(yesterdayRunsTo.toLong())
            .atZone(LONDON).withLaterOffsetAtOverlap().toInstant()
        val needed = buildList {
            if (!now.isAfter(yesterdayEnds)) add(yesterday)
            add(today)
            if (end.atZone(LONDON).toLocalDate().isAfter(today)) add(today.plusDays(1))
        }
        if (needed.any(::publicHoliday)) return null
        val covered = needed.all { day ->
            val its = schedules.filter { day.dayOfWeek in it.days }
            its.isNotEmpty() && its.all { it.complete }
        }
        return if (covered) false else null
    }

    companion object {
        private val LONDON: ZoneId = ZoneId.of("Europe/London")

        // A service day runs to 04:00 the next morning, 28 hours after its midnight.
        private const val SERVICE_DAY_MINUTES = 28 * 60
    }
}

/**
 * Whether [date] is a day TfL may run another day type's timetable on: an England bank holiday
 * (Good Friday, Easter Monday, the May and August Mondays), or any day from Christmas Eve to 3
 * January, which covers Christmas, Boxing Day and New Year with their substitutes and the special
 * services around them. A one-off holiday declared for an occasion isn't known here; it reads as
 * its weekday.
 */
fun publicHoliday(date: LocalDate): Boolean {
    val easter = easterSunday(date.year)
    return date == easter.minusDays(2) || date == easter.plusDays(1) ||
        date == LocalDate.of(date.year, Month.MAY, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)) ||
        date == LocalDate.of(date.year, Month.MAY, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)) ||
        date == LocalDate.of(date.year, Month.AUGUST, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)) ||
        (date.month == Month.DECEMBER && date.dayOfMonth >= 24) ||
        // To 3 January: New Year's Day falling on a weekend moves its holiday to the 2nd or 3rd.
        (date.month == Month.JANUARY && date.dayOfMonth <= 3)
}

// Easter Sunday in the Gregorian calendar (the anonymous Gregorian algorithm).
private fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
}

/**
 * The days TfL's schedule [name] covers: "Monday - Thursday", "Monday to Friday", "Saturday",
 * "Saturday (also Good Friday)", "Sunday", "Saturday and Sunday". What's in brackets is a holiday it
 * also runs on, not a day of the week, so it's dropped. Empty when no day can be read, so the
 * schedule counts for none.
 */
fun scheduleDays(name: String): Set<DayOfWeek> {
    val text = name.replace(BRACKETED, " ").lowercase()
    val days = DAY_WORD.findAll(text).map { match -> dayOf(match.value) to match.range }.toList()
    if (days.isEmpty()) return emptySet()
    val found = LinkedHashSet<DayOfWeek>()
    days.forEachIndexed { i, (day, range) ->
        found += day
        val next = days.getOrNull(i + 1) ?: return@forEachIndexed
        // "Monday - Thursday", "Monday to Friday": every day between, wrapping past Sunday.
        if (RANGE.matches(text.substring(range.last + 1, next.second.first))) {
            var d = day
            while (d != next.first) {
                d = d.plus(1)
                found += d
            }
        }
    }
    return found
}

private val BRACKETED = Regex("""\([^)]*\)""")
private val DAY_WORD = Regex("""\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b""")
private val RANGE = Regex("""\s*(?:-|–|to)\s*""")

private fun dayOf(word: String): DayOfWeek = when (word.take(3)) {
    "mon" -> DayOfWeek.MONDAY
    "tue" -> DayOfWeek.TUESDAY
    "wed" -> DayOfWeek.WEDNESDAY
    "thu" -> DayOfWeek.THURSDAY
    "fri" -> DayOfWeek.FRIDAY
    "sat" -> DayOfWeek.SATURDAY
    else -> DayOfWeek.SUNDAY
}

/**
 * Reads a line's timetable at a stop from TfL. Separate from [TflClient], like
 * [RouteSequenceSource], so the background refresh can't reach it: it's fetched only when a board
 * comes back empty ([EmptyTimes]). Throws a [TflException] on failure.
 */
interface TimetableSource {
    suspend fun timetable(lineId: String, stopId: String): StopTimetable
}
