package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * Trains a list may count twice, for the debug log: one TfL train ([Departure.vehicleId]) listed more
 * than once on a line, at any platform, or two trains of a line due at one numbered platform within [CLOSE] of each other, which a
 * station's own display rarely shows. A trip's board has shown a branch's times on both branches'
 * rows when the platform had fewer trains; logging what TfL sent, and what the trip screen then held, says
 * which of the two doubled them. Coarse diagnostics only: line, platform, TfL's train id, its
 * destination and how far off it is (AGENTS.md *Privacy*).
 */
object DoubledTrains {
    /** How close two of a line's trains at one platform are before they're logged. */
    val CLOSE: Duration = Duration.ofSeconds(60)

    /**
     * What [find] found: the log line, and the suspects without their times ([key]), so a board read
     * again half a minute later with the same trains isn't logged again.
     */
    data class Found(val text: String, val key: Set<String>)

    /** [find]'s line alone, or null when there's nothing to say. */
    @WorkerThread
    fun describe(trains: List<Departure>, now: Instant): String? = find(trains, now)?.text

    /**
     * A log line naming [trains]' suspects as of [now], or null when there are none. Each named once,
     * soonest first: `"northern P2: 053 Battersea Power via Charing X 50 s, 053 Morden via Bank 55 s (same train, close)"`.
     */
    @WorkerThread
    fun find(trains: List<Departure>, now: Instant): Found? {
        val named = LinkedHashSet<String>()
        val sorted = trains.sortedBy { it.expectedArrival }
        val groups = sorted.groupBy { it.lineId to PlatformDirection.platformNumber(it.platform).orEmpty() }
        // How many times each line lists each train it names an id for (TfL's ids are each line's own).
        val listed = sorted.filter { it.vehicleId.isNotBlank() }.groupBy { it.lineId }
            .mapValues { (_, trains) -> trains.groupingBy { it.vehicleId }.eachCount() }
        val parts = groups.mapNotNull { (key, group) ->
            // By position, not equality: a train held twice is two equal entries, and both are named. Counted
            // across the line's platforms, so one listed again under another platform, or none, is named too.
            val sameTrain = group.indices.filter { (listed[key.first]?.get(group[it].vehicleId) ?: 0) > 1 }
            // Buses bunch, and a stop names no platform: only trains at a numbered platform count as close.
            val close = if (key.second.isBlank()) emptyList() else (0 until group.size - 1)
                .filter { Duration.between(group[it].expectedArrival, group[it + 1].expectedArrival) < CLOSE }
                .flatMap { listOf(it, it + 1) }
            val suspects = (sameTrain + close).distinct().sorted().map { group[it] }
            if (suspects.isEmpty()) return@mapNotNull null
            val why = listOfNotNull("same train".takeIf { sameTrain.isNotEmpty() }, "close".takeIf { close.isNotEmpty() })
            // A train with no id is told apart by when it's due, and a repeat by its count, so a new pair of
            // look-alikes is a new key, not one already logged (Codex on #719).
            suspects.forEach { train ->
                val id = train.vehicleId.ifBlank { "@${train.expectedArrival.epochSecond}" }
                val base = "${train.lineId}/${train.platform}/$id/${train.destination}/${train.branch}"
                named += generateSequence(1) { it + 1 }.map { "$base#$it" }.first { it !in named }
            }
            val at = key.second.takeIf { it.isNotBlank() }?.let { " P$it" }.orEmpty()
            "${key.first}$at: " + suspects.joinToString { train ->
                val id = train.vehicleId.ifBlank { "no id" }
                "$id ${train.destination}${train.branch?.let { " via $it" }.orEmpty()} ${Duration.between(now, train.expectedArrival).seconds} s"
            } + " (" + why.joinToString(", ") + ")"
        }
        return if (parts.isEmpty()) null else Found(parts.joinToString("; "), named)
    }
}
