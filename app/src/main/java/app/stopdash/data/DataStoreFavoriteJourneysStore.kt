package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.JourneyAlertSchedule
import app.stopdash.domain.JourneyAlerts
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.TimeWindow
import app.stopdash.domain.Journeys
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.FavoriteJourneysStore
import app.stopdash.domain.Workers
import app.stopdash.domain.riderLineName
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    // Held by every write: the journey alert check holds it from its last read of the settings through its
    // posts, so no edit can land between them (Codex on #700).
    private val writes: Mutex = FavoriteJourneysWrites.lock,
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

    override fun alertSchedules(): Flow<Map<String, JourneyAlertSchedule>?> =
        dataStore.data
            // A newer schema is unreadable here, as journeys() says, not "no alerts" (Codex on #700).
            .map<PersistedFavoriteJourneys?, Map<String, JourneyAlertSchedule>?> { stored -> if (stored == null) emptyMap() else stored.alertsToDomain() }
            .flowOn(compute)
            .catch { e ->
                if (e !is IOException) throw e
                warn("journey alerts read failed: ${e::class.simpleName}")
                emit(null)
            }

    override suspend fun updateAlertSchedule(directionKey: String, change: (JourneyAlertSchedule?) -> JourneyAlertSchedule?): Unit = withContext(compute) {
        writes.withLock { dataStore.updateData { stored ->
            val journeys = stored?.toDomain()
            if (stored != null && journeys == null) {
                warn("favorite journeys file is a newer schema version; preserving it, not overwriting")
                return@updateData stored
            }
            val saved = journeys.orEmpty()
            if (saved.none { it.key == JourneyAlerts.journeyKey(directionKey) }) return@updateData stored
            val alerts = stored?.alertsToDomain().orEmpty().toMutableMap()
            val schedule = change(alerts[directionKey])
            if (schedule == null) alerts.remove(directionKey) else alerts[directionKey] = schedule
            saved.toPersisted(alerts)
        } }
    }

    override suspend fun toggle(journey: FavoriteJourney) = edit { Journeys.toggle(it, journey) }

    override suspend fun remove(journey: FavoriteJourney) = edit { Journeys.remove(it, journey) }

    override suspend fun add(journey: FavoriteJourney) = edit { Journeys.add(it, journey) }

    // On the worker first: DataStore runs the transform in the caller's context, and the edit maps and
    // searches the whole list, so a tap from the main thread must not do it there (AGENTS.md *Main
    // thread*; Codex on #631).
    private suspend fun edit(change: (List<FavoriteJourney>) -> List<FavoriteJourney>): Unit = withContext(compute) {
        writes.withLock { dataStore.updateData { stored ->
            if (stored != null && stored.toDomain() == null) {
                warn("favorite journeys file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                // A journey's alerts go with it, so one saved again later starts with them off.
                val next = change(stored?.toDomain() ?: emptyList())
                next.toPersisted(JourneyAlerts.prune(stored?.alertsToDomain().orEmpty(), next))
            }
        } }
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

/** Serializes writes to the favorite journeys and their alert schedules, process-wide (see the store). */
object FavoriteJourneysWrites {
    val lock = Mutex()
}

@Serializable
internal data class PersistedFavoriteJourneys(
    val version: Int = CURRENT_VERSION,
    val journeys: List<PersistedFavoriteJourney> = emptyList(),
    // Each watched direction's alert schedule, by JourneyAlerts.directionKey. Added without a version
    // bump: an older build reads past it (ignoreUnknownKeys) and its next write drops it, which turns
    // those alerts off rather than misreading them.
    val alerts: Map<String, PersistedAlertSchedule> = emptyMap(),
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

@Serializable
internal data class PersistedAlertSchedule(
    // DayOfWeek names.
    val days: List<String> = emptyList(),
    val windows: List<PersistedTimeWindow> = emptyList(),
)

@Serializable
internal data class PersistedTimeWindow(
    // "HH:mm".
    val start: String,
    val end: String,
)

private fun JourneyAlertSchedule.toPersisted() = PersistedAlertSchedule(
    days = days.sorted().map { it.name },
    windows = windows.map { PersistedTimeWindow(it.start.toString(), it.end.toString()) },
)

// A day or time this build can't read, or a window that never opens, drops that one entry, not the
// schedule. One left with no day or no window could never fire, so it reads as off rather than as a
// switch showing on for alerts that never come (Codex on #700); the screen never saves one like that.
private fun PersistedAlertSchedule.toDomain(): JourneyAlertSchedule? {
    val days = days.mapNotNullTo(HashSet()) { name -> DayOfWeek.values().firstOrNull { it.name == name } }
    val windows = windows.mapNotNull { w ->
        val start = parseTime(w.start) ?: return@mapNotNull null
        val end = parseTime(w.end) ?: return@mapNotNull null
        TimeWindow(start, end).takeIf { it.valid }
    }
        // As the app saves them: no repeats, in order, at most [JourneyAlertSchedule.MAX_WINDOWS], so a
        // file from elsewhere (a restore, another build) can't hand the screen an unbounded list.
        .distinct().sortedBy { it.start }.take(JourneyAlertSchedule.MAX_WINDOWS)
    if (days.isEmpty() || windows.isEmpty()) return null
    return JourneyAlertSchedule(days, windows)
}

private fun parseTime(text: String): LocalTime? = try {
    LocalTime.parse(text)
} catch (e: DateTimeParseException) {
    null
}

/** The schedules, or null for a newer schema version this build can't read. */
internal fun PersistedFavoriteJourneys.alertsToDomain(): Map<String, JourneyAlertSchedule>? {
    val journeys = toDomain() ?: return null
    // Only those for a saved journey's own directions, as the app saves them (Codex on #700).
    return JourneyAlerts.prune(alerts.mapNotNull { (key, schedule) -> schedule.toDomain()?.let { key to it } }.toMap(), journeys)
}

private fun JourneyEnd.toPersisted() = PersistedJourneyEnd(stopId, name, latitude, longitude, areaId)
private fun PersistedJourneyEnd.toDomain() = JourneyEnd(stopId, name, latitude, longitude, areaId)

internal fun List<FavoriteJourney>.toPersisted(alerts: Map<String, JourneyAlertSchedule> = emptyMap()): PersistedFavoriteJourneys =
    PersistedFavoriteJourneys(alerts = alerts.mapValues { it.value.toPersisted() }, journeys = map { PersistedFavoriteJourney(it.from.toPersisted(), it.to.toPersisted(), it.lineId, it.lineName, it.mode) })

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
