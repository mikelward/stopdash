package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * Each line's last status answer, for the process: whichever surface asked (the app's list, the
 * widget's refresh, a watch's request, a station's page, a trip and its checks on the way), so a line checked by one is reused by the rest within
 * [LineStatusReuse]'s window rather than asked again, and no two surfaces hold different answers for
 * it (SPEC *Freshness → Shared line status*). In memory only; the widget's stored checks carry its
 * answers past the process.
 */
open class LineStatusCache {
    /** One answer for a line, as of [at] (a [SteadyClock] stamp). */
    sealed interface Held {
        val at: Instant

        /** TfL's status for the line. */
        data class Verdict(override val at: Instant, val status: LineStatus) : Held

        /**
         * TfL was asked about the line and gave no status for it; [unrecognized] when it said it
         * doesn't know the line at all (a National Rail service it has no line for, such as Eurostar),
         * which a surface stops asking about rather than reading as a check that failed.
         */
        data class Omitted(override val at: Instant, val unrecognized: Boolean = false) : Held
    }

    // One answer per line with the ticket of the request it answers. The later request's answer wins
    // whichever lands last, so a slow response can't replace a newer one (Codex on #765); tickets
    // count requests rather than read the clock, so a clock moved back can't misorder them either.
    // Read on any thread.
    private class Entry(val answer: Held, val ticket: Long)

    private val held: MutableMap<String, Entry> = ConcurrentHashMap()
    private val tickets = AtomicLong()

    // Open so a test can note where it's read (AGENTS.md *Main thread*).
    open operator fun get(lineId: String): Held? = held[lineId]?.answer

    /** The order of a request about to be sent, taken before it is: pass it to [set] with its answers. */
    fun ticket(): Long = tickets.incrementAndGet()

    /**
     * Holds [answer] as [lineId]'s latest, unless an answer to a later request ([ticket]) is held
     * already. A line TfL said it doesn't know stays marked so through a later omission, which says
     * nothing new about it; only a verdict clears the mark.
     */
    fun set(lineId: String, answer: Held, ticket: Long) {
        // Under the lock [asking] validates and claims under, so a claim sees an answer and its count
        // together, never one without the other (Codex on #766).
        synchronized(asking) {
            held.compute(lineId) { _, current ->
                when {
                    current != null && current.ticket > ticket -> current
                    answer is Held.Omitted && !answer.unrecognized && (current?.answer as? Held.Omitted)?.unrecognized == true ->
                        Entry(answer.copy(unrecognized = true), ticket)
                    else -> Entry(answer, ticket)
                }
            }
            answeredAt[lineId] = answers.incrementAndGet()
        }
    }

    /** The lines whose latest answer is that TfL doesn't know them ([Held.Omitted.unrecognized]). */
    @WorkerThread
    fun unrecognized(): Set<String> =
        held.entries.mapNotNullTo(HashSet()) { (id, entry) -> id.takeIf { (entry.answer as? Held.Omitted)?.unrecognized == true } }

    /** Holds [answer] as [lineId]'s latest, as the answer to a request sent now. */
    operator fun set(lineId: String, answer: Held) = set(lineId, answer, ticket())

    // How many answers have been held, and the count as of each line's latest: a caller that read the
    // cache before another surface's answer landed must not ask on that stale reading ([asking]).
    private val answers = AtomicLong()
    private val answeredAt: MutableMap<String, Long> = ConcurrentHashMap()

    /** The point a caller reads the cache from, taken before it reads and passed to [asking]. */
    fun mark(): Long = answers.get()

    // Each line a request is out for, by any surface, with what completes when it settles.
    private val asking = HashMap<String, CompletableDeferred<Unit>>()

    /**
     * Waits for any request another surface has out for [lineIds] to settle, so the caller reads its
     * answers here rather than sending the same request alongside it (SPEC *Freshness → Shared line
     * status*). Whether it waited at all: a caller that did reads the clock afresh, since the answers
     * are stamped after it was first read. A request that fails, or whose asker is canceled, leaves
     * nothing here, so its lines are asked again.
     */
    @WorkerThread
    suspend fun awaitAsking(lineIds: Collection<String>): Boolean {
        val pending = synchronized(asking) { lineIds.mapNotNullTo(HashSet()) { asking[it] } }
        pending.forEach { it.await() }
        return pending.isNotEmpty()
    }

    /** What [asking] did: ran the request, or found one already out for a line and waited for it. */
    sealed interface Asked<out T> {
        data class Ran<T>(val value: T) : Asked<T>

        /** Nothing was asked: read the cache again, and ask for what's still missing. */
        data object Waited : Asked<Nothing>
    }

    /**
     * Runs [ask], a request for [lineIds], marked as under way so another surface waits on it
     * ([awaitAsking]) rather than asking too. The claim is all or nothing, made at once: when another
     * request is already out for any of the lines, nothing is claimed or asked; this waits for it and
     * returns [Asked.Waited], so the caller reads its answers and asks only for what's still missing.
     * Two surfaces can therefore never both ask about a line, and since none waits while holding a
     * claim, never wait on each other. [ask] holds its answers here before it returns: the mark comes
     * off only after, so a waiter never wakes to find them missing. A line answered since [since] (the
     * caller's [mark], taken before it read the cache) also returns [Asked.Waited], without waiting:
     * the caller decided to ask on a reading that request has since overtaken, so it reads again.
     */
    @WorkerThread
    suspend fun <T> asking(lineIds: Set<String>, since: Long, ask: suspend () -> T): Asked<T> {
        val done = CompletableDeferred<Unit>()
        val busy = synchronized(asking) {
            if (lineIds.any { (answeredAt[it] ?: 0L) > since }) return Asked.Waited
            val out = lineIds.mapNotNullTo(HashSet()) { asking[it] }
            if (out.isEmpty()) lineIds.forEach { asking[it] = done }
            out
        }
        if (busy.isNotEmpty()) {
            busy.forEach { it.await() }
            return Asked.Waited
        }
        try {
            return Asked.Ran(ask())
        } finally {
            synchronized(asking) { lineIds.forEach { if (asking[it] === done) asking.remove(it) } }
            done.complete(Unit)
        }
    }

    /**
     * What [check] found: each line's answer that stands ([answers]), this check's own or another
     * surface's; a line with none couldn't be checked. [fresh] are the lines TfL answered for this check
     * (a status or none) and [freshStatuses] the statuses it gave, the only ones a check settles
     * dismissals on, as another surface's answer carries none of this check's dismissal marks.
     */
    class Checked(
        val answers: Map<String, Held>,
        val fresh: Set<String>,
        val freshStatuses: Map<String, LineStatus>,
        val failed: Set<String>,
        val unknown: Set<String>,
        val failure: Exception?,
        val requests: Int,
    ) {
        // Each worked out here, when [check] makes this on its worker, so a screen reading them walks
        // nothing on its own thread (AGENTS.md *Main thread*; Codex on #776).

        /** The lines' statuses where TfL gave one. */
        val statuses: Map<String, LineStatus> =
            answers.entries.mapNotNull { (id, held) -> (held as? Held.Verdict)?.let { id to it.status } }.toMap()

        /** When each of [answers] was had. */
        val answeredAt: Map<String, Instant> = answers.mapValues { it.value.at }

        /**
         * When the oldest of [statuses] was had: what a screen's status is as old as. Only with none is it
         * the oldest omission, as an omission (TfL saying nothing, or a line it doesn't know, held for
         * good) vouches for no status shown (Codex on #776). Null with no answers.
         */
        val oldest: Instant? = answers.values.filterIsInstance<Held.Verdict>().minOfOrNull { it.at }
            ?: answers.values.minOfOrNull { it.at }
    }

    /**
     * Checks [lineIds] through this cache, the way every screen asks about a line (SPEC *Freshness →
     * Shared line status*): a request another surface has out for them is waited on; a line answered
     * within [reuse] ([LineStatusReuse]), or since this check began, is taken as it stands; the rest are
     * asked of TfL by [fetch], in as many requests as it accepts ([LineStatusBatch]), and the answers held
     * here for the others. [refresh] (a pull) asks every line afresh, unless another surface's answer
     * lands while this one waits; so does an answer had before [pulledAt] (the rider's last pull,
     * [SteadyClock.stamp]ed when it was made, as an answer is, so setting the clock since moves neither;
     * a screen's later refreshes honor it too). A line TfL said it doesn't know
     * isn't asked again. A line whose request failed is left out, whatever was held for it: a check
     * that couldn't be made never vouches on an older answer. Stamped by [clock] through
     * [SteadyClock], as a fetch is.
     */
    @WorkerThread
    suspend fun check(
        lineIds: Collection<String>,
        clock: () -> Instant,
        reuse: java.time.Duration,
        refresh: Boolean = false,
        pulledAt: Instant? = null,
        fetch: suspend (List<String>) -> List<LineStatus>?,
    ): Checked {
        val lines = lineIds.toSet()
        // Answers landing after this point are this check's to take, whatever the window.
        val began = mark()
        fun since(id: String) = (answeredAt[id] ?: 0L) > began
        // Had before the rider's last pull: asked again, however recent.
        fun prePull(held: Held) = pulledAt != null && held.at.isBefore(pulledAt)
        fun standsInWindow(id: String, held: Held, now: Instant): Boolean = when (held) {
            is Held.Verdict -> since(id) && !held.status.awaitingDirections ||
                LineStatusReuse.stands(held.at, now, reuse, held.status.awaitingDirections)
            is Held.Omitted -> held.unrecognized || since(id) || LineStatusReuse.stands(held.at, now, reuse)
        }
        // A line TfL doesn't know stands for good, a pull's included: asking again only gets the 404 again
        // (Codex on #776).
        fun unrecognized(id: String) = (this[id] as? Held.Omitted)?.unrecognized == true
        fun stands(id: String, now: Instant): Boolean = when (val held = this[id]) {
            null -> false
            else -> unrecognized(id) || !(prePull(held) && !since(id)) && standsInWindow(id, held, now)
        }
        // A request another surface has out is waited on whatever stands, so its newer answer is shown
        // rather than the one it's replacing (Codex on #776); a pull it covered needn't ask again.
        val waited = awaitAsking(lines)
        var force = refresh && !waited
        var results: LineStatusBatch.Results<List<LineStatus>>? = null
        var fresh: Map<String, LineStatus> = emptyMap()
        while (true) {
            val read = mark()
            val now = clock()
            val toAsk = lines.filterTo(LinkedHashSet()) { if (force) !unrecognized(it) else !stands(it, now) }
            if (toAsk.isEmpty()) break
            when (val asked = asking(toAsk, read) { ask(toAsk, clock, fetch) }) {
                is Asked.Ran -> {
                    results = asked.value.first
                    fresh = asked.value.second
                    break
                }
                // Another surface asked first: its answers are read on the next pass, a pull's included.
                Asked.Waited -> force = false
            }
        }
        val now = clock()
        val answered = results?.answeredIds.orEmpty()
        val failed = results?.failed.orEmpty().toSet()
        val answers = lines.mapNotNull { id ->
            if (id in failed) return@mapNotNull null
            val held = this[id] ?: return@mapNotNull null
            // Shown when it's this check's, landed since it began, or still within the window; one still
            // waiting on its alerts' directions is shown too, only asked again next time.
            val shown = id in answered || since(id) || (held as? Held.Omitted)?.unrecognized == true ||
                LineStatusReuse.stands(held.at, now, reuse)
            if (shown) id to held else null
        }.toMap()
        return Checked(
            answers = answers,
            fresh = answered,
            freshStatuses = fresh,
            failed = failed,
            unknown = results?.unknown.orEmpty().toSet(),
            failure = results?.failure,
            requests = results?.requests ?: 0,
        )
    }

    // Asks TfL about [toAsk] and holds what it answered: each line it gave a status for, and each it was
    // asked about and left out, the ones it doesn't know marked so.
    private suspend fun ask(
        toAsk: Set<String>,
        clock: () -> Instant,
        fetch: suspend (List<String>) -> List<LineStatus>?,
    ): Pair<LineStatusBatch.Results<List<LineStatus>>, Map<String, LineStatus>> {
        val ticket = ticket()
        val results = LineStatusBatch.request(toAsk, fetch)
        if (!results.anyAnswered) return results to emptyMap()
        val at = SteadyClock.stamp(clock())
        val returned = results.answers.flatMap { it.value }.filter { it.lineId in toAsk }.associateBy { it.lineId }
        results.answeredIds.forEach { id ->
            val answer = returned[id]?.let { Held.Verdict(at, it) } ?: Held.Omitted(at, unrecognized = id in results.unknown)
            set(id, answer, ticket)
        }
        return results to returned
    }

    companion object {
        /** The app's one cache, fed by every line-status check. */
        val SHARED = LineStatusCache()
    }
}
