package app.stopdash.wear

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.stopdash.data.WatchSyncContract
import app.stopdash.data.WatchTrip
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * The trip on the way the phone last sent (dev-docs/wear-os.md *Trip on the way*), in memory: the
 * Data Layer keeps the item, so a watch app that starts again reads it back ([lookUp]) rather than
 * keeping a copy of where the rider is going on the watch's disk.
 */
object WatchTripState {
    private const val TAG = "StopDash.Watch"

    /** Past this since the phone sent it, the trip reads as out of date. */
    val STALE_AFTER: Duration = Duration.ofMinutes(2)

    /** Past this, the trip isn't shown: the phone stopped following it without saying so. */
    val GONE_AFTER: Duration = Duration.ofMinutes(15)

    private val _trip = MutableStateFlow<HeldTrip?>(null)
    val trip: StateFlow<HeldTrip?> = _trip.asStateFlow()

    // Counts the listener's updates, so a lookup that read the item before one landed can't undo it.
    private val lock = Any()
    private var events = 0L

    /** How many updates have landed: a [take] given this from before its read is applied only if none did since. */
    internal fun events(): Long = synchronized(lock) { events }

    /**
     * Holds [held] (null: no trip). [ifNoneSince] from [events] keeps an older read from undoing a
     * newer update: it's applied only if none landed since, and says whether it was.
     */
    internal fun take(held: HeldTrip?, ifNoneSince: Long? = null): Boolean = synchronized(lock) {
        if (ifNoneSince != null && events != ifNoneSince) return false
        if (ifNoneSince == null) events++
        _trip.value = held
        true
    }

    /**
     * Takes the trip [items] carry, chosen among several as [prepared] says (a lookup can find one
     * per phone node); none this build can read leaves the last ([WatchTrip.decode] logs
     * why). Everything but the hand-over runs on [dispatcher] ([prepared]): the app's startup lookup
     * calls this from the main thread, and reading, decoding and comparing a trip all grow with it.
     */
    suspend fun ingest(items: List<DataItem>, ifNoneSince: Long? = null, lookedUp: Boolean = false, dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        val next = prepared(items, lookedUp, _trip.value, dispatcher) ?: return
        take(next.held, ifNoneSince)
    }

    /** What [ingest] takes: [held], null for no trip. */
    internal class Prepared(val held: HeldTrip?)

    /**
     * The trip [items] carry as it would be held, worked out wholly on [dispatcher]; null when none
     * carries a trip this build can read (the last is kept). Phones' clocks can't be compared, so no
     * stamp picks between them (maintainer, 2026-10-03): sent ([lookedUp] false), the latest event
     * received wins, the last of a batch; read back, the held trip's phone (node) if it still has an
     * item, else any. A stale item from a phone left behind by a phone change is replaced within a
     * minute by the active phone's next update ([WatchTripSync]'s heartbeat). [lookedUp]: dated as
     * [lookedUpHeld] dates it, keeping [current]'s arrival time when it's the same trip.
     */
    internal suspend fun prepared(
        items: List<DataItem>,
        lookedUp: Boolean,
        current: HeldTrip?,
        dispatcher: CoroutineDispatcher,
        read: (DataItem) -> ByteArray? = { DataMapItem.fromDataItem(it).dataMap.getByteArray(WatchSyncContract.TRIP_KEY) },
        nodeOf: (DataItem) -> String = { it.uri.authority.orEmpty() },
        elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
        // The wall clock a trip read back is dated against ([lookedUpHeld]).
        clock: () -> Instant = Instant::now,
    ): Prepared? = withContext(dispatcher) {
        val trips = items.mapNotNull { item ->
            val bytes = read(item) ?: run {
                Log.w(TAG, "trip item carries no trip")
                return@mapNotNull null
            }
            WatchTrip.decode(bytes, dispatcher) { Log.w(TAG, it) }?.let { it to nodeOf(item) }
        }
        val (trip, node) = (if (lookedUp) trips.lastOrNull { it.second == current?.node } ?: trips.lastOrNull() else trips.lastOrNull())
            ?: return@withContext null
        val elapsed = elapsedNow()
        // Read back by a new process: its arrival time went with the old one, so it's dated by the
        // phone's stamp instead, and one already past [GONE_AFTER] stays gone. The trip already held
        // (the app reopened in this process) keeps its own arrival time.
        Prepared(if (lookedUp) lookedUpHeld(trip, clock(), elapsed, current, node) else HeldTrip(trip, elapsed, node = node))
    }

    /** The phone took the trip off: it ended, or isn't followed any more. */
    fun clear() {
        take(null)
    }

    /**
     * A node's trip item was removed. Another node's may remain (after a phone change), so what's
     * left is read back ([lookUp]): none there clears the trip, a newer one stands. A read that fails
     * clears it, as the removal asked, rather than leave an ended trip up for its fifteen minutes.
     */
    suspend fun removed(lookUp: suspend () -> Boolean) {
        if (!lookUp()) clear()
    }

    /**
     * Reads the phone's trip item back, for an app started after it arrived; none there is no trip.
     * Says whether it read. A failed lookup is logged and leaves what's held, and an update that lands while it reads wins.
     */
    suspend fun lookUp(context: Context): Boolean {
        // Any node's item at the path: the phone wrote it, under its own node id ("wear://*/path").
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority("*").path(WatchSyncContract.TRIP_PATH).build()
        return try {
            val since = events()
            val items = Wearable.getDataClient(context.applicationContext).getDataItems(uri).await()
            try {
                // Every node's: after a phone change there can be more than one ([prepared] picks).
                val found = items.toList()
                if (found.isEmpty()) take(null, ifNoneSince = since) else ingest(found, ifNoneSince = since, lookedUp = true)
            } finally {
                items.release()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "trip lookup failed: ${e::class.simpleName}")
            false
        }
    }

    /**
     * [lookUp], tried again after each of [waits] while it fails, as the snapshot's own lookup is
     * ([WatchSurfaces.lookUpRetrying]): a trip already on the Data Layer sends no new event, so a
     * failed read would otherwise leave it missing for as long as the app stays open.
     */
    suspend fun lookUpRetrying(context: Context, waits: List<kotlin.time.Duration> = WatchSurfaces.LOOKUP_RETRIES) {
        retrying(waits) { lookUp(context) }
    }

    /** Runs [attempt], and again after each of [waits] until one succeeds. */
    internal suspend fun retrying(waits: List<kotlin.time.Duration>, attempt: suspend () -> Boolean) {
        if (attempt()) return
        for (wait in waits) {
            kotlinx.coroutines.delay(wait)
            if (attempt()) return
        }
    }

    /**
     * [trip] as held when read back at [now] by a process that didn't see it arrive: dated by the
     * phone's stamp (none ahead of [elapsedNow]), or null, no trip, when that stamp is already past
     * [GONE_AFTER], so a restart doesn't bring back a trip that had gone. The same trip as [held]
     * keeps [held]'s arrival time: reopening the app doesn't restart a trip's time held, which a
     * stamp ahead of the watch's clock (an age of nothing) otherwise would, again and again.
     */
    internal fun lookedUpHeld(trip: WatchTrip, now: Instant, elapsedNow: Long, held: HeldTrip? = null, node: String = ""): HeldTrip? {
        if (held != null && held.trip == trip && held.node == node) return held
        val age = Duration.between(Instant.ofEpochMilli(trip.sentAt), now)
        if (age > GONE_AFTER) return null
        return HeldTrip(trip, elapsedNow - age.toMillis().coerceAtLeast(0), node = node)
    }

    /**
     * How [held] shows at [now] (and [elapsedNow], the watch's monotonic clock): as sent, marked out
     * of date past [STALE_AFTER] since the phone stamped it, when stamped ahead of the watch's clock
     * (which can't be trusted), or past [STALE_AFTER] held without an update; not at all past
     * [GONE_AFTER] held, whatever either clock says.
     */
    fun shown(held: HeldTrip?, now: Instant, elapsedNow: Long): ShownTrip? {
        held ?: return null
        // Since it arrived, by the watch's monotonic clock: what no clock change can hold back.
        val sinceArrival = Duration.ofMillis(elapsedNow - held.arrivedElapsed)
        if (sinceArrival > GONE_AFTER) return null
        val stamped = Duration.between(Instant.ofEpochMilli(held.trip.sentAt), now)
        val ahead = stamped.isNegative && stamped.abs() > Duration.ofMinutes(1)
        return ShownTrip(held.trip, stale = ahead || stamped > STALE_AFTER || sinceArrival > STALE_AFTER, stepsKey = held.stepsKey)
    }
}

/**
 * A trip the phone sent, and when it arrived ([SystemClock.elapsedRealtime]): the phone resends at
 * least once a minute while it follows the trip, so one held long without an update was let go.
 * [stepsKey] stands for its steps, worked out where it's built (off the main thread, [prepared]).
 *
 * Not a data class, on purpose: equal by identity, so the state flow holding it, and the screen's
 * keys on it, compare it in constant time on the main thread rather than walking the whole trip.
 * Each arrival is a new one; a lookup that reads back the trip held keeps the same one.
 */
class HeldTrip(val trip: WatchTrip, val arrivedElapsed: Long, val stepsKey: Int = trip.steps.hashCode(), val node: String = "")

/**
 * A trip as the watch shows it now: [stale] when the phone hasn't updated it lately. [stepsKey] is its
 * [HeldTrip.stepsKey], so the screen can tell new steps apart in constant time. Equal by identity, as
 * [HeldTrip] is, for the same reason.
 */
class ShownTrip(val trip: WatchTrip, val stale: Boolean, val stepsKey: Int = trip.steps.hashCode())
