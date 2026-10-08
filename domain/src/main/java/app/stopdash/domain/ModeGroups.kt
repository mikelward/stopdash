package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The groups a rider hides modes by (SPEC *Finding stops → Hiding a mode*, maintainer 2026-09-24):
 * TfL's mode ids folded into how a rider rides them (maintainer, 2026-10-08): "Underground" is
 * TfL's turn-up-and-go metro, the Tube, the DLR and the Elizabeth line; "Overground" its suburban
 * trains; "National Rail" other operators' timetabled trains, Eurostar with them. A coach is a "Bus": TfL lists no coach routes or stops, and a rider
 * doesn't draw the line. Hiding a group hides every mode in it; a mode no group names (the cable
 * car) is a group of its own.
 */
object ModeGroups {
    /**
     * One group: its stable [key] and the TfL mode ids it holds, the first its anchor: a group a stored
     * set hides only in part reads as its anchor does ([fromStored]).
     */
    data class Group(val key: String, val modes: Set<String>)

    /** The groups the near-me search can return a stop for, in the order the menu lists them. */
    val ALL: List<Group> = listOf(
        Group("tube", setOf("tube", "dlr", "elizabeth-line")),
        Group("overground", setOf("overground")),
        Group("rail", setOf("national-rail", "international-rail")),
        Group("bus", setOf("bus", "coach")),
        Group("tram", setOf("tram")),
        Group("boat", setOf("river-bus")),
    )

    /**
     * The group [mode] belongs to; an unknown mode is its own group. A hidden line's
     * [HiddenModes.lineKey] is a group of its own, kept as it is, so hiding and showing it go the
     * same way a mode's do.
     */
    fun of(mode: String): Group =
        if (HiddenModes.isLineKey(mode)) Group(mode, setOf(mode)) else ALL.firstOrNull { g -> g.modes.any { it.equals(mode, ignoreCase = true) } }
            ?: Group(mode.lowercase(), setOf(mode.lowercase()))

    /**
     * Whether [group] is hidden (its checkbox reads unticked). Groups are only ever hidden or shown
     * whole, so any member hidden means the group is.
     */
    fun isHidden(group: Group, hidden: Set<String>): Boolean = group.modes.any { HiddenModes.isHidden(it, hidden) }

    /** The groups with any mode hidden, in menu order, for the banner that names them; hidden lines aren't groups. */
    fun hiddenGroups(hidden: Set<String>): List<Group> =
        (ALL + hidden.filterNot(HiddenModes::isLineKey).map(::of)).distinctBy { it.key }.filter { g -> g.modes.any { HiddenModes.isHidden(it, hidden) } }

    /**
     * Everything [hidden], one item each, for Settings' Hidden list to show again one at a time: the
     * groups in menu order, then each hidden line as a group of its own ([of]), in the order hidden.
     */
    fun hiddenItems(hidden: Set<String>): List<Group> =
        hiddenGroups(hidden) + hidden.filter(HiddenModes::isLineKey).map(::of)

    /**
     * A stored hidden set read back, with each group hidden or shown whole, as its anchor (its first
     * mode) is: the groups were regrouped on 2026-10-08 (Coach into Bus, the Elizabeth line from Train
     * into Underground), so a mode stored under its old group follows its new one's anchor rather than
     * leaving that group half hidden. Hidden lines and modes no group has are kept as they are.
     */
    @WorkerThread
    fun fromStored(hidden: Set<String>): Set<String> {
        val out = LinkedHashSet(hidden)
        for (group in ALL) {
            if (HiddenModes.isHidden(group.modes.first(), hidden)) {
                out += group.modes
            } else {
                out.removeAll { m -> group.modes.any { it.equals(m, ignoreCase = true) } }
            }
        }
        return out
    }

    /** [hidden] with all of [group] hidden (or shown again when [hide] is false). */
    fun withGroup(hidden: Set<String>, group: Group, hide: Boolean): Set<String> =
        if (hide) {
            hidden + group.modes
        } else {
            hidden.filterNotTo(LinkedHashSet()) { m -> group.modes.any { it.equals(m, ignoreCase = true) } }
        }
}
