package app.stopdash.domain

import androidx.annotation.WorkerThread
/**
 * The transport modes the user has hidden from the near-me list ("bus", "national-rail"…), and
 * what hiding one means (SPEC *Finding stops → Hiding a mode*): a stop that serves only hidden
 * modes isn't picked or fetched at all, and a hidden mode's rows are left out of a stop that also
 * serves others. A closure notice still shows at a stop that keeps an unhidden mode, so hiding one
 * mode never hides a closed stop the user still rides from; a stop serving only hidden modes isn't
 * checked, since its closure matters only to a rider of that mode. Modes compare case-insensitively,
 * as TfL's mode ids are lowercase but not guaranteed so.
 *
 * The same set holds single hidden lines ("Hide Northern line"), each as a [lineKey]: a line hides
 * just as a mode does, only narrower, and sharing the set means everything that already follows the
 * hidden modes (the widget, the watch, "Show all") follows the lines too. A mode id never starts
 * with [LINE_PREFIX], so the two can't be confused.
 */
object HiddenModes {
    /** The prefix of a hidden line's entry in the set. */
    const val LINE_PREFIX = "line:"

    /**
     * The set entry hiding line [lineId]: `line:<id>=<label>`, carrying the [label] the banner names
     * it by ("Northern line"), since the banner has no line data of its own to look it up in.
     */
    fun lineKey(lineId: String, label: String): String = "$LINE_PREFIX${lineId.lowercase()}=$label"

    /** Whether [entry] hides a line rather than a mode. */
    fun isLineKey(entry: String): Boolean = entry.startsWith(LINE_PREFIX)

    /**
     * The hidden lines' labels, in the order they were hidden. Renamed on the way back in, like any
     * saved name ([riderLineName]; the entry keeps no mode), so a line hidden before a rename isn't
     * named the old way in the banner.
     */
    fun hiddenLineLabels(hidden: Set<String>): List<String> =
        hidden.filter(::isLineKey).map { riderLineName(it.substringAfter('=', it.removePrefix(LINE_PREFIX)), mode = "") }

    /** Whether [mode] is one of [hidden]. */
    fun isHidden(mode: String, hidden: Set<String>): Boolean = hidden.any { it.equals(mode, ignoreCase = true) }

    /** Whether line [lineId] is hidden by itself. */
    fun isLineHidden(lineId: String, hidden: Set<String>): Boolean =
        lineId.isNotBlank() && hidden.any { isLineKey(it) && it.substringBefore('=').removePrefix(LINE_PREFIX).equals(lineId, ignoreCase = true) }

    /** Whether a service of [mode] on line [lineId] is hidden, by its mode or its line. */
    fun isHidden(mode: String, lineId: String, hidden: Set<String>): Boolean =
        isHidden(mode, hidden) || isLineHidden(lineId, hidden)

    /** Whether [line] is hidden, by its mode or by itself. */
    fun isHidden(line: LineRef, hidden: Set<String>): Boolean = isHidden(line.mode, line.id, hidden)

    /**
     * [stops] without their hidden-mode lines, and without a stop left serving nothing: it isn't
     * picked for the near-me set, so it costs no request. A stop TfL listed no lines for is kept as
     * it is — it has no mode to hide, and the near-me selection already treats it as route-less.
     */
    fun stops(stops: List<StopLocation>, hidden: Set<String>): List<StopLocation> {
        if (hidden.isEmpty()) return stops
        return stops.mapNotNull { stop ->
            if (stop.lines.isEmpty()) return@mapNotNull stop
            val kept = stop.lines.filterNot { isHidden(it, hidden) }
            when {
                kept.isEmpty() -> null
                kept.size == stop.lines.size -> stop
                else -> stop.copy(lines = kept)
            }
        }
    }

    /**
     * Whether a stop needs its station's National Rail board: not while National Rail is [hidden],
     * since the board would only fill rows left out, unless one of the [starred] journeys' lines
     * taken from the stop runs on it: hiding doesn't reach a favorite journey, so it keeps its times.
     * Decided by the hidden set itself, not by which lines a stop was picked with, so it holds from
     * the moment National Rail is hidden, on every list.
     */
    fun wantsRailBoard(hidden: Set<String>, starred: List<LineRef> = emptyList()): Boolean =
        !isHidden(NATIONAL_RAIL_MODE, hidden) || starred.any { it.mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true) }

    /** [rows] without a hidden mode's or line's departures and status rows; stop-closure rows always stay. */
    @WorkerThread
    fun rows(rows: List<DepartureRow>, hidden: Set<String>): List<DepartureRow> {
        if (hidden.isEmpty()) return rows
        return rows.filter { it.stopDisruption != null || !isHidden(it.mode, it.lineId, hidden) }
    }
}
