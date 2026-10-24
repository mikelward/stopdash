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
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.PendingEnd
import app.stopdash.domain.PendingJourney
import app.stopdash.domain.PendingJourneys
import app.stopdash.domain.Pick
import app.stopdash.domain.SavedJourneys
import app.stopdash.domain.Workers
import app.stopdash.domain.LineRef
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    // A pending end as it reads now ([HubsStore.resolvedNow]): an interchange's station name saved
    // before phase 2 read as its station, so a new pick of the row replaces it rather than adding one.
    private val resolveEnd: (PendingEnd.Station) -> PendingEnd.Station = { it },
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
            saved.toPersisted(alerts).copy(pending = stored?.pending.orEmpty(), syncMark = stored?.syncMark)
        } }
    }

    override suspend fun toggle(journey: FavoriteJourney) = edit { Journeys.toggle(it, journey) }

    override suspend fun remove(journey: FavoriteJourney) = edit { Journeys.remove(it, journey) }

    override suspend fun add(journey: FavoriteJourney) = edit { Journeys.add(it, journey) }

    override suspend fun setLineHidden(journey: FavoriteJourney, entry: String, hidden: Boolean) =
        edit { Journeys.withLineHidden(it, journey, entry, hidden) }

    override suspend fun addLines(found: Map<String, Collection<LineRef>>): Unit = withContext(compute) {
        // Only when some line is new: the list reports what it finds every refresh, and most find nothing new.
        val stored = journeys().first() ?: return@withContext
        if (stored.none { j -> found[j.key]?.let { Journeys.withLines(j, it) !== j } == true }) return@withContext
        edit { list -> list.map { j -> found[j.key]?.let { Journeys.withLines(j, it) } ?: j } }
    }

    override fun pendingJourneys(): Flow<List<PendingJourney>?> =
        dataStore.data
            .map { stored -> if (stored == null) emptyList() else stored.pendingToDomain(resolveEnd) }
            .flowOn(compute)
            .catch { e ->
                if (e !is IOException) throw e
                warn("pending journeys read failed: ${e::class.simpleName}")
                emit(null)
            }

    override fun savedJourneys(): Flow<SavedJourneys> =
        dataStore.data
            .map { stored -> if (stored == null) SavedJourneys(emptyList(), emptyList()) else SavedJourneys(stored.toDomain(), stored.pendingToDomain(resolveEnd)) }
            .flowOn(compute)
            .catch { e ->
                if (e !is IOException) throw e
                warn("favorite journeys read failed: ${e::class.simpleName}")
                emit(SavedJourneys(null, null))
            }

    override suspend fun addPending(journey: PendingJourney) = editPending { PendingJourneys.add(it, journey) }

    override suspend fun removePending(journey: PendingJourney) = editPending { PendingJourneys.remove(it, journey) }

    override suspend fun addReplacingPending(journey: FavoriteJourney, grayed: PendingJourney): Unit = withContext(compute) {
        writes.withLock { dataStore.updateData { stored ->
            if (stored != null && stored.toDomain() == null) {
                warn("favorite journeys file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                val next = Journeys.add(stored?.toDomain() ?: emptyList(), journey)
                val pending = PendingJourneys.remove(stored?.pendingToDomain(resolveEnd).orEmpty(), grayed)
                next.toPersisted(JourneyAlerts.prune(stored?.alertsToDomain().orEmpty(), next))
                    .copy(pending = pending.map { it.toPersisted() }, syncMark = stored?.syncMark)
            }
        } }
    }

    /**
     * The rides, their alert times and the grayed rides as sync entries, as stored now ([RideSync]); null
     * for a newer build's file, which isn't synced. Worked out on the worker. A disk read failure throws
     * its IOException to the sync, which says it couldn't sync.
     */
    suspend fun syncEntries(): Map<String, Map<String, String>>? = withContext(compute) {
        RideSync.entriesOf(dataStore.data.first())
    }

    /**
     * [syncEntries] as they change, each a change sync may have to send: null for a file this build can't
     * read. A read failure ends the flow, said with null first, as [journeys] does.
     */
    fun syncChanges(): Flow<Map<String, Map<String, String>>?> =
        dataStore.data
            .map { stored -> RideSync.entriesOf(stored) }
            .distinctUntilChanged()
            .flowOn(compute)
            .catch { e ->
                if (e !is IOException) throw e
                // By its type and first frames alone, never its message (Codex on #1151).
                warn("favorite journeys read failed: ${e::class.simpleName}${e.stackTrace.take(3).joinToString("") { " at $it" }}")
                emit(null)
            }

    /**
     * Writes [merged], a sync's merged entries by kind ([RideSync.applied]), with that sync's [mark], in one
     * update, only while the store still holds [expected], the entries that sync compared: false, with
     * nothing written, when a change landed meanwhile, which the next sync records and merges. Under
     * [writes], as every edit is.
     */
    suspend fun applySync(expected: Map<String, Map<String, String>>, merged: Map<String, Map<String, String>>, mark: String): Boolean =
        withContext(compute) {
            var applied = false
            writes.withLock {
                dataStore.updateData { stored ->
                    if (RideSync.entriesOf(stored) != expected) {
                        stored
                    } else {
                        applied = true
                        RideSync.applied(stored, merged).copy(syncMark = mark)
                    }
                }
            }
            applied
        }

    /** The mark of the last sync written here ([applySync]); null before any. A read failure throws its IOException. */
    suspend fun syncMark(): String? = withContext(compute) { dataStore.data.first()?.syncMark }

    // Only the grayed list changes; the journeys and their alerts are written back as stored.
    private suspend fun editPending(change: (List<PendingJourney>) -> List<PendingJourney>): Unit = withContext(compute) {
        writes.withLock { dataStore.updateData { stored ->
            if (stored != null && stored.toDomain() == null) {
                warn("favorite journeys file is a newer schema version; preserving it, not overwriting")
                stored
            } else {
                val base = stored ?: PersistedFavoriteJourneys()
                // Written as this build's version, as every other write is: an older file read here
                // is rewritten in this build's shape.
                base.copy(version = PersistedFavoriteJourneys.CURRENT_VERSION, pending = change(base.pendingToDomain(resolveEnd).orEmpty()).map { it.toPersisted() })
            }
        } }
    }

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
                    .copy(pending = stored?.pending.orEmpty(), syncMark = stored?.syncMark)
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
                    resolveEnd = HubsStore::resolvedNow,
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
    // The journeys saved grayed (PendingJourney). Added without a version bump, as alerts were: an
    // older build reads past it and its next write drops them.
    val pending: List<PersistedPendingJourney> = emptyList(),
    // The last Drive sync that wrote here (applySync), written in the same update, so a sync stopped before
    // keeping its result is settled exactly (SyncState.Pending). Every edit keeps it. Added without a version
    // bump: an older build reads past it, and its next write drops it, which only leaves a sync stopped
    // meanwhile settled as unwritten.
    val syncMark: String? = null,
) {
    companion object {
        /** The current on-disk format. Bump when a field an older build would drop or misread is added. */
        // 2: a pending end's level ([PersistedPendingEnd.level]).
        // 3: a ride's lines ([PersistedFavoriteJourney.lines]) in place of the one it was saved from.
        const val CURRENT_VERSION = 3
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
    // Before version 3, the line a ride was saved from: read into [lines] as one line serving it, no more.
    // Still written, as the ride's first line, for a build before 3: a synced ride carries no file version
    // (RideSync), so an older phone reads these and keeps a usable ride rather than one with no line (Codex on
    // #1205); in the file, its version check keeps it unread anyway.
    val lineId: String = "",
    val lineName: String = "",
    val mode: String = "",
    // Version 3: the lines found serving the ride's two stops, all alike (FavoriteJourney.lines).
    val lines: List<PersistedRideLine> = emptyList(),
    // The lines hidden from the ride, as HiddenModes.lineKey entries, in the order hidden. Added
    // without a version bump, as alerts were: an older build reads past it and its next write drops
    // them, which shows those lines again rather than misreading them.
    val hiddenLines: List<String> = emptyList(),
)

@Serializable
internal data class PersistedRideLine(val id: String, val name: String = "", val mode: String = "")

@Serializable
internal data class PersistedPendingEnd(
    // "station" or "place"; an end of a kind this build doesn't know drops its journey.
    val kind: String,
    // The station's id, or the favorite place's.
    val id: String,
    val name: String,
    // A station end's level by its code ([PendingEnd.Station.level]); absent where its row didn't say,
    // in a file written before levels were, or for a code this build doesn't know.
    val level: String? = null,
)

@Serializable
internal data class PersistedPendingJourney(
    val from: PersistedPendingEnd,
    val to: PersistedPendingEnd,
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

private fun PendingEnd.toPersisted() = when (this) {
    is PendingEnd.Station -> PersistedPendingEnd("station", stationId, name, level?.code)
    is PendingEnd.Place -> PersistedPendingEnd("place", placeId, name)
}

private fun PersistedPendingEnd.toDomain(): PendingEnd? = when (kind) {
    "station" -> PendingEnd.Station(id, name, Pick.Level.ofCode(level))
    "place" -> PendingEnd.Place(id, name)
    else -> null
}

private fun PendingJourney.toPersisted() = PersistedPendingJourney(from.toPersisted(), to.toPersisted())

/** The grayed journeys, or null for a newer schema version this build can't read. */
internal fun PersistedFavoriteJourneys.pendingToDomain(resolve: (PendingEnd.Station) -> PendingEnd.Station = { it }): List<PendingJourney>? {
    if (version > PersistedFavoriteJourneys.CURRENT_VERSION) return null
    fun PendingEnd.read(): PendingEnd = if (this is PendingEnd.Station) resolve(this) else this
    return pending.mapNotNull { p ->
        val from = p.from.toDomain()?.read() ?: return@mapNotNull null
        val to = p.to.toDomain()?.read() ?: return@mapNotNull null
        PendingJourney(from, to)
    }.distinctBy { it.key }
}

private fun JourneyEnd.toPersisted() = PersistedJourneyEnd(stopId, name, latitude, longitude, areaId)
private fun PersistedJourneyEnd.toDomain() = JourneyEnd(stopId, name, latitude, longitude, areaId)

internal fun List<FavoriteJourney>.toPersisted(alerts: Map<String, JourneyAlertSchedule> = emptyMap()): PersistedFavoriteJourneys =
    PersistedFavoriteJourneys(alerts = alerts.mapValues { it.value.toPersisted() }, journeys = map { it.toPersisted() })

/** [this] as stored and synced: its lines, and the first of them in the fields a build before version 3 reads. */
internal fun FavoriteJourney.toPersisted(): PersistedFavoriteJourney {
    val first = lines.firstOrNull()
    return PersistedFavoriteJourney(
        from.toPersisted(), to.toPersisted(),
        lineId = first?.id.orEmpty(), lineName = first?.name.orEmpty(), mode = first?.mode.orEmpty(),
        lines = lines.map { line -> PersistedRideLine(line.id, line.name, line.mode) },
        hiddenLines = hiddenLines.toList(),
    )
}

// Every version up to this build's is read, so a bump never hides what an older build saved; only a
// newer one is kept unread (dev-docs/station-model.md *A saved level comes with a version*).
internal fun PersistedFavoriteJourneys.toDomain(): List<FavoriteJourney>? {
    if (version > PersistedFavoriteJourneys.CURRENT_VERSION) return null
    // One journey per segment: a file from before journeys were line-free may hold the same two
    // stations starred on two lines; the first stands for both.
    // A hidden line is kept only as this build reads one: an entry of another kind, from a later build, is dropped.
    return journeys.map {
        FavoriteJourney(
            it.from.toDomain(), it.to.toDomain(),
            // An older ride's saved line becomes one of its lines, none above the others.
            (it.lines.map { line -> LineRef(line.id, line.name, line.mode) } +
                listOfNotNull(it.lineId.takeIf(String::isNotBlank)?.let { id -> LineRef(id, riderLineName(it.lineName, it.mode), it.mode) }))
                .filter { line -> line.id.isNotBlank() }.distinctBy { line -> line.id.lowercase() },
            it.hiddenLines.filterTo(LinkedHashSet(), HiddenModes::isLineKey),
        )
    }
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
