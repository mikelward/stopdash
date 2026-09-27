package app.stopdash.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.stopdash.MainActivity
import app.stopdash.R
import app.stopdash.shared.R as SharedR
import app.stopdash.StopdashDebugLog
import app.stopdash.data.HiddenModesSetting
import app.stopdash.data.DataStoreAlertsBehindStore
import app.stopdash.data.DataStoreDismissedAlertsStore
import app.stopdash.domain.Dismissals
import app.stopdash.data.DataStoreNearbySetStore
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.data.DataStoreStarredRowsStore
import app.stopdash.data.RouteTopologyStore
import app.stopdash.domain.Countdown
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.DestinationGroup
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.NoTimes
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.isStatusOnly
import app.stopdash.domain.RelativeTime
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StarredRowSet
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.abbreviateBranch
import app.stopdash.domain.lineCode
import app.stopdash.domain.riderLineName
import app.stopdash.ui.hiddenGroupsLabel
import app.stopdash.ui.BudgetedRow
import app.stopdash.ui.BudgetedRows
import app.stopdash.ui.LineCosts
import app.stopdash.ui.groupHeaderTitle
import app.stopdash.ui.PillColors
import app.stopdash.ui.pillColors
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The home-screen (and, where Android 16 QPR allows, lock-screen) widget. It renders the
 * **persisted** departures snapshot ([DataStoreSnapshotStore]) — the same last-good the app
 * writes and restores (SPEC *One widget, many surfaces*) — never the network: `provideGlance`
 * reads the snapshot once off the render path and renders from it, so the widget can't stall
 * on a fetch. The stamp keeps it honest when the data is old, and tapping opens the app.
 *
 * **Interim data source**: until Phase 2's user-chosen watched stops, the app feeds this the
 * last *nearby* set it fetched (see the save-only [WidgetSnapshotStore] wiring in
 * `MainActivity`), so the widget shows "the stops near where you last opened the app". Phase 2
 * replaces that with the watched stops; a live-refresh cadence for the widget is D5.
 */
class StopDashWidget : GlanceAppWidget() {
    // Size buckets, so the layout fits the cell it's given: two widths (a narrow widget shortens its
    // stamp rather than clip it, see WIDGET_COMPACT_WIDTH) by a ladder of heights (the line budget
    // grows with the height, see widgetRowsHeight). The host picks the largest bucket that fits.
    override val sizeMode = SizeMode.Responsive(
        WIDGET_BUCKET_WIDTHS.flatMap { w -> WIDGET_BUCKET_HEIGHTS.map { h -> DpSize(w, h) } }.toSet(),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Off the render path: read the persisted snapshot before composing. A read failure
        // degrades to the empty state (open-the-app prompt) rather than crashing the host —
        // but cancellation is rethrown (never swallowed, which would break structured
        // concurrency) and a real read failure is logged, sanitized, so it's not silent.
        val snapshot = try {
            // Pass the sanitized warn sink so a *corrupt* file is logged, not silently
            // dropped: the corruption handler consumes the CorruptionException and calls this
            // callback (the catch below only sees a read that throws all the way out, which a
            // handled corruption doesn't). from() uses the first caller's warn, so both this
            // and WidgetSnapshotStore wire it — whichever initializes the singleton first.
            DataStoreSnapshotStore.from(context, warn = ::logWidgetSnapshotWarning).load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget snapshot read failed: ${e::class.simpleName}")
            null
        }
        // The starred set (SPEC D8), read once off the render path like the snapshot, so the
        // widget pins a starred service to the top exactly as the in-app list does. A set this
        // build can't read (Unavailable) or a read failure degrades to "nothing pinned" — the
        // rows still render soonest-first — rather than crashing the host.
        val starred = try {
            when (val set = DataStoreStarredRowsStore.from(context, warn = ::logWidgetSnapshotWarning).starred().first()) {
                is StarredRowSet.Loaded -> set.starred
                StarredRowSet.Unavailable -> emptySet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget starred read failed: ${e::class.simpleName}")
            emptySet()
        }
        // The modes hidden from the near-me list (SPEC *Finding stops → Hiding a mode*), from the
        // same in-process setting the list uses, so the widget leaves out exactly what the list
        // does, even a change that failed to save and holds only until restart. Its read waits for
        // the stored set on a cold start and is bounded, so it can delay this render but never hang it.
        val hiddenModes = HiddenModesSetting.loaded()
        // The alerts the user dismissed in the app (SPEC *Disruptions*), read here rather than
        // stored with the snapshot, so the widget always follows the app's dismissed set as it is
        // now ([DeparturesSnapshot.withDismissals]). An unreadable set counts as none: the mark
        // shows rather than a warning being hidden (SPEC principle 2).
        val dismissals = try {
            DataStoreDismissedAlertsStore.from(context, warn = ::logWidgetSnapshotWarning).dismissals().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget dismissed read failed: ${e::class.simpleName}")
            Dismissals.NONE
        }
        // The app's verdicts that a bus alert lies wholly behind a stop ([DeparturesSnapshot.withAlertsBehind]),
        // which the widget, with no routes, can't reach itself. An unreadable set counts as none: the
        // alert flags rather than going unmarked (SPEC principle 2).
        val verdicts = try {
            DataStoreAlertsBehindStore.from(context, warn = ::logWidgetSnapshotWarning).verdicts().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget alerts-behind read failed: ${e::class.simpleName}")
            emptySet()
        }
        // Only the stops the app last found near the rider, so a place they've left isn't shown as
        // live while the new one's first fetch hasn't landed ([DeparturesSnapshot.scopedTo]). No set
        // stored yet (the app not opened since this shipped) shows the snapshot as it is.
        val (scoped, nearbyUnreadable) = scopedToNearby(context, snapshot)
        val shown = scoped?.withDismissals(dismissals)?.withAlertsBehind(verdicts)
        val now = Instant.now()
        // Arm the one-shot staleness-boundary redraw from the snapshot we're about to render, on
        // the render path itself: first add, host rebind, and the app's updateAll after a fetch
        // all go through here, so each arms the flip from the snapshot it just drew — and a host
        // with no widget never runs this, so a widgetless user is never scheduled for (SPEC D4).
        // A nearby set it couldn't read showed no stops; nothing else redraws the widget when the file
        // is readable again, so it looks again in a minute (sooner at a boundary), until it can.
        scheduleStalenessRedrawFor(context, shown, now, within = NEARBY_SET_RETRY.takeIf { nearbyUnreadable })
        // A widget render means a widget exists, so resume the opt-in live-refresh chain if the
        // setting is on and it isn't already running — the worker retires the chain when the last
        // widget is removed, and this restarts it after one is re-added (SPEC D5, Codex P1 on #56).
        // Wrapped: this is an optional scheduler-recovery step, so a settings/WorkManager I/O error
        // here must not fail the render — the snapshot is already loaded and must still paint
        // (SPEC jank-free UI, Codex P2 on #56).
        try {
            resumeWidgetRefreshIfEnabled(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget refresh resume failed: ${e::class.simpleName}")
        }
        // The bundled branch topology (shared, cached instance), so the widget merges/labels a
        // branching row exactly as the in-app card does.
        val topology = RouteTopologyStore.load(context)
        provideContent {
            // The line budget comes from this bucket's height and the system font scale, so the rows
            // never run past the cell's bottom edge. widgetModel is pure and cheap — no I/O on the render path.
            val size = LocalSize.current
            val fontScale = LocalContext.current.resources.configuration.fontScale
            val model = widgetModel(
                shown,
                now,
                starred,
                geometry = WidgetGeometry(size.width, size.height, fontScale),
                topology = topology,
                hiddenModes = hiddenModes,
            )
            WidgetContent(model, now, fontScale)
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        super.onDelete(context, glanceId)
        // When the last instance is removed, cancel the pending staleness redraw so a
        // widgetless user isn't left with a scheduled wake. Best-effort — the schedule path's
        // own installed-widget guard is the reliable backstop on the next save; here we catch
        // the removed-while-app-closed case. Cancellation rethrown; other failures logged.
        try {
            if (GlanceAppWidgetManager(context).getGlanceIds(StopDashWidget::class.java).isEmpty()) {
                cancelWidgetStalenessRedraw(context)
                // Also retire the opt-in live-refresh chain — a widgetless user isn't left with a
                // ~1/min fetch loop (Codex P1 on #56). The worker's own installed-widget guard is
                // the backstop; this catches the removed-while-app-closed case promptly.
                cancelWidgetRefresh(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget staleness redraw cancel on delete failed: ${e::class.simpleName}")
        }
    }
}

/**
 * The widget's render inputs, derived purely from the snapshot and the clock so the decision
 * of what to show is unit-testable without a Glance host. [rows] is line-budgeted so a long or
 * branching list can't push the widget past its cell (see [WidgetRowModel]); [stale] drives the
 * "tap to refresh" note (SPEC D4 — old data is marked, not passed off as live).
 */
internal data class WidgetModel(
    val hasData: Boolean,
    val stale: Boolean,
    val uncertain: Boolean,
    val stamp: String?,
    val rows: List<WidgetRowModel>,
    // Too short for the title row and even one departure line (the minimum size at a large font):
    // WidgetContent drops the title row for a single status line, so the next departure still fits
    // with its freshness said, rather than clipping one or the other.
    val compact: Boolean = false,
    // Not even one departure fits, compact or not (the minimum size at a very large font):
    // WidgetContent says so rather than show a departure missing its line or its destination.
    val tooSmall: Boolean = false,
    // The hidden modes' names ("Bus, Tram") when every departure left is one of theirs, so the
    // empty widget says they're hidden rather than that none are due; null otherwise.
    val onlyHidden: String? = null,
    // A fresh row shown whose line has no live status check (never checked, the lookup failed, or
    // the check aged out): the widget says so rather than let its countdown read as verified-clean
    // (SPEC D3), as the app's "Couldn't check for disruptions" banner does.
    val statusUnknown: Boolean = false,
)

/**
 * One rendered widget row: a [DepartureRow] and the exact per-(destination, branch) [groups] to
 * draw for it. The groups are chosen in [widgetModel], not by the renderer, so the fixed-height
 * widget's line budget is decided in one pure, tested place — a branching row that would overflow
 * shows only the groups that fit (line-exact), rather than expanding to an unbounded height (D8
 * parity within the budget the cell allows).
 */
internal data class WidgetRowModel(
    val row: DepartureRow,
    val groups: List<DestinationGroup>,
    // The stop header drawn above this row, set on the first row of each place group that shows one
    // (see [widgetModel]); null for every other row.
    val header: WidgetHeader? = null,
    // A status-only row's reason for no times ("No key", "No data"), where its countdown would be;
    // null otherwise, or once it isn't vouched for (see [widgetNoTimes]).
    val noTimes: NoTimes? = null,
    // For each of [groups], whether it's drawn on two lines — pill and countdown, then the
    // destination — because its countdown leaves too little of the cell's width for its destination
    // (see widgetRowStacked). Missing entries are one line.
    val stackedLines: List<Boolean> = emptyList(),
    // Drawn as its status alone (no [groups]): whether the pill and its reason for no times take a
    // line of their own above the status, because the status doesn't fit beside the pill (see
    // widgetStatusFits).
    val statusStacked: Boolean = false,
    // Drawn under the row's countdowns: whether its status fits beside the pill's column, lined up
    // with an unstacked destination; if not, it starts under the pill, at full width.
    val statusBeside: Boolean = true,
)

/**
 * The cell the widget is drawn in: the host's size bucket and the system font scale, from which
 * [widgetModel] works out each row's layout (see [widgetRowStacked]) and how many fit.
 */
internal data class WidgetGeometry(val width: Dp, val height: Dp, val fontScale: Float = 1f)

/**
 * A stop header above a group of widget rows — the same place name and qualifier the in-app list
 * shows ("King's Cross St. Pancras – Platform 1", via [groupHeaderTitle]), so the widget tells the
 * rider where to board. [spoken] is what TalkBack reads, keeping the direction the visible text drops.
 */
internal data class WidgetHeader(val text: String, val spoken: String)

internal fun widgetModel(
    snapshot: DeparturesSnapshot?,
    now: Instant,
    starred: Set<StarredRow> = emptySet(),
    maxLines: Int = 6,
    // The budget when the header's stale/partial note takes a line of its own (see WidgetContent),
    // so that note can't push the last departure off the bottom. Defaults to [maxLines].
    maxLinesWithNote: Int = maxLines,
    // The budget in the compact layout (no title row), used when the full layout fits no line.
    maxLinesCompact: Int = 1,
    // The cell, when known (the widget always passes it): the budgets are then its height in dp, and
    // each row costs what it takes, stacked or not, in place of the line budgets above.
    geometry: WidgetGeometry? = null,
    topology: RouteTopology = RouteTopology.EMPTY,
    // Modes hidden from the near-me list: left out here too, except on a journey's own rows.
    hiddenModes: Set<String> = emptySet(),
): WidgetModel {
    if (snapshot == null) {
        return WidgetModel(hasData = false, stale = false, uncertain = false, stamp = null, rows = emptyList())
    }
    val age = Staleness.age(snapshot.fetchedAt, now)
    val stale = Staleness.isStale(age)
    // Nothing to show but a stop the refresh couldn't get (the others pruned since): say the stops
    // may be out of date, not "open the app to load", so the failure stays explicit.
    val onlyMissing = WidgetModel(
        hasData = true,
        stale = stale,
        uncertain = true,
        stamp = "Updated ${RelativeTime.formatAge(age)}",
        rows = emptyList(),
    )
    if (snapshot.stops.isEmpty()) {
        return if (snapshot.missingStopIds.isNotEmpty()) {
            onlyMissing
        } else {
            WidgetModel(hasData = false, stale = false, uncertain = false, stamp = null, rows = emptyList())
        }
    }
    // Whether an empty list can be trusted as a real "no departures". Only when every stop is
    // fresh: a stop that wasn't refreshed (arrivalsFresh = false, carried from a partial
    // refresh) or is stale by its own age might have expired predictions, not a true absence —
    // so an overall-fresh snapshot can still be uncertain if one stop lagged (SPEC D4 /
    // principle 1). Mirrors MainScreen's emptyStateUncertain; drives the empty-state message,
    // not the stamp.
    // Judged on the stops it shows: a journey-only stop counts once its journey is worked out.
    val origins = snapshot.journeys.mapTo(HashSet()) { it.originId }
    val shownStops = snapshot.stops.filter { it.stopId !in snapshot.journeyOnlyStopIds || it.stopId in origins }
    // Nothing it can show yet (only journey origins whose journeys aren't worked out): no data, not
    // a trustworthy "no departures".
    if (shownStops.isEmpty()) {
        return if (snapshot.missingStopIds.isNotEmpty()) {
            onlyMissing
        } else {
            WidgetModel(hasData = false, stale = false, uncertain = false, stamp = null, rows = emptyList())
        }
    }
    // A stop the refresh asked for but couldn't get makes the rest incomplete, however fresh.
    val uncertain = stale || snapshot.missingStopIds.isNotEmpty() || shownStops.any {
        !it.arrivalsFresh || Staleness.isStale(it.fetchedAt, now)
    }
    // Order for the cap, matching the in-app list: rank fresh rows ahead of stale ones (so a
    // partial refresh doesn't spend every slot on `?`-withheld stale rows and drop a trustworthy
    // later one — stable, preserving `across`'s soonest-first order within each group), then pin
    // the user's starred services to the top (SPEC D8), so a starred service past the cap isn't
    // dropped. Warnings still lead (pinStarred keeps them above even a starred row). A line served
    // by several nearby stops shows once, from its nearest, as in the app (below).
    // One row per direction, not per platform: a split row costs a line and a header of the widget's
    // tight budget. A merged row names its platform in the header only when every train agrees on it.
    // With the line statuses still young enough to stand behind (SPEC D3/D4): a disrupted line's
    // rows carry its status, and a suspended line with no predictions gets a status row.
    val ordered = DepartureRows.freshFirst(
        DepartureRows.across(snapshot.stops, now, snapshot.liveLineStatuses(now), splitPlatforms = false, statusRowsWhenStale = true),
    ) { Staleness.isStale(it.fetchedAt, now) }
    // Bound the widget by total RENDERED lines, not outer rows: a branching (line, direction)
    // row expands to one line per destination/branch group, and the widget has a fixed height,
    // so a single multi-destination service must not push later services off the bottom. Fill
    // the line budget group-by-group across the ordered rows: each row contributes as many of
    // its groups as still fit, and once the budget is spent no more rows are added. This bounds
    // an oversized *first* row too (it shows at most `maxLines` groups) rather than exempting
    // it. Groups within a row keep destinationLines' soonest-first order and per-line time cap.
    val journeyRows = pinJourneys(ordered, snapshot, now)
    // A line two nearby stops both serve shows once, from the nearer, as the in-app list does, so it
    // doesn't take two of the widget's few slots (Codex P2 on #44). Folded before a journey's
    // departures are lifted out, so a journey from the nearer stop still drops the farther stop's
    // copy rather than leave it as the line's only row below the journey (Codex on #473). By the
    // nearest-first order the app saved: a snapshot with none (an older one) keeps every stop's rows.
    // The fold re-sorts, so fresh rows are ranked ahead of stale ones again.
    val ranks = snapshot.nearestFirstDistances()
    val folded = if (ranks.isEmpty()) {
        ordered
    } else {
        DepartureRows.freshFirst(DepartureRows.nearbyDeduped(ordered, ranks)) { Staleness.isStale(it.fetchedAt, now) }
    }
    val nearby = withoutJourneys(folded, snapshot)
    val shownNearby = HiddenModes.rows(nearby, hiddenModes)
    val pinned = journeyRows + DepartureRows.pinStarred(shownNearby, starred)
    val onlyHidden = hiddenModes.takeIf { pinned.isEmpty() && nearby.isNotEmpty() }?.let(::hiddenGroupsLabel)
    // The journeys stay a band of their own at the top: grouping by place runs within each band, so
    // another row at a journey's origin can't pull ahead of a later journey.
    val journeyBand = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DepartureRow, Boolean>())
        .apply { addAll(journeyRows) }
    fun grouped(rows: List<DepartureRow>): List<StopGroup> {
        val (band, rest) = rows.partition { it in journeyBand }
        return StopGrouping.groupByStop(band, warningsLead = false) + StopGrouping.groupByStop(rest, warningsLead = false)
    }
    // Whether a drawn row with a live countdown has a line with no current status check (a stale
    // row's countdown is withheld anyway): the widget then says it couldn't check for disruptions.
    fun unchecked(chosen: List<BudgetedRow>) = chosen.any { c ->
        c.groups.isNotEmpty() && !Staleness.isStale(c.row.fetchedAt, now) &&
            !snapshot.statusKnown(c.row.lineId, now)
    }
    // A stop's reason for no times is vouched for only while its last fetch is current.
    val currentStops = shownStops.filter {
        it.arrivalsFresh && !Staleness.isStale(it.fetchedAt, now)
    }.mapTo(HashSet()) { it.stopId }
    // Each destination line's layout, decided from its own countdown and the cell's width (see
    // widgetRowStacked), so the lines a tight budget drops can't change the ones it keeps; without a
    // geometry nothing stacks. The planned-work calendar sits before the first line's countdown.
    fun stacked(row: DepartureRow, line: DestinationGroup): Boolean {
        if (geometry == null) return false
        val countdown = if (Staleness.isStale(row.fetchedAt, now)) "?" else Countdown.mergedLabel(line.times, now)
        val calendar = row.status == null && row.plannedAlerts.isNotEmpty() &&
            DepartureRows.destinationLines(row, WIDGET_MAX_TIMES, topology).firstOrNull() == line
        return widgetRowStacked(listOf(countdown), calendar, geometry.width, geometry.fontScale)
    }
    // Whether a row's status, and the reason for no times it shows after it ("No key", see
    // widgetNoTimes), fits beside the pill's column; without a geometry it's taken to.
    fun statusFits(row: DepartureRow, reason: Boolean): Boolean {
        val status = row.status ?: return true
        if (geometry == null) return true
        val detail = if (reason) widgetNoTimes(row, current = row.stopId in currentStops)?.let { WIDGET_NO_TIMES_ESTIMATE } else null
        return widgetStatusFits(status.description, detail, geometry.width, geometry.fontScale)
    }
    // Drawn as its status alone, the status is all the row says, so it stacks rather than be cut:
    // the pill and the reason (where a countdown would sit), then the status at full width.
    val statusStackedRows = java.util.IdentityHashMap<DepartureRow, Boolean>()
    fun statusStacked(row: DepartureRow): Boolean = statusStackedRows.getOrPut(row) { !statusFits(row, reason = true) }
    // A row's cost in the budget's units: lines, or dp when the cell is known. A status under a row's
    // countdowns is a line of its own; drawn alone, it takes the pill's line too, two if stacked.
    val costs = geometry?.let { g ->
        val line = widgetLineHeight(g.fontScale, stacked = false)
        val stackedLine = widgetLineHeight(g.fontScale, stacked = true)
        LineCosts(
            header = line,
            line = { row, group -> if (stacked(row, group)) stackedLine else line },
            status = { row, alone -> if (alone && statusStacked(row)) stackedLine else line },
        )
    } ?: LineCosts.UNIT
    // The least one departure costs: less room than this shows none. Its least is what
    // BudgetedRows.select would have to draw: its first destination line with its status under it,
    // or its status alone.
    val oneLine = pinned.mapNotNull { BudgetedRows.least(it, WIDGET_MAX_TIMES, topology, costs) }.minOrNull() ?: 1
    fun layout(withNote: Boolean): Triple<Boolean, Int, List<BudgetedRow>> {
        // The same condition WidgetContent draws the note under (a stamp is always set here).
        val fullBudget = geometry?.let { widgetRowsHeight(it.height, it.fontScale, withNote = withNote) }
            ?: if (withNote) maxLinesWithNote else maxLines
        // No room for a line under the full header: the compact layout (no title row) makes more
        // room; if even that fits none, the widget is too small to show a whole departure.
        val compact = fullBudget < oneLine
        val budget = if (compact) {
            geometry?.let { widgetRowsHeight(it.height, it.fontScale, compact = true) } ?: maxLinesCompact
        } else {
            fullBudget
        }
        // The budgeted rows and their stop headers, chosen the way every glanceable surface chooses
        // them (the watch tile too): see [BudgetedRows.select].
        return Triple(compact, budget, BudgetedRows.select(pinned, budget, WIDGET_MAX_TIMES, topology, ::grouped, costs))
    }
    var (compact, budget, chosen) = layout(withNote = stale || uncertain)
    // The unchecked note is judged on the rows that fit, not every candidate, so it never speaks for
    // a line the user can't see. It takes a line of the budget (the compact layout puts it in place
    // of the stamp instead), so the rows are chosen again with room for it and judged once more: a
    // row the note's line pushed out takes its note with it.
    var statusUnknown = unchecked(chosen)
    if (statusUnknown && !stale && !uncertain && !compact) {
        val (c2, b2, again) = layout(withNote = true)
        compact = c2
        budget = b2
        chosen = again
        statusUnknown = unchecked(again)
    }
    // Only when there's a departure to fit: with none, the empty states ("No upcoming departures",
    // "may be out of date") are the honest message and fit any size.
    val tooSmall = budget < oneLine && pinned.isNotEmpty()
    val rows = chosen.map {
        WidgetRowModel(
            it.row,
            it.groups,
            it.header?.let { header -> WidgetHeader(header.text, header.spoken) },
            noTimes = if (it.groups.isEmpty()) widgetNoTimes(it.row, current = it.row.stopId in currentStops) else null,
            stackedLines = it.groups.map { group -> stacked(it.row, group) },
            statusStacked = it.groups.isEmpty() && statusStacked(it.row),
            statusBeside = it.groups.isEmpty() || statusFits(it.row, reason = false),
        )
    }
    return WidgetModel(
        hasData = true,
        stale = stale,
        uncertain = uncertain,
        statusUnknown = statusUnknown,
        stamp = "Updated ${RelativeTime.formatAge(age)}",
        rows = rows,
        compact = compact,
        tooSmall = tooSmall,
        onlyHidden = onlyHidden,
    )
}

/**
 * The starred journeys' departures at the top of the widget (SPEC *Journeys*): at each journey
 * origin, a row keeps only the departures the app found to call at the far end (by line,
 * destination and branch), fresh rows ahead of stale ones (as the rest of the list) and soonest
 * first within each. The widget can't load route data, so a destination the app hasn't seen yet
 * is left out until the app next works the journey out.
 */
internal fun pinJourneys(ordered: List<DepartureRow>, snapshot: DeparturesSnapshot, now: Instant): List<DepartureRow> {
    if (snapshot.journeys.isEmpty()) return emptyList()
    val calls = snapshot.journeys.groupBy({ it.originId }, { it.calls }).mapValues { (_, c) -> c.flatten().toSet() }
    return ordered.mapNotNull { row ->
        val at = calls[row.stopId] ?: return@mapNotNull null
        // A journey line's suspension with no predicted train is the journey's news: it stays, as
        // its status alone, rather than the journey reading as having no departure.
        if (journeyStatusRow(row, at)) return@mapNotNull row
        val calling = row.upcoming.filter { JourneyCall.of(it) in at }
        if (calling.isEmpty()) null else row.copy(upcoming = calling, destination = calling.first().destination)
    }.sortedWith(
        compareBy(
            // A status-only row has no countdown to go stale (only a live check reaches it), and leads.
            { if (!it.isStatusOnly && Staleness.isStale(it.fetchedAt, now)) 1 else 0 },
            { it.upcoming.firstOrNull()?.expectedArrival ?: Instant.MIN },
        ),
    )
}

/** Whether [row] is a status-only row for a line one of the journeys from its stop ([at]) rides. */
private fun journeyStatusRow(row: DepartureRow, at: Set<JourneyCall>) =
    row.isStatusOnly && at.any { it.lineId == row.lineId }

/**
 * [ordered] without what [pinJourneys] lifted out: a journey-only stop (not nearby) shows nothing
 * else, and at a nearby journey origin the journey's departures aren't shown twice.
 */
internal fun withoutJourneys(ordered: List<DepartureRow>, snapshot: DeparturesSnapshot): List<DepartureRow> {
    val calls = snapshot.journeys.groupBy({ it.originId }, { it.calls }).mapValues { (_, c) -> c.flatten().toSet() }
    return ordered.mapNotNull { row ->
        if (row.stopId in snapshot.journeyOnlyStopIds) return@mapNotNull null
        val at = calls[row.stopId] ?: return@mapNotNull row
        // Lifted into the journey band by [pinJourneys]; not shown twice.
        if (journeyStatusRow(row, at)) return@mapNotNull null
        if (row.upcoming.isEmpty()) return@mapNotNull row
        val rest = row.upcoming.filterNot { JourneyCall.of(it) in at }
        if (rest.isEmpty()) null else row.copy(upcoming = rest, destination = rest.first().destination)
    }
}

@androidx.compose.runtime.Composable
internal fun WidgetContent(
    model: WidgetModel,
    now: Instant,
    // The system font scale, read once by the caller (the host context in provideGlance), so the
    // pills size to it without each reading a context the unit-test harness doesn't provide.
    fontScale: Float = 1f,
) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            // The stamp reflects the *freshest* stop, so on a partial refresh (one stop fresh,
            // another carried `arrivalsFresh = false` but not yet age-stale) a bare "Updated just
            // now" would present the whole list as freshly refreshed while a carried row still
            // shows a live countdown. Surface `uncertain` — the same flag the empty state uses —
            // so one fresh stop can't mask the others (SPEC D4); a whole-snapshot-stale widget
            // gets the stronger "tap to refresh". Its own line, only when needed, so the header
            // row stays short enough for the narrowest widget.
            val note = when {
                model.stamp == null -> null
                model.stale -> "Tap to refresh"
                model.uncertain -> "Some stops out of date"
                model.statusUnknown -> LocalContext.current.getString(R.string.disruptions_unknown)
                else -> null
            }
            // A narrow widget has no room for "Updated 14 min ago" beside the title, so it drops the
            // prefix; the age alone still reads as the update time.
            val stamp = model.stamp?.let {
                if (LocalSize.current.width < WIDGET_COMPACT_WIDTH) it.removePrefix("Updated ") else it
            }
            if (model.compact) {
                // Compact: no title row, just one status line — the warning when there is one (the
                // stronger claim; a stale row's own countdown is already withheld as "?"), else the
                // age — so the one departure below still fits (SPEC D4).
                // Short forms, so the warning stays readable at the large font that forced compact.
                val status = when {
                    model.stamp == null -> null
                    model.stale -> "Tap to refresh"
                    model.uncertain -> "Partly stale"
                    model.statusUnknown -> LocalContext.current.getString(R.string.disruptions_unknown)
                    else -> stamp
                }
                status?.let { WidgetStatusLine(it) }
                Spacer(GlanceModifier.height(4.dp))
            } else {
                WidgetHeaderRow(stamp)
                note?.let { WidgetStatusLine(it) }
                Spacer(GlanceModifier.height(8.dp))
            }
            when {
                !model.hasData ->
                    WidgetMessage("Open LDN Go to load departures")
                // Not even one whole departure fits at this size and font: say how to fix it, rather
                // than show a departure without its line or destination, or claim there are none.
                model.tooSmall ->
                    WidgetMessage("Too small")
                // Has a snapshot but no rows to show — every service has departed or the
                // stops returned none. Distinguish a trustworthy "none" from data too old to
                // assert that (SPEC D4), rather than leaving the widget blank below the header.
                // Every departure left is of a mode the user hid: say so, not that none are due.
                model.rows.isEmpty() && model.onlyHidden != null ->
                    WidgetMessage("Nothing else to show with ${model.onlyHidden} hidden")
                model.rows.isEmpty() ->
                    WidgetMessage(
                        if (model.uncertain) "Departures may be out of date" else "No upcoming departures",
                    )
                else ->
                    model.rows.forEachIndexed { index, rowModel ->
                        rowModel.header?.let { header ->
                            // Extra space above every header but the first marks the break between
                            // places, as in the in-app list; 4dp ties the header to its rows.
                            if (index > 0) Spacer(GlanceModifier.height(4.dp))
                            WidgetStopHeader(header)
                            Spacer(GlanceModifier.height(4.dp))
                        }
                        WidgetRow(rowModel, now, fontScale)
                        Spacer(GlanceModifier.height(8.dp))
                    }
            }
        }
    }
}

/**
 * The title and the stamp on one row — the stamp top-right, as in the app's top bar — so the
 * departures start a line higher in the widget's tight height.
 */
@androidx.compose.runtime.Composable
private fun WidgetHeaderRow(stamp: String?) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title takes the row's slack and is the one that gives way (clipping) when space runs
        // out — a large system font at the narrowest size — so the stamp, which carries the
        // freshness the widget must never hide (SPEC D4), always keeps its full width.
        Text(
            text = "LDN Go",
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            ),
        )
        Spacer(GlanceModifier.width(8.dp))
        stamp?.let {
            Text(
                text = it,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

/** A small freshness line: the stale/partial note under the header, or the compact layout's status. */
@androidx.compose.runtime.Composable
private fun WidgetStatusLine(text: String) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
    )
}

/** A stop header above its place's rows, on one line. A long one clips at the end: Glance can't
 *  measure text, so it can't reserve the qualifier the way the in-app header does. */
@androidx.compose.runtime.Composable
private fun WidgetStopHeader(header: WidgetHeader) {
    Text(
        text = header.text,
        maxLines = 1,
        modifier = GlanceModifier.semantics { contentDescription = header.spoken },
        style = TextStyle(
            color = GlanceTheme.colors.onBackground,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        ),
    )
}

/** A single-line message row (empty / prompt states), styled like the stamp. */
@androidx.compose.runtime.Composable
private fun WidgetMessage(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
}

/**
 * The label the widget shows for a destination [group] of [row]: the resolved destination (or
 * the direction-key cue when TfL gives no destination), with the via-branch joined with a slash
 * in the board's short form ("Edgware/Charing X") when the row branches. The branch
 * is [abbreviateBranch]'d unconditionally here — the compact widget can't measure available width
 * the way the in-app card does, so it shows the short form rather than risk clipping the full
 * one; a branch with no shortenable word comes through unchanged. Combined into one string so
 * the countdown beside it stays column-aligned.
 */
internal fun widgetLineLabel(row: DepartureRow, group: DestinationGroup): String {
    val base = DepartureLabels.destinationLabel(group.destination, row.directionKey) ?: "—"
    val branch = group.branch ?: return base
    return "$base/${abbreviateBranch(branch)}"
}

/**
 * What a screen reader hears for [widgetLineLabel] when its branch is shortened — the full branch,
 * "Hainault via Newbury Park" for "Hainault/Newbury Pk" — so the full name stays the accessible
 * label (SPEC destination-label); null when the label already shows it in full.
 */
internal fun widgetLineSpoken(row: DepartureRow, group: DestinationGroup): String? {
    val branch = group.branch ?: return null
    if (abbreviateBranch(branch) == branch) return null
    val base = DepartureLabels.destinationLabel(group.destination, row.directionKey) ?: return null
    return "$base via $branch"
}

@androidx.compose.runtime.Composable
private fun WidgetRow(rowModel: WidgetRowModel, now: Instant, fontScale: Float) {
    val row = rowModel.row
    // Withhold this row's countdown once ITS stop is stale (per-row, from the row's own fetch
    // age — a fresh stop beside a stale one stays live), so old predictions aren't shown as
    // live-looking numbers (SPEC D4). "?" means "unknown", matching the in-app card.
    val stale = Staleness.isStale(row.fetchedAt, now)
    // A branching (service, direction) row keeps each destination — and each via-branch of one
    // terminus — on its own line with its own countdown, so a divergent train's time never sits
    // under the wrong destination or branch (SPEC D8). The groups were chosen (and line-budgeted)
    // in widgetModel from the shared destinationLines, so the card and widget can't drift.
    // Every line carries its own pill, as every row in the in-app card does, so a branch's second
    // destination never reads as belonging to a different (pill-less) service.
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        // A disrupted line with no countdown to show (its status row): the pill and the status on
        // one line, so the disruption isn't left out for want of a departure (SPEC D3).
        if (rowModel.groups.isEmpty()) {
            row.status?.let { status ->
                // Why a National Rail line has no times (SPEC *National Rail*): on one line it
                // follows the status in the same text, "⚠ Part Suspended · No key", so the
                // disruption always leads and a tight line cuts the reason, never the alert.
                val detail = rowModel.noTimes?.let { widgetNoTimesText(it) }
                if (rowModel.statusStacked) {
                    // Too narrow for the pill and the status on one line: the pill and the reason
                    // (where a countdown would sit), then the status below at full width, as a
                    // stacked departure puts its destination (the budget counts both lines).
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WidgetPill(row, fontScale)
                        Spacer(GlanceModifier.defaultWeight())
                        detail?.let { WidgetCountdown(it, stale = false) }
                    }
                    Spacer(GlanceModifier.height(WIDGET_STACK_GAP))
                    WidgetDisruption(status.description, GlanceModifier.fillMaxWidth())
                } else {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WidgetPill(row, fontScale)
                        Spacer(GlanceModifier.width(8.dp))
                        WidgetDisruption(status.description, GlanceModifier.defaultWeight(), detail)
                    }
                }
            }
            return@Column
        }
        // Work still to come on the line, when nothing is under way: the app row's calendar, before
        // the first countdown, in place of the ⚠ line a disruption gets (SPEC *Disruptions*). The
        // soonest, as the app marks it; it takes no line of the budget.
        val planned = row.plannedAlerts.firstOrNull()?.takeIf { row.status == null }
        rowModel.groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(GlanceModifier.height(4.dp))
            val label = widgetLineLabel(row, group)
            val spoken = widgetLineSpoken(row, group)
            val countdown = if (stale) "?" else Countdown.mergedLabel(group.times, now)
            val calendar = planned?.takeIf { index == 0 }
            if (rowModel.stackedLines.getOrElse(index) { false }) {
                // Too narrow at this font for all three on one line: pill and countdown, then the
                // destination below, so none of the three is squeezed out (see widgetRowStacked).
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WidgetPill(row, fontScale)
                    Spacer(GlanceModifier.defaultWeight())
                    calendar?.let { WidgetPlanned(it) }
                    WidgetCountdown(countdown, stale)
                }
                Spacer(GlanceModifier.height(WIDGET_STACK_GAP))
                WidgetDestination(label, spoken, GlanceModifier.fillMaxWidth())
            } else {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WidgetPill(row, fontScale)
                    Spacer(GlanceModifier.width(8.dp))
                    WidgetDestination(label, spoken, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    calendar?.let { WidgetPlanned(it) }
                    WidgetCountdown(countdown, stale)
                }
            }
        }
        // The line's disruption under its countdowns, so they aren't read as a normal service
        // (SPEC D3). Its line was counted in the budget ([BudgetedRows.select]).
        // A stacked line puts its destination under the pill, so the status lines up with it there;
        // otherwise it's indented to the destination column (the pill's width plus the 8dp gap),
        // so it reads as part of this service rather than a row of its own — unless it doesn't fit
        // there, when it starts under the pill rather than be cut (see widgetStatusFits).
        row.status?.let { status ->
            Spacer(GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                // Lined up with the destination of the line above it.
                if (rowModel.statusBeside && !rowModel.stackedLines.getOrElse(rowModel.groups.lastIndex) { false }) {
                    Spacer(GlanceModifier.width(widgetPillWidth(fontScale) + 8.dp))
                }
                WidgetDisruption(status.description, GlanceModifier.defaultWeight())
            }
        }
    }
}

/**
 * Why a status-only row has no times, when that's more than "no trains": the in-app card's "No
 * key" or "No data" for a National Rail line ([NoTimes]); null otherwise. Null too unless its
 * stop's last fetch is [current] (fresh, and the latest refresh of it succeeded): the reason came
 * with those arrivals, and a live check can keep the row past them (the user may have added a key
 * since), so it's no longer vouched for (SPEC D4).
 */
internal fun widgetNoTimes(row: DepartureRow, current: Boolean): NoTimes? =
    NoTimes.of(row).takeIf { current && it != NoTimes.NO_TRAINS }

@androidx.compose.runtime.Composable
private fun widgetNoTimesText(noTimes: NoTimes): String = LocalContext.current.getString(
    when (noTimes) {
        NoTimes.NO_KEY -> R.string.status_no_rail_key
        NoTimes.NO_DATA, NoTimes.NO_TRAINS -> R.string.status_no_data
    },
)

/**
 * A disrupted line's status, "⚠ Severe Delays", in the error color, as the in-app card marks it;
 * [detail] ("No key") follows it after a dot, so it's the part a tight line cuts.
 */
@androidx.compose.runtime.Composable
private fun WidgetDisruption(description: String, modifier: GlanceModifier, detail: String? = null) {
    Text(
        text = if (detail == null) "⚠ $description" else "⚠ $description · $detail",
        maxLines = 1,
        modifier = modifier.semantics {
            contentDescription = if (detail == null) "Disrupted: $description" else "Disrupted: $description. $detail"
        },
        style = TextStyle(color = GlanceTheme.colors.error, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    )
}

/**
 * Planned work still to come on a row's line: the app row's muted calendar, the countdown's height,
 * then the gap the app leaves before the times; TalkBack hears what's planned and from when, as in
 * the app ("Planned Part Closure from 13 Oct").
 */
@androidx.compose.runtime.Composable
private fun WidgetPlanned(alert: PlannedAlert) {
    val context = LocalContext.current
    val date = alert.startsOn.format(DateTimeFormatter.ofPattern("d MMM", context.resources.configuration.locales[0]))
    Image(
        provider = ImageProvider(SharedR.drawable.ic_calendar),
        contentDescription = context.getString(R.string.planned_alert_description, alert.label, date),
        modifier = GlanceModifier.size(WIDGET_PLANNED_SIZE),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
    )
    Spacer(GlanceModifier.width(8.dp))
}

/** The calendar's size: the 13sp countdown's line height, on the 4dp grid. */
private val WIDGET_PLANNED_SIZE = 16.dp

/**
 * A departure line's destination (and via-branch) label, one line; [spoken], when given, is what a
 * screen reader hears instead.
 */
@androidx.compose.runtime.Composable
private fun WidgetDestination(label: String, spoken: String?, modifier: GlanceModifier) {
    Text(
        text = label,
        maxLines = 1,
        modifier = spoken?.let { modifier.semantics { contentDescription = it } } ?: modifier,
        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp),
    )
}

/** A departure line's countdown, or "?" when its stop is too stale to show one (SPEC D4). */
@androidx.compose.runtime.Composable
private fun WidgetCountdown(text: String, stale: Boolean) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(
            color = if (stale) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        ),
    )
}

/** A pill's whole width at [fontScale], every pill's: its label slot (see [WidgetPill]) plus 8dp each side. */
private fun widgetPillWidth(fontScale: Float) =
    WIDGET_PILL_LABEL_WIDTH * fontScale.coerceAtMost(WIDGET_PILL_MAX_SCALE) + 16.dp

/** The line pill — short code visible, full line name to TalkBack, fixed-width so a column of
 *  pills and the labels beside them line up (SPEC fixed-width pill invariant). */
@androidx.compose.runtime.Composable
private fun WidgetPill(row: DepartureRow, fontScale: Float) {
    // The label and its fixed-width slot grow together with the system [fontScale], up to
    // WIDGET_PILL_MAX_SCALE; past that both hold (the sp size is divided back down), so the widest
    // code always fits the slot whole and a very large font can't grow the pill until it crowds out
    // the countdown. TalkBack still reads the full line name.
    val pillScale = fontScale.coerceAtMost(WIDGET_PILL_MAX_SCALE)
    // The visible label is the short code; the accessible label is the full line name, so TalkBack
    // announces "Victoria", not "VIC" (SPEC parity with the app).
    val name = riderLineName(row.lineName, row.mode)
    val code = lineCode(row.lineName, row.mode, row.lineId)
    when (val style = widgetPillStyle(row.lineName, row.lineId, row.mode)) {
        is WidgetPillStyle.Hollow -> {
            // Glance draws no border, so the ring is the accent behind a box of the background inset
            // by the ring's width. Every pill takes the one fixed width ([widgetPillWidth]), so the
            // ring's insets, rounded to pixels differently from a solid pill's padding, can't make
            // it a pixel narrower; the inner box fills it and centers the label.
            Box(
                modifier = GlanceModifier
                    .width(widgetPillWidth(fontScale))
                    .background(DayNightColor(day = style.day.border, night = style.night.border))
                    .cornerRadius(WIDGET_PILL_CORNER)
                    .padding(WIDGET_PILL_RING)
                    .semantics { contentDescription = name },
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(GlanceTheme.colors.background)
                        .cornerRadius(WIDGET_PILL_CORNER - WIDGET_PILL_RING)
                        .padding(vertical = 4.dp - WIDGET_PILL_RING),
                    contentAlignment = Alignment.Center,
                ) {
                    WidgetPillLabel(code, DayNightColor(day = style.day.label, night = style.night.label), pillScale, fontScale)
                }
            }
        }
        else -> {
            val solid = style as? WidgetPillStyle.Solid
            Box(
                modifier = GlanceModifier
                    .width(widgetPillWidth(fontScale))
                    .background(if (solid != null) ColorProvider(solid.fill) else GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(WIDGET_PILL_CORNER)
                    .padding(vertical = 4.dp)
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                WidgetPillLabel(
                    code,
                    if (solid != null) ColorProvider(solid.label) else GlanceTheme.colors.onSurfaceVariant,
                    pillScale,
                    fontScale,
                )
            }
        }
    }
}

/** How the widget draws a line's pill ([widgetPillStyle]). */
internal sealed interface WidgetPillStyle {
    /** An official line, mode or rail-operator color, with its APCA black-or-white [label]. */
    data class Solid(val fill: Color, val label: Color) : WidgetPillStyle

    /** A named Overground line: the widget's background shows through, the accent the ring and
     *  the label, nudged to stay legible on it in the light theme ([day]) and the dark ([night]). */
    data class Hollow(val day: PillColors.Hollow, val night: PillColors.Hollow) : WidgetPillStyle

    /** No confirmed color: the theme's neutral pill. */
    data object Neutral : WidgetPillStyle
}

/**
 * The widget's pill for a line, from the resolver the app and the watch draw with (SPEC *Line pill
 * colors*), against the widget's background in each theme: a named Overground line hollow, as in
 * the app, rather than a solid fill that would read as a tube line.
 */
internal fun widgetPillStyle(lineName: String, lineId: String, mode: String): WidgetPillStyle =
    when (val day = pillColors(lineName, lineId, mode, WIDGET_SURFACE_DAY)) {
        is PillColors.Hollow -> WidgetPillStyle.Hollow(day, pillColors(lineName, lineId, mode, WIDGET_SURFACE_NIGHT) as PillColors.Hollow)
        is PillColors.Solid -> WidgetPillStyle.Solid(day.fill, day.label)
        PillColors.Neutral -> WidgetPillStyle.Neutral
    }

@androidx.compose.runtime.Composable
private fun WidgetPillLabel(code: String, color: ColorProvider, pillScale: Float, fontScale: Float) {
    Text(
        text = code,
        maxLines = 1,
        modifier = GlanceModifier.width(WIDGET_PILL_LABEL_WIDTH * pillScale),
        style = TextStyle(
            color = color,
            fontWeight = FontWeight.Bold,
            // 12sp at scales up to the cap; beyond it, held at the cap's size.
            fontSize = (12f * pillScale / fontScale).sp,
            textAlign = TextAlign.Center,
        ),
    )
}

private val WIDGET_PILL_CORNER = 6.dp

// The hollow pill's ring, a stroke rather than spacing: a touch heavier than the app's 1.5dp outline,
// for the widget's smaller pill.
private val WIDGET_PILL_RING = 2.dp

/**
 * What the widget's background is taken to be, in each theme, for nudging a hollow pill's accent
 * to stay legible on it ([app.stopdash.ui.accentInkOn]). The background itself is the host's
 * dynamic color, known only when it draws: a near-white neutral in the light theme and a near-black
 * one in the dark, which these stand for (Material's baseline background).
 */
internal val WIDGET_SURFACE_DAY = Color(0xFFFFFBFE)
internal val WIDGET_SURFACE_NIGHT = Color(0xFF1C1B1F)

/**
 * Sanitized log sink for the widget's snapshot read — a discarded corrupt file, or a read
 * that threw. Class name / a fixed reason only, never a stop id or coordinate (SPEC Privacy).
 * A top-level function so both [StopDashWidget.provideGlance] and [WidgetSnapshotStore] can
 * wire it into `DataStoreSnapshotStore.from`, which keeps the first caller's sink.
 */
/**
 * [snapshot] for the nearby set the app last stored ([DataStoreNearbySetStore]), read off the render
 * path, and whether that read failed. A set that can't be read shows no nearby stops rather than the
 * whole snapshot, which may be a place the rider has left; a journey pinned keeps its rows.
 */
internal suspend fun scopedToNearby(context: Context, snapshot: DeparturesSnapshot?): Pair<DeparturesSnapshot?, Boolean> {
    val read = DataStoreNearbySetStore.from(context, warn = ::logWidgetSnapshotWarning).read()
    val scoped = snapshot?.let { s -> read.stopIds?.let(s::scopedTo) ?: s }
    return scoped to read.failed
}

internal fun logWidgetSnapshotWarning(message: String) = StopdashDebugLog.warning("widget: %s", message)

/**
 * The widget line pill's fixed label width — every pill is the same size down the column so
 * destinations and countdowns line up (the SPEC fixed-width pill invariant, matching the in-app
 * [app.stopdash.ui.LinePill]). Sized to the widest code shown — a four-character bus route
 * (`N550`, `SL10`) — so nothing truncates; a shorter code centers with more room. At font scale 1;
 * [WidgetPill] scales it and the label together with the system font, up to [WIDGET_PILL_MAX_SCALE].
 */
private val WIDGET_PILL_LABEL_WIDTH = 48.dp

/**
 * How far the pill (its label and slot together) grows with the font scale, so the narrowest
 * widget still has room for the countdown at a very large font.
 */
private const val WIDGET_PILL_MAX_SCALE = 1.15f

/** The provider's `minWidth` x `minHeight` (`stopdash_widget_info.xml`) — the smallest size. */
private val WIDGET_MIN_WIDTH = 180.dp
private val WIDGET_MIN_HEIGHT = 110.dp

/**
 * The height in dp left for departures in a widget [height] tall at the system [fontScale]: all of
 * it but the chrome (padding, the title row, the space under it, and the stale/partial note line
 * when [withNote]). The title row grows with [fontScale], the fixed padding and gaps don't. Rounded
 * down, so the rows err toward one fewer rather than one clipped off the bottom; [widgetModel] fills
 * it with rows at what each takes ([widgetLineHeight]), and when not even one fits under the full
 * header it switches to the compact layout (see [WidgetModel.compact]), whose room this is with
 * [compact] set.
 */
internal fun widgetRowsHeight(
    height: Dp,
    fontScale: Float = 1f,
    withNote: Boolean = false,
    // The compact layout's chrome: no title row, just the one status line.
    compact: Boolean = false,
): Int {
    val chrome = if (compact) {
        WIDGET_COMPACT_CHROME + WIDGET_NOTE_TEXT_HEIGHT * fontScale
    } else {
        WIDGET_FIXED_CHROME +
            (WIDGET_TITLE_TEXT_HEIGHT + (if (withNote) WIDGET_NOTE_TEXT_HEIGHT else 0.dp)) * fontScale
    }
    return (height - chrome).value.toInt().coerceAtLeast(0)
}

/**
 * What one departure line takes, in dp rounded up, at the system [fontScale]: its pill plus the
 * widest gap below it; a [stacked] line also carries its destination line below the pill (see
 * [widgetRowStacked]). The pill's label grows with [fontScale] up to [WIDGET_PILL_MAX_SCALE], the
 * text keeps growing. Costed at the widest case. A stop header and a status line cost the same as
 * an unstacked line.
 */
internal fun widgetLineHeight(fontScale: Float = 1f, stacked: Boolean = false): Int {
    // A line is as tall as its tallest part: the pill (its label stops growing at
    // WIDGET_PILL_MAX_SCALE) or the countdown/destination text (which keeps growing).
    val pill = WIDGET_PILL_PADDING + WIDGET_PILL_TEXT_HEIGHT * fontScale.coerceAtMost(WIDGET_PILL_MAX_SCALE)
    val text = WIDGET_DESTINATION_TEXT_HEIGHT * fontScale
    val core = if (pill > text) pill else text
    val line = if (stacked) {
        core + WIDGET_STACK_GAP + text + WIDGET_LINE_GAP
    } else {
        core + WIDGET_LINE_GAP
    }
    return kotlin.math.ceil(line.value).toInt()
}

/**
 * Whether a departure line stacks on two (pill and countdown, then the destination): when the widest
 * of [countdowns] (with the planned-work [calendar] before it) leaves too little of a cell [width]
 * wide, at [fontScale], for a readable destination beside the pill. Each destination line of a row
 * is judged on its own countdown; a row drawn as its status alone, on its status ([widgetStatusFits]).
 * Stacking keeps all three — the line code as text (never color alone), the destination, the
 * countdown whole — at the cost of height, which the budget counts per line. Glance can't measure text, so the widths are estimates
 * from the character counts, at the widest the host's bucket allows; judged on a device.
 */
internal fun widgetRowStacked(countdowns: List<String>, calendar: Boolean, width: Dp, fontScale: Float = 1f): Boolean {
    val countdown = WIDGET_COUNTDOWN_EM * ((countdowns.maxOfOrNull { widgetTextEms(it) } ?: 0f) * fontScale)
    val minDestination = WIDGET_MIN_DESTINATION_WIDTH * fontScale
    val needed = widgetPillWidth(fontScale) + 16.dp + minDestination + countdown +
        (if (calendar) WIDGET_PLANNED_SIZE + 8.dp else 0.dp)
    return needed > width - WIDGET_HORIZONTAL_PADDING
}

/**
 * Whether a line's status, "⚠ [description]" with its [detail] (the reason for no times) after it,
 * fits on one line beside the pill's column of a cell [width] wide at [fontScale]. Unlike a
 * destination, a status isn't cut to a few characters: on a row drawn as its status alone it's all
 * the row says (SPEC D3), so that row stacks instead, and a status under a row's countdowns starts
 * under the pill. Estimated as [widgetRowStacked] estimates a countdown.
 */
internal fun widgetStatusFits(description: String, detail: String?, width: Dp, fontScale: Float = 1f): Boolean {
    val text = if (detail == null) "⚠ $description" else "⚠ $description · $detail"
    val needed = widgetPillWidth(fontScale) + 8.dp + WIDGET_DISRUPTION_EM * (widgetTextEms(text) * fontScale)
    return needed <= width - WIDGET_HORIZONTAL_PADDING
}

/** The widget's padding either side of its rows (12dp each side). */
private val WIDGET_HORIZONTAL_PADDING = 24.dp

/**
 * How wide [text] draws, in ems of its bold or medium text, by the kind of each character: Roboto's
 * digits and letters (~0.56em, bold a little wider), its spaces (~0.25em), the middle dot (~0.27em)
 * between times, and the warning sign (a symbol font's, about an em), each rounded up so a text is
 * never costed narrower than it draws.
 */
internal fun widgetTextEms(text: String): Float = text.sumOf { c ->
    when (c) {
        ' ' -> 0.26
        '·' -> 0.3
        '⚠' -> 1.0
        else -> 0.6
    }
}.toFloat()

/** The countdown's em at font scale 1: its 13sp. */
private val WIDGET_COUNTDOWN_EM = 13.dp

/** A status line's em at font scale 1: its 12sp. */
private val WIDGET_DISRUPTION_EM = 12.dp

/** The least of a destination worth showing beside the pill: about five characters of its 13sp. */
private val WIDGET_MIN_DESTINATION_WIDTH = 32.dp

/** What a status-only row's reason for no times ("No key", "No data") is costed at. */
private const val WIDGET_NO_TIMES_ESTIMATE = "No data"

/** The chrome's fixed part: 24dp of padding and the 8dp under the title row. */
private val WIDGET_FIXED_CHROME = 32.dp

/** The title row's text height at font scale 1 (the 14sp title's line). */
private val WIDGET_TITLE_TEXT_HEIGHT = 20.dp

/** The compact chrome's fixed part: 24dp of padding and the 4dp under the status line. */
private val WIDGET_COMPACT_CHROME = 28.dp

/** A stacked row's destination line height at font scale 1 (its 13sp line). */
private val WIDGET_DESTINATION_TEXT_HEIGHT = 17.dp

/** The gap between a stacked row's pill line and its destination line. */
private val WIDGET_STACK_GAP = 4.dp

/** The stale/partial note's height at font scale 1 (its 11sp line under the title row). */
private val WIDGET_NOTE_TEXT_HEIGHT = 16.dp

/** The pill's vertical padding (4dp above and below its label). */
private val WIDGET_PILL_PADDING = 8.dp

/** The widest gap below a departure line (8dp between services). */
private val WIDGET_LINE_GAP = 8.dp

/** The pill label's text height at font scale 1 (its 12sp line). */
private val WIDGET_PILL_TEXT_HEIGHT = 16.dp

/**
 * Below this width the header can't fit the title and the full "Updated 14 min ago" stamp
 * (~165dp of text plus the 24dp of padding), so the stamp drops its "Updated" prefix.
 */
internal val WIDGET_COMPACT_WIDTH = 220.dp

/** The wide bucket's width: wide enough for three times beside a destination at a large font. */
private val WIDGET_WIDE_WIDTH = 300.dp

/** The [StopDashWidget.sizeMode] buckets: the minimum and compact widths and a wider one (a row is
 *  costed at its bucket's width, so more widths stack fewer rows that would fit), by a ladder of
 *  heights from the minimum up (each rung about two more departure lines). Three by five: a host
 *  takes at most sixteen sizes. */
private val WIDGET_BUCKET_WIDTHS by lazy { listOf(WIDGET_MIN_WIDTH, WIDGET_COMPACT_WIDTH, WIDGET_WIDE_WIDTH) }
private val WIDGET_BUCKET_HEIGHTS = listOf(WIDGET_MIN_HEIGHT, 180.dp, 250.dp, 320.dp, 400.dp)

/**
 * How many of a row's next departures the widget shows across its destination lines, matching
 * the in-app card's `MAX_TIMES` — passed to [DepartureRows.destinationLines], which caps the
 * row before grouping so a frequent service can't render an arbitrarily long merged countdown
 * that crowds the destination out of the fixed-width row.
 */
private const val WIDGET_MAX_TIMES = 3
