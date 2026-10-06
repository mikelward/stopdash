package app.stopdash.wear

import app.stopdash.data.WatchTrip
import java.time.Duration
import java.time.Instant

/**
 * The trip On the way follows, on the tile (dev-docs/wear-os.md *Trip on the way*): while the phone
 * keeps a trip updated, the tile shows it in the departures' place, as the phone's widget does,
 * rather than the departures near where the app was last opened, which go out of date on the way
 * (maintainer, 2026-10-06). Its trains are the watch app's ([trainRows]), so the two agree.
 */
internal object TileTrip {
    /** The most entries the trip takes, leaving the rest of the timeline to the departures after it. */
    private const val TRIP_ENTRIES = 62

    /**
     * [held]'s entries from [now] ([elapsedNow] on the watch's monotonic clock): one at each instant
     * its frame can change, until it isn't shown; from then, the departures [after] makes from that
     * instant, in the entries left. The tile also asks to be drawn again then
     * ([TileSchedule.refreshAt]), but a timeline that runs on to the departures doesn't depend on it:
     * the system may put a refresh off (Codex on #612). Null when no trip is shown.
     */
    fun schedule(
        held: HeldTrip?,
        now: Instant,
        elapsedNow: Long,
        screen: TileScreen?,
        after: (from: Instant, maxScheduled: Int) -> TileSchedule,
    ): TileSchedule? {
        held ?: return null
        WatchTripState.shown(held, now, elapsedNow) ?: return null
        val sinceArrival = Duration.ofMillis(elapsedNow - held.arrivedElapsed).coerceAtLeast(Duration.ZERO)
        val stamped = Duration.between(Instant.ofEpochMilli(held.trip.sentAt), now).coerceAtLeast(Duration.ZERO)
        val goneAt = now.plus(WatchTripState.GONE_AFTER.minus(sinceArrival))
        // Where it turns out of date: just past the boundary, since [WatchTripState.shown] marks it out
        // of date once its age is past [WatchTripState.STALE_AFTER], not at it (Codex on #612).
        val staleAt = now.plus(WatchTripState.STALE_AFTER.minus(maxOf(sinceArrival, stamped))).plusMillis(1)
        // Every instant the frame can change, as the departures' timeline makes them (Codex on #612):
        // each train's departure, when it drops off, and just after each whole minute before it, when
        // its count goes down (at exactly 2:00 left it still reads "2 min"); and where it turns out of date.
        val changes = sortedSetOf<Instant>()
        if (staleAt > now) changes += staleAt
        for (train in held.trip.departures) {
            val due = Instant.ofEpochMilli(train.dueAt)
            if (due <= now) continue
            changes += due
            // Only the ticks before it goes: a train due far ahead (a watch clock well behind the
            // phone's) starts at the first tick inside, never walks every minute down to now (Codex on #612).
            val beyond = Duration.between(goneAt, due.plusMillis(1))
            var k = if (beyond.isNegative) 1L else maxOf(1L, beyond.toMinutes() + 1)
            var tick = due.minusSeconds(60 * k).plusMillis(1)
            while (tick > now) {
                changes += tick
                k++
                tick = due.minusSeconds(60 * k).plusMillis(1)
            }
        }
        val within = changes.filter { it > now && it < goneAt }
        // Past the trip's share of the timeline, it hands over to the departures early rather than
        // hold a frame that's no longer right; a fresh render follows its next update anyway.
        val endAt = within.getOrNull(TRIP_ENTRIES - 1) ?: goneAt
        val instants = listOf(now) + within.filter { it < endAt }
        val entries = mutableListOf<TileEntry>()
        var start = now
        var current: TileFrame.Trip? = null
        for (instant in instants) {
            val elapsed = elapsedNow + Duration.between(now, instant).toMillis()
            val shown = WatchTripState.shown(held, instant, elapsed) ?: break
            val next = frame(shown.trip, instant, shown.stale, screen)
            if (next == current) continue
            current?.let { entries += TileEntry(start, instant, it) }
            start = instant
            current = next
        }
        current ?: return null
        entries += TileEntry(start, endAt, current)
        val departures = after(endAt, TileTimeline.MAX_SCHEDULED - entries.size)
        return TileSchedule(entries + departures.entries, refreshAt = endAt)
    }

    /** [trip] as the tile draws it at [at]: its trains, under their poles, as many as fit [screen]. */
    fun frame(trip: WatchTrip, at: Instant, stale: Boolean, screen: TileScreen?): TileFrame.Trip {
        // Its trains go with the step boarding them, and the steps on the way to it.
        val showsTrains = trip.departuresAt >= 0 && trip.current <= trip.departuresAt
        val note = trip.departuresNote.takeIf { showsTrains }.orEmpty()
        // The heading, the title (two lines at most), and the detail, note and out-of-date lines it has.
        val notes = 3 + listOf(trip.detail.isNotBlank(), note.isNotEmpty(), stale).count { it }
        val budget = TileTimeline.lineBudget(screen, notes)
        val lines = if (!showsTrains) {
            emptyList()
        } else {
            val all = trainRows(trip, at, stale).groupBy { it.stop }.flatMap { (stop, rows) ->
                listOfNotNull(stop.takeIf { it.isNotEmpty() }?.let { TileLine.Header(it) }) + rows.map { TileLine.Departure(it.row) }
            }
            val taken = all.take(budget).let { if (it.lastOrNull() is TileLine.Header) it.dropLast(1) else it }
            // Room for one line only: the soonest train, never a pole's header with none under it (Codex on #612).
            if (taken.any { it is TileLine.Departure }) taken else all.filterIsInstance<TileLine.Departure>().take(budget)
        }
        return TileFrame.Trip(trip.title, trip.detail, note, lines, stale)
    }
}
