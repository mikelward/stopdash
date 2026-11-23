package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns [Departure]s into what a surface shows, applying SPEC D4's client-side
 * rules against a caller-supplied [now] (never a wall clock read here, so this
 * stays pure and JVM-testable):
 *
 * - **Recompute from now**, not the fetch-time countdown, so the number stays
 *   honest as the clock advances between fetches.
 * - **Drop a departed service** rather than showing negative time — a countdown
 *   that reaches zero leaves the list, except that a board keeps a Tube train for
 *   [BOARD_GRACE] past its time, grayed at "0" ([onBoard]).
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
    fun mergedLabel(departures: List<Departure>, now: Instant, words: CountdownWords = CountdownWords.ENGLISH): String {
        if (departures.isEmpty()) return ""
        // Every entry is a number, so the "min" unit always belongs at the end.
        return words.minutes(departures.joinToString(words.between) { timeText(it, now) })
    }

    /**
     * The latest minutes a train may come in, where its location says well after its own time ([Departure.upTo]);
     * null where it says nothing later than the train's own minutes.
     */
    fun upToMinutes(departure: Departure, now: Instant): Long? {
        val upTo = departure.upTo ?: return null
        val latest = minutes(upTo, now)
        return latest.takeIf { it > minutes(departure.expectedArrival, now) }
    }

    /** One train's time in a countdown: its minutes ("0"), or the range to [upToMinutes] ("0–2"). */
    fun timeText(departure: Departure, now: Instant): String {
        val soonest = minutes(departure.expectedArrival, now)
        return upToMinutes(departure, now)?.let { "$soonest$RANGE$it" } ?: soonest.toString()
    }

    /** Between a range's two ends: an en dash, as a range is written. */
    const val RANGE = "\u2013"

    /**
     * One train's minutes in a merged countdown ([minutes]), whether it may not run ([Departure.mayNotRun]),
     * and whether it's [held] ([Departure.held]).
     */
    data class Part(val minutes: Long, val doubtful: Boolean, val held: Boolean = false, val pastDue: Boolean = false, val upTo: Long? = null) {
        val grayed: Boolean get() = doubtful || held || pastDue

        /** As a countdown writes it: the minutes, or the range to [upTo]. */
        val text: String get() = upTo?.let { "$minutes$RANGE$it" } ?: minutes.toString()
    }

    /**
     * [departures]' minutes, each with whether it may not run or is held, for a surface that grays
     * those; read as [mergedLabel] reads them.
     */
    fun parts(departures: List<Departure>, now: Instant): List<Part> =
        departures.map { Part(minutes(it.expectedArrival, now), it.mayNotRun, it.held, it.pastDue, upToMinutes(it, now)) }

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
    fun mergedLabel(
        entries: List<Entry>,
        now: Instant,
        canceled: String,
        delayed: String,
        words: CountdownWords = CountdownWords.ENGLISH,
    ): String {
        val last = entries.indexOfLast { it is Entry.Timed }
        return entries.mapIndexed { i, entry ->
            when (entry) {
                is Entry.Timed -> timeText(entry.departure, now).let { if (i == last) words.minutes(it) else it }
                is Entry.Untimed -> if (entry.train.canceled) canceled else delayed
            }
        }.joinToString(words.between)
    }

    /**
     * Whether a train with no time is still shown at [now]: a canceled one until its scheduled time,
     * as a timed train goes at its time (it won't run, and past it would only take a later train's
     * place among the few shown, Codex on PR #466); one delayed with no estimate while its board lists
     * it, since it hasn't left (its board drops it once it has, and a stale stop withholds it as any).
     */
    fun stillShown(train: UntimedTrain, now: Instant): Boolean = !train.canceled || !hasDeparted(train.train, now)

    /**
     * What a stale line shows in place of its countdown (SPEC D4): its soonest train's predicted time
     * in London, marked as a guess ("21:14?"), so the surface still says roughly when without a
     * live-looking count. A clock time, unlike a countdown, doesn't read as recomputed from now.
     * [departures] are soonest-first, any already gone left out ([upcoming]); with none, just "?".
     */
    fun staleLabel(departures: List<Departure>): String =
        // Never a train already past its time ([onBoard]): a stop gone stale since its rows were built
        // may still hold one for a tick, and its time is no guess at the next train.
        departures.firstOrNull { !it.pastDue }?.let { STALE_CLOCK.format(it.expectedArrival.atZone(LONDON)) + "?" } ?: "?"

    private val LONDON: ZoneId = ZoneId.of("Europe/London")
    private val STALE_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** [departures] that have not yet gone, soonest-first (ties broken by line for stability). */
    fun upcoming(departures: List<Departure>, now: Instant): List<Departure> =
        departures.filterNot { hasDeparted(it, now) }
            .sortedWith(compareBy({ it.expectedArrival }, { it.lineName }))

    /**
     * How long a board keeps a Tube train after its time has passed ([onBoard]). Measured trains came
     * a median ~35 s after the last time shown for them, but that's judged by TfL's own feed, which a
     * rider's log found a minute behind a moving train; so half a minute, not one (maintainer, 2026-10-20).
     */
    val BOARD_GRACE: Duration = Duration.ofSeconds(30)

    /**
     * What a board shows of [departures]: those still to come ([upcoming]), and a Tube train whose time
     * passed under [BOARD_GRACE] ago, marked [Departure.pastDue] so it reads "0", grayed, while it's
     * likely still on its way. Other modes go at their time, as before. A train's blank mode takes its
     * line's, from the line's other trains, else the mode its stop advertises for the line
     * ([lineModes], as its row's is resolved). Only a board takes this: a trip still judges a train gone
     * at its time ([upcoming]).
     */
    @WorkerThread
    fun onBoard(departures: List<Departure>, now: Instant, lineModes: Map<String, String> = emptyMap()): List<Departure> {
        val lineMode = lineModes + departures.filter { it.mode.isNotBlank() }.associate { it.lineId to it.mode }
        return departures.mapNotNull { d ->
            when {
                !hasDeparted(d, now) -> d
                // TfL's time stalled at due while its location says it may still be coming ([Departure.upTo]): it
                // stays, "0–2", as a train still to come, until its latest.
                stillRanged(d, now) -> d
                inGrace(d, now, lineMode) -> d.copy(pastDue = true)
                else -> null
            }
        }.sortedWith(compareBy({ it.expectedArrival }, { it.lineName }))
    }

    /** Whether a board still shows [departure] at [now]: before its time, or before the latest its location gives. */
    fun stillRanged(departure: Departure, now: Instant): Boolean = departure.upTo?.isAfter(now) == true

    /**
     * [departures] a surface with no grace shows at [now] (the widget, the watch): those still to come
     * ([upcoming]), and a train past its time while its location says it may still come ([stillRanged]),
     * soonest first, so "0–2 min" stays on every surface alike.
     */
    fun shown(departures: List<Departure>, now: Instant): List<Departure> =
        departures.filterNot { goneFromBoard(it, now) }.sortedWith(compareBy({ it.expectedArrival }, { it.lineName }))

    /**
     * The instants a [departure]'s countdown text can next change at: its time, and the latest its location
     * gives ([Departure.upTo]), each of whose minute counts goes down as the clock passes it.
     */
    fun changesAt(departure: Departure): List<Instant> = listOfNotNull(departure.expectedArrival, departure.upTo)

    /** Whether a board has let [departure] go by [now] ([onBoard]'s rule for a train past its time, less the grace). */
    fun goneFromBoard(departure: Departure, now: Instant): Boolean = hasDeparted(departure, now) && !stillRanged(departure, now)

    private fun inGrace(d: Departure, now: Instant, lineMode: Map<String, String>): Boolean =
        d.mode.ifBlank { lineMode[d.lineId].orEmpty() }.equals("tube", ignoreCase = true) &&
            now.isBefore(d.expectedArrival.plus(BOARD_GRACE))
}

/**
 * A countdown's words in the app's language: the minutes unit, written once around the numbers it
 * counts ([minutes]: "3 · 7 min"), and what goes between those numbers ([between]: " · ").
 * [ENGLISH] for a caller with no resources: a test, a diagnostic.
 */
class CountdownWords(val between: String, private val unit: (numbers: String) -> String) {
    /** [numbers], already joined, with the unit around them: "3 · 7" as "3 · 7 min". */
    fun minutes(numbers: String): String = unit(numbers)

    /**
     * The unit's words before and after the numbers it goes around ("" and " min"), for a caller that
     * styles each number on its own (a grayed time) and so can't take [minutes]' one string.
     */
    val around: Pair<String, String> by lazy {
        val marked = unit(MARK)
        marked.substringBefore(MARK) to marked.substringAfter(MARK, "")
    }

    companion object {
        /** The English the app has always written. */
        val ENGLISH = CountdownWords(" · ") { "$it min" }

        // A character no unit's words contain, to find where the numbers go in them.
        private const val MARK = "\u0000"
    }
}
