package app.stopdash.watch

import android.content.Context
import androidx.core.content.edit
import android.content.SharedPreferences
import app.stopdash.StopdashDebugLog
import app.stopdash.data.DataStoreDismissedAlertsStore
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.data.WatchComplicationRows
import app.stopdash.data.DataStoreStarredRowsStore
import app.stopdash.data.HiddenModesSetting
import app.stopdash.data.WatchPayload
import app.stopdash.data.WatchSyncContract
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.Dismissals
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StarredRowSet
import app.stopdash.widget.logWidgetSnapshotWarning
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** The Wearable Data Layer as a [WatchChannel]. */
class DataLayerWatchChannel(context: Context, private val generations: WriteGenerations) : WatchChannel {
    private val appContext = context.applicationContext

    override suspend fun watchInstalled(): Boolean =
        try {
            Wearable.getCapabilityClient(appContext)
                .getCapability(WatchSyncContract.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
                .await()
                .nodes
                .isNotEmpty()
        } catch (e: ApiException) {
            // No Wearable API on this phone (no Play services, or no Wear OS app): no watch, not a
            // failure to retry after every refresh. Anything else is a real failure.
            if (e.statusCode != CommonStatusCodes.API_NOT_CONNECTED) throw e
            false
        }

    override suspend fun put(payload: WatchPayload) {
        val request = PutDataMapRequest.create(WatchSyncContract.SNAPSHOT_PATH).apply {
            if (payload.asAsset) {
                dataMap.putAsset(WatchSyncContract.ENVELOPE_KEY, Asset.createFromBytes(payload.bytes))
            } else {
                dataMap.putByteArray(WatchSyncContract.ENVELOPE_KEY, payload.bytes)
            }
            // Identical bytes wouldn't change the item, so a forced republish would never reach
            // the watch; the publisher only writes on a change or a force, so this costs nothing.
            dataMap.putLong(WatchSyncContract.GENERATION_KEY, generations.next())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request).await()
    }
}

/**
 * The Data Layer write generation ([WatchSyncContract.GENERATION_KEY]), in app-private preferences:
 * a counter, so every write differs from the last. Only a change marker, never an ordering.
 */
class WriteGenerations(private val prefs: SharedPreferences) {
    fun next(): Long {
        val generation = prefs.getLong(KEY, 0L) + 1
        prefs.edit().putLong(KEY, generation).apply()
        return generation
    }

    private companion object {
        const val KEY = "write_generation"
    }
}

/** The last-published hash in app-private preferences. */
class PrefsPublishMarker(private val prefs: SharedPreferences) : PublishMarker {
    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(hash: String) {
        prefs.edit().putString(KEY, hash).apply()
    }

    private companion object {
        const val KEY = "published_hash"
    }
}

/**
 * The rows each paired watch's complications are set to, as it last synced them
 * ([WatchSyncContract.COMPLICATION_ROWS_PATH]), kept per watch (its Data Layer node) so one watch's
 * sync never drops another's picks: row identities only. Every envelope holds the union, even when
 * departures reorder. A watch's set is replaced whole by its sync, and dropped when it deletes it.
 */
object ComplicationRowsStore {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("watch_complication_rows", Context.MODE_PRIVATE)

    /** Every watch's rows together; a set that can't be read counts as none. */
    fun load(context: Context): Set<StarredRow> =
        prefs(context).all.values.flatMapTo(LinkedHashSet()) { stored ->
            (stored as? String)?.let { WatchComplicationRows.decode(it.encodeToByteArray()) }
                ?: emptySet<StarredRow>().also { StopdashDebugLog.warning("watch: complication rows unreadable") }
        }

    /**
     * Takes a watch's rows item ([deleted]: it removed it), keeping its rows by the node that wrote
     * it. An unreadable item keeps what's stored rather than drop the watch's rows. True when they
     * changed; an item on another path is ignored.
     */
    fun ingest(context: Context, item: DataItem, deleted: Boolean): Boolean = ingest(context, item, deleted, since = null)

    /** [ingest], but skipped for a node whose rows were saved since the [since] generations. */
    private fun ingest(context: Context, item: DataItem, deleted: Boolean, since: Map<String, Long>?): Boolean {
        if (item.uri.path != WatchSyncContract.COMPLICATION_ROWS_PATH) return false
        val node = item.uri.host ?: return false
        val rows = if (deleted) {
            emptySet()
        } else {
            DataMapItem.fromDataItem(item).dataMap.getByteArray(WatchSyncContract.COMPLICATION_ROWS_KEY)
                ?.let(WatchComplicationRows::decode)
        }
        if (rows == null) {
            StopdashDebugLog.warning("watch: complication rows unreadable")
            return false
        }
        return if (since == null) save(context, node, rows) else saveIfUnchanged(context, node, rows, since)
    }

    /**
     * Relearns every watch's rows from the items the Data Layer already holds: a phone whose data
     * was cleared, or that gained this after the watch wrote them, gets no change event for an
     * unchanged item. Once per process start, in the background; true when any changed. A watch
     * whose change event was saved while the lookup ran keeps that newer set, never the item the
     * lookup read before it.
     */
    suspend fun recover(context: Context): Boolean {
        val since = generations()
        val items = Wearable.getDataClient(context.applicationContext).dataItems.await()
        return try {
            items.map { ingest(context, it, deleted = false, since = since) }.any { it }
        } finally {
            items.release()
        }
    }

    /** Each node's count of saves in this process: what [saveIfUnchanged] compares against. */
    private val generation = HashMap<String, Long>()

    /** A copy of every node's save count now, to pass to [saveIfUnchanged] later. */
    fun generations(): Map<String, Long> = synchronized(generation) { generation.toMap() }

    /** Replaces [node]'s rows (none drops them); true when they changed. */
    fun save(context: Context, node: String, rows: Set<StarredRow>): Boolean = synchronized(generation) {
        generation[node] = (generation[node] ?: 0) + 1
        write(context, node, rows)
    }

    /**
     * [save], unless [node]'s rows were saved since [since] was taken ([generations]): a lookup's
     * older item never overwrites a newer change event. True when they changed.
     */
    fun saveIfUnchanged(context: Context, node: String, rows: Set<StarredRow>, since: Map<String, Long>): Boolean =
        synchronized(generation) {
            if (generation[node] != since[node]) return@synchronized false
            write(context, node, rows)
        }

    private fun write(context: Context, node: String, rows: Set<StarredRow>): Boolean {
        val prefs = prefs(context)
        val encoded = WatchComplicationRows.encode(rows).decodeToString().takeIf { rows.isNotEmpty() }
        if (prefs.getString(node, null) == encoded) return false
        prefs.edit { if (encoded == null) remove(node) else putString(node, encoded) }
        return true
    }
}

/** The first wait before re-reading a failed dismissed set for the watch; doubled each time. */
private const val DISMISSED_RETRY_MS = 500L

/** The ceiling on that wait: a store that keeps failing is retried quietly, never given up on. */
private const val DISMISSED_RETRY_MAX_MS = 30_000L

/**
 * This dismissed set as a publish cue ([WatchSync.dismissedChanges]): the first failure of a run
 * gives an empty set, so the cue never holds back the other inputs and the watch republishes
 * with nothing hidden, then the read is retried with capped backoff for as long as it's
 * collected. A read that succeeds ends the run, so a later failure gives an empty set again and
 * starts the backoff afresh.
 */
internal fun Flow<Dismissals>.asPublishCue(
    retryMs: Long = DISMISSED_RETRY_MS,
    maxRetryMs: Long = DISMISSED_RETRY_MAX_MS,
    log: (String?) -> Unit,
): Flow<Dismissals> = flow {
    var failures = 0
    emitAll(
        onEach { failures = 0 }.retryWhen { e, _ ->
            if (e is CancellationException) return@retryWhen false
            log(e::class.simpleName)
            if (failures == 0) emit(Dismissals.NONE)
            delay((retryMs shl failures.coerceAtMost(6)).coerceAtMost(maxRetryMs))
            failures++
            true
        },
    )
}

/**
 * Wires the phone's watch sync (dev-docs/wear-os.md *Sync*): every stored snapshot and every star
 * change is published once it settles, a failure is retried by [WatchPublishWorker], and a watch
 * that reconnects gets the current snapshot again ([PhoneWearListenerService]).
 */
object WatchSync {
    /** One publish at a time in the process, the collector's and the worker's alike, so an older
     *  envelope can never land after a newer one. */
    private val publishing = Mutex()

    @Volatile
    private var publisher: WatchPublisher? = null

    private fun publisher(context: Context): WatchPublisher =
        publisher ?: synchronized(this) {
            publisher ?: run {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences("watch_sync", Context.MODE_PRIVATE)
                WatchPublisher(
                    DataLayerWatchChannel(appContext, WriteGenerations(prefs)),
                    PrefsPublishMarker(prefs),
                    log = { StopdashDebugLog.warning("watch: %s", it) },
                ).also { publisher = it }
            }
        }

    /**
     * Publishes what is stored now, read under the process-wide publish lock, so whichever of the
     * collector and the worker runs last sends the newest snapshot, stars and hidden modes.
     */
    suspend fun publishCurrent(context: Context, force: Boolean, emptyIfNone: Boolean = false): WatchPublisher.Outcome {
        val appContext = context.applicationContext
        return publishing.withLock {
            val (snapshot, stars) = try {
                // Dismissals are applied here, where the envelope is built, rather than stored with
                // the snapshot ([DeparturesSnapshot.withDismissals]).
                snapshots(appContext).first()?.withDismissals(dismissals(appContext).first()) to starred(appContext).first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A store read failed: a failed publish, which the worker retries, never a crash.
                StopdashDebugLog.warning("watch: stored state unreadable: %s", e::class.simpleName)
                return@withLock WatchPublisher.Outcome.Failed
            }
            // The same in-process setting the widget reads, so the watch leaves out what it does;
            // and the rows the watch's complications are set to, kept in the envelope after stars.
            publisher(appContext).publish(
                snapshot,
                stars,
                HiddenModesSetting.loaded(),
                selected = ComplicationRowsStore.load(appContext),
                force = force,
                emptyIfNone = emptyIfNone,
            )
        }
    }

    fun snapshots(context: Context): Flow<DeparturesSnapshot?> =
        DataStoreSnapshotStore.from(context.applicationContext, warn = ::logWidgetSnapshotWarning).snapshots()

    /** The starred rows; a set this build can't read counts as none, never as a reason not to publish. */
    fun starred(context: Context): Flow<Set<StarredRow>> =
        // The store is a process singleton that keeps its first caller's sink, and this runs first.
        DataStoreStarredRowsStore.from(context.applicationContext, warn = { StopdashDebugLog.warning("stars: %s", it) }).starred()
            .map { (it as? StarredRowSet.Loaded)?.starred ?: emptySet() }

    private fun dismissedStore(context: Context) =
        DataStoreDismissedAlertsStore.from(context.applicationContext, warn = { StopdashDebugLog.warning("watch: %s", it) })

    /**
     * The alerts the user dismissed in the app, for one publish. An unreadable set counts as none:
     * the watch then shows the mark rather than hide a warning (SPEC principle 2), and the failure
     * is logged.
     */
    fun dismissals(context: Context): Flow<Dismissals> =
        dismissedStore(context).dismissals()
            .catch { e ->
                if (e is CancellationException) throw e
                StopdashDebugLog.warning("watch: dismissed alerts unreadable: %s", e::class.simpleName)
                emit(Dismissals.NONE)
            }

    /**
     * The dismissed set as a publish cue: a read that fails is retried with capped backoff rather
     * than ending the flow, which would leave every later dismissal off the watch until restart.
     * The first failure also gives an empty set, since the publish cue waits for every input and
     * an unreadable file must not hold back the snapshot and stars; the publish itself reads the
     * set again, counting a failure as none.
     */
    fun dismissedChanges(context: Context): Flow<Dismissals> =
        dismissedStore(context).dismissals().asPublishCue { StopdashDebugLog.warning("watch: dismissed alerts unreadable, retrying: %s", it) }

    /** Starts publishing for the life of the process. */
    fun start(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            // A failed lookup is retried with backoff: an unchanged item never raises a change event,
            // so nothing else would bring the rows back. Past the last wait, the stored rows stand
            // until the next start or the watch's next change.
            WatchPublisher.keepCollecting(log = { StopdashDebugLog.warning("watch: complication rows lookup: %s", it) }) {
                if (ComplicationRowsStore.recover(appContext)) WatchPublishWorker.enqueue(appContext, force = false)
            }
        }
        scope.launch(Dispatchers.IO) {
            // A store that can't be read stops the collection, never the app; it's restarted a few
            // times with backoff, then left to the next start (or a watch reconnecting). The watch
            // keeps its last envelope meanwhile, which ages to stale on its own clock.
            WatchPublisher.keepCollecting(log = { StopdashDebugLog.warning("watch: %s", it) }) {
                // Each settled change is a cue; the publish itself reads the latest stored state.
                WatchPublisher.requests(snapshots(appContext), starred(appContext), HiddenModesSetting.changes, dismissedChanges(appContext)).collect {
                    if (publishCurrent(appContext, force = false) == WatchPublisher.Outcome.Failed) {
                        WatchPublishWorker.enqueue(appContext, force = false)
                    }
                }
            }
        }
    }
}
