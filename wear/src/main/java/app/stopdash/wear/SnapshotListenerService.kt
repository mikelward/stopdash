package app.stopdash.wear

import android.content.Context
import android.util.Log
import app.stopdash.data.WatchRefreshReply
import app.stopdash.data.WatchSyncContract
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Receives each snapshot the phone publishes (dev-docs/wear-os.md *The snapshot over the wire*)
 * and stores it for the watch's surfaces. The Data Layer calls this on a background thread.
 */
class SnapshotListenerService : WearableListenerService() {
    /** The phone's answer to a refresh request ([WatchRefresh]); one from a newer build is ignored. */
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WatchSyncContract.REFRESH_ACK_PATH ->
                WatchRefreshReply.decodeRequest(event.data)?.let { WatchRefresh.onAck(this, it) }
                    ?: Log.w(TAG, "refresh acknowledgement unreadable")
            WatchSyncContract.REFRESH_RESULT_PATH ->
                WatchRefreshReply.decode(event.data)?.let { WatchRefresh.onReply(this, it) }
                    ?: Log.w(TAG, "refresh answer unreadable")
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // The trip on the way: shown while it lasts, gone when the phone takes it off.
        val trip = events.filter { it.dataItem.uri.path == WatchSyncContract.TRIP_PATH }
        // Blocks, as the listener may: it runs on the Data Layer's background thread.
        runBlocking {
            // One node's item removed: what's left (another node's, after a phone change) stands, so
            // it's read back rather than the trip cleared outright ([WatchTripState.removed]).
            if (trip.any { it.type == DataEvent.TYPE_DELETED }) {
                WatchTripState.removed { WatchTripState.lookUp(this@SnapshotListenerService) }
            } else if (trip.isNotEmpty()) {
                // The changes as one batch: the latest received stands ([WatchTripState.prepared]).
                WatchTripState.ingest(trip.map { it.dataItem })
            }
        }
        val store = WatchEnvelopeStore.from(this)
        val ingested = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchSyncContract.SNAPSHOT_PATH }
            .count { event -> envelopeBytesRetrying(this, event.dataItem)?.let(store::ingest) == true }
        if (ingested > 0) WatchSurfaces.requestUpdate(this)
        // Re-sends the complications' rows (dropped by the Data Layer when unchanged): heals a
        // write lost with the process, such as the last complication's removal.
        if (ingested > 0) ComplicationSelections.syncNow(this)
    }

    private companion object {
        /** Waits before re-reading an envelope whose asset read failed; the phone counts its write
         *  done, so this event is the only cue until the next publish or the app's next start. */
        val READ_RETRIES_MS = listOf(1_000L, 3_000L)
    }

    /** [envelopeBytes], re-read a couple of times if it fails. Blocks, as the listener may. */
    private fun envelopeBytesRetrying(context: Context, item: DataItem): ByteArray? {
        envelopeBytes(context, item)?.let { return it }
        for (wait in READ_RETRIES_MS) {
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                Log.w(TAG, "envelope read retry interrupted")
                Thread.currentThread().interrupt()
                return null
            }
            envelopeBytes(context, item)?.let { return it }
        }
        return null
    }
}

/**
 * The envelope carried by [item]: inline, or read from its `Asset` when it was too big for one.
 * Null (logged) when it has neither or the asset can't be read. Blocks; call off the main thread.
 */
internal fun envelopeBytes(context: Context, item: DataItem): ByteArray? {
    val map = DataMapItem.fromDataItem(item).dataMap
    map.getByteArray(WatchSyncContract.ENVELOPE_KEY)?.let { return it }
    val asset = map.getAsset(WatchSyncContract.ENVELOPE_KEY) ?: run {
        Log.w(TAG, "snapshot item carries no envelope")
        return null
    }
    return try {
        Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset)).inputStream.use { it.readBytes() }
    } catch (e: ExecutionException) {
        Log.w(TAG, "envelope asset read failed: ${(e.cause ?: e)::class.simpleName}")
        null
    } catch (e: IOException) {
        Log.w(TAG, "envelope asset read failed: ${e::class.simpleName}")
        null
    } catch (e: InterruptedException) {
        Log.w(TAG, "envelope asset read interrupted")
        Thread.currentThread().interrupt()
        null
    }
}

/**
 * Ingests the phone's latest snapshot item if the Data Layer already holds one: covers a watch app
 * installed, or its data cleared, after the phone last published. False (logged) when the lookup,
 * or reading the envelope it found, failed, so the caller can retry. Blocks; call off the main
 * thread.
 */
internal fun ingestExisting(context: Context, store: WatchEnvelopeStore): Boolean {
    return try {
        // The listener delivers the phone's writes in order; anything it stores while this reads
        // is newer, so this one is kept only if nothing arrived since it started.
        val since = store.ingestCount()
        val items = Tasks.await(Wearable.getDataClient(context).dataItems)
        try {
            // An item whose envelope couldn't be read (an asset fetch failing, logged) is a
            // failed lookup, so the caller retries; one the store refuses or skips is not.
            var stored = false
            val read = items.filter { it.uri.path == WatchSyncContract.SNAPSHOT_PATH }
                .map { item -> envelopeBytes(context, item)?.also { if (store.ingest(it, ifNoneSince = since)) stored = true } }
                .all { it != null }
            // A lookup that brought a newer envelope re-renders the surfaces, whoever asked.
            if (stored) WatchSurfaces.requestUpdate(context)
            read
        } finally {
            items.release()
        }
    } catch (e: ExecutionException) {
        val cause = e.cause
        Log.w(TAG, "snapshot lookup failed: ${if (cause is ApiException) cause.statusCode else (cause ?: e)::class.simpleName}")
        false
    } catch (e: InterruptedException) {
        Log.w(TAG, "snapshot lookup interrupted")
        Thread.currentThread().interrupt()
        false
    }
}

private const val TAG = "StopDash.Watch"

/** The watch's glanceable surfaces, which render the stored envelope and are told when it changes. */
internal object WatchSurfaces {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookedUp = AtomicBoolean(false)

    /**
     * Looks up the item the Data Layer already holds, once per process (again later if it failed),
     * in the background: a surface added (or app data cleared) after the phone last published has
     * none stored. A newer envelope re-renders every surface ([ingestExisting]).
     */
    fun lookUpOnce(context: Context, store: WatchEnvelopeStore) {
        if (!lookedUp.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch { if (!ingestExisting(appContext, store)) lookedUp.set(false) }
    }

    /** The waits before each retry of [lookUpRetrying]; after the last, the next start (or a publish) tries again. */
    val LOOKUP_RETRIES = listOf(5.seconds, 15.seconds, 45.seconds)

    /**
     * Looks up the phone's latest item ([ingestExisting]), retrying a lookup that failed (logged) a
     * few times: for a screen while it's started, so a transient failure doesn't leave it empty.
     */
    suspend fun lookUpRetrying(context: Context, store: WatchEnvelopeStore) {
        var looked = ingestExisting(context, store)
        for (wait in LOOKUP_RETRIES) {
            if (looked) break
            delay(wait)
            looked = ingestExisting(context, store)
        }
    }

    /** Asks the tile and every StopDash complication to re-render from the stored envelope. */
    fun requestUpdate(context: Context) {
        StopDashTileService.requestUpdate(context)
        StopDashComplicationService.requestUpdate(context)
    }
}
