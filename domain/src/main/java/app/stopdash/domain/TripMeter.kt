package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * A started trip as a progress bar and a status-bar chip (SPEC *On the way*, the Live Update): one
 * segment per leg, as long as the Planner's time on it, and how far along the rider is; the chip is
 * the one number the rider is counting down to next.
 */
object TripMeter {
    /** A leg as a stretch of the bar: [legIndex] into the route, [length] in seconds. */
    data class Segment(val legIndex: Int, val length: Int, val leg: TripLeg)

    /** The bar: its [segments] in order, and the rider's [progress] along their total. */
    data class Meter(val segments: List<Segment>, val progress: Int) {
        val total: Int get() = segments.sumOf { it.length }
    }

    /** What the chip counts: the stops left on a ride, or the minutes to the next thing. */
    sealed interface Chip {
        data class Stops(val count: Int) : Chip
        data class Minutes(val count: Int) : Chip
    }

    /** No leg shorter than this on the bar, so a minute's walk still shows as a stretch of its own. */
    private const val MIN_SEGMENT_SECONDS = 60

    /** At most this many segments: Android's progress bar merges any more into one, losing every color. */
    const val MAX_SEGMENTS = 10

    /**
     * [trip]'s bar at [progress] at [now]; null with no legs to draw, or with the train lost
     * ([TripProgress.Lost]): where the rider is along the leg isn't known then, so none is drawn.
     * Grows with the route, so off the main thread.
     */
    @WorkerThread
    fun meter(trip: ActiveTrip, progress: TripProgress?, now: Instant): Meter? {
        val legs = trip.route.legs
        if (legs.isEmpty() || progress is TripProgress.Lost) return null
        val segments = legs.mapIndexed { i, leg ->
            Segment(i, leg.run.seconds.coerceIn(MIN_SEGMENT_SECONDS.toLong(), Int.MAX_VALUE.toLong() / legs.size).toInt(), leg)
        }
        val total = segments.sumOf { it.length }
        if (progress == TripProgress.Arrived) return Meter(coalesce(segments), total)
        // The leg the step is on: a change names the ride it's onto, past the trip's own leg.
        val stepLeg = progress?.let(::legOf)
        val at = stepLeg?.let { legs.indexOf(it) }?.takeIf { it >= 0 } ?: trip.legIndex.coerceIn(0, legs.size - 1)
        val before = segments.take(at).sumOf { it.length }
        // A ride's stops are counted along the line the train followed runs it on ([OnTheWay.ridden]),
        // which can stop more or less often than the plan's: the fraction is of its stops, the segment the plan's.
        val counted = if (at == trip.legIndex) OnTheWay.ridden(trip) ?: segments[at].leg else segments[at].leg
        val within = (segments[at].length * fraction(progress, counted, now)).toInt()
        return Meter(coalesce(segments), (before + within).coerceIn(0, total))
    }

    /**
     * [segments] cut to [MAX_SEGMENTS] by folding the shortest into a neighbor (the shorter one), the
     * longer of the two keeping its leg: the walks and short hops go first, the rides that color the bar
     * stay. The total, so the rider's place along it, is unchanged.
     */
    internal fun coalesce(segments: List<Segment>): List<Segment> {
        val out = segments.toMutableList()
        while (out.size > MAX_SEGMENTS) {
            val i = out.indices.minBy { out[it].length }
            val j = when (i) {
                0 -> 1
                out.lastIndex -> i - 1
                else -> if (out[i - 1].length <= out[i + 1].length) i - 1 else i + 1
            }
            val (keep, gone) = if (out[i].length > out[j].length) out[i] to out[j] else out[j] to out[i]
            out[minOf(i, j)] = keep.copy(length = keep.length + gone.length)
            out.removeAt(maxOf(i, j))
        }
        return out
    }

    /** What the chip shows at [progress] at [now]; null when there's no count worth showing. */
    fun chip(progress: TripProgress?, now: Instant): Chip? = when (progress) {
        is TripProgress.Riding ->
            progress.stopsLeft?.takeIf { it > 0 }?.let { Chip.Stops(it) }
                ?: progress.getOffAt?.let { Chip.Minutes(Countdown.minutes(it, now).toInt()) }
        is TripProgress.Waiting -> progress.due?.let { Chip.Minutes(Countdown.minutes(it, now).toInt()) }
        // A change's and a walk's minutes round up, as the step's own line counts them ("About 4 min").
        is TripProgress.Changing -> Chip.Minutes(minutesUp(now, progress.until))
        // A walk run past its time has no minutes left to count.
        is TripProgress.Walking -> progress.until.takeIf { it.isAfter(now) }?.let { Chip.Minutes(minutesUp(now, it)) }
        is TripProgress.Lost, TripProgress.Arrived, null -> null
    }

    /** Whole minutes from [now] to [at], rounded up: a part minute left counts as one. */
    private fun minutesUp(now: Instant, at: Instant): Int =
        ((Duration.between(now, at).seconds.coerceAtLeast(0) + 59) / 60).toInt()

    private fun legOf(progress: TripProgress): TripLeg? = when (progress) {
        is TripProgress.Waiting -> progress.leg
        is TripProgress.Riding -> progress.leg
        is TripProgress.Changing -> progress.leg
        is TripProgress.Walking -> progress.leg
        is TripProgress.Lost -> progress.leg
        TripProgress.Arrived -> null
    }

    /** How far through [leg] the rider is, 0 to 1: by stops on a ride where counted, else by time. */
    private fun fraction(progress: TripProgress?, leg: TripLeg, now: Instant): Double {
        val f = when (progress) {
            is TripProgress.Riding -> {
                val left = progress.stopsLeft
                when {
                    left != null && leg.stops > 0 -> (leg.stops - left).toDouble() / leg.stops
                    progress.getOffAt != null -> 1.0 - remaining(now, progress.getOffAt, leg)
                    else -> 0.0
                }
            }
            is TripProgress.Walking -> 1.0 - remaining(now, progress.until, leg)
            else -> 0.0
        }
        return f.coerceIn(0.0, 1.0)
    }

    private fun remaining(now: Instant, until: Instant, leg: TripLeg): Double {
        val run = leg.run.seconds
        if (run <= 0) return 0.0
        return Duration.between(now, until).seconds.coerceAtLeast(0).toDouble() / run
    }
}
