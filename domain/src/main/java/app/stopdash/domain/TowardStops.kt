package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The bus stops near the rider that a trip from where they are asks the Planner about once more
 * (SPEC *Trips with a change*, *Buses towards the destination*; maintainer, 2026-10-15). The Planner
 * ranks on timetable times and offers three routes a request, so a bus a few minutes behind another
 * on paper can make none of its answers: a bus to a station further down the line, passed over for
 * another to the station a long walk away, which live was 25 minutes off (maintainer's report,
 * 2026-10-15). Asked for the quickest routes **via** such a stop, the Planner offers that bus, and
 * builds the whole route itself, the walk there timed at the rider's pace ([ridesFrom] keeps what it
 * adds). Via the station itself would find nothing new: a train passing through it counts.
 *
 * Which stops, worked out on the device from the bundled stops, no request: each bus line's nearest
 * stop within [REACH_METERS] that lies towards the destination (within [SPREAD_DEGREES] either side of
 * the way there), so one stop asks for every line it is the nearest stop of, and a line's farther stops,
 * or one behind the rider, add no request. A night bus picks no stop of its own, nor does a stop with no
 * bus; at most [MAX_ASKS], nearest first, so a street with many lines can't fan the plan out.
 */
object TowardStops {
    /** How far from the rider a stop is asked about: about a ten-minute walk at the average pace. */
    const val REACH_METERS = 800

    /** How far either side of the way to the destination a stop may lie: a stop behind the rider isn't asked about. */
    const val SPREAD_DEGREES = 90.0

    /**
     * Within this of the rider a stop counts as towards the destination whichever way it lies: the
     * fix itself is that uncertain, so a bearing to the stop means nothing.
     */
    const val HERE_METERS = 50.0

    /** The most requests a plan makes this way. */
    const val MAX_ASKS = 3

    /**
     * One request: via the stop [via] (its stop pair, "490G…", or the stop where it has none), and, where
     * the Planner finds no route through it, via [fallback] once instead: the next stop on the
     * destination's side serving one of its lines (the Planner can find none through a stop it plans from
     * readily, maintainer's report, 2026-10-15), or null when there's none not already asked, or taken as
     * another stop's fallback.
     */
    data class Ask(val via: String, val fallback: String?)

    /**
     * The stops to ask about, nearest first, from [stops] (bus stops near the rider at [from]) for a trip
     * to [to]. None for a destination within [REACH_METERS]: it is a walk or a bus away, which the
     * Planner's own requests offer. Grows with [stops], so off the main thread.
     */
    @WorkerThread
    fun of(from: Coordinates, to: Coordinates, stops: List<StopLocation>): List<Ask> {
        if (meters(from, to) <= REACH_METERS) return emptyList()
        val way = Compass.bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)
        val toward = stops.asSequence()
            .filter { it.latitude != 0.0 && it.longitude != 0.0 && it.lines.any(::isDayBus) }
            .map { it to meters(from, Coordinates(it.latitude, it.longitude)) }
            .filter { (stop, distance) -> distance <= REACH_METERS && (distance <= HERE_METERS || lies(from, stop, way)) }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
            .map { it.first }
            .toList()
        // Each day bus line's nearest stop, and the stops so picked in order, each once.
        val nearest = LinkedHashMap<String, String>()
        toward.forEach { stop -> stop.lines.filter(::isDayBus).forEach { nearest.putIfAbsent(it.id, key(stop)) } }
        val picked = nearest.values.distinct().take(MAX_ASKS)
        // Every stop a request may go via, each once: a fallback another stop took isn't another's too, so two
        // refusals never send the same request twice (Codex, #870).
        val taken = picked.toMutableSet()
        return picked.map { via ->
            val lines = nearest.filterValues { it == via }.keys
            val fallback = toward.firstOrNull { stop -> key(stop) !in taken && stop.lines.any { it.id in lines } }?.let(::key)
            fallback?.let(taken::add)
            Ask(via, fallback)
        }
    }

    /**
     * The routes of [answer], asked via the stop [via] (as [Ask.via] or [Ask.fallback] names it), whose first
     * ride is a bus that boards at that stop or calls at it on the way (Codex, #870). The Planner can meet the
     * via by boarding a stop earlier and passing it, which is how the bus the request exists for came back via
     * the next stop (maintainer's report, 2026-10-15); a route that reaches the stop only to walk on (through it
     * to a station, or to a bus elsewhere) is no bus from there, and adds only a longer walk. A bus leg names
     * its stops by their stop pair ("490G…") where they have one, its boarding stop by its pole too. Walks
     * every route's legs, so off the main thread.
     */
    @WorkerThread
    fun ridesFrom(answer: List<TripRoute>, via: String): List<TripRoute> = answer.filter { route ->
        val ride = route.rides.firstOrNull()?.takeIf { it.isBus } ?: return@filter false
        ride.fromArea == via || ride.fromId == via || via in ride.path
    }

    // Whether [stop] lies within [SPREAD_DEGREES] of [way], the bearing from [from] to the destination.
    private fun lies(from: Coordinates, stop: StopLocation, way: Double): Boolean {
        val bearing = Compass.bearingDegrees(from.latitude, from.longitude, stop.latitude, stop.longitude)
        return kotlin.math.abs(Compass.relative(bearing, way)) < SPREAD_DEGREES
    }

    // The stop as the Planner is asked via it: by its stop pair, both sides of the road, where it has one.
    private fun key(stop: StopLocation): String = stop.clusterId.takeIf { it.startsWith(STOP_PAIR_PREFIX) } ?: stop.id

    // A bus line that runs by day: a night bus ("N" and a number) mostly shares its stops with day
    // lines, and one of its own would be a request for a bus that may not be running.
    private fun isDayBus(line: LineRef): Boolean =
        line.mode.equals(FartherBuses.MODE, ignoreCase = true) && line.id.isNotBlank() &&
            !(line.id.length > 1 && line.id[0].equals('n', ignoreCase = true) && line.id[1].isDigit())

    private fun meters(a: Coordinates, b: Coordinates): Double = NearestStops.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    private const val STOP_PAIR_PREFIX = "490G"
}
