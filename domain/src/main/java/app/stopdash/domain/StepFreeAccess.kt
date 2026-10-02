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
 * wheelchair fits; [entrance] the entrance it starts at, when it needs a particular one. [byLift]
 * is where the platform sits in its station's [LiftMap], when only a lift gets a rider there.
 */
data class StepFreePlatform(
    val level: StepFreeLevel,
    val platform: String = "",
    val direction: String = "",
    val where: String = "",
    val limitedLift: Boolean = false,
    val entrance: String = "",
    val byLift: LiftStop? = null,
)

/** A platform's place in the [LiftMap] of [station] (TfL's station id): its [node] there. */
data class LiftStop(val station: String, val node: Int)

/**
 * A station's step-free walking map, cut down to what a lift outage can change: node 0 is the
 * street, [walks] the step-free ways from one node to another that need no lift, and [lifts] each
 * lift by the id TfL's live lift disruptions name it by, with the nodes it stops at.
 */
class LiftMap(private val walks: Map<Int, Set<Int>>, val lifts: Map<String, Set<Int>>) {
    /** The nodes the street reaches without the lifts in [out]. */
    fun reached(out: Set<String>): Set<Int> {
        val ways = HashMap<Int, MutableSet<Int>>()
        walks.forEach { (from, to) -> ways.getOrPut(from) { mutableSetOf() } += to }
        lifts.forEach { (id, stops) ->
            if (id !in out) stops.forEach { from -> ways.getOrPut(from) { mutableSetOf() } += stops - from }
        }
        val seen = mutableSetOf(0)
        val queue = ArrayDeque(listOf(0))
        while (queue.isNotEmpty()) {
            ways[queue.removeFirst()].orEmpty().forEach { if (seen.add(it)) queue.addLast(it) }
        }
        return seen
    }
}

/**
 * Step-free access at the stations TfL describes, by stop (the stop area id the app's departures
 * carry, `940GZZLUGPK`) and line (TfL's line id; every National Rail service at a station is
 * [NATIONAL_RAIL]). Bundled with the app and refreshed weekly (`scripts/build_step_free.py`); a stop
 * or line it doesn't hold is unknown, never assumed either way.
 */
class StepFreeAccess(
    private val stops: Map<String, Map<String, List<StepFreePlatform>>>,
    private val stations: Map<String, LiftMap> = emptyMap(),
) {
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

    /**
     * [level] for a train of [lineId] at [stopId]: TfL's own line where it describes it, else, for a
     * National Rail train ([mode] `national-rail`, whose line ids are its operators'), the station's
     * National Rail platforms. Null when TfL describes neither.
     */
    fun levelFor(stopId: String, lineId: String, mode: String): StepFreeLevel? =
        level(stopId, lineId) ?: if (mode.equals(NATIONAL_RAIL, ignoreCase = true)) level(stopId, NATIONAL_RAIL) else null

    /** The lines TfL describes at [stopId]. */
    fun lines(stopId: String): Set<String> = stops[stopId]?.keys.orEmpty()

    /** Whether any of [stopId]'s platforms is step-free only by a lift, so a lift outage can change it. */
    fun byLift(stopId: String): Boolean = stops[stopId].orEmpty().values.any { platforms -> platforms.any { it.byLift != null } }

    /**
     * This table with the lifts in [out] (TfL's lift ids, from its live lift disruptions) out of
     * service: a platform the street no longer reaches without them reads [StepFreeLevel.NONE] until
     * they're back. A lift this table doesn't know changes nothing.
     */
    fun withLiftsOut(out: Set<String>): StepFreeAccess {
        val reached = stations.filterValues { map -> map.lifts.keys.any { it in out } }.mapValues { it.value.reached(out) }
        if (reached.isEmpty()) return this
        return StepFreeAccess(
            stops.mapValues { (_, lines) ->
                lines.mapValues { (_, platforms) ->
                    platforms.map { platform ->
                        val stop = platform.byLift
                        val nodes = stop?.let { reached[it.station] }
                        if (nodes != null && stop.node !in nodes) platform.copy(level = StepFreeLevel.NONE) else platform
                    }
                }
            },
            stations,
        )
    }

    val isEmpty: Boolean get() = stops.isEmpty()

    companion object {
        /** The line id TfL's station data gives every National Rail service. */
        const val NATIONAL_RAIL = "national-rail"
        val EMPTY = StepFreeAccess(emptyMap())
    }
}
