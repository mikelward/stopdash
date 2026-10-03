package app.stopdash.wear

import app.stopdash.data.WatchEnvelope
import app.stopdash.data.toDomain
import app.stopdash.domain.Countdown
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.NoTimes
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.lineCode
import app.stopdash.ui.BudgetedRow
import app.stopdash.ui.BudgetedRows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.SortedSet
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * One departure line of the tile: a service's pill, where it's going, and its countdowns (or `?`).
 * [planned] is the work still to come on its line, for the calendar beside the countdown, on a row's
 * first line when nothing is under way (as the widget marks it); null otherwise.
 */
data class TileRow(
    val lineName: String,
    val lineId: String,
    val mode: String,
    val code: String,
    val label: String,
    val countdown: String,
    val starred: Boolean,
    val stale: Boolean,
    val planned: TilePlanned? = null,
    // A trip's train the rider can't be at the stop for: drawn muted, as the phone grays it.
    val muted: Boolean = false,
)

/** Work still to come on a line ([PlannedAlert]): its label and the day it starts, for a screen reader. */
data class TilePlanned(val label: String, val startsOn: LocalDate)

/** One line of the tile's list. */
sealed interface TileLine {
    /** A stop header, as the widget draws above a place's rows when there's more than one place;
     *  [spoken] keeps the direction or "towards" the short [text] drops, for a screen reader. */
    data class Header(val text: String, val spoken: String = text) : TileLine

    data class Departure(val row: TileRow) : TileLine

    /**
     * A disrupted line's status ("Severe Delays"), under its departures, or on its own beside the
     * pill ([alone]) when the line has no countdown to show (a suspension), as the widget draws it.
     * [row] carries the pill; its label and countdown are empty. [noTimes] is why a line [alone]
     * has no times when that's more than "no trains" (a National Rail line with no key, or no
     * board): drawn where the times would be, as the in-app card does; null otherwise, and null
     * once its stop's arrivals are stale or its latest refresh failed.
     */
    data class Disruption(
        val row: TileRow,
        val description: String,
        val alone: Boolean,
        val noTimes: NoTimes? = null,
    ) : TileLine

    /** A stop with no rows, listed when no stop has any: its name and the empty form it's owed. */
    data class EmptyStop(val stopName: String, val uncertain: Boolean) : TileLine

    /** Every row there is belongs to a mode hidden on the phone: said so, as on the widget, never
     *  "No departures". */
    data object OnlyHidden : TileLine
}

/** What the tile shows at one instant. */
sealed interface TileFrame {
    /** Nothing has arrived from the phone yet. */
    data object NeverSynced : TileFrame

    /** The phone sent no stops, and none failed or were left out. */
    data object NoStops : TileFrame

    /** The phone sent no stops because every one failed to load (or was left out): out of date,
     *  never "no stops". */
    data object NoneLoaded : TileFrame

    /**
     * The widget's lines, favorites first, under the widget's stop headers. [ageMinutes] is the
     * freshest stop's age; [stale] once every stop is past its boundary (or the timeline withholds
     * every countdown); [partial] when a stop failed to load or refresh, or is stale beside fresher
     * ones; [omitted] the stops left out of the envelope for size, which the tile says are on the phone.
     */
    data class Rows(
        val lines: List<TileLine>,
        val ageMinutes: Long,
        val stale: Boolean,
        val partial: Boolean,
        val omitted: Int = 0,
        /** A fresh row with a live countdown has a line with no current status check: the tile
         *  says disruptions couldn't be checked, as the widget and app do (SPEC D3). */
        val statusUnknown: Boolean = false,
    ) : TileFrame
}

/** The watch's screen, as the tile request reports it: what bounds how many lines fit. */
data class TileScreen(val heightDp: Int, val fontScale: Float)

/** A frame and the interval it's valid for; a null [end] means "until the next update". */
data class TileEntry(val start: Instant, val end: Instant?, val frame: TileFrame, val notice: RefreshNotice.Kind? = null)

/**
 * The tile's entries, and when to ask for a fresh set ([refreshAt], null when the entries already
 * run to the all-stale end): set when the entry cap cut the timeline short.
 */
data class TileSchedule(val entries: List<TileEntry>, val refreshAt: Instant?)

/**
 * The tile's staleness timeline (dev-docs/wear-os.md *Staleness on the watch*): the system swaps
 * entries at the right instants, so countdowns tick, departed services drop off and each stop turns
 * stale at its own boundary, with no polling and no network. Lines come from the widget's own code
 * ([DepartureRows.across], [DepartureRows.pinStarred], [BudgetedRows.select]) over the widget's own
 * inputs, so the tile shows what the widget would, stop headers included.
 */
object TileTimeline {
    /** The most lines the tile lists, headers included, on a screen with room for more. */
    const val MAX_LINES = 5

    /**
     * How many lines fit under the stamp and [notes] status lines (out of date, stops left out),
     * and above the Refresh chip when [refreshLine], on [screen], never more than [MAX_LINES] nor
     * fewer than one. The heights follow the tile's layout (24dp padding top and bottom, 12sp status
     * text, a pill row of 11sp text in 4dp padding beside 14sp text, 4dp between lines, the chip's
     * 12sp text in 4dp padding after a 4dp spacer), scaled by the font scale. Without a [screen],
     * five less the notes and the chip.
     */
    fun lineBudget(screen: TileScreen?, notes: Int, refreshLine: Boolean = false): Int {
        val chip = if (refreshLine) 1 else 0
        screen ?: return (MAX_LINES - notes - chip).coerceAtLeast(1)
        val scale = screen.fontScale.coerceAtLeast(1f)
        val statusDp = 12 * LINE_HEIGHT * scale
        val lineDp = maxOf(11 * LINE_HEIGHT * scale + 2 * 4 + 2, 14 * LINE_HEIGHT * scale) + 4
        val chipDp = if (refreshLine) statusDp + 2 * 4 + 4 else 0f
        val room = screen.heightDp - 2 * 24 - statusDp * (1 + notes) - chipDp
        return (room / lineDp).toInt().coerceIn(1, MAX_LINES)
    }

    /** A text line's height per sp of type size. */
    private const val LINE_HEIGHT = 1.2f

    /** Countdowns per row, as on the widget. */
    private const val MAX_TIMES = 3

    /** A [frame] budget with room for every row: the watch app's scrolling list. */
    const val UNBOUNDED = Int.MAX_VALUE

    /** The most entries a Tiles timeline takes. */
    const val MAX_ENTRIES = 100

    /** The most entries [schedule] makes: two fewer, kept for the splits [withNotice] may add. */
    const val MAX_SCHEDULED = MAX_ENTRIES - 2

    /**
     * The frame at [now]. [withhold] withholds every countdown, for the tail of a timeline the
     * entry cap cut short, so a frame held past its time never shows a departed service or a
     * frozen countdown as live. [budget] overrides the lines that fit [screen]: the watch app,
     * which scrolls, passes [UNBOUNDED] to list every row.
     */
    fun frame(
        envelope: WatchEnvelope?,
        now: Instant,
        topology: RouteTopology = RouteTopology.EMPTY,
        withhold: Boolean = false,
        screen: TileScreen? = null,
        budget: Int? = null,
    ): TileFrame {
        envelope ?: return TileFrame.NeverSynced
        if (envelope.stops.isEmpty()) {
            val incomplete = envelope.missingStopIds.isNotEmpty() || envelope.omittedStops > 0
            return if (incomplete) TileFrame.NoneLoaded else TileFrame.NoStops
        }
        val stops = envelope.stops.map { it.toDomain() }
        val starred = envelope.starred.mapTo(HashSet()) { it.toDomain() }
        val staleStop = stops.associate { it.stopId to (withhold || isStale(it, now)) }
        val arrivalsFresh = stops.associate { it.stopId to it.arrivalsFresh }
        val freshest = stops.maxOf { it.fetchedAt }
        val allStale = stops.all { staleStop.getValue(it.stopId) }
        val partial = envelope.missingStopIds.isNotEmpty() || stops.any { !it.arrivalsFresh } ||
            (!allStale && staleStop.values.any { it })
        // The status lines drawn above the list take room from it, as the widget's note does, and
        // so does the Refresh chip at the foot.
        val freshNote = allStale || partial
        val statusNote = if (freshNote) 0 else 1
        val baseNotes = (if (freshNote) 1 else 0) + (if (envelope.omittedStops > 0) 1 else 0)
        val budgetFor: (Int) -> Int = { notes -> budget ?: lineBudget(screen, notes, refreshLine = true) }
        // The disruptions still young enough to stand behind, as the widget marks them (SPEC D3);
        // none on a withheld tail, whose instant is past what the timeline was built for.
        val statuses = if (withhold) emptyMap() else envelope.liveLineStatuses(now)
        // Fresh rows ahead of stale ones (as the widget orders its cap), then favorites first.
        // A suspension's status row stays while its own check is live, past the stop's boundary.
        val ordered = DepartureRows.freshFirst(
            DepartureRows.across(stops, now, statuses, splitPlatforms = false, statusRowsWhenStale = true),
        ) { staleStop[it.stopId] == true }
        // Less the modes hidden from the near-me list, as the widget leaves them out.
        val shown = HiddenModes.rows(ordered, envelope.hiddenModes.toSet())
        val pinned = DepartureRows.pinStarred(shown, starred)
        // Judged on the rows drawn with a live countdown, as the widget judges it: a line that
        // didn't fit is never spoken for. Its note takes a line, so the rows are chosen again with
        // that line reserved and judged again; if the unchecked row was the one pushed out, the
        // note goes with it. Behind the out-of-date note it goes unsaid: one note at a time, the
        // stronger one first.
        fun uncheckedIn(selected: List<BudgetedRow>) = !withhold && !freshNote && selected.any { c ->
            c.groups.isNotEmpty() && staleStop[c.row.stopId] != true && !envelope.statusKnown(c.row.lineId, now)
        }
        var budget = budgetFor(baseNotes)
        var selected = BudgetedRows.select(pinned, budget, MAX_TIMES, topology)
        if (uncheckedIn(selected)) {
            budget = budgetFor(baseNotes + statusNote)
            selected = BudgetedRows.select(pinned, budget, MAX_TIMES, topology)
        }
        val statusUnknown = uncheckedIn(selected)
        val lines = buildList {
            for (chosen in selected) {
                chosen.header?.let { add(TileLine.Header(it.text, it.spoken)) }
                val row = chosen.row
                val stale = staleStop[row.stopId] == true
                val star = StarredRow.of(row) in starred
                val code = lineCode(row.lineName, row.mode, row.lineId)
                // The soonest work to come, when nothing's under way: a disruption's ⚠ leads instead.
                val planned = row.plannedAlerts.firstOrNull()?.takeIf { row.status == null }?.let { TilePlanned(it.label, it.startsOn) }
                for ((index, group) in chosen.groups.withIndex()) {
                    val label = DepartureLabels.destinationLabel(group.destination, row.directionKey) ?: "—"
                    val shown = if (group.branch != null) "$label/${group.branch}" else label
                    val countdown = if (stale) "?" else Countdown.mergedLabel(group.times, now)
                    val calendar = planned.takeIf { index == 0 }
                    add(TileLine.Departure(TileRow(row.lineName, row.lineId, row.mode, code, shown, countdown, star, stale, calendar)))
                }
                // Its line was counted in the budget ([BudgetedRows.select]), and is never the one dropped.
                row.status?.let { status ->
                    val pill = TileRow(row.lineName, row.lineId, row.mode, code, "", "", star, stale)
                    val alone = chosen.groups.isEmpty()
                    // The reason came with the stop's last fetch: once that's stale, or the latest
                    // refresh of the stop failed, it isn't vouched for, though a live check keeps
                    // the row (SPEC D4).
                    val noTimes = NoTimes.of(row).takeIf {
                        alone && !stale && arrivalsFresh[row.stopId] == true && it != NoTimes.NO_TRAINS
                    }
                    add(TileLine.Disruption(pill, status.description, alone, noTimes))
                }
            }
        }.ifEmpty {
            // Rows, but all of a hidden mode: the widget's "nothing else to show" state.
            if (shown.isEmpty() && ordered.isNotEmpty()) return@ifEmpty listOf(TileLine.OnlyHidden)
            // No rows anywhere: each stop with its own empty form, "No departures" or, for arrivals
            // carried from a failed refresh or past their boundary (an absence from expired data
            // isn't current), "may be out of date" (dev-docs/wear-os.md *Stops but no rows*).
            // One line per place, as the widget names it; uncertain if any of its stops is.
            stops.groupBy { it.stopName }.entries.take(budget).map { (name, place) ->
                TileLine.EmptyStop(name, uncertain = place.any { !it.arrivalsFresh || staleStop[it.stopId] == true })
            }
        }
        return TileFrame.Rows(
            lines,
            Duration.between(freshest, now).toMinutes().coerceAtLeast(0),
            allStale,
            partial,
            envelope.omittedStops,
            statusUnknown = statusUnknown,
        )
    }

    /** The entries from [now] (see [schedule]). */
    fun entries(envelope: WatchEnvelope?, now: Instant, topology: RouteTopology = RouteTopology.EMPTY): List<TileEntry> =
        schedule(envelope, now, topology).entries

    /**
     * The entries from [now]: a new one at each instant the frame can change — every countdown
     * minute, every departure, every stop's staleness boundary, every disruption's expiry, every
     * minute of the age stamp — up
     * to the moment every stop is stale, then one open-ended stale entry. A setup frame is a single
     * open-ended entry: no stop has a boundary.
     *
     * Past [MAX_SCHEDULED], the timeline stops at the first instant it can't fit: from there it holds
     * one open-ended frame with every countdown withheld, and [TileSchedule.refreshAt] asks for a
     * fresh set at that instant. Every break is generated only between [now] and the all-stale
     * horizon, so a stop fetched long ago costs nothing.
     */
    fun schedule(
        received: WatchEnvelope?,
        now: Instant,
        topology: RouteTopology = RouteTopology.EMPTY,
        screen: TileScreen? = null,
    ): TileSchedule {
        // A check or a stop dated after [now] (the clock moved back) is never trusted, and it stays
        // untrusted for the whole timeline: dropped or restamped stale here once, so a later frame
        // can't start showing it once its instant passes, with no break to take it away again.
        val envelope = received?.distrustingFuture(now)
        if (envelope == null || envelope.stops.isEmpty()) {
            return TileSchedule(listOf(TileEntry(now, null, frame(envelope, now, topology, screen = screen))), refreshAt = null)
        }
        val (breaks, horizon) = breaks(envelope, now)
        // A break opens an entry only where the frame actually changes: a departure's tick that
        // moves no shown countdown, or a row that can't make the five lines, spends nothing.
        val closed = mutableListOf<TileEntry>()
        var start = now
        var current = frame(envelope, now, topology, screen = screen)
        for ((evaluated, at) in breaks.withIndex()) {
            val next = if (evaluated < MAX_CANDIDATES) frame(envelope, at, topology, screen = screen) else null
            if (next == current) continue
            // Room is kept for the horizon's two entries; when a change doesn't fit (or the scan's
            // bound is reached), the timeline stops here with every countdown withheld.
            if (next == null || closed.size + 1 > MAX_SCHEDULED - 2) {
                closed += TileEntry(start, at, current)
                val tail = TileEntry(at, null, frame(envelope, at, topology, withhold = true, screen = screen))
                return TileSchedule(closed + tail, refreshAt = at)
            }
            closed += TileEntry(start, at, current)
            start = at
            current = next
        }
        if (horizon <= now) return TileSchedule(listOf(TileEntry(now, null, current)), refreshAt = null)
        closed += TileEntry(start, horizon, current)
        return TileSchedule(closed + TileEntry(horizon, null, frame(envelope, horizon, topology, screen = screen)), refreshAt = null)
    }

    /**
     * The instant after [now] at which the frame can next change (a countdown minute, a departure,
     * a stop's boundary, a disruption's expiry, a minute of the age stamp), or null once every stop is stale: what the
     * watch app's foreground ticker waits for. Rows past the list's reach may add instants that
     * change nothing; a re-render there is cheap and never wrong.
     */
    fun nextChange(envelope: WatchEnvelope?, now: Instant): Instant? {
        if (envelope == null || envelope.stops.isEmpty()) return null
        val (breaks, horizon) = breaks(envelope, now)
        return breaks.firstOrNull() ?: horizon.takeIf { it > now }
    }

    /** Every instant between [now] and the all-stale horizon at which a frame can change, and that horizon. */
    private fun breaks(envelope: WatchEnvelope, now: Instant): Pair<SortedSet<Instant>, Instant> {
        val stops = envelope.stops.map { it.toDomain() }
        val threshold = Staleness.THRESHOLD.toJavaDuration()
        // The last instant a frame can change: every stop stale and every line check expired, so
        // the open-ended frame after it marks no disruption a check no longer vouches for.
        val horizon = (stops.map { it.fetchedAt.plus(threshold) } + envelope.lineStatusExpiries(now)).max()
        val breaks = sortedSetOf<Instant>()
        fun add(at: Instant) {
            if (at > now && at < horizon) breaks += at
        }
        // A hidden mode's departures never show, so they mustn't spend the entry budget either.
        val hidden = envelope.hiddenModes.toSet()
        // A disruption's mark is withheld at its own check's boundary.
        envelope.lineStatusExpiries(now).forEach(::add)
        // Work starting while its check is live turns its calendar into the ⚠ at that midnight.
        envelope.plannedStarts(now).forEach(::add)
        for (stop in stops) {
            add(stop.fetchedAt.plus(threshold))
            // Each minute of this stop's age, from the first one after now.
            val sinceFetch = Duration.between(stop.fetchedAt, now)
            val firstMinute = if (sinceFetch.isNegative) 0L else sinceFetch.toMinutes() + 1
            var minute = stop.fetchedAt.plusSeconds(firstMinute * 60)
            while (minute < horizon) {
                add(minute)
                minute = minute.plusSeconds(60)
            }
            for (departure in stop.departures) {
                if (HiddenModes.isHidden(departure.mode, departure.lineId, hidden)) continue
                val arrival = departure.expectedArrival
                if (arrival <= now) continue
                // It drops off the moment it departs; before that, its minute count goes down just
                // after each whole minute left (at exactly 2:00 left it still reads "2 min"). Only
                // the ticks between now and the horizon are generated.
                add(arrival)
                val beyond = Duration.between(horizon, arrival.plusMillis(1))
                var k = if (beyond.isNegative) 1L else maxOf(1L, beyond.toMinutes() + 1)
                var tick = arrival.minusSeconds(60 * k).plusMillis(1)
                while (tick > now && tick >= stop.fetchedAt) {
                    add(tick)
                    k++
                    tick = arrival.minusSeconds(60 * k).plusMillis(1)
                }
            }
        }
        return breaks to horizon
    }

    /**
     * [entries] with [notices] shown in turn, each until its own `until` (as
     * [RefreshPolicy.notices] lists them), splitting the entries they change in. So a refresh's
     * "Refreshing…" turns to out of reach at its timeout, and a failure shows for a while over the
     * last snapshot and then goes, all without a re-render. It adds at most two entries, which
     * [MAX_SCHEDULED] leaves room for.
     */
    fun withNotice(entries: List<TileEntry>, notices: List<RefreshNotice>): List<TileEntry> {
        if (notices.isEmpty()) return entries
        fun kindAt(t: Instant) = notices.firstOrNull { t < it.until }?.kind
        return entries.flatMap { entry ->
            val end = entry.end
            val cuts = notices.map { it.until }.filter { it > entry.start && (end == null || it < end) }.distinct().sorted()
            (listOf(entry.start) + cuts).zip(cuts + listOf(end)) { start, until ->
                entry.copy(start = start, end = until, notice = kindAt(start))
            }
        }
    }

    /** A bound on the instants examined for a change, so a pathological snapshot can't stall a render. */
    private const val MAX_CANDIDATES = 2_000

    private fun isStale(stop: StopArrivals, now: Instant): Boolean =
        Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())
}
