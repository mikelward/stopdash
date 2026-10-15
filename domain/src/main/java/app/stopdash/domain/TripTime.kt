package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * When a later trip is planned for (SPEC *Trips with a change → A later trip*): leaving at, or
 * arriving by, a wall-clock time in London. A trip with none is planned for now, live. TfL's
 * Planner takes the same three things ([plannerDate], [plannerTime], [plannerTimeIs]) and answers
 * from its timetable, so a later trip's times are the Planner's alone: nothing live is that far ahead.
 */
data class TripTime(val kind: Kind, val at: LocalDateTime) {
    enum class Kind { LEAVE, ARRIVE }

    /** The Planner's `date`, `yyyyMMdd`. */
    val plannerDate: String get() = at.format(DATE)

    /** The Planner's `time`, `HHmm`. */
    val plannerTime: String get() = at.format(TIME)

    /** The Planner's `timeIs`: whether [at] is when the trip leaves or when it gets there. */
    val plannerTimeIs: String get() = if (kind == Kind.LEAVE) "Departing" else "Arriving"

    /**
     * Whether [at] has gone by at [now]: a trip can't be planned to leave, or arrive, in the past. In the
     * hour the clocks go back, [at] happens twice; it has gone by only once its second reading has, so
     * during the repeated hour a time still ahead can be picked.
     */
    fun isPast(now: Instant): Boolean =
        at.atZone(AlertStart.ZONE).withLaterOffsetAtOverlap().toInstant().isBefore(now.truncatedTo(ChronoUnit.MINUTES))

    /**
     * Whether [route] keeps to the time: leaving no sooner than a leave-at time, arriving no later than
     * an arrive-by one. The Planner is asked for one direction only, so this holds for what it offers;
     * a route that doesn't isn't listed rather than shown against the time it breaks. In the hour the
     * clocks go back, either reading of [at] is the Planner's to take, so a route keeping to either is kept.
     */
    fun keeps(route: TripRoute): Boolean {
        val zoned = at.atZone(AlertStart.ZONE)
        return when (kind) {
            Kind.LEAVE -> route.legs.firstOrNull()?.departure?.isBefore(zoned.withEarlierOffsetAtOverlap().toInstant()) == false
            Kind.ARRIVE -> route.legs.lastOrNull()?.arrival?.isAfter(zoned.withLaterOffsetAtOverlap().toInstant()) == false
        }
    }

    /**
     * The instant the Planner's answer for this time is read from, as a trip for now's is read from now: a
     * time it gives in the hour the clocks go back is the earlier of its two readings unless that falls before
     * this. Leaving at a time, its journeys leave from it on; arriving by one, they leave not long before it,
     * so it's read from an hour before the time's latest reading, and a repeated hour's departure is the
     * second one, the one that still gets there in time.
     */
    val readFrom: Instant
        get() {
            val zoned = at.atZone(AlertStart.ZONE)
            return when (kind) {
                Kind.LEAVE -> zoned.withEarlierOffsetAtOverlap().toInstant()
                Kind.ARRIVE -> zoned.withLaterOffsetAtOverlap().toInstant().minus(Duration.ofHours(1))
            }
        }

    /** As saved ([parse]), for a screen to keep it across a rotation or a recreated process. */
    fun encode(): String = "${kind.name}@$at"

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /** How many days the picker offers, today first: about as far as anyone plans a ride ahead. */
        const val DAYS = 7

        /** How far a suggested time rounds up to, so the picker opens on a time worth leaving at. */
        private const val ROUND_MINUTES = 15L

        /**
         * [kind] at [date] and [time] in London, as the picker picks it. A time the clocks skip when they go
         * forward (01:30 on that night) is the time it becomes (02:30), so the header, whether it has gone by,
         * and what the Planner is asked for all agree; any other is itself. The picker refuses a [skipped]
         * time rather than plan one other than it shows.
         */
        fun of(kind: Kind, date: LocalDate, time: LocalTime): TripTime =
            TripTime(kind, LocalDateTime.of(date, time).atZone(AlertStart.ZONE).toLocalDateTime())

        /**
         * Whether [time] on [date] never shows on a London clock: the hour skipped when the clocks go forward
         * (01:00–01:59 on that night). The repeated hour when they go back is shown, twice, so it isn't.
         */
        fun skipped(date: LocalDate, time: LocalTime): Boolean =
            AlertStart.ZONE.rules.getValidOffsets(LocalDateTime.of(date, time)).isEmpty()

        /**
         * The time [encode] saved, or null for none or one unreadable (the trip then plans for now). An
         * unreadable one is told to [warn] by its kind of failure alone, never the time it held.
         */
        fun parse(saved: String?, warn: (String) -> Unit = {}): TripTime? {
            if (saved == null) return null
            val parts = saved.split('@', limit = 2)
            if (parts.size != 2) {
                warn("saved trip time unreadable: no kind")
                return null
            }
            val (kind, at) = parts
            return try {
                TripTime(Kind.valueOf(kind), LocalDateTime.parse(at))
            } catch (e: IllegalArgumentException) {
                // Saved by this app, so only an older format gets here: the trip plans for now.
                warn("saved trip time unreadable: ${e::class.simpleName}")
                null
            } catch (e: DateTimeParseException) {
                warn("saved trip time unreadable: ${e::class.simpleName}")
                null
            }
        }

        /** The days the picker offers from [now]'s in London: today, then the next [DAYS] − 1. */
        fun days(now: Instant): List<LocalDate> {
            val today = now.atZone(AlertStart.ZONE).toLocalDate()
            return (0 until DAYS).map { today.plusDays(it.toLong()) }
        }

        /**
         * Where the picker opens with none chosen yet: leaving at [now] in London, rounded up to the
         * next quarter hour (a minute past it rounds up, the quarter itself stays).
         */
        fun suggested(now: Instant): TripTime {
            val local = now.atZone(AlertStart.ZONE).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
            val over = local.minute % ROUND_MINUTES
            val rounded = if (over == 0L) local else local.plusMinutes(ROUND_MINUTES - over)
            return TripTime(Kind.LEAVE, rounded)
        }

        /**
         * A later trip's routes as listed: leaving at a time, by when they get there (then when they
         * leave); arriving by one, the latest to leave first (then by when they get there), since
         * leaving later is the point. Work over every route's legs: off the main thread.
         */
        @WorkerThread
        fun ordered(routes: List<TripRoute>, time: TripTime): List<TripRoute> {
            fun leaves(route: TripRoute) = route.legs.firstOrNull()?.departure ?: Instant.MAX
            fun arrives(route: TripRoute) = route.legs.lastOrNull()?.arrival ?: Instant.MAX
            val order = if (time.kind == Kind.LEAVE) {
                compareBy<TripRoute>(::arrives).thenBy(::leaves)
            } else {
                compareByDescending<TripRoute>(::leaves).thenBy(::arrives)
            }
            return routes.sortedWith(order)
        }
    }
}
