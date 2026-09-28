package app.stopdash.data

import app.stopdash.domain.LineStatus

/**
 * Which directions of travel each line alert affects, looked up once per alert and remembered.
 *
 * TfL names an alert's direction (its affected routes' `inbound`/`outbound`) only in the
 * `?detail=true` line-status response, which carries every affected route's full stop list —
 * about 40× the plain response, and seconds slower. So the regular refresh stays plain, and an
 * alert not seen before has its direction fetched once, in the background ([KtorTflClient]),
 * keyed by its line and TfL's own text: an alert's direction doesn't change while its wording
 * holds, and reworded text is a new alert. Until the answer arrives — or if the lookup fails —
 * [directionsOf] says nothing and the alert counts for both directions, as it always did.
 *
 * In memory, process-wide ([shared]), bounded to [capacity] alerts (least recently used out):
 * a new process asks again, once per alert.
 */
class LineAlertDirections(private val capacity: Int = DEFAULT_CAPACITY) {
    // Access-ordered, so the eviction below drops the alert least recently read.
    private val known = LinkedHashMap<String, Set<String>>(16, 0.75f, true)
    private val pendingLines = HashSet<String>()

    /**
     * The directions [reason] on [lineId] affects, or null when not known — not looked up yet, or
     * TfL scoped it to no direction — which the caller reads as "both".
     */
    @Synchronized
    fun directionsOf(lineId: String, reason: String): Set<String>? =
        known[key(lineId, reason)]?.takeIf { it.isNotEmpty() }

    /**
     * The lines in [lines] with an alert whose direction isn't known and isn't already being looked
     * up, now marked as being looked up. The caller fetches them and reports back with [record] (or
     * [release] on failure), so a refresh that lands mid-lookup doesn't fetch the same line again.
     */
    @Synchronized
    fun claimUnknown(lines: List<TflLineDto>): List<TflLineDto> =
        lines.filter { line ->
            line.id.isNotBlank() && line.id !in pendingLines && anyUnknown(line)
        }.distinctBy { it.id }.also { claimed -> claimed.forEach { pendingLines.add(it.id) } }

    /** Whether [line] has an alert whose direction hasn't been recorded yet. */
    @Synchronized
    fun anyUnknown(line: TflLineDto): Boolean = line.alerts().any { key(line.id, it.reason) !in known }

    /**
     * Records the directions the [detailed] response gives for every alert in the [claimed] lines.
     * An alert the detail doesn't carry (withdrawn or reworded between the two requests) is
     * remembered as scoped to no direction, like one TfL gave no direction for: it counts for both,
     * and isn't looked up again on every refresh at ~150 KB a time.
     */
    @Synchronized
    fun record(claimed: List<TflLineDto>, detailed: List<TflLineDto>) {
        // Two entries can share the same text but scope different routes: the text then covers
        // both entries' directions, so none a disruption was scoped to is lost (Codex, PR #334).
        val found = HashMap<String, Set<String>>()
        detailed.forEach { line ->
            line.alerts().forEach { found.merge(key(line.id, it.reason), it.affectedDirections(), Set<String>::plus) }
        }
        claimed.forEach { line ->
            line.alerts().forEach { alert ->
                val key = key(line.id, alert.reason)
                known[key] = found[key].orEmpty()
            }
        }
        while (known.size > capacity) known.remove(known.keys.first())
        pendingLines.removeAll(claimed.map { it.id }.toSet())
    }

    /** Frees [claimed] after a failed lookup, so the next refresh tries again. */
    @Synchronized
    fun release(claimed: List<TflLineDto>) {
        pendingLines.removeAll(claimed.map { it.id }.toSet())
    }

    private fun TflLineDto.alerts(): List<TflLineStatusEntryDto> =
        lineStatuses.filter { it.statusSeverity != LineStatus.GOOD_SERVICE && it.reason.isNotBlank() }

    private fun key(lineId: String, reason: String) = "$lineId\u001F${reason.trim()}"

    companion object {
        /** TfL's two directions of travel, as its arrivals and affected routes spell them. */
        val DIRECTIONS = listOf("inbound", "outbound")

        private const val DEFAULT_CAPACITY = 256

        /** The app's one cache, shared by every client in the process. */
        val shared = LineAlertDirections()
    }
}
