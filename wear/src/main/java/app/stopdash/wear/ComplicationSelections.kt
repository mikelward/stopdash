package app.stopdash.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.stopdash.data.WatchComplicationRows
import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchSyncContract
import app.stopdash.domain.StarredRow
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * The row each LDN Go complication is set to, by its instance id, as the user picked it (none: it
 * shows the default row). The set is synced to the phone ([WatchSyncContract.COMPLICATION_ROWS_PATH])
 * so the phone keeps those rows in every envelope; row identities only, never anything else.
 */
object ComplicationSelections {
    private const val TAG = "StopDash.Complication"
    private const val PREFS = "complication_rows"
    private val SYNC_TIMEOUT = 10.seconds
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The row complication [id] is set to, or null for the default. A saved pick that can't be read
     * (damaged, or from a newer build) is logged and shows the default, but is kept, not cleared,
     * so a build that reads it still has it.
     */
    fun get(context: Context, id: Int): StarredRow? =
        prefs(context).getString(id.toString(), null)?.let(::decodeOrLog)

    /** Guards each read-then-write of a pick, so a clear never removes a pick made meanwhile. */
    private val lock = Any()

    /** Sets complication [id] to [row] (null: back to the default), and syncs the set. */
    fun set(context: Context, id: Int, row: StarredRow?) {
        synchronized(lock) { write(context, id, row) }
        sync(context)
    }

    private fun write(context: Context, id: Int, row: StarredRow?) {
        prefs(context).edit { if (row == null) remove(id.toString()) else putString(id.toString(), encode(row)) }
    }

    /** Every row a complication is set to. */
    fun all(context: Context): Set<StarredRow> =
        prefs(context).all.values.mapNotNullTo(LinkedHashSet()) { (it as? String)?.let(::decodeOrLog) }

    /**
     * Clears [id]'s row when its stop has left the widget's scope: absent from a complete [envelope]
     * (no stops left out for size, and not merely failed to load). A stop the transfer ceiling left
     * out keeps its row, so it returns when a later envelope fits. True when it cleared one. The
     * check and the clear are one step, so a pick made meanwhile (in the picker) is never cleared.
     */
    fun clearIfGone(context: Context, id: Int, envelope: WatchEnvelope?): Boolean {
        val cleared = synchronized(lock) {
            val row = get(context, id) ?: return@synchronized false
            if (!isGone(row, envelope)) return@synchronized false
            write(context, id, null)
            true
        }
        if (cleared) sync(context)
        return cleared
    }

    /** Whether [row]'s stop has left the widget's scope, judged from [envelope] ([clearIfGone]). */
    fun isGone(row: StarredRow, envelope: WatchEnvelope?): Boolean {
        envelope ?: return false
        val present = envelope.stops.any { it.stopId == row.stopId } || row.stopId in envelope.missingStopIds
        return !present && envelope.omittedStops == 0
    }

    /** One rows write at a time, each reading the set as it starts, so the latest set lands last. */
    private val writing = Mutex()

    /**
     * Writes the current set to the phone, in the background. The Data Layer drops an unchanged
     * write, so calling this again (as each complication request does) costs nothing and heals a
     * sync that failed. A write lost with the process (a complication removed, then the process
     * killed before it finished) is healed by [syncNow] on the phone's next envelope.
     */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                put(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next change, complication request or envelope tries again; the phone keeps the last set.
                Log.w(TAG, "complication rows sync failed: ${e::class.simpleName}")
            }
        }
    }

    /**
     * [sync], blocking until the write is done or [SYNC_TIMEOUT] passes. For the Data Layer
     * listener, which Play services calls on its own background thread, never the main one; the
     * bound keeps a stalled write from holding up the next event.
     */
    fun syncNow(context: Context) {
        val appContext = context.applicationContext
        try {
            runBlocking { withTimeout(SYNC_TIMEOUT) { put(appContext) } }
        } catch (e: TimeoutCancellationException) {
            // The next envelope tries again; the phone keeps the last set.
            Log.w(TAG, "complication rows sync timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "complication rows sync failed: ${e::class.simpleName}")
        }
    }

    /** Writes the set as it is now, after any write already under way has finished. */
    private suspend fun put(context: Context) {
        writing.withLock { Wearable.getDataClient(context).putDataItem(request(context)).await() }
    }

    private fun request(context: Context) =
        PutDataMapRequest.create(WatchSyncContract.COMPLICATION_ROWS_PATH).apply {
            dataMap.putByteArray(WatchSyncContract.COMPLICATION_ROWS_KEY, WatchComplicationRows.encode(all(context)))
        }.asPutDataRequest().setUrgent()

    private fun encode(row: StarredRow): String = WatchComplicationRows.encode(setOf(row)).decodeToString()

    private fun decodeOrLog(stored: String): StarredRow? =
        WatchComplicationRows.decode(stored.encodeToByteArray())?.firstOrNull()
            ?: null.also { Log.w(TAG, "complication pick unreadable; showing the default row") }
}
