package app.stopdash.watch

import app.stopdash.data.WatchTrip
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.TripLeg
import app.stopdash.domain.abbreviateBranch
import app.stopdash.ui.BusPoleCues
import app.stopdash.ui.busPoleLabels
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * A trip on the way as the watch shows it ([WatchTrip]): its steps as the trip's screen lists them
 * ([OnTheWay.steps]), the one the rider is at, and the next ride's trains. Pure: the words come
 * from the caller, which has the phone's resources.
 */
internal object WatchTrips {
    /**
     * A pole a train boards at, for its label on the watch: [key] tells two poles apart, [name] is
     * what it's called when its own cues ([cues]) name nothing ([busPoleLabels]).
     */
    class Pole(val key: String, val name: String, val cues: BusPoleCues)

    /**
     * [trip] at [now], [title] and [detail] being what to do at its step now (the trip's screen's
     * own, [app.stopdash.ui.nextStepText]). [stepText] names a step: a walk, boarding a ride, or
     * getting off it ([OnTheWay.Step.onBoard]). [trains] are those taking the rider on the next ride
     * ([OnTheWay.upcomingRide]), already kept to the ones whose route reaches its stop; only those
     * still to come are sent, soonest first, at most [WatchTrip.TRAINS_CAP]. One leaving before [readyAt]
     * ([OnTheWay.readyAt]) is marked missed, and sent only where catchable ones leave room; [poleOf]
     * is the pole each boards at, or null for none to name ([WatchTrip.Train.stop]). Built on [dispatcher]:
     * it walks every step and train, so a caller on the main thread never does that there.
     */
    suspend fun build(
        trip: ActiveTrip,
        title: String,
        detail: String,
        trains: List<Departure>,
        now: Instant,
        trainsNote: String = "",
        readyAt: Instant? = null,
        poleOf: (Departure) -> Pole? = { null },
        topology: RouteTopology = RouteTopology.EMPTY,
        // [trains] are from a board too old to stand behind: sent as [WatchTrip.oldDepartures].
        old: Boolean = false,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
        stepText: (leg: TripLeg, onBoard: Boolean) -> String,
    ): WatchTrip = kotlinx.coroutines.withContext(dispatcher) { assemble(trip, title, detail, trains, now, trainsNote, readyAt, poleOf, topology, old, stepText) }

    // [build]'s work, on whichever thread calls it.
    private fun assemble(
        trip: ActiveTrip,
        title: String,
        detail: String,
        trains: List<Departure>,
        now: Instant,
        // What the trip's screen says of [trains] (failed, updating, loading, none), or "".
        trainsNote: String = "",
        readyAt: Instant?,
        poleOf: (Departure) -> Pole?,
        topology: RouteTopology,
        old: Boolean,
        stepText: (leg: TripLeg, onBoard: Boolean) -> String,
    ): WatchTrip {
        val steps = OnTheWay.steps(trip)
        val legs = trip.route.legs
        // An arrival kept because forgetting the trip failed is past the last step: shown at the last.
        val current = OnTheWay.stepsDone(trip).coerceAtMost(steps.lastIndex).coerceAtLeast(0)
        val ride = OnTheWay.upcomingRide(trip)
        // The ride ahead is the leg the rider is at, or the one after the walk they're on.
        val rideLeg = when {
            ride == null -> -1
            trip.leg == ride -> trip.legIndex
            else -> trip.legIndex + 1
        }
        val departuresAt = if (rideLeg < 0) -1 else steps.indexOf(OnTheWay.Step(rideLeg))
        val upcoming = if (departuresAt < 0) emptyList() else Countdown.upcoming(trains, now)
        val shown = sent(upcoming, readyAt)
        val stops = labels(shown.mapNotNull(poleOf))
        val sentTrains = shown.map {
            // Shortened as the boards say it ("Brixton", not "Brixton Underground Station").
            val label = DepartureLabels.destinationLabel(it.destination, it.direction) ?: it.destination
            // With its branch where it's a choice from this stop ("Morden/Bank"), as the tile, the
            // widget and the trip's screen name it: two branches' trains read apart (maintainer, 2026-10-06).
            val stop = poleOf(it)?.key?.ifBlank { null } ?: ride?.fromId.orEmpty()
            // As the trip's board labels it: only where it changes the ride to where they get off.
            val branch = (ride?.toId?.let { to -> topology.rideGrouping(it.lineId, stop, to, it.destination, it.branch) }
                ?: topology.grouping(it.lineId, stop, it.destination, it.branch)).label
            // Shortened as the widget shortens it ("Newbury Pk"): the watch and the trip widget are narrow too.
            val destination = if (branch != null) "$label/${abbreviateBranch(branch)}" else label
            val missed = readyAt != null && it.expectedArrival.isBefore(readyAt)
            WatchTrip.Train(it.lineId, it.lineName, it.mode, destination, it.expectedArrival.toEpochMilli(), poleOf(it)?.let { pole -> stops[pole.key] }.orEmpty(), missed)
        }
        return WatchTrip(
            title = title,
            detail = detail,
            steps = steps.map { step ->
                val leg = legs[step.leg]
                WatchTrip.Step(
                    text = stepText(leg, step.onBoard),
                    lineId = if (leg.isWalk) "" else leg.lineId,
                    lineName = if (leg.isWalk) "" else leg.lineName,
                    mode = leg.mode,
                    walk = leg.isWalk,
                )
            },
            current = current,
            departures = if (old) emptyList() else sentTrains,
            oldDepartures = if (old) sentTrains else emptyList(),
            departuresAt = if (upcoming.isEmpty() && trainsNote.isEmpty()) -1 else departuresAt,
            departuresNote = if (departuresAt < 0) "" else trainsNote,
            sentAt = now.toEpochMilli(),
            startedAt = trip.startedAt.toEpochMilli(),
        )
    }

    // Each of [poles]' label by its key, among only the poles whose trains are sent: two that would read
    // the same fall back to their bearings ([busPoleLabels]), a pole with none sent can't force that on
    // one shown alone (Codex P2, #492), and one whose cues name nothing goes by its name.
    private fun labels(poles: List<Pole>): Map<String, String> {
        val distinct = poles.distinctBy { it.key }
        return distinct.zip(busPoleLabels(distinct.map { it.cues })).associate { (pole, label) -> pole.key to (label ?: pole.name) }
    }

    // The trains sent, soonest first: those the rider can catch first, then, where they leave room in
    // [WatchTrip.TRAINS_CAP], the latest of those leaving before [readyAt], so a train just missed
    // can't push a catchable one off the watch's few rows.
    private fun sent(upcoming: List<Departure>, readyAt: Instant?): List<Departure> {
        val sorted = upcoming.sortedBy { it.expectedArrival }
        val (missed, catchable) = sorted.partition { readyAt != null && it.expectedArrival.isBefore(readyAt) }
        val kept = catchable.take(WatchTrip.TRAINS_CAP)
        return (missed.takeLast(WatchTrip.TRAINS_CAP - kept.size) + kept).sortedBy { it.expectedArrival }
    }

    /**
     * The trip's writes to the Data Layer, run one at a time in the order they were asked for, on
     * [scope] rather than the asker's: a removal asked for as a trip ends ([enqueue], at once, from the
     * call) runs after every send that trip asked for and before any the next trip asks for, and a send
     * whose asker is cancelled still finishes before what's queued after it. So a removal never undoes
     * a newer trip's send, nor a send an older trip's removal, with nothing to compare. One write
     * failing, or cancelled on its own (a Data Layer task), is logged through [log] and the queue goes
     * on; only [scope]'s own cancellation stops it.
     */
    class OrderedWrites(scope: kotlinx.coroutines.CoroutineScope, private val log: (String) -> Unit = {}) {
        private val queue = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        init {
            scope.launch {
                for (op in queue) {
                    try {
                        op()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // The scope's own cancellation ends the queue; one write's doesn't.
                        ensureActive()
                        log("trip write canceled: ${e::class.simpleName}")
                    } catch (e: Exception) {
                        log("trip write failed: ${e::class.simpleName}")
                    }
                }
            }
        }

        /** Queues [op] now, without waiting for it. */
        fun enqueue(op: suspend () -> Unit) {
            queue.trySend(op)
        }

        /** Queues [op] now, and waits for it to have run. */
        suspend fun run(op: suspend () -> Unit) {
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            queue.trySend {
                try {
                    op()
                } finally {
                    done.complete(Unit)
                }
            }
            done.await()
        }
    }

    /**
     * Past this since the last send, an unchanged trip is sent again anyway: the watch reads one
     * not updated in two minutes as out of date, and a rider standing still changes nothing.
     */
    val HEARTBEAT: Duration = Duration.ofSeconds(60)

    /**
     * Whether [next] goes to the watch after [sent], [sinceSent] ago by the phone's monotonic clock
     * (a wall clock moved back would hold every heartbeat): when it changed, or a [HEARTBEAT] is due.
     */
    fun needsSend(sent: WatchTrip?, next: WatchTrip, sinceSent: Duration): Boolean =
        !sameAs(sent, next) || sinceSent >= HEARTBEAT

    /**
     * [needsSend] on [dispatcher]: comparing two trips walks every step and train, so a caller on
     * the main thread (the service following the trip) never does it there.
     */
    suspend fun needsSendOn(
        sent: WatchTrip?,
        next: WatchTrip,
        sinceSent: Duration,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    ): Boolean = kotlinx.coroutines.withContext(dispatcher) { needsSend(sent, next, sinceSent) }

    /** [trip]'s bytes without [WatchTrip.sentAt], for telling a change from a resend of the same. */
    fun sameAs(a: WatchTrip?, b: WatchTrip): Boolean = a != null && a.copy(sentAt = 0) == b.copy(sentAt = 0)
}
