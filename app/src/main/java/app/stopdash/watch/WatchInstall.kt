package app.stopdash.watch

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import app.stopdash.StopdashDebugLog
import app.stopdash.data.WatchSyncContract
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

/** The paired watches as the install offer sees them, a seam so [WatchInstallOffer] is testable. */
interface WatchNodes {
    /** The IDs of the watches connected to the phone now. */
    suspend fun connected(): Set<String>

    /** The IDs of the paired watches, connected or not, that have StopDash installed. */
    suspend fun withApp(): Set<String>

    /** Opens StopDash's Play Store page on the watch [nodeId]. Throws if it can't be reached. */
    suspend fun openPlayStore(nodeId: String)
}

/**
 * Offers StopDash to a connected watch that doesn't have it (dev-docs/wear-os.md *Distribution*):
 * a watch app is otherwise found only by searching Play on the watch itself. [available] says
 * whether there's a watch to offer it to; it is false until [refresh] has run, and false when Play
 * services or the Wearable API aren't on this phone, so no phone without a watch ever sees the offer.
 */
class WatchInstallOffer(
    private val nodes: WatchNodes,
    private val log: (String) -> Unit,
    // Where the Play services answers are mapped and compared: never the caller's thread, which
    // is the main one when the app comes to the front (AGENTS.md *Main-safe by default*).
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    // How many times [available] has been worked out, either way. A question that waits behind the offer
    // (the widget card) waits for a check finished since the app last came to the front
    // ([watchesCheckedSince]), so the offer can't land in its place (Codex on #735).
    private val _checks = MutableStateFlow(0L)
    val checks: StateFlow<Long> = _checks.asStateFlow()

    /** Re-reads which connected watches lack the app. A failure reads as none, and is logged. */
    suspend fun refresh() {
        missingNow()
    }

    private suspend fun missingNow(): Set<String> {
        val now = try {
            withContext(worker) { nodes.connected() - nodes.withApp() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("install offer: watches unreadable: ${e::class.simpleName}")
            emptySet()
        }
        _available.value = now.isNotEmpty()
        _checks.value = _checks.value + 1
        return now
    }

    /**
     * Opens the Play Store page on every connected watch without the app; true when at least one
     * opened. A watch that couldn't be reached is logged, and false tells the caller to say so.
     * The watches are read again first, not taken from the last [refresh]: the offer shown can
     * predate a watch disconnecting or getting the app since, and no tap should go to such a watch.
     */
    suspend fun install(): Boolean {
        val targets = missingNow()
        if (targets.isEmpty()) {
            log("install offer: no watch to install on")
            return false
        }
        return withContext(worker) { openOn(targets) }
    }

    private suspend fun openOn(targets: Set<String>): Boolean {
        var opened = 0
        for (node in targets) {
            try {
                nodes.openPlayStore(node)
                opened++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("install offer: Play Store not opened on a watch: ${e::class.simpleName}")
            }
        }
        return opened > 0
    }
}

/** [WatchNodes] over the Wearable Data Layer and [RemoteActivityHelper]. */
class DataLayerWatchNodes(context: Context) : WatchNodes {
    private val appContext = context.applicationContext

    override suspend fun connected(): Set<String> =
        orNone { Wearable.getNodeClient(appContext).connectedNodes.await().mapTo(HashSet()) { it.id } }

    override suspend fun withApp(): Set<String> =
        orNone {
            Wearable.getCapabilityClient(appContext)
                .getCapability(WatchSyncContract.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
                .await()
                .nodes
                .mapTo(HashSet()) { it.id }
        }

    override suspend fun openPlayStore(nodeId: String) {
        val intent = Intent(Intent.ACTION_VIEW, "market://details?id=$PLAY_PACKAGE".toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        RemoteActivityHelper(appContext).startRemoteActivity(intent, nodeId).await()
    }

    /** No Wearable API on this phone (no Play services, or no Wear OS app): no watch, not a failure. */
    private suspend fun orNone(read: suspend () -> Set<String>): Set<String> =
        try {
            read()
        } catch (e: ApiException) {
            if (e.statusCode != CommonStatusCodes.API_NOT_CONNECTED) throw e
            emptySet()
        }

    private companion object {
        /** The Play listing both apps ship in; a debug build's suffix names no listing. */
        const val PLAY_PACKAGE = "app.stopdash"
    }
}

/** The process's one [WatchInstallOffer], shared by the near-me card and Settings. */
object WatchInstall {
    @Volatile
    private var offer: WatchInstallOffer? = null

    fun offer(context: Context): WatchInstallOffer =
        offer ?: synchronized(this) {
            offer ?: WatchInstallOffer(
                DataLayerWatchNodes(context),
                log = { StopdashDebugLog.warning("watch: %s", it) },
            ).also { offer = it }
        }
}

/** Awaits a [ListenableFuture] without blocking a thread, unwrapping its failure. */
private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                } catch (e: java.util.concurrent.CancellationException) {
                    cont.cancel(e)
                }
            },
            Runnable::run,
        )
        cont.invokeOnCancellation { cancel(false) }
    }

/**
 * Whether the watches have been checked since [start], the [WatchInstallOffer.checks] count taken as the
 * app came to the front (negative before then): an answer from an earlier visit can predate a watch
 * connected while StopDash was away.
 */
internal fun watchesCheckedSince(start: Long, checks: Long): Boolean = start >= 0 && checks > start
