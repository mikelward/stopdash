package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The nearby stops the app last found around the rider, by stop id, so the widget and the watch
 * show only those from the stored departures ([app.stopdash.domain.DeparturesSnapshot.scopedTo]).
 *
 * Its own file, written only when the app resolves a nearby set, never by the departures writers:
 * the snapshot is saved only after a fetch succeeds, so a set kept with it would point at the old
 * place in exactly the failed-fetch case this exists for, and clearing the snapshot instead raced
 * those writers (PR #53). Each write replaces the whole set, so the last resolve wins.
 *
 * It is written as soon as the app resolves a set, before that set's departures are saved, so it
 * can name stops the snapshot doesn't hold yet: where the rider is now. So it's kept out of Android
 * backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`): it describes this
 * moment only, and a restored one would scope another phone's widget by a place it isn't.
 *
 * A set that couldn't be written is still the one shown: it's kept in memory, ahead of the file,
 * until a write succeeds ([nearby]), so a storage failure leaves the widget and the watch scoped to
 * where the rider is in this process rather than to the place they left (Codex on #474).
 */
class DataStoreNearbySetStore internal constructor(
    private val dataStore: DataStore<PersistedNearbySet?>,
    // Sanitized log seam for a read that fails.
    private val warn: (String) -> Unit = {},
    // Where [keep] writes: the process's, not a screen's, so a write still retrying when the app's
    // screen closes carries on (Codex on #474).
    private val writer: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    // The write [keep] has running, canceled by the next one.
    private var writing: Job? = null

    /**
     * Make [stopIds] the current set and store it from the process's own scope, retrying until it's
     * stored ([saveUntilStored]) unless a newer set replaces it; [redraw] as there. Returns at once.
     */
    fun keep(stopIds: Set<String>, redraw: suspend () -> Unit) {
        synchronized(this) {
            writing?.cancel()
            writing = writer.launch { saveUntilStored(stopIds, redraw) }
        }
    }

    // The last set asked for whose write hasn't succeeded, shown ahead of the file meanwhile.
    private val unsaved = MutableStateFlow<Set<String>?>(null)

    /**
     * The current set, re-emitted on each change; null until the app has resolved one. A file that
     * can't be read is no stops rather than no set: the snapshot may be the place the rider left,
     * so it's shown scoped to nothing until the app stores a set again, never whole (Codex on #474).
     */
    fun nearby(): Flow<Set<String>?> =
        combine(
            // On a read error: no stops at once, then watch the file again (a wait doubling from
            // [FIRST_RETRY_MILLIS] up to [MAX_RETRY_MILLIS]), so a collector that lives as long as the
            // process sees the next set rather than staying on the fallback (Codex on #474).
            dataStore.data.retryWhen { e, attempt ->
                if (e is CancellationException) return@retryWhen false
                warn("nearby set unreadable (${e::class.simpleName}); showing no nearby stops")
                emit(PersistedNearbySet())
                delay(minOf(FIRST_RETRY_MILLIS shl attempt.coerceAtMost(8).toInt(), MAX_RETRY_MILLIS))
                true
            },
            unsaved,
        ) { stored, pending -> pending ?: stored?.stopIds?.toSet() }

    /**
     * The current set once, for a render: null when none is stored yet, and [Read.failed] when the
     * file couldn't be read, in which case the set is empty (no stops, never the whole snapshot) and
     * the caller should look again soon, since nothing else will tell it the file is readable again.
     */
    suspend fun read(): Read =
        try {
            Read(unsaved.value ?: dataStore.data.first()?.stopIds?.toSet(), failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("nearby set unreadable (${e::class.simpleName}); showing no nearby stops")
            Read(unsaved.value ?: emptySet(), failed = true)
        }

    data class Read(val stopIds: Set<String>?, val failed: Boolean)

    /**
     * Make [stopIds] the current nearby set, and store it; a set unchanged is left unwritten. Returns
     * whether it changed. A failed write throws, and the set stays current in this process.
     */
    suspend fun save(stopIds: Set<String>): Boolean {
        val wasUnsaved = unsaved.value
        unsaved.value = stopIds
        var changed = false
        dataStore.updateData { stored ->
            // Set on every run, since DataStore may re-run the transform.
            changed = stored?.stopIds?.toSet() != stopIds
            if (changed) PersistedNearbySet(stopIds.sorted()) else stored
        }
        unsaved.compareAndSet(stopIds, null)
        // A set shown from memory until now changed what was shown, even if the file already held it.
        return changed || (wasUnsaved != null && wasUnsaved != stopIds)
    }

    /**
     * [save] [stopIds] and [redraw] for it, trying again after either fails (waits doubling from
     * [FIRST_RETRY_MILLIS], capped at [MAX_RETRY_MILLIS]) until both are done or the caller is canceled
     * by a newer set. Without the retry a failed write would live only in memory, and the next process
     * would scope by the place the rider left; a redraw that couldn't be asked for would leave that
     * place up (Codex on #474). The redraw is owed when what's shown changes: a change stored, or a
     * failed write, since the set is shown from memory from then on.
     */
    suspend fun saveUntilStored(stopIds: Set<String>, redraw: suspend () -> Unit) {
        var wait = FIRST_RETRY_MILLIS
        var stored = false
        var redrawOwed = false
        // Once drawn for this set, a later retry's success doesn't draw it again.
        var redrawn = false
        while (true) {
            if (!stored) {
                try {
                    if (save(stopIds) && !redrawn) redrawOwed = true
                    stored = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("nearby set save failed (${e::class.simpleName}); retrying in ${wait / 1000} s")
                    if (!redrawn) redrawOwed = true
                }
            }
            if (redrawOwed) {
                try {
                    redraw()
                    redrawOwed = false
                    redrawn = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("widget redraw for a new nearby set failed (${e::class.simpleName}); retrying in ${wait / 1000} s")
                }
            }
            if (stored && !redrawOwed) return
            delay(wait)
            wait = minOf(wait * 2, MAX_RETRY_MILLIS)
        }
    }

    companion object {
        const val FIRST_RETRY_MILLIS = 2_000L
        const val MAX_RETRY_MILLIS = 300_000L

        private const val FILE_NAME = "widget-nearby-set.json"

        @Volatile
        private var instance: DataStoreNearbySetStore? = null

        /**
         * The process-wide store: DataStore allows one instance per file per process, and the app,
         * the widget and the watch sync all read it in the app's process.
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreNearbySetStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreNearbySetStore(
                    DataStoreFactory.create(
                        serializer = NearbySetSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            // Replaced by an empty set, not by nothing: no set would show every stored
                            // stop, and the snapshot may be a place the rider has left. The app's next
                            // resolve writes the real one. Only the fact is logged, never the bytes.
                            warn("nearby set file was unreadable and has been replaced by an empty set")
                            PersistedNearbySet()
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                    warn,
                ).also { instance = it }
            }
    }
}

@Serializable
data class PersistedNearbySet(val stopIds: List<String> = emptyList())

/** [PersistedNearbySet] as JSON; an empty file is nothing stored, a corrupt one is reported as such. */
internal object NearbySetSerializer : Serializer<PersistedNearbySet?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedNearbySet? = null

    override suspend fun readFrom(input: InputStream): PersistedNearbySet? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedNearbySet.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            // Generic message: the exception could quote the bytes.
            throw CorruptionException("nearby set could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedNearbySet?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedNearbySet.serializer(), t).encodeToByteArray())
    }
}
