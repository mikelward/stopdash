package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tube trains TfL predicts that may not be there. TfL's tracking sometimes follows one train under a
 * second id a signal section behind it: both count down together 30 to 60 s apart (or due at the same
 * second), each with its own destination, at one platform, and the copy is still "At Platform" after the
 * real train has left. Nothing in TfL's answer tells the copy apart: it has its own id, moves station by
 * station and is refreshed like any train. The station's own display, fed from the signalling, shows it
 * once.
 *
 * Of two trains of a line at one platform [PAIR] or less apart, the first due within [PAIR_WITHIN],
 * either may be the copy: recordings of such pairs found one dropped from the board before it was due
 * about half the time, and nothing in the feed said which (`dev-docs/arrivals-measurements.md`). So both
 * are marked [Departure.paired] and grayed rather than hidden, since either may be real (maintainer,
 * 2026-10-13), and the later is marked [Departure.doubtful] too, so a trip counts on the earlier.
 *
 * Trains further apart are believed (maintainer, 2026-10-14): those 61 to 89 s apart were mostly two
 * real trains in the recordings, and [SpacedTrains] shows them a line's gap apart. Further out the
 * recordings say nothing, and TfL's whole-minute times would pair most real trains, so a pair there is
 * left alone too.
 *
 * Only the tube's tracking does this, and only a numbered platform says two trains share one: a bus
 * stop, a platform TfL names by direction alone, or its "Platform Unknown", is left as it is.
 */
object DoubtfulTrains {
    /** How close two of a line's trains at one platform are timed for either to be the one that doesn't come. */
    val PAIR: Duration = Duration.ofSeconds(60)

    /** How soon the first of a [PAIR] must be due for the pair to be marked: as far as the recordings looked. */
    val PAIR_WITHIN: Duration = Duration.ofMinutes(5)

    /** What [mark] returned, and which of them it marked (for the debug log). */
    data class Result(val departures: List<Departure>, val marked: List<Departure>)

    /** [departures] as of [now], with each pair marked, in their given order. Walks the whole board, so off the main thread. */
    @WorkerThread
    fun mark(departures: List<Departure>, now: Instant): Result {
        // By identity: two equal entries are still two listings, and only the later one is doubtful.
        // Every doubtful train is paired too.
        val doubtful = Collections.newSetFromMap(IdentityHashMap<Departure, Boolean>())
        val paired = Collections.newSetFromMap(IdentityHashMap<Departure, Boolean>())
        // A train TfL still lists after it was due has gone (it's dropped as the board is drawn), so it
        // can't be the one a train behind it is too close to.
        // TfL leaves a train's mode blank at times: it takes its line's, from the line's other trains.
        val lineMode = departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        departures.filter {
            it.mode.ifBlank { lineMode[it.lineId].orEmpty() }.equals("tube", ignoreCase = true) &&
                PlatformDirection.knownPlatformNumber(it.platform) != null && !Countdown.hasDeparted(it, now)
        }
            // By the platform's number, not TfL's label: it names one platform both with and without
            // its direction ("Platform 2", "Southbound - Platform 2"). "Platform Unknown" is no platform.
            .groupBy { it.lineId to PlatformDirection.knownPlatformNumber(it.platform) }
            .values.forEach { atPlatform ->
                atPlatform.sortedBy { it.expectedArrival }.zipWithNext { earlier, later ->
                    if (Duration.between(now, earlier.expectedArrival) < PAIR_WITHIN &&
                        Duration.between(earlier.expectedArrival, later.expectedArrival) <= PAIR
                    ) {
                        paired += earlier
                        paired += later
                        doubtful += later
                    }
                }
            }
        if (paired.isEmpty()) return Result(departures, emptyList())
        val marked = ArrayList<Departure>()
        val all = departures.map { d ->
            if (d in paired) d.copy(doubtful = d in doubtful, paired = d in paired).also { marked += it } else d
        }
        return Result(all, marked)
    }
}
