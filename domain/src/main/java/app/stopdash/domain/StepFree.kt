package app.stopdash.domain

/**
 * How step-free a trip's routes must be (SPEC *Trips with a change*, maintainer 2026-09-30): the
 * Journey Planner's `accessibilityPreference`, which plans only routes with that much step-free
 * access — lifts, ramps and level walkways in place of stairs and escalators.
 *
 * - [ANY]: no requirement (nothing sent).
 * - [STATION]: step-free from the street to the platform, with maybe a step or gap onto the train;
 *   what a suitcase or a buggy needs.
 * - [FULLY]: step-free to the train as well; what a wheelchair needs.
 */
enum class StepFree(
    /** The Journey Planner's value for `accessibilityPreference`, or null to send none. */
    val plannerValue: String?,
) {
    ANY(null),
    STATION("StepFreeToPlatform"),
    FULLY("StepFreeToVehicle"),
    ;

    companion object {
        /** The stored default: no requirement, the Planner's own. */
        val DEFAULT = ANY

        /** A stored name read back; anything unrecognized (a newer build's value) is [DEFAULT]. */
        fun fromStored(name: String?): StepFree = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
