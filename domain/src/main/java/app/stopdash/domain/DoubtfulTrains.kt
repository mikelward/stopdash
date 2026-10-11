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
 * Two trains of a line at one platform under [GAP] apart can't both be there (the tightest scheduled
 * headway on the Underground is the Victoria line's 100 s), so the later of them is marked
 * [Departure.doubtful]: shown grayed rather than hidden, since it may be real (maintainer,
 * 2026-10-11). Further out TfL rounds its times to whole minutes and bunches them, so real trains
 * there get marked too; the app grays them on trial to see how that looks before the rule settles.
 *
 * Only the tube's tracking does this, and only a numbered platform says two trains share one: a bus
 * stop, or a platform TfL names by direction alone, is left as it is.
 */
object DoubtfulTrains {
    /** How soon after a line's train at one platform another can be predicted and still be believed. */
    val GAP: Duration = Duration.ofSeconds(90)

    /** What [mark] returned, and which of them it marked (for the debug log). */
    data class Result(val departures: List<Departure>, val marked: List<Departure>)

    /** [departures] as of [now], with each doubtful one marked, in their given order. Walks the whole board, so off the main thread. */
    @WorkerThread
    fun mark(departures: List<Departure>, now: Instant): Result {
        // By identity: two equal entries are still two listings, and only the later one is doubtful.
        val doubtful = Collections.newSetFromMap(IdentityHashMap<Departure, Boolean>())
        // A train TfL still lists after it was due has gone (it's dropped as the board is drawn), so it
        // can't be the one a train behind it is too close to.
        // TfL leaves a train's mode blank at times: it takes its line's, from the line's other trains.
        val lineMode = departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        departures.filter {
            it.mode.ifBlank { lineMode[it.lineId].orEmpty() }.equals("tube", ignoreCase = true) &&
                PlatformDirection.platformNumber(it.platform) != null && !Countdown.hasDeparted(it, now)
        }
            // By the platform's number, not TfL's label: it names one platform both with and without
            // its direction ("Platform 2", "Southbound - Platform 2").
            .groupBy { it.lineId to PlatformDirection.platformNumber(it.platform) }
            .values.forEach { atPlatform ->
                var ahead: Departure? = null
                for (train in atPlatform.sortedBy { it.expectedArrival }) {
                    val previous = ahead
                    if (previous != null && Duration.between(previous.expectedArrival, train.expectedArrival) < GAP) {
                        doubtful += train
                    } else {
                        ahead = train
                    }
                }
            }
        if (doubtful.isEmpty()) return Result(departures, emptyList())
        val marked = ArrayList<Departure>()
        val all = departures.map { d ->
            if (d in doubtful) d.copy(doubtful = true).also { marked += it } else d
        }
        return Result(all, marked)
    }
}
