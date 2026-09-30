package app.stopdash.domain

/**
 * Which kinds of transport a trip may ride (SPEC *Trips with a change*): the [ModeGroups] a rider
 * already hides modes by on the list, toggled atop a trip and remembered across trips. Held as the
 * groups turned [off], so a group added later rides until the rider turns it off.
 *
 * Walking, the cable car and rail replacement buses aren't toggled: every route needs its walks, the
 * cable car is in no group, and a replacement bus stands in for a train or Tube the rider still rides.
 */
data class TripModes(
    /** The [ModeGroups.Group.key]s turned off; empty rides everything. */
    val off: Set<String> = emptySet(),
) {
    /** Whether a trip may ride [group]. */
    fun rides(group: ModeGroups.Group): Boolean = group.key !in off

    /** Whether [group] is the only one still riding, so turning it off would leave no route. */
    fun isLast(group: ModeGroups.Group): Boolean = rides(group) && ModeGroups.ALL.count(::rides) == 1

    /** [group] turned on ([ride]) or off; the last group riding stays on. */
    fun with(group: ModeGroups.Group, ride: Boolean): TripModes = when {
        ride -> TripModes(off - group.key)
        isLast(group) -> this
        else -> TripModes(off + group.key)
    }

    /** The Planner's `mode` list: [PLANNER_MODES] less every mode in a group turned off. */
    val plannerModes: String
        get() = PLANNER_MODES.filterNot { mode -> ModeGroups.ALL.any { it.key in off && mode in it.modes } }.joinToString(",")

    /** Names this choice for the plans kept per choice; empty when everything rides. */
    val key: String get() = off.sorted().joinToString("+")

    companion object {
        /** The stored default: every group rides. */
        val DEFAULT = TripModes()

        /**
         * The modes a trip is planned over when every group rides: the Planner's own default set,
         * named so that it times each walk at the rider's `walkingSpeed`. Without `mode=` it ignores
         * the speed for every walk in a route that rides — the first, the changes and the last alike
         * (measured 2026-09-28: a 649 m first walk read 11 min at Slow, Average and Fast; named, 15,
         * 11 and 8, and a slower pace made it offer other connections). Named, it offered the same
         * routes as its default on every trip compared, boats and the cable car included; tour boats
         * (`river-tour`) and `international-rail` stay out, as the default leaves them.
         */
        val PLANNER_MODES: List<String> = listOf(
            "bus", "cable-car", "coach", "dlr", "elizabeth-line", "national-rail", "overground",
            "replacement-bus", "river-bus", "tram", "tube", "walking",
        )

        /**
         * A stored set read back. A key no group has (a newer build's) is dropped, and a set turning
         * every group off, which [with] never writes, reads as [DEFAULT] rather than planning no route.
         */
        fun fromStored(off: Set<String>?): TripModes {
            val known = off.orEmpty().filterTo(LinkedHashSet()) { key -> ModeGroups.ALL.any { it.key == key } }
            return if (ModeGroups.ALL.all { it.key in known }) DEFAULT else TripModes(known)
        }
    }
}
