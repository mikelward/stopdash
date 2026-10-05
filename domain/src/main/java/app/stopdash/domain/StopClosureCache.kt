package app.stopdash.domain

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Each stop's last successful closure lookup ([TflClient.stopDisruptions]: a closure, a moved stop)
 * and when it was made, shared by the screens that check stops (SPEC *Disruptions*): the list and a
 * trip reuse each other's lookups for a few minutes rather than ask TfL again, since a closure
 * changes over hours and every lookup is a request against TfL's rate budget. A failure is never
 * kept, so it's asked again next time; only its place in line is, until a lookup asked after it
 * succeeds, so an answer asked before it isn't reused over it ([get]). Memory only: never saved,
 * gone with the process; bounded to [MAX] stops, the least recently looked up dropped first.
 *
 * Lookups race: the list and a trip can ask about one stop at once, and the older answer can land
 * last. So each lookup takes its place in line from [ask] before it's sent, and [settle] holds on to
 * the one asked last, whichever lands last — by that order, not by clock, which can tie or step back.
 * A failure settles the same way: a lookup asked after it that already succeeded answers for it.
 *
 * [lookUp] keeps one request per stop in flight at a time: a check that needs a stop no recent
 * lookup answers joins the request already out for it, if any, rather than send its own, so the
 * screens never ask TfL about one stop twice at once and two answers for it never cross.
 */
class StopClosureCache {
    /**
     * A lookup's place in line, taken before it's sent: when it was asked ([at], stamped by the
     * steady clock as a fetch is, [SteadyClock], so its age for reuse isn't changed by setting the
     * device's clock) and in what order. [dismissals] is the dismissed alerts' count when it was asked
     * ([DismissedAlertsStore.mark]): a dismissal counted after is newer than its answer, so a check
     * settling dismissals on it never lets that one go. 0 (none known) lets go only of dismissals
     * this process hasn't counted.
     */
    class Ask internal constructor(val at: Instant, internal val order: Long, val dismissals: Long = 0L)

    /**
     * A kept lookup: what it found ([notices]) and its place in line ([ask]). A caller that shows
     * [notices] keeps [ask] to tell later whether a newer lookup has come in ([since]) — by order, not
     * by [at], which two lookups can share.
     */
    class Lookup internal constructor(val ask: Ask, val notices: List<StopDisruption>) {
        /** When it was asked, stamped by the steady clock ([Ask.at]): its age, for reuse. */
        val at: Instant get() = ask.at
    }

    private val entries = LinkedHashMap<String, Lookup>()

    // Each stop's latest failed lookup's place in line, while no lookup asked after it has succeeded.
    private val failures = LinkedHashMap<String, Ask>()
    private var asked = 0L

    /**
     * A place in line for a lookup about to be sent at the wall time [at]: later than every one taken
     * before it. [dismissals]: the dismissed alerts' count now ([Ask.dismissals]).
     */
    @Synchronized
    fun ask(at: Instant, dismissals: Long = 0L): Ask = Ask(SteadyClock.stamp(at), ++asked, dismissals)

    /**
     * [stopId]'s last successful lookup, or null when none is kept, or when a lookup asked after it
     * has since failed: that one answered nothing, and the one before it doesn't answer for it, so
     * the stop is asked again rather than taken from an answer older than its latest check (Codex,
     * PR #375). [since] still finds it for a caller holding an older one.
     */
    @Synchronized
    operator fun get(stopId: String): Lookup? =
        entries[stopId]?.takeIf { lookup -> failures[stopId]?.let { it.order < lookup.ask.order } ?: true }

    /**
     * Keeps [notices], from the lookup [ask] names, as [stopId]'s unless one asked later is already
     * kept (this one landing late), and returns the lookup it holds after, so a caller shows the
     * latest answer rather than its own older one.
     */
    @Synchronized
    fun keep(stopId: String, ask: Ask, notices: List<StopDisruption>): Lookup {
        entries[stopId]?.takeIf { it.ask.order > ask.order }?.let { return it }
        val lookup = Lookup(ask, notices)
        entries.remove(stopId)
        entries[stopId] = lookup
        while (entries.size > MAX) entries.remove(entries.keys.first())
        // Answered by a lookup asked after it: that failure no longer stands.
        if (failures[stopId]?.let { it.order < ask.order } == true) failures.remove(stopId)
        return lookup
    }

    /**
     * Settles the lookup [ask] names for [stopId]: a success as [keep] does, and a failure with a
     * lookup asked after it, if one is kept (it landed first), else left a failure — so an older
     * failure never hides a newer answer another screen already has — whose place in line is kept
     * against reusing an answer asked before it ([get]).
     */
    @Synchronized
    fun settle(stopId: String, ask: Ask, outcome: Result<List<StopDisruption>>): Result<Lookup> =
        outcome.fold(
            onSuccess = { Result.success(keep(stopId, ask, it)) },
            onFailure = { e ->
                since(stopId, ask)?.let { Result.success(it) } ?: run {
                    if (failures[stopId]?.let { it.order < ask.order } != false) {
                        failures.remove(stopId)
                        failures[stopId] = ask
                        // A stop's answer goes with its failure: kept alone, the answer asked before
                        // it would be offered for reuse again (Codex, PR #375).
                        while (failures.size > MAX) failures.keys.first().let { failures.remove(it); entries.remove(it) }
                    }
                    Result.failure(e)
                }
            },
        )

    // Each stop's request in flight ([lookUp]), until it settles.
    private val inFlight = HashMap<String, Deferred<Result<Lookup>>>()

    /**
     * One stop's answer to a [lookUp]: a lookup already kept ([cached]), the request another check
     * already had out for it ([joined]), or the caller's own, sent now (neither). [await] waits for it.
     */
    class Pending internal constructor(
        private val answer: Deferred<Result<Lookup>>,
        val cached: Boolean,
        val joined: Boolean,
        // Asks again in the caller's own scope: for a joined request its owner canceled.
        private val again: suspend () -> Result<Lookup>,
    ) {
        /**
         * The stop's answer: success with the lookup kept, or the request's failure. A joined request
         * canceled by the check that sent it (that check left) is asked again for this caller.
         */
        suspend fun await(): Result<Lookup> =
            try {
                answer.await()
            } catch (e: CancellationException) {
                // This caller canceled: as it should. Else the request's owner did: asked again.
                currentCoroutineContext().ensureActive()
                if (!joined) throw e
                again()
            }
    }

    /**
     * Each of [ids]' closure answer: from a lookup kept that [reusable] accepts, else the request in
     * flight for it, else asked now — the rest asked together, grouped by [group] (bus poles several
     * to a request), one [send] per group, each sent in [scope] with its place in line taken as it's
     * sent ([ask], at [now], with [dismissals] counted then) and settled here as it lands ([settle]).
     * Which way each goes is decided in one step, so no stop is ever asked twice at once. [send]
     * returns each id's outcome, a failure for one TfL couldn't answer. Worked out, sent and settled on
     * [worker], whatever the caller's thread: each grows with [ids].
     */
    suspend fun lookUp(
        scope: CoroutineScope,
        worker: CoroutineDispatcher,
        ids: Collection<String>,
        reusable: (Lookup) -> Boolean,
        now: () -> Instant,
        dismissals: () -> Long,
        group: (List<String>) -> List<List<String>> = { listOf(it) },
        send: suspend (List<String>) -> Map<String, Result<List<StopDisruption>>>,
    ): Map<String, Pending> = withContext(worker) {
        fun again(id: String): suspend () -> Result<Lookup> = {
            lookUp(scope, worker, listOf(id), reusable, now, dismissals, group, send).getValue(id).await()
        }
        val sent = ArrayList<Deferred<*>>()
        val pending = synchronized(this@StopClosureCache) {
            val found = LinkedHashMap<String, Pending>()
            val missing = ArrayList<String>()
            for (id in ids.distinct()) {
                val kept = get(id)?.takeIf(reusable)
                val out = inFlight[id]
                when {
                    kept != null -> found[id] = Pending(CompletableDeferred(Result.success(kept)), cached = true, joined = false, again(id))
                    out != null -> found[id] = Pending(out, cached = false, joined = true, again(id))
                    else -> missing += id
                }
            }
            for (batch in if (missing.isEmpty()) emptyList() else group(missing)) {
                // Started once registered, outside this lock: never run while it's held.
                val request = scope.async(worker, start = CoroutineStart.LAZY) {
                    val ask = ask(now(), dismissals())
                    val answer = send(batch)
                    batch.associateWith { id -> settle(id, ask, answer[id] ?: Result.failure(NoSuchElementException(id))) }
                }
                sent += request
                for (id in batch) {
                    val one = scope.async(worker, start = CoroutineStart.LAZY) { request.await().getValue(id) }
                    inFlight[id] = one
                    one.invokeOnCompletion { synchronized(this@StopClosureCache) { if (inFlight[id] === one) inFlight.remove(id) } }
                    sent += one
                    found[id] = Pending(one, cached = false, joined = false, again(id))
                }
            }
            found
        }
        sent.forEach { it.start() }
        pending
    }

    /** A lookup of [stopId] asked after [ask], if one is kept: a newer answer than its. */
    @Synchronized
    fun since(stopId: String, ask: Ask): Lookup? = entries[stopId]?.takeIf { it.ask.order > ask.order }

    companion object {
        /** How many stops are kept at most: far more than a list and a trip check at once. */
        const val MAX = 500

        /** The one the app's screens share. */
        val SHARED = StopClosureCache()
    }
}
