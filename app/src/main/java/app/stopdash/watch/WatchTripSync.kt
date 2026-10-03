package app.stopdash.watch

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.stopdash.MainActivity
import app.stopdash.R
import app.stopdash.StopdashDebugLog
import app.stopdash.data.WatchSyncContract
import app.stopdash.data.WatchTrip
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.LineSequence
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.Staleness
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.ui.ActiveTripTracker
import app.stopdash.ui.BusPoleCues
import app.stopdash.ui.nextStepText
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Sends the trip on the way to a paired watch with the app (dev-docs/wear-os.md *Trip on the way*),
 * for as long as [app.stopdash.OnTheWayService] follows it: on each change, and every [TICK] so its
 * minutes count down, only when what the watch would show changed or a [WatchTrips.HEARTBEAT] is
 * due. The trip goes off the watch when
 * it ends ([clear]). Nothing leaves the phone without a watch that has the app.
 */
internal object WatchTripSync {
    /** How long a line's route that failed to load waits before it's tried again. */
    private val ROUTE_RETRY: Duration = Duration.ofMinutes(5)
    // The trains last left out for want of a route, so each set is logged once.
    @Volatile private var reportedMisses: Set<app.stopdash.domain.RouteMiss> = emptySet()
    // The lines whose route is loading, and a count of loads finished, which rebuilds the trip.
    private val routeLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val routesLoaded = kotlinx.coroutines.flow.MutableStateFlow(0)
    // When each line's route last failed to load, by the monotonic clock.
    private val routeFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** How often the trip is sent again with nothing new but its minutes: the trip's own refresh. */
    val TICK: Duration = Duration.ofSeconds(30)

    // [clear] outlives the service that calls it, which is stopping as it does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One write at a time, in the order asked for ([WatchTrips.OrderedWrites]).
    private val writes = WatchTrips.OrderedWrites(scope) { StopdashDebugLog.watchStatus(it) }

    // What was last sent, so an unchanged trip isn't sent again; null after a clear.
    private var sent: WatchTrip? = null
    // When [sent] went, by the monotonic clock ([SystemClock.elapsedRealtime]).
    private var sentElapsed = 0L

    /** Follows [tracker]'s trip until cancelled, sending each change ([WatchTrips.build]). */
    suspend fun follow(context: Context, tracker: ActiveTripTracker) {
        val app = context.applicationContext
        val channel = DataLayerWatchChannel(app, WriteGenerations(app.getSharedPreferences("watch_sync", Context.MODE_PRIVATE)))
        combine(tracker.trip, tracker.progress, tracker.updatedAt, tracker.nextBoard, merge(ticks(), routesLoaded.drop(1).map { })) { trip, progress, updatedAt, board, _ ->
            TripState(trip, progress, updatedAt, board)
        }.collectLatest { (trip, progress, updatedAt, board) ->
            if (trip == null) return@collectLatest
            // Before building it: no watch with the app, no route loads or work for nothing.
            val installed = try {
                channel.watchInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Tried again with the next change or tick.
                StopdashDebugLog.watchStatus("trip watch check failed: ${e::class.simpleName}")
                false
            }
            if (!installed) return@collectLatest
            val now = Instant.now()
            val (title, detail) = nextStepText(app.resources, progress, now, current = ActiveTripTracker.isCurrent(updatedAt, now))
            // The trains, their poles and the screen's note, all worked out on IO: nothing that grows
            // with the board runs on the service's main-thread scope.
            val (found, note) = withContext(Dispatchers.IO) { trainsFor(app, trip, board, now) }
            // A train leaving before the rider can board is grayed, as the trip's screen grays it.
            val readyAt = OnTheWay.readyAt(trip, progress)
            val built = WatchTrips.build(trip, title, detail, found.trains, now, note, readyAt, poleOf = { found.poles[it] }) { leg, onBoard ->
                stepText(app, leg, onBoard)
            }
            send(app, built)
        }
    }

    /**
     * Takes the trip off the watch, the trip having ended or stopped being followed. Queued at once,
     * behind that trip's sends and ahead of any the next trip makes.
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        writes.enqueue {
            try {
                // This phone's own item, by its node id ("wear://<node>/path"): a URI without one names
                // no item, and a wildcard would also take another phone's trip.
                val node = Wearable.getNodeClient(app).localNode.await().id
                val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority(node).path(WatchSyncContract.TRIP_PATH).build()
                val removed = Wearable.getDataClient(app).deleteDataItems(uri).await()
                if (sent != null) StopdashDebugLog.watchStatus(if (removed > 0) "trip taken off the watch" else "trip clear removed nothing")
                sent = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The watch also lets a trip go once the phone stops updating it.
                StopdashDebugLog.watchStatus("trip clear failed: ${e::class.simpleName}")
            }
        }
    }

    // Runs on [writes]' own scope, so a cancelled asker still lets it finish before what's queued next.
    private suspend fun send(context: Context, trip: WatchTrip) = writes.run {
        if (!WatchTrips.needsSendOn(sent, trip, Duration.ofMillis(SystemClock.elapsedRealtime() - sentElapsed))) return@run
        try {
            val bytes = WatchTrip.encode(trip)
            val request = PutDataMapRequest.create(WatchSyncContract.TRIP_PATH).apply {
                dataMap.putByteArray(WatchSyncContract.TRIP_KEY, bytes)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            if (sent == null) StopdashDebugLog.watchStatus("trip queued for the watch")
            sent = trip
            sentElapsed = SystemClock.elapsedRealtime()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tried again with the next change or tick; the watch marks a trip not updated as out of date.
            StopdashDebugLog.watchStatus("trip send failed: ${e::class.simpleName}")
        }
    }

    // The trains that take the rider on the next ride, from its boards (the boarding pole's and the
    // pair's other poles', each as the ride boards there), as the trip's screen keeps them
    // ([OnTheWay.boardTrains]), with what that screen says of them ([app.stopdash.ui.NextTrainsSection]):
    // a failed update over the last good board's rows, "Updating…" in place of a stale board's,
    // "Loading" before the first, and why none are listed. A line whose route isn't held yet is loaded
    // through the shared repository, so its trains aren't dropped for want of one ([sequences]); a failed
    // load is tried again after [ROUTE_RETRY], not on every tick.
    //
    // Each train maps to the pole it boards at ([pole]) when the pair has other poles read, as the
    // trip's screen heads each pole's trains; else to none. Keyed by identity: two poles can list equal
    // departures, and each keeps its own pole.
    private fun trainsFor(context: Context, trip: ActiveTrip, board: ActiveTripTracker.NextBoard?, now: Instant): Pair<FoundTrains, String> {
        val none = FoundTrains(emptyList(), emptyMap())
        val ride = OnTheWay.upcomingRide(trip) ?: return none to ""
        val res = context.resources
        if (board == null || board.ride != ride) return none to res.getString(R.string.on_the_way_trains_loading)
        val fetchedAt = board.fetchedAt ?: return none to res.getString(R.string.on_the_way_failed)
        val failed = board.failed || board.partial
        if (Staleness.isStale(fetchedAt, now)) {
            return none to res.getString(if (failed) R.string.on_the_way_failed else R.string.on_the_way_updating)
        }
        val routes = MainActivity.routeStops(context)
        val sequences = OnTheWay.boardLineIds(ride, board.boards.values.flatten()).let { sequences(routes, it) }
        val own = OnTheWay.boardTrains(ride, board.departures, fetchedAt, sequences, now)
        val others = board.others.map { other ->
            val there = ride.copy(fromId = other.pole.id, fromName = other.pole.name.ifBlank { ride.fromName })
            OnTheWay.boardTrains(there, other.departures, fetchedAt, sequences, now)
        }
        // Logged once per set, as the screen logs them once per change, not again every tick.
        val misses = (own.misses + others.flatMap { it.misses }).toSet()
        if (misses != reportedMisses) routes.reportMissesNow(misses)
        reportedMisses = misses
        val trains = java.util.IdentityHashMap<Departure, WatchTrips.Pole?>()
        val poles = board.others.isNotEmpty()
        own.trains.forEach { trains[it] = if (poles) pole(board.pole, ride.fromName) else null }
        others.zip(board.others).forEach { (found, other) -> found.trains.forEach { trains[it] = pole(other.pole, ride.fromName) } }
        val pending = own.pending || others.any { it.pending }
        val unresolved = own.unresolved || others.any { it.unresolved }
        val list = trains.keys.toList()
        val upcoming = Countdown.upcoming(list, now)
        val note = when {
            failed -> res.getString(R.string.on_the_way_failed)
            pending && upcoming.isEmpty() -> res.getString(R.string.on_the_way_trains_loading)
            pending -> res.getString(R.string.on_the_way_trains_checking)
            unresolved -> res.getString(R.string.on_the_way_trains_unchecked)
            upcoming.isEmpty() -> res.getString(R.string.on_the_way_trains_none, ride.toName)
            else -> ""
        }
        return FoundTrains(list, trains) to note
    }

    // [pole] as the watch labels its trains ([WatchTrips.Pole]): "Stop N" by its letter, else the towards
    // on its sign, else its bearing, among the poles whose trains are sent; else its name, else
    // [fallback] (the ride's own stop name).
    private fun pole(pole: StopLocation?, fallback: String): WatchTrips.Pole = WatchTrips.Pole(
        key = pole?.id.orEmpty(),
        name = pole?.name?.ifBlank { null } ?: fallback,
        cues = BusPoleCues(pole?.stopLetter.orEmpty(), pole?.towards.orEmpty(), pole?.bearing.orEmpty()),
    )

    // [lineId]'s route if known: held, or null when it couldn't be had lately (its trains are then
    // left out as unchecked). Absent while it loads, which reads as still being checked, as on the
    // trip's screen. The load runs on [scope], apart from the tick that asked, so a tick can't cancel
    // a slow one and restart it; when it lands, [routesLoaded] has the trip built again at once.
    private fun sequences(routes: RouteStopsRepository, lineIds: List<String>): Map<String, LineSequence?> {
        val known = HashMap<String, LineSequence?>()
        for (lineId in lineIds) {
            val held = routes.cached(lineId, "")
            if (held != null) {
                known[lineId] = held
                continue
            }
            val failed = routeFailedAt[lineId]
            if (failed != null && SystemClock.elapsedRealtime() - failed < ROUTE_RETRY.toMillis()) {
                known[lineId] = null
                continue
            }
            if (routeLoading.add(lineId)) scope.launch {
                try {
                    routes.load(lineId, "")
                    routeFailedAt.remove(lineId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TflException) {
                    // Logged (sanitized) by the repository; the trip goes without this line's trains until a retry.
                    routeFailedAt[lineId] = SystemClock.elapsedRealtime()
                } finally {
                    routeLoading.remove(lineId)
                    routesLoaded.value++
                }
            }
        }
        return known
    }

    // A step's words, as the trip's screen's list says them.
    private fun stepText(context: Context, leg: TripLeg, onBoard: Boolean): String = when {
        onBoard -> context.getString(R.string.on_the_way_ride_to, leg.toName)
        leg.isWalk || leg.fromName.isBlank() -> context.getString(R.string.on_the_way_walk, leg.toName)
        else -> context.getString(R.string.on_the_way_leg, leg.fromName, leg.toName)
    }

    private fun ticks() = flow {
        while (true) {
            emit(Unit)
            delay(TICK.toMillis())
        }
    }

    private data class TripState(
        val trip: ActiveTrip?,
        val progress: app.stopdash.domain.TripProgress?,
        val updatedAt: Instant?,
        val board: ActiveTripTracker.NextBoard?,
    )
}

// The next ride's trains ([WatchTripSync.trainsFor]), listed, and the pole each boards at by identity.
private class FoundTrains(val trains: List<Departure>, val poles: Map<Departure, WatchTrips.Pole?>)
