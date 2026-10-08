package app.stopdash.domain

/**
 * The modes the rider hid ([modes]) and whether they have shown or hidden National Rail themselves
 * ([railChosen]), stored together so the two can't be saved apart ([RailKeyDefault]).
 */
data class HiddenModesChoice(val modes: Set<String> = emptySet(), val railChosen: Boolean = false)

/**
 * National Rail's place in the hidden set while no National Rail key is set (SPEC *Finding stops →
 * Hiding a mode*, maintainer 2026-10-08): it counts as hidden then, since without a key its rows
 * carry no times and flood a big station, and it shows again by itself once a key is added. That
 * holds until the rider chooses for themselves (shows it anyway, or hides it): from then on the
 * stored set alone decides, as for every other group.
 *
 * The default is never stored, so adding a key brings National Rail back with no write, and a hide
 * of some other group isn't saved as a hide of National Rail too.
 */
object RailKeyDefault {
    /** The group the default hides. */
    val GROUP: ModeGroups.Group = ModeGroups.ALL.first { it.key == "rail" }

    /**
     * Whether the default is in force: no key, and no choice of the rider's. One who hid National
     * Rail themselves ([stored] holds it) has chosen, so their hide is theirs, not the default.
     */
    fun applies(keySet: Boolean, chosen: Boolean, stored: Set<String>): Boolean =
        !keySet && !chosen && !ModeGroups.isHidden(GROUP, stored)

    /** The modes hidden in effect: the rider's own, with National Rail's when the default [applies]. */
    fun effective(stored: Set<String>, applies: Boolean): Set<String> =
        if (applies) ModeGroups.withGroup(stored, GROUP, hide = true) else stored

    /** What a change of the effective set from [before] to [after] stores, and whether it is a choice of National Rail's. */
    data class Write(val stored: Set<String>, val choseRail: Boolean)

    /**
     * Storing a change of the effective hidden set, [before] to [after], made while the default
     * [applies] or not. One that shows or hides National Rail, or is aimed at it ([railTargeted]), is
     * the rider's choice of it, stored as given; any other leaves National Rail as the default had it, so the default isn't written down.
     */
    fun write(before: Set<String>, after: Set<String>, applies: Boolean, railTargeted: Boolean = false): Write {
        val railChanged = ModeGroups.isHidden(GROUP, before) != ModeGroups.isHidden(GROUP, after)
        return when {
            // A hide aimed at National Rail itself is the rider's choice even when the default already
            // had it hidden, so it outlasts a key added later.
            railChanged || railTargeted -> Write(after, choseRail = true)
            applies -> Write(ModeGroups.withGroup(after, GROUP, hide = false), choseRail = false)
            else -> Write(after, choseRail = false)
        }
    }
}
