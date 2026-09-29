package app.stopdash.data

import app.stopdash.domain.LineStatus
import java.time.Duration
import java.time.Instant

/**
 * Which directions of travel each line alert affects, looked up once per alert and remembered.
 *
 * TfL names an alert's direction (its affected routes' `inbound`/`outbound`) only in the
 * `?detail=true` line-status response, which carries every affected route's full stop list —
 * about 40× the plain response, and seconds slower. So the regular refresh stays plain, and an
 * alert not seen before has its direction fetched once, in the background ([KtorTflClient]),
 * keyed by its line and TfL's own text: an alert's direction doesn't change while its wording
 * holds, and reworded text is a new alert. Until the answer arrives — or if the lookup fails —
 * [directionsOf] says nothing and the alert counts for both directions, as it always did. A line
 * whose lookup failed waits before it's tried again ([fail]), longer after each failure, so TfL
 * failing it over and over costs one ~150 KB lookup every so often rather than one every refresh.
 *
 * In memory, process-wide ([shared]), bounded to [capacity] alerts (least recently used out):
 * a new process asks again, once per alert.
 */
class LineAlertDirections(private val capacity: Int = DEFAULT_CAPACITY) {
    // Access-ordered, so the eviction below drops the alert least recently read.
    private val known = LinkedHashMap<String, Set<String>>(16, 0.75f, true)
    private val pendingLines = HashSet<String>()

    // A line whose last lookup failed: how many times running, when the last one did, and the
    // alerts it was for. A new alert on the line (reworded text is one) is looked up at once, its
    // first failure waiting only a minute: it didn't fail before.
    private class Failures(val count: Int, val at: Instant, val alerts: Set<String>)
    private val failed = HashMap<String, Failures>()

    /**
     * The directions [reason] on [lineId] affects, or null when not known — not looked up yet, or
     * TfL scoped it to no direction — which the caller reads as "both".
     */
    @Synchronized
    fun directionsOf(lineId: String, reason: String): Set<String>? =
        known[key(lineId, reason)]?.takeIf { it.isNotEmpty() }

    /**
     * The lines in [lines] with an alert whose direction isn't known and isn't already being looked
     * up, nor waiting after a failed lookup ([fail]), now marked as being looked up. The caller
     * fetches them and reports back with [record] (or [fail], or [release] if it gave up), so a
     * refresh that lands mid-lookup doesn't fetch the same line again.
     */
    @Synchronized
    fun claimUnknown(lines: List<TflLineDto>, now: Instant): List<TflLineDto> =
        lines.filter { line ->
            line.id.isNotBlank() && line.id !in pendingLines && anyUnknown(line) && !waiting(line, now)
        }.distinctBy { it.id }.also { claimed -> claimed.forEach { pendingLines.add(it.id) } }

    // Whether [line]'s last lookup failed too recently to try again at [now]: [RETRY_AFTER] after
    // the first failure, doubling with each one after, up to [RETRY_AFTER_MAX]. Never while it has an
    // alert that lookup wasn't for. A failure dated after now (the clock set back) is an age that
    // can't be told, so it's tried again.
    private fun waiting(line: TflLineDto, now: Instant): Boolean {
        val last = failed[line.id] ?: return false
        if (!last.alerts.containsAll(unknownAlerts(line))) return false
        val wait = RETRY_AFTER.multipliedBy(1L shl (last.count - 1).coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(RETRY_AFTER_MAX)
        val since = Duration.between(last.at, now)
        return !since.isNegative && since < wait
    }

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
        val ids = claimed.map { it.id }.toSet()
        pendingLines.removeAll(ids)
        failed.keys.removeAll(ids)
    }

    /**
     * Frees [claimed] after a lookup that failed at [now], to be tried again once it has waited
     * ([claimUnknown]): a minute after a first failure, twice as long after each one after.
     */
    @Synchronized
    fun fail(claimed: List<TflLineDto>, now: Instant) {
        claimed.forEach { line ->
            val alerts = unknownAlerts(line)
            // Counted on only while every alert it was for failed before: a new one starts over.
            val before = failed[line.id]?.takeIf { it.alerts.containsAll(alerts) }
            failed[line.id] = Failures((before?.count ?: 0) + 1, now, alerts)
        }
        pendingLines.removeAll(claimed.map { it.id }.toSet())
    }

    /** Frees [claimed] after a lookup given up on (canceled, not failed), so the next refresh tries again. */
    @Synchronized
    fun release(claimed: List<TflLineDto>) {
        pendingLines.removeAll(claimed.map { it.id }.toSet())
    }

    // [line]'s alerts whose direction isn't known yet, by key.
    private fun unknownAlerts(line: TflLineDto): Set<String> =
        line.alerts().map { key(line.id, it.reason) }.filterTo(HashSet()) { it !in known }

    private fun TflLineDto.alerts(): List<TflLineStatusEntryDto> =
        lineStatuses.filter { it.statusSeverity != LineStatus.GOOD_SERVICE && it.reason.isNotBlank() }

    private fun key(lineId: String, reason: String) = "$lineId\u001F${reason.trim()}"

    companion object {
        /** TfL's two directions of travel, as its arrivals and affected routes spell them. */
        val DIRECTIONS = listOf("inbound", "outbound")

        private const val DEFAULT_CAPACITY = 256

        /** How long a line waits after its lookup first fails, doubling with each failure after. */
        val RETRY_AFTER: Duration = Duration.ofMinutes(1)

        /** The longest a line waits between failed lookups. */
        val RETRY_AFTER_MAX: Duration = Duration.ofMinutes(30)

        // Enough doublings to pass [RETRY_AFTER_MAX], and few enough not to overflow the shift.
        private const val MAX_DOUBLINGS = 10

        /** The app's one cache, shared by every client in the process. */
        val shared = LineAlertDirections()
    }
}
