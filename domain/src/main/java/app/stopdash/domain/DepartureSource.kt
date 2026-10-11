package app.stopdash.domain

import java.time.Instant

/**
 * Where a refresh gets each stop's departures from, the rule every surface shares (the app's list,
 * the widget, the watch): kept as shown, taken from another screen's recent fetch ([ArrivalsCache]),
 * or asked of TfL. One rule, so two screens never show different times for one stop, nor ask TfL for
 * what another screen fetched moments ago (SPEC *Freshness → Shared arrivals*).
 */
object DepartureSource {
    sealed interface Choice {
        /** The departures already shown stand: fetched recently enough, with nothing newer to take. */
        data object Kept : Choice

        /** Another screen's fetch, at its own fetch time, rather than asked for again. */
        data class Shared(val entry: ArrivalsCache.Entry) : Choice

        /** Asked of TfL. */
        data object Fetch : Choice
    }

    /**
     * [entry] (another screen's fetch within [ArrivalsCache.TTL], or null for none) taken when it's
     * newer than [held]; else [held] kept when [reusable] (recent enough, by the caller's own window);
     * else fetched. An entry no newer than [held] is never taken: an older one would show older times
     * than before, and one as old is the fetch already shown, so a stop the caller wants refreshed
     * is asked for afresh.
     */
    fun choose(held: StopArrivals?, entry: ArrivalsCache.Entry?, reusable: Boolean): Choice = when {
        entry != null && (held == null || entry.fetchedAt.isAfter(held.fetchedAt)) -> Choice.Shared(entry)
        reusable && held != null -> Choice.Kept
        else -> Choice.Fetch
    }

    /**
     * When departures asked for at [askedAt] were fetched: then, or as long ago as the oldest part of
     * them was ([oldest], a National Rail board another screen fetched moments ago, say), so they keep
     * their age.
     */
    fun stamp(askedAt: Instant, oldest: Instant?): Instant = oldest?.takeIf { it.isBefore(askedAt) } ?: askedAt
}
