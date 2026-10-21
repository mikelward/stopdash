package app.stopdash.domain

import androidx.annotation.WorkerThread
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
 * A timetable knows nothing of the disruption that empties a board (maintainer, 2026-10-03), so a
 * time from it is shown only where nothing live can say and the line runs a good service, marked as
 * the timetable's: a favorite ride's next train before its line opens ([nextDeparture], maintainer,
 * 2026-10-21). It tells an empty board's "none coming" from "none known" ([EmptyTimes]), and how long
 * a trip may wait for the next train past its live predictions on a line running a good service
 * ([longestGapAround], maintainer, 2026-10-19).
 *
 * [readable] is false when some schedule's days couldn't be read ("School days"): it's left out of
 * [schedules], but it might run on any day, so the timetable can then never say nothing is due.
 */
data class StopTimetable(val schedules: List<DaySchedule>, val readable: Boolean = true) {
    /**
     * The departures of the service days in [days], in minutes after their midnight. [complete] is
     * false when some departure couldn't be read: its days then can't be said to have nothing due.
     * [calls], where TfL said, is each departure's calling pattern, the stops it calls at after this one, in
     * [minutes]' order (null for one whose pattern couldn't be read): what [along] tells one route's from another's.
     */
    data class DaySchedule(
        val days: Set<DayOfWeek>,
        val minutes: List<Int>,
        val complete: Boolean = true,
        val calls: List<List<String>?>? = null,
    )

    /**
     * This timetable's departures along [path], a route from this stop (its first stop) to its end: those whose
     * calling pattern ends where [path] does and calls nowhere off it, so a branching line's trains each count for
     * their own branch (Highgate's for Morden via Bank, not via Charing Cross) and a short working, ending sooner,
     * for none of the two. A departure whose pattern is unknown might be one of them, so its schedule can't be said
     * to be whole ([DaySchedule.complete]): [nextDepartures] then says nothing past that day's opening.
     */
    @WorkerThread
    fun along(path: List<String>): StopTimetable {
        val end = path.lastOrNull()
        // The stop itself counted on it too, in case a pattern lists where it starts.
        val on = path.toHashSet()
        return copy(
            schedules = schedules.map { schedule ->
                val calls = schedule.calls
                if (end == null || calls == null || calls.size != schedule.minutes.size) {
                    return@map schedule.copy(minutes = emptyList(), complete = false, calls = emptyList())
                }
                var complete = schedule.complete
                val kept = schedule.minutes.indices.filter { i ->
                    val pattern = calls[i] ?: run { complete = false; return@filter false }
                    pattern.lastOrNull() == end && pattern.all { it in on }
                }
                schedule.copy(minutes = kept.map { schedule.minutes[it] }, complete = complete, calls = kept.map { calls[it] })
            },
        )
    }

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

    /**
     * The first departure at or after [at] and within [NEXT_LIMIT] of it, by this timetable. Null when it
     * can't say for sure: a schedule left unread, or a day that could run one sooner than the one found
     * that's a holiday ([publicHoliday]), has no schedule, or has a journey that couldn't be read; and
     * null when nothing leaves within the limit.
     */
    @WorkerThread
    fun nextDeparture(at: Instant): Instant? = nextDepartures(at, 1).firstOrNull()

    /**
     * The first [count] departures at or after [at] and within [NEXT_LIMIT] of it, by this timetable, soonest
     * first (maintainer, 2026-10-21: a ride's page gives the next few). Only as many as it can say for sure: none
     * past the opening of a day that's a holiday ([publicHoliday]), has no schedule, or has a journey that
     * couldn't be read, since that day could run one sooner; none at all with a schedule left unread.
     */
    @WorkerThread
    fun nextDepartures(at: Instant, count: Int): List<Instant> {
        if (!readable || count <= 0) return emptyList()
        val today = at.atZone(LONDON).toLocalDate()
        val end = at.plus(NEXT_LIMIT)
        val found = ArrayList<Instant>()
        // The soonest a day this timetable can't read could run a train from: nothing past it is sure.
        var unsureFrom: Instant? = null
        for (day in listOf(today.minusDays(1), today, today.plusDays(1))) {
            val midnight = day.atStartOfDay()
            val opens = midnight.atZone(LONDON).toInstant()
            val closes = midnight.plusMinutes(SERVICE_DAY_MINUTES.toLong()).atZone(LONDON).toInstant()
            // A service day that's over by [at], or starts after the limit, runs none of them.
            if (closes.isBefore(at) || opens.isAfter(end)) continue
            val its = schedules.filter { day.dayOfWeek in it.days }
            if (its.isEmpty() || its.any { !it.complete } || publicHoliday(day)) {
                val from = if (opens.isBefore(at)) at else opens
                if (unsureFrom == null || from.isBefore(unsureFrom)) unsureFrom = from
                continue
            }
            // In autumn's repeated hour a time has two instants, and the timetable doesn't say which: the first still
            // to come counts, so asked in the second 01:10, a 01:30 is still to come (Codex on #1036), but once: it's
            // one train, not two (Codex on #1042).
            its.forEach { schedule ->
                schedule.minutes.forEach { minute ->
                    val zoned = midnight.plusMinutes(minute.toLong()).atZone(LONDON)
                    val instant = listOf(zoned.withEarlierOffsetAtOverlap().toInstant(), zoned.withLaterOffsetAtOverlap().toInstant())
                        .firstOrNull { !it.isBefore(at) } ?: return@forEach
                    if (!instant.isAfter(end)) found += instant
                }
            }
        }
        val sure = unsureFrom
        return found.distinct().sorted().filter { sure == null || it.isBefore(sure) }.take(count)
    }

    /**
     * The longest a rider reaching the stop around [at] may wait for a departure, by this timetable:
     * the longest gap between departures over [GAP_BEFORE] before [at] to [GAP_AFTER] after it, since
     * when the rider gets there is itself an estimate. Null when the timetable can't say: a schedule
     * left unread, a holiday ([publicHoliday]), a day it doesn't cover whole, or no departure within
     * [GAP_LIMIT] either side of that stretch (a line starting or ending for the night).
     */
    @WorkerThread
    fun longestGapAround(at: Instant): Duration? {
        if (!readable) return null
        val local = at.atZone(LONDON)
        val today = local.toLocalDate()
        // The days whose service could be running at [at]: today's, and yesterday's past midnight
        // until the service day ends.
        val running = if (local.hour < SERVICE_DAY_ENDS_HOUR) listOf(today.minusDays(1), today) else listOf(today)
        val covered = running.all { day ->
            val its = schedules.filter { day.dayOfWeek in it.days }
            !publicHoliday(day) && its.isNotEmpty() && its.all { it.complete }
        }
        if (!covered) return null
        val departures = listOf(today.minusDays(1), today, today.plusDays(1)).flatMap { day ->
            val midnight = day.atStartOfDay()
            schedules.filter { day.dayOfWeek in it.days }.flatMap { schedule ->
                // In autumn's repeated hour a time has two instants: the earlier is taken, as a gap
                // across it is never shorter for it.
                schedule.minutes.map { midnight.plusMinutes(it.toLong()).atZone(LONDON).withEarlierOffsetAtOverlap().toInstant() }
            }
        }.distinct().sorted()
        val start = at.minus(GAP_BEFORE)
        val end = at.plus(GAP_AFTER)
        val before = departures.lastOrNull { !it.isAfter(start) } ?: return null
        val after = departures.firstOrNull { !it.isBefore(end) } ?: return null
        if (Duration.between(before, start) > GAP_LIMIT || Duration.between(end, after) > GAP_LIMIT) return null
        val stretch = departures.filter { !it.isBefore(before) && !it.isAfter(after) }
        return stretch.zipWithNext { a, b -> Duration.between(a, b) }.maxOrNull()
    }

    companion object {
        private val LONDON: ZoneId = ZoneId.of("Europe/London")

        /** How far before the time asked [longestGapAround] reads the timetable. */
        val GAP_BEFORE: Duration = Duration.ofMinutes(5)

        /** How far after the time asked [longestGapAround] reads the timetable. */
        val GAP_AFTER: Duration = Duration.ofMinutes(15)

        /** How far ahead [nextDeparture] looks: a day, so a line closed for the night still gives its first train. */
        val NEXT_LIMIT: Duration = Duration.ofHours(24)

        /** The longest gap [longestGapAround] reads past its stretch before it gives no answer. */
        val GAP_LIMIT: Duration = Duration.ofMinutes(60)

        // A service day ends at 04:00 the next morning ([SERVICE_DAY_MINUTES]).
        private const val SERVICE_DAY_ENDS_HOUR = 4

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

/**
 * Reads a line's timetable for a trip's ride from TfL: the departures from [fromId] whose trains call
 * at [toId] after it ([LegTimetables]). Separate from [TimetableSource], since a board asks about a
 * stop and a ride about where it gets off. Throws a [TflException] on failure.
 */
interface LegTimetableSource {
    suspend fun legTimetable(lineId: String, fromId: String, toId: String): StopTimetable
}
