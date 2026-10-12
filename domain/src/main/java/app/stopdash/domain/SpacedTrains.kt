package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * Tube trains queued behind one another at a platform, shown no sooner than a set gap apart. TfL times
 * each train in a queue as if the line ahead were clear, so a platform reads 0 · 0 · 1 · 2 · 2 min for
 * trains that come about two minutes apart: one can't leave until the one ahead has.
 *
 * Each train is moved to no sooner than [GAPS] after the train ahead of it, as already moved, so the
 * spacing cascades down the queue; the soonest keeps its own time. The gaps are measured, per line
 * (`scripts/arrivals_recorder.py`, one morning peak): the best gap is close to the line's own gap
 * between trains, so it can't be one number for every line. A line without one is left as TfL gives
 * it until it's measured.
 *
 * A train [DoubtfulTrains] marked is left out: it may not be there at all, so it holds no train back,
 * and it keeps its own time, grayed, rather than being moved into a slot as a train stopdash stands
 * behind. A train TfL still lists after it was due has gone, so it holds no train back either.
 *
 * Only a numbered platform says two trains queue for one track: a platform TfL names by direction
 * alone, TfL's "Platform Unknown" (which can be either direction), or another mode, is left as it is.
 */
object SpacedTrains {
    /** How soon after the train ahead each line's next train at a platform is shown. */
    val GAPS: Map<String, Duration> = mapOf(
        "victoria" to Duration.ofSeconds(90),
        "northern" to Duration.ofSeconds(120),
    )

    /** What [space] returned, and how far it moved each train it moved (for the debug log). */
    data class Result(val departures: List<Departure>, val moved: List<Pair<Departure, Duration>>)

    /** [departures] as of [now], each queued train no sooner than its line's gap behind the one ahead, in their given order. Walks the whole board, so off the main thread. */
    @WorkerThread
    fun space(departures: List<Departure>, now: Instant): Result {
        // TfL leaves a train's mode blank at times: it takes its line's, from the line's other trains.
        val lineMode = departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        // By index: two equal entries are still two listings.
        val times = HashMap<Int, Instant>()
        departures.indices.filter { i ->
            val d = departures[i]
            d.lineId in GAPS && !d.doubtful &&
                d.mode.ifBlank { lineMode[d.lineId].orEmpty() }.equals("tube", ignoreCase = true) &&
                PlatformDirection.platformNumber(d.platform).let { it != null && !it.equals("Unknown", ignoreCase = true) } &&
                !Countdown.hasDeparted(d, now)
        }
            // By the platform's number, not TfL's label: it names one platform both with and without
            // its direction ("Platform 2", "Southbound - Platform 2").
            .groupBy { departures[it].lineId to PlatformDirection.platformNumber(departures[it].platform) }
            .forEach { (key, atPlatform) ->
                val gap = GAPS.getValue(key.first)
                var ahead: Instant? = null
                for (i in atPlatform.sortedBy { departures[it].expectedArrival }) {
                    val own = departures[i].expectedArrival
                    val at = ahead?.plus(gap)?.takeIf { it > own } ?: own
                    if (at != own) times[i] = at
                    ahead = at
                }
            }
        if (times.isEmpty()) return Result(departures, emptyList())
        val moved = ArrayList<Pair<Departure, Duration>>()
        val all = departures.mapIndexed { i, d ->
            times[i]?.let { at -> d.copy(expectedArrival = at).also { moved += it to Duration.between(d.expectedArrival, at) } } ?: d
        }
        return Result(all, moved)
    }
}
