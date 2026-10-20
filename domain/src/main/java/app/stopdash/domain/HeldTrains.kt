package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * Tube trains held where they stand ([Departure.held]): a train whose location TfL hasn't moved for
 * [STILL] while its expected time slid later at least [SLIDE] as fast as TfL's clock. TfL keeps
 * timing it as if it were moving, so its time is one stopdash doesn't believe, and it's grayed, its
 * time kept (maintainer, 2026-10-20; SPEC *Departures*). Measured over two hours of every line at seven
 * stations: times this marks came a median 6½ minutes after the time shown, against about a minute for
 * the rest, and too spread (a quartile at 4 minutes, another past 9) to correct. Stopped trains whose
 * time still counts down aren't marked: their time can still be right.
 *
 * Only the tube, where it was measured, and only a train due a minute or more ahead: one standing at
 * the stop itself is there. Times are TfL's own (when it made each prediction), so an older cache
 * copy set aside by [NewestArrivals] moves nothing, and it needs no clock. Memory only, bounded to
 * [MAX] trains, the least recently seen dropped first; one is kept for the process ([SHARED]), as
 * screens and the widget each build their own client. A train not answered for [FORGET] starts over.
 */
class HeldTrains {
    // A train where it was first seen at [location], and its expected time then.
    private class Seen(val location: String, val since: Instant, val expectedThen: Instant, val last: Instant, val held: Boolean)

    private val seen = LinkedHashMap<String, Seen>()

    // When each stop's newest answer was made: only an answer at least that new says which trains are gone.
    private val newestAt = HashMap<String, Instant>()

    /** [departures] at [stopId], from TfL's answer made at [madeAt], each held train marked. */
    @WorkerThread
    @Synchronized
    fun mark(stopId: String, madeAt: Instant?, departures: List<Departure>, locations: List<String>): List<Departure> {
        if (madeAt == null) {
            // Undated, it's taken as it comes ([NewestArrivals]) but says nothing of when its trains stood
            // where: the stop's trains start over.
            seen.keys.removeAll { it.startsWith("$stopId|") }
            return departures
        }
        // TfL leaves a train's mode blank at times: it takes its line's, from the line's other trains, as
        // [DoubtfulTrains] and [SpacedTrains] do.
        val lineMode = departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        // An older cache copy than the stop's last answer, as [NewestArrivals] sets aside, changes nothing:
        // its trains read as the newest answer left them. One older than any copy is TfL's clock moved.
        val fresh = newestAt[stopId]?.let { newest ->
            !madeAt.isBefore(newest) || Duration.between(madeAt, newest) > NewestArrivals.MAX_STEP_BACK
        } ?: true
        val listed = HashSet<String>()
        val marked = departures.mapIndexed { i, d ->
            val tube = d.mode.ifBlank { lineMode[d.lineId].orEmpty() }.equals("tube", ignoreCase = true)
            // By the platform's number, not TfL's label, as its siblings do: it names one platform both with
            // and without its direction. Per platform, so the same train back through the stop the other way,
            // minutes later, is a new visit though TfL may name its location alike ("At Platform"). A platform
            // TfL names by direction alone ("Northbound", "Southbound - Platform Unknown") goes by that.
            val platform = PlatformDirection.knownPlatformNumber(d.platform)
                ?: PlatformDirection.of(d.platform)?.let { "($it)" }
            if (!tube || d.vehicleId.isBlank() || platform == null) return@mapIndexed d
            val key = "$stopId|${d.lineId}|${d.vehicleId}|$platform"
            if (!fresh) return@mapIndexed if (seen[key]?.held == true) d.copy(held = true) else d
            listed += key
            val location = locations.getOrNull(i).orEmpty().trim()
            if (location.isEmpty()) {
                // Nowhere TfL says: no evidence it stood still, so it starts over.
                seen.remove(key)
                return@mapIndexed d
            }
            val last = seen[key]
            val now = when {
                // Starting over too where TfL's clock moved back past it.
                last == null || last.location != location || madeAt.isBefore(last.last) ||
                    Duration.between(last.last, madeAt) > FORGET ->
                    Seen(location, madeAt, d.expectedArrival, madeAt, held = false)
                else -> {
                    val standing = Duration.between(last.since, madeAt)
                    val slid = Duration.between(last.expectedThen, d.expectedArrival)
                    val ahead = Duration.between(madeAt, d.expectedArrival)
                    val held = standing >= STILL && slid.toMillis() >= SLIDE * standing.toMillis() && ahead >= AHEAD
                    Seen(location, last.since, last.expectedThen, madeAt, held)
                }
            }
            seen.remove(key)
            seen[key] = now
            if (seen.size > MAX) seen.remove(seen.keys.first())
            if (now.held) d.copy(held = true) else d
        }
        if (fresh) {
            // A train the stop's newest answer leaves out may have moved meanwhile: no evidence it stood
            // still, so it starts over if it's listed again.
            seen.keys.removeAll { it.startsWith("$stopId|") && it !in listed }
            newestAt[stopId] = madeAt
            if (newestAt.size > MAX) newestAt.clear()
        }
        return marked
    }

    companion object {
        val STILL: Duration = Duration.ofMinutes(2)
        const val SLIDE = 0.5
        val AHEAD: Duration = Duration.ofMinutes(1)
        val FORGET: Duration = Duration.ofMinutes(10)
        const val MAX = 2048

        /** The process's one, passed to every client that fetches arrivals. */
        val SHARED = HeldTrains()
    }
}
