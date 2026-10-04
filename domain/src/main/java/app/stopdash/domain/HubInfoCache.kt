package app.stopdash.domain

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Interchanges' resolved names ([TflClient.hubInfo]), kept once TfL answers, so the list and a trip
 * on the way ask for each hub once between them, a lookup in flight included ([load]). Only a real
 * answer is kept: a failed or blank lookup is retried later rather than pinned. In memory only; hub
 * names are public TfL place names.
 */
class HubInfoCache {
    private val entries = ConcurrentHashMap<String, HubInfo>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<HubInfo>>()

    operator fun get(hubId: String): HubInfo? = entries[hubId]

    operator fun contains(hubId: String): Boolean = entries.containsKey(hubId)

    /** Keeps [info] for [hubId] when it names the hub; a blank one is dropped. */
    operator fun set(hubId: String, info: HubInfo) {
        if (info.name.isNotBlank()) entries[hubId] = info
    }

    /**
     * [hubId]'s names: kept ones, else the answer of a lookup already in flight for it, else [fetch]'s,
     * kept when it names the hub. [fetch] handles its own failures; one canceled before it answers leaves
     * a waiting caller to fetch for itself.
     */
    suspend fun load(hubId: String, fetch: suspend () -> HubInfo): HubInfo {
        while (true) {
            entries[hubId]?.let { return it }
            val mine = CompletableDeferred<HubInfo>()
            val pending = inFlight.putIfAbsent(hubId, mine)
            if (pending != null) {
                try {
                    return pending.await()
                } catch (e: CancellationException) {
                    // Ours canceled, rethrown; the fetcher's, asked again.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val info = fetch()
                this[hubId] = info
                mine.complete(info)
                return info
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(hubId, mine)
            }
        }
    }

    companion object {
        /** The one the app's screens and a trip on the way share. */
        val SHARED = HubInfoCache()
    }
}
