package app.stopdash.wear

import androidx.annotation.WorkerThread
import app.stopdash.data.WatchEnvelope
import app.stopdash.data.toDomain
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.lineCode
import app.stopdash.domain.riderLineName
import java.time.Duration
import java.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a StopDash complication shows over one stretch of time. */
sealed interface ComplicationContent {
    /**
     * The row's next departure, at [at], to [destination], counted down by the system. [uncertain]
     * when its stop was carried forward after a failed refresh: the complication has no room for
     * the widget's separate note, so the mark sits on the time itself.
     */
    data class Departure(
        val code: String,
        val lineName: String,
        val destination: String,
        val at: Instant,
        val uncertain: Boolean,
        /** The line's disruption ("Severe Delays") while its check is young enough to show (SPEC D3). */
        val disruption: String? = null,
    ) : ComplicationContent

    /**
     * The row has no departures left before its stop's staleness boundary. Fresh ([uncertain]
     * false) when a fetch established there's nothing more; else the widget's "may be out of date".
     */
    data class Empty(
        val code: String,
        val lineName: String,
        val uncertain: Boolean,
        /** As [Departure.disruption]: a suspended line often has no departures, and says why. */
        val disruption: String? = null,
    ) : ComplicationContent

    /** Past its stop's staleness boundary: the line with no time, never an old countdown. */
    data class Stale(
        val code: String,
        val lineName: String,
        /** As [Departure.disruption]: a check can outlive the stop's times, and says so till it expires. */
        val disruption: String? = null,
    ) : ComplicationContent

    /** Nothing to show (never synced, no stops, no row at all): Wear's *no data* dash. */
    data object NoData : ComplicationContent
}

/** One entry of the complication's timeline, from [start] until [end] (open-ended when null). */
data class ComplicationEntry(val start: Instant, val end: Instant?, val content: ComplicationContent)

/**
 * The complication's timeline (dev-docs/wear-os.md *Staleness on the watch*): one entry per upcoming
 * departure of its row, each counted down by the system and replaced by the next when it leaves,
 * then the empty form if the row runs out before its stop's staleness boundary, then the stale form
 * from that boundary. Built from the envelope alone, so the watch face advances it with no polling
 * and no network. The row is the widget's top row, starred ones first (D8), as the tile orders them.
 */
object ComplicationTimeline {
    /**
     * The row a complication shows by default at [now]: the widget's first row as the tile orders
     * it (fresh before stale, starred first, hidden modes left out); else, when no row has any
     * departures, the first starred row the widget would still show: its stop was sent, the stop
     * still serves its line, and the line's mode isn't hidden (a star ranks, it doesn't add a
     * service). Null when there's none at all.
     */
    fun defaultRow(envelope: WatchEnvelope?, now: Instant): StarredRow? {
        envelope ?: return null
        val starred = envelope.starred.map { it.toDomain() }
        widgetRows(envelope, now).firstOrNull()?.let { return StarredRow.of(it) }
        return starred.firstOrNull { shows(envelope, it, now) }
    }

    /**
     * The service rows the widget shows at [now], in its order, as the tile orders them: fresh
     * stops' rows before stale ones', hidden modes left out, starred rows pinned first (D8). A
     * stop's disruption notice is a status line, not a service, so it's never a complication's row;
     * a disrupted line's status row is, as the line's own "⚠" with no departure time.
     */
    fun widgetRows(envelope: WatchEnvelope, now: Instant): List<DepartureRow> {
        val stops = envelope.stops.map { it.toDomain() }
        val stale = stops.associate { it.stopId to isStale(it, now) }
        // With the live line statuses, as the tile builds its rows: a suspended line with no
        // predictions is a row too (its status row), leading as a warning does, so a complication
        // on the default row shows the suspension rather than skip past it.
        val staleRow: (DepartureRow) -> Boolean = { stale[it.stopId] == true }
        val ordered = DepartureRows.freshFirst(
            DepartureRows.across(stops, now, envelope.liveLineStatuses(now), splitPlatforms = false, statusRowsWhenStale = true)
                .filter { it.stopDisruption == null },
            staleRow,
        )
        // A line several nearby stops serve is a row once, from the nearest, as the tile shows it.
        val shown = HiddenModes.rows(DepartureRows.glanceFolded(ordered, envelope.nearestFirst, envelope.foldChoices(), staleRow), envelope.hiddenModes.toSet())
        return DepartureRows.pinStarred(shown, envelope.starred.mapTo(HashSet()) { it.toDomain() })
    }

    /**
     * Whether the widget would still show [row] at [now]: its stop was sent, the stop still serves
     * its line, the line's mode isn't hidden, and the widget's terminating filter hasn't removed the
     * line's services here (a nearer stop serves the rider first). A line with no predictions right
     * now still counts: the row shows its empty form. A star or a pick never adds a service the
     * widget leaves out.
     */
    fun shows(envelope: WatchEnvelope, row: StarredRow, now: Instant): Boolean {
        val stop = envelope.stops.firstOrNull { it.stopId == row.stopId }?.toDomain() ?: return false
        return DepartureRows.shows(stop, row, envelope.hiddenModes.toSet(), now)
    }

    /**
     * [entries], worked out on [worker], never on the caller's thread: it folds and orders every
     * stop's rows (AGENTS.md *Main thread*), and the complication service is called on the main one.
     */
    suspend fun load(
        received: WatchEnvelope?,
        now: Instant,
        row: StarredRow? = null,
        topology: RouteTopology = RouteTopology.EMPTY,
        worker: CoroutineDispatcher = Dispatchers.Default,
    ): List<ComplicationEntry> = withContext(worker) { entries(received, now, row, topology) }

    /**
     * The timeline for [row] (the [defaultRow] when null, or when [row]'s stop isn't in the
     * envelope) from [now]. A single open-ended [ComplicationContent.NoData] entry when there's
     * nothing to show; otherwise always ending in the open-ended stale entry.
     */
    @WorkerThread
    fun entries(
        received: WatchEnvelope?,
        now: Instant,
        row: StarredRow? = null,
        topology: RouteTopology = RouteTopology.EMPTY,
    ): List<ComplicationEntry> {
        val noData = listOf(ComplicationEntry(now, null, ComplicationContent.NoData))
        // As the tile does: a check or a stop dated after [now] stays untrusted for the whole
        // timeline, including the hand-over rebuilt later in it.
        val envelope = received?.distrustingFuture(now) ?: return noData
        val timeline = timeline(envelope, now, row, topology) ?: return noData
        // Work starting while its check is still live is under way from that midnight (SPEC
        // *Disruptions*): its ⚠ shows, and a suspension's status row may lead, so the timeline is
        // built again from there.
        val start = envelope.plannedStarts(now).firstOrNull() ?: return timeline
        return until(timeline, start) + entries(envelope, start, row, topology)
    }

    /** [entries] from [now] for an [envelope] already trusted as of then; null when there's nothing to show. */
    private fun timeline(envelope: WatchEnvelope, now: Instant, row: StarredRow?, topology: RouteTopology): List<ComplicationEntry>? {
        // A pick the widget no longer shows (its stop gone, or its mode hidden on the phone) gives
        // way to the default row; the pick itself is kept, so un-hiding the mode brings it back.
        val picked = row?.takeIf { shows(envelope, it, now) }
        val chosen = picked ?: defaultRow(envelope, now) ?: return null
        val built = rowEntries(envelope, chosen, now, topology)
        if (picked != null) return built
        // A default row whose line is disrupted can owe its place to that check: as a suspension's
        // status alone, or once its last train has gone and only the status is left. So at the
        // check's expiry the timeline hands over to the default chosen then (the same row, rebuilt,
        // when it still leads), rather than keep an expired line with nothing to say.
        // Work only still to come puts no row first, so it gives no place to hand over from.
        if (envelope.liveLineStatuses(now)[chosen.lineId]?.allStatuses?.any(LineStatus::disrupted) != true) return built
        val expiry = statusExpiry(envelope, chosen.lineId)?.takeIf { it > now } ?: return built
        return until(built, expiry) + entries(envelope, expiry, null, topology)
    }

    /** [entries] cut off at [at]: the ones before it, the last ending there. */
    private fun until(entries: List<ComplicationEntry>, at: Instant): List<ComplicationEntry> =
        entries.mapNotNull { entry ->
            val end = entry.end
            when {
                entry.start >= at -> null
                end == null || end > at -> entry.copy(end = at)
                else -> entry
            }
        }

    /** When [lineId]'s check stops being shown (SPEC D3/D4), or null when it has none. */
    private fun statusExpiry(envelope: WatchEnvelope, lineId: String): Instant? =
        envelope.lineStatuses.firstOrNull { it.lineId == lineId }?.toDomain()?.checkedAt
            ?.plus(Staleness.THRESHOLD.toJavaDuration())

    /** [chosen]'s own timeline from [now], marked while its line's check is live. */
    private fun rowEntries(
        envelope: WatchEnvelope,
        chosen: StarredRow,
        now: Instant,
        topology: RouteTopology,
    ): List<ComplicationEntry> {
        val stop = envelope.stops.first { it.stopId == chosen.stopId }.toDomain()
        val boundary = stop.fetchedAt.plus(Staleness.THRESHOLD.toJavaDuration())
        val statuses = envelope.liveLineStatuses(now)
        val current = DepartureRows.across(listOf(stop), now, statuses, splitPlatforms = false).firstOrNull { StarredRow.of(it) == chosen }
        // The status for the way this row's services go, as the tile and the phone mark it (SPEC
        // *Disruptions*): none when only the other direction is disrupted. A row with nothing left
        // to run (or a stale stop) goes by the pick's own direction, the line-wide status when
        // that isn't a TfL direction.
        val status = if (current != null) current.status
        else statuses[chosen.lineId]?.forDirection(chosen.directionKey)?.takeIf { it.disrupted }
        val (tflName, mode) = current?.let { it.lineName to it.mode } ?: lineOf(stop, chosen)
        val code = lineCode(tflName, mode, chosen.lineId)
        // Said as the pill's code reads (LNR, not TfL's parent-company name).
        val lineName = riderLineName(tflName, mode)
        val stale = ComplicationContent.Stale(code, lineName)
        if (now >= boundary) return marked(listOf(ComplicationEntry(now, null, stale)), envelope, chosen.lineId, status)

        val uncertain = !stop.arrivalsFresh
        val entries = mutableListOf<ComplicationEntry>()
        var start = now
        for (departure in upcoming(current, now, boundary)) {
            // Labeled as the tile labels a destination line (D8): the short terminus, "—" when TfL
            // gives none, and the via-branch when it's a choice ahead ("Morden/Bank"), the cue a
            // rider picks the train by.
            val label = DepartureLabels.destinationLabel(departure.destination, chosen.directionKey) ?: "—"
            val branch = topology.grouping(chosen.lineId, stop.stopId, departure.destination, departure.branch).label
            val destination = if (branch != null) "$label/$branch" else label
            val content = ComplicationContent.Departure(code, lineName, destination, departure.expectedArrival, uncertain)
            entries += ComplicationEntry(start, departure.expectedArrival, content)
            start = departure.expectedArrival
        }
        if (start < boundary) entries += ComplicationEntry(start, boundary, ComplicationContent.Empty(code, lineName, uncertain))
        entries += ComplicationEntry(boundary, null, stale)
        return marked(entries, envelope, chosen.lineId, status)
    }

    /**
     * [entries] with the line's disruption on them while its check is live (SPEC D3/D4): an entry
     * spanning the check's expiry is split there, and from then on the mark is withheld, as on the
     * tile. Unchanged when the row has no live disruption ([status] null).
     */
    private fun marked(entries: List<ComplicationEntry>, envelope: WatchEnvelope, lineId: String, status: LineStatus?): List<ComplicationEntry> {
        status ?: return entries
        val expiry = envelope.lineStatuses.first { it.lineId == lineId }.toDomain().checkedAt
            .plus(Staleness.THRESHOLD.toJavaDuration())
        fun mark(content: ComplicationContent, on: Boolean): ComplicationContent {
            val label = status.description.takeIf { on }
            return when (content) {
                is ComplicationContent.Departure -> content.copy(disruption = label)
                is ComplicationContent.Empty -> content.copy(disruption = label)
                is ComplicationContent.Stale -> content.copy(disruption = label)
                ComplicationContent.NoData -> content
            }
        }
        return entries.flatMap { entry ->
            val end = entry.end
            when {
                entry.start >= expiry -> listOf(entry)
                end != null && end <= expiry -> listOf(entry.copy(content = mark(entry.content, true)))
                else -> listOf(
                    ComplicationEntry(entry.start, expiry, mark(entry.content, true)),
                    ComplicationEntry(expiry, end, entry.content),
                )
            }
        }
    }

    /**
     * [row]'s departures after [now] and before [boundary], soonest first. Uncapped: the staleness
     * window bounds them, and a cap would end a busy row early with a "None" it can't stand behind.
     */
    private fun upcoming(row: DepartureRow?, now: Instant, boundary: Instant) =
        row?.upcoming.orEmpty()
            .filter { it.expectedArrival > now && it.expectedArrival < boundary }
            .sortedBy { it.expectedArrival }
            .distinctBy { it.expectedArrival }

    /** The line's name and mode for a row with no departures now, from the stop's lines. */
    private fun lineOf(stop: StopArrivals, row: StarredRow): Pair<String, String> {
        stop.departures.firstOrNull { it.lineId == row.lineId }?.let { return it.lineName to it.mode }
        stop.lines.firstOrNull { it.id == row.lineId }?.let { return it.name to it.mode }
        return row.lineId to ""
    }

    private fun isStale(stop: StopArrivals, now: Instant): Boolean =
        Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())
}
