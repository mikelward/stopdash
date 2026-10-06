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
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.FavoriteJourneysStore
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
 * The DataStore-backed [FavoriteJourneysStore], mirroring [DataStoreStarredRowsStore]: one JSON file
 * in the app's files dir, a process-wide instance, a corrupt file logged and discarded, and a file
 * from a newer schema preserved untouched (read as null). Like the starred rows it rides the user's
 * own Android backup (docs/PRIVACY.md); it is never logged.
 */
class DataStoreFavoriteJourneysStore internal constructor(
    private val dataStore: DataStore<PersistedFavoriteJourneys?>,
    private val warn: (String) -> Unit = {},
    // Where the stored file is turned into the app's values: work that grows with what's stored, never
    // on the collector's thread, which can be the main one (AGENTS.md *Main thread: read and dispatch only*).
    private val compute: CoroutineDispatcher = Workers.compute,
) : FavoriteJourneysStore {

    // A disk read failure (DataStore's IOException) reads as unavailable — starring journeys is
    // withheld and none are shown — rather than escaping into the screen's collector (Codex).
    override fun journeys(): Flow<List<FavoriteJourney>?> =
        dataStore.data
            .map { stored -> if (stored == null) emptyList() else stored.toDomain() }
            .flowOn(compute)
            .catch { e ->
                if (e !is IOException) throw e
                warn("favorite journeys read failed: ${e::class.simpleName}")
                emit(null)
            }

    override suspend fun toggle(journey: FavoriteJourney) = edit { Journeys.toggle(it, journey) }

    override suspend fun remove(journey: FavoriteJourney) = edit { Journeys.remove(it, journey) }

    private suspend fun edit(change: (List<FavoriteJourney>) -> List<FavoriteJourney>) {
        dataStore.updateData { stored ->
            if (stored != null && stored.toDomain() == null) {
                warn("favorite journeys file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                change(stored?.toDomain() ?: emptyList()).toPersisted()
            }
        }
    }

    companion object {
        // The name it was first saved under, when favorites were "starred": kept so none are lost.
        private const val FILE_NAME = "starred-journeys.json"

        @Volatile
        private var instance: DataStoreFavoriteJourneysStore? = null

        /** The process-wide store: DataStore allows one active instance per file per process. */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreFavoriteJourneysStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreFavoriteJourneysStore(
                    DataStoreFactory.create(
                        serializer = FavoriteJourneysSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("favorite journeys file was unreadable and has been discarded")
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
internal data class PersistedFavoriteJourneys(
    val version: Int = CURRENT_VERSION,
    val journeys: List<PersistedFavoriteJourney> = emptyList(),
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
internal data class PersistedFavoriteJourney(
    val from: PersistedJourneyEnd,
    val to: PersistedJourneyEnd,
    val lineId: String,
    val lineName: String = "",
    val mode: String = "",
)

private fun JourneyEnd.toPersisted() = PersistedJourneyEnd(stopId, name, latitude, longitude, areaId)
private fun PersistedJourneyEnd.toDomain() = JourneyEnd(stopId, name, latitude, longitude, areaId)

internal fun List<FavoriteJourney>.toPersisted(): PersistedFavoriteJourneys =
    PersistedFavoriteJourneys(journeys = map { PersistedFavoriteJourney(it.from.toPersisted(), it.to.toPersisted(), it.lineId, it.lineName, it.mode) })

internal fun PersistedFavoriteJourneys.toDomain(): List<FavoriteJourney>? {
    if (version != PersistedFavoriteJourneys.CURRENT_VERSION) return null
    // One journey per segment: a file from before journeys were line-free may hold the same two
    // stations starred on two lines; the first stands for both.
    return journeys.map { FavoriteJourney(it.from.toDomain(), it.to.toDomain(), it.lineId, riderLineName(it.lineName, it.mode), it.mode) }
        .distinctBy { it.key }
}

/** JSON (de)serialization; a corrupt file throws [CorruptionException] so the handler replaces it. */
internal object FavoriteJourneysSerializer : Serializer<PersistedFavoriteJourneys?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedFavoriteJourneys? = null

    override suspend fun readFrom(input: InputStream): PersistedFavoriteJourneys? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedFavoriteJourneys.serializer(), bytes.decodeToString())
        } catch (e: kotlinx.serialization.SerializationException) {
            throw CorruptionException("favorite journeys could not be decoded", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("favorite journeys could not be decoded", e)
        }
    }

    override suspend fun writeTo(t: PersistedFavoriteJourneys?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedFavoriteJourneys.serializer(), t).encodeToByteArray())
    }
}
