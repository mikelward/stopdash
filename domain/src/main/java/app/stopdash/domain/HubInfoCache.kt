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

    operator fun get(interchangeId: String): HubInfo? = entries[interchangeId]

    operator fun contains(interchangeId: String): Boolean = entries.containsKey(interchangeId)

    /** Keeps [info] for [interchangeId] when it names the hub; a blank one is dropped. */
    operator fun set(interchangeId: String, info: HubInfo) {
        if (info.name.isNotBlank()) entries[interchangeId] = info
    }

    /**
     * [interchangeId]'s names: kept ones, else the answer of a lookup already in flight for it, else [fetch]'s,
     * kept when it names the hub. [fetch] handles its own failures; one canceled before it answers leaves
     * a waiting caller to fetch for itself.
     */
    suspend fun load(interchangeId: String, fetch: suspend () -> HubInfo): HubInfo {
        while (true) {
            entries[interchangeId]?.let { return it }
            val mine = CompletableDeferred<HubInfo>()
            val pending = inFlight.putIfAbsent(interchangeId, mine)
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
                this[interchangeId] = info
                mine.complete(info)
                return info
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(interchangeId, mine)
            }
        }
    }

    companion object {
        /** The one the app's screens and a trip on the way share. */
        val SHARED = HubInfoCache()
    }
}
