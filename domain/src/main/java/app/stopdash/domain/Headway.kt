package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * How often a line runs, read off its live predictions (maintainer, 2026-09-27): the middle half of
 * the gaps between them, so one bunched pair or one long gap doesn't set the range. A leg of a trip
 * the rider hasn't reached yet shows this ("Every 2–4 min") in place of countdowns it can't use.
 */
object Headway {
    /** A gap range in whole minutes, [min] ≤ [max], each at least 1. */
    data class Range(val min: Int, val max: Int)

    /** How many predictions a range needs: fewer say too little about how often the line runs. */
    const val MIN_TRAINS = 3

    /**
     * The middle half of the gaps between [times] (any order, repeats counted once), rounded to whole
     * minutes; null with fewer than [MIN_TRAINS] distinct times.
     */
    fun of(times: List<Instant>): Range? {
        val sorted = times.distinct().sorted()
        if (sorted.size < MIN_TRAINS) return null
        val gaps = sorted.zipWithNext { a, b -> Duration.between(a, b) }.sorted()
        // The quartiles, at their nearest gap: the middle half, trimmed alike at both ends.
        val last = gaps.size - 1
        val low = minutes(gaps[Math.round(last * 0.25).toInt()])
        val high = minutes(gaps[Math.round(last * 0.75).toInt()])
        return Range(low, maxOf(low, high))
    }

    // Nearest whole minute, at least 1: two trains a few seconds apart still read as "every 1 min".
    private fun minutes(gap: Duration): Int = ((gap.seconds + 30) / 60).toInt().coerceAtLeast(1)
}
