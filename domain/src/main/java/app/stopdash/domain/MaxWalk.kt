package app.stopdash.domain

/**
 * The longest walk a trip may ask of the rider, for the walks a trip plans (SPEC *Trips with a
 * change*, maintainer 2026-09-30): the Journey Planner's `maxWalkingMinutes`, which no walk in a
 * route it offers exceeds — to the first stop, between stations, or on to the end. Timed at the
 * rider's [WalkingSpeed], as the Planner times every walk. [THIRTY] is the stored default: long
 * enough for the walk to a station a mile off, which a shorter limit dropped even when it was the
 * fastest way there.
 */
enum class MaxWalk(
    /** The Journey Planner's value for `maxWalkingMinutes`. */
    val minutes: Int,
) {
    TEN(10),
    FIFTEEN(15),
    TWENTY(20),
    THIRTY(30),
    FORTY_FIVE(45),
    SIXTY(60),
    ;

    companion object {
        /** The stored default. */
        val DEFAULT = THIRTY

        /** A stored name read back; anything unrecognized (a newer build's value) is [DEFAULT]. */
        fun fromStored(name: String?): MaxWalk = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
