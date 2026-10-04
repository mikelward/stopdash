package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.AlertBehind
import app.stopdash.domain.AlertPlacement
import app.stopdash.domain.AlertsBehind
import app.stopdash.domain.AlertsBehindStore
import app.stopdash.domain.Workers
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [AlertsBehindStore] (mirrors [DataStoreDismissedAlertsStore]): the app's
 * verdicts that a bus alert lies wholly behind a stop, each stamped when the app last reached it and
 * standing for [AlertsBehind.MAX_AGE] after that. DataStore re-emits on every write, so the widget
 * redraws and the watch republishes when one is added.
 *
 * Anything this build can't read (a newer version, a corrupt file) reads as **no verdicts**: every
 * alert then flags, the safe direction (SPEC principle 2). Each entry is a line, a stop id, a way and
 * an alert's fingerprint: TfL's public data, nothing about the user beyond the stops already in the
 * stored snapshot; a private app file, sent nowhere by this code.
 */
class DataStoreAlertsBehindStore internal constructor(
    private val dataStore: DataStore<PersistedAlertsBehind?>,
    // Where the stored file is turned into the app's values: work that grows with what's stored, never
    // on the collector's thread, which can be the main one (AGENTS.md *Main thread: read and dispatch only*).
    private val compute: CoroutineDispatcher = Workers.compute,
    private val clock: () -> Instant = Instant::now,
) : AlertsBehindStore {

    override fun verdicts(): Flow<Set<AlertBehind>> =
        dataStore.data.map { stored -> AlertsBehind.standing(stored?.toDomain().orEmpty(), clock()) }.flowOn(compute)

    override suspend fun record(placement: AlertPlacement) {
        val now = clock()
        dataStore.updateData { stored ->
            // Even a placement that weighed nothing prunes the lapsed verdicts, so none outlives its
            // day on disk (Codex, PR #471). An unreadable file reads as none, so a write on top of it
            // starts a fresh readable set. Unchanged ([AlertsBehind.recorded] gives null) returns
            // what's stored, so DataStore skips the write and the re-emit.
            AlertsBehind.recorded(stored?.toDomain().orEmpty(), placement, now)?.toPersisted() ?: stored
        }
    }

    companion object {
        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "alerts-behind.json"

        @Volatile
        private var instance: DataStoreAlertsBehindStore? = null

        /**
         * The process-wide store: DataStore permits one active instance per file per process. [warn] is
         * the sanitized log seam for a corrupt file, which is logged and discarded.
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreAlertsBehindStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreAlertsBehindStore(
                    dataStore = DataStoreFactory.create(
                        serializer = AlertsBehindSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("alerts-behind file was unreadable and has been discarded")
                            null
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                ).also { instance = it }
            }
    }
}

/** The on-disk verdicts. An unknown [version] reads as none (every alert flags). */
@Serializable
internal data class PersistedAlertsBehind(
    val version: Int = CURRENT_VERSION,
    val verdicts: List<PersistedAlertBehind> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class PersistedAlertBehind(
    val lineId: String,
    val fingerprint: String,
    val stopId: String,
    val direction: String = "",
    // When the app last reached it, the wall clock's epoch millis.
    val atMillis: Long,
)

internal fun Map<AlertBehind, Instant>.toPersisted(): PersistedAlertsBehind =
    PersistedAlertsBehind(
        verdicts = entries
            .sortedWith(compareBy({ it.key.lineId }, { it.key.stopId }, { it.key.direction }, { it.key.fingerprint }))
            .map { (v, at) -> PersistedAlertBehind(v.lineId, v.fingerprint, v.stopId, v.direction, at.toEpochMilli()) },
    )

/** The verdicts with when each was reached, or null for a version this build doesn't know. */
internal fun PersistedAlertsBehind.toDomain(): Map<AlertBehind, Instant>? {
    if (version != PersistedAlertsBehind.CURRENT_VERSION) return null
    return verdicts.associate { AlertBehind(it.lineId, it.fingerprint, it.stopId, it.direction) to Instant.ofEpochMilli(it.atMillis) }
}

/** Reads and writes [PersistedAlertsBehind] as JSON; a corrupt file throws [CorruptionException]. */
internal object AlertsBehindSerializer : Serializer<PersistedAlertsBehind?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedAlertsBehind? = null

    override suspend fun readFrom(input: InputStream): PersistedAlertsBehind? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedAlertsBehind.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            throw CorruptionException("alerts behind could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedAlertsBehind?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedAlertsBehind.serializer(), t).encodeToByteArray())
    }
}
