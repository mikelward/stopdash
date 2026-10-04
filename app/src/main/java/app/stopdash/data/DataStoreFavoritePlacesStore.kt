package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaces
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.FavoritePlacesStore
import app.stopdash.domain.Workers
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [FavoritePlacesStore] (mirrors [DataStoreStarredRowsStore]). DataStore
 * serializes reads and writes to one file and survives process death, and its [DataStore.data] flow
 * re-emits on every write — so a surface collecting [places] updates the moment the user adds, edits
 * or removes a favorite, and a later launch reads them back.
 *
 * The store adds no off-device channel of its own: it is a private app file. Each favorite carries a
 * **coordinate** (SPEC D9), which rides Android backup / device-to-device transfer like the rest of
 * the app's config (SPEC §12 / *Privacy*), a platform path the user controls, not data this code
 * sends anywhere. The coordinate is never logged.
 */
class DataStoreFavoritePlacesStore internal constructor(
    private val dataStore: DataStore<PersistedFavoritePlaces?>,
    // Sanitized log seam (no-op default): records the one notable non-happy write outcome —
    // preserving a newer-version file rather than overwriting it. The message stays a bare fact and
    // never carries a coordinate (SPEC *Privacy*).
    private val warn: (String) -> Unit = {},
    // Where the stored file is turned into the app's values: work that grows with what's stored, never
    // on the collector's thread, which can be the main one (AGENTS.md *Main thread: read and dispatch only*).
    private val compute: CoroutineDispatcher = Workers.compute,
) : FavoritePlacesStore {

    // Absent (null) → an empty list the user can add to. A discard tombstone (written when a corrupt
    // file was replaced) → Discarded, so the loss is surfaced even across process death (the marker is
    // on disk, not in memory). Present and readable → the list. Present but a version this build can't
    // read → Unavailable, kept distinct from empty so a surface never treats a newer-version list as
    // "no favorites" (SPEC principle 2).
    override fun places(): Flow<FavoritePlacesSet> = flow {
        // Backoff + log state for a read-failure streak, reset on each successful emission below.
        var backoff = READ_RETRY_MILLIS
        var loggedThisOutage = false
        val mapped = dataStore.data
            .map { stored ->
                val domain = stored?.toDomain()
                when {
                    stored == null -> FavoritePlacesSet.Loaded(emptyList())
                    // A version this build can't read is Unavailable (preserved, not writable) even if it
                    // sets `discarded` — treating a newer-schema tombstone as writable would let a save
                    // no-op against it and drop the new draft (Codex). Check the version before discarded.
                    domain == null -> FavoritePlacesSet.Unavailable
                    stored.discarded -> FavoritePlacesSet.Discarded
                    else -> FavoritePlacesSet.Loaded(domain)
                }
            }
            // Only the reading and mapping: the retries below keep the collector's clock.
            .flowOn(compute)
            .onEach {
                backoff = READ_RETRY_MILLIS
                loggedThisOutage = false
            }
        // A transient I/O read failure is retried rather than collapsing the flow, so a long-lived
        // collector (the retained ViewModel) recovers once storage comes back. But an *unbounded* silent
        // retry would leave the screen stuck on "Loading…" forever if storage stays down; so on each
        // failure emit Unavailable — the honest "couldn't read" state (loaded, saves gated) — and keep
        // retrying (Codex). Retries use **capped exponential backoff** and log **once per outage**, so a
        // persistent failure doesn't churn the disk/log/battery every second for the retained ViewModel's
        // lifetime (SPEC *Cost and reliability*; Codex). A non-IO cause (e.g. a serializer bug) propagates.
        while (true) {
            try {
                emitAll(mapped)
                return@flow // the source completed (a live DataStore's flow does not)
            } catch (e: IOException) {
                if (!loggedThisOutage) {
                    warn("favorite places read failed, retrying: ${e::class.simpleName}")
                    loggedThisOutage = true
                }
                emit(FavoritePlacesSet.Unavailable)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(READ_RETRY_MAX_MILLIS)
            }
        }
    }

    override suspend fun save(place: FavoritePlace) {
        dataStore.updateData { stored ->
            if (stored.isNewerSchema()) {
                warn("favorite places file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                FavoritePlaces.upsert(stored?.toDomain() ?: emptyList(), place).toPersisted()
            }
        }
    }

    override suspend fun remove(id: String) {
        dataStore.updateData { stored ->
            if (stored.isNewerSchema()) {
                warn("favorite places file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                FavoritePlaces.remove(stored?.toDomain() ?: emptyList(), id).toPersisted()
            }
        }
    }

    // Present but unreadable version — leave it exactly as it is rather than downgrading it and
    // erasing the user's favorites (SPEC *never lose the user's work*).
    private fun PersistedFavoritePlaces?.isNewerSchema(): Boolean = this != null && toDomain() == null

    companion object {
        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "favorite-places.json"

        /** First backoff between retries of a failed read, doubled each attempt up to
         *  [READ_RETRY_MAX_MILLIS], so a brief glitch recovers fast but a persistent failure doesn't
         *  hot-loop (mirrors DataStoreAppSettings' 1s floor). */
        private const val READ_RETRY_MILLIS = 1_000L

        /** The retry-backoff ceiling: a persistent read failure settles to one attempt a minute rather
         *  than churning the disk/log/battery every second for the retained ViewModel's lifetime. */
        private const val READ_RETRY_MAX_MILLIS = 60_000L

        @Volatile
        private var instance: DataStoreFavoritePlacesStore? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), so the [DataStore] is created once here and shared. Built from
         * the application context so it outlives any one Activity. Only the first caller's [warn] is
         * used (process singleton).
         *
         * [warn] is **required** (no default): a favorite carries the user's home/work coordinate, so
         * a discarded corrupt or newer-schema file must never be lost silently (AGENTS.md *never fail
         * silently* / *Error handling*). The caller wires the app's sanitized logger, as the wiring of
         * [DataStoreStarredRowsStore.from] does with `::logStarWarning`.
         */
        fun from(context: Context, warn: (String) -> Unit): DataStoreFavoritePlacesStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreFavoritePlacesStore(
                    DataStoreFactory.create(
                        serializer = FavoritePlacesSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("favorite places file was unreadable and has been discarded")
                            // Replace the corrupt file with a durable discard tombstone (not just an
                            // empty file), so the loss is still surfaced after a restart until a save
                            // overwrites it (Codex).
                            PersistedFavoritePlaces(discarded = true)
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                    warn,
                ).also { instance = it }
            }
    }
}

/**
 * Reads and writes [PersistedFavoritePlaces] as JSON (mirrors [StarredRowsSerializer]). An empty
 * file is "nothing saved yet" and reads back as null (→ an empty list); a **corrupt** one throws
 * [CorruptionException] rather than being taken for empty, so the store's corruption handler logs it
 * and replaces the file. `ignoreUnknownKeys` lets a list written by a newer build (extra fields)
 * still parse; a `version` mismatch is caught in [PersistedFavoritePlaces.toDomain] and read as
 * unavailable without being corruption.
 */
internal object FavoritePlacesSerializer : Serializer<PersistedFavoritePlaces?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedFavoritePlaces? = null

    override suspend fun readFrom(input: InputStream): PersistedFavoritePlaces? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedFavoritePlaces.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            throw CorruptionException("favorite places could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedFavoritePlaces?, output: OutputStream) {
        if (t == null) return
        output.write(
            json.encodeToString(PersistedFavoritePlaces.serializer(), t).encodeToByteArray(),
        )
    }
}
