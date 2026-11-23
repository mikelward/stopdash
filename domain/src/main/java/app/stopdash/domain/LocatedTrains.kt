package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * How long Tube trains took to arrive from where TfL said they were (SPEC *Departures*): for each platform
 * of each line at each stop, and each of TfL's location texts ("Left East Finchley", "Between East Finchley
 * and Highgate"), the median time to arrival of the trains recorded there, by how long they had been at it,
 * in [STEP]s. Built from recordings of the whole Tube (`scripts/arrivals_recorder.py table`) and bundled.
 *
 * TfL's countdown can sit at a minute or less while a train is still minutes away (a rider's report,
 * 2026-11-23: "0 min" for two minutes while the station's own board said 2), though its location moves on.
 * Recorded, such a train came about half a minute nearer the location's time than TfL's.
 */
class LocationTimings(private val steps: Map<String, Map<Int, Int>>) {
    /**
     * Seconds to arrival for a train at [platform] (its number) of [lineId] at [stopId], TfL having placed it
     * at [state] ([stateOf]) for [elapsed]; null where nothing was recorded so long into it.
     */
    fun estimate(stopId: String, lineId: String, platform: String, state: String, elapsed: Duration): Duration? {
        if (elapsed.isNegative) return null
        val step = (elapsed.seconds / STEP.seconds).toInt()
        return steps[key(stopId, lineId, platform, state)]?.get(step)?.let { Duration.ofSeconds(it.toLong()) }
    }

    val isEmpty: Boolean get() = steps.isEmpty()

    companion object {
        /** How finely the time a train has been at one location is told apart, as recorded. */
        val STEP: Duration = Duration.ofSeconds(20)

        val EMPTY = LocationTimings(emptyMap())

        private val PLATFORM_SUFFIX = Regex("""\s+platform\s+\w+$""", RegexOption.IGNORE_CASE)

        /**
         * TfL's location text as the table keys it: lower case, trimmed, without a trailing "Platform N" (TfL
         * names one place both with and without it) or comma; empty for none. As the recorder reads it.
         */
        fun stateOf(location: String): String =
            PLATFORM_SUFFIX.replace(location.trim().lowercase(Locale.ROOT), "").trimEnd(',').trim()

        fun key(stopId: String, lineId: String, platform: String, state: String) = "$stopId|$lineId|$platform|$state"
    }
}

/**
 * Tube trains TfL says are due that their location says are still well away ([LocationTimings]): such a
 * train keeps TfL's time and is given the location's as the latest it may come ([Departure.upTo]), shown as a
 * range ("0–2 min"; maintainer, 2026-11-23). Recorded, taking the location's time alone in place of TfL's
 * stalled one put about as many trains that did come soon a minute or more too late as it put right, so
 * neither is shown alone: the range says the train may still be coming, never that one due now isn't.
 * How long a train has been at its location is counted from when this saw it there first, so it remembers
 * each train's location per platform, bounded to [MAX] trains, the least recently seen dropped first; a
 * train not seen for [FORGET] starts over. One is kept for the process ([SHARED]), as screens and the
 * widget each build their own client.
 */
class LocatedTrains {
    // Where a train was first seen at [state], when last seen, and the latest it was given then ([Departure.upTo]).
    private class Seen(val state: String, val since: Instant, val last: Instant, val upTo: Instant? = null)

    private val seen = LinkedHashMap<String, Seen>()

    /** What [retime] returned, and each train it gave a range, with how much later its latest is (for the debug log). */
    data class Result(val departures: List<Departure>, val moved: List<Pair<Departure, Duration>>)

    /**
     * [departures] at [stopId] answered at [now], each listed at [locations] (TfL's text, by index), each due
     * within [STALLED] by TfL given its location's time from [timings] as its latest ([Departure.upTo]) where that
     * is at least [WIDER] later. Only tube trains with an id at a numbered platform, not yet at it.
     */
    @WorkerThread
    @Synchronized
    fun retime(stopId: String, now: Instant, departures: List<Departure>, locations: List<String>, timings: LocationTimings): Result {
        if (timings.isEmpty) return Result(departures, emptyList())
        val lineMode = departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        val moved = ArrayList<Pair<Departure, Duration>>()
        val out = departures.mapIndexed { i, d ->
            val tube = d.mode.ifBlank { lineMode[d.lineId].orEmpty() }.equals("tube", ignoreCase = true)
            val platform = PlatformDirection.knownPlatformNumber(d.platform)
            val state = LocationTimings.stateOf(locations.getOrNull(i).orEmpty())
            if (!tube || d.vehicleId.isBlank() || platform == null || state.isEmpty()) return@mapIndexed d
            val key = "$stopId|${d.lineId}|${d.vehicleId}|$platform"
            val last = seen.remove(key)
            val since = if (last == null || last.state != state || now.isBefore(last.last) || Duration.between(last.last, now) > FORGET) now else last.since
            seen[key] = Seen(state, since, now)
            if (seen.size > MAX) seen.remove(seen.keys.first())
            if (state.startsWith(AT_PLATFORM)) return@mapIndexed d
            if (Duration.between(now, d.expectedArrival) > STALLED) return@mapIndexed d
            val estimate = timings.estimate(stopId, d.lineId, platform, state, Duration.between(since, now)) ?: return@mapIndexed d
            val located = now.plus(estimate)
            val later = Duration.between(d.expectedArrival, located)
            if (later < WIDER) return@mapIndexed d
            seen[key] = Seen(state, since, now, located)
            val ranged = d.copy(upTo = located)
            moved += ranged to later
            ranged
        }
        return Result(out, moved)
    }

    /**
     * [departures] at [stopId] from an answer older than the last ([NewestArrivals] keeps the newer one's trains,
     * without where they were): each keeps the latest the newest answer gave it, while that's still to come at
     * [now], so a "0–2 min" doesn't fall back to "0" or go for an older copy. Nothing is learned from them.
     */
    @Synchronized
    fun kept(stopId: String, now: Instant, departures: List<Departure>): List<Departure> =
        departures.map { d ->
            val platform = PlatformDirection.knownPlatformNumber(d.platform) ?: return@map d
            val upTo = seen["$stopId|${d.lineId}|${d.vehicleId}|$platform"]?.upTo
            if (d.upTo == null && d.vehicleId.isNotBlank() && upTo != null && upTo.isAfter(now)) d.copy(upTo = upTo) else d
        }

    companion object {
        /** A train TfL says is due this soon or sooner is the one whose countdown may have stalled. */
        val STALLED: Duration = Duration.ofMinutes(1)

        /** The least its location's time must be later for a range: less reads as TfL's own minute. */
        val WIDER: Duration = Duration.ofMinutes(1)
        const val MAX = 2000
        val FORGET: Duration = Duration.ofMinutes(5)
        private const val AT_PLATFORM = "at platform"
        val SHARED = LocatedTrains()
    }
}
