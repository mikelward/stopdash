package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * One Tube train TfL lists twice at one platform, under the same id with two times ("0 · 1" and "0 · 1" for
 * two trains at Heathrow Terminals 2 & 3, a rider's report, 2026-11-24): a train comes once, so it's shown
 * once (SPEC *Departures*). Recorded across the whole Tube for two hours (`dev-docs/arrivals-measurements.md`),
 * 4,553 polls listed a train twice at one platform, most on the Piccadilly line, typically 3½ minutes apart;
 * where the train was then seen to arrive, its earlier listing was the nearer 73% of the time, so that's the
 * one kept. One TfL places at the platform is kept over any: it's there.
 *
 * Only a Tube train with an id at a numbered platform: an id is unique within a line, so the same id on two
 * lines is [DoubtfulTrains]' to judge, and at a terminus TfL lists an incoming train on each platform it may
 * use, which aren't the same platform.
 */
object TwiceListed {
    /** What [drop] returned, and each listing it dropped (for the debug log). */
    data class Result(val departures: List<Departure>, val dropped: List<Departure>)

    /** [departures] with each Tube train listed more than once at one platform kept once, in their given order. */
    @WorkerThread
    fun drop(departures: List<Departure>): Result {
        val lineMode = departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        val kept = HashMap<Triple<String, String, String>, Departure>()
        for (d in departures) {
            val key = keyOf(d, lineMode) ?: continue
            val held = kept[key]
            kept[key] = when {
                held == null -> d
                // At the platform beats coming; otherwise the sooner.
                held.atPlatform != d.atPlatform -> if (d.atPlatform) d else held
                d.expectedArrival.isBefore(held.expectedArrival) -> d
                else -> held
            }
        }
        val dropped = ArrayList<Departure>()
        val out = departures.filter { d ->
            val key = keyOf(d, lineMode) ?: return@filter true
            (kept[key] === d).also { if (!it) dropped += d }
        }
        return Result(if (dropped.isEmpty()) departures else out, dropped)
    }

    private fun keyOf(d: Departure, lineMode: Map<String, String>): Triple<String, String, String>? {
        if (!d.mode.ifBlank { lineMode[d.lineId].orEmpty() }.equals("tube", ignoreCase = true)) return null
        if (d.vehicleId.isBlank()) return null
        val platform = PlatformDirection.knownPlatformNumber(d.platform) ?: return null
        return Triple(d.lineId, d.vehicleId, platform)
    }
}
