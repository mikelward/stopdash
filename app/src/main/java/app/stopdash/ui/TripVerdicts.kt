package app.stopdash.ui

import androidx.annotation.VisibleForTesting
import app.stopdash.domain.Departure
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.LineSequence
import app.stopdash.domain.TripLeg
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Each live train's verdict on a trip leg: whether its line's route takes it where the leg gets off
 * ([DirectTrips.judge]), and whether it leaves along the leg ([leavesAlongLeg]). Matching a train
 * against a line with many routes (a main-line railway's) is far too slow for the main thread
 * (AGENTS.md *Main thread: read and dispatch only*), so the trip page works verdicts out on its worker
 * ([warm]) and only reads them as it renders ([get]); a train not judged yet reads as its route still
 * being checked.
 *
 * A verdict turns on where a train is going, not when, so it holds for every later prediction of the
 * same service until the line's route is loaded afresh.
 */
internal object TripVerdicts {
    class Verdict(val reach: DirectTrips.Verdict, val leaves: Boolean?)

    // A leg as its verdicts see it: where it boards and gets off, on which line, and its next stop.
    private data class LegKey(
        val fromId: String,
        val fromName: String,
        val toId: String,
        val toName: String,
        val lineId: String,
        val mode: String,
        val next: String?,
    )

    // A leg's verdicts on one load of its line's route; another load starts afresh.
    private class Table(val route: LineSequence) {
        val verdicts = ConcurrentHashMap<Departure, Verdict>()
    }

    private val tables = ConcurrentHashMap<LegKey, Table>()

    /** Judges a train in place when it hasn't been warmed: for tests calling the trip page's helpers directly. */
    @VisibleForTesting
    @Volatile
    var onMiss: ((TripLeg, LineSequence, Departure) -> Verdict)? = null

    /** [train]'s verdict on [leg] over [route] (its line's), or null while it hasn't been judged ([warm]). */
    fun get(leg: TripLeg, route: LineSequence, train: Departure): Verdict? =
        tables[keyOf(leg)]?.takeIf { it.route === route }?.verdicts?.get(train.service()) ?: onMiss?.invoke(leg, route, train)

    /**
     * Judges each of [trains] on [leg] over [route] not judged yet; true when any was. Slow on a line
     * with many routes: on a worker only. While [active] says no (the warm-up was canceled, superseded
     * by one for a newer route load), it leaves the verdicts as they are, so a late finish can't put an
     * older route's in place of a newer one's.
     */
    fun warm(leg: TripLeg, route: LineSequence, trains: List<Departure>, active: () -> Boolean = { true }): Boolean {
        val key = keyOf(leg)
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
        val at = routeAt(leg, route)
        for (train in todo) {
            if (!active()) return false
            table.verdicts[train] = judge(leg, route, at, train)
        }
        return true
    }

    /**
     * Makes room for [legs] before they're warmed, all at once, so a warm-up never drops what it judged
     * earlier in the same batch: past [MAX_LEGS], every leg is judged afresh. While [active] says no,
     * it leaves the verdicts as they are.
     */
    fun makeRoom(legs: List<TripLeg>, active: () -> Boolean = { true }) {
        val added = legs.mapTo(HashSet(), ::keyOf).count { !tables.containsKey(it) }
        // Not for a canceled warm-up: it would clear what the newer one judged.
        if (tables.size + added > MAX_LEGS && active()) tables.clear()
    }

    /** [train]'s verdict on [leg] over [route], worked out in place. Slow: on a worker only. */
    fun compute(leg: TripLeg, route: LineSequence, train: Departure): Verdict = judge(leg, route, routeAt(leg, route), train)

    private fun routeAt(leg: TripLeg, route: LineSequence): LineSequence =
        DirectTrips.routeAt(route, leg.fromId, "", leg.fromName, listOf(DirectTrips.End(leg.toId, leg.toName)))

    private fun judge(leg: TripLeg, route: LineSequence, at: LineSequence, train: Departure): Verdict = Verdict(
        DirectTrips.judge(train, leg.fromId, at, setOf(leg.toId)),
        leavesAlongLeg(train, leg, mapOf(leg.lineId to route)),
    )

    private fun keyOf(leg: TripLeg) = LegKey(leg.fromId, leg.fromName, leg.toId, leg.toName, leg.lineId, leg.mode, leg.path.firstOrNull())

    // The train as a service: where it goes, not when it comes or which vehicle runs it.
    private fun Departure.service() = copy(expectedArrival = Instant.EPOCH, vehicleId = "")

    // More legs than a plan's routes and their other lines ever hold: past it, all are judged afresh.
    @VisibleForTesting
    const val MAX_LEGS = 256
}
