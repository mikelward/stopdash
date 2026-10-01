package app.stopdash.domain

/**
 * The lines a rider avoids on a trip (SPEC *Trips with a change → Avoiding a line*; maintainer,
 * 2026-10-01): sticky across trips until cleared, from a chip atop the trip or from Settings. Each is
 * kept as a hidden line is ([HiddenModes.lineKey]: `line:<id>=<label>`), carrying the label it's named
 * by, so a chip or Settings can name it without line data; but in a set of its own, since avoiding a
 * line leaves it out of trips only, not the list, the widget or the watch.
 *
 * The Journey Planner has no way to leave a line out, so a route riding one is dropped on the phone,
 * from the routes it already plans, as a route riding a hidden line is: [excluded] is what a trip
 * leaves out, both together.
 */
object AvoidedLines {
    /** The entry avoiding line [lineId], named [label] ("Northern line"). */
    fun key(lineId: String, label: String): String = HiddenModes.lineKey(lineId, label)

    /** Whether line [lineId] is one of [avoided]. */
    fun avoids(lineId: String, avoided: Set<String>): Boolean = HiddenModes.isLineHidden(lineId, avoided)

    /**
     * Each of [avoided] with the label it's named by, in the order avoided: renamed on the way back
     * in, like any saved name ([riderLineName]), so a line avoided before a rename isn't named the old
     * way. An entry that isn't a line's is left out.
     */
    fun labeled(avoided: Set<String>): List<Pair<String, String>> =
        avoided.filter(HiddenModes::isLineKey).map { entry ->
            entry to riderLineName(entry.substringAfter('=', entry.removePrefix(HiddenModes.LINE_PREFIX)), mode = "")
        }

    /** [entries] less any that isn't a line's: a stored set from a later build keeps only what this one reads. */
    fun fromStored(entries: Set<String>): Set<String> = entries.filterTo(LinkedHashSet(), HiddenModes::isLineKey)

    /** What a trip leaves out: the [hidden] modes and lines, and the [avoided] lines. */
    fun excluded(hidden: Set<String>, avoided: Set<String>): Set<String> = if (avoided.isEmpty()) hidden else hidden + avoided
}
