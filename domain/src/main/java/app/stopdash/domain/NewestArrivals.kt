package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * Each stop's newest arrivals answer, so an older one TfL's cache hands back is set aside. TfL's
 * answers come from cache copies of different ages (up to a minute old), so polling a stop can
 * step back in time: a train already at the platform reads "approaching" again, a countdown jumps
 * up. Each answer is dated by when TfL made its predictions ([pick]'s `madeAt`), and one older than
 * the stop's last is swapped for that last, whose times are absolute and still count down.
 *
 * An answer more than [MAX_STEP_BACK] older than the last is taken as it comes: no cache copy is
 * that old, so TfL's own clock moved, and holding on would freeze the stop on its last answer.
 * An undated answer (none of its predictions dated, or none at all) is taken as it comes too.
 * Memory only, bounded to [MAX] stops, the least recently answered dropped first. One is kept for
 * the process ([SHARED]), since screens and the widget each build their own client yet poll the
 * same stops: a client of its own would take the first answer it sees, however old.
 */
class NewestArrivals {
    /** What [pick] chose: the departures to use, and how much older the answer set aside was, if one was. */
    data class Pick(val departures: List<Departure>, val setAside: Duration? = null)

    private class Held(val madeAt: Instant, val departures: List<Departure>)

    private val held = LinkedHashMap<String, Held>()

    @Synchronized
    fun pick(stopId: String, madeAt: Instant?, departures: List<Departure>): Pick {
        val last = held[stopId]
        if (madeAt == null) return Pick(departures)
        if (last != null && madeAt.isBefore(last.madeAt)) {
            val back = Duration.between(madeAt, last.madeAt)
            if (back <= MAX_STEP_BACK) return Pick(last.departures, back)
        }
        held.remove(stopId)
        held[stopId] = Held(madeAt, departures)
        if (held.size > MAX) held.remove(held.keys.first())
        return Pick(departures)
    }

    companion object {
        val MAX_STEP_BACK: Duration = Duration.ofMinutes(2)
        const val MAX = 64

        /** The process's one, passed to every client that fetches arrivals. */
        val SHARED = NewestArrivals()
    }
}
