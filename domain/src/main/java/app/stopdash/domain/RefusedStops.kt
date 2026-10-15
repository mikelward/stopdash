package app.stopdash.domain

/**
 * The bus stops the Planner found no route through when asked via them ([TowardStops]; maintainer,
 * 2026-10-15), for the process: a later plan to the same destination asks the next stop for their
 * lines straight away, rather than spend a round trip on a refusal it has had. Per destination and per
 * [Asked] (the walk, step-free level and modes the request was made under), as the Planner's 404 ("No
 * journey found for your inputs") may be the trip's rather than the stop's: a stop refused to a Fully
 * step-free trip is asked again once the rider picks Any (Codex, #870). Not per start: the refusals seen
 * are the stop's own (TfL plans from it readily), and a start moves with every fix, so keyed by it nothing
 * would ever be skipped. Memory only, never saved, gone with the process; at most [MAX], the oldest let go first.
 */
class RefusedStops {
    /** What a request via a stop was asked under, beside its two ends: each can decide whether a route exists. */
    data class Asked(val speed: WalkingSpeed, val maxWalk: MaxWalk, val stepFree: StepFree, val modes: TripModes)

    private val refused = LinkedHashSet<String>()

    /** Whether the Planner found no route through [stop] to [destination], asked under [asked]. */
    @Synchronized
    fun has(stop: String, destination: TripDestination, asked: Asked): Boolean = key(stop, destination, asked) in refused

    /** The Planner found no route through [stop] to [destination], asked under [asked]. */
    @Synchronized
    fun add(stop: String, destination: TripDestination, asked: Asked) {
        val key = key(stop, destination, asked)
        refused.remove(key)
        refused += key
        while (refused.size > MAX) refused.remove(refused.first())
    }

    private fun key(stop: String, destination: TripDestination, asked: Asked): String = "$stop>" + when (destination) {
        is TripDestination.Stop -> destination.id
        is TripDestination.Place -> "${destination.coordinate.latitude},${destination.coordinate.longitude}"
    } + "~${asked.speed.name}~${asked.maxWalk.name}~${asked.stepFree.name}~${asked.modes.key}"

    companion object {
        /** How many refusals are remembered: a few trips' worth. */
        const val MAX = 64

        /** The app's own, shared by every trip. */
        val SHARED = RefusedStops()
    }
}
