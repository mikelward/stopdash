package app.stopdash.domain

/**
 * How fast the rider walks, for the walks a trip plans (SPEC *Trips with a change*, maintainer
 * 2026-09-28): the Journey Planner's `walkingSpeed`, which times every walk it offers — to the first
 * stop, between stations, and on to the end — and so which trains each route can make.
 * [AVERAGE] (shown as "Medium") is the Planner's own default and the stored default.
 */
enum class WalkingSpeed(
    /** The Journey Planner's value for `walkingSpeed`. */
    val plannerValue: String,
) {
    SLOW("Slow"),
    AVERAGE("Average"),
    FAST("Fast"),
    ;

    companion object {
        /** A stored name read back; anything unrecognized (a newer build's value) is [AVERAGE]. */
        fun fromStored(name: String?): WalkingSpeed = entries.firstOrNull { it.name == name } ?: AVERAGE
    }
}
