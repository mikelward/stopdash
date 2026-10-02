package app.stopdash.domain

/**
 * How far a rider can get without a step at one platform (SPEC *Step-free access*), worst first,
 * so the lower of two levels is the one both meet.
 */
enum class StepFreeLevel {
    /** No step-free route from the street to the platform. */
    NONE,

    /** Step-free from the street to the platform, with a step or gap onto the train. */
    PLATFORM,

    /** Step-free to the platform, and onto the train by a ramp the staff put down. */
    RAMP,

    /** Step-free to the platform and level onto the train, at least at a marked spot. */
    LEVEL,
}

/**
 * One platform's step-free access for one line, from TfL's station data: its [platform] number
 * ("3", "3A"; blank for a tram stop) and [direction] ("Northbound"; blank when TfL gives none) as
 * TfL names them. [where] is the marked spot a [StepFreeLevel.LEVEL] boarding is at ("2 centre
 * doors on car 5"), when TfL says; [limitedLift] that the step-free route takes a lift not every
 * wheelchair fits; [entrance] the entrance it starts at, when it needs a particular one.
 */
data class StepFreePlatform(
    val level: StepFreeLevel,
    val platform: String = "",
    val direction: String = "",
    val where: String = "",
    val limitedLift: Boolean = false,
    val entrance: String = "",
)

/**
 * Step-free access at the stations TfL describes, by stop (the stop area id the app's departures
 * carry, `940GZZLUGPK`) and line (TfL's line id; every National Rail service at a station is
 * [NATIONAL_RAIL]). Bundled with the app and refreshed weekly (`scripts/build_step_free.py`); a stop
 * or line it doesn't hold is unknown, never assumed either way.
 */
class StepFreeAccess(private val stops: Map<String, Map<String, List<StepFreePlatform>>>) {
    /** [lineId]'s platforms at [stopId], in platform order; empty when TfL describes none. */
    fun platforms(stopId: String, lineId: String): List<StepFreePlatform> = stops[stopId]?.get(lineId).orEmpty()

    /**
     * The level every one of [lineId]'s platforms at [stopId] meets, so a rider can count on it
     * whichever way they're going; null when TfL describes none.
     */
    fun level(stopId: String, lineId: String): StepFreeLevel? = platforms(stopId, lineId).minOfOrNull { it.level }

    /**
     * The level at [lineId]'s platform [platform] at [stopId] (TfL's number, "3" or "3A"); null
     * when TfL doesn't describe that platform.
     */
    fun level(stopId: String, lineId: String, platform: String): StepFreeLevel? =
        platforms(stopId, lineId).filter { it.platform.equals(platform.trim(), ignoreCase = true) }.minOfOrNull { it.level }

    /** The lines TfL describes at [stopId]. */
    fun lines(stopId: String): Set<String> = stops[stopId]?.keys.orEmpty()

    val isEmpty: Boolean get() = stops.isEmpty()

    companion object {
        /** The line id TfL's station data gives every National Rail service. */
        const val NATIONAL_RAIL = "national-rail"
        val EMPTY = StepFreeAccess(emptyMap())
    }
}
