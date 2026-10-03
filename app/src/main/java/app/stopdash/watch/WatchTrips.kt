package app.stopdash.watch

import app.stopdash.data.WatchTrip
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.TripLeg
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
     * [trip] at [now], [title] and [detail] being what to do at its step now (the trip's screen's
     * own, [app.stopdash.ui.nextStepText]). [stepText] names a step: a walk, boarding a ride, or
     * getting off it ([OnTheWay.Step.onBoard]). [trains] are those taking the rider on the next ride
     * ([OnTheWay.upcomingRide]), already kept to the ones whose route reaches its stop; only those
     * still to come are sent, soonest first, at most [WatchTrip.TRAINS_CAP]. One leaving before [readyAt]
     * ([OnTheWay.readyAt]) is marked missed, and sent only where catchable ones leave room; [stopOf]
     * names the pole each boards at, or "" ([WatchTrip.Train.stop]). Built on [dispatcher]:
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
        stopOf: (Departure) -> String = { "" },
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
        stepText: (leg: TripLeg, onBoard: Boolean) -> String,
    ): WatchTrip = kotlinx.coroutines.withContext(dispatcher) { assemble(trip, title, detail, trains, now, trainsNote, readyAt, stopOf, stepText) }

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
        stopOf: (Departure) -> String,
        stepText: (leg: TripLeg, onBoard: Boolean) -> String,
    ): WatchTrip {
        val steps = OnTheWay.steps(trip.route)
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
            departures = sent(upcoming, readyAt).map {
                // Shortened as the boards say it ("Brixton", not "Brixton Underground Station").
                val destination = DepartureLabels.destinationLabel(it.destination, it.direction) ?: it.destination
                val missed = readyAt != null && it.expectedArrival.isBefore(readyAt)
                WatchTrip.Train(it.lineId, it.lineName, it.mode, destination, it.expectedArrival.toEpochMilli(), stopOf(it), missed)
            },
            departuresAt = if (upcoming.isEmpty() && trainsNote.isEmpty()) -1 else departuresAt,
            departuresNote = if (departuresAt < 0) "" else trainsNote,
            sentAt = now.toEpochMilli(),
            startedAt = trip.startedAt.toEpochMilli(),
        )
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
