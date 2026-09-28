package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.Dismissals
import app.stopdash.domain.Dismissed
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [DismissedAlertsStore] (mirrors [DataStoreStarredRowsStore]). DataStore
 * serializes reads and writes to one file and survives process death, and its [DataStore.data] flow
 * re-emits on every write — so a card disappears the moment the user dismisses it and a later launch
 * reads the dismissals back.
 *
 * A set this build can't read (a newer schema) reads back **empty** rather than a distinct
 * "unavailable" state, because failing to an empty dismissed set is the *safe* direction here: the
 * card reappears, no warning is hidden (SPEC principle 2). The store adds no off-device channel of
 * its own — a private app file that rides Android backup / transfer like the rest of the config
 * (SPEC *Privacy*), not data this code sends anywhere.
 */
class DataStoreDismissedAlertsStore internal constructor(
    private val dataStore: DataStore<PersistedDismissedAlerts?>,
    private val clock: () -> Instant = Instant::now,
    private val warn: (String) -> Unit = {},
) : DismissedAlertsStore {

    // Absent/discarded (null) or an unreadable newer version → an empty set (fails safe). Present
    // and readable → the set, less the ended ones kept only for the widget's old copy.
    override fun dismissed(): Flow<Set<DismissedAlert>> = dismissals().map { it.active }

    override fun dismissals(): Flow<Dismissals> =
        dataStore.data.map { stored ->
            val ended = stored?.ended().orEmpty()
            // An end time after now means the clock went back since it was recorded: applied, it
            // would hide checks made after the end, a recurrence among them, so it's left out
            // until the next reconcile drops it. What it covered is future-dated, and not shown.
            val now = clock()
            Dismissals((stored?.toDomain() ?: emptySet()) - ended.keys, ended.filterValues { !it.isAfter(now) })
        }

    override suspend fun dismiss(alert: DismissedAlert) {
        dataStore.updateData { stored ->
            // A present-but-unreadable file reads as empty here too, so a dismiss on top of it
            // starts a fresh readable set rather than being lost — the safe direction (a stale
            // dismissal at worst reappears), consistent with [dismissed]'s empty fallback.
            // Dismissing it again (it recurred) makes it an ordinary dismissal: no longer ended.
            Dismissed.dismiss(stored?.toDomain() ?: emptySet(), alert).toPersisted(stored?.ended().orEmpty() - alert)
        }
    }

    override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {
        val now = clock()
        dataStore.updateData { stored ->
            val current = stored?.toDomain() ?: emptySet()
            val ended = stored?.ended().orEmpty()
            // A line dismissal a refresh saw end is kept a while for what the widget has stored
            // ([Dismissed.keepingEnded]), hiding only checks made before its end.
            val kept = Dismissed.keepingEnded(current, ended, Dismissed.reconcile(current, live, checkedPlaces), now)
            // Return the stored value unchanged when nothing changed, so DataStore skips the write
            // (and the re-emit) on the common no-op refresh.
            if (kept.dismissed == current && kept.ended == ended) stored else kept.dismissed.toPersisted(kept.ended)
        }
    }

    companion object {
        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "dismissed-alerts.json"

        @Volatile
        private var instance: DataStoreDismissedAlertsStore? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), so the [DataStore] is created once here and shared. Built from
         * the application context so it outlives any one Activity. [warn] is the sanitized log seam:
         * a corrupt file is logged and discarded by the corruption handler rather than swallowed.
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreDismissedAlertsStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreDismissedAlertsStore(
                    warn = warn,
                    dataStore = DataStoreFactory.create(
                        serializer = DismissedAlertsSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("dismissed alerts file was unreadable and has been discarded")
                            null
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                ).also { instance = it }
            }
    }
}

/**
 * Reads and writes [PersistedDismissedAlerts] as JSON (mirrors [StarredRowsSerializer]). An empty
 * file is "nothing saved yet" and reads back as null (→ an empty set); a **corrupt** one throws
 * [CorruptionException] so the store's corruption handler logs it and replaces the file.
 * `ignoreUnknownKeys` lets a set written by a newer build (extra fields) still parse; a `version`
 * mismatch is caught in [PersistedDismissedAlerts.toDomain] and read as empty without being corruption.
 */
internal object DismissedAlertsSerializer : Serializer<PersistedDismissedAlerts?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedDismissedAlerts? = null

    override suspend fun readFrom(input: InputStream): PersistedDismissedAlerts? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedDismissedAlerts.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            throw CorruptionException("dismissed alerts could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedDismissedAlerts?, output: OutputStream) {
        if (t == null) return
        output.write(
            json.encodeToString(PersistedDismissedAlerts.serializer(), t).encodeToByteArray(),
        )
    }
}
