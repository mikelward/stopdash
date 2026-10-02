package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.toKotlinDuration

/**
 * The stops' last arrivals, shared across screens for the process (SPEC *Freshness → Shared
 * arrivals*): each stop's departures as its last fetch returned them, with when they were fetched.
 * A screen shows a stop's entry at once rather than wait on the network, and asks TfL again only
 * once it's [TTL] old; its countdowns are recomputed from the clock like any snapshot's, and an entry
 * past [Staleness.THRESHOLD] is never handed out (SPEC D4). Memory only: never saved, gone with the
 * process; bounded to [MAX] stops, the least recently fetched dropped first.
 */
class ArrivalsCache {
    /**
     * One stop's last arrivals, when they were fetched, its National Rail feed then ([TflClient.railFeed]),
     * the source they came from ([TflClient.arrivalsSource]), and the trains its board listed with no
     * time ([TflClient.untimed]).
     */
    data class Entry(
        val departures: List<Departure>,
        val fetchedAt: Instant,
        val railFeed: RailFeed? = null,
        val source: Any? = null,
        val untimed: List<UntimedTrain> = emptyList(),
    )

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * [stopId]'s last arrivals from [source] (the reader's own, [TflClient.arrivalsSource]), or null
     * when none were fetched from it — a National Rail key added or removed since, however that came
     * about — or they're stale at the wall time [now] (aged by the steady clock, [Staleness.age]), or
     * dated after it, an age that can't be told.
     */
    @Synchronized
    fun get(stopId: String, now: Instant, source: Any? = null): Entry? = entries[stopId]?.takeIf {
        val age = Staleness.age(it.fetchedAt, now)
        it.source == source && !age.isNegative() && !Staleness.isStale(age)
    }

    /** [stopId]'s last arrivals if fetched within [TTL] of [now]: recent enough to show rather than ask again. */
    fun recent(stopId: String, now: Instant, source: Any? = null): Entry? =
        get(stopId, now, source)?.takeIf { Staleness.age(it.fetchedAt, now) < TTL.toKotlinDuration() }

    /**
     * How many times the cache has been [clear]ed: a fetch asked for before the last clear (under a
     * National Rail key since removed, say) is from a source that no longer stands.
     */
    @get:Synchronized
    var generation: Long = 0
        private set

    /**
     * Keeps [departures] as [stopId]'s arrivals fetched at [at], unless a later fetch is already kept
     * or the fetch was asked for in an earlier [generation] than now.
     */
    @Synchronized
    fun put(
        stopId: String,
        departures: List<Departure>,
        at: Instant,
        railFeed: RailFeed? = null,
        generation: Long = this.generation,
        source: Any? = null,
        untimed: List<UntimedTrain> = emptyList(),
    ) {
        if (generation != this.generation) return
        val held = entries[stopId]
        if (held != null && held.source == source && held.fetchedAt.isAfter(at)) return
        entries.remove(stopId)
        entries[stopId] = Entry(departures, at, railFeed, source, untimed)
        while (entries.size > MAX) entries.remove(entries.keys.first())
    }

    /** Forgets every stop, and any fetch still out: a pull-to-refresh asks TfL afresh for everything. */
    @Synchronized
    fun clear() {
        entries.clear()
        generation++
    }

    // Each key's fetch under way through [fetchOnce], with the [generation] it was asked in.
    private val fetching = HashMap<String, Pair<Long, CompletableDeferred<Fetch>>>()

    private sealed interface Fetch {
        class Done(val entry: Entry?) : Fetch

        // Its asker was canceled before it answered.
        object Abandoned : Fetch
    }

    /**
     * [key]'s departures, fetched once for every asker, with when they were fetched: those kept
     * within [TTL] of [now] from [source], else the answer of a [fetch] of the same key already under
     * way since the last [clear], else [fetch]'s own, kept for the rest ([put]). Null when the fetch
     * had nothing to keep (it failed), which is never kept. One whose asker is canceled leaves the
     * others to ask afresh. For what several screens' clients each ask for, such as a station's
     * National Rail board; a kept answer keeps its age, as any stop's does.
     */
    suspend fun fetchOnce(key: String, now: Instant, source: Any?, fetch: suspend () -> List<Departure>?): Entry? =
        fetchBoardOnce(key, now, source) { fetch()?.let(::RailBoard) }

    /** [fetchOnce] for a National Rail board, its trains with no time ([RailBoard.untimed]) kept with it. */
    suspend fun fetchBoardOnce(key: String, now: Instant, source: Any?, fetch: suspend () -> RailBoard?): Entry? {
        val pending = CompletableDeferred<Fetch>()
        val (askedIn, running) = synchronized(this) {
            // What's kept and what's under way are read together: a fetch keeps its answer before
            // it gives up its place, so one answering meanwhile is found either way, never missed.
            recent(key, now, source)?.let { return it }
            val current = generation
            val under = fetching[key]?.takeIf { it.first == current }?.second
            if (under == null) fetching[key] = current to pending
            current to under
        }
        if (running != null) {
            return when (val outcome = running.await()) {
                is Fetch.Done -> outcome.entry
                Fetch.Abandoned -> fetchBoardOnce(key, now, source, fetch)
            }
        }
        var outcome: Fetch = Fetch.Abandoned
        try {
            // Stamped when asked, as [CachingTflClient] stamps a stop's.
            val askedAt = SteadyClock.stamp(now)
            val entry = fetch()?.let { Entry(it.departures, askedAt, source = source, untimed = it.untimed) }
            if (entry != null) put(key, entry.departures, askedAt, generation = askedIn, source = source, untimed = entry.untimed)
            outcome = Fetch.Done(entry)
            return entry
        } finally {
            synchronized(this) { if (fetching[key]?.second === pending) fetching.remove(key) }
            pending.complete(outcome)
        }
    }

    companion object {
        /**
         * How old a stop's arrivals may be before a screen asks TfL again: about a minute (maintainer,
         * 2026-09-26), a little under so the once-a-minute auto-refresh, stamped a moment after its
         * tick, never finds its last fetch still under it and skips a cycle.
         */
        val TTL: Duration = Duration.ofSeconds(50)

        /** How many stops are kept at most: a busy nearby set, its farther stops and a trip's. */
        const val MAX = 64

        /** The app's one cache, fed by every arrivals fetch ([CachingTflClient]). */
        val SHARED = ArrivalsCache()
    }
}

/**
 * [tfl] with each stop's arrivals kept in [cache] as they come back, so every screen's fetch warms
 * what the others show (SPEC *Freshness → Shared arrivals*). A failed fetch keeps nothing, so the
 * last good arrivals stand, aged; nor does one another client could answer differently
 * ([TflClient.shareable]).
 */
class CachingTflClient(
    private val tfl: TflClient,
    private val cache: ArrivalsCache = ArrivalsCache.SHARED,
    private val clock: () -> Instant = Instant::now,
) : TflClient by tfl {
    // Stamped when asked, not answered, as the list stamps its fetches (SPEC D4), and by the steady
    // clock ([SteadyClock]), as every fetch is, so setting the clock doesn't age it: an older request
    // answering late can't pass for a newer one, or overwrite it. Kept only if shareable both when
    // asked and when answered, and asked since the cache was last cleared: a source changed in
    // between (a National Rail key added or removed) leaves nothing behind.
    override suspend fun arrivals(stopId: String): List<Departure> = kept(stopId) { tfl.arrivals(stopId) }

    override suspend fun arrivals(stopId: String, railBoard: Boolean): List<Departure> =
        kept(stopId) { tfl.arrivals(stopId, railBoard) }

    private suspend fun kept(stopId: String, fetch: suspend () -> List<Departure>): List<Departure> {
        val askedAt = SteadyClock.stamp(clock())
        val generation = cache.generation
        val shareable = tfl.shareable(stopId)
        val source = tfl.arrivalsSource()
        return fetch().also {
            if (shareable && tfl.shareable(stopId) && tfl.arrivalsSource() == source) {
                cache.put(stopId, it, tfl.stampOf(stopId, askedAt), tfl.railFeed(stopId), generation, source, tfl.untimed(stopId))
            }
        }
    }
}
