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
 * What each part of a budgeted surface costs, in the units of its budget: a stop [header], each of a
 * row's destination [line]s on its own, and a row's [status] line, which is told whether it's drawn
 * alone (no destination line shown beside it). One each by default (a budget in lines, as the watch
 * tile's); the widget counts in dp, so a line it stacks on two costs what it takes.
 */
class LineCosts(
    val header: Int = 1,
    val line: (row: DepartureRow, line: DestinationGroup) -> Int = { _, _ -> 1 },
    val status: (row: DepartureRow, alone: Boolean) -> Int = { _, _ -> 1 },
) {
    companion object {
        /** A budget in lines: every part one. */
        val UNIT = LineCosts()
    }
}

/**
 * How a fixed-height surface (the widget, the watch tile) fits departures into [budget] (in the
 * units of its [LineCosts]), so every such surface shows the same rows under the same stop headers.
 */
object BudgetedRows {
    /**
     * The least [row] can be drawn as, in [costs]' units: its first destination line (at most
     * [maxTimes] times) with its status under it, or its status alone; null when it has neither to
     * show. A surface with less room than the least of its rows shows no departure, so it decides
     * its layout on this rather than its own sum, which could miss what [select] has to draw.
     */
    fun least(row: DepartureRow, maxTimes: Int, topology: RouteTopology, costs: LineCosts = LineCosts.UNIT): Int? =
        least(row, DepartureRows.destinationLines(row, maxTimes, topology), costs)

    private fun least(row: DepartureRow, lines: List<DestinationGroup>, costs: LineCosts): Int? {
        val first = lines.firstOrNull()?.let { costs.line(row, it) + (if (row.status != null) costs.status(row, false) else 0) }
        val alone = row.status?.let { costs.status(row, true) }
        return listOfNotNull(first, alone).minOrNull()
    }

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
        costs: LineCosts = LineCosts.UNIT,
    ): List<BudgetedRow> {
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
        // What [row]'s status line costs, when it has one to show: drawn alone, or under its lines.
        fun statusCost(row: DepartureRow): Int =
            if (row.status != null) costs.status(row, shownLines.getValue(row).isEmpty()) else 0
        val candidates = pinned.filterIndexed { i, row -> shows(row, allLines[i]) }
        // Headers cost a line each; a budget too small for a header and the next departure as the
        // least it can be drawn (the minimum size) drops them: the next departure matters more than
        // naming its stop, and a lone header would leave no row — and an empty list reads as "No
        // upcoming departures", a claim the data doesn't make.
        val nextLeast = pinned.indices.firstNotNullOfOrNull { least(pinned[it], allLines[it], costs) }
        val headersOn = nextLeast == null || budget >= costs.header + nextLeast
        val fullGroups = StopGrouping.groupByStop(candidates, warningsLead = false).associateBy { it.key }
        fun context(group: StopGroup): StopGroup = fullGroups[group.key] ?: group
        fun headed(group: StopGroup): Boolean = headersOn && context(group).showHeader
        fun cost(rows: List<DepartureRow>): Int = grouped(rows).sumOf { group ->
            (if (headed(group)) costs.header else 0) +
                group.rows.sumOf { row -> shownLines.getValue(row).sumOf { costs.line(row, it) } + statusCost(row) }
        }
        for ((row, lines) in pinned.zip(allLines)) {
            if (!shows(row, lines)) continue
            selected += row
            shownLines[row] = lines
            if (cost(selected) <= budget) continue
            // Over budget: a branching row may still fit with fewer of its destination lines (each
            // dropped from the end, saving what it costs); otherwise it's skipped. A row's status line
            // is never the one dropped: a countdown without its disruption is the failure the mark
            // prevents. The scan never stops early: a row that opens a new group pays for its header
            // too, so a later row of an already-shown group may still fit after one that couldn't. The
            // rows are few; checking each is cheap.
            var kept = lines
            while (kept.size > 1) {
                kept = kept.dropLast(1)
                shownLines[row] = kept
                if (cost(selected) <= budget) break
            }
            if (cost(selected) <= budget) continue
            // Room for the status but no countdown beside it: the disrupted line shows as its status
            // alone, so it isn't dropped for want of a line (and the surface never falls to a "No
            // departures" it can't stand behind). Costed as it draws alone.
            if (row.status != null) {
                shownLines[row] = emptyList()
                if (cost(selected) <= budget) continue
            }
            selected.removeAt(selected.lastIndex)
            shownLines.remove(row)
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
}
