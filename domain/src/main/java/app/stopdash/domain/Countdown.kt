package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * Turns [Departure]s into what a surface shows, applying SPEC D4's client-side
 * rules against a caller-supplied [now] (never a wall clock read here, so this
 * stays pure and JVM-testable):
 *
 * - **Recompute from now**, not the fetch-time countdown, so the number stays
 *   honest as the clock advances between fetches.
 * - **Drop a departed service** rather than holding it at "0 min" or showing
 *   negative time — a countdown that reaches zero leaves the list.
 * - **Soonest-first**, so the next departure is at the top.
 */
object Countdown {
    /** Time until [departure], negative once it has gone. */
    fun remaining(departure: Departure, now: Instant): Duration =
        Duration.between(now, departure.expectedArrival)

    /** True once [departure]'s time has reached or passed [now]; such a service is dropped. */
    fun hasDeparted(departure: Departure, now: Instant): Boolean {
        val left = remaining(departure, now)
        return left.isZero || left.isNegative
    }

    /**
     * Whole minutes from [now] until [at], rounded down and never below zero: "0" inside the last
     * minute. Every countdown the rider sees takes this one rule, so a train shown on a board and
     * the same train named elsewhere (a trip's "Due in") never read a minute apart.
     */
    @MainSafe
    fun minutes(at: Instant, now: Instant): Long =
        Duration.between(now, at).toMinutes().coerceAtLeast(0)

    /**
     * The minutes label: "0 min" inside the last minute, "N min" otherwise. Assumes
     * [departure] has not yet gone — call [upcoming] first, which drops departed
     * services — but is defensive: a non-positive remaining still reads "0 min"
     * rather than a negative number.
     */
    fun label(departure: Departure, now: Instant): String = "${minutes(departure.expectedArrival, now)} min"

    /**
     * Several [departures]' countdowns as one line — "0 · 3 · 6 min" — for a card that
     * merges a service's next few times instead of one row each. Each departure renders as
     * [label] would ("0" or the bare minute count), joined by " · ", with the "min" unit
     * written once at the end so it reads as a list of minutes rather than repeating it.
     *
     * The caller passes an already-[upcoming] list (soonest-first, departed ones dropped),
     * **all to the same destination** — the screen groups by destination first so a
     * branching direction never merges a divergent train's time under the wrong headline
     * (SPEC D8). Empty in, empty out. Every entry is a number ("0" for an imminent train),
     * so the "min" unit is always written once at the end.
     */
    fun mergedLabel(departures: List<Departure>, now: Instant): String {
        if (departures.isEmpty()) return ""
        // Every entry is a number, so the "min" unit always belongs at the end.
        return departures.joinToString(" · ") { minutes(it.expectedArrival, now).toString() } + " min"
    }

    /**
     * One time in a merged countdown: a train's [Timed] minutes, or a train its board lists with no
     * time ([Untimed]: canceled, or delayed with no estimate), drawn as a word in its place.
     */
    sealed interface Entry {
        data class Timed(val departure: Departure) : Entry

        data class Untimed(val train: UntimedTrain) : Entry
    }

    /**
     * [departures] and the [untimed] trains in one order: each by its time, an untimed train by when
     * it was scheduled, which is where a rider looks for it; a timed train first on a tie.
     */
    fun entries(departures: List<Departure>, untimed: List<UntimedTrain>): List<Entry> =
        (departures.map { it.expectedArrival to Entry.Timed(it) } + untimed.map { it.train.expectedArrival to Entry.Untimed(it) })
            .sortedWith(compareBy({ it.first }, { if (it.second is Entry.Timed) 0 else 1 }))
            .map { it.second }

    /**
     * [entries] as one merged label, as [mergedLabel] writes times alone: each timed train's minutes
     * ([minutes]), a canceled train as [canceled], one delayed with no estimate as [delayed]. The "min"
     * unit goes after the last number, so "5 · Canceled · 12 min" and "5 min · Canceled" both read;
     * with no number there's no unit.
     */
    fun mergedLabel(entries: List<Entry>, now: Instant, canceled: String, delayed: String): String {
        val last = entries.indexOfLast { it is Entry.Timed }
        return entries.mapIndexed { i, entry ->
            when (entry) {
                is Entry.Timed -> minutes(entry.departure.expectedArrival, now).toString() + if (i == last) " min" else ""
                is Entry.Untimed -> if (entry.train.canceled) canceled else delayed
            }
        }.joinToString(" · ")
    }

    /**
     * Whether a train with no time is still shown at [now]: a canceled one until its scheduled time,
     * as a timed train goes at its time (it won't run, and past it would only take a later train's
     * place among the few shown, Codex on PR #466); one delayed with no estimate while its board lists
     * it, since it hasn't left (its board drops it once it has, and a stale stop withholds it as any).
     */
    fun stillShown(train: UntimedTrain, now: Instant): Boolean = !train.canceled || !hasDeparted(train.train, now)

    /** [departures] that have not yet gone, soonest-first (ties broken by line for stability). */
    fun upcoming(departures: List<Departure>, now: Instant): List<Departure> =
        departures.filterNot { hasDeparted(it, now) }
            .sortedWith(compareBy({ it.expectedArrival }, { it.lineName }))
}
