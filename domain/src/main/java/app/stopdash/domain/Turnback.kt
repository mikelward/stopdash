package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Trains TfL lists at a rail terminus as arriving there, relabeled as the departures they become
 * (maintainer, 2026-10-09; SPEC *Departures*). At Walthamstow Central TfL's live list holds only
 * Victoria line trains bound for Walthamstow Central, filed under its "Southbound" platforms and
 * each listed once per platform, since TfL doesn't know which it will use; those are the next
 * trains to Brixton. So a train bound for the terminus it's listed at, on a line the station
 * ends ([farEnds], from the bundled station index), becomes one train:
 * - **bound for the line's far end** ("Brixton"), or TfL's own [RouteStops.isUnknownDestination]
 *   "Check Front of Train" where the station is the end of routes to more than one (Morden);
 * - **grouped by its compass**, not its platform ("Southbound"), as the platform is unknown, and
 *   with no platform at all where TfL's names no compass;
 * - **listed once** (by TfL's vehicle id), at its soonest time. Where TfL gives no vehicle id, its
 *   copies are told apart only when every platform lists the same number of them: then each
 *   platform's nth is one train, kept at its soonest. Otherwise all are kept, rather than guess.
 * - **the other direction** from TfL's, which names the way it arrived (none where TfL gives none).
 * Its time stays the one TfL gives, when it reaches the platform. A train ending at a station
 * routes run through (turned short) isn't relabeled: it goes nowhere ([Terminating]). Pure, so it
 * is JVM-tested.
 */
object Turnback {
    /** A far end of a line from a terminus: its TfL id and cleaned name. */
    data class End(val id: String, val name: String)

    /**
     * [departures] at [stopId] with those turning back there relabeled, per line's [farEnds]. Walks the
     * whole board, so it runs where the arrivals are decoded, never on the main thread.
     */
    @WorkerThread
    fun relabel(departures: List<Departure>, stopId: String, farEnds: Map<String, List<End>>): List<Departure> {
        if (farEnds.isEmpty()) return departures
        fun turnsBack(d: Departure) = d.destinationId == stopId && farEnds[d.lineId].orEmpty().isNotEmpty()
        // Once per train, at its soonest: the same one is listed on each platform it might use.
        val soonest = departures.filter { turnsBack(it) && it.vehicleId.isNotBlank() }
            .groupBy { it.lineId to it.vehicleId }.mapValues { (_, listed) -> listed.minBy { it.expectedArrival } }
        val anonymous = anonymousKept(departures.filter { turnsBack(it) && it.vehicleId.isBlank() })
        return departures.mapNotNull { d ->
            if (!turnsBack(d)) return@mapNotNull d
            if (d.vehicleId.isNotBlank() && soonest[d.lineId to d.vehicleId] !== d) return@mapNotNull null
            if (d.vehicleId.isBlank() && anonymous.none { it === d }) return@mapNotNull null
            val ends = farEnds.getValue(d.lineId)
            val end = ends.singleOrNull()
            d.copy(
                destination = end?.name ?: RouteStops.CHECK_FRONT_OF_TRAIN,
                destinationId = end?.id.orEmpty(),
                // The way it leaves, without the platform it may not use: none at all where the
                // platform names no compass ("Platform 1"), rather than one TfL can't vouch for.
                platform = PlatformDirection.of(d.platform),
                // TfL's direction, where given, is the way it arrived; it leaves the other way, so the
                // row is keyed, its route found and its line status read for that (Codex, #729).
                // Anything else is cleared rather than guessed.
                direction = when (d.direction) {
                    "inbound" -> "outbound"
                    "outbound" -> "inbound"
                    else -> ""
                },
                branch = null,
            )
        }
    }

    // Of [listed], trains TfL names no vehicle for, the copies to keep (Codex, #729). The same train on
    // every platform shows as the same count on each: then each platform's nth, by time, is one train,
    // kept at its soonest. Any other shape may be different trains on different platforms: all kept.
    private fun anonymousKept(listed: List<Departure>): List<Departure> =
        listed.groupBy { it.lineId }.values.flatMap { line ->
            val byPlatform = line.groupBy { it.platform.orEmpty() }.values.map { copies -> copies.sortedBy { it.expectedArrival } }
            val count = byPlatform.first().size
            // A copy with no platform named isn't on one of them: nothing to match it by (Codex, #729).
            if (line.any { it.platform.isNullOrBlank() } || byPlatform.size < 2 || byPlatform.any { it.size != count }) line
            else (0 until count).map { n -> byPlatform.minBy { it[n].expectedArrival }[n] }
        }
}
