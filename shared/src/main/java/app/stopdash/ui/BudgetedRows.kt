package app.stopdash.ui

import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DestinationGroup
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopGrouping

/** A stop header above a group of rows: the place's one-line [text] and what a screen reader hears. */
data class GroupHeader(val text: String, val spoken: String)

/**
 * A chosen row, the destination lines of it that fit, and the header drawn above it, if any. A row
 * with a [DepartureRow.status] also draws its status line, which [BudgetedRows.select] counts.
 */
data class BudgetedRow(val row: DepartureRow, val groups: List<DestinationGroup>, val header: GroupHeader?)

/**
 * How a fixed-height surface (the widget, the watch tile) fits departures into [budget] lines,
 * so every such surface shows the same rows under the same stop headers.
 */
object BudgetedRows {
    /**
     * The rows of [pinned] (already in priority order) that fit [budget] lines, each with the
     * destination lines of it that fit (at most [maxTimes] times each) and, on the first row of
     * each place group that shows one, its stop header. [grouped] groups rows by place for
     * display; the widget passes its own to keep the journeys a band of their own.
     */
    fun select(
        pinned: List<DepartureRow>,
        budget: Int,
        maxTimes: Int,
        topology: RouteTopology,
        grouped: (List<DepartureRow>) -> List<StopGroup> = { StopGrouping.groupByStop(it, warningsLead = false) },
    ): List<BudgetedRow> {
        // Headers cost a line each; a budget too small for a header and a line (the minimum size)
        // drops them: the next departure matters more than naming its stop, and a lone header would
        // leave no row — and an empty list reads as "No upcoming departures", a claim the data
        // doesn't make.
        val headersOn = budget >= 2
        // Choose which rows fit in PRIORITY order (fresh before stale, starred first — `pinned`),
        // counting the headers the chosen set will draw; only then group the chosen rows by place
        // for display. Grouping first would let a place's lower-priority rows (a stale sibling, an
        // unstarred one) take lines ahead of a fresher or starred row at another place. The grouping
        // itself is the in-app list's (SPEC D8): a place's rows together, places in the order their
        // first row came, and a header only where the list shows one — one place with no qualifier
        // stays header-less, so the common single-stop surface pays nothing for it.
        // warningsLead = false: `pinned` decided the lead.
        val shownLines = java.util.IdentityHashMap<DepartureRow, List<DestinationGroup>>()
        val selected = mutableListOf<DepartureRow>()
        // Each header — whether it shows, and what it says — is judged against every row there are
        // departures for, not just the rows that fit: when the cap leaves one place of several, its
        // rows still need its name, and a bus place whose second route didn't fit mustn't claim the
        // one shown route's terminus ("➔ …") as the whole stop's. Group keys are the same in both
        // groupings (place plus split, read from each row), so a chosen group looks up its
        // full-context twin.
        val allLines = pinned.map { DepartureRows.destinationLines(it, maxTimes, topology) }
        // A disrupted line with no countdown (a suspended line, its status row) is shown too: its
        // status line is all it has, and leaving it out would hide the disruption (SPEC D3).
        fun shows(row: DepartureRow, lines: List<DestinationGroup>) = lines.isNotEmpty() || row.status != null
        val candidates = pinned.filterIndexed { i, row -> shows(row, allLines[i]) }
        val fullGroups = StopGrouping.groupByStop(candidates, warningsLead = false).associateBy { it.key }
        fun context(group: StopGroup): StopGroup = fullGroups[group.key] ?: group
        fun headed(group: StopGroup): Boolean = headersOn && context(group).showHeader
        fun cost(rows: List<DepartureRow>): Int = grouped(rows).sumOf { group ->
            (if (headed(group)) 1 else 0) + group.rows.sumOf { shownLines.getValue(it).size + statusLines(it) }
        }
        for ((row, lines) in pinned.zip(allLines)) {
            if (!shows(row, lines)) continue
            selected += row
            shownLines[row] = lines
            val over = cost(selected) - budget
            if (over <= 0) continue
            // Over budget: a branching row may still fit with fewer of its destination lines (each
            // line dropped saves exactly one); otherwise it's skipped. A row's status line is never
            // the one dropped: a countdown without its disruption is the failure the mark prevents. The scan never stops early: a
            // row that opens a new group pays for its header too, so a later row of an already-shown
            // group may still fit after one that couldn't. The rows are few; checking each is cheap.
            if (lines.size - over >= 1) {
                shownLines[row] = lines.take(lines.size - over)
            } else if (row.status != null && over <= lines.size) {
                // Room for the status but no countdown beside it: the disrupted line shows as its
                // status alone, so it isn't dropped for want of a line (and the surface never falls
                // to a "No departures" it can't stand behind).
                shownLines[row] = emptyList()
            } else {
                selected.removeAt(selected.lastIndex)
                shownLines.remove(row)
            }
        }
        return buildList {
            for (group in grouped(selected)) {
                val header = if (headed(group)) {
                    val full = context(group)
                    GroupHeader(
                        text = groupHeaderTitle(full.stopName, full.qualifier),
                        spoken = groupHeaderSpoken(full.qualifier)?.let { "${full.stopName}, $it" } ?: full.stopName,
                    )
                } else {
                    null
                }
                group.rows.forEachIndexed { index, row ->
                    add(BudgetedRow(row, shownLines.getValue(row), header.takeIf { index == 0 }))
                }
            }
        }
    }

    /** The lines [row]'s status costs: one when it has one to show. */
    private fun statusLines(row: DepartureRow): Int = if (row.status != null) 1 else 0
}
