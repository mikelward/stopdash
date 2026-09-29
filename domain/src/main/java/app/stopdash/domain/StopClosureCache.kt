package app.stopdash.domain

import java.time.Instant

/**
 * Each stop's last successful closure lookup ([TflClient.stopDisruptions]: a closure, a moved stop)
 * and when it was made, shared by the screens that check stops (SPEC *Disruptions*): the list and a
 * trip reuse each other's lookups for a few minutes rather than ask TfL again, since a closure
 * changes over hours and every lookup is a request against TfL's rate budget. A failure is never
 * kept, so it's asked again next time. Memory only: never saved, gone with the process; bounded to
 * [MAX] stops, the least recently looked up dropped first.
 *
 * Lookups race: the list and a trip can ask about one stop at once, and the older answer can land
 * last. So each lookup takes its place in line from [ask] before it's sent, and [settle] holds on to
 * the one asked last, whichever lands last — by that order, not by clock, which can tie or step back.
 * A failure settles the same way: a lookup asked after it that already succeeded answers for it.
 */
class StopClosureCache {
    /** A lookup's place in line, taken before it's sent: when it was asked ([at]) and in what order. */
    class Ask internal constructor(val at: Instant, internal val order: Long)

    /**
     * A kept lookup: what it found ([notices]) and its place in line ([ask]). A caller that shows
     * [notices] keeps [ask] to tell later whether a newer lookup has come in ([since]) — by order, not
     * by [at], which two lookups can share.
     */
    class Lookup internal constructor(val ask: Ask, val notices: List<StopDisruption>) {
        /** When it was asked: its age, for reuse. */
        val at: Instant get() = ask.at
    }

    private val entries = LinkedHashMap<String, Lookup>()
    private var asked = 0L

    /** A place in line for a lookup about to be sent at [at]: later than every one taken before it. */
    @Synchronized
    fun ask(at: Instant): Ask = Ask(at, ++asked)

    /** [stopId]'s last successful lookup, or null when none is kept. */
    @Synchronized
    operator fun get(stopId: String): Lookup? = entries[stopId]

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
        return lookup
    }

    /**
     * Settles the lookup [ask] names for [stopId]: a success as [keep] does, and a failure with a
     * lookup asked after it, if one is kept (it landed first), else left a failure — so an older
     * failure never hides a newer answer another screen already has.
     */
    @Synchronized
    fun settle(stopId: String, ask: Ask, outcome: Result<List<StopDisruption>>): Result<Lookup> =
        outcome.fold(
            onSuccess = { Result.success(keep(stopId, ask, it)) },
            onFailure = { e -> since(stopId, ask)?.let { Result.success(it) } ?: Result.failure(e) },
        )

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
