package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.Journeys
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.StarredJourneysStore
import app.stopdash.domain.Workers
import app.stopdash.domain.riderLineName
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [StarredJourneysStore], mirroring [DataStoreStarredRowsStore]: one JSON file
 * in the app's files dir, a process-wide instance, a corrupt file logged and discarded, and a file
 * from a newer schema preserved untouched (read as null). Like the starred rows it rides the user's
 * own Android backup (docs/PRIVACY.md); it is never logged.
 */
class DataStoreStarredJourneysStore internal constructor(
    private val dataStore: DataStore<PersistedStarredJourneys?>,
    private val warn: (String) -> Unit = {},
    // Where the stored file is turned into the app's values: work that grows with what's stored, never
    // on the collector's thread, which can be the main one (AGENTS.md *Main thread: read and dispatch only*).
    private val compute: CoroutineDispatcher = Workers.compute,
) : StarredJourneysStore {

    // A disk read failure (DataStore's IOException) reads as unavailable — starring journeys is
    // withheld and none are shown — rather than escaping into the screen's collector (Codex).
    override fun journeys(): Flow<List<StarredJourney>?> =
        dataStore.data
            .map { stored -> if (stored == null) emptyList() else stored.toDomain() }
            .flowOn(compute)
            .catch { e ->
                if (e !is IOException) throw e
                warn("starred journeys read failed: ${e::class.simpleName}")
                emit(null)
            }

    override suspend fun toggle(journey: StarredJourney) {
        dataStore.updateData { stored ->
            if (stored != null && stored.toDomain() == null) {
                warn("starred journeys file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                Journeys.toggle(stored?.toDomain() ?: emptyList(), journey).toPersisted()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "starred-journeys.json"

        @Volatile
        private var instance: DataStoreStarredJourneysStore? = null

        /** The process-wide store: DataStore allows one active instance per file per process. */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreStarredJourneysStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreStarredJourneysStore(
                    DataStoreFactory.create(
                        serializer = StarredJourneysSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("starred journeys file was unreadable and has been discarded")
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

@Serializable
internal data class PersistedStarredJourneys(
    val version: Int = CURRENT_VERSION,
    val journeys: List<PersistedStarredJourney> = emptyList(),
) {
    companion object {
        /** The current on-disk format. Bump when a field's meaning changes incompatibly. */
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class PersistedJourneyEnd(
    val stopId: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val areaId: String = "",
)

@Serializable
internal data class PersistedStarredJourney(
    val from: PersistedJourneyEnd,
    val to: PersistedJourneyEnd,
    val lineId: String,
    val lineName: String = "",
    val mode: String = "",
)

private fun JourneyEnd.toPersisted() = PersistedJourneyEnd(stopId, name, latitude, longitude, areaId)
private fun PersistedJourneyEnd.toDomain() = JourneyEnd(stopId, name, latitude, longitude, areaId)

internal fun List<StarredJourney>.toPersisted(): PersistedStarredJourneys =
    PersistedStarredJourneys(journeys = map { PersistedStarredJourney(it.from.toPersisted(), it.to.toPersisted(), it.lineId, it.lineName, it.mode) })

internal fun PersistedStarredJourneys.toDomain(): List<StarredJourney>? {
    if (version != PersistedStarredJourneys.CURRENT_VERSION) return null
    // One journey per segment: a file from before journeys were line-free may hold the same two
    // stations starred on two lines; the first stands for both.
    return journeys.map { StarredJourney(it.from.toDomain(), it.to.toDomain(), it.lineId, riderLineName(it.lineName, it.mode), it.mode) }
        .distinctBy { it.key }
}

/** JSON (de)serialization; a corrupt file throws [CorruptionException] so the handler replaces it. */
internal object StarredJourneysSerializer : Serializer<PersistedStarredJourneys?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedStarredJourneys? = null

    override suspend fun readFrom(input: InputStream): PersistedStarredJourneys? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedStarredJourneys.serializer(), bytes.decodeToString())
        } catch (e: kotlinx.serialization.SerializationException) {
            throw CorruptionException("starred journeys could not be decoded", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("starred journeys could not be decoded", e)
        }
    }

    override suspend fun writeTo(t: PersistedStarredJourneys?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedStarredJourneys.serializer(), t).encodeToByteArray())
    }
}
