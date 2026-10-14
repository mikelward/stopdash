package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.util.IdentityHashMap

/**
 * Tube trains spaced out at their platform. TfL times each train as if the line ahead were clear, so a
 * queue of trains reads as due within a minute of each other (0 · 0 · 1 · 2 · 2 min at one platform)
 * when they come about two minutes apart. Each train of a line at one numbered platform is shown no
 * sooner than its line's [gapFor] after the one ahead of it, as that one was shown. A moved train is
 * shown as any other: its time is a better guess than TfL's, not a worse one, so it isn't grayed
 * (maintainer, 2026-10-14).
 *
 * The gaps are what a day of recordings at 15 hubs supported (`dev-docs/arrivals-measurements.md`):
 * 100 s for the Victoria, Northern and Central lines, where trains bunch most; 90 s for the
 * Piccadilly, Bakerloo, Circle and Hammersmith & City, where spacing never made a time worse. The
 * Jubilee's times already run late and spacing made them later, and the other lines weren't measured,
 * so their times stay as TfL gives them.
 *
 * Each line is spaced on its own, as [DoubtfulTrains] judges it: two lines sharing a platform's track
 * (the Circle and District at Victoria) aren't held back by each other's trains, which isn't measured
 * yet. The queue is TfL's own order: spacing only moves a time later, never past the train behind it.
 * Run it on TfL's times, after [DoubtfulTrains], which judges how close TfL timed two trains.
 */
object SpacedTrains {
    private val GAPS: Map<String, Duration> = mapOf(
        "victoria" to Duration.ofSeconds(100),
        "northern" to Duration.ofSeconds(100),
        "central" to Duration.ofSeconds(100),
        "piccadilly" to Duration.ofSeconds(90),
        "bakerloo" to Duration.ofSeconds(90),
        "circle" to Duration.ofSeconds(90),
        "hammersmith-city" to Duration.ofSeconds(90),
    )

    /** The least gap [lineId]'s trains are shown apart at one platform; null to leave them as TfL gives them. */
    fun gapFor(lineId: String): Duration? = GAPS[lineId]

    /** What [space] returned, and each train it moved with how far, as moved (for the debug log). */
    data class Result(val departures: List<Departure>, val moved: List<Pair<Departure, Duration>>)

    /** [departures] as of [now], spaced, in their given order. Walks the whole board, so off the main thread. */
    @WorkerThread
    fun space(departures: List<Departure>, now: Instant): Result {
        // By identity: two equal entries are still two listings, each with its own place in the queue.
        val shifts = IdentityHashMap<Departure, Instant>()
        // TfL leaves a train's mode blank at times: it takes its line's, from the line's other trains.
        val lineMode = departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        departures.filter {
            gapFor(it.lineId) != null &&
                it.mode.ifBlank { lineMode[it.lineId].orEmpty() }.equals("tube", ignoreCase = true) &&
                PlatformDirection.knownPlatformNumber(it.platform) != null &&
                // A train TfL still lists after it was due has gone (it's dropped as the board is drawn),
                // so it holds nothing back.
                !Countdown.hasDeparted(it, now)
        }
            // By the platform's number, not TfL's label: it names one platform both with and without its
            // direction ("Platform 2", "Southbound - Platform 2"). "Platform Unknown" is no platform.
            .groupBy { it.lineId to PlatformDirection.knownPlatformNumber(it.platform) }
            .forEach { (key, atPlatform) ->
                val gap = gapFor(key.first) ?: return@forEach
                var previous: Instant? = null
                for (train in atPlatform.sortedBy { it.expectedArrival }) {
                    val earliest = previous?.plus(gap)
                    val at = if (earliest != null && earliest.isAfter(train.expectedArrival)) earliest else train.expectedArrival
                    if (at != train.expectedArrival) shifts[train] = at
                    previous = at
                }
            }
        if (shifts.isEmpty()) return Result(departures, emptyList())
        val moved = ArrayList<Pair<Departure, Duration>>()
        val all = departures.map { d ->
            val at = shifts[d] ?: return@map d
            d.copy(expectedArrival = at).also { moved += it to Duration.between(d.expectedArrival, at) }
        }
        return Result(all, moved)
    }
}
