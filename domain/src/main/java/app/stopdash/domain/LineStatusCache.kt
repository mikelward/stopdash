package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Each line's last status answer, for the process: whichever surface asked (the app's list, the
 * widget's refresh, a watch's request), so a line checked by one is reused by the rest within
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
        held.compute(lineId) { _, current ->
            when {
                current != null && current.ticket > ticket -> current
                answer is Held.Omitted && !answer.unrecognized && (current?.answer as? Held.Omitted)?.unrecognized == true ->
                    Entry(answer.copy(unrecognized = true), ticket)
                else -> Entry(answer, ticket)
            }
        }
    }

    /** The lines whose latest answer is that TfL doesn't know them ([Held.Omitted.unrecognized]). */
    @WorkerThread
    fun unrecognized(): Set<String> =
        held.entries.mapNotNullTo(HashSet()) { (id, entry) -> id.takeIf { (entry.answer as? Held.Omitted)?.unrecognized == true } }

    /** Holds [answer] as [lineId]'s latest, as the answer to a request sent now. */
    operator fun set(lineId: String, answer: Held) = set(lineId, answer, ticket())

    companion object {
        /** The app's one cache, fed by every line-status check. */
        val SHARED = LineStatusCache()
    }
}
