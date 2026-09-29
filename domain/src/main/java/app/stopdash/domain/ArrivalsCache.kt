package app.stopdash.domain

import java.time.Duration
import java.time.Instant
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
     * and the source they came from ([TflClient.arrivalsSource]).
     */
    data class Entry(val departures: List<Departure>, val fetchedAt: Instant, val railFeed: RailFeed? = null, val source: Any? = null)

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
    ) {
        if (generation != this.generation) return
        val held = entries[stopId]
        if (held != null && held.source == source && held.fetchedAt.isAfter(at)) return
        entries.remove(stopId)
        entries[stopId] = Entry(departures, at, railFeed, source)
        while (entries.size > MAX) entries.remove(entries.keys.first())
    }

    /** Forgets every stop, and any fetch still out: a pull-to-refresh asks TfL afresh for everything. */
    @Synchronized
    fun clear() {
        entries.clear()
        generation++
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
    override suspend fun arrivals(stopId: String): List<Departure> {
        val askedAt = SteadyClock.stamp(clock())
        val generation = cache.generation
        val shareable = tfl.shareable(stopId)
        val source = tfl.arrivalsSource()
        return tfl.arrivals(stopId).also {
            if (shareable && tfl.shareable(stopId) && tfl.arrivalsSource() == source) {
                cache.put(stopId, it, askedAt, tfl.railFeed(stopId), generation, source)
            }
        }
    }
}
