package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A route's fare charged at the times the rider touches in (SPEC *Trips with a change → Fare*): each of
 * the Planner's charges that has a peak and an off-peak price ([TripFare.Part]) costs the one TfL's
 * published hours give for when it's boarded. The Planner prices its own timetabled times, which a live
 * card moves away from, and it misplaces the edges of the peak by a few minutes besides (a 09:26 train
 * charged off-peak, a 06:34 one off-peak, a 16:02 one off-peak): a card arriving by a train boarded at
 * 09:15 showed the off-peak price beside a peak one for the same train (report, 2026-10-21).
 *
 * TfL charges peak fares on weekdays other than bank holidays, for touching in from 06:30 to 09:30 and
 * from 16:00 to 19:00, but not in the evening on a journey into zone 1 against the rush ([TripFare.Part.counterflow]).
 * A holiday declared for an occasion isn't known here, and is charged as its weekday.
 */
object PeakFare {
    private val LONDON: ZoneId = ZoneId.of("Europe/London")
    // Each window from its start up to, not including, its end: a boarding time can carry fractions of a second.
    private val MORNING = LocalTime.of(6, 30) to LocalTime.of(9, 30)
    private val EVENING = LocalTime.of(16, 0) to LocalTime.of(19, 0)

    private fun LocalTime.within(window: Pair<LocalTime, LocalTime>) = !isBefore(window.first) && isBefore(window.second)

    /** Whether touching in at [at] is charged peak; [counterflow] for a journey into zone 1 against the evening rush. */
    fun peak(at: Instant, counterflow: Boolean = false): Boolean {
        val local = at.atZone(LONDON)
        val day = local.toLocalDate()
        if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY || bankHoliday(day)) return false
        val time = local.toLocalTime()
        return time.within(MORNING) || (!counterflow && time.within(EVENING))
    }

    /**
     * [fare] with each charge that has both prices charged at the time [boardsAt] gives for its
     * Planner touch-in ([TripFare.Part.tapIn]); a charge it gives no time for keeps the Planner's own
     * time. Its [TripFare.pence] moves by what each charge moved by, so a Hopper discount the Planner
     * took off stays off; its [TripFare.level] is the one every charge with a level shares.
     */
    @WorkerThread
    fun charged(fare: TripFare, boardsAt: (Instant) -> Instant? = { it }): TripFare {
        if (fare.parts.none { it.peak != null && it.offPeak != null }) return fare
        val parts = fare.parts.map { part ->
            val peak = part.peak
            val offPeak = part.offPeak
            val planned = part.tapIn
            if (peak == null || offPeak == null || planned == null) return@map part
            val at = boardsAt(planned) ?: planned
            if (peak(at, part.counterflow)) {
                part.copy(pence = peak, level = TripFare.Level.PEAK)
            } else {
                part.copy(pence = offPeak, level = TripFare.Level.OFF_PEAK)
            }
        }
        val moved = parts.zip(fare.parts).sumOf { (now, was) -> now.pence - was.pence }
        val levels = parts.map { it.level }.distinct()
        return fare.copy(
            pence = fare.pence + moved,
            level = levels.singleOrNull(),
            parts = parts,
        )
    }

    /**
     * [fare] charged at the boarding times of a route's timing: the leg of [route] that leaves at a
     * charge's Planner touch-in is boarded when [legs] says. A leg without a time to board keeps the
     * Planner's.
     */
    @WorkerThread
    fun charged(fare: TripFare, route: TripRoute, legs: List<TripTiming.LegTiming>): TripFare = charged(fare) { tapIn ->
        val index = route.legs.indexOfFirst { !it.isWalk && it.departure == tapIn }
        legs.getOrNull(index)?.board
    }

    /** England's bank holidays, on which TfL charges off-peak all day. */
    fun bankHoliday(date: LocalDate): Boolean {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        val year = date.year
        val easter = easterSunday(year)
        fun monday(month: Month, last: Boolean) = LocalDate.of(year, month, 1).with(
            if (last) TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY) else TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY),
        )
        return date == easter.minusDays(2) || date == easter.plusDays(1) ||
            date == monday(Month.MAY, last = false) || date == monday(Month.MAY, last = true) ||
            date == monday(Month.AUGUST, last = true) ||
            date in substituted(LocalDate.of(year, Month.JANUARY, 1)) ||
            date in substituted(LocalDate.of(year, Month.DECEMBER, 25), LocalDate.of(year, Month.DECEMBER, 26))
    }

    // The weekdays a run of holidays is kept on: each on its own date if a weekday, or the next weekday
    // not already taken, as a holiday falling at a weekend moves to the Monday (or Tuesday) after.
    private fun substituted(vararg holidays: LocalDate): Set<LocalDate> {
        val kept = HashSet<LocalDate>()
        holidays.filter { !weekend(it) }.forEach { kept += it }
        holidays.filter { weekend(it) }.forEach { holiday ->
            var day = holiday
            while (weekend(day) || day in kept) day = day.plusDays(1)
            kept += day
        }
        return kept
    }

    private fun weekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
}
