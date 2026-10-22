package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One service day of a line's timetable at a stop, as a route page's timetable shows it (maintainer, 2026-10-22):
 * each stretch where trains come often summed up as "06:00–10:00 every 4 min" ([Stretch.Frequent]), and the rest
 * listed by time, an hour of the clock at a time ([Stretch.Times]). Times are minutes after the service day's
 * midnight in London, as the timetable gives them, so a night's last trains read 24:00 and later.
 *
 * Shown whatever the line's status or the stop's notices, since the rider asked for the timetable itself; the page
 * says what it's up against beside it. A timetable knows nothing of a disruption, so these are never shown as live.
 *
 * [holiday] is a day TfL may run another day type's timetable on ([publicHoliday]), which the times can't say.
 * [partial] is a day with a departure the timetable couldn't read, or a schedule it couldn't place on a day at all
 * ([StopTimetable.readable]): some trains may be missing. [unscheduled] is a timetable with no schedule for any day
 * (TfL has none for this stop and line), rather than one that runs nothing on [day].
 */
data class ScheduledDay(
    val day: LocalDate,
    val stretches: List<Stretch>,
    val holiday: Boolean = false,
    val partial: Boolean = false,
    val unscheduled: Boolean = false,
) {
    sealed interface Stretch {
        /** The minute of the stretch's last departure, after the service day's midnight. */
        val last: Int

        /** Departures from [first] to [last], each [shortest] to [longest] minutes after the one before. */
        data class Frequent(val first: Int, override val last: Int, val shortest: Int, val longest: Int) : Stretch

        /** Departures listed one by one, [minutes] after the service day's midnight, all in one hour of the clock. */
        data class Times(val minutes: List<Int>) : Stretch {
            override val last: Int get() = minutes.last()
        }
    }

    companion object {
        /**
         * The longest gap trains may leave and still be summed up as a frequency: TfL counts a bus route running five
         * or more an hour as high frequency, and gives its stops "every few minutes" rather than times.
         */
        const val FREQUENT_GAP = 12

        /** The fewest departures a frequency sums up: fewer read as quickly listed. */
        const val FREQUENT_TRAINS = 6

        private val LONDON: ZoneId = ZoneId.of("Europe/London")

        /**
         * [timetable]'s departures on service [day], summed up ([summarize]). Every schedule running that day counts,
         * a time two of them share once.
         */
        @WorkerThread
        fun of(timetable: StopTimetable, day: LocalDate): ScheduledDay {
            val running = timetable.schedules.filter { day.dayOfWeek in it.days }
            val minutes = running.flatMap { it.minutes }.distinct().sorted()
            return ScheduledDay(
                day,
                summarize(minutes),
                holiday = publicHoliday(day),
                partial = !timetable.readable || running.any { !it.complete },
                unscheduled = timetable.readable && timetable.schedules.isEmpty(),
            )
        }

        /**
         * [minutes] (sorted, distinct) as stretches: each run of at least [FREQUENT_TRAINS] departures whose gaps stay
         * within [FREQUENT_GAP] and close enough to one another to be one frequency (the longest within half the
         * shortest again, or two minutes, of it) sums up as one, so a change from every 4 minutes to every 8 starts
         * another; the rest are listed, an hour of the clock to a stretch. A run's last departure starts the next
         * run where one follows straight on ("06:00–07:00 every 4 min", "07:00–09:00 every 8 min"), and is never
         * listed again after it.
         */
        @WorkerThread
        fun summarize(minutes: List<Int>): List<Stretch> {
            val stretches = ArrayList<Stretch>()
            val listed = ArrayList<Int>()
            fun flush() {
                listed.groupBy { it / 60 }.values.forEach { stretches += Stretch.Times(it) }
                listed.clear()
            }
            var i = 0
            // The departure at [i] ends the run before it, so it's shown already.
            var shown = false
            while (i < minutes.size) {
                var j = i
                var shortest = Int.MAX_VALUE
                var longest = 0
                while (j + 1 < minutes.size) {
                    val gap = minutes[j + 1] - minutes[j]
                    if (gap > FREQUENT_GAP) break
                    val low = minOf(shortest, gap)
                    val high = maxOf(longest, gap)
                    if (high > low + maxOf(2, low / 2)) break
                    shortest = low
                    longest = high
                    j++
                }
                if (j - i + 1 >= FREQUENT_TRAINS) {
                    flush()
                    stretches += Stretch.Frequent(minutes[i], minutes[j], shortest, longest)
                    i = j
                    shown = true
                } else {
                    if (!shown) listed += minutes[i]
                    shown = false
                    i++
                }
            }
            flush()
            return stretches
        }

        /** The London service day [at] falls in: it ends at 04:00, when the last night buses have run. */
        fun serviceDay(at: Instant): LocalDate = TimetableRepository.serviceDay(at)

        /** [at] as minutes after service [day]'s midnight on London's clock, as a timetable's times are. */
        fun minuteOf(at: Instant, day: LocalDate): Long = ChronoUnit.MINUTES.between(day.atStartOfDay(), at.atZone(LONDON).toLocalDateTime())
    }
}
