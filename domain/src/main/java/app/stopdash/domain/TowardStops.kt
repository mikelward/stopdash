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
 * stop within [REACH_METERS] that the walk there leaves no more than [SETBACK_METERS] farther from the
 * destination, so one stop asks for every line it is the nearest stop of, and a line's farther stops,
 * or one behind the rider, add no request. A stop off to the side counts: a bus from the end of the
 * street that runs straight to a station on the way is no less a bus towards the destination (one
 * from a stop square to the way there was missed while the cut was a bearing, maintainer's report,
 * 2026-11-10). Only a line that goes somewhere, one of its stops more than [REACH_METERS] nearer the
 * destination than the rider, picks a stop, so a sideways line that never heads there can't take a
 * request from one that does. A night bus picks no stop of its own, nor does a stop with no bus; at
 * most [MAX_ASKS], nearest first, so a street with many lines can't fan the plan out.
 */
object TowardStops {
    /** How far from the rider a stop is asked about: about a ten-minute walk at the average pace. */
    const val REACH_METERS = 800

    /**
     * How much farther from the destination the walk to a stop may leave the rider: about a three-minute
     * walk, so a stop behind them isn't asked about, while one to the side, or one where they stand
     * (the fix itself that uncertain), is.
     */
    const val SETBACK_METERS = 250

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
     * to [to], each line's way judged by its stops in [network] (every bus stop, as the bundled stops hold
     * them). None for a destination within [REACH_METERS]: it is a walk or a bus away, which the Planner's
     * own requests offer. Grows with [stops] and [network], so off the main thread.
     */
    @WorkerThread
    fun of(from: Coordinates, to: Coordinates, stops: List<StopLocation>, network: List<StopLocation>): List<Ask> {
        val away = meters(from, to)
        if (away <= REACH_METERS) return emptyList()
        val goes = goesToward(to, away, stops, network)
        val toward = stops.asSequence()
            .filter { it.latitude != 0.0 && it.longitude != 0.0 && it.lines.any { line -> line.id in goes } }
            .map { it to meters(from, Coordinates(it.latitude, it.longitude)) }
            .filter { (stop, distance) -> distance <= REACH_METERS && meters(position(stop), to) - away <= SETBACK_METERS }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
            .map { it.first }
            .toList()
        // Each day bus line's nearest stop, and the stops so picked in order, each once.
        val nearest = LinkedHashMap<String, String>()
        toward.forEach { stop -> stop.lines.filter { it.id in goes }.forEach { nearest.putIfAbsent(it.id, key(stop)) } }
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

    // The day bus lines of [stops] with a stop, of [stops] or [network], more than [REACH_METERS] nearer [to] than
    // the rider, [away] from it: a line that takes them farther than walking would.
    private fun goesToward(to: Coordinates, away: Double, stops: List<StopLocation>, network: List<StopLocation>): Set<String> {
        val lines = stops.flatMap { it.lines }.filter(::isDayBus).mapTo(HashSet()) { it.id }
        val goes = HashSet<String>()
        for (stop in stops.asSequence() + network.asSequence()) {
            if (stop.latitude == 0.0 && stop.longitude == 0.0) continue
            val ids = stop.lines.map { it.id }.filter { it in lines && it !in goes }
            if (ids.isNotEmpty() && meters(position(stop), to) < away - REACH_METERS) goes += ids
        }
        return goes
    }

    private fun position(stop: StopLocation) = Coordinates(stop.latitude, stop.longitude)

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
