package app.stopdash.domain

import androidx.annotation.WorkerThread

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
 *
 * A stop is avoided the same way (maintainer, 2026-10-08: a station found closed, from a missed change),
 * kept in the same set as `stop:<id>=<name>` ([stopKey]): a route boarding, getting off or changing on foot
 * there is dropped ([drops]); one riding through it isn't, as a train runs through a closed station.
 */
object AvoidedLines {
    const val STOP_PREFIX = "stop:"

    /** The entry avoiding line [lineId], named [label] ("Northern line"). */
    fun key(lineId: String, label: String): String = HiddenModes.lineKey(lineId, label)

    /**
     * The entry avoiding stop [stopId], named [name] ("Bank"): a bus stop by its area ([TripLeg.toArea]), so
     * both its poles are avoided.
     */
    fun stopKey(stopId: String, name: String): String = "$STOP_PREFIX$stopId=$name"

    /** Whether [entry] avoids a stop ([stopKey]). */
    fun isStopKey(entry: String): Boolean = entry.startsWith(STOP_PREFIX)

    /** The stops [avoided] avoids, by id. */
    fun stopIds(avoided: Set<String>): Set<String> =
        avoided.filter(::isStopKey).map { it.removePrefix(STOP_PREFIX).substringBefore('=') }.filterTo(HashSet()) { it.isNotBlank() }

    /**
     * Whether a trip leaving out [excluded] (hidden modes and lines, avoided lines and stops) drops [route]:
     * it rides a line left out, or boards, gets off or walks at a stop avoided, by the stop's own id, its
     * area or the one the Planner named. Not marked worker-only, as the line check it replaced wasn't: the
     * trip still filters its routes on the main thread from several paths (`TODO.md`).
     */
    fun drops(route: TripRoute, excluded: Set<String>): Boolean {
        if (route.rides.any { HiddenModes.isHidden(it.mode, it.lineId, excluded) }) return true
        if (excluded.none(::isStopKey)) return false
        val stops = stopIds(excluded)
        return route.legs.any { leg ->
            leg.fromId in stops || leg.toId in stops || leg.fromArea in stops || leg.toArea in stops ||
                leg.plannedFromId in stops || leg.plannedToId in stops
        }
    }

    /** Whether line [lineId] is one of [avoided]. */
    fun avoids(lineId: String, avoided: Set<String>): Boolean = HiddenModes.isLineHidden(lineId, avoided)

    /**
     * Each of [avoided] with the label it's named by, in the order avoided: a line renamed on the way back
     * in, like any saved name ([riderLineName]), so a line avoided before a rename isn't named the old
     * way; a stop by its name. An entry that's neither is left out.
     */
    fun labeled(avoided: Set<String>): List<Pair<String, String>> =
        avoided.mapNotNull { entry ->
            when {
                HiddenModes.isLineKey(entry) ->
                    entry to riderLineName(entry.substringAfter('=', entry.removePrefix(HiddenModes.LINE_PREFIX)), mode = "")
                isStopKey(entry) -> entry to entry.substringAfter('=', entry.removePrefix(STOP_PREFIX))
                else -> null
            }
        }

    /** [entries] less any that isn't a line's or a stop's: a stored set from a later build keeps only what this one reads. */
    fun fromStored(entries: Set<String>): Set<String> = entries.filterTo(LinkedHashSet()) { HiddenModes.isLineKey(it) || isStopKey(it) }

    /** [current] with [entry] avoided, or no longer avoided: the edit a tap makes, in the stored order. */
    @WorkerThread
    fun changed(current: Set<String>, entry: String, avoided: Boolean): Set<String> = if (avoided) current + entry else current - entry

    /** What a trip leaves out: the [hidden] modes and lines, and the [avoided] lines. */
    fun excluded(hidden: Set<String>, avoided: Set<String>): Set<String> = if (avoided.isEmpty()) hidden else hidden + avoided
}
