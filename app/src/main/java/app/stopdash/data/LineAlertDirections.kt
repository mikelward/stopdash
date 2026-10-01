package app.stopdash.data

import app.stopdash.domain.LineStatus
import java.time.Duration
import java.time.Instant

/**
 * Which directions of travel each line alert affects, and which stops, looked up once per alert and
 * remembered.
 *
 * TfL names an alert's direction (its affected routes' `inbound`/`outbound`) only in the
 * `?detail=true` line-status response, which carries every affected route's full stop list —
 * about 40× the plain response, and seconds slower. So the regular refresh stays plain, and an
 * alert not seen before has its direction fetched once, in the background ([KtorTflClient]),
 * keyed by its line and TfL's own text: an alert's direction doesn't change while its wording
 * holds, and reworded text is a new alert. So is the same text once it has gone from its line's
 * status and come back ([claimUnknown]): TfL reuses wording for a later occurrence, which may shut
 * another stretch. Until the answer arrives — or if the lookup fails —
 * [directionsOf] says nothing and the alert counts for both directions, as it always did. A line
 * whose lookup failed waits before it's tried again ([fail]), longer after each failure, so TfL
 * failing it over and over costs one ~150 KB lookup every so often rather than one every refresh.
 *
 * The same answer names the stops each alert affects ([sectionsOf]): for a part closure, the sections
 * it shuts, so a trip can tell whether a ride runs through one ([LineStatus.coversRide]).
 *
 * In memory, process-wide ([shared]), bounded to [capacity] alerts (least recently used out):
 * a new process asks again, once per alert.
 */
class LineAlertDirections(private val capacity: Int = DEFAULT_CAPACITY) {
    // An alert's directions and the sections it shuts, as the detail gave them (either empty where it
    // gave none), each kept per [Kind]: entries sharing the alert's text pool what they give only
    // with entries no response can tell apart from them, so a closure one way lends neither its
    // stops nor its direction to minor delays the other way with the same text (Codex, PR #446).
    // [kinds] are those looked up for it, given stops or not: one not among them (the same text at a
    // new severity, or the plain and detailed responses racing across such a change) is looked up
    // again rather than left unplaced for good (Codex, PR #446).
    private class Scope(val directions: Map<Kind, Set<String>>, val sections: Map<Kind, List<AffectedSection>>, val kinds: Set<Kind>)

    // What tells two entries sharing an alert's text apart in both of TfL's responses: TfL's severity
    // and its kind of alert (`PlannedWork`, `RealTime`). Without it, a closure planned for later, or
    // one ranked differently, would lend its stops to the one under way with the same text and place
    // a ride on a stretch that's still open (Codex, PR #446).
    private data class Kind(val severity: Int, val category: String)

    private fun TflLineStatusEntryDto.kind() =
        Kind(statusSeverity, disruption?.category.orEmpty().trim().lowercase())

    // Access-ordered, so the eviction below drops the alert least recently read.
    private val known = LinkedHashMap<String, Scope>(16, 0.75f, true)
    private val pendingLines = HashSet<String>()

    // What each lookup under way is for: every alert its line showed when claimed ([alertKey]), all of
    // which [record] writes. A line among [ended] saw one of them end mid-lookup ([forgetGone]): its
    // answer is the last occurrence's, so it's dropped when it lands rather than written back.
    private val lookingUp = HashMap<String, Set<String>>()
    private val ended = HashSet<String>()

    // A line whose last lookup failed: how many times running, when the last one did, and the
    // alerts it was for. A new alert on the line (reworded text is one) is looked up at once, its
    // first failure waiting only a minute: it didn't fail before.
    private class Failures(val count: Int, val at: Instant, val alerts: Set<String>)
    private val failed = HashMap<String, Failures>()

    /**
     * The directions [entry] on [lineId] affects: those of the detail's entries with its text, severity
     * and kind ([Kind]). Null when not known — not looked up yet, or TfL scoped it to no direction —
     * which the caller reads as "both".
     */
    @Synchronized
    fun directionsOf(lineId: String, entry: TflLineStatusEntryDto): Set<String>? =
        known[key(lineId, entry.reason)]?.directions?.get(entry.kind())?.takeIf { it.isNotEmpty() }

    /**
     * The sections [entry] on [lineId] shuts ([affectedSections]), each with its route's direction:
     * those of the detail's entries with its text, severity and kind ([Kind]). Empty when not known:
     * not looked up yet, or TfL named no stops.
     */
    @Synchronized
    fun sectionsOf(lineId: String, entry: TflLineStatusEntryDto): List<AffectedSection> =
        known[key(lineId, entry.reason)]?.sections?.get(entry.kind()).orEmpty()

    /**
     * The lines in [lines] with an alert whose direction isn't known and isn't already being looked
     * up, nor waiting after a failed lookup ([fail]), now marked as being looked up. The caller
     * fetches them and reports back with [record] (or [fail], or [release] if it gave up), so a
     * refresh that lands mid-lookup doesn't fetch the same line again. [lines] are a refresh's whole
     * answer for each line: what was recorded for an alert one of them no longer shows is forgotten
     * first ([forgetGone]), so the same text back later is looked up afresh.
     */
    @Synchronized
    fun claimUnknown(lines: List<TflLineDto>, now: Instant): List<TflLineDto> {
        forgetGone(lines)
        return lines.filter { line ->
            line.id.isNotBlank() && line.id !in pendingLines && anyUnknown(line) && !waiting(line, now)
        }.distinctBy { it.id }.also { claimed ->
            claimed.forEach {
                pendingLines.add(it.id)
                lookingUp[it.id] = it.shownKeys()
            }
        }
    }

    // Where an alert's occurrence ends: what [lines] no longer show, by text or by kind ([Kind]), is
    // over, and TfL reuses an alert's text for a later one, which may shut another stretch. So all
    // that's tied to it goes here, and the next is looked up as new (Codex, PR #446): what was
    // recorded for it, so the next isn't placed on its stops; a failed lookup's wait, so the next
    // isn't held back by its failures; and a lookup still under way for it, whose answer is dropped
    // when it lands. Only [lines]' own: a line this refresh didn't ask about says nothing of its alerts.
    private fun forgetGone(lines: List<TflLineDto>) {
        val ids = lines.mapNotNullTo(HashSet()) { line -> line.id.takeIf { it.isNotBlank() } }
        val showing = HashMap<String, MutableSet<Kind>>()
        lines.filter { it.id in ids }.forEach { line ->
            line.alerts().forEach { showing.getOrPut(key(line.id, it.reason)) { HashSet() } += it.kind() }
        }
        val shown = showing.flatMapTo(HashSet()) { (key, kinds) -> kinds.map { alertKey(key, it) } }
        ids.filter { it in pendingLines && !shown.containsAll(lookingUp[it].orEmpty()) }.forEach { ended += it }
        ids.forEach { id ->
            val last = failed[id] ?: return@forEach
            val still = last.alerts intersect shown
            when {
                still.isEmpty() -> failed.remove(id)
                still.size != last.alerts.size -> failed[id] = Failures(last.count, last.at, still)
            }
        }
        val entries = known.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.substringBefore(SEPARATOR) !in ids) continue
            val scope = entry.value
            val kept = scope.kinds intersect showing[entry.key].orEmpty()
            when {
                kept.isEmpty() -> entries.remove()
                kept != scope.kinds -> entry.setValue(
                    Scope(scope.directions.filterKeys { it in kept }, scope.sections.filterKeys { it in kept }, kept),
                )
            }
        }
    }

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

    /** Whether [line] has an alert whose direction, or whose stops for its kind ([Kind]), hasn't been recorded yet. */
    @Synchronized
    fun anyUnknown(line: TflLineDto): Boolean = unknownAlerts(line).isNotEmpty()

    /**
     * Records the directions and sections the [detailed] response gives for every alert in the [claimed]
     * lines. An alert the detail doesn't carry (withdrawn or reworded between the two requests) is
     * remembered as scoped to no direction and no section, like one TfL gave neither for: it counts for
     * both directions, and isn't looked up again on every refresh at ~150 KB a time.
     */
    @Synchronized
    fun record(claimed: List<TflLineDto>, detailed: List<TflLineDto>) {
        // Two entries can share the same text but scope different routes: the text then covers
        // both entries' directions and sections, within each kind, so none a disruption was scoped
        // to is lost (Codex, PR #334). Each section stays its own, in its route's order and with its
        // route's direction: joined into one, two apart would seem to shut the stretch between them,
        // and each direction's status takes only its own (Codex, PR #446).
        val found = HashMap<String, Scope>()
        detailed.forEach { line ->
            line.alerts().forEach { entry ->
                found.merge(key(line.id, entry.reason), Scope(mapOf(entry.kind() to entry.affectedDirections()), mapOf(entry.kind() to entry.affectedSections()), setOf(entry.kind()))) { a, b ->
                    Scope(
                        (a.directions.keys + b.directions.keys).associateWith { kind ->
                            a.directions[kind].orEmpty() + b.directions[kind].orEmpty()
                        },
                        (a.sections.keys + b.sections.keys).associateWith { kind ->
                            (a.sections[kind].orEmpty() + b.sections[kind].orEmpty()).distinct()
                        },
                        a.kinds + b.kinds,
                    )
                }
            }
        }
        // A lookup whose alerts ended while it ran answers for the last occurrence: not written.
        claimed.filterNot { it.id in ended }.forEach { line ->
            line.alerts().groupBy { key(line.id, it.reason) }.forEach { (key, alerts) ->
                // The kinds asked about count as looked up too, found or not: a kind the detail didn't
                // carry has no stops, and isn't asked about again on every refresh at ~150 KB a time.
                // One looked up before that this answer doesn't carry (the two responses racing) keeps
                // what it had: the answer says nothing new about it (Codex, PR #446).
                val scope = found[key] ?: NONE
                val prior = known[key] ?: NONE
                val kept = prior.kinds - scope.kinds
                known[key] = Scope(
                    scope.directions + prior.directions.filterKeys { it in kept },
                    scope.sections + prior.sections.filterKeys { it in kept },
                    scope.kinds + alerts.map { it.kind() } + kept,
                )
            }
        }
        while (known.size > capacity) known.remove(known.keys.first())
        val ids = claimed.map { it.id }.toSet()
        settle(ids)
        failed.keys.removeAll(ids)
    }

    /**
     * Frees [claimed] after a lookup that failed at [now], to be tried again once it has waited
     * ([claimUnknown]): a minute after a first failure, twice as long after each one after.
     */
    @Synchronized
    fun fail(claimed: List<TflLineDto>, now: Instant) {
        // One whose alerts ended while it ran failed for the last occurrence: no wait for the next.
        claimed.filterNot { it.id in ended }.forEach { line ->
            val alerts = unknownAlerts(line)
            // Counted on only while every alert it was for failed before: a new one starts over.
            val before = failed[line.id]?.takeIf { it.alerts.containsAll(alerts) }
            failed[line.id] = Failures((before?.count ?: 0) + 1, now, alerts)
        }
        settle(claimed.map { it.id }.toSet())
    }

    /** Frees [claimed] after a lookup given up on (canceled, not failed), so the next refresh tries again. */
    @Synchronized
    fun release(claimed: List<TflLineDto>) {
        settle(claimed.map { it.id }.toSet())
    }

    // [ids]' lookups are over, answered or not.
    private fun settle(ids: Set<String>) {
        pendingLines.removeAll(ids)
        lookingUp.keys.removeAll(ids)
        ended.removeAll(ids)
    }

    // [line]'s alerts not looked up yet, by key and kind: none for their text, or none of their kind.
    private fun unknownAlerts(line: TflLineDto): Set<String> =
        line.alerts().filter { known[key(line.id, it.reason)]?.kinds?.contains(it.kind()) != true }
            .mapTo(HashSet()) { alertKey(key(line.id, it.reason), it.kind()) }

    // An alert of one kind, as a failed lookup records it: its line and text ([key]), and its [kind].
    private fun alertKey(key: String, kind: Kind) = "$key$SEPARATOR$kind"

    // Every alert this line shows, each of its kinds ([alertKey]).
    private fun TflLineDto.shownKeys(): Set<String> = alerts().mapTo(HashSet()) { alertKey(key(id, it.reason), it.kind()) }

    private fun TflLineDto.alerts(): List<TflLineStatusEntryDto> =
        lineStatuses.filter { it.statusSeverity != LineStatus.GOOD_SERVICE && it.reason.isNotBlank() }

    private fun key(lineId: String, reason: String) = "$lineId$SEPARATOR${reason.trim()}"

    companion object {
        /** TfL's two directions of travel, as its arrivals and affected routes spell them. */
        val DIRECTIONS = listOf("inbound", "outbound")

        private const val DEFAULT_CAPACITY = 256

        // Between a key's line id and its alert's text: a character neither contains.
        private const val SEPARATOR = "\u001F"

        private val NONE = Scope(emptyMap(), emptyMap(), emptySet())

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
