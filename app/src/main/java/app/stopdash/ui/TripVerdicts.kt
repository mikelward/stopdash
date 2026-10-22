package app.stopdash.ui

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import app.stopdash.domain.Departure
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.LegEnd
import app.stopdash.domain.LineSequence
import app.stopdash.domain.TripLeg
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Each live train's verdict on a trip leg: whether its line's route takes it where the leg gets off
 * ([DirectTrips.judge]), and whether it leaves along the leg ([leavesAlongLeg]). A leg is named with
 * where its ride gets off ([LegEnd], paired as [TripRideLines.endsOf] pairs it), so a leg two routes
 * get off at different places is judged apart for each (dev-docs/station-resolution.md, phase 2).
 * Matching a train against a line with many routes (a main-line railway's) is far too slow for the main thread
 * (AGENTS.md *Main thread: read and dispatch only*), so the trip page works verdicts out on its worker
 * ([warm]) and only reads them as it renders ([get]); a train not judged yet reads as its route still
 * being checked.
 *
 * A verdict turns on where a train is going, not when, so it holds for every later prediction of the
 * same service until the line's route is loaded afresh.
 */
internal object TripVerdicts {
    class Verdict(val reach: DirectTrips.Verdict, val leaves: Boolean?)

    // A leg as its verdicts see it: where it boards and gets off, on which line, its next stop, and
    // where its ride gets off.
    private data class LegKey(
        val fromId: String,
        val fromName: String,
        val toId: String,
        val toName: String,
        val lineId: String,
        val mode: String,
        val next: String?,
        val end: LegEnd,
    )

    // A leg's verdicts on one load of its line's route; another load starts afresh.
    private class Table(val route: LineSequence) {
        val verdicts = ConcurrentHashMap<Departure, Verdict>()
    }

    private val tables = ConcurrentHashMap<LegKey, Table>()

    /** A train's own calling points' answer for a leg ([DirectTrips.settledByStops]), as [byStops] reads it. */
    enum class ByStops { REACHES, MISSES, OPEN, UNJUDGED }

    // Each train's [ByStops] answer per stop it would get off at and its ride's end, worked out on the
    // worker ([warmStops]): the scan over its portions' stops grows with the board, so never on the main thread.
    private data class StopsKey(val toId: String, val end: LegEnd, val train: Departure)

    private val stops = ConcurrentHashMap<StopsKey, ByStops>()

    /**
     * Whether [train]'s own calling points settle [ride] ([DirectTrips.settledByStops]), as worked out
     * ([warmStops]): [ByStops.OPEN] for a train with none and no stops known missed ([Departure.missesStops]),
     * [ByStops.UNJUDGED] while one with either hasn't been. A lookup only.
     */
    fun byStops(ride: Pair<TripLeg, LegEnd>, train: Departure): ByStops {
        if (!knowsStops(train)) return ByStops.OPEN
        stops[stopsKey(ride, train)]?.let { return it }
        return onStopsMiss?.invoke(ride, train) ?: ByStops.UNJUDGED
    }

    /** Works out [byStops] for each of [trains] on [ride] not yet judged; true when any was. On a worker only. */
    @WorkerThread
    fun warmStops(ride: Pair<TripLeg, LegEnd>, trains: List<Departure>): Boolean {
        if (stops.size > MAX_STOPS) stops.clear()
        var warmed = false
        for (train in trains) {
            if (!knowsStops(train)) continue
            if (stops.putIfAbsent(stopsKey(ride, train), judgeStops(ride, train)) == null) warmed = true
        }
        return warmed
    }

    // Whether [train] says anything of its own stops: its calling points, or stops a board of the trains calling
    // there left it out of, which settle it with no route needed too (Codex, #1138).
    private fun knowsStops(train: Departure): Boolean = train.callingAt != null || train.missesStops.isNotEmpty()

    /** Answers [byStops] in place when it hasn't been warmed: for tests calling the trip page's helpers directly, as [onMiss]. */
    @VisibleForTesting
    @Volatile
    var onStopsMiss: ((Pair<TripLeg, LegEnd>, Departure) -> ByStops)? = null

    /** [train]'s [byStops] answer on [ride], worked out in place. On a worker only. */
    @WorkerThread
    fun judgeStops(ride: Pair<TripLeg, LegEnd>, train: Departure): ByStops = when (DirectTrips.settledByStops(train, ride)) {
        true -> ByStops.REACHES
        false -> ByStops.MISSES
        null -> ByStops.OPEN
    }

    /** Judges a train in place when it hasn't been warmed: for tests calling the trip page's helpers directly. */
    @VisibleForTesting
    @Volatile
    var onMiss: ((Pair<TripLeg, LegEnd>, LineSequence, Departure) -> Verdict)? = null

    /** [train]'s verdict on [ride] over [route] (its line's), or null while it hasn't been judged ([warm]). */
    fun get(ride: Pair<TripLeg, LegEnd>, route: LineSequence, train: Departure): Verdict? =
        tables[keyOf(ride)]?.takeIf { it.route === route }?.verdicts?.get(train.service()) ?: onMiss?.invoke(ride, route, train)

    /**
     * Judges each of [trains] on [ride] over [route] not judged yet; true when any was. Slow on a line
     * with many routes: on a worker only. While [active] says no (the warm-up was canceled, superseded
     * by one for a newer route load), it leaves the verdicts as they are, so a late finish can't put an
     * older route's in place of a newer one's.
     */
    @WorkerThread
    fun warm(ride: Pair<TripLeg, LegEnd>, route: LineSequence, trains: List<Departure>, active: () -> Boolean = { true }): Boolean {
        val key = keyOf(ride)
        val table = tables.compute(key) { _, held ->
            when {
                held?.route === route -> held
                // Checked here, atomically with the swap: a canceled warm-up never displaces the newer one.
                !active() -> held
                else -> Table(route)
            }
        }?.takeIf { it.route === route } ?: return false
        val todo = trains.map { it.service() }.distinct().filterNot { table.verdicts.containsKey(it) }
        if (todo.isEmpty()) return false
        val at = DirectTrips.routeAt(route, ride)
        for (train in todo) {
            if (!active()) return false
            table.verdicts[train] = judge(ride, route, at, train)
        }
        return true
    }

    /**
     * Makes room for [rides] before they're warmed, all at once, so a warm-up never drops what it judged
     * earlier in the same batch: past [MAX_LEGS], every leg is judged afresh. While [active] says no,
     * it leaves the verdicts as they are.
     */
    fun makeRoom(rides: List<Pair<TripLeg, LegEnd>>, active: () -> Boolean = { true }) {
        val added = rides.mapTo(HashSet(), ::keyOf).count { !tables.containsKey(it) }
        // Not for a canceled warm-up: it would clear what the newer one judged.
        if (tables.size + added > MAX_LEGS && active()) tables.clear()
    }

    /** [train]'s verdict on [ride] over [route], worked out in place. Slow: on a worker only. */
    @WorkerThread
    fun compute(ride: Pair<TripLeg, LegEnd>, route: LineSequence, train: Departure): Verdict =
        judge(ride, route, DirectTrips.routeAt(route, ride), train)

    private fun judge(ride: Pair<TripLeg, LegEnd>, route: LineSequence, at: LineSequence, train: Departure): Verdict = Verdict(
        DirectTrips.judge(train, ride, at),
        leavesAlongLeg(train, ride.first, mapOf(ride.first.lineId to route)),
    )

    private fun keyOf(ride: Pair<TripLeg, LegEnd>): LegKey {
        val (leg, end) = ride
        return LegKey(leg.fromId, leg.fromName, leg.toId, leg.toName, leg.lineId, leg.mode, leg.path.firstOrNull(), end)
    }

    private fun stopsKey(ride: Pair<TripLeg, LegEnd>, train: Departure) = StopsKey(ride.first.toId, ride.second, train.service())

    // The train as a service: where it goes, not when it comes or which vehicle runs it.
    private fun Departure.service() = copy(expectedArrival = Instant.EPOCH, vehicleId = "")

    // More legs than a plan's routes and their other lines ever hold: past it, all are judged afresh.
    @VisibleForTesting
    const val MAX_LEGS = 256

    // More trains' stop answers than a plan's boards ever hold: past it, all are judged afresh.
    private const val MAX_STOPS = 4096
}
