package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.Starred
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StarredRowSet
import app.stopdash.domain.StarredRowsStore
import app.stopdash.domain.Workers
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [StarredRowsStore] (mirrors [DataStoreWatchedStopsStore]). DataStore
 * serializes reads and writes to one file and survives process death, and its [DataStore.data]
 * flow re-emits on every write — so a surface collecting [starred] re-orders the moment the
 * user stars or unstars a row, and a later launch reads the stars back.
 *
 * The store adds no off-device channel of its own: it is a private app file carrying the row
 * identities, the same as any on-device config. It rides Android backup / device-to-device
 * transfer like the rest of the app's data (SPEC §12 / *Privacy*), a platform path the user
 * controls, not data this code sends anywhere.
 */
class DataStoreStarredRowsStore internal constructor(
    private val dataStore: DataStore<PersistedStarredRows?>,
    // Sanitized log seam (no-op default): records the one notable non-happy write outcome —
    // preserving a newer-version file rather than overwriting it. A star identity carries a
    // stop/line id (canned identifiers, not user data — SPEC *Privacy*), but the message
    // stays a bare fact for consistency with the watched-stops store.
    private val warn: (String) -> Unit = {},
    // Where the stored file is turned into the app's values: work that grows with what's stored, never
    // on the collector's thread, which can be the main one (AGENTS.md *Main thread: read and dispatch only*).
    private val compute: CoroutineDispatcher = Workers.compute,
) : StarredRowsStore {

    // Absent/discarded (null) → an empty set the user can add to. Present and readable → the
    // set. Present but a version this build can't read → Unavailable, kept distinct from empty
    // so a surface never treats a newer-version set as "nothing starred" (SPEC principle 2).
    override fun starred(): Flow<StarredRowSet> =
        dataStore.data.map { stored ->
            when {
                stored == null -> StarredRowSet.Loaded(emptySet())
                else -> stored.toDomain()?.let { StarredRowSet.Loaded(it) }
                    ?: StarredRowSet.Unavailable
            }
        }.flowOn(compute)

    override suspend fun toggle(row: StarredRow) {
        dataStore.updateData { stored ->
            if (stored != null && stored.toDomain() == null) {
                // Present but unreadable version — leave it exactly as it is rather than
                // downgrading it and erasing the user's stars (SPEC *never lose the user's work*).
                warn("starred rows file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                Starred.toggle(stored?.toDomain() ?: emptySet(), row).toPersisted()
            }
        }
    }

    companion object {
        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "starred-rows.json"

        @Volatile
        private var instance: DataStoreStarredRowsStore? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), so the [DataStore] is created once here and shared. Built
         * from the application context so it outlives any one Activity.
         *
         * [warn] is the sanitized log seam (no-op until the shared on-device logger lands): a
         * corrupt or truncated file is logged and then discarded by the corruption handler
         * rather than swallowed. Only the first caller's [warn] is used (process singleton).
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreStarredRowsStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreStarredRowsStore(
                    DataStoreFactory.create(
                        serializer = StarredRowsSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("starred rows file was unreadable and has been discarded")
                            null
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
 * Reads and writes [PersistedStarredRows] as JSON (mirrors [WatchedStopsSerializer]). An empty
 * file is "nothing saved yet" and reads back as null (→ an empty set); a **corrupt** one throws
 * [CorruptionException] rather than being taken for empty, so the store's corruption handler
 * logs it and replaces the file. `ignoreUnknownKeys` lets a set written by a newer build (extra
 * fields) still parse; a `version` mismatch is caught in [PersistedStarredRows.toDomain] and
 * read as unavailable without being corruption.
 */
internal object StarredRowsSerializer : Serializer<PersistedStarredRows?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedStarredRows? = null

    override suspend fun readFrom(input: InputStream): PersistedStarredRows? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedStarredRows.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            throw CorruptionException("starred rows could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedStarredRows?, output: OutputStream) {
        if (t == null) return
        output.write(
            json.encodeToString(PersistedStarredRows.serializer(), t).encodeToByteArray(),
        )
    }
}
